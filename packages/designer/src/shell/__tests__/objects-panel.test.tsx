// SPDX-License-Identifier: Apache-2.0
// VL-P1 T4 — the Objects panel (VL contracts V-18…V-21), as a component: groups, tri-state package
// checkboxes, search with All / None on the result, Missing with Remove.

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';
import { ttrmToGetGraphResponse } from '../../data/structural-graph.js';
import type { TtrmNode } from '../../data/ttrm-types.js';
import { layoutIndexOf } from '../../model/layout-projection.js';
import { ObjectsPanel, type ObjectsPanelProps } from '../ObjectsPanel.js';

afterEach(() => cleanup());

const entity = (pkg: string, name: string, label?: string): TtrmNode =>
  ({ qname: `er.entity.${name}`, kind: 'entity', label: name, schema: 'er', pkg, ...(label ? { displayLabel: { en: label } } : {}) });
const relation = (pkg: string, name: string, from: string, to: string): TtrmNode =>
  ({ qname: `er.entity.${name}`, kind: 'relation', label: name, schema: 'er', pkg, from: `er.entity.${from}`, to: `er.entity.${to}` });

// two packages + a card without one; the web package's `order` joins two store cards
const whole = ttrmToGetGraphResponse('er', [
  entity('hartland.store', 'item', 'Product'),
  entity('hartland.store', 'store', 'Store'),
  entity('hartland.store', 'store_sales', 'Store Sales'),
  entity('hartland.web', 'web_sales', 'Web Sales'),
  entity('hartland.web', 'web_page'),
  entity('', 'loose'),
  relation('hartland.store', 'rel_ss_item', 'store_sales', 'item'),
  relation('hartland.store', 'rel_ss_store', 'store_sales', 'store'),
  relation('hartland.web', 'rel_ws_item', 'web_sales', 'item'),
], []);
const index = layoutIndexOf(whole);
const FQ = {
  item: 'hartland.store.er.entity.item', store: 'hartland.store.er.entity.store', storeSales: 'hartland.store.er.entity.store_sales',
  webSales: 'hartland.web.er.entity.web_sales', webPage: 'hartland.web.er.entity.web_page', loose: 'er.entity.loose',
};

function panel(shown: string[], over: Partial<ObjectsPanelProps> = {}) {
  const props: ObjectsPanelProps = {
    whole, index, shown: new Set(shown), missing: [],
    onSetCards: vi.fn(), onNeighbours: vi.fn(), onRemoveMissing: vi.fn(), onExpand: vi.fn(), onHide: vi.fn(), ...over,
  };
  render(<ObjectsPanel {...props} />);
  return props;
}
const group = (pkg: string) => screen.getAllByTestId('objects-group').find((g) => g.getAttribute('data-pkg') === pkg)!;

describe('ObjectsPanel — contents (V-18)', () => {
  it('groups by package, (no package) last; rows by label; label · identifier · relation count', () => {
    panel([FQ.item, FQ.store]);
    expect(screen.getAllByTestId('objects-group').map((g) => g.getAttribute('data-pkg'))).toEqual(['hartland.store', 'hartland.web', '(no package)']);
    const rows = within(group('hartland.store')).getAllByTestId('objects-row');
    expect(rows.map((r) => within(r).getByTestId('objects-label').textContent)).toEqual(['Product', 'Store', 'Store Sales']);
    expect(within(rows[0]).getByTestId('objects-identifier')).toHaveTextContent('item');
    expect(within(rows[0]).getByTestId('objects-degree')).toHaveTextContent('2');
    // a card whose label IS its identifier shows it once
    const page = within(group('hartland.web')).getAllByTestId('objects-row').find((r) => r.getAttribute('data-qname') === FQ.webPage)!;
    expect(within(page).queryByTestId('objects-identifier')).toBeNull();
    expect(screen.getByTestId('objects-count')).toHaveTextContent('2 / 6');
  });

  it('package counts k / n and the tri-state checkbox', () => {
    panel([FQ.item, FQ.store]);
    expect(within(group('hartland.store')).getByTestId('objects-group-count')).toHaveTextContent('2 / 3');
    expect(within(group('hartland.store')).getByTestId('objects-group-check')).toHaveAttribute('data-state', 'mixed');
    expect(within(group('hartland.web')).getByTestId('objects-group-check')).toHaveAttribute('data-state', 'off');
    cleanup();
    panel([FQ.item, FQ.store, FQ.storeSales]);
    expect(within(group('hartland.store')).getByTestId('objects-group-check')).toHaveAttribute('data-state', 'on');
  });
});

