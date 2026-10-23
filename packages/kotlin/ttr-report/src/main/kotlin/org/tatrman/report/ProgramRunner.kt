// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import java.nio.file.Path

data class Caller(
    val userId: String,
    val roles: List<String> = emptyList(),
    val bearer: String? = null,
)

fun interface ProgramRunner {
    fun run(
        program: String,
        params: Map<String, Any?>,
        caller: Caller?,
    ): Table

    /** Offline files bind by report anchor; a door runner uses the same named program for each anchor. */
    fun forAnchor(anchor: String): ProgramRunner = this
}

class RowsProgramRunner(
    private val files: Map<String, Path>,
) : ProgramRunner {
    override fun forAnchor(anchor: String): ProgramRunner = ProgramRunner { _, _, _ -> read(anchor) }

    override fun run(
        program: String,
        params: Map<String, Any?>,
        caller: Caller?,
    ): Table {
        if (files.size !=
            1
        ) {
            throw ReportException("rows-missing", "Select a report anchor when more than one CSV is supplied")
        }
        return read(files.keys.single())
    }

    private fun read(anchor: String): Table {
        val file = files[anchor] ?: throw ReportException("rows-missing", "No CSV supplied for anchor '$anchor'")
        try {
            val records =
                csv(
                    java.nio.file.Files
                        .readString(file)
                        .removePrefix("\uFEFF"),
                )
            if (records.isEmpty()) throw IllegalArgumentException("CSV must have a header")
            return Table(records.first(), records.drop(1))
        } catch (e: java.io.IOException) {
            throw ReportException("rows-missing", "Cannot read CSV for '$anchor': ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ReportException("rows-invalid", "Invalid CSV for '$anchor': ${e.message}", e)
        }
    }

    /** RFC 4180 fields, including embedded CR/LF and doubled quotes; blank cells are empty text. */
    private fun csv(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var started = false
        var i = 0

        fun endField() {
            row += field.toString()
            field.setLength(0)
            closed = false
            started = false
        }
        while (i < text.length) {
            val c = text[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < text.length && text[i] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                        closed = true
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    ',' -> endField()
                    '\r', '\n' -> {
                        if (c == '\r' && i < text.length && text[i] == '\n') {
                            i++
                        }
                        endField()
                        records += row.toList()
                        row.clear()
                    }
                    '"' -> {
                        require(!started && !closed) { "quote inside an unquoted field" }
                        started = true
                        quoted = true
                    }
                    else -> {
                        require(!closed) { "text after a quoted field" }
                        started = true
                        field.append(c)
                    }
                }
            }
        }
        require(!quoted) { "unterminated quoted field" }
        if (started || closed || row.isNotEmpty()) {
            endField()
            records += row.toList()
        }
        return records
    }
}
