// SPDX-License-Identifier: Apache-2.0
// VL-P1 T8 — Save as… and the layout commands (VL contracts V-30, V-32, V-33): Save as of an opened
// vector reproduces the vector's bytes in both formats; the dialog enforces the name rule live; the
// format defaults to the last choice, then VIEWER_LAYOUT_FORMAT, then YAML; Save as is disabled on a
// program tab; leaving the page is guarded iff a local layout has unsaved edits.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import type { GetGraphResponse, ModelGraphEdge, ModelGraphNode } from '@tatrman/lsp';
import { ShellFrame } from '../ShellFrame.js';
import { SaveAsDialog, layoutWriteProblems } from '../SaveAsDialog.js';
import { readYaml } from '../../layout/layout-yaml.js';
import type { LayoutDocument } from '../../layout/layout-document.js';
import type { ModelDataSource } from '../../data/model-data-source.js';
import type { LayoutIndexEntry } from '../../layout/resolve.js';
import type { CatalogGroup } from '../types.js';
import { setRuntimeConfig, defaultRuntimeConfig } from '../../runtime-config.js';
import { fakeVeles, SCHEMA_CATALOG } from './layout-harness.js';

vi.mock('../../canvas/Kernel.js', async () => (await import('./layout-harness.js')).kernelMock);

beforeEach(() => window.localStorage.clear());
afterEach(() => {
  cleanup();
  setRuntimeConfig(null);
  window.history.replaceState(null, '', '/');
});

const VECTORS = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', 'package-sdk', 'schemas', 'layout-vectors');
const read = (c: string, f: string) => readFileSync(join(VECTORS, c, f), 'utf8');
const json = <T,>(c: string, f: string): T => JSON.parse(read(c, f)) as T;
interface VectorModel { cards: LayoutIndexEntry[]; edges?: LayoutIndexEntry[] }
interface VectorExpect { resolved: Record<string, string>; ignored: string[] }

// the vectors a viewer opens unchanged: every ref resolves to itself or stays verbatim, none is ignored
const SAME_ON_OPEN = readdirSync(VECTORS)
  .filter((d) => statSync(join(VECTORS, d)).isDirectory())
  .filter((c) => {
    const e = json<VectorExpect>(c, 'expect.json');
    return e.ignored.length === 0 && Object.entries(e.resolved).every(([ref, fq]) => ref === fq);
  })
  .sort();

function graphOf(model: VectorModel, schema: string): GetGraphResponse {
  const cards = model.cards.filter((c) => c.schema === schema);
  const nodes: ModelGraphNode[] = cards.map((c) => ({
    qname: c.fullQname, kind: c.kind as ModelGraphNode['kind'], name: c.name, schemaCode: schema as ModelGraphNode['schemaCode'], pkg: c.pkg,
    label: c.name, sourceUri: '', sourceLocation: { line: 0, column: 0 }, rows: [],
  }));
  const edges: ModelGraphEdge[] = [];
  return { schema: schema as GetGraphResponse['schema'], nodes, edges, layout: { nodes: {}, edges: {} }, missingObjects: [], imports: [] };
}
function vectorSource(model: VectorModel): ModelDataSource {
  const unused = () => Promise.reject(new Error('not served'));
  return {
    capabilities: { edit: false, modelKinds: ['db', 'er'], bindings: false, perspectives: false, layoutPersist: 'file', graphShape: 'structural' },
    getModelIndex: unused, getModelGraph: unused, getObject: unused, search: async () => [], listCatalog: async () => ({ graphs: [], symbols: [] }),
    getGraph: async () => null, onModelChanged: () => ({ dispose() {} }), getSchemaGraph: async (schema) => graphOf(model, schema),
  };
}

