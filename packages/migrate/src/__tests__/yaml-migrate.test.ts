// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  chmodSync,
  mkdtempSync,
  linkSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { runYamlMigration } from '../yaml-migrate.js';

const race = vi.hoisted(() => ({
  afterRename: undefined as (() => void) | undefined,
  failRename: undefined as string | undefined,
  beforeLstat: undefined as ((path: unknown) => void) | undefined,
  failRemove: undefined as { path: string; error: Error } | undefined,
}));
vi.mock('node:fs/promises', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:fs/promises')>();
  return {
    ...actual,
    lstat: async (...args: Parameters<typeof actual.lstat>) => {
      race.beforeLstat?.(args[0]);
      return actual.lstat(...args);
    },
    rm: async (...args: Parameters<typeof actual.rm>) => {
      if (race.failRemove && String(args[0]).startsWith(`${race.failRemove.path}.migrate-`))
        throw race.failRemove.error;
      return actual.rm(...args);
    },
    rename: async (...args: Parameters<typeof actual.rename>) => {
      if (args[1] === race.failRename) throw new Error('simulated rename failure');
      await actual.rename(...args);
      race.afterRename?.();
    },
  };
});

// File policy tests use a protocol peer; the real Kotlin bridge is tested in canonical-yaml CI.
describe('canonical YAML migration file policy', () => {
  let root: string;
  let bridge: string;
  let file: string;
  const before = "ttr: '0.21'\nmodel: er\n";
  const after = 'ttr: "0.21"\nmodel: "er"\n';
  beforeEach(() => {
    root = mkdtempSync(join(tmpdir(), 'yaml migrate '));
    bridge = join(root, 'bridge with spaces');
    file = join(root, 'model.yaml');
    writeFileSync(file, before);
    writeFileSync(
      bridge,
      `#!/usr/bin/env node
const fs = require('node:fs');
const r = JSON.parse(fs.readFileSync(0, 'utf8'));
if (r.operation !== 'migrate' || r.from !== '0.21' || r.to !== '0.21' || !r.source.startsWith('ttr:')) {
  process.stderr.write('<yaml>:1:1: refused marker or version'); process.exit(2);
}
const source = ${JSON.stringify(after)};
process.stdout.write(JSON.stringify({ source, changed: source !== r.source }));
`
    );
    chmodSync(bridge, 0o755);
  });
  afterEach(() => {
    race.afterRename = undefined;
    race.failRename = undefined;
    race.beforeLstat = undefined;
    race.failRemove = undefined;
    chmodSync(root, 0o755);
    rmSync(root, { recursive: true, force: true });
  });
  const options = () => ({ from: '0.21', to: '0.21', bridge });

  it('dry-run validates via bridge and returns a diff without writes or metadata changes', async () => {
    const stat = statSync(file);
    const result = await runYamlMigration([file], { ...options(), dryRun: true });
    expect(result.writes).toHaveLength(1);
    expect(result.writes[0].diff).toContain('+ttr: "0.21"');
    expect(readFileSync(file, 'utf8')).toBe(before);
    expect(statSync(file).mtimeMs).toBe(stat.mtimeMs);
    expect(readdirSync(root).sort()).toEqual(['bridge with spaces', 'model.yaml']);
  });
  it('writes only changed files, preserves permissions and is idempotent', async () => {
    const mask = process.umask(0o022);
    try {
      chmodSync(file, 0o664);
      const result = await runYamlMigration([file], options());
      expect(result.writes).toHaveLength(1);
      expect(result.writes[0].diff).toBe('');
      expect(readFileSync(file, 'utf8')).toBe(after);
      expect(statSync(file).mode & 0o777).toBe(0o664);
      const stat = statSync(file);
      expect((await runYamlMigration([file], options())).writes).toEqual([]);
      expect(statSync(file).mtimeMs).toBe(stat.mtimeMs);
    } finally {
      process.umask(mask);
    }
  });
  it('refuses the entire batch before writes when any marker or version fails', async () => {
    const other = join(root, 'foreign.yaml');
    writeFileSync(other, 'entities: []\n');
    await expect(runYamlMigration([file, other], options())).rejects.toThrow('<yaml>:1:1: refused');
    expect(readFileSync(file, 'utf8')).toBe(before);
    expect(readFileSync(other, 'utf8')).toBe('entities: []\n');
    await expect(runYamlMigration([file], { ...options(), to: '0.22' })).rejects.toThrow('version');
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
  for (const stdout of [
    'broken',
    '{}',
    '[]',
    'null',
    '{"source":1,"changed":true}',
    '{"source":"x","changed":false}',
    '{"source":"x","changed":true,"extra":1}',
  ]) {
    it(`refuses invalid bridge output ${stdout} before writes`, async () => {
      writeFileSync(
        bridge,
        `#!/usr/bin/env node\nprocess.stdout.write(${JSON.stringify(stdout)});\n`
      );
      await expect(runYamlMigration([file], options())).rejects.toThrow();
      expect(readFileSync(file, 'utf8')).toBe(before);
    });
  }
  it('preserves a concurrent edit and removes staged temporary files', async () => {
    writeFileSync(
      bridge,
      `#!/usr/bin/env node
const fs = require('node:fs');
fs.writeFileSync(${JSON.stringify(file)}, 'external edit');
process.stdout.write(JSON.stringify({ source: ${JSON.stringify(after)}, changed: true }));
`
    );
    await expect(runYamlMigration([file], options())).rejects.toThrow('input changed');
    expect(readFileSync(file, 'utf8')).toBe('external edit');
    expect(readdirSync(root).sort()).toEqual(['bridge with spaces', 'model.yaml']);
  });
  it('requires explicit input files and a bridge', async () => {
    await expect(runYamlMigration([], options())).rejects.toThrow('At least one');
    await expect(runYamlMigration([file], { ...options(), bridge: '' })).rejects.toThrow(
      'bridge executable'
    );
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
  it('refuses missing executable, duplicate paths, symlinks and invalid UTF-8 without writes', async () => {
    await expect(
      runYamlMigration([file], { ...options(), bridge: join(root, 'missing') })
    ).rejects.toThrow();
    await expect(runYamlMigration([file, file], options())).rejects.toThrow('Duplicate');
    const link = join(root, 'link.yaml');
    symlinkSync(file, link);
    await expect(runYamlMigration([link], options())).rejects.toThrow('regular file');
    const bad = join(root, 'bad.yaml');
    writeFileSync(bad, Buffer.from([0xc3, 0x28]));
    await expect(runYamlMigration([file, bad], options())).rejects.toThrow(bad);
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
  it('rejects alias identities before any bridge call', async () => {
    const alias = join(root, 'alias');
    symlinkSync(root, alias);
    writeFileSync(
      bridge,
      `#!/usr/bin/env node\nrequire('node:fs').writeFileSync(${JSON.stringify(file)}, 'bridge called');`
    );
    await expect(runYamlMigration([file, join(alias, 'model.yaml')], options())).rejects.toThrow(
      `Duplicate migration file: ${file} and ${join(alias, 'model.yaml')}`
    );
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
  it('refuses hard links through either name', async () => {
    const hard = join(root, 'hard.yaml');
    linkSync(file, hard);
    for (const path of [file, hard])
      await expect(runYamlMigration([path], options())).rejects.toThrow('one link');
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
  it('names a file corrupted to invalid UTF-8 during planning', async () => {
    writeFileSync(
      bridge,
      `#!/usr/bin/env node
require('node:fs').writeFileSync(${JSON.stringify(file)}, Buffer.from([0xc3, 0x28]));
process.stdout.write(JSON.stringify({source: ${JSON.stringify(after)}, changed: true}));`
    );
    await expect(runYamlMigration([file], options())).rejects.toThrow(`${file}: not valid UTF-8`);
    expect(readdirSync(root).some((name) => name.includes('.migrate-'))).toBe(false);
  });
  it('keeps bridge stderr and abnormal termination cause', async () => {
    writeFileSync(
      bridge,
      `#!/usr/bin/env node
require('node:fs').writeSync(2, 'bridge diagnostic\\n');
process.kill(process.pid, 'SIGKILL');`
    );
    await expect(runYamlMigration([file], options())).rejects.toThrow(/bridge diagnostic.*SIGKILL/);
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
  it('reports a timeout without attributing its own SIGTERM to the bridge', async () => {
    writeFileSync(
      bridge,
      `#!/usr/bin/env node
require('node:fs').writeSync(2, 'waiting');
setInterval(() => {}, 1000);`
    );
    const error = await runYamlMigration([file], { ...options(), timeoutMs: 500 }).catch(
      (error: Error) => error
    );
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).toMatch(/timed out after 0.5 s/);
    expect((error as Error).message).not.toContain('killed by SIGTERM');
  });
  it('reports stderr and a non-protocol exit status', async () => {
    writeFileSync(
      bridge,
      `#!/usr/bin/env node\nrequire('node:fs').writeSync(2, 'failed\\n'); process.exit(1);`
    );
    await expect(runYamlMigration([file], options())).rejects.toThrow(/failed.*bridge exited 1/);
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
  it('reports ENOBUFS without attributing its own SIGTERM to the bridge', async () => {
    writeFileSync(
      bridge,
      `#!/usr/bin/env node\nrequire('node:fs').writeSync(1, 'x'.repeat(8192)); setInterval(() => {}, 1000);`
    );
    const error = await runYamlMigration([file], { ...options(), maxBuffer: 1024 }).catch(
      (error: Error) => error
    );
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).toContain('bridge output exceeded maxBuffer (ENOBUFS)');
    expect((error as Error).message).not.toContain('killed by SIGTERM');
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
  it('checks every planned file before replacing the first file', async () => {
    const second = join(root, 'second.yaml');
    writeFileSync(second, before);
    writeFileSync(
      bridge,
      `#!/usr/bin/env node
const fs = require('node:fs');
const r = JSON.parse(fs.readFileSync(0, 'utf8'));
const counter = ${JSON.stringify(join(root, 'calls'))};
if (fs.existsSync(counter)) fs.writeFileSync(${JSON.stringify(second)}, 'editor save');
else fs.writeFileSync(counter, '1');
process.stdout.write(JSON.stringify({source: ${JSON.stringify(after)}, changed: true}));`
    );
    await expect(runYamlMigration([file, second], options())).rejects.toThrow(
      `${second}: input changed while planning migration`
    );
    expect(readFileSync(file, 'utf8')).toBe(before);
    expect(readFileSync(second, 'utf8')).toBe('editor save');
    expect(readdirSync(root).some((name) => name.includes('.migrate-'))).toBe(false);
  });
  it('stops remaining replacements when an edit lands between two renames', async () => {
    const second = join(root, 'second.yaml');
    writeFileSync(second, before);
    race.afterRename = () => {
      writeFileSync(second, 'editor save');
      race.afterRename = undefined;
    };
    await expect(runYamlMigration([file, second], options())).rejects.toThrow(
      `${second}: input changed before replacement; already replaced: ${file}`
    );
    expect(readFileSync(file, 'utf8')).toBe(after);
    expect(readFileSync(second, 'utf8')).toBe('editor save');
    expect(readdirSync(root).some((name) => name.includes('.migrate-'))).toBe(false);
  });
  for (const damage of ['delete', 'invalid UTF-8']) {
    it(`reports already replaced files when the next input is ${damage}`, async () => {
      const second = join(root, 'second.yaml');
      writeFileSync(second, before);
      race.afterRename = () => {
        if (damage === 'delete') rmSync(second);
        else writeFileSync(second, Buffer.from([0xff, 0xfe]));
        race.afterRename = undefined;
      };
      const error = await runYamlMigration([file, second], options()).catch(
        (error: Error) => error
      );
      expect(error).toBeInstanceOf(Error);
      const message = (error as Error).message;
      expect(message.startsWith(`${second}: input changed before replacement (`)).toBe(true);
      expect(message).toContain(damage === 'delete' ? 'ENOENT' : 'not valid UTF-8');
      expect(message.endsWith(`); already replaced: ${file}`)).toBe(true);
      expect((error as Error).cause).toBeInstanceOf(Error);
      if (damage === 'delete') expect((error as Error).cause).toMatchObject({ code: 'ENOENT' });
      expect(readFileSync(file, 'utf8')).toBe(after);
      if (damage === 'invalid UTF-8')
        expect(readFileSync(second)).toEqual(Buffer.from([0xff, 0xfe]));
      expect(readdirSync(root).some((name) => name.includes('.migrate-'))).toBe(false);
    });
  }
  it('reports only successful replacements after a subsequent rename fails', async () => {
    const second = join(root, 'second.yaml');
    writeFileSync(second, before);
    race.failRename = second;
    await expect(runYamlMigration([file, second], options())).rejects.toMatchObject({
      message: `${second}: replacement failed (simulated rename failure); already replaced: ${file}`,
      cause: { message: 'simulated rename failure' },
    });
    expect(readFileSync(file, 'utf8')).toBe(after);
    expect(readFileSync(second, 'utf8')).toBe(before);
    expect(readdirSync(root).some((name) => name.includes('.migrate-'))).toBe(false);
  });
  it('reports none after a first-replacement rename failure and removes its staged files', async () => {
    race.failRename = file;
    await expect(runYamlMigration([file], options())).rejects.toMatchObject({
      message: `${file}: replacement failed (simulated rename failure); already replaced: none`,
      cause: { message: 'simulated rename failure' },
    });
    expect(readFileSync(file, 'utf8')).toBe(before);
    expect(readdirSync(root).some((name) => name.includes('.migrate-'))).toBe(false);
  });
  it('reports none and retains ENOENT when file 1 disappears at its replacement recheck', async () => {
    let calls = 0;
    race.beforeLstat = (path) => {
      // Discovery, pre-check, then the replace loop's check.
      if (path === file && ++calls === 3) rmSync(file);
    };
    const error = await runYamlMigration([file], options()).catch((error: Error) => error);
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message.startsWith(`${file}: input changed before replacement (ENOENT`)).toBe(true);
    expect((error as Error).message.endsWith('); already replaced: none')).toBe(true);
    expect((error as Error).cause).toMatchObject({ code: 'ENOENT' });
    expect(readdirSync(root).some((name) => name.includes('.migrate-'))).toBe(false);
  });
  it.skipIf(process.getuid?.() === 0)('preserves the replacement report when chmod also prevents cleanup', async () => {
    const second = join(root, 'second.yaml');
    writeFileSync(second, before);
    race.afterRename = () => chmodSync(root, 0o555);
    const error = await runYamlMigration([file, second], options()).catch((error: Error) => error);
    expect(error).toBeInstanceOf(Error);
    const message = (error as Error).message;
    expect(message.startsWith(`${second}: replacement failed (EACCES`)).toBe(true);
    expect(message).toContain(`already replaced: ${file}; could not remove: ${second}.migrate-`);
    expect(message).not.toContain(`could not remove: ${file}.migrate-`);
    expect((error as Error).cause).toMatchObject({ cause: { code: 'EACCES' } });
    expect(readFileSync(file, 'utf8')).toBe(after);
    expect(readFileSync(second, 'utf8')).toBe(before);
    expect(readdirSync(root).filter((name) => name.includes('.migrate-'))).toHaveLength(1);
  });
  it('reports only failed cleanup paths after successful replacements, retaining the cleanup cause', async () => {
    const second = join(root, 'second.yaml');
    writeFileSync(second, before);
    const cause = Object.assign(new Error('simulated cleanup failure'), { code: 'EACCES' });
    race.failRemove = { path: second, error: cause };
    const error = await runYamlMigration([file, second], options()).catch((error: Error) => error);
    expect(error).toBeInstanceOf(Error);
    const message = (error as Error).message;
    expect(message.startsWith(`could not remove temporary files: ${second}.migrate-`)).toBe(true);
    expect(message.endsWith(`; already replaced: ${file}, ${second}`)).toBe(true);
    expect(message).not.toContain(`${file}.migrate-`);
    expect((error as Error).cause).toBeInstanceOf(AggregateError);
    expect(((error as Error).cause as AggregateError).errors).toEqual([cause]);
    expect(readFileSync(file, 'utf8')).toBe(after);
    expect(readFileSync(second, 'utf8')).toBe(after);
  });

});
