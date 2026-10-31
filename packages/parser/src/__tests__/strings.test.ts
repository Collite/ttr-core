// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { decodeTtrString, encodeTtrString, parseString } from '../index.js';

it('decodes in one pass and encodes only the five TTR escapes', () => {
  const value = '\\d C:\\new "hi"\n\t\r\b\f\u0001😀';
  expect(decodeTtrString(encodeTtrString(value))).toBe(value);
  expect(encodeTtrString(value)).toContain('\b\f\u0001');
  expect(decodeTtrString(String.raw`"\b\f\u0001"`)).toBe('\\b\\f\\u0001');
});

it('keeps triple strings and tagged source blocks raw', () => {
  const r = parseString(String.raw`model db def query q { sourceText: """sql
select '\d\n'
""" }`);
  expect(r.errors).toEqual([]);
  expect(r.ast?.definitions[0]).toMatchObject({sourceText: {value: "select '\\d\\n'"}});
});

it('maps decoded line breaks in expression blocks to their physical source columns', async () => {
  const { conceptProperty, embeddedBlockLocation } = await import('../index.js');
  const source = String.raw`model cn def quality P { of: X, definition: "a\n😀\tb\r\"c\"" }`;
  const result = parseString(source);
  expect(result.errors).toEqual([]);
  const def = result.ast!.definitions[0];
  if (def.kind !== 'concept') throw new Error('expected concept');
  const b = conceptProperty(def, 'definition')!.block;
  expect(b.text).toBe('a\n😀\tb\r"c"');
  const location = embeddedBlockLocation(b, 1, 2, 1); // b follows the astral character and TAB
  const offset = [...source].indexOf('b', [...source].indexOf('😀'));
  expect(location.offsetStart).toBe(offset);
  expect(location.line).toBe(1);
  expect(location.column).toBe(offset);
  expect(b.textSource.endLine).toBe(1);
  expect(b.textSource.endColumn).toBe([...source.slice(0, source.lastIndexOf('"'))].length);
});
