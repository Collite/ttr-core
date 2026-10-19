// SPDX-License-Identifier: Apache-2.0
import Ajv2020Module from 'ajv/dist/2020.js';
import type { Definition, Document, EntityDef, ObjectValue, Reference, SimpleDataType, StructuredDataType, RoleDef, GraphBlock, CubeletDef, DimensionDef, HierarchyDef, MeasureDef } from '@tatrman/parser';
import type { ProjectSymbolTable, Resolver, ReferenceIndex, ResolvedManifest } from '@tatrman/semantics';
import { buildCanonicalKey } from '@tatrman/semantics';

// Renderable modeling kinds. DS-P3 grew this from db|er to add md (cube/dimension) and cnc
// (concepts modeled as entities + labeled relations). Both flow through the same node/edge
// builders as db/er — cnc reuses the entity/relation paths outright.
export type RenderableSchemaCode = 'db' | 'er' | 'md' | 'cnc';
export type DisplayMode = 'just-names' | 'with-types' | 'with-constraints';
export type SchemaCode = 'db' | 'er' | 'md' | 'binding' | 'query' | 'cnc';

export type Cardinality = 'one' | 'zero-or-one' | 'many' | 'one-or-many';

export interface ModelGraphNode {
  qname: string;
  kind: 'table' | 'view' | 'entity' | 'cubelet' | 'dimension';
  name: string;
  schemaCode: RenderableSchemaCode;
  /** the package, when the backend reports it apart from the qname (a catalog serves `er.entity.item` + `hartland`) */
  pkg?: string;
  label: string;
  sourceUri: string;
  sourceLocation: { line: number; column: number };
  rows: ModelGraphRow[];
}

export interface ModelGraphRow {
  name: string;
  qname: string;
  kind: 'column' | 'attribute' | 'measure' | 'level';
  type: string | null;
  isKey: boolean;
  optional: boolean;
  isNameAttribute: boolean;
  isCodeAttribute: boolean;
}

export interface ModelGraphEdge {
  id: string;
  qname: string;
  /** `hop` — a viewer-derived path through hidden cards (VL V-24), never served by a backend */
  kind: 'fk' | 'relation' | 'grain' | 'hop';
  /** hop edges: the hidden cards in between, as full qnames */
  via?: string[];
  fromNode: string;
  toNode: string;
  fromCardinality: Cardinality | null;
  toCardinality: Cardinality | null;
  sourceUri: string;
  sourceLocation: { line: number; column: number };
}

/**
 * md nodes need sibling context the def alone doesn't carry: a dimension's ordered level-stack
 * lives in referenced `def hierarchy`s, and a cube's measures may be string refs to `def
 * measure`s. Both call sites (buildProjectModelGraph, getGraph) collect this from the parsed
 * asts and thread it into buildNodeForDef. Absent ⇒ md dimensions fall back to inline attributes.
 */
export interface MdGraphContext {
  hierarchyByName: Map<string, HierarchyDef>;
  measureByName: Map<string, MeasureDef>;
}

// NOTE: hierarchies/measures are keyed by bare `def.name` across all md asts (mirroring the
// grammar's own bare intra-model refs). For a single md model this is exact; a multi-package md
// project with same-named hierarchies/measures would shadow — revisit with qname-keying then.
export function buildMdContext(asts: Document[]): MdGraphContext {
  const hierarchyByName = new Map<string, HierarchyDef>();
  const measureByName = new Map<string, MeasureDef>();
  for (const ast of asts) {
    if (ast.modelDirective?.modelCode !== 'md') continue;
    for (const def of ast.definitions) {
      if (def.kind === 'hierarchy') hierarchyByName.set(def.name, def);
      else if (def.kind === 'measure') measureByName.set(def.name, def);
    }
  }
  return { hierarchyByName, measureByName };
}

export interface ModelGraph {
  schemaCode: RenderableSchemaCode;
  nodes: ModelGraphNode[];
  edges: ModelGraphEdge[];
}

export type DataType = DataTypeSimple | DataTypeStructured | undefined;
export interface DataTypeSimple { kind: 'simple'; name: string }
export interface DataTypeStructured { kind: 'structured'; typeName: string; length?: number; precision?: number }

export function renderDataType(t: DataType | SimpleDataType | StructuredDataType | undefined): string | null {
  if (!t) return null;
  if (t.kind === 'simple') return t.name;
  if (t.kind === 'structured') {
    const parts: string[] = [];
    if (typeof t.length === 'number') parts.push(String(t.length));
    if (typeof t.precision === 'number') parts.push(String(t.precision));
    return parts.length === 0 ? t.typeName : `${t.typeName}(${parts.join(',')})`;
  }
  return null;
}

export function parseCardinality(s: string): Cardinality | null {
  switch (s) {
    case '1': return 'one';
    case '0..1': return 'zero-or-one';
    case '0..*':
    case 'n':
    case '*': return 'many';
    case '1..n':
    case '1..*': return 'one-or-many';
    default: return null;
  }
}

export function extractCardinality(obj: ObjectValue | undefined): { from: Cardinality | null; to: Cardinality | null } {
  if (!obj) return { from: null, to: null };
  const lookup = (key: string): Cardinality | null => {
    const entry = obj.entries.find((e) => e.key === key);
    if (!entry || entry.value.kind !== 'string') return null;
    return parseCardinality(entry.value.value);
  };
  return { from: lookup('from'), to: lookup('to') };
}

