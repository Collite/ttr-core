// SPDX-License-Identifier: Apache-2.0
// 0.17 — the model-level `cn/*` checks (CN-P2 S2.3; cn contracts §7, §1.1–§1.6, design `09` §2). One
// project pass over the desugared model (`groundings.ts`): every concept with what it states (a cn
// def's properties, then those of each `concept { }` block that names it), every grounding with its
// concept and target resolved. Each check is reported under its own slug; the lint rules wrap this
// analysis one slug per rule (`@tatrman/lint` `rules/cn.ts`), the way `rules/semantics.ts` wraps
// `analyzeSemantics`. A reference that does not resolve is the generic `ttr/unresolved-reference`'s
// to report — a check here skips it — except where the contracts give the case a slug of its own (an
// undefined relation type, a closure attribute the closure entity lacks).
//
// The definition-level slugs (`cn/definition-*`, `cn/basis-mismatch`, `cn/imprecise-comparison`) are
// Kotlin's (S2.6, CN-D29); `cn/retired-code` needs registered vocabulary versions (P4).

import type {
  ConceptBindingKind,
  ConceptDef,
  ConceptEntry,
  ConceptInvolvesProperty,
  ConceptProperty,
  ConceptStereotype,
  Definition,
  Document,
  EntityDef,
  IdValue,
  ObjectEntry,
  PropertyValue,
  Reference,
  RelationDef,
  SourceLocation,
} from '@tatrman/parser';
import { DiagnosticCode } from '@tatrman/parser';
import { buildCanonicalKey, cnWireName } from '../qname.js';
import type { Resolver } from '../resolver.js';
import type { SymbolEntry } from '../symbol-table.js';
import { desugarLexicon } from '../lexicon/desugar.js';
import { foldForCollision } from '../lexicon/fold.js';
import type { CanonicalLexiconEntry } from '../lexicon/model.js';
import { desugarConcepts, layerOf, type SugarConcept } from './desugar.js';
import {
  analyzeGroundings,
  conceptKeyOf,
  type CnDiagnostic,
  type CnDocument,
  type GroundingAnalysis,
  type PlacedGrounding,
} from './groundings.js';

/** A property a concept states, with the document it is written in (its references resolve there). */
export interface StatedProperty {
  property: ConceptProperty;
  doc: CnDocument;
}

/** A concept as the project states it — its cn def and every `concept { }` block that names it. */
export interface CnConcept {
  key: string;
  name: string;
  /** The cn def's; for a concept only blocks declare, the first block's. */
  stereotype: ConceptStereotype;
  /** Where it is declared: the cn def, else the declaring slot of its first block. */
  uri: string;
  source: SourceLocation;
  def?: ConceptDef;
  /** The blocks that name it, in document and source order. */
  sugars: Array<{ concept: SugarConcept; doc: CnDocument }>;
  /** The def's properties, then each block's past its declaring slot — the first of a key is the one read. */
  properties: StatedProperty[];
}

export interface CnValidation {
  /** Every concept by key, the project's cn defs first, then the ones only blocks declare. */
  concepts: Map<string, CnConcept>;
  groundings: GroundingAnalysis;
  /** Every model-level `cn/*` diagnostic (and the cn `ttr/required-property-missing`), in document and source order. */
  diagnostics: CnDiagnostic[];
}

// ---------------------------------------------------------------------------
// Small readers
// ---------------------------------------------------------------------------

function byPlace(a: { uri: string; source: SourceLocation }, b: { uri: string; source: SourceLocation }): number {
  if (a.uri !== b.uri) return a.uri < b.uri ? -1 : 1;
  return a.source.offsetStart - b.source.offsetStart || a.source.offsetEnd - b.source.offsetEnd;
}

/** The first property a concept states of `kind` (typed) or, for `entry`, under `key`. */
function stated(c: CnConcept, kind: ConceptProperty['kind'], key?: string): StatedProperty | undefined {
  return c.properties.find((s) => s.property.kind === kind && (key === undefined || s.property.key === key));
}

/** The id values of a value that is one id or a list of them. */
function idsOf(value: PropertyValue | undefined): IdValue[] {
  if (!value) return [];
  if (value.kind === 'id') return [value];
  if (value.kind === 'list') return value.items.flatMap((item) => idsOf(item));
  return [];
}

function entryOf(value: PropertyValue | undefined, key: string): ObjectEntry | undefined {
  return value?.kind === 'object' ? value.entries.find((e) => e.key === key) : undefined;
}

function isTrue(s: StatedProperty | undefined): boolean {
  return s?.property.kind === 'entry' && s.property.value.kind === 'bool' && s.property.value.value;
}

function idRef(v: IdValue): Reference {
  return { path: v.path, parts: v.parts, source: v.source };
}

/**
 * `cn/subkind-temporal-definition`'s word rule (review-175 F9), shared with Kotlin and Python: a definition's words are
 * `TTR.g4`'s `IDENT` (`À-ɏ` included). It is temporal when it reads `:as_of` or a `:name` parameter — written, or
 * implied by `within(…)` / `age(…)`, which `ttr-concept`'s reads count as an as-of read and DC-12 lists as a temporal
 * predicate — or a DC-12 word. `period` alone is not temporal (a period bound against a constant is rigid).
 */
