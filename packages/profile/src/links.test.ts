// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, afterEach } from 'vitest';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, symlinkSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ForeignTree, JsonPointer, type Json } from './input.js';
import { type ProfileDefinition } from './loading.js';
import { Record, type RecordPlan, type CanonicalBuilder } from './engine.js';
import {
  LinkIndex,
  SourcesJsonReader,
  OverlayApplier,
  OverlayLoader,
  Qnames,
  Renames,
  bytewise,
  jsonEqual,
  type OverlayEntry,
  type LinkEntry,
} from './links.js';
const temp: string[] = [];
const dir = () => {
  const d = realpathSync(mkdtempSync(join(tmpdir(), 'profile-links-')));
  temp.push(d);
  return d;
};
afterEach(() => {
  for (const d of temp.splice(0)) rmSync(d, { recursive: true, force: true });
});
const tree = () => ForeignTree.read('id: R\n', 'dialect.yaml');
const candidate = (
  kind: string,
  name = 'p.' + kind,
  sourceName = 'R',
  parent: Record | null = null
): LinkEntry => ({
  qname: name,
  kind,
  pkg: 'p',
  sourceName,
  record: new Record(tree().root, kind, 'R', parent),
});
const overlay = (property: string, value: Json, key = 'R'): OverlayEntry => ({
  key,
  property,
  value,
  keyLocation: { file: 'overlay.yaml', line: 2, column: 3, endLine: 2, endColumn: 4, path: '/R' },
  valueLocation: {
    file: 'overlay.yaml',
    line: 3,
    column: 5,
    endLine: 3,
    endColumn: 6,
    path: `/R/${property}`,
  },
});
const builder = () =>
  ({
    overlay: vi.fn((): Json | undefined => undefined),
    rekind: vi.fn(() => 'kind'),
  }) as unknown as CanonicalBuilder;

