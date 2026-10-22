// SPDX-License-Identifier: Apache-2.0
// 0.17 — the editor's cn hygiene report (CN-P2 S2.7 T3; cn contracts §7 "Hygiene report v2"). The
// TS-computable part of `ttr-concept`'s report (`CnHygiene` / `HygieneRender`): per concept its grade,
// `via`, grounding, the hop column, its E-R landing, core vs open, dangling groundings, redundant term
// duplicates, phase-as-default and retired codes. The definition-level findings are Kotlin's (CN-D29):
// the report's last section names `ttr cn hygiene` for them. One golden pins both
// (`tests/conformance/cn-hygiene/`): this report is `expected.json` less `definitions`, and renders as
// `expected-editor.md`.
//
// The landing mirrors the Kotlin typed tier (ttr-metadata `CnBuilder`, CN-P2 S2.5): an er grounding
// lands directly, an md one through the hop, a db one nowhere, and a defined phase with no grounding
// inherits its bearer's. HP-P1 adds explicit dimensions and structural measures to `ExplicitMdErHop`.

import type { ConceptProperty, IdValue, LexiconEntryDef, Md2ErCubeletDef, ObjectValue, PropertyValue } from '@tatrman/parser';
import { explicitErAttributes, buildMdErRelationIndex, resolveMdNs, resolveMdAttribute, cubeletMeasureKeys } from '../md/explicit.js';
import { buildCanonicalKey } from '../qname.js';
import type { Resolver } from '../resolver.js';
import type { SymbolEntry } from '../symbol-table.js';
import { foldForCollision } from '../lexicon/fold.js';
import type { CnDocument, PlacedGrounding } from './groundings.js';
import type { CnConcept, CnValidation, StatedProperty } from './validate.js';
import { validateCn } from './validate.js';

export type GroundingGrade = 'ungrounded' | 'er-grounded' | 'md-grounded' | 'md-grounded-hop-missing';
export type HopSlug = 'md/er-unmapped' | 'md/er-ambiguous';

/** An md element's E-R landing, or the md/hop slug saying why there is none (the Kotlin `HopResult`). */
export interface HopAnswer {
  erTarget?: string;
  slug?: HopSlug;
  message: string;
}

/** The row of one concept — the JSON of `expected.json`'s `concepts[]`, key for key. */
export interface ConceptHygieneRow {
  concept: string;
  package: string;
  stereotype: string;
  grade: GroundingGrade;
  via: 'direct' | 'md-hop' | null;
  grounding: { layer: 'md' | 'er' | 'db'; binding: string; target: string | null } | null;
  hop: { from: string; to: string | null; slug: string | null } | null;
  landsOn: string | null;
  landingFrom: string | null;
  openEntries: string[];
  relations: string[];
  dangling: Array<{ binding: string; ref: string }>;
  redundantTerms: Array<{ form: string; locale: string | null; conceptEntry: string; element: string; elementEntry: string }>;
  phaseAsDefault: Array<{ entry: string; form: string; alsoNames: string }>;
  retiredCodes: string[];
}

/** The editor's report — `ttr cn hygiene --format json` less its `definitions` (CN-D29). */
export interface CnHygieneReport {
  concepts: ConceptHygieneRow[];
  relationTypes: string[];
  retiredCodes: string;
}

export const RETIRED_CODES_NOTE = 'checked once vocabulary versions register (CN-P4 S4.2) — none yet';

// ---------------------------------------------------------------------------
// Element refs and the hop
// ---------------------------------------------------------------------------

/**
 * The archive's spelling of a symbol (cn contracts §1.2, A-CN-9): its key without the package —
 * `er.entity.<e>`, `er.entity.<e>.<a>`, `md.dimension.<D>.<a>` — and for db without the kind
 * segment (`db.dbo.table.x` → `db.dbo.x`). The Kotlin `elementRefOf`.
 */
export function elementRefOf(sym: SymbolEntry): string {
  const local = sym.packageName && sym.qname.startsWith(`${sym.packageName}.`) ? sym.qname.slice(sym.packageName.length + 1) : sym.qname;
  const segs = local.split('.');
  return segs.length >= 4 && segs[0] === 'db' ? [...segs.slice(0, 2), ...segs.slice(3)].join('.') : local;
}