function buildEdgeForDef(
  def: Definition,
  schemaCode: string,
  namespace: string,
  knownQnames: Set<string>,
  packageName = '',
): ModelGraphEdge | null {
  const defQname = buildQname(schemaCode, namespace, def.kind, [def.name], packageName);
  if (def.kind === 'fk') {
    const fromQname = extractFkRef(def.from, schemaCode, namespace, knownQnames, packageName);
    const toQname = extractFkRef(def.to, schemaCode, namespace, knownQnames, packageName);
    if (fromQname && toQname) {
      return {
        id: defQname,
        qname: defQname,
        kind: 'fk',
        fromNode: fromQname,
        toNode: toQname,
        fromCardinality: null,
        toCardinality: null,
        sourceUri: def.source.file,
        sourceLocation: { line: def.source.line, column: def.source.column },
      };
    }
  } else if (def.kind === 'relation') {
    const fromRef = def.from?.kind === 'id' ? { path: def.from.path, parts: def.from.parts } : null;
    const toRef = def.to?.kind === 'id' ? { path: def.to.path, parts: def.to.parts } : null;
    const fromQname = fromRef ? resolveRef(fromRef, schemaCode, namespace, knownQnames, packageName) : null;
    const toQname = toRef ? resolveRef(toRef, schemaCode, namespace, knownQnames, packageName) : null;
    if (fromQname && toQname) {
      const card = extractCardinality(def.cardinality);
      return {
        id: defQname,
        qname: defQname,
        kind: 'relation',
        fromNode: fromQname,
        toNode: toQname,
        fromCardinality: card.from,
        toCardinality: card.to,
        sourceUri: def.source.file,
        sourceLocation: { line: def.source.line, column: def.source.column },
      };
    }
  }
  return null;
}

/**
 * A cube's `grain: [Dimension.attr, …]` yields one cube→dimension edge per DISTINCT dimension.
 * Dimension endpoints resolve by name against the known md node set (same resolver as relations).
 */
function buildGrainEdges(
  def: Definition,
  schemaCode: string,
  namespace: string,
  knownQnames: Set<string>,
  packageName = '',
): ModelGraphEdge[] {
  if (def.kind !== 'cubelet') return [];
  const cube = def as CubeletDef;
  const cubeQname = buildQname(schemaCode, namespace, def.kind, [def.name], packageName);
  const edges: ModelGraphEdge[] = [];
  const seen = new Set<string>();
  for (const grain of cube.grain) {
    const dimName = grain.split('.')[0];
    if (!dimName || seen.has(dimName)) continue;
    seen.add(dimName);
    const dimQname = resolveRef({ path: dimName, parts: [dimName] }, schemaCode, namespace, knownQnames, packageName);
    if (!dimQname) continue;
    edges.push({
      id: `${cubeQname}->${dimQname}`,
      qname: cubeQname,
      kind: 'grain',
      fromNode: cubeQname,
      toNode: dimQname,
      fromCardinality: null,
      toCardinality: null,
      sourceUri: def.source.file,
      sourceLocation: { line: def.source.line, column: def.source.column },
    });
  }
  return edges;
}

// Layout sidecar types
export interface ViewportState {
  zoom: number;
  panX: number;
  panY: number;
  displayMode: DisplayMode;
}

export interface LayoutFile {
  version: 1;
  viewport?: ViewportState;
  nodes: Record<string, { x: number; y: number }>;
  edges: Record<string, { bendPoints: Array<[number, number]> }>;
}

export function emptyLayout(): LayoutFile {
  return {
    version: 1,
    nodes: {},
    edges: {},
  };
}

const layoutSchema = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'https://tatrman.org/schemas/layout/1.json',
  title: 'Tatrman Modeler layout sidecar',
  type: 'object',
  required: ['version', 'nodes', 'edges'],
  additionalProperties: false,
  properties: {
    version: { const: 1 },
    viewport: { $ref: '#/$defs/viewport' },
    nodes: {
      type: 'object',
      patternProperties: {
        '^.+$': {
          type: 'object',
          required: ['x', 'y'],
          additionalProperties: false,
          properties: { x: { type: 'number' }, y: { type: 'number' } },
        },
      },
      additionalProperties: false,
    },
    edges: {
      type: 'object',
      patternProperties: {
        '^.+$': {
          type: 'object',
          required: ['bendPoints'],
          additionalProperties: false,
          properties: {
            bendPoints: {
              type: 'array',
              items: { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2 },
            },
          },
        },
      },
      additionalProperties: false,
    },
  },
  $defs: {
    viewport: {
      type: 'object',
      required: ['zoom', 'panX', 'panY', 'displayMode'],
      additionalProperties: false,
      properties: {
        zoom: { type: 'number', exclusiveMinimum: 0 },
        panX: { type: 'number' },
        panY: { type: 'number' },
        displayMode: { enum: ['just-names', 'with-types', 'with-constraints'] },
      },
    },
  },
};

// ajv/dist/2020.js has a default export but TypeScript can't infer the constructor
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const AjvClass = (Ajv2020Module as any).default ?? Ajv2020Module;
const ajv = new AjvClass({ strict: false });
const validateLayoutFn = ajv.compile(layoutSchema);

export function validateLayout(unknown: unknown): LayoutFile | null {
  if (validateLayoutFn(unknown)) return unknown as LayoutFile;
  return null;
}

