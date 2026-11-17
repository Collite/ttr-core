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
import org.apache.jena.riot.system.ErrorHandler
import org.apache.jena.riot.system.StreamRDFBase
import org.apache.jena.shared.JenaException
import org.tatrman.ttr.profile.Bytewise
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileSpan
import java.nio.file.Path
import java.security.MessageDigest
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
 *   tag, or `"@type"` (the datatype IRI) when its datatype is not `xsd:string` (an `xsd:string` literal has no `@type`).
 *   A language tag is written in the TTR locale form, lower case with `-` → `_` (`en-GB` → `en_gb`; ✅D-MW-87 item 5), so
 *   a label map key and a lexicon locale read it alike.
 * - A blank node is `_:` + 16 hex digits of a SHA-256 of its content (✅R350-B, see [Graph]); a cycle of blank nodes
 *   is `ys/profile-invalid`.
 * - A predicate's key is its IRI, written `<prefix>:<local>` when it lies in one of the fixed namespaces of [PREFIXES]
 *   (never the document's own prefixes, which differ between syntaxes), so a profile reads `/skos:broader`.
 * - Subjects are in bytewise key order, predicates in bytewise IRI order after `@id`/`@local`, and each predicate's
 *   objects in bytewise order of (`@id`, `@value`, `@language`, `@type`). A graph is a set, so the same graph written as
 *   Turtle or as RDF/XML yields the same tree.
 *
 * Relative IRIs resolve against `file:///` whatever the file is called (review-350 F3), so `<#Customer>` is
 * `file:///#Customer` in every export of the glossary. A syntax error is `ys/profile-invalid` at the parser's line and
 * column in the file; any other Jena failure at the file's first line. RIOT reports no positions for triples, so a
 * diagnostic or `ignored[]` entry on a tree node is labelled `<file name>#subjects`: its line and column are the subject
 * map's, and its path names the subject's IRI and the predicate. The tree's `file` (what `files` globs and `package`
 * stems read) is the plain file name.
 */
class RdfFrontEnd private constructor(
    override val name: String,
    private val lang: Lang,
) : FrontEnd {
    override val records = "/subjects"

    override fun parse(files: List<Path>): List<ForeignTree> =
        labelled(files).map { (file, name) ->
            ForeignTree.read(tree(JsonFrontEnd.text(file), name), name, "$name$TREE")
        }

    /** The subject map of [text] as JSON text. */
    internal fun tree(
        text: String,
        label: String,
    ): String {
        val triples = mutableListOf<Triple>()
        val sink =
            object : StreamRDFBase() {
                override fun triple(triple: Triple) {
                    triples += triple
                }
            }
        try {
            RDFParser
                .create()
                .fromString(text)
                .lang(lang)
                .base(BASE)
                .errorHandler(Errors(label))
                .parse(sink)
        } catch (e: JenaException) {
            // F3 (review-350): any Jena failure (an IRI it cannot resolve, say), not only RIOT's, is a located error.
            throw ProfileException(
                "ys/profile-invalid",
                ProfileSpan(label, 1, 1, 1, 1, ""),
                e.message ?: "RDF syntax error",
            )
        }
        val graph = Graph(triples, label)
        val subjects = TreeMap<String, JsonObject>(Bytewise)
        for (subject in graph.subjects) {
            val id = graph.term(subject, emptyList())
            subjects[id] =
                JsonObject(
                    reference(id) +
                        graph.properties(subject, emptyList()).mapValues { (_, objects) ->
                            JsonArray(objects.toList())
                        },
                )
        }
        val tree = JsonObject(mapOf("subjects" to JsonObject(subjects)))
        return PRETTY.encodeToString(JsonElement.serializer(), tree) + "\n"
    }

    /**
     * The parsed graph's terms and properties. ✅R350-B: a blank node is labelled `_:` + the first 16 hex digits of the
     * SHA-256 of its content — its predicates in bytewise IRI order, each with its objects in [objectOrder], a blank
     * object by its own label — so the label is the same whatever the syntax or the statement order. Two blank nodes with
     * the same content get one label (one subject in the tree; their objects are a set). A blank node met again on its own
     * path has no content label: `ys/profile-invalid`, naming the predicates of the cycle.
     */
    private class Graph(
        triples: List<Triple>,
        private val label: String,
    ) {
        private val bySubject = triples.groupBy { it.subject }
        private val keys = HashMap<String, String>()
        private val labels = HashMap<Node, String>()
        val subjects: Set<Node> = bySubject.keys

        /** [path]: the blank nodes being labelled, outermost first, each with the predicate that leads on. */
        fun term(
            node: Node,
            path: List<Pair<Node, String>>,
        ): String {
            if (!node.isBlank) return node.uri
            labels[node]?.let { return it }
            val start = path.indexOfFirst { it.first == node }
            if (start >= 0) {
                val cycle = path.drop(start).joinToString(" → ") { it.second }
                throw ProfileException(
                    "ys/profile-invalid",
                    ProfileSpan("$label$TREE", 1, 1, 1, 1, "/subjects"),
                    "blank nodes in a cycle ($cycle) have no content label; give one of them an IRI",
                )
            }
            val content =
                properties(node, path).entries.joinToString("\n") { (key, objects) ->
                    "${full(key)} ${JsonArray(objects.toList())}"
                }
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            return "_:${digest.joinToString("") { "%02x".format(it) }.take(16)}".also { labels[node] = it }
        }

        /** [subject]'s predicates (keys in bytewise order of the full IRI) and their objects in [objectOrder]. */
        fun properties(
            subject: Node,
            path: List<Pair<Node, String>>,
        ): Map<String, Set<JsonObject>> {
            val grouped = TreeMap<String, MutableSet<JsonObject>>(compareBy(Bytewise) { full(it) })
            for (triple in bySubject[subject].orEmpty()) {
                val key = keys.getOrPut(triple.predicate.uri) { compact(triple.predicate.uri) }
                grouped.getOrPut(key) { sortedSetOf(objectOrder) } += value(triple.`object`, path + (subject to key))
            }
            return grouped
        }

        private fun value(
            node: Node,
            path: List<Pair<Node, String>>,
        ): JsonObject =
            if (node.isLiteral) {
                JsonObject(
                    buildMap {
                        put("@value", JsonPrimitive(node.literalLexicalForm))
                        val language = node.literalLanguage.orEmpty()
                        if (language.isNotEmpty()) {
                            put("@language", JsonPrimitive(locale(language)))
                        } else if (node.literalDatatypeURI != XSD_STRING) {
                            put("@type", JsonPrimitive(node.literalDatatypeURI))
                        }
                    },
                )
            } else {
                reference(term(node, path))
            }
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

        /** The label suffix of a subject map's spans: they are positions in the tree, not in the file. */
        const val TREE = "#subjects"

        private const val XSD_STRING = "http://www.w3.org/2001/XMLSchema#string"

        /** An IRI or blank-node term as an object: `{"@id", "@local"}` (✅D-MW-83). */
        private fun reference(id: String): JsonObject =
            JsonObject(
                buildMap {
                    put("@id", JsonPrimitive(id))
                    local(id)?.let { put("@local", JsonPrimitive(it)) }
                },
            )

        /** F3 (review-350): one base for every file, so a relative IRI does not change with the file's name. */
        const val BASE = "file:///"

        /** F4 (✅D-MW-87 item 5): a language tag in the TTR locale form — lower case, `-` → `_` (`en-GB` → `en_gb`). */
        internal fun locale(tag: String): String = tag.lowercase().replace('-', '_')

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
