// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export.rdf

/** Deterministic Turtle without an RDF runtime. Blank subjects are rendered inline. */
object TurtleSerializer {
    fun write(
        sink: TripleSink,
        world: String,
    ): ByteArray {
        val prefixes = Vocabulary.prefixes(world)
        val triples = sink.sorted()
        val grouped = triples.groupBy { it.subject }

        fun iri(value: String): String {
            for ((prefix, namespace) in prefixes) {
                if (!value.startsWith(namespace)) continue
                val local = value.removePrefix(namespace)
                if (Regex("[A-Za-z_][A-Za-z0-9_.-]*").matches(local) && !local.endsWith('.')) return "$prefix:$local"
            }
            return "<${escapeIri(value)}>"
        }

        fun term(
            value: Term,
            visiting: Set<Blank> = emptySet(),
        ): String =
            when (value) {
                is Iri -> iri(value.value)
                is Literal -> literal(value, ::iri)
                is Blank -> {
                    require(value !in visiting) { "Cyclic inline blank node: ${value.id}" }
                    val triples = grouped[value].orEmpty()
                    "[ " +
                        triples.groupBy { it.predicate }.entries.joinToString(" ; ") { (p, objects) ->
                            (if (p.value == Vocabulary.RDF + "type") "a" else iri(p.value)) + " " +
                                objects.joinToString(", ") { term(it.obj, visiting + value) }
                        } + " ]"
                }
            }
        // Inlining is only valid for a rooted tree of uniquely referenced blanks.
        val blanks = (grouped.keys + triples.map { it.obj }).filterIsInstance<Blank>().toSet()
        val incoming = triples.mapNotNull { it.obj as? Blank }.groupingBy { it }.eachCount()
        for (blank in blanks) {
            require(incoming[blank] == 1) { "Inline blank must have one incoming reference: ${blank.id}" }
        }
        val reachable = mutableSetOf<Blank>()

        fun visit(value: Term) {
            if (value is Blank && reachable.add(value)) grouped[value].orEmpty().forEach { visit(it.obj) }
        }
        triples.filter { it.subject is Iri }.forEach { visit(it.obj) }
        require(reachable == blanks) { "Inline blanks must be reachable from an IRI subject" }
        return buildString {
            for ((prefix, namespace) in prefixes) append("@prefix $prefix: <${escapeIri(namespace)}> .\n")
            append('\n')
            val subjects = grouped.filterKeys { it is Iri }
            for ((index, entry) in subjects.entries.withIndex()) {
                if (index > 0) append('\n')
                append(term(entry.key))
                append(' ')
                append(
                    entry.value.groupBy { it.predicate }.entries.joinToString(" ;\n    ") { (p, objects) ->
                        (if (p.value == Vocabulary.RDF + "type") "a" else iri(p.value)) + " " +
                            objects.joinToString(", ") { term(it.obj) }
                    },
                )
                append(" .\n")
            }
        }.toByteArray(Charsets.UTF_8)
    }

    private fun literal(
        value: Literal,
        iri: (String) -> String,
    ): String {
        if (value.datatype?.value == Vocabulary.XSD + "boolean" &&
            value.lexical in setOf("true", "false")
        ) {
            return value.lexical
        }
        val multiline = '\n' in value.lexical
        val quote = if (multiline) "\"\"\"" else "\""
        val text =
            buildString {
                for (c in value.lexical) {
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append(if (multiline) "\n" else "\\n")
                        '\t' -> append("\\t")
                        '\r' -> append("\\r")
                        else -> if (c.code < 0x20 || c.code == 0x7f) append("\\u%04X".format(c.code)) else append(c)
                    }
                }
            }
        return quote + text + quote +
            when {
                value.lang != null -> "@${value.lang}"
                value.datatype != null -> "^^${iri(value.datatype.value)}"
                else -> ""
            }
    }

    private fun escapeIri(value: String): String =
        buildString {
            for (c in value) {
                if (c.code <= 0x20 ||
                    c in "<>\"{}|^`\\" ||
                    c.code == 0x7f
                ) {
                    append("\\u%04X".format(c.code))
                } else {
                    append(c)
                }
            }
        }
}
