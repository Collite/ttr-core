// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttrp.emit.polars.PolarsExprRenderer
import org.tatrman.ttrp.emit.polars.PolarsIslandEmitter
import org.tatrman.ttrp.emit.polars.PolarsStep
import org.tatrman.ttrp.emit.sql.EmitInput
import org.tatrman.ttrp.emit.sql.JoinDedup
import org.tatrman.ttrp.emit.sql.SqlGraphEmitter
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortKind
import org.tatrman.ttrp.resolve.LoadSource

/**
 * Polars twin of RecipeStatements: the same resolved graph cones and row schemas, emitted as native frame
 * operations. Model reads are staged by the caller under [sources]; no output is evaluated by a SQL engine.
 * SQL fragments use their decomposed members, exactly as the SQL recipe emitter does.
 */
class RecipePolarsEmitter(
    private val plan: TtrpPipeline.PlanResult,
) {
    val sources = linkedMapOf<String, LoadSource>()
    private val graph = requireNotNull(plan.graph)
    private val world = requireNotNull(plan.bound)
    private val memo = linkedMapOf<Pair<String, String>, String>()
    private val lines = mutableListOf<String>()
    private var serial = 0

    private fun variable() = "f${serial++}"

    fun emit(): String {
        require(plan.recipe != null) { "Polars recipe emit needs a checked recipe" }
        lines.clear()
        sources.clear()
        memo.clear()
        serial = 0
        val outputs = linkedMapOf<String, String>()
        requireNotNull(plan.exec).islands.forEach { island ->
            val c = graph.containers.getValue(island.id)
            c.declaredPorts
                .filter {
                    it.direction == PortDirection.OUT &&
                        it.kind == PortKind.DATA &&
                        it.name != "rejects"
                }.forEach { p -> outputs["${island.name}.${p.name}"] = output(c.id, p.name) }
        }
        return buildString {
            append("import polars as pl\n")
            append(JOIN_HELPER).append('\n')
            lines.forEach { append(it).append('\n') }
            outputs.forEach { (name, v) ->
                append(
                    "$v.select(pl.all().exclude(\"^_ttrp_.*\$\")).write_ipc(${q(
                        "out/$name.arrow",
                    )}, compat_level=pl.CompatLevel.oldest())\n",
                )
            }
        }
    }

    private fun output(
        containerId: String,
        port: String,
    ): String =
        memo.getOrPut(containerId to port) {
            val c = graph.containers.getValue(containerId)
            val chain =
                SqlGraphEmitter(graph, world, sqlText = true, fragmentsAsMembers = true, textKeys = true)
                    .plansByOutput(c)
                    .getValue(port)
            val built = linkedMapOf<String, String>()
            chain.forEach { en ->
                val ins =
                    en.inputs.map { input ->
                        when (input) {
                            is EmitInput.Cte -> built.getValue(input.producerNodeId)
                            is EmitInput.Upstream -> output(input.containerId, input.port)
                            is EmitInput.Model -> {
                                val name =
                                    sources.entries.firstOrNull { it.value == input.source }?.key
                                        ?: "s${sources.size}".also {
                                            sources[it] = input.source
                                        }
                                variable().also { lines += "$it = pl.read_ipc(${q("staging/$name.arrow")})" }
                            }
                            is EmitInput.BaseTable -> error("recipe input ${input.name} is not a staged model source")
                        }
                    }
                val v = variable()
                val node = en.node
                if (node is Join) {
                    val cols =
                        en.inputs.map { i ->
                            when (i) {
                                is EmitInput.Cte -> i.columns
                                is EmitInput.Upstream -> i.columns
                                is EmitInput.Model -> i.columns
                                is EmitInput.BaseTable -> i.columns
                            }.map { it.name }
                        }
                    val renderer =
                        PolarsExprRenderer(column = { r ->
                            if (r.port ==
                                "right"
                            ) {
                                "_ttrp_r_${cols[1].indexOf(r.column)}"
                            } else {
                                r.column
                            }
                        })
                    val pred = node.on?.let(renderer::render) ?: "pl.lit(True)"
                    val survivors =
                        if (node.type in setOf(JoinType.SEMI, JoinType.ANTI)) {
                            cols[0].mapIndexed { i, n -> i to n }
                        } else {
                            JoinDedup.survivors(
                                cols[0],
                                cols[1],
                                node.on?.let(JoinDedup::rightEquiKeys).orEmpty(),
                                en.joinKeep,
                            )
                        }
                    val names = cols[0] + cols[1].indices.map { "_ttrp_r_$it" }
                    val projection = survivors.joinToString(", ") { (i, n) -> "pl.col(${q(names[i])}).alias(${q(n)})" }
                    lines +=
                        "$v = _ttrp_join(${ins[0]}, ${ins[1]}, $pred, ${q(
                            node.type.name.lowercase(),
                        )}).select([$projection])"
                } else {
                    // Keep the established node emitter (including reject and aggregate semantics).
                    lines +=
                        PolarsIslandEmitter()
                            .emit("recipe", listOf(PolarsStep(v, node, ins)))
                            .text
                            .lineSequence()
                            .filterNot { it.startsWith("import ") || it.startsWith("#") }
                            .joinToString("\n")
                }
                built[node.id] = v
            }
            built.getValue(chain.last().node.id)
        }

    companion object {
        private fun q(s: String) = JsonPrimitive(s).toString()

        // Row identities preserve multiplicity, including duplicate left rows and unmatched NULL keys. A theta
        // join is required for lowered calendar counts; outer ON predicates must be evaluated before null extension.
        private val JOIN_HELPER =
            """
            def _ttrp_join(left, right, predicate, how):
                right = right.rename({c: f"_ttrp_r_{i}" for i, c in enumerate(right.columns)})
                left = left.with_row_index("_ttrp_li")
                right = right.with_row_index("_ttrp_ri")
                matched = left.lazy().join(right.lazy(), how="cross").filter(predicate).collect()
                if how in ("semi", "anti"):
                    return left.join(matched.select("_ttrp_li").unique(), on="_ttrp_li", how=how)
                frames = [matched]
                if how in ("left", "full"):
                    absent = left.join(matched.select("_ttrp_li").unique(), on="_ttrp_li", how="anti")
                    frames.append(absent.with_columns([pl.lit(None, dtype=t).alias(c) for c, t in right.schema.items()]))
                if how in ("right", "full"):
                    absent = right.join(matched.select("_ttrp_ri").unique(), on="_ttrp_ri", how="anti")
                    frames.append(absent.with_columns([pl.lit(None, dtype=t).alias(c) for c, t in left.schema.items()]).select(matched.columns))
                return pl.concat(frames)
            """.trimIndent()
    }
}
