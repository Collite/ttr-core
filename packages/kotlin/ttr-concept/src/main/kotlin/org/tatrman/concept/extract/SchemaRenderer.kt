// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.extract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.concept.ConceptChain
import org.tatrman.concept.ErType
import org.tatrman.concept.ParticipantPaths
import org.tatrman.concept.ResolvedCnModel
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import java.math.BigDecimal

/** Which model-generated annotation type to render (the extraction pack's type registry, `rendered_from.kind`). */
enum class TypeKind { STATEMENT, RECORD }

/** A type that cannot be rendered: an unknown entity, an ambiguous concept, a modifier off its bearer's entity, … */
class SchemaRenderException(
    val slug: String,
    message: String,
) : IllegalArgumentException(message)

/**
 * The model-type schema renderer of the extraction pipeline (⚑DX-P-2): JSON Schema 2020-12 for an er entity's
 * `Statement[<entity>]` (what one extracted assertion carries) and `Record.<entity>` (the consolidated subject-level
 * shape that feeds gold), rendered from the resolved cn + er model — never hand-written (P-4). A pure function of
 * [model]: no I/O, no clock; every object's keys sorted, every list in model order, so two renderings are byte-equal.
 *
 * What a rendering reads:
 * - the **entity's attributes** (keys excluded — a row's identity is the gold ETL's, not the extraction's), each typed
 *   from its er type: text → `string` (+ `maxLength`), int → `integer`, decimal → `number` (+ `multipleOf` for the
 *   scale), date / timestamp → `string` + `format`, boolean; a closed vocabulary (`states:` or value labels, ≤ 200
 *   members) → `enum`; a larger or data-backed one (a searchable member vocabulary) → `x-ttr-vocabulary: <attribute>`;
 *   an attribute holding a relation's single-column join → a `Ref` (`x-ttr-references: <entity>`);
 * - the **concept grounded on the entity** (a kind, role or event landing on it directly), whose phases make the
 *   `concept` enum, and whose qualities tagged `"modifier"` (`tags: ["modifier"]`, up the concept's chain — no grammar
 *   change) are the `modifiers`: the attributes that qualify an assertion (verification, certainty, temporal context)
 *   rather than describe the thing. A modifier must land on an attribute of the entity itself.
 *
 * Keys the schema itself adds are camelCase (Rule 7: a payload is camelCase JSON); attribute and modifier names are
 * the model's own.
 */
