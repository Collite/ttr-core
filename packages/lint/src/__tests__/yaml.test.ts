// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { mkdtempSync, cpSync, writeFileSync, readFileSync, rmSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import { YamlWorkspaceLoader } from '../yaml.js';
import { lintInputDiagnostics } from '../runner.js';
import { recommendedConfig } from '../config.js';
import { RULES } from '../registry.js';

function workspace(run: (root: string) => void) {
  const root = mkdtempSync(join(tmpdir(), 's73-lint-'));
  cpSync(new URL('../../../lsp/src/__tests__/fixtures/yaml-workspace/', import.meta.url), root, {
    recursive: true,
  });
  try {
    run(root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}
const uri = (root: string, file: string) => pathToFileURL(join(root, file)).href;

// Allow cold schema compilation on shared CI runners, as the profile/LSP suites do.
describe('YAML lint input', { timeout: 30_000 }, () => {
  it('replays schema/profile errors through registered ys rules without requiring an AST', () =>
    workspace((root) => {
      writeFileSync(join(root, 'model/entities.yaml'), 'entity:\n  Customer:\n    typo: wrong\n');
      const result = new YamlWorkspaceLoader([root]).load().get(uri(root, 'model/entities.yaml'))!;
      expect(result.ast).toBeUndefined();
      expect(lintInputDiagnostics(result.errors, recommendedConfig({}))).toEqual([
        expect.objectContaining({
          ruleId: 'ys/unknown-key',
          code: 'ys/unknown-key',
          severity: 'error',
          source: expect.objectContaining({ line: 3, column: 4 }),
        }),
      ]);
      expect(RULES.get('ys/schema')!.scope).toBe('input');
    }));
  it('detects nearest roots and per-file headers without capturing plain YAML', () =>
    workspace((root) => {
      const loader = new YamlWorkspaceLoader([root]);
      const initial = loader.load();
      expect(initial.has(uri(root, 'notes.yaml'))).toBe(false);
      mkdirSync(join(root, 'separate'));
      writeFileSync(
        join(root, 'separate/header.yml'),
        'ttr: {profile: ../model/ttr-profile.yaml, grammar: ">=0.22 <1.0"}\nentity:\n  Header: {popis: in header}\n'
      );
      expect(loader.load().get(uri(root, 'separate/header.yml'))!.ast!.definitions[0].name).toBe(
        'Header'
      );
      // A new nested marker overrides the cached ancestor; canonical profile allows this canonical document.
      mkdirSync(join(root, 'model/nested'));
      writeFileSync(
        join(root, 'model/nested/ttr.yaml'),
        'profile: canonical/1\ngrammar: ">=0.22 <1.0"\n'
      );
      writeFileSync(
        join(root, 'model/nested/e.yml'),
        'ttr: "0.22"\nmodel: er\ndefs: [{kind: entity, id: Nested, properties: {}}]\n'
      );
      expect(loader.load().get(uri(root, 'model/nested/e.yml'))!.ast!.definitions[0].name).toBe(
        'Nested'
      );
    }));
  it('refreshes unsaved profile buffers, then reverts to saved bytes', () =>
    workspace((root) => {
      const loader = new YamlWorkspaceLoader([root]);
      const profile = readFileSync(join(root, 'model/ttr-profile.yaml'), 'utf8');
      const changed = loader.load(
        new Map([
          [
            uri(root, 'model/ttr-profile.yaml'),
            profile.replace('property: description', 'property: attributes'),
          ],
        ])
      );
      expect(changed.get(uri(root, 'model/entities.yaml'))!.errors[0].code).toBe(
        'ys/profile-invalid'
      );
      expect(loader.load().get(uri(root, 'model/entities.yaml'))!.ast!.definitions[0].name).toBe(
        'Customer'
      );
    }));
  it('CLI --profile reports ys/* on a negative tree and --fix preserves dialect bytes', () =>
    workspace((root) => {
      const file = join(root, 'model/entities.yaml'),
        text = 'entity:\n  Customer:\n    typo: wrong\n';
      writeFileSync(file, text);
      let stdout = '';
      try {
        execFileSync(
          'node',
          [resolve(__dirname, '../../dist/cli.js'), root, '--profile', '--fix', '--format', 'json'],
          { encoding: 'utf8', stdio: 'pipe' }
        );
      } catch (error) {
        const e = error as { status: number; stdout: string };
        expect(e.status).toBe(1);
        stdout = e.stdout;
      }
      expect(JSON.parse(stdout)).toContainEqual(
        expect.objectContaining({ code: 'ys/unknown-key', uri: file })
      );
      expect(readFileSync(file, 'utf8')).toBe(text);
    }));
  it('leaves malformed unmarked YAML alone and locates malformed model UTF-8 without crashing', () => workspace(root => {
    writeFileSync(join(root, 'notes.yaml'), Buffer.from([0xff]));
    const loader = new YamlWorkspaceLoader([root]);
    expect(loader.load().has(uri(root, 'notes.yaml'))).toBe(false);
    writeFileSync(join(root, 'model/entities.yaml'), Buffer.from([0xff]));
    expect(loader.load().get(uri(root, 'model/entities.yaml'))!.errors).toEqual([
      expect.objectContaining({ code: 'ys/profile-invalid', message: 'Malformed UTF-8' }),
    ]);
  }));
  it('a nearest marker without a profile leaves its subtree as plain YAML', () => workspace(root => {
    mkdirSync(join(root, 'model/plain'));
    writeFileSync(join(root, 'model/plain/ttr.yaml'), 'other: settings');
    writeFileSync(join(root, 'model/plain/notes.yaml'), 'plain: data');
    expect(new YamlWorkspaceLoader([root]).load().has(uri(root, 'model/plain/notes.yaml'))).toBe(false);
  }));

});
