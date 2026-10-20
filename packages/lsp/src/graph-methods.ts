// SPDX-License-Identifier: Apache-2.0
import { parseString, type Document, type Definition, type ObjectValue, type Reference } from '@tatrman/parser';
import { computeGraphEdges, buildNodeForDef, buildMdContext, er2dbTargetDescription, type ModelGraphNode, type ModelGraphEdge } from './model-graph.js';
import { findCyclesOn, buildCanonicalKey, keyKindOf, type PackageGraph } from '@tatrman/semantics';

export interface GraphMetadata {
  uri: string;
  name: string;
  schema: 'db' | 'er' | 'md' | 'binding' | 'query' | 'cnc';
  description?: string;
  tags: string[];
  objectCount: number;
  missingObjectCount: number;
}

export interface GetGraphResponse {
  schema: 'db' | 'er' | 'md' | 'binding' | 'query' | 'cnc';
  nodes: ModelGraphNode[];
  edges: ModelGraphEdge[];
  layout: GraphLayoutOutput;
  missingObjects: string[];
  imports: string[];
}

export interface GraphLayoutOutput {
  viewport?: { zoom: number; panX: number; panY: number; displayMode: string };
  nodes: Record<string, { x: number; y: number }>;
  edges: Record<string, { bendPoints?: [number, number][] }>;
}

export interface PackageGraphResponse {
  packages: { name: string; documentUris: string[] }[];
  dependencies: { from: string; to: string; citedBy: string[] }[];
  cycles: string[][];
}

function getAllTtrgUris(documents: Map<string, string>): string[] {
  return [...documents.keys()].filter((uri) => uri.endsWith('.ttrg'));
}

function parseAllDocs(documents: Map<string, string>): Map<string, Document> {
  const docs = new Map<string, Document>();
  for (const [uri, content] of documents) {
    const result = parseString(content, uri);
    if (result.ast) docs.set(uri, result.ast);
  }
  return docs;
}

function buildQnameToDef(asts: Document[]): Map<string, { def: Definition; schemaCode: string; namespace: string; packageName: string }> {
  const map = new Map<string, { def: Definition; schemaCode: string; namespace: string; packageName: string }>();
  for (const ast of asts) {
    // TODO(pkg-schema-defaults): presentation-layer schema default; out of scope
    // for the schema-by-kind correctness fix. Should later use defaultSchemaForKind.
    const schemaCode = ast.modelDirective?.modelCode ?? 'er';
    const namespace = ast.modelDirective?.schema ?? '';
    const packageName = ast.packageDecl?.name ?? '';
    // The key reads the raw directive, not the presentation default above: only a
    // query declared under an explicit `model er` is a logical query (LQ L-1).
    const declaringModel = ast.modelDirective?.modelCode ?? '';
    for (const def of ast.definitions) {
      addDefAndChildren(map, def, schemaCode, namespace, packageName, declaringModel);
    }
  }
  return map;
}

function addDefAndChildren(
  map: Map<string, { def: Definition; schemaCode: string; namespace: string; packageName: string }>,
  def: Definition,
  schemaCode: string,
  namespace: string,
  packageName: string,
  declaringModel: string
): void {
  const qname = buildCanonicalKey({ packageName, schemaId: namespace, kind: keyKindOf(def), parts: [def.name], declaringModel });
  map.set(qname, { def, schemaCode, namespace, packageName });

  const children: Definition[] = [];
  if (def.kind === 'entity' && def.attributes) children.push(...def.attributes);
  if (def.kind === 'table') {
    if (def.columns) children.push(...def.columns);
    if (def.indices) children.push(...def.indices);
    if (def.constraints) children.push(...def.constraints);
  }
  if (def.kind === 'view' && def.columns) children.push(...def.columns);
  if (def.kind === 'procedure' && def.resultColumns) children.push(...def.resultColumns);

  for (const child of children) {
    // Members are grouped under the parent def's model/schema/kind.
    const childQname = buildCanonicalKey({ packageName, schemaId: namespace, kind: def.kind, parts: [def.name, child.name] });
    map.set(childQname, { def: child, schemaCode, namespace, packageName });
  }
}

