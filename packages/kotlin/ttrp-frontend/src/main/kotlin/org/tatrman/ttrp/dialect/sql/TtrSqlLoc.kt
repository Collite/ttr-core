// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.sql

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.tatrman.ttrp.ast.SourceLocation

/**
 * Remaps fragment-local ANTLR positions into the HOST document (C2-g: every dialect
 * span must be byte-precise in the enclosing `.ttrp`, feeding E-d provenance + C1-b
 * hover). The interior is byte-verbatim (C2-f), so the map is a straight shift by the
 * fragment start: offsets add [interior].offsetStart; the first interior line also
 * shifts columns by [interior].column.
 */
class TtrSqlLoc(
    private val interior: SourceLocation,
) {
    private fun line(localLine: Int): Int = interior.line + (localLine - 1)

    private fun col(
        localLine: Int,
        localCol: Int,
    ): Int = if (localLine == 1) interior.column + localCol else localCol

    fun of(token: Token): SourceLocation {
        val startLine = token.line
        val startCol = token.charPositionInLine
        val text = token.text ?: ""
        return SourceLocation(
            file = interior.file,
            line = line(startLine),
            column = col(startLine, startCol),
            endLine = line(startLine),
            endColumn = col(startLine, startCol) + text.length,
            offsetStart = interior.offsetStart + token.startIndex,
            offsetEnd = interior.offsetStart + token.stopIndex + 1,
        )
    }

    /** One character at a local line/column + char index (a lexer error has no token to point at). */
    fun at(
        localLine: Int,
        localCol: Int,
        index: Int,
    ): SourceLocation =
        SourceLocation(
            file = interior.file,
            line = line(localLine),
            column = col(localLine, localCol),
            endLine = line(localLine),
            endColumn = col(localLine, localCol) + 1,
            offsetStart = interior.offsetStart + index,
            offsetEnd = interior.offsetStart + index + 1,
        )

    fun of(ctx: ParserRuleContext): SourceLocation {
        val start = ctx.start ?: return interior
        val stop = ctx.stop ?: start
        return SourceLocation(
            file = interior.file,
            line = line(start.line),
            column = col(start.line, start.charPositionInLine),
            endLine = line(stop.line),
            endColumn = col(stop.line, stop.charPositionInLine) + (stop.text?.length ?: 0),
            offsetStart = interior.offsetStart + start.startIndex,
            offsetEnd = interior.offsetStart + stop.stopIndex + 1,
        )
    }
}
