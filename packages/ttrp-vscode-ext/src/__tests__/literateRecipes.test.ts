// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { matchesGlob } from 'node:path';
import fs from 'node:fs';
import path from 'node:path';
import { DEFAULT_LITERATE_RECIPE_GLOB, literateRecipeSelector } from '../literateRecipes';

const pkg = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../package.json'), 'utf8'));

describe('literate Markdown selection', () => {
  it('selects recipe Markdown without selecting arbitrary Markdown', () => {
    const selector = literateRecipeSelector();
    expect(selector.language).toBe('markdown');
    expect(selector.scheme).toBe('file');
    expect(matchesGlob('/project/recipes/release.md', selector.pattern)).toBe(true);
    expect(matchesGlob('/project/recipes/nested/release.md', selector.pattern)).toBe(true);
    expect(matchesGlob('/project/docs/notes.md', selector.pattern)).toBe(false);
    expect(matchesGlob('/project/README.md', selector.pattern)).toBe(false);
    expect(
      matchesGlob(
        '/project/automation/release.md',
        literateRecipeSelector('**/automation/*.md').pattern
      )
    ).toBe(true);
  });

  it('does not activate for an arbitrary Markdown file and publishes the same default', () => {
    expect(pkg.activationEvents).not.toContain('onLanguage:markdown');
    expect(pkg.activationEvents).toContain(`workspaceContains:${DEFAULT_LITERATE_RECIPE_GLOB}`);
    expect(pkg.contributes.configuration.properties['ttrp.literateRecipes.glob'].default).toBe(
      DEFAULT_LITERATE_RECIPE_GLOB
    );
  });
});
