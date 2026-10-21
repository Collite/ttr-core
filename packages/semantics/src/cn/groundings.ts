// SPDX-License-Identifier: Apache-2.0
// 0.17 — one grounding per concept (CN-P2 S2.2; cn contracts §1.2, §1.4; CN-D12 strict, CN-D21).
// Over a project's canonical groundings (`desugar.ts`): resolve the concept each authored binding
// names and the element each grounding targets, group by concept, and report the second and later
// groundings of a concept — and a `concept { }` block that restates an authored concept differently
// — as `cn/duplicate-grounding`. The first grounding in document and source order is the one read
// (the `ttr/duplicate-property` "first is read" rule). The lint rule wraps this in S2.3.

import type { ConceptDef, ConceptProperty, Document, ImportDecl, SourceLocation } from '@tatrman/parser';
import { DiagnosticCode } from '@tatrman/parser';
import { buildCanonicalKey } from '../qname.js';
import type { Resolver } from '../resolver.js';
import type { SymbolEntry } from '../symbol-table.js';
import { desugarConcepts, type CnGrounding, type SugarConcept } from './desugar.js';

/** A project document, as the symbol table was built from it. */
export interface CnDocument {
  uri: string;
  ast: Document;
  /** The effective package. */
  packageName: string;
  schemaCode: string;
  namespace: string;
}

/** A grounding placed in its document, its concept and target resolved. */
export interface PlacedGrounding extends CnGrounding {
  uri: string;
  packageName: string;
  /** The grounded concept's key; `undefined` when an authored binding's concept slot does not resolve. */
  conceptKey?: string;
  /** The target element; `undefined` when the target does not resolve. */
  targetSymbol?: SymbolEntry;
}

export interface CnDiagnostic {
  code: DiagnosticCode;
  message: string;
  /** The slug's grade (cn contracts §7): hygiene and advisory ones are `info`; lint config decides what is shown. */
  severity: 'error' | 'warning' | 'info';
  uri: string;
  source: SourceLocation;
}

export interface GroundingAnalysis {
  /** Every grounding, in document and source order. */
  groundings: PlacedGrounding[];
  /** Per concept key, its groundings in document and source order — the first is the one read. */
  byConcept: Map<string, PlacedGrounding[]>;
  diagnostics: CnDiagnostic[];
}

/** The key of the concept `name` declared in `packageName`: `<pkg>.cn.concept.<name>`. */
export function conceptKeyOf(packageName: string, name: string): string {
  return buildCanonicalKey({ packageName, kind: 'concept', parts: [name] });
}

function byPlace(a: { uri: string; source: SourceLocation }, b: { uri: string; source: SourceLocation }): number {
  if (a.uri !== b.uri) return a.uri < b.uri ? -1 : 1;
  return a.source.offsetStart - b.source.offsetStart;
}

function lineOf(g: { uri: string; source: SourceLocation }): string {
  return `${g.uri}:${g.source.line}`;
}

/** Keys a structural fingerprint ignores — where a thing was written, not what it says. */
const PLACE_KEYS: ReadonlySet<string> = new Set([
  'source', 'leadingTrivia', 'trailingTrivia', 'textSource', 'lineOffsets', 'indentWidth', 'escapes', 'parts',
]);

/**
 * A property's meaning as a string: its value with every source position dropped, object keys and an
 * object value's entries sorted by key (`{ start:, end: }` = `{ end:, start: }`); a concept ref's
 * optional `cn.` head is dropped (`Order` and `cn.Order` say the same). A `lexicon { }` block is read
 * by its five known keys (terms, patterns, examples, default, sense_priority) — the canonical form the
 * Kotlin and Python twins share (review-119 M-10).
 */
export function propertyFingerprint(p: ConceptProperty): string {
  if (p.kind === 'lexicon') {
    const entry = (key: string) => p.block.object.entries.find((e) => e.key === key)?.value;
    const d = entry('default');
    const s = entry('sense_priority');
    return JSON.stringify([
      'lexicon',
      p.key,
      {
        terms: p.block.terms ?? [],
        patterns: p.block.patterns ?? [],
        examples: p.block.examples ?? [],
        default: d?.kind === 'bool' ? d.value : null,
        sense_priority: s?.kind === 'number' ? s.value : null,
      },
    ]);
  }
  const norm = (v: unknown): unknown => {
    if (Array.isArray(v)) return v.map(norm);
    if (v && typeof v === 'object') {
      const node = v as { kind?: unknown; entries?: unknown };
      if (node.kind === 'object' && Array.isArray(node.entries)) {
        const entries = [...(node.entries as Array<{ key: string }>)].sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
        return { kind: 'object', entries: entries.map(norm) };
      }
      const out: Record<string, unknown> = {};
      for (const k of Object.keys(v).sort()) {
        if (PLACE_KEYS.has(k)) continue;
        const x = (v as Record<string, unknown>)[k];
        out[k] = k === 'path' && typeof x === 'string' && x.startsWith('cn.') ? x.slice(3) : norm(x);
      }
      return out;
    }
    return v;
  };
  return JSON.stringify(norm(p));
}

