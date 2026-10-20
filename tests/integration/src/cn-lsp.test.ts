// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';

/**
 * Grammar 0.17 (CN-P1 S1.2 T6) — the LSP over the conceptual model: the outline names each concept
 * def by its keyword, and a half-typed `def phase` (an unclosed body) still yields diagnostics and an
 * outline — the AST survives the syntax error.
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

const URI = 'file:///cn/concepts.ttrm';

const FILE = `package hartland.concepts
model cn

def kind Item { label: { en: "Product" }, grounds: md.Product }
def role Customer { of: Party, event: Order }
def event Order { involves: [ Customer, Warehouse as fulfilling ], period: { start: placed_date, end: delivered_date } }
def phase PendingOrder { of: Order, definition: """state in ('placed', 'held')""" }
`;

// The same file while the author is typing a new phase: its body is not closed yet.
const HALF_TYPED = `${FILE}def phase Overdue {
    of: Order,
    definition: """pl
        state = 'open'
    """,
    basis: `;

describe('grammar 0.17 — the LSP over model cn', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;
  const diagnostics = new Map<number, lsp.Diagnostic[]>();
  let version = 1;

  beforeAll(async () => {
    const p = createPairedConnection();
    client = p.client;
    server = p.server;
    client.onNotification('textDocument/publishDiagnostics', (params: lsp.PublishDiagnosticsParams) => {
      if (params.uri === URI) diagnostics.set(params.version ?? version, params.diagnostics);
    });
    createServerConnection(server);
    await client.sendRequest('initialize', {
      processId: null,
      rootUri: 'file:///cn',
      workspaceFolders: [{ uri: 'file:///cn', name: 'cn' }],
      capabilities: {},
    });
    client.sendNotification('initialized', {});
    client.sendNotification('textDocument/didOpen', { textDocument: { uri: URI, languageId: 'ttr', version, text: FILE } });
    await sleep(300);
  });
  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  async function outline(): Promise<{ name: string; detail?: string }[]> {
    const symbols = (await client.sendRequest('textDocument/documentSymbol', { textDocument: { uri: URI } })) as lsp.DocumentSymbol[];
    // package → model → the defs
    const defs = (list: lsp.DocumentSymbol[]): lsp.DocumentSymbol[] =>
      list.flatMap((s) => (s.detail?.startsWith('def ') ? [s] : defs(s.children ?? [])));
    return defs(symbols).map((s) => ({ name: s.name, detail: s.detail }));
  }

  it('outlines each concept def under its own keyword', async () => {
    expect(await outline()).toEqual([
      { name: 'Item', detail: 'def kind' },
      { name: 'Customer', detail: 'def role' },
      { name: 'Order', detail: 'def event' },
      { name: 'PendingOrder', detail: 'def phase' },
    ]);
    const parseErrors = (diagnostics.get(1) ?? []).filter((d) => d.code === 'ttr/parse-error');
    expect(parseErrors).toEqual([]);
  });

  it('keeps working on a half-typed `def phase`: a parse error, and the outline still there', async () => {
    version = 2;
    client.sendNotification('textDocument/didChange', {
      textDocument: { uri: URI, version },
      contentChanges: [{ text: HALF_TYPED }],
    });
    await sleep(300);
    const parseErrors = (diagnostics.get(2) ?? []).filter((d) => d.code === 'ttr/parse-error');
    expect(parseErrors.length).toBeGreaterThan(0);
    expect((await outline()).map((s) => s.name)).toEqual(['Item', 'Customer', 'Order', 'PendingOrder', 'Overdue']);
  });
});
