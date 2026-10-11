// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import org.tatrman.plan.v1.ColumnRef as PbColumnRef
import org.tatrman.plan.v1.Expression as PbExpression
import org.tatrman.plan.v1.FunctionCall as PbFunctionCall
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelTable
import org.tatrman.ttrp.emit.EmitDiagnosticId
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.emit.core.DisplayTypes
import org.tatrman.ttrp.graph.capability.BoundWorld
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.resolve.DisplaySchema

/**
 * One `sql-text` island output (AG-P0): a single self-contained SELECT in the engine's dialect.
 * [params] are the runtime params it binds, as `:name` placeholders in [sql] (order of first use).
 */
data class SqlTextOutput(
    val port: String,
    val sql: String,
    val columns: List<EmitColumn>,
    val params: List<String>,
)

/**
 * Plans a `sql-text` island output (AG-P0, contracts AG C-12) as **one statement**: the output's
 * whole dependency cone — the container's own nodes, the model tables it loads, and every
 * same-engine container it reads, transitively — is built into ONE `plan.v1` tree and unparsed once
 * by the translator. No CTE stitching and no session temp tables: a host can hand each output to a
 * plan-based SQL door (parse → validate → execute) on its own, the way it runs any other query.
 *
 *  - a model-object Load scans its physical table (`[dbo].[T]`) and renames physical → logical
 *    columns, so the program's names hold downstream; a Load bound to an **inline** source (a db
 *    `query`/`view`, e.g. a YAML `source: {table, where}` entity's `__filter` query) scans a
 *    placeholder table that is substituted by its SQL text as a derived table after unparse;
 *  - a runtime param lowers to a `?` the translator reports by name, rendered back as `:name`.
 *
 * Shared sub-cones are re-inlined per consumer (a nested statement has no names to share them by);
 * that is the price of self-containment, and the right one for a door that validates statements.
 */
