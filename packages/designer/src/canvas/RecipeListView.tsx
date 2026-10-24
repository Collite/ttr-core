// SPDX-License-Identifier: Apache-2.0
import type { RecipeSummary } from '../model/ttrp/types.js';

/** The recipe's four lanes are lists. The compiler owns role, kind and mode classification. */
export function RecipeListView({ recipe }: { recipe: RecipeSummary }) {
  return <section aria-label={`Recipe ${recipe.name}`} style={{ padding: 20, overflow: 'auto', height: '100%' }}>
    <h2>{recipe.name}</h2>
    <p><span role="status" aria-label="Recipe mode">{recipe.mode}</span></p>
    {recipe.lanes.map(lane => <section key={lane.role} aria-label={lane.role}>
      <h3>{lane.role}</h3>
      <ul>{lane.islands.map((island, index) => <li key={`${island.name}-${index}`}>
        <strong>{island.name}</strong> <span>{island.kind}</span>
        {island.contextClass && <span> · {island.contextClass}</span>}
        {island.routes.length > 0 && <span> · {island.routes.join(', ')}</span>}
      </li>)}</ul>
    </section>)}
  </section>;
}
