// TTR-P canonical grammar — the C3-converged canonical surface (P1 Stage 1.1).
// @grammar-version: 0.6  (TTR-P spec version is an integer cut via docs/grammar-master/ — S6)
//   0.1 → 0.2 (PL-P2.S1, additive minor): runtime params (F-4-i) + on-failure
//   islands / retries (F-4-iv/F-4-ii) — see docs/features/grammar-master/pl-grammar-work-items.md.
//   0.2 → 0.3 (AG B5, additive minor): a container body may live in a file —
//   `container x(…) target e from "rules/x.ttrb-cs"` (`from` is a SOFT keyword, still a name).
//   0.3 → 0.4 (AU-core-P0 · S0.1, additive minor — the recipe block; the manifest's
//   `ttrpVersion` 2 for a document that carries one): see the RECIPE section below.
//     1. `recipe { headers… members… }` — one per document (TTRP-REC-000 is the S1.1
//        check); headers `key:` · `as of:` · `parameters: [ … ]` · `document:` ·
//        `protocol:` · `recipe: <name>` (a multi-skeleton document, AU contracts §6);
//        members `candidates`/`context` role containers (an island form: `target` ·
//        `builtin` · `foreign … per candidate | set schema` · `call` · `turn … ask …
//        answer`, + an optional `window { by: … over: … }`), one `decision` whose body
//        ends in a `switch` statement (routes · `mode:` · `else:` · `because:` ·
//        `detail:`), and an `actions { }` wiring block (`verdict.route, … -> action
//        { arg: value, … } when: <expr>`). D-25, D-19, D-28, D-31/D-102, D-108.
//     2. Expressions gain a runtime/parameter binding `:name` (`:as_of` reserved); a
//        function call may name its arguments (`age(since: x)`, `working_days(between: a,
//        and: :as_of)` — `and` / `between` are legal argument NAMES; elsewhere a named
//        argument is TTRP-PRS-008). In an expression a duration is a call the walker folds:
//        `days(30)`, `days(:p)` (17 §5). The juxtaposed literal `30 days` / `2 working_days`
//        is a WIRING value and a window's `gap:` only — in an expression it would merge a
//        comma-less config entry `lead = 30` with a following `days = amount` (review-114 M1).
//     3. `aggregate { group_by: k, … }` (the LD spelling, D-108 "LD wins") beside the
//        existing `group by k`; config entries may be separated by commas.
//     4. New tokens, ALL soft (also in `identifier`, so `key`, `mode`, `detail`, `days`,
//        `context`, `set`, `call`, `ask`, `recipe` … stay usable as names — the WORLD
//        precedent): RECIPE KEY PARAMETERS DOCUMENT PROTOCOL CANDIDATES CONTEXT DECISION
//        ACTIONS BUILTIN_FORM FOREIGN PER CANDIDATE SET CALL TURN ASK ANSWER WINDOW OVER
//        SWITCH MODE BECAUSE DETAIL GROUP_BY DAYS WORKING_DAYS WEEKS MONTHS MINUTES HOURS;
//        punctuation LBRACK RBRACK. `builtin` is BUILTIN_FORM — BUILTIN is the `@run-date`
//        sigil token. Enumerated VALUES (`mode: exclusive|overlapping`, `else: none`,
//        `protocol: always|on_route`, `as of: now`, `over: day|week|month`) are plain
//        identifiers checked by the walker, not tokens.
//   0.4 → 0.5 (CN-P2 S2.6, CN-D29 — additive minor): the conceptual-model definition forms, so a cn
//   `definition:` / `members:` / `filter:` block (TTR-M 0.17 `embeddedBlock`, ⚑CN-P5) parses with this
//   expression grammar. Where 0.4 already spelled a construct its spelling is the cn one (`:as_of`, `:name`,
//   `hours(24)`, `within(x, last: d)`, `<>`); only what it lacked is added — cn contracts §1.1 "The 0.5
//   spellings", A-CN-25…31:
//     1. `[not] exists C [v] (, C [v])* involving p [as label] (, …)* [where e]` (DC-2/7/11) and the
//        concept aggregate `count(C [v] involving … [where …] (, name: e)*)` + `.quality…` (DC-4/9).
//     2. `x [not] in <call>` beside `in (<list>)` — `code in descendants(Kind, 'X')` (DC-1/8).
//     3. The temporal words, infix at the comparison level: `at_or_before before contains ended_before
//        overlaps` (DC-12).
//     4. `this` (+ a dotted path) is its own node; `end` is legal after a dot (`this.period.end`).
//     5. New tokens, all in `identifier`: EXISTS INVOLVING WHERE THIS AT_OR_BEFORE BEFORE CONTAINS ENDED_BEFORE
//        OVERLAPS. Eight are soft — still usable as column names and calc targets. `this` is not: in an expression
//        it always reads as the cn `this`, so a 0.4 column named `this` is now TTRP-CN-001 (review-119 M-3; rename
//        the column). Every other 0.4 document parses to the same AST — a temporal word followed by `:` (a route or
//        a config key) stays what 0.4 read, see `predicate`.
//     6. The grammar stays target-neutral: a TTR-P document refuses the forms wherever they sit (TTRP-CN-001,
//        reported by the document parse); a standalone expression (`cnDefinition`) reads them.
//     `standaloneExpr : expr EOF` is the standalone-expression entry (ttr-core#202 — a tail is an error).
//
// Kotlin-only generation (G-b): this .g4 is read directly by the ANTLR Gradle
// plugin in ttrp-frontend; there is no antlr-ng/TS target and no TextMate grammar.
//
// Design notes (see docs/ttr-p/design/05-canonical-dsl-options.md §"The converged
// hero rendering" and contracts §3):
//   · γ-hybrid statements: chains (`a -> filter(…) -> sort(…)`) + SSA assignment,
//     freely mixed; precedence `=` < `->` < call (C3-a/C3-a-iv).
//   · `->` is ONE token for chains AND program-level wiring (C3-b).
//   · Chain elements are op-calls or references — NOT arbitrary expressions.
//     Expressions live only inside op arguments / config entries. This is what
//     keeps statement-level `=` (assignment) unambiguous vs expression `=`
//     (equality, S9): the two never meet in the same grammar position.
//   · Named-only multi-in; union list form — enforced in the walker, not here
//     (C3-c/S11 → TTRP-PRS-003/004).
//   · Closed containers, program-level wiring (C3-d-iii); keyword control
//     `after`/`with`, `finishes with` reserved (C3-e/F-b → TTRP-CTL-001).
//   · Two error ports `err`/`rejects` (C3-f); tagged blocks lexed opaque (C3-g).
//   · Reserved port names (`in out err rejects true false else`) stay lexable as
//     identifier/idPart fragments; reservation is a WALKER check (S10 → PRS-005).