// Symbol detail types
export type PerKindData =
  | { kind: 'table'; columns: ModelGraphRow[]; primaryKey: string[] }
  | { kind: 'view'; columns: ModelGraphRow[] }
  | { kind: 'entity'; attributes: ModelGraphRow[]; nameAttributeQname: string | null; codeAttributeQname: string | null; roleQnames: string[] }
  | { kind: 'fk'; fromQname: string; toQname: string }
  | { kind: 'relation'; fromQname: string; toQname: string; fromCardinality: Cardinality | null; toCardinality: Cardinality | null }
  | { kind: 'role'; labelByLanguage: Record<string, string> }
  | { kind: 'query' }
  | { kind: 'er2dbEntity'; entityQname: string; targetDescription: string }
  | { kind: 'er2dbAttribute'; attributeQname: string; targetDescription: string }
  | { kind: 'er2dbRelation'; relationQname: string; fkQname: string }
  | { kind: 'er2cncRole'; entityQname: string; roleQname: string }
  | { kind: 'other' };

/**
 * The lineage-entry root a symbol can seed (FO-A1 W2, contracts §5). `member` is the
 * NEW nested-member root (a column/attribute/level/measure); the def kinds are carried
 * for symmetry with the perspective's root vocabulary. Present on a `SymbolDetail` iff
 * the lineage perspective accepts that symbol as a root — the detail-panel Lineage
 * affordance renders iff this is present (architecture §2, DS-P4 auto-activation).
 */
export interface LineageRootRef {
  kind: 'member' | 'entity' | 'table' | 'view' | 'query';
  qname: string;
  label: string;
}

export interface SymbolDetail {
  qname: string;
  // `member` for a resolved nested member (column/attribute); else the owning def kind.
  kind: Definition['kind'] | 'member';
  name: string;
  label: string;
  description: string | null;
  tags: string[];
  sourceUri: string;
  sourceLine: number;
  perKindData: PerKindData;
  referencedBy: Array<{ qname: string; sourceUri: string; sourceLine: number }>;
  /**
   * Present iff this detail is a nested member (contracts §5). `memberPath` is the
   * parent-relative segment list; `parent` is the owning def's canonical qname. The
   * member's *semantic* kind (column/attribute) is on `lineageRoot`-adjacent
   * `memberKind` so the entry converges with the chip re-root (which passes an
   * `ObjectKind`), while `lineageRoot.kind` stays `'member'` for the entry classification.
   */
  member?: { memberPath: string[]; parent: string; memberKind: 'column' | 'attribute' | 'measure' | 'level' };
  /** the lineage entry root — drives the detail-panel Lineage affordance (contracts §5). */
  lineageRoot?: LineageRootRef;
}

// v4.0 uniform key. The model/schema/kind come from the owning def's `kind`
// (a column/attribute row passes its parent table/entity kind); `namespace` is
// the file `schema` id (db only). `schemaCode` (the file model directive, or the
// model segment of a symbol's key) only moves a `query`: under `er` it is a
// logical query, `er.query.<name>` (LQ L-1).
function buildQname(schemaCode: string, namespace: string, kind: string, parts: string[], packageName = ''): string {
  return buildCanonicalKey({ packageName, schemaId: namespace, kind, parts, declaringModel: schemaCode });
}

/**
 * Render an er2db `target:` as a short display string for the graph.
 * Recognises the three target-object keys — `table`, `view`, `query` — and the
 * bare-reference form (`target: db.dbo.X`, treated as a table). `target` is an
 * `ObjectValue | Reference`, so we read its `entries`, not flat properties.
 */
export function er2dbTargetDescription(target: ObjectValue | Reference | undefined): string {
  if (!target) return '';
  if ('kind' in target && target.kind === 'object') {
    for (const key of ['table', 'view', 'query'] as const) {
      const entry = target.entries.find((e) => e.key === key);
      if (entry && entry.value.kind === 'id') return `${key}:${entry.value.path}`;
    }
    return '';
  }
  // Bare reference form: `target: db.dbo.X` → behaves as a table target.
  return 'path' in target ? `table:${target.path}` : '';
}

/**
 * NLS-P10 (grammar 0.13, ⚑GXP-D7) — resolve `description:` for a reader.
 *
 * A definition carries the two forms side by side and exactly one is populated:
 * `description` (plain) or `descriptionLocalized` (the `{ en: …, cs: … }` map).
 * The parser deliberately does not fold the map, so every reader picks — and this
 * file is a reader: its output is what the Designer's detail drawer and the LSP's
 * symbol detail show an author. Before this existed, a map-form description read
 * as "No description." in the very editor used to write it.
 *
 * The chain is Veles' D7 chain (`MetadataServiceImpl.selectDescription`), with one
 * difference forced by the caller: `preferredLang` here always has a value (it
 * defaults to `'en'` and otherwise comes from `manifest.preferredLanguage`), so
 * there is no "caller expressed no preference" branch to model.
 *
 *   requested language → plain form → `en` → first entry by language code → null
 *
 * Sorting the last step by language code keeps the shown text independent of the
 * order the author happened to write the block in.
 */
