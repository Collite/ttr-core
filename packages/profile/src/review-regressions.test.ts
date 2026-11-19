// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  closeSync,
  ftruncateSync,
  mkdirSync,
  mkdtempSync,
  openSync,
  readFileSync,
  readSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ForeignTree, type Json } from './input.js';
import {
  DeclarationReader,
  FileSelector,
  ProfileException,
  ProfileReader,
  ProfileResolver,
} from './loading.js';
import { ProfileEngine } from './engine.js';
import { OverlayLoader, Renames, SourcesJsonReader } from './links.js';
import { readProfileFile } from './file-input.js';

vi.mock('node:fs', async (original) => {
  const fs = await original<typeof import('node:fs')>();
  return { ...fs, readFileSync: vi.fn(fs.readFileSync), readSync: vi.fn(fs.readSync) };
});
const roots: string[] = [];
function root(): string {
  const value = realpathSync(mkdtempSync(join(tmpdir(), 'profile-review-373-')));
  roots.push(value);
  mkdirSync(join(value, 'profiles'));
  return value;
}
afterEach(() => {
  for (const value of roots.splice(0)) rmSync(value, { recursive: true, force: true });
  vi.clearAllMocks();
});
const packageOp = { op: 'package', mapping: {}, default: 'demo' };
const entityOp = { op: 'kinds', at: '/entity', mapping: { e: 'entity' } };
const profile = (ops: Json[] = [packageOp, entityOp], unknown?: string) =>
  ProfileReader.read(
    JSON.stringify({
      profile: 'review/1',
      grammar: '>=0.18 <1.0',
      operations: ops,
      ...(unknown ? { unknown } : {}),
    }),
    'profile.yaml'
  );
function failure(action: () => unknown): ProfileException {
  try {
    action();
  } catch (error) {
    expect(error).toBeInstanceOf(ProfileException);
    return error as ProfileException;
  }
  throw new Error('Expected ProfileException');
}
const apply = (source: string, ops: Json[] = [packageOp, entityOp], unknown?: string) =>
  ProfileEngine.apply([ForeignTree.read(source, 'dialect.yaml')], [profile(ops, unknown)]);

// Oracle positions are from review-373; end coordinates are informational (R373-A).
describe('review-373: warning ownership and first refusals', () => {
  it('F1: a scalar record is dropped at its key in file scope, while strict mode stays at its value', () => {
    const source = 'entity:\n  Invoice: {popis: Faktura}\n  domena: Ž𝟘ř\n';
    const ops = [
      packageOp,
      entityOp,
      { op: 'rename', in: 'entity', at: '/popis', to: { kind: 'entity', property: 'description' } },
    ];
    expect(apply(source, ops, 'ignore').reportJson().warnings).toEqual([
      {
        file: 'dialect.yaml',
        line: 3,
        column: 3,
        path: '/entity/domena',
        key: '/entity/domena',
        count: 1,
        message: 'not in this profile',
      },
    ]);
    expect(failure(() => apply(source, ops, 'error'))).toMatchObject({
      code: 'ys/unknown-key',
      location: { line: 3, column: 11, path: '/entity/domena' },
    });
  });
  it('F1: a nested scalar record belongs to its enclosing entity; list elements retain the list owner', () => {
    const ops = [
      packageOp,
      entityOp,
      { op: 'kinds', in: 'entity', at: '/sloupce', mapping: { a: 'attribute' } },
    ];
    expect(
      apply('entity:\n  A:\n    sloupce:\n      zdroj: ";"\n', ops, 'ignore').reportJson().warnings
    ).toEqual([
      {
        file: 'dialect.yaml',
        line: 4,
        column: 7,
        path: '/entity/A/sloupce/zdroj',
        in: 'entity',
        key: '/sloupce/zdroj',
        count: 1,
        message: 'not in this profile',
      },
    ]);
    expect(apply('entity:\n  A: [x, y]\n', undefined, 'ignore').reportJson().warnings).toEqual([
      {
        file: 'dialect.yaml',
        line: 2,
        column: 7,
        path: '/entity/A/0',
        in: 'entity',
        key: '/*',
        count: 1,
        message: 'not in this profile',
      },
    ]);
  });
  it('F6: a missing overlay is refused before any data operation', () => {
    const boundary = root();
    const selected = ProfileReader.read(
      "profile: review/1\ngrammar: '>=0.18 <1.0'\noperations: " +
        JSON.stringify([packageOp, entityOp]) +
        '\noverlay: ./missing-overlay.yaml\n',
      'profiles/values.yaml'
    );
    expect(
      failure(() =>
        ProfileEngine.apply(
          [ForeignTree.read('entity: {A: {x: bad}}', 'dialect.yaml')],
          [selected],
          boundary
        )
      )
    ).toMatchObject({
      code: 'ys/profile-not-found',
      location: { file: 'profiles/values.yaml', line: 4, column: 10, path: '/overlay' },
    });
  });
  it('F9: the first kinds refusal at a node wins over a later scalar-selection refusal', () => {
    const first = { op: 'kinds', at: '/entity', kind: '/type', mapping: { e: 'entity' } };
    const second = { op: 'kinds', at: '/entity/P', mapping: { e: 'entity' } };
    for (const [ops, code] of [
      [[packageOp, first, second], 'ys/unknown-kind'],
      [[packageOp, second, first], 'ys/profile-invalid'],
    ] as const)
      expect(failure(() => apply('entity:\n  P: scalar\n', [...ops]))).toMatchObject({
        code,
        location: { line: 2, column: 3, path: '/entity/P' },
      });
  });
});

