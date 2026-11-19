// SPDX-License-Identifier: Apache-2.0
/** A resolved numeric scalar. Arithmetic values are approximate; text is the lossless authority. */
export class NumericScalar {
  constructor(
    readonly value: number,
    readonly text: string
  ) {}
  toString(): string {
    return this.text;
  }
  toJSON(): number {
    return this.value;
  }
}

export { decimalText } from '@tatrman/parser';

/** JSON-shaped output can contain ordered maps and lossless numbers without lossy JSON.stringify. */
export function entries(value: import('./input.js').Json): [string, import('./input.js').Json][] {
  return value instanceof Map ? [...value] : Object.entries(value as object);
}
export function jsonText(value: import('./input.js').Json): string {
  if (value instanceof NumericScalar) return value.text;
  if (Array.isArray(value)) return '[' + value.map(jsonText).join(',') + ']';
  if (value !== null && typeof value === 'object')
    return (
      '{' +
      entries(value)
        .map(([key, child]) => JSON.stringify(key) + ':' + jsonText(child))
        .join(',') +
      '}'
    );
  return JSON.stringify(value);
}
