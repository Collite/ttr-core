// SPDX-License-Identifier: Apache-2.0
import type { DocumentFilter } from 'vscode';

export const DEFAULT_LITERATE_RECIPE_GLOB = '**/recipes/**/*.md';

export function literateRecipeSelector(glob = DEFAULT_LITERATE_RECIPE_GLOB) {
  return { scheme: 'file', language: 'markdown', pattern: glob } satisfies DocumentFilter;
}
