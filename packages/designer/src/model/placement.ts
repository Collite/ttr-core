// SPDX-License-Identifier: Apache-2.0
// Placing a newly checked card (VL contracts V-19, ⚑VL-11): beside its nearest visible neighbour, on the
// first free slot clockwise one node size away, never overlapping a placed card; at the viewport centre
// when it has no positioned neighbour. Existing positions never move — the caller adds the result.
// Pure and deterministic: the same inputs give the same spot.

export interface Point {
  x: number;
  y: number;
}

export interface Size {
  width: number;
  height: number;
}

/** The gap between a neighbour and a placed card, and the margin kept around every card. */
const GAP = 40;
const PAD = 16;
/** How many rings out from the neighbour (or the centre) a free slot is looked for. */
const RINGS = 3;

interface Rect extends Point, Size {}

function overlaps(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.width + PAD && a.x + a.width + PAD > b.x && a.y < b.y + b.height + PAD && a.y + a.height + PAD > b.y;
}

/**
 * The slots around `r` for a card of `size`, clockwise from the right (screen y grows down): right, below,
 * left, above, then the four corners — each `ring` times further out.
 */
function slotsAround(r: Rect, size: Size, ring: number): Point[] {
  const right = r.x + (r.width + GAP) * ring;
  const below = r.y + (r.height + GAP) * ring;
  const left = r.x - (size.width + GAP) * ring;
  const above = r.y - (size.height + GAP) * ring;
  return [
    { x: right, y: r.y }, { x: r.x, y: below }, { x: left, y: r.y }, { x: r.x, y: above },
    { x: right, y: below }, { x: left, y: below }, { x: left, y: above }, { x: right, y: above },
  ];
}

/**
 * Where `cardId` goes. `positions` are the placed cards (top-left corners, by node id); `neighbours` are
 * its visible neighbours in the order to try (the first with a free slot wins); `viewportCenter` is the
 * centre of what the user sees, in canvas coordinates.
 */
export function placeNew(
  cardId: string,
  positions: Readonly<Record<string, Point>>,
  sizeOf: (id: string) => Size,
  neighbours: readonly string[],
  viewportCenter: Point,
): Point {
  const size = sizeOf(cardId);
  const placed: Rect[] = Object.entries(positions)
    .filter(([id]) => id !== cardId)
    .map(([id, p]) => ({ ...p, ...sizeOf(id) }));
  const free = (p: Point) => !placed.some((o) => overlaps({ ...p, ...size }, o));
  const around = (r: Rect): Point | null => {
    for (let ring = 1; ring <= RINGS; ring++) for (const p of slotsAround(r, size, ring)) if (free(p)) return p;
    return null;
  };
  for (const n of neighbours) {
    const p = positions[n];
    if (!p) continue;
    const spot = around({ ...p, ...sizeOf(n) });
    if (spot) return spot;
  }
  const centre = { x: Math.round(viewportCenter.x - size.width / 2), y: Math.round(viewportCenter.y - size.height / 2) };
  if (free(centre)) return centre;
  return around({ ...centre, ...size }) ?? centre;
}

/** The centre of the placed cards' bounding box — the fallback when the canvas cannot say what is in view. */
export function centreOf(positions: Readonly<Record<string, Point>>, sizeOf: (id: string) => Size): Point {
  const entries = Object.entries(positions);
  if (entries.length === 0) return { x: 0, y: 0 };
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const [id, p] of entries) {
    const s = sizeOf(id);
    minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
    maxX = Math.max(maxX, p.x + s.width); maxY = Math.max(maxY, p.y + s.height);
  }
  return { x: (minX + maxX) / 2, y: (minY + maxY) / 2 };
}
