# @tatrman/edit

Pure TTR-M edit synthesis shared by the Node and browser LSP. Text is canonical: callers apply the
returned `WorkspaceEdit` and reparse their cache. The Studio plan and request contract live in
[`project/platform/features/studio/implementation/ttr-core`](https://github.com/Collite/project/tree/master/platform/features/studio/implementation/ttr-core).
Existing graph/layout/import/rename helpers remain exported.

```ts
const result = synthesize('insert-entity', {
  name: 'returns_review',
  file: 'model/er/returns-review.ttrm',
  on: 'er.query.returns_review',
  terms: { en: 'returns review', cs: 'přehled reklamací' },
}, { files, rootUri, manifest });
```

`files` is a complete immutable text snapshot, keyed consistently by document URI or repo-relative path
(`rootUri: ''` for relative keys), including `model/tests/*.mjs`. `manifest` is the resolved branch
manifest from `@tatrman/semantics`. All reads are in memory. New files have explicit `CreateFile` operations;
`touched` lists changed keys in deterministic order. Refusal returns no edits. Same-target duplicate terms
return an empty successful edit. The pure API returns `diagnostics: []`; the LSP fills semantic diagnostics
when it applies an edit to its cache (S1.2).

| Operation | Arguments | Writes | Refuses |
|---|---|---|---|
| `insert-entity` | `name, file?, from?, on?, terms?` | Entity attributes from a table or parameterless query, entity/attribute mappings, optional EN/CS terms | `entity-needs-binding`, `entity-exists`, `source-unknown`, `query-has-parameters` |
| `add-attribute` | `entity, name, type, column?` | Attribute and optional physical-column mapping | `attribute-needs-binding`, `attribute-exists`, `column-unknown`, `entity-is-query-backed` |
| `add-relation` | `from, to, kind, fk?, name?` | Relation with cardinality and optional inline FK binding | `relation-exists`, `entity-unknown`, `fk-needs-binding`, `source-unknown` |
| `set-source` | `nodeRef, text` | Replaces exactly one definition, printed canonically | `node-unknown`, `text-unparseable` |
| `remove-definition` | `qname, force?` | Removes a top-level definition; force also removes its dependent definitions, inline bindings and roster lines | `would-orphan`, `node-unknown` |
| `add-term` | `lang, term, target, file?` | Canonical `def term` with `for` and `forms` | `term-exists`, `target-unknown` |

All operations can also refuse `invalid-args` or `ambiguous-reference`. Qualify references when packages
collide. Generated term IDs end in `_en`/`_cs` and receive numeric suffixes on name collisions. Inline
`insert-entity.terms` uses the entity file's first hyphen-separated stem (`returns-review.ttrm` →
`returns.ttrm`) under each locale. Standalone `add-term` chooses a matching existing locale/model file,
then the locale's first file, otherwise `<model>.ttrm`; `file` overrides this choice. Binding files are
selected within the source package, preferring an existing `er2db_entity` carrier.

The DB input is already parsed TTR-M, so column types, precision, optionality and keys are retained.
TS has no LQ `outputColumnNames` API yet. Browser-safe SQL lexer analysis derives columns from loaded
entities/tables/views, aliases, wildcards, typed arithmetic, literals and simple aggregates. Outer joins
make the nullable side optional. Unsupported or ambiguous query shapes refuse `source-unknown` with a
reason; arbitrary SQL expression typing is unavailable. This is projection analysis, not full SQL validation.

Removal follows the semantic reference index, including member mappings and transitive dependents.
The v1 roster check is the documented single-quoted local-name regex over `model/tests/**/*.mjs`, removing
whole matching lines; keep one roster entry per line. SQL text references and arbitrary JavaScript are
outside that index's coverage. A worker's diagnostics and the strict loader remain subsequent gates.

The 14 byte-for-byte vectors are in [`__tests__/synth/vectors`](./__tests__/synth/vectors).
The `insert-entity/on-query/expected/` files are the demo's portable output contract. Run `pnpm test` here;
a read-only real-world check is `node packages/edit/scripts/studio-synth-smoke.mjs ../hartland` from the
repository root after building dependencies and this package.
