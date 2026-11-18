// SPDX-License-Identifier: Apache-2.0
import { describe, expect, test } from 'vitest';
import { ForeignTree, type Json } from './input.js';
import { ProfileReader, ProfileException, type ProfileDefinition } from './loading.js';
import { ProfileEngine, type ApplyResult } from './engine.js';

type Obj = { [key: string]: Json };
// Until producer #422 merges, construct the resolved policy separately from the older generated schema.
const profile = (ops: Json[], unknown?: string): ProfileDefinition =>
  Object.assign(
    ProfileReader.read(
      JSON.stringify({ profile: 'test/1', grammar: '>=0.22 <1.0', operations: ops }),
      'profile.yaml'
    ),
    unknown ? { unknown: unknown as 'error' | 'ignore' } : {}
  );
const packageOp = { op: 'package', mapping: {}, default: 'demo' };
const entities = { op: 'kinds', at: '/entities', mapping: { e: 'entity' } };
const write = (op: string, at: string, property: string, kind = 'entity'): Json => ({
  op,
  in: kind,
  at,
  to: { kind, property },
});
const run = (source: string, ops: Json[] = [], unknown?: string): ApplyResult =>
  ProfileEngine.apply(
    [ForeignTree.read(source, 'one.yaml')],
    [profile([packageOp, entities, ...ops], unknown)]
  );
const defs = (result: ApplyResult) => result.documents.flatMap((d) => d.value.defs as Obj[]);
const props = (result: ApplyResult) => defs(result)[0].properties as Obj;
function errorOf(action: () => unknown): ProfileException {
  try {
    action();
  } catch (e) {
    if (e instanceof ProfileException) return e;
    throw e;
  }
  throw new Error('Expected ProfileException');
}
function nodes(value: unknown): { [key: string]: unknown }[] {
  if (!value || typeof value !== 'object') return [];
  if (Array.isArray(value)) return value.flatMap(nodes);
  const node = value as { [key: string]: unknown };
  return [node, ...Object.values(node).flatMap(nodes)];
}

