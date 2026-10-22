// SPDX-License-Identifier: Apache-2.0
import { DiagnosticCode } from '@tatrman/parser';
import type { Definition, SourceLocation, Document } from '@tatrman/parser';
import {
  mdErBindingDiagnostics,
  defaultSchemaForKind,
  namespaceForKind,
  resolveMdRef,
  underlyingDomainOf,
  mdCrossRefsOf,
  getCalcEntry,
  shapeSatisfied,
  validateCalcArgs,
  buildMdMapGraph,
  resolveLevelDomains,
  inferStep,
  grainReachable,
  type DomainShape,
  type MdMapGraph,
  type ProjectSymbolTable,
} from '@tatrman/semantics';
import type { Rule, DocumentRuleContext, ProjectRuleContext } from '../rule.js';

/** The six MD logical def kinds (schema md). */
const MD_LOGICAL_KINDS: ReadonlySet<string> = new Set([
  'mdDomain',
  'dimension',
  'mdMap',
  'hierarchy',
  'measure',
  'cubelet',
]);

/** The MD binding def kinds (schema binding). */
const MD_BINDING_KINDS: ReadonlySet<string> = new Set([
  'md2dbCubelet',
  'md2dbDomain',
  'md2dbMap',
  'md2erCubelet',
  'md2erDimension',
]);

export function isMdKind(kind: string): boolean {
  return MD_LOGICAL_KINDS.has(kind) || MD_BINDING_KINDS.has(kind);
}

const unknownRef: Rule = {
  id: 'md-unknown-ref',
  code: DiagnosticCode.MdUnknownRef,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: "An MD cross-reference (domain/map/dimension/measure/hierarchy/grain) doesn't resolve.",
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of ctx.ast.definitions) {
      for (const cr of mdCrossRefsOf(def)) {
        if (cr.role === 'grain') continue; // cubelet grain → md/grain-ref-unknown
        if (!resolveMdRef(ctx.symbols, cr.path, cr.role)) {
          ctx.report({
            source: cr.source,
            message: `Unresolved ${cr.role} reference: '${cr.path}'`,
            data: { role: cr.role, path: cr.path },
          });
        }
      }
    }
  },
};

const unknownSchemaDef: Rule = {
  id: 'md-unknown-schema-def',
  code: DiagnosticCode.MdUnknownSchemaDef,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'An MD logical def appears outside `schema md`, or a binding def outside `schema binding`.',
  check(ctx: DocumentRuleContext) {
    if (ctx.scope !== 'document') return;
    const directive = ctx.ast.modelDirective?.modelCode;
    if (!directive) return; // schema-less files derive per-def; nothing to police
    for (const def of ctx.ast.definitions) {
      if (!isMdKind(def.kind)) continue;
      const expected = defaultSchemaForKind(def.kind); // 'md' (logical) | 'binding' (binding)
      if (expected !== directive) {
        ctx.report({
          source: def.source,
          message: `'${def.kind}' belongs in 'model ${expected}', not 'model ${directive}'`,
          data: { kind: def.kind, expected, actual: directive },
        });
      }
    }
  },
};

// ---------------------------------------------------------------------------
// 2C — domain / attribute / measure validators (contracts §3.1, §3.3, §3.6, §6.5)
// ---------------------------------------------------------------------------

/** Continuous value types — a member-set/`kind` is nonsensical on these (scalars). */
const CONTINUOUS_TYPES: ReadonlySet<string> = new Set(['decimal', 'float', 'double']);
/** Restrict clause names the validator understands. Others warn (open set). */
const KNOWN_RESTRICT_CLAUSES: ReadonlySet<string> = new Set(['range', 'members', 'pattern', 'length']);

function domainTypeName(def: Extract<Definition, { kind: 'mdDomain' }>): string | undefined {
  if (!def.type) return undefined;
  return def.type.kind === 'simple' ? def.type.name : def.type.typeName;
}

const kindOnScalar: Rule = {
  id: 'md-kind-on-scalar',
  code: DiagnosticCode.MdKindOnScalar,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: '`kind:` on a scalar (continuous-typed) domain, which cannot form a member set.',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of ctx.ast.definitions) {
      if (def.kind !== 'mdDomain' || !def.domainKind) continue;
      const t = domainTypeName(def)?.toLowerCase();
      if (t && CONTINUOUS_TYPES.has(t)) {
        ctx.report({
          source: def.source,
          message: `Domain '${def.name}' has 'kind: ${def.domainKind}' but a scalar '${t}' type; kind applies only to member-set/calc domains`,
        });
      }
    }
  },
};