/** The entity of an er element ref: `er.entity.<e>` itself, or the entity around `er.entity.<e>.<a>`. */
function entityOfElement(ref: string): string | undefined {
  const segs = ref.split('.');
  return segs.length >= 3 && segs[0] === 'er' && segs[1] === 'entity' ? segs.slice(0, 3).join('.') : undefined;
}

function contextOf(doc: CnDocument, enclosingQname?: string) {
  return { schemaCode: doc.schemaCode, namespace: doc.namespace, enclosingQname, imports: doc.ast.imports, packageName: doc.packageName };
}

function resolveIn(resolver: Resolver, doc: CnDocument, path: string, parts: string[], opts: { cn?: boolean; enclosing?: string } = {}): SymbolEntry | undefined {
  const r = resolver.resolveReference({ path, parts, cnContext: opts.cn }, contextOf(doc, opts.enclosing));
  return r.resolved ? r.symbol : undefined;
}

/** Explicit structural mappings. Bearer selection runs first; then a dimension's own binding wins over a cubelet grain mapping of the same attribute. */
export class ExplicitMdErHop {
  private readonly ambiguousPaths = new Set<string>();
  private readonly candidates = new Map<string, Array<{ er: string; from: 'dimension' | 'cubelet' }>>();

  constructor(docs: CnDocument[], resolver: Resolver) {
    const relationIndex = buildMdErRelationIndex(docs, resolver);
    for (const doc of docs) {
      for (const def of doc.ast.definitions) {
        if (def.kind === 'md2erDimension') {
          const entity = resolveIn(resolver, doc, def.entity, def.entity.split('.'));
          const dimension = resolveMdNs(resolver, doc, def.dimensionRef, 'dimension');
          if (entity?.kind !== 'entity' || dimension?.kind !== 'dimension') continue;
          this.add(elementRefOf(dimension), elementRefOf(entity), 'dimension');
          for (const [attr, path] of Object.entries(def.attributes)) {
            const md = resolver.getSymbol(`${dimension.qname}.${attr}`);
            const targets = explicitErAttributes(relationIndex, doc, resolver, entity, path);
            if (md && targets.length > 1) this.ambiguousPaths.add(elementRefOf(md));
            if (md?.kind === 'attribute' && targets.length === 1) this.add(elementRefOf(md), elementRefOf(targets[0]), 'dimension');
          }
          continue;
        }
        if (def.kind !== 'md2erCubelet') continue;
        const d = def as Md2ErCubeletDef;
        const entity = d.entity ? resolveIn(resolver, doc, d.entity, d.entity.split('.')) : undefined;
        if (entity?.kind !== 'entity') continue;
        const cubelet = d.cubeletRef ? resolveMdNs(resolver, doc, d.cubeletRef, 'cubelet') : undefined;
        if (!cubelet) continue;
        this.add(elementRefOf(cubelet), elementRefOf(entity));
        const measures = cubeletMeasureKeys(docs, resolver, cubelet);
        for (const [measure, erAttr] of Object.entries(d.measures ?? {})) {
          const md = resolveMdNs(resolver, doc, measure, 'measure');
          const er = resolver.getSymbol(`${entity.qname}.${erAttr}`);
          if (md?.kind === 'measure' && measures.has(md.qname) && er?.kind === 'attribute') this.add(elementRefOf(md), elementRefOf(er));
        }
        for (const [mdAttr, erAttr] of Object.entries(d.attributes ?? {})) {
          const md = resolveMdAttribute(resolver, doc, mdAttr);
          if (md?.kind !== 'attribute' || md.schemaCode !== 'md') continue;
          const er = resolver.getSymbol(`${entity.qname}.${erAttr}`);
          if (er?.kind !== 'attribute') continue;
          this.add(elementRefOf(md), elementRefOf(er));
        }
      }
    }
  }

  private add(md: string, er: string, from: 'dimension' | 'cubelet' = 'cubelet'): void {
    const list = this.candidates.get(md) ?? [];
    const existing = list.find(c => c.er === er);
    if (!existing) list.push({ er, from });
    else if (from === 'dimension') existing.from = 'dimension';
    this.candidates.set(md, list);
  }

