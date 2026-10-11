// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.diagnostics

import org.tatrman.ttrp.ast.SourceLocation

/**
 * The single TTR-P diagnostic catalogue (contracts §8): stable ids
 * `TTRP-<AREA>-<NNN>`, each rejected form carrying a suggested alternative. This
 * enum later feeds `ttrp/authoringContext`'s diagnostics table — keep messages
 * self-contained. Stages 1.2/1.3 append their own ids (EQ/FN/AGG/TYP/EXP, WLD/RES/
 * SCH/CFG/MOV, …); the area list is extensible by design.
 */
enum class TtrpDiagnosticId(
    val id: String,
    val suggestedAlternative: String?,
) {
    EQ_001("TTRP-EQ-001", "use `=` — it is the one equality operator; `==` is only a TTR-pandas synonym (S9)"),
    CTL_001(
        "TTRP-CTL-001",
        "`finishes with` (FF) is reserved and not available in v1; use `after` (FS) or `with` (SS) (F-b)",
    ),

    // ---- Stage 2.1 graph-construction ids (CTL-002..006; CTL-001 above is contracts-pinned) ----
    CTL_002(
        "TTRP-CTL-002",
        "the graph must be acyclic (B-T2); break the cycle among the named nodes",
    ),
    CTL_003(
        "TTRP-CTL-003",
        "a data in-port takes exactly one edge (B-T2, no implicit union); use `union(a, b)` to merge",
    ),
    CTL_004(
        "TTRP-CTL-004",
        "cross-container `err` (signal) is not supported in v1 (F-d-i); consume `err` inside the island or rely on " +
            "fail-fast; `rejects` (data) may cross",
    ),
    CTL_005(
        "TTRP-CTL-005",
        "`display` is a sink-only leaf (Q11); read from the node feeding it instead",
    ),
    CTL_006(
        "TTRP-CTL-006",
        "this is a reserved port name (in, out, err, rejects, true, false, else) — rename the declared port (S10)",
    ),

    // ---- RJ-P1 rejects producer diagnostics (contracts §9). Row codes TTRP-RJ-0xx are
    // string literals sourced from the validity YAMLs (not enum ids); these are the
    // AUTHORING diagnostics. 101/102/105 fire as WARNING, 103/104/106/107/108 as ERROR
    // (severity is set at the call site). 107/108 are the RJ-P5-review fail-closed guards:
    // a wired reject site whose domain (107) or position (108) v1 does not emit faithfully
    // is a compile error, never a silent accept-all guard or dropped stream.
    RJ_101(
        "TTRP-RJ-101",
        "this node can never reject — remove the `rejects` wire (the stream is always empty; R-A2-α)",
    ),
    RJ_102(
        "TTRP-RJ-102",
        "the rejects cluster was moved off this engine because it cannot produce rejects (knob=escalate)",
    ),
    RJ_103(
        "TTRP-RJ-103",
        "the `_ttrp_` column prefix is reserved for synthesized rejects columns — rename this column (RS-5)",
    ),
    RJ_104("TTRP-RJ-104", "a volatile (impure) function cannot appear in a reject-capable position (R-C2-b)"),
    RJ_105("TTRP-RJ-105", "this ON expression spans both inputs — its rejects fall back to the pair schema (R-B3-β)"),
    RJ_106(
        "TTRP-RJ-106",
        "this engine cannot produce rejects; set `[ttrp] rejects-in-sql = escalate` to move the cluster to a " +
            "capable engine, or bind a rejects-capable engine (contracts §3/§4)",
    ),
    RJ_107(
        "TTRP-RJ-107",
        "only `cast(x as int)` (text->int64) and `op.div` produce rejects in v1; this reject-capable type is not " +
            "yet supported — remove the `rejects` wire, or change the target type (contracts §2 v1 roster)",
    ),
    RJ_108(
        "TTRP-RJ-108",
        "reject-capable expressions in a join `on:` do not produce rejects in v1 — move the `cast`/`op.div` into a " +
            "`calc` before the join and wire `rejects` there (contracts §5)",
    ),
    PRS_001("TTRP-PRS-001", null), // generic syntax error (ANTLR-reported)
    PRS_002("TTRP-PRS-002", "TTR-P has no `program` header — identity is the filename; delete this line (S12)"),
    PRS_003("TTRP-PRS-003", "multi-input ops take named inputs only: join(left: …, right: …) (C3-c)"),
    PRS_004("TTRP-PRS-004", "union takes the list form: union(a, b, c) (S11)"),
    PRS_005(
        "TTRP-PRS-005",
        "this name is a reserved port name (in, out, err, rejects, true, false, else) — choose another (S10)",
    ),
    FRG_001("TTRP-FRG-001", "supported fragment dialects: sql, pandas, ttrb, ttrb-cs (C3-g/C4-f)"),

    // ---- Stage 6.3 bare-fragment programs (FRG) ----
    FRG_002(
        "TTRP-FRG-002",
        "no dialect marker: use the .ttr.sql / .ttr.py extension or a first-line `-- ttr: dialect=…` comment (contracts §1)",
    ),
    FRG_003(
        "TTRP-FRG-003",
        "a bare-fragment program needs [ttrp] bare-target (and bare-shell) — no fallback guessing (P2)",
    ),

    // ---- AG B5 file-backed fragments (FRG) ----
    FRG_004(
        "TTRP-FRG-004",
        "check the path — it is relative to the program file (and the program must be saved to a file)",
    ),
    FRG_005(
        "TTRP-FRG-005",
        "name the fragment file .ttrb / .ttrb-cs / .ttr.sql / .ttr.py, or start it with a `# ttr: dialect=…` line",
    ),

    // ---- Stage 6.1 TTR-SQL dialect ids (SQL) — messages/suggestions come from the reject
    // table (ttr-sql.rejects.toml); the enum carries the DEFAULT suggestion, overridable per-site.
    SQL_001("TTRP-SQL-001", "TTR-SQL is read-only; writes go through canonical `store` (A3)"),
    SQL_002("TTRP-SQL-002", "use LIMIT n"),
    SQL_003("TTRP-SQL-003", "generic SQL only — remove the hint"),
    SQL_004("TTRP-SQL-004", "use double quotes"),
    SQL_005("TTRP-SQL-005", "use CAST(x AS type)"),
    SQL_006("TTRP-SQL-006", "no subquery expressions; EXISTS/IN in WHERE are the only subquery forms"),
    SQL_007("TTRP-SQL-007", "window functions are v2"),
    SQL_008("TTRP-SQL-008", "TTR-SQL is one query expression"),
    SQL_009("TTRP-SQL-009", "one query expression per fragment"),
    SQL_010("TTRP-SQL-010", "the fragment's final SELECT is the container's default out port"),
    SQL_011("TTRP-SQL-011", "author this in canonical TTR-P (PIVOT is canonical-only in v1)"),
    SQL_012("TTRP-SQL-012", "lift it into a WITH cte — CTE names become SSA labels"),
    SQL_013("TTRP-SQL-013", "spell the ON condition explicitly"),
    SQL_014("TTRP-SQL-014", "add ORDER BY before LIMIT (deterministic results, A4/Q9)"),
    SQL_015("TTRP-SQL-015", null), // generic TTR-SQL syntax error (grammar reject, no curated form)

    // ---- Stage 6.2 TTR-pandas dialect ids (PD) — messages from ttr-pandas.rejects.toml ----
    PD_001(
        "TTRP-PD-001",
        "not in the TTR-pandas method roster: select calc filter join aggregate sort union limit load store display",
    ),
    PD_002(
        "TTRP-PD-002",
        "no lambdas — write the expression directly in filter/calc (expressions are grammar, not API)",
    ),
    PD_003("TTRP-PD-003", "use load()/store() — IO beyond load() is canonical-land"),
    PD_004("TTRP-PD-004", "use .filter(amount > 0)"),
    PD_005("TTRP-PD-005", "TTR-pandas has no control flow — statements are assignment + chain"),
    PD_006("TTRP-PD-006", "no index — tables are relational; use filter/select"),
    PD_007("TTRP-PD-007", "write the bare column name"),
    PD_008("TTRP-PD-008", "single default-out in fragments — branch in a canonical container"),
    PD_009("TTRP-PD-009", "add .sort() before .limit() (deterministic results, A4/Q9)"), // S15 mirror
    PD_010("TTRP-PD-010", null), // generic TTR-pandas syntax error (grammar reject, no curated form)

    // ---- TTR-B dialect ids (B) — messages + suggested sentences come from the skin's reject table
    // (ttrb/rejects.<lang>.yaml, AG B6); the enum carries the English default suggestion.
    B_001(
        "TTRP-B-001",
        "To ask the host for a change: Set <attribute> of <entity> with key <key> to <value> with reason \"<reason>\".",
    ),
    B_002("TTRP-B-002", "Combine that with <name>. / Store that to <model-ref>."),
    B_003("TTRP-B-003", "Model changes belong in TTR-M; data writes are Store: Store that to <model-ref>."),
    B_004("TTRP-B-004", "Start the sentence with a roster verb, e.g. Keep only the rows where <condition>."),
    B_005("TTRP-B-005", "# a comment"),
    B_006("TTRP-B-006", "Mark the fragment with the sentence's language (`\"\"\"ttrb-cs`) or use this skin's roster."),
    B_007("TTRP-B-007", "Use a listed verbose form or the canonical operator."),
    B_008("TTRP-B-008", "Author the Pivot node in canonical TTR-P."),
    B_101("TTRP-B-101", "Remove the character, or put the text in quotes."),
    B_102("TTRP-B-102", "If <condition>:  — then the block's sentences on the next lines, indented."),
    B_103("TTRP-B-103", "Indent every sentence of a block by the same amount."),
    B_104(
        "TTRP-B-104",
        "Send an e-mail to <recipient> with subject \"<subject>\", template \"<template>\", key <key> [and attachments <a>, <b>].",
    ),
    B_105("TTRP-B-105", "Set <attribute> of <entity> with key <key> to <value> with reason \"<reason>\"."),
    B_106("TTRP-B-106", "Create a manual task for <recipient> \"<title>\" with description \"<description>\"."),
    B_107("TTRP-B-107", "Count the rows of <table> as <name>."),
    B_108("TTRP-B-108", "Attach <table> to the result."),
    B_109("TTRP-B-109", "Choose another name — a keyword cannot name a column, a table or a result."),
    B_110("TTRP-B-110", "Join the conditions with `and` in one block: If <a> and <b>:"),
    B_111("TTRP-B-111", "Send that to output <port>."),
    B_112("TTRP-B-112", "Declare the port in the container header — container <name>(…, out <port>) …"),
    B_113(
        "TTRP-B-113",
        "Name it and use the name — Call that <name>. — continue from it with `that`, or send it to an output.",
    ),

    // ---- Stage 1.2 expression ids (EQ above; FN/AGG/TYP/EXP here) ----
    FN_001(
        "TTRP-FN-001",
        "unknown function — check the spelling against the builtin roster; known aliases suggest their canonical (T5-c)",
    ),
    FN_002(
        "TTRP-FN-002",
        "check the call's arity and argument types against the catalogue signature (T5-c)",
    ),
    AGG_001(
        "TTRP-AGG-001",
        "aggregate functions are only legal inside aggregate(…) / aggregate { … } (B-T5)",
    ),
    EXP_001(
        "TTRP-EXP-001",
        "only input columns are in scope inside op expressions — variables never resolve here (C3-a-iv)",
    ),
    TYP_001(
        "TTRP-TYP-001",
        "add an explicit cast(x as <type>) — there is no implicit coercion across type kinds (B-T5/Q9-4)",
    ),
    TYP_002(
        "TTRP-TYP-002",
        "no cast rule for this type pair — this coercion is undefined; there is no legal cast (B-T5)",
    ),

    // ---- Stage 1.3 resolution ids (WLD / RES / SCH / CFG / MOV) ----
    WLD_001(
        "TTRP-WLD-001",
        "select a world: set `world = \"…\"` under [ttrp] in modeler.toml, or pin one with `uses world \"…\"` (contracts §2)",
    ),
    WLD_002(
        "TTRP-WLD-002",
        "check the world qname against the model repo's `def world` declarations (S22)",
    ),
    WLD_003(
        "TTRP-WLD-003",
        "name a `def world` — this qname is a different kind of object (D-d)",
    ),
    WLD_004(
        "TTRP-WLD-004",
        "exactly one storage may declare `staging: true` in a world (D-f); remove the extra one",
    ),
    RES_001(
        "TTRP-RES-001",
        "no object of the expected kind by that name — add an `import`, use the full qname, or check the world (D-b)",
    ),
    RES_002(
        "TTRP-RES-002",
        "the name is exported by more than one import — qualify it with its full qname (C2-d/D-b, no first-wins)",
    ),
    RES_003(
        "TTRP-RES-003",
        "`target` takes an engine instance — use a `def engine` declared in the world (D-b position typing)",
    ),
    RES_004(
        "TTRP-RES-004",
        "name a `def relation` declared between the two joined entities (D-a sub-2)",
    ),
    RES_005(
        "TTRP-RES-005",
        "bind it in the model (er2db) or reference the db object directly — no er2db binding is reachable (E-d)",
    ),
    RES_006(
        "TTRP-RES-006",
        "the import path resolves to no package — check the package name against the model repo (D-b)",
    ),
    SCH_001(
        "TTRP-SCH-001",
        "only one schema is allowed at each level — remove the duplicate declaration (D-c same-level conflict)",
    ),
    SCH_002(
        "TTRP-SCH-002",
        "declare a schema: inline `schema: { … }`, a program `def schema`, or on the storage in the world (schema-on-read is banned, T7)",
    ),
    SCH_003(
        "TTRP-SCH-003",
        "use a TTR db-schema type (S23): int/integer, decimal, string/text/char/varchar, bool/boolean, float, double, number, date, timestamp, datetime, object, list",
    ),
    CFG_001(
        "TTRP-CFG-001",
        "check the allowed values for this [ttrp] key (contracts §2)",
    ),
    CFG_002(
        "TTRP-CFG-002",
        "unknown [ttrp] key — check the spelling against the contracts §2 key roster",
    ),
    MOV_001(
        "TTRP-MOV-001",
        "`store` takes a storage — use a `def storage` declared in the world (D-b position typing)",
    ),

    // ---- Stage 2.2 world-binding + capability ids (WLD/CAP/MOV; existing 001-004/001 kept) ----
    WLD_005(
        "TTRP-WLD-005",
        "unknown engine type — no shipped capability manifest matches; shipped types: postgres-16, polars, bash (T6)",
    ),
    WLD_006(
        "TTRP-WLD-006",
        "no staging declared: mark one storage `staging: true` in the world, or set `[ttrp] staging` (D-f, contracts §2)",
    ),
    WLD_007(
        "TTRP-WLD-007",
        "no invocation binding for this (data engine, executor) pair — the executor manifest supports: pg, polars, display (F-c)",
    ),
    CAP_001(
        "TTRP-CAP-001",
        "node kind is not native on this engine — Stage 2.3 lowers it or re-places it (informational)",
    ),
    CAP_002(
        "TTRP-CAP-002",
        "function is not in this engine's supported set — Stage 2.3 lowers it or re-places the node (informational)",
    ),
    CAP_003(
        "TTRP-CAP-003",
        "node re-placed to a capable engine (function unsupported); target it explicitly or set `[ttrp] split-policy = error` (T5-b)",
    ),
    CAP_005(
        "TTRP-CAP-005",
        "no engine in the world supports this function — bind it, rewrite it, or add a capable engine (T5-b)",
    ),
    CAP_101(
        "TTRP-CAP-101",
        null,
    ),
    CAP_102(
        "TTRP-CAP-102",
        null,
    ),

    // ---- PL-P2.S1 executor-capability gate (F-4 vocabulary; T6 — the world's executor
    // manifest must declare a feature before a program may use it, else an ordinary
    // capability compile error). CAP-2xx = the F-4 block; the message names the capability. ----
    CAP_201(
        "TTRP-CAP-201",
        "this world's executor does not support runtime `param`s (F-4-i) — deploy to a platform (tatrman) " +
            "executor, or remove the params",
    ),
    CAP_202(
        "TTRP-CAP-202",
        "this world's executor does not support `on failure of` islands (F-4-iv) — deploy to a platform " +
            "(tatrman) executor, or remove the on-failure container",
    ),
    CAP_203(
        "TTRP-CAP-203",
        "this world's executor does not support per-island `retries` (F-4-ii) — deploy to a platform " +
            "(tatrman) executor, or remove the retries attribute",
    ),
    CAP_204(
        "TTRP-CAP-204",
        "this world's executor is read-only and does not allow `store` — route the rows to a `display`, " +
            "or deploy to an executor that writes (bash / tatrman)",
    ),
    MOV_002(
        "TTRP-MOV-002",
        "cannot stage between these engines via the staging storage — one side cannot read/write it (T6-e); pick a reachable `via`",
    ),
    MOV_003(
        "TTRP-MOV-003",
        "data leaves an `rls: true` storage — verify this egress is intended, or set `[ttrp] rls-egress = error` (Q8)",
    ),
    MOV_004(
        "TTRP-MOV-004",
        "this engine has no read relation to the loaded storage — load it in a container targeting a capable engine (T6-e)",
    ),

    // ---- Stage 5.2 `.ttrl` view-state pair-integrity ids (LAY) ----
    LAY_001(
        "TTRP-LAY-001",
        "layout entries no longer match the graph (SSA chain changed or nodes removed) — reset or re-place them (C1-c-i)",
    ),
    LAY_002(
        "TTRP-LAY-002",
        "the `.ttrl` sidecar failed to parse — fix or delete it; the canvas falls back to auto-layout",
    ),
    LAY_003(
        "TTRP-LAY-003",
        "the `.ttrl` sidecar references a canvas that is not in the graph — remove the stale canvas block",
    ),

    // ---- Stage 5.4 graphical-edit ids (EDIT) ----
    EDIT_001(
        "TTRP-EDIT-001",
        "stale document version — re-pull the graph and replay the edits against the current version (C1-d-iii)",
    ),
    EDIT_002(
        "TTRP-EDIT-002",
        "canvas-edit refusal — a fragment interior / derived container (edit the fragment as text; C2-f, C1-b-iv), " +
            "or a node removal that would orphan live outputs (the refusal names the dependents; FO-A1 §1)",
    ),
    EDIT_003(
        "TTRP-EDIT-003",
        "unknown or unsupported graph-edit op — use the closed β vocabulary (C1-d-i); everything else is a text edit",
    ),
    EDIT_004(
        "TTRP-EDIT-004",
        "invalid edit target (unknown ζ / container, or an occupied single-in port) — no partial edits are applied (C1-d)",
    ),

    // ---- MD dot-path ids (area MD; the resolver's TTRP-MD-001..014 roster, contracts §6) ----
    // Mirrors `org.tatrman.ttr.md.resolve.MdDiagId`: the frontend surfaces a resolver
    // `MdDiagnostic` at the path's source range through these enum seats. The one-line
    // message comes from the resolver's §6 text + per-occurrence detail (S3-A4); the
    // `suggestedAlternative` here is the authoring hint for the actionable rejects.
    MD_001("TTRP-MD-001", null),
    MD_002("TTRP-MD-002", null),
    MD_003("TTRP-MD-003", "the path is ambiguous — pick one of the listed canonical forms, or add a qualifier"),
    MD_004("TTRP-MD-004", "bind the `*`/set/range to an attribute with a qualified pair (e.g. `month.*`)"),
    MD_005("TTRP-MD-005", "a path carries at most one measure (D12) — split into two expressions"),
    MD_006("TTRP-MD-006", "collapse the repeated attribute into a `{a, b}` member set (D15)"),
    MD_007("TTRP-MD-007", "qualify the bare member as `dim.member` — bare members need a live catalog (D18)"),
    MD_008(
        "TTRP-MD-008",
        "collapse the free dimension with an explicit agg token, or compare in a non-scalar position (R17)",
    ),
    MD_009(
        "TTRP-MD-009",
        "pin, restrict, or `dim.*` every grain dimension, and name the measure — the LHS is strict (R19)",
    ),
    MD_010("TTRP-MD-010", "declare an allocation strategy on the binding for the spread dimension (R21)"),
    MD_011("TTRP-MD-011", "check the member against the domain's published catalog (D13)"),
    MD_012(
        "TTRP-MD-012",
        "rename the input column, or qualify the path (`dim.member`), so a drilling MD chain is not shadowed " +
            "by a same-named column — it cannot be that column (R23)",
    ),
    MD_013("TTRP-MD-013", null),
    MD_014("TTRP-MD-014", "shorten the path — the resolver hit its search bound on this input (R8)"),

    // ---- `entry` stdlib surface (contracts §7). EN-P2 seated 001..005/007; EN-P5 seats 006 (call-fn);
    // 008 (Q-8 beyond reservation) is seated by its phase with its fixtures. Messages name the offending
    // column/row/call at the site (RJ_104 pattern) — the static text is the fix hint.
    EN_001(
        "TTRP-EN-001",
        "the batch does not match the target table's md shape — check the named column, its type, or the " +
            "key/values usage for the op (contracts §5)",
    ),
    EN_002(
        "TTRP-EN-002",
        "this verb does not match the target's declared change-semantics (e.g. `effective-date-change` needs " +
            "an scd2 target, `reverse-and-replace` a ledger target) (contracts §4/§9)",
    ),
    EN_003(
        "TTRP-EN-003",
        "the declared change-semantics is missing a required role column (scd2 needs valid-from/valid-to; " +
            "ledger needs reversal-link) — declare it in the md table (contracts §2)",
    ),
    EN_004(
        "TTRP-EN-004",
        "a hard delete is not emittable for a ledger/scd2 target — use `delete-rows` (soft-close for scd2, pure " +
            "reversal for ledger); §12 remains the server gate (contracts §4)",
    ),
    EN_005(
        "TTRP-EN-005",
        "an apply program is pure — no flow constructs (load/store/display) and no foreign call except " +
            "`call-fn(...)` to a `pure`-certified canon-function (contracts §6, P-3)",
    ),
    EN_006(
        "TTRP-EN-006",
        "a `call-fn` is malformed or unresolvable — check the string-literal id, the arg count/types " +
            "against the canon-function signature, and that a registered version satisfies the constraint " +
            "(contracts §6)",
    ),
    EN_007(
        "TTRP-EN-007",
        "the apply program's target table has no md model resolution — check the `<table>` in the program name " +
            "and the imported package (contracts §1)",
    ),

    // MD-015…023 — cubelet statements (S5C, contracts §6/§11). MD-018 (journal role) is wired in S5C-B.
    MD_015("TTRP-MD-015", "fix the `with` clause: every key must be known and match the existing binding (R26/R27)"),
    MD_016("TTRP-MD-016", "drop the measure/agg token — a `-=` deletes by key and ignores values (R29)"),
    MD_017("TTRP-MD-017", "`-=` is not defined on a diff-journaled cubelet — deltas can't be deleted (R29)"),
    MD_018("TTRP-MD-018", "add the technical-column role the journaling mode needs to the backing table (R30)"),
    MD_019(
        "TTRP-MD-019",
        "a write needs a bound target cubelet — a virtual or unbound cubelet has no backing table (§5)",
    ),
    MD_020(
        "TTRP-MD-020",
        "use a bare-identifier target for `:=`/`-=` — cubelet statements need a name, not a slice (R24)",
    ),
    MD_021("TTRP-MD-021", "`+=`/`-=` needs an existing target — create it first with `=`/`:=` (R24)"),
    MD_022(
        "TTRP-MD-022",
        "the script variable shadows a model cubelet of the same name — rename to avoid confusion (R25)",
    ),
    MD_023("TTRP-MD-023", "match the RHS grain/measures to the target cubelet — no silent reshape (R26/R28)"),
    MD_024(
        "TTRP-MD-024",
        "MD write statements are checked but not yet executed by the compile pipeline (S5C deferral)",
    ),

    // ---- PL-P1 ② seam-client ids (platform contracts §21). LCK = ttr.lock / fetch;
    // STA = statistics source; IMP = import-schema. ----
    LCK_001(
        "TTRP-LCK-001",
        "`ttr.lock` is missing or unparseable where a connected binding is configured — run `ttr fetch`",
    ),
    LCK_002(
        "TTRP-LCK-002",
        "`--frozen`: a pinned archive is absent from the cache — run `ttr fetch` and commit the lock diff",
    ),
    LCK_003(
        "TTRP-LCK-003",
        "`--offline`: compiling from cache; staleness is recorded in the compile record",
    ),
    LCK_004(
        "TTRP-LCK-004",
        "the lock pins a platform world whose declared `extends` target contradicts it (K) — reconcile the worlds",
    ),
    STA_001(
        "TTRP-STA-001",
        "stats entry discarded: object schema hash mismatch — the object degrades to the static cost model",
    ),
    IMP_001(
        "TTRP-IMP-001",
        "import-schema qname collision after mangling — add a rename mapping entry (never auto-suffixed)",
    ),

    // ---- PL-P2.S1 runtime params (F-4-i, PARAM) + on-failure islands (F-4-iv, FAIL). Structural
    // checks (TtrpChecks); the executor-capability gate is CAP-2xx above (needs the bound world). ----
    PARAM_001(
        "TTRP-PARAM-001",
        "a runtime param must be a scalar type: string, int, decimal, date, datetime, or bool (F-4-i)",
    ),
    PARAM_002(
        "TTRP-PARAM-002",
        "duplicate param name — each `param` is declared once per program (F-4-i)",
    ),
    PARAM_003(
        "TTRP-PARAM-003",
        "`@run-date` is the only builtin default and applies to a `date`/`datetime` param — use a literal " +
            "default for other types (F-4-i)",
    ),
    FAIL_001(
        "TTRP-FAIL-001",
        "`on failure of` names an unknown island — reference a container declared in this program (F-4-iv)",
    ),
    FAIL_002(
        "TTRP-FAIL-002",
        "an on-failure island cannot depend on itself (directly or transitively) — break the on-failure cycle (F-4-iv)",
    ),
    FAIL_003(
        "TTRP-FAIL-003",
        "`absorbs` is reserved (F-4-iv γ) and unavailable in v1 — a handled failure still ends the run non-success; " +
            "drop `absorbs`",
    ),

    // ---- Action displays (DSP): `display(<name>)` held to a declared row schema (grammar 0.14 `def schema`,
    // in scope through `import <pkg>.*`). 001/002/004/005 are errors, 003 a warning (severity set at the site). ----
    DSP_001(
        "TTRP-DSP-001",
        "the action display's schema requires this column — produce it (e.g. `calc { <col> = … }`) before the " +
            "display, or mark it `optional: true` in the schema",
    ),
    DSP_002(
        "TTRP-DSP-002",
        "the column's type is not assignable to the schema's — assignable is the same type, int → decimal/float, " +
            "or any scalar → text; convert it with `cast(x as <type>)` before the display",
    ),
    DSP_003(
        "TTRP-DSP-003",
        "the column is not in the action display's schema and is dropped from the display's output — `select` " +
            "the schema's columns to silence this",
    ),
    DSP_004(
        "TTRP-DSP-004",
        "only an action display (a declared row schema in an imported package) may take several sources — rename " +
            "the displays, or declare `def schema <name>` in a TTR-M package and import it",
    ),
    DSP_005(
        "TTRP-DSP-005",
        "an action display's column must be a scalar every engine can produce — declare it as text, int, bigint, " +
            "smallint, decimal (precision ≤ 38), float, bool, date, time, datetime or timestamp",
    ),
    ;

    companion object {
        /** Guards against id-string collisions across the catalogue (Stage 1.3 DONE bar). */
        fun assertNoDuplicateIds() {
            val dupes = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(dupes.isEmpty()) { "duplicate TtrpDiagnosticId id-strings: $dupes" }
        }
    }
}

enum class Severity { ERROR, WARNING, INFO }

data class TtrpDiagnostic(
    val id: TtrpDiagnosticId,
    val severity: Severity,
    val message: String,
    val location: SourceLocation,
    val suggestedAlternative: String? = id.suggestedAlternative,
) {
    /** One-line render: `FILE:LINE:COL <ID> <message>` (CLI/T1.3.7 format). */
    fun render(): String = "${location.file}:${location.line}:${location.column} ${id.id} $message"
}
