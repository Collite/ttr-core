// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';

/**
 * CN-P2 S2.3 — the model-level `cn/*` slugs reach the editor through lint: a check on one file sees the
 * project (a concept's grounding written in another file), each diagnostic is published on the file
 * it points at, the hygiene slugs stay out of the editor under the default `recommended` preset, and
 * fixing the model clears the diagnostic on the next change.
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

const ROOT = 'file:///cn-diag';
const ER = `${ROOT}/a-er.ttrm`;
const CN = `${ROOT}/b-concepts.ttrm`;
const BINDING = `${ROOT}/c-bindings.ttrm`;

const ER_TEXT = `package hartland.stores
model er

def entity party { attributes: [ def attribute party_id { type: int, isKey: true } ] }
def entity sale {
    attributes: [ def attribute sale_id { type: int, isKey: true }, def attribute sold_date { type: date } ]
}
`;
const CN_TEXT = `package hartland.stores
model cn

def kind Party     { grounds: er.entity.party }
def event Purchase { involves: [Party], period: { at: sold_date }, grounds: er.entity.sale }
def role Visitor   { of: Party, grounds: er.entity.party }
def kind Brand     { label: { en: "Brand" } }
`;
const BINDING_TEXT = `package hartland.stores
model binding

def cn2er_kind party_again { kind: cn.Party, target: er.entity.sale }
`;

describe('CN-P2 S2.3 — cn diagnostics in the editor', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;
  const published = new Map<string, lsp.Diagnostic[]>();

  const open = (uri: string, text: string) =>
    client.sendNotification('textDocument/didOpen', { textDocument: { uri, languageId: 'ttr', version: 1, text } });
  const cn = (uri: string) => (published.get(uri) ?? []).filter((d) => String(d.code).startsWith('cn/'));

  beforeAll(async () => {
    const p = createPairedConnection();
    client = p.client;
    server = p.server;
    client.onNotification('textDocument/publishDiagnostics', (params: lsp.PublishDiagnosticsParams) => {
      published.set(params.uri, params.diagnostics);
    });
    createServerConnection(server);
    await client.sendRequest('initialize', {
      processId: null,
      rootUri: ROOT,
      workspaceFolders: [{ uri: ROOT, name: 'cn-diag' }],
      capabilities: {},
    });
    client.sendNotification('initialized', {});
    open(ER, ER_TEXT);
    open(CN, CN_TEXT);
    open(BINDING, BINDING_TEXT);
    await sleep(400);
    // Re-publish the concepts with the whole project open.
    client.sendNotification('textDocument/didChange', { textDocument: { uri: CN, version: 2 }, contentChanges: [{ text: CN_TEXT }] });
    await sleep(300);
  });
  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  it('a role with no event is an error on its def', () => {
    const d = cn(CN).find((x) => x.code === 'cn/role-no-event');
    expect(d?.severity).toBe(lsp.DiagnosticSeverity.Error);
    expect(d?.range.start.line).toBe(5);
    expect(d?.message).toContain("role 'Visitor' names no defining event");
  });

  it('a second grounding is published on the binding’s file, naming the first', () => {
    const d = cn(BINDING).find((x) => x.code === 'cn/duplicate-grounding');
    expect(d?.range.start.line).toBe(3);
    expect(d?.message).toContain("'Party' is already grounded at");
  });

  it('hygiene stays out of the editor under `recommended` (Brand is ungrounded, not reported)', () => {
    expect(cn(CN).map((d) => d.code)).not.toContain('cn/ungrounded');
  });

  it('fixing the model clears the diagnostic', async () => {
    client.sendNotification('textDocument/didChange', {
      textDocument: { uri: CN, version: 3 },
      contentChanges: [{ text: CN_TEXT.replace('{ of: Party, grounds', '{ of: Party, event: Purchase, grounds') }],
    });
    await sleep(300);
    expect(cn(CN).map((d) => d.code)).not.toContain('cn/role-no-event');
  });
});
