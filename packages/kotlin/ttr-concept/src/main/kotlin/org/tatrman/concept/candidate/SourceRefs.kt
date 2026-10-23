// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

/**
 * CN-P5 S5.2 T2 (cn contracts §6, A-CN-82; the S-7 pin) — where a candidate, or one piece of its evidence, came from.
 * Three schemes, one spelling each, so a promoted def's `sources:` line compares as text:
 * - `doc://<system>/<document…>[#<locator>]` — a document in a producer's store; `<system>` names the store
 *   (`kallimachos` for Kleio's), the rest is that store's own document identity;
 * - `recipe://<package>/<recipe>#s<n>` — a sentence of a recipe (the recipe authoring surface as a producer, Q14-7);
 * - `question://<system>/<question…>` — a question a user asked, by the asking system's own id.
 */
sealed interface SourceRef {
    val text: String

    data class Doc(
        val system: String,
        val path: List<String>,
        val locator: Locator?,
    ) : SourceRef {
        override val text: String get() =
            "doc://$system/${path.joinToString("/")}" +
                (locator?.let { "#${it.text}" } ?: "")
    }

    data class Recipe(
        val pkg: String,
        val recipe: String,
        val sentence: Int,
    ) : SourceRef {
        override val text: String get() = "recipe://$pkg/$recipe#s$sentence"
    }

    data class Question(
        val system: String,
        val path: List<String>,
    ) : SourceRef {
        override val text: String get() = "question://$system/${path.joinToString("/")}"
    }
}

/** A place inside a document: a sentence or a table row (1-based), or a code-point span (0-based, end exclusive). */
sealed interface Locator {
    val text: String

    data class Sentence(
        val n: Int,
    ) : Locator {
        override val text: String get() = "s$n"
    }

    data class Row(
        val n: Int,
    ) : Locator {
        override val text: String get() = "r$n"
    }

    data class Span(
        val start: Int,
        val end: Int,
    ) : Locator {
        override val text: String get() = "c$start-$end"
    }
}

object SourceRefs {
    private const val SEG = "[A-Za-z0-9._~%-]+"
    private const val ORDINAL = "[1-9][0-9]{0,8}"
    private const val OFFSET = "(?:0|[1-9][0-9]{0,8})"
    private val DOC = Regex("^doc://($SEG)((?:/$SEG)+)(?:#(.+))?$")
    private val RECIPE = Regex("^recipe://($SEG)/($SEG)#s($ORDINAL)$")
    private val QUESTION = Regex("^question://($SEG)((?:/$SEG)+)$")
    private val SENTENCE = Regex("^s($ORDINAL)$")
    private val ROW = Regex("^r($ORDINAL)$")
    private val SPAN = Regex("^c($OFFSET)-($OFFSET)$")
    private val KALLIMACHOS = Regex("^kallimachos://($SEG)/($SEG)/($SEG)$")

    /** The ref [text] spells, or null when it is not one (a span whose end does not exceed its start included). */
    fun parse(text: String): SourceRef? {
        DOC.matchEntire(text)?.let { m ->
            val fragment = m.groupValues[3]
            val locator = if (fragment.isEmpty()) null else locator(fragment) ?: return null
            return SourceRef.Doc(m.groupValues[1], m.groupValues[2].removePrefix("/").split('/'), locator)
        }
        RECIPE.matchEntire(text)?.let { m ->
            return SourceRef.Recipe(m.groupValues[1], m.groupValues[2], m.groupValues[3].toInt())
        }
        QUESTION.matchEntire(text)?.let { m ->
            return SourceRef.Question(m.groupValues[1], m.groupValues[2].removePrefix("/").split('/'))
        }
        return null
    }

    /**
     * Kleio's citation ref (`Citation.source_ref`, kleio contracts §4: `kallimachos://{notebook}/{source}/{part}`) as a
     * cn source ref — one prefix swap, a locator relative to the part. Null when [kallimachos] is not one.
     */
    fun fromKallimachos(
        kallimachos: String,
        locator: Locator? = null,
    ): String? {
        val m = KALLIMACHOS.matchEntire(kallimachos) ?: return null
        return SourceRef.Doc("kallimachos", m.groupValues.drop(1), locator).text
    }

    private fun locator(fragment: String): Locator? {
        SENTENCE.matchEntire(fragment)?.let { return Locator.Sentence(it.groupValues[1].toInt()) }
        ROW.matchEntire(fragment)?.let { return Locator.Row(it.groupValues[1].toInt()) }
        SPAN.matchEntire(fragment)?.let { m ->
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2].toInt()
            return if (end > start) Locator.Span(start, end) else null
        }
        return null
    }
}