export function getPackageGraphFromCache(pkgGraph: PackageGraph): PackageGraphResponse {
  return {
    packages: pkgGraph.nodes.map((n) => ({ name: n.name, documentUris: n.documentUris })),
    dependencies: pkgGraph.edges.map((e) => ({ from: e.from, to: e.to, citedBy: e.citedBy })),
    cycles: findCyclesOn(pkgGraph),
  };
}

export function listGraphs(
  documents: Map<string, string>,
  qnameToDef?: Map<string, { def: Definition; schemaCode: string; namespace: string }>,
): GraphMetadata[] {
  const uris = getAllTtrgUris(documents);
  const results: GraphMetadata[] = [];

  for (const uri of uris) {
    const content = documents.get(uri);
    if (content === undefined) continue;
    const result = parseString(content, uri);
    const graph = result.ast?.graph;
    if (!graph) continue;

    let missingObjectCount = 0;
    if (qnameToDef) {
      for (const objQname of graph.objects) {
        if (!qnameToDef.has(objQname)) missingObjectCount++;
      }
    }

    results.push({
      uri,
      name: graph.name,
      schema: graph.schema ?? 'er',
      description: graph.description,
      tags: graph.tags ?? [],
      objectCount: graph.objects.length,
      missingObjectCount,
    });
  }

  return results;
}

// ---- binding map (DS-P4.S1) — the er↔db binding-model data, canonicalized so its qnames
// match the er/db ModelGraph node/row qnames. Feeds BOTH the binding perspective and the
// er-canvas show-bindings decoration. Structurally compatible with @tatrman/perspectives'
// BindingMap (the designer adapter passes this straight into the generator).

export interface BindingMapEntity {
  entityQname: string;
  target:
    | { kind: 'table'; tableQname: string }
    | { kind: 'query'; queryQname: string }
    | { kind: 'unresolved'; raw?: string };
}
export interface BindingMapAttribute { attributeQname: string; columnQname: string }
export interface BindingMapQuery { qname: string; predicate: string; provenance: string[] }
export interface BindingMapData {
  entities: BindingMapEntity[];
  attributes: BindingMapAttribute[];
  queries: BindingMapQuery[];
}

const MODEL_SEGMENTS = new Set(['db', 'er', 'md', 'cnc', 'query', 'binding', 'calc']);

/**
 * Canonicalize a surface binding reference (`er.entity.Customer`, `db.dbo.Customer.Col`,
 * `query.query.active_customers`) to the v4.0 canonical key that the er/db/query ModelGraph
 * nodes carry. Single-package heuristic: if the first segment isn't a model code it's read as
 * the package (else the binding file's package is used). db/query paths carry a schema segment;
 * er paths carry the kind segment verbatim. NOTE: mirrors buildMdContext's bare-name caveat —
 * a fully cross-package binding graph would need the resolver; the hero corpus is single-package.
 */
function canonicalizeBindingRef(path: string, defaultPkg: string, forcedKind?: string): string | null {
  const segs = path.split('.').filter((s) => s.length > 0);
  if (segs.length < 2) return null;
  let pkg = defaultPkg;
  let rest = segs;
  if (!MODEL_SEGMENTS.has(segs[0])) { pkg = segs[0]; rest = segs.slice(1); }
  const model = rest[0];
  if (model === 'db') {
    // db.<schema>.<table>[.<col>] — kind is table (columns group under their table)
    const schemaId = rest[1] ?? 'dbo';
    const nameParts = rest.slice(2);
    if (nameParts.length === 0) return null;
    return buildCanonicalKey({ packageName: pkg, schemaId, kind: forcedKind ?? 'table', parts: nameParts });
  }
  if (model === 'query') {
    // query.<schema>.<name> — a query is a db-layer object (D14: modelForKind('query')='db'),
    // so it carries the file's `schema` segment (e.g. `query`), NOT a defaulted `dbo`. Pass it
    // through so the qname matches the canonical query symbol/node.
    const schemaId = rest[1];
    const nameParts = rest.slice(2);
    if (nameParts.length === 0) return null;
    return buildCanonicalKey({ packageName: pkg, schemaId, kind: 'query', parts: nameParts });
  }
  // er.<kind>.<name>[.<member>] — incl. a logical query `er.query.<name>` (LQ L-1)
  const kind = forcedKind ?? rest[1];
  const nameParts = rest.slice(2);
  if (!kind || nameParts.length === 0) return null;
  return buildCanonicalKey({ packageName: pkg, kind, parts: nameParts, declaringModel: model });
}

