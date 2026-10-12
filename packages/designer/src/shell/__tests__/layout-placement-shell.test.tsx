// SPDX-License-Identifier: Apache-2.0
// VL-P1 T5 — placement and Re-layout in the shell (VL contracts V-19, ⚑VL-11): in a manual layout a
// checked card goes beside its neighbour and nothing else moves; in an auto layout nothing is placed;
// Re-layout sets every shown card's position (manual).

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { ShellFrame } from '../ShellFrame.js';
import { fakeVeles, SCHEMA_CATALOG, attr } from './layout-harness.js';

vi.mock('../../canvas/Kernel.js', async () => (await import('./layout-harness.js')).kernelMock);

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

const row = (fq: string) => screen.getAllByTestId('objects-row').find((r) => r.getAttribute('data-qname') === fq)!;
const toggle = (fq: string) => fireEvent.click(within(row(fq)).getByTestId('objects-check'));
const positions = () => JSON.parse(attr('positions')) as Record<string, { x: number; y: number }>;
/** a drag reaches the shell after the canvas's 400 ms persist debounce */
const persisted = () => new Promise((r) => setTimeout(r, 450));
const ITEM = 'hartland.er.entity.item';
const STORE = 'hartland.er.entity.store';
const SALES = 'hartland.er.entity.store_sales';

async function layoutWithItemOnly() {
  render(<ShellFrame dataSource={fakeVeles().source} workspace="veles" catalog={SCHEMA_CATALOG} files={[]} displayMode="just-names" />);
  fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
  await screen.findByTestId('objects-panel');
  fireEvent.click(screen.getByTestId('objects-none'));
  toggle(ITEM);
  await waitFor(() => expect(attr('nodes')).toBe('er.entity.item'));
}

describe('placement (V-19)', () => {
  it('an auto layout places nothing — the canvas lays the cards out', async () => {
    await layoutWithItemOnly();
    toggle(SALES);
    await waitFor(() => expect(positions()['er.entity.store_sales']).toBeDefined()); // ELK's, not a stored position
  });

  it('a manual layout: the checked card goes beside its neighbour, nothing else moves; the next one takes another slot', async () => {
    await layoutWithItemOnly();
    fireEvent.click(screen.getByTestId('mock-drag')); // item → (777, 333), mode manual
    await waitFor(() => expect(positions()['er.entity.item']).toEqual({ x: 777, y: 333 }), { timeout: 2000 });
    await persisted();
    toggle(SALES);
    await waitFor(() => expect(attr('nodes')).toBe('er.entity.item,er.entity.store_sales'));
    expect(positions()['er.entity.item']).toEqual({ x: 777, y: 333 });
    expect(positions()['er.entity.store_sales']).toEqual({ x: 777 + 200 + 40, y: 333 }); // the right-hand slot
    toggle(STORE); // store's only neighbour is store_sales
    await waitFor(() => expect(attr('nodes').split(',')).toHaveLength(3));
    const p = positions();
    expect(p['er.entity.item']).toEqual({ x: 777, y: 333 });
    expect(p['er.entity.store_sales']).toEqual({ x: 1017, y: 333 });
    expect(p['er.entity.store']).toEqual({ x: 1017 + 200 + 40, y: 333 });
  });

  it('unchecking drops the card’s position', async () => {
    await layoutWithItemOnly();
    fireEvent.click(screen.getByTestId('mock-drag'));
    await waitFor(() => expect(positions()['er.entity.item']).toEqual({ x: 777, y: 333 }), { timeout: 2000 });
    await persisted();
    toggle(SALES);
    await waitFor(() => expect(positions()['er.entity.store_sales']).toBeDefined());
    toggle(SALES);
    await waitFor(() => expect(attr('nodes')).toBe('er.entity.item'));
    toggle(SALES); // placed afresh, at the same free slot
    await waitFor(() => expect(positions()['er.entity.store_sales']).toEqual({ x: 1017, y: 333 }));
  });
});

describe('Re-layout (V-19)', () => {
  it('runs the auto layout over the cards shown and keeps the result as manual positions', async () => {
    await layoutWithItemOnly();
    toggle(SALES);
    toggle(STORE);
    await waitFor(() => expect(attr('nodes').split(',')).toHaveLength(3));
    fireEvent.click(screen.getByTestId('mock-drag'));
    await waitFor(() => expect(positions()['er.entity.item']).toEqual({ x: 777, y: 333 }), { timeout: 2000 });
    await persisted();
    fireEvent.click(screen.getByTestId('layout-relayout'));
    await waitFor(() => expect(positions()['er.entity.item']).not.toEqual({ x: 777, y: 333 }));
    expect(Object.keys(positions()).sort()).toEqual(['er.entity.item', 'er.entity.store', 'er.entity.store_sales']);
    expect(screen.getByTestId('truth-chip')).toHaveTextContent('mode=manual');
  });
});
