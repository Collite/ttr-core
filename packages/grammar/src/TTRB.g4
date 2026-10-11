// TTR-B — the controlled-sentence fragment dialect (P7 Stage 7.1). Own grammar (C2-g α),
// beside TTR.g4 / TTRP.g4 / TTRSql.g4 / TTRPandas.g4, read directly by the ANTLR Gradle
// plugin in ttrp-frontend. Kotlin-only (G-b).
//
// "Sentence-shaped TTR-P" (C4-a = α): one sentence per statement, each sentence decomposes
// to node(s) of the standard set (C2 regime inherited wholesale). The C4-b verb roster:
// Load, Keep/Take/Select, Remove/Delete, Rename, Convert/Retype, Create/Compute, Summarize,
// Join, Sort, Combine/Append, Store, Show/Display. Anaphora (C4-b-i): `that`/`this`/`it` and
// the implicit subject = the previous sentence's out. NOT NLP, never fuzzy (P2).
//
// KEYWORD SKINS (AG B2): ONE grammar, several keyword tables. The keyword token types are
// declared in `tokens { … }` below and have NO lexer rule — the lexer produces only IDENT,
// literals and punctuation, and the skin token source (`TtrbSyntax`, ttrp-frontend) re-types
// an IDENT (or a run of them, or a hyphenated word) that the ACTIVE skin spells — matched
// case- and diacritic-insensitively — into the keyword token(s). The tables live in
// `ttrp-frontend/src/main/resources/ttrb/roster.<lang>.yaml`: `roster.en.yaml` serves
// `"""ttrb` / `*.ttrb`, `roster.cs.yaml` serves `"""ttrb-cs` / `*.ttrb-cs`. Everything the
// skin does not spell stays an IDENT and is matched EXACTLY (identifiers are never folded):
// Latin letters incl. Latin-1 Supplement + Latin Extended-A/B, the range TTRP.g4 uses.
//
// S16: skins the ONE PL expression IR (org.tatrman.ttrp.expr.Expression). Verbose
// comparisons ("is more than", "is not empty", "is one of") fold to the SAME CatalogId
// ids as canonical TTR-P (KeywordTable / CatalogId); the closed English synonym set lives in
// ttr-b.synonyms.toml (C4-c = β). Canonical operators (`>`, `=`, …) remain valid and may mix
// with verbose forms in one predicate.
//
// `#` line comments (S19); no `//` or `/* */` (they reach the reject path, TtrbRejectScanner).
//
// GRAMMAR-PROTOTYPE LEFTOVERS DECIDED HERE (close 12-nl-options.md §Leftover):
//   · Ref-word roster (C4-b-i): v1 keeps `that | this | it` only. Byx's plural
//     `these/those` are DROPPED — a TTR-B pipeline value is a single table, so a plural
//     anaphor has no distinct referent (P2: one deterministic antecedent = the prev out).
//   · Binding form: v1 uses `… as <name>` ONLY. Byx's `call it <name>` standalone shape
//     is DROPPED for v1 (one closed binding spelling; `as` already carries Load/Join/
//     Compute/Show naming). Reconsider both in a v1.x NL sweep.

grammar TTRB;

// Keyword token types — spelled by the active skin (roster.<lang>.yaml), never by the lexer.
tokens {
    // verbs
    LOAD, KEEP, TAKE, SELECT, FILTER, REMOVE, DELETE, RENAME, CONVERT, RETYPE, CREATE,
    COMPUTE, CALCULATE, SUMMARIZE, JOIN, SORT, COMBINE, APPEND, UNION, STORE, SHOW, DISPLAY,
    // helper / noise words
    COLUMN, COLUMNS, COL, COLS, FIELD, FIELDS, ROW, ROWS, RECORD, RECORDS, LINE, LINES,
    ONLY, ALL, THE, ME, NEW, FROM, WITH, ON, BY, OF, TO, AS, FOR, WHERE, WHICH, THAT, THIS,
    IT, EXCEPT, BUT, EXCLUDING, EXCLUDE, GROUP, GROUPED, FIRST, RESULT, RESULTS, W_TYPE,
    FILE, SCHEMA, ASC, ASCENDING, DESC, DESCENDING,
    // verbose comparison words (C4-c; shared spellings fold to CatalogId)
    IS, NOT, AND, OR, W_MORE, LESS, THAN, BIGGER, LARGER, HIGHER, LOWER, SMALLER, FEWER,
    BEFORE, AFTER, COMES, EQUAL, EQUALS, SAME, DOES, EMPTY, ONE, BETWEEN,
    // literals-as-keywords
    TRUE, FALSE,
    // blocks (B3): the header word; INDENT / DEDENT are injected by the token source
    IF, INDENT, DEDENT,
    // count / attach / actions / recipients (B4)
    COUNT, COUNT_NOUN, ATTACH, SEND, EMAIL, SUBJECT, TEMPLATE, KEY, ATTACHMENT, DEPARTMENT,
    OTHERWISE, SET, REASON, MANUAL_TASK, DESCRIPTION,
    // container ports (B7): an output sentence names an OUT port
    OUTPUT,
    // joins (B7): optional join, have / have no match, a model relation
    OPTIONALLY, MATCH_IN, NO_MATCH_IN, RELATION,
    // naming the current value (B8)
    NAME_VERB
}

