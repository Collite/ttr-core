// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import org.tatrman.ttr.semantics.modelForKind
import org.tatrman.ttr.yaml.CanonicalYaml
import org.tatrman.ttr.yaml.CanonicalYamlException
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

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

    private val conceptKinds = setOf("kind", "subkind", "variant", "event", "phase", "quality", "relation_type")
    private val ruleSeparator = Regex("[^A-Za-z0-9_]+")
    private val slots = ConcurrentHashMap<Pair<String, String>, Optional<String>>()

    /**
     * ✅D-MW-67 (c′): the kind a `link` target accepts is the one its last path step names — a keyword property
     * (`er2db_entity.entity`, `binding.fk`) or a free-form step (`target.table`, `binding.target.column`). Any other
     * step accepts every kind.
     */
    fun referentKind(path: List<String>): String? = path.lastOrNull()?.takeIf { it in kinds }

    fun semanticsKind(kind: String): String = if (kind in conceptKinds) "concept" else kind

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
            ?.split(ruleSeparator)
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
    ): String =
        nestedSlotOrNull(parent, child) ?: throw ProfileException(
            "ys/profile-invalid",
            child.source.span(),
            "${child.kind} cannot nest unambiguously under ${parent.kind}",
        )

    fun nestedSlotOrNull(
        parent: Record,
        child: Record,
    ): String? {
        val bodyName = bodyName(parent)
        val childBody = bodyName(child)
        return slots
            .getOrPut(bodyName to childBody) {
                Optional.ofNullable(
                    run {
                        val body = bodies.getValue(bodyName).jsonObject
                        if (body.getValue("members").jsonArray.any { it.jsonObject.string("body") == childBody }) {
                            if (bodyName == "storageDef") "schemas" else "members"
                        } else {
                            body
                                .getValue("properties")
                                .jsonArray
                                .map { it.jsonObject }
                                .filter { property ->
                                    symbols(property.getValue("value")).any { reaches(it, childBody, mutableSetOf()) }
                                }.singleOrNull()
                                ?.string("key")
                        }
                    },
                )
            }.orElse(null)
    }
}

internal class CanonicalBuilder(
    private val plans: List<RecordPlan>,
    private val packages: Map<RecordPlan, String>,
) {
    private val properties = mutableMapOf<Record, MutableMap<String, JsonElement>>()
    private val children = mutableMapOf<Record, MutableMap<String, MutableList<Record>>>()
    private val origins = mutableMapOf<Record, MutableMap<String, ProfileSpan>>()
    private val imports = mutableMapOf<Pair<String, String>, MutableSet<String>>()

    init {
        val identities = mutableSetOf<List<String>>()
        plans.forEach { plan ->
            plan.records.forEach { record ->
                properties[record] = linkedMapOf()
                origins[record] = linkedMapOf()
                if (record.parent == null) {
                    val identity =
                        listOf(
                            packages.getValue(plan),
                            ProfileVocabulary.model(record.kind),
                            ProfileVocabulary.semanticsKind(record.kind),
                            record.id,
                        )
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
        if (path.size > 1 &&
            plan.nestedKinds(record).any { kind ->
                ProfileVocabulary.nestedSlotOrNull(record, Record(record.source, kind, record.id, record)) ==
                    path.first()
            }
        ) {
            children[record]?.get(path.first())?.forEach { write(plan, it, path.drop(1), value, source) }
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
        // The authored key: a diagnostic on the transformed value names it (contracts §3).
        origins.getValue(record)[canonicalPath] = source.span(key = true)
    }

    private fun group(
        plan: RecordPlan,
        record: Record,
    ): Pair<String, String> =
        packages.getValue(plan) to ProfileVocabulary.model(generateSequence(record) { it.parent }.last().kind)

    /** A resolved cross-package `link` imports the target package into the record's document. */
    fun import(
        plan: RecordPlan,
        record: Record,
        pkg: String,
    ) {
        imports.getOrPut(group(plan, record)) { sortedSetOf() } += "$pkg.*"
    }

    /** Overlay values bypass the conflict ledger: the overlay wins. Returns the transform's value, if any. */
    fun overlay(
        record: Record,
        property: String,
        value: JsonElement,
        origin: ProfileSpan,
    ): JsonElement? {
        if (children[record]?.containsKey(property) == true) {
            throw ProfileException(
                "ys/profile-invalid",
                origin,
                "An overlay cannot replace nested records at $property",
            )
        }
        val previous = properties.getValue(record).put(property, value)
        origins.getValue(record)["/${escapePointer(property)}"] = origin
        return previous
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
            val authored = mutableSetOf<String>()

            fun definition(
                record: Record,
                path: String,
                nested: Boolean,
            ): JsonObject {
                val propertyKey = if (ProfileVocabulary.storageMember(record)) "fields" else "properties"
                locations[path] = record.source.span()
                origins.getValue(record).forEach { (target, origin) ->
                    locations["$path/$propertyKey$target"] = origin
                    authored += "$path/$propertyKey$target"
                }
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
                    put("imports", JsonArray(imports[group].orEmpty().map(::JsonPrimitive)))
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
            // JSON string escaping is narrower than YAML's printable set.
            val json =
                buildString {
                    raw.toString().codePoints().forEach { cp ->
                        if (cp == 9 ||
                            cp == 10 ||
                            cp == 13 ||
                            cp in 0x20..0x7e ||
                            cp == 0x85 ||
                            cp in 0xa0..0xd7ff ||
                            cp in 0xe000..0xfffd ||
                            cp >= 0x10000
                        ) {
                            appendCodePoint(cp)
                        } else {
                            append("\\u" + cp.toString(16).padStart(4, '0'))
                        }
                    }
                }
            if (json.codePointCount(0, json.length) > 3_000_000) {
                throw ProfileException(
                    "ys/profile-invalid",
                    records.first().source.span(),
                    "Canonical document ${group.first}/${group.second} exceeds 3000000 code points",
                )
            }
            val file =
                records
                    .first()
                    .source.tree.file
            val canonical =
                try {
                    CanonicalYaml.read(json, file)
                } catch (error: CanonicalYamlException) {
                    // Contracts §3, without parsing again: the authored value at or above the failing path; else the
                    // first one written beneath it (an object-level rule such as dependentRequired); else the record.
                    val path = error.keyPath
                    val enclosing =
                        locations.entries
                            .filter { path == it.key || path.startsWith(it.key + "/") }
                            .maxByOrNull { it.key.length }
                    val origin =
                        enclosing?.takeIf { it.key in authored }?.value
                            ?: locations.entries
                                .firstOrNull {
                                    it.key in authored &&
                                        it.key.startsWith(
                                            "$path/",
                                        )
                                }?.value
                            ?: enclosing?.value
                            ?: records.first().source.span()
                    throw ProfileException("ys/profile-invalid", origin, error.detail)
                }
            EmittedDocument(canonical, locations)
        }
    }
}
