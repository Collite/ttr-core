// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { CharStream } from 'antlr4ng';
import { parseString } from '../index.js';
import { TTRLexer } from '../generated/TTRLexer.js';
import { TTR_NON_ID_WORDS } from '../generated/ttr-id-words.js';
import { DiagnosticCode } from '../diagnostics.js';

// Grammar 0.17 — the conceptual model (CN-P1 S1.1). The fixtures (cn contracts §1, block-form
// definitions per ⚑CN-P5) live with the Kotlin parser specs and are shared by all three runtimes; the
// conformance fixtures 75–79 mirror them byte-for-byte.
const RESOURCES = join(__dirname, '../../../kotlin/ttr-parser/src/test/resources');
const CN = join(RESOURCES, 'cn');
const CN_NEGATIVE = join(RESOURCES, 'cn-negative');
const CONFORMANCE = join(__dirname, '../../../../tests/conformance/fixtures');

const read = (dir: string, name: string) => readFileSync(join(dir, name), 'utf-8');

/** The symbolic token names the 0.17 lexer produces for `text` (hidden channel and EOF dropped). */
function tokenNames(text: string): string[] {
  const lexer = new TTRLexer(CharStream.fromString(text));
  return lexer
    .getAllTokens()
    .filter((t) => t.channel === 0)
    .map((t) => lexer.vocabulary.getSymbolicName(t.type) ?? String(t.type));
}

const CN_TOKENS = [
  'cn', 'event', 'phase', 'quality', 'relation_type', 'concept',
  'cn2md_kind', 'cn2md_role', 'cn2md_quality', 'cn2md_event',
  'cn2er_kind', 'cn2er_role', 'cn2er_event', 'cn2er_quality', 'cn2er_phase', 'cn2db_concept',
  'involves', 'period', 'grounds', 'definition', 'value_space', 'extent', 'basis', 'as',
  'default', 'sense_priority',
];

describe('grammar 0.17 — model cn', () => {
  const fixtures = readdirSync(CN).filter((f) => f.endsWith('.ttrm')).sort();

  it('has the five cn fixtures, each mirrored byte-for-byte as a conformance fixture', () => {
    expect(fixtures).toEqual([
      '75-cn-concepts.ttrm', '76-cn-bindings.ttrm', '77-cn-sugar.ttrm', '78-cn-lexicon.ttrm', '79-cn-nouns-as-ids.ttrm',
    ]);
    for (const f of fixtures) expect(read(CONFORMANCE, f), f).toBe(read(CN, f));
  });

  it.each([
    ['75-cn-concepts.ttrm', 'cn', 25],
    ['76-cn-bindings.ttrm', 'binding', 15],
    ['77-cn-sugar.ttrm', undefined, 8],
    ['78-cn-lexicon.ttrm', 'lexicon', 7],
    ['79-cn-nouns-as-ids.ttrm', 'db', 2],
  ])('parses %s with no diagnostics', (name, modelCode, defs) => {
    const { ast, errors } = parseString(read(CN, name), name);
    expect(errors).toEqual([]);
    expect(ast?.modelDirective?.modelCode).toBe(modelCode);
    expect(ast?.definitions).toHaveLength(defs);
  });

  it.each(readdirSync(CN_NEGATIVE).filter((f) => f.endsWith('.ttrm')).sort())('rejects %s', (name) => {
    const { errors } = parseString(read(CN_NEGATIVE, name), name);
    expect(errors.some((e) => e.severity === 'error')).toBe(true);
  });

  it('keeps every cn token an identifier (none joins the non-id keyword set)', () => {
    for (const word of CN_TOKENS) expect(TTR_NON_ID_WORDS.has(word), word).toBe(false);
  });

  it('lexes by longest match: the cn keywords, and the words that must stay what they were', () => {
    expect(tokenNames('cn cnc definition definitionSql relation relation_type value_space')).toEqual([
      'CN', 'CNC', 'DEFINITION', 'DEFINITION_SQL', 'RELATION', 'RELATION_TYPE', 'VALUE_SPACE',
    ]);
    expect(tokenNames('as as_of qualities definitions periods events default defaults cn2md_kinds')).toEqual([
      'AS', 'IDENT', 'IDENT', 'IDENT', 'IDENT', 'IDENT', 'DEFAULT', 'IDENT', 'IDENT',
    ]);
  });

  it('still reads a view `definitionSql:` block (DEFINITION_SQL wins over DEFINITION)', () => {
    const { ast, errors } = parseString('model db\ndef view v { definitionSql: """sql\nSELECT 1\n""" }', 'v.ttrm');
    expect(errors).toEqual([]);
    expect(ast?.definitions[0]?.kind).toBe('view');
  });

  // ⚑CN-P6 — one `roleDef` production; review-114 M5 holds outside `model cn`.
  describe('the one role production (⚑CN-P6, review-114 M5)', () => {
    const cnRole = 'def role Customer { of: Party, event: Order, grounds: md.Customer, lexicon: { terms: ["client"] } }';

    it('takes the concept properties in a `model cn` file', () => {
      expect(parseString(`model cn\n${cnRole}`, 'r.ttrm').errors).toEqual([]);
    });

    it.each(['cnc', 'automation'])('rejects them in a `model %s` file, one diagnostic per property', (model) => {
      const { ast, errors } = parseString(`model ${model}\n${cnRole}`, 'r.ttrm');
      expect(errors.map((e) => [e.code, e.severity])).toEqual(
        Array(4).fill([DiagnosticCode.UnknownProperty, 'error']),
      );
      expect(errors.map((e) => e.message.match(/'([^']+)'/)?.[1])).toEqual(['of', 'event', 'grounds', 'lexicon']);
      expect(ast?.definitions[0]?.name).toBe('Customer');
    });

    it('rejects a property typo in a cnc and an automation role, and in a file with no model', () => {
      for (const header of ['model cnc\n', 'model automation\n', '']) {
        const { errors } = parseString(`${header}def role r { lable: { en: "x" } }`, 'r.ttrm');
        expect(errors.some((e) => e.severity === 'error'), header).toBe(true);
      }
    });
  });
});
