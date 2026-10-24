// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { LspClient } from '../../lsp-client.js';
import { WorkerLspDataSource } from '../worker-lsp-data-source.js';
import { BffFilesDataSource } from '../bff-files-data-source.js';

const manifest = '[packages]\nlayout = "off"';
const files = {
  'modeler.toml': manifest,
  'model/er/item.ttrm': 'model er',
  'model/layouts/sales.ttrg': 'graph sales {}',
  'README.md': 'ignored',
};
function harness() {
  const clients: LspClient[] = [];
  const createClient = vi.fn(async () => {
    const c = {
      setProjectRoot: vi.fn(),
      openDocument: vi.fn(),
      dispose: vi.fn(),
      listGraphs: vi.fn(async () => ({ graphs: [] })),
      listSymbols: vi.fn(async () => []),
      listSchemaSubjects: vi.fn(async () => ({ subjects: [] })),
    } as unknown as LspClient;
    clients.push(c);
    return c;
  });
  const fetcher = vi.fn<typeof fetch>(
    async () => ({ ok: true, json: async () => ({ sha: 'one', files }) }) as Response
  );
  const source = new BffFilesDataSource(
    { api: '/api/model', scope: { kind: 'served' }, token: 'first' },
    { createClient, fetch: fetcher as typeof fetch }
  );
  return { source, clients, createClient, fetcher };
}

describe('BFF snapshots in a worker', () => {
  it('fetches with a bearer, applies the root manifest before opening model documents', async () => {
    const { source, clients, fetcher } = harness();
    expect(await source.refresh()).toBe(true);
    expect(fetcher).toHaveBeenCalledWith(
      '/api/model/served/files',
      expect.objectContaining({
        headers: { Authorization: 'Bearer first', Accept: 'application/json' },
        cache: 'no-store',
      })
    );
    expect(clients[0].setProjectRoot).toHaveBeenCalledWith('file:///repo', manifest);
    expect(clients[0].openDocument).toHaveBeenCalledExactlyOnceWith(
      'file:///repo/model/er/item.ttrm',
      'model er'
    );
    expect(source.project!.files.get('model/layouts/sales.ttrg')).toBe('graph sales {}');
    expect(clients[0].setProjectRoot).toHaveBeenCalledTimes(2);
    source.dispose();
  });

  it('refreshes the bearer even when the SHA is unchanged; changed snapshots replace the worker', async () => {
    const { source, clients, fetcher, createClient } = harness();
    await source.refresh();
    source.setToken('second');
    expect(await source.refresh()).toBe(false);
    expect(createClient).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[1][1]).toMatchObject({ headers: { Authorization: 'Bearer second' } });
    fetcher.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ sha: 'two', files: { 'modeler.toml': manifest } }),
    } as Response);
    expect(await source.refresh()).toBe(true);
    expect(clients[0].dispose).toHaveBeenCalledOnce();
    expect(clients[1].openDocument).not.toHaveBeenCalled();
    source.dispose();
  });

  it('encodes change IDs, rejects malformed snapshots and keeps the previous worker on load failure', async () => {
    const { source, clients, fetcher } = harness();
    await source.refresh();
    fetcher.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ sha: 'two', files: { '../secret': 'bad' } }),
    } as Response);
    await expect(source.refresh()).rejects.toThrow(/repo-rooted/);
    expect(source.sha).toBe('one');
    expect(clients[0].dispose).not.toHaveBeenCalled();
    source.dispose();
    const change = new BffFilesDataSource(
      { api: '/api/model/', scope: { kind: 'change', id: 'a/b' }, token: 't' },
      { fetch: fetcher as typeof fetch, createClient: async () => clients[0] }
    );
    await change.refresh();
    expect(fetcher.mock.calls.at(-1)![0]).toBe('/api/model/changes/a%2Fb/files');
    change.dispose();
  });

  it('shares concurrent refreshes and disposes a failed replacement worker', async () => {
    const { source, clients } = harness();
    const first = source.refresh();
    expect(source.refresh()).toBe(first);
    await first;
    source.dispose();
    expect(clients[0].dispose).toHaveBeenCalledExactlyOnceWith();
    await expect(source.refresh()).rejects.toThrow(/disposed/);
    const broken = {
      setProjectRoot: vi.fn(async () => {
        throw new Error('bad TOML');
      }),
      dispose: vi.fn(),
    } as unknown as LspClient;
    const b = new BffFilesDataSource(
      { api: '/api/model', token: 't', scope: { kind: 'served' } },
      {
        fetch: vi.fn<typeof fetch>(
          async () => ({ ok: true, json: async () => ({ sha: 'a', files }) }) as Response
        ),
        createClient: async () => broken,
      }
    );
    await expect(b.refresh()).rejects.toThrow('bad TOML');
    expect(b.sha).toBeNull();
    expect(broken.dispose).toHaveBeenCalledOnce();
    b.dispose();
  });

  it('retains the old snapshot on a catalog failure and defers retirement until the new shell commits', async () => {
    const { source, clients, fetcher, createClient } = harness();
    await source.refresh();
    fetcher.mockResolvedValue({
      ok: true,
      json: async () => ({ sha: 'two', files: { 'modeler.toml': manifest } }),
    } as Response);
    const broken = {
      setProjectRoot: vi.fn(),
      openDocument: vi.fn(),
      dispose: vi.fn(),
      listGraphs: vi.fn(async () => {
        throw new Error('catalog unavailable');
      }),
      listSymbols: vi.fn(async () => []),
      listSchemaSubjects: vi.fn(async () => ({ subjects: [] })),
    } as unknown as LspClient;
    createClient.mockResolvedValueOnce(broken);
    await expect(source.refresh()).rejects.toThrow('catalog unavailable');
    expect(source.sha).toBe('one');
    expect(clients[0].dispose).not.toHaveBeenCalled();
    expect(broken.dispose).toHaveBeenCalledOnce();
    await source.refresh({ deferDisposal: true });
    expect(source.sha).toBe('two');
    expect(clients[0].dispose).not.toHaveBeenCalled();
    source.disposeRetired();
    expect(clients[0].dispose).toHaveBeenCalledOnce();
    source.dispose();
  });

  it('rejects cross-origin API paths before a bearer can be sent', () => {
    for (const api of [
      'https://foreign.example',
      '//foreign.example/api',
      '/\\foreign.example/api',
      '/a/../api',
    ]) {
      expect(
        () => new BffFilesDataSource({ api, token: 'secret', scope: { kind: 'served' } })
      ).toThrow(/same-origin/);
    }
  });
});


