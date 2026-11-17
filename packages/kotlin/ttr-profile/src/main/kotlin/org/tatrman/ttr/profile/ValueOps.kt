// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*

internal fun PlannedOp.targetPath(): List<String> {
    val target = value.getValue("to").jsonObject
    return target["path"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf(target.string("property"))
}

/**
 * `values`: a foreign scalar → the canonical value its `mapping` names; missing or null → `default` if given. With the
 * `split` operand (✅D-MW-81, S5.2a; the schema then forbids `mapping`/`default`) a string → the list of its trimmed
 * non-empty parts: the separator is matched without its surrounding whitespace (`"; "` splits like `";"`), a blank
 * separator splits on whitespace runs; a non-string is `ys/split-not-a-string`; a missing or null input stays absent.
 */
internal class ValuesOp(
    private val op: PlannedOp,
) : Op {
    override fun apply(
        plan: RecordPlan,
        builder: CanonicalBuilder,
        ignored: MutableList<Ignored>,
    ) {
        val path = op.targetPath()
        val mapping = op.value["mapping"]?.jsonObject ?: JsonObject(emptyMap())
        val default = op.value["default"]
        val split = op.value.optionalString("split")
        for (record in plan.records.filter { it.kind == op.scope }) {
            val source = op.at.resolve(record.source, includeNull = true)
            source?.let(plan.consumption::consume)
            if (source == null || source.value == JsonNull) {
                default?.let { builder.write(plan, record, path, it, source ?: record.source) }
                continue
            }
            if (split != null) {
                builder.write(plan, record, path, split(source, split), source)
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

    private fun split(
        source: ForeignNode,
        separator: String,
    ): JsonArray {
        val text =
            (source.value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ProfileException(
                "ys/split-not-a-string",
                source.span(key = true),
                "split needs a string at ${source.path}",
            )
        val parts = separator.trim().let { if (it.isEmpty()) text.split(WHITESPACE) else text.split(it) }
        return JsonArray(parts.map(String::trim).filter(String::isNotEmpty).map(::JsonPrimitive))
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")
    }
}

/** A template input found nowhere — or a container whose every input was missing (✅R251-B). */
internal object Missing

/** [value] is a [JsonElement] or [Missing]; [inputs] says whether any `{input:}` lies beneath. */
internal class Filled(
    val value: Any,
    val inputs: Boolean,
)

/**
 * Fills a `shape` template — `{input: <pointer text>}` substitutions only (P-8), each resolved by [input] (null = missing)
 * and handed to [consume] when found. A list element or object member whose input is missing is dropped, and so is a
 * container that had inputs beneath it none of which was found (✅D-MW-67 (b), ✅R251-B); a literal `{}`/`[]` stays.
 * Shared by `shape` (one template per record) and `each` (one per list element).
 */
internal fun fillTemplate(
    template: JsonElement,
    input: (String) -> ForeignNode?,
    consume: (ForeignNode) -> Unit,
): Filled {
    fun <K> container(
        parts: List<Pair<K, Filled>>,
        build: (List<Pair<K, Filled>>) -> JsonElement,
    ): Filled {
        val inputs = parts.any { it.second.inputs }
        val present = parts.filter { it.second.value !== Missing }
        // Inputs beneath and none found: a literal sibling does not keep the container (✅R251-B).
        val emptied = inputs && present.none { it.second.inputs }
        return if (emptied) Filled(Missing, true) else Filled(build(present), inputs)
    }

    fun fill(node: JsonElement): Filled =
        when {
            node is JsonObject && node.keys == setOf("input") -> {
                val found = input(node.string("input"))
                found?.let(consume)
                Filled(found?.value?.takeIf { it != JsonNull } ?: Missing, true)
            }
            node is JsonObject ->
                container(node.entries.map { (key, value) -> key to fill(value) }) { present ->
                    JsonObject(present.associate { (key, filled) -> key to filled.value as JsonElement })
                }
            node is JsonArray ->
                container(node.map { Unit to fill(it) }) { present ->
                    JsonArray(present.map { it.second.value as JsonElement })
                }
            else -> Filled(node, false)
        }
    return fill(template)
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
            val filled =
                fillTemplate(
                    template,
                    { JsonPointer(it).resolve(base, includeNull = true) },
                    plan.consumption::consume,
                )
            if (!filled.inputs) plan.consumption.consume(base)
            val shaped = filled.value
            if (base.value == JsonNull || shaped === Missing) continue
            builder.write(plan, record, path, shaped as JsonElement, base)
        }
    }
}

/**
 * `each` (✅D-MW-81, S5.2a; P-8 amended to eleven operations): the list at `at` (relative to the record) → one `shape`
 * template filled per element, in order. An input `$item` or `$item/<pointer>` reads the element; a plain pointer reads
 * the record. An element whose template yields nothing is dropped. Without `key` the result is the list; with `key` (a
 * pointer within the element) it is an object keyed by each element's string there — a duplicate is
 * `ys/each-duplicate-key`, a missing (or non-string) one `ys/each-missing-key`, both at the element. A non-list is
 * `ys/each-not-a-list`; an empty list writes `[]`/`{}`; a missing or null `at` writes nothing. The list is consumed
 * whole. It walks the list as `link` does (`entries()`, one result per element) and fills as `shape` does
 * ([fillTemplate]); no expressions.
 */
internal class EachOp(
    private val op: PlannedOp,
) : Op {
    override fun apply(
        plan: RecordPlan,
        builder: CanonicalBuilder,
        ignored: MutableList<Ignored>,
    ) {
        val path = op.targetPath()
        val template = op.value.getValue("shape")
        val keyText = op.value.optionalString("key")
        val key = keyText?.let(::JsonPointer)
        for (record in plan.records.filter { it.kind == op.scope }) {
            val source = op.at.resolve(record.source, includeNull = true) ?: continue
            plan.consumption.consume(source)
            if (source.value == JsonNull) continue
            if (source.value !is JsonArray) {
                throw ProfileException(
                    "ys/each-not-a-list",
                    source.span(key = true),
                    "each needs a list at ${source.path}",
                )
            }
            val shaped =
                source.entries().map { element ->
                    element to
                        fillTemplate(
                            template,
                            { text ->
                                if (text == ITEM || text.startsWith("$ITEM/")) {
                                    JsonPointer(text.removePrefix(ITEM)).resolve(element, includeNull = true)
                                } else {
                                    JsonPointer(text).resolve(record.source, includeNull = true)
                                }
                            },
                            plan.consumption::consume,
                        ).value
                }
            val result =
                if (key == null) {
                    JsonArray(shaped.mapNotNull { (_, value) -> value as? JsonElement })
                } else {
                    val keyed = linkedMapOf<String, JsonElement>()
                    // An element whose template yields nothing is dropped before its key is checked (review-338 B2).
                    shaped.filter { (_, value) -> value is JsonElement }.forEach { (element, value) ->
                        val name =
                            (key.resolve(element)?.value as? JsonPrimitive)?.takeIf { it.isString }?.content
                                ?: throw ProfileException(
                                    "ys/each-missing-key",
                                    element.span(),
                                    "each needs a string key at ${element.path}$keyText",
                                )
                        if (name in keyed) {
                            throw ProfileException(
                                "ys/each-duplicate-key",
                                element.span(),
                                "Duplicate each key $name at ${element.path}",
                            )
                        }
                        keyed[name] = value as JsonElement
                    }
                    JsonObject(keyed)
                }
            builder.write(plan, record, path, result, source)
        }
    }

    private companion object {
        const val ITEM = "\$item"
    }
}

/**
 * `link`: a foreign reference → the qname of one record of the run or one sidecar entry, matched `by` sourceName, iri
 * or path, among the kinds the target property accepts (✅D-MW-67 (c′)) — or, where the property names none, the kind the
 * op's `kind` operand names (✅D-MW-72, S3.7b; a name that exists only as other kinds is `xm/link-kind-missing`). A list
 * of references links each element.
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
        val operand = op.value.optionalString("kind")
        val kind = operand ?: ProfileVocabulary.referentKind(path)
        for (record in plan.records.filter { it.kind == op.scope }) {
            val source = op.at.resolve(record.source, includeNull = true) ?: continue
            plan.consumption.consume(source)
            val linked =
                when (source.value) {
                    JsonNull -> continue
                    is JsonArray ->
                        JsonArray(
                            source.entries().map { resolve(it, by, kind, operand != null, plan, builder, record) },
                        )
                    else -> resolve(source, by, kind, operand != null, plan, builder, record)
                }
            builder.write(plan, record, path, linked, source)
        }
    }

    private fun resolve(
        node: ForeignNode,
        by: String,
        kind: String?,
        declared: Boolean,
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
        if (candidates.isEmpty() && declared) {
            val other = index.resolve(by, reference, null)
            if (other.isNotEmpty()) {
                throw ProfileException(
                    "xm/link-kind-missing",
                    node.span(key = true),
                    "$by $reference names no $kind record, only " + other.joinToString { "${it.qname} (${it.kind})" },
                )
            }
        }
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

/**
 * `join-pairs` (✅D-MW-77, S3.7b; P-8 amended to ten operations): a legacy join string of exactly the shape
 * `a.x = b.y [AND c.z = d.w …]` — two-part references, equality only, `AND`-joined (any case) — becomes
 * `[{from: {ref: er.entity.a.x}, to: {ref: er.entity.b.y}}, …]` in declaration order — the attribute ref form the loader
 * resolves (✅R298-A; the prefix is output only, the input stays two-part). Any other shape, a blank value included, is
 * `xm/join-pair-shape` at the value, naming the offending text. A structural read of one known form, not an expression parser.
 */
internal class JoinPairsOp(
    private val op: PlannedOp,
) : Op {
    override fun apply(
        plan: RecordPlan,
        builder: CanonicalBuilder,
        ignored: MutableList<Ignored>,
    ) {
        val path = op.targetPath()
        for (record in plan.records.filter { it.kind == op.scope }) {
            val source = op.at.resolve(record.source, includeNull = true) ?: continue
            plan.consumption.consume(source)
            if (source.value == JsonNull) continue
            builder.write(plan, record, path, pairs(source), source)
        }
    }

    private fun pairs(node: ForeignNode): JsonArray {
        val text =
            (node.value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw refused(node, "join-pairs needs a string `a.x = b.y [AND …]` at ${node.path}")
        if (text.isBlank()) throw refused(node, "join-pairs needs a non-empty `a.x = b.y [AND …]` at ${node.path}")
        return JsonArray(
            text.split(AND).map { chunk ->
                val match =
                    PAIR.matchEntire(chunk) ?: throw refused(
                        node,
                        "'${chunk.trim().ifEmpty {
                            "AND"
                        }}' in join '$text' is not `a.x = b.y` (two-part references, equality only)",
                    )
                buildJsonObject {
                    putJsonObject("from") { put("ref", ATTRIBUTE_REF + match.groupValues[1]) }
                    putJsonObject("to") { put("ref", ATTRIBUTE_REF + match.groupValues[2]) }
                }
            },
        )
    }

    private fun refused(
        node: ForeignNode,
        detail: String,
    ) = ProfileException("xm/join-pair-shape", node.span(), detail)

    private companion object {
        /** ✅R298-A: `a.x` → `er.entity.a.x`, the attribute qname form `Reference.toQname` resolves (ttr-gen writes it too). */
        const val ATTRIBUTE_REF = "er.entity."
        val AND = Regex("""\s+AND\s+|\s+AND\s*$""", RegexOption.IGNORE_CASE)
        private const val PART = """[\p{L}\p{N}_]+"""
        val PAIR = Regex("""\s*($PART\.$PART)\s*=\s*($PART\.$PART)\s*""")
    }
}