type Saved = { fileName: string; content: string; mimeType: string };
function mount(source: ModelDataSource, picked: File[] = [], catalog: CatalogGroup[] = SCHEMA_CATALOG) {
  const saved: Saved[] = [];
  render(
    <ShellFrame dataSource={source} workspace="w" catalog={catalog} files={[]} displayMode="just-names"
      pickFiles={async () => picked} downloadFile={async (fileName, content, mimeType) => { saved.push({ fileName, content, mimeType }); }} />,
  );
  return saved;
}
async function openVector(c: string) {
  const doc = json<{ name: string }>(c, 'document.json');
  const saved = mount(vectorSource(json<VectorModel>(c, 'model.json')), [new File([read(c, 'layout.yaml')], `${doc.name}.yaml`)]);
  fireEvent.click(screen.getByTestId('layout-open'));
  await waitFor(() => expect(screen.getAllByTestId('subject-tab')).toHaveLength(1));
  return { saved, name: doc.name };
}
async function saveAs(format: 'yaml' | 'ttr', name?: string) {
  fireEvent.click(screen.getByTestId('layout-save-as'));
  const dialog = await screen.findByTestId('save-as-dialog');
  if (name !== undefined) fireEvent.change(within(dialog).getByTestId('save-as-name'), { target: { value: name } });
  fireEvent.click(within(dialog).getByTestId(`save-as-format-${format}`));
  fireEvent.click(within(dialog).getByTestId('save-as-save'));
}

describe('Save as… reproduces the shared vectors byte for byte (V-5, V-32)', () => {
  it('opens the vectors that resolve to themselves', () => {
    expect(SAME_ON_OPEN).toEqual(['diacritics', 'empty', 'minimal', 'missing', 'not-an-id', 'positions', 'sidecar-wins', 'skin-collapsed']);
  });

  it.each(SAME_ON_OPEN)('%s — YAML and TTR (or no TTR form, A-VL-5)', async (c) => {
    const { saved, name } = await openVector(c);
    await saveAs('yaml');
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0]).toEqual({ fileName: `${name}.yaml`, content: read(c, 'layout.yaml'), mimeType: 'application/yaml' });
    if (existsSync(join(VECTORS, c, 'layout.ttrg'))) {
      await saveAs('ttr');
      await waitFor(() => expect(saved).toHaveLength(2));
      expect(saved[1]).toEqual({ fileName: `${name}.ttrg`, content: read(c, 'layout.ttrg'), mimeType: 'text/plain' });
      return;
    }
    // a ref that is not a TTR name: the dialog names it, shows no text and saves nothing
    await saveAs('ttr');
    const dialog = screen.getByTestId('save-as-dialog');
    expect(within(dialog).getByTestId('save-as-problem')).toHaveTextContent('TTR cannot spell “Customer Address”, “hartland.er.entity.date” — a .ttrg lists TTR names only. Save as YAML, or remove them from the layout.');
    expect(within(dialog).getByTestId('save-as-preview')).toHaveTextContent(/^$/);
    expect(within(dialog).getByTestId('save-as-save')).toBeDisabled();
    expect(saved).toHaveLength(1);
  });

  it('after the save the tab is the clean local file, renamed', async () => {
    const { saved } = await openVector('positions');
    await saveAs('yaml', 'sales_overview');
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0].fileName).toBe('sales_overview.yaml');
    expect(saved[0].content).toMatch(/^name: sales_overview$/m);
    await waitFor(() => expect(screen.getAllByTestId('subject-tab')[0]).toHaveTextContent(/^er · sales_overview×$/));
  });
});