// =============================================================================
// Parser — sentence+ (C4-a); one statement per sentence, `.` terminated
// =============================================================================

fragmentProgram : item+ EOF ;

item : sentence | block ;

// `If <pred>:` / `Když <pred>:` + an indented run of sentences (B3). The token source injects
// INDENT before the first sentence indented deeper than the header and DEDENT where the
// indentation returns. Each block is one output — filter(<current value>, <pred>) — and blocks
// over the same value OVERLAP (not else-if). A block inside a block parses (so the reject is
// precise) and is TTRP-B-110 in the decomposer.
block : IF boolExpr COLON INDENT item+ DEDENT ;

sentence : statement DOT ;

statement
    : loadStmt          # loadSentence
    | limitStmt         # limitSentence      // before keep* — `Keep … first n rows` disambiguates on FIRST
    | keepColumnsStmt   # keepColumnsSentence
    | keepExceptStmt    # keepExceptSentence
    | matchStmt         # matchSentence      // before filter — `Keep … that have a match in` disambiguates on MATCH_IN
    | filterStmt        # filterSentence
    | renameStmt        # renameSentence
    | convertStmt       # convertSentence
    | conditionalStmt   # conditionalSentence  // `Compute x as 1 when p, otherwise 2.` (B7)
    | computeStmt       # computeSentence
    | summarizeStmt     # summarizeSentence
    | joinStmt          # joinSentence
    | sortStmt          # sortSentence
    | combineStmt       # combineSentence
    | storeStmt         # storeSentence
    | showStmt          # showSentence
    | countStmt         # countSentence
    | attachStmt        # attachSentence
    | emailStmt         # emailSentence
    | setFieldStmt      # setFieldSentence
    | taskStmt          # taskSentence
    | outputStmt        # outputSentence
    | nameStmt          # nameSentence
    ;

// ---- statements (C4-b roster) --------------------------------------------------

// `Load [from] file "…" [with schema <ref>] [as <name>].` /
// `Load [from] <ref> [with schema <ref>] [as <name>].`
// The optional `with schema` on the model form lets a storage-dataset load carry an explicit
// schema (e.g. `Load from files.sales_2026 with schema sales_csv` → `load(files.sales_2026,
// schema: sales_csv)`) — the shape a bare `.ttrb` needs to load a `files` dataset (S18/C4-b).
loadStmt
    : LOAD FROM? fileSource (WITH SCHEMA schema=qname)? (AS name=ident)?    # loadFile
    | LOAD FROM? source=qname (WITH SCHEMA schema=qname)? (AS name=ident)?  # loadModel
    ;
fileSource : FILE str ;

// `Keep/Take/Select only the columns a, b [as c].`
keepColumnsStmt : keepVerb ONLY? THE? columnWord colRenameList ;

// `Keep all columns except a, b.`
keepExceptStmt  : keepVerb ALL? THE? columnWord? exceptWord colList ;

// `Keep/Filter [only] [the] rows where <expr>.` / `Remove/Delete [the] rows where <expr>.`
// The optional comma before the where-word is Czech punctuation (`řádky, kde …`).
filterStmt
    : keepVerb ONLY? THE? rowWord? COMMA? whereWord? boolExpr        # keepFilter
    | FILTER FOR? THE? rowWord? COMMA? whereWord? boolExpr           # keepFilter
    | (REMOVE | DELETE) THE? rowWord? COMMA? whereWord? boolExpr     # removeFilter
    ;

// `Rename a to b.` / `Rename the columns a as b, c as d.`
renameStmt : RENAME THE? columnWord? renamePair (COMMA renamePair)* ;
renamePair : from=ident (AS | TO) to=ident ;

