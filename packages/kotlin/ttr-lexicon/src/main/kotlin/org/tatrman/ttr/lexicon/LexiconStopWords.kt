// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon

/**
 * LP (contracts §3.1) — the function-word list the `RG-LEX-031` guard asks.
 *
 * Only the `pred:` slice consults it, and that narrowness is the point. Every other target class
 * is anchored by something outside the words: a model ref exists in the snapshot or it dangles, an
 * operator trigger is a whole phrase. A predicate form is matched against RUNNING TEXT to decide
 * that the words *around* a quoted literal mean "starts with" — so a one-character or function-word
 * form declares a filter in questions nobody meant one in, and no later stage can tell that from a
 * filter the user did write.
 *
 * **A deliberate twin.** `ttr-metadata` carries the same two lists at `/search/stop-words-<lang>.txt`
 * for its keyword search, and `StopWordParitySpec` (in `ttr-lexicon-compile`, the one module that
 * sees both) fails if they diverge. Two files rather than one edge, because this module is the lean
 * artifact the SERVING side reads and must not drag `ttr-metadata` in for a word list; one
 * vocabulary rather than two, because a word that is noise to keyword search is a word nobody
 * should hang a filter on either.
 *
 * Looked up on [TermNormalizer.fold] — the resource lists folded forms, so `že` and `ze` are one
 * entry, exactly as they are one anchor to the matcher.
 */
object LexiconStopWords {
    /** Resource path of one language's list. */
    private const val ROOT: String = "/lexicon-stopwords"

    /** The languages with a real list. [Lang.CS_EN] asks both, so it needs no list of its own. */
    private val BY_LANG: Map<String, Set<String>> =
        listOf("cs", "en").associateWith { lang ->
            val path = "$ROOT/stop-words-$lang.txt"
            val stream =
                requireNotNull(LexiconStopWords::class.java.getResourceAsStream(path)) {
                    // Shipped content: a missing file is a broken build of the toolchain itself,
                    // not something an author can fix. Failing loudly beats a guard that silently
                    // admits every word (the `LexiconStdlib` posture, same reason).
                    "lexicon stop-word list is missing $path"
                }
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map(TermNormalizer::fold)
                    .toSet()
            }
        }

    /** Every word listed for [lang]; for [Lang.CS_EN], the union — a form must clear BOTH. */
    fun of(lang: Lang): Set<String> =
        when (lang) {
            Lang.CS -> BY_LANG.getValue("cs")
            Lang.EN -> BY_LANG.getValue("en")
            Lang.CS_EN -> BY_LANG.getValue("cs") + BY_LANG.getValue("en")
        }

    /** True when [text] is a function word of [lang]. Folded on the way in. */
    fun isStop(
        text: String,
        lang: Lang,
    ): Boolean = TermNormalizer.fold(text) in of(lang)
}
