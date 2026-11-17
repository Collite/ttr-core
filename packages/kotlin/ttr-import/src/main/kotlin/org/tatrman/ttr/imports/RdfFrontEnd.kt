// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParser
import org.apache.jena.riot.RiotException
import org.apache.jena.riot.system.ErrorHandler
import org.apache.jena.riot.system.StreamRDFBase
import org.tatrman.ttr.profile.Bytewise
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileSpan
import java.nio.file.Path
import java.util.TreeMap

/**
 * MW-P5 S5.2 T2 (contracts §1; ⚑MW-P5-S52-1 (a)/(e), ⚑MW-P5-S52-2) — the RDF front-ends `turtle` and `rdfxml`. Jena RIOT
 * parses the file (strict UTF-8 and the reader budget first, as [JsonFrontEnd] reads one); the graph becomes the subject map
 *
 * ```
 * {subjects: {<subject>: {"@id": <subject>, "@local": <local name>, <predicate>: [<object>…], …}, …}}
 * ```
 *
 * - A subject is keyed by its IRI, verbatim; `@local` is the IRI's part after its last `#` or `/` (left out when empty), so
 *   a profile names a definition with `kinds.id: /@local` while the sidecar keeps `@id` (✅D-MW-81 (e)).
 * - An IRI object is `{"@id", "@local"}` (✅D-MW-83); a literal is `{"@value"}` with `"@language"` when it has a language
 *   tag, or `"@type"` (the datatype IRI) when its datatype is not `xsd:string`.
 * - A blank node is `_:b<n>`, numbered in the order the parser first meets it (a blank node has no name to keep).
 * - A predicate's key is its IRI, written `<prefix>:<local>` when it lies in one of the fixed namespaces of [PREFIXES]
 *   (never the document's own prefixes, which differ between syntaxes), so a profile reads `/skos:broader`.
 * - Subjects are in bytewise key order, predicates in bytewise IRI order after `@id`/`@local`, and each predicate's
 *   objects in bytewise order of (`@id`, `@value`, `@language`, `@type`). A graph is a set, so the same graph written as
 *   Turtle or as RDF/XML yields the same tree.
 *
 * Relative IRIs resolve against `file:///<file name>`, which is the same on every machine. A syntax error is
 * `ys/profile-invalid` at the parser's line and column. RIOT reports no positions for triples, so a diagnostic on a tree
 * node names its path (the subject's IRI and the predicate) and the tree's own line.
 */
