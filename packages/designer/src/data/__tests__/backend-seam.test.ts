// SPDX-License-Identifier: Apache-2.0
// DM-P2.S4 — the 3-backend seam test (the inherited DM-P1 close item, co-designed with the shell
// consumer). One assertion sweep proving the shell's data needs are met across ALL three backends:
// Worker full, WS/Veles honestly degraded. Drives the shell-facing surface (capabilities, getGraph
// shape §1.1a, listCatalog, getBindings, ViewStateStore round-trip) so a regression on any backend's
// contract fails here, not in a browser.

import { describe, it, expect, vi } from 'vitest';
import type { LspClient } from '../../lsp-client.js';
import type { GetGraphResponse } from '@tatrman/lsp';
import { WorkerLspDataSource } from '../worker-lsp-data-source.js';
import { WsDesignerServerDataSource } from '../ws-designer-server-data-source.js';
import { VelesReadApiDataSource } from '../veles-read-api-data-source.js';
import { ttrmToGetGraphResponse } from '../structural-graph.js';
import { WorkerLayoutStore, WsSidecarStore, LayoutDocumentStore, type LayoutIO, type PrefsIO, type TtrmLayoutIO } from '../view-state-store.js';
import type { LayoutFile } from '@tatrman/lsp';
import type { TtrmLayoutPayload, TtrmLayoutCanvas } from '../ttrm-types.js';

const RICH: GetGraphResponse = {
  schema: 'er', edges: [], layout: { nodes: { A: { x: 1, y: 2 } }, edges: {} }, missingObjects: [], imports: ['pkg.a'],
  nodes: [{ qname: 'er.A', kind: 'entity', name: 'A', schemaCode: 'er', label: 'A', sourceUri: 'u', sourceLocation: { line: 1, column: 0 }, rows: [{ name: 'id', qname: 'er.A.id', kind: 'attribute', type: 'int', isKey: true, optional: false, isNameAttribute: false, isCodeAttribute: false }] }],
};

function workerStub(): LspClient {
  return {
    transportKind: 'browser',
    openDocument: vi.fn(), setProjectRoot: vi.fn(),
    listGraphs: vi.fn().mockResolvedValue({ graphs: [{ uri: 'file:///er.ttrg', name: 'sales', schema: 'er', tags: [], objectCount: 1, missingObjectCount: 0 }] }),
    getGraph: vi.fn().mockResolvedValue(RICH),
    getPackageGraph: vi.fn().mockResolvedValue({ packages: [], dependencies: [], cycles: [] }),
    getModelGraph: vi.fn(), getBindings: vi.fn().mockResolvedValue({ entities: [{ entityQname: 'er.A', target: { kind: 'table', tableQname: 'db.A' } }], attributes: [], queries: [] }),
    getLayout: vi.fn(), setLayout: vi.fn(), exportLayout: vi.fn(),
    addObjectToGraph: vi.fn(), removeObjectFromGraph: vi.fn(), createGraph: vi.fn(), applyGraphEdit: vi.fn(),
    getSymbolDetail: vi.fn().mockResolvedValue({ qname: 'er.A', kind: 'entity', name: 'A', label: 'A', description: null, tags: [], sourceUri: 'u', sourceLine: 1 }),
    listSymbols: vi.fn().mockResolvedValue([{ qname: 'md.sales', kind: 'cubelet', name: 'sales', packageName: 'pkg' }]),
    onDiagnostics: vi.fn(), dispose: vi.fn(),
  } as unknown as LspClient;
}

