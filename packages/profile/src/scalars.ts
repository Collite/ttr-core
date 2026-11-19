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

/** BigDecimal.toString's decimal spelling, used by Kotlin's foreign YAML -> JSON boundary. */
export function decimalText(source: string): string {
  if (/^0[xo]/.test(source)) return BigInt(source).toString();
  const match = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(source);
  if (!match || !(match[2] || match[3])) throw new Error('Expected a finite number');
  const [, sign, integer, fraction = '', exponent = '0'] = match;
  const digits = (integer + fraction).replace(/^0+/, '') || '0';
  const exponentValue = Number(exponent);
  const scale = fraction.length - exponentValue;
  if (
    !Number.isInteger(exponentValue) ||
    Math.abs(exponentValue) > 2147483647 ||
    scale < -2147483648 ||
    scale > 2147483647
  )
    throw new Error('Expected a finite number');
  const adjusted = digits.length - scale - 1;
  const prefix = sign === '-' && digits !== '0' ? '-' : '';
  if (scale >= 0 && adjusted >= -6) {
    const split = digits.length - scale;
    return (
      prefix +
      (scale === 0
        ? digits
        : split > 0
          ? digits.slice(0, split) + '.' + digits.slice(split)
          : '0.' + '0'.repeat(-split) + digits)
    );
  }
  return (
    prefix +
    digits[0] +
    (digits.length > 1 ? '.' + digits.slice(1) : '') +
    'E' +
    (adjusted >= 0 ? '+' : '') +
    adjusted
  );
}

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
