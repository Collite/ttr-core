// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { createServerConnection } from '@tatrman/lsp/server';

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

const GOLDEN = join(__dirname, '../../conformance/md-hop');
const PROJECT = join(__dirname, '../../../packages/kotlin/ttr-metadata/src/test/resources/md-hop');
const ROOT = 'file:///md-hop';

describe('MD hop report, roles and open-buffer invalidation', () => {
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
    for (const f of readdirSync(PROJECT).filter((f) => f.endsWith('.ttrm')).sort()) {
      client.sendNotification('textDocument/didOpen', {
        textDocument: { uri: `${ROOT}/${f}`, languageId: 'ttr', version: 1, text: readFileSync(join(PROJECT, f), 'utf-8') },
      });
    }
    await sleep(400);
  });
  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  it('answers the golden report and its markdown', async () => {
    const r = await client.sendRequest<{ report: unknown; markdown: string }>('modeler/getMdHop');
    const expected = JSON.parse(readFileSync(join(GOLDEN, 'expected.json'), 'utf-8'));
    expect(r.report).toEqual(expected);
    expect(r.markdown).toBe(readFileSync(join(GOLDEN, 'expected.md'), 'utf-8'));
  });

  it('shows derived entity roles on hover', async () => {
    const text = readFileSync(join(PROJECT, 'er.ttrm'), 'utf8');
    const lines = text.split('\n'); const line = lines.findIndex((s) => s.startsWith('def entity customer {'));
    const result = await client.sendRequest<lsp.Hover>('textDocument/hover', { textDocument: { uri: `${ROOT}/er.ttrm` }, position: { line, character: 14 } });
    expect(JSON.stringify(result)).toContain('Derived warehouse role:** dimension');
  });
  it('recomputes the report after an open-buffer binding edit', async () => {
    const text = readFileSync(join(PROJECT, 'binding.ttrm'), 'utf8').replace('cubelet: md.orphan, target: db.dbo.orphan', 'cubelet: md.orphan, target: db.dbo.store_sales');
    client.sendNotification('textDocument/didChange', { textDocument: { uri: `${ROOT}/binding.ttrm`, version: 2 }, contentChanges: [{ text }] });
    await sleep(300);
    const r = await client.sendRequest<{ report: { elements: Array<{ element: string; erTarget: string | null }> } }>('modeler/getMdHop');
    expect(r.report.elements.find((e) => e.element === 'md.cubelet.orphan')?.erTarget).toBe('er.entity.store_sales');
  });
});
