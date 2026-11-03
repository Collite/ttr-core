// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { parseString } from '@tatrman/parser';
import { getPropertyNameCompletions } from '../completion-property.js';

function labels(content: string, line: number) {
  const parsed = parseString(content);
  expect(parsed.errors).toEqual([]);
  return getPropertyNameCompletions({ content, doc: parsed.ast!, position: { line, character: 2 } })?.items.map((item) => item.label) ?? [];
}
it('offers by and due on actions and omits properties already present', () => {
  expect(labels('model automation\ndef action release {\n  \n}', 2)).toEqual(expect.arrayContaining(['by', 'due']));
  expect(labels('model automation\ndef action release {\n  by: role.manager\n  \n}', 3)).not.toContain('by');
});
it('offers egress_url inside the world executor', () => {
  expect(labels('model world\ndef world demo {\n def executor engine {\n  \n }\n}', 3)).toContain('egress_url');
});
it('offers startable_by inside the world deployment', () => {
  expect(labels('model world\ndef world demo {\n def deployment release {\n  \n }\n}', 3)).toContain('startable_by');
});
