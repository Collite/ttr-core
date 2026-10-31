// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { parseString } from '@tatrman/parser';
import { format } from '../index.js';

it('format/reparse retains explicit false, empty default, bindings and lexical policy', () => {
  const source = `model db
    def query q { skipSecurity: false, parameters: [{name: p, type: text, defaultValue: "", binds: [er.Customer.name, er.Customer.code]}] }
  `;
  const printed = format(source, 'q.ttrm');
  expect(format(printed, 'q.ttrm')).toBe(printed);
  expect(parseString(printed).ast?.definitions[0]).toMatchObject({
    skipSecurity: false,
    parameters: [{ defaultValue: '', binds: [{path: 'er.Customer.name'}, {path: 'er.Customer.code'}] }],
  });
  const lex = format('model lexicon def pattern pair { for: db.query.q, match: "name (.*)", text: "Name Alice?", indexSecurity: none }', 'lex.ttrm');
  expect(parseString(lex).ast?.definitions[0]).toMatchObject({match: 'name (.*)', text: 'Name Alice?', indexSecurity: 'none'});
});