const DEFINITION_WORD = /[A-Za-zÀ-ɏ_][A-Za-z0-9_À-ɏ]*/g;
const TEMPORAL_READ = /(?<![A-Za-z0-9_À-ɏ]):[A-Za-zÀ-ɏ_]|(?<![.A-Za-z0-9_À-ɏ])(?:within|age)\s*\(/i;
const TEMPORAL_WORDS: ReadonlySet<string> = new Set(['at_or_before', 'before', 'contains', 'ended_before', 'overlaps']);

/** The last segment of a key — how a message names a concept or an element. */
function shortName(qname: string): string {
  return qname.slice(qname.lastIndexOf('.') + 1);
}

/** The model of a symbol (`md`, `er`, `db`, …) — its key's first segment past the package. */
function modelOf(sym: SymbolEntry): string {
  const rest = sym.packageName ? sym.qname.slice(sym.packageName.length + 1) : sym.qname;
  return rest.split('.')[0];
}

function describe(sym: SymbolEntry): string {
  switch (sym.kind) {
    case 'entity':
      return 'an er entity';
    case 'attribute':
      return modelOf(sym) === 'md' ? 'an md attribute' : 'an er attribute';
    case 'dimension':
      return 'a dimension';
    case 'mdDomain':
      return 'a domain';
    case 'measure':
      return 'a measure';
    case 'cubelet':
      return 'a cubelet';
    case 'relation':
      return 'a relation';
    case 'concept':
      return `a ${sym.stereotype ?? 'concept'}`;
    default:
      return `${modelOf(sym) === 'db' ? 'a db ' : 'a '}${sym.kind}`;
  }
}

function article(stereotype: string): string {
  return /^[aeiou]/.test(stereotype) ? `an ${stereotype}` : `a ${stereotype}`;
}

// ---------------------------------------------------------------------------
// The stereotype × layer table (design `09` §2, cn contracts §1.2)
// ---------------------------------------------------------------------------

/** The stereotype a binding kind grounds — `undefined` for `cn2db_concept`, which grounds any. */
function stereotypeOfBinding(bindingKind: ConceptBindingKind): ConceptStereotype | undefined {
  return bindingKind === 'cn2db_concept' ? undefined : (bindingKind.slice(bindingKind.indexOf('_') + 1) as ConceptStereotype);
}

/** What a binding kind may target, as a predicate and as words. */
const TARGETS: Record<ConceptBindingKind, { ok: (s: SymbolEntry, stacked: ReadonlySet<string>) => boolean; what: string }> = {
  cn2md_kind: { ok: (s) => s.kind === 'dimension' || s.kind === 'mdDomain', what: 'a dimension or a domain' },
  cn2md_role: { ok: (s) => s.kind === 'dimension' || s.kind === 'mdDomain', what: 'a dimension or a domain' },
  cn2md_quality: {
    ok: (s) => s.kind === 'measure' || (s.kind === 'attribute' && modelOf(s) === 'md'),
    what: 'a measure or an md attribute',
  },
  cn2md_event: { ok: (s) => s.kind === 'cubelet', what: 'a cubelet' },
  cn2er_kind: {
    ok: (s) => s.kind === 'entity' || (s.kind === 'attribute' && modelOf(s) === 'er'),
    what: 'an er entity, or an er attribute (a value kind)',
  },
  cn2er_role: { ok: (s) => s.kind === 'entity', what: 'an er entity' },
  cn2er_event: { ok: (s) => s.kind === 'entity', what: 'an er entity' },
  cn2er_quality: { ok: (s) => s.kind === 'attribute' && modelOf(s) === 'er', what: 'an er attribute' },
  cn2er_phase: {
    ok: (s, stacked) => s.kind === 'entity' && stacked.has(s.qname),
    what: 'an er entity stacked on a query (AM-2)',
  },
  cn2db_concept: { ok: (s) => modelOf(s) === 'db', what: 'a db object' },
};

// ---------------------------------------------------------------------------
// The analysis
// ---------------------------------------------------------------------------

class CnValidator {
  private readonly docs: CnDocument[];
  private readonly docByUri: Map<string, CnDocument>;
  private readonly out: CnDiagnostic[] = [];
  readonly concepts = new Map<string, CnConcept>();
  readonly groundings: GroundingAnalysis;
  private readonly stacked: Set<string>;

  constructor(docs: CnDocument[], private readonly resolver: Resolver) {
    this.docs = [...docs].sort((a, b) => (a.uri < b.uri ? -1 : a.uri > b.uri ? 1 : 0));
    this.docByUri = new Map(this.docs.map((d) => [d.uri, d]));
    this.groundings = analyzeGroundings(this.docs, resolver);
    this.out.push(...this.groundings.diagnostics);
    this.collectConcepts();
    this.stacked = this.collectStacked();
  }

  run(): CnDiagnostic[] {
    this.checkGroundingTargets();
    for (const c of this.concepts.values()) {
      this.checkShape(c);
      this.checkInvolves(c);
      this.checkAntirigid(c);
      this.checkRelationTypes(c);
    }
    this.checkThingsInTime();
    this.checkHierarchies();
    this.checkParticipantPaths();
    this.checkSenses();
    return this.out.sort(byPlace);
  }

  private report(
    code: DiagnosticCode,
    severity: CnDiagnostic['severity'],
    uri: string,
    source: SourceLocation,
    message: string,
  ): void {
    this.out.push({ code, severity, uri, source, message });
  }

  // --- resolution ----------------------------------------------------------

  private context(doc: CnDocument, enclosingQname?: string) {
    return {
      schemaCode: doc.schemaCode,
      namespace: doc.namespace,
      enclosingQname,
      imports: doc.ast.imports,
      packageName: doc.packageName,
    };
  }

  /** A reference in a cn slot — among concepts only. */
  private concept(ref: Reference, doc: CnDocument): SymbolEntry | undefined {
    const r = this.resolver.resolveReference({ ...ref, cnContext: true }, this.context(doc));
    return r.resolved && r.symbol.kind === 'concept' ? r.symbol : undefined;
  }

  /** A reference to an md / er / db element. */
  private element(ref: Reference, doc: CnDocument, enclosingQname?: string): SymbolEntry | undefined {
    const r = this.resolver.resolveReference(ref, this.context(doc, enclosingQname));
    return r.resolved ? r.symbol : undefined;
  }

  /** The top-level def a symbol stands for. */
  private defOf(sym: SymbolEntry): Definition | undefined {
    return this.docByUri
      .get(sym.documentUri)
      ?.ast.definitions.find((d) => d.kind === sym.kind && 'name' in d && d.name === sym.name && d.source.offsetStart === sym.source.offsetStart);
  }

  // --- the model -----------------------------------------------------------

  private collectConcepts(): void {
    for (const doc of this.docs) {
      for (const def of doc.ast.definitions) {
        if (def.kind !== 'concept') continue;
        const key = conceptKeyOf(doc.packageName, def.name);
        if (this.concepts.has(key)) continue; // a second cn def of one name is `ttr/duplicate-definition`
        this.concepts.set(key, {
          key,
          name: def.name,
          stereotype: def.stereotype,
          uri: doc.uri,
          source: def.source,
          def,
          sugars: [],
          properties: def.properties.map((property) => ({ property, doc })),
        });
      }
    }
    for (const doc of this.docs) {
      for (const sugar of desugarConcepts(doc.ast, doc.packageName).concepts) {
        const key = conceptKeyOf(doc.packageName, sugar.name);
        let c = this.concepts.get(key);
        if (!c) {
          c = { key, name: sugar.name, stereotype: sugar.stereotype, uri: doc.uri, source: sugar.declaring.source, sugars: [], properties: [] };
          this.concepts.set(key, c);
        }
        c.sugars.push({ concept: sugar, doc });
        for (const property of sugar.body.properties) if (property !== sugar.declaring) c.properties.push({ property, doc });
      }
    }
  }

  /**
   * The entities stacked on a query (the manual's "Entities on queries"): an entity whose inline
   * `binding:` targets `{ query: … }`, or one a `def er2db_entity` with such a target binds.
   */
  private collectStacked(): Set<string> {
    const out = new Set<string>();
    const isQuery = (t: unknown): boolean =>
      !!t && typeof t === 'object' && (t as { kind?: string }).kind === 'object' &&
      (t as { entries: ObjectEntry[] }).entries.some((e) => e.key === 'query');
    for (const doc of this.docs) {
      for (const def of doc.ast.definitions) {
        if (def.kind === 'entity' && def.binding?.kind === 'block' && isQuery(def.binding.target)) {
          out.add(buildCanonicalKey({ packageName: doc.packageName, kind: 'entity', parts: [def.name] }));
        } else if (def.kind === 'er2dbEntity' && def.entity && isQuery(def.target)) {
          const sym = this.element(def.entity, doc);
          if (sym?.kind === 'entity') out.add(sym.qname);
        }
      }
    }
    return out;
  }

  private groundingsOf(c: CnConcept): PlacedGrounding[] {
    return this.groundings.byConcept.get(c.key) ?? [];
  }

  // --- cn/grounding-target-kind · cn/db-grounding --------------------------

  private checkGroundingTargets(): void {
    // A `concept { }` on a measure written inline in a cubelet's `measures:` grounds nothing — the inline
    // measure is no symbol a grounding could name (review-119 M-11): reported, never dropped silently.
    for (const doc of this.docs) {
      for (const def of doc.ast.definitions) {
        if (def.kind !== 'cubelet') continue;
        for (const m of def.measures) {
          if (typeof m === 'string' || !m.concept) continue;
          this.report(DiagnosticCode.CnGroundingTargetKind, 'error', doc.uri, m.concept.source, inlineMeasureSugar(m.name, def.name));
        }
      }
    }
    for (const g of this.groundings.groundings) {
      const name = g.conceptName ?? (g.conceptKey ? shortName(g.conceptKey) : g.conceptRef?.path ?? g.name);
      if (!g.bindingKind) {
        this.report(DiagnosticCode.CnGroundingTargetKind, 'error', g.uri, g.target?.source ?? g.source, this.noBindingKind(g, name));
        continue;
      }
      const expected = stereotypeOfBinding(g.bindingKind);
      if (g.origin === 'binding' && expected && g.conceptRef && g.conceptKey) {
        const st = this.concepts.get(g.conceptKey)?.stereotype ?? this.resolver.getSymbol(g.conceptKey)?.stereotype;
        if (st && st !== expected && !(st === 'subkind' && (expected === 'kind' || expected === 'phase'))) {
          this.report(
            DiagnosticCode.CnGroundingTargetKind,
            'error',
            g.uri,
            g.conceptRef.source,
            `'${g.bindingKind}' grounds ${article(expected)}, but '${name}' is ${article(st)}`,
          );
        }
      }
      if (g.targetSymbol) {
        const t = TARGETS[g.bindingKind];
        if (!t.ok(g.targetSymbol, this.stacked) && !(g.bindingKind === 'cn2er_phase' && g.targetSymbol.kind === 'entity' && (this.concepts.get(g.conceptKey ?? '')?.properties.some((s) => s.property.kind === 'period') || g.properties.some((p) => p.kind === 'period')))) {
          const sym = g.targetSymbol;
          const is = g.bindingKind === 'cn2er_phase' && sym.kind === 'entity' ? 'an entity no query backs' : describe(sym);
          this.report(
            DiagnosticCode.CnGroundingTargetKind,
            'error',
            g.uri,
            g.target?.source ?? g.source,
            `'${g.bindingKind}' grounds '${name}' to ${t.what} — '${sym.name}' is ${is}`,
          );
        }
      }
      if (g.bindingKind === 'cn2db_concept') {
        this.report(
          DiagnosticCode.CnDbGrounding,
          'info',
          g.uri,
          g.source,
          `'${name}' grounds to db — documentation only: no consumer reaches a db grounding (ground it to md or er)`,
        );
      }
    }
  }

  private noBindingKind(g: PlacedGrounding, name: string): string {
    if (g.stereotype === 'relation_type') return `relation type '${name}' is open tier — it is never grounded (cn contracts §1.3)`;
    if (g.target && !layerOf(g.target)) {
      return `'grounds: ${g.target.path}' names no md, er or db element — write the target with its model (\`md.…\`, \`er.entity.…\`, \`db.…\`)`;
    }
    return `phase '${name}' grounds only to an er entity stacked on a query (AM-2), not to md or db`;
  }

  // --- the def-shape slugs -------------------------------------------------

  /** CN-P8 AM-45…49, shared with Kotlin and Python's model-level checks. */
  private checkThingsInTime(): void {
    const prop = (c: CnConcept, key: string): StatedProperty | undefined => {
      const own = c.properties.find((s) => s.property.key === key); if (own) return own;
      const g = this.groundingsOf(c)[0], doc = g && this.docByUri.get(g.uri); if (!g || !doc) return;
      const properties = g.properties, direct = properties.find((p) => p.key === key); if (direct) return { property: direct, doc };
      const bounds = properties.find((p) => p.kind === 'entry' && p.key === 'bounds');
      const value = bounds?.kind === 'entry' && bounds.value.kind === 'object' ? bounds.value.entries.find((e) => e.key === key)?.value : undefined;
      const via = properties.find((p) => p.kind === 'entry' && p.key === 'via');
      const id = value?.kind === 'id' ? value : key === 'from' && c.stereotype === 'quality' && via?.kind === 'entry' && via.value.kind === 'id' ? via.value : undefined;
      if (id) return { property: { kind: key === 'until' ? 'until' : 'from', key, ref: idRef(id), source: id.source }, doc };
    };
    const named = (c: CnConcept, key: string): CnConcept | undefined => {
      const s = prop(c, key); if (!s) return;
      const p = s.property;
      const ref = p.kind === 'entry' && p.value.kind === 'id' ? idRef(p.value) : 'ref' in p ? p.ref : undefined;
      const symbol = ref && this.concept(ref, s.doc); return symbol && this.concepts.get(symbol.qname);
    };
    const chain = (c: CnConcept): Set<string> => {
      const seen = new Set<string>(); let at: CnConcept | undefined = c;
      while (at && !seen.has(at.key)) { seen.add(at.key); at = named(at, 'of') ?? named(at, 'is_a'); }
      return seen;
    };
    const participates = (c: CnConcept, bearer: CnConcept): boolean => [...chain(c)].some((key) => {
      const s = prop(this.concepts.get(key)!, 'involves'); if (s?.property.kind !== 'involves') return false;
      return s.property.participants.some((pt) => { const sym = this.concept(pt.ref, s.doc); const got = sym && this.concepts.get(sym.qname); return got && (chain(got).has(bearer.key) || chain(bearer).has(got.key)); });
    });
    const emit = (c: CnConcept, code: DiagnosticCode, message: string, warning = false, key?: string) => {
      const s = key ? prop(c, key) : undefined;
      this.report(code, warning ? 'warning' : 'error', s?.doc.uri ?? c.uri, s?.property.source ?? c.source, message);
    };
    const temporal = (c: CnConcept, visiting: Set<string>): boolean => {
      if (visiting.has(c.key)) return false; visiting.add(c.key);
      if (c.stereotype === 'phase' || isTrue(prop(c, 'valued_state'))) return true;
      const p = prop(c, 'definition')?.property ?? prop(c, 'members')?.property;
      if (!p || !('block' in p) || !('text' in p.block)) return false;
      const text = p.block.text.replace(/'(''|[^'])*'|"(\\.|[^"])*"/g, ' ');
      if (TEMPORAL_READ.test(text)) return true;
      const words = new Set(text.match(DEFINITION_WORD));
      if ([...words].some((w) => TEMPORAL_WORDS.has(w.toLowerCase()))) return true;
      return [...this.concepts.values()].some((x) => x.key !== c.key && words.has(x.name) && temporal(x, visiting));
    };
    for (const c of this.concepts.values()) {
      if (c.stereotype === 'subkind' && temporal(c, new Set())) emit(c, DiagnosticCode.CnSubkindTemporalDefinition, `subkind '${c.name}' has a temporal or parametric definition`, false, prop(c, 'definition') ? 'definition' : 'members');
      if (c.stereotype === 'kind' && prop(c, 'members') && prop(c, 'is_a')) emit(c, DiagnosticCode.CnSubkindAsKind, `kind '${c.name}' with members and is_a is a subkind — use 'def subkind'`, true);
      if (c.stereotype === 'role' && named(c, 'event') && named(c, 'event')!.stereotype !== 'event') emit(c, DiagnosticCode.CnRoleNoEvent, `role '${c.name}' requires an event as its defining event`, false, 'event');
      if (c.stereotype === 'subkind' && named(c, 'of') && !['kind', 'subkind', 'role', 'event'].includes(named(c, 'of')!.stereotype)) emit(c, DiagnosticCode.CnSubkindBearer, `subkind '${c.name}' requires a rigid bearer — a kind, subkind, role or event, not a ${named(c, 'of')!.stereotype}`, false, 'of');
      const ep = prop(c, 'extent')?.property; const extent = ep?.kind === 'extent' ? ep.value : undefined;
      if (ep) emit(c, DiagnosticCode.CnExtentRetired, 'extent is retired — events are instantaneous; kinds and phases carry intervals', true, 'extent');
      const pp = prop(c, 'period')?.property; const period = pp?.kind === 'period' ? pp.value.entries.map((e) => e.key) : undefined;
      if (c.stereotype === 'event' && (extent === 'durative' || period?.some((k) => k === 'start' || k === 'end'))) emit(c, DiagnosticCode.CnDurativeEvent, `event '${c.name}' is durative — model a kind with a lifetime or a phase`, false, ep ? 'extent' : 'period');
      if (['kind', 'role', 'subkind', 'phase'].includes(c.stereotype) && period && (period.includes('at') || !period.includes('start') || !period.includes('end'))) emit(c, DiagnosticCode.CnLifetimePeriod, `'${c.name}' needs a start/end interval`, false, 'period');
      const from = !!prop(c, 'from'), until = !!prop(c, 'until');
      if (c.stereotype === 'phase') {
        const sources = [!!prop(c, 'definition'), this.groundingsOf(c).some((g) => !!g.target || !!g.targetKey), from || until].filter(Boolean).length;
        if (sources > 1 || from !== until) emit(c, DiagnosticCode.CnPhaseSourceConflict, `phase '${c.name}' needs exactly one source: definition, grounding, or both from and until`);
        const bearer = named(c, 'of');
        if (from && until && bearer) for (const key of ['from', 'until']) { const event = named(c, key); if (!event || event.stereotype !== 'event' || !participates(event, bearer)) emit(c, DiagnosticCode.CnPhaseBoundsParticipant, `phase '${c.name}' ${key} bound must be an event involving '${bearer.name}'`, false, key); }
        if (period && bearer) {
          const target = this.groundingsOf(c)[0]?.targetSymbol;
          const subject = [...chain(bearer)].map((key) => this.directErEntity(key)).find(Boolean);
          if (target && subject) {
            const paths = this.docs.flatMap((d) => d.ast.definitions.filter((r): r is RelationDef => r.kind === 'relation').flatMap((r) => { const ref: Reference = { path: `er.relation.${r.name}`, parts: ['er', 'relation', r.name], source: r.source }; const sym = this.element(ref, d); const ends = sym && this.endsOf(sym); return ends ? [ends] : []; }));
            if (!paths.some(([a, b]) => a === target.qname && b === subject)) emit(c, DiagnosticCode.CnEpisodeSubjectMissing, `episode phase '${c.name}' has no relation to its subject '${bearer.name}'`);
          }
        }
      }
      if (c.stereotype === 'quality' && (from || prop(c, 'per'))) {
        const s = prop(c, 'from'); const ref = s?.property.kind === 'from' ? s.property.ref : undefined;
        const parts = ref?.parts.slice(0, -1) ?? []; const sym = parts.length && s ? this.concept({ path: parts.join('.'), parts, source: ref!.source }, s.doc) : undefined;
        const source = sym && this.concepts.get(sym.qname), bearer = named(c, 'of'), per = named(c, 'per');
        const quality = [...this.concepts.values()].find((q) => q.name === ref?.parts.at(-1) && q.stereotype === 'quality' && named(q, 'of')?.key === source?.key);
        if (!isTrue(prop(c, 'valued_state')) || !source || !['kind', 'subkind', 'role'].includes(source.stereotype) || !bearer || !participates(source, bearer) || !quality || (!!prop(c, 'per') && (!per || !participates(source, per)))) emit(c, DiagnosticCode.CnProjectionSource, `quality '${c.name}' needs a valued-state projection of a relator quality involving its bearer and per participant`);
        else {
          // ⚑C (review-175 F6): one row per bearer — every other participant of the relator is the `per:` one.
          const covers = (got: CnConcept, by: CnConcept | undefined) => !!by && (chain(got).has(by.key) || chain(by).has(got.key));
          const uncovered = [...chain(source)].flatMap((key) => {
            const s = prop(this.concepts.get(key)!, 'involves'); if (s?.property.kind !== 'involves') return [];
            return s.property.participants.flatMap((pt) => { const sym = this.concept(pt.ref, s.doc); const got = sym && this.concepts.get(sym.qname); return got && !covers(got, bearer) && !covers(got, per) ? [got.name] : []; });
          });
          if (uncovered.length) emit(c, DiagnosticCode.CnProjectionSource, `quality '${c.name}' projects '${source.name}', whose participant '${uncovered[0]}' is neither its bearer nor its per: — one '${bearer.name}' reads several rows; add 'per: ${uncovered[0]}'`);
        }
      }
    }
    const groups = new Map<string, Array<{c: CnConcept; bearer: CnConcept; attr: string; value: string}>>();
    for (const c of this.concepts.values()) {
      if (c.stereotype !== 'subkind') continue;
      const p = prop(c, 'definition')?.property; const match = p?.kind === 'definition' ? /^\s*(?:this\.)?([A-Za-z\u00C0-\u024F_][A-Za-z0-9_\u00C0-\u024F]*)\s*=\s*'([^']*)'\s*$/.exec(p.block.text) : null;
      const bearer = named(c, 'of'); if (!match || !bearer) continue;
      const key = `${bearer.key}|${match[1]}`; const group = groups.get(key) ?? []; group.push({c, bearer, attr: match[1], value: match[2]}); groups.set(key, group);
    }
    for (const group of groups.values()) {
      const values = new Map<string, CnConcept>();
      for (const p of group) { const prior = values.get(p.value); if (prior) emit(p.c, DiagnosticCode.CnPartitionOverlap, `subkind '${p.c.name}' overlaps '${prior.name}' on '${p.attr}'`, false, 'definition'); else values.set(p.value, p.c); }
      const first = group[0]; const quality = [...this.concepts.values()].find((q) => q.name === first.attr && named(q, 'of')?.key === first.bearer.key);
      const space = quality && named(quality, 'value_space'), target = space && this.groundingsOf(space)[0]?.targetSymbol;
      if (!target) continue;
      const attribute = this.docs.flatMap((d) => d.ast.definitions.filter((x): x is EntityDef => x.kind === 'entity').flatMap((x) => x.attributes ?? [])).find((x) => x.source.file === target.source.file && x.source.offsetStart === target.source.offsetStart);
      const missing = (attribute?.valueLabels?.entries.map((e) => e.key) ?? []).filter((v) => !values.has(v));
      if (missing.length) emit(first.c, DiagnosticCode.CnPartitionIncomplete, `subkind partition on '${first.attr}' misses ${missing.sort().join(', ')}`, true);
    }
  }

  private checkShape(c: CnConcept): void {
    const grounded = this.groundingsOf(c).length > 0;
    const definition = stated(c, 'definition');
    const abstract = isTrue(stated(c, 'entry', 'abstract'));
    switch (c.stereotype) {
      case 'role':
        if (!c.properties.some((s) => s.property.kind === 'ref' && s.property.key === 'event')) {
          this.report(DiagnosticCode.CnRoleNoEvent, 'error', c.uri, c.source, `role '${c.name}' names no defining event — add 'event: <Event>' (CN-D6)`);
        }
        break;
      case 'quality':
      case 'phase':
      case 'subkind':
        if (!stated(c, 'entry', 'of')) {
          this.report(DiagnosticCode.RequiredPropertyMissing, 'error', c.uri, c.source, `${c.stereotype} '${c.name}' names no bearer — 'of:' is required`);
        }
        break;
      default:
        break;
    }
    if (c.stereotype === 'phase') {
      const first = this.groundingsOf(c)[0];
      if (definition && first) {
        this.report(
          DiagnosticCode.CnPhaseDoubleDefinition,
          'error',
          first.uri,
          first.source,
          `phase '${c.name}' has a 'definition:' and a grounding — a phase is defined, or grounded to a stacked entity, never both (AM-2)`,
        );
      }
      if (!definition && !grounded && !abstract && !stated(c, 'from')) {
        this.report(
          DiagnosticCode.CnPhaseNoDefinition,
          'info',
          c.uri,
          c.source,
          `phase '${c.name}' has neither a 'definition:' nor a grounding — no consumer can tell its members (mark a superconcept 'abstract: true')`,
        );
      }
    }
    if (c.stereotype === 'event' && !stated(c, 'period')) {
      this.report(DiagnosticCode.CnEventNoPeriod, 'info', c.uri, c.source, `event '${c.name}' has no 'period:' — no consumer can place it in time`);
    }
    const exempt =
      c.stereotype === 'relation_type' || c.stereotype === 'phase' || c.stereotype === 'subkind' || !!stated(c, 'from') || !!definition || !!stated(c, 'members') || abstract;
    if (!grounded && !exempt) {
      this.report(DiagnosticCode.CnUngrounded, 'info', c.uri, c.source, `${c.stereotype} '${c.name}' is not grounded yet — no consumer reaches it (P-1, eventually grounded)`);
    }
  }

  // --- cn/involves-unlabelled-duplicate -----------------------------------

  private checkInvolves(c: CnConcept): void {
    if (!['event', 'kind', 'subkind', 'role'].includes(c.stereotype)) return;
    const involves = stated(c, 'involves');
    if (!involves) return;
    const seen = new Set<string>();
    for (const p of (involves.property as ConceptInvolvesProperty).participants) {
      const label = p.label ?? p.ref.parts[p.ref.parts.length - 1].toLowerCase();
      if (!seen.has(label)) {
        seen.add(label);
        continue;
      }
      this.report(
        DiagnosticCode.CnInvolvesUnlabelledDuplicate,
        'error',
        involves.doc.uri,
        p.source,
        `'${c.name}' involves a second participant under the label '${label}' — label each one ('${p.ref.path} as <label>')`,
      );
    }
  }

  // --- cn/antirigid-supertype ---------------------------------------------

  private checkAntirigid(c: CnConcept): void {
    if (c.stereotype !== 'kind') return;
    const isA = stated(c, 'entry', 'is_a');
    if (!isA) return;
    const seen = new Set<string>([c.key]);
    // Breadth-first up the chain; `via` keeps the direct supertype each one was reached through.
    const queue: Array<{ key: string; via?: string }> = [];
    const push = (s: StatedProperty, via?: string) => {
      for (const id of idsOf((s.property as ConceptEntry).value)) {
        const sym = this.concept(idRef(id), s.doc);
        if (sym && !seen.has(sym.qname)) {
          seen.add(sym.qname);
          queue.push({ key: sym.qname, via: via ?? sym.name });
        }
      }
    };
    push(isA);
    while (queue.length > 0) {
      const { key, via } = queue.shift()!;
      const sup = this.concepts.get(key);
      if (!sup) continue;
      if (sup.stereotype === 'role' || sup.stereotype === 'phase') {
        const through = via && via !== sup.name ? ` (through '${via}')` : '';
        this.report(
          DiagnosticCode.CnAntirigidSupertype,
          'warning',
          isA.doc.uri,
          isA.property.source,
          `kind '${c.name}' specializes ${sup.stereotype} '${sup.name}'${through} — a kind is rigid and cannot be a subtype of an anti-rigid ${sup.stereotype} (OntoUML); make '${c.name}' ${article(sup.stereotype)}`,
        );
        return;
      }
      const next = stated(sup, 'entry', 'is_a');
      if (next) push(next, via);
    }
  }

  // --- cn/unknown-relation-type -------------------------------------------

  private checkRelationTypes(c: CnConcept): void {
    const relations = stated(c, 'entry', 'relations');
    if (!relations || relations.property.kind !== 'entry' || relations.property.value.kind !== 'list') return;
    for (const item of relations.property.value.items) {
      for (const id of idsOf(entryOf(item, 'type')?.value)) {
        const sym = this.concept(idRef(id), relations.doc);
        if (sym?.stereotype === 'relation_type') continue;
        this.report(
          DiagnosticCode.CnUnknownRelationType,
          'error',
          relations.doc.uri,
          id.source,
          sym
            ? `'${id.path}' is ${article(sym.stereotype ?? 'concept')}, not a relation type — an open-tier edge's 'type:' names a 'def relation_type'`
            : `'${id.path}' is no relation type — declare 'def relation_type ${id.parts[id.parts.length - 1]}'`,
        );
      }
    }
  }

  // --- cn/hierarchy-shape --------------------------------------------------

  private checkHierarchies(): void {
    const seen = new Set<ConceptProperty>();
    const all: StatedProperty[] = [];
    for (const g of this.groundings.groundings) {
      const doc = this.docByUri.get(g.uri);
      if (!doc) continue;
      for (const property of g.properties) all.push({ property, doc });
    }
    for (const c of this.concepts.values()) all.push(...c.properties);
    for (const s of all) {
      if (s.property.kind !== 'entry' || s.property.key !== 'hierarchy' || seen.has(s.property)) continue;
      seen.add(s.property);
      this.checkHierarchy(s.property, s.doc);
    }
  }

  private checkHierarchy(h: ConceptEntry, doc: CnDocument): void {
    const closureId = idsOf(entryOf(h.value, 'closure')?.value)[0];
    if (!closureId) {
      this.report(DiagnosticCode.CnHierarchyShape, 'error', doc.uri, h.source, `'hierarchy:' names no 'closure:' entity`);
      return;
    }
    const closure = this.element(idRef(closureId), doc);
    if (!closure) return; // `ttr/unresolved-reference`
    if (closure.kind !== 'entity') {
      this.report(DiagnosticCode.CnHierarchyShape, 'error', doc.uri, closureId.source, `the hierarchy closure '${closureId.path}' is ${describe(closure)} — it must be an er entity`);
      return;
    }
    const def = this.defOf(closure) as EntityDef | undefined;
    if (!def) return;
    const attributes = new Map((def.attributes ?? []).map((a) => [a.name, a]));
    for (const key of ['ancestor', 'descendant']) {
      const id = idsOf(entryOf(h.value, key)?.value)[0];
      if (!id) {
        this.report(DiagnosticCode.CnHierarchyShape, 'error', doc.uri, h.source, `'hierarchy:' names no '${key}:' attribute of '${closure.name}'`);
      } else if (!attributes.has(id.path)) {
        this.report(DiagnosticCode.CnHierarchyShape, 'error', doc.uri, id.source, `the hierarchy closure '${closure.name}' has no attribute '${id.path}' (its '${key}:')`);
      }
    }
    if (![...attributes.values()].some((a) => a.isKey)) {
      this.report(DiagnosticCode.CnHierarchyShape, 'error', doc.uri, closureId.source, `the hierarchy closure '${closure.name}' has no key attribute`);
    }
  }

  // --- cn/participant-path-broken -----------------------------------------

  /** A relation's two entities, resolved in the relation's own document. */
  private endsOf(rel: SymbolEntry): [string, string] | undefined {
    const def = this.defOf(rel) as RelationDef | undefined;
    const doc = this.docByUri.get(rel.documentUri);
    if (!def || !doc) return undefined;
    const end = (v: PropertyValue | undefined) => {
      const id = idsOf(v)[0];
      return id ? this.element(idRef(id), doc)?.qname : undefined;
    };
    const from = end(def.from);
    const to = end(def.to);
    return from && to ? [from, to] : undefined;
  }

  /** The entity a participant concept's own grounding lands on directly in er; `undefined` when it has none. */
  private directErEntity(conceptKey: string): string | undefined {
    const g = this.groundings.byConcept.get(conceptKey)?.[0];
    const t = g?.targetSymbol;
    if (!g?.bindingKind?.startsWith('cn2er_') || !t) return undefined;
    if (t.kind === 'entity') return t.qname;
    return t.kind === 'attribute' ? t.parent : undefined;
  }

  private checkParticipantPaths(): void {
    for (const g of this.groundings.groundings) {
      if (!['cn2er_event', 'cn2er_kind', 'cn2er_role', 'cn2er_phase'].includes(g.bindingKind ?? '') || g.targetSymbol?.kind !== 'entity') continue;
      const participants = g.properties.find((p) => p.kind === 'entry' && p.key === 'participants');
      const doc = this.docByUri.get(g.uri);
      if (participants?.kind !== 'entry' || participants.value.kind !== 'object' || !doc) continue;
      const event = g.conceptKey ? this.concepts.get(g.conceptKey) : undefined;
      const involves = event ? stated(event, 'involves') : undefined;
      const enclosing = buildCanonicalKey({ packageName: doc.packageName, kind: g.bindingKind!, parts: [g.name] });
      for (const entry of participants.value.entries) {
        const path = idsOf(entry.value);
        const rels = path.map((id) => this.element(idRef(id), doc, enclosing));
        if (path.length === 0 || rels.some((r) => !r)) continue; // `ttr/unresolved-reference`
        let at = g.targetSymbol.qname;
        let broken = false;
        for (let i = 0; i < path.length && !broken; i++) {
          const rel = rels[i]!;
          const ends = rel.kind === 'relation' ? this.endsOf(rel) : undefined;
          if (rel.kind !== 'relation') {
            this.report(DiagnosticCode.CnParticipantPathBroken, 'error', g.uri, path[i].source, `'${path[i].path}' in the path of '${entry.key}' is ${describe(rel)}, not a relation`);
            broken = true;
          } else if (!ends) {
            broken = true; // a relation whose ends do not resolve: `ttr/unresolved-reference` on the relation
          } else if (ends[0] !== at && ends[1] !== at) {
            this.report(
              DiagnosticCode.CnParticipantPathBroken,
              'error',
              g.uri,
              path[i].source,
              i === 0
                ? `the path of '${entry.key}' starts at '${rel.name}', which does not touch the event's entity '${shortName(at)}'`
                : `the path of '${entry.key}' breaks at '${rel.name}' — it does not continue from '${shortName(at)}'`,
            );
            broken = true;
          } else {
            at = ends[0] === at ? ends[1] : ends[0];
          }
        }
        if (broken || involves?.property.kind !== 'involves') continue;
        const participant = involves.property.participants.find(
          (p) => (p.label ?? p.ref.parts[p.ref.parts.length - 1].toLowerCase()) === entry.key,
        );
        const concept = participant ? this.concept(participant.ref, involves.doc) : undefined;
        const lands = concept ? this.directErEntity(concept.qname) : undefined;
        if (lands && lands !== at) {
          this.report(
            DiagnosticCode.CnParticipantPathBroken,
            'error',
            g.uri,
            path[path.length - 1].source,
            `the path of '${entry.key}' ends on '${shortName(at)}', but '${concept!.name}' is grounded to '${shortName(lands)}'`,
          );
        }
      }
    }
  }

  // --- the lexicon pair (outside the portable subset) ---------------------

  private checkSenses(): void {
    interface Sense {
      entry: CanonicalLexiconEntry;
      doc: CnDocument;
      target: string;
      concept?: SymbolEntry;
    }
    const senses: Sense[] = [];
    for (const doc of this.docs) {
      for (const entry of desugarLexicon(doc.ast).entries) {
        if (entry.entryKind !== 'term' || (entry.origin !== 'canonical' && entry.origin !== 'inline') || !entry.target) continue;
        const sym = this.element(entry.targetRef ?? { path: entry.target, parts: entry.target.split('.'), source: entry.source }, doc);
        senses.push({ entry, doc, target: sym?.qname ?? entry.target, concept: sym?.kind === 'concept' ? sym : undefined });
      }
    }
    senses.sort((a, b) => byPlace({ uri: a.doc.uri, source: a.entry.source }, { uri: b.doc.uri, source: b.entry.source }));
    const keyOf = (s: Sense, form: string) => `${s.entry.locale ?? ''}\u0000${foldForCollision(form)}`;
    const byForm = new Map<string, Sense[]>();
    for (const s of senses) {
      for (const form of new Set(s.entry.forms ?? [])) {
        const list = byForm.get(keyOf(s, form)) ?? [];
        list.push(s);
        byForm.set(keyOf(s, form), list);
      }
    }
    const wire = (target: string) => cnWireName(target) ?? target;

    // cn/duplicate-default-sense — one default per bare form per locale.
    const reported = new Set<Sense>();
    for (const [key, list] of byForm) {
      const defaults = list.filter((s) => s.entry.default === true);
      const first = defaults[0];
      for (const s of defaults.slice(1)) {
        if (s.target === first.target || reported.has(s)) continue;
        reported.add(s);
        const form = key.slice(key.indexOf('\u0000') + 1);
        const locale = s.entry.locale ? ` (locale ${s.entry.locale})` : '';
        this.report(
          DiagnosticCode.CnDuplicateDefaultSense,
          'error',
          s.doc.uri,
          s.entry.source,
          `'${form}' is already the default sense of ${wire(first.target)} (${first.doc.uri}:${first.entry.source.line})${locale} — one default per bare form per locale`,
        );
      }
    }

    // cn/phase-as-default — a bare term never defaults to a phase (AM-36).
    for (const s of senses) {
      if (s.entry.default !== true || s.concept?.stereotype !== 'phase') continue;
      const chain = this.bearers(s.concept.qname);
      const hit = (s.entry.forms ?? [])
        .flatMap((form) => (byForm.get(keyOf(s, form)) ?? []).map((o) => ({ form, o })))
        .find(({ o }) => o !== s && chain.has(o.target));
      if (!hit) continue;
      this.report(
        DiagnosticCode.CnPhaseAsDefault,
        'warning',
        s.doc.uri,
        s.entry.source,
        `'${hit.form}' also names ${wire(hit.o.target)}, up the 'of:' chain of phase ${wire(s.target)} — a bare term never defaults to a phase; the resolver ignores this default (AM-36)`,
      );
    }
  }

  /** The concepts up a concept's `of:` chain (its bearer, the bearer's bearer, …), itself excluded. */
  private bearers(key: string): Set<string> {
    const out = new Set<string>();
    let at = this.concepts.get(key);
    while (at) {
      const of = stated(at, 'entry', 'of');
      const id = of ? idsOf((of.property as ConceptEntry).value)[0] : undefined;
      const sym = id && of ? this.concept(idRef(id), of.doc) : undefined;
      if (!sym || sym.qname === key || out.has(sym.qname)) break;
      out.add(sym.qname);
      at = this.concepts.get(sym.qname);
    }
    return out;
  }
}

