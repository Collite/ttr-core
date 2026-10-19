// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.recipe

import org.tatrman.ttrp.ast.AsOf
import org.tatrman.ttrp.ast.ContainerBody
import org.tatrman.ttrp.ast.DecisionDecl
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.IslandForm
import org.tatrman.ttrp.ast.PortDecl
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.RoleContainer
import org.tatrman.ttrp.ast.Wiring
import org.tatrman.ttrp.ast.WiringValue
import org.tatrman.ttrp.ast.WindowOver

/**
 * `ttrp explain` for a recipe (AU-core-P0 · S0.1 T6): the skeleton straight from the AST — the header,
 * every role container with its island form, the decision's routes, the wiring. Deterministic. No graph
 * and no mode verdict yet: the key-lineage verdict is S1.2's, the compiled recipe section S2.3's.
 */
object RecipeExplain {
    fun render(recipe: RecipeDecl): String =
        buildString {
            val header =
                buildList {
                    add("key ${recipe.key.joinToString(", ") { it.text }.ifEmpty { "?" }}")
                    add(
                        "as of " +
                            when (val a = recipe.asOf) {
                                is AsOf.Now -> "now"
                                is AsOf.Instant -> "\"${a.text}\""
                            },
                    )
                    if (recipe.parameters.isNotEmpty()) {
                        add(
                            "parameters ${recipe.parameters.joinToString(", ") { it.text }}",
                        )
                    }
                    recipe.protocol?.let { add("protocol ${it.spelling}") }
                }
            appendLine("recipe${recipe.name?.let { " $it" } ?: ""} · ${header.joinToString(" · ")}")
            recipe.document?.let { appendLine("  document $it") }
            for (member in recipe.members) {
                when (member) {
                    is RoleContainer -> appendLine(role(member))
                    is DecisionDecl -> decision(member)
                    is org.tatrman.ttrp.ast.ActionsBlock -> {
                        appendLine("actions")
                        val width = member.wirings.maxOfOrNull { routes(it).length } ?: 0
                        member.wirings.forEach { appendLine("  ${routes(it).padEnd(width)} -> ${call(it)}") }
                    }
                }
            }
        }.trimEnd()

    private fun role(c: RoleContainer): String {
        val form =
            when (val f = c.form) {
                is IslandForm.Target -> "target ${f.target.text}"
                is IslandForm.Builtin -> "builtin ${f.capability.text}"
                is IslandForm.Foreign -> "foreign ${f.language} ${grain(f.grain)} schema ${f.schema}"
                is IslandForm.Call -> "call ${f.service.text}${f.grain?.let { " ${grain(it)}" } ?: ""}"
                is IslandForm.Turn -> "turn ${f.agent.text} answer ${f.answerSchema}"
            }
        val window =
            c.window?.let { w ->
                val over =
                    when (val o = w.over) {
                        null -> "?"
                        is WindowOver.Calendar -> o.unit
                        is WindowOver.Rows -> "rows ${o.count}"
                        is WindowOver.Gap -> "gap ${o.duration.amount} ${o.duration.unit.spelling}"
                    }
                "  window by ${w.by.joinToString(", ") { it.text }} over $over"
            } ?: ""
        return "${c.role.keyword.padEnd(ROLE_WIDTH)} ${c.name}${ports(c.ports)}  $form  [${body(c.body)}]$window"
    }

    private fun StringBuilder.decision(d: DecisionDecl) {
        val sw = d.switch
        appendLine(
            "${"decision".padEnd(ROLE_WIDTH)} ${d.name}${ports(d.ports)}  switch ${sw.input.parts.joinToString(".")}" +
                (sw.mode?.let { "  ${it.spelling}" } ?: ""),
        )
        val width =
            (sw.routes.map { it.name } + listOfNotNull(sw.elseRoute?.let { "else" })).maxOfOrNull { it.length } ?: 0
        for (r in sw.routes) {
            appendLine("  route ${r.name.padEnd(width)}${attrs(r.because, r.detail.map { it.text })}")
        }
        sw.elseRoute?.let { e ->
            appendLine(
                "  route ${"else".padEnd(width)}  -> ${e.name ?: "none"}${attrs(e.because, e.detail.map { it.text })}",
            )
        }
    }

    private fun attrs(
        because: String?,
        detail: List<String>,
    ): String =
        (because?.let { "  because \"$it\"" } ?: "") +
            (if (detail.isNotEmpty()) "  detail ${detail.joinToString(", ")}" else "")

    private fun routes(w: Wiring): String = w.routes.joinToString(", ") { "${it.decision}.${it.route}" }

    private fun call(w: Wiring): String {
        val args = if (w.args.isEmpty()) "" else " { ${w.args.joinToString(", ") { it.name + nested(it.value) }} }"
        return "${w.action}$args${if (w.whenCondition != null) " when …" else ""}"
    }

    /** An `on_due: hold { … }` argument shows the action it calls. */
    private fun nested(v: WiringValue): String = if (v is WiringValue.Call) ": ${v.action}" else ""

    private fun ports(ports: List<PortDecl>): String =
        "(${ports.joinToString(", ") { "${it.kind.name.lowercase()} ${it.name}" }})"

    private fun body(b: ContainerBody?): String =
        when (b) {
            null -> "no body"
            is FlowBody -> "canonical"
            is FragmentBody -> b.tag.ifEmpty { "file" }
        }

    private fun grain(g: org.tatrman.ttrp.ast.Grain): String =
        when (g) {
            org.tatrman.ttrp.ast.Grain.PER_CANDIDATE -> "per candidate"
            org.tatrman.ttrp.ast.Grain.SET -> "set"
        }

    private const val ROLE_WIDTH = 10
}
