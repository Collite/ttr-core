// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.parser

import org.tatrman.ttrp.ast.ActionsBlock
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.Chain
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.ControlBlock
import org.tatrman.ttrp.ast.ControlDep
import org.tatrman.ttrp.ast.ControlKind
import org.tatrman.ttrp.ast.DecisionDecl
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.ParamDecl
import org.tatrman.ttrp.ast.ParamDefault
import org.tatrman.ttrp.ast.ProgramHeader
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.RoleContainer
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.ConceptAggregate
import org.tatrman.ttrp.expr.ConceptForm
import org.tatrman.ttrp.expr.ExistsExpr
import org.tatrman.ttrp.expr.InCall
import org.tatrman.ttrp.expr.TemporalPredicate
import org.tatrman.ttrp.expr.ThisRef

/**
 * The Stage 1.1 STRUCTURAL named-reject pass (contracts §8): PRS/CTL/FRG. Runs in
 * every parse. `TTRP-EQ-001` now fires from [TtrpWalker] during expression folding
 * (S9). The Stage 1.2 EXPRESSION-semantic checks (catalogue FN/AGG, typing TYP,
 * scope EXP) live behind [org.tatrman.ttrp.TtrpFrontend].check, gated on the schema
 * seam — they are not part of the bare parse.
 */
internal object TtrpChecks {
    // S10 reserved port names. NOTE (review-001 1.1-B): only the identifier-lexable
    // members (`in`/`out`/`err`/`rejects`) can reach this walker check as an assignment
    // TARGET → PRS-005. `true`/`false`/`else` are keyword/literal tokens and are NOT in
    // the grammar's `identifier` rule, so `true = …` fails to parse as an assignment and
    // yields a generic syntax error (PRS-001) at parse time instead. Both paths reject
    // the binding; the difference is a conscious grammar-level split (pinned by a spec),
    // not a gap — a distinct `rejects` keyword etc. is a Stage 2.1 port-typing decision.
    private val RESERVED_PORTS = setOf("in", "out", "err", "rejects", "true", "false", "else")
    private val MULTI_IN_OPS = setOf("join", "intersect", "except")

    /** PL-P2.S1 (F-4-i): the runtime-param scalar types + the one builtin (run-date). */
    private val PARAM_SCALAR_TYPES = setOf("string", "int", "decimal", "date", "datetime", "bool")
    private val DATE_PARAM_TYPES = setOf("date", "datetime")
    private const val RUN_DATE_BUILTIN = "run-date"

    /** The recipe's time sugar ops (AU-core-P1; lowered at P2). */
    private val TIME_SUGAR_OPS = setOf("latest", "within")

    /** Reserved synthesized-rejects column prefix (contracts §1 / RS-5). */
    private const val TTRP_PREFIX = "_ttrp_"

    /**
     * TTRP 0.5 (CN-D29): a cn definition form in a TTR-P document is refused wherever it sits — a chain op, a recipe
     * `switch` route, a decision statement, a wiring `when:` (review-119 M-1: the typechecker sees only the chain ops).
     * One `TTRP-CN-001` per outermost form; a form inside another (`this` in an `exists`) is the outer one's.
     */
    fun refuseConceptForms(forms: List<ConceptForm>): List<TtrpDiagnostic> {
        val spans = forms.distinctBy { it.location.offsetStart to it.location.offsetEnd }
        return spans
            .filter { f ->
                spans.none { g ->
                    g !== f &&
                        g.location.offsetStart <= f.location.offsetStart &&
                        f.location.offsetEnd <= g.location.offsetEnd
                }
            }.sortedBy { it.location.offsetStart }
            .map { diag(TtrpDiagnosticId.CN_001, "${conceptFormName(it)} outside a cn `definition:`", it.location) }
    }

