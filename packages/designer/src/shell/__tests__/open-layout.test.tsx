// SPDX-License-Identifier: Apache-2.0
// VL-P1 T7 — Open layout… (VL contracts V-31): every shared vector opens a tab with the cards its
// expect.json resolves (missing / ambiguous kept, ignored dropped); a .ttrg + .ttrl picked together merge
// with the sidecar's positions winning; a .ttrl alone and a model the backend does not serve are errors —
// a toast, no tab.

import { describe, it, expect, vi, afterEach } from 'vitest';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import type { GetGraphResponse, ModelGraphEdge, ModelGraphNode } from '@tatrman/lsp';
import { ShellFrame } from '../ShellFrame.js';
import type { ModelDataSource } from '../../data/model-data-source.js';
import { readLayoutFiles, normalizeForTab } from '../../layout/open-files.js';
import { resolveRefs, type LayoutIndexEntry } from '../../layout/resolve.js';
import { layoutIndexOf } from '../../model/layout-projection.js';
import { fakeVeles, SCHEMA_CATALOG } from './layout-harness.js';

vi.mock('../../canvas/Kernel.js', async () => (await import('./layout-harness.js')).kernelMock);

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

const VECTORS = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', 'package-sdk', 'schemas', 'layout-vectors');
const CASES = readdirSync(VECTORS).filter((d) => statSync(join(VECTORS, d)).isDirectory()).sort();
const read = (c: string, f: string) => readFileSync(join(VECTORS, c, f), 'utf8');
const json = <T,>(c: string, f: string): T => JSON.parse(read(c, f)) as T;

interface VectorModel { cards: LayoutIndexEntry[]; edges?: LayoutIndexEntry[] }
interface VectorExpect { resolved: Record<string, string>; missing: string[]; ambiguous: Array<{ ref: string; candidates: string[] }>; ignored: string[] }

/** A vector's model index as a backend's whole-schema graph (node ids = full qnames, as the worker keys them). */
function graphOf(model: VectorModel, schema: string): GetGraphResponse {
  const cards = model.cards.filter((c) => c.schema === schema);
  const nodes: ModelGraphNode[] = cards.map((c) => ({
    qname: c.fullQname, kind: c.kind as ModelGraphNode['kind'], name: c.name, schemaCode: schema as ModelGraphNode['schemaCode'], pkg: c.pkg,
    label: c.name, sourceUri: '', sourceLocation: { line: 0, column: 0 }, rows: [],
  }));
  // the vectors list edges without their ends; hang each on a card of its package (what matters is the index entry)
  const edges: ModelGraphEdge[] = (model.edges ?? []).filter((e) => e.schema === schema).map((e) => {
    const end = (cards.find((c) => c.pkg === e.pkg) ?? cards[0]).fullQname;
    return { id: e.fullQname, qname: e.fullQname, kind: e.kind === 'foreign_key' ? 'fk' : 'relation', fromNode: end, toNode: end, fromCardinality: null, toCardinality: null, sourceUri: '', sourceLocation: { line: 0, column: 0 } };
  });
  return { schema: schema as GetGraphResponse['schema'], nodes, edges, layout: { nodes: {}, edges: {} }, missingObjects: [], imports: [] };
}

function vectorSource(model: VectorModel): ModelDataSource {
  const unused = () => Promise.reject(new Error('not served'));
  return {
    capabilities: { edit: false, modelKinds: ['db', 'er'], bindings: false, perspectives: false, layoutPersist: 'file', graphShape: 'structural' },
    getModelIndex: unused, getModelGraph: unused, getObject: unused, search: async () => [], listCatalog: async () => ({ graphs: [], symbols: [] }),
    getGraph: async () => null,
    onModelChanged: () => ({ dispose() {} }),
    getSchemaGraph: async (schema) => graphOf(model, schema),
  };
}

const file = (c: string, f: string, as = f) => new File([read(c, f)], as, { type: 'text/plain' });

function mount(source: ModelDataSource, picked: File[], errors: string[] = [], notices: string[] = []) {
  render(
    <ShellFrame dataSource={source} workspace="w" catalog={SCHEMA_CATALOG} files={[]} displayMode="just-names"
      onError={(m) => errors.push(m)} onNotice={(m) => notices.push(m)} pickFiles={async () => picked} />,
  );
  fireEvent.click(screen.getByTestId('layout-open'));
}

/** What the open tab holds: the checked cards and the Missing rows, by the panel. */
function panelState() {
  const checked = screen.getAllByTestId('objects-row')
    .filter((r) => (within(r).getByTestId('objects-check') as HTMLInputElement).checked)
    .map((r) => r.getAttribute('data-qname'));
  const missing = screen.queryAllByTestId('objects-missing-row').map((r) => r.getAttribute('data-ref'));
  return { checked: checked.sort(), missing: missing.sort() };
}