describe('record discovery and structural emission', () => {
  test('whole-file IDs and mapping-entry IDs remain authored, in source order', () => {
    const result = run('entities:\n  Zebra: {}\n  Alpha: {}\n');
    expect(defs(result).map((d) => d.id)).toEqual(['Zebra', 'Alpha']);
    const whole = ProfileEngine.apply(
      [ForeignTree.read('id: Whole\n', 'whole.yaml')],
      [profile([packageOp, { op: 'kinds', at: '', id: '/id', mapping: { e: 'entity' } }])]
    );
    expect(defs(whole)[0].id).toBe('Whole');
    expect(whole.origins[0]).toMatchObject({ file: 'whole.yaml', path: '', kind: 'entity' });
  });
  test('fan-out is legal across kinds but duplicate canonical identities are conflicts', () => {
    const result = run('entities: {A: {}}', [
      { op: 'kinds', at: '/entities', mapping: { e: 'er2db_entity' } },
    ]);
    expect(result.documents.map((d) => d.value.model)).toEqual(['er', 'binding']);
    expect(errorOf(() => run('entities: {A: {}}', [entities])).code).toBe('ys/profile-conflict');
    expect(
      errorOf(() =>
        ProfileEngine.apply(
          [
            ForeignTree.read('entities: {A: {}}', 'one.yaml'),
            ForeignTree.read('entities: {A: {}}', 'two.yaml'),
          ],
          [profile([packageOp, entities])]
        )
      ).code
    ).toBe('ys/profile-conflict');
  });
  test('full file stem and explicit package selectors group documents deterministically', () => {
    const result = ProfileEngine.apply(
      [
        ForeignTree.read('entities: {First: {}}', 'pt02_one.yaml'),
        ForeignTree.read('entities: {Second: {}}', 'two.yaml'),
      ],
      [profile([{ op: 'package', mapping: { pt02_one: 'shop', two: 'shop' } }, entities])]
    );
    expect(result.documents).toHaveLength(1);
    expect(result.documents[0].value.package).toBe('shop');
    expect(defs(result).map((d) => d.id)).toEqual(['First', 'Second']);
    const explicit = ProfileEngine.apply(
      [ForeignTree.read('pkg: Sales\nentities: {A: {}}')],
      [profile([{ op: 'package', at: '/pkg', mapping: { Sales: 'shop' } }, entities])]
    );
    expect(explicit.documents[0].value.package).toBe('shop');
    expect(
      errorOf(() =>
        ProfileEngine.apply(
          [ForeignTree.read('entities: {A: {}}', 'pt02_new.yaml')],
          [profile([{ op: 'package', mapping: {} }, entities])]
        )
      )
    ).toMatchObject({ code: 'ys/value-unmapped', location: { line: 1, column: 1 } });
  });
  for (const family of ['rename', 'nest', 'unnest'])
    test(`${family} reads original values and carries the authored key into the typed AST`, () => {
      const source = 'entities:\n  A:\n    label: Výrobek\n';
      const result = run(source, [write(family, '/label', 'description')]);
      expect(props(result).description).toBe('Výrobek');
      const location = result.documents[0].locations.get('/defs/0/properties/description');
      expect(location).toMatchObject({
        file: 'one.yaml',
        line: 3,
        column: 5,
        path: '/entities/A/label',
      });
      const description = nodes(result.documents[0].ast).find(
        (n) => n.kind === 'string' && n.value === 'Výrobek'
      );
      expect(description?.source).toMatchObject({
        file: 'one.yaml',
        line: 3,
        column: 4,
        offsetStart: source.indexOf('label'),
      });
    });
  test('nested paths and repeated source reads do not consume unrelated siblings', () => {
    const result = run('entities: {A: {label: Buyer, envelope: {plural: Buyers}}}', [
      write('rename', '/label', 'description'),
      {
        op: 'nest',
        in: 'entity',
        at: '/label',
        to: { kind: 'entity', path: ['semantics', 'role'] },
      },
      write('unnest', '/envelope/plural', 'labelPlural'),
    ]);
    expect(props(result)).toMatchObject({
      description: 'Buyer',
      semantics: { role: 'Buyer' },
      labelPlural: 'Buyers',
    });
    const error = errorOf(() =>
      run('entities: {A: {envelope: {plural: Buyers, stray: no}}}', [
        write('unnest', '/envelope/plural', 'labelPlural'),
      ])
    );
    expect(error).toMatchObject({
      code: 'ys/unknown-key',
      location: { path: '/entities/A/envelope/stray' },
    });
  });
  test('nested records reserve their collection and parent writes can target child properties', () => {
    const nested = { op: 'kinds', in: 'entity', at: '/columns', mapping: { c: 'attribute' } };
    const ops = [
      nested,
      write('rename', '/type', 'type', 'attribute'),
      {
        op: 'nest',
        in: 'entity',
        at: '/description',
        to: { kind: 'entity', path: ['attributes', 'description'] },
      },
    ];
    const result = run(
      'entities: {A: {description: Shared, columns: {a: {type: text}, b: {type: text}}}}',
      ops
    );
    const attributes = props(result).attributes as Obj[];
    expect(attributes.map((a) => a.id)).toEqual(['a', 'b']);
    expect(attributes.map((a) => (a.properties as Obj).description)).toEqual(['Shared', 'Shared']);
    const conflict = errorOf(() =>
      run('entities: {A: {columns: {a: {}}, x: []}}', [nested, write('rename', '/x', 'attributes')])
    );
    expect(conflict.code).toBe('ys/profile-conflict');
  });
  test('prefix collisions conflict, null writes stay absent, unknown keys retain their spans', () => {
    expect(
      errorOf(() =>
        run('entities: {A: {x: a, y: b}}', [
          {
            op: 'nest',
            in: 'entity',
            at: '/x',
            to: { kind: 'entity', path: ['semantics', 'role'] },
          },
          write('rename', '/y', 'semantics'),
        ])
      ).code
    ).toBe('ys/profile-conflict');
    expect(props(run('entities: {A: {x: null}}', [write('rename', '/x', 'description')]))).toEqual(
      {}
    );
    expect(errorOf(() => run('entities:\n  A:\n    surprise: 4\n'))).toMatchObject({
      code: 'ys/unknown-key',
      location: { line: 3, column: 5, path: '/entities/A/surprise' },
    });
  });
  test('ignore applies to keys and whole files, unions reasons, and refuses to hide records', () => {
    const result = ProfileEngine.apply(
      [
        ForeignTree.read('bad: x', 'layouts/view.yaml'),
        ForeignTree.read('entities: {A: {note: x}}', 'one.yaml'),
      ],
      [
        profile([
          packageOp,
          entities,
          { op: 'ignore', files: ['layouts/'], reason: 'layout' },
          { op: 'ignore', in: 'entity', at: '/note', reason: 'note' },
        ]),
      ]
    );
    expect(result.ignored.map((i) => i.reason)).toEqual(['layout', 'note']);
    expect(errorOf(() => run('entities: {A: {}}', [{ op: 'ignore', at: '/entities' }])).code).toBe(
      'ys/profile-conflict'
    );
  });
  test('codes merge child-wins; per-file grouping retains cross-run identity checks', () => {
    const result = ProfileEngine.apply(
      [
        ForeignTree.read('entities: {A: {}}', 'a.yaml'),
        ForeignTree.read('entities: {B: {}}', 'b.yaml'),
      ],
      [
        profile([packageOp, entities, { op: 'codes', mapping: { E_X: 'ys/unknown-key' } }]),
        profile([{ op: 'codes', mapping: { E_X: 'ys/value-unmapped' } }]),
      ],
      undefined,
      true
    );
    expect(result.documents.map((d) => d.file)).toEqual(['a.yaml', 'b.yaml']);
    expect(result.report.codes.E_X).toBe('ys/value-unmapped');
    expect(result.reportJson()).toMatchObject({
      ignored: [],
      overridden: [],
      documents: [
        { defs: 1, imports: [] },
        { defs: 1, imports: [] },
      ],
    });
    expect(result.reportJson()).not.toHaveProperty('warnings');
  });
});