// 0.5 -> 0.6 (AU-core-P3, additive): a decision body may be a tagged TTR-B route list.
// Other fragment dialects in decisions remain TTRP-REC-070; canonical decision syntax is unchanged.
grammar TTRP;

// 0.5 (review-119 M-2): the one parser mode. `TtrpParser.parseExpression` (a cn `definition:` / `members:` /
// `filter:` block, the expression corpus) sets `cnDefinition`; a document parse leaves it false. It gates ONE arm,
// the temporal infix of `predicate` — see there. Java, because this grammar has the one (Kotlin/JVM) target.
@parser::members {
    /** True while a standalone expression is parsed (a cn definition block); false for a TTR-P document. */
    public boolean cnDefinition = false;

    /**
     * Whether the temporal word ahead (`before`, `contains`, …) is the 0.5 infix. In a document it is not when a
     * `:` follows it: there the word is a recipe route or a config key (`before: is_early`, `before :as_of`), which
     * 0.4 parsed so — never the infix, whatever the spacing. A definition has no routes; the infix always reads.
     */
    public boolean temporalInfix() {
        return cnDefinition || _input.LA(2) != COLON;
    }
}

// =============================================================================
// Parser
// =============================================================================

document        : statement* EOF ;

statement
    : usesWorld
    | importDecl
    | schemaDecl                 // program-level `def schema <name> { col: type, … }` (D-c, Stage 1.3)
    | paramDecl                  // PL-P2.S1 (F-4-i): program-level runtime param `param <n>: <type> = <default>`
    | recipeBlock                // AU-core-P0 (D-25): the recipe block — listed before bindingOrChain so a soft
                                 // `recipe { … }` is the block, never an op-call with a config block
    | containerDecl
    | controlBlock
    | programHeader              // parses ONLY to name the S12 rejection (walker → TTRP-PRS-002)
    | controlStmt                // b after a / a with b / a finishes with b
    | bindingOrChain
    | cubeletStmt                // MD cubelet statements (D20–D24) — listed AFTER bindingOrChain so
                                 // a chain-viable `x = a` stays an Assignment; dispatch is semantic (R24)
    ;

