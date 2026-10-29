// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttrp.bundle.RecipeFingerprints
import org.tatrman.ttrp.bundle.RecipeApprovalDependencies
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
 *   reach it); one line per wiring, `routes|action|column=value,…|when`, columns sorted; one line per windowed
 *   context, sorted, `window=<context>|by=a,b|over=<over>`; the lines `key=…`, `as_of=…`, `mode=…`, `protocol=…`;
 *   then every SQL artifact (direct output, part, combine), sorted by path, as `path` + `\n` + its text. The `verdict_reason` the
 *   switch lowering wrote ([RecipeModel.reasonNodes] — those nodes only, never a column of that name elsewhere) is the
 *   placeholder `{}` in the graph the json and the statements are taken from — a `because:` is explanation, not
 *   logic. Parameter values never reach it (statements bind them).
 * - **values** — `sha256` over `ttrp-recipe-values/1` + `\n`, then per parameter, sorted by name, `name\ttype\tvalue\n`.
 * - **labels** — `sha256` over `ttrp-recipe-labels/1` + `\n`, then per route, sorted by name, `route\tbecause\n`, then
 *   per wired action with a `dry_run:` text, sorted by name, `action\tlang=text,…\n` (languages sorted; D-125).
 *
 * Every field is escaped (review-119 m7): `\` → `\\`, and tab, newline, `|`, `,`, `=` as `\t`, `\n`, `\|`, `\,`,
 * `\=` — no two different recipes write the same bytes. Each is spelled `sha256:<64 hex>`.
 * Recipes with directly wired writer defaults use [RecipeActionDependencies]' versioned text/2 binding
 * over this unchanged text/1 base. Values and labels retain their existing byte recipes.
 */
object RecipeFingerprints {
    data class Result(
        val fingerprints: RecipeFingerprints,
        val approvalDependencies: RecipeApprovalDependencies?,
    )

    fun of(
        plan: TtrpPipeline.PlanResult,
        model: RecipeModel,
        protocol: String,
        asOf: String,
    ): RecipeFingerprints = capture(plan, model, protocol, asOf).fingerprints

    /** Compute the digest and the exact evidence serialized beside it once, from the same metadata snapshot. */
    fun capture(
        plan: TtrpPipeline.PlanResult,
        model: RecipeModel,
        protocol: String,
        asOf: String,
    ): Result = capture(plan, model, protocol, asOf, RecipeActionDependencies.wiredActions(plan, model))

    /** Reuse [RecipeActionDependencies.wiredActions] when the assembler also derives implicit reads. */
    fun capture(
        plan: TtrpPipeline.PlanResult,
        model: RecipeModel,
        protocol: String,
        asOf: String,
        actions: Map<String, AutomationAction>?,
    ): Result {
        val base = legacyText(plan, model, protocol, asOf)
        val values = StringBuilder("ttrp-recipe-values/1\n")
        model.parameterDecls.sortedBy { it.name }.forEach { p ->
            values.append("${esc(p.name)}\t${esc(p.type ?: "")}\t${esc(p.value ?: "")}\n")
        }
        val labels = StringBuilder("ttrp-recipe-labels/1\n")
        model.routes.sortedBy { it.name }.forEach { r -> labels.append("${esc(r.name)}\t${esc(r.because ?: "")}\n") }
        model.dryRuns.toSortedMap().forEach { (action, texts) ->
            val byLang = texts.toSortedMap().entries.joinToString(",") { "${esc(it.key)}=${esc(it.value)}" }
            labels.append("${esc(action)}\t$byLang\n")
        }
        val dependencies = RecipeActionDependencies.of(plan, base, actions)
        return Result(
            RecipeFingerprints(
                dependencies?.let(RecipeActionDependencies::text) ?: base,
                sha256(values),
                sha256(labels),
            ),
            dependencies,
        )
    }

    /** Frozen text/1 digest; do not change these bytes when adding approval dependencies. */
    fun legacyText(
        plan: TtrpPipeline.PlanResult,
        model: RecipeModel,
        protocol: String,
        asOf: String,
    ): String {
        val masked = masked(requireNotNull(plan.graph), model.reasonNodes)
        val text = StringBuilder("ttrp-recipe-text/1\n")
        text.append(NormalizedGraphJson.write(masked)).append('\n')
        for (w in model.wirings) {
            val cols =
                w.columns
                    .toSortedMap()
                    .entries
                    .joinToString(",") { "${esc(it.key)}=${esc(it.value)}" }
            text.append("${w.routes.joinToString(",") { esc(it) }}|${esc(w.action)}|$cols|${esc(w.whenText ?: "")}\n")
        }
        // a window scopes a set-level context (06 A-5) — logic, though no statement spells it (review-119 B3)
        model.contexts
            .mapNotNull { c ->
                c.window?.let {
                    "window=${esc(
                        c.name,
                    )}|by=${it.by.joinToString(",") { b -> esc(b.text) }}|over=${esc(over(it))}"
                }
            }.sorted()
            .forEach { text.append(it).append('\n') }
        text.append("key=${model.keyAttributes.joinToString(",") { esc(it) }}\n")
        text.append("as_of=${esc(asOf)}\n")
        text.append("mode=${model.mode.spelling}\n")
        text.append("protocol=${esc(protocol)}\n")
        val statements = RecipeStatements.plan(plan.copy(graph = masked))
        val sqlFiles = linkedMapOf<String, String>()
        statements.forEach { s ->
            sqlFiles[s.file] = s.sql
            s.parts.forEach { p -> sqlFiles[p.file] = p.sql }
        }
        sqlFiles.toSortedMap().forEach { (file, sql) -> text.append("$file\n$sql\n") }

        return sha256(text)
    }

    /** A byte-recipe field: `\\`, tab, newline and the separators `|`, `,`, `=` escaped. */
    private fun esc(s: String): String =
        buildString {
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '|', ',', '=' -> append('\\').append(ch)
                    else -> append(ch)
                }
            }
        }

    /** A window's `over:` as written: `day`, `rows: 5`, `gap: 30 minutes`. */
    private fun over(w: org.tatrman.ttrp.ast.Window): String =
        when (val o = w.over) {
            null -> ""
            is org.tatrman.ttrp.ast.WindowOver.Calendar -> o.unit
            is org.tatrman.ttrp.ast.WindowOver.Rows -> "rows: ${o.count}"
            is org.tatrman.ttrp.ast.WindowOver.Gap -> "gap: ${o.duration.amount} ${o.duration.unit.spelling}"
        }

    /**
     * [graph] with the `verdict_reason` of the switch lowering's route nodes ([reasonNodes]) the placeholder `{}` —
     * the calc's assignment, or the projection's column once normalize made it one (same node id). A `verdict_reason`
     * any other node computes is logic and stays.
     */
    fun masked(
        graph: TtrpGraph,
        reasonNodes: Set<String>,
    ): TtrpGraph {
        val placeholder = { n: org.tatrman.ttrp.ast.SourceLocation -> Literal(LiteralValue.Str("{}"), n) }
        val nodes =
            graph.nodes.mapValues { (id, n) ->
                if (id !in reasonNodes) return@mapValues n
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