describe('DM-P2 seam — Worker (full backend)', () => {
  const src = new WorkerLspDataSource(workerStub(), { projectRoot: 'file:///proj' });

  it('capabilities: rich + editable + perspectives + in-file', () => {
    expect(src.capabilities).toMatchObject({ edit: true, graphShape: 'rich', perspectives: true, bindings: true, layoutPersist: 'in-file' });
  });
  it('getGraph returns the rich slot shape (rows present)', async () => {
    const g = await src.getGraph('file:///er.ttrg');
    expect(g!.nodes[0].rows.length).toBe(1); // slot data
  });
  it('listCatalog serves graphs AND symbols; getBindings + getSymbolDetail resolve', async () => {
    const cat = await src.listCatalog();
    expect(cat.graphs).toHaveLength(1);
    expect(cat.symbols).toHaveLength(1); // cubelet subject
    expect((await src.getBindings!()).entities).toHaveLength(1);
    expect((await src.getSymbolDetail!('er.A'))?.sourceUri).toBe('u');
  });
  it('ViewStateStore (in-file) round-trips positions', async () => {
    const store: Record<string, LayoutFile> = {};
    const io: LayoutIO = { getLayout: async (k) => store[k] ?? { version: 1, nodes: {}, edges: {} }, setLayout: async (k, l) => { store[k] = l; } };
    const prefs: PrefsIO = { get: () => undefined, set: () => {} };
    const vss = new WorkerLayoutStore(io, prefs);
    await vss.write('file:///er.ttrg#main', { skin: 'er.crow', mode: 'manual', nodes: { A: { x: 5, y: 6 } }, collapsed: [] });
    expect((await vss.read('file:///er.ttrg#main')).nodes).toEqual({ A: { x: 5, y: 6 } });
  });
});

describe('DM-P2 seam — WS (structural, sidecar, no bindings)', () => {
  const src = new WsDesignerServerDataSource('ws://127.0.0.1:7270');

  it('capabilities: structural + read-only + no perspectives + sidecar', () => {
    expect(src.capabilities).toMatchObject({ edit: false, graphShape: 'structural', perspectives: false, bindings: false, layoutPersist: 'sidecar' });
  });
  it('a served kind renders STRUCTURAL-ONLY (rows empty) — DM-CAP-002', () => {
    const g = ttrmToGetGraphResponse('er', [{ qname: 'er.A', kind: 'entity', label: 'A', schema: 'er', pkg: '' }], [], []);
    expect(g.nodes[0].rows).toEqual([]); // no slot bodies on this backend
  });
  it('ViewStateStore (.ttrl sidecar) round-trips positions, preserving siblings', async () => {
    const store: Record<string, TtrmLayoutCanvas[]> = { 'file:///m.ttrg': [{ key: 'other', skin: 'er.crow', mode: 'auto', nodes: [], collapsed: [] }] };
    const io: TtrmLayoutIO = {
      getLayout: async (uri): Promise<TtrmLayoutPayload> => ({ exists: true, version: 1, canvases: store[uri] ?? [], orphaned: [], errors: [] }),
      setLayout: async (uri, canvases) => { store[uri] = canvases; },
    };
    const vss = new WsSidecarStore(io);
    await vss.write('file:///m.ttrg#main', { skin: 'db.uml', mode: 'manual', nodes: { A: { x: 7, y: 8 } }, collapsed: [] });
    expect((await vss.read('file:///m.ttrg#main')).nodes).toEqual({ A: { x: 7, y: 8 } });
    expect(store['file:///m.ttrg'].find((c) => c.key === 'other')).toBeDefined(); // sibling preserved
  });
});

describe('DM-P2 seam — Veles (thinnest: structural, layouts held by the tab)', () => {
  const src = new VelesReadApiDataSource('/veles');

  it("capabilities: db/er only, structural, no perspectives, layout persist 'file' (VL V-29)", () => {
    expect(src.capabilities).toMatchObject({ edit: false, modelKinds: ['db', 'er'], graphShape: 'structural', perspectives: false, layoutPersist: 'file' });
  });
  it("ViewStateStore ('file') keeps the session's positions in memory — Save as… downloads them", async () => {
    const vss = new LayoutDocumentStore();
    expect((await vss.read('x')).mode).toBe('auto');
    await vss.write('x', { skin: 'er.crow', mode: 'manual', nodes: { A: { x: 1, y: 1 } }, collapsed: [] });
    expect((await vss.read('x')).nodes).toEqual({ A: { x: 1, y: 1 } });
  });
});