const badRestrictValue: Rule = {
  id: 'md-bad-restrict-value',
  code: DiagnosticCode.MdBadRestrictValue,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'A restrict clause value has the wrong shape for its clause (or `members` on a non-discrete type).',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of ctx.ast.definitions) {
      if (def.kind !== 'mdDomain' || !def.restrict) continue;
      const t = domainTypeName(def)?.toLowerCase();
      for (const clause of def.restrict) {
        const v = clause.value;
        const isRange = !Array.isArray(v) && v.kind === 'rangeLiteral';
        const isMembers = Array.isArray(v);
        let bad: string | undefined;
        if (clause.clause === 'range' && !isRange) bad = 'expects a `lo..hi` range literal';
        else if (clause.clause === 'members' && !isMembers) bad = 'expects a labelled member set';
        else if (clause.clause === 'members' && isMembers && t && CONTINUOUS_TYPES.has(t))
          bad = `is only valid on a discrete type (not '${t}')`;
        else if (clause.clause === 'length' && (Array.isArray(v) || v.kind !== 'number')) bad = 'expects a number';
        else if (clause.clause === 'pattern' && (Array.isArray(v) || (v.kind !== 'string' && v.kind !== 'tripleString')))
          bad = 'expects a string';
        if (bad) ctx.report({ source: clause.source, message: `restrict '${clause.clause}' ${bad}` });
      }
    }
  },
};

const unknownRestrictClause: Rule = {
  id: 'md-unknown-restrict-clause',
  code: DiagnosticCode.MdUnknownRestrictClause,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'warning',
  docs: 'An unrecognised restrict clause name (the clause set is open; this is advisory).',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of ctx.ast.definitions) {
      if (def.kind !== 'mdDomain' || !def.restrict) continue;
      for (const clause of def.restrict) {
        if (!KNOWN_RESTRICT_CLAUSES.has(clause.clause)) {
          ctx.report({ source: clause.source, message: `Unknown restrict clause '${clause.clause}'` });
        }
      }
    }
  },
};

const attrNeedsDomain: Rule = {
  id: 'md-attr-needs-domain',
  code: DiagnosticCode.MdAttrNeedsDomain,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'An MD (dimension) attribute lacks the required `domain:`.',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of ctx.ast.definitions) {
      if (def.kind !== 'dimension') continue;
      for (const attr of def.attributes) {
        if (!attr.domainRef) ctx.report({ source: attr.source, message: `Attribute '${attr.name}' needs a 'domain:'` });
      }
    }
  },
};

const attrTypeInMd: Rule = {
  id: 'md-attr-type-in-md',
  code: DiagnosticCode.MdAttrTypeInMd,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'An MD (dimension) attribute carries the ER-only `type:` (use `domain:`).',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of ctx.ast.definitions) {
      if (def.kind !== 'dimension') continue;
      for (const attr of def.attributes) {
        if (attr.type) ctx.report({ source: attr.source, message: `MD attribute '${attr.name}' must use 'domain:', not 'type:'` });
      }
    }
  },
};

const erAttrDomainInEr: Rule = {
  id: 'er-attr-domain-in-er',
  code: DiagnosticCode.ErAttrDomainInEr,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'An ER (entity) attribute carries the MD-only `domain:` (use `type:`).',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of ctx.ast.definitions) {
      if (def.kind !== 'entity' || !def.attributes) continue;
      for (const attr of def.attributes) {
        if (attr.domainRef) ctx.report({ source: attr.source, message: `ER attribute '${attr.name}' must use 'type:', not 'domain:'` });
      }
    }
  },
};

/** Whether an aggregation override names a latest-valid-style function (needs validBy). */
function hasLatestValidOverride(agg: { perDimension?: Record<string, string>; default?: string } | undefined): boolean {
  if (!agg) return false;
  const vals = [agg.default ?? '', ...Object.values(agg.perDimension ?? {})];
  return vals.some((v) => v.toLowerCase().includes('latestvalid') || v.toLowerCase().startsWith('latest'));
}

const semiadditiveNoValidby: Rule = {
  id: 'md-semiadditive-no-validby',
  code: DiagnosticCode.MdSemiadditiveNoValidby,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'A semi-additive measure with a latest-valid aggregation override but no `validBy`.',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    const measures = ctx.ast.definitions.flatMap((d) =>
      d.kind === 'measure' ? [d] : d.kind === 'cubelet' ? d.measures.filter((m): m is Extract<Definition, { kind: "measure" }> => typeof m !== "string") : []
    );
    for (const m of measures) {
      if (m.measureClass === 'semiAdditive' && hasLatestValidOverride(m.aggregation) && !m.validBy) {
        ctx.report({ source: m.source, message: `Semi-additive measure '${m.name}' uses a latest-valid override but has no 'validBy'` });
      }
    }
  },
};

