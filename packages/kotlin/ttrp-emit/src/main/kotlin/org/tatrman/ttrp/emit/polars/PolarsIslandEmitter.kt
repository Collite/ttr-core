// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.polars

import org.tatrman.ttrp.emit.EmitDiagnosticId
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.emit.core.DisplayTypes
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.graph.capability.RejectsSupport
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Display
import org.tatrman.ttrp.graph.model.Distinct
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
import org.tatrman.ttrp.graph.model.Limit
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.Select
import org.tatrman.ttrp.graph.model.Sort
import org.tatrman.ttrp.graph.model.Store
import org.tatrman.ttrp.graph.model.Union
import org.tatrman.ttrp.resolve.ColumnType

/** How a Load node reads its input: a staged Arrow file, or a declared-schema CSV (D-c). */
sealed interface PolarsSource {
    data class Staged(
        val edge: String,
    ) : PolarsSource

    data class Csv(
        val path: String,
        val schema: List<Pair<String, String>>,
    ) : PolarsSource
}

/**
 * One straight-line statement of a Polars island: its [varName] (from
 * [org.tatrman.ttrp.emit.core.SsaNames]), the graph [node], its input variable names in port
 * order, plus [source] (for Loads) / [sinkPath] (for Store/Display sinks).
 */
data class PolarsStep(
    val varName: String,
    val node: Node,
    val inputVars: List<String> = emptyList(),
    val source: PolarsSource? = null,
    val sinkPath: String? = null,
)

data class PolarsEmitResult(
    val text: String,
    val prelude: List<String>,
)

/**
 * One elaborated reject site's partition frames, by SSA var name (RJ-P5): the guard-input frame
 * (`in`), the guard's clean-output frame(s) (`processed` — the split's honest witness, counted
 * before any downstream row-dropping op), and the `rejects` frame. [PolarsIslandEmitter] renders a
 * `counts.json` writer from these so the eighth conform point has each engine's honest `in` count.
 */
data class PolarsPartition(
    val site: String,
    val inVar: String,
    val processedVars: List<String>,
    val rejectsVar: String,
)

/**
 * Emits a Polars island as a straight-line Python script — one statement per node, SSA names
 * carried as variable names, mirroring the canonical text (E-c γ). A generated inline prelude
 * (only the helpers the program needs) is prepended by [PreludeGenerator]; the script is
 * dependency-free beyond `polars` itself (F-c).
 */
