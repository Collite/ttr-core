// SPDX-License-Identifier: Apache-2.0
/** UTF-16 boundary indices to public Unicode code-point offsets. Count before allocating. */
export function codePointTable(text: string, limit = Infinity): Uint32Array {
  let count = 0;
  for (let i = 0; i < text.length; i += text.codePointAt(i)! > 0xffff ? 2 : 1) {
    if (++count > limit) throw new RangeError(`Text exceeds ${limit} code points`);
  }
  const points = new Uint32Array(text.length + 1);
  let units = 0;
  count = 0;
  for (const ch of text) {
    points[units] = count;
    if (ch.length === 2) points[units + 1] = count;
    units += ch.length;
    points[units] = ++count;
  }
  return points;
}

/** Index of the last line start at or before offset. */
export function lineOf(starts: readonly number[], offset: number): number {
  let lo = 0,
    hi = starts.length;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >>> 1;
    if (starts[mid] <= offset) lo = mid;
    else hi = mid;
  }
  return lo;
}

export interface TextMapping {
  offsets: readonly number[] | Uint32Array;
  lineStarts: number[];
  startLine: number;
}
export function mappedPoint(
  mapping: TextMapping,
  index: number
): { offset: number; line: number; column: number } {
  const offset = mapping.offsets[Math.max(0, Math.min(index, mapping.offsets.length - 1))];
  const line = lineOf(mapping.lineStarts, offset);
  return { offset, line: mapping.startLine + line, column: offset - mapping.lineStarts[line] };
}
