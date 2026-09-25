// SPDX-License-Identifier: Apache-2.0
// Grounding Phase 1 (grammar 4.2) — the typed, validated result of a `semantics`
// block. Populated by the validator ONLY when the block is diagnostics-free for
// that element (degrade-don't-fail: a block with errors leaves the element
// without a ResolvedSemantics but the surrounding model still loads).

import type { EntityKind, AttributeRole, Aggregation } from './vocabulary.js';

/** A resolved cross-reference to another symbol (entity or sibling attribute). */
export interface SymbolRef {
  /** The reference text as written (opaque id, e.g. `AccountingPeriod`). */
  path: string;
  /** The resolved target's canonical qname, when resolution succeeded. */
  qname?: string;
}

/**
 * One declared measure: the attribute that carries the value, and how it aggregates.
 *
 * The aggregation lives HERE, on the measure, and not on the attribute definition — a
 * def-level `aggregation:` already means "this attribute is derived by an aggregation"
 * (EN-P1.2), which is a different claim about a different thing.
 */
export interface MeasureRef {
  /** Resolved against the OWNING entity's attributes (or the table's columns). */
  readonly attribute: SymbolRef;
  /** `'sum'` when the item was written as a bare id. */
  readonly aggregation: Aggregation;
}

/**
 * The resolved `semantics` block on an entity or db table.
 *
 * `kind` is the grounding facet and is now OPTIONAL: v3 lets an entity declare only how
 * humans refer to it (`name`/`code`/`measures`) without claiming to be a period table, a
 * calendar, a POI or an fx-rate table.
 */
export interface ResolvedEntitySemantics {
  readonly kind?: EntityKind;
  /** `name:` → the attribute that carries this entity's human-readable name. */
  readonly name?: SymbolRef;
  /** `code:` → the attribute that carries its business code / identifier. */
  readonly code?: SymbolRef;
  /**
   * `measures:` → the attributes people ask for as VALUES, in declared order. The FIRST
   * is the default measure. Always present: empty means none were declared, and callers
   * never have to distinguish empty from absent.
   */
  readonly measures: ReadonlyArray<MeasureRef>;
  /**
   * LP review-103 (D4) — `code_pattern:` → a regex every value of the `code` attribute
   * matches (`"^[A-P]{16}$"`), so a quoted literal can be recognised as a code even with
   * no digit in it. Only ever set together with `code`. Java-dialect: the JVM compiles it
   * (the lexicon compiler and the resolver), so the JVM analyzer is the authority on
   * whether it is valid.
   */
  readonly codePattern?: string;
}

/** The resolved `semantics` block on an attribute or db column. */
export interface ResolvedAttributeSemantics {
  role: AttributeRole;
  refs: {
    /** `period:` → the period-table entity (event/document/posting/due dates). */
    period?: SymbolRef;
    /** `currency:` → the sibling `currency_code` attribute (on `amount`). */
    currency?: SymbolRef;
  };
  params: {
    /** `code_format:` on `period_code` (default `"yyyyMM"`). */
    codeFormat?: string;
  };
}

/** Either shape, discriminated by the owning symbol's kind. */
export type ResolvedSemantics = ResolvedEntitySemantics | ResolvedAttributeSemantics;

/**
 * ⛑ Discriminated on `measures`, not on `kind`.
 *
 * `kind` was the entity shape's only field, so `'kind' in r` was a total test. In v3 it
 * is optional — a mention-only block has none — and that test would have quietly sorted
 * such a block into the ATTRIBUTE shape, for exactly the estates adopting the new
 * feature. `measures` is always present on the entity shape (contracts §3) and never on
 * the attribute one, so it is the discriminator that stays total.
 */
export function isEntitySemantics(r: ResolvedSemantics): r is ResolvedEntitySemantics {
  return 'measures' in r;
}

export function isAttributeSemantics(r: ResolvedSemantics): r is ResolvedAttributeSemantics {
  return 'role' in r;
}
