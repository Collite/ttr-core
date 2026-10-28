// SPDX-License-Identifier: Apache-2.0
import type {
  CrossRef,
  ConceptBody,
  ConceptRefKey,
  ConceptSugar,
  Definition,
  Document,
  IdValue,
  ImportDecl,
  ObjectEntry,
  PropertyValue,
  Reference,
  SourceLocation,
} from '@tatrman/parser';

/** Preserve the MD slot when the generic reference index consumes a cross-reference. */
export interface MdSlotReference extends Reference {
  mdContext: CrossRef['role'];
}

function mdRef(ref: CrossRef): MdSlotReference {
  return { path: ref.path, parts: ref.path.split('.'), source: ref.source, mdContext: ref.role };
}

/**
 * 0.17 — a reference written in a cn slot (cn contracts §1.1–§1.4): `is_a`, `of`, a role's `event`,
 * `involves`, `value_space`, `relations[].type` / `.to`, a binding's stereotype slot (`kind: cn.Item`)
 * and its `participants:` keys, every slot of the `concept { }` sugar but the one that declares. It
 * resolves among concepts only, `cn` the implied model head (`Resolver.resolveReference`).
 *
 * `soft` marks a bearer-local name — a concept's `qualities:` item or `period:` value: one that names
 * a declared concept resolves to it; any other is an implicit quality of the bearer, never an
 * unresolved reference.
 */
export interface CnSlotReference extends Reference {
  cnContext: true;
  soft?: true;
}

export function isCnContextRef(ref: Reference): ref is CnSlotReference {
  return (ref as Partial<CnSlotReference>).cnContext === true;
}

/**
 * Returns all explicit `Reference` AST nodes attached to a definition.
 *
 * Covers every property whose declared type is `Reference` on any def kind:
 *   - EntityDef.nameAttribute, EntityDef.codeAttribute
 *   - Er2dbEntityDef.entity, Er2dbAttributeDef.attribute
 *   - Er2dbRelationDef.relation, Er2dbRelationDef.fk
 *   - Er2cncRoleDef.entity, Er2cncRoleDef.role
 *
 * `IdValue` occurrences inside `from`/`to`/`join` on relation/fk defs are
 * also returned, wrapped as synthetic References (parser exposes those as
 * generic PropertyValue, but they behave like references for navigation).
 */
export function collectReferences(def: Definition): Reference[] {
  const refs: Reference[] = [];

  switch (def.kind) {
    case 'entity':
      if (def.nameAttribute) refs.push(def.nameAttribute);
      if (def.codeAttribute) refs.push(def.codeAttribute);
      break;
    case 'md2erDimension':
      if (def.dimensionReference) refs.push(def.dimensionReference);
      if (def.entityReference) refs.push(def.entityReference);
      break;
    case 'er2dbEntity':
      if (def.entity) refs.push(def.entity);
      break;
    case 'er2dbAttribute':
      if (def.attribute) refs.push(def.attribute);
      break;
    case 'er2dbRelation':
      if (def.relation) refs.push(def.relation);
      if (def.fk) refs.push(def.fk);
      break;
    case 'er2cncRole':
      if (def.entity) refs.push(def.entity);
      if (def.role) refs.push(def.role);
      break;
    case 'relation':
      pushIdValueAsReference(def.from, refs);
      pushIdValueAsReference(def.to, refs);
      break;
    case 'fk':
      pushIdValueAsReference(def.from, refs);
      pushIdValueAsReference(def.to, refs);
      break;
    // v3.1 MD — span-carrying cross-references collected by the walker.
    case 'attribute':
    case 'dimension':
    case 'mdMap':
    case 'hierarchy':
    case 'measure':
    case 'cubelet':
      for (const c of def.crossRefs ?? []) {
        refs.push(mdRef(c));
      }
      break;
    // v4.4 lexicon entries — the `for:` target ref (er/db/md) resolves through the
    // standard path, giving goto-def + unresolved-reference for free.
    case 'term':
    case 'pattern':
    case 'example':
      if (def.target) refs.push(def.target);
      break;
    // 0.17 — the cn model: a concept def's and a binding's slots.
    case 'concept':
      collectConceptBodyRefs(def, 'def', refs, def.stereotype);
      break;
    case 'conceptBinding':
      collectConceptBodyRefs(def, 'binding', refs);
      break;
    default:
      break;
  }

  // 0.17 — the `concept { }` sugar on an md / er host.
  const sugar = conceptSugarOf(def);
  if (sugar) collectConceptBodyRefs(sugar, 'sugar', refs);

  return refs;
}

