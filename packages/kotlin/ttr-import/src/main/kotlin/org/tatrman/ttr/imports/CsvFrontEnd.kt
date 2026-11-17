// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileSpan
import java.nio.file.Path

/**
 * MW-P5 S5.2 T2 (contracts §1) — the `csv` front-end: an RFC 4180 file (comma-separated, `"`-quoted, `""` inside quotes,
 * CRLF or LF line ends, quoted fields may span lines) becomes the tree `{rows: [<row>…]}`, one mapping per data row whose
 * keys are the header row's names in header order. A profile iterates the rows with `kinds at: /rows` and picks files with
 * its `files` globs, as for any other input.
 *
 * Pure parsing, nothing interpreted: every cell is a string, exactly as written (no trimming, no number or list
 * splitting); an empty cell is absent from its row, because a CSV cannot tell an empty string from no value. An empty
 * line is no row. The header must name every column once and non-empty; a row with a different number of cells, an
 * unterminated quote, a quote inside an unquoted cell or text after a closing quote is `ys/profile-invalid` at its line
 * and column. Each row's mapping sits on the tree line of the row's first CSV line, so a diagnostic on a row names the
 * CSV line the author sees. The input is read as [JsonFrontEnd] reads one (strict UTF-8, the reader budget, a leading
 * byte-order mark dropped); the budget applies again to the tree, whose keys repeat on every row.
 */
object CsvFrontEnd : FrontEnd {
    override val name = "csv"

    override val records = "/rows"

    override fun parse(files: List<Path>): List<ForeignTree> =
        labelled(files).map { (file, name) -> ForeignTree.read(tree(JsonFrontEnd.text(file), name), name, name) }

    /** One CSV record: the line it starts on and its cells in order. */
    internal class Record(
        val line: Int,
        val cells: List<String>,
    )

    /** The `{rows: […]}` tree as JSON text, each row on the line its CSV record starts on. */
    internal fun tree(
        text: String,
        label: String,
    ): String {
        val records = records(text, label)
        val header = records.firstOrNull() ?: return "{\"rows\": []}"
        header.cells.forEachIndexed { index, cell ->
            if (cell.isEmpty()) fail(label, header.line, 1, "Header column ${index + 1} has no name")
            if (header.cells.indexOf(cell) != index) fail(label, header.line, 1, "Header names column $cell twice")
        }
        return buildString {
            append("{\"rows\": [")
            var line = 1
            records.drop(1).forEachIndexed { index, record ->
                if (record.cells.size != header.cells.size) {
                    fail(
                        label,
                        record.line,
                        1,
                        "Row has ${record.cells.size} cells; the header names ${header.cells.size} columns",
                    )
                }
                if (index > 0) append(',')
                while (line < record.line) {
                    append('\n')
                    line++
                }
                append('{')
                header.cells
                    .zip(record.cells)
                    .filter { (_, cell) -> cell.isNotEmpty() }
                    .joinTo(this, ", ") { (key, cell) -> "${JsonPrimitive(key)}: ${JsonPrimitive(cell)}" }
                append('}')
            }
            append("\n]}\n")
        }
    }

    /** The file's records, header first; an empty line is no record. */
    internal fun records(
        text: String,
        label: String,
    ): List<Record> {
        val records = mutableListOf<Record>()
        var i = 0
        var line = 1
        var column = 1

        fun lineEnd(at: Int): Int =
            when {
                at >= text.length -> 0
                text[at] == '\n' -> 1
                text[at] == '\r' -> if (at + 1 < text.length && text[at + 1] == '\n') 2 else 1
                else -> -1
            }

        while (i < text.length) {
            val end = lineEnd(i)
            if (end > 0) {
                i += end
                line++
                column = 1
                continue
            }
            val start = line
            val cells = mutableListOf<String>()
            while (true) {
                val cell = StringBuilder()
                if (i < text.length && text[i] == '"') {
                    val openLine = line
                    val openColumn = column
                    i++
                    column++
                    while (true) {
                        if (i >= text.length) fail(label, openLine, openColumn, "Unterminated quoted cell")
                        val c = text[i]
                        if (c == '"') {
                            if (i + 1 < text.length && text[i + 1] == '"') {
                                cell.append('"')
                                i += 2
                                column += 2
                                continue
                            }
                            i++
                            column++
                            break
                        }
                        val newline = lineEnd(i)
                        if (newline > 0) {
                            cell.append(text, i, i + newline)
                            i += newline
                            line++
                            column = 1
                        } else {
                            cell.append(c)
                            i++
                            column++
                        }
                    }
                    if (i < text.length && text[i] != ',' && lineEnd(i) < 0) {
                        fail(label, line, column, "Text after a closing quote")
                    }
                } else {
                    while (i < text.length && text[i] != ',' && lineEnd(i) < 0) {
                        if (text[i] == '"') fail(label, line, column, "A quote inside an unquoted cell")
                        cell.append(text[i])
                        i++
                        column++
                    }
                }
                cells += cell.toString()
                if (i < text.length && text[i] == ',') {
                    i++
                    column++
                    continue
                }
                break
            }
            records += Record(start, cells)
        }
        return records
    }

    private fun fail(
        label: String,
        line: Int,
        column: Int,
        detail: String,
    ): Nothing =
        throw ProfileException("ys/profile-invalid", ProfileSpan(label, line, column, line, column, ""), detail)
}
