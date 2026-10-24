// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

object HtmlTable {
    fun render(
        table: Table,
        limit: Int = 500,
    ): String {
        require(limit >= 0) { "preview limit must be non-negative" }
        return buildString {
            append("<table><thead><tr>")
            table.columns.forEach { append("<th>").append(escape(it)).append("</th>") }
            append("</tr></thead><tbody>")
            table.rows.take(limit).forEach { row ->
                append("<tr>")
                row.forEach { append("<td>").append(escape(it.orEmpty())).append("</td>") }
                append("</tr>")
            }
            append("</tbody></table>")
            if (table.rows.size > limit) append("<p>Showing $limit of ${table.rows.size} rows</p>")
        }
    }

    private fun escape(text: String): String =
        buildString {
            for (c in text) {
                append(
                    when (c) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> c.toString()
                    },
                )
            }
        }
}