class SqlTextPlanner(
    private val graph: TtrpGraph,
    private val world: BoundWorld,
    private val dialect: SqlDialect,
    /** The program's runtime params (name → positional index + surface tag). */
    private val params: Map<String, PlanParam>,
) {
    private data class Built(
        val plan: PlanNode,
        val columns: List<EmitColumn>,
    )

    private val tables = LinkedHashMap<Pair<String, String>, LinkedHashMap<String, String>>()
    private val inlineSql = LinkedHashMap<String, String>()

    /**
     * The statement for [container]'s OUT [port]. With a [projection] (an action display's row schema, grammar
     * 0.14) the statement's columns are exactly the schema's, in schema order, each CAST to the schema column's type
     * ([DisplayTypes.sqlText]) — a column the port carries as `CAST("c" AS …)`, an absent `optional` one as
     * `CAST(NULL AS …)` — so every source of one display returns the same row type; an absent required one is an
     * emit error (the frontend reports it first as TTRP-DSP-001). Columns the schema does not name are dropped
     * (TTRP-DSP-003). The output's columns report the schema's own type spelling.
     */
    fun emit(
        container: Container,
        port: String,
        projection: DisplaySchema? = null,
    ): SqlTextOutput {
        tables.clear()
        inlineSql.clear()
        val portPlan = output(container, port, HashMap())
        val built = projection?.let { project(portPlan, it, container, port) } ?: portPlan
        val model =
            tables.map { (key, cols) ->
                ModelTable(
                    qname =
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace(key.first)
                            .setName(key.second)
                            .build(),
                    columns = cols.map { (n, t) -> ModelColumn(n, TypeMapping.surfaceType(t)) },
                )
            }
        val facade = TranslatorFacade(IslandModelHandle(model), dialect)
        val (raw, order) = facade.unparseNamed(built.plan, params.mapValues { it.value.typeTag }, container.label)
        val named = SqlTextRender.namePlaceholders(raw, order)
        val inlined = SqlTextRender.inlineSources(named, INLINE_NS, inlineSql)
        // The door dialect (F-AG-17): the host's SQL door parses the statement back with default quoting
        // and resolves DB-tier tables unqualified — so tables lose the schema, identifiers use "…".
        val sql = SqlTextRender.doubleQuoted(SqlTextRender.unqualify(inlined, TABLE_NS))
        return SqlTextOutput(port, sql.trim(), built.columns, order.distinct())
    }

    /** [built] projected to an action display's [schema] columns, in schema order, each cast (see [emit]). */
    private fun project(
        built: Built,
        schema: DisplaySchema,
        container: Container,
        port: String,
    ): Built {
        val byName = built.columns.associateBy { it.name }
        val project = ProjectNode.newBuilder().setInput(built.plan)
        val columns =
            schema.columns.map { c ->
                val operand =
                    when {
                        c.name in byName ->
                            PbExpression.newBuilder().setColumnRef(PbColumnRef.newBuilder().setName(c.name)).build()
                        c.optional -> PbExpression.newBuilder().setLiteral(Literal.newBuilder().setIsNull(true)).build()
                        else ->
                            throw TtrpEmitException(
                                EmitDiagnosticId.UNSUPPORTED_NODE,
                                detail =
                                    "island '${container.label}' OUT port '$port' feeds action display schema " +
                                        "`${schema.qualifiedName}` but has no column `${c.name}` (TTRP-DSP-001)",
                                island = container.label,
                            )
                    }
                val cast =
                    PbExpression
                        .newBuilder()
                        .setFunction(PbFunctionCall.newBuilder().setOperation("cast").addOperands(operand))
                        .setResultType(DisplayTypes.sqlText(DisplayTypes.of(c)))
                        .build()
                project.addExpressions(NamedExpression.newBuilder().setExpression(cast).setAlias(c.name))
                EmitColumn(c.name, c.spelling)
            }
        return Built(PlanNode.newBuilder().setProject(project).build(), columns)
    }

    /** The plan of [container]'s OUT [port] — its node chain over model scans and upstream outputs. */
    private fun output(
        container: Container,
        port: String,
        memo: HashMap<Pair<String, String>, Built>,
    ): Built =
        memo.getOrPut(container.id to port) {
            container.verbatimFragment?.let { frag -> return@getOrPut fragmentSource(container, port, frag.sourceText) }
            val chain =
                SqlGraphEmitter(graph, world, sqlText = true).plansByOutput(container)[port]
                    ?: throw TtrpEmitException(
                        EmitDiagnosticId.UNSUPPORTED_NODE,
                        detail = "island '${container.label}' has no emittable OUT port '$port'",
                        island = container.label,
                    )
            val built = HashMap<String, Built>()
            for (en in chain) {
                val ins = en.inputs.map { input(it, built, memo, container) }
                val body =
                    PlanNodeBuilder(params = params, existenceAsJoin = true).body(
                        en.node,
                        ins.map { it.plan },
                        ins.map { i ->
                            i.columns.map { it.name }
                        },
                    )
                built[en.node.id] = Built(body, en.outputColumns)
            }
            built.getValue(chain.last().node.id)
        }

    private fun input(
        input: EmitInput,
        built: Map<String, Built>,
        memo: HashMap<Pair<String, String>, Built>,
        container: Container,
    ): Built =
        when (input) {
            is EmitInput.Cte -> built.getValue(input.producerNodeId)
            is EmitInput.Model -> modelScan(input)
            is EmitInput.Upstream -> output(graph.containers.getValue(input.containerId), input.port, memo)
            is EmitInput.BaseTable ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail =
                        "island '${container.label}' reads '${input.name}', which is not a model object — " +
                            "a sql-text island reads only model tables/entities and other islands' outputs",
                    island = container.label,
                )
        }

    /** A model Load: scan the physical relation, then rename physical → logical columns. */
    private fun modelScan(m: EmitInput.Model): Built {
        val src = m.source
        val inline = src.sql
        val ns = if (inline != null) INLINE_NS else TABLE_NS
        if (inline != null) inlineSql[src.name] = inline
        val physical = src.columns.map { it.physical to it.type }.distinctBy { it.first }
        val registered = tables.getOrPut(ns to src.name) { LinkedHashMap() }
        physical.forEach { (n, t) -> registered.putIfAbsent(n, t) }
        val scan = scan(ns, src.name, physical.map { it.first })
        val project = ProjectNode.newBuilder().setInput(scan)
        src.columns.forEach { c ->
            project.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(PbExpression.newBuilder().setColumnRef(PbColumnRef.newBuilder().setName(c.physical)))
                    .setAlias(c.name),
            )
        }
        return Built(PlanNode.newBuilder().setProject(project).build(), m.columns)
    }

    /** An opaque `"""sql` fragment producer: its verbatim text as an inline source, typed by the world schema. */
    private fun fragmentSource(
        container: Container,
        port: String,
        text: String,
    ): Built {
        val name = "${container.label}__$port"
        val cols =
            world.world.storages
                .flatMap { it.schemas }
                .firstOrNull { it.qname.name.substringAfterLast('.') == port }
                ?.fields
                ?.entries
                ?.map { EmitColumn(it.key, it.value) }
                ?: throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail =
                        "fragment island '${container.label}' feeds '$port' but no schema '$port' " +
                            "is declared in the world",
                    island = container.label,
                )
        inlineSql[name] = text.trim()
        val registered = tables.getOrPut(INLINE_NS to name) { LinkedHashMap() }
        cols.forEach { registered.putIfAbsent(it.name, it.type) }
        return Built(scan(INLINE_NS, name, cols.map { it.name }), cols)
    }

    private fun scan(
        ns: String,
        name: String,
        columns: List<String>,
    ): PlanNode {
        val scan =
            TableScanNode
                .newBuilder()
                .setTable(
                    QualifiedName
                        .newBuilder()
                        .setSchemaCode(SchemaCode.DB)
                        .setNamespace(ns)
                        .setName(name),
                )
        columns.forEach { scan.addOutputColumns(PbColumnRef.newBuilder().setName(it)) }
        return PlanNode.newBuilder().setTableScan(scan).build()
    }

    companion object {
        /** Namespace of the placeholder tables that stand for inline (query/view/fragment) sources. */
        const val INLINE_NS = "_ttrp_inline"

        /** Namespace physical tables register under for the translator; removed from the text (unqualified). */
        const val TABLE_NS = "_ttrp_table"
    }
}

