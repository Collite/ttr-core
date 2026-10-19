// TTR-SQL — the SQL fragment dialect (P6 Stage 6.1). Own grammar per C2-g α,
// beside TTR.g4 / TTRP.g4, read directly by the ANTLR Gradle plugin in ttrp-frontend.
//
// Scope contract = the C2-b α clause table (docs/ttr-p/design/11-fragments-options.md):
// ONE query expression per fragment — an optional WITH block + a final SELECT (set-ops
// allowed). CTE names become SSA labels (E-b's inverse). Everything outside the cut is a
// GRAMMAR reject (C2-g "rejects are grammar rejects"), surfaced with a named
// TTRP-SQL-NNN diagnostic + suggested alternative by TtrSqlRejectScanner over the token
// stream — never a bare syntax error.
//
// S16: this grammar does NOT re-declare the shared keyword/operator TABLE. It skins the
// ONE PL expression IR (org.tatrman.ttrp.expr.Expression) — the SQL expression rules
// below fold to the SAME CatalogId ids as canonical TTR-P (KeywordTable / CatalogId),
// pinned by TtrSqlKeywordDriftSpec. Only SQL-CLAUSE keywords are declared here.
//
// caseInsensitive: SQL is conventionally case-insensitive; the hero authors uppercase
// (WITH/SELECT/AS/GROUP BY/DESC/NULLS LAST). This is a lexer option on THIS grammar only
// — it does not touch the (lowercase-only) canonical TTRP.g4.

grammar TTRSql;

options { caseInsensitive = true; }

// =============================================================================
// Parser — one query expression per fragment (C2-b)
// =============================================================================

fragmentProgram : withClause? queryExpression EOF ;

withClause      : WITH cte (COMMA cte)* ;
cte             : name=identifier AS LPAREN queryExpression RPAREN ;

// Set-ops chain left-to-right (Union/Intersect/Except). ORDER BY / LIMIT bind the whole.
queryExpression : selectCore (setOp selectCore)* orderByClause? limitClause? ;
setOp           : UNION ALL? | INTERSECT | EXCEPT ;

selectCore
    : SELECT DISTINCT? selectList
      fromClause?
      whereClause?
      groupByClause?                                        # selectQuery
    | VALUES rowValue (COMMA rowValue)*                     # valuesQuery
    ;

selectList      : selectItem (COMMA selectItem)* ;
selectItem
    : STAR                                                  # starAll
    | qualifier=identifier DOT STAR                         # starQualified
    | expr (AS? alias=identifier)?                          # exprItem
    ;

fromClause      : FROM fromItem joinClause* ;
fromItem        : tableRef (AS? alias=identifier)? ;
tableRef        : identifier (DOT namePart)* ;              // in-port name or full qname

joinClause      : joinKind? JOIN fromItem ON expr ;
joinKind        : INNER | LEFT OUTER? | RIGHT OUTER? | FULL OUTER? | CROSS ;

whereClause     : WHERE expr ;
groupByClause   : GROUP BY groupItem (COMMA groupItem)* (HAVING having=expr)? ;
groupItem       : expr ;

orderByClause   : ORDER BY sortKey (COMMA sortKey)* ;
sortKey         : expr (ASC | DESC)? (NULLS (FIRST | LAST))? ;

limitClause     : LIMIT count=NUMBER (OFFSET off=NUMBER)? ;

rowValue        : LPAREN expr (COMMA expr)* RPAREN ;

