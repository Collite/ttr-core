// SPDX-License-Identifier: Apache-2.0
// VL-P1 T4 — the Objects panel in the shell (V-18…V-22) over a catalog (the Hartland er graph, as Veles
// serves it): the whole schema turns into `er · untitled` on its first edit, hop edges appear through
// hidden cards, Neighbours adds exactly the frontier, and the hop inspector's Show checks the card.

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
const ITEM = 'hartland.er.entity.item';
const STORE = 'hartland.er.entity.store';
const SALES = 'hartland.er.entity.store_sales';
const ADDR = 'hartland.er.entity.customer_address';

async function openEr(notices: string[] = []) {
  render(<ShellFrame dataSource={fakeVeles().source} workspace="veles" catalog={SCHEMA_CATALOG} files={[]} displayMode="just-names" onNotice={(m) => notices.push(m)} />);
  fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
  await screen.findByTestId('objects-panel');
  await screen.findByTestId('mock-kernel');
}

describe('the Objects panel in the shell', () => {
  it('the whole schema: every card listed and checked, no layout yet (V-22)', async () => {
    await openEr();
    expect(screen.getByTestId('objects-count')).toHaveTextContent('4 / 4');
    expect(screen.getAllByTestId('objects-group').map((g) => g.getAttribute('data-pkg'))).toEqual(['hartland']);
    expect(screen.getAllByTestId('subject-tab').map((t) => t.textContent)).toEqual(['er📌×']);
    expect(attr('nodes').split(',')).toHaveLength(4);
  });

  it('its first selection edit turns the tab into er · untitled, and hop edges appear through the hidden card', async () => {
    await openEr();
    toggle(SALES);
    await waitFor(() => expect(screen.getAllByTestId('subject-tab')[0]).toHaveTextContent('er · untitled'));
    expect(screen.getByTestId('objects-count')).toHaveTextContent('3 / 4');
    await waitFor(() => expect(attr('nodes')).toBe('er.entity.customer_address,er.entity.item,er.entity.store'));
    expect(attr('edges')).toBe('');
    expect(attr('hops')).toBe([
      `er.entity.customer_address|er.entity.item|${SALES}`,
      `er.entity.customer_address|er.entity.store|${SALES}`,
      `er.entity.item|er.entity.store|${SALES}`,
    ].join(','));
  });

  it('Neighbours adds exactly the hidden cards one relation away', async () => {
    const notices: string[] = [];
    await openEr(notices);
    fireEvent.click(screen.getByTestId('objects-none'));
    await waitFor(() => expect(screen.getByTestId('objects-count')).toHaveTextContent('0 / 4'));
    toggle(ITEM);
    await waitFor(() => expect(attr('nodes')).toBe('er.entity.item'));
    fireEvent.click(screen.getByTestId('objects-neighbours'));
    await waitFor(() => expect(attr('nodes')).toBe('er.entity.item,er.entity.store_sales'));
    expect(notices).toContain('Expanded: +1');
    fireEvent.click(screen.getByTestId('objects-neighbours'));
    await waitFor(() => expect(attr('nodes').split(',')).toHaveLength(4));
    fireEvent.click(screen.getByTestId('objects-neighbours'));
    expect(notices.at(-1)).toBe('Every neighbour is already visible');
  });

  it('the hop inspector: Show checks the hidden card and the hop is gone', async () => {
    await openEr();
    fireEvent.click(screen.getByTestId('objects-none'));
    await waitFor(() => expect(screen.getByTestId('objects-count')).toHaveTextContent('0 / 4'));
    toggle(ITEM);
    toggle(STORE);
    await waitFor(() => expect(screen.getAllByTestId('mock-hop')).toHaveLength(1));
    fireEvent.click(screen.getByTestId('mock-hop'));
    const inspector = await screen.findByTestId('hop-inspector');
    expect(within(inspector).getByTestId('hop-via')).toHaveAttribute('data-qname', SALES);
    fireEvent.click(within(inspector).getByTestId('hop-show'));
    await waitFor(() => expect(attr('hops')).toBe(''));
    expect(attr('edges')).toBe('er.entity.rel_store_sales_item,er.entity.rel_store_sales_store');
    expect(screen.queryByTestId('hop-inspector')).toBeNull();
    expect(within(row(ADDR)).getByTestId('objects-check')).not.toBeChecked();
  });
});
