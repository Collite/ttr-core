// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.tatrman.ttr.parser.loader.TtrId
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** One reason a candidate was refused: where ([path], a JSON pointer), which [rule], in words. */
data class CandidateViolation(
    val path: String,
    val rule: String,
    val message: String,
)

/** Read, or refused with every violation found. Never both, never neither. */
sealed interface CandidateLoad {
    data class Ok(
        val candidate: CnCandidate,
    ) : CandidateLoad

    data class Rejected(
        val violations: List<CandidateViolation>,
    ) : CandidateLoad {
        init {
            require(violations.isNotEmpty()) { "a rejection must carry at least one violation" }
        }
    }
}

/**
 * CN-P5 S5.2 T1 — `cn-candidate.v1` read and written. The packaged `schemas/cn-candidate.v1.schema.json` is the
 * published contract — what a producer outside the JVM validates against; this is the JVM enforcement of the same rules
 * (the store's `submit`, S5.3), hand-written so ttr-concept ships no JSON-schema stack. `CandidateSchemaSpec` runs every
 * fixture through both and fails if they disagree.
 *
 * Four rules are Kotlin-only, because JSON Schema cannot say them: a timestamp must be a real instant (`2026-02-30`
 * matches the pattern), a span's end must exceed its start (arithmetic between two numbers in one string), a term
 * cannot name its own candidate as the concept it waits for, and a name the promotion writes as model text (a concept's
 * `name`, a relation's `type_name`, a participant's `label`) must be a grammar `id` — not one of the words `TTR.g4`
 * reserves (`date`, `type`, `target`, …), decided by the grammar's own rule ([TtrId.isId], review-133 ⚑R133-a).
 *
 * **Every violation is reported**, not the first — a refused submission should be fixable in one round.
 */
object CnCandidates {
    private val pretty = Json { prettyPrint = true }

    fun parse(json: String): CandidateLoad {
        val element =
            try {
                Json.parseToJsonElement(json)
            } catch (e: SerializationException) {
                return CandidateLoad.Rejected(listOf(CandidateViolation("", "json", "not JSON: ${e.message}")))
            }
        return read(element)
    }

    fun read(element: JsonElement): CandidateLoad {
        val reader = Reader()
        val candidate = reader.candidate(element)
        return if (reader.violations.isEmpty() && candidate != null) {
            CandidateLoad.Ok(candidate)
        } else {
            CandidateLoad.Rejected(reader.violations.ifEmpty { listOf(CandidateViolation("", "type", "not read")) })
        }
    }

    /** The canonical JSON: the schema's key order, label locales sorted, absent optionals left out. */
    fun toJson(c: CnCandidate): JsonObject =
        buildJsonObject {
            put("id", c.id)
            put("proposal", proposal(c.proposal))
            put("provenance", provenance(c.provenance))
            put("status", c.status.word)
            c.resolution?.let { r ->
                put(
                    "resolution",
                    buildJsonObject {
                        put("by", r.by)
                        put("at", r.at)
                        r.canonRef?.let { put("canon_ref", it) }
                        r.note?.let { put("note", it) }
                    },
                )
            }
        }

    /** [toJson], pretty-printed, one trailing newline — the store's byte-stable copy. */
    fun render(c: CnCandidate): String = pretty.encodeToString(JsonElement.serializer(), toJson(c)) + "\n"

