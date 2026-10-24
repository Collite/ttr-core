// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttrp.ast.ImportDecl
import org.tatrman.ttrp.bundle.RecipeApprovalDependencies
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.recipe.RecipeModel
import org.tatrman.ttrp.resolve.ModelIndex
import java.security.MessageDigest

/**
 * Native preview approval dependencies v1 (AU contracts §2.3). Activated by a directly wired
 * `writer_defaults` declaration; all directly wired actions and their logical ER targets are then covered.
 * This is not coverage for physical writes, connector configuration or deferred action dispatch.
 * [matches] is the consumer seam: a version/toolchain claim alone never authorizes pinned metadata.
 * Changing coverage or executable default semantics requires a new dependency version, never silent v1 drift.
 */
object RecipeActionDependencies {
    private val hash = Regex("sha256:[0-9a-f]{64}")

    /** The frozen v1 recipe text digest remains the base; default-free recipes keep their old bytes. */
    fun of(
        plan: TtrpPipeline.PlanResult,
        model: RecipeModel,
        textBase: String,
    ): RecipeApprovalDependencies? {
        val report = plan.frontendReport ?: return null
        val index = report.modelIndex ?: return null
        val imports =
            report.document.statements.filterIsInstance<ImportDecl>().map {
                ModelIndex.importScope(it.qname.parts, it.qname.text)
            }
        val visible = index.automationInScope(imports).filterIsInstance<AutomationAction>()
        val names =
            model.wirings
                .map { it.action }
                .distinct()
                .sorted()
        if (visible.none { it.qname.name in names && it.properties.any { p -> p.first == "writer_defaults" } }) {
            return null
        }
        val actions = names.associateWith { name -> visible.single { it.qname.name == name } }
        return RecipeApprovalDependencies(
            textBase = textBase,
            actions = fingerprints(actions) { index.entityInScope(it, imports) },
        )
    }

    /** The text/2 binding is independently recomputable without recompiling graph or SQL. */
    fun text(evidence: RecipeApprovalDependencies): String {
        require(evidence.version == 1 && hash.matches(evidence.textBase)) { "unsupported approval evidence" }
        require(
            evidence.actions.isNotEmpty() && evidence.actions.all { it.key.isNotBlank() && hash.matches(it.value) },
        ) {
            "invalid action approval evidence"
        }
        return sha256("ttrp-recipe-text/2\n${evidence.textBase}\n${json(evidence.actions)}\n")
    }

    /** Caller supplies exactly the distinct manifest wiring names, resolved in its pinned bundle scope. */
    fun matches(
        evidence: RecipeApprovalDependencies?,
        approvedText: String,
        actions: Map<String, AutomationAction>,
        entity: (String) -> Entity?,
    ): Boolean =
        evidence != null &&
            runCatching {
                text(evidence) == approvedText && evidence.actions == fingerprints(actions, entity)
            }.getOrDefault(false)

    fun fingerprints(
        actions: Map<String, AutomationAction>,
        entity: (String) -> Entity?,
    ): Map<String, String> =
        actions.toSortedMap().mapValues { (_, action) ->
            val targets =
                (action.columns.map { it.type } + listOfNotNull(action.set?.attribute, action.transition?.attribute))
                    .filter { '.' in it }
                    .map { it.substringBefore('.') }
                    .distinct()
                    .sorted()
                    .associateWith { name ->
                        val target = requireNotNull(entity(name)) { "unresolved action ER target: $name" }
                        mapOf(
                            "qname" to qname(target.qname),
                            "attributes" to target.attributes.sortedBy { it.qname.name }.map(::attribute),
                        )
                    }
            val canonical =
                mapOf(
                    "qname" to qname(action.qname),
                    "columns" to action.columns.sortedBy { it.name }.map { listOf(it.name, it.type, it.optional) },
                    "sinks" to action.sinks,
                    "transition" to action.transition?.let { listOf(it.attribute, it.to, it.toColumn, it.from) },
                    "set" to action.set?.let { listOf(it.attribute, it.column) },
                    "approval" to action.approval,
                    "deliverable" to action.deliverable,
                    "due" to action.due?.let { listOf(it.amount.toString(), it.unit) },
                    "onDue" to action.onDue?.let { listOf(it.action, entries(it.arguments)) },
                    "properties" to entries(action.properties.filter { it.first !in setOf("label", "dry_run") }),
                    "targets" to targets,
                )
            sha256("ttrp-action-dependency/1\n${json(canonical)}\n")
        }

    private fun attribute(a: Attribute): Any =
        mapOf(
            "qname" to qname(a.qname),
            "entity" to qname(a.entity),
            "type" to a.type,
            "key" to a.isKey,
            "nullable" to a.nullable,
            "aggregated" to a.aggregated,
            "length" to a.length,
            "precision" to a.precision,
            "states" to a.states,
            "transitions" to a.transitions,
        )

    private fun qname(q: QualifiedName): Any = listOf(q.schemaCode.name, q.namespace, q.name, q.`package`)

    // Sort object keys stably: duplicate keys keep their authored relative order and are never collapsed.
    private fun entries(pairs: List<Pair<String, AutoValue>>): Any =
        pairs.sortedBy { it.first }.map { listOf(it.first, value(it.second)) }

    private fun value(v: AutoValue): Any =
        when (v) {
            is AutoValue.Str -> listOf("str", v.raw)
            is AutoValue.Num -> listOf("num", v.text)
            is AutoValue.Bool -> listOf("bool", v.raw)
            is AutoValue.Null -> listOf("null")
            is AutoValue.Id -> listOf("id", v.parts)
            is AutoValue.Duration -> listOf("duration", v.amount.toString(), v.unit)
            is AutoValue.Tagged -> listOf("tagged", v.tag, v.value)
            is AutoValue.Call -> listOf("call", v.name, entries(v.entries.map { it.key to it.value }))
            is AutoValue.Star -> listOf("star")
            is AutoValue.Obj -> listOf("obj", entries(v.entries.map { it.key to it.value }))
            is AutoValue.ListV -> listOf("list", v.items.map(::value))
        }

    private fun json(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map(::json))
            is Map<*, *> ->
                JsonObject(
                    value.entries.sortedBy { it.key as String }.associate {
                        it.key as String to
                            json(it.value)
                    },
                )
            else -> error("unsupported canonical value")
        }

    private fun sha256(s: String): String =
        "sha256:" +
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
}