  erTargetOf(mdElement: string, bearerEntity?: string): HopAnswer {
    const found = this.candidates.get(mdElement) ?? [];
    if (found.length === 1) return { erTarget: found[0].er, message: '' };
    if (!found.length && this.ambiguousPaths.has(mdElement)) return { slug: 'md/er-ambiguous', message: `'${mdElement}' has an ambiguous explicit dimension path` };
    if (found.length === 0) return {
      slug: 'md/er-unmapped', message: `'${mdElement}' has no er counterpart — no explicit md2er binding maps it`,
    };
    const ofBearer = found.filter(c => entityOfElement(c.er) === bearerEntity);
    if (bearerEntity !== undefined && ofBearer.length === 1) return { erTarget: ofBearer[0].er, message: '' };
    const own = found.filter(c => c.from === 'dimension');
    if (own.length === 1) return { erTarget: own[0].er, message: '' };
    return { slug: 'md/er-ambiguous', message: `'${mdElement}' maps to ${found.map(c => c.er).join(', ')}` };
  }
}

// ---------------------------------------------------------------------------
// The report
// ---------------------------------------------------------------------------

interface Landing {
  erTarget?: string;
  via?: 'direct' | 'md-hop';
  grade: GroundingGrade;
  from?: string;
  hop?: HopAnswer;
}

interface TermLink {
  name: string;
  locale: string | null;
  forms: string[];
  default?: boolean;
}

/** The stock keys a concept's free-form entries may carry (CN-D7) — every other key is the open tier. */
const STOCK_ENTRY_KEYS: ReadonlySet<string> = new Set([
  'of', 'is_a', 'sources', 'qualities', 'relations', 'hierarchy', 'imprecision', 'valued_state', 'abstract',
]);

const LEXICON_KINDS: ReadonlySet<string> = new Set(['term', 'pattern', 'example']);

function idsOf(value: PropertyValue | undefined): IdValue[] {
  if (!value) return [];
  if (value.kind === 'id') return [value];
  if (value.kind === 'list') return value.items.flatMap((item) => idsOf(item));
  return [];
}

function entryValue(o: ObjectValue, key: string): PropertyValue | undefined {
  return o.entries.find((e) => e.key === key)?.value;
}

function packageOfKey(key: string): string {
  const i = key.indexOf('.cn.concept.');
  return i < 0 ? '' : key.slice(0, i);
}

function nameOfKey(key: string): string {
  return key.slice(key.lastIndexOf('.') + 1);
}

function layerOfBinding(bindingKind: string): 'md' | 'er' | 'db' {
  return bindingKind.startsWith('cn2md_') ? 'md' : bindingKind.startsWith('cn2er_') ? 'er' : 'db';
}

class HygieneBuilder {
  private readonly docs: CnDocument[];
  private readonly docByUri: Map<string, CnDocument>;
  private readonly landings = new Map<string, Landing>();
  private readonly visiting = new Set<string>();
  private readonly conceptTerms = new Map<string, TermLink[]>();
  private readonly elementTerms = new Map<string, TermLink[]>();
  private readonly hop: ExplicitMdErHop;

  constructor(docs: CnDocument[], private readonly resolver: Resolver, private readonly v: CnValidation) {
    this.docs = [...docs].sort((a, b) => (a.uri < b.uri ? -1 : a.uri > b.uri ? 1 : 0));
    this.docByUri = new Map(this.docs.map((d) => [d.uri, d]));
    this.hop = new ExplicitMdErHop(this.docs, resolver);
    this.collectCanonicalTerms();
  }

  report(): CnHygieneReport {
    const concepts = [...this.v.concepts.values()].filter((c) => c.stereotype !== 'relation_type');
    concepts.sort((a, b) => {
      const pa = packageOfKey(a.key);
      const pb = packageOfKey(b.key);
      if (pa !== pb) return pa < pb ? -1 : 1;
      return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
    });
    const relationTypes = [...this.v.concepts.values()]
      .filter((c) => c.stereotype === 'relation_type')
      .map((c) => `cn.${c.name}`)
      .sort();
    return { concepts: concepts.map((c) => this.row(c)), relationTypes, retiredCodes: RETIRED_CODES_NOTE };
  }

  // --- reading a concept --------------------------------------------------