describe('ObjectsPanel — edits (V-19, V-20)', () => {
  it('a row checkbox shows / hides one card, keeping the others', () => {
    const p = panel([FQ.item]);
    fireEvent.click(within(group('hartland.store')).getAllByTestId('objects-check')[1]); // Store
    expect(p.onSetCards).toHaveBeenLastCalledWith([FQ.item, FQ.store]);
    fireEvent.click(within(group('hartland.store')).getAllByTestId('objects-check')[0]); // Product off
    expect(p.onSetCards).toHaveBeenLastCalledWith([]);
  });

  it('the package checkbox: mixed → all, all → none', () => {
    const p = panel([FQ.item, FQ.webSales]);
    fireEvent.click(within(group('hartland.store')).getByTestId('objects-group-check'));
    expect(p.onSetCards).toHaveBeenLastCalledWith([FQ.item, FQ.store, FQ.storeSales, FQ.webSales]);
    cleanup();
    const q = panel([FQ.item, FQ.store, FQ.storeSales]);
    fireEvent.click(within(group('hartland.store')).getByTestId('objects-group-check'));
    expect(q.onSetCards).toHaveBeenLastCalledWith([]);
  });

  it('search narrows on label, identifier, qname and package; All / None act on the result; Esc clears', () => {
    const p = panel([FQ.webPage]);
    const search = screen.getByTestId('objects-search');
    fireEvent.change(search, { target: { value: 'sales' } });
    expect(screen.getAllByTestId('objects-row').map((r) => r.getAttribute('data-qname'))).toEqual([FQ.storeSales, FQ.webSales]);
    fireEvent.click(screen.getByTestId('objects-all'));
    expect(p.onSetCards).toHaveBeenLastCalledWith([FQ.storeSales, FQ.webSales, FQ.webPage]);
    fireEvent.change(search, { target: { value: 'hartland.web' } }); // the package
    expect(screen.getAllByTestId('objects-row')).toHaveLength(2);
    fireEvent.click(screen.getByTestId('objects-none'));
    expect(p.onSetCards).toHaveBeenLastCalledWith([]);
    fireEvent.keyDown(search, { key: 'Escape' });
    expect(screen.getAllByTestId('objects-row')).toHaveLength(6);
  });

  it('Invert swaps within the schema; Neighbours / Expand / Hide go to the shell', () => {
    const p = panel([FQ.item, FQ.loose]);
    fireEvent.click(screen.getByTestId('objects-invert'));
    expect(p.onSetCards).toHaveBeenLastCalledWith([FQ.store, FQ.storeSales, FQ.webSales, FQ.webPage]);
    fireEvent.click(screen.getByTestId('objects-neighbours'));
    expect(p.onNeighbours).toHaveBeenCalledOnce();
    const product = screen.getAllByTestId('objects-row').find((r) => r.getAttribute('data-qname') === FQ.item)!;
    fireEvent.click(within(product).getByTestId('objects-expand'));
    expect(p.onExpand).toHaveBeenCalledWith(FQ.item);
    fireEvent.click(within(product).getByTestId('objects-hide'));
    expect(p.onHide).toHaveBeenCalledWith(FQ.item);
  });
});

describe('ObjectsPanel — Missing (V-17)', () => {
  it('lists the refs the model lacks on top, unchecked and struck through, each with Remove', () => {
    const p = panel([FQ.item], { missing: [{ ref: 'Customer Address' }, { ref: 'item', candidates: [FQ.item, 'other.er.entity.item'] }] });
    const missing = screen.getByTestId('objects-missing');
    expect(missing).toHaveTextContent('Missing 2');
    const rows = within(missing).getAllByTestId('objects-missing-row');
    expect(rows.map((r) => r.getAttribute('data-ref'))).toEqual(['Customer Address', 'item']);
    const box = within(rows[0]).getByRole('checkbox');
    expect(box).not.toBeChecked();
    expect(box).toBeDisabled();
    expect(within(rows[0]).getByText('Customer Address')).toHaveStyle({ textDecoration: 'line-through' });
    expect(within(rows[1]).getByText('ambiguous')).toBeInTheDocument();
    fireEvent.click(within(rows[0]).getByTestId('objects-remove'));
    expect(p.onRemoveMissing).toHaveBeenCalledWith('Customer Address');
  });
});
