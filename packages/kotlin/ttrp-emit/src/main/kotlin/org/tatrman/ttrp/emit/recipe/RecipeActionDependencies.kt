// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttrp.bundle.RecipeApprovalDependencies
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.recipe.RecipeModel
import org.tatrman.ttrp.resolve.ActionResolution
import org.tatrman.ttrp.resolve.AutomationScope
import org.tatrman.ttrp.resolve.TargetResolution
import org.tatrman.ttr.metadata.world.AutomationFingerprints

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
        val scope = plan.frontendReport?.automationScope ?: return null
        val names =
            model.wirings
                .map { it.action }
                .distinct()
                .sorted()
        val actions =
            names
                .mapNotNull { name ->
                    (scope.action(name) as? ActionResolution.Resolved)?.action?.let { name to it }
                }.toMap()
        if (actions.values.none(AutomationScope::hasDefaults)) return null
        check(actions.size == names.size) { "checker accepted unresolved or ambiguous wired actions" }
        return RecipeApprovalDependencies(
            version = 1,
            imports =
                scope.imports
                    .map { it.raw }
                    .distinct()
                    .sorted(),
            textBase = textBase,
            actions = fingerprints(actions, scope::target),
        )
    }

    sealed interface Verification {
        data object Ok : Verification

        data class Stale(
            val action: String,
        ) : Verification

        data class Unresolved(
            val ref: String,
        ) : Verification

        data class Ambiguous(
            val ref: String,
        ) : Verification

        data class Malformed(
            val reason: String,
        ) : Verification
    }

    private class EvidenceException(
        message: String,
    ) : IllegalArgumentException(message)

    private class TargetException(
        val result: Verification,
    ) : IllegalArgumentException(
            when (result) {
                is Verification.Unresolved -> "unresolved action target `${result.ref}`"
                is Verification.Ambiguous -> "ambiguous action target `${result.ref}`"
                else -> "invalid action target: $result"
            },
        )

    /** Validate evidence without hashing; callers can reject its format before reconstructing its scope. */
    fun validate(evidence: RecipeApprovalDependencies): Verification.Malformed? =
        when {
            evidence.version != 1 -> Verification.Malformed("unsupported approval evidence version ${evidence.version}")
            !hash.matches(evidence.textBase) -> Verification.Malformed("invalid approval evidence textBase")
            evidence.actions.isEmpty() || evidence.actions.any { it.key.isBlank() || !hash.matches(it.value) } ->
                Verification.Malformed("invalid action approval evidence")
            evidence.imports.any { it.isBlank() || it.split('.').any(String::isBlank) } ->
                Verification.Malformed("invalid approval evidence import scope")
            evidence.imports != evidence.imports.distinct().sorted() ->
                Verification.Malformed("approval evidence imports must be sorted and unique")
            else -> null
        }

    /** The text/2 binding includes the checked import scope; changing scope cannot reuse old evidence. */
    fun text(evidence: RecipeApprovalDependencies): String {
        validate(evidence)?.let { throw EvidenceException(it.reason) }
        val dependencies = mapOf("actions" to evidence.actions, "imports" to evidence.imports)
        return AutomationFingerprints.sha256("ttrp-recipe-text/2\n${evidence.textBase}\n${json(dependencies)}\n")
    }

    /** Caller supplies exactly the manifest wiring names, resolved in its recorded, pinned metadata scope. */
    fun verify(
        evidence: RecipeApprovalDependencies?,
        approvedText: String,
        actions: Map<String, AutomationAction>,
        target: (String) -> TargetResolution,
    ): Verification {
        if (evidence == null) return Verification.Malformed("action approval evidence is missing")
        val bound =
            try {
                text(evidence)
            } catch (e: EvidenceException) {
                return Verification.Malformed(requireNotNull(e.message))
            }
        if (bound != approvedText) return Verification.Malformed("approval evidence is not bound to recipe text/2")
        if (evidence.actions.keys !=
            actions.keys
        ) {
            return Verification.Malformed("action approval evidence names differ from wiring")
        }
        val actual =
            try {
                fingerprints(actions, target)
            } catch (e: TargetException) {
                return e.result
            }
        val stale = evidence.actions.keys.firstOrNull { evidence.actions[it] != actual[it] }
        return if (stale == null) Verification.Ok else Verification.Stale(stale)
    }

    fun matches(
        evidence: RecipeApprovalDependencies?,
        approvedText: String,
        actions: Map<String, AutomationAction>,
        target: (String) -> TargetResolution,
    ): Boolean = verify(evidence, approvedText, actions, target) == Verification.Ok

    fun fingerprints(
        actions: Map<String, AutomationAction>,
        target: (String) -> TargetResolution,
    ): Map<String, String> =
        actions.toSortedMap().mapValues { (_, action) ->
            val resolved =
                AutomationScope.targetsOf(action, target).values.map { result ->
                    when (result) {
                        is TargetResolution.Resolved -> result
                        is TargetResolution.Unresolved -> throw TargetException(Verification.Unresolved(result.ref))
                        is TargetResolution.Ambiguous -> throw TargetException(Verification.Ambiguous(result.ref))
                    }
                }
            val targets =
                resolved.groupBy { it.entity.qname.name }.toSortedMap().mapValues { (_, refs) ->
                    val entity = refs.first().entity
                    mapOf(
                        "qname" to qname(entity.qname),
                        "attributes" to entity.attributes.sortedBy { it.qname.name }.map(::attribute),
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
            AutomationFingerprints.sha256("ttrp-action-dependency/1\n${json(canonical)}\n")
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
}