    /** How a refused form is named in its `TTRP-CN-001`. */
    fun conceptFormName(e: ConceptForm): String =
        when (e) {
            is ExistsExpr -> if (e.negated) "`not exists`" else "`exists`"
            is ConceptAggregate -> "`${e.function}(<concept> involving …)`"
            is ThisRef -> "`this`"
            is InCall -> "`in <call>`"
            is TemporalPredicate -> "`${e.op.spelling}`"
        }

    fun run(doc: TtrpDocument): List<TtrpDiagnostic> {
        val out = mutableListOf<TtrpDiagnostic>()
        checkStatements(doc.statements, enclosingPorts = emptySet(), out, topLevel = true)
        checkParams(doc.statements.filterIsInstance<ParamDecl>(), out)
        checkOnFailure(doc.statements.filterIsInstance<ContainerDecl>(), out)
        checkRecipeDocument(doc, out)
        return out
    }

    /**
     * AU-core-P1 (REC-000, document level): one recipe per file, and no `program` header beside it. Checked here,
     * at parse time, because a second recipe or a header stops the compile before the recipe checker runs.
     */
    private fun checkRecipeDocument(
        doc: TtrpDocument,
        out: MutableList<TtrpDiagnostic>,
    ) {
        val recipes = doc.statements.filterIsInstance<RecipeDecl>()
        if (recipes.isEmpty()) return
        recipes.drop(1).forEach {
            out += diag(TtrpDiagnosticId.REC_000, "a second `recipe` block — a document holds one recipe", it.location)
        }
        doc.statements.filterIsInstance<ProgramHeader>().forEach {
            out +=
                diag(
                    TtrpDiagnosticId.REC_000,
                    "a `program` header beside a recipe — a recipe is the document",
                    it.location,
                )
        }
    }

    /** PL-P2.S1 (F-4-i): scalar-type, duplicate-name, and builtin-default validity. */
    private fun checkParams(
        params: List<ParamDecl>,
        out: MutableList<TtrpDiagnostic>,
    ) {
        val seen = mutableSetOf<String>()
        for (p in params) {
            val base = p.type.substringBefore('(').trim()
            if (base !in PARAM_SCALAR_TYPES) {
                out +=
                    diag(TtrpDiagnosticId.PARAM_001, "param `${p.name}` has unknown type `${p.type}`", p.typeLocation)
            }
            if (!seen.add(p.name)) {
                out += diag(TtrpDiagnosticId.PARAM_002, "param `${p.name}` is declared more than once", p.nameLocation)
            }
            val default = p.default
            if (default is ParamDefault.Builtin) {
                if (default.name != RUN_DATE_BUILTIN) {
                    out += diag(TtrpDiagnosticId.PARAM_003, "unknown builtin `@${default.name}`", default.location)
                } else if (base !in DATE_PARAM_TYPES) {
                    out +=
                        diag(
                            TtrpDiagnosticId.PARAM_003,
                            "`@run-date` defaults a `date`/`datetime` param, not `${p.type}`",
                            default.location,
                        )
                }
            }
        }
    }

    /**
     * PL-P2.S1 (F-4-iv): `on failure of <island>` must name a declared container; `absorbs` is
     * reserved; the on-failure relation must be acyclic (a handler cannot, transitively, run on
     * its own failure). Only top-level containers participate (cross-container wiring is program-level).
     */
    private fun checkOnFailure(
        containers: List<ContainerDecl>,
        out: MutableList<TtrpDiagnostic>,
    ) {
        val names = containers.map { it.name }.toSet()
        val onFailureOf = containers.filter { it.onFailureOf != null }.associate { it.name to it.onFailureOf!! }
        for (c in containers) {
            val src = c.onFailureOf ?: continue
            if (c.onFailureAbsorbs) {
                out +=
                    diag(
                        TtrpDiagnosticId.FAIL_003,
                        "`absorbs` on `${c.name}` is reserved",
                        c.onFailureOfLocation ?: c.location,
                    )
            }
            if (src !in names) {
                out +=
                    diag(
                        TtrpDiagnosticId.FAIL_001,
                        "`on failure of $src` on `${c.name}` names an unknown island",
                        c.onFailureOfLocation ?: c.location,
                    )
                continue
            }
            // Cycle detection over the single-edge on-failure relation (functional graph).
            var cur: String? = c.name
            val visited = mutableSetOf<String>()
            while (cur != null && visited.add(cur)) {
                cur = onFailureOf[cur]
                if (cur == c.name) {
                    out +=
                        diag(
                            TtrpDiagnosticId.FAIL_002,
                            "`${c.name}` is transitively an on-failure handler of itself",
                            c.onFailureOfLocation ?: c.location,
                        )
                    break
                }
            }
        }
    }

