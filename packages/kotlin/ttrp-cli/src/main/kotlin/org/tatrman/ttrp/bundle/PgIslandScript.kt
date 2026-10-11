// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import org.tatrman.ttr.semantics.md.MdBindings
import org.tatrman.ttr.semantics.md.MdModel
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.emit.EmitDiagnosticId
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.emit.core.DisplayTypes
import org.tatrman.ttrp.emit.sql.PgAdbcIslandEmitter
import org.tatrman.ttrp.emit.sql.SqlIslandEmitter
import org.tatrman.ttrp.graph.capability.BoundWorld
import org.tatrman.ttrp.graph.collapse.Island
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Display
import org.tatrman.ttrp.graph.model.DisplayLeaves
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.Store
import org.tatrman.ttrp.graph.model.TtrpGraph

/**
 * Assembles the Python `adbc_driver_postgresql` runtime script for a **decomposed Postgres island**
 * (S3.5 T3.5.4) by gathering, from the graph, exactly what [PgAdbcIslandEmitter] needs:
 *  - **SQL temps** — each container IN port fed by a *same-engine fragment* (the hero's `accounts`
 *    port fed by the `acc_prep` PG fragment) becomes `CREATE TEMP TABLE <port> AS <fragment sql>`;
 *  - **CSV temps** — each member [Load] of a CSV storage becomes a typed temp from `files/<leaf>.csv`;
 *  - **outputs** — each OUT port's SQL (via [SqlIslandEmitter.emitOutputs]) written to its sink
 *    (`out/<display>.arrow` / `staging/<port>.arrow`). An elaborated **wired** `rejects` port is
 *    re-wired onto a normal `.out` producer (RJ-P1), so it exports here like any port; only a
 *    literal, un-elaborated `rejects` mapping (a dead wire, RJ-101) is skipped.
 */
object PgIslandScript {
    fun build(
        island: Island,
        graph: TtrpGraph,
        bound: BoundWorld,
        connEnv: String,
        mdBindings: MdBindings? = null,
        mdModel: MdModel? = null,
    ): String {
        val container = graph.containers.getValue(island.id)
        // The decomposed relational PG island is where a resolved MD dot-path predicate actually
        // lowers to SQL (a fragment island stays verbatim), so the `md2db_*` bindings + logical model
        // are threaded to its emitter — the counterpart of the graph's `mdResolutions`.
        val emitter = SqlIslandEmitter(bound, mdBindings, mdModel)
        val outSql = emitter.emitOutputs(island, graph)

        val outputs =
            container.portMapping.entries.flatMap { (port, ref) ->
                // Dead-wire rejects only (elaborated rejects are re-wired to a `.out`, RJ-P1).
                if (ref.port == "rejects") return@flatMap emptyList()
                val sql = outSql[port]?.text ?: return@flatMap emptyList()
                sinks(container, port, graph).map { (sink, leaf) ->
                    PgAdbcIslandEmitter.Output(
                        actionProjection(sql, leaf, container, port, graph, bound),
                        sink,
                        leaf?.schema?.columns?.map { it.name to DisplayTypes.arrow(DisplayTypes.of(it)) },
                    )
                }
            }

        val sqlTemps =
            container.declaredPorts
                .filter { it.direction == PortDirection.IN }
                .mapNotNull { p ->
                    val feed =
                        graph.edges.firstOrNull { it.to == PortRef(container.id, p.name) } ?: return@mapNotNull null
                    val frag = graph.containers[feed.from.nodeId]?.verbatimFragment ?: return@mapNotNull null
                    PgAdbcIslandEmitter.SqlTemp(p.name, frag.sourceText.trim())
                }

        val csvTemps =
            container.memberIds
                .mapNotNull { graph.nodes[it] as? Load }
                .mapNotNull { load ->
                    val cols = csvColumns(load, bound) ?: return@mapNotNull null
                    PgAdbcIslandEmitter.CsvTemp(
                        table = load.source.substringAfterLast('.'),
                        csvPath = load.source.replace('.', '/') + ".csv",
                        columns = cols,
                    )
                }

        return PgAdbcIslandEmitter().emit(connEnv, sqlTemps, csvTemps, outputs, emitter.countQueries(island, graph))
    }

