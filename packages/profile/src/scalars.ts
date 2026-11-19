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

/** Networknt's located type text, shared by profile loading and canonical validation. */
export function schemaTypeMessage(
  message: string,
  root: import('./input.js').Json,
  path: string
): string {
  const expected = /^must be (string|object|array|number|integer|boolean|null)$/.exec(message)?.[1];
  if (!expected) return message;
  const value = path
    .split('/')
    .slice(1)
    .reduce<import('./input.js').Json | undefined>((value, key) => {
      const decoded = key.replace(/~1/g, '/').replace(/~0/g, '~');
      return Array.isArray(value)
        ? value[Number(decoded)]
        : value instanceof Map
          ? value.get(decoded)
          : value !== null && typeof value === 'object' && !(value instanceof NumericScalar)
            ? (value as { [key: string]: import('./input.js').Json })[decoded]
            : undefined;
    }, root);
  const found =
    value === null
      ? 'null'
      : value instanceof NumericScalar
        ? 'number'
        : Array.isArray(value)
          ? 'array'
          : typeof value;
  return `${path}: ${found} found, ${expected} expected`;
}
