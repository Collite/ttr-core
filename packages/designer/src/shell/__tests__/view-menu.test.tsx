// SPDX-License-Identifier: Apache-2.0
// VL-P1 T6 — the view menu (VL contracts V-27, ⚑VL-13): Expand (+n) adds exactly the hidden one-edge
// frontier and places it; Expand all levels… asks with the count; Hide / Hide others; Show <H> on a hop
// edge; shift-click applies to the set; with the FO-21 edit menu injected, one list — view, separator, edit.

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import type { ModelGraph } from '@tatrman/lsp';
import { ShellFrame } from '../ShellFrame.js';
import { SkinnedCanvas } from '../../canvas/SkinnedCanvas.js';
import { ViewMenu } from '../ViewMenu.js';
import { fakeVeles, SCHEMA_CATALOG, attr } from './layout-harness.js';

vi.mock('../../canvas/Kernel.js', async () => (await import('./layout-harness.js')).kernelMock);

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

const row = (fq: string) => screen.getAllByTestId('objects-row').find((r) => r.getAttribute('data-qname') === fq)!;
const toggle = (fq: string) => fireEvent.click(within(row(fq)).getByTestId('objects-check'));
const node = (id: string) => screen.getAllByTestId('mock-node').find((n) => n.getAttribute('data-id') === id)!;
const menuItem = (id: string) => within(screen.getByTestId('view-menu')).getAllByTestId('view-menu-item').find((b) => b.getAttribute('data-item') === id)!;
const positions = () => JSON.parse(attr('positions')) as Record<string, { x: number; y: number }>;
const persisted = () => new Promise((r) => setTimeout(r, 450));
const ITEM = 'hartland.er.entity.item';
const STORE = 'hartland.er.entity.store';
const SALES = 'hartland.er.entity.store_sales';

async function openEr(notices: string[] = []) {
  render(<ShellFrame dataSource={fakeVeles().source} workspace="veles" catalog={SCHEMA_CATALOG} files={[]} displayMode="just-names" onNotice={(m) => notices.push(m)} />);
  fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
  await screen.findByTestId('objects-panel');
  await screen.findByTestId('mock-kernel');
}
async function onlyItem() {
  await openEr();
  fireEvent.click(screen.getByTestId('objects-none'));
  toggle(ITEM);
  await waitFor(() => expect(attr('nodes')).toBe('er.entity.item'));
}

describe('the view menu on a card', () => {
  it('lists Expand (+n) · Expand all levels… · Hide · Hide others · Inspect', async () => {
    await openEr();
    fireEvent.contextMenu(node('er.entity.item'));
    const items = within(screen.getByTestId('view-menu')).getAllByTestId('view-menu-item');
    expect(items.map((b) => b.textContent)).toEqual(['Expand+0', 'Expand all levels…closure', 'Hide', 'Hide otherskeep + neighbours', 'Inspect']);
    expect(menuItem('expand')).toBeDisabled(); // every neighbour already shown
  });

  it('Expand adds exactly the hidden one-edge frontier, placed beside the card in a manual layout', async () => {
    await onlyItem();
    fireEvent.click(screen.getByTestId('mock-drag'));
    await persisted();
    fireEvent.contextMenu(node('er.entity.item'));
    expect(menuItem('expand')).toHaveTextContent('Expand+1');
    fireEvent.click(menuItem('expand'));
    await waitFor(() => expect(attr('nodes')).toBe('er.entity.item,er.entity.store_sales'));
    expect(positions()['er.entity.item']).toEqual({ x: 777, y: 333 });
    expect(positions()['er.entity.store_sales']).toEqual({ x: 1017, y: 333 });
    expect(screen.queryByTestId('view-menu')).toBeNull();
  });

  it('Expand all levels… names the count; Cancel changes nothing, Add them adds the closure', async () => {
    await onlyItem();
    fireEvent.contextMenu(node('er.entity.item'));
    fireEvent.click(menuItem('expand-all'));
    expect(screen.getByTestId('confirm-text')).toHaveTextContent('This adds 3 cards (4 of 4 shown afterwards)');
    fireEvent.click(screen.getByTestId('confirm-no'));
    expect(screen.queryByTestId('confirm-dialog')).toBeNull();
    expect(attr('nodes')).toBe('er.entity.item');
    fireEvent.contextMenu(node('er.entity.item'));
    fireEvent.click(menuItem('expand-all'));
    fireEvent.click(screen.getByTestId('confirm-yes'));
    await waitFor(() => expect(attr('nodes').split(',')).toHaveLength(4));
  });

  it('Hide others keeps the card and its visible neighbours; Hide removes the card', async () => {
    await openEr();
    fireEvent.contextMenu(node('er.entity.item'));
    fireEvent.click(menuItem('hide-others'));
    await waitFor(() => expect(attr('nodes')).toBe('er.entity.item,er.entity.store_sales'));
    fireEvent.contextMenu(node('er.entity.store_sales'));
    fireEvent.click(menuItem('hide'));
    await waitFor(() => expect(attr('nodes')).toBe('er.entity.item'));
  });

  it('shift-click selects several; the menu applies to the set', async () => {
    await openEr();
    fireEvent.click(node('er.entity.item'), { shiftKey: true });
    fireEvent.click(node('er.entity.store'), { shiftKey: true });
    fireEvent.contextMenu(node('er.entity.store'));
    expect(menuItem('hide')).toHaveTextContent('Hide (2 cards)');
    fireEvent.click(menuItem('hide'));
    await waitFor(() => expect(attr('nodes')).toBe('er.entity.customer_address,er.entity.store_sales'));
  });

  it('a click, then a shift-click: both are drawn selected and the menu acts on both (ttr-core#180 #2)', async () => {
    await openEr();
    fireEvent.click(node('er.entity.item'));
    await waitFor(() => expect(attr('selected')).toBe('er.entity.item'));
    fireEvent.click(node('er.entity.store'), { shiftKey: true });
    await waitFor(() => expect(attr('selected')).toBe('er.entity.item,er.entity.store'));
    fireEvent.contextMenu(node('er.entity.store'));
    expect(menuItem('hide')).toHaveTextContent('Hide (2 cards)');
    // shift-click the first card again: it leaves the set and is no longer drawn selected
    fireEvent.click(node('er.entity.item'), { shiftKey: true });
    await waitFor(() => expect(attr('selected')).toBe('er.entity.store'));
    fireEvent.contextMenu(node('er.entity.store'));
    expect(menuItem('hide')).toHaveTextContent(/^Hide$/);
  });

  it('a tab switch clears the multi-selection — another tab never acts on its ids (ttr-core#180 #2)', async () => {
    await openEr();
    fireEvent.click(node('er.entity.item'), { shiftKey: true });
    fireEvent.click(node('er.entity.store'), { shiftKey: true });
    await waitFor(() => expect(attr('selected')).toBe('er.entity.item,er.entity.store'));
    fireEvent.click(screen.getByTestId('layout-new')); // a second tab with the same cards
    await waitFor(() => expect(screen.getAllByTestId('subject-tab')).toHaveLength(2));
    await waitFor(() => expect(attr('selected')).toBe(''));
    fireEvent.contextMenu(node('er.entity.store'));
    expect(menuItem('hide')).toHaveTextContent(/^Hide$/);
  });

  it('Inspect opens the card in the inspector', async () => {
    await openEr();
    fireEvent.contextMenu(node('er.entity.item'));
    fireEvent.click(menuItem('inspect'));
    expect(await screen.findByTestId('text-drawer')).toHaveTextContent('Product');
  });
});

