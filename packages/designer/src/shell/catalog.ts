// SPDX-License-Identifier: Apache-2.0
// CatalogSource (DS-P2.S1 / contracts §6): turn the LSP client's raw graph + symbol
// listings into the search-first, kind-grouped catalog spine the shell renders.
// Groups appear in a fixed order (Schemas, Cubes, Concepts, Programs); empty groups are
// dropped so the spine never shows a dead section.

import type { SchemaCode } from '@tatrman/canvas-core';
import type { CatalogItem, CatalogGroup, SubjectKind } from './types.js';

/** One graph as reported by `listCatalog` (contracts §6). `schema` is a free string — the
 *  backend-agnostic `CatalogGraphMeta` shape; `RENDERABLE_SCHEMAS` narrows it to a subject kind. */
export interface GraphMeta {
  uri: string;
  name: string;
  schema: string;
  /** a layout the catalog serves (VL V-34) */
  layout?: { missingCount: number };
}

/** One symbol as reported by the LSP `listSymbols` custom request. */
export interface SymbolMeta {
  qname: string;
  kind: string;
  name: string;
  packageName: string | null;
}

// Renderable model kinds get a schema tab; binding/query graphs are not subjects.
const RENDERABLE_SCHEMAS: ReadonlySet<GraphMeta['schema']> = new Set(['db', 'er', 'md', 'cnc']);

// Fixed group order + display labels (present groups render in this order).
const GROUP_ORDER: ReadonlyArray<{ kind: SubjectKind; label: string }> = [
  { kind: 'schema', label: 'Schemas' },
  { kind: 'cube', label: 'Cubes' },
  { kind: 'concept', label: 'Concepts' },
  { kind: 'program', label: 'Programs' },
];

function byLabel(a: CatalogItem, b: CatalogItem): number {
  return a.label.localeCompare(b.label);
}

/**
 * Build the grouped catalog from the LSP's graph + symbol listings.
 * Empty groups are omitted; `buildCatalog([], [])` returns `[]`.
 */
export function buildCatalog(graphs: GraphMeta[], symbols: SymbolMeta[]): CatalogGroup[] {
  const schemas: CatalogItem[] = [];
  const cubes: CatalogItem[] = [];
  const concepts: CatalogItem[] = [];
  const programs: CatalogItem[] = [];

  // Schemas (VL V-34): a catalog's whole-schema subjects (`er`, `db`) first, then the project's graphs,
  // then the layouts the catalog serves — `<model> · <name>`, by model then name, `· n missing` when so.
  const wholeSchemas: CatalogItem[] = [];
  const layouts: CatalogItem[] = [];
  for (const g of graphs) {
    if (!RENDERABLE_SCHEMAS.has(g.schema)) continue;
    const schemaCode = g.schema as SchemaCode; // narrowed by RENDERABLE_SCHEMAS
    if (g.layout) {
      const missing = g.layout.missingCount > 0 ? ` · ${g.layout.missingCount} missing` : '';
      layouts.push({
        ref: g.uri, qname: g.uri, kind: 'schema', label: `${g.schema} · ${g.name}${missing}`, schemaCode, packageName: null,
        layout: { name: g.name, missingCount: g.layout.missingCount },
      });
      continue;
    }
    const whole = g.name === g.schema && g.uri === g.schema;
    (whole ? wholeSchemas : schemas).push({
      ref: g.uri,
      qname: g.uri,
      kind: 'schema',
      label: whole ? g.schema : `${g.schema} · ${g.name}`,
      schemaCode,
      packageName: null,
    });
  }
  layouts.sort((a, b) => a.schemaCode!.localeCompare(b.schemaCode!) || a.layout!.name.localeCompare(b.layout!.name));

  for (const s of symbols) {
    switch (s.kind) {
      case 'cubelet':
        cubes.push({
          ref: s.qname,
          qname: s.qname,
          kind: 'cube',
          label: `cube ${s.name}`,
          schemaCode: 'md',
          packageName: s.packageName,
        });
        break;
      // cnc has no dedicated `concept` def kind yet — accept both `concept` and `role`
      // so hero cnc entities (LSP-tagged as concepts) and stock roles both surface.
      case 'concept':
      case 'role':
        concepts.push({
          ref: s.qname,
          qname: s.qname,
          kind: 'concept',
          label: s.name,
          schemaCode: 'cnc',
          packageName: s.packageName,
        });
        break;
      case 'program':
        programs.push({
          ref: s.qname,
          qname: s.qname,
          kind: 'program',
          label: `program ${s.name}`,
          packageName: s.packageName,
        });
        break;
      default:
        break; // noise (table/entity/relation/...) is not a subject
    }
  }

  const byKind: Record<SubjectKind, CatalogItem[]> = {
    schema: [...wholeSchemas.sort(byLabel), ...schemas.sort(byLabel), ...layouts],
    cube: cubes.sort(byLabel),
    concept: concepts.sort(byLabel),
    program: programs.sort(byLabel),
  };

  const groups: CatalogGroup[] = [];
  for (const { kind, label } of GROUP_ORDER) {
    const items = byKind[kind];
    if (items.length > 0) groups.push({ kind, label, items });
  }
  return groups;
}

/**
 * Search-first filter: case-insensitive substring match on item `label` or `qname`.
 * Groups that become empty are dropped. An empty/whitespace query returns `groups` as-is.
 */
export function filterCatalog(groups: CatalogGroup[], query: string): CatalogGroup[] {
  const q = query.trim().toLowerCase();
  if (q === '') return groups;

  const result: CatalogGroup[] = [];
  for (const group of groups) {
    const items = group.items.filter(
      (i) => i.label.toLowerCase().includes(q) || i.qname.toLowerCase().includes(q),
    );
    if (items.length > 0) result.push({ ...group, items });
  }
  return result;
}