const nonadditiveRecompute: Rule = {
  id: 'md-nonadditive-recompute-unsupported',
  code: DiagnosticCode.MdNonadditiveRecomputeUnsupported,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'A non-additive measure declares a per-dimension recompute (a v1.1 feature; v1 marks only).',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    const measures = ctx.ast.definitions.flatMap((d) =>
      d.kind === 'measure' ? [d] : d.kind === 'cubelet' ? d.measures.filter((m): m is Extract<Definition, { kind: "measure" }> => typeof m !== "string") : []
    );
    for (const m of measures) {
      if (m.measureClass === 'nonAdditive' && m.aggregation?.perDimension && Object.keys(m.aggregation.perDimension).length > 0) {
        ctx.report({
          source: m.source,
          message: `Non-additive measure '${m.name}' declares a per-dimension recompute; v1 marks non-additive only (v1.1 feature)`,
        });
      }
    }
  },
};

// ---------------------------------------------------------------------------
// 2D — map + calc-catalog validation (contracts §3.4, §6.4; map-catalog §1)
// ---------------------------------------------------------------------------

type MdMap = Extract<Definition, { kind: 'mdMap' }>;
type Ctx = DocumentRuleContext;

function mapDefs(ctx: Ctx): MdMap[] {
  return ctx.ast.definitions.filter((d): d is MdMap => d.kind === 'mdMap');
}

/** The crossRef span for a from/to domain path on a map, or the map's own source. */
function refSourceFor(def: MdMap, path: string): SourceLocation {
  return def.crossRefs?.find((c) => c.path === path)?.source ?? def.source;
}

/**
 * Resolve a from/to ref to its {type, range} shape via the project symbols.
 * Uses `underlyingDomainOf` so an attribute-sugar ref (`from: Time.day`, §5.3)
 * lowers to its `domain:` — matching how `buildMdMapGraph` lowers the same refs.
 * (A bare `resolveMdRef(.., 'domain')` would stop at the attribute symbol, which
 * carries no `domainType`, and fire a spurious md/calc-type-mismatch.)
 */
function domainShape(ctx: Ctx, path: string): DomainShape | undefined {
  const sym = underlyingDomainOf(ctx.symbols, path);
  if (!sym) return undefined;
  return { type: sym.domainType, range: sym.domainRange };
}

const unknownCalcMap: Rule = {
  id: 'md-unknown-calc-map',
  code: DiagnosticCode.MdUnknownCalcMap,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'A `calc:` references a name not in the built-in catalog.',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of mapDefs(ctx)) {
      if (def.calc && !getCalcEntry(def.calc.name)) {
        ctx.report({ source: def.calc.source, message: `Unknown calc map '${def.calc.name}'` });
      }
    }
  },
};

const badCalcArgs: Rule = {
  id: 'md-bad-calc-args',
  code: DiagnosticCode.MdBadCalcArgs,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'A calc argument is unknown, missing (required), or out of range.',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of mapDefs(ctx)) {
      if (!def.calc) continue;
      const entry = getCalcEntry(def.calc.name);
      if (!entry) continue; // md/unknown-calc-map handles this
      for (const p of validateCalcArgs(entry, def.calc.args)) {
        ctx.report({
          source: p.arg?.source ?? def.calc.source,
          message: `calc '${def.calc.name}' arg '${p.paramName}' is ${p.problem}`,
        });
      }
    }
  },
};

const calcTypeMismatch: Rule = {
  id: 'md-calc-type-mismatch',
  code: DiagnosticCode.MdCalcTypeMismatch,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: "A map's `from`/`to` domain types don't satisfy the calc signature.",
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of mapDefs(ctx)) {
      if (!def.calc) continue;
      const entry = getCalcEntry(def.calc.name);
      if (!entry) continue;
      const fromPath = def.from[0];
      const toPath = def.to[0];
      const fromShape = fromPath ? domainShape(ctx, fromPath) : undefined;
      const toShape = toPath ? domainShape(ctx, toPath) : undefined;
      // Only flag a mismatch when the domain resolves (else md/unknown-ref fires).
      if (fromShape && !shapeSatisfied(fromShape, entry.input)) {
        ctx.report({ source: refSourceFor(def, fromPath), message: `'from' domain does not satisfy calc '${def.calc.name}' input` });
      }
      if (toShape && !shapeSatisfied(toShape, entry.output)) {
        ctx.report({ source: refSourceFor(def, toPath), message: `'to' domain does not satisfy calc '${def.calc.name}' output` });
      }
    }
  },
};

