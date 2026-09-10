// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import {
  buildArtifactFromFiles,
  serializeArtifact,
  type ModelFile,
} from '../resolve-packages.js';
import type { PackagesConfig } from '@tatrman/semantics';

const ROOT = '/proj';
const flexible: PackagesConfig = { root: '', layout: 'flexible' };
const withRoot: PackagesConfig = { root: 'com.tatrman', layout: 'flexible' };

const declared = (pkg: string, entity: string) =>
  `package ${pkg}\nmodel er schema entity\ndef entity ${entity} { attributes: [def attribute id { type: int }] }`;
const undeclared = (entity: string) =>
  `model er schema entity\ndef entity ${entity} { attributes: [def attribute id { type: int }] }`;

const FIXTURE: ModelFile[] = [
  { path: '/proj/a/er.ttrm', text: declared('a', 'ea') },
  { path: '/proj/a/b/er.ttrm', text: declared('a.b', 'eb') },
  { path: '/proj/a/b/c/er.ttrm', text: declared('a.b.c', 'ec') },
  { path: '/proj/domains/core.ttrm', text: 'def area D { packages: [a] }' },
];

describe('PD4 — buildArtifactFromFiles', () => {
  it('produces the contracts §13.4 shape', () => {
    const a = buildArtifactFromFiles(FIXTURE, ROOT, flexible, 'proj');
    expect(a.formatVersion).toBe(1);
    expect(a.root).toBe('');
    expect(a.generatedFrom).toBe('proj');
  });

  it('packages sorted by canonicalName with nested flags and directories', () => {
    const { packages } = buildArtifactFromFiles(FIXTURE, ROOT, flexible, 'proj');
    expect(packages).toEqual([
      { canonicalName: 'a', declaredName: 'a', nested: false, directory: 'a' },
      { canonicalName: 'a.b', declaredName: 'a.b', nested: true, directory: 'a/b' },
      { canonicalName: 'a.b.c', declaredName: 'a.b.c', nested: true, directory: 'a/b/c' },
    ]);
  });

  it('entities sorted by qname with owning package + schema', () => {
    const { entities } = buildArtifactFromFiles(FIXTURE, ROOT, flexible, 'proj');
    expect(entities).toEqual([
      { qname: 'a.b.c.er.entity.ec', package: 'a.b.c', schema: 'er' },
      { qname: 'a.b.er.entity.eb', package: 'a.b', schema: 'er' },
      { qname: 'a.er.entity.ea', package: 'a', schema: 'er' },
    ]);
  });

  it('areas carry the RECURSIVE package closure', () => {
    const { areas } = buildArtifactFromFiles(FIXTURE, ROOT, flexible, 'proj');
    expect(areas).toEqual([
      { name: 'D', resolvedPackages: ['a', 'a.b', 'a.b.c'], resolvedEntities: [] },
    ]);
  });

  it('is byte-deterministic — serialise twice → identical', () => {
    const a = serializeArtifact(buildArtifactFromFiles(FIXTURE, ROOT, flexible, 'proj'));
    const b = serializeArtifact(buildArtifactFromFiles(FIXTURE, ROOT, flexible, 'proj'));
    expect(a).toBe(b);
    expect(a.endsWith('\n')).toBe(true); // trailing newline
  });

  it('input file order does not affect output (sorting defeats order)', () => {
    const forward = serializeArtifact(buildArtifactFromFiles(FIXTURE, ROOT, flexible, 'proj'));
    const reversed = serializeArtifact(buildArtifactFromFiles([...FIXTURE].reverse(), ROOT, flexible, 'proj'));
    expect(reversed).toBe(forward);
  });

  it('root="com.tatrman": canonicalNames prefixed, declaredNames the bare written form', () => {
    // Files declare bare packages (eliding the root); the artifact re-prefixes.
    const files: ModelFile[] = [
      { path: '/proj/a/er.ttrm', text: declared('a', 'ea') },
      { path: '/proj/a/b/er.ttrm', text: declared('a.b', 'eb') },
      { path: '/proj/domains/core.ttrm', text: 'def area D { packages: [a] }' },
    ];
    const a = buildArtifactFromFiles(files, ROOT, withRoot, 'proj');
    expect(a.root).toBe('com.tatrman');
    expect(a.packages).toEqual([
      { canonicalName: 'com.tatrman.a', declaredName: 'a', nested: false, directory: 'a' },
      { canonicalName: 'com.tatrman.a.b', declaredName: 'a.b', nested: true, directory: 'a/b' },
    ]);
    expect(a.entities.map((e) => e.qname)).toEqual([
      'com.tatrman.a.b.er.entity.eb',
      'com.tatrman.a.er.entity.ea',
    ]);
    expect(a.areas[0].resolvedPackages).toEqual(['com.tatrman.a', 'com.tatrman.a.b']);
  });

  it('undeclared files under a root derive prefixed canonical names', () => {
    const files: ModelFile[] = [
      { path: '/proj/a/er.ttrm', text: undeclared('ea') },
      { path: '/proj/a/b/er.ttrm', text: undeclared('eb') },
    ];
    const a = buildArtifactFromFiles(files, ROOT, withRoot, 'proj');
    expect(a.packages.map((p) => p.canonicalName)).toEqual(['com.tatrman.a', 'com.tatrman.a.b']);
  });

  it('empty project → valid artifact with empty arrays (not missing keys)', () => {
    const a = buildArtifactFromFiles([], ROOT, flexible, 'proj');
    expect(a).toEqual({
      formatVersion: 1,
      generatedFrom: 'proj',
      root: '',
      packages: [],
      entities: [],
      areas: [],
    });
  });

  it('a project with no areas still emits an empty areas array', () => {
    const files: ModelFile[] = [{ path: '/proj/a/er.ttrm', text: declared('a', 'ea') }];
    const a = buildArtifactFromFiles(files, ROOT, flexible, 'proj');
    expect(a.areas).toEqual([]);
  });

  // ── a file that does not parse contributes nothing ───────────────────────────────────────────
  //
  // The parser is deliberately error-TOLERANT: it recovers past a bad token and keeps the
  // definitions it can still read, which is what an editor needs. A build artifact needs the
  // opposite. `buildArtifactFromFiles` used to take `parseString(...).ast` and never look at
  // `.errors`, so a file the parser had REJECTED still contributed packages, entities and areas —
  // under a guessed schema code, since its `model` directive was part of what failed.
  //
  // Found on kantheon's `investment` package (IE-P2·S2.1), where three files declare `model book`
  // — not a model code this grammar has — and were believed invisible. They were not: their
  // `def entity` declarations reached the symbol table, and because the table takes the FIRST
  // writer of a qname and `model/book.ttrm` sorts before `model/er/book.ttrm`, the artifact
  // resolved `transaction` and `position` to the file that had failed to parse rather than to the
  // authored `er` layer. Alphabetical order decided which model a consumer would be served.
  //
  // The artifact cannot express WHICH file won a qname, so that half is asserted in kantheon's
  // own suite against the symbol table. What the artifact does show — and what these assert — is
  // the entities and areas that only exist because a rejected file was read anyway.
  const unparseable = (entity: string) =>
    `package a\nmodel book schema entity\ndef entity ${entity} { attributes: [def attribute id { type: int }] }`;

  it('a file with parse errors contributes NO entities', () => {
    const files: ModelFile[] = [{ path: '/proj/a/broken.ttrm', text: unparseable('ghost') }];
    const a = buildArtifactFromFiles(files, ROOT, flexible, 'proj');
    expect(a.entities).toEqual([]);
    expect(a.packages).toEqual([]);
  });

  it('a file with parse errors cannot add an entity beside the ones that parse', () => {
    // The kantheon shape exactly: one authored `er` file, one file the parser rejected, both in
    // the same package. Before the fix the artifact carried BOTH `client` and `consultant`, and
    // nothing distinguished the one that had been authored from the one that had been recovered
    // out of a syntax error.
    const files: ModelFile[] = [
      { path: '/proj/a/book.ttrm', text: unparseable('consultant') },
      { path: '/proj/a/er/parties.ttrm', text: declared('a', 'client') },
    ];
    const a = buildArtifactFromFiles(files, ROOT, flexible, 'proj');
    expect(a.entities.map((e) => e.qname)).toEqual(['a.er.entity.client']);
  });

  it('a file with parse errors contributes no AREA either', () => {
    const files: ModelFile[] = [
      { path: '/proj/a/er.ttrm', text: declared('a', 'ea') },
      { path: '/proj/domains/broken.ttrd.ttrm', text: 'def area D { packages: [a] } }}}' },
    ];
    const a = buildArtifactFromFiles(files, ROOT, flexible, 'proj');
    expect(a.areas).toEqual([]);
  });

  it('a file that parses is unaffected', () => {
    // The guard must key on ERRORS, not on "looks odd" — every existing fixture still resolves.
    const a = buildArtifactFromFiles(FIXTURE, ROOT, flexible, 'proj');
    expect(a.entities.map((e) => e.qname)).toEqual([
      'a.b.c.er.entity.ec',
      'a.b.er.entity.eb',
      'a.er.entity.ea',
    ]);
  });

  // ── a WARNING is not a rejection (review-089 ⒄) ────────────────────────────────────────────
  //
  // `parseString(...).errors` carries every diagnostic the parser has, not only errors: the
  // walker's lint warnings (`UnknownLanguageTag`, `DeprecatedLanguageProperty`) ride the same list.
  // The guard above first keyed on `errors.length`, so a well-formed query in the CURRENT tagged
  // form that also kept the soft-deprecated `language: SQL` vanished from the artifact, and the CLI
  // reported it as a file that "does not parse". No estate was exposed only because every
  // `q_*.ttrm` so far uses an untagged `"""` block.
  const query = (pkg: string, body: string) => `package ${pkg}\nmodel query\ndef query x {\n${body}\n}`;
  const TAGGED_SQL = '  sourceText: """sql\nSELECT 1\n"""';

  it('a query keeping `language: SQL` beside a tagged """sql block contributes its package', () => {
    const skipped: string[] = [];
    const a = buildArtifactFromFiles(
      [
        { path: '/proj/a/er.ttrm', text: declared('a', 'ea') },
        { path: '/proj/q/q.ttrm', text: query('q', `  language: SQL\n${TAGGED_SQL}`) },
      ],
      ROOT,
      flexible,
      'proj',
      (p) => skipped.push(p)
    );
    expect(a.packages.map((p) => p.canonicalName)).toEqual(['a', 'q']);
    expect(skipped).toEqual([]);
  });

  it('an unknown embedded-language tag (a warning) does not drop the file either', () => {
    const skipped: string[] = [];
    const a = buildArtifactFromFiles(
      [{ path: '/proj/q/q.ttrm', text: query('q', '  sourceText: """cobol\nMOVE 1 TO X\n"""') }],
      ROOT,
      flexible,
      'proj',
      (p) => skipped.push(p)
    );
    expect(a.packages.map((p) => p.canonicalName)).toEqual(['q']);
    expect(skipped).toEqual([]);
  });

  it('a file with an error AND a warning is skipped, and onSkip hears only the error', () => {
    // `language: PYTHON` against a `"""sql` tag is LanguageTagMismatch (error) AND
    // DeprecatedLanguageProperty (warning) on the same property. The file is rejected — and the
    // reason handed on must be the error, or the CLI's "it does not parse (<first message>)" line
    // would quote a deprecation notice as the reason a file was dropped.
    const heard: { path: string; severities: string[] }[] = [];
    const a = buildArtifactFromFiles(
      [{ path: '/proj/q/q.ttrm', text: query('q', `  language: PYTHON\n${TAGGED_SQL}`) }],
      ROOT,
      flexible,
      'proj',
      (p, errs) => heard.push({ path: p, severities: errs.map((e) => (e as { severity?: string }).severity ?? '') })
    );
    expect(a.packages).toEqual([]);
    expect(heard).toEqual([{ path: '/proj/q/q.ttrm', severities: ['error'] }]);
  });
});
