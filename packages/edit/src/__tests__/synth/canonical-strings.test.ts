// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { parseString } from '@tatrman/parser';
import { Fragment } from '../../synth/print.js';

it('synthesized quoted object values use TTR escapes and preserve other control characters', () => {
  const value = '\\d C:\\new "hi"\n\t\r\b\f\u0001';
  const fragment = new Fragment();
  fragment.object({en: value}, true);
  const result = parseString(`model cn def kind K { custom: ${fragment.source} }`);
  expect(result.errors).toEqual([]);
  expect(result.ast?.definitions[0]).toMatchObject({properties: [
    {key: 'custom', value: {kind: 'object', entries: [{key: 'en', value: {value}}]}},
  ]});
});
