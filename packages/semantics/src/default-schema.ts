// SPDX-License-Identifier: Apache-2.0
/**
 * Default schema code derived from a definition's kind, applied only when a
 * file has no explicit `schema` directive. An explicit directive always wins
 * for the whole file. Mirrors the namespace fallback (`namespace || def.kind`)
 * but per the normative kind→schema map in
 * `docs/features/pkg-schema-defaults/INDEX.md`.
 *
 * `kind` is the camelCase `def.kind` string (e.g. `er2dbEntity`, `drillMap`).
 * Unknown kinds fall back to `db` — kept identical to the Kotlin twin
 * (`defaultSchemaForKind` in `Kinds.kt`).
 */
/**
 * Conventional default namespace for a schema, applied when a file declares a
 * `schema` but no explicit `namespace` (and, symmetrically, to the schema
 * derived for schema-less files). Mirrors the architecture's manifest default
 * `namespaces = { db = "dbo", … }` (architecture.md §5).
 *
 * Scoped to `db` (the SQL-default `dbo` schema): without it, a `schema db` file
 * with no namespace registers tables/columns under per-kind segments
 * (`db.table.<t>`, `db.table.<t>.<col>`), so the canonical fully-qualified
 * `db.dbo.<table>.<column>` references fail to resolve. Every other schema
 * returns '' so the caller keeps the per-kind fallback (`def.kind`): `er`
 * already coincides with its `entity` namespace, `binding` addresses symbols per
 * kind (`binding.er2dbEntity.…`, relied on by the inline-mapping synthesizer), and
 * `cnc`/`query` files carry explicit namespaces in practice.
 */
import type { ModelCode } from './qname.js';

export function defaultNamespaceForSchema(schemaCode: string): string {
  return schemaCode === 'db' ? 'dbo' : '';
}

/**
 * The single-valued kind→model map (D4/D14/D15) and the one source of truth for
 * model derivation. `query`/`drillMap` → `db` (D14 — there is no `query` model),
 * except that a `query` declared in a `model er` file is a **logical query** →
 * `er` (LQ, contract L-1: the lane is the model the query sits in; the body is
 * never inspected). `declaringModel` is the file's `model` directive code (`''`
 * or absent for a directive-less file) and moves no other kind (D12);
 * `role`/`er2cncRole` → `cnc` (D15, schema-less); er2db / md2db binding kinds →
 * `binding`; MD logical kinds → `md`; everything else (table, view, …) → `db`.
 *
 * `defaultSchemaForKind` is a deprecated alias kept for the existing call sites
 * (`reference-index`, `mapping-references`, lint rules) — it returns the same
 * value. `qname.ts` re-exports `modelForKind` so the public API name is stable.
 * Mirrored byte-for-byte in Kotlin (`Kinds.kt`) and Python (`qname.py`).
 */
export function modelForKind(kind: string, declaringModel?: string): ModelCode {
  if (kind === 'query' && declaringModel === 'er') return 'er';
  // 0.16 — a `def role` declared in a `model automation` file is an automation role
  // (resolve / delivery / members), not a cnc role; the directive moves no other kind.
  if (kind === 'role' && declaringModel === 'automation') return 'automation';
  switch (kind) {
    case 'entity':
    case 'attribute':
    case 'relation':
      return 'er';
    case 'er2dbEntity':
    case 'er2dbAttribute':
    case 'er2dbRelation':
    case 'md2dbCubelet':
    case 'md2dbDomain':
    case 'md2dbMap':
    case 'md2erCubelet':
      return 'binding';
    case 'role':
    case 'er2cncRole':
      return 'cnc';
    // MD logical kinds → `md`.
    case 'mdDomain':
    case 'dimension':
    case 'mdMap':
    case 'hierarchy':
    case 'measure':
    case 'cubelet':
      return 'md';
    // v4.1 — world model kinds (ttr-metadata M0).
    case 'world':
    case 'engine':
    case 'executor':
    case 'storage':
    case 'worldSchema':
    // 0.16 — the automation world members (identity / deployment / process) nest in a world.
    case 'identity':
    case 'deployment':
    case 'process':
      return 'world';
    // 0.16 — the automation model kinds (`model automation`).
    case 'parameter':
    case 'action':
    case 'service':
    case 'approval':
    case 'program':
    case 'recipe':
      return 'automation';
    // D14 — query + drillMap are db-layer objects (no separate `query` model).
    case 'query':
    case 'drillMap':
    case 'project':
    case 'table':
    case 'view':
    case 'column':
    case 'index':
    case 'constraint':
    case 'fk':
    case 'procedure':
      return 'db';
    default:
      return 'db';
  }
}

/** @deprecated Use {@link modelForKind}. Kept as an alias for existing callers. */
export const defaultSchemaForKind: (kind: string, declaringModel?: string) => ModelCode = modelForKind;

/**
 * The symbol-table namespace segment for a def kind, where it differs from the
 * camelCase `def.kind`. MD logical/binding kinds map to the contracts §5
 * namespaces (`md.domain.*`, `md.map.*`, `binding.md2db_cubelet.*`, …) rather
 * than the raw kind (`mdDomain`/`mdMap`). Returns `''` for every other kind so
 * the caller keeps the existing `def.kind` fallback.
 */
export function namespaceForKind(kind: string): string {
  switch (kind) {
    case 'mdDomain':
      return 'domain';
    case 'mdMap':
      return 'map';
    case 'dimension':
      return 'dimension';
    case 'hierarchy':
      return 'hierarchy';
    case 'measure':
      return 'measure';
    case 'cubelet':
      return 'cubelet';
    case 'md2dbCubelet':
      return 'md2db_cubelet';
    case 'md2dbDomain':
      return 'md2db_domain';
    case 'md2dbMap':
      return 'md2db_map';
    case 'md2erCubelet':
      return 'md2er_cubelet';
    default:
      return '';
  }
}