describe('the view menu on a hop edge', () => {
  it('offers Show <H> per hidden card', async () => {
    await openEr();
    fireEvent.click(screen.getByTestId('objects-none'));
    toggle(ITEM);
    toggle(STORE);
    await waitFor(() => expect(screen.getAllByTestId('mock-hop')).toHaveLength(1));
    fireEvent.contextMenu(screen.getByTestId('mock-hop'));
    expect(within(screen.getByTestId('view-menu')).getAllByTestId('view-menu-item').map((b) => b.textContent)).toEqual(['Show Store Sales']);
    fireEvent.click(menuItem(`show:${SALES}`));
    await waitFor(() => expect(attr('hops')).toBe(''));
  });
});

describe('view + edit (FO-21) in one list', () => {
  const graph: ModelGraph = {
    schemaCode: 'er',
    nodes: [{ qname: 'er.A', kind: 'entity', name: 'A', schemaCode: 'er', label: 'A', sourceUri: '', sourceLocation: { line: 0, column: 0 }, rows: [] }],
    edges: [],
  };
  const pos = { 'er.A': { x: 0, y: 0 } };

  it('with an injected edit menu both halves render once, separated — view first', async () => {
    const view = vi.fn((_t, close: () => void) => <ViewMenu items={[{ id: 'expand', label: 'Expand', run: vi.fn() }]} onClose={close} />);
    const edit = vi.fn(() => <button data-testid="edit-item">Remove object</button>);
    render(<SkinnedCanvas graph={graph} displayMode="just-names" nodePositions={pos} canvasKey="g" onNodeSelect={vi.fn()} renderViewMenu={view} renderNodeMenu={edit} />);
    fireEvent.contextMenu(await screen.findByTestId('mock-node'));
    const menu = screen.getByTestId('node-context-menu');
    const parts = [...menu.children].map((c) => c.getAttribute('data-testid'));
    expect(parts).toEqual(['view-menu', 'menu-separator', 'edit-item']);
    expect(view).toHaveBeenCalledWith({ kind: 'node', id: 'er.A' }, expect.any(Function));
  });

  it('the open viewer (no edit menu): the view menu alone, no separator', async () => {
    render(<SkinnedCanvas graph={graph} displayMode="just-names" nodePositions={pos} canvasKey="g" onNodeSelect={vi.fn()}
      renderViewMenu={(_t, close) => <ViewMenu items={[{ id: 'hide', label: 'Hide', run: vi.fn() }]} onClose={close} />} />);
    fireEvent.contextMenu(await screen.findByTestId('mock-node'));
    expect([...screen.getByTestId('node-context-menu').children].map((c) => c.getAttribute('data-testid'))).toEqual(['view-menu']);
  });
});
