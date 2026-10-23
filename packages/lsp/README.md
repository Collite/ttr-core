# @tatrman/lsp

**Status:** v1, 2026-05-18. Full Phase 3 implementation shipped.

LSP server for TTR (Tatrman) language. Powers VS Code extension, Designer (browser worker), and IntelliJ plugin.

## Entry Points

| File | Transport | Used by |
|------|-----------|---------|
| `dist/server-stdio.js` | stdio | VS Code extension, IntelliJ plugin |
| `dist/server-browser.js` | Web Worker | Designer (browser) |

## Standard LSP Methods

The server implements the full Foundation + Core tier:

| Method | Capability |
|--------|------------|
| `textDocument/didOpen` | Document sync (full) |
| `textDocument/didChange` | Document sync (full) |
| `textDocument/didClose` | Document sync (full) |
| `textDocument/didSave` | Document sync (full) |
| `textDocument/definition` | Go to definition |
| `textDocument/references` | Find all references |
| `textDocument/hover` | Hover info |
| `textDocument/publishDiagnostics` | Live diagnostics on every change |
| `textDocument/semanticTokens/full` | Semantic token highlighting |
| `workspace/symbol` | Workspace-wide symbol search |

## Custom Methods (Phase 3)

All custom methods use `modeler/` prefix.

### `modeler/getModelGraph`

Returns the full graph for a schema as a set of nodes and edges.

**Request:**
```ts
{
  textDocument: { uri: string };
  schema: 'db' | 'er' | 'binding' | 'query' | 'cnc';
}
```

**Response:**
```ts
{
  schemaCode: string;
  nodes: Array<{
    qname: string;
    label: string;       // localized
    kind: string;
    rows?: ModelGraphRow[];
  }>;
  edges: Array<{
    id: string;
    qname: string;
    kind: 'fk' | 'relation';
    fromNode: string;
    toNode: string;
    fromCardinality: Cardinality | null;
    toCardinality: Cardinality | null;
    sourceUri: string;
  }>;
}
```

Cardinality values: `'one' | 'zero-or-one' | 'many' | 'one-or-many'`.

Example (TypeScript):
```ts
const graph = await client.sendRequest('modeler/getModelGraph', {
  textDocument: { uri: 'file:///path/to/er.ttrm' },
  schema: 'er',
});
console.log(graph.nodes.length, 'nodes,', graph.edges.length, 'edges');
```

### `modeler/getSymbolDetail`

Returns detailed information about a symbol by qname.

**Request:**
```ts
{ qname: string }
```

**Response:**
```ts
{
  qname: string;
  kind: 'table' | 'entity' | 'column' | 'fk' | 'relation' | ...;
  name: string;
  label: string;           // localized
  description: string | null;
  tags: string[];
  sourceUri: string;
  sourceLine: number;
  perKindData: PerKindData; // kind-specific payload
  referencedBy: Array<{
    qname: string;          // the REFERRER's qname (enclosing def), deduplicated
    sourceUri: string;
    sourceLine: number;
  }>;
}
```

`referencedBy[i].qname` is the qname of the def that contains the reference, not the queried symbol — so the Inspector's "Referenced by" list shows distinct callers, not echoes of the target. Returns `null` if the qname is not found (e.g. nested defs in v1).

### `modeler/setLayout` / `modeler/getLayout`

Manage layout sidecar (`.ttrl`) for node positions and viewport.

**Request (setLayout):**
```ts
{
  projectRoot: string;
  layout: LayoutFile;
}
```

**Response (getLayout):**
```ts
LayoutFile
```

LayoutFile shape:
```ts
{
  version: 1;
  viewports: Record<RenderableSchemaCode, { zoom: number; panX: number; panY: number; displayMode: DisplayMode }>;
  nodes: Record<string, { x: number; y: number }>;
  edges: Record<string, { bendPoints: [number, number][] }>;
}
```

### `modeler/listSymbols`

Lists all symbols in the workspace, optionally filtered by kind.

**Request:**
```ts
{
  kinds?: string[]; // e.g. ['entity', 'relation']
  limit?: number;   // max results, default 500
}
```

**Response:**
```ts
Array<{
  qname: string;
  kind: string;       // TTR def kind, not LSP SymbolKind
  name: string;
}>
```

Example:
```ts
const relations = await client.sendRequest('modeler/listSymbols', {
  kinds: ['relation'],
  limit: 100,
});
console.log(relations.map((r) => r.qname));
```

### `modeler/applyGraphEdit`

Deprecated alias of `modeler/edit { op: 'set-source', args: { nodeRef, text }, dryRun? }`.
Responses add `deprecated: 'modeler/edit'`. The shared Node/Worker handler applies edits to its cache
and returns post-edit worker diagnostics; the host still applies the returned edit to its own files.
See [the request manual](../../docs/manual/lsp-requests.md#structured-authoring) for all six operations,
refusals, dry runs, roster inputs and `modeler/applyWorkspaceEdit`.

## Diagnostics

The server publishes diagnostics with these codes:

| Code | Severity | Trigger |
|------|----------|---------|
| `ttr/parse-error` | Error | ANTLR syntax error |
| `ttr/parse-recovery-info` | Information | Parser recovery at a token |
| `ttr/unresolved-reference` | Warning | Dotted ref doesn't resolve |
| `ttr/duplicate-definition` | Error | Duplicate qname |
| `ttr/required-property-missing` | Warning | Missing required property |
| `ttr/invalid-type` | Error | Unknown type name |
| `ttr/entity-attribute-not-found` | Error | nameAttribute/codeAttribute missing |
| `ttr/primary-key-column-not-found` | Error | pk column missing |

## Build

```bash
pnpm install
pnpm run build
```

Build produces both transport bundles:
- `dist/server-stdio.js` (Node, stdio transport)
- `dist/server-browser.js` (browser, Web Worker transport)

Last verified to compile: 2026-05-18.