usesWorld       : USES WORLD STRING ;
importDecl      : IMPORT qname (DOT STAR)? ;
programHeader   : PROGRAM identifier ;
schemaDecl      : DEF SCHEMA identifier LBRACE (schemaField (COMMA schemaField)*)? RBRACE ;

// PL-P2.S1 (F-4-i) runtime params: declared + typed, bound at trigger time. The default
// is a scalar literal OR a `@builtin` (BUILTIN token; `@run-date` in v1). Types + builtin
// validity are WALKER/TtrpChecks checks (TTRP-PARAM-*), not grammar, so `typeName` reuse
// keeps the surface uniform. Legal only against a world whose executor manifest declares
// `params` (T6 gate, ttrp-graph — else a capability error).
paramDecl       : PARAM identifier COLON typeName (ASSIGN paramDefault)? ;
paramDefault    : BUILTIN | literal ;

// C3-a-iv precedence: `=` < `->` < call — encoded by rule nesting, not token
// precedence. Assignment vs bare chain is decided by the token AFTER the leading
// identifier (`=` ⇒ assignment; `(` / `.` / `->` / end ⇒ chain), so there is no
// ambiguity: a chainElem never begins `identifier =`.
bindingOrChain
    : identifier ASSIGN chain    # assignment          // SSA reassignment legal (Q7-γ)
    | chain                      # chainStmt           // incl. program-level wiring
    ;

chain           : chainElem (ARROW chainElem)* ;       // one token for chains AND wiring (C3-b)
chainElem       : opCall | dottedRef ;                 // dottedRef = variable | node.port | qname
                                                        // (which one is a RESOLUTION question — D-b)

opCall
    : identifier LPAREN argList? RPAREN configBlock?
    | identifier configBlock                            // wide-op block form: aggregate { … }
    ;
argList         : arg (COMMA arg)* ;
arg             : (identifier COLON)? argValue ;        // named canonical; bare positional allowed
                                                        // ONLY as a single-in op's source or a union
                                                        // list element — enforced in the walker
                                                        // (TTRP-PRS-003/004), NOT the grammar.
argValue        : RELATION qname | schemaLiteral | expr ;

configBlock     : LBRACE (configEntry COMMA?)* RBRACE ;
configEntry
    : GROUP BY identifier (COMMA identifier)*
    | GROUP_BY COLON identifier (COMMA identifier)*        // AU-core-P0: the LD spelling (D-108)
    | identifier ASSIGN expr
    ;

controlStmt     : identifier AFTER identifier                    # afterFs      // FS
                | identifier WITH identifier                     # withSs       // SS
                | identifier FINISHES WITH identifier            # finishesFf   // reserved → TTRP-CTL-001
                ;
controlBlock    : CONTROL LBRACE controlStmt* RBRACE ;

// MD cubelet statements (contracts §1.2, D20–D24). LHS dispatches semantics (R24, S5C):
// an mdPath LHS is a slice; a bare identifier is a cubelet/virtual/fresh target. The parser
// stays mechanical — the four operators, an expr RHS, and an optional free-form `with` object.
cubeletStmt     : cubeletLhs op=(ASSIGN | ASSIGN_MAT | PLUS_ASSIGN | MINUS_ASSIGN) expr withClause? ;
cubeletLhs      : mdPath | identifier ;                          // mdPath first: a dotted LHS is a path
withClause      : WITH mdObject ;                                // keys (shape/table/journal) checked semantically (R27)
mdObject        : LBRACE (mdObjectEntry (COMMA mdObjectEntry)*)? RBRACE ;
mdObjectEntry   : identifier COLON mdObjectValue ;
mdObjectValue   : qname | STRING | numericLiteral | TRUE | FALSE ;