class SchemaRenderer(
    private val model: ResolvedCnModel,
) {
    private val er: ErSchema = model.model.schemas["er"] as? ErSchema ?: ErSchema()
    private val chain = ConceptChain(model.cn)

    /** The entity [ref] names — `review`, `er.entity.review` or `<package>.review` — or a [SchemaRenderException]. */
    fun entity(ref: String): Entity {
        val name = ref.removePrefix("er.entity.").substringAfterLast('.')
        val pkg = ref.removePrefix("er.entity.").substringBeforeLast('.', "")
        val hits = er.entities.values.filter { it.qname.name == name && (pkg.isEmpty() || it.qname.`package` == pkg) }
        return when (hits.size) {
            1 -> hits.single()
            0 -> throw SchemaRenderException(UNKNOWN_ENTITY, "unknown entity '$ref'")
            else -> throw SchemaRenderException(
                AMBIGUOUS_ENTITY,
                "'$ref' names ${hits.size} entities — qualify it with its package",
            )
        }
    }

    /** The JSON Schema of [kind] for the entity [ref] names. */
    fun render(
        ref: String,
        kind: TypeKind,
    ): JsonObject {
        val e = entity(ref)
        val name = e.qname.name
        val concept = conceptOf(e)
        val modifiers = concept?.let { modifiersOf(it, e) } ?: emptyList()
        val modifierAttrs = modifiers.map { it.second.simple }.toSet()
        val attributes = e.attributes.filter { !it.isKey && it.simple !in modifierAttrs }
        val record = kind == TypeKind.RECORD

        val defs = sortedMapOf<String, JsonElement>()
        val props = sortedMapOf<String, JsonElement>()
        props["attributes"] =
            group(
                attributes.associate { it.simple to attributeSchema(e, it, defs) },
                required = if (record) attributes.filterNot { it.nullable }.map { it.simple } else emptyList(),
            )
        if (concept != null) {
            props["concept"] = obj("type" to str("string"), "enum" to strings(conceptValues(concept)))
            props["modifiers"] =
                group(
                    modifiers.associate { (q, a) -> q.qname.name to modifierSchema(e, q, a) },
                    required =
                        if (record) {
                            modifiers
                                .filterNot {
                                    it.second.nullable
                                }.map { it.first.qname.name }
                        } else {
                            emptyList()
                        },
                )
        }
        val required: List<String>
        if (record) {
            props["evidence"] = obj("type" to str("array"), "minItems" to num(1), "items" to defRef("Evidence"))
            props["history"] = obj("type" to str("array"), "items" to defRef("HistoryEntry"))
            props["conflicts"] = obj("type" to str("array"), "items" to defRef("Conflict"))
            props["consolidationRun"] = obj("type" to str("string"), "minLength" to num(1))
            defs.putAll(RecordParts.defs)
            required = listOf("attributes", "consolidationRun", "evidence")
        } else {
            props["spans"] = obj("type" to str("array"), "minItems" to num(1), "items" to defRef("Span"))
            props["verbatim"] = obj("type" to str("string"), "minLength" to num(1))
            props["confidence"] = obj("type" to str("number"), "minimum" to num(0), "maximum" to num(1))
            defs["Span"] = SPAN
            required = listOf("attributes", "spans", "verbatim")
        }

        val top = sortedMapOf<String, JsonElement>()
        top["\$schema"] = str(JSON_SCHEMA)
        top["\$id"] = str("ttr:types/${fileStem(name, kind)}")
        top["title"] = str(if (record) "Record.$name" else "Statement[$name]")
        describe(e.descriptionLocalized.byLanguage, e.description)?.let { top["description"] = str(it) }
        top["type"] = str("object")
        top["additionalProperties"] = JsonPrimitive(false)
        top["required"] = strings(required.sorted())
        top["properties"] = JsonObject(props)
        top["\$defs"] = JsonObject(defs)
        top["x-ttr-entity"] = str("er.entity.$name")
        top["x-ttr-kind"] = str(kind.name.lowercase())
        concept?.let { top["x-ttr-concept"] = str(it.ref) }
        if (record) top["x-ttr-feedsGold"] = JsonPrimitive(true)
        return JsonObject(top)
    }

    /** The concept grounded on [e] itself — a kind, role or event — or null; two is a refusal. */
    fun conceptOf(e: Entity): Concept? {
        val target = "er.entity.${e.qname.name}"
        val hits =
            model.cn.concepts.values
                .filter { it.stereotype in BEARERS && it.erTarget == target && it.landingFrom == null }
                .sortedBy { it.ref }
        if (hits.size > 1) {
            throw SchemaRenderException(
                AMBIGUOUS_CONCEPT,
                "${hits.joinToString { "`${it.ref}`" }} all ground on `$target` — one concept per extraction entity",
            )
        }
        return hits.firstOrNull()
    }

    /** [c] and the phases specializing it, sorted — the values a statement's `concept` may name. */
    private fun conceptValues(c: Concept): List<String> =
        (
            listOf(c.ref) +
                model.cn.concepts.values
                    .filter { it.stereotype == "phase" && chain.chain(it).any { x -> x.qname == c.qname } }
                    .map { it.ref }
        ).distinct().sorted()

    /** [c]'s modifier qualities (tagged `"modifier"`, up its chain) and the attribute of [e] each lands on. */
    private fun modifiersOf(
        c: Concept,
        e: Entity,
    ): List<Pair<Concept, Attribute>> {
        val owners = chain.chain(c).map { it.qname }.toSet()
        return model.cn.concepts.values
            .filter { it.stereotype == "quality" && it.of in owners && MODIFIER_TAG in it.tags }
            .sortedBy { it.qname.name }
            .map { q ->
                val (on, attr) =
                    ParticipantPaths.attributeRef(q.erTarget)
                        ?: throw SchemaRenderException(
                            MODIFIER_NOT_AN_ATTRIBUTE,
                            "modifier `${q.ref}` lands on no er attribute (a modifier is an attribute of its bearer's entity)",
                        )
                if (on != e.qname.name) {
                    throw SchemaRenderException(
                        MODIFIER_OFF_ENTITY,
                        "modifier `${q.ref}` lands on `er.entity.$on.$attr`, not on `er.entity.${e.qname.name}`",
                    )
                }
                q to (
                    e.attributes.firstOrNull { it.simple == attr }
                        ?: throw SchemaRenderException(
                            MODIFIER_NOT_AN_ATTRIBUTE,
                            "`er.entity.$on.$attr` is no attribute",
                        )
                )
            }
    }

    private fun modifierSchema(
        e: Entity,
        q: Concept,
        a: Attribute,
    ): JsonObject {
        val base = valueSchema(e, a)
        return JsonObject(
            sortedMapOf<String, JsonElement>().apply {
                putAll(base)
                put("x-ttr-quality", str(q.ref))
            },
        )
    }

    private fun attributeSchema(
        e: Entity,
        a: Attribute,
        defs: MutableMap<String, JsonElement>,
    ): JsonObject {
        val target = referenced(e, a) ?: return valueSchema(e, a)
        defs["Ref"] = REF
        return obj(
            "\$ref" to str("#/\$defs/Ref"),
            "x-ttr-attribute" to str(attrRef(e, a)),
            "x-ttr-references" to str("er.entity.$target"),
        )
    }

    /** [a]'s value schema: the er type mapping, the vocabulary, the description, the attribute ref. */
    private fun valueSchema(
        e: Entity,
        a: Attribute,
    ): JsonObject {
        val m = sortedMapOf<String, JsonElement>()
        val type =
            ErType.of(a.type)
                ?: throw SchemaRenderException(
                    UNKNOWN_TYPE,
                    "`${attrRef(e, a)}` has type `${a.type}` — no JSON Schema type for it",
                )
        when (type) {
            ErType.TEXT -> {
                m["type"] = str("string")
                a.length?.let { m["maxLength"] = num(it) }
            }
            ErType.INTEGER -> m["type"] = str("integer")
            ErType.DECIMAL -> {
                m["type"] = str("number")
                a.precision?.let { m["multipleOf"] = JsonPrimitive(BigDecimal.ONE.movePointLeft(it)) }
            }
            ErType.BOOLEAN -> m["type"] = str("boolean")
            ErType.DATE -> {
                m["type"] = str("string")
                m["format"] = str("date")
            }
            ErType.TIMESTAMP -> {
                m["type"] = str("string")
                m["format"] = str("date-time")
            }
        }
        val closed = a.states.ifEmpty { a.valueLabels.keys.sorted() }
        when {
            closed.isNotEmpty() && closed.size <= CLOSED_LIMIT ->
                m["enum"] =
                    JsonArray(
                        closed.map { v ->
                            if (type ==
                                ErType.INTEGER
                            ) {
                                v.toLongOrNull()?.let(::num) ?: str(v)
                            } else {
                                str(v)
                            }
                        },
                    )
            closed.isNotEmpty() || a.search.indexed -> m["x-ttr-vocabulary"] = str(attrRef(e, a))
        }
        describe(a.descriptionLocalized.byLanguage, a.description)?.let { m["description"] = str(it) }
        m["x-ttr-attribute"] = str(attrRef(e, a))
        return JsonObject(m)
    }

    /** The entity [a]'s single-column relation join points at, if any (the first relation by name). */
    private fun referenced(
        e: Entity,
        a: Attribute,
    ): String? =
        er.relations.values
            .filter { it.fromEntity == e.qname && it.joinPairs.size == 1 && it.joinPairs.single().fromAttr == a.qname }
            .minByOrNull { it.qname.name }
            ?.toEntity
            ?.name

    private fun attrRef(
        e: Entity,
        a: Attribute,
    ) = "er.entity.${e.qname.name}.${a.simple}"

    companion object {
        const val JSON_SCHEMA = "https://json-schema.org/draft/2020-12/schema"
        const val MODIFIER_TAG = "modifier"
        const val CLOSED_LIMIT = 200

        const val UNKNOWN_ENTITY = "extract/unknown-entity"
        const val AMBIGUOUS_ENTITY = "extract/ambiguous-entity"
        const val AMBIGUOUS_CONCEPT = "extract/ambiguous-concept"
        const val MODIFIER_NOT_AN_ATTRIBUTE = "extract/modifier-not-an-attribute"
        const val MODIFIER_OFF_ENTITY = "extract/modifier-off-entity"
        const val UNKNOWN_TYPE = "extract/unknown-type"

        private val BEARERS = setOf("kind", "role", "event")

        /** The file stem of a rendered type: `Statement.review`, `Record.review`. */
        fun fileStem(
            entity: String,
            kind: TypeKind,
        ): String = (if (kind == TypeKind.RECORD) "Record." else "Statement.") + entity

        private val REF =
            obj(
                "type" to str("object"),
                "additionalProperties" to JsonPrimitive(false),
                "required" to strings(listOf("qname")),
                "properties" to
                    obj(
                        "qname" to obj("type" to str("string"), "minLength" to num(1)),
                        "display" to obj("type" to str("string")),
                    ),
                "description" to
                    str(
                        "A reference to a row of the referenced entity: its qname (key or code as the source " +
                            "names it) and the surface it was read from.",
                    ),
            )

        private val SPAN =
            obj(
                "type" to str("object"),
                "additionalProperties" to JsonPrimitive(false),
                "required" to strings(listOf("end", "start", "unitId")),
                "properties" to
                    obj(
                        "unitId" to obj("type" to str("integer")),
                        "start" to obj("type" to str("integer"), "minimum" to num(0)),
                        "end" to obj("type" to str("integer"), "minimum" to num(0)),
                    ),
                "description" to str("[start, end) in Unicode code points of the unit's NFC text."),
            )
    }
}

