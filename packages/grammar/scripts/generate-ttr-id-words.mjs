#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// The TTR words that cannot be an `id` part: the lexer keywords of TTR.g4 whose token the `idPart` rule
// does not admit (`text`, `date`, `true`, `description`, …). A string is a TTR `id` iff it is dot-separated
// IDENT-shaped parts none of which is one of these — the layout module's `isTtrId` (`@tatrman/parser/layout`)
// decides that without the ANTLR runtime, so the Studio Viewer's bundle stays free of it. The parser's
// tests hold the result to the generated parser's own `id` rule.
//
//   node generate-ttr-id-words.mjs <TTR.g4> <out.ts>

import { readFileSync, writeFileSync } from 'node:fs';

const [grammarFile, outFile] = process.argv.slice(2);
if (!grammarFile || !outFile) {
  console.error('usage: generate-ttr-id-words.mjs <TTR.g4> <out.ts>');
  process.exit(2);
}

const g4 = readFileSync(grammarFile, 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/\/\/[^\n]*/g, '');
const idPart = /\bidPart\s*:([\s\S]*?);/.exec(g4);
if (!idPart) {
  console.error(`${grammarFile}: no idPart rule`);
  process.exit(1);
}
const inIdPart = new Set(idPart[1].match(/\b[A-Z][A-Z0-9_]*\b/g));
const word = /^[A-Za-z_À-ɏ][A-Za-z0-9_À-ɏ]*$/;
const words = new Set();
// a lexer rule (upper-case name) made only of literal alternatives, each a word: a keyword token
for (const m of g4.matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*:\s*([^;]*?);/gm)) {
  const literals = m[2].split('|').map((a) => /^'([^']*)'$/.exec(a.replace(/->.*/, '').trim())?.[1]);
  if (inIdPart.has(m[1]) || literals.some((l) => l === undefined)) continue;
  for (const l of literals) if (word.test(l)) words.add(l);
}
if (words.size === 0) {
  console.error(`${grammarFile}: no keyword outside idPart — the grammar moved; check this script`);
  process.exit(1);
}

const sorted = [...words].sort();
const lines = [];
for (let i = 0; i < sorted.length; i += 8) lines.push(`  ${sorted.slice(i, i + 8).map((w) => `'${w}'`).join(', ')},`);
writeFileSync(
  outFile,
  [
    '// SPDX-License-Identifier: Apache-2.0',
    '// GENERATED from TTR.g4 by packages/grammar/scripts/generate-ttr-id-words.mjs — do not edit.',
    '',
    '/** The lexer keywords of `TTR.g4` that the `idPart` rule does not admit — an `id` part spelled like one is not an `id`. */',
    'export const TTR_NON_ID_WORDS: ReadonlySet<string> = new Set([',
    ...lines,
    ']);',
    '',
  ].join('\n'),
);
console.log(`${sorted.length} non-id keywords written to ${outFile}`);