describe('link index and sidecars', () => {
  it('reads sidecars once, only on resolve; deduplicates after filtering kind', () => {
    const run = candidate('entity', 'p.er.entity.R');
    const sidecars = vi.fn(() => [
      { qname: run.qname, kind: 'entity', pkg: 'p', sourceName: 'R' },
      { qname: 'q.db.dbo.R', kind: 'table', pkg: 'q', sourceName: 'R' },
    ]);
    const index = new LinkIndex([run], sidecars);
    expect(index.qname(run.qname)).toEqual([run]);
    expect(index.sources()).toHaveLength(1);
    expect(sidecars).not.toHaveBeenCalled();
    expect(index.resolve('sourceName', 'R')).toHaveLength(2);
    expect(index.resolve('sourceName', 'R', 'table')).toHaveLength(1);
    expect(sidecars).toHaveBeenCalledTimes(1);
  });
  it('uses id-chain paths, sourceName pointers and stable grammar qnames', () => {
    const t = ForeignTree.read('name: FQN');
    const parent = new Record(t.root, 'entity', 'Customer');
    const child = new Record(t.root, 'attribute', 'code', parent);
    expect(Qnames.of(parent, 'demo')).toBe('demo.er.entity.Customer');
    expect(Qnames.of(child, 'demo')).toBe('demo.er.entity.Customer.code');
    expect(Qnames.path(child)).toBe('Customer/code');
    expect(Qnames.of(new Record(t.root, 'table', 'T'), '')).toBe('db.dbo.T');
    const plan = { records: [parent, child] } as RecordPlan;
    const index = LinkIndex.build(
      [plan],
      new Map([[plan, 'demo']]),
      undefined,
      new JsonPointer('/name')
    );
    expect(index.resolve('sourceName', 'FQN')).toHaveLength(2);
    expect(index.resolve('path', 'Customer/code')[0].record).toBe(child);
    expect(index.resolve('iri', 'FQN')).toEqual([]);
  });
  it('walks sidecars in path order and skips hidden/build/vendor directories at every depth', () => {
    const root = dir();
    mkdirSync(join(root, 'nested'));
    mkdirSync(join(root, 'nested', 'build'));
    mkdirSync(join(root, '.git'));
    mkdirSync(join(root, 'node_modules'));
    for (const name of [
      'nested/build/bad.sources.json',
      '.git/bad.sources.json',
      'node_modules/bad.sources.json',
    ])
      writeFileSync(join(root, name), 'not JSON');
    writeFileSync(
      join(root, 'b.sources.json'),
      JSON.stringify({
        entries: [
          {
            qname: 'b.er.entity.X',
            kind: 'entity',
            sourceName: 'B',
            foreignId: 'iri:B',
            locator: 'X',
          },
        ],
      })
    );
    writeFileSync(
      join(root, 'nested/a.sources.json'),
      JSON.stringify({ entries: [{ qname: 'a.er.entity.X', kind: 'entity' }] })
    );
    const entries = SourcesJsonReader.read(root);
    expect(entries.map((e) => e.pkg)).toEqual(['b', 'a']);
    expect(entries[0]).toMatchObject({ sourceName: 'B', iri: 'iri:B', path: 'X' });
  });
  it('does not follow directory symlinks and diagnoses malformed sidecars at relative path', () => {
    const root = dir(),
      other = dir();
    writeFileSync(join(other, 'x.sources.json'), 'bad');
    symlinkSync(other, join(root, 'linked'));
    expect(SourcesJsonReader.read(root)).toEqual([]);
    mkdirSync(join(root, 'nested'));
    writeFileSync(
      join(root, 'nested', 'bad.sources.json'),
      '{"entries":[{"qname":42,"kind":"entity"}]}'
    );
    expect(() => SourcesJsonReader.read(root)).toThrow(
      expect.objectContaining({
        code: 'ys/profile-invalid',
        location: expect.objectContaining({ file: 'nested/bad.sources.json', line: 1, column: 1 }),
      })
    );
  });
  it.each(['{}', '{"entries":{}}', '{"entries":[null]}', '{"entries":[{"qname":"x"}]}'])(
    'refuses invalid sidecar shape %s',
    (text) => {
      const root = dir();
      writeFileSync(join(root, 'p.sources.json'), text);
      expect(() => SourcesJsonReader.read(root)).toThrow(
        expect.objectContaining({ code: 'ys/profile-invalid' })
      );
    }
  );
  it('compares Unicode by UTF-8 bytes', () => {
    expect(bytewise('\ue000', '😀')).toBeLessThan(0);
    expect(bytewise('a', 'aa')).toBeLessThan(0);
  });
});
describe('overlay target selection and reports', () => {
  it('prefers the primary defining record over binding fan-out', () => {
    const entity = candidate('entity'),
      binding = candidate('er2db_entity');
    const b = builder();
    const result = OverlayApplier.apply(
      [overlay('label', 'Name')],
      new LinkIndex([binding, entity]),
      b
    );
    expect(b.overlay).toHaveBeenCalledWith(
      entity.record,
      'label',
      'Name',
      expect.objectContaining({ path: '/R/label' })
    );
    expect(result).toEqual([{ qname: entity.qname, property: 'label', from: null, to: 'Name' }]);
  });
  it('qname bypasses selection and does not load sidecars or renames', () => {
    const e = candidate('entity');
    const load = vi.fn(() => {
      throw new Error('must remain lazy');
    });
    const b = builder();
    OverlayApplier.apply(
      [overlay('label', 'Name', e.qname)],
      new LinkIndex([e], load),
      b,
      '/absent/root'
    );
    expect(load).not.toHaveBeenCalled();
  });
  it('resolves sourceName sidecar aliases back to run records', () => {
    const e = candidate('entity');
    const index = new LinkIndex([e], () => [
      { qname: e.qname, kind: e.kind, pkg: e.pkg, sourceName: 'Old' },
    ]);
    const b = builder();
    OverlayApplier.apply([overlay('label', 'Name', 'Old')], index, b);
    expect(b.overlay).toHaveBeenCalledWith(e.record, 'label', 'Name', expect.anything());
  });
  it('cn re-kind wins fan-out selection and preserves index identity', () => {
    const cn = candidate('kind', 'p.cn.kind.R'),
      er = candidate('entity');
    const index = new LinkIndex([er, cn]);
    const b = builder();
    const result = OverlayApplier.apply([overlay('kind', 'role')], index, b);
    expect(b.rekind).toHaveBeenCalledWith(
      cn.record,
      'role',
      cn.qname,
      expect.objectContaining({ path: '/R/kind' })
    );
    expect(b.overlay).not.toHaveBeenCalled();
    expect(result).toEqual([{ qname: cn.qname, property: 'kind', from: 'kind', to: 'role' }]);
    expect(index.sources().find((e) => e.qname === cn.qname)?.kind).toBe('kind');
    expect(index.resolve('sourceName', 'R', 'role')).toEqual([]);
  });
  it.each(['entity', 'er2db_entity', 'table'])('kind stays a property on %s', (kind) => {
    const e = candidate(kind);
    const b = builder();
    OverlayApplier.apply([overlay('kind', 'role')], new LinkIndex([e]), b);
    expect(b.rekind).not.toHaveBeenCalled();
    expect(b.overlay).toHaveBeenCalled();
  });
  it('nested cn and nonstereotype values stay properties', () => {
    const parent = candidate('kind').record!;
    for (const [e, value] of [
      [candidate('kind', 'nested', 'R', parent), 'role'],
      [candidate('kind'), 'Invoice'],
      [candidate('kind'), { ref: 'role' }],
    ] as [LinkEntry, Json][]) {
      const b = builder();
      OverlayApplier.apply([overlay('kind', value)], new LinkIndex([e]), b);
      expect(b.rekind).not.toHaveBeenCalled();
      expect(b.overlay).toHaveBeenCalled();
    }
  });
  it('reports successive keyword changes and omits unchanged re-kind', () => {
    const cn = candidate('kind');
    const b = builder();
    vi.mocked(b.rekind)
      .mockReturnValueOnce('kind')
      .mockReturnValueOnce('role')
      .mockReturnValueOnce('event');
    expect(
      OverlayApplier.apply(
        [overlay('kind', 'role'), overlay('kind', 'event'), overlay('kind', 'event')],
        new LinkIndex([cn]),
        b
      ).map((e) => [e.from, e.to])
    ).toEqual([
      ['kind', 'role'],
      ['role', 'event'],
    ]);
  });
  it('compares objects ignoring order and arrays preserving order', () => {
    expect(jsonEqual({ a: 1, b: 2 }, { b: 2, a: 1 })).toBe(true);
    expect(jsonEqual([1, 2], [2, 1])).toBe(false);
    const e = candidate('entity'),
      b = builder();
    vi.mocked(b.overlay).mockReturnValue({ a: 1, b: 2 });
    expect(OverlayApplier.apply([overlay('label', { b: 2, a: 1 })], new LinkIndex([e]), b)).toEqual(
      []
    );
  });
  it('reports ambiguity at overlay key and names candidate qnames', () => {
    const one = candidate('entity', 'p.er.entity.One'),
      two = candidate('entity', 'p.er.entity.Two');
    expect(() =>
      OverlayApplier.apply([overlay('label', 'Name')], new LinkIndex([one, two]), builder())
    ).toThrow(
      expect.objectContaining({
        code: 'xm/link-ambiguous',
        location: overlay('label', 'Name').keyLocation,
      })
    );
  });
  it('loads rename suggestions only for orphans and keeps authored key span', () => {
    const root = dir();
    writeFileSync(join(root, 'renames.yaml'), '---\n- from: R\n  to: New\n  since: 2026-11-17\n');
    expect(() =>
      OverlayApplier.apply([overlay('label', 'Name')], new LinkIndex([]), builder(), root)
    ).toThrow(
      expect.objectContaining({
        code: 'xm/overlay-orphan',
        location: overlay('label', 'Name').keyLocation,
        suggestion: { delta: { renamed: { from: 'R', to: 'New', since: '2026-11-17' } } },
      })
    );
  });
});
// Overlay definitions are represented directly here, avoiding schema-dependent unrelated operands.
function profile(source: string, file: string): ProfileDefinition {
  const tree = ForeignTree.read(source, file);
  const locations = new Map<string, ReturnType<typeof tree.root.span>>(),
    keyLocations = new Map<string, ReturnType<typeof tree.root.span>>();
  const walk = (node: typeof tree.root) => {
    locations.set(node.path, node.span());
    keyLocations.set(node.path, node.span(true));
    node.entries().forEach(walk);
  };
  walk(tree.root);
  return {
    overlay: (tree.root.value as { [key: string]: Json }).overlay,
    locations,
    keyLocations,
  } as ProfileDefinition;
}
describe('rename ledger parity', () => {
  it.each(['', '# empty ledger\n', '---\n'])('accepts empty ledger %s', (source) => {
    const root = dir();
    writeFileSync(join(root, 'renames.yaml'), source);
    expect(Renames.read(root)).toEqual([]);
  });
  it.each([
    ['anchors and aliases', '- &a {from: Old, to: New, since: v1}\n- *a', 2],
    [
      'YAML 1.1 directive with core scalar resolution',
      '%YAML 1.1\n---\n- {from: yes, to: New, since: 2026-11-17}',
      1,
    ],
  ])('C2: accepts %s with plain-load semantics', (_name, source, count) => {
    const root = dir();
    writeFileSync(join(root, 'renames.yaml'), source);
    const ledger = Renames.read(root);
    expect(ledger).toHaveLength(count);
    if (count === 2)
      expect(ledger).toEqual([
        { from: 'Old', to: 'New', since: 'v1' },
        { from: 'Old', to: 'New', since: 'v1' },
      ]);
    else expect(ledger).toEqual([{ from: 'yes', to: 'New', since: '2026-11-17' }]);
  });
  it.each([
    ['unmerged merge key', '- {<<: {since: v1}, from: Old, to: New}', 1, 1, '/0'],
    ['duplicate key', '- {from: Old, from: Other, to: New, since: v1}', 1, 15, ''],
    ['unknown tag', '!unknown []', 1, 1, ''],
    ['second document', '[]\n---\n[]', 2, 1, ''],
    ['non-string field', '- {from: 1, to: New, since: v1}', 1, 1, '/0/from'],
  ])('C2: %s uses Kotlin ledger positions and paths', (_name, source, line, column, path) => {
    const root = dir();
    writeFileSync(join(root, 'renames.yaml'), source);
    expect(() => Renames.read(root)).toThrow(
      expect.objectContaining({
        code: 'ys/profile-invalid',
        location: expect.objectContaining({ file: 'renames.yaml', line, column, path }),
      })
    );
  });
  it('ignores a directory ledger and locates malformed YAML at the ledger', () => {
    const root = dir();
    mkdirSync(join(root, 'renames.yaml'));
    expect(Renames.read(root)).toEqual([]);
    rmSync(join(root, 'renames.yaml'), { recursive: true });
    writeFileSync(join(root, 'renames.yaml'), '- from: Old\n  to: [broken\n');
    expect(() => Renames.read(root)).toThrow(
      expect.objectContaining({
        code: 'ys/profile-invalid',
        location: expect.objectContaining({ file: 'renames.yaml', line: 3 }),
      })
    );
  });
});
describe('overlay loading', () => {
  it('retains inline escaped key/value spans', () => {
    const entries = OverlayLoader.load(
      profile('overlay:\n  "a/b~c":\n    label: New\n', 'profile.yaml')
    );
    expect(entries[0]).toMatchObject({
      key: 'a/b~c',
      keyLocation: { file: 'profile.yaml', line: 2, column: 3, path: '/overlay/a~1b~0c' },
      valueLocation: { line: 3, column: 12, path: '/overlay/a~1b~0c/label' },
    });
  });
  it('resolves path relative to declaring profile and confines real paths', () => {
    const root = dir();
    mkdirSync(join(root, 'profiles'));
    writeFileSync(join(root, 'profiles', 'overlay.yaml'), 'R:\n  label: New\n');
    const p = profile('overlay: overlay.yaml\n', 'profiles/p.yaml');
    expect(OverlayLoader.load(p, root)[0]).toMatchObject({
      key: 'R',
      valueLocation: { file: join(root, 'profiles', 'overlay.yaml'), line: 2, column: 10 },
    });
    const other = dir();
    writeFileSync(join(other, 'out.yaml'), 'R: {label: Bad}');
    symlinkSync(join(other, 'out.yaml'), join(root, 'profiles', 'escape.yaml'));
    expect(() =>
      OverlayLoader.load(profile('overlay: escape.yaml', 'profiles/p.yaml'), root)
    ).toThrow(
      expect.objectContaining({
        code: 'ys/profile-not-found',
        location: expect.objectContaining({ path: '/overlay' }),
      })
    );
  });
  it('refuses missing roots/files and nonmapping entries at authored values', () => {
    expect(() => OverlayLoader.load(profile('overlay: other.yaml', 'p.yaml'))).toThrow(
      expect.objectContaining({ code: 'ys/profile-not-found' })
    );
    expect(() => OverlayLoader.load(profile('overlay:\n  R: scalar', 'p.yaml'))).toThrow(
      expect.objectContaining({
        code: 'ys/profile-invalid',
        location: expect.objectContaining({ line: 2, column: 6, path: '/overlay/R' }),
      })
    );
  });
});