describe('null-skip, list kinds, and inherited unknown policy', () => {
  const kinds = {
    op: 'kinds',
    at: '/items',
    id: '/id',
    kind: '/types',
    mapping: { Class: 'kind', Skip: null },
    unmapped: 'skip',
  };
  test('first named list kind wins and skip reasons name all unmapped values', () => {
    const result = ProfileEngine.apply(
      [
        ForeignTree.read(
          'items:\n- {id: A, types: [Other, Class]}\n- {id: B, types: [Skip, Class], secret: x}\n- {id: C, types: [Alien, Thing]}\n- {id: D}\n',
          'one.yaml'
        ),
      ],
      [profile([packageOp, kinds])]
    );
    expect(defs(result).map((d) => d.id)).toEqual(['A']);
    expect(result.ignored.map((i) => i.reason)).toEqual([
      'kind mapping null (/types = Skip)',
      'kind unmapped (Alien, Thing)',
      'kind missing (/types)',
    ]);
    expect(result.ignored.map((i) => i.record)).toEqual(['B', 'C', 'D']);
  });
  for (const reverse of [false, true])
    test(`skip suppresses discovery/refusals beneath it in either order (${reverse})`, () => {
      const rootKinds = { op: 'kinds', at: '/items', kind: '/type', mapping: { skip: null } };
      const childKinds = {
        op: 'kinds',
        at: '/items/0/children',
        id: '/id',
        mapping: { e: 'entity' },
      };
      const result = ProfileEngine.apply(
        [ForeignTree.read('items: [{type: skip, children: [{bad: value}]}]')],
        [profile(reverse ? [packageOp, childKinds, rootKinds] : [packageOp, rootKinds, childKinds])]
      );
      expect(result.documents).toHaveLength(0);
      expect(result.ignored).toHaveLength(1);
    });
  test('another kind recording the skipped node keeps its unknown keys checked', () => {
    const source = 'items: {A: {type: skip, stray: 1}}';
    const ops = [
      { op: 'kinds', at: '/items', kind: '/type', mapping: { skip: null } },
      { op: 'kinds', at: '/items', mapping: { e: 'entity' } },
      { op: 'ignore', in: 'entity', at: '/type' },
    ];
    expect(
      errorOf(() => ProfileEngine.apply([ForeignTree.read(source)], [profile([packageOp, ...ops])]))
        .code
    ).toBe('ys/unknown-key');
  });
  test('unknown ignore aggregates distinct records, drops containers whole, preserves numeric map keys', () => {
    const result = run(
      'entities:\n  A: {extra: {deep: 1}, "123": x}\n  B: {extra: {deep: 2}, "123": y}\n',
      [],
      'ignore'
    );
    expect(result.report.warnings).toEqual([
      expect.objectContaining({
        in: 'entity',
        key: '/extra',
        count: 2,
        message: 'not in this profile',
        location: expect.objectContaining({ line: 2, path: '/entities/A/extra' }),
      }),
      expect.objectContaining({
        in: 'entity',
        key: '/123',
        count: 2,
        message: 'not in this profile',
      }),
    ]);
    expect(result.ignored).toEqual([]);
  });
  test('child without unknown inherits ignore, explicit error restores strictness', () => {
    const tree = ForeignTree.read('entities: {A: {stray: x}}', 'one.yaml');
    const parent = profile([packageOp, entities], 'ignore');
    expect(ProfileEngine.apply([tree], [parent, profile([])]).report.warnings).toHaveLength(1);
    expect(errorOf(() => ProfileEngine.apply([tree], [parent, profile([], 'error')])).code).toBe(
      'ys/unknown-key'
    );
  });
  test('file-scoped list occurrences wildcard real indices and report each occurrence', () => {
    const result = ProfileEngine.apply(
      [ForeignTree.read('items: [a,b]\n')],
      [profile([{ op: 'ignore', at: '/absent' }], 'ignore')]
    );
    expect(result.report.warnings[0]).toMatchObject({ key: '/items', count: 1 });
    expect(result.report.warnings[0]).not.toHaveProperty('in');
  });
  test('wildcard kind selection consumes the selected element and warning keys wildcard only actual lists', () => {
    const result = ProfileEngine.apply(
      [ForeignTree.read('items:\n  A:\n    types: [Class, Other, More]\n    "123": x\n')],
      [
        profile(
          [packageOp, { op: 'kinds', at: '/items', kind: '/types/*', mapping: { Class: 'kind' } }],
          'ignore'
        ),
      ]
    );
    expect(result.report.warnings).toEqual([
      expect.objectContaining({ in: 'kind', key: '/types/*', count: 1 }),
      expect.objectContaining({ in: 'kind', key: '/123', count: 1 }),
    ]);
  });
});

