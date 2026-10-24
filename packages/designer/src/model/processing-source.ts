// SPDX-License-Identifier: Apache-2.0
// The fixture ProcessingGraphSource (DM-P4.S1, ported from modeler designer/src/model/processing-source.ts;
// contracts §5 — designer-side adapter target). Serves the hero `monthly_sales` orchestration +
// container drill-ins from an in-repo fixture. This is a v1 deliverable, not a stopgap: it is also
// what component tests run on. The LIVE impl (`TtrpServerProcessingSource`, DM-P4.S4) plugs in behind
// the SAME interface — the canvas never sees the transport. `@modeler/canvas-core` → `@tatrman/canvas-core`.

import type { ProcessingGraph } from '@tatrman/canvas-core';

export interface ProcessingGraphSource {
  getRecipeSummary?(programRef: string): Promise<import('./ttrp/types.js').RecipeSummary | null>;
  getProgramGraph(programRef: string): Promise<ProcessingGraph>;
  getContainerGraph(containerRef: string): Promise<ProcessingGraph>;
}

// ---- hero orchestration (program level) ----
const ORCHESTRATION: ProcessingGraph = {
  id: 'monthly_sales',
  face: 'processing',
  nodes: [
    {
      id: 'extract', qname: 'monthly_sales.extract', kind: 'container', label: 'extract',
      engine: 'sql @ mssql', fragmentDerived: true, collapsed: true,
      bodyText: '"""sql\n  select o.OrderKey, o.CustomerKey, o.DateKey, l.ProductKey, l.Quantity, l.NetAmount\n  from dbo.OrderHeader o join dbo.OrderLine l on l.OrderKey = o.OrderKey',
      ports: [
        { id: 'extract.orders', direction: 'out', role: 'data', connected: true, label: 'orders' },
        { id: 'extract.lines', direction: 'out', role: 'data', connected: true, label: 'lines' },
      ],
    },
    {
      id: 'crunch', qname: 'monthly_sales.crunch', kind: 'container', label: 'crunch',
      engine: 'polars', collapsed: true,
      bodyText: 'join orders+lines, filter cancelled, aggregate net_amount & qty by month',
      ports: [
        { id: 'crunch.in_orders', direction: 'in', role: 'data', connected: true, label: 'orders' },
        { id: 'crunch.in_lines', direction: 'in', role: 'data', connected: true, label: 'lines' },
        { id: 'crunch.out', direction: 'out', role: 'data', connected: true, label: 'monthly_sales' },
        { id: 'crunch.rejects', direction: 'out', role: 'rejects', connected: false, label: 'rejects ∅' },
      ],
    },
    {
      id: 'store', qname: 'monthly_sales.store', kind: 'store', label: 'store monthly_sales',
      bodyText: 'materialize to warehouse (out/monthly_sales.arrow)',
      ports: [{ id: 'store.in', direction: 'in', role: 'data', connected: true }],
    },
    {
      id: 'display', qname: 'monthly_sales.display', kind: 'display', label: 'display top_customers',
      bodyText: 'display sink (out/top_customers.arrow)',
      slotData: { previewRows: 5, sinkRef: 'top_customers' },
      ports: [{ id: 'display.in', direction: 'in', role: 'data', connected: true }],
    },
  ],
  edges: [
    { id: 'e_transfer', from: 'extract', to: 'crunch', role: 'transfer', label: '⇄ transfer' },
    { id: 'e_store', from: 'crunch', to: 'store', role: 'data' },
    { id: 'e_display', from: 'crunch', to: 'display', role: 'data' },
    { id: 'e_after', from: 'store', to: 'display', role: 'control', label: 'after' },
  ],
};

// ---- crunch drill-in: the polars op-graph (join → filter → aggregate) ----
const CRUNCH_OPS: ProcessingGraph = {
  id: 'monthly_sales.crunch',
  face: 'processing',
  nodes: [
    {
      id: 'join', qname: 'monthly_sales.crunch.join', kind: 'op', label: 'join',
      bodyText: 'join lines to orders on OrderKey',
      ports: [
        { id: 'join.in_orders', direction: 'in', role: 'data', connected: true, label: 'orders' },
        { id: 'join.in_lines', direction: 'in', role: 'data', connected: true, label: 'lines' },
        { id: 'join.out', direction: 'out', role: 'data', connected: true, label: 'joined' },
      ],
    },
    {
      id: 'filter', qname: 'monthly_sales.crunch.filter', kind: 'op', label: 'filter cancelled',
      bodyText: 'drop rows where status = cancelled',
      ports: [
        { id: 'filter.in', direction: 'in', role: 'data', connected: true },
        { id: 'filter.out', direction: 'out', role: 'data', connected: true, label: 'kept' },
        { id: 'filter.rejects', direction: 'out', role: 'rejects', connected: false, label: 'rejects ∅' },
      ],
    },
    {
      id: 'aggregate', qname: 'monthly_sales.crunch.aggregate', kind: 'op', label: 'aggregate by month',
      bodyText: 'sum net_amount, qty grouped by month',
      ports: [
        { id: 'aggregate.in', direction: 'in', role: 'data', connected: true },
        { id: 'aggregate.out', direction: 'out', role: 'data', connected: true, label: 'monthly_sales' },
      ],
    },
  ],
  edges: [
    { id: 'c_join_filter', from: 'join', to: 'filter', role: 'data' },
    { id: 'c_filter_agg', from: 'filter', to: 'aggregate', role: 'data' },
  ],
};

// ---- extract drill-in: the `"""sql · derived view (read-only + banner) ----
const EXTRACT_DERIVED: ProcessingGraph = {
  id: 'monthly_sales.extract',
  face: 'processing',
  derived: true,
  nodes: [
    {
      id: 'extract.sql', qname: 'monthly_sales.extract.sql', kind: 'op', label: 'sql · derived view',
      engine: 'sql @ mssql', fragmentDerived: true,
      bodyText: 'select o.OrderKey, o.CustomerKey, o.DateKey, l.ProductKey, l.Quantity, l.NetAmount\nfrom dbo.OrderHeader o join dbo.OrderLine l on l.OrderKey = o.OrderKey',
      ports: [
        { id: 'extract.sql.orders', direction: 'out', role: 'data', connected: true, label: 'orders' },
        { id: 'extract.sql.lines', direction: 'out', role: 'data', connected: true, label: 'lines' },
      ],
    },
  ],
  edges: [],
};

const CONTAINER_GRAPHS: Record<string, ProcessingGraph> = {
  crunch: CRUNCH_OPS,
  extract: EXTRACT_DERIVED,
};

const empty = (id: string): ProcessingGraph => ({ id, face: 'processing', nodes: [], edges: [] });

// deep clone so callers can't mutate the module-level fixtures.
const clone = <T>(g: T): T => JSON.parse(JSON.stringify(g)) as T;

export function fixtureProcessingSource(): ProcessingGraphSource {
  return {
    async getProgramGraph(programRef: string): Promise<ProcessingGraph> {
      return programRef === 'monthly_sales' ? clone(ORCHESTRATION) : empty(programRef);
    },
    async getContainerGraph(containerRef: string): Promise<ProcessingGraph> {
      const key = containerRef.split('.').pop() ?? containerRef; // accept 'crunch' or 'monthly_sales.crunch'
      return CONTAINER_GRAPHS[key] ? clone(CONTAINER_GRAPHS[key]) : empty(containerRef);
    },
  };
}