/** Naive base-table provenance from a query's SQL (stub, honest): the FROM/JOIN dbo.* tables. */
function queryProvenance(sourceText: string, pkg: string): string[] {
  const out = new Set<string>();
  const re = /\b(?:from|join)\s+([a-zA-Z_][\w]*)\.([a-zA-Z_][\w]*)/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(sourceText)) !== null) {
    const canon = buildCanonicalKey({ packageName: pkg, schemaId: m[1], kind: 'table', parts: [m[2]] });
    out.add(canon);
  }
  return [...out];
}

/** Pull a specific target-object key's reference path (`{ column: db.dbo.T.C }` → the path),
 *  or the bare-reference path. Used for attribute binds (`column:`), which er2dbTargetDescription
 *  — table/view/query only — does not cover. */
function targetRefPath(target: ObjectValue | Reference | undefined, key: string): string | undefined {
  if (!target) return undefined;
  if ('kind' in target && target.kind === 'object') {
    const entry = target.entries.find((e) => e.key === key);
    return entry && entry.value.kind === 'id' ? entry.value.path : undefined;
  }
  return 'path' in target ? target.path : undefined;
}

/**
 * Extract the whole-project er↔db binding map (canonicalized), plus query metadata (C-2).
 *
 * `preferredLang` (NLS-P10) only reaches the query `predicate`, which is a
 * `description:` and so can be authored in either the plain or the localised map
 * form. Defaulted rather than required: every caller today is an LSP request that
 * already knows the manifest's preferred language, and none supplies it yet.
 */
