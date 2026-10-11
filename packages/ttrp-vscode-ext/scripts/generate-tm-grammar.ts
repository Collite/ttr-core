#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import fs from 'fs';
import path from 'path';

// The sibling .js is emitted by `pnpm run build-generator`. Do not edit the .js by hand.
// Mirrors packages/vscode-ext/scripts/generate-tm-grammar.ts (TTR-M) — same shape, TTR-P scopes.

function getScriptDir(): string {
  if (process.argv[1]) return path.dirname(path.resolve(process.argv[1]));
  return '<unknown-script-dir>';
}

const scriptDir = getScriptDir();
const monorepoRoot = path.resolve(scriptDir, '..', '..', '..');
const GRAMMAR_PATH = path.join(monorepoRoot, 'packages', 'grammar', 'src', 'TTRP.g4');
const OUTPUT_PATH = path.join(scriptDir, '..', 'syntaxes', 'ttrp.tmLanguage.json');

export interface TokenDef {
  name: string;
  literal: string;
}

/** Parse `NAME : 'literal' ;` lexer rules out of the .g4. */
export function parseGrammar(g4: string): TokenDef[] {
  const tokens: TokenDef[] = [];
  const ruleRegex = /^([A-Z_][A-Z0-9_]*)\s*:\s*(.+?)\s*;/gm;
  let match: RegExpExecArray | null;
  while ((match = ruleRegex.exec(g4)) !== null) {
    const name = match[1];
    for (const alt of match[2].split(/\s*\|\s*/)) {
      const t = alt.trim();
      if (t.startsWith("'") && t.endsWith("'")) tokens.push({ name, literal: t.slice(1, -1) });
    }
  }
  return tokens;
}

/** Reserved port names (S10) — highlighted as language variables. */
const RESERVED_PORTS = ['in', 'out', 'err', 'rejects', 'true', 'false', 'else'];
/** Control keywords (C3-e) + reserved `finishes` (TTRP-CTL-001). */
const CONTROL_KEYWORDS = ['after', 'with', 'control', 'finishes'];
const DECLARATION_KEYWORDS = ['uses', 'world', 'import', 'container', 'def', 'target', 'schema', 'relation'];
const OPERATOR_KEYWORDS = ['and', 'or', 'not', 'is', 'between', 'in', 'group', 'by', 'as', 'distinct', 'cast'];
const CASE_KEYWORDS = ['case', 'when', 'then', 'else', 'end'];
const CONSTANT_KEYWORDS = ['null', 'true', 'false'];

export function tokenToScope(name: string): string | null {
  if (DECLARATION_KEYWORDS.includes(name.toLowerCase())) return 'keyword.control.declaration.ttrp';
  if (CONTROL_KEYWORDS.includes(name.toLowerCase())) return 'keyword.control.flow.ttrp';
  if (CASE_KEYWORDS.includes(name.toLowerCase())) return 'keyword.control.conditional.ttrp';
  if (CONSTANT_KEYWORDS.includes(name.toLowerCase())) return 'constant.language.ttrp';
  if (OPERATOR_KEYWORDS.includes(name.toLowerCase())) return 'keyword.operator.word.ttrp';
  if (name === 'ARROW') return 'keyword.operator.arrow.ttrp';
  return null;
}

interface Pattern {
  name?: string;
  match?: string;
  begin?: string;
  end?: string;
  contentName?: string;
  patterns?: Pattern[];
}

function keywordPattern(literals: string[], scope: string): Pattern {
  const alt = literals.map((l) => l.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|');
  return { name: scope, match: `\\b(${alt})\\b` };
}

/** Fence delegation: `"""<tag>` interiors colored by the embedded grammar (C2-f — colored, never rewritten). */
function fencePattern(tag: string, embedScope: string): Pattern {
  return {
    begin: `(""")(${tag})\\s*$`,
    end: `(""")`,
    name: `string.quoted.fenced.${tag}.ttrp`,
    contentName: `meta.embedded.block.${tag}`,
    patterns: [{ include: embedScope } as unknown as Pattern],
  };
}

export function buildGrammar(g4: string): object {
  const tokens = parseGrammar(g4);
  const seen = new Map<string, string>();
  for (const t of tokens) {
    const scope = tokenToScope(t.name);
    if (scope) seen.set(t.literal, scope);
  }

  const keywordPatterns: Pattern[] = [];
  // Group literals by scope for compact alternations.
  const byScope = new Map<string, string[]>();
  for (const [literal, scope] of seen) {
    if (!/^[a-z]+$/.test(literal)) continue; // word keywords only
    byScope.set(scope, [...(byScope.get(scope) ?? []), literal]);
  }
  for (const [scope, literals] of byScope) keywordPatterns.push(keywordPattern(literals, scope));

  return {
    $schema: 'https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json',
    name: 'TTR-P',
    scopeName: 'source.ttrp',
    comment: 'GENERATED from packages/grammar/src/TTRP.g4 by scripts/generate-tm-grammar.ts — do not hand-edit.',
    patterns: [
      { include: '#comments' },
      // Fences first: an embedded block must win over ordinary string/keyword scopes.
      fencePattern('sql', 'source.sql'),
      fencePattern('pandas', 'source.python'),
      // TTR-B in either skin: `"""ttrb` (English) and `"""ttrb-cs` (Czech) — one fence scope.
      { begin: '(""")(ttrb(?:-cs)?)\\s*$', end: '(""")', name: 'string.quoted.fenced.ttrb.ttrp' },
      { include: '#strings' },
      { include: '#reserved-ports' },
      ...keywordPatterns,
      { include: '#numbers' },
    ],
    repository: {
      comments: {
        patterns: [
          { name: 'comment.line.double-slash.ttrp', match: '//.*$' },
          { name: 'comment.block.ttrp', begin: '/\\*', end: '\\*/' },
        ],
      },
      strings: {
        patterns: [
          { name: 'string.quoted.double.ttrp', begin: '"', end: '"' },
          { name: 'string.quoted.single.ttrp', begin: "'", end: "'" },
        ],
      },
      'reserved-ports': {
        name: 'variable.language.port.ttrp',
        match: `\\b(${RESERVED_PORTS.join('|')})\\b`,
      },
      numbers: { name: 'constant.numeric.ttrp', match: '\\b[0-9]+(\\.[0-9]+)?\\b' },
    },
  };
}

export function main(): void {
  const g4 = fs.readFileSync(GRAMMAR_PATH, 'utf8');
  const grammar = buildGrammar(g4);
  fs.writeFileSync(OUTPUT_PATH, JSON.stringify(grammar, null, 2) + '\n');
  // eslint-disable-next-line no-console
  console.log(`wrote ${OUTPUT_PATH}`);
}

// Run when invoked directly (node scripts/generate-tm-grammar.js).
if (process.argv[1] && process.argv[1].endsWith('generate-tm-grammar.js')) {
  main();
}
