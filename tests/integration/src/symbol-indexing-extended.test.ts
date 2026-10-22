// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';
import path from 'path';

const samplesDir = path.resolve(__dirname, '../../../samples');

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
  await new Promise(resolve => setTimeout(resolve, ms));
}

describe('symbol-indexing-extended (H.4)', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;

  beforeAll(async () => {
    const pair = createPairedConnection();
    client = pair.client;
    server = pair.server;
    createServerConnection(server);
    await client.sendRequest('initialize', {
      processId: null,
      rootUri: null,
      capabilities: {},
    });
    client.sendNotification('initialized', {});

    const mapPath = path.join(samplesDir, 'v1-metadata/map.ttrm');
    const erPath = path.join(samplesDir, 'v1-metadata/er.ttrm');
    const modelerPath = path.join(samplesDir, 'v1-metadata/modeler.toml');

    const [mapContent, erContent, modelerContent] = await Promise.all([
      import('fs/promises').then(fs => fs.readFile(mapPath, 'utf-8')),
      import('fs/promises').then(fs => fs.readFile(erPath, 'utf-8')),
      import('fs/promises').then(fs => fs.readFile(modelerPath, 'utf-8')),
    ]);

    client.sendNotification('textDocument/didOpen', {
      textDocument: { uri: `file://${erPath}`, languageId: 'ttr', version: 1, text: erContent },
    });
    client.sendNotification('textDocument/didOpen', {
      textDocument: { uri: `file://${mapPath}`, languageId: 'ttr', version: 1, text: mapContent },
    });
    client.sendNotification('textDocument/didOpen', {
      textDocument: { uri: `file://${modelerPath}`, languageId: 'ttr', version: 1, text: modelerContent },
    });
    await sleep(200);
  });

  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  it('workspace/symbol query="rel" includes at least one relation kind entry', async () => {
    const res = await client.sendRequest('workspace/symbol', { query: 'rel' }) as lsp.SymbolInformation[];
    expect(res.length, `Expected at least 1 symbol for query "rel", got ${res.length}`).toBeGreaterThanOrEqual(1);

    const hasRelation = await Promise.all(res.map(async (sym) => {
      const detail = await client.sendRequest('modeler/getSymbolDetail', { qname: sym.name }) as { kind: string; perKindData?: { kind: string } } | null;
      return (detail?.perKindData?.kind ?? detail?.kind) === 'relation';
    }));
    expect(hasRelation.some(Boolean), `No relation found among ${res.length} symbols for query "rel"`).toBe(true);
    // First workspace/symbol queries bear cold-start indexing + fan out a
    // getSymbolDetail round-trip per hit; ~5s on a loaded CI runner (warm by the
    // 3rd test). Give headroom over the 5s default.
  });

  it('workspace/symbol query="ent" floats entity-kind defs (kind-prefix boost)', async () => {
    const res = await client.sendRequest('workspace/symbol', { query: 'ent' }) as lsp.SymbolInformation[];
    expect(res.length, `Expected at least 1 symbol for query "ent", got ${res.length}`).toBeGreaterThanOrEqual(1);

    const firstKinds = await Promise.all(res.slice(0, 5).map(async (sym) => {
      const detail = await client.sendRequest('modeler/getSymbolDetail', { qname: sym.name }) as { kind: string; perKindData?: { kind: string } } | null;
      return detail?.perKindData?.kind ?? detail?.kind;
    }));
    expect(firstKinds, `top-5 kinds for "ent": ${firstKinds.join(', ')}`).toContain('entity');
  });

  it('workspace/symbol query="attr" floats attribute-kind defs (kind-prefix boost)', async () => {
    // Attributes are nested defs; verify via the LSP SymbolKind (attribute maps to
    // SymbolKind.Field, and no other kind in this fixture does). Note: since W2,
    // getSymbolDetail DOES resolve nested members (returning kind:'member') — see
    // member-lineage-live.test.ts — so this SymbolKind check is now belt-and-suspenders.
    const res = await client.sendRequest('workspace/symbol', { query: 'attr' }) as lsp.SymbolInformation[];
    expect(res.length, `Expected at least 1 symbol for query "attr", got ${res.length}`).toBeGreaterThanOrEqual(1);
    const topKinds = res.slice(0, 5).map((sym) => sym.kind);
    expect(topKinds, `top-5 SymbolKinds for "attr": ${topKinds.join(', ')}`).toContain(lsp.SymbolKind.Field);
  });

  it('listSymbols with kinds=[relation] returns at least one relation', async () => {
    const { symbols: allSymbols } = await client.sendRequest('modeler/listSymbols', { kinds: ['relation'], limit: 500 }) as import('@tatrman/lsp').SymbolPage;
    expect(allSymbols.length, `Expected at least 1 relation symbol, got ${allSymbols.length}`).toBeGreaterThanOrEqual(1);
    expect(allSymbols[0].kind).toBe('relation');
  });

  it('getSymbolDetail for a known relation qname returns non-null with perKindData.kind === relation', async () => {
    const { symbols: allSymbols } = await client.sendRequest('modeler/listSymbols', { kinds: ['relation'], limit: 500 }) as import('@tatrman/lsp').SymbolPage;
    expect(allSymbols.length, `Expected at least 1 relation symbol, got ${allSymbols.length}`).toBeGreaterThanOrEqual(1);

    const firstRelation = allSymbols[0];
    const detail = await client.sendRequest('modeler/getSymbolDetail', { qname: firstRelation.qname }) as { kind: string; perKindData?: { kind: string } } | null;
    expect(detail).not.toBeNull();
    expect(detail!.perKindData?.kind ?? detail!.kind).toBe('relation');
  });

  it('er.entity.item referencedBy includes at least one relation and one er2dbEntity', async () => {
    const detail = await client.sendRequest('modeler/getSymbolDetail', { qname: 'er.entity.item' }) as { qname: string; referencedBy: Array<{ qname: string }> } | null;
    expect(detail).not.toBeNull();
    expect(detail!.referencedBy.length, `Expected at least 1 referencedBy for er.entity.item, got ${detail!.referencedBy.length}`).toBeGreaterThanOrEqual(1);

    const referrerKinds = await Promise.all(
      detail!.referencedBy.map(async (r) => {
        const d = await client.sendRequest('modeler/getSymbolDetail', { qname: r.qname }) as { kind: string; perKindData?: { kind: string } } | null;
        return d?.perKindData?.kind ?? d?.kind ?? 'unknown';
      }),
    );

    expect(referrerKinds, `referencedBy kinds: ${referrerKinds.join(', ')}`).toContain('relation');
    expect(referrerKinds, `referencedBy kinds: ${referrerKinds.join(', ')}`).toContain('er2dbEntity');
  });
});
