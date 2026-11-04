// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import org.tatrman.ttr.semantics.modelForKind
import org.tatrman.ttr.yaml.CanonicalYaml
import org.tatrman.ttr.yaml.CanonicalYamlException
import org.tatrman.ttr.yaml.YamlData

/** Grammar metadata supplies body membership; the existing semantics layer supplies model membership. */
internal object ProfileVocabulary {
    private val metadata =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.getResourceAsStream("/schemas/canonical-metadata.json"))
                    .bufferedReader()
                    .use { it.readText() },
            ).jsonObject
    private val kinds =
        (metadata.getValue("nestedKinds").jsonArray + metadata.getValue("kinds").jsonArray)
            .associate { it.jsonObject.string("kind") to it.jsonObject.string("body") }
    private val bodies = metadata.getValue("bodies").jsonObject
    private val rules = metadata.getValue("rules").jsonObject

    fun model(kind: String): String {
        val ast =
            when (kind) {
                "domain" -> "mdDomain"
                "map" -> "mdMap"
                "kind", "subkind", "variant", "event", "phase", "quality", "relation_type" -> "concept"
                "term", "pattern", "example" -> return "lexicon"
                // CN binding keywords are already the semantics layer's kind keys.
                else -> if (kind.startsWith("cn2")) kind else camelCase(kind)
            }
        return modelForKind(ast)
    }

    private fun camelCase(kind: String) =
        kind
            .split('_')
            .mapIndexed { index, word -> if (index == 0) word else word.replaceFirstChar { it.uppercase() } }
            .joinToString("")

    private fun symbols(value: JsonElement): List<String> =
        when (value) {
            is JsonObject -> value["symbol"]?.jsonPrimitive?.content?.let(::listOf) ?: value.values.flatMap(::symbols)
            is JsonArray -> value.flatMap(::symbols)
            else -> emptyList()
        }

    private fun reaches(
        rule: String,
        body: String,
        seen: MutableSet<String>,
    ): Boolean {
        if (rule == body) return true
        if (!seen.add(rule)) return false
        return rules[rule]
            ?.jsonPrimitive
            ?.content
            ?.split(Regex("[^A-Za-z0-9_]+"))
            ?.any { it != rule && it.firstOrNull()?.isLowerCase() == true && reaches(it, body, seen) } == true
    }

    private fun bodyName(record: Record): String = member(record)?.string("body") ?: kinds.getValue(record.kind)

    fun member(record: Record): JsonObject? =
        record.parent?.let { parent ->
            bodies
                .getValue(bodyName(parent))
                .jsonObject
                .getValue("members")
                .jsonArray
                .map { it.jsonObject }
                .singleOrNull { it.string("kind") == record.kind }
        }

    fun storageMember(record: Record): Boolean = record.parent?.let { bodyName(it) == "storageDef" } == true

    fun nestedSlot(
        parent: Record,
        child: Record,
    ): String {
        val bodyName = bodyName(parent)
        val body = bodies.getValue(bodyName).jsonObject
        val childBody = bodyName(child)
        if (body.getValue("members").jsonArray.any { it.jsonObject.string("body") == childBody }) {
            return if (bodyName == "storageDef") "schemas" else "members"
        }
        val properties =
            body.getValue("properties").jsonArray.map { it.jsonObject }.filter { property ->
                symbols(property.getValue("value")).any { reaches(it, childBody, mutableSetOf()) }
            }
        if (properties.size == 1) return properties.single().string("key")
        throw ProfileException(
            "ys/profile-invalid",
            child.source.span(),
            "${child.kind} cannot nest unambiguously under ${parent.kind}",
        )
    }
}

