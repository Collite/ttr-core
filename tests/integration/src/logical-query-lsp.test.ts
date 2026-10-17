// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';

/**
 * LQ (logical queries, contract L-1) — editor features on a `def query` declared in a `model er` file. The
 * symbol table keys it `er.query.<name>`; the server's own def → key derivation (definition, references, hover,
 * rename) and `modeler/listGraphs` must derive the same key, or every lookup misses.
 */
function createPairedConnection(): { client: lsp.Connection; server: lsp.Connection } {
  const a = new PassThrough({ objectMode: true });
  const b = new PassThrough({ objectMode: true });
  const client = lsp.createConnection(
    new lsp.StreamMessageReader(a as unknown as NodeJS.ReadableStream),
    new lsp.StreamMessageWriter(b as unknown as NodeJS.WritableStream),
  ) as lsp.Connection;
  const server = lsp.createConnection(
    new lsp.StreamMessageReader(b as unknown as NodeJS.ReadableStream),
    new lsp.StreamMessageWriter(a as unknown as NodeJS.WritableStream),
  ) as lsp.Connection;
  client.listen();
  server.listen();
  return { client, server };
}
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

const ER_URI = 'file:///lq/er.ttrm';
const GRAPH_URI = 'file:///lq/graphs/revenue.ttrg';

// line 2 = the query's `def` line; line 7 = the stacked entity's binding to it
const ER_FILE = `model er

def query shopper_revenue {
    sourceText: "SELECT shopper.shopper_id, SUM(sale.revenue) AS revenue FROM sale JOIN shopper GROUP BY shopper.shopper_id"
}

def entity shopper_revenue {
    binding: { target: { query: er.query.shopper_revenue } },
    attributes: [
        def attribute shopper_id { type: int, isKey: true },
        def attribute total_revenue { type: decimal, binding: revenue }
    ]
}
`;

const GRAPH_FILE = `graph revenue {
  model: er,
  objects: [
    er.query.shopper_revenue,
    er.entity.shopper_revenue
  ]
}`;

const ON_QUERY_NAME = { line: 2, character: 14 }; // inside `shopper_revenue` of `def query shopper_revenue`

describe('LQ — editor features on a logical query (er.query.<name>)', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;

  beforeAll(async () => {
    const p = createPairedConnection();
    client = p.client;
    server = p.server;
    createServerConnection(server);
    await client.sendRequest('initialize', {
      processId: null,
      rootUri: 'file:///lq',
      workspaceFolders: [{ uri: 'file:///lq', name: 'lq' }],
      capabilities: {},
    });
    client.sendNotification('initialized', {});
    for (const [uri, text] of [[ER_URI, ER_FILE], [GRAPH_URI, GRAPH_FILE]]) {
      client.sendNotification('textDocument/didOpen', { textDocument: { uri, languageId: 'ttr', version: 1, text } });
    }
    await sleep(300);
  });
  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  it('go-to-definition on the query name returns its declaration', async () => {
    const loc = (await client.sendRequest('textDocument/definition', {
      textDocument: { uri: ER_URI },
      position: ON_QUERY_NAME,
    })) as lsp.Location | null;
    expect(loc, 'definition returned null for a logical query').not.toBeNull();
    expect(loc!.uri).toBe(ER_URI);
    expect(loc!.range.start.line).toBe(2);
  });

  it('hover on the query name describes it', async () => {
    const hover = (await client.sendRequest('textDocument/hover', {
      textDocument: { uri: ER_URI },
      position: ON_QUERY_NAME,
    })) as lsp.Hover | null;
    expect(hover, 'hover returned null for a logical query').not.toBeNull();
    expect(JSON.stringify(hover!.contents)).toContain('shopper_revenue');
  });

  // `query:` binding targets are not in the reference index for either lane (it collects `table:` / `view:`
  // only) — a pre-existing gap, so these two pin the declaration site, which the key mismatch lost.
  it('find-references on the query name finds its declaration', async () => {
    const refs = (await client.sendRequest('textDocument/references', {
      textDocument: { uri: ER_URI },
      position: ON_QUERY_NAME,
      context: { includeDeclaration: true },
    })) as lsp.Location[];
    expect(refs.some((r) => r.uri === ER_URI && r.range.start.line === 2), JSON.stringify(refs)).toBe(true);
  });

  it('prepareRename + rename on the query name edit the def', async () => {
    const prep = (await client.sendRequest('textDocument/prepareRename', {
      textDocument: { uri: ER_URI },
      position: ON_QUERY_NAME,
    })) as { range: lsp.Range; placeholder: string } | null;
    expect(prep, 'prepareRename returned null for a logical query').not.toBeNull();
    expect(prep!.placeholder).toBe('shopper_revenue');

    const edit = (await client.sendRequest('textDocument/rename', {
      textDocument: { uri: ER_URI },
      position: ON_QUERY_NAME,
      newName: 'revenue_per_shopper',
    })) as lsp.WorkspaceEdit;
    const lines = (edit.documentChanges ?? [])
      .map((d) => d as lsp.TextDocumentEdit)
      .filter((d) => d.textDocument.uri === ER_URI)
      .flatMap((d) => d.edits.map((e) => e.range.start.line));
    expect(lines).toContain(2);
  });

  it('modeler/listGraphs finds the logical query a graph lists (no missing object)', async () => {
    const result = (await client.sendRequest('modeler/listGraphs', { projectRoot: 'file:///lq' })) as {
      graphs: Array<{ uri: string; objectCount: number; missingObjectCount: number }>;
    };
    const graph = result.graphs.find((g) => g.uri === GRAPH_URI);
    expect(graph).toBeDefined();
    expect(graph!.objectCount).toBe(2);
    expect(graph!.missingObjectCount).toBe(0);
  });
});