/** The `concept { }` sugar of a host def — a cubelet, dimension, domain, measure, entity or attribute. */
export function conceptSugarOf(def: Definition): ConceptSugar | undefined {
  switch (def.kind) {
    case 'entity':
    case 'attribute':
    case 'dimension':
    case 'mdDomain':
    case 'measure':
    case 'cubelet':
      return def.concept;
    default:
      return undefined;
  }
}

/**
 * The slot of a `concept { }` sugar that declares the concept — the stereotype slot. A role sugar
 * also names its defining event (`{ role: Customer, event: Order }`), so `role:` wins; otherwise the
 * first stereotype ref. Every other slot refers.
 */
export function sugarStereotypeKey(body: ConceptBody): ConceptRefKey | undefined {
  let first: ConceptRefKey | undefined;
  for (const p of body.properties) {
    if (p.kind !== 'ref') continue;
    if (p.key === 'role') return 'role';
    first ??= p.key;
  }
  return first;
}

function cnRef(ref: Reference, soft = false): CnSlotReference {
  const { path, parts, source } = ref;
  return soft ? { path, parts, source, cnContext: true, soft: true } : { path, parts, source, cnContext: true };
}

/**
 * `ListPrice` of `ListPrice.amount` — the reference to a relator whose quality a projection reads. Its span covers the
 * head only, so a rename of the relator rewrites `ListPrice` and keeps `.amount` (a span with whitespace inside keeps
 * the whole span).
 */
function headRef(ref: Reference): Reference {
  const parts = ref.parts.slice(0, -1);
  const path = parts.join('.');
  const s = ref.source;
  const tight = s.offsetEnd - s.offsetStart === ref.path.length && s.line === s.endLine;
  const source = tight ? { ...s, endColumn: s.column + path.length, offsetEnd: s.offsetStart + path.length } : s;
  return { path, parts, source };
}