/** `Record.<entity>`'s consolidation parts: evidence with roles, valid-time history, conflict markers. */
internal object RecordParts {
    val ROLES = listOf("creates", "confirms", "restates", "updates", "contradicts", "refutes")

    val defs: Map<String, JsonElement> =
        mapOf(
            "Evidence" to
                obj(
                    "type" to str("object"),
                    "additionalProperties" to JsonPrimitive(false),
                    "required" to strings(listOf("annotationId", "role")),
                    "properties" to
                        obj(
                            "annotationId" to obj("type" to str("integer")),
                            "role" to obj("type" to str("string"), "enum" to strings(ROLES)),
                        ),
                ),
            "HistoryEntry" to
                obj(
                    "type" to str("object"),
                    "additionalProperties" to JsonPrimitive(false),
                    "required" to strings(listOf("annotationId", "attribute", "consolidationRun", "value")),
                    "description" to
                        str(
                            "A superseded value (valid-time): the record's earlier value of one attribute or modifier.",
                        ),
                    "properties" to
                        obj(
                            "attribute" to obj("type" to str("string")),
                            "value" to JsonObject(emptyMap()),
                            "asOf" to obj("type" to str("string"), "format" to str("date-time")),
                            "annotationId" to obj("type" to str("integer")),
                            "consolidationRun" to obj("type" to str("string")),
                        ),
                ),
            "Conflict" to
                obj(
                    "type" to str("object"),
                    "additionalProperties" to JsonPrimitive(false),
                    "required" to strings(listOf("attribute", "status", "values")),
                    "description" to str("Incompatible values for the same time, kept with a marker for review."),
                    "properties" to
                        obj(
                            "attribute" to obj("type" to str("string")),
                            "values" to
                                obj(
                                    "type" to str("array"),
                                    "minItems" to num(2),
                                    "items" to
                                        obj(
                                            "type" to str("object"),
                                            "additionalProperties" to JsonPrimitive(false),
                                            "required" to strings(listOf("annotationIds", "value")),
                                            "properties" to
                                                obj(
                                                    "value" to JsonObject(emptyMap()),
                                                    "annotationIds" to
                                                        obj(
                                                            "type" to str("array"),
                                                            "minItems" to num(1),
                                                            "items" to obj("type" to str("integer")),
                                                        ),
                                                ),
                                        ),
                                ),
                            "status" to obj("type" to str("string"), "enum" to strings(listOf("open", "resolved"))),
                        ),
                ),
        )
}

private val Attribute.simple: String get() = qname.name.substringAfterLast('.')

private fun describe(
    localized: Map<String, String>,
    plain: String,
): String? = (localized["en"] ?: plain).takeIf { it.isNotBlank() }

private fun str(s: String) = JsonPrimitive(s)

private fun num(n: Number) = JsonPrimitive(n)

private fun strings(xs: List<String>) = JsonArray(xs.map(::JsonPrimitive))

private fun defRef(name: String) = obj("\$ref" to str("#/\$defs/$name"))

/** An object whose keys iterate sorted (a TreeMap) — what makes a rendering's own order deterministic. */
private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(sortedMapOf(*pairs))

private fun group(
    properties: Map<String, JsonObject>,
    required: List<String>,
): JsonObject {
    val m =
        sortedMapOf<String, JsonElement>(
            "type" to str("object"),
            "additionalProperties" to JsonPrimitive(false),
            "properties" to JsonObject(properties.toSortedMap()),
        )
    if (required.isNotEmpty()) m["required"] = strings(required.sorted())
    return JsonObject(m)
}