const calcCardinalityConflict: Rule = {
  id: 'md-calc-cardinality-conflict',
  code: DiagnosticCode.MdCalcCardinalityConflict,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'An explicit `cardinality: 1:1` on a calc (implicitly N:1) map.',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of mapDefs(ctx)) {
      if (def.calc && def.cardinality === '1:1') {
        ctx.report({ source: def.source, message: `calc map '${def.name}' is implicitly N:1; remove the explicit '1:1'` });
      }
    }
  },
};

/** Last dotted segment of a ref (the bare name). */
function leaf(path: string): string {
  const parts = path.split('.');
  return parts[parts.length - 1];
}

/** Every top-level def of a given kind across all project documents. */
function* projectDefs<K extends Definition['kind']>(
  documents: ReadonlyMap<string, Document>,
  kind: K
): Generator<Extract<Definition, { kind: K }>> {
  for (const doc of documents.values()) {
    for (const def of doc.definitions) {
      if (def.kind === kind) yield def as Extract<Definition, { kind: K }>;
    }
  }
}

/**
 * The project-wide MD map graph (every `def map` across all documents), built
 * once per lint pass and memoized on `ctx.cache`. Built from the whole project
 * (not a single document) so hierarchy-step and grain rules see maps that live
 * in a different file from the hierarchy/cubelet that uses them.
 */
function projectMapGraph(ctx: ProjectRuleContext): MdMapGraph {
  const cached = ctx.cache.get('md:mapGraph');
  if (cached) return cached as MdMapGraph;
  const graph = buildMdMapGraph(ctx.symbols, [...projectDefs(ctx.documents, 'mdMap')]);
  ctx.cache.set('md:mapGraph', graph);
  return graph;
}

const tableMapNoBinding: Rule = {
  id: 'md-table-map-no-binding',
  code: DiagnosticCode.MdTableMapNoBinding,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'warning',
  docs: 'A table-backed map (no `calc:`) with no `md2db_map` binding in this file (Phase 3 refines cross-file).',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    const boundMapNames = new Set(
      ctx.ast.definitions.filter((d) => d.kind === 'md2dbMap').map((d) => leaf((d as Extract<Definition, { kind: 'md2dbMap' }>).mapRef))
    );
    for (const def of mapDefs(ctx)) {
      if (!def.calc && !boundMapNames.has(def.name)) {
        ctx.report({ source: def.source, message: `table-backed map '${def.name}' has no md2db_map binding` });
      }
    }
  },
};

// ---------------------------------------------------------------------------
// 2E — hierarchy step inference (contracts §6.3)
// ---------------------------------------------------------------------------

const levelNotInDim: Rule = {
  id: 'md-level-not-in-dim',
  code: DiagnosticCode.MdLevelNotInDim,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: "A hierarchy level attribute isn't a member of the hierarchy's dimension.",
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of ctx.ast.definitions) {
      if (def.kind !== 'hierarchy') continue;
      const levelInfos = resolveLevelDomains(ctx.symbols, def.dimensionRef, def.levels.map((l) => l.attribute));
      def.levels.forEach((lvl, i) => {
        if (!levelInfos[i].inDim) {
          ctx.report({ source: lvl.source, message: `Level '${lvl.attribute}' is not in dimension '${def.dimensionRef ?? '?'}'` });
        }
      });
    }
  },
};

/** Shared step inference for the two step-error rules; returns one result per consecutive pair. */
function hierarchySteps(
  symbols: ProjectSymbolTable,
  graph: MdMapGraph,
  def: Extract<Definition, { kind: 'hierarchy' }>
) {
  const infos = resolveLevelDomains(symbols, def.dimensionRef, def.levels.map((l) => l.attribute));
  const out: { upper: typeof def.levels[number]; result: 'ok' | 'none' | 'ambiguous' }[] = [];
  for (let i = 0; i + 1 < def.levels.length; i++) {
    const lower = infos[i];
    const upper = infos[i + 1];
    const upperLevel = def.levels[i + 1];
    if (!lower.inDim || !upper.inDim || !lower.domain || !upper.domain) continue; // covered elsewhere
    const via = upperLevel.via;
    if (via) {
      const viaName = leaf(via);
      const ok = graph.edges.some(
        (e) => !e.oneToOne && e.from === lower.domain && e.to === upper.domain && e.mapName === viaName
      );
      out.push({ upper: upperLevel, result: ok ? 'ok' : 'none' });
      continue;
    }
    const step = inferStep(graph.edges, lower.domain, upper.domain);
    out.push({ upper: upperLevel, result: 'ok' in step ? 'ok' : step.error });
  }
  return out;
}