  private first(c: CnConcept, kind: ConceptProperty['kind'], key?: string): StatedProperty | undefined {
    return c.properties.find((s) => s.property.kind === kind && (key === undefined || s.property.key === key));
  }

  /** The concept an `of:` names, by key. */
  private ofKey(c: CnConcept): string | undefined {
    const of = this.first(c, 'entry', 'of');
    if (!of || of.property.kind !== 'entry' || of.property.value.kind !== 'id') return undefined;
    const id = of.property.value;
    const sym = resolveIn(this.resolver, of.doc, id.path, id.parts, { cn: true });
    return sym?.kind === 'concept' ? sym.qname : undefined;
  }

  private groundingOf(c: CnConcept): PlacedGrounding | undefined {
    return this.v.groundings.byConcept.get(c.key)?.[0];
  }

  // --- the landing (the Kotlin `CnBuilder.landingOf`) ---------------------

  private landingOf(key: string): Landing {
    const known = this.landings.get(key);
    if (known) return known;
    if (this.visiting.has(key)) return { grade: 'ungrounded' };
    this.visiting.add(key);
    const result = this.computeLanding(key);
    this.visiting.delete(key);
    this.landings.set(key, result);
    return result;
  }

  private computeLanding(key: string): Landing {
    const c = this.v.concepts.get(key);
    if (!c) return { grade: 'ungrounded' };
    const placed = this.groundingOf(c);
    if (!placed) {
      if (c.stereotype !== 'phase' || !this.first(c, 'definition')) return { grade: 'ungrounded' };
      const bearer = this.ofKey(c);
      if (!bearer) return { grade: 'ungrounded' };
      return { ...this.landingOf(bearer), from: bearer, hop: undefined };
    }
    const target = placed.targetSymbol;
    switch (layerOfBinding(placed.bindingKind!)) {
      case 'er':
        return {
          erTarget: target && (target.kind === 'entity' || target.kind === 'attribute') ? elementRefOf(target) : undefined,
          via: 'direct',
          grade: 'er-grounded',
        };
      case 'md': {
        if (!target) return { via: 'md-hop', grade: 'md-grounded-hop-missing' };
        const bearer = c.stereotype === 'quality' ? this.bearerEntity(c) : undefined;
        const answer = this.hop.erTargetOf(elementRefOf(target), bearer);
        return {
          erTarget: answer.erTarget,
          via: 'md-hop',
          grade: answer.erTarget !== undefined ? 'md-grounded' : 'md-grounded-hop-missing',
          hop: answer,
        };
      }
      default:
        return { grade: 'ungrounded' };
    }
  }

  /** The er entity a quality's bearer lands on — rule 2 of the hop (AM-29). */
  private bearerEntity(c: CnConcept): string | undefined {
    const bearer = this.ofKey(c);
    const er = bearer ? this.landingOf(bearer).erTarget : undefined;
    return er ? entityOfElement(er) : undefined;
  }

  // --- dangling refs (the Kotlin `Grounding.dangling`) --------------------

  private dangling(p: PlacedGrounding): string[] {
    const doc = this.docByUri.get(p.uri);
    if (!doc || !p.bindingKind) return [];
    const out: string[] = [];
    if (!p.targetSymbol) {
      const written = p.target?.path ?? p.targetKey;
      if (written !== undefined) out.push(written);
    }
    const enclosing = p.origin === 'binding'
      ? buildCanonicalKey({ packageName: doc.packageName, kind: p.bindingKind, parts: [p.name] })
      : p.conceptKey;
    const element = (id: IdValue) => {
      const sym = resolveIn(this.resolver, doc, id.path, id.parts, { enclosing });
      if (!sym) out.push(id.path);
      return sym;
    };
    const entry = (key: string): PropertyValue | undefined => {
      const prop = p.properties.find((q) => q.key === key);
      return prop?.kind === 'entry' ? prop.value : undefined;
    };
    const hierarchy = entry('hierarchy');
    if (hierarchy?.kind === 'object') {
      const closureId = entryValue(hierarchy, 'closure');
      const closure = closureId?.kind === 'id' ? resolveIn(this.resolver, doc, closureId.path, closureId.parts, { enclosing }) : undefined;
      if (!closure && closureId?.kind === 'id') out.push(closureId.path);
      for (const key of ['ancestor', 'descendant']) {
        const id = entryValue(hierarchy, key);
        if (id?.kind !== 'id' || !closure) continue;
        if (!this.resolver.getSymbol(`${closure.qname}.${id.path}`)) out.push(`${elementRefOf(closure)}.${id.path}`);
      }
    }
    const participants = entry('participants');
    if (participants?.kind === 'object') {
      for (const e of participants.entries) for (const id of idsOf(e.value)) element(id);
    }
    const period = p.properties.find((q) => q.key === 'period');
    if (period?.kind === 'period') {
      for (const key of ['start', 'end', 'at']) {
        const id = entryValue(period.value, key);
        if (id?.kind === 'id') element(id);
      }
    }
    for (const id of idsOf(entry('via'))) element(id);
    return [...new Set(out)];
  }

