// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.suggest

import kotlin.math.max
import kotlin.math.min

/**
 * Phase 08 C2 — "did you mean…?" suggestions for misspelt identifiers.
 *
 * Given an unknown identifier (table or column name) parsed out of a Calcite validation error,
 * scan a small corpus of known names for nearest matches by case-insensitive Levenshtein
 * distance. Returns up to [limit] suggestions sorted by ascending distance, with ties broken by
 * the candidate's natural alphabetical order for determinism.
 *
 * Tuning:
 *   - **Threshold:** a candidate qualifies only when its distance is ≤
 *     `max(2, ceil(badName.length * 0.4))`. So `customer` ~ `custmer` (dist=1) qualifies easily;
 *     `customer` ~ `xyz` (dist=8) does not. The 0.4 ratio is the standard "typo budget" used by
 *     many spell-checkers; bumping it widens false positives.
 *   - **Case:** comparison is case-insensitive (SQL identifiers usually case-fold in practice);
 *     the returned suggestion preserves the candidate's original casing.
 *
 * Designed to be cheap: O(N × |badName| × max_candidate_length) where N is the corpus size.
 * For our v1 models (< 10k identifiers) this is negligible; if the corpus ever blows past
 * 100k, swap the implementation for a BK-tree or symspell.
 */
object IdentifierSuggester {
    fun suggest(
        badName: String,
        corpus: Collection<String>,
        limit: Int = 3,
    ): List<String> {
        if (badName.isBlank() || corpus.isEmpty()) return emptyList()
        val needle = badName.lowercase()
        val maxDist = max(2, kotlin.math.ceil(badName.length * 0.4).toInt())
        return corpus
            .asSequence()
            .map { candidate -> candidate to distance(needle, candidate.lowercase()) }
            .filter { (_, d) -> d <= maxDist }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .map { it.first }
            .distinct()
            .take(limit)
            .toList()
    }

    /** Standard Levenshtein distance (insert/delete/substitute = 1). Iterative, O(|a|·|b|). */
    private fun distance(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] =
                    min(
                        min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost,
                    )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}
