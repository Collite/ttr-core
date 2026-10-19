// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';

/**
 * Grammar 0.16 (AU-core-P0 S0.2 T4) — hover on the automation model: a `def parameter` shows its
 * value, type and label; a `def action` its columns and sinks. Both resolve through the symbol table
 * (`<pkg>.automation.<kind>.<name>`).
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

const URI = 'file:///au/automation.ttrm';

// line 3 = the parameter, line 4 = the action
const FILE = `package hartland.automation
model automation

def parameter tolerance { type: decimal, value: 0.01, label: { en: "Price tolerance", cs: "Cenová tolerance" } }
def action release { columns: [def column order { type: order.order_number }], sinks: { native: transition, n8n: door }, approval: none }
`;

describe('grammar 0.16 — hover on the automation model', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;

  beforeAll(async () => {
    const p = createPairedConnection();
    client = p.client;
    server = p.server;
    createServerConnection(server);
    await client.sendRequest('initialize', {
      processId: null,
      rootUri: 'file:///au',
      workspaceFolders: [{ uri: 'file:///au', name: 'au' }],
      capabilities: {},
    });
    client.sendNotification('initialized', {});
    client.sendNotification('textDocument/didOpen', { textDocument: { uri: URI, languageId: 'ttr', version: 1, text: FILE } });
    await sleep(300);
  });
  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  async function hover(line: number, character: number): Promise<string> {
    const h = (await client.sendRequest('textDocument/hover', {
      textDocument: { uri: URI },
      position: { line, character },
    })) as lsp.Hover | null;
    expect(h, `hover returned null at ${line}:${character}`).not.toBeNull();
    return (h!.contents as lsp.MarkupContent).value;
  }

  it("a parameter's hover shows its value, type and label", async () => {
    const value = await hover(3, 16); // inside `tolerance`
    expect(value).toContain('hartland.automation.automation.parameter.tolerance');
    expect(value).toContain('**Value:** `0.01` *(decimal)*');
    expect(value).toContain('**Label:** en: Price tolerance · cs: Cenová tolerance');
  });

  it("an action's hover shows its columns and sinks", async () => {
    const value = await hover(4, 13); // inside `release`
    expect(value).toContain('hartland.automation.automation.action.release');
    expect(value).toContain('**Columns:** `order: order.order_number`');
    expect(value).toContain('**Sinks:** native → transition · n8n → door');
  });
});
