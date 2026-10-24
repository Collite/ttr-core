// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import postcss from 'postcss';
import tailwindcss from 'tailwindcss';
import { expect, it } from 'vitest';
import scopeDesignerCss from './scope-css.js';
import tailwindConfig from '../tailwind.config.js';

it('scopes the real Tailwind, reset, shared and React Flow rules without global preflight', async () => {
  const require = createRequire(import.meta.url);
  const flow = readFileSync(require.resolve('@xyflow/react/dist/style.css'), 'utf8');
  const css = readFileSync(resolve('src/designer.css'), 'utf8').replace(/^@import .*;$/gm, '') +
    readFileSync(resolve('src/shared.css'), 'utf8') + flow;
  const result = await postcss([tailwindcss({ ...tailwindConfig, important: '.ttr-designer', corePlugins: { preflight: false } }), scopeDesignerCss()]).process(css, { from: undefined });
  let count = 0;
  result.root.walkRules(rule => {
    if (rule.parent?.type === 'atrule' && /keyframes$/.test(rule.parent.name)) return;
    count++;
    expect(rule.selectors.every(selector => selector.startsWith('.ttr-designer')), rule.selector).toBe(true);
  });
  const sheet = document.createElement('style');
  sheet.textContent = result.css;
  document.head.append(sheet);
  try {
    expect(sheet.sheet).not.toBeNull();
    for (const rule of Array.from(sheet.sheet!.cssRules)) {
      if ('selectorText' in rule) expect(postcss.list.comma(String(rule.selectorText)).every(s => s.trim().startsWith('.ttr-designer')), String(rule.selectorText)).toBe(true);
    }
  } finally { sheet.remove(); }
  expect(count).toBeGreaterThan(100);
  expect(result.css).not.toContain('html,');
  expect(result.css).toContain('@keyframes ttr-designer-dashdraw');
  expect(result.css).not.toMatch(/(?:^|[\s:])dashdraw(?:[\s;]|$)/);
});