/** Text post-processing of an unparsed `sql-text` statement (pure; unit-tested on its own). */
object SqlTextRender {
    /**
     * Rename the translator's positional `?` placeholders to `:name`, in appearance order ([names] has
     * one entry per `?`). Skips `'…'` string literals, `[…]` and `"…"` quoted identifiers.
     */
    fun namePlaceholders(
        sql: String,
        names: List<String>,
    ): String {
        if (names.isEmpty()) return sql
        val out = StringBuilder()
        var i = 0
        var n = 0
        while (i < sql.length) {
            val c = sql[i]
            when (c) {
                '\'', '[', '"' -> {
                    val close = if (c == '[') ']' else c
                    var j = i + 1
                    while (j < sql.length) {
                        if (sql[j] == close) {
                            if (j + 1 < sql.length && sql[j + 1] == close) {
                                j += 2
                                continue
                            }
                            break
                        }
                        j++
                    }
                    out.append(sql, i, minOf(j + 1, sql.length))
                    i = j + 1
                }
                '?' -> {
                    val name =
                        names.getOrNull(n++)
                            ?: throw TtrpEmitException(
                                EmitDiagnosticId.UNSUPPORTED_NODE,
                                detail = "more `?` placeholders than reported parameters (${names.size})",
                            )
                    out.append(':').append(name)
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Drop the `[<ns>].` qualifier from every table reference in [ns] (the door resolves tables unqualified). */
    fun unqualify(
        sql: String,
        ns: String,
    ): String = sql.replace("[$ns].", "")

    /**
     * `[ident]` → `"ident"` outside string literals (a `"` inside becomes `""`; `]]` becomes `]`). Both
     * SQL Server (QUOTED_IDENTIFIER ON) and the host door's parser read double-quoted identifiers; the
     * door's parser does not read brackets.
     */
    fun doubleQuoted(sql: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when (c) {
                '\'' -> {
                    var j = i + 1
                    while (j < sql.length) {
                        if (sql[j] == '\'') {
                            if (j + 1 < sql.length && sql[j + 1] == '\'') {
                                j += 2
                                continue
                            }
                            break
                        }
                        j++
                    }
                    out.append(sql, i, minOf(j + 1, sql.length))
                    i = j + 1
                }
                '[' -> {
                    val name = StringBuilder()
                    var j = i + 1
                    while (j < sql.length) {
                        if (sql[j] == ']') {
                            if (j + 1 < sql.length && sql[j + 1] == ']') {
                                name.append(']')
                                j += 2
                                continue
                            }
                            break
                        }
                        name.append(sql[j])
                        j++
                    }
                    out.append('"').append(name.toString().replace("\"", "\"\"")).append('"')
                    i = j + 1
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Substitute each inline-source placeholder table `[<ns>].[<name>]` (with or without a following
     * `AS [alias]`) by its SQL text as a derived table — `(<sql>) AS [alias-or-name]`.
     */
    fun inlineSources(
        sql: String,
        ns: String,
        sources: Map<String, String>,
    ): String {
        var out = sql
        for ((name, text) in sources) {
            val ref = Regex("\\[" + Regex.escape(ns) + "]\\.\\[" + Regex.escape(name) + "](\\s+AS\\s+(\\[[^\\]]+]))?")
            out =
                ref.replace(out) { m ->
                    val alias = m.groups[2]?.value ?: "[$name]"
                    "(\n" + text.trim().prependIndent("    ") + "\n) AS " + alias
                }
        }
        return out
    }
}