    private fun checkStatements(
        statements: List<Statement>,
        enclosingPorts: Set<String>,
        out: MutableList<TtrpDiagnostic>,
        topLevel: Boolean = false,
        inRecipe: Boolean = false,
    ) {
        for (stmt in statements) {
            when (stmt) {
                // AU-core-P0: a recipe is a document-level block; its role containers and decision
                // carry ordinary statements, checked like a container's (their ports in scope).
                is RecipeDecl ->
                    if (!topLevel) {
                        out += diag(TtrpDiagnosticId.PRS_007, "a `recipe { }` block is top-level only", stmt.location)
                    } else {
                        checkRecipe(stmt, out)
                    }

                is ProgramHeader ->
                    out += diag(TtrpDiagnosticId.PRS_002, "`program` header is not part of TTR-P", stmt.location)

                is ControlDep -> checkControlDep(stmt, out)

                is ControlBlock -> stmt.deps.forEach { checkControlDep(it, out) }

                is Assignment -> {
                    if (stmt.target in RESERVED_PORTS && stmt.target !in enclosingPorts) {
                        out +=
                            diag(
                                TtrpDiagnosticId.PRS_005,
                                "`${stmt.target}` is a reserved port name and cannot be bound as a variable",
                                stmt.targetLocation,
                            )
                    }
                    checkChain(stmt.chain, out, inRecipe)
                }

                is ChainStmt -> checkChain(stmt.chain, out, inRecipe)

                is ContainerDecl -> {
                    when (val body = stmt.body) {
                        is FragmentBody -> checkFragmentTag(body, out)
                        is FlowBody ->
                            checkStatements(body.statements, stmt.ports.map { it.name }.toSet(), out)
                    }
                }

                else -> Unit
            }
        }
    }

    private fun checkRecipe(
        recipe: RecipeDecl,
        out: MutableList<TtrpDiagnostic>,
    ) {
        val roleNames = recipe.roleContainers.map { it.name }.toSet()
        for (member in recipe.members) {
            when (member) {
                is RoleContainer -> {
                    // The container precedent (F-4-iv): `absorbs` is reserved; `on failure of` names an island of THIS recipe.
                    if (member.onFailureAbsorbs) {
                        out +=
                            diag(
                                TtrpDiagnosticId.FAIL_003,
                                "`absorbs` on `${member.name}` is reserved",
                                member.location,
                            )
                    }
                    member.onFailureOf?.takeIf { it !in roleNames }?.let {
                        out +=
                            diag(
                                TtrpDiagnosticId.FAIL_001,
                                "`on failure of $it` on `${member.name}` names no container of this recipe",
                                member.location,
                            )
                    }
                    val ports = member.ports.map { it.name }.toSet()
                    when (val body = member.body) {
                        // A `foreign` body is the foreign language (python), not a TTR fragment dialect.
                        is FragmentBody ->
                            if (member.form is org.tatrman.ttrp.ast.IslandForm.Target) {
                                checkFragmentTag(body, out)
                                if (body.tag == "sql") checkSqlReadOnly(member.name, body, out)
                            }
                        is FlowBody -> {
                            checkStatements(body.statements, ports, out, inRecipe = true)
                            checkNoStore(member.name, body.statements, out)
                        }
                        null -> Unit
                    }
                }
                is DecisionDecl -> {
                    checkStatements(member.statements, member.ports.map { it.name }.toSet(), out, inRecipe = true)
                    checkNoStore(member.name, member.statements, out)
                    // REC-070: the decision is a canonical body ending in its switch — no island of its own.
                    member.statements.filter { it is ContainerDecl || it is RecipeDecl }.forEach {
                        out +=
                            diag(
                                TtrpDiagnosticId.REC_070,
                                "decision `${member.name}` declares an island — a decision is canonical statements " +
                                    "and its switch",
                                it.location,
                            )
                    }
                }
                is ActionsBlock -> Unit
            }
        }
    }

