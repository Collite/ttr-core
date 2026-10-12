// SPDX-License-Identifier: Apache-2.0
// VL-P1 shell-test harness: the real VelesReadApiDataSource over a fake fetch serving the Hartland
// catalog fixtures, and a kernel stand-in that exposes what the canvas would draw (React Flow does not
// draw edges in jsdom). Import `kernelMock` into a `vi.mock('../../canvas/Kernel.js', …)` factory.

import { screen } from '@testing-library/react';
import type { KernelProps } from '../../canvas/Kernel.js';
import { VelesReadApiDataSource } from '../../data/veles-read-api-data-source.js';
import erFixture from '../../data/__tests__/fixtures/veles/hartland-er-graph.json';
import dbFixture from '../../data/__tests__/fixtures/veles/hartland-db-graph.json';
import type { CatalogGroup } from '../types.js';

/** The kernel, flattened to data attributes + the gestures a test drives (drag, right-click, select). */
export function KernelStandIn(p: KernelProps) {
  const real = p.graph.edges.filter((e) => e.kind !== 'hop');
  const hops = p.graph.edges.filter((e) => e.kind === 'hop');
  return (
    <div
      data-testid="mock-kernel"
      data-nodes={p.graph.nodes.map((n) => n.id).sort().join(',')}
      data-edges={real.map((e) => e.id).sort().join(',')}
      data-hops={hops.map((e) => `${e.from.node}|${e.to.node}|${(e.via ?? []).join('+')}`).sort().join(',')}
      data-positions={JSON.stringify(p.positions)}
    >
      {p.graph.nodes.map((n) => (
        <button
          key={n.id}
          data-testid="mock-node"
          data-id={n.id}
          onClick={(e) => p.onSelect?.(n.id, e.shiftKey)}
          onContextMenu={(e) => { e.preventDefault(); p.onNodeContextMenu?.(n.id, 100, 100); }}
        >
          {n.label}
        </button>
      ))}
      {hops.map((e) => (
        <button key={e.id} data-testid="mock-hop" data-id={e.id} onClick={() => p.onEdgeSelect?.(e.id)}
          onContextMenu={(ev) => { ev.preventDefault(); p.onEdgeContextMenu?.(e.id, 100, 100); }}>
          {e.label}
        </button>
      ))}
      <button data-testid="mock-drag" onClick={() => {
        const id = p.graph.nodes[0]?.id;
        if (id) p.onNodeDrag?.(id, { x: 777, y: 333 });
      }}>drag</button>
    </div>
  );
}

export const kernelMock = { CanvasKernel: KernelStandIn };

/** A Veles that serves the Hartland er + db graphs (and whatever `extra` routes a test adds). */
export function fakeVeles(extra: Record<string, unknown> = {}, lang = 'en') {
  const calls: string[] = [];
  const fetchImpl = (async (input: string | URL | Request) => {
    const url = String(input);
    calls.push(url);
    const path = url.replace(/^\/veles/, '');
    if (path === '/model/graph?schema=er') return new Response(JSON.stringify(erFixture), { status: 200 });
    if (path === '/model/graph?schema=db') return new Response(JSON.stringify(dbFixture), { status: 200 });
    if (path in extra) {
      const body = extra[path];
      if (body instanceof Response) return body;
      return new Response(JSON.stringify(body), { status: 200 });
    }
    return new Response('{"error":"not found"}', { status: 404 });
  }) as typeof fetch;
  return { source: new VelesReadApiDataSource('/veles', { fetchImpl, lang }), calls };
}

export const SCHEMA_CATALOG: CatalogGroup[] = [
  {
    kind: 'schema',
    label: 'Schemas',
    items: [
      { ref: 'db', qname: 'db', kind: 'schema', schemaCode: 'db', label: 'db' },
      { ref: 'er', qname: 'er', kind: 'schema', schemaCode: 'er', label: 'er' },
    ],
  },
];

export const kernel = () => screen.getByTestId('mock-kernel');
export const attr = (name: string) => kernel().getAttribute(`data-${name}`) ?? '';
