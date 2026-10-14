// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.lexicon.CompiledEntry
import org.tatrman.ttr.lexicon.Lang
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconStopWords
import org.tatrman.ttr.lexicon.MatchMethod
import org.tatrman.ttr.lexicon.MatchProfile
import org.tatrman.ttr.lexicon.TermNormalizer

/**
 * ttr-core#178 — the words a question uses to ask for a count, an average or a ranking, looked up
 * the way the matcher looks a word up.
 *
 * [operatorsOf] mirrors the matcher's gate for the three sugar methods: `EXACT` is equality on the
 * canonical form (NFC, lowercase, diacritics kept); `TYPOS(d)` is a Levenshtein distance of at most
 * `d` on that form, with no fuzz at all on a term of [MatchProfile.SHORT_TERM_MAX_CHARS] characters
 * or fewer (⚑M-4); `TOKENS` fires when the window holds ANY content word of the term — the fragment
 * reading LP review-103 found live (*názvem* firing *s názvem přesně*), so the mirror is at least as
 * wide as the matcher there. It is context-free on purpose, because the matcher is: a row cannot see
 * the words around the window it is asked about. That is the whole argument behind the guard below.
 */
class StdlibOperatorWordsSpec :
    FunSpec({

        val entries: List<CompiledEntry> =
            LexiconCompiler
                .compile(
                    LexiconSources(area = LexiconArea(emptyList(), LexiconStdlib.skills())),
                    ModelRefIndex.EMPTY,
                    "sha256:" + "78".repeat(32),
                    "2026-10-14T00:00:00Z",
                ).lexicon.entries

        fun overlaps(
            row: String,
            question: Lang,
        ): Boolean = row == question.wire || row == Lang.CS_EN.wire || question == Lang.CS_EN

        fun levenshtein(
            a: String,
            b: String,
        ): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                prev = cur
            }
            return prev[b.length]
        }

        fun fires(
            window: String,
            row: CompiledEntry,
            lang: Lang,
        ): Boolean {
            val query = TermNormalizer.normalize(window)
            val term = row.termNormalized
            return when (val method = MatchMethod.ofWire(row.method)) {
                MatchMethod.Exact -> query == term
                is MatchMethod.Typos -> {
                    val d = levenshtein(query, term)
                    d == 0 || (term.length > MatchProfile.SHORT_TERM_MAX_CHARS && d <= method.maxDistance)
                }
                MatchMethod.Tokens -> {
                    val words = query.split(' ').toSet()
                    term.split(' ').any { it in words && !LexiconStopWords.isStop(it, lang) }
                }
                null -> error("unparseable method ${row.method} on '$term'")
            }
        }

        /** Every operator a word window resolves to, as the matcher would answer it. */
        fun operatorsOf(
            window: String,
            lang: Lang,
        ): Set<String> =
            entries
                .filter { it.targetRef.startsWith("op:") && overlaps(it.lang, lang) && fires(window, it, lang) }
                .map { it.targetRef }
                .toSet()

        /** Every contiguous window of one to three words — the widest a resolver window is. */
        fun windows(question: String): List<String> {
            val words = question.split(' ').map { it.trim('?', ',', '.', '„', '“', '"') }.filter { it.isNotEmpty() }
            return (1..3).flatMap { n -> words.windowed(n).map { it.joinToString(" ") } }
        }

        // ---- the words that ask for a count, an average or a ranking ----------------------------

        val asks: List<Triple<String, Lang, String>> =
            listOf(
                // op:count — "How many stores do we have?" / „Kolik máme prodejen?“
                Triple("How many", Lang.EN, "op:count"),
                Triple("number of", Lang.EN, "op:count"),
                Triple("count of", Lang.EN, "op:count"),
                Triple("count", Lang.EN, "op:count"),
                Triple("Kolik", Lang.CS, "op:count"),
                Triple("kolika", Lang.CS, "op:count"),
                Triple("Počet", Lang.CS, "op:count"),
                Triple("počtu", Lang.CS, "op:count"),
                Triple("počtem", Lang.CS, "op:count"),
                Triple("pocet", Lang.CS, "op:count"),
                // op:avg — "What was the average marketplace revenue per line item in 2025?"
                Triple("average", Lang.EN, "op:avg"),
                Triple("averages", Lang.EN, "op:avg"),
                Triple("mean", Lang.EN, "op:avg"),
                Triple("průměrná", Lang.CS, "op:avg"),
                Triple("průměrný", Lang.CS, "op:avg"),
                Triple("průměrné", Lang.CS, "op:avg"),
                Triple("průměrnou", Lang.CS, "op:avg"),
                Triple("průměrného", Lang.CS, "op:avg"),
                Triple("průměrných", Lang.CS, "op:avg"),
                Triple("průměrně", Lang.CS, "op:avg"),
                Triple("průměr", Lang.CS, "op:avg"),
                Triple("průměru", Lang.CS, "op:avg"),
                Triple("v průměru", Lang.CS, "op:avg"),
                // op:top-n — the superlatives. "Which distribution center has the highest
                // late-delivery share?" / „10 nejprodávanějších produktů na tržišti“
                Triple("highest", Lang.EN, "op:top-n"),
                Triple("best", Lang.EN, "op:top-n"),
                Triple("biggest", Lang.EN, "op:top-n"),
                Triple("largest", Lang.EN, "op:top-n"),
                Triple("nejvyšší", Lang.CS, "op:top-n"),
                Triple("nejvyššího", Lang.CS, "op:top-n"),
                Triple("nejvyšším", Lang.CS, "op:top-n"),
                Triple("nejvyšších", Lang.CS, "op:top-n"),
                Triple("nejvyššími", Lang.CS, "op:top-n"),
                Triple("největší", Lang.CS, "op:top-n"),
                Triple("největšího", Lang.CS, "op:top-n"),
                Triple("největších", Lang.CS, "op:top-n"),
                Triple("nejlepší", Lang.CS, "op:top-n"),
                Triple("nejlepším", Lang.CS, "op:top-n"),
                Triple("nejlepších", Lang.CS, "op:top-n"),
                Triple("nejprodávanější", Lang.CS, "op:top-n"),
                Triple("nejprodávanějšího", Lang.CS, "op:top-n"),
                Triple("nejprodávanějších", Lang.CS, "op:top-n"),
                Triple("nejprodávanějšími", Lang.CS, "op:top-n"),
            )

        asks.forEach { (window, lang, op) ->
            test("'$window' [${lang.wire}] resolves to $op and to nothing else") {
                operatorsOf(window, lang) shouldBe setOf(op)
            }
        }

        // ---- the guard: questions that ask for none of them --------------------------------------
        //
        // Each phrase holds a word that was considered as a trigger and left out, or a near
        // neighbour of a trigger that was added. A trigger that fired here would put an operator on
        // a plain question — "revenue per month" read as an average is one number where twelve were
        // asked for, and nothing downstream could tell.

        val plain: List<Pair<String, Lang>> =
            listOf(
                // `per` names a grouping as often as a unit: twelve monthly totals, not an average.
                "Revenue per month in 2025" to Lang.EN,
                "Revenue per store in Texas" to Lang.EN,
                // `na` is the commonest Czech preposition — a place, a share's whole, a predicate.
                "Tržby na tržišti v roce 2025" to Lang.CS,
                "Tržby na webu za rok 2025" to Lang.CS,
                // `most` forms the superlative of ANOTHER adjective; "most recent" is a period.
                "Revenue in the most recent month" to Lang.EN,
                "Which channel brings most of our revenue" to Lang.EN,
                // One edit from `mean` · `count` · `best`, and two words that hold `number`.
                "Meat sales by county" to Lang.EN,
                "Revenue in the West region" to Lang.EN,
                "Revenue by order number" to Lang.EN,
                // One edit from `kolik`, typed without its diacritics.
                "Tržby prodejny Kolin v roce 2025" to Lang.CS,
            )

        plain.forEach { (question, lang) ->
            test("no operator fires anywhere in \"$question\" [${lang.wire}]") {
                val fired =
                    windows(question)
                        .associateWith { operatorsOf(it, lang) }
                        .filterValues { it.isNotEmpty() }
                fired shouldBe emptyMap()
            }
        }

        test("every multi-word count / average / ranking trigger is EXACT — a TOKENS row fires on its fragment") {
            // `number of` as TOKENS is `number` once `of` is a function word: *order number*.
            entries
                .filter { it.targetRef in setOf("op:count", "op:avg", "op:top-n") && ' ' in it.termNormalized }
                .forEach { row -> withClue(row.termNormalized) { row.method shouldBe MatchMethod.Exact.wire } }
        }

        // ---- the bodies -----------------------------------------------------------------------------

        /** The body as one line — the prose is wrapped, the sentences are not. */
        fun body(op: String): String =
            LexiconStdlib
                .skills()
                .single { it.opId == op }
                .body
                .replace(Regex("""\s+"""), " ")

        test("op:top-n answers the top 10 when no number is named, and says so (no longer a gap)") {
            val topN = body("op:top-n")
            topN shouldNotContain "is a gap, not a default of 10"
            topN shouldContain "If the question names no number, answer the top 10 and say so"
            topN shouldContain "it is not a gap to ask about"
        }

        test("op:count counts identities, not rows; op:avg divides a sum by a count from one result") {
            body("op:count") shouldContain "COUNT(DISTINCT"
            body("op:avg") shouldContain "one result"
        }

        test("each Applicability paragraph names exactly the requirements the frontmatter declares") {
            // A consumer that reads the requirement from the prose (the `requires:` list does not
            // reach the archive's operator entry) takes every backticked token of that paragraph as
            // one. A second token there is a requirement nobody declared.
            val section = Regex("""^(Retrieval|Formatting|Applicability):\s*""", RegexOption.MULTILINE)
            val token = Regex("""`([a-z0-9-]+)`""")

            LexiconStdlib.skills().forEach { skill ->
                val matches = section.findAll(skill.body).toList()
                val applicability =
                    matches
                        .indexOfFirst { it.groupValues[1] == "Applicability" }
                        .takeIf { it >= 0 }
                        ?.let { i ->
                            val end = matches.getOrNull(i + 1)?.range?.first ?: skill.body.length
                            skill.body.substring(matches[i].range.last + 1, end)
                        }.orEmpty()

                withClue(skill.opId) {
                    token.findAll(applicability).map { it.groupValues[1] }.toList() shouldContainExactlyInAnyOrder
                        skill.requires
                }
            }
        }
    })