// ---- expression grammar — SQL skin over the ONE PL IR (S16, T5-e) --------------
// Precedence ladder mirrors TTRP.g4 so the fold is 1:1: or < and < not < predicate <
// additive < multiplicative < unary < primary. `=` is equality here (SQL convention);
// `<>` and `!=` are both not-equal. EXISTS/IN subquery forms are the ONLY subqueries.
expr            : orExpr ;
orExpr          : andExpr (OR andExpr)* ;
andExpr         : notExpr (AND notExpr)* ;
notExpr         : NOT notExpr | predicate ;
predicate
    : EXISTS LPAREN queryExpression RPAREN                                     # existsPredicate
    | addExpr (EQ | NEQ | NEQ2 | LT | LTE | GT | GTE) addExpr                  # comparePredicate
    | addExpr IS NOT? NULL                                                     # isNullPredicate
    | addExpr NOT? IN LPAREN (queryExpression | expr (COMMA expr)*) RPAREN     # inPredicate
    | addExpr NOT? BETWEEN addExpr AND addExpr                                 # betweenPredicate
    | addExpr                                                                  # barePredicate
    ;
addExpr         : mulExpr ((PLUS | MINUS) mulExpr)* ;
mulExpr         : unaryExpr ((STAR | SLASH) unaryExpr)* ;
unaryExpr       : MINUS unaryExpr | primary ;
primary
    : literal                                               # litPrimary
    | CAST LPAREN expr AS typeName RPAREN                   # castPrimary
    | CASE (WHEN expr THEN expr)+ (ELSE expr)? END          # casePrimary
    | functionCall                                          # callPrimary
    | PARAM_REF                                             # paramPrimary   // AU-core-P0: `:name` (D-108)
    | columnRef                                             # colPrimary
    | LPAREN expr RPAREN                                    # parenPrimary
    ;

// COUNT(*) is the one starred call (folds to AggregateCall(agg.count, args=[])).
functionCall
    : name=identifier LPAREN STAR RPAREN                                      # countStar
    | name=identifier LPAREN (DISTINCT? expr (COMMA expr)*)? RPAREN           # namedCall
    ;
columnRef       : identifier (DOT namePart)* ;
typeName        : identifier (LPAREN NUMBER (COMMA NUMBER)? RPAREN)? ;
literal         : STRING | NUMBER | TRUE | FALSE | NULL ;

// Bare names are plain IDENTs. SQL-clause keywords stay reserved inside fragments
// (the C2-b cut is small; hero column names — account_id, region, amount, … — are
// not keywords). `FIRST`/`LAST` double as the NULLS placement words only.
identifier      : IDENT | QUOTED_IDENT ;                     // AU-core-P0 review-114 M3: `"Customer"` (SQL-004's own advice)

// AU-core-P0: after a dot a clause keyword is a NAME (`er.entity.order`, `o.group`) — the PostgreSQL
// rule (a qualified name's attribute is a ColLabel). The curated reject tokens (INSERT, OVER, TOP, …)
// stay out: the reject scanner names them wherever they appear.
namePart
    : identifier
    | ORDER | GROUP | BY | ALL | FIRST | LAST | VALUES | LIMIT | OFFSET | ASC | DESC | NULLS
    | SELECT | FROM | WHERE | HAVING | JOIN | INNER | LEFT | RIGHT | FULL | CROSS | OUTER | ON | WITH
    | UNION | INTERSECT | EXCEPT | AS | EXISTS | DISTINCT | AND | OR | NOT | IS | IN | BETWEEN
    | CASE | WHEN | THEN | ELSE | END | CAST | NULL | TRUE | FALSE
    ;

// =============================================================================
// Lexer — SQL-CLAUSE keywords only (the shared operator/keyword TABLE is NOT here)
// =============================================================================

WITH        : 'with' ;
SELECT      : 'select' ;
DISTINCT    : 'distinct' ;
FROM        : 'from' ;
JOIN        : 'join' ;
INNER       : 'inner' ;
LEFT        : 'left' ;
RIGHT       : 'right' ;
FULL        : 'full' ;
CROSS       : 'cross' ;
OUTER       : 'outer' ;
ON          : 'on' ;
WHERE       : 'where' ;
GROUP       : 'group' ;
BY          : 'by' ;
HAVING      : 'having' ;
UNION       : 'union' ;
ALL         : 'all' ;
INTERSECT   : 'intersect' ;
EXCEPT      : 'except' ;
ORDER       : 'order' ;
ASC         : 'asc' ;
DESC        : 'desc' ;
NULLS       : 'nulls' ;
FIRST       : 'first' ;
LAST        : 'last' ;
LIMIT       : 'limit' ;
OFFSET      : 'offset' ;
VALUES      : 'values' ;
AS          : 'as' ;
EXISTS      : 'exists' ;