// `Convert/Retype a to <type>.`
convertStmt : (CONVERT | RETYPE) THE? W_TYPE? OF? col=ident (AS | TO) typeName ;

// `Create/Compute [new column] <expr> as <name>.`
computeStmt : (CREATE | COMPUTE | CALCULATE) NEW? columnWord? expr AS name=ident ;

// A conditional value (B7): `Compute x as 1 when <p>[, 2 when <q>…], otherwise 3.` /
// `Spočti x jako 1, když <p>[, 2, když <q>…], jinak 3.` → `calc { x = case when p then 1 … else 3 end }`.
// No `otherwise` / `jinak` ⇒ no else (NULL). Note the order: the NAME first, unlike plain Compute.
conditionalStmt
    : (CREATE | COMPUTE | CALCULATE) NEW? columnWord? name=ident AS whenArm (COMMA whenArm)* (COMMA? OTHERWISE otherwise=expr)? ;
whenArm : value=expr COMMA? IF cond=boolExpr ;

// `Summarize sum of amount as total [, …] by/grouped by region.` (`of` optional — the Czech
// skin has no preposition there: `Shrň součet částka jako celkem podle oblast.`)
summarizeStmt : SUMMARIZE aggItem (COMMA aggItem)* groupBy groupKey (COMMA groupKey)* ;
aggItem  : func=aggFunc OF? arg=ident (AS name=ident)? ;
aggFunc  : ident ;
groupBy  : BY | (GROUP | GROUPED) BY ;
groupKey : ident ;

// `Join that/it/<name> [optionally] with <name> on <expr> | on relation <r> [as <name>].`
// `Spoj to|<jméno> [volitelně] s <jméno> přes <výraz> | přes vazbu <r> [jako <jméno>].` — optionally:
// `type: left` (the unmatched right columns are NULL); `on relation r`: `on: relation r` (B7).
joinStmt : JOIN joinLeft OPTIONALLY? WITH right=qname ON joinCond (AS name=ident)? ;
joinLeft : refWord | qname ;
joinCond : RELATION rel=qname | boolExpr ;

// `Keep only the rows that have [no] match in <name> on <cond>.` /
// `Ponech jen řádky, které [ne]mají protějšek v <jméno> přes <podmínka>.` — a semi (anti) join of the
// current value; `Remove the rows that have a match …` is the anti join (B7).
matchStmt
    : keepVerb ONLY? THE? rowWord? COMMA? whereWord? (MATCH_IN | NO_MATCH_IN) right=qname ON joinCond             # keepMatch
    | (REMOVE | DELETE) THE? rowWord? COMMA? whereWord? (MATCH_IN | NO_MATCH_IN) right=qname ON joinCond          # removeMatch
    ;

// `Sort [the rows] by a [descending] [, …].`
sortStmt : SORT refWord? (THE? rowWord)? BY sortKey (COMMA sortKey)* ;
sortKey  : col=ident (ASC | ASCENDING | DESC | DESCENDING)? ;

// `Keep [only] the first <n> rows.`
limitStmt : KEEP? ONLY? THE? FIRST count=NUMBER rowWord ;

// `Combine/Append that with <name>.`
combineStmt : (COMBINE | APPEND | UNION) joinLeft WITH right=qname ;

// `Store that/[the] result to file "…" / <ref>.`
storeStmt : STORE storeSource TO (fileSource | dest=qname) ;
storeSource : refWord | THE? (RESULT | RESULTS) | qname ;

// `Show/Display [me] [the] result [as <name>].`
showStmt : (SHOW | DISPLAY) ME? THE? (RESULT | RESULTS)? refWord? (AS name=ident)? ;

// ---- count / attach (B4) ---------------------------------------------------------

// `Count the rows of T as x.` / `Count x as the number of rows of T.` /
// `Spočítej x jako počet řádků T.` — the row count of T, cross-joined into the current row:
// `T -> calc { x = 1 } -> aggregate { x = count(x) }` (count of a never-null column = the row count).
countStmt
    : COUNT THE? rowWord OF? source=qname AS name=ident                        # countRowsAs
    | COUNT name=ident AS THE? COUNT_NOUN rowWord OF? source=qname            # countAsNumber
    ;

// `Attach T to the result.` / `Připoj T k výsledku.` — cross join of the (one-row) T.
attachStmt : ATTACH source=qname (TO THE? (RESULT | RESULTS))? ;

// ---- actions (B4) — each a sink: calc { <schema columns> } -> select(…) -> display(<kind>) ----

