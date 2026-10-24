// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export.rdf

import org.tatrman.ttr.metadata.export.bytewise

sealed interface Term

sealed interface Subject : Term

data class Iri(
    val value: String,
) : Subject

data class Blank(
    val id: String,
) : Subject

data class Literal(
    val lexical: String,
    val lang: String? = null,
    val datatype: Iri? = null,
) : Term {
    init {
        require(lang == null || datatype == null) { "A literal cannot have both language and datatype" }
        require(lang == null || Regex("[a-zA-Z]+(-[a-zA-Z0-9]+)*").matches(lang)) { "Invalid RDF language tag: $lang" }
    }
}

data class Triple(
    val subject: Subject,
    val predicate: Iri,
    val obj: Term,
)

/** C-11 row order; ontology metadata first, then assertions and their aliases. */
object Vocabulary {
    const val TTR = "https://tatrman.org/ns/ttr#"
    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val RDFS = "http://www.w3.org/2000/01/rdf-schema#"
    const val OWL = "http://www.w3.org/2002/07/owl#"
    const val SKOS = "http://www.w3.org/2004/02/skos/core#"
    const val XSD = "http://www.w3.org/2001/XMLSchema#"
    const val DCT = "http://purl.org/dc/terms/"
    val predicates =
        listOf(
            RDF + "type",
            DCT + "identifier",
            OWL + "versionInfo",
            SKOS + "inScheme",
            TTR + "tier",
            TTR + "abstract",
            OWL + "sameAs",
            TTR + "is_a",
            RDFS + "subClassOf",
            SKOS + "broader",
            TTR + "involves",
            TTR + "label",
            TTR + "concept",
            RDFS + "domain",
            RDFS + "range",
            TTR + "extent",
            TTR + "basis",
            TTR + "periodAt",
            TTR + "periodStart",
            TTR + "periodEnd",
            TTR + "end_open",
            TTR + "imprecision",
            TTR + "definition",
            TTR + "definitionLanguage",
            TTR + "valueSpace",
            TTR + "memberRestriction",
            TTR + "groundsTo",
            TTR + "groundingLayer",
            SKOS + "exactMatch",
            SKOS + "prefLabel",
            SKOS + "altLabel",
            TTR + "example",
            RDFS + "label",
            SKOS + "definition",
            RDFS + "comment",
            TTR + "member",
        )

    fun prefixes(world: String): Map<String, String> =
        linkedMapOf(
            "ttr" to TTR,
            "rdf" to RDF,
            "rdfs" to RDFS,
            "owl" to OWL,
            "skos" to SKOS,
            "xsd" to XSD,
            "dct" to DCT,
            "w" to "urn:ttr:$world:",
        )
}

/** RDF is a set: duplicate assertions contribute one triple, including shared aliases/groundings. */
class TripleSink {
    private val values = linkedSetOf<Triple>()
    val size: Int get() = values.size

    fun add(
        subject: Subject,
        predicate: Iri,
        obj: Term,
    ) {
        val canonical =
            if (obj is Literal) {
                obj.copy(
                    lang = obj.lang?.lowercase(),
                    datatype = obj.datatype?.takeUnless { it.value == Vocabulary.XSD + "string" },
                )
            } else {
                obj
            }
        values += Triple(subject, predicate, canonical)
    }

    fun sorted(): List<Triple> =
        values.sortedWith(
            compareBy<Triple, String>(bytewise) { key(it.subject) }
                .thenBy {
                    Vocabulary.predicates.indexOf(it.predicate.value).let { i ->
                        if (i <
                            0
                        ) {
                            Int.MAX_VALUE
                        } else {
                            i
                        }
                    }
                }.thenBy(bytewise) { it.predicate.value }
                .thenComparator { a, b -> compareObjects(a.obj, b.obj) },
        )

    private fun compareObjects(
        a: Term,
        b: Term,
    ): Int {
        val type = fun(t: Term) =
            when (t) {
                is Iri -> 0
                is Blank -> 1
                is Literal -> 2
            }
        if (type(a) != type(b)) return type(a).compareTo(type(b))
        if (a is Literal && b is Literal) {
            val lexical = bytewise.compare(a.lexical, b.lexical)
            if (lexical != 0) return lexical
            val language = bytewise.compare(a.lang.orEmpty(), b.lang.orEmpty())
            if (language != 0) return language
            return bytewise.compare(a.datatype?.value.orEmpty(), b.datatype?.value.orEmpty())
        }
        return bytewise.compare(key(a), key(b))
    }

    private fun key(term: Term): String =
        when (term) {
            is Iri -> term.value
            is Blank -> "_:" + term.id
            is Literal -> term.lexical + "\u0000" + term.lang.orEmpty() + "\u0000" + term.datatype?.value.orEmpty()
        }
}