describe('the Save as… dialog', () => {
  it('enforces the Identifier rule live: a-b is refused, Save disabled', async () => {
    await openVector('minimal');
    fireEvent.click(screen.getByTestId('layout-save-as'));
    const dialog = await screen.findByTestId('save-as-dialog');
    fireEvent.change(within(dialog).getByTestId('save-as-name'), { target: { value: 'a-b' } });
    expect(within(dialog).getByTestId('save-as-name-error')).toHaveTextContent('Letters, digits and underscore');
    expect(within(dialog).getByTestId('save-as-save')).toBeDisabled();
    fireEvent.change(within(dialog).getByTestId('save-as-name'), { target: { value: '2nd' } });
    expect(within(dialog).getByTestId('save-as-save')).toBeDisabled();
    fireEvent.change(within(dialog).getByTestId('save-as-name'), { target: { value: 'sales_2' } });
    expect(within(dialog).getByTestId('save-as-save')).toBeEnabled();
    expect(within(dialog).getByTestId('save-as-preview')).toHaveTextContent('name: sales_2');
  });

  it('TTR drops the skin and says so (V-10); a TTR keyword as the name warns (VL-P0 Q1)', async () => {
    await openVector('skin-collapsed');
    fireEvent.click(screen.getByTestId('layout-save-as'));
    const dialog = await screen.findByTestId('save-as-dialog');
    expect(within(dialog).queryByTestId('save-as-skin-note')).toBeNull();
    fireEvent.click(within(dialog).getByTestId('save-as-format-ttr'));
    expect(within(dialog).getByTestId('save-as-skin-note')).toHaveTextContent('does not carry the notation');
    fireEvent.change(within(dialog).getByTestId('save-as-name'), { target: { value: 'text' } });
    expect(within(dialog).getByTestId('save-as-keyword-note')).toHaveTextContent('“text” is a TTR keyword');
    fireEvent.click(within(dialog).getByTestId('save-as-format-yaml'));
    expect(within(dialog).queryByTestId('save-as-keyword-note')).toBeNull();
  });

  it('the format: VIEWER_LAYOUT_FORMAT first, then the last choice in this workspace', async () => {
    setRuntimeConfig({ ...defaultRuntimeConfig(), layoutFormat: 'ttr' });
    const { saved } = await openVector('minimal');
    fireEvent.click(screen.getByTestId('layout-save-as'));
    let dialog = await screen.findByTestId('save-as-dialog');
    expect(within(dialog).getByTestId('save-as-format-ttr')).toBeChecked();
    fireEvent.click(within(dialog).getByTestId('save-as-format-yaml'));
    fireEvent.click(within(dialog).getByTestId('save-as-save'));
    await waitFor(() => expect(saved).toHaveLength(1));
    fireEvent.click(screen.getByTestId('layout-save-as'));
    dialog = await screen.findByTestId('save-as-dialog');
    expect(within(dialog).getByTestId('save-as-format-yaml')).toBeChecked();
  });

  it('the whole schema saves too — the tab becomes the saved file', async () => {
    const saved = mount(fakeVeles().source);
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
    await screen.findByTestId('objects-panel');
    await saveAs('yaml', 'everything');
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0].content).toContain('  - hartland.er.entity.customer_address\n  - hartland.er.entity.item\n');
    await waitFor(() => expect(screen.getAllByTestId('subject-tab')[0]).toHaveTextContent(/^er · everything×$/));
  });
});

describe('Save as… writes only what Open layout… reads (V-3; ttr-core#180 #1)', () => {
  it('a plain subject saves the positions of the cards it shows — a stale node in the store is left out', async () => {
    const saved: Saved[] = [];
    const viewState = {
      read: async () => ({ skin: 'er.crow', mode: 'manual' as const, nodes: { 'er.entity.item': { x: 10, y: 20 }, 'er.entity.gone': { x: 30, y: 40 } }, collapsed: [] }),
      write: async () => {},
    };
    render(
      <ShellFrame dataSource={fakeVeles().source} workspace="w" catalog={SCHEMA_CATALOG} files={[]} displayMode="just-names" viewState={viewState}
        downloadFile={async (fileName, content, mimeType) => { saved.push({ fileName, content, mimeType }); }} />,
    );
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
    await screen.findByTestId('objects-panel');
    await saveAs('yaml', 'everything');
    await waitFor(() => expect(saved).toHaveLength(1));
    const back = readYaml(saved[0].content);
    expect(back.ok).toBe(true);
    if (back.ok) expect(back.doc.positions).toEqual({ 'hartland.er.entity.item': { x: 10, y: 20 } });
  });

  it('a document the readers would refuse is not offered for saving: the reasons, no preview, Save disabled', () => {
    const doc: LayoutDocument = { name: 'g', model: 'er', objects: ['a'], positions: { b: { x: 1, y: 2 } } };
    expect(layoutWriteProblems(doc, 'g', 'yaml')).toEqual(['positions key b is not in objects']);
    render(<SaveAsDialog doc={doc} initialName="g" initialFormat="yaml" onSave={() => {}} onCancel={() => {}} />);
    expect(screen.getByTestId('save-as-problem')).toHaveTextContent('positions key b is not in objects');
    expect(screen.getByTestId('save-as-preview')).toHaveTextContent(/^$/);
    expect(screen.getByTestId('save-as-save')).toBeDisabled();
  });
});