// `Send an e-mail to <recipient> with subject "s", template "t"[,| and] key <k> [and attachments a, b].`
// `Pošli e-mail <komu> s předmětem "s", šablonou "t"[,| a] klíčem <k> [a přílohami a, b].` → send_email
emailStmt
    : SEND EMAIL TO? recipient WITH SUBJECT subject=str COMMA TEMPLATE template=str (COMMA | AND) KEY key=expr
      attachments?
    ;

// `Set <attribute> of <entity> with key <k> to <v> with reason "r".`
// `Nastav <atribut> <entita> s klíčem <k> na <v> s důvodem "r".` → update_field
setFieldStmt
    : SET attribute=nameRef OF? entity=nameRef WITH KEY key=expr TO value=expr WITH REASON reason=str ;

// `Create a manual task for <recipient> "title" with description "d" [and attachment a].`
// `Vytvoř ruční úkol pro <řešitel> "název" s popisem "d" [a přílohou a].` → manual_task
taskStmt : CREATE MANUAL_TASK FOR recipient title=str WITH DESCRIPTION description=str attachments? ;

// A recipient: a column holding an address; `department "x"` (→ the text "oddělení:x"); or a column
// with a department fallback `<column>, otherwise department "x"` (→ coalesce(<column>, "oddělení:x")).
recipient
    : DEPARTMENT dept=str                                         # departmentRecipient
    | column=dottedRef (COMMA OTHERWISE DEPARTMENT dept=str)?     # columnRecipient
    ;
attachments    : AND ATTACHMENT attachmentName (COMMA attachmentName)* ;
attachmentName : ident | str ;
nameRef        : ident | str ;

// ---- container ports (B7) ----------------------------------------------------------

// `Send that|the result|<name> to output <port>.` / `Pošli to|výsledek|<jméno> na výstup <port>.` — the
// container OUT port <port> carries the current value (or the named one). The port must be declared in
// the container header (TTRP-B-112). A container's IN ports are plain names in every sentence.
outputStmt   : SEND outputSource? TO OUTPUT port=ident ;
outputSource : refWord | THE? (RESULT | RESULTS) | qname ;

// ---- naming the current value (B8) ------------------------------------------------

// `Call that|the result <name>.` / `Name that [as] <name>.` / `Pojmenuj to|výsledek jako <jméno>.` /
// `Nazvi to <jméno>.` — binds the CURRENT value to <name> (a pending chain is assigned to it, else the
// name refers to the current value); the current value stays the same, now named. `Load <name>.` /
// `Načti <jméno>.` of a bound name makes that value current again.
nameStmt : NAME_VERB (refWord | THE? (RESULT | RESULTS))? AS? name=ident ;

// ---- helper word classes (C4-b-ii = α: full synonym breadth + noise words) ------

keepVerb   : KEEP | TAKE | SELECT ;
columnWord : COLUMN | COLUMNS | COL | COLS | FIELD | FIELDS ;
rowWord    : ROW | ROWS | RECORD | RECORDS | LINE | LINES ;
whereWord  : WHERE | WHICH | THAT | WITH ;
exceptWord : EXCEPT | BUT | EXCLUDING | EXCLUDE ;
refWord    : THAT | THIS | IT ;

colList        : ident (COMMA ident)* ;
colRenameList  : colRename (COMMA colRename)* ;
colRename      : ident (AS ident)? ;

// An identifier (column / table / binding name) — matched exactly, never folded. The B4 keywords
// are SOFT: a column named `key`, `subject`, `email` or `count` keeps working (ALL(*) decides by
// position; a sentence never starts with an identifier).
ident
    : IDENT
    | COUNT | COUNT_NOUN | ATTACH | SEND | SET | EMAIL | SUBJECT | TEMPLATE | KEY | ATTACHMENT
    | DEPARTMENT | OTHERWISE | REASON | DESCRIPTION | OUTPUT | RELATION | NAME_VERB
    ;

