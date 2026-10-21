// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { createServerConnection } from '@tatrman/lsp/server';

/**
 * CN-P2 S2.7 T3 — `modeler/getCnHygiene`: the editor's hygiene report over the project the server holds,
 * the data the golden pins (`tests/conformance/cn-hygiene/expected.json` less the Kotlin-only
 * `definitions`, CN-D29) and the markdown the VS Code command previews (`expected-editor.md`).
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

const GOLDEN = join(__dirname, '../../conformance/cn-hygiene');
const PROJECT = join(GOLDEN, 'hartland/hygiene');
const ROOT = 'file:///cn-hygiene';

describe('CN-P2 S2.7 — modeler/getCnHygiene', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;

  beforeAll(async () => {
    const p = createPairedConnection();
    client = p.client;
    server = p.server;
    createServerConnection(server);
    await client.sendRequest('initialize', {
      processId: null,
      rootUri: ROOT,
      workspaceFolders: [{ uri: ROOT, name: 'cn-hygiene' }],
      capabilities: {},
    });
    client.sendNotification('initialized', {});
    for (const f of readdirSync(PROJECT).sort()) {
      client.sendNotification('textDocument/didOpen', {
        textDocument: { uri: `${ROOT}/hartland/hygiene/${f}`, languageId: 'ttr', version: 1, text: readFileSync(join(PROJECT, f), 'utf-8') },
      });
    }
    await sleep(400);
  });
  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  it('answers the golden report and its markdown', async () => {
    const r = await client.sendRequest<{ report: unknown; markdown: string }>('modeler/getCnHygiene');
    const expected = JSON.parse(readFileSync(join(GOLDEN, 'expected.json'), 'utf-8'));
    delete expected.definitions;
    expect(r.report).toEqual(expected);
    expect(r.markdown).toBe(readFileSync(join(GOLDEN, 'expected-editor.md'), 'utf-8'));
  });

  it('follows an edit: grounding Supplier moves it off ungrounded', async () => {
    const uri = `${ROOT}/hartland/hygiene/c-concepts.ttrm`;
    const text = readFileSync(join(PROJECT, 'c-concepts.ttrm'), 'utf-8').replace(
      'def kind Supplier    { relations',
      'def kind Supplier    { grounds: er.entity.shopper, relations',
    );
    client.sendNotification('textDocument/didChange', { textDocument: { uri, version: 2 }, contentChanges: [{ text }] });
    await sleep(300);
    const r = await client.sendRequest<{ report: { concepts: Array<{ concept: string; grade: string }> } }>('modeler/getCnHygiene');
    expect(r.report.concepts.find((c) => c.concept === 'cn.Supplier')?.grade).toBe('er-grounded');
  });
});
