// SPDX-License-Identifier: Apache-2.0
import type { EmbeddedBlock, SourceLocation } from '../ast.js';
import { pointer, type YamlInput } from './input.js';

const escapes: Record<string, string> = {
  '0': '\0',
  a: '\x07',
  b: '\b',
  e: '\x1b',
  f: '\f',
  n: '\n',
  r: '\r',
  t: '\t',
  v: '\v',
  N: '\u0085',
  _: '\u00a0',
  L: '\u2028',
  P: '\u2029',
  ' ': ' ',
  '"': '"',
  '/': '/',
  '\\': '\\',
  '\t': '\t',
};

/** Map double-quoted YAML spelling, including Unicode escapes and folded physical lines.
 * A mismatch conservatively falls back to the scalar's own position. */
function quotedOffsets(raw: string, text: string): number[] | undefined {
  let decoded = '';
  const units: number[] = [];
  const append = (value: string, at: number) => {
    decoded += value;
    for (let j = 0; j < value.length; j++) units.push(at);
  };
  for (let i = 1; i < raw.length - 1; i++) {
    const start = i,
      ch = raw[i];
    if (ch === '\r' && raw[i + 1] === '\n') continue;
    if (ch === '\n') {
      let fold = '';
      while (/[ \t\r\n]/.test(raw[i + 1] ?? '') && i + 1 < raw.length - 1) {
        if (raw[++i] === '\n') fold += '\n';
      }
      append(fold || ' ', start);
    } else if (ch === '\\') {
      const next = raw[++i];
      if (Object.hasOwn(escapes, next)) append(escapes[next], start);
      else if (next === 'x' || next === 'u' || next === 'U') {
        const size = next === 'x' ? 2 : next === 'u' ? 4 : 8;
        append(String.fromCodePoint(parseInt(raw.slice(i + 1, i + size + 1), 16)), start);
        i += size;
      } else if (next === '\n' || (next === '\r' && raw[i + 1] === '\n')) {
        if (next === '\r') i++;
        while (raw[i + 1] === ' ' || raw[i + 1] === '\t') i++;
      } else return undefined;
    } else if (ch === ' ' || ch === '\t') {
      while (raw[i + 1] === ' ' || raw[i + 1] === '\t') i++;
      if (raw[i + 1] !== '\n' && !(raw[i + 1] === '\r' && raw[i + 2] === '\n'))
        for (let j = start; j <= i; j++) append(raw[j], j);
    } else {
      const point = String.fromCodePoint(raw.codePointAt(i)!);
      append(point, start);
      i += point.length - 1;
    }
  }
  if (decoded !== text) return undefined;
  const offsets: number[] = [];
  let index = 0;
  for (const point of decoded) {
    offsets.push(units[index]);
    index += point.length;
  }
  offsets.push(raw.length - 1);
  return offsets;
}

export function yamlContent(
  input: YamlInput,
  path: string,
  text: string
): {
  source: SourceLocation;
  lines: number[];
  indent: number;
  mapping: NonNullable<EmbeddedBlock['textMapping']>;
} {
  const scalarPath = pointer(path, 'block');
  const node = input.node(scalarPath);
  const range = node?.range ?? [0, input.text.length];
  const source = input.source(scalarPath);
  const raw = input.text.slice(range[0], range[1]);
  const logical = text.split('\n');
  let offsets: number[] = [range[0]];
  let lines = logical.map(() => source.offsetStart);
  let indent = 0;
  if (node && 'type' in node && node.type === 'BLOCK_LITERAL') {
    const starts: number[] = [];
    for (let i = range[0]; i < range[1]; i++) if (input.text[i] === '\n') starts.push(i + 1);
    const first = logical.findIndex((line) => line.trim());
    const physicalStart = starts[Math.max(0, first)] ?? range[1];
    const physical = input.text.slice(
      physicalStart,
      input.text.indexOf('\n', physicalStart) < 0
        ? range[1]
        : input.text.indexOf('\n', physicalStart)
    );
    indent = Math.max(
      0,
      (physical.match(/^ */)?.[0].length ?? 0) -
        (logical[Math.max(0, first)]?.match(/^ */)?.[0].length ?? 0)
    );
    offsets = [];
    for (const [i, line] of logical.entries()) {
      let at = Math.min(range[1], (starts[i] ?? range[1]) + (line.trim() ? indent : 0));
      for (const point of line) {
        offsets.push(at);
        at += point.length;
      }
      offsets.push(Math.min(range[1], at));
    }
  } else if (node && 'type' in node && node.type === 'QUOTE_DOUBLE') {
    const quoted = quotedOffsets(raw, text);
    if (quoted) offsets = quoted.map((offset) => range[0] + offset);
  } else if (!raw.includes('\n') && (raw === text || raw.slice(1, -1) === text)) {
    let at = range[0] + (raw === text ? 0 : 1);
    offsets = [];
    for (const point of text) {
      offsets.push(at);
      at += point.length;
    }
    offsets.push(at);
  }
  const mapped = Uint32Array.from(offsets, (offset) => input.coordinates.points[offset]);
  if (mapped.length > 1) {
    lines = [];
    let i = 0;
    for (const line of logical) {
      lines.push(mapped[i]);
      i += [...line].length + 1;
    }
  }
  const lineStarts = [source.offsetStart - source.column];
  for (let i = range[0]; i < range[1]; i++)
    if (input.text[i] === '\n') lineStarts.push(input.coordinates.points[i + 1]);
  return {
    source: offsets.length > 1 ? input.span([offsets[0], offsets.at(-1)!]) : source,
    lines,
    indent,
    mapping: { offsets: mapped, lineStarts, startLine: source.line },
  };
}
