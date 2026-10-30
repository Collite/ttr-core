// SPDX-License-Identifier: Apache-2.0
import { createRef, StrictMode, useState } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { DesignerPage, type CollectFiles } from '../page/DesignerPage.js';
import { FakeWorker } from '../page/fake-worker.test-support.js';
import { BffFilesDataSource } from '../data/bff-files-data-source.js';
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
    <DesignerPage files={files} basePath="/b/" lang="cs" workspace="b" location="/w/b/s/db" onLocation={b} /></>);
  await waitFor(() => expect(screen.getAllByTestId('studio-header')).toHaveLength(2));
  const roots = screen.getAllByTestId('designer-page');
  await waitFor(() => expect(roots[1].querySelector('[role=tab][aria-selected=true]')).toHaveAttribute('title', 'db'));
  fireEvent.click([...roots[0].querySelectorAll('[data-testid="catalog-item"]')].find(e => e.textContent === 'md')!);
  await waitFor(() => expect(a).toHaveBeenCalledWith('/w/a/s/md'));
  expect(b).not.toHaveBeenCalled();
  expect(roots[1].querySelector('[role=tab][aria-selected=true]')).toHaveAttribute('title', 'db');
  host.unmount();
});

it('accepts a saved SHA and keeps late edits dirty, while refusing a conflicting snapshot', async () => {
  const collect = createRef<CollectFiles>(), onDirty = vi.fn(), onError = vi.fn();
  let source!: ModelDataSource;
  const extensions = [{ manifest: { name: 'probe', version: '1', license: 'none' as const, requires: [] },
    activate: async (context: { dataSource: ModelDataSource }) => { source = context.dataSource; return null; } }];
  const mount = (snapshot: typeof files, expectedSha?: string) => <DesignerPage files={snapshot} expectedSha={expectedSha} basePath="/m/" lang="en" collect={collect}
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
  view.rerender(mount(snapshot, 'two'));
  await waitFor(() => expect((source as BffFilesDataSource).sha).toBe('two'));
  await waitFor(() => expect(onDirty).toHaveBeenLastCalledWith(1));
  await waitFor(() => expect(collect.current!()[0].text).toContain('late'));
  // A second collection pins the expected base for conflict detection.
  await edit('pending');
  view.rerender(mount({ sha: 'three', files: { ...snapshot.files, [saved[0].path]: saved[0].text! + '\n// concurrent edit' } }, 'three'));
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


it('ignores controlled location echoes and preserves a subject notation across tabs', async () => {
  function Controlled() {
    const [location, setLocation] = useState('/w/demo/s/md');
    return <DesignerPage files={files} basePath="/m/" lang="en" workspace="demo" location={location} onLocation={setLocation} />;
  }
  render(<Controlled />);
  const picker = await screen.findByTestId('skin-picker');
  fireEvent.change(picker, { target: { value: 'md.er-dialect' } });
  expect(picker).toHaveValue('md.er-dialect');
  fireEvent.click(screen.getAllByTestId('catalog-item').find(e => e.textContent === 'er')!);
  await waitFor(() => expect(screen.getByRole('tab', { selected: true })).toHaveAttribute('title', 'er'));
  fireEvent.click(screen.getByRole('tab', { name: /md/ }));
  await waitFor(() => expect(screen.getByTestId('skin-picker')).toHaveValue('md.er-dialect'));
});

it('reports malformed initial/later locations once and keeps the host mounted and navigable', async () => {
  const onError = vi.fn();
  const mount = (location: string) => <StrictMode><div data-testid="host-sibling" /><DesignerPage files={files} basePath="/m/" lang="en" workspace="x" location={location} onError={onError} /></StrictMode>;
  const view = render(mount('/s/md'));
  await screen.findByTestId('studio-header');
  expect(onError).toHaveBeenCalledTimes(1);
  expect(onError).toHaveBeenLastCalledWith({ code: 'host/bad-location', message: 'Invalid app location.' });
  view.rerender(mount('/w/x/s/%E0'));
  await waitFor(() => expect(onError).toHaveBeenCalledTimes(2));
  expect(screen.getByTestId('host-sibling')).toBeInTheDocument();
  view.rerender(mount('/w/x/s/md'));
  await waitFor(() => expect(screen.getByRole('tab', { selected: true })).toHaveAttribute('title', 'md'));
  expect(onError).toHaveBeenCalledTimes(2);
});

it('retries a post-refresh catalog failure even when the snapshot SHA is unchanged', async () => {
  const original = BffFilesDataSource.prototype.listCatalog;
  const catalog = vi.spyOn(BffFilesDataSource.prototype, 'listCatalog').mockRejectedValueOnce(new Error('catalog failed'))
    .mockImplementation(function(this: BffFilesDataSource) { return original.call(this); });
  try {
    const onError = vi.fn();
    const view = render(<DesignerPage files={files} basePath="/m/" lang="en" onError={onError} />);
    await screen.findByText('Could not load the model.');
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'host/load-failed' }));
    view.rerender(<DesignerPage files={{ ...files }} basePath="/m/" lang="en" onError={onError} />);
    await screen.findByTestId('studio-header');
    expect(catalog).toHaveBeenCalledTimes(2);
  } finally { catalog.mockRestore(); }
});

