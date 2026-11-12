// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*

internal fun PlannedOp.targetPath(): List<String> {
    val target = value.getValue("to").jsonObject
    return target["path"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf(target.string("property"))
}

/** `values`: a foreign scalar → the canonical value its `mapping` names; missing or null → `default` if given. */
internal class ValuesOp(
    private val op: PlannedOp,
) : Op {
    override fun apply(
        plan: RecordPlan,
        builder: CanonicalBuilder,
        ignored: MutableList<Ignored>,
    ) {
        val path = op.targetPath()
        val mapping = op.value.getValue("mapping").jsonObject
        val default = op.value["default"]
        for (record in plan.records.filter { it.kind == op.scope }) {
            val source = op.at.resolve(record.source, includeNull = true)
            source?.let(plan.consumption::consume)
            if (source == null || source.value == JsonNull) {
                default?.let { builder.write(plan, record, path, it, source ?: record.source) }
                continue
            }
            // A default covers a missing, null or unmapped scalar, never a misauthored structure.
            val key =
                (source.value as? JsonPrimitive)?.content ?: throw ProfileException(
                    "ys/value-unmapped",
                    source.span(key = true),
                    "values needs a scalar at ${source.path}",
                )
            val mapped =
                mapping[key] ?: default ?: throw ProfileException(
                    "ys/value-unmapped",
                    source.span(key = true),
                    "Unmapped value $key",
                )
            builder.write(plan, record, path, mapped, source)
        }
    }
}

/**
 * `shape`: a literal template whose only substitutions are `{input: <pointer>}`, relative to the `at` selection
 * (✅D-MW-67 (b), recursive per ✅R251-B): a list element or object member whose input is missing is dropped, and so
 * is a container all of whose inputs are missing; the template yields nothing when every input is missing. No
 * expressions, no concatenation (P-8).
 */
internal class ShapeOp(
    private val op: PlannedOp,
) : Op {
    private object Missing

    override fun apply(
        plan: RecordPlan,
        builder: CanonicalBuilder,
        ignored: MutableList<Ignored>,
    ) {
        val path = op.targetPath()
        val template = op.value.getValue("template")
        for (record in plan.records.filter { it.kind == op.scope }) {
            val base = op.at.resolve(record.source, includeNull = true) ?: continue
            plan.consumption.open(base)

            // value is Missing for a missing input, and for a container with inputs beneath it none of which was
            // found (✅R251-B: dropped like a missing leaf, all the way up). A literal {} or [] has no inputs: kept.
            class Filled(
                val value: Any,
                val inputs: Boolean,
            )

            fun container(
                parts: List<Filled>,
                build: (List<Filled>) -> JsonElement,
            ): Filled {
                val inputs = parts.any { it.inputs }
                val present = parts.filter { it.value !== Missing }
                // Inputs beneath and none found: a literal sibling does not keep the container (✅R251-B).
                val emptied = inputs && present.none { it.inputs }
                return if (emptied) Filled(Missing, true) else Filled(build(present), inputs)
            }

            fun fill(node: JsonElement): Filled =
                when {
                    node is JsonObject && node.keys == setOf("input") -> {
                        val input = JsonPointer(node.string("input")).resolve(base, includeNull = true)
                        input?.let(plan.consumption::consume)
                        Filled(input?.value?.takeIf { it != JsonNull } ?: Missing, true)
                    }
                    node is JsonObject -> {
                        val members = node.entries.map { (key, value) -> key to fill(value) }
                        container(members.map { it.second }) { _ ->
                            JsonObject(
                                members
                                    .filter { it.second.value !== Missing }
                                    .associate { (key, filled) -> key to filled.value as JsonElement },
                            )
                        }
                    }
                    node is JsonArray ->
                        container(
                            node.map(::fill),
                        ) { present -> JsonArray(present.map { it.value as JsonElement }) }
                    else -> Filled(node, false)
                }
            val filled = fill(template)
            if (!filled.inputs) plan.consumption.consume(base)
            val shaped = filled.value
            if (base.value == JsonNull || shaped === Missing) continue
            builder.write(plan, record, path, shaped as JsonElement, base)
        }
    }
}

/**
 * `link`: a foreign reference → the qname of one record of the run or one sidecar entry, matched `by` sourceName, iri
 * or path, among the kinds the target property accepts (✅D-MW-67 (c′)). A list of references links each element.
 */
internal class LinkOp(
    private val op: PlannedOp,
    private val index: LinkIndex,
    private val ownPackage: String,
) : Op {
    override fun apply(
        plan: RecordPlan,
        builder: CanonicalBuilder,
        ignored: MutableList<Ignored>,
    ) {
        val path = op.targetPath()
        val by = op.value.string("by")
        val kind = ProfileVocabulary.referentKind(path)
        for (record in plan.records.filter { it.kind == op.scope }) {
            val source = op.at.resolve(record.source, includeNull = true) ?: continue
            plan.consumption.consume(source)
            val linked =
                when (source.value) {
                    JsonNull -> continue
                    is JsonArray -> JsonArray(source.entries().map { resolve(it, by, kind, plan, builder, record) })
                    else -> resolve(source, by, kind, plan, builder, record)
                }
            builder.write(plan, record, path, linked, source)
        }
    }

    private fun resolve(
        node: ForeignNode,
        by: String,
        kind: String?,
        plan: RecordPlan,
        builder: CanonicalBuilder,
        record: Record,
    ): JsonPrimitive {
        val reference =
            (node.value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ProfileException(
                "xm/link-unresolved",
                node.span(key = true),
                "link needs a string reference at ${node.path}",
            )
        val candidates = index.resolve(by, reference, kind)
        val accepted = kind?.let { " among $it records" } ?: ""
        val target =
            when (candidates.size) {
                0 -> throw ProfileException(
                    "xm/link-unresolved",
                    node.span(key = true),
                    "No record with $by $reference$accepted",
                )
                1 -> candidates.single()
                else -> throw ProfileException(
                    "xm/link-ambiguous",
                    node.span(key = true),
                    "$by $reference$accepted matches ${candidates.joinToString { it.qname }}",
                )
            }
        if (target.pkg.isNotEmpty() && target.pkg != ownPackage) builder.import(plan, record, target.pkg)
        return JsonPrimitive(target.qname)
    }
}