internal class CanonicalBuilder(
    private val plans: List<RecordPlan>,
    private val packages: Map<RecordPlan, String>,
) {
    private val properties = mutableMapOf<Record, MutableMap<String, JsonElement>>()
    private val children = mutableMapOf<Record, MutableMap<String, MutableList<Record>>>()
    private val origins = mutableMapOf<Record, MutableMap<String, ProfileSpan>>()

    init {
        val identities = mutableSetOf<List<String>>()
        plans.forEach { plan ->
            plan.records.forEach { record ->
                properties[record] = linkedMapOf()
                origins[record] = linkedMapOf()
                if (record.parent == null) {
                    val identity =
                        listOf(packages.getValue(plan), ProfileVocabulary.model(record.kind), record.kind, record.id)
                    if (!identities.add(
                            identity,
                        )
                    ) {
                        throw ProfileException(
                            "ys/profile-conflict",
                            record.source.span(key = true),
                            "Repeated canonical definition ${identity.joinToString(".")}",
                        )
                    }
                } else {
                    val slot = ProfileVocabulary.nestedSlot(record.parent, record)
                    val siblings =
                        children
                            .getOrPut(record.parent) { linkedMapOf() }
                            .getOrPut(slot) { mutableListOf() }
                    if (siblings.any { it.kind == record.kind && it.id == record.id }) {
                        throw ProfileException(
                            "ys/profile-conflict",
                            record.source.span(key = true),
                            "Repeated nested identity ${record.id}",
                        )
                    }
                    siblings.add(record)
                }
            }

            fun register(
                record: Record,
                root: Record,
                prefix: List<String>,
            ) {
                plan.conflicts.register(record, root, prefix)
                // Reserve identity as well as properties: replacing a nested collection would lose authored records.
                plan.conflicts.claim(record, listOf("@id"), record.source)
                children[record]?.forEach { (slot, nested) ->
                    nested.forEachIndexed { i, child ->
                        register(child, root, prefix + slot + i.toString())
                    }
                }
            }
            plan.records.filter { it.parent == null }.forEach { register(it, it, emptyList()) }
        }
    }

    fun write(
        plan: RecordPlan,
        record: Record,
        path: List<String>,
        value: JsonElement,
        source: ForeignNode,
    ) {
        val nested = children[record]?.get(path.first())
        if (nested != null && path.size > 1) {
            nested.forEach { write(plan, it, path.drop(1), value, source) }
            return
        }
        plan.conflicts.claim(record, path, source)

        fun insert(
            current: JsonObject,
            rest: List<String>,
        ): JsonObject {
            val key = rest.first()
            val next =
                if (rest.size ==
                    1
                ) {
                    value
                } else {
                    insert(current[key] as? JsonObject ?: JsonObject(emptyMap()), rest.drop(1))
                }
            return JsonObject(current + (key to next))
        }
        val updated = insert(JsonObject(properties.getValue(record)), path)
        properties.getValue(record).apply {
            clear()
            putAll(updated)
        }
        val canonicalPath = path.joinToString("/", prefix = "/") { escapePointer(it) }
        origins.getValue(record)[canonicalPath] = source.span()
    }

    fun documents(): List<EmittedDocument> {
        val groups = linkedMapOf<Pair<String, String>, MutableList<Record>>()
        plans.forEach { plan ->
            plan.records.filter { it.parent == null }.forEach { record ->
                groups
                    .getOrPut(
                        packages.getValue(plan) to ProfileVocabulary.model(record.kind),
                    ) { mutableListOf() }
                    .add(record)
            }
        }
        return groups.map { (group, records) ->
            val locations = linkedMapOf<String, ProfileSpan>()
            val recordLocations = linkedMapOf<String, ProfileSpan>()

            fun definition(
                record: Record,
                path: String,
                nested: Boolean,
            ): JsonObject {
                val propertyKey = if (ProfileVocabulary.storageMember(record)) "fields" else "properties"
                locations[path] = record.source.span()
                recordLocations[path] = record.source.span()
                origins.getValue(record).forEach { (target, origin) -> locations["$path/$propertyKey$target"] = origin }
                val props = LinkedHashMap(properties.getValue(record))
                children[record]?.forEach { (slot, items) ->
                    props[slot] =
                        JsonArray(
                            items.mapIndexed { i, child ->
                                definition(child, "$path/$propertyKey/${escapePointer(slot)}/$i", nested = true)
                            },
                        )
                }
                val member = ProfileVocabulary.member(record)
                return buildJsonObject {
                    if (!nested ||
                        (member != null && !ProfileVocabulary.storageMember(record))
                    ) {
                        put("kind", record.kind)
                    }
                    if (member?.get("named")?.jsonPrimitive?.boolean != false) put("id", record.id)
                    put(propertyKey, JsonObject(props))
                }
            }
            val raw =
                buildJsonObject {
                    put("ttr", GrammarRange.runningGrammar)
                    put("package", group.first)
                    put("imports", JsonArray(emptyList()))
                    put("model", group.second)
                    put(
                        "defs",
                        JsonArray(
                            records.mapIndexed {
                                i,
                                record,
                                ->
                                definition(record, "/defs/$i", nested = false)
                            },
                        ),
                    )
                }
            val json = raw.toString()
            val file =
                records
                    .first()
                    .source.tree.file
            val canonical =
                try {
                    CanonicalYaml.read(json, file)
                } catch (error: CanonicalYamlException) {
                    // The bridge diagnoses generated JSON; map that offset back to the closest authored record.
                    val data = YamlData.read(json, file)
                    val path =
                        data.paths
                            .filter { candidate ->
                                val span = data.source(candidate)
                                error.column - 1 in span.offsetStart until span.offsetEnd
                            }.maxByOrNull { it.length } ?: ""
                    val origin =
                        recordLocations.entries
                            .filter { path == it.key || path.startsWith(it.key + "/") }
                            .maxByOrNull { it.key.length }
                            ?.value ?: records.first().source.span()
                    throw ProfileException("ys/profile-invalid", origin, error.detail)
                }
            EmittedDocument(canonical, locations)
        }
    }
}