it('scopes command shortcuts to one page and leaves a host input alone', async () => {
  render(<><input data-testid="host-input" /><DesignerPage files={files} basePath="/a/" lang="en" workspace="a" />
    <DesignerPage files={files} basePath="/b/" lang="en" workspace="b" /></>);
  await waitFor(() => expect(screen.getAllByTestId('studio-header')).toHaveLength(2));
  const input = screen.getByTestId('host-input'); input.focus();
  expect(fireEvent.keyDown(input, { key: 'k', ctrlKey: true })).toBe(true);
  expect(screen.queryAllByTestId('cmdk-input')).toHaveLength(0);
  fireEvent.keyDown(screen.getAllByTestId('designer-page')[0], { key: 'k', metaKey: true });
  expect(screen.getAllByTestId('cmdk-input')).toHaveLength(1);
  expect(screen.getAllByTestId('designer-page')[0]).toContainElement(screen.getByTestId('cmdk-input'));
});

it('N11 source authoring is opt-in on the embedded page', async () => {
  const view = render(<DesignerPage files={files} basePath="/m/" lang="en" />);
  await screen.findByTestId('studio-header');
  expect(screen.queryByRole('button', { name: 'Source files' })).toBeNull();
  view.rerender(<DesignerPage files={files} basePath="/m/" lang="en" sourceAuthoring />);
  expect(await screen.findByRole('button', { name: 'Source files' })).toBeInTheDocument();
});

it('focuses each page after an empty canvas click without stealing control focus', async () => {
  render(<><input data-testid="host-input" /><DesignerPage files={files} basePath="/a/" lang="en" workspace="a" location="/w/a/s/er" />
    <DesignerPage files={files} basePath="/b/" lang="en" workspace="b" location="/w/b/s/er" /></>);
  await waitFor(() => expect(screen.getAllByTestId('mock-kernel')).toHaveLength(2));
  const input = screen.getByTestId('host-input');
  for (const root of screen.getAllByTestId('designer-page')) {
    input.focus();
    fireEvent.click(root.querySelector('[data-testid="mock-kernel"]')!);
    expect(root).toHaveFocus();
    expect(root).toHaveAttribute('tabindex', '-1');
    fireEvent.keyDown(document.activeElement!, { key: 'k', ctrlKey: true });
    expect(screen.getAllByTestId('cmdk-input')).toHaveLength(1);
    expect(root).toContainElement(screen.getByTestId('cmdk-input'));
    fireEvent.keyDown(screen.getByTestId('cmdk-input'), { key: 'Escape' });
    expect(screen.queryAllByTestId('cmdk-input')).toHaveLength(0);
    const button = root.querySelector<HTMLButtonElement>('[data-testid="mock-node"]')!;
    button.focus();
    fireEvent.click(button);
    expect(button).toHaveFocus();
    const picker = root.querySelector<HTMLSelectElement>('[data-testid="skin-picker"]')!;
    picker.focus();
    fireEvent.click(picker);
    expect(picker).toHaveFocus();
  }
  input.focus();
  fireEvent.click(input);
  expect(fireEvent.keyDown(input, { key: 'k', ctrlKey: true })).toBe(true);
  expect(input).toHaveFocus();
  expect(screen.queryAllByTestId('cmdk-input')).toHaveLength(0);
});


