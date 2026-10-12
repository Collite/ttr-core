// SPDX-License-Identifier: Apache-2.0
// VL-P1 T9 — the spine, the address and the data seam (VL contracts V-34…V-36): both Veles sources read
// GET /model/layouts and /model/layout?name=; the spine lists the schemas, then the served layouts by model
// then name with `· n missing`; opening one re-resolves and projects it; a 404 is a toast, not a blank tab;
// a catalog layout is addressed by `layout:<name>` (a shareable link), a local file by `file:` (no link).

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { ShellFrame } from '../ShellFrame.js';
import { buildCatalog } from '../catalog.js';
import { formatPath, parsePath } from '../url.js';
import { VelesTtrmDataSource } from '../../data/veles-ttrm-data-source.js';
import layouts from '../../data/__tests__/fixtures/veles/layouts.json';
import positionsLayout from '../../data/__tests__/fixtures/veles/layout-positions.json';
import dbLayout from '../../data/__tests__/fixtures/veles/layout-db_tables.json';
import { fakeVeles, attr } from './layout-harness.js';

vi.mock('../../canvas/Kernel.js', async () => (await import('./layout-harness.js')).kernelMock);

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

const ROUTES = {
  '/model/index': { packages: ['hartland'], schemas: ['db', 'er'], areas: [], counts: { objects: 0, schemas: 2, areas: 0 }, modelVersion: 'hartland-fixture' },
  '/model/layouts': layouts,
  '/model/layout?name=positions': positionsLayout,
  '/model/layout?name=db_tables': dbLayout,
};

async function mountCatalog(extra: Record<string, unknown> = ROUTES, errors: string[] = []) {
  const { source, calls } = fakeVeles(extra);
  const { graphs, symbols } = await source.listCatalog();
  render(<ShellFrame dataSource={source} workspace="veles" catalog={buildCatalog(graphs, symbols)} files={[]} displayMode="just-names" onError={(m) => errors.push(m)} />);
  return { calls, errors };
}
const spine = () => screen.getAllByTestId('catalog-item').map((b) => b.textContent);
const item = (label: string) => screen.getAllByTestId('catalog-item').find((b) => b.textContent === label)!;

describe('the data seam (V-36)', () => {
  it('VelesReadApiDataSource reads the two routes; listCatalog lists schemas, then layouts', async () => {
    const { source, calls } = fakeVeles(ROUTES);
    expect((await source.listLayouts()).map((l) => l.name)).toEqual(['db_tables', 'positions']);
    expect((await source.getLayoutDoc('positions')).missing).toEqual(['hartland.er.entity.customer']);
    expect(calls).toContain('/veles/model/layout?name=positions');
    const { graphs } = await source.listCatalog();
    expect(graphs).toEqual([
      { uri: 'db', name: 'db', schema: 'db' },
      { uri: 'er', name: 'er', schema: 'er' },
      { uri: 'db_tables', name: 'db_tables', schema: 'db', layout: { missingCount: 2 } },
      { uri: 'positions', name: 'positions', schema: 'er', layout: { missingCount: 1 } },
    ]);
  });

  it('a catalog without the layout routes (an older Veles, the proxy’s 404) lists no layouts — the schemas still open', async () => {
    const { source } = fakeVeles({ '/model/index': ROUTES['/model/index'] });
    expect((await source.listCatalog()).graphs.map((g) => g.uri)).toEqual(['db', 'er']);
  });

  it('VelesTtrmDataSource reads the same routes over http(s) on its host, with the bearer', async () => {
    const seen: Array<[string, RequestInit | undefined]> = [];
    const fetchImpl = (async (input: string | URL | Request, init?: RequestInit) => {
      seen.push([String(input), init]);
      return new Response(JSON.stringify(layouts), { status: 200 });
    }) as typeof fetch;
    const v = new VelesTtrmDataSource('wss://veles.example/', { token: 't0k', fetchImpl });
    expect((await v.listLayouts()).map((l) => l.name)).toEqual(['db_tables', 'positions']);
    expect(seen[0][0]).toBe('https://veles.example/model/layouts');
    expect((seen[0][1]?.headers as Record<string, string>).Authorization).toBe('Bearer t0k');
  });
});