describe('review-373: schema diagnostics, roots, headers and YAML positions', () => {
  it('F2: schema error depth excludes the unknown property name', () => {
    expect(
      failure(() =>
        ProfileReader.read(
          "profile: review/1\ngrammar: '>=0.18 <1.0'\nzzzzzzzzzzzzzzzzzzzz: x\noperations: [3]\n",
          'profile.yaml'
        )
      )
    ).toMatchObject({
      code: 'ys/profile-invalid',
      location: { file: 'profile.yaml', line: 4, path: '/operations/0' },
    });
    expect(
      failure(() => DeclarationReader.readTree('profile: review/1\nentity: {}\n', 'ttr.yaml'))
    ).toMatchObject({
      code: 'ys/profile-invalid',
      location: { file: 'ttr.yaml', line: 1, column: 1, path: '' },
    });
  });
  for (const source of ['~', '- a\n- b\n', 'hello', '42', 'true', ''])
    it(`F3: refuses a non-mapping dialect root ${JSON.stringify(source)}`, () => {
      expect(failure(() => apply(source))).toMatchObject({
        code: 'ys/profile-invalid',
        location: { file: 'dialect.yaml', line: 1, column: 1, path: '' },
      });
    });
  it('F3: profile files reject scalar roots while renames remains a list reader', () => {
    expect(failure(() => ProfileReader.read('~', 'profile.yaml'))).toMatchObject({
      code: 'ys/profile-invalid',
      location: { file: 'profile.yaml', line: 1, column: 1, path: '' },
    });
    const boundary = root();
    for (const ending of ['\n', '\r\n', '\r']) {
      writeFileSync(join(boundary, 'renames.yaml'), '- {from: Old, to: New, since: v1}' + ending);
      expect(Renames.read(boundary)).toEqual([{ from: 'Old', to: 'New', since: 'v1' }]);
    }
    writeFileSync(join(boundary, 'renames.yaml'), '# empty\r# ledger\r');
    expect(Renames.read(boundary)).toEqual([]);
  });
  it('F4: a valid authored-first ttr header stays consumed before an integer-like key', () => {
    const source =
      'ttr: {profile: ./profiles/values.yaml, grammar: ">=0.18 <1.0"}\n"2024": x\nentity:\n  A: {popis: a}\n';
    expect(failure(() => apply(source))).toMatchObject({
      code: 'ys/unknown-key',
      location: { file: 'dialect.yaml', line: 2, column: 1, path: '/2024' },
    });
  });
  it('F4: inline and file overlays enumerate integer-like foreign keys in authored order', () => {
    const source =
      '"10": {description: first, tags: [one]}\n"2": {tags: [two], description: second}\n';
    const boundary = root();
    writeFileSync(join(boundary, 'profiles/overlay.yaml'), source);
    for (const overlay of [
      'overlay: ./overlay.yaml\n',
      'overlay:\n' +
        source
          .split('\n')
          .filter(Boolean)
          .map((line) => '  ' + line)
          .join('\n'),
    ]) {
      const selected = ProfileReader.read(
        "profile: review/1\ngrammar: '>=0.18 <1.0'\noperations: []\n" + overlay,
        'profiles/values.yaml'
      );
      expect(
        OverlayLoader.load(selected, boundary).map((entry) => [entry.key, entry.property])
      ).toEqual([
        ['10', 'description'],
        ['10', 'tags'],
        ['2', 'tags'],
        ['2', 'description'],
      ]);
    }
  });
  for (const ending of ['\r', '\r\n', '\n'])
    it(`F7: authored lines survive ${JSON.stringify(ending)} line breaks`, () => {
      const source = ['entity:', '  A: {popis: a}', '  B: {popis: b, x: 1}', ''].join(ending);
      const ops = [
        packageOp,
        entityOp,
        {
          op: 'rename',
          in: 'entity',
          at: '/popis',
          to: { kind: 'entity', property: 'description' },
        },
      ];
      expect(failure(() => apply(source, ops))).toMatchObject({
        code: 'ys/unknown-key',
        location: { line: 3, column: 17, path: '/entity/B/x' },
      });
    });
  for (const value of ['&x a', '!!binary aGVsbG8='])
    it(`F8: locates ${value} at its value token rather than its key`, () => {
      expect(failure(() => apply(`entity:\n  A: {popis: ${value}}\n`))).toMatchObject({
        code: 'ys/profile-invalid',
        location: { line: 2, column: 14, path: '/entity/A/popis' },
      });
    });
  it('F8: root and sequence property tokens retain their own authored starts', () => {
    for (const [source, line, column, path] of [
      ['# c\n---\n&root {x: a}', 3, 1, ''],
      ['x: [&a one]', 1, 5, '/x/0'],
      ['x: [!!binary aGVsbG8=]', 1, 5, '/x/0'],
    ] as const)
      expect(failure(() => apply(source))).toMatchObject({
        code: 'ys/profile-invalid',
        location: { line, column, path },
      });
  });
  it('F10: globs match Unicode code points and Kotlin negated-class prefixes', () => {
    for (const [pattern, name, expected] of [
      ['?.yaml', '𝟘.yaml', true],
      ['[!^a].yaml', '^.yaml', true],
      ['[!^a].yaml', 'a.yaml', false],
      ['[𝟘a].yaml', '𝟘.yaml', true],
      ['[a-z].yaml', 'x.yaml', true],
      ['\\*.yaml', '*.yaml', true],
      ['[\\!].yaml', '!.yaml', true],
    ] as const)
      expect(new FileSelector([pattern]).matches(name), pattern).toBe(expected);
  });
});