/**
 * The sugar merge rule (S2.2 T3, cn contracts §1.4): a `concept { }` block may name a concept an
 * authored cn def declares when it adds only the grounding and properties the def leaves unset. A
 * different stereotype, or a property both state with different values, is `cn/duplicate-grounding`
 * at the block's slot; a second grounding is counted with the others.
 */
function mergeConflicts(sugar: SugarConcept, uri: string, def: ConceptDef, defUri: string): CnDiagnostic[] {
  const out: CnDiagnostic[] = [];
  const at = `${defUri}:${def.source.line}`;
  if (sugar.stereotype !== def.stereotype) {
    out.push({
      code: DiagnosticCode.CnDuplicateGrounding,
      message: `'concept { ${sugar.declaring.key}: ${sugar.name} }' declares a ${sugar.stereotype}, but '${sugar.name}' is a ${def.stereotype} (${at})`,
      severity: 'error',
      uri,
      source: sugar.declaring.source,
    });
    return out;
  }
  for (const p of sugar.body.properties) {
    if (p === sugar.declaring) continue;
    const theirs = def.properties.find((q) => q.key === p.key);
    if (!theirs || propertyFingerprint(theirs) === propertyFingerprint(p)) continue;
    out.push({
      code: DiagnosticCode.CnDuplicateGrounding,
      message: `'${p.key}' of '${sugar.name}' differs from its concept def (${at}) — a 'concept { }' block may add only what the def leaves unset`,
      severity: 'error',
      uri,
      source: p.source,
    });
  }
  return out;
}

/**
 * Resolve, group and check the groundings of a project. `resolver` must be built over the symbol
 * table of exactly these documents (the desugared concepts resolve through it).
 */
export function analyzeGroundings(docs: CnDocument[], resolver: Resolver): GroundingAnalysis {
  const groundings: PlacedGrounding[] = [];
  const diagnostics: CnDiagnostic[] = [];
  const sugars: Array<{ concept: SugarConcept; uri: string; key: string }> = [];
  const authored = new Map<string, { def: ConceptDef; uri: string }>();

  for (const doc of docs) {
    const context = (imports: ImportDecl[], enclosingQname?: string) => ({
      schemaCode: doc.schemaCode,
      namespace: doc.namespace,
      enclosingQname,
      imports,
      packageName: doc.packageName,
    });
    for (const def of doc.ast.definitions) {
      if (def.kind !== 'concept') continue;
      const key = conceptKeyOf(doc.packageName, def.name);
      if (!authored.has(key)) authored.set(key, { def, uri: doc.uri });
    }
    const { concepts, groundings: own } = desugarConcepts(doc.ast, doc.packageName);
    for (const c of concepts) sugars.push({ concept: c, uri: doc.uri, key: conceptKeyOf(doc.packageName, c.name) });
    for (const g of own) {
      const placed: PlacedGrounding = { ...g, uri: doc.uri, packageName: doc.packageName };
      if (g.conceptName !== undefined) {
        placed.conceptKey = conceptKeyOf(doc.packageName, g.conceptName);
      } else if (g.conceptRef) {
        const r = resolver.resolveReference({ ...g.conceptRef, cnContext: true }, context(doc.ast.imports));
        if (r.resolved) placed.conceptKey = r.symbol.qname;
      }
      if (g.targetKey !== undefined) {
        placed.targetSymbol = resolver.getSymbol(g.targetKey);
      } else if (g.target) {
        const enclosing = g.origin === 'binding'
          ? buildCanonicalKey({ packageName: doc.packageName, kind: g.bindingKind ?? 'conceptBinding', parts: [g.name] })
          : placed.conceptKey;
        const r = resolver.resolveReference(g.target, context(doc.ast.imports, enclosing));
        if (r.resolved) placed.targetSymbol = r.symbol;
      }
      groundings.push(placed);
    }
  }
  groundings.sort(byPlace);

  const byConcept = new Map<string, PlacedGrounding[]>();
  for (const g of groundings) {
    if (!g.bindingKind || g.conceptKey === undefined) continue;
    const list = byConcept.get(g.conceptKey) ?? [];
    list.push(g);
    byConcept.set(g.conceptKey, list);
  }
  for (const [key, list] of byConcept) {
    for (const extra of list.slice(1)) {
      const name = key.slice(key.lastIndexOf('.') + 1);
      diagnostics.push({
        code: DiagnosticCode.CnDuplicateGrounding,
        message: `'${name}' is already grounded at ${lineOf(list[0])} — one grounding per concept (CN-D12)`,
        severity: 'error',
        uri: extra.uri,
        source: extra.source,
      });
    }
  }

  for (const s of sugars) {
    const def = authored.get(s.key);
    if (def) diagnostics.push(...mergeConflicts(s.concept, s.uri, def.def, def.uri));
  }
  diagnostics.sort(byPlace);

  return { groundings, byConcept, diagnostics };
}