describe('the spine (V-34)', () => {
  it('lists db, er, then the served layouts by model then name, with · n missing', async () => {
    await mountCatalog();
    expect(spine()).toEqual(['db', 'er', 'db · db_tables · 2 missing', 'er · positions · 1 missing']);
  });

  it('opening a served layout re-resolves and projects it — its cards, positions and missing refs', async () => {
    await mountCatalog();
    fireEvent.click(item('er · positions · 1 missing'));
    await waitFor(() => expect(screen.getAllByTestId('subject-tab').map((t) => t.textContent)).toEqual(['er · positions×']));
    await waitFor(() => expect(attr('nodes')).toBe('er.entity.item,er.entity.store,er.entity.store_sales'));
    expect(JSON.parse(attr('positions'))).toEqual({
      'er.entity.item': { x: 320, y: 180 }, 'er.entity.store': { x: 580, y: 180 }, 'er.entity.store_sales': { x: 320, y: 400 },
    });
    const missing = await screen.findByTestId('objects-missing');
    expect(within(missing).getAllByTestId('objects-missing-row').map((r) => r.getAttribute('data-ref'))).toEqual(['hartland.er.entity.customer']);
  });

  it('a db layout too — the foreign key it listed is not a card', async () => {
    await mountCatalog();
    fireEvent.click(item('db · db_tables · 2 missing'));
    await waitFor(() => expect(attr('nodes')).toBe('db.dbo.item,db.dbo.store_sales'));
    expect(attr('edges')).toBe('db.dbo.fk_ss_item');
  });

  it('a layout the catalog no longer has (404) is a toast — no blank tab', async () => {
    const errors: string[] = [];
    const rest: Record<string, unknown> = { ...ROUTES };
    delete rest['/model/layout?name=positions'];
    await mountCatalog(rest, errors);
    fireEvent.click(item('er · positions · 1 missing'));
    await waitFor(() => expect(errors).toHaveLength(1));
    expect(errors[0]).toMatch(/^Could not open the layout positions: Veles \/model\/layout\?name=positions → 404/);
    expect(screen.queryByTestId('subject-tab')).toBeNull();
  });

  it('a served layout short of the §6 shape is a toast too — not a silent nothing', async () => {
    const errors: string[] = [];
    const short: Record<string, unknown> = { ...positionsLayout };
    delete short.ambiguous;
    await mountCatalog({ ...ROUTES, '/model/layout?name=positions': short }, errors);
    fireEvent.click(item('er · positions · 1 missing'));
    await waitFor(() => expect(errors).toHaveLength(1));
    expect(errors[0]).toMatch(/^Could not open the layout positions: /);
    expect(screen.queryByTestId('subject-tab')).toBeNull();
  });

  it('a schema read that fails is not kept — opening the layout again reads it again', async () => {
    const errors: string[] = [];
    const { source } = fakeVeles(ROUTES);
    const read = source.getSchemaGraph.bind(source);
    let fail = true;
    source.getSchemaGraph = (code) => {
      if (!fail) return read(code);
      fail = false;
      return Promise.reject(new Error('502'));
    };
    const { graphs, symbols } = await source.listCatalog();
    render(<ShellFrame dataSource={source} workspace="veles" catalog={buildCatalog(graphs, symbols)} files={[]} displayMode="just-names" onError={(m) => errors.push(m)} />);
    fireEvent.click(item('db · db_tables · 2 missing'));
    await waitFor(() => expect(errors).toEqual([
      'Failed to read the db schema: Error: 502',
      'Could not open the layout db_tables: the db schema could not be read.',
    ]));
    expect(screen.queryByTestId('subject-tab')).toBeNull();
    fireEvent.click(item('db · db_tables · 2 missing'));
    await waitFor(() => expect(attr('nodes')).toBe('db.dbo.item,db.dbo.store_sales'));
    expect(errors).toHaveLength(2);
  });
});

