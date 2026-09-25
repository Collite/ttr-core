// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString, DiagnosticCode } from '@tatrman/parser';
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';
import { analyzeSemantics } from '../index.js';
import type { ResolvedEntitySemantics } from '../index.js';

function codesFor(src: string): DiagnosticCode[] {
  const r = parseString(src, 'x.ttrm');
  return analyzeSemantics(r.ast!).diagnostics.map((d) => d.code);
}
function diagsFor(src: string) {
  const r = parseString(src, 'x.ttrm');
  return analyzeSemantics(r.ast!).diagnostics;
}

const ent = (body: string) => `model er\ndef entity E {\n${body}\n}`;

describe('semantics-block validation (TTR-SEM-2xx)', () => {
  it('200 — unknown key (with nearest-match suggestion)', () => {
    const d = diagsFor(ent('attributes: [ def attribute a { type: { type: varchar, length: 6 }, semantics { role: period_code, code_forma: "x" } } ]'));
    const hit = d.find((x) => x.code === DiagnosticCode.SemUnknownKey);
    expect(hit).toBeDefined();
    expect(hit?.suggestion).toBe('code_format');
  });

  it('201 — unknown role (suggests event_date for event_dat)', () => {
    const d = diagsFor(ent('attributes: [ def attribute a { type: date, semantics { role: event_dat } } ]'));
    const hit = d.find((x) => x.code === DiagnosticCode.SemUnknownRole);
    expect(hit).toBeDefined();
    expect(hit?.suggestion).toBe('event_date');
    expect(hit?.message).toContain('event_date');
  });

  it('202 — unknown kind (suggests period_table)', () => {
    const d = diagsFor(ent('semantics { kind: periodtable }'));
    const hit = d.find((x) => x.code === DiagnosticCode.SemUnknownKind);
    expect(hit).toBeDefined();
    expect(hit?.suggestion).toBe('period_table');
  });

  it('203 — duplicate key', () => {
    expect(codesFor(ent('attributes: [ def attribute a { type: date, semantics { role: event_date, role: due_date } } ]')))
      .toContain(DiagnosticCode.SemDuplicateKey);
  });

  it('204 — kind on an attribute, and role on an entity', () => {
    expect(codesFor(ent('attributes: [ def attribute a { type: date, semantics { kind: poi } } ]')))
      .toContain(DiagnosticCode.SemMisplacedKeyword);
    expect(codesFor(ent('semantics { role: event_date }'))).toContain(DiagnosticCode.SemMisplacedKeyword);
  });

  it('205 — type-constraint violation (amount on a text column)', () => {
    expect(codesFor(ent('attributes: [ def attribute a { type: { type: varchar, length: 3 }, semantics { role: amount, currency: a } } ]')))
      .toContain(DiagnosticCode.SemTypeConstraint);
  });

  it('206 — completeness (period_table missing period_end)', () => {
    const src = ent([
      'semantics { kind: period_table },',
      'attributes: [',
      '  def attribute s { type: date, semantics { role: period_start } },',
      '  def attribute c { type: { type: varchar, length: 6 }, semantics { role: period_code } }',
      ']',
    ].join('\n'));
    expect(codesFor(src)).toContain(DiagnosticCode.SemCompleteness);
  });

  it('207 — more than one event_date on an entity', () => {
    const src = ent([
      'attributes: [',
      '  def attribute a { type: date, semantics { role: event_date } },',
      '  def attribute b { type: date, semantics { role: event_date } }',
      ']',
    ].join('\n'));
    expect(codesFor(src)).toContain(DiagnosticCode.SemMultipleEventDate);
  });

  it('208 — period: to a nonexistent entity, and to a non-period_table entity', () => {
    expect(codesFor(ent('attributes: [ def attribute a { type: date, semantics { role: event_date, period: Nope } } ]')))
      .toContain(DiagnosticCode.SemBadPeriodRef);
    const miskinded = [
      'model er',
      'def entity P { semantics { kind: poi }, attributes: [ def attribute x { type: decimal, semantics { role: geo_lat } }, def attribute y { type: decimal, semantics { role: geo_lon } } ] }',
      'def entity E { attributes: [ def attribute a { type: date, semantics { role: event_date, period: P } } ] }',
    ].join('\n');
    expect(codesFor(miskinded)).toContain(DiagnosticCode.SemBadPeriodRef);
  });

  it('209 — currency: to a missing sibling, and to a non-currency_code sibling', () => {
    expect(codesFor(ent('attributes: [ def attribute a { type: decimal, semantics { role: amount, currency: nope } } ]')))
      .toContain(DiagnosticCode.SemBadCurrencyRef);
    const roleless = ent([
      'attributes: [',
      '  def attribute a { type: decimal, semantics { role: amount, currency: c } },',
      '  def attribute c { type: date, semantics { role: event_date } }',
      ']',
    ].join('\n'));
    expect(codesFor(roleless)).toContain(DiagnosticCode.SemBadCurrencyRef);
  });

  it('210 — geo_lat without geo_lon, and geo_point + pair', () => {
    expect(codesFor(ent('semantics { kind: poi }, attributes: [ def attribute a { type: decimal, semantics { role: geo_lat } } ]')))
      .toContain(DiagnosticCode.SemGeoPair);
    const both = ent([
      'semantics { kind: poi },',
      'attributes: [',
      '  def attribute p { type: text, semantics { role: geo_point } },',
      '  def attribute a { type: decimal, semantics { role: geo_lat } },',
      '  def attribute o { type: decimal, semantics { role: geo_lon } }',
      ']',
    ].join('\n'));
    expect(codesFor(both)).toContain(DiagnosticCode.SemGeoPair);
  });

  it('211 — valid_from without valid_to', () => {
    expect(codesFor(ent('attributes: [ def attribute a { type: date, semantics { role: valid_from } } ]')))
      .toContain(DiagnosticCode.SemValidPair);
  });

  it('green path — the golden 59-semantics.ttrm fixture yields zero diagnostics', () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const src = readFileSync(resolve(here, '../../../../tests/conformance/fixtures/59-semantics.ttrm'), 'utf-8');
    const diags = diagsFor(src);
    expect(diags).toEqual([]);
  });

  it('green path — the golden 60-semantics-db.ttrm fixture yields zero diagnostics', () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const src = readFileSync(resolve(here, '../../../../tests/conformance/fixtures/60-semantics-db.ttrm'), 'utf-8');
    expect(diagsFor(src)).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// MS (vocabulary v3) — the mention facet. contracts §1 (surface) + §4 (diagnostics).
// ---------------------------------------------------------------------------

const MEMBERS =
  'attributes: [ def attribute customer_name { type: text }, def attribute doc_no { type: text }, ' +
  'def attribute amount_czk { type: decimal }, def attribute quantity { type: decimal } ]';

function resolvedEntity(src: string) {
  const r = parseString(src, 'x.ttrm');
  const a = analyzeSemantics(r.ast!);
  const first = [...a.resolved.values()][0];
  return { diagnostics: a.diagnostics, resolved: first, all: a.resolved };
}

describe('MS — mention semantics on an entity block', () => {
  it('accepts kind + name + code + measures and resolves them (contracts §1.1)', () => {
    // `kind: period_table` brings its own completeness rule (206), so the fixture carries
    // the three period roles too — the point here is that the grounding facet and the
    // mention facet coexist on one block without interfering.
    const src = ent(
      'semantics { kind: period_table, name: customer_name, code: doc_no, ' +
        'measures: [amount_czk, { attribute: quantity, aggregation: avg }] }, ' +
        'attributes: [ def attribute customer_name { type: text }, def attribute doc_no { type: text }, ' +
        'def attribute amount_czk { type: decimal }, def attribute quantity { type: decimal }, ' +
        'def attribute start_date { type: date, semantics { role: period_start } }, ' +
        'def attribute end_date { type: date, semantics { role: period_end } }, ' +
        'def attribute period { type: text, semantics { role: period_code } } ]',
    );
    const { diagnostics, resolved } = resolvedEntity(src);
    expect(diagnostics).toEqual([]);
    const e = resolved as ResolvedEntitySemantics;
    expect(e.kind).toBe('period_table');
    expect(e.name?.path).toBe('customer_name');
    expect(e.code?.path).toBe('doc_no');
    // Declared order is the contract — the first measure is the default measure.
    expect(e.measures.map((m) => [m.attribute.path, m.aggregation])).toEqual([
      ['amount_czk', 'sum'],
      ['quantity', 'avg'],
    ]);
  });

  it('accepts the same surface on a db table, against its columns', () => {
    const src =
      'model db\ndef table sales {\nsemantics { name: customer_name, code: doc_no, measures: [amount_czk] },\n' +
      'columns: [ def column customer_name { type: text }, def column doc_no { type: text }, ' +
      'def column amount_czk { type: decimal } ]\n}';
    const { diagnostics, resolved } = resolvedEntity(src);
    expect(diagnostics).toEqual([]);
    const e = resolved as ResolvedEntitySemantics;
    expect(e.name?.path).toBe('customer_name');
    expect(e.measures.map((m) => m.attribute.path)).toEqual(['amount_czk']);
  });

  it('accepts a mention-only block — no kind at all', () => {
    const { diagnostics, resolved } = resolvedEntity(ent('semantics { name: customer_name }, ' + MEMBERS));
    expect(diagnostics).toEqual([]);
    const e = resolved as ResolvedEntitySemantics;
    expect(e.kind).toBeUndefined();
    expect(e.measures).toEqual([]);
  });

  // ⛑ The misplaced-keyword branch tests the VALUE against the role roster, so an
  // attribute that happens to be named like a role (`amount`, `version`, `due_date`)
  // would be reported as an attribute key on an entity block. The mention keys have to
  // be recognised before that branch runs.
  it('accepts a mention ref whose target is named like a role', () => {
    const src = ent(
      'semantics { name: version, measures: [amount] }, ' +
        'attributes: [ def attribute version { type: text }, def attribute amount { type: decimal } ]',
    );
    expect(resolvedEntity(src).diagnostics).toEqual([]);
  });

  it('212 — a name: that is not an attribute of THIS entity', () => {
    const d = diagsFor(ent('semantics { name: nonexistent }, ' + MEMBERS));
    expect(d.map((x) => x.code)).toContain(DiagnosticCode.SemMentionRefUnresolved);
  });

  it('212 — a measure belonging to ANOTHER entity does not resolve', () => {
    // The owner-scoped assertion: `other_col` exists in the document, just not here.
    const src =
      'model er\ndef entity Other { attributes: [ def attribute other_col { type: decimal } ] }\n' +
      'def entity E { semantics { measures: [other_col] }, ' + MEMBERS + ' }';
    expect(diagsFor(src).map((x) => x.code)).toContain(DiagnosticCode.SemMentionRefUnresolved);
  });

  it('213 — a measure that is not numeric', () => {
    const d = diagsFor(ent('semantics { measures: [customer_name] }, ' + MEMBERS));
    expect(d.map((x) => x.code)).toContain(DiagnosticCode.SemMeasureNotNumeric);
  });

  it('214 — the same attribute listed twice, in either spelling', () => {
    expect(diagsFor(ent('semantics { measures: [amount_czk, amount_czk] }, ' + MEMBERS)).map((x) => x.code))
      .toContain(DiagnosticCode.SemMeasureDuplicate);
    expect(
      diagsFor(
        ent('semantics { measures: [amount_czk, { attribute: amount_czk, aggregation: avg }] }, ' + MEMBERS),
      ).map((x) => x.code),
    ).toContain(DiagnosticCode.SemMeasureDuplicate);
  });

  it('215 — an aggregation outside the closed vocabulary, with a suggestion', () => {
    const d = diagsFor(ent('semantics { measures: [{ attribute: amount_czk, aggregation: summ }] }, ' + MEMBERS));
    const hit = d.find((x) => x.code === DiagnosticCode.SemBadAggregation);
    expect(hit).toBeDefined();
    expect(hit?.suggestion).toBe('sum');
  });

  it('216 — a measures item that is neither an id nor an {attribute, …} object', () => {
    expect(diagsFor(ent('semantics { measures: [42] }, ' + MEMBERS)).map((x) => x.code))
      .toContain(DiagnosticCode.SemMentionShape);
  });

  it('216 — an unknown key inside a measures item object', () => {
    const d = diagsFor(
      ent('semantics { measures: [{ attribute: amount_czk, aggregate: avg }] }, ' + MEMBERS),
    );
    expect(d.map((x) => x.code)).toContain(DiagnosticCode.SemMentionShape);
  });

  it('216 — measures that is not a list, and a name: that is not an id', () => {
    expect(diagsFor(ent('semantics { measures: amount_czk }, ' + MEMBERS)).map((x) => x.code))
      .toContain(DiagnosticCode.SemMentionShape);
    expect(diagsFor(ent('semantics { name: 7 }, ' + MEMBERS)).map((x) => x.code))
      .toContain(DiagnosticCode.SemMentionShape);
  });

  it('an empty measures list is legal, and equivalent to absent', () => {
    // contracts §1.1: `measures: []` is legal and means the same as not writing it. So it
    // is not an error, and it does not by itself make the block carry a fact worth
    // resolving — an entity that declared nothing gets no ResolvedEntitySemantics, exactly
    // as one with no semantics block at all does.
    const { diagnostics, all } = resolvedEntity(ent('semantics { measures: [] }, ' + MEMBERS));
    expect(diagnostics).toEqual([]);
    expect(all.size).toBe(0);
  });

  it('a block with any mention ERROR degrades — no resolved semantics for it', () => {
    const { all } = resolvedEntity(ent('semantics { name: nonexistent }, ' + MEMBERS));
    expect(all.size).toBe(0);
  });

  // T7 — suggestion coverage for the three new keys.
  it('suggests `measures` for a typo on the entity block', () => {
    const d = diagsFor(ent('semantics { measurse: [amount_czk] }, ' + MEMBERS));
    const hit = d.find((x) => x.code === DiagnosticCode.SemUnknownKey);
    expect(hit?.suggestion).toBe('measures');
  });
});

// LP review-103 (D4) — `code_pattern:`, the code attribute's value regex. Twins of the
// Kotlin suite's `LP D4 — …` cases.
describe('LP D4 — code_pattern on the mention facet', () => {
  it('LP D4 — code_pattern beside code: resolves, as written', () => {
    const { diagnostics, resolved } = resolvedEntity(
      ent('semantics { name: customer_name, code: doc_no, code_pattern: "^[A-P]{16}$" }, ' + MEMBERS),
    );
    expect(diagnostics).toEqual([]);
    const e = resolved as ResolvedEntitySemantics;
    expect(e.code?.path).toBe('doc_no');
    expect(e.codePattern).toBe('^[A-P]{16}$');
  });

  it('LP D4 — a doubled backslash reaches the analyzer as ONE (the parsers\' escape rule)', () => {
    const { diagnostics, resolved } = resolvedEntity(ent('semantics { code: doc_no, code_pattern: "^\\\\d{6}$" }, ' + MEMBERS));
    expect(diagnostics).toEqual([]);
    expect((resolved as ResolvedEntitySemantics).codePattern).toBe('^\\d{6}$');
  });

  it('LP D4 — a Java-only construct is judged by the JVM, and not flagged here', () => {
    // JS has no `(?i)` inline flag; the Kotlin twin compiles it with java.util.regex and
    // accepts it. Flagging it here would be an error that is not one.
    expect(diagsFor(ent('semantics { code: doc_no, code_pattern: "(?i)^[a-z]{2}[0-9]+$" }, ' + MEMBERS))).toEqual([]);
  });

  it('219 — a code_pattern that does not compile, and the block degrades', () => {
    const { diagnostics, all } = resolvedEntity(ent('semantics { code: doc_no, code_pattern: "^[A-P{16}$" }, ' + MEMBERS));
    expect(diagnostics.map((d) => d.code)).toEqual([DiagnosticCode.SemBadCodePattern]);
    expect(diagnostics[0].message).toContain('is not a valid regular expression');
    expect(all.size).toBe(0);
  });

  it('219 — an empty code_pattern', () => {
    expect(codesFor(ent('semantics { code: doc_no, code_pattern: "" }, ' + MEMBERS))).toEqual([DiagnosticCode.SemBadCodePattern]);
  });

  it('219 — a code_pattern with no code: beside it', () => {
    expect(codesFor(ent('semantics { name: customer_name, code_pattern: "^X[0-9]+$" }, ' + MEMBERS))).toEqual([
      DiagnosticCode.SemBadCodePattern,
    ]);
  });

  it('219 does not pile onto a code: that failed to resolve — 212 alone says what is wrong', () => {
    expect(codesFor(ent('semantics { code: nonexistent, code_pattern: "^X[0-9]+$" }, ' + MEMBERS))).toEqual([
      DiagnosticCode.SemMentionRefUnresolved,
    ]);
  });

  it('216 — a code_pattern that is not a single value', () => {
    expect(codesFor(ent('semantics { code: doc_no, code_pattern: [a, b] }, ' + MEMBERS))).toEqual([DiagnosticCode.SemMentionShape]);
  });

  it('code_pattern is an ENTITY key — on an attribute block it is unknown', () => {
    expect(
      codesFor(ent('attributes: [ def attribute p { type: text, semantics { role: period_code, code_pattern: "^[0-9]{6}$" } } ]')),
    ).toContain(DiagnosticCode.SemUnknownKey);
  });
});

// The aggregation-surface firewall (plan risk 4, contracts §1.1 ⚠). Three different
// `aggregation:` surfaces exist — the def-level attribute property (EN-P1.2 derived
// attributes), md's measure property, and the measures-item key. They must not read
// each other.
describe('MS — the three aggregation surfaces stay apart', () => {
  const withDefLevel = (aggProp: string) =>
    ent(
      'semantics { measures: [total] }, ' +
        `attributes: [ def attribute total { type: decimal, aggregation: ${aggProp} } ]`,
    );

  it('a def-level aggregation neither satisfies nor conflicts with the measures default', () => {
    const { diagnostics, resolved } = resolvedEntity(withDefLevel('sum'));
    expect(diagnostics).toEqual([]);
    // 'sum' here is the measures-side DEFAULT for a bare id — not the def property,
    // which says something else entirely ("this attribute is derived by aggregating").
    expect((resolved as ResolvedEntitySemantics).measures[0].aggregation).toBe('sum');
  });

  it('the measures-side value is unaffected by the def-level property changing shape', () => {
    const { diagnostics, resolved } = resolvedEntity(withDefLevel('{ default: avg }'));
    expect(diagnostics).toEqual([]);
    expect((resolved as ResolvedEntitySemantics).measures[0].aggregation).toBe('sum');
  });
});

// contracts §1.2 / MS-D2 — the legacy `nameAttribute:` / `codeAttribute:` matrix.
describe('MS — the D2 legacy mention matrix', () => {
  it('legacy only — the deprecation says what the property still feeds (review-103 F16)', () => {
    const hit = diagsFor(ent('nameAttribute: customer_name, ' + MEMBERS)).find((d) => d.code === DiagnosticCode.SemLegacyMentionDeprecated);
    expect(hit?.message).toBe(
      "'nameAttribute:' is superseded by 'semantics { name: customer_name }' — quoting a value (\"…\") filters the column the semantics block names, and reads 'nameAttribute:' only as a fallback",
    );
  });

  it('legacy only — the deprecation warning, and no mismatch', () => {
    // The "works as today" half of contracts §1.2 row 1 is `EntityDef.nameAttribute`
    // continuing to feed the metadata merge — that is MS-P1·S2's `Source.kt`, not
    // something this layer can assert (there is no semantics block here to resolve).
    const src = ent('nameAttribute: customer_name, ' + MEMBERS);
    const d = diagsFor(src);
    expect(d.map((x) => x.code)).toContain(DiagnosticCode.SemLegacyMentionDeprecated);
    expect(d.map((x) => x.code)).not.toContain(DiagnosticCode.SemLegacyMentionMismatch);
  });

  it('semantics only — clean', () => {
    expect(diagsFor(ent('semantics { name: customer_name }, ' + MEMBERS))).toEqual([]);
  });

  it('both, agreeing — the deprecation warning only', () => {
    const d = diagsFor(ent('nameAttribute: customer_name, semantics { name: customer_name }, ' + MEMBERS));
    expect(d.map((x) => x.code)).toEqual([DiagnosticCode.SemLegacyMentionDeprecated]);
  });

  it('both, disagreeing — ERROR, and the block degrades', () => {
    const src = ent('nameAttribute: doc_no, semantics { name: customer_name }, ' + MEMBERS);
    const { diagnostics, all } = resolvedEntity(src);
    expect(diagnostics.map((x) => x.code)).toContain(DiagnosticCode.SemLegacyMentionMismatch);
    // "A disagreement is always a bug" (MS-D2) — degrade, do not pick a winner.
    expect(all.size).toBe(0);
  });

  it('codeAttribute follows the same matrix', () => {
    expect(diagsFor(ent('codeAttribute: doc_no, ' + MEMBERS)).map((x) => x.code))
      .toContain(DiagnosticCode.SemLegacyMentionDeprecated);
    expect(
      diagsFor(ent('codeAttribute: doc_no, semantics { code: customer_name }, ' + MEMBERS)).map((x) => x.code),
    ).toContain(DiagnosticCode.SemLegacyMentionMismatch);
  });
});

// ---------------------------------------------------------------------------
// review-081 — the six findings, each pinned by the case that was measured wrong
// on `feat/ms-p0-vocabulary-v3` before the fix.
// ---------------------------------------------------------------------------

// F2. Since MS-P0·S1b the parser carries lists and nested objects verbatim, so this layer
// owns the "this key must be scalar" judgement the parser gave up. Without the gate every
// vocabulary lookup stringified the structure — `String(['period_table'])` is
// `'period_table'` — and reported a PERFECTLY VALID member as unknown, sending the author
// to check a spelling that was never wrong. Shape is decided before vocabulary, always.
describe('MS — a structured value is a wrong SHAPE, not an unknown member', () => {
  const only = (src: string) => {
    const d = diagsFor(src);
    return { codes: d.map((x) => x.code), messages: d.map((x) => x.message) };
  };

  it('kind: was reported as an unknown kind naming a valid kind', () => {
    const { codes, messages } = only(ent('semantics { kind: [period_table] }'));
    expect(codes).toEqual([DiagnosticCode.SemMentionShape]);
    expect(messages[0]).toBe("'kind:' takes a single value, not a list");
  });

  it('role: was reported as an unknown role naming a valid role', () => {
    const { codes, messages } = only(
      ent('attributes: [ def attribute a { type: date, semantics { role: [event_date] } } ]'),
    );
    expect(codes).toEqual([DiagnosticCode.SemMentionShape]);
    expect(messages[0]).toBe("'role:' takes a single value, not a list");
  });

  it('period: was reported as a dangling reference', () => {
    const { codes } = only(
      ent('attributes: [ def attribute a { type: date, semantics { role: event_date, period: [P] } } ]'),
    );
    expect(codes).toEqual([DiagnosticCode.SemMentionShape]);
  });

  it('currency: was reported as a dangling reference', () => {
    const { codes } = only(
      ent(
        'attributes: [ def attribute a { type: decimal, semantics { role: amount, currency: { x: 1 } } }, ' +
          'def attribute c { type: text, semantics { role: currency_code } } ]',
      ),
    );
    expect(codes).toEqual([DiagnosticCode.SemMentionShape]);
    expect(only(ent('attributes: [ def attribute a { type: decimal, semantics { role: amount, currency: { x: 1 } } } ]')).messages[0])
      .toBe("'currency:' takes a single value, not an object");
  });

  it('code_format: silently became the yyyyMM default', () => {
    // The worst of the six: no diagnostic at all. The `params` build is only reached once
    // the block is clean, and there `typeof cf === 'string'` fell through to the default.
    const { codes } = only(
      ent('attributes: [ def attribute a { type: text, semantics { role: period_code, code_format: [x] } } ]'),
    );
    expect(codes).toEqual([DiagnosticCode.SemMentionShape]);
  });

  it('aggregation: was reported as an unknown aggregation naming a valid one', () => {
    // MS's own new code doing it, not inherited debt.
    const { codes, messages } = only(
      ent('semantics { measures: [{ attribute: amount_czk, aggregation: [avg] }] }, ' + MEMBERS),
    );
    expect(codes).toEqual([DiagnosticCode.SemMentionShape]);
    expect(messages[0]).toBe("'aggregation:' takes a single value, not a list");
  });

  it('an unknown key whose value stringifies to a role name is not a misplaced keyword', () => {
    // The entity branch's roster test reads the VALUE: `String(['event_date'])` is
    // `'event_date'`, so this used to be reported as an attribute/column key on an
    // entity block rather than as an unknown key.
    const { codes } = only(ent('semantics { whatever: [event_date] }'));
    expect(codes).toEqual([DiagnosticCode.SemUnknownKey]);
  });
});

// F1. `ownerClean = r.clean && legacyMentionOk(...)` short-circuits, so the whole
// contracts §1.2 matrix was switched off for any block carrying an unrelated error —
// including row 4, the MS-D2 "a disagreement is always a bug" ERROR. The author saw a
// diagnostic appear only after fixing something else; the model was wrong the whole time.
describe('MS — the D2 matrix does not depend on the rest of the block validating', () => {
  it('row 4 (mismatch) still fires alongside an unrelated block error', () => {
    const codes = diagsFor(
      ent('nameAttribute: doc_no, semantics { name: customer_name, bogus_key: 1 }, ' + MEMBERS),
    ).map((x) => x.code);
    expect(codes).toContain(DiagnosticCode.SemUnknownKey);
    expect(codes).toContain(DiagnosticCode.SemLegacyMentionMismatch);
  });

  it('rows 1 and 3 (deprecation) still fire alongside an unrelated block error', () => {
    const codes = diagsFor(ent('nameAttribute: customer_name, semantics { kind: nope }, ' + MEMBERS)).map(
      (x) => x.code,
    );
    expect(codes).toContain(DiagnosticCode.SemUnknownKind);
    expect(codes).toContain(DiagnosticCode.SemLegacyMentionDeprecated);
  });
});

// F3. `lastSeg` exists so a self-qualified legacy path agrees with the bare semantics id.
// It also made a path pointing at ANOTHER entity agree — and then advised deleting the
// legacy property, which is destructive advice built on a comparison that could not see
// the difference.
describe('MS — the legacy/semantics comparison is owner-scoped', () => {
  it('a legacy ref qualified to another entity is a mismatch, not a repeat', () => {
    const src =
      'model er\ndef entity Other { attributes: [ def attribute customer_name { type: text } ] }\n' +
      'def entity E { nameAttribute: Other.customer_name, semantics { name: customer_name }, ' + MEMBERS + ' }';
    const codes = diagsFor(src).map((x) => x.code);
    expect(codes).toContain(DiagnosticCode.SemLegacyMentionMismatch);
    expect(codes).not.toContain(DiagnosticCode.SemLegacyMentionDeprecated);
  });

  it('a legacy ref qualified to the owner itself still reads as a repeat', () => {
    const codes = diagsFor(ent('nameAttribute: E.customer_name, semantics { name: customer_name }, ' + MEMBERS)).map(
      (x) => x.code,
    );
    expect(codes).toEqual([DiagnosticCode.SemLegacyMentionDeprecated]);
  });
});

// F6. A duplicate key inside a measures item was silent last-wins, while the same repeat
// one level up is TTR-SEM-203. The parser now records repeats at every depth as a path.
describe('MS — a duplicate key inside a measures item is reported like one on the block', () => {
  it('reports SemDuplicateKey with the path to the repeat', () => {
    const d = diagsFor(ent('semantics { measures: [{ attribute: amount_czk, attribute: quantity }] }, ' + MEMBERS));
    const dup = d.find((x) => x.code === DiagnosticCode.SemDuplicateKey);
    expect(dup).toBeDefined();
    expect(dup?.message).toBe("duplicate semantics key 'measures[0].attribute'");
  });

  it('a block-level repeat is still the bare key', () => {
    const d = diagsFor(ent('semantics { name: customer_name, name: doc_no }, ' + MEMBERS));
    const dup = d.find((x) => x.code === DiagnosticCode.SemDuplicateKey);
    expect(dup?.message).toBe("duplicate semantics key 'name'");
  });
});

// F4. contracts §4 names the SemMisplacedKeyword rewrite as a requirement, and MS-P1·S1
// has to mirror this exact string in the Kotlin analyzer. Pin the text, not just the code.
describe('MS — the misplaced-keyword message names all four entity keys', () => {
  it('204 lists kind, name, code, code_pattern and measures', () => {
    const hit = diagsFor(ent('semantics { code_format: "x" }')).find(
      (x) => x.code === DiagnosticCode.SemMisplacedKeyword,
    );
    expect(hit?.message).toBe(
      "'code_format' is an attribute/column key; entity/table blocks carry 'kind', 'name', 'code', 'code_pattern', 'measures'",
    );
  });
});
