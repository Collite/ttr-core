# @tatrman/edit

Apache-2.0 · public package `@tatrman/edit@0.1.0` (release tag `edit/v0.1.0`).

Pure TTR-M edit synthesis shared by the Node and browser LSP. Text is canonical: callers apply the
returned `WorkspaceEdit` and reparse their cache. The Studio plan and request contract live in
[`project/platform/features/studio/implementation/ttr-core`](https://github.com/Collite/project/tree/master/platform/features/studio/implementation/ttr-core).
Existing graph/layout/import/rename helpers remain exported.

```ts
import { synthesize, parseManifest, resolveManifest } from '@tatrman/edit';
const manifest = resolveManifest(parseManifest(files['modeler.toml']), rootUri);
const result = synthesize('insert-entity', {
  name: 'returns_review',
  file: 'model/er/returns-review.ttrm',
  on: 'er.query.returns_review',
  terms: { en: 'returns review', cs: 'přehled reklamací' },
}, { files, rootUri, manifest });
```

`files` is a complete immutable text snapshot, keyed consistently by document URI or repo-relative path
(`rootUri: ''` for relative keys), including `model/tests/*.mjs`. `manifest` is the resolved branch
manifest; `parseManifest` / `resolveManifest` are exported for standalone consumers. All reads are in memory. New files have explicit `CreateFile` operations;
`touched` lists changed keys in deterministic order. Refusal returns no edits. Same-target duplicate terms
return an empty successful edit. The pure API returns `diagnostics: []`; the LSP fills semantic diagnostics
when it applies an edit to its cache (S1.2).

| Operation | Arguments | Writes | Refuses |
|---|---|---|---|
| `insert-entity` | `name, file?, from?, on?, terms?` | Entity attributes from a table or parameterless query, entity/attribute mappings, optional EN/CS terms | `entity-needs-binding`, `entity-exists`, `source-unknown`, `query-has-parameters` |
| `add-attribute` | `entity, name, type, column?` | Attribute and optional physical-column mapping | `attribute-needs-binding`, `attribute-exists`, `column-unknown`, `entity-is-query-backed` |
| `add-relation` | `from, to, kind, fk?, name?` | Relation with cardinality and optional inline FK binding | `relation-exists`, `entity-unknown`, `fk-needs-binding`, `source-unknown` |
| `set-source` | `nodeRef, text` | Preserves one validated top-level definition verbatim, with identity and referenced-member guards | `node-unknown`, `text-unparseable` |
| `remove-definition` | `qname, force?` | Removes a top-level definition; force also removes its dependent definitions, inline bindings and roster lines | `would-orphan`, `node-unknown` |
| `add-term` | `lang, term, target, file?` | Canonical `def term` with `for` and `forms` | `term-exists`, `target-unknown` |

All operations can also refuse `invalid-args` or `ambiguous-reference`. Qualify references when packages
collide. Generated term IDs end in `_en`/`_cs` and receive numeric suffixes on name collisions. Inline
`insert-entity.terms` uses the entity file's first hyphen-separated stem (`returns-review.ttrm` →
`returns.ttrm`) under each locale. Standalone `add-term` chooses a matching existing locale/model file,
then the locale's first file, otherwise `<model>.ttrm`; `file` is a locale-relative filename (for example `returns.ttrm`) and overrides this choice. Binding files are
selected within the source package, preferring an existing `er2db_entity` carrier.

The DB input is already parsed TTR-M, so column types, precision, optionality and keys are retained.
TS has no LQ `outputColumnNames` API yet. Browser-safe SQL lexer analysis derives columns from loaded
entities/tables/views, aliases, wildcards, typed arithmetic, literals and simple aggregates. Outer joins
make the nullable side optional. Unsupported or ambiguous query shapes refuse `source-unknown` with a
reason; arbitrary SQL expression typing is unavailable. This is projection analysis, not full SQL validation.

Removal follows the semantic reference index, including member mappings and transitive dependents.
The v1 roster check is the documented single-quoted local-name regex over `model/tests/**/*.mjs`, removing
only pure one-entry roster lines. Any other match is a `roster-manual` ref and refuses even with force. SQL text references and arbitrary JavaScript are
outside that index's coverage. A worker's diagnostics and the strict loader remain subsequent gates.

The 14 byte-for-byte vectors are in [`__tests__/synth/vectors`](./__tests__/synth/vectors).
The `insert-entity/on-query/expected/` files are the demo's portable output contract. Run `pnpm test` here;
a read-only real-world check is `node packages/edit/scripts/studio-synth-smoke.mjs ../hartland` from the
repository root after building dependencies and this package.

The npm artifact bundles its runtime and declaration dependencies, so installing it requires no unpublished
`@tatrman/*` packages. Workspace builds retain their unbundled entry points; `publishConfig` switches only the
published `main`/`types`/`exports` to `dist/package/`. CI runs `node scripts/edit-consumer-smoke.mjs`: publish dry run,
tarball install outside the workspace, a canonical four-file synthesis and strict TypeScript consumer compilation.
The real public cut follows merge and a configured `NPM_TOKEN`; dry runs do not publish.

Schema resolution follows file schema → package `default-schema` → `[defaults].schema` → conventional db default.
An entity-level bare-id binding is ignored, matching the runtime loader; use a block-form entity binding.
Unrelated malformed/duplicated documents do not block safe creation operations. Writing a broken file refuses
`text-unparseable`; `set-source` and removal require a complete reference picture and name any broken files.
Lookups of duplicated definitions refuse `ambiguous-reference`. Existing CRLF files keep their line endings;
new files inherit CRLF when the input snapshot consistently uses it. Existing file URI keys are reused across
encoded/decoded path spellings.

`set-source` keeps kind/name; renames use `textDocument/rename`. Generated text from the other five operations still uses the formatter. Author comments/properties/spacing survive source saves, with file line endings retained and trailing definition whitespace trimmed.
