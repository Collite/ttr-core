// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

data class ReportIssue(
    val code: String,
    val message: String,
)

class ReportException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Rows retain the program's column order; SQL NULL is represented by null. */
data class Table(
    val columns: List<String>,
    val rows: List<List<String?>>,
) {
    init {
        require(columns.isNotEmpty() && columns.none { it.isBlank() }) { "a table must name its columns" }
        require(columns.distinct().size == columns.size) { "table column names must be unique" }
        require(rows.all { it.size == columns.size }) { "every row must match the table column count" }
    }
}

data class ReportData(
    val scalars: Map<String, String> = emptyMap(),
    val tables: Map<String, Table> = emptyMap(),
)

data class TableSpec(
    val columns: List<String>,
    val limit: Int? = null,
)

data class SheetSpec(
    val sheet: String,
    val scalars: Map<String, String> = emptyMap(),
    val tables: Map<String, TableSpec>,
)

data class HistorySpec(
    val keep: String = "90d",
)

enum class ParamKind(
    val wire: String,
) {
    TEXT("text"),
    INT("int"),
    FLOAT("float"),
    BOOL("bool"),
    DATETIME("datetime"),
    ENTITY("entity"),
}

data class PickerSpec(
    val program: String,
    val label: String? = null,
    val value: String? = null,
)

data class ParamSpec(
    val name: String,
    val kind: ParamKind,
    val required: Boolean = false,
    val default: Any? = null,
    val picker: PickerSpec? = null,
    val label: Map<String, String> = emptyMap(),
)

data class ReportSpec(
    val id: String,
    val title: Map<String, String>,
    val version: Int = 1,
    val program: String,
    val params: List<ParamSpec>,
    val refresh: String? = null,
    val sheets: List<SheetSpec>,
    val history: HistorySpec = HistorySpec(),
    val roles: List<String> = emptyList(),
)

interface ModelLookup {
    fun programExists(qname: String): Boolean

    fun outputColumns(qname: String): List<String>
}