// ---- RECIPE (AU-core-P0 · S0.1; AU ttr-core contracts §1) -----------------------------
// A recipe is a closed block of role containers + one decision + the action wiring. The
// structure is grammar; every value the contract enumerates is a walker check (TTRP-PRS-006)
// and every cross-reference (routes ↔ wiring, `:name` ↔ `parameters:`, actions) is the
// S1.1 RecipeChecker's (TTRP-REC-*).
recipeBlock     : RECIPE LBRACE recipeHeader* recipeMember* RBRACE ;
recipeHeader
    : KEY COLON keyRef                                          # recipeKeyHeader
    | AS OF COLON (identifier | STRING)                         # recipeAsOfHeader       // now | a literal instant (replay)
    | PARAMETERS COLON LBRACK (identifier (COMMA identifier)* COMMA?)? RBRACK   # recipeParametersHeader
    | DOCUMENT COLON STRING                                     # recipeDocumentHeader
    | PROTOCOL COLON identifier                                 # recipeProtocolHeader   // always | on_route (D-80)
    | RECIPE COLON identifier                                   # recipeNameHeader       // contracts §6 multi-skeleton
    ;
keyRef          : qname | LBRACK qname (COMMA qname)* COMMA? RBRACK ;                  // composite key
recipeMember    : roleContainer | decisionBlock | actionsBlock ;

// A role container: the container header with an island FORM instead of a bare `target`
// (D-19). A body is required for `target` (walker) and optional for the other forms.
roleContainer
    : role=(CANDIDATES | CONTEXT) identifier portSig? islandForm containerAttr* windowClause?
      ( LBRACE statement* RBRACE | TAGGED_BLOCK | FROM STRING )?
    ;
islandForm
    : TARGET qname                                              # targetForm
    | BUILTIN_FORM qname configBlock?                           # builtinForm
    | FOREIGN identifier grain SCHEMA identifier                # foreignForm            // `foreign python per candidate schema s`
    | CALL qname grain?                                         # callForm               // a `def service`
    | TURN qname ASK STRING ANSWER identifier                   # turnForm               // nondeterministic; answer = a def schema
    ;
grain           : PER CANDIDATE | SET ;
windowClause    : WINDOW LBRACE BY COLON identifier (COMMA identifier)* COMMA? OVER COLON overSpec RBRACE ;
overSpec        : identifier (COLON (INT | durationLiteral))? ;    // day | week | month | rows: N | gap: <duration>

// The decision: ordinary statements, then exactly one `switch` statement (its routes are
// the decision's outputs). `-> switch {` is a STATEMENT here, not the `switch` op of a chain.
decisionBlock   : DECISION identifier portSig (LBRACE statement* switchStmt RBRACE | TAGGED_BLOCK) ;
switchStmt      : dottedRef ARROW SWITCH LBRACE (MODE COLON identifier)? route* elseRoute? RBRACE ;
route           : identifier COLON expr routeAttr* ;
elseRoute       : ELSE COLON identifier routeAttr* ;             // a named default route, or `none` (no verdict row)
routeAttr
    : BECAUSE COLON STRING                                      # becauseAttr            // "{column}" fills from the row (D-108)
    | DETAIL COLON identifier (COMMA identifier)*               # detailAttr             // context names attached per key
    ;

// The action wiring. A value is an expression (`order_number`, `role.sales`, `2 working_days`,
// `working_days(:settlement)`), an action call (`on_due: hold { … }`) or a bare comma list
// (`detail: shortfall, price_differences` — a new argument starts at `name:`).
actionsBlock    : ACTIONS LBRACE wiring* RBRACE ;
wiring          : routeRef (COMMA routeRef)* ARROW identifier wiringArgs? (WHEN COLON expr)? ;   // when: → a route split (17 §5)
routeRef        : identifier DOT identifier ;                    // verdict.release · verdict.all
wiringArgs      : LBRACE (wiringArg (COMMA wiringArg)* COMMA?)? RBRACE ;
wiringArg       : identifier COLON wiringValue (COMMA wiringValue)* ;
wiringValue     : identifier wiringArgs                         # wiringCall
                | durationLiteral                               # wiringDuration   // `due: 2 working_days`
                | expr                                          # wiringExpr
                ;
// ------------------------------------------------------------------------------------

containerDecl   : CONTAINER identifier portSig? TARGET qname containerAttr*
                  ( LBRACE statement* RBRACE | TAGGED_BLOCK | FROM STRING ) ;   // closed containers (C3-d-iii)
// `from "<path>"` (AG B5): a FILE-BACKED fragment — the file's whole content is the interior
// (byte-preserved, like a TAGGED_BLOCK's), the path relative to the program, the dialect read from
// the file's extension / first-line marker (`.ttrb`, `.ttrb-cs`, `.ttr.sql`, `.ttr.py`).

