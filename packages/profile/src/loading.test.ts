// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it } from 'vitest';
import { mkdtempSync, mkdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ForeignTree, JsonPointer } from './input.js';
import {
  DeclarationReader,
  FileSelector,
  GrammarRange,
  ProfileException,
  ProfileReader,
  ProfileResolver,
} from './loading.js';
import { runningGrammar } from './generated/resources.js';

const definition = (name = 'demo/1', extra = '', grammar = '>=0.18 <1.0') =>
  `profile: ${name}\ngrammar: '${grammar}'\noperations: []\n${extra}`;
const roots: string[] = [];
const root = () => {
  const dir = mkdtempSync(join(tmpdir(), 'profile-loading-'));
  roots.push(dir);
  return dir;
};
afterEach(() => {
  for (const dir of roots.splice(0)) rmSync(dir, { recursive: true, force: true });
});
function failure(run: () => unknown, path?: string, code = 'ys/profile-invalid'): ProfileException {
  try {
    run();
  } catch (error) {
    expect(error).toBeInstanceOf(ProfileException);
    const e = error as ProfileException;
    expect(e.code).toBe(code);
    if (path !== undefined) expect(e.location.path).toBe(path);
    expect(e.location.line).toBeGreaterThan(0);
    expect(e.location.column).toBeGreaterThan(0);
    return e;
  }
  throw new Error('Expected a located profile failure');
}

