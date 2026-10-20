#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// 0.17 conceptual model (CN-P1 S1.4 T1) — the COMMITTED ttrm.tmLanguage.json, run through
// vscode-textmate + oniguruma (the engine VS Code itself uses) over conformance fixtures 75–78.
// Targeted assertions first, then a compact per-line snapshot of every scoped token.
import { describe, it, expect, beforeAll } from 'vitest';
import fs from 'fs';
import path from 'path';
import { createRequire } from 'module';
import { fileURLToPath } from 'url';
import { PL_CONDITIONAL_WORDS, PL_CONSTANTS, PL_OPERATOR_WORDS } from '../generate-tm-grammar.js';

const require = createRequire(import.meta.url);
// Both packages ship UMD bundles; `require` reads them without ESM interop guesswork.
const vsctm = require('vscode-textmate') as typeof import('vscode-textmate');
const oniguruma = require('vscode-oniguruma') as typeof import('vscode-oniguruma');

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TTRM_PATH = path.resolve(__dirname, '../../syntaxes/ttrm.tmLanguage.json');
const TTRP_PATH = path.resolve(__dirname, '../../../ttrp-vscode-ext/syntaxes/ttrp.tmLanguage.json');
const FIXTURES = path.resolve(__dirname, '../../../../tests/conformance/fixtures');
const CN_FIXTURES = ['75-cn-concepts.ttrm', '76-cn-bindings.ttrm', '77-cn-sugar.ttrm', '78-cn-lexicon.ttrm'];

interface Tok { text: string; scopes: string[] }

let grammar: import('vscode-textmate').IGrammar;

beforeAll(async () => {
  const wasm = fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm'));
  await oniguruma.loadWASM(wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength));
  const registry = new vsctm.Registry({
    onigLib: Promise.resolve({
      createOnigScanner: (patterns: string[]) => new oniguruma.OnigScanner(patterns),
      createOnigString: (s: string) => new oniguruma.OnigString(s),
    }),
    loadGrammar: async (scopeName: string) =>
      scopeName === 'source.ttrm'
        ? vsctm.parseRawGrammar(fs.readFileSync(TTRM_PATH, 'utf-8'), TTRM_PATH)
        : null,
  });
  const g = await registry.loadGrammar('source.ttrm');
  if (!g) throw new Error('source.ttrm did not load');
  grammar = g;
});

function tokenize(text: string): Tok[][] {
  let stack = vsctm.INITIAL;
  return text.split(/\r?\n/).map(line => {
    const r = grammar.tokenizeLine(line, stack);
    stack = r.ruleStack;
    return r.tokens.map(t => ({ text: line.slice(t.startIndex, t.endIndex), scopes: t.scopes.slice(1) }));
  });
}

/** The innermost scope of the first token whose text is exactly `text` (trimmed). */
function scopesOf(lines: Tok[][], text: string): string[] {
  for (const line of lines) {
    for (const t of line) if (t.text.trim() === text) return t.scopes;
  }
  throw new Error(`no token "${text}"`);
}

/** The first code line (not a comment) containing `needle`. */
function lineOf(lines: Tok[][], needle: string): Tok[] {
  const hit = lines.find(l => !l[0]?.text.trimStart().startsWith('//') && l.map(t => t.text).join('').includes(needle));
  if (!hit) throw new Error(`no line with "${needle}"`);
  return hit;
}

function tokenIn(line: Tok[], text: string): Tok {
  const t = line.find(x => x.text.trim() === text);
  if (!t) throw new Error(`no token "${text}" in: ${line.map(x => x.text).join('')}`);
  return t;
}

const fixture = (name: string) => tokenize(fs.readFileSync(path.join(FIXTURES, name), 'utf-8'));

