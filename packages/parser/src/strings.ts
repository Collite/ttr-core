// SPDX-License-Identifier: Apache-2.0
/** TTR STRING_LITERAL rules. Triple strings and tagged blocks do not use these helpers. */
const ESCAPES: Record<string, string> = { '\\': '\\', '"': '"', n: '\n', t: '\t', r: '\r' };

/** Decode one quoted token in a single pass; unknown escapes keep their backslash. */
export function decodeTtrString(raw: string, onEscape?: (offset: number) => void): string {
  const input = [...raw.slice(1, -1)];
  const out: string[] = [];
  for (let i = 0; i < input.length; i++) {
    if (input[i] === '\\' && ESCAPES[input[i + 1]] !== undefined) {
      onEscape?.(out.length);
      out.push(ESCAPES[input[++i]]);
    } else out.push(input[i]);
  }
  return out.join('');
}

/** Quote a TTR string, escaping only backslash, quote, LF, TAB and CR. */
export function encodeTtrString(value: string): string {
  return '"' + value.replace(/[\\"\n\t\r]/g, (c) => {
    if (c === '\n') return '\\n';
    if (c === '\t') return '\\t';
    if (c === '\r') return '\\r';
    return '\\' + c;
  }) + '"';
}

/** Physical source position before a decoded code point, including escapes that encode line breaks. */
export function stringSourcePosition(text: string, escapes: readonly number[], offset: number, line: number, column: number): { line: number; column: number } {
  const escaped = new Set(escapes);
  for (const [i, ch] of [...text].slice(0, offset).entries()) {
    if (escaped.has(i)) column += 2;
    else if (ch === '\n') { line++; column = 0; }
    else column++;
  }
  return { line, column };
}
