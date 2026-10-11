// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '../server.js';

function createPairedConnection(): { client: lsp.Connection; server: lsp.Connection } {
  const clientTransport = new PassThrough({ objectMode: true });
  const serverTransport = new PassThrough({ objectMode: true });

  const clientReader = new lsp.StreamMessageReader(clientTransport as unknown as NodeJS.ReadableStream);
  const clientWriter = new lsp.StreamMessageWriter(serverTransport as unknown as NodeJS.WritableStream);
  const client = lsp.createConnection(clientReader, clientWriter) as lsp.Connection;

  const serverReader = new lsp.StreamMessageReader(serverTransport as unknown as NodeJS.ReadableStream);
  const serverWriter = new lsp.StreamMessageWriter(clientTransport as unknown as NodeJS.WritableStream);
  const server = lsp.createConnection(serverReader, serverWriter) as lsp.Connection;

  client.listen();
  server.listen();

  return { client, server };
}

async function sleep(ms: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

describe('document-symbol', () => {
  let clientConnection: lsp.Connection;
  let serverConnection: lsp.Connection;

  beforeEach(async () => {
    const { client, server } = createPairedConnection();
    clientConnection = client;
    serverConnection = server;
    createServerConnection(serverConnection);
  });

  afterEach(() => {
    clientConnection.dispose();
    serverConnection.dispose();
  });

  it('returns hierarchical tree: package -> schema -> defs -> properties', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null,
      rootUri: 'file:///proj',
      capabilities: { documentSymbol: {} },
    });
    clientConnection.sendNotification('initialized', {});

    const content = `package billing.invoicing

model er schema entity

def entity artikl {
  nameAttribute: er.entity.nazev
  attributes {
    def attribute kod {
      type: string
    }
  }
}

def entity faktura {
  to: er.entity.artikl
}`;

    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///proj/billing/invoicing/artikl.ttrm',
        languageId: 'ttr',
        version: 1,
        text: content,
      },
    });

    await sleep(50);

    const result = await clientConnection.sendRequest('textDocument/documentSymbol', {
      textDocument: { uri: 'file:///proj/billing/invoicing/artikl.ttrm' },
    }) as { kind: number; name: string; children?: unknown[] }[];

    expect(result.length).toBeGreaterThan(0);
    const pkg = result.find((s) => s.name === 'billing.invoicing');
    expect(pkg).toBeDefined();
    expect(pkg!.kind).toBe(lsp.SymbolKind.Package);
    expect(pkg!.children).toBeDefined();
    expect(pkg!.children!.length).toBeGreaterThan(0);

    const schema = pkg!.children!.find((s: unknown) => (s as { name: string }).name.startsWith('er.')) as { kind: number; name: string; children?: unknown[] } | undefined;
    expect(schema).toBeDefined();
    expect(schema!.kind).toBe(lsp.SymbolKind.Namespace);

    const entities = schema!.children!;
    const artikl = entities.find((s: unknown) => (s as { name: string }).name === 'artikl') as { kind: number; name: string; children?: unknown[] };
    expect(artikl).toBeDefined();
    expect(artikl.kind).toBe(lsp.SymbolKind.Class);
    expect(artikl.children, 'artikl should have attribute children').toBeDefined();
    const kod = artikl.children!.find((s: unknown) => (s as { name: string }).name === 'kod') as { kind: number; name: string };
    expect(kod).toBeDefined();
    expect(kod.kind).toBe(lsp.SymbolKind.Field, 'attribute child should have kind Field');
  });

  it('file with no package declaration: root is the schema directive', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null,
      rootUri: 'file:///proj',
      capabilities: { documentSymbol: {} },
    });
    clientConnection.sendNotification('initialized', {});

    const content = `model er schema entity

def entity artikl {
}`;

    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///proj/artikl.ttrm',
        languageId: 'ttr',
        version: 1,
        text: content,
      },
    });

    await sleep(50);

    const result = await clientConnection.sendRequest('textDocument/documentSymbol', {
      textDocument: { uri: 'file:///proj/artikl.ttrm' },
    }) as { kind: number; name: string }[];

    expect(result.length).toBeGreaterThan(0);
    const schemaSymbol = result.find((s) => s.name.startsWith('er.'));
    expect(schemaSymbol).toBeDefined();
    expect(schemaSymbol!.kind).toBe(lsp.SymbolKind.Namespace);
  });

  it('every symbol satisfies selectionRange ⊆ range, incl. multi-line defs whose `}` is less indented', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null, rootUri: 'file:///proj', capabilities: { documentSymbol: {} },
    });
    clientConnection.sendNotification('initialized', {});

    // The attribute def starts at column 8 but its closing `}` sits at column 0,
    // so the def's end column (1) is less than its start column (8). The old
    // selectionRange (start..endColumn on the start line) inverted here, which
    // VS Code rejects with "selectionRange must be contained in fullRange".
    const content = `model er schema entity

def entity promotion {
    attributes: [
        def attribute sk { type: int,
description: "FK (promotion.p_promo_sk)",
binding: p_promo_sk,
}
    ]
}`;
    const uri = 'file:///proj/promotion.ttrm';
    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: { uri, languageId: 'ttr', version: 1, text: content },
    });
    await sleep(50);

    type Sym = { name: string; range: lsp.Range; selectionRange: lsp.Range; children?: Sym[] };
    const result = await clientConnection.sendRequest('textDocument/documentSymbol', {
      textDocument: { uri },
    }) as Sym[];

    const lte = (a: lsp.Position, b: lsp.Position) => a.line < b.line || (a.line === b.line && a.character <= b.character);
    const violations: string[] = [];
    const walk = (syms: Sym[]) => {
      for (const s of syms) {
        const r = s.range, sr = s.selectionRange;
        if (!(lte(r.start, sr.start) && lte(sr.start, sr.end) && lte(sr.end, r.end))) {
          violations.push(`${s.name}: sel ${JSON.stringify(sr)} not within range ${JSON.stringify(r)}`);
        }
        if (s.children) walk(s.children);
      }
    };
    walk(result);
    expect(violations, violations.join('\n')).toEqual([]);
  });

  it('.ttrg file: root is the graph block; children are the listed objects', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null,
      rootUri: 'file:///proj',
      capabilities: { documentSymbol: {} },
    });
    clientConnection.sendNotification('initialized', {});

    const content = `graph test_graph {
  model: er
  objects: [
    er.entity.artikl,
    er.entity.faktura
  ]
}`;

    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///proj/graphs/test.ttrg',
        languageId: 'ttrg',
        version: 1,
        text: content,
      },
    });

    await sleep(50);

    const result = await clientConnection.sendRequest('textDocument/documentSymbol', {
      textDocument: { uri: 'file:///proj/graphs/test.ttrg' },
    }) as { kind: number; name: string; children?: { name: string }[] }[];

    expect(result.length).toBeGreaterThan(0);
    const graphSymbol = result.find((s) => s.name === 'test_graph');
    expect(graphSymbol).toBeDefined();
    expect(graphSymbol!.kind).toBe(lsp.SymbolKind.File);
    expect(graphSymbol!.children).toBeDefined();
    expect(graphSymbol!.children!.length).toBe(2);
    expect(graphSymbol!.children!.some((c) => c.name === 'er.entity.artikl')).toBe(true);
    expect(graphSymbol!.children!.some((c) => c.name === 'er.entity.faktura')).toBe(true);
  });
});