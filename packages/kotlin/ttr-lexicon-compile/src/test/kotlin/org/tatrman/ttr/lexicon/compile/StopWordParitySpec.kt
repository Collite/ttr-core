// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.lexicon.Lang
import org.tatrman.ttr.lexicon.LexiconStopWords
import org.tatrman.ttr.lexicon.TermNormalizer

/**
 * LP-P2a T2 — the two stop-word lists are one vocabulary, held equal here.
 *
 * `ttr-lexicon` carries `/lexicon-stopwords/stop-words-<lang>.txt` for the RG-LEX-031 guard;
 * `ttr-metadata` carries `/search/stop-words-<lang>.txt` for its keyword search. **Two files, one
 * list** — and this module is the only one that can see both, which is why the spec lives here
 * rather than beside either of them.
 *
 * Why not one file and a module edge: `ttr-lexicon` is the lean artifact the SERVING side reads
 * (`lex-matcher`, the resolver), and its KDoc is explicit that it must not drag `ttr-parser`,
 * `ttr-metadata` or `ttr-snapshot` in. A word list is not worth inverting that.
 *
 * Why one list and not two vocabularies: a word that is noise to keyword search is a word nobody
 * should hang a filter on either, and two lists that "happened to agree" would drift the first
 * time someone improved one of them. Add a word to BOTH — or split them deliberately, delete this
 * spec, and say in both files why.
 *
 * ⚠ The task list that opened LP-P2a said to reuse "the per-lang stopword list already used by the
 * lint's collision rule". There is no such list: `packages/lint`'s `lexicon-form-collides-with-name`
 * folds and compares, and consults no stop words at all. `ttr-metadata`'s search lists are the only
 * per-language word lists in the repo, so they are what "reuse" can mean here.
 */
class StopWordParitySpec :
    FunSpec({

        /** The `ttr-metadata` twin, read straight off the classpath — never a copy in this file. */
        fun metadataList(lang: String): Set<String> {
            val path = "/search/stop-words-$lang.txt"
            val stream =
                requireNotNull(StopWordParitySpec::class.java.getResourceAsStream(path)) {
                    "the ttr-metadata twin is missing: $path"
                }
            return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map(TermNormalizer::fold)
                    .toSet()
            }
        }

        listOf("cs" to Lang.CS, "en" to Lang.EN).forEach { (wire, lang) ->
            test("$wire — the lexicon guard and the metadata search read the same words") {
                LexiconStopWords.of(lang) shouldContainExactlyInAnyOrder metadataList(wire)
            }
        }

        test("cs|en asks BOTH lists — a form must clear the union") {
            // A `cs|en` term is claimed to work in either language, so a word that is noise in
            // EITHER is a word it must not be. The union, not the intersection.
            LexiconStopWords.of(Lang.CS_EN) shouldContainExactlyInAnyOrder
                (metadataList("cs") + metadataList("en"))
        }

        test("the guard folds before it looks — diacritics are not a way around it") {
            // The lists carry folded forms (`ze`, not `že`), because the resolver's anchor index
            // strips combining marks: the two are ONE word to the matcher.
            withClue("že") { LexiconStopWords.isStop("že", Lang.CS) shouldBe true }
            withClue("Ze") { LexiconStopWords.isStop("Ze", Lang.CS) shouldBe true }
            withClue("obsahuje") { LexiconStopWords.isStop("obsahuje", Lang.CS) shouldBe false }
        }
    })