function resolveDescription(def: Definition, preferredLang: string): string | null {
  const plain =
    'description' in def && def.description &&
    (def.description.kind === 'string' || def.description.kind === 'tripleString')
      ? def.description.value
      : null;
  const entries =
    (def as { descriptionLocalized?: { entries: Record<string, string> } }).descriptionLocalized
      ?.entries ?? {};

  // Steps 1 and 3 test PRESENCE, step 2 tests non-emptiness — mirroring Veles'
  // `selectDescription` exactly, where `description` is a non-null String that is `""`
  // when unauthored. An authored-but-empty `{ cs: "" }` therefore wins here just as it
  // does on the wire, instead of quietly promoting the `en` text on one target only.
  if (preferredLang in entries) return entries[preferredLang];
  if (plain !== null && plain !== '') return plain;
  if ('en' in entries) return entries.en;
  const firstByCode = Object.keys(entries).sort()[0];
  return firstByCode === undefined ? null : entries[firstByCode];
}

function getDisplayLabel(def: Definition, preferredLang: string): string {
  if (def.kind === 'entity') {
    const entity = def as EntityDef;
    if (entity.displayLabel && entity.displayLabel.kind === 'localizedString') {
      const entry = entity.displayLabel.entries[preferredLang];
      if (entry) return entry;
    }
  }
  // A table has no `displayLabel`, so its description doubles as one — through the
  // same D7 chain, or a bilingual table would fall through to its bare name.
  if (def.kind === 'table') {
    const described = resolveDescription(def, preferredLang);
    if (described !== null) return described;
  }
  return def.name;
}

function getDescription(def: Definition, preferredLang: string): string | null {
  return resolveDescription(def, preferredLang);
}

function buildSymbolDetailForDef(
  def: Definition,
  schemaCode: string,
  namespace: string,
  preferredLang: string,
  documentUri: string,
  refIndex: ReferenceIndex,
  packageName = ''
): SymbolDetail {
  const qname = buildQname(schemaCode, namespace, def.kind, [def.name], packageName);
  const tags: string[] = ('tags' in def ? (def as { tags?: string[] }).tags : []) ?? [];

  let perKindData: PerKindData = { kind: 'other' };
  if (def.kind === 'table' && def.columns) {
    const columns = (def.columns ?? []).map(col => ({
      name: col.name,
      qname: buildQname(schemaCode, namespace, def.kind, [def.name, col.name], packageName),
      kind: 'column' as const,
      type: renderDataType(col.type),
      isKey: !!(col.isKey || (def.primaryKey ?? []).includes(col.name)),
      optional: !!col.optional,
      isNameAttribute: false,
      isCodeAttribute: false,
    }));
    perKindData = { kind: 'table', columns, primaryKey: def.primaryKey ?? [] };
  } else if (def.kind === 'view' && def.columns) {
    const columns = (def.columns ?? []).map(col => ({
      name: col.name,
      qname: buildQname(schemaCode, namespace, def.kind, [def.name, col.name], packageName),
      kind: 'column' as const,
      type: renderDataType(col.type),
      isKey: false,
      optional: !!col.optional,
      isNameAttribute: false,
      isCodeAttribute: false,
    }));
    perKindData = { kind: 'view', columns };
  } else if (def.kind === 'entity') {
    const nameAttrPath = def.nameAttribute?.path;
    const codeAttrPath = def.codeAttribute?.path;
    const attributes = (def.attributes ?? []).map(attr => ({
      name: attr.name,
      qname: buildQname(schemaCode, namespace, def.kind, [def.name, attr.name], packageName),
      kind: 'attribute' as const,
      type: renderDataType(attr.type),
      isKey: !!attr.isKey,
      optional: !!attr.optional,
      isNameAttribute: attr.name === nameAttrPath,
      isCodeAttribute: attr.name === codeAttrPath,
    }));
    perKindData = {
      kind: 'entity',
      attributes,
      nameAttributeQname: def.nameAttribute ? buildQname(schemaCode, namespace, def.kind, [def.name, def.nameAttribute.path], packageName) : null,
      codeAttributeQname: def.codeAttribute ? buildQname(schemaCode, namespace, def.kind, [def.name, def.codeAttribute.path], packageName) : null,
      roleQnames: def.roles ?? [],
    };
  } else if (def.kind === 'fk') {
    const fromRef = def.from?.kind === 'id' ? { path: def.from.path, parts: def.from.parts } : null;
    const toRef = def.to?.kind === 'id' ? { path: def.to.path, parts: def.to.parts } : null;
    const fromQname = fromRef ? [schemaCode, namespace, fromRef.path].filter(s => s !== '').join('.') : null;
    const toQname = toRef ? [schemaCode, namespace, toRef.path].filter(s => s !== '').join('.') : null;
    perKindData = { kind: 'fk', fromQname: fromQname ?? '', toQname: toQname ?? '' };
  } else if (def.kind === 'relation') {
    const fromRef = def.from?.kind === 'id' ? { path: def.from.path, parts: def.from.parts } : null;
    const toRef = def.to?.kind === 'id' ? { path: def.to.path, parts: def.to.parts } : null;
    const fromQname = fromRef ? [schemaCode, namespace, fromRef.path].filter(s => s !== '').join('.') : null;
    const toQname = toRef ? [schemaCode, namespace, toRef.path].filter(s => s !== '').join('.') : null;
    const card = extractCardinality(def.cardinality);
    perKindData = { kind: 'relation', fromQname: fromQname ?? '', toQname: toQname ?? '', fromCardinality: card.from, toCardinality: card.to };
  } else if (def.kind === 'role') {
    const labelByLanguage: Record<string, string> = {};
    const role = def as RoleDef;
    if (role.label && role.label.kind === 'localizedString') {
      Object.assign(labelByLanguage, role.label.entries);
    }
    perKindData = { kind: 'role', labelByLanguage };
  } else if (def.kind === 'query') {
    perKindData = { kind: 'query' };
  } else if (def.kind === 'er2dbEntity') {
    const e2 = def as { entity?: { path: string }; target?: ObjectValue | Reference };
    const entityRef = e2.entity?.path ?? '';
    const targetDesc = er2dbTargetDescription(e2.target);
    perKindData = { kind: 'er2dbEntity', entityQname: entityRef, targetDescription: targetDesc };
  } else if (def.kind === 'er2dbAttribute') {
    const e2a = def as { attribute?: { path: string }; target?: ObjectValue | Reference };
    const attrRef = e2a.attribute?.path ?? '';
    const targetDesc = er2dbTargetDescription(e2a.target);
    perKindData = { kind: 'er2dbAttribute', attributeQname: attrRef, targetDescription: targetDesc };
  } else if (def.kind === 'er2dbRelation') {
    const e2r = def as { relation?: { path: string }; fk?: { path: string } };
    perKindData = {
      kind: 'er2dbRelation',
      relationQname: e2r.relation?.path ?? '',
      fkQname: e2r.fk?.path ?? '',
    };
  } else if (def.kind === 'er2cncRole') {
    const e2c = def as { entity?: { path: string }; role?: { path: string } };
    perKindData = {
      kind: 'er2cncRole',
      entityQname: e2c.entity?.path ?? '',
      roleQname: e2c.role?.path ?? '',
    };
  }

  const seen = new Set<string>();
  const referencedBy = refIndex.findByQname(qname).map(loc => ({
    qname: loc.referrerQname ?? loc.targetQname,
    sourceUri: loc.documentUri,
    sourceLine: loc.source.line,
  })).filter(loc => {
    if (seen.has(loc.qname)) return false;
    seen.add(loc.qname);
    return true;
  });

  return {
    qname,
    kind: def.kind,
    name: def.name,
    label: getDisplayLabel(def, preferredLang),
    description: getDescription(def, preferredLang),
    tags,
    sourceUri: documentUri,
    sourceLine: def.source.line,
    perKindData,
    referencedBy,
  };
}

