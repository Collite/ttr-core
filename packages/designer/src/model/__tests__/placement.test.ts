// SPDX-License-Identifier: Apache-2.0
// VL-P1 T5 — placing a newly checked card (VL contracts V-19, ⚑VL-11).

import { describe, it, expect } from 'vitest';
import { placeNew, centreOf, type Point } from '../placement.js';

const size = () => ({ width: 200, height: 80 });
const CENTRE = { x: 1000, y: 500 };

describe('placeNew', () => {
  it('goes beside its neighbour — the right-hand slot first (clockwise from the right)', () => {
    expect(placeNew('b', { a: { x: 0, y: 0 } }, size, ['a'], CENTRE)).toEqual({ x: 240, y: 0 });
  });

  it('never moves an existing position, and takes the next slot clockwise when one is taken', () => {
    const positions: Record<string, Point> = { a: { x: 0, y: 0 }, c: { x: 240, y: 0 } };
    const before = JSON.stringify(positions);
    expect(placeNew('b', positions, size, ['a'], CENTRE)).toEqual({ x: 0, y: 120 }); // below
    expect(JSON.stringify(positions)).toBe(before);
  });

  it('two cards added in a row take two distinct, non-overlapping slots', () => {
    const positions: Record<string, Point> = { a: { x: 0, y: 0 } };
    const b = placeNew('b', positions, size, ['a'], CENTRE);
    positions.b = b;
    const c = placeNew('c', positions, size, ['a'], CENTRE);
    expect(c).not.toEqual(b);
    expect(Math.abs(c.x - b.x) >= 216 || Math.abs(c.y - b.y) >= 96).toBe(true);
  });

  it('a card without a positioned neighbour lands at the viewport centre', () => {
    expect(placeNew('b', { a: { x: 0, y: 0 } }, size, [], CENTRE)).toEqual({ x: 900, y: 460 });
    expect(placeNew('b', { a: { x: 0, y: 0 } }, size, ['ghost'], CENTRE)).toEqual({ x: 900, y: 460 });
  });

  it('…next to the centre when the centre is taken', () => {
    const p = placeNew('b', { a: { x: 900, y: 460 } }, size, [], CENTRE);
    expect(p).toEqual({ x: 1140, y: 460 });
  });

  it('is deterministic', () => {
    const positions = { a: { x: 0, y: 0 }, c: { x: 240, y: 0 }, d: { x: 0, y: 120 } };
    expect(placeNew('b', positions, size, ['a'], CENTRE)).toEqual(placeNew('b', positions, size, ['a'], CENTRE));
  });
});

describe('centreOf', () => {
  it('the centre of the cards’ bounding box; the origin when there are none', () => {
    expect(centreOf({ a: { x: 0, y: 0 }, b: { x: 400, y: 200 } }, size)).toEqual({ x: 300, y: 140 });
    expect(centreOf({}, size)).toEqual({ x: 0, y: 0 });
  });
});
