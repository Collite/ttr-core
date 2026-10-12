// SPDX-License-Identifier: Apache-2.0
// ttr-core#171 — under `/` and under a sub-path, with the catalog chosen by `?veles=`: opening a graph
// rewrites the address bar INSIDE the base path and KEEPS the catalog selection, and a reload of
// that URL (a fresh mount at the same location) lands on the same graph.
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react';
import type { GetGraphResponse } from '@tatrman/lsp';
import { ShellFrame } from '../ShellFrame.js';
import type { CatalogGroup } from '../types.js';
import { fakeDataSource } from './fake-data-source.js';
import { setRuntimeConfig, type RuntimeConfig } from '../../runtime-config.js';

// The catalog a Veles backend lists: one entry per served schema (VelesReadApiDataSource.listCatalog).
const CATALOG: CatalogGroup[] = [
  {
    kind: 'schema',
    label: 'Schemas',
    items: [
      { ref: 'er', qname: 'er', kind: 'schema', schemaCode: 'er', label: 'er' },
      { ref: 'db', qname: 'db', kind: 'schema', schemaCode: 'db', label: 'db' },
    ],
  },
];

const erGraph: GetGraphResponse = {
  schema: 'er',
  nodes: [{ qname: 'er.entity.item', kind: 'entity', name: 'item', schemaCode: 'er', label: 'Product', sourceUri: '', sourceLocation: { line: 0, column: 0 }, rows: [] }],
  edges: [],
  layout: { nodes: {}, edges: {} },
  missingObjects: [],
  imports: [],
};

function config(basePath: string): RuntimeConfig {
  return { basePath, veles: null, lang: null, languages: [], layoutFormat: 'yaml' };
}

function mountShell() {
  return render(
    <ShellFrame dataSource={fakeDataSource({ getGraph: erGraph })} workspace="veles" catalog={CATALOG} files={[]} displayMode="just-names" />,
  );
}

const here = () => window.location.pathname + window.location.search;

describe.each([
  { basePath: '/', entry: '/?veles=/veles', velesParam: 'veles=/veles' },
  { basePath: '/models/', entry: '/models/?veles=/models/veles', velesParam: 'veles=/models/veles' },
  { basePath: '/apps/models/', entry: '/apps/models/?veles=http://localhost:7260&lang=cs', velesParam: 'veles=http://localhost:7260' },
])('address-bar rewrites under $basePath (ttr-core#171)', ({ basePath, entry, velesParam }) => {
  afterEach(() => {
    cleanup();
    setRuntimeConfig(null);
    window.history.replaceState(null, '', '/');
  });

  it('opening a graph rewrites inside the base path and keeps the catalog selection', async () => {
    setRuntimeConfig(config(basePath));
    window.history.replaceState(null, '', entry);
    mountShell();

    // the empty shell already sits inside the prefix, catalog kept
    await waitFor(() => expect(here()).toBe(`${basePath}w/veles?${entry.split('?')[1]}`));

    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
    await waitFor(() => expect(screen.getByTestId('skinned-canvas')).toBeInTheDocument());

    expect(window.location.pathname).toBe(`${basePath}w/veles/s/er`);
    expect(window.location.search).toContain(velesParam);
    if (entry.includes('lang=cs')) expect(window.location.search).toContain('lang=cs');
  });

  it('a reload of the rewritten URL lands on the same graph', async () => {
    setRuntimeConfig(config(basePath));
    window.history.replaceState(null, '', entry);
    const first = mountShell();
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
    await waitFor(() => expect(window.location.pathname).toBe(`${basePath}w/veles/s/er`));
    const bookmarked = here();
    first.unmount();

    // "reload": a fresh shell, booting from the bookmarked address bar
    window.history.replaceState(null, '', bookmarked);
    mountShell();
    await waitFor(() => expect(screen.getByTestId('skinned-canvas')).toBeInTheDocument());
    expect(screen.getAllByTestId('subject-tab')).toHaveLength(1);
    expect(screen.getByTestId('subject-tab')).toHaveTextContent('er');
    expect(screen.queryByTestId('ds-shell-001-hint')).not.toBeInTheDocument();
    expect(here()).toBe(bookmarked);
  });

  it('a deep link typed straight into the address bar opens its graph', async () => {
    setRuntimeConfig(config(basePath));
    window.history.replaceState(null, '', `${basePath}w/veles/s/er?${velesParam}`);
    mountShell();
    await waitFor(() => expect(screen.getByTestId('skinned-canvas')).toBeInTheDocument());
    expect(window.location.pathname).toBe(`${basePath}w/veles/s/er`);
    expect(window.location.search).toContain(velesParam);
  });
});
