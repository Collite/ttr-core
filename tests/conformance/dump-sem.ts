// SPDX-License-Identifier: Apache-2.0
/**
 * TS side of the *semantics* conformance dump (contracts.md §5). For each
 * fixture it loads the stock vocab, builds the project symbol table, resolves
 * every reference and runs the portable validator subset, then emits a
 * normalised `{ diagnostics, resolved }` object that must be byte-identical to
 * the Kotlin `SemanticsConformanceDump` output.
 *
 * Normalisation (both runtimes):
 *  - `symbols`: sorted full qnames of every definition in the scenario's own
 *    documents (stock vocab excluded). This is what compares the schema-code
 *    prefix directly, so kind-derived defaults (pkg-schema-defaults) are checked
 *    even when a fixture has no resolvable references.
 *  - `resolved`: sorted `"<refPath> => <resolvedQname>"` strings (one per
 *    reference that resolves) — no source positions.
 *  - `diagnostics`: sorted diagnostic-code strings (code only; severity is
 *    consumer policy and positions are impl-specific).
 */
import type { Document } from '@tatrman/parser';
import {
  ProjectSymbolTable,
  Resolver,
  PackageGraphBuilder,
  collectAllReferences,
  enclosingQnameOf,
  resolveManifest,
} from '@tatrman/semantics';
import { lintDocument, lintProject, recommendedConfig, RULES } from '@tatrman/lint';

// The portable validator subset (matching the Kotlin dump): structural + search +
// reference + import checks per document, plus project-level duplicate
// definition/mapping. Excludes impl-specific rules (file-ordering, package
// declarations, graph, circular dependency).
const PORTABLE_RULE_IDS = new Set([
  'entity-no-attributes',
  'table-no-columns',
  'column-missing-type',
  'attribute-missing-type',
  'missing-description',
  'entity-attribute-not-found',
  'primary-key-column-not-found',
  'fuzzy-without-searchable',
  'duplicate-search-property',
  // RV-P1.5 (grammar 0.12) — the match-method checks; the Kotlin/Python
  // validators emit them unconditionally, so they belong in the portable subset.
  'fuzzy-deprecated',
  'unknown-match-method',
  'invalid-match-method-argument',
  'unresolved-reference',
  'ambiguous-reference',
  'unimported-reference',
  'unused-import',
  'duplicate-import',
  'wildcard-with-no-matches',
  'duplicate-definition',
  'duplicate-mapping',
  // 0.17 (CN-P2 S2.3) — the model-level cn checks (cn contracts §7); Kotlin and Python emit them
  // unconditionally (S2.4). The lexicon pair (`cn-duplicate-default-sense`, `cn-phase-as-default`)
  // and the definition-level slugs (Kotlin, S2.6) are not portable.
  'cn-duplicate-grounding',
  'cn-grounding-target-kind',
  'cn-role-no-event',
  'concept-bearer-missing',
  'cn-phase-double-definition',
  'cn-phase-no-definition',
  'cn-event-no-period',
  'cn-ungrounded',
  'cn-db-grounding',
  'cn-hierarchy-shape',
  'cn-participant-path-broken',
  'cn-involves-unlabelled-duplicate',
  'cn-unknown-relation-type',
  'cn-antirigid-supertype',
  'cn-cnc-deprecated',
  'cn-subkind-temporal-definition',
  'cn-subkind-bearer',
  'cn-partition-overlap',
  'cn-partition-incomplete',
  'cn-subkind-as-kind',
  'cn-extent-retired',
  'cn-durative-event',
  'cn-episode-subject-missing',
  'cn-phase-bounds-participant',
  'cn-phase-source-conflict',
  'cn-projection-source',
  'cn-lifetime-period',

  'md-md2er-physical-prop',
  'md-md2er-dimension-key',
  'md-md2er-path-ambiguous',
  'md-md2er-measure-unknown',
  'md-duplicate-binding',
  'md-md2er-reference',
]);
/**
 * The cn hygiene slugs are off in `recommended` (an editor shows them only on request), but they are
 * part of the portable subset: the dump switches them on, as the Kotlin and Python dumps emit them.
 */