describe('review-373: bounded strict UTF-8 file readers', () => {
  const malformed = (file: string, prefix: string, suffix = '') =>
    writeFileSync(
      file,
      Buffer.concat([Buffer.from(prefix), Buffer.from([0xff, 0xfe]), Buffer.from(suffix)])
    );
  it('F5: a malformed profile is located in that file, not its declaration', () => {
    const boundary = root(),
      file = join(boundary, 'profiles/values.yaml');
    malformed(file, "profile: review/1\ngrammar: '>=0.18 <1.0'\noperations: []\n# ok\n# ");
    expect(
      failure(() => new ProfileResolver(boundary).resolve('./profiles/values.yaml', 'ttr.yaml'))
    ).toMatchObject({
      code: 'ys/profile-invalid',
      location: { file, line: 5, column: 3, path: '' },
    });
  });
  it('F5: rejects malformed multibyte sequences after a code-point-counted prefix, while valid U+FFFD stays valid', () => {
    const boundary = root(),
      file = join(boundary, 'profiles/values.yaml');
    const prefix = "profile: review/1\rgrammar: '>=0.18 <1.0'\r\noperations: []\n# 😀 ";
    for (const bytes of [
      [0xc0, 0x80],
      [0xe0, 0x80, 0x80],
      [0xed, 0xa0, 0x80],
      [0xf4, 0x90, 0x80, 0x80],
      [0xe2, 0x82],
      [0x80],
    ]) {
      writeFileSync(file, Buffer.concat([Buffer.from(prefix), Buffer.from(bytes)]));
      expect(
        failure(() => new ProfileResolver(boundary).resolve('./profiles/values.yaml', 'ttr.yaml'))
      ).toMatchObject({
        code: 'ys/profile-invalid',
        location: { file, line: 4, column: 5, path: '' },
      });
    }
    writeFileSync(file, prefix + '�');
    expect(
      new ProfileResolver(boundary).resolve('./profiles/values.yaml', 'ttr.yaml')[0].name
    ).toBe('review');
    writeFileSync(file, '\ufeff' + prefix + '�');
    expect(readProfileFile(file)).toBe('\ufeff' + prefix + '�');
    expect(
      new ProfileResolver(boundary).resolve('./profiles/values.yaml', 'ttr.yaml')[0].name
    ).toBe('review');
    for (const separator of ['\u0085', '\u2028', '\u2029']) {
      malformed(file, '\ufeff# first' + separator + '# ');
      expect(failure(() => readProfileFile(file))).toMatchObject({
        code: 'ys/profile-invalid',
        location: { file, line: 1, column: 11, path: '' },
      });
    }
  });
  it('F5: malformed overlay bytes never become replacement characters in canonical output', () => {
    const boundary = root(),
      file = join(boundary, 'profiles/ov.yaml');
    const selected = ProfileReader.read(
      "profile: review/1\ngrammar: '>=0.18 <1.0'\noperations: []\noverlay: ./ov.yaml\n",
      'profiles/values.yaml'
    );
    for (const prefix of ['A: {description: ', '\ufeffA: {description: ']) {
      malformed(file, prefix, '}');
      expect(failure(() => OverlayLoader.load(selected, boundary))).toMatchObject({
        code: 'ys/profile-invalid',
        location: { file, line: 1, column: 18, path: '' },
      });
    }
  });
  it('F5: malformed renames bytes retain the ledger label and decoded-prefix position', () => {
    const boundary = root();
    malformed(join(boundary, 'renames.yaml'), '- {from: ', ', to: A, since: v1}');
    expect(failure(() => Renames.read(boundary))).toMatchObject({
      code: 'ys/profile-invalid',
      location: { file: 'renames.yaml', line: 1, column: 10, path: '' },
    });
  });
  it('F5: malformed sidecars use the sidecar reader Kotlin error position', () => {
    const boundary = root();
    malformed(
      join(boundary, 'demo.sources.json'),
      '{"entries":[{"qname":"p.er.entity.A","kind":"entity","sourceName":"',
      '"}]}'
    );
    expect(failure(() => SourcesJsonReader.read(boundary))).toMatchObject({
      code: 'ys/profile-invalid',
      location: { file: 'demo.sources.json', line: 1, column: 1, path: '' },
    });
  });
  it('F5: an oversized profile is refused at 1:1 without an unbounded whole-file read', () => {
    const boundary = root(),
      file = join(boundary, 'profiles/large.yaml');
    const fd = openSync(file, 'w');
    try {
      ftruncateSync(fd, 24_000_001);
    } finally {
      closeSync(fd);
    }
    vi.mocked(readFileSync).mockClear();
    vi.mocked(readSync).mockClear();
    expect(
      failure(() => new ProfileResolver(boundary).resolve('./profiles/large.yaml', 'ttr.yaml'))
    ).toMatchObject({
      code: 'ys/profile-invalid',
      detail: 'Profile exceeds 24000000 bytes',
      location: { file, line: 1, column: 1, path: '' },
    });
    expect(readFileSync).not.toHaveBeenCalled();
    const bytes = vi
      .mocked(readSync)
      .mock.results.reduce(
        (n, result) => n + (result.type === 'return' ? Number(result.value) : 0),
        0
      );
    expect(bytes).toBeLessThanOrEqual(24_000_001);
  });
});
