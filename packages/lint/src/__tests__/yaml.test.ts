// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi } from 'vitest';
import { mkdtempSync, cpSync, writeFileSync, readFileSync, rmSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import { ProfileEngine } from '@tatrman/profile';
import { YamlWorkspaceLoader } from '../yaml-node.js';
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
      loader.invalidate();
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
      loader.invalidate();
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
  it('resolves a marker-selected profile identity from the model root catalogue', () =>
    workspace((root) => {
      const model = join(root, 'model');
      mkdirSync(join(model, 'profiles'));
      cpSync(join(model, 'ttr-profile.yaml'), join(model, 'profiles/editor.yaml'));
      const marker = join(model, 'ttr.yaml');
      writeFileSync(marker, 'profile: editor-demo/1\ngrammar: ">=0.22 <1.0"\n');
      const loader = new YamlWorkspaceLoader([root]);
      const result = loader.load().get(uri(root, 'model/entities.yaml'));
      expect(result).toMatchObject({
        ast: {
          definitions: expect.arrayContaining([expect.objectContaining({ name: 'Customer' })]),
        },
        errors: [],
      });

      const markerUri = uri(root, 'model/ttr.yaml');
      const missing = loader.load(
        new Map([[markerUri, 'profile: missing/1\ngrammar: ">=0.22 <1.0"\n']])
      );
      expect(missing.get(markerUri)?.errors).toEqual([
        expect.objectContaining({ code: 'ys/profile-not-found' }),
      ]);
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
  it('leaves malformed unmarked YAML alone and locates malformed model UTF-8 without crashing', () =>
    workspace((root) => {
      writeFileSync(join(root, 'notes.yaml'), Buffer.from([0xff]));
      const loader = new YamlWorkspaceLoader([root]);
      expect(loader.load().has(uri(root, 'notes.yaml'))).toBe(false);
      writeFileSync(join(root, 'model/entities.yaml'), Buffer.from([0xff]));
      expect(loader.load().get(uri(root, 'model/entities.yaml'))!.errors).toEqual([
        expect.objectContaining({ code: 'ys/profile-invalid', message: 'Malformed UTF-8' }),
      ]);
    }));
  it('a nearest marker without a profile leaves its subtree as plain YAML', () =>
    workspace((root) => {
      mkdirSync(join(root, 'model/plain'));
      writeFileSync(join(root, 'model/plain/ttr.yaml'), 'other: settings');
      writeFileSync(join(root, 'model/plain/notes.yaml'), 'plain: data');
      expect(new YamlWorkspaceLoader([root]).load().has(uri(root, 'model/plain/notes.yaml'))).toBe(
        false
      );
    }));

  it('re-applies only the changed group and its profile dependencies', () =>
    workspace((root) => {
      cpSync(join(root, 'model'), join(root, 'second'), { recursive: true });
      const loader = new YamlWorkspaceLoader([root]);
      const apply = vi.spyOn(ProfileEngine, 'apply');
      try {
        loader.load();
        apply.mockClear();
        const file = uri(root, 'model/entities.yaml');
        const buffers = new Map([
          [
            file,
            readFileSync(join(root, 'model/entities.yaml'), 'utf8').replace(
              'A customer',
              'Changed'
            ),
          ],
        ]);
        loader.load(buffers);
        expect(apply).toHaveBeenCalledTimes(1);
        apply.mockClear();
        loader.load(buffers);
        expect(apply).not.toHaveBeenCalled();
        buffers.set(
          uri(root, 'model/ttr-profile.yaml'),
          readFileSync(join(root, 'model/ttr-profile.yaml'), 'utf8').replace(
            'editor-demo/1',
            'editor-demo/2'
          )
        );
        loader.load(buffers);
        expect(apply).toHaveBeenCalledTimes(1);
      } finally {
        apply.mockRestore();
      }
    }));
  it('excludes dot paths, agents, controls, model.ignore and profile file ignores', () =>
    workspace((root) => {
      for (const file of [
        '.github/workflows/ci.yml',
        'agents/a.yaml',
        'renames.yaml',
        'profiles/unused.yaml',
        'layouts/layout.yaml',
        'scratch/x.yaml',
        'docker-compose.yml',
      ]) {
        mkdirSync(join(root, 'model', file, '..'), { recursive: true });
        writeFileSync(join(root, 'model', file), 'notInProfile: data');
      }
      writeFileSync(join(root, 'model/model.ignore'), 'scratch/\ndocker-compose.yml\n');
      writeFileSync(join(root, 'model/build.yaml'), 'entity: {Build: {popis: content}}');
      const results = new YamlWorkspaceLoader([root]).load();
      expect([...results.keys()].map((f) => f.split('/').pop()).sort()).toEqual([
        'build.yaml',
        'entities.yaml',
        'relations.yaml',
      ]);
      expect([...results.values()].flatMap((r) => r.errors)).toEqual([]);
    }));
  it('resolves CLI profile paths from cwd, identities from the tree, and confines outside paths', () =>
    workspace((root) => {
      const cli = resolve(__dirname, '../../dist/cli.js');
      const parent = resolve(root, '..');
      const relativeRoot = root.slice(parent.length + 1);
      const run = (profile: string) => {
        try {
          return {
            status: 0,
            stdout: execFileSync(
              'node',
              [
                cli,
                relativeRoot + '/model',
                '--profile',
                profile,
                '--rule',
                'ys/profile-not-found',
                '--format',
                'json',
              ],
              { cwd: parent, encoding: 'utf8', stdio: 'pipe' }
            ),
          };
        } catch (e) {
          return e as { status: number; stdout: string };
        }
      };
      expect(run(relativeRoot + '/model/ttr-profile.yaml').status).toBe(0);
      mkdirSync(join(root, 'model/profiles'));
      cpSync(join(root, 'model/ttr-profile.yaml'), join(root, 'model/profiles/p.yaml'));
      expect(run('editor-demo/1').status).toBe(0);
      const missing = run('missing.yaml');
      expect(missing.status).not.toBe(0);
      expect(missing.stdout).not.toContain('/model/ttr.yaml');
      const outside = run(relativeRoot + '/notes.yaml');
      expect(outside.status).toBe(1);
      expect(outside.stdout).toContain('outside root');
    }));
  it('uses configured input severity instead of the producer severity', () =>
    workspace((root) => {
      const source = {
        file: uri(root, 'model/entities.yaml'),
        line: 1,
        column: 0,
        endLine: 1,
        endColumn: 1,
        offsetStart: 0,
        offsetEnd: 1,
      };
      const diagnostics = [
        {
          code: 'ys/key-missing' as never,
          severity: 'warning' as const,
          message: 'required warning',
          source,
        },
      ];
      expect(
        lintInputDiagnostics(diagnostics, {
          ...recommendedConfig({}),
          severityOf: () => 'error',
        })[0].severity
      ).toBe('error');
      expect(lintInputDiagnostics(diagnostics, recommendedConfig({}))[0].severity).toBe('warning');
    }));

  it.each([
    ['ignore', 'other', 'warning', 0],
    ['error', 'ys/unknown-key', 'error', 1],
  ] as const)(
    'codes unknown: %s consistently in the loader and CLI',
    (policy, code, severity, status) =>
      workspace((root) => {
        const profile = join(root, 'model/ttr-profile.yaml');
        writeFileSync(profile, readFileSync(profile, 'utf8') + `\nunknown: ${policy}\n`);
        const file = join(root, 'model/entities.yaml');
        writeFileSync(file, readFileSync(file, 'utf8') + '    zzstray: 1\n');
        const result = new YamlWorkspaceLoader([root])
          .load()
          .get(uri(root, 'model/entities.yaml'))!;
        expect(lintInputDiagnostics(result.errors, recommendedConfig({}))).toEqual([
          expect.objectContaining({
            code,
            severity,
            message: policy === 'ignore' ? 'not in this profile' : 'Unconsumed key zzstray',
          }),
        ]);
        let actualStatus = 0,
          stdout = '';
        try {
          stdout = execFileSync(
            'node',
            [
              resolve(__dirname, '../../dist/cli.js'),
              join(root, 'model'),
              '--profile',
              '--rule',
              code,
              '--format',
              'json',
            ],
            { encoding: 'utf8', stdio: 'pipe' }
          );
        } catch (error) {
          const e = error as { status: number; stdout: string };
          actualStatus = e.status;
          stdout = e.stdout;
        }
        expect(actualStatus).toBe(status);
        const diagnostics = JSON.parse(stdout);
        expect(diagnostics).toContainEqual(expect.objectContaining({ code, severity }));
        if (policy === 'ignore')
          expect(diagnostics.some((d: { severity: string }) => d.severity === 'error')).toBe(false);
      })
  );

  it('keeps each warnings separate from required: warn, using Kotlin lint code other', () => {
    const root = resolve(__dirname, 'fixtures/yaml-values');
    const result = new YamlWorkspaceLoader([root]).load();
    const warnings = [...result.values()].flatMap((r) => r.errors);
    expect(warnings.map((w) => [w.code, w.message])).toEqual([
      ['other', 'each key repeated (en)'],
      ['other', 'each key missing → en'],
    ]);
    expect(lintInputDiagnostics(warnings, recommendedConfig({})).map((d) => d.code)).toEqual([
      'other',
      'other',
    ]);
  });

  it('agrees with server language selection on eight header and marker shapes', () =>
    workspace((root) => {
      const canonical = '\nmodel: er\ndefs: [{kind: entity, id: Header, properties: {}}]\n';
      const cases: [string, string, boolean][] = [
        ['canonical.yaml', 'ttr: 0.22' + canonical, true],
        ['quoted.yaml', '"ttr": "0.22"' + canonical, true],
        ['flow.yaml', '{ttr: "0.22", model: er, defs: []}', true],
        [
          'inline.yaml',
          'ttr: {profile: ./model/ttr-profile.yaml, grammar: ">=0.22 <1.0"}\nentity: {Header: {popis: value}}',
          true,
        ],
        [
          'block.yaml',
          'ttr:\n  profile: ./model/ttr-profile.yaml\n  grammar: ">=0.22 <1.0"\nentity: {Header: {popis: value}}',
          true,
        ],
        ['model/marked.yaml', 'entity: {Marked: {popis: value}}', true],
        ['plain.yaml', 'plain: YAML', false],
        ['model/renames.yaml', 'plain: control', false],
      ];
      for (const [file, text] of cases) writeFileSync(join(root, file), text);
      const loader = new YamlWorkspaceLoader([root]),
        results = loader.load();
      for (const [file, text, admitted] of cases) {
        expect(loader.language(uri(root, file), text), file).toBe(admitted ? 'ttr-yaml' : 'yaml');
        expect(results.has(uri(root, file)), file).toBe(admitted);
      }
    }));
  it('CLI required: warn stays a warning by default and .ttrlint.toml can raise it to error', () =>
    workspace((root) => {
      const tree = join(root, 'required');
      cpSync(
        resolve(
          __dirname,
          '../../../../tests/conformance/dialect/demo-cs-links/operands/required-warn/tree'
        ),
        tree,
        { recursive: true }
      );
      mkdirSync(join(tree, 'profiles'));
      cpSync(
        resolve(
          __dirname,
          '../../../../tests/conformance/dialect/demo-cs-links/operands/required-warn/profile.yaml'
        ),
        join(tree, 'profiles/p.yaml')
      );
      writeFileSync(join(tree, 'ttr.yaml'), 'profile: ./profiles/p.yaml\ngrammar: ">=0.22 <1.0"\n');
      writeFileSync(join(tree, 'model.ignore'), 'profiles/\n');
      const args = [
        resolve(__dirname, '../../dist/cli.js'),
        tree,
        '--profile',
        '--rule',
        'ys/key-missing',
        '--format',
        'json',
      ];
      const plain = JSON.parse(execFileSync('node', args, { encoding: 'utf8', stdio: 'pipe' }));
      expect(plain.length).toBeGreaterThan(0);
      expect(plain.every((d: { severity: string }) => d.severity === 'warning')).toBe(true);
      writeFileSync(join(tree, '.ttrlint.toml'), '[rules]\n"ys/key-missing" = "error"\n');
      try {
        execFileSync('node', args, { encoding: 'utf8', stdio: 'pipe' });
        throw new Error('expected CLI failure');
      } catch (error) {
        const e = error as { status: number; stdout: string };
        expect(e.status).toBe(1);
        expect(
          JSON.parse(e.stdout).every((d: { severity: string }) => d.severity === 'error')
        ).toBe(true);
      }
    }));

  it('replays the exclusion probe on the 11-file shared Kotlin tree', () =>
    workspace((root) => {
      const model = join(root, 'shared');
      cpSync(resolve(__dirname, '../../../../tests/conformance/dialect/demo-cs/tree'), model, {
        recursive: true,
      });
      cpSync(join(__dirname, 'fixtures/yaml-exclusions'), model, { recursive: true });
      const loader = new YamlWorkspaceLoader([model]);
      const results = loader.load();
      expect([...results.keys()].map((f) => f.split('/').pop()).sort()).toEqual([
        '01-sequence.yaml',
        '02-mapping.yaml',
        '03-mixed.yaml',
        '04-nest.yaml',
        '05-unnest.yaml',
        '06-table.yaml',
        '07-fanout.yaml',
        '08-values.yaml',
        '09-patterns.yaml',
        '10-vazby.yaml',
        '11-pojmy.yaml',
      ]);
      expect([...results.values()].flatMap((r) => r.errors)).toEqual([]);
      expect(loader.notify([{ uri: uri(root, 'shared/.github/workflows/ci.yml'), type: 2 }])).toBe(
        false
      );
      expect(loader.notify([{ uri: uri(root, 'shared/scratch/new.yaml'), type: 1 }])).toBe(true);
    }));
  it('invalidates a cached group when a sidecar input changes', () =>
    workspace((root) => {
      const sidecar = join(root, 'model/demo.sources.json');
      writeFileSync(sidecar, '{"entries": []}');
      const loader = new YamlWorkspaceLoader([root]);
      const apply = vi.spyOn(ProfileEngine, 'apply');
      try {
        loader.load();
        apply.mockClear();
        writeFileSync(sidecar, '{"entries": [], "revision": 2}');
        expect(loader.notify([{ uri: uri(root, 'model/demo.sources.json'), type: 2 }])).toBe(true);
        loader.load();
        expect(apply).toHaveBeenCalledTimes(1);
      } finally {
        apply.mockRestore();
      }
    }));
});
