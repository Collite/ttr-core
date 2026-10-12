// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { FakeWebSocket } from './fake-websocket.js';
import {
  VelesTtrmDataSource,
  ModelNotLoadedError,
  ProtocolVersionMismatchError,
} from '../veles-ttrm-data-source.js';

const FIXTURES = join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'ttrm');
const fixture = (name: string) => JSON.parse(readFileSync(join(FIXTURES, name), 'utf8'));

/** Wire an adapter to a fresh FakeWebSocket; capture the socket (with its handshake connect info). */
function wired(token?: string): { source: VelesTtrmDataSource; socket: () => FakeWebSocket } {
  let sock: FakeWebSocket | null = null;
  const source = new VelesTtrmDataSource('wss://veles.example', {
    token,
    wsClientOptions: {
      wsFactory: (url, connect) => {
        sock = new FakeWebSocket(url, connect);
        return sock;
      },
    },
  });
  return { source, socket: () => sock! };
}

async function untilSent(s: FakeWebSocket, n: number): Promise<void> {
  for (let i = 0; i < 100 && s.sent.length < n; i++) await Promise.resolve();
}

/** Connect + answer the getStatus handshake; return the live socket. */
async function establish(source: VelesTtrmDataSource, socket: () => FakeWebSocket): Promise<FakeWebSocket> {
  const cp = source.connect();
  const s = socket();
  await untilSent(s, 1);
  s.receive({ ...fixture('get-status.json'), id: s.lastSent().id });
  await cp;
  return s;
}

/** Answer the pending request `s` with `fixtureName`, then await `p`. */
async function answer<T>(s: FakeWebSocket, before: number, p: Promise<T>, fixtureName: string): Promise<T> {
  await untilSent(s, before + 1);
  s.receive({ ...fixture(fixtureName), id: s.lastSent().id });
  return p;
}

describe('VelesTtrmDataSource', () => {
  it('appends /v1/ttrm, sends the bearer on the handshake, and verifies protocolVersion', async () => {
    const { source, socket } = wired('tok-123');
    const connectP = source.connect();
    const s = socket();
    await untilSent(s, 1);
    expect(s.url).toBe('wss://veles.example/v1/ttrm');
    // VS-2: the bearer rides the handshake (a browser can't set it; a Node/dev factory / the test seam does).
    expect(s.connect?.headers?.Authorization).toBe('Bearer tok-123');
    expect(s.lastSent().method).toBe('ttrm/getStatus');
    s.receive({ ...fixture('get-status.json'), id: s.lastSent().id });
    const status = await connectP;
    expect(status.protocolVersion).toBe(1);
  });

  it('rejects a protocolVersion mismatch on connect', async () => {
    const { source, socket } = wired('t');
    const cp = source.connect();
    const s = socket();
    await untilSent(s, 1);
    s.receive({ jsonrpc: '2.0', id: s.lastSent().id, result: { protocolVersion: 2, modelVersion: null, loadedAt: null, repoRoot: '/x', issues: [] } });
    await expect(cp).rejects.toBeInstanceOf(ProtocolVersionMismatchError);
  });

  it('implements ModelDataSource: getModelIndex/getModelGraph/getObject/search resolve', async () => {
    const { source, socket } = wired('t');
    const s = await establish(source, socket);

    const index = await answer(s, s.sent.length, source.getModelIndex(), 'get-model-index.json');
    expect(index.schemas).toContain('db');

    const graph = await answer(s, s.sent.length, source.getModelGraph({ schema: 'db' }), 'get-model-graph.json');
    expect(Array.isArray(graph.nodes)).toBe(true);

    const obj = await answer(s, s.sent.length, source.getObject('acme.erp.db.customers'), 'get-object.json');
    expect(obj).toBeTruthy();

    const hits = await answer(s, s.sent.length, source.search({ query: 'cust' }), 'search.json');
    expect(Array.isArray(hits)).toBe(true);
  });

  it('capabilities.edit === false (read-only)', () => {
    expect(new VelesTtrmDataSource('wss://x').capabilities.edit).toBe(false);
    expect(new VelesTtrmDataSource('wss://x').capabilities.layoutPersist).toBe('file');
  });

  it('onModelChanged fires on a ttrm/modelChanged notification', async () => {
    const { source, socket } = wired('t');
    const s = await establish(source, socket);
    const cb = vi.fn();
    source.onModelChanged(cb);
    s.receive(fixture('model-changed.json'));
    expect(cb).toHaveBeenCalledOnce();
    expect(typeof cb.mock.calls[0][0]).toBe('string');
  });

  it('a JSON-RPC -32000 surfaces as a typed ModelNotLoadedError (NotLoaded signal), not an opaque error', async () => {
    const { source, socket } = wired('t');
    const s = await establish(source, socket);
    const p = source.getModelIndex();
    await untilSent(s, s.sent.length + 1);
    s.receive({ jsonrpc: '2.0', id: s.lastSent().id, error: { code: -32000, message: 'model-not-loaded', data: { kind: 'model-not-loaded' } } });
    await expect(p).rejects.toBeInstanceOf(ModelNotLoadedError);
  });
});
