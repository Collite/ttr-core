// SPDX-License-Identifier: Apache-2.0
// The resolution rule (VL contracts §4, V-15, V-16) — how a layout's refs find the model's cards.
// Every reader resolves the same way: the viewer (here, against the whole-schema graph it holds), the
// catalog (Kotlin `LayoutResolver`, against its registry snapshot) and any third-party writer of the
// format (its own implementation, tested on the shared vectors' `resolve` expectations).
//
// A ref resolves against the cards of the layout's schema, in order; the first step that matches
// decides, and a step that matches more than one card is AMBIGUOUS (never guessed, P2):
//   1. `<pkg>.<qname>`   the exact full qname              (`hartland.er.entity.item`)
//   2. `<qname>`         the catalog's qname, any package   (`er.entity.item`, `db.dbo.item`)
//   3. `<kind>.<name>`   kind + name, any package           (`entity.item`, `table.item`)
//   4. `<name>`          the bare name, any package         (`item`)
// No card at any step ⇒ the ref names a relation / foreign key (the same four forms against the
// schema's edges, or a qname whose kind segment says so) and is IGNORED (V-1), else it is MISSING.

import { compareCodePoints } from './layout-document.js';

/** One model object as resolution sees it. `fullQname` = `pkg` + `.` + `qname` (`fullQname()` format). */
export interface LayoutIndexEntry {
  fullQname: string;
  /** the catalog's qname: `<schema>.<namespace>.<name>` (`er.entity.item`, `db.dbo.item`) */
  qname: string;
  /** `entity`, `table`, `view` for cards; `relation`, `foreign_key` for edges */
  kind: string;
  /** the leaf identifier (`item`) */
  name: string;
  pkg: string;
  /** the schema code (`er`, `db`) */
  schema: string;
}

export interface LayoutModelIndex {
  /** the objects a layout shows: entities (er), tables and views (db) */
  cards: readonly LayoutIndexEntry[];
  /** relations and foreign keys — a ref naming one is ignored, not missing (V-1) */
  edges?: readonly LayoutIndexEntry[];
}

export interface Ambiguity {
  ref: string;
  /** the full qnames the deciding step matched, sorted */
  candidates: string[];
}

export interface Resolution {
  /** ref → full qname, in code-point order of the ref */
  resolved: Map<string, string>;
  missing: string[];
  ambiguous: Ambiguity[];
  ignored: string[];
}

/** A qname whose kind segment (the one before the name) is one of these names an edge, not a card. */
export const EDGE_KIND_SEGMENTS: ReadonlySet<string> = new Set(['relation', 'fk', 'foreign_key']);

const STEPS: ReadonlyArray<(e: LayoutIndexEntry) => string> = [
  (e) => e.fullQname,
  (e) => e.qname,
  (e) => `${e.kind}.${e.name}`,
  (e) => e.name,
];

/** The first step whose form of some entry equals `ref` decides; its distinct full qnames, sorted. */
function match(ref: string, entries: readonly LayoutIndexEntry[]): string[] {
  for (const form of STEPS) {
    const hits = new Set(entries.filter((e) => form(e) === ref).map((e) => e.fullQname));
    if (hits.size > 0) return [...hits].sort(compareCodePoints);
  }
  return [];
}

function namesAnEdgeKind(ref: string): boolean {
  const parts = ref.split('.');
  return parts.length >= 2 && EDGE_KIND_SEGMENTS.has(parts[parts.length - 2]);
}

/** Resolve `refs` against the cards (and edges) of `model` in `index` — §4, steps 1–4. */
export function resolveRefs(refs: readonly string[], index: LayoutModelIndex, model: string): Resolution {
  const cards = index.cards.filter((e) => e.schema === model);
  const edges = (index.edges ?? []).filter((e) => e.schema === model);
  const out: Resolution = { resolved: new Map(), missing: [], ambiguous: [], ignored: [] };
  for (const ref of [...new Set(refs)].sort(compareCodePoints)) {
    const hits = match(ref, cards);
    if (hits.length === 1) out.resolved.set(ref, hits[0]);
    else if (hits.length > 1) out.ambiguous.push({ ref, candidates: hits });
    else if (match(ref, edges).length > 0 || namesAnEdgeKind(ref)) out.ignored.push(ref);
    else out.missing.push(ref);
  }
  return out;
}

/** The JSON form of a resolution — the shared vectors' `expect.json` (keys and lists sorted). */
export function resolutionToJson(r: Resolution): {
  resolved: Record<string, string>;
  missing: string[];
  ambiguous: Ambiguity[];
  ignored: string[];
} {
  const resolved: Record<string, string> = {};
  for (const ref of [...r.resolved.keys()].sort(compareCodePoints)) resolved[ref] = r.resolved.get(ref)!;
  return {
    resolved,
    missing: [...r.missing].sort(compareCodePoints),
    ambiguous: [...r.ambiguous].sort((a, b) => compareCodePoints(a.ref, b.ref)),
    ignored: [...r.ignored].sort(compareCodePoints),
  };
}