    /**
     * The external sinks of an OUT [port] — every leaf it feeds: Display → `out/<stem>.arrow` (the stem unique
     * among same-named displays, [DisplayLeaves]), Store → `staging/<port>.arrow`.
     */
    private fun sinks(
        container: Container,
        port: String,
        graph: TtrpGraph,
    ): List<Pair<String, Display?>> =
        graph.edges
            .filter { it.from == PortRef(container.id, port) }
            .mapNotNull { e ->
                when (val leaf = graph.nodes[e.to.nodeId]) {
                    is Display -> "out/${DisplayLeaves.stemOf(graph, leaf)}.arrow" to leaf
                    is Store -> "staging/$port.arrow" to null
                    else -> null
                }
            }.distinctBy { it.first }

    /**
     * An action display (grammar 0.14) reads exactly its row schema's columns, in schema order, each CAST to the
     * schema column's Postgres type ([DisplayTypes.postgres]): the port's statement wrapped in
     * `SELECT CAST("c" AS …) AS "c", … FROM (…)`, an absent `optional` column `CAST(NULL AS …)`. A required column
     * the port's statement does not produce is an emit error naming TTRP-DSP-001 — never a NULL. Any other sink
     * reads the port's statement unchanged.
     */
    private fun actionProjection(
        sql: String,
        leaf: Display?,
        container: Container,
        port: String,
        graph: TtrpGraph,
        bound: BoundWorld,
    ): String {
        val schema = leaf?.schema ?: return sql
        val present =
            org.tatrman.ttrp.emit.sql
                .SqlGraphEmitter(graph, bound)
                .plansByOutput(container)[port]
                ?.lastOrNull()
                ?.outputColumns
                ?.map { it.name }
                ?.toSet() ?: emptySet()
        return displayProjection(sql, schema, present, container.label, port, leaf.location)
    }

    /** [sql] projected to the action display [schema], given the columns the port's statement produces. */
    internal fun displayProjection(
        sql: String,
        schema: org.tatrman.ttrp.resolve.DisplaySchema,
        present: Set<String>,
        island: String,
        port: String,
        location: org.tatrman.ttrp.ast.SourceLocation? = null,
    ): String {
        val cols =
            schema.columns.joinToString(", ") { c ->
                val q = "\"" + c.name.replace("\"", "\"\"") + "\""
                val type = DisplayTypes.postgres(DisplayTypes.of(c))
                when {
                    c.name in present -> "CAST($q AS $type) AS $q"
                    c.optional -> "CAST(NULL AS $type) AS $q"
                    else ->
                        throw TtrpEmitException(
                            EmitDiagnosticId.UNSUPPORTED_NODE,
                            detail =
                                "island '$island' OUT port '$port' feeds action display schema " +
                                    "`${schema.qualifiedName}` but has no column `${c.name}` (TTRP-DSP-001)",
                            island = island,
                            location = location,
                            suggestedAlternative = TtrpDiagnosticId.DSP_001.suggestedAlternative,
                        )
                }
            }
        return "SELECT $cols\nFROM (\n${sql.trimEnd()}\n) AS \"_ttrp_display\""
    }

    /** A member [Load]'s CSV columns from its world-declared schema (D-c), typed for the temp table. */
    private fun csvColumns(
        load: Load,
        bound: BoundWorld,
    ): List<PgAdbcIslandEmitter.PgColumn>? {
        val ref = load.schemaRef ?: return null

        fun matches(name: String) = name == ref || name.substringAfterLast('.') == ref
        val storage = bound.world.storages.firstOrNull { s -> s.schemas.any { matches(it.qname.name) } } ?: return null
        val schema = storage.schemas.first { matches(it.qname.name) }
        return schema.fields.entries.map { PgAdbcIslandEmitter.pgColumn(it.key, it.value) }
    }
}