  // --- the lexicon --------------------------------------------------------

  private collectCanonicalTerms(): void {
    for (const doc of this.docs) {
      const locale = doc.ast.modelDirective?.locale ?? null;
      for (const def of doc.ast.definitions) {
        if (!LEXICON_KINDS.has(def.kind)) continue;
        const e = def as LexiconEntryDef;
        if (e.kind !== 'term' || !e.target) continue;
        const sym = resolveIn(this.resolver, doc, e.target.path, e.target.parts);
        if (!sym) continue;
        const link: TermLink = { name: e.name, locale, forms: e.forms ?? [], default: e.default };
        const [map, key] = sym.kind === 'concept'
          ? [this.conceptTerms, sym.qname]
          : sym.schemaCode === 'md' || sym.schemaCode === 'er' ? [this.elementTerms, elementRefOf(sym)] : [undefined, ''];
        if (!map) continue;
        const list = map.get(key) ?? [];
        list.push(link);
        map.set(key, list);
      }
    }
  }

  /** A concept's terms: its inline `lexicon { terms }` first (base locale), then its canonical terms. */
  private terms(c: CnConcept): TermLink[] {
    const out: TermLink[] = [];
    const inline = this.first(c, 'lexicon');
    if (inline?.property.kind === 'lexicon') {
      const b = inline.property.block;
      const d = b.object.entries.find((e) => e.key === 'default')?.value;
      if (b.terms && b.terms.length > 0) {
        out.push({ name: c.name, locale: null, forms: b.terms, default: d?.kind === 'bool' ? d.value : undefined });
      }
    }
    return [...out, ...(this.conceptTerms.get(c.key) ?? [])];
  }

  private redundantTerms(c: CnConcept, landing: Landing, target: string | null): ConceptHygieneRow['redundantTerms'] {
    const placed = this.groundingOf(c);
    if (!placed || c.stereotype === 'phase' || landing.from !== undefined || layerOfBinding(placed.bindingKind!) === 'db') return [];
    const elements = [...new Set([target, landing.erTarget ?? null].filter((e): e is string => e !== null))];
    const out: ConceptHygieneRow['redundantTerms'] = [];
    for (const t of this.terms(c)) {
      const seen = new Set<string>();
      for (const form of t.forms) {
        const folded = foldForCollision(form);
        if (seen.has(folded)) continue;
        seen.add(folded);
        for (const element of elements) {
          for (const u of this.elementTerms.get(element) ?? []) {
            if (u.locale !== t.locale || !u.forms.some((f) => foldForCollision(f) === folded)) continue;
            out.push({ form, locale: t.locale, conceptEntry: t.name, element, elementEntry: u.name });
          }
        }
      }
    }
    return out;
  }

  private phaseAsDefault(c: CnConcept): ConceptHygieneRow['phaseAsDefault'] {
    if (c.stereotype !== 'phase') return [];
    const chain: CnConcept[] = [];
    let at = this.nextBearer(c);
    while (at && at.key !== c.key && !chain.some((x) => x.key === at!.key)) {
      chain.push(at);
      at = this.nextBearer(at);
    }
    const out: ConceptHygieneRow['phaseAsDefault'] = [];
    for (const t of this.terms(c)) {
      if (t.default !== true) continue;
      for (const form of t.forms) {
        const folded = foldForCollision(form);
        const bearer = chain.find((b) =>
          this.terms(b).some((u) => u.locale === t.locale && u.forms.some((f) => foldForCollision(f) === folded)),
        );
        if (bearer) {
          out.push({ entry: t.name, form, alsoNames: `cn.${bearer.name}` });
          break;
        }
      }
    }
    return out;
  }