describe('canonical AST relocation after normalization', () => {
  test('ordered cn properties carry each distinct authored key', () => {
    const source = 'items:\n  A:\n    label: {cs: Koncept}\n    number: Numeric\n';
    const result = ProfileEngine.apply(
      [ForeignTree.read(source, 'concept.yaml')],
      [
        profile([
          packageOp,
          { op: 'kinds', at: '/items', mapping: { c: 'kind' } },
          write('rename', '/label', 'label', 'kind'),
          write('rename', '/number', 'description', 'kind'),
        ]),
      ]
    );
    const doc = result.documents[0];
    const label = nodes(doc.ast).find(
      (n) => n.kind === 'localizedString' && (n.entries as Obj)?.cs === 'Koncept'
    );
    const number = nodes(doc.ast).find((n) => n.kind === 'string' && n.value === 'Numeric');
    expect(label?.source).toMatchObject({
      file: 'concept.yaml',
      line: 3,
      column: 4,
      offsetStart: source.indexOf('label'),
    });
    expect(number?.source).toMatchObject({
      file: 'concept.yaml',
      line: 4,
      column: 4,
      offsetStart: source.indexOf('number'),
    });
    expect([...doc.locations.keys()]).toContain('/defs/0/properties/0/label');
    expect([...doc.locations.keys()]).toContain('/defs/0/properties/1/description');
  });
  test('canonical schema errors name the authored structural input', () => {
    const error = errorOf(() =>
      run('entities:\n  A:\n    invalid: []\n', [write('rename', '/invalid', 'description')])
    );
    expect(error).toMatchObject({
      code: 'ys/profile-invalid',
      location: { line: 3, column: 5, path: '/entities/A/invalid' },
    });
  });
  test('literal embedded blocks preserve authored code-point offsets, indent and line starts', () => {
    const source =
      '# 😀\nitems:\n  Q:\n    expression:\n      block: |\n        return "😀";\n        next();\n';
    const result = ProfileEngine.apply(
      [ForeignTree.read(source, 'query.yaml')],
      [
        profile([
          packageOp,
          { op: 'kinds', at: '/items', mapping: { q: 'kind' } },
          {
            op: 'rename',
            in: 'kind',
            at: '/expression',
            to: { kind: 'kind', path: ['definition'] },
          },
        ]),
      ]
    );
    const block = nodes(result.documents[0].ast).find((n) => n.kind === 'embeddedBlock');
    expect(block).toBeDefined();
    const start = [...source.slice(0, source.indexOf('return'))].length;
    expect(block?.textSource).toMatchObject({
      file: 'query.yaml',
      line: 6,
      column: 8,
      offsetStart: start,
    });
    expect(block?.textMapping).toMatchObject({ offsets: expect.arrayContaining([start]) });
    expect(block?.lineOffsets).toEqual(
      expect.arrayContaining([start, [...source.slice(0, source.indexOf('next'))].length])
    );
    expect(block?.indentWidth).toBe(8);
  });
  test('quoted embedded blocks map decoded newlines and Unicode escapes into authored bytes', () => {
    const source = '# 😀\nitems:\n  A:\n    expression: {block: "return \\u0041;\\nnext();"}\n';
    const result = ProfileEngine.apply(
      [ForeignTree.read(source, 'quoted.yaml')],
      [
        profile([
          packageOp,
          { op: 'kinds', at: '/items', mapping: { c: 'kind' } },
          {
            op: 'rename',
            in: 'kind',
            at: '/expression',
            to: { kind: 'kind', path: ['definition'] },
          },
        ]),
      ]
    );
    const block = nodes(result.documents[0].ast).find((n) => n.kind === 'embeddedBlock');
    expect(block?.text).toBe('return A;\nnext();');
    const mapping = block?.textMapping as { offsets: number[] };
    expect(mapping.offsets[7]).toBe([...source.slice(0, source.indexOf('\\u0041'))].length);
    expect(mapping.offsets[10]).toBe([...source.slice(0, source.indexOf('next'))].length);
  });
  test('tagged query blocks retain their authored scalar and indentation', () => {
    const source =
      'items:\n  Q:\n    sql:\n      tag: sql\n      block: |\n        SELECT a\n          FROM t\n';
    const result = ProfileEngine.apply(
      [ForeignTree.read(source, 'sql.yaml')],
      [
        profile([
          packageOp,
          { op: 'kinds', at: '/items', mapping: { q: 'query' } },
          { op: 'nest', in: 'query', at: '/sql', to: { kind: 'query', path: ['sourceText'] } },
        ]),
      ]
    );
    const block = nodes(result.documents[0].ast).find((n) => n.kind === 'taggedBlock');
    expect(block?.valueSource).toMatchObject({
      file: 'sql.yaml',
      line: 6,
      column: 8,
      offsetStart: source.indexOf('SELECT'),
    });
    expect(block?.indentWidth).toBe(8);
  });
  test('an overlay block retains one generated text coordinate system while its outer source stays dialect-authored', () => {
    const source = 'items:\n  Q:\n    sql: {tag: sql, block: SELECT a}\n';
    const selected = profile([
      packageOp,
      { op: 'kinds', at: '/items', mapping: { q: 'query' } },
      { op: 'nest', in: 'query', at: '/sql', to: { kind: 'query', path: ['sourceText'] } },
    ]);
    const overlay = ProfileReader.read(
      'profile: overlay/1\ngrammar: ">=0.22 <1.0"\noperations: []\noverlay:\n  Q:\n    sourceText: {tag: sql, block: "SELECT b\\n  FROM z"}\n',
      'overlay.yaml'
    );
    const result = ProfileEngine.apply([ForeignTree.read(source, 'sql.yaml')], [selected, overlay]);
    const block = nodes(result.documents[0].ast).find((n) => n.kind === 'taggedBlock');
    expect(block?.value).toBe('SELECT b\n  FROM z');
    expect(block?.source).toMatchObject({ file: 'sql.yaml' });
    expect(block?.valueSource).toMatchObject({ line: 1 });
    expect(block?.indentWidth).toBe(0);
  });
});
