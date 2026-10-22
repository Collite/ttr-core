// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi } from 'vitest';
import { WorkerLspDataSource } from '../worker-lsp-data-source.js';
import type { LspClient } from '../../lsp-client.js';
import type { ModelGraph } from '@tatrman/lsp';

function stubClient(overrides: Partial<LspClient> = {}): LspClient {
  return {
    transportKind: 'browser',
    openDocument: vi.fn(),
    setProjectRoot: vi.fn(),
    listSchemaSubjects: vi.fn().mockResolvedValue({ subjects: [] }),
    listGraphs: vi.fn().mockResolvedValue({ graphs: [{ uri: 'g1', name: 'g1', schema: 'db', tags: [], objectCount: 1, missingObjectCount: 0 }] }),
    getGraph: vi.fn(),
    getPackageGraph: vi.fn().mockResolvedValue({ packages: [{ name: 'acme.erp', documentUris: [] }], dependencies: [], cycles: [] }),
    getModelGraph: vi.fn().mockResolvedValue({
      schemaCode: 'db',
      nodes: [{ qname: 'db.dbo.customers', kind: 'table', name: 'customers', schemaCode: 'db', label: 'customers', sourceUri: 'u', sourceLocation: { line: 1, column: 0 }, rows: [] }],
      edges: [{ id: 'e', qname: 'e', kind: 'fk', fromNode: 'db.dbo.orders', toNode: 'db.dbo.customers', fromCardinality: null, toCardinality: null, sourceUri: 'u', sourceLocation: { line: 1, column: 0 } }],
    } satisfies ModelGraph),
    getBindings: vi.fn().mockResolvedValue({ entities: [], attributes: [], queries: [] }),
    getLayout: vi.fn(),
    setLayout: vi.fn(),
    exportLayout: vi.fn(),
    addObjectToGraph: vi.fn(),
    removeObjectFromGraph: vi.fn(),
    createGraph: vi.fn(),
    applyGraphEdit: vi.fn(),
    getSymbolDetail: vi.fn().mockResolvedValue({ qname: 'db.dbo.customers', kind: 'table', name: 'customers', label: 'customers', description: null, tags: [], sourceUri: 'u', sourceLine: 12 }),
    listSymbols: vi.fn().mockResolvedValue([
      { qname: 'db.dbo.customers', kind: 'table', name: 'customers', packageName: null },
      { qname: 'db.dbo.orders', kind: 'table', name: 'orders', packageName: null },
    ]),
    onDiagnostics: vi.fn(),
    dispose: vi.fn(),
    ...overrides,
  } as LspClient;
}

describe('WorkerLspDataSource', () => {
  it('capabilities.edit is true and exposes the lspClient escape hatch', () => {
    const client = stubClient();
    const src = new WorkerLspDataSource(client, { projectRoot: 'file:///proj' });
    expect(src.capabilities.edit).toBe(true);
    expect(src.lspClient).toBe(client);
  });

  it('is the full-featured backend (DM-P1 capability descriptor)', () => {
    const src = new WorkerLspDataSource(stubClient(), { projectRoot: 'file:///proj' });
    expect(src.capabilities).toEqual({
      edit: true,
      modelKinds: ['db', 'er', 'md', 'cnc'],
      bindings: true,
      perspectives: true,
      layoutPersist: 'in-file',
      graphShape: 'rich',
    });
  });

  it('getBindings delegates to modeler/getBindings on the lsp client', async () => {
    const client = stubClient();
    const src = new WorkerLspDataSource(client, { projectRoot: 'file:///proj' });
    const b = await src.getBindings();
    expect(client.getBindings).toHaveBeenCalled();
    expect(b).toEqual({ entities: [], attributes: [], queries: [] });
  });

  it('getSymbolDetail delegates to modeler/getSymbolDetail (the shell inspector read, DM-P2.S1)', async () => {
    const client = stubClient();
    const src = new WorkerLspDataSource(client, { projectRoot: 'file:///proj' });
    const detail = await src.getSymbolDetail('db.dbo.customers');
    expect(client.getSymbolDetail).toHaveBeenCalledWith('db.dbo.customers');
    expect(detail?.sourceUri).toBe('u');
    expect(detail?.sourceLine).toBe(12);
  });

  it('getModelIndex composes listGraphs + getPackageGraph', async () => {
    const client = stubClient();
    const src = new WorkerLspDataSource(client, { projectRoot: 'file:///proj' });
    const index = await src.getModelIndex();
    expect(client.listGraphs).toHaveBeenCalledWith('file:///proj');
    expect(client.getPackageGraph).toHaveBeenCalled();
    expect(index.packages).toEqual(['acme.erp']);
    expect(index.schemas).toEqual(['db']);
  });

  it('getModelGraph issues modeler/getModelGraph for the current uri + schema', async () => {
    const client = stubClient();
    const src = new WorkerLspDataSource(client, { projectRoot: 'file:///proj', graphUri: 'file:///g.ttrg' });
    const graph = await src.getModelGraph({ schema: 'db' });
    expect(client.getModelGraph).toHaveBeenCalledWith('file:///g.ttrg', 'db');
    expect(graph.nodes[0]).toMatchObject({ qname: 'db.dbo.customers', kind: 'table', schema: 'db' });
    expect(graph.edges[0]).toMatchObject({ from: 'db.dbo.orders', to: 'db.dbo.customers', type: 'REFERENCES' });
  });

  it('getObject issues modeler/getSymbolDetail', async () => {
    const client = stubClient();
    const src = new WorkerLspDataSource(client, { projectRoot: 'file:///proj' });
    const detail = await src.getObject('db.dbo.customers');
    expect(client.getSymbolDetail).toHaveBeenCalledWith('db.dbo.customers');
    expect(detail.object.qname).toBe('db.dbo.customers');
  });

  it('search issues modeler/listSymbols + client-side filtering', async () => {
    const client = stubClient();
    const src = new WorkerLspDataSource(client, { projectRoot: 'file:///proj' });
    const hits = await src.search({ query: 'order' });
    expect(client.listSymbols).toHaveBeenCalled();
    expect(hits).toHaveLength(1);
    expect(hits[0]!.qname).toBe('db.dbo.orders');
  });

  it('onModelChanged is a no-op disposable (no file watching on the worker path)', () => {
    const client = stubClient();
    const src = new WorkerLspDataSource(client, { projectRoot: 'file:///proj' });
    const d = src.onModelChanged(() => { throw new Error('should not fire'); });
    expect(() => d.dispose()).not.toThrow();
  });
});