const CN_HYGIENE_ON = { 'cn-ungrounded': 'info', 'cn-phase-no-definition': 'info', 'cn-event-no-period': 'info' } as const;
const PORTABLE_RULES = [...RULES.values()].filter((r) => PORTABLE_RULE_IDS.has(r.id));

export interface SemDump {
  resolved: string[];
  diagnostics: string[];
  symbols: string[];
}

/** One parsed document in a (possibly multi-file) scenario. */
export interface SemDocInput {
  ast: Document;
  uri: string;
}

/** Single-document dump — the common case. A 1-element delegation to {@link dumpSemDocs}. */
export function dumpSem(ast: Document | undefined, uri: string, stock: Map<string, Document>): SemDump {
  if (!ast) return dumpSemDocs([], stock);
  return dumpSemDocs([{ ast, uri }], stock);
}

/**
 * Multi-document dump: builds ONE project symbol table from the stock vocab plus
 * every document in the scenario, then resolves references and runs the portable
 * validator subset across all of them. This is what exercises cross-file
 * resolution (same-package siblings, named/wildcard imports) — see the
 * `fixtures/<scenario>/` directories. For a single document it is byte-identical
 * to the previous single-doc dump.
 */
export function dumpSemDocs(docs: SemDocInput[], stock: Map<string, Document>): SemDump {
  const symbols = new ProjectSymbolTable();
  for (const [name, doc] of stock) {
    symbols.upsertDocument(`stock://${name}.ttrm`, doc, 'cnc', 'role', '');
  }

  // Upsert every document FIRST so cross-document lookups (getByPackage,
  // named/wildcard imports, getBySuffix) see the whole project.
  const metas = docs.map(({ ast, uri }) => {
    // '' (no directive) ⇒ the semantics layer derives the schema per definition
    // from its kind. Must match the Kotlin dump (`?: ""`) for byte-identical output.
    const schemaCode = ast.modelDirective?.modelCode ?? '';
    const namespace = ast.modelDirective?.schema ?? '';
    const packageName = ast.packageDecl?.name ?? '';
    symbols.upsertDocument(uri, ast, schemaCode, namespace, packageName);
    return { ast, uri, schemaCode, namespace, packageName };
  });

  const resolver = new Resolver(symbols);
  const deps = { manifest: resolveManifest(undefined, ''), symbols, resolver };
  const config = recommendedConfig({ overrides: { ...CN_HYGIENE_ON } });
  const projectDocs = new Map(metas.map((m) => [m.uri, m.ast]));
  const packageGraph = new PackageGraphBuilder(symbols, projectDocs).build();

  const resolved: string[] = [];
  const diagnostics: string[] = [];
  for (const m of metas) {
    for (const { ref, ownerDef } of collectAllReferences(m.ast)) {
      const enclosingQname = enclosingQnameOf(ownerDef, m.schemaCode, m.namespace, m.packageName);
      // The ref itself: a cn slot's ref carries its context (0.17).
      const res = resolver.resolveReference(
        ref,
        { schemaCode: m.schemaCode, namespace: m.namespace, enclosingQname, imports: m.ast.imports, packageName: m.packageName }
      );
      if (res.resolved) resolved.push(`${ref.path} => ${res.symbol.qname}`);
    }
    diagnostics.push(...lintDocument(m.uri, m.ast, deps, config, PORTABLE_RULES).map((d) => d.code));
  }
  // Project-scoped rules (duplicate definition/mapping) run once across all docs.
  for (const diags of lintProject(projectDocs, packageGraph, deps, config, PORTABLE_RULES).values()) {
    diagnostics.push(...diags.map((d) => d.code));
  }

  // Full qnames of the scenario's own definitions (stock vocab excluded).
  const symbolQnames = symbols
    .all()
    .filter((e) => !e.documentUri.startsWith('stock://'))
    .map((e) => e.qname);

  resolved.sort();
  diagnostics.sort();
  symbolQnames.sort();
  return { resolved, diagnostics, symbols: symbolQnames };
}

/** Keys ordered `diagnostics`, `resolved`, `symbols`; matches the Kotlin render. */
export function renderSem(d: SemDump): string {
  return JSON.stringify({ diagnostics: d.diagnostics, resolved: d.resolved, symbols: d.symbols }, null, 4);
}
