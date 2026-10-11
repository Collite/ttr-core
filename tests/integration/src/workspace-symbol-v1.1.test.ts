// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';
import path from 'path';
import fs from 'fs/promises';
import { fileURLToPath } from 'url';

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
  await new Promise((resolve) => setTimeout(resolve, ms));
}

async function loadFile(filePath: string): Promise<string> {
  return fs.readFile(filePath, 'utf-8');
}

describe('workspace-symbol-v1.1', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;

  beforeAll(async () => {
    const pair = createPairedConnection();
    client = pair.client;
    server = pair.server;
    createServerConnection(server);

    const rootUri = `file://${samplesDir}/v1.1-mini`;
    await client.sendRequest('initialize', {
      processId: null,
      rootUri,
      capabilities: {},
    });
    client.sendNotification('initialized', {});

    const files = [
      'billing/invoicing/item_in_class.ttrm',
      'billing/invoicing/er.ttrm',
      'billing/invoicing/db.ttrm',
      'billing/products/category.ttrm',
    ];

    for (const relPath of files) {
      const fullPath = path.join(samplesDir, 'v1.1-mini', relPath);
      const content = await loadFile(fullPath);
      client.sendNotification('textDocument/didOpen', {
        textDocument: { uri: `file://${fullPath}`, languageId: 'ttr', version: 1, text: content },
      });
    }
    await sleep(200);
  });

  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  it('workspace/symbol query=item returns hits with full package-prefixed qnames', async () => {
    const res = await client.sendRequest('workspace/symbol', { query: 'item' }) as lsp.SymbolInformation[];
    expect(res.length, `Expected at least 1 symbol for query "item", got ${res.length}`).toBeGreaterThanOrEqual(1);

    const hasPackagePrefix = res.some((sym) => sym.name.includes('.'));
    expect(hasPackagePrefix, `Expected package-prefixed qnames like "billing.invoicing.er.entity.item", got: ${res.map((s) => s.name).join(', ')}`).toBe(true);
  });

  it('workspace/symbol query=billing. returns every symbol in billing package and sub-packages', async () => {
    const res = await client.sendRequest('workspace/symbol', { query: 'billing.' }) as lsp.SymbolInformation[];
    expect(res.length, `Expected symbols in billing.* for query "billing.", got ${res.length}`).toBeGreaterThanOrEqual(1);

    const allInBilling = res.every((sym) => sym.name.startsWith('billing.'));
    expect(allInBilling, `Not all symbols are in billing.*: ${res.map((s) => s.name).join(', ')}`).toBe(true);
  });

  it('workspace/symbol query=billing.invoicing. returns symbols in specific sub-package', async () => {
    const res = await client.sendRequest('workspace/symbol', { query: 'billing.invoicing.' }) as lsp.SymbolInformation[];
    expect(res.length, `Expected symbols in billing.invoicing.*, got ${res.length}`).toBeGreaterThanOrEqual(1);

    const allCorrect = res.every((sym) => sym.name.startsWith('billing.invoicing.'));
    expect(allCorrect, `Not all symbols are in billing.invoicing.*: ${res.map((s) => s.name).join(', ')}`).toBe(true);
  });
});