// PL-P2.S1 island execution attributes (F-4-iv on-failure, F-4-ii retries). An
// `on failure of <island>` container runs IFF the named source island failed
// (manifest `island.onFailureOf`); `absorbs` stays RESERVED (→ TTRP-FAIL-003, the
// `finishes with` precedent). `retries N` = manifest-declared per-island retry count.
containerAttr   : ON FAILURE OF identifier ABSORBS?   # onFailureAttr
                | RETRIES INT                          # retriesAttr
                ;
portSig         : LPAREN portDecl (COMMA portDecl)* RPAREN ;
portDecl        : (IN | OUT | ERR) identifier ;                  // hero writes `err rejects` — an
                                                                 // err-kind port NAMED rejects (R4;
                                                                 // typing revisited in Stage 2.1).

// Inline schema literal (D-c, Stage 1.3): `schema: { col: type, … }`. Column types
// reuse the Stage 1.2 `typeName` production (S23 spellings, e.g. `decimal(12,2)`),
// shared with `def schema` via `schemaField`.
schemaLiteral   : LBRACE (schemaField (COMMA schemaField)*)? RBRACE ;
schemaField     : identifier COLON typeName ;

qname           : identifier (DOT identifier)* ;
dottedRef       : identifier (DOT idPart)* ;

// ---- expression grammar (STAGE 1.2 — the ONE PL expression layer, T5-e) --------
// Precedence ladder: or < and < not < predicate < additive < multiplicative <
// unary < primary. `=` IS the equality operator inside expression context (S9),
// context-separated from statement binding by grammar position; `==` stays lexable
// (EQEQ) and is rejected by the walker (→ TTRP-EQ-001).
//
// `between … and …` sits at the predicate level with `addExpr` operands: this
// resolves the `and` overlap with boolean `andExpr` structurally — no predicates,
// no backtracking — because `between addExpr AND addExpr` is a fixed shape ANTLR's
// ALL(*) commits to before the higher `andExpr` loop can see the `and`.
expr            : orExpr ;
standaloneExpr  : expr EOF ;                                 // 0.5: `parseExpression`'s entry — a trailing tail is an error (ttr-core#202)
orExpr          : andExpr (OR andExpr)* ;
andExpr         : notExpr (AND notExpr)* ;
notExpr         : NOT notExpr | predicate ;
predicate       : addExpr ( (ASSIGN | EQEQ | NEQ | LT | LTE | GT | GTE) addExpr
                          | IS NOT? NULL
                          | NOT? IN LPAREN expr (COMMA expr)* RPAREN
                          | NOT? IN functionCall                    // 0.5: `code in descendants(Kind, 'X')` (DC-1)
                          | BETWEEN addExpr AND addExpr
                          | {temporalInfix()}? temporalOp addExpr   // 0.5: `e.period contains :as_of` (DC-12)
                          )? ;
// The temporal infix and a recipe `switch`: routes are juxtaposed (`route : identifier COLON expr`), so after a
// condition with no comparison (`release: … and is_clear`) a route named `before` / `contains` / `overlaps` /
// `at_or_before` / `ended_before` would otherwise be read as `is_clear before :is_early` — ALL(*) takes the
// optional arm (review-119 M-2). `temporalInfix()` keeps the arm off in a document wherever a `:` follows the word,
// so every 0.4 document keeps its AST; in a document the infix is TTRP-CN-001 anyway, and a definition never has a
// route.
temporalOp      : AT_OR_BEFORE | BEFORE | CONTAINS | ENDED_BEFORE | OVERLAPS ;
addExpr         : mulExpr ((PLUS | MINUS) mulExpr)* ;
mulExpr         : unaryExpr ((STAR | SLASH) unaryExpr)* ;
unaryExpr       : MINUS unaryExpr | primary ;
primary
    : literal
    | paramRef                                              // AU-core-P0: `:name` — a parameter / runtime binding (D-108)
    | castExpr
    | caseExpr
    | existsExpr                                            // 0.5 (cn): before functionCall/dottedRef — `exists` is soft
    | conceptAggregate                                      // 0.5 (cn): `count(Visit involving this)` — before functionCall
    | functionCall
    | thisRef                                               // 0.5 (cn): `this`, `this.from.period.end` — before dottedRef
    | dottedRef                                             // column or port.column (C3-a-iv-4)
    | mdPath                                                // MD dot-path (D14) — LAST: only catches
                                                            // chains dottedRef can't (numeric/quoted/
                                                            // set/range/star). `floatLiteral` wins via
                                                            // `literal` above (R1: float before path).
    | LPAREN expr RPAREN
    ;