export function buildSymbolDetail(
  qname: string,
  symbols: ProjectSymbolTable,
  resolver: Resolver,
  refIndex: ReferenceIndex,
  manifest: ResolvedManifest,
  getDocument: (uri: string) => string | null,
  parseDocument: (content: string, uri: string) => { ast?: Document | null }
): SymbolDetail | null {
  const symbol = symbols.get(qname);
  // v1 limitation lifted (FO-A1 W2, contracts §5): a qname is a NESTED MEMBER when
  // it is absent from the top-level table, OR present but registered as a member
  // (columns/attributes register with a `parent`). Either way, resolve it against
  // the parent def's roster (the same rosters buildSymbolDetailForDef builds) — the
  // v1 top-level def lookup can't produce a member detail.
  const isMemberEntry = symbol && (symbol.parent !== undefined || symbol.kind === 'column' || symbol.kind === 'attribute');
  if (!symbol || isMemberEntry) {
    return resolveMemberSymbolDetail(qname, symbols, resolver, refIndex, manifest, getDocument, parseDocument);
  }

  // The v4.0 key is `[pkg.]<model>.<schema?>.<kind>.<name>`. Recover the def by
  // its name + kind (recorded on the symbol entry), and the db schema handle (db
  // only) from the segment right after the `db` model segment. The package and
  // schema are threaded back into the key builders so the detail's qnames match
  // the canonical symbol-table keys everywhere else.
  const localQname = symbol.packageName && qname.startsWith(`${symbol.packageName}.`)
    ? qname.slice(symbol.packageName.length + 1)
    : qname;
  const localSegs = localQname.split('.');
  const schemaCode = localSegs[0] ?? 'db';
  const schemaId = schemaCode === 'db' ? (localSegs[1] ?? '') : '';

  const realDef = findDefByQname(symbol.documentUri, symbol.name, symbol.kind, getDocument, parseDocument);
  if (!realDef) return null;

  const detail = buildSymbolDetailForDef(
    realDef,
    schemaCode,
    schemaId,
    manifest.preferredLanguage,
    symbol.documentUri,
    refIndex,
    symbol.packageName
  );
  // The builder already produces the canonical package-qualified key; keep the
  // asked-for qname verbatim so callers key the detail by the same id.
  if (detail) detail.qname = qname;
  return detail;
}

// ---- member (nested-qname) resolution (FO-A1 W2, contracts §5) ----

type MemberKind = 'column' | 'attribute' | 'measure' | 'level';
interface MemberRow { name: string; qname: string; kind: MemberKind }

/** The nested members a resolved parent detail exposes (columns for table/view,
 *  attributes for entity). md measures/levels are not in perKindData yet → not
 *  resolvable this pass (A1-D6 watch-row; Worker-only member depth). */
function memberRowsOf(detail: SymbolDetail): MemberRow[] {
  const pk = detail.perKindData;
  if (pk.kind === 'table' || pk.kind === 'view') {
    return pk.columns.map((c) => ({ name: c.name, qname: c.qname, kind: 'column' as const }));
  }
  if (pk.kind === 'entity') {
    return pk.attributes.map((a) => ({ name: a.name, qname: a.qname, kind: 'attribute' as const }));
  }
  return [];
}

