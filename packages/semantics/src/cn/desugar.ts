// SPDX-License-Identifier: Apache-2.0
// 0.17 — the cn desugar (CN-P2 S2.2; cn contracts §1.2, §1.4; CN-D9 "sugar in both directions").
// A concept is grounded three ways — an authored `def cn2<layer>_*` binding, `grounds:` on the
// concept def (top-down), or the `concept { }` block inside the md / er def it grounds to
// (bottom-up). All three project into ONE canonical grounding shape here, the way inline `binding:`
// and `def er2db_*` meet in `mapping-synthesizer.ts` and inline `lexicon { }` and `def term` meet
// in `lexicon/desugar.ts`. The sugar also declares its concept. Per document and mechanical: which
// concept an authored binding grounds, and whether a concept is grounded twice, is the project-level
// analysis in `groundings.ts`.

import type {
  AttributeDef,
  ConceptBindingKind,
  ConceptProperty,
  ConceptRefKey,
  ConceptRefProperty,
  ConceptStereotype,
  ConceptSugar,
  Definition,
  Document,
  Reference,
  SourceLocation,
} from '@tatrman/parser';
import { conceptProperty, conceptRef } from '@tatrman/parser';
import { buildCanonicalKey } from '../qname.js';
import { conceptSugarOf, sugarStereotypeKey } from '../references.js';

/** The layer a grounding lands on — the target's model. */
export type CnLayer = 'md' | 'er' | 'db';

const LAYERS: ReadonlySet<string> = new Set(['md', 'er', 'db']);

const STEREOTYPE_KEYS: ReadonlySet<string> = new Set(['kind', 'role', 'quality', 'event', 'phase']);

/**
 * The binding kind a grounding of a `stereotype` concept on `layer` desugars to (cn contracts §1.2,
 * design `09` §2), or `undefined` where the table has none: a phase grounds only to an er entity
 * (AM-2), an open-tier relation type never grounds. Which md / er element a target may be (a kind
 * to a dimension, not a measure) is `cn/grounding-target-kind` (S2.3), checked on authored and
 * synthesized bindings alike.
 */
export function bindingKindFor(stereotype: ConceptStereotype, layer: CnLayer): ConceptBindingKind | undefined {
  if (stereotype === 'subkind') return layer === 'er' ? 'cn2er_phase' : undefined;
  if (stereotype === 'relation_type') return undefined;
  if (layer === 'db') return stereotype === 'phase' ? undefined : 'cn2db_concept';
  if (layer === 'md') return stereotype === 'phase' ? undefined : `cn2md_${stereotype}`;
  return `cn2er_${stereotype}`;
}

/** The slot of a binding that names its concept (`event:` on `cn2md_event`, `concept:` on `cn2db_concept`). */
export function bindingConceptKey(bindingKind: ConceptBindingKind): ConceptRefKey {
  return bindingKind === 'cn2db_concept' ? 'concept' : (bindingKind.slice(bindingKind.indexOf('_') + 1) as ConceptRefKey);
}

/** The layer a written target names — its first `md` / `er` / `db` segment (a package prefix is allowed). */
export function layerOf(ref: Reference): CnLayer | undefined {
  return ref.parts.find((p) => LAYERS.has(p)) as CnLayer | undefined;
}

/** The suffix of a synthesized binding's name: `<Concept>.grounds` or `<Concept>.concept`. */
const SYNTH_SUFFIX = { grounds: 'grounds', sugar: 'concept' } as const;

/**
 * The name of a binding the desugar synthesizes for `concept`: `<Concept>.grounds` for a
 * `grounds:`, `<Concept>.concept` for a `concept { }` block. Two segments, so it can never be the
 * name of an authored binding (one identifier).
 */
export function synthesizedBindingName(concept: string, origin: 'grounds' | 'sugar'): string {
  return `${concept}.${SYNTH_SUFFIX[origin]}`;
}

