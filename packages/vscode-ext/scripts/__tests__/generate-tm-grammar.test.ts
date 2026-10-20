#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { parseGrammar, tokenToScope } from '../generate-tm-grammar.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GRAMMAR_PATH = path.resolve(__dirname, '../../../grammar/src/TTR.g4');

const EXPECTED_SCOPES = [
  'keyword.control.def.ttrm',
  'keyword.control.package.ttrm',    // NEW (v1.1)
  'keyword.control.import.ttrm',      // NEW (v1.1)
  'keyword.declaration.graph.ttrm',   // NEW (v1.1)
  'keyword.other.packages.ttrm',      // area body (v3.0; was v2.3 .ttrd)
  'keyword.other.entities.ttrm',      // area body (v3.0; was v2.3 .ttrd)
  'keyword.other.schema.ttrm',
  'keyword.other.kind.ttrm',
  'keyword.other.property.ttrm',
  'support.type.primitive.ttrm',
  'constant.language.ttrm',
  'constant.language.indextype.ttrm',
  'constant.language.constrainttype.ttrm',
  'constant.language.querylang.ttrm',
  'punctuation.separator.ttrm',
  'punctuation.section.braces.ttrm',
  'punctuation.section.brackets.ttrm',
  'punctuation.section.parens.ttrm',
];

