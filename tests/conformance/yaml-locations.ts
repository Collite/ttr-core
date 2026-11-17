// SPDX-License-Identifier: Apache-2.0
import type { SourceLocation } from '@tatrman/parser';
import { YamlInput } from '../../packages/parser/src/yaml/input.js';

/** Dumps intentionally omit positions. Independently check every AST location, including arrays. */
export function checkYamlLocations(text: string, uri: string, value: unknown): void {
  const input = new YamlInput(text, uri);
  const spans = new Set<string>();
  const scalars: SourceLocation[] = [];
  for (const node of [...input.nodes.values(), ...input.keys.values()]) {
    if (!node.range) continue;
    const span = input.span([node.range[0], node.range[1]]);
    spans.add(`${span.offsetStart}:${span.offsetEnd}`);
    if ('value' in node) scalars.push(span);
  }
  const points = [...text];
  // O(document + locations log lines), rather than slicing the document per location.
  const starts = [0];
  points.forEach((point, i) => {
    if (point === '\n') starts.push(i + 1);
  });
  const position = (offset: number) => {
    let lo = 0,
      hi = starts.length;
    while (lo + 1 < hi) {
      const mid = (lo + hi) >>> 1;
      if (starts[mid] <= offset) lo = mid;
      else hi = mid;
    }
    return [lo + 1, offset - starts[lo]];
  };
  const walk = (value: unknown, key = ''): void => {
    if (!value || typeof value !== 'object') return;
    const object = value as Record<string, unknown>;
    if (
      typeof object.file === 'string' &&
      typeof object.offsetStart === 'number' &&
      typeof object.offsetEnd === 'number'
    ) {
      const source = value as SourceLocation;
      const isContent = key === 'textSource' || key === 'valueSource';
      if (
        source.file !== uri ||
        source.offsetStart < 0 ||
        source.offsetEnd < source.offsetStart ||
        source.offsetEnd > points.length ||
        JSON.stringify([source.line, source.column]) !==
          JSON.stringify(position(source.offsetStart)) ||
        JSON.stringify([source.endLine, source.endColumn]) !==
          JSON.stringify(position(source.offsetEnd)) ||
        !(
          spans.has(`${source.offsetStart}:${source.offsetEnd}`) ||
          (isContent &&
            scalars.some(
              (scalar) =>
                scalar.offsetStart <= source.offsetStart && scalar.offsetEnd >= source.offsetEnd
            ))
        )
      )
        throw new Error(`${uri}: non-authored ${key} location ${JSON.stringify(source)}`);
      return;
    }
    for (const [key, child] of Object.entries(object)) {
      // Semantics entries are opaque authored data, including location-shaped maps.
      if (object.source && key === 'entries' && child && !Array.isArray(child)) continue;
      walk(child, key);
    }
  };
  walk(value);
}
