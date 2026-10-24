// SPDX-License-Identifier: Apache-2.0
import type { AttributeDef, EntityDef } from '@tatrman/parser';
import { parseString } from '@tatrman/parser';
import { Fragment, location, utf16Offset } from './print.js';

/** Tokens needed to locate the entity's attributes property, excluding comments and string bodies. */
function tokens(text: string): { text: string; start: number; end: number }[] {
  const out: { text: string; start: number; end: number }[] = [];
  const pattern = /\/\/[^\r\n]*|\/\*[\s\S]*?\*\/|"""[\s\S]*?"""|"(?:\\[\s\S]|[^"\\])*"|[a-zA-Z_][a-zA-Z0-9_]*|[^\s]/g;
  for (const m of text.matchAll(pattern)) {
    if (m[0].startsWith('//') || m[0].startsWith('/*') || m[0].startsWith('"')) continue;
    out.push({ text: m[0], start: m.index, end: m.index + m[0].length });
  }
  return out;
}

/** Add one formatter-printed attribute while leaving all existing source and trivia in place. */
export function addAttribute(text: string, entity: EntityDef, attribute: AttributeDef, f: Fragment): string {
  const wrapped = f.print([{ kind: 'entity', name: '_fragment', attributes: [attribute], source: location }]);
  const parsed = parseString(wrapped, '').ast!.definitions[0] as EntityDef;
  const attr = parsed.attributes![0];
  const printed = wrapped.slice(utf16Offset(wrapped, attr.source.offsetStart), utf16Offset(wrapped, attr.source.offsetEnd));
  const start = utf16Offset(text, entity.source.offsetStart), end = utf16Offset(text, entity.source.offsetEnd);
  const body = text.slice(start, end);
  const ts = tokens(body);
  let depth = 0;
  let list = -1;
  for (let i = 0; i < ts.length; i++) {
    if (ts[i].text === '{') depth++;
    if (ts[i].text === '}') depth--;
    if (depth === 1 && ts[i].text === 'attributes') {
      list = ts[i + 1]?.text === '[' ? i + 1 : ts[i + 2]?.text === '[' ? i + 2 : -1;
      break;
    }
  }
  let changed: string;
  if (list < 0) {
    const property = wrapped.slice(wrapped.indexOf('{') + 1, wrapped.lastIndexOf('}')).trimEnd();
    changed = body.slice(0, -1).trimEnd() + property + '\n}';
  } else {
    let bracket = 1, close = list + 1;
    for (; close < ts.length; close++) {
      if (ts[close].text === '[') bracket++;
      if (ts[close].text === ']') { bracket--; if (bracket === 0) break; }
    }
    const closeOffset = ts[close].start;
    const last = entity.attributes?.at(-1);
    const insert = last ? utf16Offset(text, last.source.offsetEnd) - start : ts[list].end;
    const tail = body.slice(insert, closeOffset);
    const hasComma = tokens(tail)[0]?.text === ',';
    const indentation = entity.attributes?.[0]?.source.column ?? 8;
    const line = ' '.repeat(indentation) + printed.replaceAll('\n', '\n' + ' '.repeat(indentation));
    changed = body.slice(0, insert) + (last && !hasComma ? ',' : '') + tail.trimEnd() + '\n' + line + '\n    ' + body.slice(closeOffset);
  }
  return text.slice(0, start) + changed + text.slice(end);
}
