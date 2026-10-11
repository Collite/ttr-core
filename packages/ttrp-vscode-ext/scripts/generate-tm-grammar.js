#!/usr/bin/env node
"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.parseGrammar = parseGrammar;
exports.tokenToScope = tokenToScope;
exports.buildGrammar = buildGrammar;
exports.main = main;
// SPDX-License-Identifier: Apache-2.0
const fs_1 = __importDefault(require("fs"));
const path_1 = __importDefault(require("path"));
// The sibling .js is emitted by `pnpm run build-generator`. Do not edit the .js by hand.
// Mirrors packages/vscode-ext/scripts/generate-tm-grammar.ts (TTR-M) — same shape, TTR-P scopes.
function getScriptDir() {
    if (process.argv[1])
        return path_1.default.dirname(path_1.default.resolve(process.argv[1]));
    return '<unknown-script-dir>';
}
const scriptDir = getScriptDir();
const monorepoRoot = path_1.default.resolve(scriptDir, '..', '..', '..');
const GRAMMAR_PATH = path_1.default.join(monorepoRoot, 'packages', 'grammar', 'src', 'TTRP.g4');
const OUTPUT_PATH = path_1.default.join(scriptDir, '..', 'syntaxes', 'ttrp.tmLanguage.json');
/** Parse `NAME : 'literal' ;` lexer rules out of the .g4. */
function parseGrammar(g4) {
    const tokens = [];
    const ruleRegex = /^([A-Z_][A-Z0-9_]*)\s*:\s*(.+?)\s*;/gm;
    let match;
    while ((match = ruleRegex.exec(g4)) !== null) {
        const name = match[1];
        for (const alt of match[2].split(/\s*\|\s*/)) {
            const t = alt.trim();
            if (t.startsWith("'") && t.endsWith("'"))
                tokens.push({ name, literal: t.slice(1, -1) });
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
function tokenToScope(name) {
    if (DECLARATION_KEYWORDS.includes(name.toLowerCase()))
        return 'keyword.control.declaration.ttrp';
    if (CONTROL_KEYWORDS.includes(name.toLowerCase()))
        return 'keyword.control.flow.ttrp';
    if (CASE_KEYWORDS.includes(name.toLowerCase()))
        return 'keyword.control.conditional.ttrp';
    if (CONSTANT_KEYWORDS.includes(name.toLowerCase()))
        return 'constant.language.ttrp';
    if (OPERATOR_KEYWORDS.includes(name.toLowerCase()))
        return 'keyword.operator.word.ttrp';
    if (name === 'ARROW')
        return 'keyword.operator.arrow.ttrp';
    return null;
}
function keywordPattern(literals, scope) {
    const alt = literals.map((l) => l.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|');
    return { name: scope, match: `\\b(${alt})\\b` };
}
/** Fence delegation: `"""<tag>` interiors colored by the embedded grammar (C2-f — colored, never rewritten). */
function fencePattern(tag, embedScope) {
    return {
        begin: `(""")(${tag})\\s*$`,
        end: `(""")`,
        name: `string.quoted.fenced.${tag}.ttrp`,
        contentName: `meta.embedded.block.${tag}`,
        patterns: [{ include: embedScope }],
    };
}
function buildGrammar(g4) {
    const tokens = parseGrammar(g4);
    const seen = new Map();
    for (const t of tokens) {
        const scope = tokenToScope(t.name);
        if (scope)
            seen.set(t.literal, scope);
    }
    const keywordPatterns = [];
    // Group literals by scope for compact alternations.
    const byScope = new Map();
    for (const [literal, scope] of seen) {
        if (!/^[a-z]+$/.test(literal))
            continue; // word keywords only
        byScope.set(scope, [...(byScope.get(scope) ?? []), literal]);
    }
    for (const [scope, literals] of byScope)
        keywordPatterns.push(keywordPattern(literals, scope));
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
function main() {
    const g4 = fs_1.default.readFileSync(GRAMMAR_PATH, 'utf8');
    const grammar = buildGrammar(g4);
    fs_1.default.writeFileSync(OUTPUT_PATH, JSON.stringify(grammar, null, 2) + '\n');
    // eslint-disable-next-line no-console
    console.log(`wrote ${OUTPUT_PATH}`);
}
// Run when invoked directly (node scripts/generate-tm-grammar.js).
if (process.argv[1] && process.argv[1].endsWith('generate-tm-grammar.js')) {
    main();
}
