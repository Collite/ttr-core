// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from '../App.js';
import { currentAppPath, replaceAppPath } from '../shell/app-location.js';
import { setRuntimeConfig } from '../runtime-config.js';
import { BffFilesDataSource } from '../data/bff-files-data-source.js';
import { createTestClient } from '../data/__tests__/lsp-harness.js';
import { APP_NAVIGATE } from '../shell/app-location.js';
import { StrictMode } from 'react';

const { createClient, client } = vi.hoisted(() => {
  const c = {
    setProjectRoot: vi.fn(async () => ({})),
    openDocument: vi.fn(async () => {}),
    dispose: vi.fn(),
    listGraphs: vi.fn(async () => ({ graphs: [] })),
    listSymbols: vi.fn(async () => []),
    listSchemaSubjects: vi.fn(async () => ({
      subjects: [
        { schema: 'er', count: 1 },
        { schema: 'md', count: 1 },
      ],
    })),
    getModelGraph: vi.fn(async () => ({ nodes: [], edges: [] })),
    getBindings: vi.fn(async () => ({ entities: [], attributes: [], relations: [] })),
  };
  return { client: c, createClient: vi.fn(async () => c) };
});
vi.mock('../lsp-client.js', () => ({ createLspClient: createClient }));
vi.mock(
  '../canvas/Kernel.js',
  async () => (await import('../shell/__tests__/layout-harness.js')).kernelMock
);

const manifest = '[packages]\nlayout = "off"';
const init = {
  type: 'ttr.host.init',
  api: '/api/model',
  basePath: '/m/',
  workspace: 'hartland',
  token: 'secret',
  scope: { kind: 'served' },
  lang: 'cs',
  theme: 'light',
};
const post = (
  data: unknown,
  origin = window.location.origin,
  source: MessageEventSource | null = window.parent
) => window.dispatchEvent(new MessageEvent('message', { data, origin, source }));
let fetcher: ReturnType<typeof vi.fn>;
let sent: MockInstance<Window['postMessage']>;
beforeEach(() => {
  vi.clearAllMocks();
  createClient.mockResolvedValue(client);
  window.history.replaceState(null, '', '/m/_designer/index.html?backend=host#/w/hartland/s/er');
  fetcher = vi.fn(async () => ({
    ok: true,
    json: async () => ({
      sha: 'one',
      files: { 'modeler.toml': manifest, 'model/er/item.ttrm': 'model er' },
    }),
  }));
  vi.stubGlobal('fetch', fetcher);
  sent = vi.spyOn(window.parent, 'postMessage');
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  setRuntimeConfig(null);
  window.history.replaceState(null, '', '/');
  document.documentElement.removeAttribute('data-hosted');
});