function idRef(v: IdValue): Reference {
  return { path: v.path, parts: v.parts, source: v.source };
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

/** The span of an object entry's key alone (`customer` in `customer: [...]`). */
function keySource(entry: ObjectEntry): SourceLocation {
  const s = entry.source;
  return { ...s, endLine: s.line, endColumn: s.column + entry.key.length, offsetEnd: s.offsetStart + entry.key.length };
}

/** `period:` names its instants through `start` / `end` / `at`; `end_open` and `precision` are enums. */
const PERIOD_INSTANTS: ReadonlySet<string> = new Set(['start', 'end', 'at']);

/** A binding's `period:` may also name a stored upper bound — `start_hi` / `at_hi` (⚑CN-P10, ruled 2026-10-22). */
const BINDING_PERIOD_INSTANTS: ReadonlySet<string> = new Set([...PERIOD_INSTANTS, 'start_hi', 'at_hi']);

/**
 * The references of one cn body, in authored order. `mode` says whose body it is: a concept def
 * (`period:` names the bearer's qualities), a binding (`period:` names er attributes, `participants:`
 * keys name the event's labels) or the sugar (its stereotype slot declares, so it is no reference).
 * Groundings and binding targets name an md / er / db element and resolve the generic way;
 * `hierarchy:`'s `ancestor` / `descendant` are read relative to its `closure` entity.
 */
function collectConceptBodyRefs(body: ConceptBody, mode: 'def' | 'binding' | 'sugar', out: Reference[], stereotype?: string): void {
  const declaring = mode === 'sugar' ? sugarStereotypeKey(body) : undefined;
  const quality = (stereotype ?? declaring) === 'quality';
  const eventRef = mode === 'binding'
    ? body.properties.find((p) => p.kind === 'ref' && p.key === 'event')
    : undefined;
  for (const p of body.properties) {
    switch (p.kind) {
      case 'ref':
        if (p.key !== declaring) out.push(cnRef(p.ref));
        break;
      case 'from':
        // A quality's `from: ListPrice.amount` names its relator (the quality is the relator's, checked by
        // `cn/projection-source`); a phase's `from:` names its starting event, dotted or not (review-175 F11).
        out.push(cnRef(quality && p.ref.parts.length > 1 ? headRef(p.ref) : p.ref));
        break;
      case 'until':
      case 'per':
        out.push(cnRef(p.ref));
        break;
      case 'grounds':
        out.push(p.ref);
        break;
      case 'valueSpace':
        out.push(cnRef(p.ref));
        break;
      case 'involves':
        for (const participant of p.participants) out.push(cnRef(participant.ref));
        break;
      case 'period':
        for (const e of p.value.entries) {
          if (!(mode === 'binding' ? BINDING_PERIOD_INSTANTS : PERIOD_INSTANTS).has(e.key)) continue;
          for (const id of idsOf(e.value)) out.push(mode === 'binding' ? idRef(id) : cnRef(idRef(id), true));
        }
        break;
      case 'target':
        if ('kind' in p.value) pushIdValueAsReference(p.value, out);
        else out.push(p.value);
        break;
      case 'entry':
        switch (p.key) {
          case 'is_a':
          case 'of':
            for (const id of idsOf(p.value)) out.push(cnRef(idRef(id)));
            break;
          case 'qualities':
            for (const id of idsOf(p.value)) out.push(cnRef(idRef(id), true));
            break;
          case 'relations':
            if (p.value.kind === 'list') {
              for (const item of p.value.items) {
                for (const key of ['type', 'to']) {
                  for (const id of idsOf(entryOf(item, key)?.value)) out.push(cnRef(idRef(id)));
                }
              }
            }
            break;
          case 'hierarchy': {
            const closure = idsOf(entryOf(p.value, 'closure')?.value)[0];
            if (!closure) break;
            out.push(idRef(closure));
            for (const key of ['ancestor', 'descendant']) {
              for (const id of idsOf(entryOf(p.value, key)?.value)) {
                out.push({ path: `${closure.path}.${id.path}`, parts: [...closure.parts, ...id.parts], source: id.source });
              }
            }
            break;
          }
          case 'participants':
            if (mode !== 'binding' || p.value.kind !== 'object') break;
            for (const e of p.value.entries) {
              if (eventRef?.kind === 'ref') {
                const ev = eventRef.ref;
                out.push(cnRef({ path: `${ev.path}.${e.key}`, parts: [...ev.parts, e.key], source: keySource(e) }));
              }
              for (const id of idsOf(e.value)) out.push(idRef(id));
            }
            break;
          case 'episodes':
            for (const id of idsOf(p.value)) out.push(idRef(id));
            break;
          case 'bounds':
            for (const key of ['from', 'until']) for (const id of idsOf(entryOf(p.value, key)?.value)) out.push(cnRef(idRef(id)));
            break;
          case 'via':
            for (const id of idsOf(p.value)) {
              if (mode === 'binding' && body.properties.some((p) => p.kind === 'ref' && p.key === 'quality') && !['er', 'md', 'db'].includes(id.parts[0])) {
                out.push(cnRef(headRef(idRef(id))));
              } else out.push(idRef(id));
            }
            break;
          default:
            break;
        }
        break;
      default:
        break;
    }
  }
}

function pushIdValueAsReference(
  value: PropertyValue | undefined,
  out: Reference[]
): void {
  if (!value) return;
  if (value.kind === 'id') {
    out.push({ path: value.path, parts: value.parts, source: value.source });
  } else if (value.kind === 'list') {
    for (const item of value.items) {
      pushIdValueAsReference(item, out);
    }
  } else if (value.kind === 'object') {
    for (const entry of value.entries) {
      pushIdValueAsReference(entry.value, out);
    }
  }
}

export interface CollectedReference {
  ref: Reference;
  /** The top-level def this reference is attached to (or its containing parent). */
  ownerDef: Definition;
}

/**
 * Walk every definition in a document (including nested attribute / column /
 * index / constraint / resultColumn defs) and collect every `Reference`
 * reachable from them, paired with the def it came from. The owner is the
 * top-level def for nested children (so `nameAttribute` on `entity artikl`
 * is owned by `artikl` itself, not by an inner attribute).
 */
export function collectAllReferences(ast: Document): CollectedReference[] {
  const out: CollectedReference[] = [];
  for (const def of ast.definitions) {
    for (const ref of collectReferences(def)) out.push({ ref, ownerDef: def });
    for (const child of nestedDefs(def)) {
      for (const ref of collectReferences(child)) out.push({ ref, ownerDef: def });
    }
  }
  return out;
}

/** The stereotype slots a `concept { }` block declares with (`desugar.ts` reads the same five). */
const SUGAR_DECLARING_KEYS: ReadonlySet<string> = new Set(['kind', 'role', 'quality', 'event', 'phase']);

/**
 * 0.17 — the declaring slot of a host's `concept { }` block (`event: Return`) as a cn reference, or
 * `undefined`. It declares its concept, or names one a cn def or another block declares (S2.2's merge
 * rule), so it is none of {@link collectReferences}' — nor the conformance dump's. The editor reads
 * it (review-119 H-2): the cursor finds it, and the reference index lists it wherever it resolves to
 * a concept declared elsewhere, so goto, hover, find-references and rename reach the block.
 */
export function sugarDeclaringRef(def: Definition): CnSlotReference | undefined {
  const sugar = conceptSugarOf(def);
  if (!sugar) return undefined;
  const key = sugarStereotypeKey(sugar);
  if (!key || !SUGAR_DECLARING_KEYS.has(key)) return undefined;
  const declaring = sugar.properties.find((p) => p.kind === 'ref' && p.key === key);
  return declaring?.kind === 'ref' ? cnRef(declaring.ref) : undefined;
}

/** {@link sugarDeclaringRef} over a document, nested hosts included, owned as {@link collectAllReferences} owns. */
export function collectSugarDeclaringRefs(ast: Document): CollectedReference[] {
  const out: CollectedReference[] = [];
  for (const def of ast.definitions) {
    for (const host of [def, ...nestedDefs(def)]) {
      const ref = sugarDeclaringRef(host);
      if (ref) out.push({ ref, ownerDef: def });
    }
  }
  return out;
}

/**
 * Returns the nested per-def children (attributes, columns, indices, ...).
 * Top-level relations / queries / roles are at file scope, not nested here.
 */
export function nestedDefs(def: Definition): Definition[] {
  const out: Definition[] = [];
  switch (def.kind) {
    case 'entity':
      if (def.attributes) out.push(...def.attributes);
      break;
    case 'table':
      if (def.columns) out.push(...def.columns);
      if (def.indices) out.push(...def.indices);
      if (def.constraints) out.push(...def.constraints);
      break;
    case 'view':
      if (def.columns) out.push(...def.columns);
      break;
    case 'procedure':
      if (def.resultColumns) out.push(...def.resultColumns);
      break;
    case 'dimension':
      // Inline MD attributes carry their own `domain:` cross-references.
      out.push(...def.attributes);
      break;
    default:
      break;
  }
  return out;
}

export function packageOfImport(imp: ImportDecl): string {
  if (imp.wildcard) return imp.target;
  const parts = imp.target.split('.');
  return parts.length >= 2 ? parts.slice(0, -1).join('.') : '';
}