class RdfFrontEnd private constructor(
    override val name: String,
    private val lang: Lang,
) : FrontEnd {
    override fun parse(files: List<Path>): List<ForeignTree> =
        labelled(files).map { (file, label) -> ForeignTree.read(tree(JsonFrontEnd.text(file), label), label, label) }

    /** The subject map of [text] as JSON text. */
    internal fun tree(
        text: String,
        label: String,
    ): String {
        val subjects = TreeMap<String, TreeMap<String, MutableSet<JsonObject>>>(Bytewise)
        val predicates = HashMap<String, String>()
        val blanks = HashMap<String, String>()

        fun term(node: Node): String =
            if (node.isBlank) blanks.getOrPut(node.blankNodeLabel) { "_:b${blanks.size}" } else node.uri

        fun reference(id: String): JsonObject =
            JsonObject(
                buildMap {
                    put("@id", JsonPrimitive(id))
                    local(id)?.let { put("@local", JsonPrimitive(it)) }
                },
            )

        fun value(node: Node): JsonObject =
            if (node.isLiteral) {
                JsonObject(
                    buildMap {
                        put("@value", JsonPrimitive(node.literalLexicalForm))
                        val language = node.literalLanguage.orEmpty()
                        if (language.isNotEmpty()) {
                            put("@language", JsonPrimitive(language))
                        } else if (node.literalDatatypeURI != XSD_STRING) {
                            put("@type", JsonPrimitive(node.literalDatatypeURI))
                        }
                    },
                )
            } else {
                reference(term(node))
            }

        val sink =
            object : StreamRDFBase() {
                override fun triple(triple: Triple) {
                    val predicate = triple.predicate.uri
                    val key = predicates.getOrPut(predicate) { compact(predicate) }
                    subjects
                        .getOrPut(term(triple.subject)) { TreeMap(Bytewise) }
                        .getOrPut(key) { sortedSetOf(objectOrder) } += value(triple.`object`)
                }
            }
        try {
            RDFParser
                .create()
                .fromString(text)
                .lang(lang)
                .base("file:///$label")
                .errorHandler(Errors(label))
                .parse(sink)
        } catch (e: RiotException) {
            throw ProfileException(
                "ys/profile-invalid",
                ProfileSpan(label, 1, 1, 1, 1, ""),
                e.message ?: "RDF syntax error",
            )
        }
        val tree =
            JsonObject(
                mapOf(
                    "subjects" to
                        JsonObject(
                            subjects.mapValues { (id, properties) ->
                                val head = reference(id)
                                val ordered = properties.entries.sortedWith(compareBy(Bytewise) { full(it.key) })
                                JsonObject(
                                    head + ordered.associate { (key, objects) -> key to JsonArray(objects.toList()) },
                                )
                            },
                        ),
                ),
            )
        return PRETTY.encodeToString(JsonElement.serializer(), tree) + "\n"
    }

    /** RIOT's errors as located `ys/profile-invalid`; warnings (an odd lexical form, say) do not stop a reader. */
    private class Errors(
        private val label: String,
    ) : ErrorHandler {
        override fun warning(
            message: String,
            line: Long,
            col: Long,
        ) = Unit

        override fun error(
            message: String,
            line: Long,
            col: Long,
        ): Unit = fail(message, line, col)

        override fun fatal(
            message: String,
            line: Long,
            col: Long,
        ): Unit = fail(message, line, col)

        private fun fail(
            message: String,
            line: Long,
            col: Long,
        ): Nothing {
            val l = line.coerceAtLeast(1).toInt()
            val c = col.coerceAtLeast(1).toInt()
            throw ProfileException("ys/profile-invalid", ProfileSpan(label, l, c, l, c, ""), message)
        }
    }

    companion object {
        val TURTLE = RdfFrontEnd("turtle", Lang.TURTLE)
        val RDFXML = RdfFrontEnd("rdfxml", Lang.RDFXML)

        private const val XSD_STRING = "http://www.w3.org/2001/XMLSchema#string"

        /** The fixed namespaces a predicate key is compacted with; nothing else is (contracts §1, S5.2 Found (h)). */
        val PREFIXES: Map<String, String> =
            linkedMapOf(
                "rdf" to "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                "rdfs" to "http://www.w3.org/2000/01/rdf-schema#",
                "owl" to "http://www.w3.org/2002/07/owl#",
                "xsd" to "http://www.w3.org/2001/XMLSchema#",
                "skos" to "http://www.w3.org/2004/02/skos/core#",
                "skosxl" to "http://www.w3.org/2008/05/skos-xl#",
                "dct" to "http://purl.org/dc/terms/",
                "dc" to "http://purl.org/dc/elements/1.1/",
            )

        private val SIMPLE = Regex("[A-Za-z_][A-Za-z0-9_.-]*")

        private val PRETTY = Json { prettyPrint = true }

        private val objectOrder: Comparator<JsonObject> =
            listOf("@id", "@value", "@language", "@type")
                .map { field ->
                    compareBy<JsonObject, String>(Bytewise) { (it[field] as? JsonPrimitive)?.content ?: "" }
                }.reduce { a, b -> a.then(b) }

        /** The part after the last `#` or `/`, or null when that is empty or the term is a blank node. */
        internal fun local(id: String): String? =
            if (id.startsWith(
                    "_:",
                )
            ) {
                null
            } else {
                id.substring(maxOf(id.lastIndexOf('#'), id.lastIndexOf('/')) + 1).ifEmpty { null }
            }

        internal fun compact(iri: String): String =
            PREFIXES.entries
                .firstOrNull { (_, ns) -> iri.startsWith(ns) && SIMPLE.matches(iri.substring(ns.length)) }
                ?.let { (prefix, ns) -> "$prefix:${iri.substring(ns.length)}" } ?: iri

        private fun full(key: String): String =
            key.substringBefore(':').let { prefix -> PREFIXES[prefix]?.let { it + key.substringAfter(':') } } ?: key
    }
}