    /**
     * REC-060 (D-65): a recipe's SQL island only reads — actions write. TTR-SQL already refuses DML everywhere
     * (TTRP-SQL-001); in a recipe the island also says why and what to do instead.
     */
    private fun checkSqlReadOnly(
        role: String,
        body: FragmentBody,
        out: MutableList<TtrpDiagnostic>,
    ) {
        val lexer =
            org.tatrman.ttrp.parser.generated.TTRSqlLexer(
                org.antlr.v4.runtime.CharStreams
                    .fromString(body.sourceText),
            )
        lexer.removeErrorListeners()
        val dml =
            setOf(
                org.tatrman.ttrp.parser.generated.TTRSqlLexer.INSERT,
                org.tatrman.ttrp.parser.generated.TTRSqlLexer.UPDATE,
                org.tatrman.ttrp.parser.generated.TTRSqlLexer.DELETE,
                org.tatrman.ttrp.parser.generated.TTRSqlLexer.MERGE,
            )
        val head = lexer.allTokens.firstOrNull { it.type in dml } ?: return
        out +=
            diag(
                TtrpDiagnosticId.REC_060,
                "island `$role` writes (`${head.text.uppercase()}`) — a recipe's islands only read",
                body.location,
            )
    }

    /** REC-060 for a canonical body: a `store` in a role container or the decision writes. */
    private fun checkNoStore(
        role: String,
        statements: List<Statement>,
        out: MutableList<TtrpDiagnostic>,
    ) {
        for (st in statements) {
            val chain =
                when (st) {
                    is Assignment -> st.chain
                    is ChainStmt -> st.chain
                    else -> continue
                }
            chain.elements.filterIsInstance<OpCall>().filter { it.name == "store" }.forEach {
                out += diag(TtrpDiagnosticId.REC_060, "`$role` stores rows — a recipe's islands only read", it.location)
            }
        }
    }

    private fun checkFragmentTag(
        body: FragmentBody,
        out: MutableList<TtrpDiagnostic>,
    ) {
        // An empty tag is a file-backed body that failed to load (FRG-004/005, already reported).
        if (body.tag.isNotEmpty() &&
            body.tag !in setOf("sql", "pandas") &&
            org.tatrman.ttrp.dialect.b.TtrbSkin
                .forTag(body.tag) == null
        ) {
            out += diag(TtrpDiagnosticId.FRG_001, "unknown fragment dialect `${body.tag}`", body.location)
        }
    }

    private fun checkControlDep(
        dep: ControlDep,
        out: MutableList<TtrpDiagnostic>,
    ) {
        if (dep.kind == ControlKind.FF) {
            out +=
                diag(TtrpDiagnosticId.CTL_001, "`finishes with` (FF) is reserved and unavailable in v1", dep.location)
        }
    }

    private fun checkChain(
        chain: Chain,
        out: MutableList<TtrpDiagnostic>,
        inRecipe: Boolean = false,
    ) {
        for (elem in chain.elements) {
            if (elem is OpCall) checkOpCall(elem, out, inRecipe)
        }
    }