afterEach(()=>vi.restoreAllMocks());
it.each(['snapshot','edit'])('R15 does not cache a catalog invalidated by a concurrent %s',async mode=>{
  const h=harness();
  const old={graphs:[],symbols:[{qname:'old',kind:'entity',name:'old',packageName:'shop'}]};
  const fresh={graphs:[],symbols:[{qname:'fresh',kind:'entity',name:'fresh',packageName:'shop'}]};
  let release!: (value: typeof old)=>void;
  const held=new Promise<typeof old>(resolve=>{release=resolve;});
  const listing=vi.spyOn(WorkerLspDataSource.prototype,'listCatalog').mockResolvedValueOnce(old).mockImplementationOnce(()=>held).mockResolvedValue(fresh);
  try {
    await h.source.refresh();
    h.clients[0].edit=vi.fn(async()=>({ok:true as const,edit:{changes:{'file:///repo/model/er/item.ttrm':[{range:{start:{line:0,character:0},end:{line:0,character:0}},newText:'// changed\n'}]}},touched:[],diagnostics:[]}));
    const change=()=>h.source.edit({op:'add-term',args:{lang:'en',term:'x',target:'er.entity.x'}});
    await change();
    const stale=h.source.listCatalog();
    expect(listing).toHaveBeenCalledTimes(2);
    if(mode==='snapshot') {
      h.fetcher.mockResolvedValueOnce({ok:true,json:async()=>({sha:'two',files})} as Response);
      await h.source.refresh({discard:true});
    } else await change();
    release(old);await stale;
    expect(await h.source.listCatalog()).toEqual(fresh);
  } finally {h.source.dispose();}
});
