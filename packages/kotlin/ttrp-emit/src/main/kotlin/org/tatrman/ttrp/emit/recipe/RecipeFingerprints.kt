// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import org.tatrman.ttrp.bundle.RecipeFingerprints
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.explain.NormalizedGraphJson
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.graph.recipe.RecipeModel
import org.tatrman.ttrp.graph.recipe.SwitchLowering
import java.security.MessageDigest

/**
 * A recipe's three fingerprints (AU-core-P2 · S2.3 T3; D-13, D-60, contracts §5.1) — the byte recipe is a cross-repo
 * contract (the Server compares them to the approved ones), so it is spelled out here and in §5.1:
 *
 * - **text** — `sha256` over, each followed by `\n`: the line `ttrp-recipe-text/1`; the recipe's lowered graph as
 *   [NormalizedGraphJson] writes it (canonical: no node ids, no source locations — whitespace and comments never
 *   reach it); one line per wiring, `routes|action|column=value,…|when`, columns sorted; the lines `key=…`,
 *   `as_of=…`, `mode=…`, `protocol=…`; then every statement, sorted by `island.port`, as `island.port` + `\n` + its
 *   text. Every route's `verdict_reason` is replaced by the placeholder `{}` in the graph the json and the statements
 *   are taken from — a `because:` is explanation, not logic. Parameter values never reach it (statements bind them).
 * - **values** — `sha256` over `ttrp-recipe-values/1` + `\n`, then per parameter, sorted by name, `name\ttype\tvalue\n`.
 * - **labels** — `sha256` over `ttrp-recipe-labels/1` + `\n`, then per route, sorted by name, `route\tbecause\n`.
 *
 * Each is spelled `sha256:<64 hex>`.
 */
object RecipeFingerprints {
    fun of(
        plan: TtrpPipeline.PlanResult,
        model: RecipeModel,
        protocol: String,
        asOf: String,
    ): RecipeFingerprints {
        val masked = masked(requireNotNull(plan.graph))
        val text = StringBuilder("ttrp-recipe-text/1\n")
        text.append(NormalizedGraphJson.write(masked)).append('\n')
        for (w in model.wirings) {
            val cols =
                w.columns
                    .toSortedMap()
                    .entries
                    .joinToString(",") { "${it.key}=${it.value}" }
            text.append("${w.routes.joinToString(",")}|${w.action}|$cols|${w.whenText ?: ""}\n")
        }
        text.append("key=${model.keyAttributes.joinToString(",")}\n")
        text.append("as_of=$asOf\n")
        text.append("mode=${model.mode.spelling}\n")
        text.append("protocol=$protocol\n")
        RecipeStatements
            .plan(plan.copy(graph = masked))
            .sortedBy { "${it.island}.${it.port}" }
            .forEach { s -> text.append("${s.island}.${s.port}\n${s.sql}\n") }

        val values = StringBuilder("ttrp-recipe-values/1\n")
        model.parameterDecls.sortedBy { it.name }.forEach { p ->
            values.append("${p.name}\t${p.type ?: ""}\t${p.value ?: ""}\n")
        }
        val labels = StringBuilder("ttrp-recipe-labels/1\n")
        model.routes.sortedBy { it.name }.forEach { r -> labels.append("${r.name}\t${r.because ?: ""}\n") }
        return RecipeFingerprints(sha256(text), sha256(values), sha256(labels))
    }

    /** [graph] with every route's `verdict_reason` the placeholder `{}` — a calc's assignment, a projection's column. */
    fun masked(graph: TtrpGraph): TtrpGraph {
        val placeholder = { n: org.tatrman.ttrp.ast.SourceLocation -> Literal(LiteralValue.Str("{}"), n) }
        val nodes =
            graph.nodes.mapValues { (_, n) ->
                when (n) {
                    is Calc ->
                        n.copy(
                            assignments =
                                n.assignments.map {
                                    if (it.name ==
                                        SwitchLowering.VERDICT_REASON
                                    ) {
                                        it.copy(value = placeholder(n.location))
                                    } else {
                                        it
                                    }
                                },
                        )
                    is Project ->
                        n.copy(
                            columns =
                                n.columns.mapIndexed { i, c ->
                                    if (n.aliases.getOrNull(i) ==
                                        SwitchLowering.VERDICT_REASON
                                    ) {
                                        placeholder(n.location)
                                    } else {
                                        c
                                    }
                                },
                        )
                    else -> n
                }
            }
        return graph.copy(nodes = nodes)
    }

    private fun sha256(s: CharSequence): String =
        "sha256:" +
            MessageDigest
                .getInstance("SHA-256")
                .digest(s.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
}
