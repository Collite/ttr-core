// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ProcessingCanvas } from '../ProcessingCanvas.js';
import { RecipeListView } from '../RecipeListView.js';

describe('recipe list', () => {
  it('shows the four compiler lanes, island kinds, context classes and mode', () => {
    render(<RecipeListView recipe={{ name: 'release_order', mode: 'per-candidate', lanes: [
      { role: 'candidates', islands: [{ name: 'placed_orders', kind: 'sql', routes: [] }] },
      { role: 'context', islands: [{ name: 'shortfall', kind: 'canonical', contextClass: 'per-candidate', routes: [] }] },
      { role: 'decision', islands: [{ name: 'verdict', kind: 'decision', routes: ['hold_sales', 'release'] }] },
      { role: 'actions', islands: [{ name: 'release', kind: 'action', routes: ['verdict.release'] }] },
    ] }} />);
    for (const lane of ['candidates', 'context', 'decision', 'actions']) expect(screen.getByRole('region', { name: lane })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toBe('per-candidate');
    expect(screen.getByText('shortfall')).toBeTruthy();
    expect(screen.getByText('sql')).toBeTruthy();
    expect(screen.queryByTestId('canvas-kernel')).toBeNull();
  });
  it('uses the live recipe summary in the processing surface', async () => {
    const recipe = { name: 'release_order', mode: 'per-candidate', lanes: [
      { role: 'candidates', islands: [{ name: 'placed_orders', kind: 'sql', routes: [] }] },
      { role: 'context', islands: [] }, { role: 'decision', islands: [] }, { role: 'actions', islands: [] },
    ] };
    const graph = { id: 'recipe', face: 'processing' as const, nodes: [], edges: [] };
    render(<ProcessingCanvas source={{ getProgramGraph: async () => graph, getContainerGraph: async () => graph,
      getRecipeSummary: async () => recipe }} programRef="release_order" drillPath={[]} />);
    expect(await screen.findByRole('region', { name: 'Recipe release_order' })).toBeTruthy();
  });
});
