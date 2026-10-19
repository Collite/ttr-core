#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import fs from 'fs';
import path from 'path';

// The sibling .js is emitted by `pnpm run build-generator`. Do not edit the .js by hand.

function getScriptDir(): string {
  if (process.argv[1]) {
    return path.dirname(path.resolve(process.argv[1]));
  }
  return '<unknown-script-dir>';
}

const __dirname_script = getScriptDir();
const monorepoRoot = path.resolve(__dirname_script, '..', '..', '..');

const GRAMMAR_PATH = path.join(monorepoRoot, 'packages', 'grammar', 'src', 'TTR.g4');
const OUTPUT_PATH  = path.join(__dirname_script, '..', 'syntaxes', 'ttrm.tmLanguage.json');

interface TokenDef {
  name: string;
  literal: string;
}

interface ScopeRule {
  scope: string;
  match?: string;
  begin?: string;
  end?: string;
  patterns?: ScopeRule[];
}

export function parseGrammar(g4Content: string): TokenDef[] {
  const tokens: TokenDef[] = [];
  const lexerRuleRegex = /^([A-Z_][A-Z0-9_]*)\s*:\s*(.+?)\s*;/gm;
  let match;
  while ((match = lexerRuleRegex.exec(g4Content)) !== null) {
    const name = match[1];
    const rhs = match[2];
    const alternatives = rhs.split(/\s*\|\s*/);
    for (const alt of alternatives) {
      const trimmed = alt.trim();
      if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
        tokens.push({ name, literal: trimmed.slice(1, -1) });
      }
    }
  }
  return tokens;
}