castExpr        : CAST LPAREN expr AS typeName RPAREN ;      // explicit cast only (B-T5)
caseExpr        : CASE (WHEN expr THEN expr)+ (ELSE expr)? END ;
functionCall    : identifier LPAREN (DISTINCT? fnArg (COMMA fnArg)*)? RPAREN ;  // distinct → AggregateCall only
fnArg           : (fnArgName COLON)? expr ;                  // AU-core-P0: named (`age(since: x)`) or positional
fnArgName       : identifier | AND | OR | BETWEEN | NOT ;        // `working_days(between: a, and: :as_of)`
paramRef        : COLON identifier ;                         // `:as_of` · `:tolerance`

// ---- the conceptual-model forms (0.5, CN-D29 — cn contracts §1.1 "The 0.5 spellings") --------------------------
// Parsed here, target-neutral; a TTR-P document refuses them (TTRP-CN-001), a cn `definition:` / `members:` /
// `filter:` block reads them. `where` is greedy: it takes the rest of the expression, so an `exists` followed by an
// `or` is parenthesized. A concept aggregate's trailing arguments are NAMED (`by:`), so a participant list and an
// argument never read alike.
existsExpr       : EXISTS conceptBinding (COMMA conceptBinding)* involvingClause (WHERE expr)? ;
conceptAggregate : identifier LPAREN conceptBinding (COMMA conceptBinding)* involvingClause (WHERE expr)?
                   (COMMA namedArg)* RPAREN (DOT idPart)* ;  // `sum(Sale involving this).revenue` — a quality after
conceptBinding   : identifier identifier? ;                  // `Visit` · `Visit v1`
involvingClause  : INVOLVING participant (COMMA participant)* ;
participant      : (thisRef | dottedRef) (AS identifier)? ;  // `this as customer` · `this.request.order`
namedArg         : fnArgName COLON expr ;
thisRef          : THIS (DOT idPart)* ;
durationLiteral : INT durationUnit ;                         // `30 days` · `2 working_days` — wiring + window only (M1)
durationUnit    : DAYS | WORKING_DAYS | WEEKS | MONTHS | MINUTES | HOURS ;
typeName        : identifier (LPAREN INT (COMMA INT)? RPAREN)? ;              // decimal(12,2) — S23 spellings
literal         : STRING | CHAR_STRING | numericLiteral | TRUE | FALSE | NULL ;

// MD dot-path float/path split (contracts §1.2/§1.3, D14). `floatLiteral` is exactly one of the
// three float shapes; anything else dotted is an `mdPath`. Reconstruction to a raw number string
// (WS-insignificant, R2) keeps `12.5`-style literals byte-identical (zero behaviour change).
numericLiteral  : floatLiteral | INT ;
floatLiteral    : INT DOT INT                               // 12.5, 2025.06
                | DOT INT                                   // .25
                | INT DOT                                   // 25.
                ;
mdPath          : pathComponent (DOT pathComponent)+ ;
pathComponent   : identifier                                // member, level, measure, agg, cubelet
                | INT                                        // numeric member (2025, 06)
                | STRING                                     // quoted member: "Kaufland K123"
                | LBRACE pathAtom (COMMA pathAtom)* RBRACE   // set (D15: braces compulsory)
                | pathAtom DOTDOT pathAtom                   // range: 2024..2026
                | STAR                                       // free dimension (bound to an attr — R7)
                ;
pathAtom        : identifier | INT | STRING ;
// -------------------------------------------------------------------------------

