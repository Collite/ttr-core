// SPDX-License-Identifier: Apache-2.0
import { createRef } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { DesignerPage, type CollectFiles } from '../page/DesignerPage.js';
import { BffFilesDataSource } from '../data/bff-files-data-source.js';
import { FakeWorker } from '../page/fake-worker.test-support.js';
import type { WorkerDiagnostic } from '@tatrman/lsp';
import type { ModelDataSource } from '../data/model-data-source.js';

vi.mock('../canvas/Kernel.js', async () => (await import('../shell/__tests__/layout-harness.js')).kernelMock);
beforeEach(() => vi.stubGlobal('Worker', FakeWorker));
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
const files = { sha: 'one', files: {
  'modeler.toml': '[bindings]\nrequired=false\n[packages]\nlayout="off"',
  'model/er.ttrm': 'package demo\nmodel er\ndef entity customer { description: "original" }',
} };
const diagnostic: WorkerDiagnostic = { source: 'worker', range: { start: { line: 0, character: 0 }, end: { line: 0, character: 1 } }, uri: 'file:///repo/model/other.ttrm', severity: 1, code: 'example', message: 'Other file error' };
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(yes => { resolve = yes; }); return { promise, resolve }; }
function probe() {
  let source!: ModelDataSource;
  const extensions = [{ manifest: { name: 'probe', version: '1', license: 'none' as const, requires: [] }, activate: async (ctx: { dataSource: ModelDataSource }) => { source = ctx.dataSource; return null; } }];
  return { extensions, source: () => source };
}
async function edit(source: ModelDataSource, description: string) {
  await act(async () => expect((await source.edit!({ op: 'set-source', args: {
    nodeRef: 'demo.er.entity.customer', text: `def entity customer { description: "${description}" }`,
  } })).ok).toBe(true));
}
it('reports ready after the shell commits, acknowledges every successful input, and uses latest callbacks', async () => {
  const ready = vi.fn(() => expect(screen.getByTestId('studio-header')).toBeInTheDocument());
  const snapshot = vi.fn(), next = vi.fn();
  const view = render(<DesignerPage files={files} basePath="/m/" lang="en" onReady={ready} onSnapshot={snapshot} />);
  await waitFor(() => expect(ready).toHaveBeenCalledWith({ sha: 'one', manifest: { bindings: { required: false } } }));
  expect(snapshot).toHaveBeenLastCalledWith({ sha: 'one', changed: 0 });
  view.rerender(<DesignerPage files={{ ...files }} basePath="/m/" lang="en" onReady={ready} onSnapshot={next} />);
  await waitFor(() => expect(next).toHaveBeenCalledWith({ sha: 'one', changed: 0 }));
  expect(ready).toHaveBeenCalledTimes(1);
  expect(snapshot).toHaveBeenCalledTimes(1);
});
it('acknowledges a saved SHA with later edits still dirty', async () => {
  const p = probe(), collect = createRef<CollectFiles>(), snapshot = vi.fn();
  const mount = (value: typeof files) => <DesignerPage files={value} basePath="/m/" lang="en" extensions={p.extensions} collect={collect} onSnapshot={snapshot} />;
  const view = render(mount(files));
  await waitFor(() => expect(p.source()).toBeDefined());
  await edit(p.source(), 'saved');
  const saved = collect.current!();
  await edit(p.source(), 'later');
  view.rerender(mount({ sha: 'two', files: { ...files.files, [saved[0].path]: saved[0].text! } }));
  await waitFor(() => expect(snapshot).toHaveBeenLastCalledWith({ sha: 'two', changed: 1 }));
  expect(collect.current!()[0].text).toContain('later');
});
it('correlates a refused replacement and failed loader with their requested SHA, retaining the installed page', async () => {
  const p = probe(), collect = createRef<CollectFiles>(), error = vi.fn(), snapshot = vi.fn();
  const props = { basePath: '/m/', lang: 'en' as const, extensions: p.extensions, collect, onError: error, onSnapshot: snapshot };
  const view = render(<DesignerPage {...props} files={files} />);
  await waitFor(() => expect(p.source()).toBeDefined());
  await edit(p.source(), 'local');
  view.rerender(<DesignerPage {...props} files={{ sha: 'two', files: files.files }} />);
  await waitFor(() => expect(error).toHaveBeenCalledWith(expect.objectContaining({ code: 'frame/unsaved-edits', sha: 'two' })));
  expect(snapshot).toHaveBeenCalledTimes(1);
  expect(collect.current!()[0].text).toContain('local');
  view.rerender(<DesignerPage {...props} expectedSha="three" files={async () => { throw new Error('transport'); }} />);
  await waitFor(() => expect(error).toHaveBeenCalledWith(expect.objectContaining({ code: 'host/load-failed', sha: 'three' })));
  expect(screen.getByTestId('studio-header')).toBeInTheDocument();
  expect(snapshot).toHaveBeenCalledTimes(1);
});
it('fences a loader to the expected SHA and captures each queued request independently', async () => {
  const pending = deferred<typeof files>(), error = vi.fn(), snapshot = vi.fn();
  const view = render(<DesignerPage basePath="/m/" lang="en" expectedSha="requested-one" files={() => pending.promise} onError={error} onSnapshot={snapshot} />);
  await act(async () => {});
  view.rerender(<DesignerPage basePath="/m/" lang="en" expectedSha="requested-two" files={async () => ({ ...files, sha: 'requested-two' })} onError={error} onSnapshot={snapshot} />);
  await act(async () => pending.resolve(files));
  await waitFor(() => expect(error).toHaveBeenCalledWith(expect.objectContaining({ code: 'frame/edit-conflict', sha: 'requested-one' })));
  await waitFor(() => expect(snapshot).toHaveBeenLastCalledWith({ sha: 'requested-two', changed: 0 }));
});
it('publishes complete diagnostics initially and after an edit, including an empty replacement set', async () => {
  vi.spyOn(BffFilesDataSource.prototype, 'validatedDiagnostics').mockResolvedValueOnce([diagnostic]).mockResolvedValue([]);
  const p = probe(), diagnostics = vi.fn();
  render(<DesignerPage files={files} basePath="/m/" lang="en" extensions={p.extensions} onDiagnostics={diagnostics} />);
  await waitFor(() => expect(diagnostics).toHaveBeenCalledWith([{ uri: diagnostic.uri, code: diagnostic.code, message: diagnostic.message, severity: 'error', source: 'worker' }]));
  await waitFor(() => expect(p.source()).toBeDefined());
  await edit(p.source(), 'edited');
  await waitFor(() => expect(diagnostics).toHaveBeenLastCalledWith([]));
});
it('keeps validation failures blocking and recovers on a later successful revision', async () => {
  vi.spyOn(BffFilesDataSource.prototype, 'validatedDiagnostics').mockRejectedValueOnce(new Error('worker')).mockResolvedValue([]);
  const p = probe(), diagnostics = vi.fn();
  render(<DesignerPage files={files} basePath="/m/" lang="en" extensions={p.extensions} onDiagnostics={diagnostics} />);
  await waitFor(() => expect(diagnostics).toHaveBeenCalledWith([expect.objectContaining({ severity: 'error', code: 'worker/validation-unavailable' })]));
  await waitFor(() => expect(p.source()).toBeDefined());
  await edit(p.source(), 'recovered');
  await waitFor(() => expect(diagnostics).toHaveBeenLastCalledWith([]));
});
it('a retired validation cannot overwrite diagnostics from a newer snapshot, and unmount suppresses callbacks', async () => {
  const old = deferred<typeof diagnostic[]>(), afterUnmount = deferred<typeof diagnostic[]>();
  const validate = vi.spyOn(BffFilesDataSource.prototype, 'validatedDiagnostics').mockReturnValueOnce(old.promise).mockResolvedValue([]);
  const diagnostics = vi.fn(), snapshot = vi.fn();
  const props = { basePath: '/m/', lang: 'en' as const, onDiagnostics: diagnostics, onSnapshot: snapshot };
  const view = render(<DesignerPage {...props} files={files} />);
  await waitFor(() => expect(validate).toHaveBeenCalledTimes(1));
  view.rerender(<DesignerPage {...props} files={{ ...files, sha: 'two' }} />);
  await waitFor(() => expect(diagnostics).toHaveBeenLastCalledWith([]));
  await act(async () => old.resolve([diagnostic]));
  expect(diagnostics).not.toHaveBeenCalledWith(expect.arrayContaining([expect.objectContaining({ code: 'example' })]));
  validate.mockReturnValue(afterUnmount.promise);
  view.rerender(<DesignerPage {...props} files={{ ...files, sha: 'three' }} />);
  await waitFor(() => expect(snapshot).toHaveBeenLastCalledWith({ sha: 'three', changed: 0 }));
  const calls = diagnostics.mock.calls.length;
  view.unmount();
  await act(async () => afterUnmount.resolve([diagnostic]));
  expect(diagnostics).toHaveBeenCalledTimes(calls);
});
it('keeps model reads available after optional extension failure, and revokes source editing', async () => {
  const error = vi.fn();
  const extensions = [{ manifest: { name: 'optional', version: '1', license: 'none' as const, requires: [] }, activate: vi.fn(async () => { throw new Error('module failed'); }) }];
  const view = render(<DesignerPage files={files} basePath="/m/" lang="en" extensions={extensions} sourceAuthoring onError={error} />);
  await waitFor(() => expect(error).toHaveBeenCalledWith(expect.objectContaining({ code: 'frame/extension-unavailable' })));
  expect(screen.getByTestId('studio-header')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Source files' })).toBeInTheDocument();
  view.rerender(<DesignerPage files={files} basePath="/m/" lang="en" sourceAuthoring={false} onError={error} />);
  expect(screen.queryByRole('button', { name: 'Source files' })).toBeNull();
  expect(screen.getByTestId('studio-header')).toBeInTheDocument();
});
it('keeps installed versions and diagnostics isolated between embedded instances', async () => {
  const a = vi.fn(), b = vi.fn(), da = vi.fn(), db = vi.fn();
  render(<>
    <DesignerPage files={files} workspace="a" basePath="/a/" lang="en" onSnapshot={a} onDiagnostics={da} />
    <DesignerPage files={{ ...files, sha: 'other' }} workspace="b" basePath="/b/" lang="cs" onSnapshot={b} onDiagnostics={db} />
  </>);
  await waitFor(() => expect(a).toHaveBeenCalledWith({ sha: 'one', changed: 0 }));
  await waitFor(() => expect(b).toHaveBeenCalledWith({ sha: 'other', changed: 0 }));
  expect(a).not.toHaveBeenCalledWith(expect.objectContaining({ sha: 'other' }));
  expect(b).not.toHaveBeenCalledWith(expect.objectContaining({ sha: 'one' }));
  await waitFor(() => expect(da).toHaveBeenCalled());
  await waitFor(() => expect(db).toHaveBeenCalled());
});