    /** `name = expression` written as an argument — the form `calc(…)` takes (AU-core-P1, the LD forms). */
    private fun isAssignment(arg: org.tatrman.ttrp.ast.Arg): Boolean {
        if (arg.name != null) return false
        val eq =
            (arg.value as? org.tatrman.ttrp.ast.ExprArg)?.expr as? org.tatrman.ttrp.expr.FunctionCall ?: return false
        return eq.function == org.tatrman.ttrp.expr.CatalogId.EQ &&
            (eq.args.firstOrNull() as? org.tatrman.ttrp.expr.ColumnRef)?.port == null &&
            eq.args.firstOrNull() is org.tatrman.ttrp.expr.ColumnRef
    }

    private fun checkOpCall(
        op: OpCall,
        out: MutableList<TtrpDiagnostic>,
        inRecipe: Boolean = false,
    ) {
        // The LD argument forms the resolver reads (AU-core-P1, review-115 m9): every `calc` argument is
        // `name = expression`, a join names its kind once — anything else would be dropped without a word.
        if (op.name == "calc") {
            op.args.firstOrNull { !isAssignment(it) }?.let {
                out +=
                    diag(
                        TtrpDiagnosticId.PRS_009,
                        "`calc` reads `name = expression` arguments; this one is not",
                        it.location,
                    )
            }
        }
        if (op.name == "join" && op.args.any { it.name == "kind" } && op.args.any { it.name == "type" }) {
            out += diag(TtrpDiagnosticId.PRS_009, "`kind:` and `type:` on one join", op.location)
        }
        // The time sugar ops compile with the recipe compile (AU-core-P2): outside a recipe they would reach an
        // emitter that cannot lower them (review-115 m8).
        if (!inRecipe && op.name in TIME_SUGAR_OPS) {
            out +=
                diag(
                    TtrpDiagnosticId.REC_099,
                    "`${op.name}` is a recipe op — it compiles with the recipe compile (AU-core-P2)",
                    op.location,
                )
        }
        // RJ-103 (RS-5): the `_ttrp_` prefix is reserved for synthesized rejects columns; a user
        // column declared (schema literal) or computed (calc/aggregate assignment) with it errors.
        op.config?.entries?.filterIsInstance<org.tatrman.ttrp.ast.AssignEntry>()?.forEach { e ->
            if (e.name.startsWith(TTRP_PREFIX)) {
                out += diag(TtrpDiagnosticId.RJ_103, "`${e.name}` uses the reserved `_ttrp_` prefix", e.location)
            }
        }
        op.args.forEach { a ->
            (a.value as? org.tatrman.ttrp.ast.SchemaLiteralArg)?.columns?.forEach { col ->
                if (col.name.startsWith(TTRP_PREFIX)) {
                    out +=
                        diag(TtrpDiagnosticId.RJ_103, "`${col.name}` uses the reserved `_ttrp_` prefix", col.location)
                }
            }
        }
        // C3-c: multi-input ops take named inputs only (bare positional beyond index 0 → PRS-003).
        if (op.name in MULTI_IN_OPS) {
            op.args.forEachIndexed { i, arg ->
                if (i >= 1 && arg.name == null) {
                    out +=
                        diag(
                            TtrpDiagnosticId.PRS_003,
                            "op `${op.name}` requires named inputs for multiple sources",
                            arg.location,
                        )
                }
            }
        }
        // S11: union takes the list form only — any named input is a reject (PRS-004,
        // one per op-call regardless of how many inputs were named).
        if (op.name == "union") {
            op.args.firstOrNull { it.name != null }?.let {
                out += diag(TtrpDiagnosticId.PRS_004, "`union` uses the list form, not named inputs", it.location)
            }
        }
    }

    private fun diag(
        id: TtrpDiagnosticId,
        message: String,
        location: org.tatrman.ttrp.ast.SourceLocation,
    ) = TtrpDiagnostic(id = id, severity = Severity.ERROR, message = message, location = location)
}
