// SPDX-License-Identifier: Apache-2.0
import type { SourceLocation } from '../ast.js';
import type { Piece, Projection } from './bridge.js';

interface Interval {
  span: Projection;
  order: number;
  maxEnd: number;
  left?: Interval;
  right?: Interval;
}

/** Immutable interval index. Equal-width ties retain the projection's original order. */
export class Projections {
  private readonly root?: Interval;
  private readonly literals?: Interval;
  private readonly cache = new Map<string, Projection | undefined>();
  constructor(bridge: Piece) {
    // The bridge builder uses JS string indices; the native parser uses code points.
    const points = new Uint32Array(bridge.text.length + 1);
    let units = 0,
      count = 0;
    for (const ch of bridge.text) {
      points[units] = count;
      if (ch.length === 2) points[units + 1] = count;
      units += ch.length;
      points[units] = ++count;
    }
    const spans = bridge.spans
      .map((span, order) => ({
        span: { ...span, start: points[span.start], end: points[span.end] },
        order,
      }))
      .sort((a, b) => a.span.start - b.span.start || a.order - b.order);
    const tree = (items: typeof spans, lo = 0, hi = items.length): Interval | undefined => {
      if (lo === hi) return undefined;
      const mid = (lo + hi) >>> 1;
      const left = tree(items, lo, mid),
        right = tree(items, mid + 1, hi);
      return {
        ...items[mid],
        left,
        right,
        maxEnd: Math.max(items[mid].span.end, left?.maxEnd ?? 0, right?.maxEnd ?? 0),
      };
    };
    this.root = tree(spans);
    this.literals = tree(spans.filter(({ span }) => span.literal));
  }
  find(source: SourceLocation, literal = false): Projection | undefined {
    const { offsetStart: start, offsetEnd: end } = source;
    const key = `${start}:${end}:${literal}`;
    if (this.cache.has(key)) return this.cache.get(key);
    let best: Interval | undefined;
    const visit = (node?: Interval): void => {
      if (!node || node.maxEnd < end) return;
      visit(node.left);
      if (node.span.start > start) return;
      if (node.span.end >= end) {
        const width = node.span.end - node.span.start;
        const previous = best ? best.span.end - best.span.start : Infinity;
        if (width < previous || (width === previous && node.order < best!.order)) best = node;
      }
      visit(node.right);
    };
    visit(literal ? this.literals : this.root);
    this.cache.set(key, best?.span);
    return best?.span;
  }
}
