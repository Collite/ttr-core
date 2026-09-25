// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import {
  SEMANTICS_VOCABULARY_VERSION,
  ENTITY_KINDS,
  ATTRIBUTE_ROLES,
  KIND_COMPLETENESS,
  ALL_ROLES,
  ALL_ENTITY_KEYS,
  AGGREGATIONS,
  DEFAULT_AGGREGATION,
  isEntitySemantics,
  isAttributeSemantics,
} from '../index.js';
import type { ResolvedEntitySemantics, ResolvedSemantics } from '../index.js';

// Drift guard — these rosters are the cross-repo contract with ai-platform's
// closed proto enums (feature-grounding-contracts.md §4). A failing assertion is
// the reminder that the proto enums + SEMANTICS_VOCABULARY_VERSION move together.
describe('semantics vocabulary (grammar 4.2 / ttr-semantics role roster)', () => {
  // The version pin moved to the v3 block below — one assertion, not one per version.
  it('has exactly the four entity/table kinds', () => {
    expect([...ENTITY_KINDS].sort()).toEqual(['calendar', 'fx_rate', 'period_table', 'poi']);
  });

  it('has exactly the twenty-three attribute/column roles (v2 adds the journal-role family)', () => {
    expect([...ALL_ROLES].sort()).toEqual(
      [
        'amount',
        'amount_domestic',
        'authored_by',
        'calendar_date',
        'currency_code',
        'document_date',
        'due_date',
        'event_date',
        'fx_from_currency',
        'fx_rate',
        'fx_to_currency',
        'geo_lat',
        'geo_lon',
        'geo_point',
        'period_code',
        'period_end',
        'period_start',
        'posting_date',
        'valid_flag',
        'valid_from',
        'valid_to',
        'version',
        'written_at',
      ].sort(),
    );
  });

  it('pins each role type-constraint and extra-key set', () => {
    expect(ATTRIBUTE_ROLES.amount.typeConstraint).toBe('numeric');
    expect(ATTRIBUTE_ROLES.amount.extraKeys.map((k) => k.key)).toEqual(['currency']);
    expect(ATTRIBUTE_ROLES.period_code.typeConstraint).toBe('text');
    expect(ATTRIBUTE_ROLES.period_code.extraKeys.map((k) => k.key)).toEqual(['code_format']);
    expect(ATTRIBUTE_ROLES.event_date.typeConstraint).toBe('date');
    expect(ATTRIBUTE_ROLES.event_date.extraKeys.map((k) => k.key)).toEqual(['period']);
    expect(ATTRIBUTE_ROLES.geo_lat.typeConstraint).toBe('numeric');
    expect(ATTRIBUTE_ROLES.geo_point.typeConstraint).toBe('text');
  });

  it('pins the journal-role family type-constraints (S5C-B.4, R30)', () => {
    expect(ATTRIBUTE_ROLES.version.typeConstraint).toBe('numeric');
    expect(ATTRIBUTE_ROLES.authored_by.typeConstraint).toBe('text');
    expect(ATTRIBUTE_ROLES.written_at.typeConstraint).toBe('date');
    // valid_flag is boolean — no numeric/text/date family, left unconstrained.
    expect(ATTRIBUTE_ROLES.valid_flag.typeConstraint).toBeUndefined();
  });

  it('pins the kind completeness clauses', () => {
    expect(KIND_COMPLETENESS.period_table.map((c) => c.role)).toEqual(['period_start', 'period_end', 'period_code']);
    expect(KIND_COMPLETENESS.calendar.map((c) => c.role)).toEqual(['calendar_date']);
    expect(KIND_COMPLETENESS.fx_rate.map((c) => c.role)).toEqual(['fx_from_currency', 'fx_to_currency', 'fx_rate']);
    // poi is the geo_point XOR lat/lon special case — no flat clauses.
    expect(KIND_COMPLETENESS.poi).toEqual([]);
  });
});