/** A concept declared by the `concept { }` block of an md / er def (cn contracts §1.4). */
export interface SugarConcept {
  name: string;
  stereotype: ConceptStereotype;
  /** The block; every slot but the declaring one is a property of the concept. */
  body: ConceptSugar;
  /** The declaring slot (`event: Return`) — the concept's authoring site. */
  declaring: ConceptRefProperty;
  /** The host def's canonical key — what the concept grounds to. */
  hostKey: string;
  layer: CnLayer;
}

/**
 * One grounding of a concept — the canonical binding def. `origin` says how it was written; an
 * authored binding names its concept by reference (`conceptRef`, resolved in `groundings.ts`), the
 * two sugars declare it by name in the document's package (`conceptName`).
 */
export interface CnGrounding {
  origin: 'binding' | 'grounds' | 'sugar';
  /**
   * The binding kind; `undefined` when the stereotype × layer pair has none (an md target on a
   * phase) or a `grounds:` target names no layer — nothing is synthesized and nothing is grounded;
   * `cn/grounding-target-kind` reports it (S2.3).
   */
  bindingKind?: ConceptBindingKind;
  /**
   * The stereotype a `grounds:` (its concept def's) or a `concept { }` block (its declaring slot's)
   * states; unset on an authored binding, whose binding kind says it.
   */
  stereotype?: ConceptStereotype;
  /** The authored binding's name, or {@link synthesizedBindingName}. */
  name: string;
  conceptName?: string;
  conceptRef?: Reference;
  /** The written target (an authored binding's `target:`, a `grounds:`). */
  target?: Reference;
  /** The sugar's host def, by canonical key. */
  targetKey?: string;
  /**
   * The binding's properties past its concept slot and `target:` — `hierarchy`, `participants`,
   * `period`, `via`, `filter`. A `grounds:` carries its concept's `hierarchy:` (Q15-1).
   */
  properties: ConceptProperty[];
  /** The binding def, the `grounds:` property or the `concept { }` block. */
  source: SourceLocation;
}

export interface ConceptDesugar {
  concepts: SugarConcept[];
  groundings: CnGrounding[];
}

/** Properties a binding carries for documentation only — not part of what it grounds. */
const DOC_PROPERTY_KINDS: ReadonlySet<string> = new Set(['description', 'label', 'tags']);

/** The md / er defs that may hold a `concept { }` block, nested attributes included, with their keys. */
function sugarHosts(def: Definition, packageName: string): Array<{ host: Definition; key: string; layer: CnLayer }> {
  const key = (kind: string, parts: string[]) => buildCanonicalKey({ packageName, kind, parts });
  const out: Array<{ host: Definition; key: string; layer: CnLayer }> = [];
  const nested = (attrs: AttributeDef[] | undefined, layer: CnLayer) => {
    for (const a of attrs ?? []) out.push({ host: a, key: key(def.kind, [def.name, a.name]), layer });
  };
  switch (def.kind) {
    case 'entity':
      out.push({ host: def, key: key(def.kind, [def.name]), layer: 'er' });
      nested(def.attributes, 'er');
      break;
    case 'attribute':
      out.push({ host: def, key: key(def.kind, [def.name]), layer: def.domainRef !== undefined ? 'md' : 'er' });
      break;
    case 'dimension':
      out.push({ host: def, key: key(def.kind, [def.name]), layer: 'md' });
      nested(def.attributes, 'md');
      break;
    case 'mdDomain':
    case 'measure':
    case 'cubelet':
      out.push({ host: def, key: key(def.kind, [def.name]), layer: 'md' });
      break;
    default:
      break;
  }
  return out;
}

/** The concept a `concept { }` block declares, or `undefined` when its declaring slot names none. */
function sugarConcept(body: ConceptSugar, hostKey: string, layer: CnLayer): SugarConcept | undefined {
  const key = sugarStereotypeKey(body);
  if (!key || !STEREOTYPE_KEYS.has(key)) return undefined;
  const declaring = conceptRef(body, key);
  if (!declaring) return undefined;
  const parts = declaring.ref.parts;
  return {
    name: parts[parts.length - 1],
    stereotype: key as ConceptStereotype,
    body,
    declaring,
    hostKey,
    layer,
  };
}

