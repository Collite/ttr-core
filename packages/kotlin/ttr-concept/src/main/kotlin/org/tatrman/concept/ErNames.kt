// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.translator.parser.impl.CalciteExtParserImpl
import java.io.StringReader

/**
 * The er renderer's names (CN-P3 S3.4 T2; S0.3 §3 rule 1, A-CN-12): an entity, an attribute or an alias is written as
 * it is named, and double-quoted exactly when the translator's SQL parser would not read it bare — a reserved word of
 * its parser (`"order"`, `"condition"`, `"at"`, `"value"`), or a name that is not a plain identifier. The reserved set is
 * the parser's own ([CalciteExtParserImpl], the parser `ttr-translator` reads SQL with), so a Calcite bump that reserves
 * a new word changes it — `ErNamesSpec` pins the list and fails loudly when it moves.
 */
internal object ErNames {
    private val PLAIN = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The translator parser's reserved words, upper case, sorted. */
    val reserved: Set<String> by lazy {
        val metadata = CalciteExtParserImpl.FACTORY.getParser(StringReader("")).metadata
        metadata.tokens.filter { metadata.isReservedWord(it) }.toSortedSet()
    }

    /** Whether [name] needs double quotes to read as itself. */
    fun quoted(name: String): Boolean = !PLAIN.matches(name) || name.uppercase() in reserved

    /** [name] as the rendering writes it. */
    fun sql(name: String): String = if (quoted(name)) "\"" + name.replace("\"", "\"\"") + "\"" else name
}
