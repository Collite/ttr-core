// SPDX-License-Identifier: Apache-2.0
import type {
  Document,
  EntityDef,
  BindingColumnValue,
  BindingProperty,
  ObjectValue,
  Reference,
  RelationDef,
} from '@tatrman/parser';
import { Resolver } from './resolver.js';
import { defaultSchemaForKind } from './default-schema.js';
import { enclosingQnameOf } from './reference-index.js';

/**
 * A navigable reference discovered inside a v2.1 inline `binding:` property,
 * already resolved to the db column (or other target) it points at.
 *
 * Inline-mapping column references can't go through the generic
 * `collectReferences` + `resolveReference` path: the bare id (`binding:
 * p_promo_sk`) names a *db column*, but it's written in `er`-entity scope, so it
 * only resolves once you know the table the enclosing entity maps to. This
 * module computes that bridge.
 *
 * Covered forms:
 *   Attribute-level (Increment A) — column resolved against the enclosing
 *   entity's inline `mapping.target.table`:
 *     - `binding: COL`                         (bare id)
 *     - `binding: { target: COL }`             (target bare id)
 *     - `binding: { target: { column: COL } }` (target object)
 *   Entity-level `columns:` map (Increment B) — each value resolved against the
 *   same target table, in all three column-value shapes above.
 *   Relation `fk` (Increment B) — `binding: db.dbo.fk_x` / `binding: { fk:
 *   db.dbo.fk_x }`; the fk is a top-level `def fk`, so it resolves directly.
 *
 * The enclosing entity's target table is taken from its inline `mapping {
 * target: { table: … } }` when present, otherwise from an explicit `def
 * er2db_entity <name>` declared anywhere in the project (its target table ref is
 * cached on the symbol — see SymbolEntry.targetTableRef).
 */
export interface BindingReference {
  /** The column-name token (its source span drives highlight / hover / definition). */
  ref: Reference;
  /** Resolved db column symbol qname, e.g. `<pkg>.db.dbo.promotion.p_promo_sk`. */
  targetQname: string;
  /** The enclosing entity's qname (the referrer), or null if it can't be derived. */
  referrerQname: string | null;
}

/** Reference vs ObjectValue discriminator: ObjectValue carries a `kind`. */
function isReference(v: ObjectValue | Reference): v is Reference {
  return !('kind' in v);
}

/**
 * Resolve the db-table (or db-view) qname an entity maps to. Prefers the
 * entity's inline `mapping.target.table` / `mapping.target.view`; falls back to
 * the target declared on an explicit `def er2db_entity <name>` (Increment B2)
 * for entities with no inline block. Views resolve identically to tables —
 * their columns are registered as child symbols, so column bridging works the
 * same way.
 */
function entityTargetTableQname(
  entity: EntityDef,
  resolver: Resolver,
  schemaCode: string,
  namespace: string,
  packageName: string,
): string | undefined {
  const resolveTablePath = (path: string, parts: string[]): string | undefined => {
    const res = resolver.resolveReference({ path, parts }, { schemaCode, namespace, packageName });
    return res.resolved ? res.symbol.qname : undefined;
  };

  // 1. Inline `mapping { target: { table: … } }` (or `{ view: … }`) on the entity.
  const m = entity.binding;
  if (m && m.kind === 'block' && m.target) {
    let tableRef: Reference | undefined;
    if (isReference(m.target)) {
      tableRef = m.target;
    } else {
      const tableEntry = m.target.entries.find((e) => e.key === 'table' || e.key === 'view');
      if (tableEntry && tableEntry.value.kind === 'id') {
        tableRef = { path: tableEntry.value.path, parts: tableEntry.value.parts, source: tableEntry.value.source };
      }
    }
    if (tableRef) {
      const q = resolveTablePath(tableRef.path, tableRef.parts);
      if (q) return q;
    }
  }

  // 2. Explicit `def er2db_entity <name> { target: { table: … } }` elsewhere in
  //    the project. The binding-schema convention is no namespace (see
  //    mapping-synthesizer.synthQname), so the qname is `<pkg>.binding.er2dbEntity.
  //    <name>`; try the entity's package and the package-less form.
  const candidates = packageName
    ? [`${packageName}.binding.er2dbEntity.${entity.name}`, `binding.er2dbEntity.${entity.name}`]
    : [`binding.er2dbEntity.${entity.name}`];
  for (const qname of candidates) {
    const sym = resolver.getSymbol(qname);
    if (sym?.targetTableRef) {
      const q = resolveTablePath(sym.targetTableRef, sym.targetTableRef.split('.'));
      if (q) return q;
    }
  }

  return undefined;
}

