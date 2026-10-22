// SPDX-License-Identifier: Apache-2.0
//
// FO-A1 W2 (task 2.6, contracts §5) — member resolution over the LIVE LSP server path
// (paired-connection harness, NOT a unit mock — the DS-P3 "test the live path" lesson).
// Boots the real server, opens the metadata fixture, and drives modeler/getSymbolDetail +
// modeler/listSymbols the way the Worker data source does, asserting a nested member
// resolves to a member detail carrying a lineageRoot (kind:'member').

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';
import path from 'path';

const samplesDir = path.resolve(__dirname, '../../../samples');

function createPairedConnection(): { client: lsp.Connection; server: lsp.Connection } {
  const clientTransport = new PassThrough({ objectMode: true });
  const serverTransport = new PassThrough({ objectMode: true });
  const client = lsp.createConnection(
    new lsp.StreamMessageReader(clientTransport as unknown as NodeJS.ReadableStream),
    new lsp.StreamMessageWriter(serverTransport as unknown as NodeJS.WritableStream),
  ) as lsp.Connection;
  const server = lsp.createConnection(
    new lsp.StreamMessageReader(serverTransport as unknown as NodeJS.ReadableStream),
    new lsp.StreamMessageWriter(clientTransport as unknown as NodeJS.WritableStream),
  ) as lsp.Connection;
  client.listen();
  server.listen();
  return { client, server };
}
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

interface Detail {
  qname: string;
  kind: string;
  name: string;
  perKindData?: { kind: string; attributes?: Array<{ name: string; qname: string }>; columns?: Array<{ name: string; qname: string }> };
  member?: { memberPath: string[]; parent: string; memberKind: string };
  lineageRoot?: { kind: string; qname: string; label: string };
}

describe('member lineage — live LSP path (W2)', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;

  beforeAll(async () => {
    const pair = createPairedConnection();
    client = pair.client;
    server = pair.server;
    createServerConnection(server);
    await client.sendRequest('initialize', { processId: null, rootUri: null, capabilities: {} });
    client.sendNotification('initialized', {});
    const files = ['v1-metadata/map.ttrm', 'v1-metadata/er.ttrm', 'v1-metadata/modeler.toml'];
    const fs = await import('fs/promises');
    for (const rel of files) {
      const p = path.join(samplesDir, rel);
      const text = await fs.readFile(p, 'utf-8');
      client.sendNotification('textDocument/didOpen', { textDocument: { uri: `file://${p}`, languageId: 'ttr', version: 1, text } });
    }
    await sleep(200);
  });

  afterAll(() => { client.dispose(); server.dispose(); });

  it('getSymbolDetail on a nested ATTRIBUTE member resolves the member (not the parent) + lineageRoot', async () => {
    // discover a real attribute of er.entity.item from the parent detail (no hardcoded name)
    const parent = await client.sendRequest('modeler/getSymbolDetail', { qname: 'er.entity.item' }) as Detail | null;
    expect(parent, 'parent er.entity.item must resolve').not.toBeNull();
    const attrs = parent!.perKindData?.attributes ?? [];
    expect(attrs.length, 'er.entity.item must have at least one attribute').toBeGreaterThanOrEqual(1);
    const memberQname = attrs[0].qname;

    const member = await client.sendRequest('modeler/getSymbolDetail', { qname: memberQname }) as Detail | null;
    expect(member, `member ${memberQname} must resolve over the live path`).not.toBeNull();
    expect(member!.kind).toBe('member');
    expect(member!.name).toBe(attrs[0].name);
    expect(member!.member?.parent).toBe('er.entity.item');
    expect(member!.member?.memberKind).toBe('attribute');
    expect(member!.lineageRoot).toEqual({ kind: 'member', qname: memberQname, label: attrs[0].name });
  });

  it('listSymbols includeMembers:true surfaces member refs (kind:"member" + memberPath + parent)', async () => {
    const { symbols: withMembers } = await client.sendRequest('modeler/listSymbols', { includeMembers: true, limit: 5000 }) as import('@tatrman/lsp').SymbolPage;
    const members = withMembers.filter((s) => s.kind === 'member');
    expect(members.length, 'includeMembers must surface at least one member ref').toBeGreaterThanOrEqual(1);
    const m = members[0];
    expect(m.memberPath, 'member ref carries memberPath').toBeTruthy();
    expect(m.parent, 'member ref carries parent qname').toBeTruthy();
    expect(m.qname.startsWith(`${m.parent}.`), `member qname ${m.qname} is under parent ${m.parent}`).toBe(true);

    // and the default (no includeMembers) does NOT include members — additive, opt-in
    const { symbols: noMembers } = await client.sendRequest('modeler/listSymbols', { limit: 5000 }) as import('@tatrman/lsp').SymbolPage;
    expect(noMembers.some((s) => s.kind === 'member')).toBe(false);
  });
});
