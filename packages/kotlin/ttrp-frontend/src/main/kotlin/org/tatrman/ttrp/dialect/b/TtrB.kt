// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.tatrman.ttrp.ast.FragmentDecomposition
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.dialect.sql.TtrSqlLoc
import org.tatrman.ttrp.parser.TtrpParser

/**
 * The TTR-B fragment front door (T7.1.3/T7.1.7). Peeled `"""ttrb` / `"""ttrb-cs` interior +
 * host offset → canonical AST + host-mapped diagnostics. The [TtrbSkin] picks the keyword table
 * (AG B2) and the reject table (AG B6). A curated reject is named BEFORE any bare syntax error
 * (C2-g); a failed parse gets the most specific help the scanner can give; a clean parse decomposes
 * sentence-wise (C2-a-β). The interior is never rewritten (C2-f) — this parse is derived from the
 * verbatim `sourceText`.
 */
object TtrB {
    private val tables = HashMap<String, TtrbRejectTable>()

    /** The reject table of [skin] (`rejects.<lang>.yaml`). */
    fun rejects(skin: TtrbSkin): TtrbRejectTable =
        synchronized(tables) { tables.getOrPut(skin.lang) { TtrbRejectTable.load(skin.lang) } }

    /** The English reject table (the default skin's). */
    val rejectTable: TtrbRejectTable get() = rejects(TtrbSkin.EN)

    fun decompose(
        sourceText: String,
        interior: SourceLocation,
        outPort: String?,
        skin: TtrbSkin = TtrbSkin.EN,
        /** The container's IN ports — names in every sentence, never loaded (B7). */
        inPorts: Set<String> = emptySet(),
        /** The container's declared OUT ports; null when unknown (bare / corpus — no check). */
        outPorts: Set<String>? = null,
    ): FragmentDecomposition {
        val loc = TtrSqlLoc(interior)
        val parsed = TtrbSyntax.parse(sourceText, skin)
        val scanner = TtrbRejectScanner(rejects(skin), loc, skin)

        // 1) Curated reject scan — one primary diagnostic, wins over syntax noise.
        val reject = scanner.scan(parsed.tokens, parsed.indentProblems)
        if (reject != null) return FragmentDecomposition(emptyList(), listOf(reject), emptyList())

        // 2) Parse — a failed one gets the shape-specific help (B-102 / B-104…B-109) or B-004.
        parsed.syntaxErrors.firstOrNull()?.let {
            return FragmentDecomposition(emptyList(), listOf(scanner.syntax(it, parsed.tokens)), emptyList())
        }

        // 3) Decompose.
        val result = TtrbDecomposer(loc, TtrpParser.catalog, skin, inPorts, outPorts).decompose(parsed.tree, outPort)
        return FragmentDecomposition(
            result.statements,
            result.diagnostics,
            result.derivedInPorts,
            result.actionOutputs,
            result.outputPorts,
        )
    }
}