describe('profile loading contract', () => {
  it('requires a mapping root and retains pointers, key order and code-point source coordinates', () => {
    for (const source of ['[1, null, true]', '42', 'null', 'hello'])
      expect(failure(() => ForeignTree.read(source), '').location).toMatchObject({
        line: 1,
        column: 1,
      });
    const tree = ForeignTree.read(
      'records:\n  odd/key:\n    a~b: [value, null]\n',
      'relative.yaml',
      '/model/relative.yaml'
    );
    const record = new JsonPointer('/records/odd~1key').resolve(tree.root)!;
    const value = new JsonPointer('/a~0b/0').resolve(record)!;
    expect(value.value).toBe('value');
    expect(value.span()).toMatchObject({
      file: '/model/relative.yaml',
      line: 3,
      column: 11,
      path: '/records/odd~1key/a~0b/0',
    });
    expect(new JsonPointer('/$key').resolve(record)?.span()).toMatchObject({ line: 2, column: 3 });
    expect(new JsonPointer('/$key').resolve(record)?.value).toBe('odd/key');
    for (const pointer of ['/a~0b/1', '/a~0b/01', '/a~0b/-', '/a~0b/9007199254740993', '/missing'])
      expect(new JsonPointer(pointer).resolve(record)).toBeNull();
    expect(new JsonPointer('/a~0b/1').resolve(record, true)?.value).toBeNull();
    for (const bad of ['bad', '/bad~2', '/bad~']) expect(() => new JsonPointer(bad)).toThrow();
    const numeric = ForeignTree.read('first: a\n"123": b\n"2": c\nlast: d\n');
    expect(numeric.root.entries().map((n) => n.mappingKey)).toEqual(['first', '123', '2', 'last']);
    const astral = ForeignTree.read('{emoji: 😀, after: value}', 'unicode.yaml');
    const after = new JsonPointer('/after').resolve(astral.root)!;
    expect(after.span().column).toBe(19);
    expect(astral.data.source('/after').offsetStart).toBe(18);
    expect(astral.data.coordinates.span([19, 24]).column).toBe(18);
    expect(astral.data.node('/after')).toHaveProperty('range');
    expect(astral.data.text).toBe(astral.sourceText);
  });

  it('enforces safe YAML with located errors, depth and input budgets', () => {
    const unsafe: [string, string][] = [
      ['x: &a 1', '/x'],
      ['x: *missing', '/x'],
      ['x: {<<: {y: 1}}', '/x/<<'],
      ['x: !!python/object foo', '/x'],
      ['x: !pl hello', '/x'],
      ['x: .inf', '/x'],
      ['x: .nan', '/x'],
      ['x: {a: 1, a: 2}', '/x/a'],
      ['x: {1: value}', '/x/1'],
      ['x: {? [a, b]: value}', '/x'],
      ['&a x: 1', '/x'],
      ['!pl x: 1', '/x'],
      ['x: !!timestamp 2001-01-01', '/x'],
    ];
    for (const [source, path] of unsafe)
      expect(failure(() => ForeignTree.read(source, 'unsafe.yaml'), path).location.file).toBe(
        'unsafe.yaml'
      );
    for (const source of [
      '%YAML 1.1\n---\nx: 1',
      'x: 1\n---\nx: 2',
      'x: \u0000',
      `x: ${'['.repeat(65)}0${']'.repeat(65)}`,
      `#${'x'.repeat(6_000_001)}`,
    ])
      failure(() => ForeignTree.read(source));
    expect(ForeignTree.read('x: yes\ny: 2026-01-01\n').root.value).toEqual({
      x: 'yes',
      y: '2026-01-01',
    });
  });

  it('validates generated schema branches, semantic operands and preserved operation locations', () => {
    const source = `profile: demo/12345678901234567890\ngrammar: '>=0.18 <1.0'\nwritable: true\noverlay: {Customer: {description: zákazník}}\noperations:\n  - op: ignore\n    at: /poznámka\n    reason: fixture\n  - op: codes\n    mapping: {E_DEMO: ys/unknown-key}\n`;
    const profile = ProfileReader.read(source, 'profile.yaml');
    expect(profile.major).toBe(12345678901234567890n);
    expect(profile.name).toBe('demo');
    expect(profile.writable).toBe(true);
    expect(profile.operations.map((op) => op.value.op)).toEqual(['ignore', 'codes']);
    expect(profile.operations[0].location).toMatchObject({ line: 6, column: 5 });
    expect(profile.operations[0].locations.get('/operations/0/at')).toMatchObject({
      line: 7,
      column: 9,
    });
    expect(profile.keyLocations.get('/overlay/Customer')).toMatchObject({ line: 4, column: 11 });
    expect(ProfileReader.read(definition()).writable).toBe(false);
    expect(ProfileReader.read(definition()).unknown).toBeUndefined();
    for (const policy of ['error', 'ignore'])
      expect(ProfileReader.read(definition('demo/1', `unknown: ${policy}\n`)).unknown).toBe(policy);
    for (const policy of ['skip', "'true'", '[ignore]', 'null'])
      failure(() => ProfileReader.read(definition('demo/1', `unknown: ${policy}\n`)), '/unknown');
    expect(ProfileReader.read(definition('demo/1', 'overlay: ./overlay.yaml\n')).overlay).toBe(
      './overlay.yaml'
    );
    failure(() => ProfileReader.read("profile: demo/1\ngrammar: '>=0.18'"));
    failure(() => DeclarationReader.readTree(definition()), '/operations');
    failure(() => ProfileReader.read(definition('demo/1', 'surprise: 1')), '/surprise');
    const op = (text: string) => definition().replace('operations: []', `operations: [${text}]`);
    failure(() =>
      ProfileReader.read(op('{op: rename, in: entity, at: /x, to: {kind: entity, property: typo}}'))
    );
    failure(
      () =>
        ProfileReader.read(
          op('{op: rename, in: entity, at: /x, to: {kind: table, property: description}}')
        ),
      '/operations/0/to/kind'
    );
    expect(
      ProfileReader.read(
        op(
          '{op: link, in: relation, at: /x, to: {kind: relation, property: from}, kind: table, by: sourceName}'
        )
      ).operations
    ).toHaveLength(1);
    failure(
      () =>
        ProfileReader.read(
          op(
            '{op: link, in: er2db_entity, at: /x, to: {kind: er2db_entity, property: entity}, kind: table, by: sourceName}'
          )
        ),
      '/operations/0/kind'
    );
    for (const pattern of ['!x', '[x', 'x\\'])
      failure(
        () =>
          ProfileReader.read(
            op(`{op: ignore, files: [${JSON.stringify(pattern)}], reason: fixture}`)
          ),
        '/operations/0/files/0'
      );
    expect(new FileSelector(['layouts/']).matches('x/layouts/a.yaml')).toBe(true);
    expect(new FileSelector(['a/**/b.yaml']).matches('a/b.yaml')).toBe(true);
    expect(new FileSelector(['a**b.yaml']).matches('a/b.yaml')).toBe(false);
  });

  it('parses comparator sets and selects declarations with canonical header precedence', () => {
    expect(GrammarRange.runningGrammar).toBe(runningGrammar);
    expect(GrammarRange.parse(`=${runningGrammar}`).admits()).toBe(true);
    for (const [text, admitted] of [
      ['>=0.18 <1.0', true],
      ['<0.22', false],
      ['0.22', true],
      ['>0.22', false],
      ['>=0.23 || >=0.18 <0.23', true],
      ['0.22 0.23', false],
    ] as const)
      expect(GrammarRange.parse(text).admits('0.22')).toBe(admitted);
    for (const text of [
      '^0.22',
      '~0.22',
      '*',
      '0.22-rc1',
      '0.22 - 1.0',
      '',
      '0.22 ||',
      '0.22.0.1',
      '0',
      '>=0.22 junk',
      '0.22>=0.18',
    ])
      failure(() => ProfileReader.read(definition('demo/1', '', text)), '/grammar');
    expect(GrammarRange.parse('>=9007199254740993.0').admits('9007199254740992.99')).toBe(false);
    const tree = DeclarationReader.readTree("profile: demo/1\ngrammar: '>=0.18 <1.0'\n");
    expect(DeclarationReader.select('records: []', 'one.yaml', tree)).toBe(tree);
    expect(DeclarationReader.select("ttr: '0.22'\nmodel: er", 'one.yaml', tree)).toBeNull();
    expect(
      DeclarationReader.select(
        "ttr: {profile: canonical/1, grammar: '>=0.18'}\nrecords: []",
        'one.yaml',
        tree
      )?.profile
    ).toBe('canonical/1');
    failure(
      () => DeclarationReader.header("records: []\nttr: {profile: demo/1, grammar: '>=0.18'}"),
      '/ttr'
    );
    failure(() => DeclarationReader.header('ttr: []'), '/ttr');
    expect(DeclarationReader.header('ttr: null')).toEqual({ type: 'absent' });
    failure(() => DeclarationReader.header('[1, 2]'), '');
    expect(DeclarationReader.header("ttr: '0.22'\nbody: !pl hello").type).toBe('canonical');
  });

  it('resolves local chains with real-path confinement, identity, cycle, depth and shipped rules', () => {
    const dir = root();
    mkdirSync(join(dir, 'sub'));
    writeFileSync(join(dir, 'base.yaml'), definition('base/1', 'overlay: ./overlay.yaml\n'));
    writeFileSync(join(dir, 'sub/child.yaml'), definition('demo/1', 'extends: ../base.yaml\n'));
    const resolver = new ProfileResolver(dir, new Map([['demo/1', 'sub/child.yaml']]));
    expect(resolver.resolve('demo/1', 'ttr.yaml').map((p) => p.name)).toEqual(['base', 'demo']);
    expect(resolver.resolve('./child.yaml', 'sub/input.yaml')[0].overlay).toBe('./overlay.yaml');
    expect(resolver.resolve(null)[0].name).toBe('canonical');
    expect(ProfileResolver.bundled('canonical/0001')?.[0].name).toBe('canonical');
    for (const ref of ['missing/1', './absent.yaml', './sub', '../escape.yaml'])
      failure(() => resolver.resolve(ref, 'ttr.yaml'), '/profile', 'ys/profile-not-found');
    const outside = root();
    writeFileSync(join(outside, 'outside.yaml'), definition('outside/1'));
    symlinkSync(join(outside, 'outside.yaml'), join(dir, 'escape.yaml'));
    failure(
      () => resolver.resolve('./escape.yaml', 'ttr.yaml'),
      '/profile',
      'ys/profile-not-found'
    );
    failure(
      () => new ProfileResolver(dir, { 'wrong/1': 'base.yaml' }).resolve('wrong/1', 'ttr.yaml'),
      '/profile'
    );
    writeFileSync(join(dir, 'base.yaml'), definition('base/1', 'extends: ./sub/child.yaml\n'));
    failure(() => resolver.resolve('demo/1', 'ttr.yaml'), '/extends', 'ys/profile-cycle');
    for (let i = 1; i <= 33; i++)
      writeFileSync(
        join(dir, `p${i}.yaml`),
        definition(`p${i}/1`, i < 33 ? `extends: ./p${i + 1}.yaml\n` : '')
      );
    failure(() => resolver.resolve('./p1.yaml', 'ttr.yaml'), '/extends');
    writeFileSync(join(dir, 'p32.yaml'), definition('p32/1'));
    expect(resolver.resolve('./p1.yaml', 'ttr.yaml')).toHaveLength(32);
    writeFileSync(join(dir, 'p32.yaml'), definition('p32/1', '', '<0.18'));
    failure(
      () => resolver.resolve('./p1.yaml', 'ttr.yaml'),
      '/grammar',
      'ys/profile-grammar-mismatch'
    );
    const badDeclaration = DeclarationReader.readTree("profile: canonical/1\ngrammar: '<0.18'");
    failure(() => resolver.resolve(badDeclaration), '/grammar', 'ys/profile-grammar-mismatch');
    failure(
      () => ProfileResolver.bundled(badDeclaration),
      '/grammar',
      'ys/profile-grammar-mismatch'
    );
    const shipped = new ProfileResolver(dir, {}, (id) =>
      id === 'child/1'
        ? definition('child/1', 'extends: parent/1\n')
        : id === 'parent/1'
          ? definition('parent/1')
          : null
    );
    expect(shipped.resolve('child/1', 'ttr.yaml').map((p) => p.name)).toEqual(['parent', 'child']);
    expect(shipped.resolve('child/1', 'ttr.yaml')[0].file).toBe('<shipped>/profiles/parent/1.yaml');
    failure(
      () =>
        new ProfileResolver(dir, {}, () => definition('wrong/1')).resolve('child/1', 'ttr.yaml'),
      '/profile'
    );
    failure(
      () =>
        new ProfileResolver(dir, {}, () => definition('child/1', 'extends: ./base.yaml\n')).resolve(
          'child/1',
          'ttr.yaml'
        ),
      '/extends',
      'ys/profile-not-found'
    );
    const configured = new ProfileResolver(dir, { 'base/1': 'sub/child.yaml' }, () =>
      definition('base/1')
    );
    failure(() => configured.resolve('base/1', 'ttr.yaml'), '/profile');
  });
});
