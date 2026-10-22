// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { selectBackend, serverWorkspaceName, BackendSelectionError } from '../select-data-source.js';

describe('selectBackend', () => {
  it('selects host explicitly, overrides the runtime catalog, and rejects mixed selectors', () => {
    expect(selectBackend('?backend=host', { veles: '/veles' })).toEqual({ kind: 'host' });
    for (const query of ['backend=unknown', 'backend=host&demo=x', 'backend=host&veles=/veles', 'backend=host&server=ws://localhost']) {
      expect(() => selectBackend(query)).toThrow(BackendSelectionError);
    }
  });
  it('selects the WS backend for a loopback ?server= origin', () => {
    expect(selectBackend('?server=ws://127.0.0.1:7270')).toEqual({ kind: 'ws', origin: 'ws://127.0.0.1:7270' });
    expect(selectBackend('?server=ws://localhost:7270')).toEqual({ kind: 'ws', origin: 'ws://localhost:7270' });
  });

  it('strips a trailing slash from the origin', () => {
    expect(selectBackend('?server=ws://127.0.0.1:7270/')).toEqual({ kind: 'ws', origin: 'ws://127.0.0.1:7270' });
  });

  it('defaults to the worker path with no params', () => {
    expect(selectBackend('')).toEqual({ kind: 'worker', demo: null });
  });

  it('keeps ?demo= on the worker path', () => {
    expect(selectBackend('?demo=v1.1-mini')).toEqual({ kind: 'worker', demo: 'v1.1-mini' });
  });

  it('rejects a non-loopback ?server= host (no guessing fallback)', () => {
    expect(() => selectBackend('?server=ws://evil.example.com:7270')).toThrow(BackendSelectionError);
  });

  it('rejects a ?server= value carrying a path', () => {
    expect(() => selectBackend('?server=ws://127.0.0.1:7270/ttrm')).toThrow(BackendSelectionError);
  });

  it('rejects a malformed ?server= value', () => {
    expect(() => selectBackend('?server=not-a-url')).toThrow(BackendSelectionError);
  });

  it('rejects ?server= and ?demo= together (conflicting backends)', () => {
    expect(() => selectBackend('?server=ws://127.0.0.1:7270&demo=v1.1-mini')).toThrow(BackendSelectionError);
  });

  it('selects the read-api Veles backend for a same-origin path prefix', () => {
    expect(selectBackend('?veles=/veles')).toEqual({ kind: 'veles', transport: 'read-api', base: '/veles' });
  });

  it('normalizes `?veles=/` (origin root) to an empty base', () => {
    expect(selectBackend('?veles=/')).toEqual({ kind: 'veles', transport: 'read-api', base: '' });
  });

  it('strips a trailing slash from a Veles path prefix', () => {
    expect(selectBackend('?veles=/veles/')).toEqual({ kind: 'veles', transport: 'read-api', base: '/veles' });
  });

  it('rejects a protocol-relative `?veles=//host` (cross-origin bypass of the same-origin path branch)', () => {
    // `//evil.example` has no scheme/query/fragment, so it would slip through the path branch and fetch
    // cross-origin with credentials. It must be rejected, not treated as a same-origin path.
    expect(() => selectBackend('?veles=//evil.example')).toThrow(BackendSelectionError);
    expect(() => selectBackend('?veles=//evil.example/model')).toThrow(BackendSelectionError);
  });

  it('selects the read-api Veles backend for a full http(s) origin (non-loopback allowed)', () => {
    expect(selectBackend('?veles=http://localhost:7260')).toEqual({ kind: 'veles', transport: 'read-api', base: 'http://localhost:7260' });
    expect(selectBackend('?veles=https://veles.example.com')).toEqual({
      kind: 'veles',
      transport: 'read-api',
      base: 'https://veles.example.com',
    });
  });

  // VS-3: `?veles=` dispatches on scheme — ws(s) → the platform Veles (ttrm + bearer).
  it('?veles= dispatches on scheme: http → read-api, wss → ttrm (RO-31 / VS-3)', () => {
    expect(selectBackend('?veles=http://localhost:7260')).toMatchObject({ kind: 'veles', transport: 'read-api' });
    expect(selectBackend('?veles=wss://veles.example.com')).toEqual({
      kind: 'veles',
      transport: 'ttrm',
      origin: 'wss://veles.example.com',
      token: null,
    });
    expect(selectBackend('?veles=ws://localhost:7260/')).toEqual({
      kind: 'veles',
      transport: 'ttrm',
      origin: 'ws://localhost:7260',
      token: null,
    });
  });

  it('carries the bearer from `?velesToken=` on the ttrm transport (dev-only)', () => {
    expect(selectBackend('?veles=wss://veles.example.com&velesToken=abc.def')).toEqual({
      kind: 'veles',
      transport: 'ttrm',
      origin: 'wss://veles.example.com',
      token: 'abc.def',
    });
  });

  it('rejects a `?veles=` origin that carries a path', () => {
    expect(() => selectBackend('?veles=http://localhost:7260/model')).toThrow(BackendSelectionError);
    expect(() => selectBackend('?veles=wss://veles.example.com/v1/ttrm')).toThrow(BackendSelectionError);
  });

  it('rejects a malformed `?veles=` value', () => {
    expect(() => selectBackend('?veles=not a url')).toThrow(BackendSelectionError);
  });

  it('rejects `?veles=` and `?server=` together (conflicting backends)', () => {
    expect(() => selectBackend('?veles=/veles&server=ws://127.0.0.1:7270')).toThrow(BackendSelectionError);
  });

  it('rejects `?veles=` and `?demo=` together (conflicting backends)', () => {
    expect(() => selectBackend('?veles=/veles&demo=v1.1-mini')).toThrow(BackendSelectionError);
  });

  // ttr-core#169 — the deployment's catalog comes from the runtime config (config.json), not a
  // query parameter. The URL still wins: an explicit choice beats a deployment default.
  describe('runtime-config default (VIEWER_CATALOG_URL)', () => {
    it('opens the configured catalog when the URL selects nothing', () => {
      expect(selectBackend('', { veles: '/veles' })).toEqual({ kind: 'veles', transport: 'read-api', base: '/veles' });
      expect(selectBackend('', { veles: '/models/veles' })).toEqual({ kind: 'veles', transport: 'read-api', base: '/models/veles' });
      expect(selectBackend('', { veles: 'wss://veles.example.com' })).toEqual({
        kind: 'veles', transport: 'ttrm', origin: 'wss://veles.example.com', token: null,
      });
    });

    it('lets ?veles= / ?server= / ?demo= override the configured catalog', () => {
      const cfg = { veles: '/veles' };
      expect(selectBackend('?veles=http://localhost:7260', cfg)).toEqual({ kind: 'veles', transport: 'read-api', base: 'http://localhost:7260' });
      expect(selectBackend('?server=ws://127.0.0.1:7270', cfg)).toEqual({ kind: 'ws', origin: 'ws://127.0.0.1:7270' });
      expect(selectBackend('?demo=v1.1-mini', cfg)).toEqual({ kind: 'worker', demo: 'v1.1-mini' });
    });

    it('falls back to the worker landing with no configured catalog', () => {
      expect(selectBackend('', { veles: null })).toEqual({ kind: 'worker', demo: null });
    });

    it('rejects a malformed configured catalog visibly, naming where it came from', () => {
      expect(() => selectBackend('', { veles: '//evil.example.com' })).toThrow(/config\.json/);
      expect(() => selectBackend('', { veles: 'http://localhost:7260/model' })).toThrow(BackendSelectionError);
    });
  });
});

// ttr-core#171 — the workspace segment of a server backend's URLs is a short name, not the raw
// backend value (which percent-encodes into `/w/%2Fmodels%2Fveles/…`).
describe('serverWorkspaceName', () => {
  it('names a same-origin catalog by its last path segment, an origin by its host', () => {
    expect(serverWorkspaceName({ kind: 'veles', transport: 'read-api', base: '/veles' })).toBe('veles');
    expect(serverWorkspaceName({ kind: 'veles', transport: 'read-api', base: '/models/veles' })).toBe('veles');
    expect(serverWorkspaceName({ kind: 'veles', transport: 'read-api', base: 'http://localhost:7260' })).toBe('localhost:7260');
    expect(serverWorkspaceName({ kind: 'veles', transport: 'ttrm', origin: 'wss://veles.example.com', token: null })).toBe('veles.example.com');
    expect(serverWorkspaceName({ kind: 'ws', origin: 'ws://127.0.0.1:7270' })).toBe('127.0.0.1:7270');
    expect(serverWorkspaceName({ kind: 'veles', transport: 'read-api', base: '' })).toBe('catalog');
  });
});
