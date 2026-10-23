# Browser and Node model requests

The Node server and browser worker share these `modeler/*` JSON-RPC handlers.
Initialize the connection, set the project root, open model documents, then set the root again to reindex references against the complete symbol universe.

## Project root and manifest

`modeler/setProjectRoot { projectRoot: string, manifest?: string }` accepts the
text of `modeler.toml`. It returns `{ ok: true, projectRoot, manifest }`, where
`manifest` is the resolved configuration. Supplying new configuration reindexes
and revalidates already open documents. Invalid TOML applies defaults and publishes an Error
`lsp/manifest-invalid` on `modeler.toml`, with the parser message/location. The request still resolves,
so a change branch with a manifest typo remains viewable.

A browser host passes the manifest explicitly; the worker cannot read the
filesystem. Without it, defaults apply and the worker publishes a
single `lsp/manifest-missing` warning on `modeler.toml` while absent, republished on every call.
Supplying a manifest clears it. The Node
entry point can load the manifest through its filesystem callback.

`[bindings] required = true` resolves to `manifest.bindings.required`; the default
is false. Unknown keys in that block produce `manifest/unknown-key` warnings.
The editing operations enforce this policy.

## Whole-schema subjects

`modeler/listSchemaSubjects {}` returns
`{ subjects: [{ schema, objectCount, packageCount }] }` in er/db/md/cnc order (the renderable codes; `cn` joins once its canvas is implemented),
omitting empty models. Counts are top-level model definitions and their distinct
packages, excluding nested members, terms, areas and project definitions.
`objectCount` includes definitions such as relations and measures, so it can
exceed the number of cards on the canvas.