describe('readLayoutFiles + normalizeForTab (V-31)', () => {
  // a vector without a layout.ttrg has no TTR form (A-VL-5)
  it.each(CASES.filter((c) => existsSync(join(VECTORS, c, 'layout.ttrg'))))('%s: the YAML and the TTR form open to the same document', (c) => {
    const y = readLayoutFiles([{ name: `${c}.yaml`, text: read(c, 'layout.yaml') }]);
    const t = readLayoutFiles([{ name: `${c}.ttrg`, text: read(c, 'layout.ttrg') }]);
    if (!y.ok || !t.ok) throw new Error(JSON.stringify([y, t]));
    // a .ttrg carries neither skin nor collapsed (V-10)
    expect(t.doc).toEqual({ name: y.doc.name, model: y.doc.model, objects: y.doc.objects, positions: y.doc.positions });
  });

  it('normalizes refs to full qnames, keeps missing / ambiguous verbatim, drops ignored (V-1, V-15)', () => {
    const model = json<VectorModel>('bare-names', 'model.json');
    const r = readLayoutFiles([{ name: 'bare-names.yaml', text: read('bare-names', 'layout.yaml') }]);
    if (!r.ok) throw new Error(r.error);
    const idx = layoutIndexOf(graphOf(model, 'er'));
    const doc = normalizeForTab(r.doc, resolveRefs([...r.doc.objects, ...Object.keys(r.doc.positions)], idx.index, 'er'));
    expect(doc.objects).toEqual(Object.values(json<VectorExpect>('bare-names', 'expect.json').resolved));
    expect(Object.keys(doc.positions).every((k) => doc.objects.includes(k))).toBe(true);
  });

  it('refuses a .ttrl alone, naming the graph file it needs; and an unknown file', () => {
    expect(readLayoutFiles([{ name: 'sales.ttrl', text: read('sidecar-wins', 'input.ttrl') }])).toEqual({
      ok: false, error: 'sales.ttrl is a .ttrl sidecar — it names no graph on its own. Open it together with sales.ttrg.',
    });
    expect(readLayoutFiles([{ name: 'notes.txt', text: '' }])).toMatchObject({ ok: false, error: expect.stringMatching(/not a layout file/) });
  });
});

describe('Open layout… in the shell', () => {
  it.each(CASES)('%s: the vector opens a tab with the expected cards', async (c) => {
    const expected = json<VectorExpect>(c, 'expect.json');
    const doc = json<{ name: string; model: string }>(c, 'document.json');
    const errors: string[] = [];
    mount(vectorSource(json<VectorModel>(c, 'model.json')), [file(c, 'layout.yaml', `${doc.name}.yaml`)], errors);
    await waitFor(() => expect(screen.getAllByTestId('subject-tab').map((t) => t.textContent)).toEqual([`${doc.model} · ${doc.name}×`]));
    expect(errors).toEqual([]);
    if (Object.keys(expected.resolved).length === 0 && expected.missing.length === 0 && expected.ambiguous.length === 0) return;
    await screen.findByTestId('objects-panel');
    expect(panelState()).toEqual({
      checked: [...new Set(Object.values(expected.resolved))].sort(),
      missing: [...expected.missing, ...expected.ambiguous.map((a) => a.ref)].sort(),
    });
  });

  it('a .ttrg and its .ttrl picked together: the sidecar’s positions win', async () => {
    const notices: string[] = [];
    mount(vectorSource(json<VectorModel>('sidecar-wins', 'model.json')), [file('sidecar-wins', 'input.ttrg', 'sidecar_wins.ttrg'), file('sidecar-wins', 'input.ttrl', 'sidecar_wins.ttrl')], [], notices);
    await screen.findByTestId('mock-kernel');
    const expected = json<{ positions: Record<string, { x: number; y: number }> }>('sidecar-wins', 'document.json').positions;
    await waitFor(() => expect(JSON.parse(screen.getByTestId('mock-kernel').getAttribute('data-positions')!)).toEqual(expected));
    expect(notices.at(-1)).toMatch(/positions from the \.ttrl sidecar$/);
  });

  it('a .ttrl alone is an error toast naming the missing graph file — no tab', async () => {
    const errors: string[] = [];
    mount(vectorSource(json<VectorModel>('sidecar-wins', 'model.json')), [file('sidecar-wins', 'input.ttrl', 'sales.ttrl')], errors);
    await waitFor(() => expect(errors).toHaveLength(1));
    expect(errors[0]).toMatch(/sales\.ttrl is a \.ttrl sidecar.*sales\.ttrg/);
    expect(screen.queryByTestId('subject-tab')).toBeNull();
  });

  it('a model the catalog does not serve (md on Veles) is an error toast — no tab', async () => {
    const errors: string[] = [];
    const md = new File(['layout: 1\nname: cubes\nmodel: md\nobjects: []\n'], 'cubes.yaml');
    mount(fakeVeles().source, [md], errors);
    await waitFor(() => expect(errors).toHaveLength(1));
    expect(errors[0]).toBe('Cannot open cubes.yaml: This backend serves db/er — not md.');
    expect(screen.queryByTestId('subject-tab')).toBeNull();
  });

  it('a file that is not a valid layout names the problem and the line', async () => {
    const errors: string[] = [];
    mount(fakeVeles().source, [new File(['layout: 1\nname: x\nmodel: er\nobjects: []\ncolour: red\n'], 'x.yaml')], errors);
    await waitFor(() => expect(errors).toHaveLength(1));
    expect(errors[0]).toMatch(/^Cannot open the layout — x\.yaml: line 5: /);
  });

  it('dropping a file on the canvas opens it too', async () => {
    const errors: string[] = [];
    render(<ShellFrame dataSource={vectorSource(json<VectorModel>('positions', 'model.json'))} workspace="w" catalog={SCHEMA_CATALOG} files={[]} displayMode="just-names" onError={(m) => errors.push(m)} />);
    const dropped = file('positions', 'layout.yaml', 'positions.yaml');
    fireEvent.drop(screen.getByTestId('canvas-host'), { dataTransfer: { files: [dropped], types: ['Files'] } });
    await waitFor(() => expect(screen.getAllByTestId('subject-tab').map((t) => t.textContent)).toEqual(['er · positions×']));
  });
});
