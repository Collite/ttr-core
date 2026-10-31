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

it('format/reparse retains recognized and unknown escapes without JSON-only escapes', () => {
  const source = String.raw`model lexicon
    def pattern p { for: db.query.q, match: "^\d{4}\s+(\w+)$", text: "a\tb", description: "say \"hi\"\nnext" }
    def query q { parameters: [{name: p, defaultValue: "C:\\new", label: "a\tb"}] }
    def entity Customer { labelPlural: "a\tb", tags: ["\b\f\u0001"] }
  `;
  const printed = format(source, 'escapes.ttrm');
  const result = parseString(printed);
  expect(result.errors).toEqual([]);
  expect(result.ast?.definitions[0]).toMatchObject({ match: '^\\d{4}\\s+(\\w+)$', text: 'a\tb', description: {value: 'say "hi"\nnext'} });
  expect(result.ast?.definitions[1]).toMatchObject({parameters: [{defaultValue: 'C:\\new', label: 'a\tb'}]});
  expect(result.ast?.definitions[2]).toMatchObject({labelPlural: 'a\tb', tags: ['\\b\\f\\u0001']});
  expect(format(printed, 'escapes.ttrm')).toBe(printed);
});

it('does not introduce JSON-only escapes when reconstructing scalar strings', () => {
  const value = '\b\f\u0001';
  const printed = format(`model er def entity E { labelPlural: "${value}", tags: ["${value}"] }`, 'control.ttrm');
  const result = parseString(printed);
  expect(result.errors).toEqual([]);
  expect(result.ast?.definitions[0]).toMatchObject({labelPlural: value, tags: [value]});
});
