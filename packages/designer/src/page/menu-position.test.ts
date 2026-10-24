// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { menuPosition } from './menu-position.js';
it('translates viewport coordinates into the contained page and clamps measured menus', () => {
  const root = document.createElement('div');
  root.getBoundingClientRect = () => ({
    x: 200,
    y: 120,
    left: 200,
    top: 120,
    right: 1440,
    bottom: 920,
    width: 1240,
    height: 800,
    toJSON() {},
  });
  const frame = root.getBoundingClientRect();
  expect(menuPosition({ x: 300, y: 240 }, frame, { width: 220, height: 180 })).toEqual({
    left: 100,
    top: 120,
    maxWidth: 1240,
    maxHeight: 800,
  });
  expect(menuPosition({ x: 1400, y: 900 }, frame, { width: 220, height: 180 })).toEqual({
    left: 1020,
    top: 620,
    maxWidth: 1240,
    maxHeight: 800,
  });
  expect(menuPosition({ x: 0, y: 0 }, frame, { width: 2000, height: 1000 })).toMatchObject({
    left: 0,
    top: 0,
  });
});
