// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { changedFiles } from '../changed-files.js';
it('compares whole texts and returns sorted creates, updates and deletion tombstones', () => {
  const base = new Map([['same', 'x'], ['removed', 'old'], ['edited', 'old']]);
  const current = new Map([['same', 'x'], ['new', ''], ['edited', 'new']]);
  expect(changedFiles(base, current)).toEqual([
    { path: 'edited', text: 'new' }, { path: 'new', text: '' }, { path: 'removed', text: null },
  ]);
});
