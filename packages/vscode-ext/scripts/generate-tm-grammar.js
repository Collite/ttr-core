#!/usr/bin/env node
"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.PL_CONSTANTS = exports.PL_CONDITIONAL_WORDS = exports.PL_OPERATOR_WORDS = void 0;
exports.parseGrammar = parseGrammar;
exports.tokenToScope = tokenToScope;
exports.buildGrammar = buildGrammar;
// SPDX-License-Identifier: Apache-2.0
const fs_1 = __importDefault(require("fs"));
const path_1 = __importDefault(require("path"));
// The sibling .js is emitted by `pnpm run build-generator`. Do not edit the .js by hand.
function getScriptDir() {
    if (process.argv[1]) {
        return path_1.default.dirname(path_1.default.resolve(process.argv[1]));
    }
    return '<unknown-script-dir>';
}
const __dirname_script = getScriptDir();
const monorepoRoot = path_1.default.resolve(__dirname_script, '..', '..', '..');
const GRAMMAR_PATH = path_1.default.join(monorepoRoot, 'packages', 'grammar', 'src', 'TTR.g4');
const OUTPUT_PATH = path_1.default.join(__dirname_script, '..', 'syntaxes', 'ttrm.tmLanguage.json');
function parseGrammar(g4Content) {
    const tokens = [];
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
function tokenToScope(name, literal) {
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
        case 'INDEX_SECURITY':
        case 'SKIP_SECURITY':
        case 'DEFAULT_VALUE':
        case 'BINDS':
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
        case 'NULL_LITERAL': return 'constant.language.ttrm'; // `null` — language constant, alongside true|false
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
        case 'MAP': return 'keyword.other.kind.ttrm'; // v3.1 — `def map` is now a kind
        case 'DOMAIN': return 'keyword.other.kind.ttrm';
        case 'DIMENSION': return 'keyword.other.kind.ttrm';
        case 'HIERARCHY': return 'keyword.other.kind.ttrm';
        case 'MEASURE': return 'keyword.other.kind.ttrm';
        case 'CUBELET': return 'keyword.other.kind.ttrm';
        case 'MD2DB_CUBELET': return 'keyword.other.kind.ttrm';
        case 'MD2DB_DOMAIN': return 'keyword.other.kind.ttrm';
        case 'MD2DB_MAP': return 'keyword.other.kind.ttrm';
        case 'MD2ER_DIMENSION': return 'keyword.other.kind.ttrm';
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
        case 'ALLOCATION': return 'keyword.other.property.ttrm'; // v0.10 — md2db_cubelet spread strategy
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
        // 0.17 conceptual model (CN-P1 S1.4). CN is a model code (`model cn`) and the
        // `cn.` ref head, like MD/ER; the concept def nouns and the ten binding kinds
        // are kinds; the cn body keys, the `concept { }` sugar and the lexicon
        // `default` / `sense_priority` are body properties. KIND stays a property
        // (`kind: cn.Item`, md `kind:`) — `def kind` is scoped by the #def-kind rule.
        // AS (`Warehouse as from`) is an operator word, the TTRP spelling.
        case 'CN': return 'keyword.other.schema.ttrm';
        case 'EVENT': return 'keyword.other.kind.ttrm';
        case 'SUBKIND': return 'keyword.other.kind.ttrm';
        case 'PER': return 'keyword.other.property.ttrm';
        case 'UNTIL': return 'keyword.other.property.ttrm';
        case 'PHASE': return 'keyword.other.kind.ttrm';
        case 'QUALITY': return 'keyword.other.kind.ttrm';
        case 'RELATION_TYPE': return 'keyword.other.kind.ttrm';
        case 'CN2MD_KIND': return 'keyword.other.kind.ttrm';
        case 'CN2MD_ROLE': return 'keyword.other.kind.ttrm';
        case 'CN2MD_QUALITY': return 'keyword.other.kind.ttrm';
        case 'CN2MD_EVENT': return 'keyword.other.kind.ttrm';
        case 'CN2ER_KIND': return 'keyword.other.kind.ttrm';
        case 'CN2ER_ROLE': return 'keyword.other.kind.ttrm';
        case 'CN2ER_EVENT': return 'keyword.other.kind.ttrm';
        case 'CN2ER_QUALITY': return 'keyword.other.kind.ttrm';
        case 'CN2ER_PHASE': return 'keyword.other.kind.ttrm';
        case 'CN2DB_CONCEPT': return 'keyword.other.kind.ttrm';
        case 'CONCEPT': return 'keyword.other.property.ttrm';
        case 'INVOLVES': return 'keyword.other.property.ttrm';
        case 'PERIOD': return 'keyword.other.property.ttrm';
        case 'GROUNDS': return 'keyword.other.property.ttrm';
        case 'DEFINITION': return 'keyword.other.property.ttrm';
        case 'VALUE_SPACE': return 'keyword.other.property.ttrm';
        case 'EXTENT': return 'keyword.other.property.ttrm';
        case 'BASIS': return 'keyword.other.property.ttrm';
        case 'DEFAULT': return 'keyword.other.property.ttrm';
        case 'SENSE_PRIORITY': return 'keyword.other.property.ttrm';
        case 'AS': return 'keyword.operator.word.ttrm';
        default: return null;
    }
}
function escapeRegex(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
// 0.17 — the cn expression blocks (⚑CN-P5, CN-D28). `definition:` / `members:` (and a binding's
// `filter:`) carry a TTRP expression, the `pl` language of EXPRESSION_TAG_REGISTRY — in any of the
// three `embeddedBlock` forms: a `"""pl` block, an untagged triple-string, a plain string. Only after
// those keys (with `:` or `=`, both `propSep`) — a `"""pl` first line elsewhere (a description) is
// text. Another tag after the key (`"""sql`) is left to #strings. The body is the self-contained
// #pl-expression set (the TTR-M extension does not depend on the TTR-P one), mirroring the TTRP
// grammar's expression words; every region in it is one line or closes at the fence, so an
// expression can never colour the rest of the file.
const EXPRESSION_KEY = '\\b(definition|members|filter)\\s*([:=])?\\s*';
const EMBEDDED_BLOCKS = {
    patterns: [
        {
            begin: EXPRESSION_KEY + '(""")(?:(pl)(?=[ \\t]*\\r?$)|(?![a-zA-Z][a-zA-Z0-9-]*[ \\t]*\\r?$))',
            end: '"""',
            beginCaptures: {
                1: { name: 'keyword.other.property.ttrm' },
                2: { name: 'punctuation.separator.ttrm' },
                3: { name: 'string.quoted.fenced.pl.ttrm punctuation.definition.string.begin.ttrm' },
                4: { name: 'string.quoted.fenced.pl.ttrm entity.name.tag.language.ttrm' },
            },
            endCaptures: { 0: { name: 'string.quoted.fenced.pl.ttrm punctuation.definition.string.end.ttrm' } },
            contentName: 'meta.embedded.block.pl.ttrm',
            patterns: [{ include: '#pl-expression' }],
        },
        {
            begin: EXPRESSION_KEY + '(")(?!"")',
            end: '"',
            beginCaptures: {
                1: { name: 'keyword.other.property.ttrm' },
                2: { name: 'punctuation.separator.ttrm' },
                3: { name: 'string.quoted.double.ttrm punctuation.definition.string.begin.ttrm' },
            },
            endCaptures: { 0: { name: 'string.quoted.double.ttrm punctuation.definition.string.end.ttrm' } },
            contentName: 'meta.embedded.line.pl.ttrm',
            // Inside a TTR string `\x` is one escaped character, so a comment here steps over `\"` instead of
            // stopping at it (the quote would close the string). Listed before #pl-expression's comments.
            patterns: [
                { name: 'constant.character.escape.ttrm', match: '\\\\.' },
                { name: 'comment.line.double-slash.ttrm', match: '//(?:[^"\\\\\\r\\n]|\\\\.)*' },
                { name: 'comment.block.ttrm', begin: '/\\*', end: '\\*/|(?=")', patterns: [{ match: '\\\\.' }] },
                { include: '#pl-expression' },
            ],
        },
    ],
};
// The TTRP expression words, by the TTRP grammar's scopes (ttrp.tmLanguage.json: keyword.operator.word,
// keyword.control.conditional, constant.language) with the .ttrm suffix. A test pins the sets against
// the TTR-P grammar so the two cannot drift silently.
exports.PL_OPERATOR_WORDS = ['group', 'by', 'in', 'and', 'or', 'not', 'is', 'between', 'cast', 'as', 'distinct'];
exports.PL_CONDITIONAL_WORDS = ['case', 'when', 'then', 'else', 'end'];
exports.PL_CONSTANTS = ['null', 'true', 'false'];
// TTRP's strings (`'…'`, `"…"`) and `//` comments cannot span a line; a `/* … */` comment can. No rule
// here may swallow the enclosing block's closing `"""` or string's closing `"`: a comment stops at
// the first `"` (a quote inside a comment ends its colouring early — cosmetic, never a runaway).
const PL_EXPRESSION = {
    patterns: [
        { name: 'comment.line.double-slash.ttrm', match: '//[^"\\r\\n]*' },
        { name: 'comment.block.ttrm', begin: '/\\*', end: '\\*/|(?=")' },
        { name: 'string.quoted.single.ttrm', match: "'[^'\\r\\n]*'" },
        { name: 'string.quoted.double.ttrm', match: '"(?!"")[^"\\r\\n]*"' },
        { name: 'constant.numeric.ttrm', match: '\\b[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?\\b' },
        { name: 'keyword.operator.word.ttrm', match: '\\b(' + exports.PL_OPERATOR_WORDS.join('|') + ')\\b' },
        { name: 'keyword.control.conditional.ttrm', match: '\\b(' + exports.PL_CONDITIONAL_WORDS.join('|') + ')\\b' },
        { name: 'constant.language.ttrm', match: '\\b(' + exports.PL_CONSTANTS.join('|') + ')\\b' },
        { name: 'keyword.operator.ttrm', match: '<=|>=|<>|!=|=|<|>|\\+|-|\\*|/|%' },
    ],
};
function buildGrammar(tokens) {
    const byScope = new Map();
    const rules = [];
    const scopeMap = {
        'keyword.control.def.ttrm': [],
        'keyword.control.package.ttrm': [],
        'keyword.control.import.ttrm': [],
        'keyword.declaration.graph.ttrm': [],
        'keyword.other.packages.ttrm': [],
        'keyword.other.entities.ttrm': [],
        'keyword.other.schema.ttrm': [],
        'keyword.other.kind.ttrm': [],
        'keyword.other.property.ttrm': [],
        'keyword.operator.word.ttrm': [],
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
        if (!scope)
            continue;
        if (!scopeMap[scope])
            scopeMap[scope] = [];
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
        'keyword.operator.word.ttrm',
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
    const keywordsRepo = [];
    for (const scope of keywordScopes) {
        const rules = scopeMap[scope];
        if (rules.length > 0) {
            const combined = rules.join('|');
            keywordsRepo.push({ scope, match: '\\b(' + combined + ')\\b' });
        }
    }
    const grammar = {
        name: 'TTR',
        fileTypes: ['ttr'],
        scopeName: 'source.ttrm',
        patterns: [
            { include: '#comments' },
            { include: '#embedded-blocks' },
            { include: '#strings' },
            { include: '#numbers' },
            { include: '#def-kind' },
            { include: '#keywords' },
            { include: '#literals' },
            { include: '#operators' },
        ],
        repository: {
            'embedded-blocks': EMBEDDED_BLOCKS,
            'pl-expression': PL_EXPRESSION,
            'def-kind': {
                patterns: [{
                        match: '\\b(def)\\s+(kind)\\b',
                        captures: {
                            1: { name: 'keyword.control.def.ttrm' },
                            2: { name: 'keyword.other.kind.ttrm' },
                        },
                    }],
            },
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
            ...Object.fromEntries([...keywordScopes, ...literalScopes].map(scope => {
                const key = scope.replace(/\./g, '_');
                const rules = scopeMap[scope] ?? [];
                return [key, { patterns: [{ name: scope, match: '\\b(' + rules.join('|') + ')\\b' }] }];
            })),
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
function main() {
    const g4Content = fs_1.default.readFileSync(GRAMMAR_PATH, 'utf-8');
    const tokens = parseGrammar(g4Content);
    const grammar = buildGrammar(tokens);
    fs_1.default.writeFileSync(OUTPUT_PATH, JSON.stringify(grammar, null, 2));
    console.log(`Generated TextMate grammar (${tokens.length} tokens) to ${OUTPUT_PATH}`);
}
const invokedAsScript = !!process.argv[1] && path_1.default.resolve(process.argv[1]).endsWith('generate-tm-grammar.js');
if (invokedAsScript)
    main();
