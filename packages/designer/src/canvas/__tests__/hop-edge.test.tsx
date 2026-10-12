// SPDX-License-Identifier: Apache-2.0
// VL-P1 T3 — `kind: 'hop'` through the stack (VL V-26): the mapper, both skins, the kernel edge, and
// the inspector's Show (the card is checked, the hop disappears on re-projection).

import { describe, it, expect, vi, afterEach } from 'vitest';
import type { ReactNode } from 'react';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';
import { Position, type EdgeProps } from '@xyflow/react';
import { modelGraphToCanvas, type CanvasEdge } from '@tatrman/canvas-core';
import type { GetGraphResponse } from '@tatrman/lsp';
import { canvas as palette } from '@tatrman/tokens';
import { erCrow, dbTableClassic } from '../../skins/index.js';
import { CanvasEdgeView } from '../edges.js';
import { HopEdgeInspector } from '../../shell/HopEdgeInspector.js';
import { ttrmToGetGraphResponse } from '../../data/structural-graph.js';
import type { TtrmGraph } from '../../data/ttrm-types.js';
import { layoutIndexOf, projectLayout } from '../../model/layout-projection.js';
import { emptyShell, openLayoutTab, setLayoutObjects, activeTab } from '../../shell/shell-state.js';
import erFixture from '../../data/__tests__/fixtures/veles/hartland-er-graph.json';

// EdgeLabelRenderer portals into the React Flow viewport; inline it so the label renders in jsdom.
vi.mock('@xyflow/react', async (orig) => ({
  ...(await orig<typeof import('@xyflow/react')>()),
  EdgeLabelRenderer: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

afterEach(() => cleanup());

const hopModelEdge = (via: string[]) => ({
  id: 'hop:a|b', qname: 'hop:a|b', kind: 'hop' as const, via, fromNode: 'a', toNode: 'b', fromCardinality: null, toCardinality: null,
});
const nodeIn = (id: string) => ({ qname: id, kind: 'entity' as const, name: id, label: id, schemaCode: 'er' as const, rows: [] });

describe('modelGraphToCanvas — a hop edge', () => {
  it('carries kind + via, labels `…` / `… ×n`, and has no cardinality', () => {
    const one = modelGraphToCanvas({ schemaCode: 'er', nodes: [nodeIn('a'), nodeIn('b')], edges: [hopModelEdge(['h'])] }).edges[0];
    expect(one).toMatchObject({ kind: 'hop', via: ['h'], label: '…' });
    expect(one.cardinality).toBeUndefined();
    const two = modelGraphToCanvas({ schemaCode: 'er', nodes: [nodeIn('a'), nodeIn('b')], edges: [hopModelEdge(['h', 'k'])] }).edges[0];
    expect(two.label).toBe('… ×2');
  });
});

describe('the er and db skins draw a hop edge dashed, without a marker', () => {
  const hop = { id: 'h', from: { node: 'a', port: 'a::out' }, to: { node: 'b', port: 'b::in' }, role: 'data', kind: 'hop' } as CanvasEdge;
  const real = { ...hop, kind: undefined } as CanvasEdge;
  it.each([['er.crow', erCrow], ['db.table-classic', dbTableClassic]])('%s', (_id, skin) => {
    const ctx = { skin: skin as never, theme: 'ice' as const };
    expect(skin.edgeStyle(hop, ctx)).toEqual({ stroke: palette.edgeHop, width: 1.6, dash: '6 5', marker: 'none' });
    expect(skin.edgeStyle(real, ctx).dash).toBeUndefined();
  });
});

function edgeProps(data: Record<string, unknown>, label: string): EdgeProps {
  return {
    id: 'hop:a|b', source: 'a', target: 'b', sourceX: 0, sourceY: 0, targetX: 200, targetY: 0,
    sourcePosition: Position.Right, targetPosition: Position.Left, label, data,
  } as unknown as EdgeProps;
}

describe('the kernel edge (edges.tsx) — kind hop', () => {
  it('a dashed path, the `… ×2` pill at the midpoint, no cardinality glyphs; the pill selects and opens the menu', () => {
    const onSelect = vi.fn();
    const onContextMenu = vi.fn();
    const style = { stroke: palette.edgeHop, width: 1.6, dash: '6 5', marker: 'none' as const };
    const { container } = render(
      <svg><CanvasEdgeView {...edgeProps({ style, theme: 'ice', kind: 'hop', viaCount: 2, onSelect, onContextMenu }, '… ×2')} /></svg>,
    );
    const pill = screen.getByTestId('hop-edge');
    expect(pill).toHaveTextContent('… ×2');
    expect(pill).toHaveAttribute('data-via-count', '2');
    expect(pill).toHaveClass('hop-edge');
    const path = container.querySelector('path.hop-edge-path') as SVGPathElement;
    expect(path.style.strokeDasharray).toBe('6 5');
    expect(screen.queryByTestId('edge-card-source')).toBeNull();
    fireEvent.click(pill);
    expect(onSelect).toHaveBeenCalledOnce();
    fireEvent.contextMenu(pill, { clientX: 10, clientY: 20 });
    expect(onContextMenu).toHaveBeenCalledWith(10, 20);
  });

  it('a real relation keeps its solid stroke and cardinality ends', () => {
    const style = { stroke: palette.edgeStroke, width: 1.6, marker: 'none' as const };
    render(<svg><CanvasEdgeView {...edgeProps({ style, theme: 'ice', cardinality: { from: '0..*', to: '1' } }, 'rel')} /></svg>);
    expect(screen.queryByTestId('hop-edge')).toBeNull();
    expect(screen.getByTestId('edge-card-source')).toHaveAttribute('data-card', '0..*');
  });
});

describe('the hop inspector — Show checks the hidden card, and the hop is gone', () => {
  const whole: GetGraphResponse = ttrmToGetGraphResponse('er', (erFixture as TtrmGraph).nodes, (erFixture as TtrmGraph).edges);
  const idx = layoutIndexOf(whole);

  it('names the hidden card and the two relations it joins; Show re-projects without the hop', () => {
    let s = openLayoutTab(emptyShell, {
      doc: { name: 'sales', model: 'er', objects: ['hartland.er.entity.item', 'hartland.er.entity.store'], positions: {} },
      source: 'catalog', dirty: false,
    });
    const before = projectLayout(whole, activeTab(s)!.layout!.doc, { hops: 1 }, idx);
    expect(before.hopEdges).toHaveLength(1);
    const onShow = vi.fn((fq: string) => {
      s = setLayoutObjects(s, s.activeTabId!, [...activeTab(s)!.layout!.doc.objects, fq]);
    });
    render(<HopEdgeInspector hop={before.hopEdges[0]} whole={whole} nodeOf={idx.nodeOf} onShow={onShow} onClose={vi.fn()} />);
    const via = screen.getByTestId('hop-via');
    expect(via).toHaveAttribute('data-qname', 'hartland.er.entity.store_sales');
    expect(within(via).getAllByTestId('hop-relation').map((r) => r.textContent)).toEqual([
      'rel_store_sales_item: Store Sales → Product',
      'rel_store_sales_store: Store Sales → Store',
    ]);
    fireEvent.click(within(via).getByTestId('hop-show'));
    expect(onShow).toHaveBeenCalledWith('hartland.er.entity.store_sales');
    const after = projectLayout(whole, activeTab(s)!.layout!.doc, { hops: 1 }, idx);
    expect(after.hopEdges).toEqual([]);
    expect(after.graph.edges.map((e) => e.id).sort()).toEqual(['er.entity.rel_store_sales_item', 'er.entity.rel_store_sales_store']);
  });
});