describe('ttrm.tmLanguage.json — the 0.17 conceptual model', () => {
  it('scopes `model cn` and the `cn.` ref head as a schema code', () => {
    const f75 = fixture('75-cn-concepts.ttrm');
    expect(tokenIn(lineOf(f75, 'model cn'), 'cn').scopes).toContain('keyword.other.schema.ttrm');
    const f76 = fixture('76-cn-bindings.ttrm');
    expect(tokenIn(lineOf(f76, 'kind: cn.Item'), 'cn').scopes).toContain('keyword.other.schema.ttrm');
  });

  it('scopes `def kind` as a def kind, and `kind:` stays a property key', () => {
    const f75 = fixture('75-cn-concepts.ttrm');
    const defKind = lineOf(f75, 'def kind Item');
    expect(tokenIn(defKind, 'def').scopes).toContain('keyword.control.def.ttrm');
    expect(tokenIn(defKind, 'kind').scopes).toContain('keyword.other.kind.ttrm');
    const f76 = fixture('76-cn-bindings.ttrm');
    expect(tokenIn(lineOf(f76, 'kind: cn.Item'), 'kind').scopes).toContain('keyword.other.property.ttrm');
  });

  it('scopes every concept def noun and every binding kind as a kind', () => {
    const f75 = fixture('75-cn-concepts.ttrm');
    for (const noun of ['role', 'event', 'quality', 'phase', 'relation_type']) {
      expect(tokenIn(lineOf(f75, `def ${noun} `), noun).scopes, noun).toContain('keyword.other.kind.ttrm');
    }
    const f76 = fixture('76-cn-bindings.ttrm');
    for (const kind of ['cn2md_kind', 'cn2md_role', 'cn2md_quality', 'cn2md_event', 'cn2er_kind', 'cn2er_role',
      'cn2er_event', 'cn2er_quality', 'cn2er_phase', 'cn2db_concept']) {
      expect(tokenIn(lineOf(f76, `def ${kind} `), kind).scopes, kind).toContain('keyword.other.kind.ttrm');
    }
  });

  it('scopes the cn property keys, the sugar and the lexicon keys as properties', () => {
    const f75 = fixture('75-cn-concepts.ttrm');
    // (`hierarchy:` keeps the md `def hierarchy` kind scope — TextMate scopes a word, not its slot.)
    for (const key of ['involves', 'period', 'grounds', 'definition', 'value_space', 'extent', 'basis',
      'members']) {
      expect(scopesOf(f75, key), key).toContain('keyword.other.property.ttrm');
    }
    expect(scopesOf(fixture('77-cn-sugar.ttrm'), 'concept')).toContain('keyword.other.property.ttrm');
    const f78 = fixture('78-cn-lexicon.ttrm');
    for (const key of ['default', 'sense_priority']) {
      expect(scopesOf(f78, key), key).toContain('keyword.other.property.ttrm');
    }
  });

  it('scopes `as` in a labelled participant as an operator word', () => {
    const line = lineOf(fixture('75-cn-concepts.ttrm'), 'Warehouse as fulfilling');
    expect(tokenIn(line, 'as').scopes).toContain('keyword.operator.word.ttrm');
  });

  it('a """pl-tagged block is an embedded pl expression to its closing fence', () => {
    const f75 = fixture('75-cn-concepts.ttrm');
    const open = lineOf(f75, 'definition: """pl');
    expect(tokenIn(open, 'pl').scopes).toContain('entity.name.tag.language.ttrm');
    const body = lineOf(f75, 'this.period.at - this.from.period.at');
    expect(tokenIn(body, '-').scopes).toEqual(['meta.embedded.block.pl.ttrm', 'keyword.operator.ttrm']);
    // The block closes: the next def is back at top level.
    const after = lineOf(f75, 'def phase PendingOrder');
    expect(tokenIn(after, 'def').scopes).toEqual(['keyword.control.def.ttrm']);
  });

  it('an untagged triple-string after definition: / members: / filter: is a pl expression', () => {
    const f75 = fixture('75-cn-concepts.ttrm');
    const pending = lineOf(f75, `definition: """state in ('placed', 'held')"""`);
    expect(tokenIn(pending, 'definition').scopes).toContain('keyword.other.property.ttrm');
    expect(tokenIn(pending, 'in').scopes).toContain('meta.embedded.block.pl.ttrm');
    expect(tokenIn(pending, 'in').scopes).toContain('keyword.operator.word.ttrm');
    expect(tokenIn(pending, "'placed'").scopes).toContain('string.quoted.single.ttrm');
    const members = lineOf(f75, 'members: """descendants');
    expect(tokenIn(members, "'H10-H19'").scopes).toContain('meta.embedded.block.pl.ttrm');
    const filter = lineOf(fixture('76-cn-bindings.ttrm'), 'filter: """warehouse = 1"""');
    expect(tokenIn(filter, '1').scopes).toEqual(['meta.embedded.block.pl.ttrm', 'constant.numeric.ttrm']);
  });

  it('a plain string after definition: is a one-line pl expression', () => {
    const line = lineOf(fixture('75-cn-concepts.ttrm'), 'definition: "amount - paid"');
    expect(tokenIn(line, '-').scopes).toEqual(['meta.embedded.line.pl.ttrm', 'keyword.operator.ttrm']);
  });

  it('leaves definitionSql and other tagged blocks to the plain string rules', () => {
    const lines = tokenize([
      'def view v {',
      '    definitionSql: """sql',
      '        select 1 as one',
      '    """',
      '    description: """state = 1"""',
      '}',
    ].join('\n'));
    expect(tokenIn(lines[1], 'definitionSql').scopes).toContain('keyword.other.property.ttrm');
    expect(lines[2][0].scopes).toEqual(['string.quoted.triple.ttrm']);
    expect(lines[4].find(t => t.text.includes('state'))?.scopes).toEqual(['string.quoted.triple.ttrm']);
    expect(tokenIn(lines[5], '}').scopes).toEqual(['punctuation.section.braces.ttrm']);
  });

  // review-117 M-2: nothing inside an expression may run past its block — a quote in a comment, an
  // unclosed comment, a `"""pl` first line outside the three keys.
  it('never lets an expression region run past its block', () => {
    const lines = tokenize([
      'model cn',
      'def phase H {',
      '  definition = """pl',
      "    state = 'held'   // the customer's hold",
      '    and a = 1 /* open',
      '  """',
      '}',
      'def quality q { definition: "a /* open b", of: X }',
      'def quality r { definition: "a // tail", grounds: md.r }',
      'def entity e { description: """pl',
      '    plain text',
      '""" }',
      'def kind After { grounds: md.after }',
      // review-118 M-1: an escaped quote inside a comment of the string form does not close the string.
      'def phase X { of: Order, definition: "a = 1 // it\\"s" }',
      'def phase Y { of: Order, definition: "a /* it\\"s */ = 1" }',
      'def kind Last { grounds: md.last }',
    ].join('\n'));
    expect(tokenIn(lines[13], '}').scopes).toEqual(['punctuation.section.braces.ttrm']);
    expect(tokenIn(lines[14], '}').scopes).toEqual(['punctuation.section.braces.ttrm']);
    expect(tokenIn(lines[15], 'grounds').scopes).toEqual(['keyword.other.property.ttrm']);
    expect(tokenIn(lines[2], 'definition').scopes).toContain('keyword.other.property.ttrm');
    expect(tokenIn(lines[2], 'pl').scopes).toContain('entity.name.tag.language.ttrm');
    expect(tokenIn(lines[3], "// the customer's hold").scopes).toContain('comment.line.double-slash.ttrm');
    expect(tokenIn(lines[6], '}').scopes).toEqual(['punctuation.section.braces.ttrm']);
    expect(tokenIn(lines[7], 'of').scopes).toEqual([]);
    expect(tokenIn(lines[8], 'grounds').scopes).toEqual(['keyword.other.property.ttrm']);
    expect(tokenIn(lines[10], 'plain text').scopes).toEqual(['string.quoted.triple.ttrm']);
    expect(tokenIn(lines[12], 'grounds').scopes).toEqual(['keyword.other.property.ttrm']);
  });

  it('pl expression words match the TTR-P grammar (no silent drift)', () => {
    const ttrp = JSON.parse(fs.readFileSync(TTRP_PATH, 'utf-8')) as { patterns: { name?: string; match?: string }[] };
    const words = (scope: string) => {
      const m = ttrp.patterns.find(p => p.name === scope)?.match ?? '';
      return /\\b\(([^)]*)\)\\b/.exec(m)?.[1].split('|') ?? [];
    };
    expect(PL_OPERATOR_WORDS).toEqual(words('keyword.operator.word.ttrp'));
    expect(PL_CONDITIONAL_WORDS).toEqual(words('keyword.control.conditional.ttrp'));
    expect(PL_CONSTANTS).toEqual(words('constant.language.ttrp'));
  });

  // The compact snapshot: per line, every scoped word as `text=innermost scope` (comments and
  // punctuation left out — the fixture headers are prose, the fences are asserted above).
  it.each(CN_FIXTURES)('tokenizes %s (snapshot)', name => {
    const lines = fixture(name);
    const rendered = lines
      .map((line, i) => {
        const scoped = line
          .filter(t => t.text.trim() !== '' && t.scopes.length > 0)
          .filter(t => !t.scopes[0].startsWith('comment.') && !t.scopes[t.scopes.length - 1].startsWith('punctuation.'))
          .map(t => `${t.text.trim()}=${t.scopes[t.scopes.length - 1].replace(/\.ttrm$/, '')}`);
        return scoped.length > 0 ? `${String(i + 1).padStart(3)}  ${scoped.join('  ')}` : null;
      })
      .filter((l): l is string => l !== null)
      .join('\n');
    expect(rendered).toMatchSnapshot();
  });
});
