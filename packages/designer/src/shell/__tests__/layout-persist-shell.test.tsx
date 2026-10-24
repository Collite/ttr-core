// SPDX-License-Identifier: Apache-2.0
// VL-P1 — what a canvas persist does to a tab (review of ttr-core#179): a display-mode switch re-emits the
// canvas's positions, and that is not an edit — an auto layout stays auto, an unmoved manual one stays
// clean; a drag on a plain subject is kept by the shell, so the first selection edit (V-22) and New layout
// carry it. A display-mode switch on an auto plain subject writes nothing to the store (an auto canvas with
// nodes is a `.ttrl` the designer server's own loader refuses, A-VL-3).

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { ShellFrame } from '../ShellFrame.js';
import { buildCatalog } from '../catalog.js';
import type { DisplayMode } from '@tatrman/lsp';
import type { CanvasViewState, ViewStateStore } from '@tatrman/canvas-core';
import layouts from '../../data/__tests__/fixtures/veles/layouts.json';
import positionsLayout from '../../data/__tests__/fixtures/veles/layout-positions.json';
import { fakeVeles, SCHEMA_CATALOG, attr } from './layout-harness.js';

vi.mock('../../canvas/Kernel.js', async () => (await import('./layout-harness.js')).kernelMock);

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

const positions = () => JSON.parse(attr('positions')) as Record<string, { x: number; y: number }>;
/** a drag reaches the shell after the canvas's 400 ms persist debounce */
const persisted = () => new Promise((r) => setTimeout(r, 450));
const settled = () => new Promise((r) => setTimeout(r, 50));
const layoutChipText = () => screen.getByTestId('truth-chip-layout').textContent;
const row = (fq: string) => screen.getAllByTestId('objects-row').find((r) => r.getAttribute('data-qname') === fq)!;
const toggle = (fq: string) => fireEvent.click(within(row(fq)).getByTestId('objects-check'));

// the catalog serves `positions` (manual) and, here, `auto_er` — the same cards with no positions
const ROUTES = {
  '/model/index': { packages: ['hartland'], schemas: ['db', 'er'], areas: [], counts: { objects: 0, schemas: 2, areas: 0 }, modelVersion: 'hartland-fixture' },
  '/model/layouts': {
    ...layouts,
    layouts: [...layouts.layouts, { name: 'auto_er', schema: 'er', objectCount: 3, missingCount: 0, ambiguousCount: 0, hasPositions: false, source: 'layouts/auto_er.ttrg' }],
  },
  '/model/layout?name=positions': positionsLayout,
  '/model/layout?name=auto_er': {
    ...positionsLayout, name: 'auto_er', positions: {}, mode: 'auto', missing: [], positionsFrom: null, source: 'layouts/auto_er.ttrg',
  },
};

async function openCatalogLayout(label: string) {
  const { source } = fakeVeles(ROUTES);
  const { graphs, symbols } = await source.listCatalog();
  const catalog = buildCatalog(graphs, symbols);
  const view = (displayMode: DisplayMode) => <ShellFrame dataSource={source} workspace="veles" catalog={catalog} files={[]} displayMode={displayMode} />;
  const { rerender } = render(view('just-names'));
  fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.textContent === label)!);
  // Kernel creation and ELK placement share one readiness boundary on a loaded CI runner.
  await waitFor(() => {
    expect(attr('nodes')).toBe('er.entity.item,er.entity.store,er.entity.store_sales');
    expect(Object.keys(positions())).toHaveLength(3);
  }, { timeout: 3000 });
  return { switchTo: (m: DisplayMode) => rerender(view(m)) };
}