/** The member SymbolRefs a parent exposes (contracts §5 `listSymbols` `includeMembers`). */
export interface MemberSymbolRef {
  qname: string;
  kind: 'member';
  name: string;
  packageName: string | null;
  memberPath: string[];
  parent: string;
}
export function memberRefsOf(detail: SymbolDetail, packageName: string | null): MemberSymbolRef[] {
  return memberRowsOf(detail).map((r) => ({
    qname: r.qname,
    kind: 'member' as const,
    name: r.name,
    packageName,
    memberPath: [r.name],
    parent: detail.qname,
  }));
}

/**
 * Resolve a nested-member qname (`…<parent>.<member>`) that the top-level symbol
 * table missed. The parent def is resolved through the normal builder (so a 4-part
 * package segment resolves the same way); the member is matched in the parent's
 * roster. Returns a member `SymbolDetail` carrying `member` + `lineageRoot`
 * (`kind:'member'`), or null for a genuine miss (never throws — contracts §5).
 */
function resolveMemberSymbolDetail(
  qname: string,
  symbols: ProjectSymbolTable,
  resolver: Resolver,
  refIndex: ReferenceIndex,
  manifest: ResolvedManifest,
  getDocument: (uri: string) => string | null,
  parseDocument: (content: string, uri: string) => { ast?: Document | null }
): SymbolDetail | null {
  const lastDot = qname.lastIndexOf('.');
  if (lastDot <= 0) return null;
  const parentQname = qname.slice(0, lastDot);
  const memberName = qname.slice(lastDot + 1);
  if (!memberName) return null;

  const parentDetail = buildSymbolDetail(parentQname, symbols, resolver, refIndex, manifest, getDocument, parseDocument);
  if (!parentDetail || parentDetail.kind === 'member') return null;

  const rows = memberRowsOf(parentDetail);
  const member = rows.find((r) => r.qname === qname) ?? rows.find((r) => r.name === memberName);
  if (!member) return null;

  const seen = new Set<string>();
  const referencedBy = refIndex.findByQname(qname)
    .map((loc) => ({ qname: loc.referrerQname ?? loc.targetQname, sourceUri: loc.documentUri, sourceLine: loc.source.line }))
    .filter((loc) => (seen.has(loc.qname) ? false : (seen.add(loc.qname), true)));

  return {
    qname,
    kind: 'member',
    name: memberName,
    label: memberName,
    description: null,
    tags: [],
    sourceUri: parentDetail.sourceUri,
    sourceLine: parentDetail.sourceLine,
    perKindData: { kind: 'other' },
    referencedBy,
    member: { memberPath: [memberName], parent: parentQname, memberKind: member.kind },
    lineageRoot: { kind: 'member', qname, label: memberName },
  };
}

// v1 limitation: only top-level defs (table / view / entity / role / relation) are
// looked up by their name + kind. Nested members (columns/attributes) are not
// resolved here; the Designer inspector only opens on top-level nodes in v1.
function findDefByQname(
  uri: string,
  name: string,
  // Accepts nested world-member kinds too (v4.1; the 0.16 automation members); they
  // never match a top-level def, so lookups for them return null.
  kind: Definition['kind'] | 'engine' | 'executor' | 'storage' | 'worldSchema' | 'identity' | 'deployment' | 'process',
  getDocument: (uri: string) => string | null,
  parseDocument: (content: string, uri: string) => { ast?: Document | null }
): Definition | null {
  const content = getDocument(uri);
  if (!content) return null;
  const result = parseDocument(content, uri);
  if (!result.ast) return null;
  for (const def of result.ast.definitions) {
    if (def.name === name && def.kind === kind) return def;
  }
  return null;
}

function resolveRef(ref: { path: string; parts: string[] }, schemaCode: string, namespace: string, knownQnames: Set<string>, packageName = ''): string | null {
  const parts = ref.parts;
  // 1. Ref as written: already fully/cross-package qualified (v1.1) or v1
  //    non-package (`er.entity.x`, `db.dbo.TABLE.COLUMN`). Drop trailing parts
  //    until a known qname matches, so an FK column ref collapses to its table
  //    node (FKs target columns but the edge is node-to-node).
  for (let i = parts.length; i >= 1; i--) {
    const candidate = parts.slice(0, i).join('.');
    if (knownQnames.has(candidate)) return candidate;
  }
  // 2. Package-relative ref (e.g. `er.entity.artikl` written inside package
  //    `billing.invoicing`): prefix the current package, again dropping trailing
  //    parts. This is the common case in packaged projects, where the .ttrg
  //    object set is package-qualified but same-package refs are not.
  if (packageName) {
    for (let i = parts.length; i >= 1; i--) {
      const candidate = `${packageName}.${parts.slice(0, i).join('.')}`;
      if (knownQnames.has(candidate)) return candidate;
    }
  }
  // 3. Bare ref relative to the current schema/namespace: `TABLE` or `TABLE.COLUMN`.
  for (let i = 1; i <= parts.length; i++) {
    const base = [schemaCode, namespace, ...parts.slice(0, i)].filter(s => s !== '').join('.');
    if (knownQnames.has(base)) return base;
    if (packageName && knownQnames.has(`${packageName}.${base}`)) return `${packageName}.${base}`;
  }
  // 4. v4.0: the node key carries a kind segment the written ref omits
  //    (`db.dbo.PRODUCTS` → `db.dbo.table.PRODUCTS`). Collapse to the node whose
  //    trailing name matches — trying the deepest name part first, so an fk
  //    column ref (`…PRODUCTS.PRODUCT_ID`) falls back to its table node.
  for (let j = parts.length - 1; j >= 0; j--) {
    const name = parts[j];
    for (const q of knownQnames) {
      if (q === name || q.endsWith(`.${name}`)) return q;
    }
  }
  return null;
}