/**
 * Project a document's groundings into canonical binding defs, and collect the concepts its
 * `concept { }` blocks declare. `packageName` is the document's effective package — the one its
 * sugar declares in.
 */
export function desugarConcepts(doc: Document, packageName = doc.packageDecl?.name ?? ''): ConceptDesugar {
  const concepts: SugarConcept[] = [];
  const groundings: CnGrounding[] = [];

  for (const def of doc.definitions) {
    if (def.kind === 'conceptBinding') {
      const slot = bindingConceptKey(def.bindingKind);
      const target = conceptProperty(def, 'target')?.value;
      groundings.push({
        origin: 'binding',
        bindingKind: def.bindingKind,
        name: def.name,
        conceptRef: conceptRef(def, slot)?.ref,
        target: target && !('kind' in target) ? target : (() => { const p = def.properties.find((p) => p.kind === 'entry' && p.key === 'episodes'); return p?.kind === 'entry' && p.value.kind === 'id' ? { path: p.value.path, parts: p.value.parts, source: p.value.source } : undefined; })(),
        properties: def.properties.filter(
          (p) => !(p.kind === 'ref' && p.key === slot) && p.kind !== 'target' && !DOC_PROPERTY_KINDS.has(p.kind),
        ),
        source: def.source,
      });
      continue;
    }

    if (def.kind === 'concept') {
      const grounds = conceptProperty(def, 'grounds');
      if (!grounds) {
        const from = conceptProperty(def, 'from'), until = conceptProperty(def, 'until');
        if (from && (def.stereotype === 'quality' || def.stereotype === 'phase' && until)) {
          const id = (ref: Reference) => ({ kind: 'id' as const, path: ref.path, parts: ref.parts, source: ref.source });
          const source: ConceptProperty = def.stereotype === 'quality'
            ? { kind: 'entry', key: 'via', value: id(from.ref), source: from.source }
            : { kind: 'entry', key: 'bounds', value: { kind: 'object', entries: [{key: 'from', value: id(from.ref), source: from.source}, {key: 'until', value: id(until!.ref), source: until!.source}], source: from.source }, source: from.source };
          const per = conceptProperty(def, 'per');
          groundings.push({ origin: 'grounds', bindingKind: def.stereotype === 'quality' ? 'cn2er_quality' : 'cn2er_phase', stereotype: def.stereotype,
            name: synthesizedBindingName(def.name, 'grounds'), conceptName: def.name, properties: per ? [source, per] : [source], source: from.source });
        }
        continue;
      }
      const layer = layerOf(grounds.ref);
      const hierarchy = def.properties.find((p) => p.kind === 'entry' && p.key === 'hierarchy');
      groundings.push({
        origin: 'grounds',
        bindingKind: layer ? bindingKindFor(def.stereotype, layer) : undefined,
        stereotype: def.stereotype,
        name: synthesizedBindingName(def.name, 'grounds'),
        conceptName: def.name,
        target: grounds.ref,
        properties: def.properties.filter((p) => p === hierarchy || layer === 'er' && def.stereotype === 'phase' && p.kind === 'period'),
        source: grounds.source,
      });
      continue;
    }

    for (const { host, key, layer } of sugarHosts(def, packageName)) {
      const body = conceptSugarOf(host);
      if (!body) continue;
      const concept = sugarConcept(body, key, layer);
      if (!concept) continue;
      concepts.push(concept);
      groundings.push({
        origin: 'sugar',
        bindingKind: bindingKindFor(concept.stereotype, layer),
        stereotype: concept.stereotype,
        name: synthesizedBindingName(concept.name, 'sugar'),
        conceptName: concept.name,
        targetKey: key,
        properties: [],
        source: body.source,
      });
    }
  }

  return { concepts, groundings };
}
