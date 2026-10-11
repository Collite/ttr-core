// SPDX-License-Identifier: Apache-2.0
// ttr-core#170 end to end in the shell: the REAL VelesReadApiDataSource over a fake fetch serving the
// Hartland graph as a Veles with ttr-server#185 serves it. The ER card reads "Product" in English and
// "Produkt" in Czech; the inspector shows the label, the identifier, the plural, the description and
// the attributes with theirs.
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor, fireEvent, within } from '@testing-library/react';
import { ShellFrame } from '../ShellFrame.js';
import type { CatalogGroup } from '../types.js';
import { VelesReadApiDataSource } from '../../data/veles-read-api-data-source.js';
import erFixture from '../../data/__tests__/fixtures/veles/hartland-er-graph.json';

const CATALOG: CatalogGroup[] = [
  { kind: 'schema', label: 'Schemas', items: [{ ref: 'er', qname: 'er', kind: 'schema', schemaCode: 'er', label: 'er' }] },
];

function veles(lang: string) {
  const fetchImpl = (async (input: string | URL | Request) => {
    const url = String(input);
    if (url.endsWith('/model/graph?schema=er')) return new Response(JSON.stringify(erFixture), { status: 200 });
    return new Response('{"error":"not found"}', { status: 404 });
  }) as typeof fetch;
  return new VelesReadApiDataSource('/veles', { fetchImpl, lang });
}

// `with-types` — the detail level that draws member rows (`just-names` is headers only).
async function openErAndSelectItem(lang: string) {
  render(<ShellFrame dataSource={veles(lang)} workspace="veles" catalog={CATALOG} files={[]} displayMode="with-types" />);
  fireEvent.click(screen.getByTestId('catalog-item'));
  await waitFor(() => expect(screen.getAllByTestId('node-label').length).toBe(4));
  const label = lang === 'cs' ? 'Produkt' : 'Product';
  const card = screen.getAllByTestId('row-card').find((c) => within(c).getByTestId('node-label').textContent === label)!;
  expect(card).toBeDefined();
  fireEvent.click(card.closest('.react-flow__node')!);
  await waitFor(() => expect(screen.getByTestId('text-drawer')).toBeInTheDocument());
}

describe('catalog labels in the shell (ttr-core#170)', () => {
  afterEach(() => {
    cleanup();
    window.history.replaceState(null, '', '/');
  });

  it('English: the cards read Product / Store / Store Sales / Customer Address, attributes as rows', async () => {
    await openErAndSelectItem('en');
    const labels = screen.getAllByTestId('node-label').map((n) => n.textContent).sort();
    expect(labels).toEqual(['Customer Address', 'Product', 'Store', 'Store Sales']);
    const product = screen.getAllByTestId('row-card').find((c) => within(c).getByTestId('node-label').textContent === 'Product')!;
    expect(within(product).getAllByTestId('card-row').map((r) => r.textContent)).toEqual(['sk', 'product_name', 'category']);
  });

  it('the inspector shows the label, the identifier, the plural, the description and the attributes', async () => {
    await openErAndSelectItem('en');
    const drawer = screen.getByTestId('text-drawer');
    expect(within(drawer).getByTestId('drawer-label')).toHaveTextContent('Product');
    expect(within(drawer).getByTestId('drawer-identifier')).toHaveTextContent('item');
    expect(within(drawer).getByTestId('drawer-qname')).toHaveTextContent('er.entity.item');
    expect(within(drawer).getByTestId('drawer-plural')).toHaveTextContent('Products');
    expect(within(drawer).getByTestId('property-panel')).toHaveTextContent(/^Products — category\/brand drills/);
    const category = within(drawer).getAllByTestId('drawer-member').find((m) => m.getAttribute('data-qname') === 'er.entity.item.category')!;
    expect(category).toHaveTextContent('category');
    expect(within(category).getByTestId('drawer-member-description')).toHaveTextContent('Product category of this item');
  });

  it('Czech: Produkt, with the Czech descriptions', async () => {
    await openErAndSelectItem('cs');
    const drawer = screen.getByTestId('text-drawer');
    expect(within(drawer).getByTestId('drawer-label')).toHaveTextContent('Produkt');
    expect(within(drawer).getByTestId('drawer-identifier')).toHaveTextContent('item');
    expect(within(drawer).getByTestId('property-panel')).toHaveTextContent(/^Produkty — členění/);
    const category = within(drawer).getAllByTestId('drawer-member').find((m) => m.getAttribute('data-qname') === 'er.entity.item.category')!;
    expect(within(category).getByTestId('drawer-member-description')).toHaveTextContent('Kategorie tohoto produktu');
  });
});