describe('the address (V-35)', () => {
  it('a catalog layout’s ref is layout:<name> — the URL round-trips and a shared link opens it', async () => {
    const ref = 'layout:positions';
    expect(parsePath(formatPath({ kind: 'subject', workspace: 'veles', subjectRef: ref }))).toEqual({ kind: 'subject', workspace: 'veles', subjectRef: ref });
    window.history.replaceState(null, '', '/w/veles/s/layout%3Apositions');
    await mountCatalog();
    await waitFor(() => expect(screen.getAllByTestId('subject-tab').map((t) => t.textContent)).toEqual(['er · positions×']));
    await waitFor(() => expect(window.location.pathname).toBe('/w/veles/s/layout%3Apositions')); // the address follows the tab in an effect
    expect(screen.getByTestId('copy-federation-link')).toBeEnabled();
  });

  it('a layout named like a schema (`er`) is its own subject — the schema still opens, and its link opens the schema (A-VL-4)', async () => {
    const named = {
      ...ROUTES,
      '/model/layouts': { ...layouts, layouts: [...layouts.layouts, { ...layouts.layouts[1], name: 'er', source: 'layouts/er.ttrg' }] },
      '/model/layout?name=er': { ...positionsLayout, name: 'er', source: 'layouts/er.ttrg' },
    };
    const { source } = fakeVeles(named);
    const { graphs, symbols } = await source.listCatalog();
    const catalog = buildCatalog(graphs, symbols);
    const refs = catalog.flatMap((g) => g.items).map((i) => i.ref);
    expect(new Set(refs).size).toBe(refs.length);
    expect(refs).toEqual(expect.arrayContaining(['er', 'layout:er']));

    window.history.replaceState(null, '', '/w/veles/s/er');
    render(<ShellFrame dataSource={source} workspace="veles" catalog={catalog} files={[]} displayMode="just-names" />);
    await screen.findByTestId('objects-panel');
    expect(screen.getAllByTestId('subject-tab').map((t) => t.textContent)).toEqual(['er×']);
    fireEvent.click(item('er · er · 1 missing'));
    await waitFor(() => expect(screen.getAllByTestId('subject-tab').map((t) => t.textContent)).toEqual(['er×', 'er · er×']));
    await waitFor(() => expect(window.location.pathname).toBe('/w/veles/s/layout%3Aer')); // the address follows the tab in an effect
    fireEvent.click(item('er'));
    await waitFor(() => expect(window.location.pathname).toBe('/w/veles/s/er'));
    expect(screen.getAllByTestId('subject-tab')).toHaveLength(2);
  });

  it('a local file’s ref is file:<name> — the URL round-trips, the link is disabled with the reason', async () => {
    const ref = 'file:sales_overview';
    expect(parsePath(formatPath({ kind: 'subject', workspace: 'veles', subjectRef: ref }))).toEqual({ kind: 'subject', workspace: 'veles', subjectRef: ref });
    await mountCatalog();
    fireEvent.click(item('er'));
    await screen.findByTestId('objects-panel');
    fireEvent.click(screen.getByTestId('objects-none')); // → er · untitled (new)
    await waitFor(() => expect(window.location.pathname).toBe('/w/veles/s/new%3Auntitled'));
    const link = screen.getByTestId('copy-federation-link');
    expect(link).toBeDisabled();
    expect(link).toHaveAttribute('title', 'Saved files are not on the server — commit it to share.');
  });

  it('a file: link opened later is not in the catalog — the DS-SHELL-001 hint, not a crash', async () => {
    window.history.replaceState(null, '', '/w/veles/s/file%3Asales_overview');
    await mountCatalog();
    expect(await screen.findByTestId('ds-shell-001-hint')).toHaveTextContent('file:sales_overview');
  });
});
