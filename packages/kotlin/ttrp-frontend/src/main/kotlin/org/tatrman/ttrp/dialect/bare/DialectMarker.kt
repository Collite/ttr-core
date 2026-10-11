// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.bare

/**
 * Dialect marker resolution for bare-fragment programs (contracts §1, C0). Resolution
 * order is fully EXPLICIT — zero content sniffing (P2):
 *   1. a first-line comment override `-- ttr: dialect=sql` / `# ttr: dialect=pandas` /
 *      `# ttr: dialect=b [lang=<xx>]` (tolerating leading whitespace only) — WINS over the
 *      extension (C3-g-ii). TTR-B's `lang` picks the keyword skin (AG B2): `cs` → `ttrb-cs`,
 *      `en` or none → `ttrb`, any other → `ttrb-<xx>` (an unknown dialect downstream, FRG-001).
 *      `dialect=ttrb` is accepted as the pre-B2 spelling of `dialect=b`;
 *   2. the extension `.ttr.sql` → sql, `.ttr.py` → pandas, `.ttrb` → ttrb, `.ttrb-cs` → ttrb-cs;
 *   3. neither → null (the caller emits TTRP-FRG-002).
 */
object DialectMarker {
    private val commentRe =
        Regex("""^\s*(--|#)\s*ttr:\s*dialect\s*=\s*(sql|pandas|ttrb|b)(?:\s+lang\s*=\s*([A-Za-z]+))?\s*$""")

    /** The dialect for [fileName] with [content], or null if unmarked. */
    fun resolve(
        fileName: String,
        content: String,
    ): String? {
        commentOverride(content)?.let { return it }
        return extensionDialect(fileName)
    }

    fun commentOverride(content: String): String? {
        val firstLine = content.lineSequence().firstOrNull() ?: return null
        val m = commentRe.find(firstLine) ?: return null
        val dialect = m.groupValues[2]
        val lang = m.groupValues[3].lowercase()
        return when (dialect) {
            "b", "ttrb" -> if (lang.isEmpty() || lang == "en") "ttrb" else "ttrb-$lang"
            else -> dialect
        }
    }

    fun extensionDialect(fileName: String): String? {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".ttr.sql") -> "sql"
            lower.endsWith(".ttr.py") -> "pandas"
            lower.endsWith(".ttrb") -> "ttrb"
            lower.endsWith(".ttrb-cs") -> "ttrb-cs"
            else -> null
        }
    }

    /** True for a file that TTR-P should route to the bare-fragment path (not `.ttrp`). */
    fun isBareFragmentFile(
        fileName: String,
        content: String,
    ): Boolean = resolve(fileName, content) != null
}
