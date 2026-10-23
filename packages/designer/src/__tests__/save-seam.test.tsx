// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, relative } from 'node:path';
import App from '../App.js';
import { BffFilesDataSource } from '../data/bff-files-data-source.js';
import { createTestClient } from '../data/__tests__/lsp-harness.js';
import { WorkerLspDataSource } from '../data/worker-lsp-data-source.js';
import type { LspClient } from '../lsp-client.js';
import { setRuntimeConfig } from '../runtime-config.js';

const { createClient } = vi.hoisted(() => ({ createClient: vi.fn() }));
vi.mock('../lsp-client.js', () => ({ createLspClient: createClient }));
vi.mock('../canvas/Kernel.js', async () => (await import('../shell/__tests__/layout-harness.js')).kernelMock);
const post = (data: unknown) => window.dispatchEvent(new MessageEvent('message', {
  data, origin: window.location.origin, source: window.parent,
}));
const init = { type: 'ttr.host.init', api: '/api/model', basePath: '/m/', workspace: 'hartland',
  token: 'secret', scope: { kind: 'change', id: 'review' }, lang: 'en', theme: 'light' };
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); setRuntimeConfig(null); });
it('collects whole changed files, coalesces edits, and accepts a new saved SHA', async () => {
  window.history.replaceState(null, '', '/m/_designer/index.html?backend=host#/w/hartland/s/er');
  const root = resolve('../edit/__tests__/synth/vectors/insert-entity/on-query/world');
  let files = Object.fromEntries(readdirSync(root, { recursive: true, withFileTypes: true })
    .filter(e => e.isFile()).map(e => {
      const path = resolve(e.parentPath, e.name);
      return [relative(root, path), readFileSync(path, 'utf8')];
    }));
  let sha = 'one';
  createClient.mockImplementation(() => createTestClient());
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ sha, files }) })));
  const refresh = vi.spyOn(BffFilesDataSource.prototype, 'refresh');
  const sent = vi.spyOn(window.parent, 'postMessage');
  render(<App />); post(init);
  await waitFor(() => expect(sent).toHaveBeenCalledWith({ type: 'ttr.frame.ready' }, window.location.origin));
  const source = refresh.mock.contexts[0] as BffFilesDataSource;
  const path = 'model/lexicon/en/returns.ttrm';
  expect((await source.edit({ op: 'add-term', args: { lang: 'en', term: 'dry term',
    target: 'er.entity.return_request' }, dryRun: true })).ok).toBe(true);
  expect((await source.edit({ op: 'add-term', args: { lang: 'en', term: 'bad term',
    target: 'er.entity.missing' } })).ok).toBe(false);
  expect(source.changedFiles()).toEqual([]);
  for (const term of ['returns inspection', 'returns triage']) {
    await act(async () => { expect((await source.edit({ op: 'add-term', args: { lang: 'en', term,
      target: 'er.entity.return_request', file: 'returns.ttrm' } })).ok).toBe(true); });
    await waitFor(() => expect(sent).toHaveBeenCalledWith({ type: 'ttr.frame.dirty', changed: 1 }, window.location.origin));
    post({ type: 'ttr.host.collect' });
    await waitFor(() => expect(sent).toHaveBeenCalledWith({ type: 'ttr.frame.changedFiles', files: source.changedFiles() }, window.location.origin));
    expect(source.changedFiles()).toHaveLength(1);
    expect(source.changedFiles()[0]).toMatchObject({ path, text: expect.stringContaining(term) });
  }
  files = { ...files, [path]: source.changedFiles()[0].text! }; sha = 'two';
  post({ ...init, sha });
  await waitFor(() => expect(source.sha).toBe('two'));
  await waitFor(() => expect(sent).toHaveBeenCalledWith({ type: 'ttr.frame.dirty', changed: 0 }, window.location.origin));
  expect(source.changedFiles()).toEqual([]);
  expect((await source.edit({ op: 'add-term', args: { lang: 'en', term: 'returns triage',
    target: 'er.entity.return_request', file: 'returns.ttrm' } })).ok).toBe(true);
  expect(source.changedFiles()).toEqual([]);
  fireEvent.click((await screen.findAllByTestId('mock-node')).find(n => n.dataset.id === 'hartland.er.entity.return_request')!);
  await screen.findByTestId('peek-escalate');
  fireEvent.click(screen.getByTestId('peek-escalate'));
  fireEvent.click(screen.getByTestId('drawer-edit'));
  const editor = screen.getByTestId('drawer-editor') as HTMLTextAreaElement;
  expect(editor.value).toContain('def entity return_request');
  expect(editor.value).not.toContain('model er');
  const original = editor.value;
  fireEvent.change(editor, { target: { value: 'def entity return_request {' } });
  fireEvent.click(screen.getByTestId('drawer-save'));
  await screen.findByTestId('drawer-save-error');
  expect(source.changedFiles()).toEqual([]);
  fireEvent.change(editor, { target: { value: original.replace('{', '{ description: "Reviewed returns",') } });
  fireEvent.click(screen.getByTestId('drawer-save'));
  await waitFor(() => expect(screen.queryByTestId('drawer-editor')).toBeNull());
  expect(source.changedFiles()).toHaveLength(1);
  expect(source.changedFiles()[0].text).toContain('Reviewed returns');
  expect(screen.getByTestId('peek-source')).toHaveTextContent('Reviewed returns');
});

it('collect sees a whole multi-file edit while transport acknowledgements are pending', async () => {
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  const openDocument = vi.fn(async () => { await held; });
  const edit = { changes: Object.fromEntries(['a', 'b'].map(name => [`file:///repo/${name}.ttrm`,
    [{ range: { start: { line: 0, character: 0 }, end: { line: 0, character: 3 } }, newText: 'new-' + name }]])) };
  const client = { edit: async () => ({ ok: true, edit, touched: [], diagnostics: [] }), openDocument } as unknown as LspClient;
  const source = new WorkerLspDataSource(client, { projectRoot: 'file:///repo', files: new Map([['a.ttrm', 'old'], ['b.ttrm', 'old']]) });
  const applying = source.edit({ op: 'add-term', args: { lang: 'en', term: 'x', target: 'er.entity.x' } });
  await waitFor(() => expect(openDocument).toHaveBeenCalledTimes(1));
  expect(source.changedFiles()).toEqual([{ path: 'a.ttrm', text: 'new-a' }, { path: 'b.ttrm', text: 'new-b' }]);
  release(); await applying;
  expect(openDocument).toHaveBeenCalledTimes(2);
});