export function buildModelGraph(ast: Document, schema: RenderableSchemaCode, preferredLang = 'en'): ModelGraph {
  return buildProjectModelGraph([ast], schema, preferredLang);
}

// computeGraphEdges: objects in .ttrg are expected to be fully-qualified qnames
// (contract §7.1, decision D2). Endpoints resolve via resolveRef against the
// objects set -- NOT the six-step Resolver. Bare/wildcard-imported objects in
// a .ttrg will not resolve here.
export function computeGraphEdges(
  graph: GraphBlock,
  asts: Document[],
  _qnameToDef?: Map<string, { def: Definition; schemaCode: string; namespace: string; packageName: string }>
): ModelGraphEdge[] {
  const objectSet = new Set(graph.objects ?? []);
  if (objectSet.size === 0) return [];

  const edges: ModelGraphEdge[] = [];

  for (const ast of asts) {
    const schemaCode = ast.modelDirective?.modelCode ?? 'er';
    const namespace = ast.modelDirective?.schema ?? '';
    const packageName = ast.packageDecl?.name ?? '';

    for (const def of ast.definitions) {
      const defQname = buildQname(schemaCode, namespace, def.kind, [def.name], packageName);
      if (!objectSet.has(defQname)) continue;
      if (def.kind === 'cubelet') {
        edges.push(...buildGrainEdges(def, schemaCode, namespace, objectSet, packageName));
      } else {
        const edge = buildEdgeForDef(def, schemaCode, namespace, objectSet, packageName);
        if (edge) edges.push(edge);
      }
    }
  }

  return edges;
}

export function buildNodeForDef(
  def: Definition,
  schemaCode: string,
  namespace: string,
  preferredLang: string,
  packageName = '',
  mdContext?: MdGraphContext,
): ModelGraphNode | null {
  if (def.kind === 'cubelet') {
    // measures render as the cube's rows (the star-glyph / er-dialect both read them here).
    const cube = def as CubeletDef;
    const rows: ModelGraphRow[] = cube.measures.map((m) => {
      const name = typeof m === 'string' ? m : m.name;
      const measureDef = typeof m === 'string' ? mdContext?.measureByName.get(m) : m;
      return {
        name,
        qname: buildQname(schemaCode, namespace, def.kind, [def.name, name], packageName),
        kind: 'measure' as const,
        type: measureDef?.aggregation?.default ?? measureDef?.measureClass ?? null,
        isKey: false,
        optional: false,
        isNameAttribute: false,
        isCodeAttribute: false,
      };
    });
    return {
      qname: buildQname(schemaCode, namespace, def.kind, [def.name], packageName),
      kind: 'cubelet',
      name: def.name,
      schemaCode: schemaCode as RenderableSchemaCode,
      label: def.name,
      sourceUri: def.source.file,
      sourceLocation: { line: def.source.line, column: def.source.column },
      rows,
    };
  } else if (def.kind === 'dimension') {
    // the level-stack is the ordered levels of the dimension's hierarchies (order preserved);
    // with no hierarchy context, fall back to the dimension's own attributes.
    const dim = def as DimensionDef;
    const levelRows: ModelGraphRow[] = [];
    for (const hName of dim.hierarchies ?? []) {
      const h = mdContext?.hierarchyByName.get(hName);
      if (!h) continue;
      for (const lvl of h.levels) {
        levelRows.push({
          name: lvl.attribute,
          qname: buildQname(schemaCode, namespace, def.kind, [def.name, lvl.attribute], packageName),
          kind: 'level',
          type: lvl.via ?? null, // `via <map>` = the calc map driving this level
          isKey: dim.key === lvl.attribute,
          optional: false,
          isNameAttribute: false,
          isCodeAttribute: false,
        });
      }
    }
    const rows: ModelGraphRow[] = levelRows.length > 0
      ? levelRows
      : (dim.attributes ?? []).map((attr) => ({
          name: attr.name,
          qname: buildQname(schemaCode, namespace, def.kind, [def.name, attr.name], packageName),
          kind: 'level' as const,
          type: null,
          isKey: dim.key === attr.name || !!attr.isKey,
          optional: false,
          isNameAttribute: false,
          isCodeAttribute: false,
        }));
    return {
      qname: buildQname(schemaCode, namespace, def.kind, [def.name], packageName),
      kind: 'dimension',
      name: def.name,
      schemaCode: schemaCode as RenderableSchemaCode,
      label: def.name,
      sourceUri: def.source.file,
      sourceLocation: { line: def.source.line, column: def.source.column },
      rows,
    };
  } else if (def.kind === 'table') {
    const rows: ModelGraphRow[] = (def.columns ?? []).map(col => ({
      name: col.name,
      qname: buildQname(schemaCode, namespace, def.kind, [def.name, col.name], packageName),
      kind: 'column' as const,
      type: renderDataType(col.type),
      isKey: !!(col.isKey || (def.primaryKey ?? []).includes(col.name)),
      optional: !!col.optional,
      isNameAttribute: false,
      isCodeAttribute: false,
    }));
    return {
      qname: buildQname(schemaCode, namespace, def.kind, [def.name], packageName),
      kind: 'table',
      name: def.name,
      schemaCode: schemaCode as RenderableSchemaCode,
      label: def.name,
      sourceUri: def.source.file,
      sourceLocation: { line: def.source.line, column: def.source.column },
      rows,
    };
  } else if (def.kind === 'view') {
    const rows: ModelGraphRow[] = (def.columns ?? []).map(col => ({
      name: col.name,
      qname: buildQname(schemaCode, namespace, def.kind, [def.name, col.name], packageName),
      kind: 'column' as const,
      type: renderDataType(col.type),
      isKey: false,
      optional: !!col.optional,
      isNameAttribute: false,
      isCodeAttribute: false,
    }));
    return {
      qname: buildQname(schemaCode, namespace, def.kind, [def.name], packageName),
      kind: 'view',
      name: def.name,
      schemaCode: schemaCode as RenderableSchemaCode,
      label: def.name,
      sourceUri: def.source.file,
      sourceLocation: { line: def.source.line, column: def.source.column },
      rows,
    };
  } else if (def.kind === 'entity') {
    const entity = def as EntityDef;
    const nameAttrPath = entity.nameAttribute?.path;
    const codeAttrPath = entity.codeAttribute?.path;
    const rows: ModelGraphRow[] = (entity.attributes ?? []).map(attr => ({
      name: attr.name,
      qname: buildQname(schemaCode, namespace, def.kind, [def.name, attr.name], packageName),
      kind: 'attribute' as const,
      type: renderDataType(attr.type),
      isKey: !!attr.isKey,
      optional: !!attr.optional,
      isNameAttribute: attr.name === nameAttrPath,
      isCodeAttribute: attr.name === codeAttrPath,
    }));
    return {
      qname: buildQname(schemaCode, namespace, def.kind, [def.name], packageName),
      kind: 'entity',
      name: def.name,
      schemaCode: schemaCode as RenderableSchemaCode,
      label: getDisplayLabel(def, preferredLang),
      sourceUri: def.source.file,
      sourceLocation: { line: def.source.line, column: def.source.column },
      rows,
    };
  }
  return null;
}