/**
 * The model-level cn checks over a project. `resolver` must be built over the symbol table of exactly
 * these documents (the desugared concepts resolve through it).
 */
/** The message for a `concept { }` on a cubelet's inline measure (review-119 M-11; the Kotlin and Python twins say the same). */
export function inlineMeasureSugar(measure: string, cubelet: string): string {
  return `a 'concept { }' on the measure '${measure}' written inline in cubelet '${cubelet}' grounds nothing — declare it with 'def measure ${measure}' and list it in 'measures:'`;
}

export function validateCn(docs: CnDocument[], resolver: Resolver): CnValidation {
  const v = new CnValidator(docs, resolver);
  const diagnostics = v.run();
  return { concepts: v.concepts, groundings: v.groundings, diagnostics };
}

/**
 * `cn/cnc-deprecated` (cn contracts §1.6) — `model cnc` and an entity's `roles:` parse through the
 * window, warned. Per document; never run on the stock vocabulary (only on a project's documents).
 */
export function cncDeprecations(ast: Document, uri: string): CnDiagnostic[] {
  const out: CnDiagnostic[] = [];
  if (ast.modelDirective?.modelCode === 'cnc') {
    out.push({
      code: DiagnosticCode.CnCncDeprecated,
      severity: 'warning',
      uri,
      source: ast.modelDirective.source,
      message: "'model cnc' is deprecated — the cnc roles are superseded by the conceptual model ('model cn'); nothing to migrate to (CN-D18)",
    });
  }
  for (const def of ast.definitions) {
    if (def.kind !== 'entity' || !def.roles?.length) continue;
    out.push({
      code: DiagnosticCode.CnCncDeprecated,
      severity: 'warning',
      uri,
      source: def.source,
      message: `'roles:' on entity '${def.name}' is deprecated — the cnc roles are superseded by the conceptual model ('model cn'); nothing to migrate to (CN-D18)`,
    });
  }
  return out;

}