class PolarsIslandEmitter(
    mdStaging: Map<org.tatrman.ttrp.ast.SourceLocation, org.tatrman.ttrp.graph.model.MdStageRef> = emptyMap(),
) {
    private val expr = PolarsExprRenderer(mdStaging)

    companion object {
        /**
         * Write Arrow IPC at the oldest compat level so string columns are `large_string` (utf8),
         * not Polars' default `string_view` — the latter is a newer Arrow type the conform reader
         * (Arrow Java, [org.tatrman.ttrp.conform.ArrowIo]) can't read and that mismatches the PG/
         * pyarrow side's `utf8` in the Q9-1 schema fingerprint. Applied to every Arrow sink.
         */
        const val IPC_COMPAT = ", compat_level=pl.CompatLevel.oldest()"
    }

    fun emit(
        islandName: String,
        steps: List<PolarsStep>,
        rejects: RejectsSupport = RejectsSupport.NONE,
        partitions: List<PolarsPartition> = emptyList(),
    ): PolarsEmitResult {
        val prelude = PreludeGenerator().forSteps(steps)
        val sb = StringBuilder()
        sb.append("import polars as pl\n")
        if (prelude.isNotEmpty()) {
            sb.append("# --- ttrp prelude (generated; only helpers this program needs) ---\n")
            prelude.forEach { sb.append(it).append('\n') }
        }
        sb.append("# --- island: ").append(islandName).append(" ---\n")
        // A reject-elaborated island carries a guard project (one that computes a `_ttrp_v*` flag);
        // only then do downstream passthroughs drop those flags. A fail-fast island has none, so its
        // scripts stay byte-clean of `_ttrp_` (R-P3) — no defensive `exclude` is emitted.
        val hasGuard = steps.any { (it.node as? Project)?.let(::computesValidityFlag) == true }
        steps.forEach { sb.append(statement(it, rejects, hasGuard)).append('\n') }
        if (partitions.isNotEmpty()) sb.append(countsJson(partitions))
        return PolarsEmitResult(sb.toString().trimEnd('\n') + "\n", prelude)
    }

    /**
     * A run-time `counts.json` writer for the eighth conform point (RJ-P5): per site, the guard-input
     * frame's `.height` (`in`), the clean-output frame's height (`processed` — counted at the split,
     * before any downstream row-dropping op), and the `rejects` frame's height. The written file feeds
     * `PartitionCheck` — the independent witness the exported streams can't give.
     */
    private fun countsJson(partitions: List<PolarsPartition>): String {
        val entries =
            partitions.joinToString(",\n") { p ->
                val processed =
                    if (p.processedVars.isEmpty()) {
                        "0"
                    } else {
                        p.processedVars.joinToString(
                            " + ",
                        ) { "$it.height" }
                    }
                "    {\"site\": ${quote(p.site)}, \"in\": ${p.inVar}.height, " +
                    "\"processed\": $processed, \"rejects\": ${p.rejectsVar}.height}"
            }
        return buildString {
            append("# --- ttrp partition counts (RJ-P5 eighth conform point) ---\n")
            append("import json\n")
            append("_ttrp_counts = {\"sites\": [\n").append(entries).append("\n]}\n")
            append("with open(\"counts.json\", \"w\") as _ttrp_f:\n")
            append("    json.dump(_ttrp_counts, _ttrp_f, indent=2)\n")
        }
    }

    private fun statement(
        step: PolarsStep,
        rejects: RejectsSupport,
        hasGuard: Boolean,
    ): String {
        val v = step.varName
        val ins = step.inputVars
        return when (val n = step.node) {
            is Load -> "$v = ${loadExpr(step)}"
            is Filter -> "$v = ${ins[0]}.filter(${expr.render(requirePred(n.predicate, n))})"
            is Project -> "$v = ${project(n, ins[0], rejects, hasGuard)}"
            is Aggregate -> "$v = ${aggregate(n, ins[0])}"
            is Sort -> "$v = ${sort(n, ins[0])}"
            is Union -> "$v = pl.concat([${ins.joinToString(", ")}])"
            is Join -> "$v = ${join(n, ins)}"
            is Limit -> "$v = ${ins[0]}.head(${n.count ?: error("Limit needs a count")})"
            is Store -> "${ins[0]}.write_ipc(${quote(step.sinkPath ?: "staging/$v.arrow")}$IPC_COMPAT)"
            is Display -> displayStmt(n, ins[0], step.sinkPath)
            is Select, is Calc, is Distinct ->
                throw TtrpEmitException(
                    EmitDiagnosticId.SUGAR_REACHED_EMIT,
                    detail = "sugar node ${n::class.simpleName} '${n.label}' reached Polars emit",
                    location = n.location,
                )
            else ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "node ${n::class.simpleName} '${n.label}' has no Polars emit",
                    location = n.location,
                )
        }
    }

    private fun loadExpr(step: PolarsStep): String =
        when (val s = step.source) {
            is PolarsSource.Staged -> "pl.read_ipc(${quote("staging/${s.edge}.arrow")})"
            is PolarsSource.Csv -> {
                val schema = s.schema.joinToString(", ") { "${quote(it.first)}: ${csvDtype(it.second)}" }
                "pl.read_csv(${quote(s.path)}, schema={$schema})"
            }
            null ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "Load '${step.node.label}' has no source binding",
                    location = step.node.location,
                )
        }

    /**
     * A [Project] statement. Three shapes reach here:
     *  - a bare **select** projection (`passthrough=false`, no aliases) → `.select([cols])`, Polars
     *    keeps each column's input name;
     *  - a named-computed **calc** (`passthrough=true`) → `.with_columns([expr.alias(name)])` add-
     *    semantics, keeping the input columns (the SQL `SELECT src.*, expr AS name` mirror);
     *  - a reject **guard** — a computed column carrying an `internal.*` validity call renders via
     *    [RejectGuardPolars] (the canonical mask); its `_ttrp_v*` flag stays (it drives the branch),
     *    while every other passthrough drops `_ttrp_v*` (never part of an OUT / rejects schema).
     */
    private fun project(
        n: Project,
        input: String,
        rejects: RejectsSupport,
        hasGuard: Boolean,
    ): String {
        val computed =
            n.columns.mapIndexed { i, c ->
                val body =
                    when {
                        RejectGuardPolars.isInternal(c) ->
                            RejectGuardPolars.render(c as FunctionCall, expr::render, rejects) ?: expr.render(c)
                        // A reject-capable cast on the clean side of a guarded island degrades to a
                        // non-strict cast (no whole-frame crash on a guard-narrower residual, R-P3).
                        hasGuard && c is Cast ->
                            RejectGuardPolars.renderCleanCast(c, expr::render) ?: expr.render(c)
                        else -> expr.render(c)
                    }
                n.aliases.getOrNull(i)?.let { "$body.alias(${quote(it)})" } ?: body
            }
        if (!n.passthrough) {
            return "$input.select([${computed.joinToString(", ")}])"
        }
        // calc add-semantics: keep the input columns, add/override the computed ones. A guard project
        // computes the `_ttrp_v*` flags itself (keep them — they drive the two branch filters); every
        // other passthrough in a guarded island drops them (contracts §1 — internal, never an output/
        // rejects column). A guard-free (fail-fast) island emits no `exclude` ⇒ stays `_ttrp_`-clean.
        val withColumns = "$input.with_columns([${computed.joinToString(", ")}])"
        val dropsFlags = hasGuard && !computesValidityFlag(n)
        return if (dropsFlags) "$withColumns.select(pl.all().exclude(\"^_ttrp_v[0-9]+\$\"))" else withColumns
    }

    /** True if [n] computes at least one `_ttrp_v*` validity flag — i.e. it is a reject guard. */
    private fun computesValidityFlag(n: Project): Boolean =
        n.columns.indices.any { RejectGuardPolars.isValidityFlag(n.aliases.getOrNull(it) ?: "") }

    private fun aggregate(
        n: Aggregate,
        input: String,
    ): String {
        val keys = n.groupBy.joinToString(", ") { quote(it) }
        val aggs =
            n.aggregations.joinToString(", ") { a ->
                val call =
                    a.value as? org.tatrman.ttrp.expr.AggregateCall
                        ?: throw TtrpEmitException(
                            EmitDiagnosticId.UNSUPPORTED_NODE,
                            detail = "aggregation '${a.name}' is not an AggregateCall",
                            location = n.location,
                        )
                "${expr.aggregate(call)}.alias(${quote(a.name)})"
            }
        return "$input.group_by([$keys]).agg([$aggs])"
    }

    private fun sort(
        n: Sort,
        input: String,
    ): String {
        val parsed = n.keys.map { parseSortKey(it) }
        val by = parsed.joinToString(", ") { quote(it.first) }
        val desc = parsed.joinToString(", ") { if (it.second) "True" else "False" }
        return "$input.sort(by=[$by], descending=[$desc], nulls_last=True)"
    }

    private fun parseSortKey(key: String): Pair<String, Boolean> {
        val t = key.trim()
        return when {
            t.endsWith(" desc", ignoreCase = true) -> t.dropLast(5).trim() to true
            t.endsWith(" asc", ignoreCase = true) -> t.dropLast(4).trim() to false
            else -> t to false
        }
    }

    private fun join(
        n: Join,
        ins: List<String>,
    ): String {
        val how =
            when (n.type) {
                JoinType.INNER -> "inner"
                JoinType.LEFT -> "left"
                JoinType.RIGHT -> "right"
                JoinType.FULL -> "full"
                JoinType.SEMI -> "semi"
                JoinType.ANTI -> "anti"
                JoinType.CROSS -> "cross"
            }
        if (n.type == JoinType.CROSS || n.on == null) {
            return "${ins[0]}.join(${ins[1]}, how=\"$how\")"
        }
        val (left, right) = equiKeys(n, n.on!!)
        val leftOn = left.joinToString(", ") { quote(it) }
        val rightOn = right.joinToString(", ") { quote(it) }
        return "${ins[0]}.join(${ins[1]}, left_on=[$leftOn], right_on=[$rightOn], how=\"$how\")"
    }

    /** Extract equi-join key columns from a conjunction of `left.x = right.y` conditions. */
    private fun equiKeys(
        n: Join,
        on: Expression,
    ): Pair<List<String>, List<String>> {
        val left = mutableListOf<String>()
        val right = mutableListOf<String>()

        fun walk(e: Expression) {
            when {
                e is FunctionCall && e.function.name == "and" -> e.args.forEach { walk(it) }
                e is FunctionCall && e.function.name == "eq" -> {
                    val a = e.args[0] as? ColumnRef
                    val b = e.args[1] as? ColumnRef
                    if (a == null || b == null) unsupportedJoin(n)
                    // Order by port: left.* → left_on, right.* → right_on.
                    if (a!!.port == "right" || b!!.port == "left") {
                        left += b.column
                        right += a.column
                    } else {
                        left += a.column
                        right += b.column
                    }
                }
                else -> unsupportedJoin(n)
            }
        }
        walk(on)
        return left to right
    }

    private fun unsupportedJoin(n: Join): Nothing =
        throw TtrpEmitException(
            EmitDiagnosticId.UNSUPPORTED_NODE,
            detail = "Join '${n.label}' has a non-equi ON condition; only equi-joins emit to Polars in v1",
            location = n.location,
        )

    private fun displayStmt(
        n: Display,
        input: String,
        sinkPath: String?,
    ): String {
        val path = sinkPath ?: "out/${n.name}.arrow"
        val write = ".write_ipc(${quote(path)}$IPC_COMPAT)\nprint(f\"display ${n.name}: $path\")"
        val schema = n.schema ?: return input + write
        // An action display (grammar 0.14) writes exactly its row schema's columns, in schema order, each cast to
        // the schema column's type (DisplayTypes — so every source of one display writes the same Arrow schema): a
        // column the frame carries is cast, an absent `optional` one is a null of that type. A Polars frame has no
        // static columns at emit time, so a REQUIRED column the frame lacks (a row type the checker could not see
        // — TTRP-DSP-001 is reported at check time whenever it can) stops the island before it writes the file;
        // it is never NULL-filled.
        val required = schema.columns.filterNot { it.optional }.map { quote(it.name) }
        val guard =
            if (required.isEmpty()) {
                ""
            } else {
                val message =
                    quote(
                        "TTRP-DSP-001: display ${n.name} (schema ${schema.qualifiedName}) is missing required " +
                            "column(s): ",
                    )
                "_ttrp_missing = [_c for _c in [${required.joinToString(", ")}] if _c not in $input.columns]\n" +
                    "if _ttrp_missing:\n" +
                    "    raise SystemExit($message + \", \".join(_ttrp_missing))\n"
            }
        val columns =
            schema.columns.joinToString(", ") { c ->
                val q = quote(c.name)
                val dtype = DisplayTypes.polars(DisplayTypes.of(c))
                if (c.optional) {
                    "(pl.col($q).cast($dtype) if $q in $input.columns else pl.lit(None, dtype=$dtype).alias($q))"
                } else {
                    "pl.col($q).cast($dtype)"
                }
            }
        return "$guard$input.select([$columns])$write"
    }

    private fun requirePred(
        pred: Expression?,
        n: Node,
    ): Expression =
        pred ?: throw TtrpEmitException(
            EmitDiagnosticId.UNSUPPORTED_NODE,
            detail = "Filter '${n.label}' has no predicate",
            location = n.location,
        )

    /**
     * The `read_csv` dtype of a world-schema field. A decimal carries its declared precision/scale
     * (`{ type: decimal, length: 19, precision: 2 }` → `pl.Decimal(19, 2)`), else the toolchain default
     * ([ColumnType.DEFAULT_DECIMAL_PRECISION], [ColumnType.DEFAULT_DECIMAL_SCALE]) — Polars ≥ 1.42 rejects a
     * bare `pl.Decimal` ("Decimal without precision/scale set is not a valid Polars datatype").
     */
    private fun csvDtype(spelling: String): String {
        ColumnType.parse(spelling)?.takeIf { it.kind == ColumnType.Kind.DECIMAL }?.let {
            return "pl.Decimal(${it.precision}, ${it.scale})"
        }
        return when (spelling.substringBefore('(').trim().lowercase()) {
            "int", "integer", "bigint", "long" -> "pl.Int64"
            "float", "double", "real" -> "pl.Float64"
            "bool", "boolean" -> "pl.Boolean"
            "date" -> "pl.Date"
            "timestamp", "datetime" -> "pl.Datetime(\"us\", \"UTC\")"
            else -> "pl.String"
        }
    }

    private fun quote(s: String): String = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
