// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Pin the EXACT request payloads lsp-client.ts sends today (the "zero behavior
// change" contract for the WorkerLspDataSource refactor). We mock the protocol
// connection and capture every sendRequest(method, params).
//
// This is the Worker-mode offline-fallback path (TP-5 T5.2, decided
// 2026-07-15: kept alongside WsDesignerServerDataSource's `ttrm/*`, not
// retired) — these `modeler/*` payloads staying pinned is the point.

const sent: Array<{ method: string; params: unknown }> = [];

vi.mock('vscode-languageserver-protocol/browser.js', () => ({
  BrowserMessageReader: class {},
  BrowserMessageWriter: class {},
  createProtocolConnection: () => ({
    listen: vi.fn(),
    onNotification: vi.fn(),
    sendNotification: vi.fn(),
    sendRequest: (methodOrType: unknown, params: unknown) => {
      const method = typeof methodOrType === 'string' ? methodOrType : (methodOrType as { method: string }).method;
      sent.push({ method, params });
      return Promise.resolve(defaultResult(method));
    },
  }),
}));

vi.mock('@tatrman/lsp/browser?worker', () => ({ default: class { terminate() {} } }));

function defaultResult(method: string): unknown {
  switch (method) {
    case 'modeler/listGraphs':
      return { graphs: [] };
    case 'modeler/getModelGraph':
      return { schemaCode: 'db', nodes: [], edges: [] };
    case 'modeler/getSymbolDetail':
      return null;
    case 'modeler/listSymbols':
      return { symbols: [], total: 0 };
    default:
      return {};
  }
}

import { createLspClient } from '../../lsp-client.js';
import { lspClientOver } from '../../lsp-connection.js';

beforeEach(() => {
  sent.length = 0;
});

describe('worker path regression pins (lsp-client.ts request payloads)', () => {
  it('reads every symbol page, retaining filters across requests', async () => {
    const request = vi.fn(async (method: string, params: unknown) => {
      if (method === 'initialize') return {};
      const cursor = (params as { cursor?: string }).cursor;
      return { symbols: [{ qname: cursor ? 'b' : 'a' }], total: 2, ...(cursor ? {} : { nextCursor: '1' }) };
    });
    const client = await lspClientOver({ sendRequest: request, sendNotification: vi.fn(), onNotification: vi.fn() }, { transportKind: 'browser', dispose: vi.fn() });
    expect((await client.listSymbols({ kinds: ['table'], limit: 1 })).map((s) => s.qname)).toEqual(['a', 'b']);
    expect(request).toHaveBeenLastCalledWith('modeler/listSymbols', { kinds: ['table'], limit: 1, cursor: '1' });
  });

  it('listGraphs sends modeler/listGraphs {projectRoot}', async () => {
    const client = await createLspClient();
    await client.listGraphs('file:///proj');
    expect(sent.at(-1)).toEqual({ method: 'modeler/listGraphs', params: { projectRoot: 'file:///proj' } });
  });

  it('getGraph sends modeler/getGraph {uri}', async () => {
    const client = await createLspClient();
    await client.getGraph('file:///g.ttrg');
    expect(sent.at(-1)).toEqual({ method: 'modeler/getGraph', params: { uri: 'file:///g.ttrg' } });
  });

  it('getModelGraph sends modeler/getModelGraph {textDocument:{uri}, schema}', async () => {
    const client = await createLspClient();
    await client.getModelGraph('file:///g.ttrg', 'db');
    expect(sent.at(-1)).toEqual({
      method: 'modeler/getModelGraph',
      params: { textDocument: { uri: 'file:///g.ttrg' }, schema: 'db' },
    });
  });

  it('getSymbolDetail sends modeler/getSymbolDetail {qname}', async () => {
    const client = await createLspClient();
    await client.getSymbolDetail('db.dbo.customers');
    expect(sent.at(-1)).toEqual({ method: 'modeler/getSymbolDetail', params: { qname: 'db.dbo.customers' } });
  });

  it('listSymbols sends modeler/listSymbols {kinds?,limit?}', async () => {
    const client = await createLspClient();
    await client.listSymbols({ kinds: ['table'], limit: 50 });
    expect(sent.at(-1)).toEqual({ method: 'modeler/listSymbols', params: { kinds: ['table'], limit: 50 } });
    await client.listSymbols();
    expect(sent.at(-1)).toEqual({ method: 'modeler/listSymbols', params: {} });
  });

  it('getPackageGraph sends modeler/getPackageGraph {}', async () => {
    const client = await createLspClient();
    await client.getPackageGraph();
    expect(sent.at(-1)).toEqual({ method: 'modeler/getPackageGraph', params: {} });
  });
});
