// SPDX-License-Identifier: Apache-2.0
import { createRef, StrictMode } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { DesignerPage, type CollectFiles } from '../page/DesignerPage.js';
import { FakeWorker } from '../page/fake-worker.test-support.js';
import type { BffFilesDataSource } from '../data/bff-files-data-source.js';
import type { ModelDataSource } from '../data/model-data-source.js';

beforeEach(() => { FakeWorker.urls = []; vi.stubGlobal('Worker', FakeWorker); });
vi.mock('../canvas/Kernel.js', async () => (await import('../shell/__tests__/layout-harness.js')).kernelMock);
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const files = { sha: 'one', files: {
  'modeler.toml': '[packages]\nlayout="off"',
  'model/er.ttrm': 'package demo\nmodel er\ndef entity customer { attributes: [def attribute id { type: int }] }',
  'model/db.ttrm': 'package demo\nmodel db\ndef table customer { columns: [def column id { type: int }] }',
  'model/md.ttrm': 'package demo\nmodel md\ndef dimension customer { }',
} };

it('mounts the real LSP catalog, navigates without host history, edits and collects through a scoped extension', async () => {
  const collect = createRef<CollectFiles>();
  const onLocation = vi.fn(), onDirty = vi.fn();
  const before = location.href;
  let source!: ModelDataSource;
  const activate = vi.fn(async (ctx: { dataSource: ModelDataSource }) => {
    source = ctx.dataSource;
    return null;
  });
  render(<StrictMode><DesignerPage files={files} basePath="/m/" lang="en" theme="dark"
    onLocation={onLocation} onDirty={onDirty} collect={collect}
    extensions={[{ manifest: { name: 'probe', version: '1', license: 'none', requires: [] }, activate }]} /></StrictMode>);
  await waitFor(() => expect(screen.getAllByTestId('catalog-item').map(e => e.textContent)).toEqual(expect.arrayContaining(['er', 'db', 'md'])));
  expect(screen.getByTestId('designer-page').style.colorScheme).toBe('dark');
  fireEvent.click(screen.getAllByTestId('catalog-item').find(e => e.textContent === 'er')!);
  await waitFor(() => expect(onLocation).toHaveBeenCalledWith('/w/repo/s/er'));
  await waitFor(() => expect(source).toBeDefined());
  let result!: Awaited<ReturnType<NonNullable<ModelDataSource['edit']>>>;
  await act(async () => { result = await source.edit!({ op: 'set-source', args: { nodeRef: 'demo.er.entity.customer', text: 'def entity customer { attributes: [def attribute id { type: int }, def attribute name { type: string }] }' } }); });
  expect(result.ok, JSON.stringify(result)).toBe(true);
  await waitFor(() => expect(onDirty).toHaveBeenLastCalledWith(1));
  expect(collect.current!()).toEqual([{ path: 'model/er.ttrm', text: expect.stringContaining('def attribute name') }]);
  expect(FakeWorker.urls.length).toBeGreaterThan(0);
  expect(FakeWorker.urls.every(url => url.includes('browser.worker.js'))).toBe(true);
  expect(location.href).toBe(before);
  expect(document.documentElement.dataset.hosted).toBeUndefined();
});

it('keeps two page instances and their callbacks isolated', async () => {
  const a = vi.fn(), b = vi.fn();
  const host = render(<><DesignerPage files={files} basePath="/a/" lang="en" workspace="a" onLocation={a} />
    <DesignerPage files={files} basePath="/b/" lang="cs" workspace="b" onLocation={b} /></>);
  await waitFor(() => expect(screen.getAllByTestId('studio-header')).toHaveLength(2));
  const roots = screen.getAllByTestId('designer-page');
  fireEvent.click([...roots[0].querySelectorAll('[data-testid="catalog-item"]')].find(e => e.textContent === 'md')!);
  await waitFor(() => expect(a).toHaveBeenCalledWith('/w/a/s/md'));
  expect(b).not.toHaveBeenCalledWith('/w/a/s/md');
  host.unmount();
});

it('accepts a saved SHA and keeps late edits dirty, while refusing a conflicting snapshot', async () => {
  const collect = createRef<CollectFiles>(), onDirty = vi.fn(), onError = vi.fn();
  let source!: ModelDataSource;
  const extensions = [{ manifest: { name: 'probe', version: '1', license: 'none' as const, requires: [] },
    activate: async (context: { dataSource: ModelDataSource }) => { source = context.dataSource; return null; } }];
  const mount = (snapshot: typeof files) => <DesignerPage files={snapshot} basePath="/m/" lang="en" collect={collect}
    extensions={extensions} onDirty={onDirty} onError={onError} />;
  const view = render(mount(files));
  await waitFor(() => expect(source).toBeDefined());
  const edit = async (text: string) => act(async () => {
    expect((await source.edit!({ op: 'set-source', args: { nodeRef: 'demo.er.entity.customer',
      text: `def entity customer { description: "${text}" }` } })).ok).toBe(true);
  });
  await edit('saved');
  const saved = collect.current!();
  await edit('late');
  const snapshot = { sha: 'two', files: { ...files.files, [saved[0].path]: saved[0].text! } };
  view.rerender(mount(snapshot));
  await waitFor(() => expect((source as BffFilesDataSource).sha).toBe('two'));
  await waitFor(() => expect(onDirty).toHaveBeenLastCalledWith(1));
  await waitFor(() => expect(collect.current!()[0].text).toContain('late'));
  // A second collection pins the expected base for conflict detection.
  await edit('pending');
  view.rerender(mount({ sha: 'three', files: { ...snapshot.files, [saved[0].path]: saved[0].text! + '\n// concurrent edit' } }));
  await waitFor(() => expect(onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'frame/edit-conflict' })));
  expect(collect.current!()[0].text).toContain('pending');
});

it('supports a loader and controlled navigation, refreshes callbacks, and refuses unlicensed extensions', async () => {
  const denied = vi.fn(), onLocation = vi.fn();
  const extensions = [{ manifest: { name: 'paid', version: '1', license: 'platform' as const, requires: ['authoring'] }, activate: denied }];
  const loader = vi.fn(async () => files);
  const props = { files: loader, basePath: '/m/', lang: 'cs' as const, workspace: 'demo', extensions };
  const view = render(<DesignerPage {...props} location="/w/demo/s/md" />);
  await waitFor(() => expect(screen.getByRole('tab', { selected: true })).toHaveAttribute('title', 'md'));
  view.rerender(<DesignerPage {...props} location="/w/demo/s/db" onLocation={onLocation} theme="dark" />);
  await waitFor(() => expect(screen.getByRole('tab', { selected: true })).toHaveAttribute('title', 'db'));
  fireEvent.click(screen.getAllByTestId('catalog-item').find(e => e.textContent === 'er')!);
  await waitFor(() => expect(onLocation).toHaveBeenCalledWith('/w/demo/s/er'));
  expect(denied).not.toHaveBeenCalled();
  expect(screen.getByTestId('designer-page')).toHaveAttribute('lang', 'cs');
  expect(screen.getByTestId('designer-page').style.colorScheme).toBe('dark');
  expect(loader).toHaveBeenCalledTimes(1);
});

it('reports a failed loader without fetching config or changing host state', async () => {
  const onError = vi.fn(), fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  render(<DesignerPage files={async () => { throw new Error('private response'); }} basePath="/m/" lang="en" onError={onError} />);
  await screen.findByText('Could not load the model.');
  expect(onError).toHaveBeenCalledWith({ code: 'host/load-failed', message: 'Could not load the model snapshot.' });
  expect(fetcher).not.toHaveBeenCalled();
});