const noHierarchyStep: Rule = {
  id: 'md-no-hierarchy-step',
  code: DiagnosticCode.MdNoHierarchyStep,
  category: 'md',
  // Project-scoped: the connecting `def map`s may live in a different file from
  // the `def hierarchy` (the map graph is project-wide, like the binding rules).
  scope: 'project',
  defaultSeverity: 'error',
  docs: 'No N:1 map connects two consecutive hierarchy levels.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const graph = projectMapGraph(ctx);
    for (const def of projectDefs(ctx.documents, 'hierarchy')) {
      for (const s of hierarchySteps(ctx.symbols, graph, def)) {
        if (s.result === 'none') {
          ctx.report({ source: s.upper.source, message: `No map connects to level '${s.upper.attribute}'` });
        }
      }
    }
  },
};

const ambiguousHierarchyStep: Rule = {
  id: 'md-ambiguous-hierarchy-step',
  code: DiagnosticCode.MdAmbiguousHierarchyStep,
  category: 'md',
  scope: 'project',
  defaultSeverity: 'error',
  docs: 'More than one N:1 map connects two consecutive levels and no `via:` disambiguates.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const graph = projectMapGraph(ctx);
    for (const def of projectDefs(ctx.documents, 'hierarchy')) {
      for (const s of hierarchySteps(ctx.symbols, graph, def)) {
        if (s.result === 'ambiguous') {
          ctx.report({ source: s.upper.source, message: `Ambiguous step to level '${s.upper.attribute}'; add 'via <map>'` });
        }
      }
    }
  },
};

// ---------------------------------------------------------------------------
// 2F — cubelet validator (contracts §3.7, §6.1)
// ---------------------------------------------------------------------------

type Cubelet = Extract<Definition, { kind: 'cubelet' }>;

const grainRefUnknown: Rule = {
  id: 'md-grain-ref-unknown',
  code: DiagnosticCode.MdGrainRefUnknown,
  category: 'md',
  scope: 'document',
  defaultSeverity: 'error',
  docs: "A cubelet `grain` ref doesn't resolve to a real Dimension.attribute.",
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const def of ctx.ast.definitions) {
      if (def.kind !== 'cubelet') continue;
      for (const cr of def.crossRefs ?? []) {
        if (cr.role === 'grain' && !resolveMdRef(ctx.symbols, cr.path, 'grain')) {
          ctx.report({ source: cr.source, message: `Unknown grain attribute '${cr.path}'` });
        }
      }
    }
  },
};

/** The underlying domain qname of a grain attribute ref, or undefined. */
function grainDomain(symbols: ProjectSymbolTable, path: string): string | undefined {
  const attr = resolveMdRef(symbols, path, 'grain');
  if (!attr?.domainRef) return undefined;
  return resolveMdRef(symbols, attr.domainRef, 'domain')?.qname;
}

const grainNotLeaf: Rule = {
  id: 'md-grain-not-leaf',
  code: DiagnosticCode.MdGrainNotLeaf,
  category: 'md',
  // Project-scoped: coarsening maps may live in a different file from the cubelet.
  scope: 'project',
  defaultSeverity: 'warning',
  docs: 'A grain attribute is strictly coarser than another in the same grain (advisory).',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const reach = grainReachable(projectMapGraph(ctx).edges);
    for (const cubelet of projectDefs(ctx.documents, 'cubelet')) {
      const grainCrs = (cubelet.crossRefs ?? []).filter((c) => c.role === 'grain');
      const domains = grainCrs.map((c) => grainDomain(ctx.symbols, c.path));
      for (let j = 0; j < grainCrs.length; j++) {
        const b = domains[j];
        if (!b) continue;
        for (let i = 0; i < grainCrs.length; i++) {
          if (i === j) continue;
          const a = domains[i];
          // a (finer) coarsens to b (coarser) ⇒ b is redundant/coarser in the grain.
          if (a && a !== b && reach(a, b)) {
            ctx.report({
              source: grainCrs[j].source,
              message: `Grain attribute '${grainCrs[j].path}' is coarser than '${grainCrs[i].path}'`,
            });
            break; // report each coarser attribute once, not once per finer peer
          }
        }
      }
    }
  },
};

// ---------------------------------------------------------------------------
// Phase 3 — binding-layer validators (project-scoped; cross-file logical+binding)
// contracts §4, §6.6
// ---------------------------------------------------------------------------