export function buildBindingMap(documents: Map<string, string>, preferredLang = 'en'): BindingMapData {
  const asts = [...parseAllDocs(documents).values()];
  const entities: BindingMapEntity[] = [];
  const attributes: BindingMapAttribute[] = [];
  const queries: BindingMapQuery[] = [];

  for (const ast of asts) {
    const pkg = ast.packageDecl?.name ?? '';
    for (const def of ast.definitions) {
      if (def.kind === 'er2dbEntity') {
        const e = def as unknown as { entity?: Reference; target?: ObjectValue | Reference };
        const entityQname = e.entity ? canonicalizeBindingRef(e.entity.path, pkg, 'entity') : null;
        if (!entityQname) continue;
        const desc = er2dbTargetDescription(e.target); // 'table:...', 'query:...', 'view:...' or ''
        if (desc.startsWith('table:') || desc.startsWith('view:')) {
          // a `view:` target canonicalizes with kind `view` (its live node is `…db.<schema>.view.X`,
          // NOT `…table.X`) but still renders as a db-object bind.
          const isView = desc.startsWith('view:');
          const tableQname = canonicalizeBindingRef(desc.slice(desc.indexOf(':') + 1), pkg, isView ? 'view' : 'table');
          entities.push(tableQname
            ? { entityQname, target: { kind: 'table', tableQname } }
            : { entityQname, target: { kind: 'unresolved', raw: desc } });
        } else if (desc.startsWith('query:')) {
          const queryQname = canonicalizeBindingRef(desc.slice(6), pkg, 'query');
          entities.push(queryQname
            ? { entityQname, target: { kind: 'query', queryQname } }
            : { entityQname, target: { kind: 'unresolved', raw: desc } });
        } else {
          entities.push({ entityQname, target: { kind: 'unresolved', raw: desc || undefined } });
        }
      } else if (def.kind === 'er2dbAttribute') {
        const a = def as unknown as { attribute?: Reference; target?: ObjectValue | Reference };
        const attributeQname = a.attribute ? canonicalizeBindingRef(a.attribute.path, pkg, 'entity') : null;
        const colPath = targetRefPath(a.target, 'column'); // { column: db.dbo.T.Col }
        const columnQname = colPath ? canonicalizeBindingRef(colPath, pkg, 'table') : null;
        if (attributeQname && columnQname) attributes.push({ attributeQname, columnQname });
      } else if (def.kind === 'query') {
        const q = def as unknown as {
          name: string;
          description?: { kind: string; value: string };
          descriptionLocalized?: { entries: Record<string, string> };
          sourceText?: { kind: string; value: string };
        };
        // use the file's actual `schema` so the query-def qname matches the entity's query-target
        // qname (both go through canonicalizeBindingRef, which reads the schema from segment 1).
        const qSchema = ast.modelDirective?.schema ?? 'query';
        // a query declared under `model er` is a logical query, `er.query.<name>` (LQ L-1)
        const qPath = ast.modelDirective?.modelCode === 'er' ? `er.query.${q.name}` : `query.${qSchema}.${q.name}`;
        const qname = canonicalizeBindingRef(qPath, pkg, 'query') ?? q.name;
        // NLS-P10 D7 chain: requested language → plain form → `en` → first by code → ''.
        // Presence on the map steps, non-emptiness on the plain one — same as Veles'
        // `selectDescription` and the LSP's `resolveDescription`.
        const locEntries = q.descriptionLocalized?.entries ?? {};
        const predicate =
          preferredLang in locEntries
            ? locEntries[preferredLang]
            : (q.description?.value ||
               ('en' in locEntries
                 ? locEntries.en
                 : locEntries[Object.keys(locEntries).sort()[0]]) ||
               '');
        const provenance = q.sourceText?.value ? queryProvenance(q.sourceText.value, pkg) : [];
        queries.push({ qname, predicate, provenance });
      }
    }
  }
  return { entities, attributes, queries };
}

export function getGraph(
  uri: string,
  documents: Map<string, string>,
  preferredLang = 'en',
): GetGraphResponse | null {
  const content = documents.get(uri);
  if (content === undefined) return null;
  const result = parseString(content, uri);
  const graph = result.ast?.graph;
  if (!result.ast || !graph) return null;

  const schema = graph.schema ?? 'er';
  const objectSet = new Set(graph.objects);

  const allDocs = parseAllDocs(documents);
  const qnameToDef = buildQnameToDef([...allDocs.values()]);
  const mdContext = buildMdContext([...allDocs.values()]);

  const missingObjects = graph.objects.filter((qname) => !qnameToDef.has(qname));

  const nodes: ModelGraphNode[] = [];

  for (const objQname of objectSet) {
    const entry = qnameToDef.get(objQname);
    if (!entry) continue;
    const { def, schemaCode, namespace, packageName } = entry;
    const node = buildNodeForDef(def, schemaCode, namespace, preferredLang, packageName, mdContext);
    if (node) {
      node.qname = objQname;
      nodes.push(node);
    }
  }

  const edges = computeGraphEdges(graph, [...allDocs.values()], qnameToDef);

  const layout: GraphLayoutOutput = {
    viewport: graph.layout?.viewport ? {
      zoom: graph.layout.viewport.zoom,
      panX: graph.layout.viewport.panX,
      panY: graph.layout.viewport.panY,
      displayMode: graph.layout.viewport.displayMode,
    } : undefined,
    nodes: graph.layout?.nodes ?? {},
    edges: graph.layout?.edges ?? {},
  };

  const imports = result.ast.imports.map((imp) => imp.target);

  return { schema, nodes, edges, layout, missingObjects, imports };
}