describe('hosted mode through a parent window', () => {
  it('ignores untrusted messages, loads the snapshot before ready, and mirrors navigation both ways', async () => {
    render(
      <StrictMode>
        <App />
      </StrictMode>
    );
    expect(screen.queryByText('Load Project Folder')).not.toBeInTheDocument();
    post({ ...init, workspace: undefined });
    post(init, 'https://foreign.example');
    post(init, window.location.origin, null);
    post({ ...init, api: '//foreign.example/api' });
    post({ ...init, api: '/\\foreign.example/api' });
    expect(fetcher).not.toHaveBeenCalled();
    post(init);
    await waitFor(() =>
      expect(sent).toHaveBeenCalledWith({ type: 'ttr.frame.ready' }, window.location.origin)
    );
    expect(client.setProjectRoot).toHaveBeenCalledWith('file:///repo', manifest);
    expect(client.openDocument).toHaveBeenCalledWith('file:///repo/model/er/item.ttrm', 'model er');
    expect(screen.getByTestId('copy-federation-link')).toHaveAttribute(
      'data-federation-url',
      `${window.location.origin}/m/hartland/s/er`
    );
    expect(window.location.pathname).toBe('/m/_designer/index.html');
    expect(new URLSearchParams(window.location.search).get('lang')).toBe('cs');
    post({ type: 'ttr.host.navigate', location: '/w/hartland/s/md' });
    await waitFor(() => expect(currentAppPath()).toBe('/w/hartland/s/md'));
    fireEvent.click(screen.getAllByTestId('catalog-item').find((e) => e.textContent === 'er')!);
    await waitFor(() =>
      expect(sent).toHaveBeenCalledWith(
        { type: 'ttr.frame.location', location: '/w/hartland/s/er' },
        window.location.origin
      )
    );
    post({ type: 'ttr.host.token', token: 'renewed' });
    post({ type: 'ttr.host.refresh' });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(fetcher.mock.calls[1][1].headers.Authorization).toBe('Bearer renewed');
    expect(createClient).toHaveBeenCalledTimes(1);
    expect(sent).not.toHaveBeenCalledWith(
      { type: 'ttr.frame.location', location: '/w/hartland' },
      expect.anything()
    );
  });

  it('fetches a change scope and reports a load error without announcing ready', async () => {
    fetcher.mockResolvedValue({ ok: false, status: 403 });
    render(<App />);
    post({ ...init, scope: { kind: 'change', id: '42' } });
    await waitFor(() =>
      expect(sent).toHaveBeenCalledWith(
        expect.objectContaining({ type: 'ttr.frame.error', code: 'host/load-failed' }),
        window.location.origin
      )
    );
    expect(fetcher.mock.calls[0][0]).toBe('/api/model/changes/42/files');
    expect(sent).not.toHaveBeenCalledWith({ type: 'ttr.frame.ready' }, expect.anything());
  });

  it('reloads a changed SHA on navigate, keeps the requested deep link, and announces ready once', async () => {
    render(<App />);
    post(init);
    await waitFor(() =>
      expect(sent).toHaveBeenCalledWith({ type: 'ttr.frame.ready' }, window.location.origin)
    );
    const response = {
      ok: true,
      json: async () => ({ sha: 'two', files: { 'modeler.toml': manifest } }),
    };
    let finishRefresh!: (value: typeof response) => void;
    fetcher.mockImplementationOnce(() => new Promise((resolve) => { finishRefresh = resolve; }));
    const previousHeader = screen.getByTestId('studio-header');
    const navigate = vi.fn();
    window.addEventListener(APP_NAVIGATE, navigate);
    client.dispose.mockImplementationOnce(() => {
      expect(screen.getByTestId('studio-header')).not.toBe(previousHeader);
    });
    post({ type: 'ttr.host.navigate', location: '#/w/foreign/s/md', sha: 'two' });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    // The old shell can still sync its active tab while the replacement snapshot is loading.
    replaceAppPath('/w/hartland/s/er');
    finishRefresh(response);
    await waitFor(() => expect(createClient).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(currentAppPath()).toBe('/w/hartland/s/md'));
    await waitFor(() => expect(client.dispose).toHaveBeenCalledTimes(1));
    expect(navigate).not.toHaveBeenCalled();
    window.removeEventListener(APP_NAVIGATE, navigate);
    expect(sent.mock.calls.filter(([m]) => m.type === 'ttr.frame.ready')).toHaveLength(1);
    expect(window.location.pathname).toBe('/m/_designer/index.html');
  });
  it.each(['#/', '#/w/another/s/er', '#/w/%E0%A4%A/s/er'])(
    'uses the init workspace for initial hash %s',
    async (hash) => {
      window.history.replaceState(null, '', `/m/_designer/index.html?backend=host${hash}`);
      render(<App />);
      post(init);
      await waitFor(() =>
        expect(sent).toHaveBeenCalledWith({ type: 'ttr.frame.ready' }, window.location.origin)
      );
      const locations = sent.mock.calls
        .map(([m]) => m)
        .filter((m) => m.type === 'ttr.frame.location');
      expect(locations.length).toBeGreaterThan(0);
      expect(locations.every((m) => m.location.startsWith('/w/hartland'))).toBe(true);
      const errors = sent.mock.calls.map(([m]) => m).filter((m) => m.type === 'ttr.frame.error');
      expect(errors).toHaveLength(hash.includes('%') ? 1 : 0);
      if (errors.length) expect(errors[0].code).toBe('host/bad-location');
    }
  );

  it('keeps navigation on the old snapshot when refresh fails', async () => {
    const refresh = vi.spyOn(BffFilesDataSource.prototype, 'refresh');
    render(<App />);
    post(init);
    await waitFor(() =>
      expect(sent).toHaveBeenCalledWith({ type: 'ttr.frame.ready' }, window.location.origin)
    );
    const header = screen.getByTestId('studio-header');
    fetcher.mockResolvedValue({ ok: false, status: 500 });
    post({ type: 'ttr.host.navigate', location: '/w/hartland/s/md', sha: 'two' });
    await waitFor(() =>
      expect(
        sent.mock.calls.map(([m]) => m).filter((m) => m.type === 'ttr.frame.error')
      ).toHaveLength(1)
    );
    // The error is sent before React commits the fallback navigation on the retained shell.
    await waitFor(() => {
      expect(currentAppPath()).toBe('/w/hartland/s/md');
      expect(screen.getByTestId('copy-federation-link')).toHaveAttribute(
        'data-federation-url',
        `${window.location.origin}/m/hartland/s/md`
      );
    });
    expect(screen.getByTestId('studio-header')).toBe(header);
    expect((refresh.mock.contexts[0] as BffFilesDataSource).sha).toBe('one');
    expect(client.dispose).not.toHaveBeenCalled();
  });

  it('copies the parent path for a lineage tab too', async () => {
    render(<App />);
    post(init);
    await waitFor(() =>
      expect(sent).toHaveBeenCalledWith({ type: 'ttr.frame.ready' }, window.location.origin)
    );
    post({
      type: 'ttr.host.navigate',
      location:
        '/w/hartland/p/lineage?root=shop.md.Items&scope=neighborhood&dir=upstream&kind=measure',
    });
    await waitFor(() =>
      expect(
        screen.getByTestId('copy-federation-link').getAttribute('data-federation-url')
      ).toContain('/m/hartland/p/lineage?root=shop.md.Items')
    );
  });

  it('posts ready for an actual worker with a malformed manifest and an unreadable layout', async () => {
    const messages: string[] = [];
    createClient.mockImplementationOnce(async () => {
      const real = await createTestClient();
      real.onDiagnostics((_uri, diagnostics) => messages.push(...diagnostics));
      return real as unknown as typeof client;
    });
    fetcher.mockResolvedValue({
      ok: true,
      json: async () => ({
        sha: 'bad',
        files: {
          'modeler.toml': '[bindings',
          'model/er/items.ttrm': 'package shop\nmodel er\ndef entity item {}',
          'model/layouts/broken.ttrg': 'not a layout',
        },
      }),
    });
    render(<App />);
    post(init);
    await waitFor(() =>
      expect(sent).toHaveBeenCalledWith({ type: 'ttr.frame.ready' }, window.location.origin)
    );
    expect(messages.some((m) => m.includes('modeler.toml could not be parsed'))).toBe(true);
    expect(screen.getAllByTestId('catalog-item').map((e) => e.textContent)).toContain('er');
  });
});