/** Extract the column-name reference from an attribute-level `binding:` property. */
function attributeBindingColumnRef(m: BindingProperty | undefined): Reference | undefined {
  if (!m) return undefined;
  if (m.kind === 'bareId') return m.id;

  // block form: `binding: { target: … }`
  if (!m.target) return undefined;
  if (isReference(m.target)) return m.target; // binding: { target: COL }

  // binding: { target: { column: COL } }
  const colEntry = m.target.entries.find((e) => e.key === 'column');
  if (colEntry && colEntry.value.kind === 'id') {
    return { path: colEntry.value.path, parts: colEntry.value.parts, source: colEntry.value.source };
  }
  return undefined;
}

/**
 * Extract the column reference from one entry of an entity-level `columns:` map.
 * The entry value is `COL` (bare id) or `{ target: COL }` / `{ target: {
 * column: COL } }` (object form).
 */
function columnRefFromColumnValue(v: BindingColumnValue): Reference | undefined {
  if (v.kind === 'bareId') return v.id;

  const targetEntry = v.object.entries.find((e) => e.key === 'target');
  if (!targetEntry) return undefined;
  const tv = targetEntry.value;
  if (tv.kind === 'id') return { path: tv.path, parts: tv.parts, source: tv.source };
  if (tv.kind === 'object') {
    const colEntry = tv.entries.find((e) => e.key === 'column');
    if (colEntry && colEntry.value.kind === 'id') {
      return { path: colEntry.value.path, parts: colEntry.value.parts, source: colEntry.value.source };
    }
  }
  return undefined;
}

/** Extract the fk reference from a relation's `binding:` property. */
function relationFkRef(m: BindingProperty | undefined): Reference | undefined {
  if (!m) return undefined;
  if (m.kind === 'bareId') return m.id; // binding: db.dbo.fk_x
  return m.fk;                          // binding: { fk: db.dbo.fk_x }
}

/**
 * Collect every resolvable inline attribute-mapping column reference in a
 * document. Unresolvable ones (no entity target table, or the column doesn't
 * exist in the mapped table) are silently skipped — they behave like any other
 * dangling reference.
 */
export function collectBindingReferences(
  ast: Document,
  resolver: Resolver,
  schemaCode: string,
  namespace: string,
  packageName: string,
): BindingReference[] {
  const out: BindingReference[] = [];

  for (const def of ast.definitions) {
    if (def.kind === 'entity') {
      collectFromEntity(def, resolver, schemaCode, namespace, packageName, out);
    } else if (def.kind === 'relation') {
      collectFromRelation(def, resolver, schemaCode, namespace, packageName, out);
    }
  }

  return out;
}

function collectFromEntity(
  def: EntityDef,
  resolver: Resolver,
  schemaCode: string,
  namespace: string,
  packageName: string,
  out: BindingReference[],
): void {
  const effSchema = schemaCode || defaultSchemaForKind(def.kind);
  const tableQname = entityTargetTableQname(def, resolver, effSchema, namespace, packageName);
  if (!tableQname) return;

  const referrerQname = enclosingQnameOf(def, effSchema, namespace, packageName) ?? null;

  const pushColumn = (colRef: Reference | undefined): void => {
    if (!colRef) return;
    const columnQname = `${tableQname}.${colRef.parts[colRef.parts.length - 1]}`;
    if (resolver.getSymbol(columnQname)) {
      out.push({ ref: colRef, targetQname: columnQname, referrerQname });
    }
  };

  // Attribute-level mappings (Increment A).
  for (const attr of def.attributes ?? []) {
    pushColumn(attributeBindingColumnRef(attr.binding));
  }

  // Entity-level `columns:` map (Increment B).
  if (def.binding?.kind === 'block') {
    for (const entry of def.binding.columns ?? []) {
      pushColumn(columnRefFromColumnValue(entry.value));
    }
  }
}