// Soft keywords: structural words that must still be usable as names. `in/out/err`
// are port-kind keywords but also legal port names / refs (`prep.err`) / the
// PRS-005 reject subject (`err = …`); `by` is both `group by` and the `by:` arg
// name. `true/false/null` are literals but also dotted-ref parts (`b.true`).
// `schema` is the S23 inline-schema keyword but also a legal arg name (`schema: s`).
// `distinct` is the canonical `distinct()` op name (GraphBuilder, S3.5) but also
// `functionCall`'s SQL-style DISTINCT? modifier (`count(distinct x)`) — same
// dual-use pattern; the two never collide (DISTINCT? there is a fixed pre-`expr`
// position, distinct() here is `identifier LPAREN …` — ANTLR's context disambiguates).
// `on` is a SOFT keyword: it opens the PL-P2 `on failure of …` container attribute but
// must stay usable as a name — the hero writes `join(…, on: left.k = right.k)` (`on:` arg
// name). Like `by`/`schema`, it is promoted back into `identifier`; the `on failure of`
// clause is unambiguous by position (it only follows `target <qname>` on a container).
// `param`/`failure`/`of`/`retries`/`absorbs` are hard keywords (no corpus collision).
// `from` (AG B5) is soft too: it only opens a file-backed container body after `target <qname>`.
// AU-core-P0: every recipe word is soft too (the TTR-M WORLD precedent) — `key`, `mode`,
// `detail`, `context`, `set`, `days`, `recipe` … are ordinary column / port / op names.
identifier      : IDENT | IN | OUT | ERR | BY | SCHEMA | DISTINCT | ON | FROM
                | RECIPE | KEY | PARAMETERS | DOCUMENT | PROTOCOL | CANDIDATES | CONTEXT | DECISION
                | ACTIONS | BUILTIN_FORM | FOREIGN | PER | CANDIDATE | SET | CALL | TURN | ASK | ANSWER
                | WINDOW | OVER | SWITCH | MODE | BECAUSE | DETAIL | GROUP_BY
                | DAYS | WORKING_DAYS | WEEKS | MONTHS | MINUTES | HOURS
                | EXISTS | INVOLVING | WHERE | THIS | AT_OR_BEFORE | BEFORE | CONTAINS | ENDED_BEFORE | OVERLAPS   // 0.5 (cn)
                ;
idPart          : identifier | TRUE | FALSE | NULL | END ;   // 0.5: `end` after a dot — `this.period.end` (DC-6)

// =============================================================================
// Lexer
// =============================================================================

// Structural keywords (declared before IDENT so they win maximal-munch ties).
USES        : 'uses' ;
WORLD       : 'world' ;
IMPORT      : 'import' ;
PROGRAM     : 'program' ;
CONTAINER   : 'container' ;
DEF         : 'def' ;
TARGET      : 'target' ;
CONTROL     : 'control' ;
AFTER       : 'after' ;
WITH        : 'with' ;
FINISHES    : 'finishes' ;
GROUP       : 'group' ;
BY          : 'by' ;
RELATION    : 'relation' ;
SCHEMA      : 'schema' ;
IN          : 'in' ;
OUT         : 'out' ;
ERR         : 'err' ;

// PL-P2.S1 (F-4-i/F-4-iv/F-4-ii) — runtime params + on-failure islands.
// (`on` is a soft keyword — also in `identifier` — for the `on:` join arg; `absorbs`
//  is reserved, F-4-iv γ → TTRP-FAIL-003. Comments stay off the token lines so the
//  KeywordTable drift regex `NAME : 'lit' ;` matches — S16.)
PARAM       : 'param' ;
ON          : 'on' ;
FAILURE     : 'failure' ;
OF          : 'of' ;
RETRIES     : 'retries' ;
ABSORBS     : 'absorbs' ;
FROM        : 'from' ;

// AU-core-P0 · S0.1 — the recipe block (all soft: also in `identifier`). Comments stay off
// the token lines (the KeywordTable drift regex, S16).
RECIPE       : 'recipe' ;
KEY          : 'key' ;
PARAMETERS   : 'parameters' ;
DOCUMENT     : 'document' ;
PROTOCOL     : 'protocol' ;
CANDIDATES   : 'candidates' ;
CONTEXT      : 'context' ;
DECISION     : 'decision' ;
ACTIONS      : 'actions' ;
BUILTIN_FORM : 'builtin' ;
FOREIGN      : 'foreign' ;
PER          : 'per' ;
CANDIDATE    : 'candidate' ;
SET          : 'set' ;
CALL         : 'call' ;
TURN         : 'turn' ;
ASK          : 'ask' ;
ANSWER       : 'answer' ;
WINDOW       : 'window' ;
OVER         : 'over' ;
SWITCH       : 'switch' ;
MODE         : 'mode' ;
BECAUSE      : 'because' ;
DETAIL       : 'detail' ;
GROUP_BY     : 'group_by' ;
DAYS         : 'days' ;
WORKING_DAYS : 'working_days' ;
WEEKS        : 'weeks' ;
MONTHS       : 'months' ;
MINUTES      : 'minutes' ;
HOURS        : 'hours' ;

