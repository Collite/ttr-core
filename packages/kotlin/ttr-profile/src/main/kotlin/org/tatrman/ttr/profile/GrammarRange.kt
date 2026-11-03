// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import org.tatrman.ttr.yaml.YamlData
import java.math.BigInteger

/** Whitespace joins comparators with AND; || joins sets with OR. No npm range shorthands. */
class GrammarRange private constructor(
    val text: String,
    private val alternatives: List<List<Pair<String, Version>>>,
) {
    override fun equals(other: Any?): Boolean = other is GrammarRange && text == other.text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text

    fun admits(grammar: String = runningGrammar): Boolean {
        val actual = version(grammar)
        return alternatives.any { comparators ->
            comparators.all { (operator, expected) ->
                val comparison = actual.compareTo(expected)
                when (operator) {
                    ">" -> comparison > 0
                    ">=" -> comparison >= 0
                    "<" -> comparison < 0
                    "<=" -> comparison <= 0
                    else -> comparison == 0
                }
            }
        }
    }

    private data class Version(
        val major: BigInteger,
        val minor: BigInteger,
        val patch: BigInteger,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    }

    companion object {
        val runningGrammar: String get() = YamlData.grammarVersion
        private val versionPattern = Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")
        private val comparator = Regex("(?:(>=|<=|>|<|=)\\s*)?([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)")

        private fun version(text: String): Version {
            require(versionPattern.matches(text)) { "Expected a version with two or three numeric components" }
            val parts = text.split('.').map(String::toBigInteger)
            return Version(parts[0], parts[1], parts.getOrElse(2) { BigInteger.ZERO })
        }

        fun parse(text: String): GrammarRange {
            val alternatives =
                text.split("||").map { set ->
                    val value = set.trim()
                    require(value.isNotEmpty()) { "Empty grammar comparator set" }
                    val matches = comparator.findAll(value).toList()
                    var end = 0
                    val comparators =
                        matches.map { match ->
                            require(
                                (end == 0 && match.range.first == 0) ||
                                    (
                                        match.range.first > end &&
                                            value.substring(end, match.range.first).all(Char::isWhitespace)
                                    ),
                            ) { "Comparators must be separated by whitespace" }
                            end = match.range.last + 1
                            (match.groupValues[1].ifEmpty { "=" }) to version(match.groupValues[2])
                        }
                    require(comparators.isNotEmpty() && end == value.length) { "Unsupported grammar range: $text" }
                    comparators
                }
            return GrammarRange(text, alternatives)
        }
    }
}