// MS (mention semantics) — vocabulary v3. The mention facet is entity-side, so the
// role table itself gains no members: what it gains is metadata saying which facet and
// family each role belongs to, so a future role must state where it sits.
describe('semantics vocabulary v3 (MS — mention facet)', () => {
  it('is version 3', () => {
    expect(SEMANTICS_VOCABULARY_VERSION).toBe(3);
  });

  it('carries the five entity/table keys (LP D4 added code_pattern), in declaration order', () => {
    // Order is meaningful in the message of SemMisplacedKeyword and in the README table.
    expect([...ALL_ENTITY_KEYS]).toEqual(['kind', 'name', 'code', 'code_pattern', 'measures']);
  });

  it('closes the aggregation vocabulary, defaulting to sum', () => {
    expect([...AGGREGATIONS]).toEqual(['sum', 'avg', 'min', 'max', 'count', 'last']);
    expect(DEFAULT_AGGREGATION).toBe('sum');
  });

  it('gives every role a grounding facet and a known family', () => {
    // Every role that exists today answers "what computation grounds on this column".
    // The mention facet (name/code/measures) is declared entity-side and deliberately
    // adds no role — see design.md §2 on the single-valued `role:` collision.
    const families = ['dates', 'geo', 'finance', 'journal'];
    for (const [role, spec] of Object.entries(ATTRIBUTE_ROLES)) {
      expect(spec.facet, `${role}.facet`).toBe('grounding');
      expect(families, `${role}.family`).toContain(spec.family);
    }
  });

  it('pins the family of one role per family', () => {
    expect(ATTRIBUTE_ROLES.event_date.family).toBe('dates');
    expect(ATTRIBUTE_ROLES.geo_point.family).toBe('geo');
    expect(ATTRIBUTE_ROLES.amount.family).toBe('finance');
    expect(ATTRIBUTE_ROLES.written_at.family).toBe('journal');
  });

  it('assigns every role to its contracts §2 family', () => {
    const byFamily: Record<string, string[]> = { dates: [], geo: [], finance: [], journal: [] };
    for (const [role, spec] of Object.entries(ATTRIBUTE_ROLES)) byFamily[spec.family].push(role);
    expect(byFamily.dates.sort()).toEqual(
      [
        'calendar_date',
        'document_date',
        'due_date',
        'event_date',
        'period_code',
        'period_end',
        'period_start',
        'posting_date',
        'valid_from',
        'valid_to',
      ].sort(),
    );
    expect(byFamily.geo.sort()).toEqual(['geo_lat', 'geo_lon', 'geo_point'].sort());
    expect(byFamily.finance.sort()).toEqual(
      ['amount', 'amount_domestic', 'currency_code', 'fx_from_currency', 'fx_rate', 'fx_to_currency'].sort(),
    );
    expect(byFamily.journal.sort()).toEqual(['authored_by', 'valid_flag', 'version', 'written_at'].sort());
  });

  // MS: adding a role now requires facet+family — this count moves consciously, with
  // the vocabulary version.
  it('still has exactly twenty-three roles', () => {
    expect(Object.keys(ATTRIBUTE_ROLES).length).toBe(23);
  });
});

// MS — the resolved model shape (contracts §3). Shapes only; the validator that
// populates them is MS-P0·S2.
describe('resolved entity semantics v3 (MS)', () => {
  it('carries name, code and ordered measures with a kind-less block', () => {
    // A mention-only block is legal: an entity can declare how people refer to it
    // without being a period table, a calendar, a POI or an fx-rate table.
    const r: ResolvedEntitySemantics = {
      name: { path: 'customer_name' },
      code: { path: 'doc_no' },
      measures: [
        { attribute: { path: 'amount_czk' }, aggregation: 'sum' },
        { attribute: { path: 'quantity' }, aggregation: 'avg' },
      ],
    };
    expect(r.kind).toBeUndefined();
    expect(r.name?.path).toBe('customer_name');
    expect(r.code?.path).toBe('doc_no');
    // Order is the contract: the FIRST measure is the default measure.
    expect(r.measures.map((m) => m.attribute.path)).toEqual(['amount_czk', 'quantity']);
    expect(r.measures.map((m) => m.aggregation)).toEqual(['sum', 'avg']);
  });

  it('treats an empty measures list as "none declared"', () => {
    const r: ResolvedEntitySemantics = { kind: 'period_table', measures: [] };
    expect(r.measures).toEqual([]);
  });

  // ⛑ `kind` going optional breaks the old discriminator, which tested `'kind' in r`.
  // A mention-only block has no `kind`, so it would have been classified as ATTRIBUTE
  // semantics — silently, and only for the estates that use the new feature. `measures`
  // is the total discriminator: contracts §3 makes it always present on the entity shape.
  it('discriminates a kind-less entity block from attribute semantics', () => {
    const mentionOnly: ResolvedSemantics = { name: { path: 'customer_name' }, measures: [] };
    expect(isEntitySemantics(mentionOnly)).toBe(true);
    expect(isAttributeSemantics(mentionOnly)).toBe(false);

    const attr: ResolvedSemantics = { role: 'event_date', refs: {}, params: {} };
    expect(isAttributeSemantics(attr)).toBe(true);
    expect(isEntitySemantics(attr)).toBe(false);
  });
});
