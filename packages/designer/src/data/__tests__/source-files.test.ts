// SPDX-License-Identifier: Apache-2.0
import { expect, it, vi } from 'vitest';
import { BffFilesDataSource } from '../bff-files-data-source.js';
import { createTestClient } from './lsp-harness.js';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, relative } from 'node:path';
const world = resolve('../edit/__tests__/synth/vectors/insert-entity/on-query/world');
const files = Object.fromEntries(
  readdirSync(world, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile())
    .map((e) => {
      const path = resolve(e.parentPath, e.name);
      return [relative(world, path), readFileSync(path, 'utf8')];
    })
);
it('creates a source file in the one worker cache, exposes its query to forms and collects it for Save', async () => {
  const path = 'model/er/returns-review.ttrm';
  const before = { ...files };
  delete before[path];
  const source = new BffFilesDataSource(
    { api: '/api/model', scope: { kind: 'change', id: 'review' }, token: '' },
    { createClient: createTestClient, loadSnapshot: async () => ({ sha: 'one', files: before }) }
  );
  try {
    await source.refresh();
    const diagnostics = await source.writeSourceFile({ path, text: files[path] });
    expect(diagnostics.filter((d) => d.severity === 1)).toEqual([]);
    expect((await source.listCatalog()).symbols).toEqual(
      expect.arrayContaining([expect.objectContaining({ kind: 'query', name: 'returns_review' })])
    );
    expect(source.collectChangedFiles()).toEqual([{ path, text: files[path] }]);
    expect(
      (
        await source.edit({
          op: 'insert-entity',
          args: { name: 'returns_review', on: 'er.query.returns_review' },
        })
      ).ok
    ).toBe(true);
    expect(source.sourceFiles().find((file) => file.path === path)?.text).toContain(
      'def query returns_review'
    );
  } finally {
    source.dispose();
  }
});
it('rejects paths outside regular model source files before any worker write', async () => {
  const client = await createTestClient();
  const open = vi.spyOn(client, 'openDocument');
  const source = new BffFilesDataSource(
    { api: '/api/model', scope: { kind: 'change', id: 'review' }, token: '' },
    { createClient: async () => client, loadSnapshot: async () => ({ sha: 'one', files }) }
  );
  try {
    await source.refresh();
    open.mockClear();
    for (const path of [
      '../a.ttrm',
      'model/../a.ttrm',
      'model//a.ttrm',
      '/model/a.ttrm',
      'generated/a.ttrm',
      'model/a.xlsx',
      'model/%2e%2e/a.ttrm',
    ])
      await expect(source.writeSourceFile({ path, text: 'model er' })).rejects.toThrow(
        'source path'
      );
    expect(open).not.toHaveBeenCalled();
    expect(source.changedFiles()).toEqual([]);
  } finally {
    source.dispose();
  }
});
it('collect sees the whole new file while a transport acknowledgement waits, and structured edits queue after it', async () => {
  const { WorkerLspDataSource } = await import('../worker-lsp-data-source.js');
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const open = vi.fn(async () => {
    await held;
  });
  const edit = vi.fn(async () => ({ ok: false, code: 'not-applied', message: 'test' }));
  const source = new WorkerLspDataSource(
    {
      openDocument: open,
      setProjectRoot: async () => ({}),
      edit,
    } as unknown as import('../../lsp-client.js').LspClient,
    { projectRoot: 'file:///repo', files: new Map() }
  );
  const writing = source.writeSourceFile({
    path: 'model/er/new.ttrm',
    text: 'model er\ndef entity created {}',
  });
  await vi.waitFor(() => expect(open).toHaveBeenCalledOnce());
  expect(source.changedFiles()).toEqual([
    { path: 'model/er/new.ttrm', text: 'model er\ndef entity created {}' },
  ]);
  const editing = source.edit({ op: 'insert-entity', args: { name: 'later' } });
  expect(edit).not.toHaveBeenCalled();
  release();
  await writing;
  await editing;
  expect(edit).toHaveBeenCalledOnce();
});