describe('TextMate grammar generator', () => {
  const g4Content = fs.readFileSync(GRAMMAR_PATH, 'utf-8');
  const tokens = parseGrammar(g4Content);

  it('parses at least 100 tokens from TTR.g4', () => {
    expect(tokens.length).toBeGreaterThanOrEqual(100);
  });

  it('every expected scope has at least one token', () => {
    const byScope = new Map<string, number>();
    for (const token of tokens) {
      const scope = tokenToScope(token.name, token.literal);
      if (!scope) continue;
      byScope.set(scope, (byScope.get(scope) ?? 0) + 1);
    }
    for (const scope of EXPECTED_SCOPES) {
      expect(byScope.get(scope) ?? 0).toBeGreaterThan(0);
    }
  });

  it('no duplicate patterns within any scope', () => {
    const byScope = new Map<string, string[]>();
    for (const token of tokens) {
      const scope = tokenToScope(token.name, token.literal);
      if (!scope) continue;
      if (!byScope.has(scope)) byScope.set(scope, []);
      byScope.get(scope)!.push(token.literal);
    }
    for (const [, lits] of byScope) {
      const unique = [...new Set(lits)];
      expect(lits.length).toBe(unique.length);
    }
  });

  it('QUERY is classified as schema keyword, not kind', () => {
    const queryScope = tokenToScope('QUERY', 'query');
    expect(queryScope).toBe('keyword.other.schema.ttrm');
  });

  it('BOOLEAN_LITERAL maps to constant.language.ttrm (covers true|false)', () => {
    const scope = tokenToScope('BOOLEAN_LITERAL', 'true');
    expect(scope).toBe('constant.language.ttrm');
  });

  it('schema codes (DB, ER, BINDING, CNC, QUERY) all map to schema scope', () => {
    for (const [name, literal] of [['DB', 'db'], ['ER', 'er'], ['BINDING', 'binding'], ['CNC', 'cnc'], ['QUERY', 'query']]) {
      expect(tokenToScope(name, literal)).toBe('keyword.other.schema.ttrm');
    }
  });

  it('v1.1 keywords map to dedicated scopes', () => {
    expect(tokenToScope('PACKAGE', 'package')).toBe('keyword.control.package.ttrm');
    expect(tokenToScope('IMPORT',  'import')).toBe('keyword.control.import.ttrm');
    expect(tokenToScope('GRAPH',   'graph')).toBe('keyword.declaration.graph.ttrm');
    expect(tokenToScope('OBJECTS', 'objects')).toBe('keyword.other.property.ttrm');
    expect(tokenToScope('LAYOUT',  'layout')).toBe('keyword.other.property.ttrm');
  });

  it('v3.0 area keywords map to dedicated scopes', () => {
    expect(tokenToScope('AREA',     'area')).toBe('keyword.other.kind.ttrm');
    expect(tokenToScope('PACKAGES', 'packages')).toBe('keyword.other.packages.ttrm');
    expect(tokenToScope('ENTITIES', 'entities')).toBe('keyword.other.entities.ttrm');
  });

  it('SEMANTICS is a body property (blue like aliases/attributes/search), not white', () => {
    // Regression: `semantics { … }` rendered white because SEMANTICS was absent
    // from the switch → default:null. It is the structural twin of SEARCH.
    expect(tokenToScope('SEMANTICS', 'semantics')).toBe('keyword.other.property.ttrm');
    expect(tokenToScope('SEMANTICS', 'semantics')).toBe(tokenToScope('SEARCH', 'search'));
  });

  it('v4.1–4.4 keyword family all resolve to a keyword scope (none render white)', () => {
    const family: [string, string][] = [
      ['WORLD', 'world'], ['ENGINE', 'engine'], ['EXECUTOR', 'executor'], ['STORAGE', 'storage'],
      ['EXTENDS', 'extends'], ['HOSTS', 'hosts'], ['STAGING', 'staging'],
      ['SEMANTICS', 'semantics'],
      ['LEXICON', 'lexicon'], ['TERM', 'term'], ['PATTERN', 'pattern'], ['EXAMPLE', 'example'],
      ['FOR', 'for'], ['FORMS', 'forms'], ['MATCH', 'match'], ['LOCALE', 'locale'],
    ];
    for (const [name, literal] of family) {
      expect(tokenToScope(name, literal), `${name} must have a scope`).toMatch(/^keyword\./);
    }
  });

  // The regen gate (`regen-tmgrammar && git diff --exit-code`) cannot see a NEW keyword token the
  // switch does not know: it falls to `default: null` and the output does not change. Found at 0.17
  // (none of the cn tokens was scoped). Every word-literal token needs a scope now, or a place on this
  // list — the gaps that predate the guard, each a follow-up of its own feature.
  const UNSCOPED_BEFORE_THE_GUARD = new Set([
    'TTRL', 'CANVAS',                                     // .ttrl layout sidecar (v4.3)
    'MANAGEMENT', 'CHANGE_SEMANTICS', 'WRITEBACK',        // db table management (0.10, EN-P1)
    'SECURITY', 'OWN', 'CLASSIFY', 'GRANT', 'MASK', 'ON', // security block (0.11, PL-P4)
  ]);

  it('every keyword token has a scope (or is a known pre-guard gap)', () => {
    const unscoped = tokens
      .filter(t => /^[A-Za-z_][A-Za-z0-9_]*$/.test(t.literal))
      .filter(t => tokenToScope(t.name, t.literal) === null)
      .map(t => t.name)
      .filter(name => !UNSCOPED_BEFORE_THE_GUARD.has(name));
    expect(unscoped).toEqual([]);
  });

  it('the pre-guard list holds only tokens still unscoped (shrinks as they are fixed)', () => {
    for (const name of UNSCOPED_BEFORE_THE_GUARD) {
      const t = tokens.find(x => x.name === name);
      expect(t, `${name} is no longer in TTR.g4 — drop it from the list`).toBeDefined();
      expect(tokenToScope(name, t!.literal), `${name} is scoped now — drop it from the list`).toBeNull();
    }
  });

  it('0.17 cn tokens: model code, kinds, properties and the `as` operator word', () => {
    expect(tokenToScope('CN', 'cn')).toBe('keyword.other.schema.ttrm');
    for (const [name, literal] of [['EVENT', 'event'], ['PHASE', 'phase'], ['QUALITY', 'quality'],
      ['RELATION_TYPE', 'relation_type'], ['CN2MD_KIND', 'cn2md_kind'], ['CN2ER_EVENT', 'cn2er_event'],
      ['CN2DB_CONCEPT', 'cn2db_concept']]) {
      expect(tokenToScope(name, literal), name).toBe('keyword.other.kind.ttrm');
    }
    for (const [name, literal] of [['CONCEPT', 'concept'], ['INVOLVES', 'involves'], ['PERIOD', 'period'],
      ['GROUNDS', 'grounds'], ['DEFINITION', 'definition'], ['VALUE_SPACE', 'value_space'],
      ['EXTENT', 'extent'], ['BASIS', 'basis'], ['DEFAULT', 'default'], ['SENSE_PRIORITY', 'sense_priority']]) {
      expect(tokenToScope(name, literal), name).toBe('keyword.other.property.ttrm');
    }
    expect(tokenToScope('AS', 'as')).toBe('keyword.operator.word.ttrm');
  });

  it('NULL_LITERAL is a language constant (grouped with true|false)', () => {
    expect(tokenToScope('NULL_LITERAL', 'null')).toBe('constant.language.ttrm');
    expect(tokenToScope('NULL_LITERAL', 'null')).toBe(tokenToScope('BOOLEAN_LITERAL', 'true'));
  });

  // Emission gap: primitive types + language constants were mapped in the switch
  // but never reached the output (no top-level pattern referenced them), so they
  // rendered white. This reads the COMMITTED grammar to guard the wiring, not
  // just tokenToScope.
  describe('emitted ttrm.tmLanguage.json wires up literal scopes', () => {
    const OUT = path.resolve(__dirname, '../../syntaxes/ttrm.tmLanguage.json');
    const grammar = JSON.parse(fs.readFileSync(OUT, 'utf-8'));
    const topIncludes = (grammar.patterns as { include?: string }[]).map(p => p.include);
    const matchFor = (scope: string): string =>
      grammar.repository[scope.replace(/\./g, '_')]?.patterns?.[0]?.match ?? '';

    it('top-level patterns reference #literals', () => {
      expect(topIncludes).toContain('#literals');
    });

    it('primitive types (text/int/bool) are emitted under support.type.primitive', () => {
      const m = matchFor('support.type.primitive.ttrm');
      for (const w of ['text', 'int', 'bool']) expect(m).toMatch(new RegExp(`\\b${w}\\b`));
    });

    it('true/false/null are emitted under constant.language', () => {
      const m = matchFor('constant.language.ttrm');
      for (const w of ['true', 'false', 'null']) expect(m).toMatch(new RegExp(`\\b${w}\\b`));
    });

    it('every #literals include resolves to a non-empty repository entry', () => {
      const litIncludes: string[] = grammar.repository['literals'].patterns.map((p: { include: string }) => p.include);
      expect(litIncludes.length).toBeGreaterThan(0);
      for (const inc of litIncludes) {
        const key = inc.slice(1); // drop leading '#'
        const match = grammar.repository[key]?.patterns?.[0]?.match ?? '';
        expect(match, `${inc} must have a non-vacuous match`).not.toMatch(/\\b\(\)\\b/);
        expect(match.length).toBeGreaterThan(0);
      }
    });
  });
});