// ---- expression grammar — verbose skin over the ONE PL IR (S16, T5-e) ----------
// Ladder mirrors TTRP.g4 / TTRSql.g4: or < and < not < predicate < additive <
// multiplicative < unary < primary — so without parentheses `and` binds tighter than `or`
// (`a or b and c` = `a or (b and c)`). Parentheses group ANY sub-condition (B8: `( … )` is a
// primary over the whole boolean expression, as in TTRP.g4), arithmetic or boolean. Verbose
// comparators are closed alternatives (C4-c); canonical operators (`>` `=` …) remain valid and may mix.
boolExpr : orExpr ;
orExpr   : andExpr (OR andExpr)* ;
andExpr  : notExpr (AND notExpr)* ;
notExpr  : NOT notExpr | predicate ;
predicate
    : addExpr IS NOT? EMPTY                                       # emptyPredicate      // is [not] empty
    | addExpr IS ONE OF LPAREN expr (COMMA expr)* RPAREN          # oneOfPredicate      // is one of (…)
    | addExpr IS NOT? BETWEEN addExpr AND addExpr                 # betweenPredicate
    | addExpr comparator addExpr                                  # comparePredicate
    | addExpr                                                     # barePredicate
    ;

// Verbose + canonical comparators (closed set; 1:1 with ttr-b.synonyms.toml). Each form
// is its own sub-rule so the fold reads which matched (labels can't hold `|` in ANTLR).
comparator
    : symbolOp
    | verboseGe | verboseLe | verboseGt | verboseLt | verboseNe | verboseEq
    ;
symbolOp  : EQ | NEQ | NEQ2 | LT | LTE | GT | GTE ;
verboseGt : IS W_MORE THAN | IS BIGGER THAN | IS LARGER THAN | IS HIGHER THAN | COMES AFTER ;
verboseLt : IS LESS THAN | IS FEWER THAN | IS LOWER THAN | IS SMALLER THAN | COMES BEFORE ;
verboseLe : IS NOT (W_MORE | BIGGER | LARGER | HIGHER) THAN | COMES NOT AFTER ;
verboseGe : IS NOT (LESS | FEWER | LOWER | SMALLER) THAN | COMES NOT BEFORE ;
verboseNe : IS NOT EQUAL TO? | DOES NOT EQUAL | IS NOT ;
verboseEq : IS EQUAL TO? | EQUALS | IS THE? SAME AS | IS ;

expr     : addExpr ;
addExpr  : mulExpr ((PLUS | MINUS) mulExpr)* ;
mulExpr  : unaryExpr ((STAR | SLASH) unaryExpr)* ;
unaryExpr : MINUS unaryExpr | primary ;
primary
    : literal                                               # litPrimary
    | funcCall                                              # callPrimary
    | dottedRef                                             # colPrimary
    | LPAREN boolExpr RPAREN                                # parenPrimary   // any grouping (B8)
    ;
funcCall  : name=ident LPAREN (expr (COMMA expr)*)? RPAREN ;
dottedRef : ident (DOT ident)* ;
qname     : ident (DOT ident)* ;
typeName  : ident (LPAREN NUMBER (COMMA NUMBER)? RPAREN)? ;
literal   : str | NUMBER | TRUE | FALSE ;
str       : STRING | CHAR_STRING ;

// =============================================================================
// Lexer — identifiers, literals and punctuation ONLY (keywords come from the skin)
// =============================================================================

// Operators & punctuation (multi-char before single-char for maximal munch).
EQEQ        : '==' ;                 // S9 reject → TTRP-EQ-001 (scanner)
NEQ         : '<>' ;
NEQ2        : '!=' ;
LTE         : '<=' ;
GTE         : '>=' ;
EQ          : '=' ;
LT          : '<' ;
GT          : '>' ;
PLUS        : '+' ;
MINUS       : '-' ;
STAR        : '*' ;
SLASH       : '/' ;                  // `//` = two SLASH ⇒ TTRP-B-005 (scanner)
LPAREN      : '(' ;
RPAREN      : ')' ;
COMMA       : ',' ;
DOT         : '.' ;
COLON       : ':' ;                  // ends a block header (B3)

STRING        : '"' (~["\r\n])* '"' ;
CHAR_STRING   : '\'' (~['\r\n])* '\'' ;

LINE_COMMENT  : '#' ~[\r\n]* -> channel(HIDDEN) ;   // S19 `#` comments
WS            : [ \t\r\n]+ -> skip ;

NUMBER        : [0-9]+ ('.' [0-9]+)? ;
// Latin letters incl. the Latin-1 Supplement + Latin Extended-A/B block (À-ɏ) — the
// range TTRP.g4 / TTR.g4 admit, so a sentence names the model objects exactly as declared
// (`objednávky`, `částka`). A keyword is an IDENT the active skin spells (re-typed downstream).
IDENT         : [a-zA-Z_À-ɏ] [a-zA-Z0-9_À-ɏ]* ;

// Catch-all LAST: any other char becomes an UNMATCHED token the reject scanner names instead of
// a bare lexer error.
UNMATCHED     : . ;
