# Browser and Node model reads

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
The editing operations enforce this policy when the synthesizer is available.

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