function collectFromRelation(
  def: RelationDef,
  resolver: Resolver,
  schemaCode: string,
  namespace: string,
  packageName: string,
  out: BindingReference[],
): void {
  const fkRef = relationFkRef(def.binding);
  if (!fkRef) return;

  const effSchema = schemaCode || defaultSchemaForKind(def.kind);
  // The fk is a fully-qualified `db.dbo.fk_x`; it resolves directly, no table bridge.
  const res = resolver.resolveReference(
    { path: fkRef.path, parts: fkRef.parts },
    { schemaCode: effSchema, namespace, packageName },
  );
  if (!res.resolved) return;
  out.push({
    ref: fkRef,
    targetQname: res.symbol.qname,
    referrerQname: enclosingQnameOf(def, effSchema, namespace, packageName) ?? null,
  });
}

/** The object kind each binding-target key names; a bare `target: X` may name any of them. */
const TARGET_KINDS = new Set(['table', 'view', 'query']);

/**
 * Every resolvable binding *target* in a document — the table, view or query an entity's inline `binding: {
 * target: … }` or an explicit `def er2db_entity { target: … }` points at — as a reference to that object, so
 * find-references, rename and go-to-definition on a table, a view or a query (either lane) reach the entities
 * bound to it (LQ-14). A target that does not resolve to an object of its key's kind is skipped, like any other
 * dangling reference.
 *
 * A short `query:` target (fewer than three parts) follows the metadata loader: the db-lane default
 * `db.dbo.<name>` first, then the name as written, then the logical query `er.query.<name>` — so an entity
 * stacked on a query of its own name binds to the query, not to itself.
 */
export function collectBindingTargetReferences(
  ast: Document,
  resolver: Resolver,
  schemaCode: string,
  namespace: string,
  packageName: string,
): BindingReference[] {
  const out: BindingReference[] = [];

  for (const def of ast.definitions) {
    let target: ObjectValue | Reference | undefined;
    if (def.kind === 'entity') target = def.binding?.kind === 'block' ? def.binding.target : undefined;
    else if (def.kind === 'er2dbEntity') target = def.target;
    if (!target) continue;

    const effSchema = schemaCode || defaultSchemaForKind(def.kind);
    const referrerQname = enclosingQnameOf(def, effSchema, namespace, packageName) ?? null;
    const ctx = { schemaCode: effSchema, namespace, packageName };

    for (const { key, ref } of bindingTargets(target)) {
      const kinds = key ? new Set([key]) : TARGET_KINDS;
      const short = key === 'query' && ref.parts.length < 3;
      const name = ref.parts[ref.parts.length - 1];
      const candidates = short ? [`db.dbo.${ref.path}`, ref.path, `er.query.${name}`] : [ref.path];
      for (const path of candidates) {
        const res = resolver.resolveReference({ path, parts: path.split('.') }, ctx);
        if (res.resolved && kinds.has(res.symbol.kind)) {
          out.push({ ref, targetQname: res.symbol.qname, referrerQname });
          break;
        }
      }
    }
  }

  return out;
}

/** The `table:` / `view:` / `query:` references of a binding target (`key` undefined for a bare `target: X`). */
function bindingTargets(target: ObjectValue | Reference): Array<{ key?: string; ref: Reference }> {
  if (isReference(target)) return [{ ref: target }];
  const out: Array<{ key?: string; ref: Reference }> = [];
  for (const e of target.entries) {
    if (TARGET_KINDS.has(e.key) && e.value.kind === 'id') {
      out.push({ key: e.key, ref: { path: e.value.path, parts: e.value.parts, source: e.value.source } });
    }
  }
  return out;
}