it.each(['en', 'cs'] as const)('uses the selected %s language for hosted schema and layout labels without changing source bytes', async lang => {
  const snapshot = { sha: 'bilingual', files: {
    'modeler.toml': '[packages]\nlayout="off"\n[language]\npreferred="en"',
    'model/er.ttrm': 'package demo\nmodel er\ndef entity calendar { displayLabel: { en: "Calendar", cs: "Kalendář" } }',
    'model/view.ttrg': 'graph view { model: er, objects: [demo.er.entity.calendar] }',
  } };
  let source!: ModelDataSource;
  const extensions = [{ manifest: { name: 'probe', version: '1', license: 'none' as const, requires: [] },
    activate: async (context: { dataSource: ModelDataSource }) => { source = context.dataSource; return null; } }];
  const collect = createRef<CollectFiles>();
  render(<DesignerPage files={snapshot} basePath="/m/" lang={lang} workspace="demo" location="/w/demo/s/er" collect={collect} extensions={extensions} />);
  await waitFor(() => expect(source).toBeDefined());
  const label = lang === 'cs' ? 'Kalendář' : 'Calendar';
  await waitFor(() => expect(screen.getByTestId('mock-kernel')).toHaveTextContent(label));
  expect((await source.getGraph('file:///repo/model/view.ttrg'))?.nodes[0].label).toBe(label);
  expect(collect.current!()).toEqual([]);
  expect((source as BffFilesDataSource).project?.files.get('modeler.toml')).toBe(snapshot.files['modeler.toml']);
});

it.each([
  ['/m/', 'en', '/w/demo/s/er', '/m/demo/s/er?lang=en'],
  ['/m/', 'cs', '/w/demo/s/er?skin=er.chen', '/m/demo/s/er?skin=er.chen&lang=cs'],
  ['/m/c/model/maya/3/', 'cs', '/w/demo/s/er', '/m/c/model/maya/3/demo/s/er?lang=cs'],
  ['/m/c/model/maya/3/', 'cs', '/w/demo/p/lineage?root=demo.er.entity.customer', '/m/c/model/maya/3/demo/p/lineage?root=demo.er.entity.customer&scope=neighborhood&dir=upstream&kind=measure&label=customer&lang=cs'],
] as const)('Copy retains scope, view query and language (%s %s %s)', async (basePath, lang, path, expected) => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
  render(<DesignerPage files={files} basePath={basePath} lang={lang} workspace="demo" location={path} />);
  const copy = await screen.findByTestId('copy-federation-link');
  await waitFor(() => expect(copy).toHaveAttribute('data-federation-url', window.location.origin + expected));
  fireEvent.click(copy);
  await waitFor(() => expect(writeText).toHaveBeenCalledWith(window.location.origin + expected));
});


it('switches an installed page language while preserving its authored files and selected view', async () => {
  let source!: ModelDataSource;
  const collect = createRef<CollectFiles>();
  const snapshot = { sha: 'bilingual', files: { ...files.files,
    'model/er.ttrm': 'package demo\nmodel er\ndef entity customer { displayLabel: { en: "Customer", cs: "Zákazník" } }',
  } };
  const extensions = [{ manifest: { name: 'probe', version: '1', license: 'none' as const, requires: [] },
    activate: async (context: { dataSource: ModelDataSource }) => { source = context.dataSource; return null; } }];
  const props = { files: snapshot, basePath: '/m/', workspace: 'demo', location: '/w/demo/s/er', extensions, collect };
  const view = render(<DesignerPage {...props} lang="en" />);
  await waitFor(() => expect(screen.getByTestId('mock-kernel')).toHaveTextContent('Customer'));
  await act(async () => expect((await source.edit!({ op: 'set-source', args: { nodeRef: 'demo.er.entity.customer',
    text: 'def entity customer { displayLabel: { en: "Customer", cs: "Zákazník" }, description: "unsaved" }',
  } })).ok).toBe(true));
  const edits = collect.current!();
  view.rerender(<DesignerPage {...props} lang="cs" />);
  await waitFor(() => expect(screen.getByTestId('mock-kernel')).toHaveTextContent('Zákazník'));
  expect(collect.current!()).toEqual(edits);
  expect((source as BffFilesDataSource).sha).toBe('bilingual');
});