type Md2DbDomain = Extract<Definition, { kind: 'md2dbDomain' }>;
type Md2DbMap = Extract<Definition, { kind: 'md2dbMap' }>;
type Md2DbCubelet = Extract<Definition, { kind: 'md2dbCubelet' }>;
type Md2ErCubelet = Extract<Definition, { kind: 'md2erCubelet' }>;
type Domain = Extract<Definition, { kind: 'mdDomain' }>;

interface BindingModel {
  // Logical defs are keyed by their package-qualified project qname (not the
  // bare name) so same-named defs in different packages don't collide.
  domains: Map<string, Domain>;
  maps: Map<string, MdMap>;
  cubelets: Map<string, Cubelet>;
  md2dbDomains: Md2DbDomain[];
  md2dbMaps: Md2DbMap[];
  md2dbCubelets: Md2DbCubelet[];
  md2erCubelets: Md2ErCubelet[];
}

/**
 * The package-qualified project qname a logical-MD ref denotes, under the given
 * namespace segment (`domain`/`map`/`cubelet`). Resolves through the shared
 * symbol index (exact, then unique package-qualified suffix); returns undefined
 * when nothing — or more than one package — matches. Used to key/look-up the
 * binding model so cross-package same-named defs are kept distinct.
 */
function mdQnameFor(symbols: ProjectSymbolTable, ref: string, ns: string): string | undefined {
  const canonical = `md.${ns}.${leaf(ref)}`;
  const exact = symbols.get(canonical);
  if (exact) return exact.qname;
  const distinct = new Set(symbols.getBySuffix(canonical).map((e) => e.qname));
  return distinct.size === 1 ? [...distinct][0] : undefined;
}

/** Key for a logical def in the binding model (its own qname; bare name fallback). */
function defKey(symbols: ProjectSymbolTable, def: Domain | MdMap | Cubelet): string {
  return mdQnameFor(symbols, def.name, namespaceForKind(def.kind)) ?? def.name;
}

/** Index the logical + binding defs across all project documents, keyed by qname. */
function buildBindingModel(documents: ReadonlyMap<string, Document>, symbols: ProjectSymbolTable): BindingModel {
  const m: BindingModel = {
    domains: new Map(),
    maps: new Map(),
    cubelets: new Map(),
    md2dbDomains: [],
    md2dbMaps: [],
    md2dbCubelets: [],
    md2erCubelets: [],
  };
  for (const doc of documents.values()) {
    for (const def of doc.definitions) {
      switch (def.kind) {
        case 'mdDomain': m.domains.set(defKey(symbols, def), def); break;
        case 'mdMap': m.maps.set(defKey(symbols, def), def); break;
        case 'cubelet': m.cubelets.set(defKey(symbols, def), def); break;
        case 'md2dbDomain': m.md2dbDomains.push(def); break;
        case 'md2dbMap': m.md2dbMaps.push(def); break;
        case 'md2dbCubelet': m.md2dbCubelets.push(def); break;
        case 'md2erCubelet': m.md2erCubelets.push(def); break;
        default: break;
      }
    }
  }
  return m;
}

/** The binding model, built once per lint pass and memoized on `ctx.cache`. */
function bindingModelFor(ctx: ProjectRuleContext): BindingModel {
  const cached = ctx.cache.get('md:bindingModel');
  if (cached) return cached as BindingModel;
  const model = buildBindingModel(ctx.documents, ctx.symbols);
  ctx.cache.set('md:bindingModel', model);
  return model;
}

/** Resolve a binding's ref (`domain:`/`map:`/`cubelet:`) to its model key. */
function refKey(symbols: ProjectSymbolTable, ref: string, ns: string): string {
  return mdQnameFor(symbols, ref, ns) ?? leaf(ref);
}

/** A cubelet's measure names (standalone refs + inline defs). */
function cubeletMeasureNames(c: Cubelet): string[] {
  return c.measures.map((mm) => (typeof mm === 'string' ? leaf(mm) : mm.name));
}

const sourceOnUnboundDomain: Rule = {
  id: 'md-source-on-unbound-domain',
  code: DiagnosticCode.MdSourceOnUnboundDomain,
  category: 'md',
  scope: 'project',
  defaultSeverity: 'error',
  docs: 'An `md2db_domain` targets a domain that is not `kind: bound`.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const model = bindingModelFor(ctx);
    for (const def of model.md2dbDomains) {
      const domain = model.domains.get(refKey(ctx.symbols, def.domainRef, 'domain'));
      if (domain && domain.domainKind !== 'bound') {
        ctx.report({ source: def.source, message: `md2db_domain targets '${def.domainRef}', which is not 'kind: bound'` });
      }
    }
  },
};