The Designer lists supported whole-schema subjects above project graphs and
layouts. `/w/<workspace>/s/er` opens the ER schema without requiring a `.ttrg`.
All graph cards carry their effective package in `pkg`; the Designer needs no symbol-list fetch to
recover it. Graph qnames are unchanged for saved-position compatibility; inferred-package qnames
can still differ from the symbol table ([ttr-core#255](https://github.com/Collite/ttr-core/issues/255)).

## Symbol pages

`modeler/listSymbols { kind?, kinds?, package?, cursor?, limit?, includeMembers? }`
returns `{ symbols, total, nextCursor? }`. The default page size is 500. `limit`
must be a positive integer. `cursor` is opaque to clients: pass `nextCursor`
back with the same filters until it is absent. Pages share a cached listing. A model change invalidates
the sequence; an old cursor fails with InvalidParams (`stale cursor`). The Designer restarts once
from the first page and discards the incomplete list. Callers must treat cursor contents as opaque.

`kind` is the singular shorthand for the existing `kinds` array and takes
precedence when both are supplied. `package` matches an exact package name.
`includeMembers` adds the existing `kind: 'member'` refs with `memberPath` and
`parent`. `total` counts the filtered, expanded result. The Designer's
`LspClient.listSymbols` collects all pages and returns an array to its callers.

## Layout files in a fetched project

The Designer reads `model/layouts/**/*.ttrg` and optional `.ttrl` sidecars from
the supplied file map through the shared VL reader. These files are layouts,
separate from authored LSP graphs: only cards are listed and all edges between
visible cards are derived from the whole-schema graph. DB catalog names such as
`hartland.db.dbo.item` and worker names such as `hartland.db.dbo.table.item`
resolve to the same card when the catalog spelling is unique. A same-named table and view keep
their kinded names to remain separately addressable. Saved layouts otherwise use the portable catalog spelling.

Unreadable files and duplicate declared layout names are skipped without failing the model catalog.
`layoutIssues()` returns `{ path, reason }` records; each is logged once as a warning. Names come from
the graph declaration, not the filename. Position-only keys never create missing-object entries.

The local folder picker recursively loads project files and retains its
`FileSystemDirectoryHandle` for a later local save. Hidden directories and
`node_modules` are skipped.

For a manual check against a real checkout, build the workspace and run:

```sh
node packages/lsp/scripts/hosted-smoke.mjs ../hartland
```

## Structured authoring

The Node stdio server and browser Worker implement the same `modeler/edit` handler:

```ts
request: { op, args, dryRun?: boolean }
result:  { ok: true, edit: WorkspaceEdit, touched: string[], diagnostics: WorkerDiagnostic[] }
       | { ok: false, code, message, refs?: { uri, range, kind }[] }
```

Set the project root before editing. Load the complete model snapshot first, including
`model/tests/**/*.mjs` rosters. A browser host sends roster bytes through `textDocument/didOpen`
(and subsequent changes) alongside model documents; the worker stores these bytes without parsing
or linting them as TTR. The stdio entry scans them from disk and accepts watched-file updates.
The Designer's `openFileMap` supplies these roster documents automatically.

| `op` | `args` |
|---|---|
| `insert-entity` | `{ name, file?, from?: tableQname, on?: queryQname, terms?: { en?, cs? } }` |
| `add-attribute` | `{ entity, name, type, column? }` |
| `add-relation` | `{ from, to, kind: 'one-to-many' \| 'many-to-one' \| 'one-to-one', fk?, name? }` |
| `set-source` | `{ nodeRef, text }` |
| `remove-definition` | `{ qname, force?: boolean }` |
| `add-term` | `{ lang: 'en' \| 'cs', term, target, file? }` |

`type` accepts a canonical TTR type string or the parser's simple/structured data-type object.
File arguments are checkout-relative; edits and `touched` use document URIs. `from` and `on` are
mutually exclusive. Physical sources and query outputs determine inserted entity attributes.
Unsupported query projections refuse `source-unknown`; the synthesizer does not perform full SQL validation.
Removal follows semantic references, transitive dependents and single-quoted roster entries (one per line).
SQL text references and arbitrary JavaScript are outside this removal index.

On success the server stages the edit in its document cache, rebuilds symbols/references, and returns
the resulting diagnostics. Immediate symbol/graph/source reads see this text, including newly created files.
No file is written to disk. The host applies the returned edit to its own **pre-request** file texts exactly
once, then sends normal full-text `didChange` / `didOpen` acknowledgements. Those acknowledgements retain host
versions and do not apply the patch again. Serial host acknowledgement is recommended; an acknowledgement
matching an earlier staged edit preserves any later staged text. Unrelated host text replaces the staged text.
Closing an open document discards its staged text and restores saved disk content when available.

`dryRun: true` computes the same edit and post-edit diagnostics in an isolated snapshot. It does not alter
text, symbols, pagination cursors or diagnostic notifications. Refusals apply nothing and retain the pure
synthesizer's code, message and optional reference ranges. Malformed operation arguments return `invalid-args`;
a malformed request envelope (including a non-boolean `dryRun`) fails with JSON-RPC InvalidParams.

A successful edit can contain Error diagnostics: `ok` says the edit was synthesized/applied to the cache,
while validation describes the resulting model. The later Studio proposal gate decides whether to accept it.
Each returned diagnostic is a normal LSP Diagnostic with `uri` added and `source: 'worker'`:
`{ uri, range, severity, code, message, source: 'worker', ... }`. The same diagnostics are published for touched
TTR URIs, including an empty list to clear previous findings. JS rosters receive no TTR diagnostics.
Hosts merge worker and loader findings by `{uri, range}`. A no-op returns empty `touched` / `diagnostics`.

### Existing edit requests

`modeler/applyGraphEdit { op: 'set-source', args: { nodeRef, text }, dryRun? }` is a one-minor-version alias
of `modeler/edit`, adding `deprecated: 'modeler/edit'` to success and refusal responses. The shorthand
`{ nodeRef, text, dryRun? }` is also accepted. Other historical graph operations are unsupported.
The Kotlin designer-server is a separate protocol; its synthesizer twin remains a follow-up.

`modeler/applyWorkspaceEdit { edit: WorkspaceEdit, dryRun?: boolean }` uses the same staging, cache and
diagnostic path, returning the success shape above. It accepts either `changes` or `documentChanges`,
with text edits and explicit `CreateFile`; rename/delete resource operations are not supported.
Destinations must be `.ttrm` / `.ttrg` files or `model/tests/**/*.mjs` under the project root.
File-create conflicts, stale explicit document versions, invalid/overlapping ranges, and unsupported resources
fail with InvalidParams, atomically: no preceding file creation or edit is retained. This request applies
caller-supplied text edits; it does not add the six structured operations' semantic refusal checks.

To verify both built entries against a Hartland checkout without changing it:

```sh
node packages/lsp/scripts/studio-edit-smoke.mjs ../hartland
```

The smoke launches the stdio entry and a real Chromium Worker (use `PW_EXECUTABLE` to select Chromium),
checks dry-run isolation, identical authored edits, immediate reads and roster guards. Desktop discovery also
loads TTR manual examples outside `model/`; the hosted input remains scoped to `model/**` as its contract requires.