// 0.5 (CN-D29) — the conceptual-model forms (all in `identifier`; eight soft, `this` not). Comments stay off the token lines
// (the KeywordTable drift regex, S16).
EXISTS       : 'exists' ;
INVOLVING    : 'involving' ;
WHERE        : 'where' ;
THIS         : 'this' ;
AT_OR_BEFORE : 'at_or_before' ;
BEFORE       : 'before' ;
CONTAINS     : 'contains' ;
ENDED_BEFORE : 'ended_before' ;
OVERLAPS     : 'overlaps' ;

// Expression keywords.
AND         : 'and' ;
OR          : 'or' ;
NOT         : 'not' ;
IS          : 'is' ;
NULL        : 'null' ;
TRUE        : 'true' ;
FALSE       : 'false' ;
BETWEEN     : 'between' ;
CASE        : 'case' ;
WHEN        : 'when' ;
THEN        : 'then' ;
ELSE        : 'else' ;
END         : 'end' ;
CAST        : 'cast' ;
AS          : 'as' ;
DISTINCT    : 'distinct' ;

// Operators & punctuation (multi-char before single-char for maximal munch).
ARROW        : '->' ;
EQEQ         : '==' ;
NEQ          : '<>' ;
LTE          : '<=' ;
GTE          : '>=' ;
DOTDOT       : '..' ;    // MD range (declared before DOT — maximal munch keeps `..` one token)
ASSIGN_MAT   : ':=' ;    // materialize (D22 — before COLON)
PLUS_ASSIGN  : '+=' ;    // merge (D23)
MINUS_ASSIGN : '-=' ;    // delete (D24)
ASSIGN       : '=' ;
LT           : '<' ;
GT           : '>' ;
PLUS         : '+' ;
MINUS        : '-' ;
STAR         : '*' ;
SLASH        : '/' ;
LPAREN       : '(' ;
RPAREN       : ')' ;
LBRACE       : '{' ;
RBRACE       : '}' ;
LBRACK       : '[' ;
RBRACK       : ']' ;
COMMA        : ',' ;
COLON        : ':' ;
DOT          : '.' ;

// Tagged blocks (C2-f: interiors byte-preserved — captured verbatim, no dedent).
// Single-token, non-greedy `.*?`; declared BEFORE STRING so the `"""` tag wins the
// equal-length tie (same trick as TTR.g4's TAGGED_BLOCK_LITERAL).
TAGGED_BLOCK  : '"""' [a-zA-Z] [a-zA-Z0-9-]* [ \t]* '\r'? '\n' .*? '"""' ;
STRING        : '"' (~["\r\n])* '"' ;
CHAR_STRING   : '\'' (~['\r\n])* '\'' ;

LINE_COMMENT  : '//' ~[\r\n]* -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN) ;
WS            : [ \t\r\n]+ -> skip ;

// PL-P2.S1 (F-4-i): a runtime-param builtin default, e.g. `@run-date`. Its own token
// (hyphens allowed inside, like TAGGED_BLOCK's tag) so `run-date` lexes as ONE builtin
// name rather than `run` MINUS `date`; the `@` sigil is unused elsewhere (collision-free).
// Builtin-name validity (`run-date` only, v1) is a TtrpChecks check, not the grammar.
BUILTIN       : '@' [a-zA-Z] [a-zA-Z0-9-]* ;

INT           : [0-9]+ ;              // MD dot-path (D14): NUMBER split into INT + `floatLiteral`
                                      // parser rule so `2025.06` can be a float OR a path by context.
// Latin letters incl. the Latin-1 Supplement + Latin Extended-A/B block (\u00C0-\u024F) — the same
// range TTR.g4's IDENT admits, so a program can name the model objects it loads (`zakázka`,
// `id_zakázky`): identifiers are the client's own model names and are never translated (AG C-23).
IDENT         : [a-zA-Z_\u00C0-\u024F] [a-zA-Z0-9_\u00C0-\u024F]* ;