describe('a display-mode switch is not an edit', () => {
  it('an auto layout stays auto and clean — the canvas lays the resized cards out afresh', async () => {
    const { switchTo } = await openCatalogLayout('er · auto_er');
    expect(layoutChipText()).toBe(' · layout: catalog · auto_er');
    const before = positions();
    switchTo('with-types');
    await waitFor(() => expect(positions()).not.toEqual(before)); // ELK's, for the taller cards
    await settled();
    expect(layoutChipText()).toBe(' · layout: catalog · auto_er');
    expect(screen.getByTestId('truth-chip')).toHaveTextContent('mode=auto');
  });

  it('a manual layout nothing moved in stays clean, its positions as they were', async () => {
    const { switchTo } = await openCatalogLayout('er · positions · 1 missing');
    const before = positions();
    switchTo('with-types');
    await settled();
    expect(layoutChipText()).toBe(' · layout: catalog · positions');
    expect(positions()).toEqual(before);
  });

  it('a drag still edits it', async () => {
    await openCatalogLayout('er · positions · 1 missing');
    fireEvent.click(screen.getByTestId('mock-drag'));
    await persisted();
    await waitFor(() => expect(layoutChipText()).toBe(' · layout: catalog · positions (edited, unsaved)'));
  });

  it('a drag moves cards, not the picture: the canvas keeps the graph it was handed (ttr-core#180 #7)', async () => {
    await openCatalogLayout('er · positions · 1 missing');
    const graph = attr('graph');
    fireEvent.click(screen.getByTestId('mock-drag'));
    await persisted();
    await waitFor(() => expect(layoutChipText()).toBe(' · layout: catalog · positions (edited, unsaved)'));
    expect(positions()['er.entity.item']).toEqual({ x: 777, y: 333 });
    expect(attr('graph')).toBe(graph); // not re-projected, not re-mapped, no fresh ELK run
  });
});

describe('a drag on a plain subject is kept (V-22, V-33)', () => {
  async function dragOnEr() {
    render(<ShellFrame dataSource={fakeVeles().source} workspace="veles" catalog={SCHEMA_CATALOG} files={[]} displayMode="just-names" />);
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
    await screen.findByTestId('objects-panel');
    await waitFor(() => expect(attr('nodes').split(',')).toHaveLength(4));
    const dragged = screen.getAllByTestId('mock-node')[0].getAttribute('data-id')!;
    fireEvent.click(screen.getByTestId('mock-drag'));
    await waitFor(() => expect(positions()[dragged]).toEqual({ x: 777, y: 333 }));
    await persisted();
    return dragged;
  }

  it('the first selection edit carries it into er · untitled', async () => {
    const dragged = await dragOnEr();
    const other = attr('nodes').split(',').find((id) => id !== dragged)!;
    toggle(`hartland.${other}`);
    await waitFor(() => expect(screen.getAllByTestId('subject-tab')[0]).toHaveTextContent('er · untitled'));
    await waitFor(() => expect(attr('nodes').split(',')).toHaveLength(3));
    expect(positions()[dragged]).toEqual({ x: 777, y: 333 });
    expect(screen.getByTestId('truth-chip')).toHaveTextContent('mode=manual');
  });

  it('New layout carries it', async () => {
    const dragged = await dragOnEr();
    fireEvent.click(screen.getByTestId('layout-new'));
    await waitFor(() => expect(screen.getAllByTestId('subject-tab')).toHaveLength(2));
    await waitFor(() => expect(positions()[dragged]).toEqual({ x: 777, y: 333 }));
  });
});

describe('a plain subject’s store sees drags only', () => {
  it('a display-mode switch on an auto canvas writes nothing; a drag writes manual positions', async () => {
    const writes: CanvasViewState[] = [];
    const viewState: ViewStateStore = {
      read: async () => ({ skin: 'er.crow', mode: 'auto', nodes: {}, collapsed: [] }),
      write: async (_key, vs) => { writes.push(vs); },
    };
    const source = fakeVeles().source;
    const view = (displayMode: DisplayMode) => <ShellFrame dataSource={source} workspace="veles" catalog={SCHEMA_CATALOG} files={[]} displayMode={displayMode} viewState={viewState} />;
    const { rerender } = render(view('just-names'));
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
    await waitFor(() => expect(Object.keys(positions())).toHaveLength(4));
    rerender(view('with-types'));
    await settled();
    expect(writes).toEqual([]);
    fireEvent.click(screen.getByTestId('mock-drag'));
    await persisted();
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0].mode).toBe('manual');
    expect(Object.keys(writes[0].nodes)).toHaveLength(4);
  });
});