// Expression keywords (shared spellings — mapped back to CatalogId in the walker).
AND         : 'and' ;
OR          : 'or' ;
NOT         : 'not' ;
IS          : 'is' ;
IN          : 'in' ;
BETWEEN     : 'between' ;
CASE        : 'case' ;
WHEN        : 'when' ;
THEN        : 'then' ;
ELSE        : 'else' ;
END         : 'end' ;
CAST        : 'cast' ;
NULL        : 'null' ;
TRUE        : 'true' ;
FALSE       : 'false' ;

// Curated REJECT tokens — lexed so TtrSqlRejectScanner can NAME the reject
// (TTRP-SQL-NNN) instead of the parser emitting a bare syntax error (C2-g / T6.1.4).
INSERT      : 'insert' ;
UPDATE      : 'update' ;
DELETE      : 'delete' ;
CREATE      : 'create' ;
DROP        : 'drop' ;
ALTER       : 'alter' ;
TRUNCATE    : 'truncate' ;
MERGE       : 'merge' ;
INTO        : 'into' ;
TOP         : 'top' ;
OVER        : 'over' ;
PIVOT       : 'pivot' ;
UNPIVOT     : 'unpivot' ;
GROUPING    : 'grouping' ;
LATERAL     : 'lateral' ;
NATURAL     : 'natural' ;
USING       : 'using' ;
DECLARE     : 'declare' ;
NOLOCK      : 'nolock' ;

// Operators & punctuation (multi-char before single-char for maximal munch).
DCOLON      : '::' ;                 // reject 005 (::cast)
// AU-core-P0: `:name` — a parameter / runtime binding, ONE token so a keyword is a legal name (`:limit`) and
// `: p` (a lone colon) is unreadable. `::` stays DCOLON (longest match: `::` is two characters, `:x` needs a letter).
PARAM_REF   : ':' [a-z_\u00C0-\u024F] [a-z0-9_\u00C0-\u024F]* ;
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
SLASH       : '/' ;
LPAREN      : '(' ;
RPAREN      : ')' ;
LBRACKET    : '[' ;                  // reject 004 (bracket quoting)
RBRACKET    : ']' ;
COMMA       : ',' ;
SEMI        : ';' ;                  // reject 009 (statement chain)
DOT         : '.' ;
BACKTICK    : '`' ;                  // reject 004 (backtick quoting)
AT          : '@' ;                  // reject 008 (@var procedural)

STRING        : '\'' ( ~['\r\n] | '\'\'' )* '\'' ;   // SQL single-quoted string, '' escape

LINE_COMMENT  : '--' ~[\r\n]* -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN) ;
// AU-core-P0 review-114 M3: a BOM, a no-break space and a form feed are whitespace (the lexer used to DROP them
// silently; an unreadable character is now the generic reject, so these must be read).
WS            : [ \t\r\n\f\u00A0\uFEFF]+ -> skip ;

NUMBER        : [0-9]+ ('.' [0-9]+)? ;
// caseInsensitive folds A-Z into a-z (avoids the dup-range warning). \u00C0-\u024F = TTR.g4's identifier
// range (Czech model names, `zásoba_na_skladě`) — identifiers are model names, never translated (AG C-23).
IDENT         : [a-z_\u00C0-\u024F] [a-z0-9_\u00C0-\u024F]* ;
// A double-quoted identifier, `""` inside for a quote (review-114 M3). The walker strips the quotes (TtrSqlNames).
QUOTED_IDENT  : '"' ( ~["\r\n] | '""' )+ '"' ;