describe('the layout commands (V-33)', () => {
  const PROGRAMS: CatalogGroup[] = [
    ...SCHEMA_CATALOG,
    { kind: 'program', label: 'Programs', items: [{ ref: 'p.monthly', qname: 'p.monthly', kind: 'program', label: 'program monthly' }] },
  ];
  const command = (id: string) => screen.getAllByTestId('cmdk-item').find((i) => i.getAttribute('data-command') === id)!;

  it('are in ⌘K; Save as… / New / Re-layout are disabled on a program tab, Open is not', async () => {
    mount(fakeVeles().source, [], PROGRAMS);
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'p.monthly')!);
    await waitFor(() => expect(screen.getAllByTestId('subject-tab')).toHaveLength(1));
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    fireEvent.change(await screen.findByTestId('cmdk-input'), { target: { value: 'layout' } });
    expect(command('layout.saveAs')).toHaveAttribute('aria-disabled', 'true');
    expect(command('layout.new')).toHaveAttribute('aria-disabled', 'true');
    expect(command('layout.relayout')).toHaveAttribute('aria-disabled', 'true');
    expect(command('layout.open')).not.toHaveAttribute('aria-disabled');
    fireEvent.click(command('layout.saveAs'));
    expect(screen.queryByTestId('save-as-dialog')).toBeNull();
  });

  it('the tab menu offers them for that tab', async () => {
    mount(fakeVeles().source);
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
    await screen.findByTestId('objects-panel');
    fireEvent.contextMenu(screen.getAllByTestId('subject-tab')[0]);
    const menu = screen.getByTestId('tab-menu');
    expect(within(menu).getAllByTestId('view-menu-item').map((b) => b.textContent)).toEqual(['Save layout as…', 'New layout from this view', 'Re-layout', 'Open layout…']);
    fireEvent.click(within(menu).getAllByTestId('view-menu-item')[1]);
    await waitFor(() => expect(screen.getAllByTestId('subject-tab').map((t) => t.textContent)).toEqual(['er📌×', 'er · untitled*×']));
  });
});

describe('the tab menu (ttr-core#180 #6)', () => {
  it('closes on a press outside it and on Escape — not on a press inside', async () => {
    mount(fakeVeles().source);
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
    await screen.findByTestId('objects-panel');
    fireEvent.contextMenu(screen.getAllByTestId('subject-tab')[0]);
    fireEvent.pointerDown(within(screen.getByTestId('tab-menu')).getAllByTestId('view-menu-item')[0]);
    expect(screen.getByTestId('tab-menu')).toBeInTheDocument();
    fireEvent.pointerDown(screen.getByTestId('canvas-host'));
    expect(screen.queryByTestId('tab-menu')).toBeNull();
    fireEvent.contextMenu(screen.getAllByTestId('subject-tab')[0]);
    expect(screen.getByTestId('tab-menu')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('tab-menu')).toBeNull();
  });
});

describe('leaving the page (V-30)', () => {
  const leave = () => {
    const e = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(e);
    return e.defaultPrevented;
  };

  it('is guarded iff a file / new layout has unsaved edits — never for a catalog layout', async () => {
    const { saved } = await openVector('positions');
    expect(leave()).toBe(false); // opened, untouched
    fireEvent.click(screen.getByTestId('objects-none'));
    await waitFor(() => expect(screen.getByTestId('tab-dirty')).toBeInTheDocument());
    expect(leave()).toBe(true);
    await saveAs('yaml');
    await waitFor(() => expect(saved).toHaveLength(1));
    await waitFor(() => expect(screen.queryByTestId('tab-dirty')).toBeNull());
    expect(leave()).toBe(false);
  });
});