export function tokenToScope(name: string, literal: string): string | null {
  switch (name) {
    // v1.1 top-level keywords
    case 'PACKAGE': return 'keyword.control.package.ttrm';
    case 'IMPORT': return 'keyword.control.import.ttrm';
    case 'GRAPH': return 'keyword.declaration.graph.ttrm';
    // v1.1 graph body keywords
    case 'OBJECTS': return 'keyword.other.property.ttrm';
    case 'LAYOUT': return 'keyword.other.property.ttrm';
    // v3.0 subject area + its body keywords (DOMAIN removed)
    case 'AREA': return 'keyword.other.kind.ttrm';
    case 'PACKAGES': return 'keyword.other.packages.ttrm';
    case 'ENTITIES': return 'keyword.other.entities.ttrm';
    case 'DEF': return 'keyword.control.def.ttrm';
    // v4.0 — `model <code> schema <id>` directive: MODEL + SCHEMA are control
    // keywords; NAMESPACE was deleted. PROJECT is the `def project` kind keyword.
    case 'MODEL': return 'keyword.control.def.ttrm';
    case 'SCHEMA': return 'keyword.control.def.ttrm';
    case 'PROJECT': return 'keyword.other.kind.ttrm';
    case 'DB': return 'keyword.other.schema.ttrm';
    case 'ER': return 'keyword.other.schema.ttrm';
    case 'BINDING': return 'keyword.other.schema.ttrm';
    case 'CNC': return 'keyword.other.schema.ttrm';
    case 'QUERY': return 'keyword.other.schema.ttrm';
    case 'TABLE': return 'keyword.other.kind.ttrm';
    case 'VIEW': return 'keyword.other.kind.ttrm';
    case 'COLUMN': return 'keyword.other.kind.ttrm';
    case 'INDEX': return 'keyword.other.kind.ttrm';
    case 'CONSTRAINT': return 'keyword.other.kind.ttrm';
    case 'FK': return 'keyword.other.kind.ttrm';
    case 'PROCEDURE': return 'keyword.other.kind.ttrm';
    case 'ENTITY': return 'keyword.other.kind.ttrm';
    case 'ATTRIBUTE': return 'keyword.other.kind.ttrm';
    case 'RELATION': return 'keyword.other.kind.ttrm';
    case 'ER2DB_ENTITY': return 'keyword.other.kind.ttrm';
    case 'ER2DB_ATTRIBUTE': return 'keyword.other.kind.ttrm';
    case 'ER2DB_RELATION': return 'keyword.other.kind.ttrm';
    case 'ROLE': return 'keyword.other.kind.ttrm';
    case 'ER2CNC_ROLE': return 'keyword.other.kind.ttrm';
    case 'DRILL_MAP': return 'keyword.other.kind.ttrm';
    case 'ARGS': return 'keyword.other.property.ttrm';
    case 'DISPLAY': return 'keyword.other.property.ttrm';
    case 'OVERRIDE': return 'keyword.other.property.ttrm';
    case 'DESCRIPTION': return 'keyword.other.property.ttrm';
    case 'TAGS': return 'keyword.other.property.ttrm';
    case 'VERSION': return 'keyword.other.property.ttrm';
    case 'PRIMARY_KEY': return 'keyword.other.property.ttrm';
    case 'COLUMNS': return 'keyword.other.property.ttrm';
    case 'INDICES': return 'keyword.other.property.ttrm';
    case 'CONSTRAINTS': return 'keyword.other.property.ttrm';
    case 'ATTRIBUTES': return 'keyword.other.property.ttrm';
    case 'PARAMETERS': return 'keyword.other.property.ttrm';
    case 'RESULT_COLUMNS': return 'keyword.other.property.ttrm';
    case 'DEFINITION_SQL': return 'keyword.other.property.ttrm';
    case 'DATA_TYPE': return 'keyword.other.property.ttrm';
    case 'OPTIONAL': return 'keyword.other.property.ttrm';
    case 'IS_KEY': return 'keyword.other.property.ttrm';
    case 'SEARCHABLE': return 'keyword.other.property.ttrm';
    case 'INDEXED': return 'keyword.other.property.ttrm';
    case 'LABEL_PLURAL': return 'keyword.other.property.ttrm';
    case 'NAME_ATTRIBUTE': return 'keyword.other.property.ttrm';
    case 'CODE_ATTRIBUTE': return 'keyword.other.property.ttrm';
    case 'ALIASES': return 'keyword.other.property.ttrm';
    case 'CARDINALITY': return 'keyword.other.property.ttrm';
    case 'JOIN': return 'keyword.other.property.ttrm';
    case 'TARGET': return 'keyword.other.property.ttrm';
    case 'WHERE_FILTER': return 'keyword.other.property.ttrm';
    case 'LANGUAGE': return 'keyword.other.property.ttrm';
    case 'SOURCE_TEXT': return 'keyword.other.property.ttrm';
    case 'LENGTH': return 'keyword.other.property.ttrm';
    case 'PRECISION': return 'keyword.other.property.ttrm';
    case 'LABEL': return 'keyword.other.property.ttrm';
    case 'NAME': return 'keyword.other.property.ttrm';
    case 'DIRECTION': return 'keyword.other.property.ttrm';
    case 'DISPLAY_LABEL': return 'keyword.other.property.ttrm';
    case 'VALUE_LABELS': return 'keyword.other.property.ttrm';
    case 'ROLES': return 'keyword.other.property.ttrm';
    case 'SEARCH': return 'keyword.other.property.ttrm';
    case 'SEMANTICS': return 'keyword.other.property.ttrm';
    case 'KEYWORDS': return 'keyword.other.property.ttrm';
    case 'PATTERNS': return 'keyword.other.property.ttrm';
    case 'DESCRIPTIONS': return 'keyword.other.property.ttrm';
    case 'EXAMPLES': return 'keyword.other.property.ttrm';
    case 'FUZZY': return 'keyword.other.property.ttrm';
    // v0.12 (RV-P1.5) — the match-method attribute on `searchable`. The method
    // VALUES (EXACT/TYPOS/TOKENS) are un-minted ids, so they highlight as
    // identifiers, like `allocation: proportional`.
    case 'METHOD': return 'keyword.other.property.ttrm';
    // MAPPING token removed in v3.0; inline `binding:` highlights via the BINDING
    // schema-keyword scope (above).
    case 'FROM': return 'keyword.other.property.ttrm';
    case 'TO': return 'keyword.other.property.ttrm';
    case 'TEXT': return 'support.type.primitive.ttrm';
    case 'INT': return 'support.type.primitive.ttrm';
    case 'FLOAT': return 'support.type.primitive.ttrm';
    case 'BOOL': return 'support.type.primitive.ttrm';
    case 'DATETIME': return 'support.type.primitive.ttrm';
    case 'STRING': return 'support.type.primitive.ttrm';
    case 'BOOLEAN': return 'support.type.primitive.ttrm';
    case 'NUMBER': return 'support.type.primitive.ttrm';
    case 'INTEGER': return 'support.type.primitive.ttrm';
    case 'DOUBLE': return 'support.type.primitive.ttrm';
    case 'OBJECT': return 'support.type.primitive.ttrm';
    case 'LIST': return 'support.type.primitive.ttrm';
    case 'CHAR': return 'support.type.primitive.ttrm';
    case 'VARCHAR': return 'support.type.primitive.ttrm';
    case 'DECIMAL': return 'support.type.primitive.ttrm';
    case 'DATE': return 'support.type.primitive.ttrm';
    case 'TIMESTAMP': return 'support.type.primitive.ttrm';
    case 'PRIMARY': return 'constant.language.indextype.ttrm';
    case 'SECONDARY': return 'constant.language.indextype.ttrm';
    case 'ORDERED': return 'constant.language.indextype.ttrm';
    case 'BTREE': return 'constant.language.indextype.ttrm';
    case 'FULLTEXT': return 'constant.language.indextype.ttrm';
    case 'UNIQUE': return 'constant.language.constrainttype.ttrm';
    case 'NOT_NULL': return 'constant.language.constrainttype.ttrm';
    case 'SQL': return 'constant.language.querylang.ttrm';
    case 'TRANSFORMATION_DSL': return 'constant.language.querylang.ttrm';
    case 'DATAFRAME_DSL': return 'constant.language.querylang.ttrm';
    case 'REL_NODE': return 'constant.language.querylang.ttrm';
    case 'BOOLEAN_LITERAL': return 'constant.language.ttrm';
    case 'NULL_LITERAL': return 'constant.language.ttrm';   // `null` — language constant, alongside true|false
    case 'NUMBER_LITERAL': return null;
    case 'TRIPLE_STRING_LITERAL': return null;
    case 'STRING_LITERAL': return null;
    case 'IDENT': return null;
    case 'EQUALS': return 'punctuation.separator.ttrm';
    case 'COLON': return 'punctuation.separator.ttrm';
    case 'COMMA': return 'punctuation.separator.ttrm';
    case 'LBRACE': return 'punctuation.section.braces.ttrm';
    case 'RBRACE': return 'punctuation.section.braces.ttrm';
    case 'LBRACK': return 'punctuation.section.brackets.ttrm';
    case 'RBRACK': return 'punctuation.section.brackets.ttrm';
    case 'LPAREN': return 'punctuation.section.parens.ttrm';
    case 'RPAREN': return 'punctuation.section.parens.ttrm';
    case 'DOT': return 'punctuation.separator.ttrm';
    case 'DOTDOT': return 'punctuation.separator.ttrm';
    // v3.1 MD model — schema code, def kinds, body property keywords.
    case 'MD': return 'keyword.other.schema.ttrm';
    case 'MAP': return 'keyword.other.kind.ttrm';        // v3.1 — `def map` is now a kind
    case 'DOMAIN': return 'keyword.other.kind.ttrm';
    case 'DIMENSION': return 'keyword.other.kind.ttrm';
    case 'HIERARCHY': return 'keyword.other.kind.ttrm';
    case 'MEASURE': return 'keyword.other.kind.ttrm';
    case 'CUBELET': return 'keyword.other.kind.ttrm';
    case 'MD2DB_CUBELET': return 'keyword.other.kind.ttrm';
    case 'MD2DB_DOMAIN': return 'keyword.other.kind.ttrm';
    case 'MD2DB_MAP': return 'keyword.other.kind.ttrm';
    case 'MD2ER_CUBELET': return 'keyword.other.kind.ttrm';
    case 'RESTRICT': return 'keyword.other.property.ttrm';
    case 'MEMBERS': return 'keyword.other.property.ttrm';
    case 'PUBLISH': return 'keyword.other.property.ttrm'; // MD dot-path §1.4 — `publish: members`
    case 'KIND': return 'keyword.other.property.ttrm';
    case 'CALC': return 'keyword.other.property.ttrm';
    case 'KEY': return 'keyword.other.property.ttrm';
    case 'HIERARCHIES': return 'keyword.other.property.ttrm';
    case 'LEVELS': return 'keyword.other.property.ttrm';
    case 'VIA': return 'keyword.other.property.ttrm';
    case 'CLASS': return 'keyword.other.property.ttrm';
    case 'AGGREGATION': return 'keyword.other.property.ttrm';
    case 'VALID_BY': return 'keyword.other.property.ttrm';
    case 'GRAIN': return 'keyword.other.property.ttrm';
    case 'MEASURES': return 'keyword.other.property.ttrm';
    case 'SHAPE': return 'keyword.other.property.ttrm';
    case 'JOURNALING': return 'keyword.other.property.ttrm';
    case 'ALLOCATION': return 'keyword.other.property.ttrm';   // v0.10 — md2db_cubelet spread strategy
    case 'SOURCE': return 'keyword.other.property.ttrm';
    // v4.1 world model (ttr-metadata M0). WORLD is a schema code (`model world`)
    // like DB/ER/MD; engine/executor/storage are def-kind nouns; extends/hosts/
    // staging are body property keywords.
    case 'WORLD': return 'keyword.other.schema.ttrm';
    case 'ENGINE': return 'keyword.other.kind.ttrm';
    case 'EXECUTOR': return 'keyword.other.kind.ttrm';
    case 'STORAGE': return 'keyword.other.kind.ttrm';
    case 'EXTENDS': return 'keyword.other.property.ttrm';
    case 'HOSTS': return 'keyword.other.property.ttrm';
    case 'STAGING': return 'keyword.other.property.ttrm';
    // v4.4 lexicon surface (RG-P4). LEXICON is a model code (`model lexicon`) +
    // an inline block keyword; term/pattern/example are def-kind nouns; for/
    // forms/match are lexicon-entry body property keywords. LOCALE is NOT a body
    // property — it's the `model … locale <id>` directive header, the structural
    // sibling of SCHEMA in the same slot, so it takes SCHEMA's control scope.
    case 'LEXICON': return 'keyword.other.schema.ttrm';
    case 'TERM': return 'keyword.other.kind.ttrm';
    case 'PATTERN': return 'keyword.other.kind.ttrm';
    case 'EXAMPLE': return 'keyword.other.kind.ttrm';
    case 'FOR': return 'keyword.other.property.ttrm';
    case 'FORMS': return 'keyword.other.property.ttrm';
    case 'MATCH': return 'keyword.other.property.ttrm';
    case 'LOCALE': return 'keyword.control.def.ttrm';
    // 0.16 automation model (AU-core-P0 S0.2). AUTOMATION is a model code; the def
    // nouns (incl. the world members) are kinds; the lifecycle / transition / set
    // keywords are body properties; ARROW is the transition's punctuation.
    case 'AUTOMATION': return 'keyword.other.schema.ttrm';
    case 'PARAMETER': return 'keyword.other.kind.ttrm';
    case 'ACTION': return 'keyword.other.kind.ttrm';
    case 'SERVICE': return 'keyword.other.kind.ttrm';
    case 'APPROVAL': return 'keyword.other.kind.ttrm';
    case 'PROGRAM': return 'keyword.other.kind.ttrm';
    case 'RECIPE': return 'keyword.other.kind.ttrm';
    case 'IDENTITY': return 'keyword.other.kind.ttrm';
    case 'DEPLOYMENT': return 'keyword.other.kind.ttrm';
    case 'PROCESS': return 'keyword.other.kind.ttrm';
    case 'STEP': return 'keyword.other.kind.ttrm';
    case 'STEPS': return 'keyword.other.property.ttrm';
    case 'TRANSITION': return 'keyword.other.property.ttrm';
    case 'TRANSITIONS': return 'keyword.other.property.ttrm';
    case 'STATES': return 'keyword.other.property.ttrm';
    case 'SET': return 'keyword.other.property.ttrm';
    case 'RESOLVE': return 'keyword.other.property.ttrm';
    case 'DELIVERY': return 'keyword.other.property.ttrm';
    case 'ARROW': return 'punctuation.separator.ttrm';
    default: return null;
  }
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function buildGrammar(tokens: TokenDef[]): object {
  const byScope = new Map<string, string[]>();
  const rules: ScopeRule[] = [];

  const scopeMap: Record<string, string[]> = {
    'keyword.control.def.ttrm': [],
    'keyword.control.package.ttrm': [],
    'keyword.control.import.ttrm': [],
    'keyword.declaration.graph.ttrm': [],
    'keyword.other.packages.ttrm': [],
    'keyword.other.entities.ttrm': [],
    'keyword.other.schema.ttrm': [],
    'keyword.other.kind.ttrm': [],
    'keyword.other.property.ttrm': [],
    'support.type.primitive.ttrm': [],
    'constant.language.ttrm': [],
    'constant.language.indextype.ttrm': [],
    'constant.language.constrainttype.ttrm': [],
    'constant.language.querylang.ttrm': [],
    'punctuation.separator.ttrm': [],
    'punctuation.section.braces.ttrm': [],
    'punctuation.section.brackets.ttrm': [],
    'punctuation.section.parens.ttrm': [],
  };

  for (const token of tokens) {
    const scope = tokenToScope(token.name, token.literal);
    if (!scope) continue;
    if (!scopeMap[scope]) scopeMap[scope] = [];
    scopeMap[scope].push(escapeRegex(token.literal));
  }

  const keywordScopes = [
    'keyword.control.def.ttrm',
    'keyword.control.package.ttrm',
    'keyword.control.import.ttrm',
    'keyword.declaration.graph.ttrm',
    'keyword.other.packages.ttrm',
    'keyword.other.entities.ttrm',
    'keyword.other.schema.ttrm',
    'keyword.other.kind.ttrm',
    'keyword.other.property.ttrm',
  ];

  // Non-keyword word scopes: primitive type names (text/int/…) and language
  // constants (true/false/null, index/constraint/query-lang enums). These were
  // populated in scopeMap but never emitted — no top-level pattern referenced
  // them — so they rendered white. Emit them now. Filter empties so we never
  // produce a vacuous `\b()\b` regex if a future grammar drops a whole class.
  const literalScopes = [
    'support.type.primitive.ttrm',
    'constant.language.ttrm',
    'constant.language.indextype.ttrm',
    'constant.language.constrainttype.ttrm',
    'constant.language.querylang.ttrm',
  ].filter(scope => (scopeMap[scope]?.length ?? 0) > 0);

  const keywordsRepo: { scope: string; match: string; }[] = [];
  for (const scope of keywordScopes) {
    const rules = scopeMap[scope];
    if (rules.length > 0) {
      const combined = rules.join('|');
      keywordsRepo.push({ scope, match: '\\b(' + combined + ')\\b' });
    }
  }

  const grammar: Record<string, unknown> = {
    name: 'TTR',
    fileTypes: ['ttr'],
    scopeName: 'source.ttrm',
    patterns: [
      { include: '#comments' },
      { include: '#strings' },
      { include: '#numbers' },
      { include: '#keywords' },
      { include: '#literals' },
      { include: '#operators' },
    ],
    repository: {
      comments: {
        patterns: [
          { name: 'comment.line.double-slash.ttrm', match: '//.*$' },
          { name: 'comment.block.ttrm', begin: '/\\*', end: '\\*/' },
        ],
      },
      strings: {
        patterns: [
          {
            name: 'string.quoted.triple.ttrm',
            begin: '"""',
            end: '"""',
          },
          {
            name: 'string.quoted.double.ttrm',
            begin: '"',
            end: '"(?=[^\\\\])',
            patterns: [
              { name: 'constant.character.escape.ttrm', match: '\\\\.' },
            ],
          },
        ],
      },
      numbers: {
        patterns: [
          {
            name: 'constant.numeric.ttrm',
            match: '-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?',
          },
        ],
      },
      keywords: {
        patterns: keywordScopes.map(scope => ({ include: '#' + scope.replace(/\./g, '_') })),
      },
      literals: {
        patterns: literalScopes.map(scope => ({ include: '#' + scope.replace(/\./g, '_') })),
      },
      ...Object.fromEntries(
        [...keywordScopes, ...literalScopes].map(scope => {
          const key = scope.replace(/\./g, '_');
          const rules = scopeMap[scope] ?? [];
          return [key, { patterns: [{ name: scope, match: '\\b(' + rules.join('|') + ')\\b' }] }];
        })
      ),
      operators: {
        patterns: [
          { name: 'punctuation.separator.ttrm', match: '=' },
          { name: 'punctuation.separator.ttrm', match: ':' },
          { name: 'punctuation.separator.ttrm', match: ',' },
          { name: 'punctuation.separator.ttrm', match: '\\.' },
          { name: 'punctuation.section.braces.ttrm', match: '\\{' },
          { name: 'punctuation.section.braces.ttrm', match: '\\}' },
          { name: 'punctuation.section.brackets.ttrm', match: '\\[' },
          { name: 'punctuation.section.brackets.ttrm', match: '\\]' },
          { name: 'punctuation.section.parens.ttrm', match: '\\(' },
          { name: 'punctuation.section.parens.ttrm', match: '\\)' },
        ],
      },
    },
  };

  return grammar;
}

function main(): void {
  const g4Content = fs.readFileSync(GRAMMAR_PATH, 'utf-8');
  const tokens    = parseGrammar(g4Content);
  const grammar   = buildGrammar(tokens);
  fs.writeFileSync(OUTPUT_PATH, JSON.stringify(grammar, null, 2));
  console.log(`Generated TextMate grammar (${tokens.length} tokens) to ${OUTPUT_PATH}`);
}

const invokedAsScript = !!process.argv[1] && path.resolve(process.argv[1]).endsWith('generate-tm-grammar.js');
if (invokedAsScript) main();