const boundDomainNoSource: Rule = {
  id: 'md-bound-domain-no-source',
  code: DiagnosticCode.MdBoundDomainNoSource,
  category: 'md',
  scope: 'project',
  defaultSeverity: 'error',
  docs: 'A `kind: bound` domain has no `md2db_domain` source.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const model = bindingModelFor(ctx);
    const sourced = new Set(model.md2dbDomains.map((d) => refKey(ctx.symbols, d.domainRef, 'domain')));
    for (const [qname, domain] of model.domains) {
      if (domain.domainKind === 'bound' && !sourced.has(qname)) {
        ctx.report({ source: domain.source, message: `Bound domain '${domain.name}' has no md2db_domain source` });
      }
    }
  },
};

const bindingOnCalcMap: Rule = {
  id: 'md-binding-on-calc-map',
  code: DiagnosticCode.MdBindingOnCalcMap,
  category: 'md',
  scope: 'project',
  defaultSeverity: 'error',
  docs: 'An `md2db_map` targets a calc (non-table-backed) map.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const model = bindingModelFor(ctx);
    for (const def of model.md2dbMaps) {
      const map = model.maps.get(refKey(ctx.symbols, def.mapRef, 'map'));
      if (map && map.calc) {
        ctx.report({ source: def.source, message: `md2db_map targets calc map '${def.mapRef}'; only table-backed maps need a binding` });
      }
    }
  },
};

const mapColumnsIncomplete: Rule = {
  id: 'md-map-columns-incomplete',
  code: DiagnosticCode.MdMapColumnsIncomplete,
  category: 'md',
  scope: 'project',
  defaultSeverity: 'error',
  docs: "An `md2db_map`'s `columns` don't cover every from/to domain of the map.",
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const model = bindingModelFor(ctx);
    for (const def of model.md2dbMaps) {
      const map = model.maps.get(refKey(ctx.symbols, def.mapRef, 'map'));
      if (!map || map.calc) continue;
      const need = [...map.from, ...map.to].map(leaf);
      const have = new Set(Object.keys(def.columns));
      const missing = need.filter((d) => !have.has(d));
      if (missing.length > 0) {
        ctx.report({ source: def.source, message: `md2db_map columns miss domain(s): ${missing.join(', ')}` });
      }
    }
  },
};

const shapeMeasureMismatch: Rule = {
  id: 'md-shape-measure-mismatch',
  code: DiagnosticCode.MdShapeMeasureMismatch,
  category: 'md',
  scope: 'project',
  defaultSeverity: 'error',
  docs: 'A measure binding form does not match the cubelet binding `shape` (wide⇒column, long⇒code).',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const model = bindingModelFor(ctx);
    for (const def of model.md2dbCubelets) {
      const wide = def.shape.shape === 'wide';
      for (const [measure, binding] of Object.entries(def.measures)) {
        const isCode = 'code' in binding;
        if (wide && isCode) {
          ctx.report({ source: def.source, message: `wide shape: measure '${measure}' must bind a column, not a code` });
        } else if (!wide && !isCode) {
          ctx.report({ source: def.source, message: `long shape: measure '${measure}' must bind a code, not a column` });
        }
      }
    }
  },
};

const cubeletGrainCoverage: Rule = {
  id: 'md-cubelet-grain-coverage',
  // Distinct from md/grain-ref-unknown (a logical grain ref not resolving): this
  // is a physical binding-completeness error, so it carries its own code.
  code: DiagnosticCode.MdCubeletGrainUncovered,
  category: 'md',
  scope: 'project',
  defaultSeverity: 'error',
  docs: "An `md2db_cubelet`'s attribute bindings don't cover the cubelet's grain.",
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const model = bindingModelFor(ctx);
    for (const def of model.md2dbCubelets) {
      const cubelet = model.cubelets.get(refKey(ctx.symbols, def.cubeletRef, 'cubelet'));
      if (!cubelet) continue;
      const bound = new Set(Object.keys(def.attributes));
      for (const g of cubelet.grain) {
        if (!bound.has(g)) {
          ctx.report({ source: def.source, message: `grain attribute '${g}' is not bound to a column` });
        }
      }
    }
  },
};