  private nextBearer(c: CnConcept): CnConcept | undefined {
    const key = this.ofKey(c);
    return key ? this.v.concepts.get(key) : undefined;
  }

  // --- one row ------------------------------------------------------------

  private row(c: CnConcept): ConceptHygieneRow {
    const landing = this.landingOf(c.key);
    const placed = this.groundingOf(c);
    const target = placed?.targetSymbol ? elementRefOf(placed.targetSymbol) : null;
    const dangling: ConceptHygieneRow['dangling'] = [];
    const seenBindings = new Set<string>();
    for (const p of this.v.groundings.byConcept.get(c.key) ?? []) {
      const id = `${p.bindingKind}\u0000${p.name}\u0000${p.packageName}`;
      if (seenBindings.has(id)) continue;
      seenBindings.add(id);
      for (const ref of this.dangling(p)) dangling.push({ binding: p.name, ref });
    }
    const openEntries: string[] = [];
    const seenKeys = new Set<string>();
    for (const s of c.properties) {
      if (s.property.kind !== 'entry' || STOCK_ENTRY_KEYS.has(s.property.key) || seenKeys.has(s.property.key)) continue;
      seenKeys.add(s.property.key);
      openEntries.push(s.property.key);
    }
    return {
      concept: `cn.${c.name}`,
      package: packageOfKey(c.key),
      stereotype: c.stereotype,
      grade: landing.grade,
      via: landing.via ?? null,
      grounding: placed ? { layer: layerOfBinding(placed.bindingKind!), binding: placed.bindingKind!, target } : null,
      hop: landing.hop && target !== null ? { from: target, to: landing.hop.erTarget ?? null, slug: landing.hop.slug ?? null } : null,
      landsOn: landing.erTarget ?? null,
      landingFrom: landing.from !== undefined ? `cn.${nameOfKey(landing.from)}` : null,
      openEntries,
      relations: this.relations(c),
      dangling,
      redundantTerms: this.redundantTerms(c, landing, target),
      phaseAsDefault: this.phaseAsDefault(c),
      retiredCodes: [],
    };
  }

  private relations(c: CnConcept): string[] {
    const s = this.first(c, 'entry', 'relations');
    if (s?.property.kind !== 'entry' || s.property.value.kind !== 'list') return [];
    const out: string[] = [];
    for (const item of s.property.value.items) {
      if (item.kind !== 'object') continue;
      const type = entryValue(item, 'type');
      if (type?.kind !== 'id') continue;
      const to = entryValue(item, 'to');
      const toSym = to?.kind === 'id' ? resolveIn(this.resolver, s.doc, to.path, to.parts, { cn: true }) : undefined;
      const toName = toSym?.kind === 'concept' ? `cn.${toSym.name}` : to?.kind === 'id' ? to.path : '?';
      out.push(`${type.path} → ${toName}`);
    }
    return out;
  }
}

/**
 * The editor's hygiene report over a project. `resolver` must be built over the symbol table of
 * exactly these documents; `validation` is `validateCn(docs, resolver)` when the caller has it.
 */
export function cnHygieneReport(docs: CnDocument[], resolver: Resolver, validation?: CnValidation): CnHygieneReport {
  return new HygieneBuilder(docs, resolver, validation ?? validateCn(docs, resolver)).report();
}

// ---------------------------------------------------------------------------
// Rendering — the Kotlin `HygieneRender.body`, line for line, then the editor's definitions note
// ---------------------------------------------------------------------------

const DASH = '—';
const GRADE_ORDER: GroundingGrade[] = ['er-grounded', 'md-grounded', 'md-grounded-hop-missing', 'ungrounded'];

/** What the editor's report says in place of the definition-level findings (CN-D29). */
export const EDITOR_DEFINITIONS_NOTE =
  'Not checked in the editor — `ttr cn hygiene <dir>` reports the definition-level findings (syntax, names, cycles,\n' +
  'reachability, basis, instant comparisons; CN-D29).';