export function buildProjectModelGraph(asts: Document[], schema: RenderableSchemaCode, preferredLang = 'en'): ModelGraph {
  const nodes: ModelGraphNode[] = [];
  const edges: ModelGraphEdge[] = [];
  const knownNodes = new Map<string, { def: Definition; qname: string; schemaCode: string; namespace: string; packageName: string }>();

  for (const ast of asts) {
    if (ast.modelDirective?.modelCode && ast.modelDirective.modelCode !== schema) continue;

    const schemaCode = ast.modelDirective?.modelCode ?? schema;
    const namespace = ast.modelDirective?.schema ?? '';
    const packageName = ast.packageDecl?.name ?? '';

    for (const def of ast.definitions) {
      if (def.kind === 'table' || def.kind === 'view' || def.kind === 'entity'
        || def.kind === 'cubelet' || def.kind === 'dimension') {
        const qname = buildQname(schemaCode, namespace, def.kind, [def.name], packageName);
        knownNodes.set(qname, { def, qname, schemaCode, namespace, packageName });
      }
    }
  }

  const knownQnames = new Set(knownNodes.keys());
  const mdContext = buildMdContext(asts);

  for (const [, { def, schemaCode, namespace, packageName }] of knownNodes) {
    const node = buildNodeForDef(def, schemaCode, namespace, preferredLang, packageName, mdContext);
    if (node) nodes.push(node);
  }

  for (const ast of asts) {
    if (ast.modelDirective?.modelCode && ast.modelDirective.modelCode !== schema) continue;

    const schemaCode = ast.modelDirective?.modelCode ?? schema;
    const namespace = ast.modelDirective?.schema ?? '';
    const packageName = ast.packageDecl?.name ?? '';

    for (const def of ast.definitions) {
      if (def.kind === 'fk' || def.kind === 'relation') {
        const edge = buildEdgeForDef(def, schemaCode, namespace, knownQnames, packageName);
        if (edge) edges.push(edge);
      } else if (def.kind === 'cubelet') {
        edges.push(...buildGrainEdges(def, schemaCode, namespace, knownQnames, packageName));
      }
    }
  }

  return { schemaCode: schema as RenderableSchemaCode, nodes, edges };
}

// FK edges are table-to-table; we pick the first column to derive the source
// table — multi-column FKs collapse to one edge.
function extractFkRef(pv: import('@tatrman/parser').PropertyValue | undefined, schemaCode: string, namespace: string, knownQnames: Set<string>, packageName = ''): string | null {
  if (!pv) return null;
  if (pv.kind === 'id') {
    return resolveRef({ path: pv.path, parts: pv.parts }, schemaCode, namespace, knownQnames, packageName);
  }
  if (pv.kind === 'list' && pv.items[0]?.kind === 'id') {
    return resolveRef({ path: pv.items[0].path, parts: pv.items[0].parts }, schemaCode, namespace, knownQnames, packageName);
  }
  return null;
}