const incompleteJournaling: Rule = {
  id: 'md-incomplete-journaling',
  code: DiagnosticCode.MdIncompleteJournaling,
  category: 'md',
  scope: 'project',
  defaultSeverity: 'error',
  docs: 'An invalidate journaling missing `validColumn`, or a writeback cubelet leaving a measure unbound.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const model = bindingModelFor(ctx);
    for (const def of model.md2dbCubelets) {
      const j = def.journaling;
      if (!j) continue; // read-only binding — no writeback completeness
      if (j.mode === 'invalidate' && !j.validColumn) {
        ctx.report({ source: def.source, message: `invalidate journaling needs a 'validColumn'` });
      }
      // Writeback completeness: every cubelet measure must be bound.
      const cubelet = model.cubelets.get(refKey(ctx.symbols, def.cubeletRef, 'cubelet'));
      if (cubelet) {
        const bound = new Set(Object.keys(def.measures));
        for (const measure of cubeletMeasureNames(cubelet)) {
          if (!bound.has(measure)) {
            ctx.report({ source: def.source, message: `writeback binding leaves measure '${measure}' unbound` });
          }
        }
      }
    }
  },
};

const multisourceGrainMismatch: Rule = {
  id: 'md-multisource-grain-mismatch',
  code: DiagnosticCode.MdMultisourceGrainMismatch,
  category: 'md',
  scope: 'project',
  defaultSeverity: 'error',
  docs: 'Multiple `md2db_cubelet` defs for one cubelet disagree on the bound grain.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const model = bindingModelFor(ctx);
    const byCubelet = new Map<string, Md2DbCubelet[]>();
    for (const def of model.md2dbCubelets) {
      const key = refKey(ctx.symbols, def.cubeletRef, 'cubelet');
      const arr = byCubelet.get(key) ?? [];
      arr.push(def);
      byCubelet.set(key, arr);
    }
    for (const defs of byCubelet.values()) {
      if (defs.length < 2) continue;
      const sig = (d: Md2DbCubelet) => Object.keys(d.attributes).sort().join('|');
      const first = sig(defs[0]);
      for (const d of defs) {
        if (sig(d) !== first) {
          ctx.report({ source: d.source, message: `multi-source binding disagrees on grain for cubelet '${d.cubeletRef}'` });
        }
      }
    }
  },
};

const mdErStructuralRules: Rule[] = [
  ['md-md2er-physical-prop', DiagnosticCode.MdMd2erPhysicalProp],
  ['md-md2er-dimension-key', DiagnosticCode.MdMd2erDimensionKey],
  ['md-md2er-path-ambiguous', DiagnosticCode.MdMd2erPathAmbiguous],
  ['md-md2er-measure-unknown', DiagnosticCode.MdMd2erMeasureUnknown],
  ['md-duplicate-binding', DiagnosticCode.MdDuplicateBinding],
  ['md-er-binding-reference', DiagnosticCode.MdMd2erReference],
].map(([id, code]) => ({
  id, code: code as DiagnosticCode, category: 'md', scope: 'project', defaultSeverity: 'error',
  docs: 'Validate explicit structural MD to ER bindings.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    let diagnostics = ctx.cache.get('md:erStructural') as ReturnType<typeof mdErBindingDiagnostics> | undefined;
    if (!diagnostics) {
      diagnostics = mdErBindingDiagnostics([...ctx.documents].map(([uri, ast]) => {
        const sym = ctx.symbols.documentContext(uri);
        return { uri, ast, schemaCode: ast.modelDirective?.modelCode ?? '', namespace: ast.modelDirective?.schema ?? '', packageName: sym?.packageName ?? ast.packageDecl?.name ?? '' };
      }), ctx.resolver);
      ctx.cache.set('md:erStructural', diagnostics);
    }
    for (const d of diagnostics) if (d.code === code) ctx.report(d);
  },
}));

export const MD_RULES: Rule[] = [
  unknownRef,
  unknownSchemaDef,
  kindOnScalar,
  badRestrictValue,
  unknownRestrictClause,
  attrNeedsDomain,
  attrTypeInMd,
  erAttrDomainInEr,
  semiadditiveNoValidby,
  nonadditiveRecompute,
  unknownCalcMap,
  badCalcArgs,
  calcTypeMismatch,
  calcCardinalityConflict,
  tableMapNoBinding,
  levelNotInDim,
  noHierarchyStep,
  ambiguousHierarchyStep,
  grainRefUnknown,
  grainNotLeaf,
  // Phase 3 — binding layer
  sourceOnUnboundDomain,
  boundDomainNoSource,
  bindingOnCalcMap,
  mapColumnsIncomplete,
  shapeMeasureMismatch,
  cubeletGrainCoverage,
  incompleteJournaling,
  multisourceGrainMismatch,
  ...mdErStructuralRules,
];