    private fun proposal(p: Proposal): JsonObject =
        buildJsonObject {
            when (p) {
                is Proposal.Concept ->
                    put(
                        "concept",
                        buildJsonObject {
                            put("stereo", p.stereo.word)
                            put("name", p.name)
                            if (p.label.isNotEmpty()) {
                                put("label", buildJsonObject { p.label.toSortedMap().forEach { (k, v) -> put(k, v) } })
                            }
                            if (p.isA.isNotEmpty()) put("is_a", strings(p.isA))
                            if (p.involves.isNotEmpty()) {
                                put(
                                    "involves",
                                    buildJsonArray {
                                        for (i in p.involves) {
                                            add(
                                                buildJsonObject {
                                                    put("concept", i.concept)
                                                    i.label?.let { put("label", it) }
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                            p.of?.let { put("of", it) }
                            p.event?.let { put("event", it) }
                            p.definitionText?.let { put("definition_text", it) }
                            p.basis?.let { put("basis", it.word) }
                            p.suggestedGrounding?.let { put("suggested_grounding", it) }
                            p.erRendering?.let { put("er_rendering", it) }
                        },
                    )
                is Proposal.Term ->
                    put(
                        "term",
                        buildJsonObject {
                            p.targetQname?.let { put("target_qname", it) }
                            p.proposedConceptRef?.let { put("proposed_concept_ref", it) }
                            put("locale", p.locale)
                            put("forms", strings(p.forms))
                            p.default?.let { put("default", it) }
                        },
                    )
                is Proposal.Relation ->
                    put(
                        "relation",
                        buildJsonObject {
                            put("type_name", p.typeName)
                            put("from", p.from)
                            put("to", p.to)
                        },
                    )
            }
        }

    private fun provenance(p: Provenance): JsonObject =
        buildJsonObject {
            put("producer", p.producer)
            p.sourceRef?.let { put("source_ref", it) }
            put(
                "confidence",
                buildJsonObject {
                    put("value", p.confidence.value)
                    put("producer", p.confidence.producer)
                    put("method", p.confidence.method)
                },
            )
            p.evidence?.let { e ->
                put(
                    "evidence",
                    buildJsonObject {
                        e.questionCount?.let { put("question_count", it) }
                        e.documentCount?.let { put("document_count", it) }
                        put(
                            "examples",
                            buildJsonArray {
                                for (x in e.examples) {
                                    add(
                                        buildJsonObject {
                                            put("kind", x.kind.word)
                                            put("ref", x.ref)
                                            put("text", x.text)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
            put("created_at", p.createdAt)
        }

    private fun strings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}

/** The schema's `$defs`, as the reader checks them. */
private object Shapes {
    val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    val TIMESTAMP =
        Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$")
    val NAME = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    val CN_REF = Regex("^cn\\.[A-Za-z][A-Za-z0-9_]*$")
    val LOCALE = Regex("^[a-z]{2,3}$")
    val LINE = Regex("^[^\\u0000-\\u001f]+$")
    val GROUNDING = Regex("^(er|md)(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    val PRODUCER = Regex("^[a-z][a-z0-9-]*(:[A-Za-z0-9._@-]+)?$")

    /**
     * Whitespace, as the schema spells it — one explicit set, never `\s` (whose meaning differs between the JDK,
     * ECMA-262 and Python validators): a definition made only of these is blank.
     */
    val BLANK: Set<Char> =
        (
            "\t\n\u000b\u000c\r \u0085\u00a0\u1680\u2028\u2029\u202f\u205f\u3000\ufeff" +
                ('\u2000'..'\u200a').joinToString("")
        ).toSet()
    val CANON_REF = Regex("^[0-9a-f]{7,64}:(cn|lexicon)\\.[A-Za-z][A-Za-z0-9_]*$")

    /** Which concept fields each stereotype refuses (cn contracts §1.1). */
    val FORBIDDEN: Map<Stereo, Set<String>> =
        mapOf(
            Stereo.KIND to setOf("of", "event", "involves", "definition_text", "basis", "er_rendering"),
            Stereo.ROLE to setOf("involves", "definition_text", "basis", "er_rendering"),
            Stereo.EVENT to setOf("of", "event", "definition_text", "basis", "er_rendering"),
            Stereo.PHASE to setOf("event", "involves"),
            Stereo.QUALITY to setOf("event", "involves", "basis", "er_rendering"),
        )
    val REQUIRED: Map<Stereo, Set<String>> =
        mapOf(Stereo.ROLE to setOf("event"), Stereo.PHASE to setOf("of"), Stereo.QUALITY to setOf("of"))
}

private class Reader {
    val violations = mutableListOf<CandidateViolation>()

    private fun fail(
        path: String,
        rule: String,
        message: String,
    ) {
        violations += CandidateViolation(path, rule, message)
    }

    fun candidate(e: JsonElement): CnCandidate? {
        val o =
            obj(
                e,
                "",
                setOf("id", "proposal", "provenance", "status", "resolution"),
                setOf("id", "proposal", "provenance", "status"),
            )
                ?: return null
        val id = string(o, "id", "", Shapes.UUID, "a lower-case UUID")
        val proposal = o["proposal"]?.let { proposal(it, "/proposal") }
        val provenance = o["provenance"]?.let { provenance(it, "/provenance") }
        val status = enumOf(o, "status", "", Status.entries, Status::word)
        val resolution = o["resolution"]?.let { resolution(it, "/resolution") }
        if (status != null) statusRules(status, o["resolution"] as? JsonObject, o.containsKey("resolution"))
        if (proposal is Proposal.Term && id != null && proposal.proposedConceptRef == id) {
            fail(
                "/proposal/term/proposed_concept_ref",
                "self-reference",
                "a term cannot wait for its own candidate",
            )
        }
        if (violations.isNotEmpty()) return null
        return CnCandidate(id!!, proposal!!, provenance!!, status!!, resolution)
    }

    private fun statusRules(
        status: Status,
        resolution: JsonObject?,
        hasResolution: Boolean,
    ) {
        when (status) {
            Status.PROPOSED ->
                if (hasResolution) {
                    fail(
                        "/resolution",
                        "status-resolution",
                        "a proposed candidate has no resolution yet",
                    )
                }
            Status.REJECTED, Status.PROMOTED -> {
                if (!hasResolution) {
                    fail("/resolution", "status-resolution", "a ${status.word} candidate carries its resolution")
                    return
                }
                val canon = resolution?.containsKey("canon_ref") ?: return
                if (status == Status.PROMOTED && !canon) {
                    fail("/resolution/canon_ref", "status-resolution", "a promotion records its canon_ref")
                }
                if (status == Status.REJECTED && canon) {
                    fail("/resolution/canon_ref", "status-resolution", "a rejection has no canon_ref")
                }
            }
        }
    }

    private fun proposal(
        e: JsonElement,
        path: String,
    ): Proposal? {
        val o = obj(e, path, setOf("concept", "term", "relation"), emptySet()) ?: return null
        val kinds = o.keys.intersect(setOf("concept", "term", "relation"))
        if (o.size != 1 || kinds.size != 1) {
            fail(path, "one-of", "a proposal is exactly one of concept, term, relation")
            return null
        }
        return when (kinds.single()) {
            "concept" -> concept(o.getValue("concept"), "$path/concept")
            "term" -> term(o.getValue("term"), "$path/term")
            else -> relation(o.getValue("relation"), "$path/relation")
        }
    }

    private fun concept(
        e: JsonElement,
        path: String,
    ): Proposal.Concept? {
        val fields =
            setOf(
                "stereo",
                "name",
                "label",
                "is_a",
                "involves",
                "of",
                "event",
                "definition_text",
                "basis",
                "suggested_grounding",
                "er_rendering",
            )
        val o = obj(e, path, fields, setOf("stereo", "name")) ?: return null
        val stereo = enumOf(o, "stereo", path, Stereo.entries, Stereo::word)
        val name = id(o, "name", path)
        val label = o["label"]?.let { label(it, "$path/label") }
        val isA = o["is_a"]?.let { refList(it, "$path/is_a") }
        val involves = o["involves"]?.let { participants(it, "$path/involves") }
        val of = string(o, "of", path, Shapes.CN_REF, "a concept ref (cn.X)")
        val event = string(o, "event", path, Shapes.CN_REF, "a concept ref (cn.X)")
        val definition = string(o, "definition_text", path)
        if (definition != null && (definition.all { it in Shapes.BLANK } || definition.contains("\"\"\""))) {
            fail("$path/definition_text", "pattern", "a definition expression: not blank, no triple quote")
        }
        val basis = enumOf(o, "basis", path, Basis.entries, Basis::word)
        val grounding = string(o, "suggested_grounding", path, Shapes.GROUNDING, "an md or er ref")
        val er = string(o, "er_rendering", path)
        if (er != null && er.isEmpty()) fail("$path/er_rendering", "pattern", "not empty")
        if (stereo != null) {
            for (k in Shapes.FORBIDDEN.getValue(stereo).intersect(o.keys)) {
                fail("$path/$k", "stereotype-field", "a ${stereo.word} takes no '$k'")
            }
            for (k in Shapes.REQUIRED[stereo].orEmpty() - o.keys) {
                fail("$path/$k", "required", "a ${stereo.word} requires '$k'")
            }
            if ((stereo == Stereo.PHASE || stereo == Stereo.QUALITY) &&
                o.containsKey("definition_text") &&
                o.containsKey("suggested_grounding")
            ) {
                fail(path, "one-of", "a ${stereo.word} is defined or grounded, never both")
            }
        }
        if (stereo == null || name == null) return null
        return Proposal.Concept(
            stereo = stereo,
            name = name,
            label = label.orEmpty(),
            isA = isA.orEmpty(),
            involves = involves.orEmpty(),
            of = of,
            event = event,
            definitionText = definition,
            basis = basis,
            suggestedGrounding = grounding,
            erRendering = er,
        )
    }

    private fun term(
        e: JsonElement,
        path: String,
    ): Proposal.Term? {
        val o =
            obj(
                e,
                path,
                setOf("target_qname", "proposed_concept_ref", "locale", "forms", "default"),
                setOf("locale", "forms"),
            )
                ?: return null
        val target = string(o, "target_qname", path, Shapes.CN_REF, "a concept ref (cn.X)")
        val queued = string(o, "proposed_concept_ref", path, Shapes.UUID, "a candidate id")
        if (o.containsKey("target_qname") == o.containsKey("proposed_concept_ref")) {
            fail(path, "one-of", "a term targets a concept (target_qname) or a queued one (proposed_concept_ref)")
        }
        val locale = string(o, "locale", path, Shapes.LOCALE, "a locale (cs)")
        val forms = o["forms"]?.let { lines(it, "$path/forms") }
        val default = o["default"]?.let { boolean(it, "$path/default") }
        if (locale == null || forms == null) return null
        return Proposal.Term(target, queued, locale, forms, default)
    }

    private fun relation(
        e: JsonElement,
        path: String,
    ): Proposal.Relation? {
        val o = obj(e, path, setOf("type_name", "from", "to"), setOf("type_name", "from", "to")) ?: return null
        val type = id(o, "type_name", path)
        val from = string(o, "from", path, Shapes.CN_REF, "a concept ref (cn.X)")
        val to = string(o, "to", path, Shapes.CN_REF, "a concept ref (cn.X)")
        if (type == null || from == null || to == null) return null
        return Proposal.Relation(type, from, to)
    }

    private fun provenance(
        e: JsonElement,
        path: String,
    ): Provenance? {
        val o =
            obj(
                e,
                path,
                setOf("producer", "source_ref", "confidence", "evidence", "created_at"),
                setOf("producer", "confidence", "created_at"),
            ) ?: return null
        val producer = string(o, "producer", path, Shapes.PRODUCER, "a producer id")
        val sourceRef = sourceRef(o, "source_ref", path)
        val confidence = o["confidence"]?.let { confidence(it, "$path/confidence") }
        val evidence = o["evidence"]?.let { evidence(it, "$path/evidence") }
        val createdAt = timestamp(o, "created_at", path)
        if (producer == null || confidence == null || createdAt == null) return null
        return Provenance(producer, sourceRef, confidence, evidence, createdAt)
    }

    private fun confidence(
        e: JsonElement,
        path: String,
    ): CandidateConfidence? {
        val o = obj(e, path, setOf("value", "producer", "method"), setOf("value", "producer", "method")) ?: return null
        val value =
            o["value"]?.let { v ->
                val d = (v as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.doubleOrNull
                when {
                    d == null -> null.also { fail("$path/value", "type", "a number is required") }
                    d < 0.0 || d > 1.0 -> null.also { fail("$path/value", "range", "a confidence is within [0, 1]") }
                    else -> d
                }
            }
        val producer = string(o, "producer", path, nonEmpty = true)
        val method = string(o, "method", path, nonEmpty = true)
        if (value == null || producer == null || method == null) return null
        return CandidateConfidence(value, producer, method)
    }

    private fun evidence(
        e: JsonElement,
        path: String,
    ): Evidence? {
        val o = obj(e, path, setOf("question_count", "document_count", "examples"), setOf("examples")) ?: return null
        val questions = o["question_count"]?.let { count(it, "$path/question_count") }
        val documents = o["document_count"]?.let { count(it, "$path/document_count") }
        val examples =
            o["examples"]?.let { x ->
                val a = x as? JsonArray ?: return null.also { fail("$path/examples", "type", "an array is required") }
                a.mapIndexedNotNull { i, item -> example(item, "$path/examples/$i") }
            }
        return examples?.let { Evidence(questions, documents, it) }
    }

    private fun example(
        e: JsonElement,
        path: String,
    ): EvidenceExample? {
        val o = obj(e, path, setOf("kind", "ref", "text"), setOf("kind", "ref", "text")) ?: return null
        val kind = enumOf(o, "kind", path, ExampleKind.entries, ExampleKind::word)
        val ref = sourceRef(o, "ref", path)
        val text = string(o, "text", path, nonEmpty = true)
        if (kind != null && ref != null && !ref.startsWith("${kind.scheme}://")) {
            fail("$path/ref", "scheme", "a ${kind.word} is referenced by ${kind.scheme}://")
        }
        if (kind == null || ref == null || text == null) return null
        return EvidenceExample(kind, ref, text)
    }

    private fun resolution(
        e: JsonElement,
        path: String,
    ): Resolution? {
        val o = obj(e, path, setOf("by", "at", "canon_ref", "note"), setOf("by", "at")) ?: return null
        val by = string(o, "by", path, nonEmpty = true)
        val at = timestamp(o, "at", path)
        val canon = string(o, "canon_ref", path, Shapes.CANON_REF, "<commit>:<qname>")
        val note = string(o, "note", path)
        if (by == null || at == null) return null
        return Resolution(by, at, canon, note)
    }

    // ----- shapes -----

    private fun obj(
        e: JsonElement,
        path: String,
        allowed: Set<String>,
        required: Set<String>,
    ): JsonObject? {
        if (e !is JsonObject) {
            fail(path, "type", "an object is required")
            return null
        }
        for (k in e.keys - allowed) fail("$path/$k", "unknown-field", "'$k' is not a field here")
        for (k in required - e.keys) fail("$path/$k", "required", "'$k' is required")
        return e
    }

    private fun string(
        o: JsonObject,
        key: String,
        path: String,
        pattern: Regex? = null,
        what: String = "",
        nonEmpty: Boolean = false,
    ): String? {
        val e = o[key] ?: return null
        if (e !is JsonPrimitive || !e.isString) {
            fail("$path/$key", "type", "a string is required")
            return null
        }
        val s = e.content
        if (nonEmpty && s.isEmpty()) {
            fail("$path/$key", "pattern", "not empty")
            return null
        }
        if (pattern != null && !pattern.matches(s)) {
            fail("$path/$key", "pattern", "'$s' is not $what")
            return null
        }
        return s
    }

    /** A name the promotion writes as model text: the identifier shape, then the grammar's own `id` rule. */
    private fun id(
        o: JsonObject,
        key: String,
        path: String,
    ): String? {
        val s = string(o, key, path, Shapes.NAME, "a TTR identifier") ?: return null
        if (!TtrId.isId(s)) {
            fail("$path/$key", "not-a-ttr-id", "'$s' is a word the TTR grammar reserves — not a name")
            return null
        }
        return s
    }

    private fun <T> enumOf(
        o: JsonObject,
        key: String,
        path: String,
        values: List<T>,
        word: (T) -> String,
    ): T? {
        val s = string(o, key, path) ?: return null
        return values.firstOrNull { word(it) == s }
            ?: null.also { fail("$path/$key", "enum", "'$s' is not one of ${values.map(word)}") }
    }

    private fun timestamp(
        o: JsonObject,
        key: String,
        path: String,
    ): String? {
        val s = string(o, key, path) ?: return null
        val real =
            Shapes.TIMESTAMP.matches(s) &&
                try {
                    OffsetDateTime.parse(s)
                    true
                } catch (_: DateTimeParseException) {
                    false
                }
        if (!real) fail("$path/$key", "timestamp", "'$s' is not an RFC 3339 instant with an offset")
        return s.takeIf { real }
    }

    private fun sourceRef(
        o: JsonObject,
        key: String,
        path: String,
    ): String? {
        val s = string(o, key, path) ?: return null
        if (SourceRefs.parse(s) == null) {
            fail("$path/$key", "source-ref", "'$s' is not a doc://, recipe:// or question:// ref (cn contracts §6)")
            return null
        }
        return s
    }

    private fun label(
        e: JsonElement,
        path: String,
    ): Map<String, String>? {
        if (e !is JsonObject) return null.also { fail(path, "type", "an object is required") }
        if (e.isEmpty()) return null.also { fail(path, "required", "a label names at least one locale") }
        val out = linkedMapOf<String, String>()
        for ((k, v) in e) {
            if (!Shapes.LOCALE.matches(k)) fail("$path/$k", "pattern", "'$k' is not a locale (cs)")
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            when {
                s == null -> fail("$path/$k", "type", "a string is required")
                !Shapes.LINE.matches(s) -> fail("$path/$k", "pattern", "one line of text")
                else -> out[k] = s
            }
        }
        return out
    }

    private fun refList(
        e: JsonElement,
        path: String,
    ): List<String>? {
        val a = e as? JsonArray ?: return null.also { fail(path, "type", "an array is required") }
        if (a.isEmpty()) fail(path, "required", "at least one ref")
        val refs =
            a.mapIndexedNotNull { i, item ->
                val s = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s == null ||
                    !Shapes.CN_REF.matches(s)
                ) {
                    null.also { fail("$path/$i", "pattern", "a concept ref (cn.X)") }
                } else {
                    s
                }
            }
        if (refs.size != refs.toSet().size) fail(path, "unique", "a ref appears twice")
        return refs
    }

    private fun lines(
        e: JsonElement,
        path: String,
    ): List<String>? {
        val a = e as? JsonArray ?: return null.also { fail(path, "type", "an array is required") }
        if (a.isEmpty()) fail(path, "required", "at least one form")
        val out =
            a.mapIndexedNotNull { i, item ->
                val s = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s == null ||
                    !Shapes.LINE.matches(s)
                ) {
                    null.also { fail("$path/$i", "pattern", "one line of text") }
                } else {
                    s
                }
            }
        if (out.size != out.toSet().size) fail(path, "unique", "a form appears twice")
        return out
    }

    private fun participants(
        e: JsonElement,
        path: String,
    ): List<Participant>? {
        val a = e as? JsonArray ?: return null.also { fail(path, "type", "an array is required") }
        if (a.isEmpty()) fail(path, "required", "at least one participant")
        return a.mapIndexedNotNull { i, item ->
            val p = "$path/$i"
            val o = obj(item, p, setOf("concept", "label"), setOf("concept")) ?: return@mapIndexedNotNull null
            val concept = string(o, "concept", p, Shapes.CN_REF, "a concept ref (cn.X)")
            val label = id(o, "label", p)
            concept?.let { Participant(it, label) }
        }
    }

    private fun boolean(
        e: JsonElement,
        path: String,
    ): Boolean? =
        (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: null.also { fail(path, "type", "a boolean is required") }

    private fun count(
        e: JsonElement,
        path: String,
    ): Int? {
        // JSON Schema's integer: any integral number (`14.0` is 14), capped at 32 bits on both sides.
        val n =
            (e as? JsonPrimitive)
                ?.takeIf { !it.isString && it.booleanOrNull == null }
                ?.content
                ?.toBigDecimalOrNull()
                ?.takeIf { it.stripTrailingZeros().scale() <= 0 }
        return when {
            n == null -> null.also { fail(path, "type", "an integer is required") }
            n.signum() < 0 -> null.also { fail(path, "range", "a count is not negative") }
            n > Int.MAX_VALUE.toBigDecimal() -> null.also { fail(path, "range", "a count fits in 32 bits") }
            else -> n.toInt()
        }
    }
}
