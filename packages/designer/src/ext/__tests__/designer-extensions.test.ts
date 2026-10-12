// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi } from 'vitest';
import {
  loadDesignerExtensions,
  type BackendInfo,
  type ExtensionContext,
  type DesignerExtension,
} from '../designer-extensions.js';
import type { DataSourceCapabilities, ModelDataSource } from '../../data/model-data-source.js';

const CAPS: DataSourceCapabilities = {
  edit: false,
  modelKinds: ['db', 'er'],
  bindings: false,
  perspectives: false,
  layoutPersist: 'file',
  graphShape: 'structural',
} as const;

const dataSource = {} as ModelDataSource;

function ctxFor(backend: BackendInfo): ExtensionContext {
  return { dataSource, backend, auth: { token: async () => 'tok' } };
}

function velesBackend(baseUrl = 'https://veles.example'): BackendInfo {
  return { kind: 'veles', baseUrl, capabilities: CAPS };
}

/** A fetch stub that answers `/v1/designer/extensions` with `entries` (or a 404). */
function advertising(entries: unknown, ok = true): typeof fetch {
  return vi.fn(async (url: string | URL) => {
    expect(String(url)).toContain('/v1/designer/extensions');
    return { ok, json: async () => entries } as Response;
  }) as unknown as typeof fetch;
}

describe('loadDesignerExtensions (§10 surface, VS-5)', () => {
  it('fetches /v1/designer/extensions and dynamic-imports + activates each moduleUrl', async () => {
    const activate = vi.fn();
    const mod: DesignerExtension = { id: 'cz.tatrman.runs', version: '1.0.0', contributes: {}, activate };
    const importModule = vi.fn(async () => mod);
    const fetchImpl = advertising([{ id: 'cz.tatrman.runs', version: '1.0.0', moduleUrl: 'https://ext/runs.js' }]);

    const res = await loadDesignerExtensions(ctxFor(velesBackend()), { fetchImpl, importModule });

    expect(importModule).toHaveBeenCalledWith('https://ext/runs.js');
    expect(activate).toHaveBeenCalledOnce();
    expect(res.loaded.map((l) => l.id)).toEqual(['cz.tatrman.runs']);
    expect(res.refused).toEqual([]);
  });

  it('activate receives { dataSource, backend, auth }', async () => {
    const activate = vi.fn();
    const importModule = vi.fn(async () => ({ activate }));
    const fetchImpl = advertising([{ id: 'x', version: '1', moduleUrl: 'u' }]);
    const ctx = ctxFor(velesBackend());

    await loadDesignerExtensions(ctx, { fetchImpl, importModule });

    const passed = activate.mock.calls[0][0] as ExtensionContext;
    expect(passed.dataSource).toBe(ctx.dataSource);
    expect(passed.backend).toBe(ctx.backend);
    expect(await passed.auth.token()).toBe('tok');
  });

  it('worker/loopback backends load nothing (no advertise endpoint is even queried)', async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const worker: BackendInfo = { kind: 'worker', baseUrl: null, capabilities: CAPS };
    const loopback: BackendInfo = { kind: 'designer-server', baseUrl: null, capabilities: CAPS };

    for (const backend of [worker, loopback]) {
      const res = await loadDesignerExtensions(ctxFor(backend), { fetchImpl });
      expect(res.loaded).toEqual([]);
    }
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('the SV read-api Veles backend also loads zero (its server 404s the advertise endpoint) [VS-5]', async () => {
    const fetchImpl = advertising([], false); // 404
    const res = await loadDesignerExtensions(ctxFor(velesBackend()), { fetchImpl, importModule: vi.fn() });
    expect(res.loaded).toEqual([]);
    expect(res.refused).toEqual([]);
  });

  it('open builds refuse a license:platform extension (requires present, no license client)', async () => {
    const importModule = vi.fn();
    const fetchImpl = advertising([{ id: 'authoring', version: '1', moduleUrl: 'u', requires: ['authoring'] }]);
    const res = await loadDesignerExtensions(ctxFor(velesBackend()), { fetchImpl, importModule });
    expect(res.loaded).toEqual([]);
    expect(res.refused).toEqual([{ id: 'authoring', reason: 'no-license-client', locked: true }]);
    expect(importModule).not.toHaveBeenCalled(); // gated before import
  });

  it('isolates a failing extension — logged, the shell (and siblings) are unaffected', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const good: DesignerExtension = { id: 'good', version: '1', contributes: {}, activate: vi.fn() };
    const importModule = vi.fn(async (url: string) => {
      if (url === 'bad') throw new Error('boom');
      return good;
    });
    const fetchImpl = advertising([
      { id: 'bad', version: '1', moduleUrl: 'bad' },
      { id: 'good', version: '1', moduleUrl: 'good' },
    ]);

    const res = await loadDesignerExtensions(ctxFor(velesBackend()), { fetchImpl, importModule });

    expect(res.loaded.map((l) => l.id)).toEqual(['good']); // sibling still loaded
    expect(res.refused).toEqual([{ id: 'bad', reason: 'activate-failed', locked: false }]);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});