/**
 * Authored text (a name, a ref, a term form, an open-tier entry or relation) escaped for markdown: a character that
 * would render as emphasis, code, a link, an entity or a table-cell break in an editor preview gets a backslash, and a
 * line break is a space (it would end the row or the list item). An underscore inside a word stays
 * (`loyalty_programme`) — CommonMark never reads it as emphasis. Kotlin `HygieneRender.md` is the twin (UTF-16 units,
 * a letter or decimal digit on both sides).
 */
export function mdEscape(s: string): string {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === '\n' || ch === '\r') {
      out += ' ';
      continue;
    }
    const inWord = ch === '_' && i > 0 && i < s.length - 1 && WORD_CHAR.test(s[i - 1]) && WORD_CHAR.test(s[i + 1]);
    if (MD_SPECIAL.includes(ch) && !inWord) out += '\\';
    out += ch;
  }
  return out;
}

const MD_SPECIAL = '\\`*_[]<>|~&';
const WORD_CHAR = /^[\p{L}\p{Nd}]$/u;

export function renderCnHygieneMarkdown(report: CnHygieneReport): string {
  const lines: string[] = [];
  const cs = report.concepts;
  lines.push('# Concept hygiene', '');
  const counts = GRADE_ORDER.flatMap((g) => {
    const n = cs.filter((c) => c.grade === g).length;
    return n > 0 ? [`${n} ${g}`] : [];
  });
  lines.push(`${cs.length} concept${cs.length === 1 ? '' : 's'}${counts.length === 0 ? '' : ` — ${counts.join(' · ')}`}`, '');
  if (cs.length > 0) {
    lines.push('| Concept | Stereotype | Grade | Via | Grounding | Hop | Lands on | Open tier |', '|---|---|---|---|---|---|---|---|');
    for (const c of cs) {
      const cells = [
        mdEscape(c.concept),
        c.stereotype,
        c.grade,
        c.via ?? DASH,
        c.grounding ? (c.grounding.target != null ? mdEscape(c.grounding.target) : `unresolved (${c.grounding.binding})`) : DASH,
        c.hop ? `${mdEscape(c.hop.from)} → ${mdEscape(c.hop.to ?? c.hop.slug ?? '?')}` : DASH,
        (c.landsOn != null ? mdEscape(c.landsOn) : DASH) + (c.landingFrom ? ` (← ${mdEscape(c.landingFrom)})` : ''),
        [...c.openEntries, ...c.relations].map(mdEscape).join(', ') || DASH,
      ];
      lines.push(`| ${cells.join(' | ')} |`);
    }
    lines.push('');
  }
  lines.push(`Open-tier relation types: ${report.relationTypes.map(mdEscape).join(', ') || 'none'}.`);
  const section = (title: string, rows: string[]) => {
    lines.push('', `## ${title}`, '');
    if (rows.length === 0) lines.push('None.');
    for (const r of rows) lines.push(`- ${r}`);
  };
  section('Dangling groundings', cs.flatMap((c) => c.dangling.map((d) => `${mdEscape(c.concept)} — \`${d.binding}\`: ${mdEscape(d.ref)}`)));
  section(
    'Redundant term duplicates',
    cs.flatMap((c) =>
      c.redundantTerms.map((t) => `${mdEscape(c.concept)} — "${mdEscape(t.form)}" (${t.locale ?? 'base'}): term \`${t.conceptEntry}\`, and term \`${t.elementEntry}\` on ${mdEscape(t.element)}`),
    ),
  );
  section(
    'Phase as default sense',
    cs.flatMap((c) =>
      c.phaseAsDefault.map((p) => `${mdEscape(c.concept)} — term \`${p.entry}\` defaults "${mdEscape(p.form)}" to the phase, but "${mdEscape(p.form)}" also names ${mdEscape(p.alsoNames)} up its \`of:\` chain (cn/phase-as-default)`),
    ),
  );
  lines.push('', '## Retired codes', '');
  const retired = cs.flatMap((c) => c.retiredCodes.map((r) => `- ${mdEscape(c.concept)} — ${r}`));
  if (retired.length === 0) lines.push(`None — ${report.retiredCodes.split(' — none')[0]}.`);
  lines.push(...retired);
  lines.push('', '## Definitions', '', EDITOR_DEFINITIONS_NOTE);
  return `${lines.join('\n')}\n`;
}
