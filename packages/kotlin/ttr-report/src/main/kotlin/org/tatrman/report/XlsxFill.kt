// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFRow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** C-8 behaviour implemented here independently; no service, investment model or runtime dependency. */
object XlsxFill {
    private val tableMarker = Regex("\\{\\{table:([A-Za-z_][A-Za-z0-9_]*)(?:\\|([^{}]+))?}}")
    private val scalarMarker = Regex("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}")

    private data class Region(
        val cell: Cell,
        val table: Table,
        val indices: List<Int>,
    )

    fun fill(
        template: ByteArray,
        data: ReportData,
    ): ByteArray = fillWorkbook(template, { data }, { "" }, mapOf("" to data))

    fun fillSheets(
        template: ByteArray,
        sheets: Map<String, ReportData>,
    ): ByteArray = fillWorkbook(template, { sheets[it] ?: ReportData() }, { "$it/" }, sheets.mapKeys { "${it.key}/" })

    private fun fillWorkbook(
        template: ByteArray,
        dataFor: (String) -> ReportData,
        scope: (String) -> String,
        required: Map<String, ReportData>,
    ): ByteArray {
        XSSFWorkbook(ByteArrayInputStream(template)).use { book ->
            for (sheet in required.keys.filter { it.isNotEmpty() }) {
                if (book.getSheet(sheet.removeSuffix("/")) ==
                    null
                ) {
                    throw ReportException("anchor-missing", "Template has no sheet '${sheet.removeSuffix("/")}'")
                }
            }
            val seenScalars = mutableSetOf<String>()
            val seenTables = mutableSetOf<String>()
            val regions = mutableListOf<Region>()
            for (sheet in book) {
                val data = dataFor(sheet.sheetName)
                val prefix = scope(sheet.sheetName)
                for (row in sheet) {
                    val rowRegions = mutableListOf<Region>()
                    for (cell in row) {
                        if (cell.cellType != CellType.STRING) continue
                        val text = cell.stringCellValue
                        val match = tableMarker.matchEntire(text)
                        if (match != null) {
                            val name = match.groupValues[1]
                            val table =
                                data.tables[name]
                                    ?: throw ReportException("anchor-missing", "Template table '$name' has no data")
                            val columns =
                                match.groups[2]
                                    ?.value
                                    ?.split(',')
                                    ?.map { it.trim() } ?: table.columns
                            val indices =
                                columns.map { column ->
                                    table.columns.indexOf(column).also {
                                        if (it <
                                            0
                                        ) {
                                            throw ReportException(
                                                "column-missing",
                                                "Table '$name' has no column '$column'",
                                            )
                                        }
                                    }
                                }
                            if (cell.columnIndex.toLong() + indices.size > SpreadsheetVersion.EXCEL2007.maxColumns) {
                                throw ReportException("template-invalid", "Table '$name' exceeds the XLSX column limit")
                            }
                            seenTables += prefix + name
                            rowRegions += Region(cell, table, indices)
                        } else {
                            if (text.contains(
                                    "{{table:",
                                )
                            ) {
                                throw ReportException(
                                    "template-invalid",
                                    "A table marker must occupy a whole cell: ${cell.address}",
                                )
                            }
                            scalarMarker.findAll(text).forEach { seenScalars += prefix + it.groupValues[1] }
                        }
                    }
                    if (rowRegions.size >
                        1
                    ) {
                        throw ReportException(
                            "template-invalid",
                            "Tables must be stacked, not share a row in '${sheet.sheetName}'",
                        )
                    }
                    regions += rowRegions
                }
            }
            val missing =
                required.flatMap { (prefix, data) ->
                    (data.tables.keys.map { prefix + it } - seenTables) +
                        (data.scalars.keys.map { prefix + it } - seenScalars)
                }
            if (missing.isNotEmpty()) {
                throw ReportException(
                    "anchor-missing",
                    "Template is missing anchors: ${missing.sorted().joinToString()}",
                )
            }
            for (sheet in book) {
                val data = dataFor(sheet.sheetName)
                val growth =
                    regions.filter { it.cell.sheet === sheet }.sumOf {
                        (it.table.rows.size - 1)
                            .coerceAtLeast(
                                0,
                            ).toLong()
                    }
                if (sheet.lastRowNum.toLong() + growth >= SpreadsheetVersion.EXCEL2007.maxRows) {
                    throw ReportException(
                        "template-invalid",
                        "Tables exceed the XLSX row limit in '${sheet.sheetName}'",
                    )
                }
                // Substitute only authored text. A value that happens to contain braces is data.
                for (row in sheet) {
                    for (cell in row) {
                        if (cell.cellType == CellType.STRING &&
                            !tableMarker.matches(cell.stringCellValue) &&
                            scalarMarker.containsMatchIn(cell.stringCellValue)
                        ) {
                            writeText(
                                cell,
                                scalarMarker.replace(
                                    cell.stringCellValue,
                                ) { data.scalars[it.groupValues[1]].orEmpty() },
                            )
                        }
                    }
                }
            }
            // Expand bottom-up: a lower region is filled before an upper one shifts its whole rows.
            for (region in regions.asReversed()) expand(region)
            return writeDeterministic(book)
        }
    }

    /** Shared by generated templates and filled workbooks; does not close the caller's workbook. */
    internal fun writeDeterministic(book: XSSFWorkbook): ByteArray {
        book.properties.coreProperties.apply {
            setCreated(Optional.empty())
            setModified(Optional.empty())
            setLastPrinted(Optional.empty())
            creator = null
            lastModifiedByUser = null
        }
        book.properties.extendedProperties.underlyingProperties.apply {
            if (isSetApplication) unsetApplication()
            if (isSetAppVersion) unsetAppVersion()
        }
        book.setForceFormulaRecalculation(true)
        val out = ByteArrayOutputStream().also(book::write).toByteArray()
        return deterministicPackage(out)
    }

    private fun expand(region: Region) {
        val anchor = region.cell
        val row = anchor.row
        val sheet = anchor.sheet
        val styles = region.indices.indices.map { row.getCell(anchor.columnIndex + it)?.cellStyle ?: anchor.cellStyle }
        // Borrowed appearance does not confer a type. In particular, keep account codes' leading zeroes.
        val typed = region.indices.indices.map { row.getCell(anchor.columnIndex + it) != null }
        val authoredRow = (row as XSSFRow).ctRow
        val height =
            if (authoredRow.isSetHt &&
                authoredRow.isSetCustomHeight &&
                authoredRow.customHeight
            ) {
                authoredRow.ht
            } else {
                null
            }
        if (region.table.rows.size > 1 && row.rowNum < sheet.lastRowNum) {
            sheet.shiftRows(row.rowNum + 1, sheet.lastRowNum, region.table.rows.size - 1, true, false)
        }
        if (region.table.rows.isEmpty()) {
            for (j in region.indices.indices) row.getCell(anchor.columnIndex + j)?.setBlank()
            anchor.setBlank()
        }
        region.table.rows.forEachIndexed { i, values ->
            val target = sheet.getRow(row.rowNum + i) ?: sheet.createRow(row.rowNum + i)
            val targetRow = (target as XSSFRow).ctRow
            if (height != null) {
                targetRow.ht = height
                targetRow.customHeight = true
            } else {
                if (targetRow.isSetHt) targetRow.unsetHt()
                if (targetRow.isSetCustomHeight) targetRow.unsetCustomHeight()
            }
            region.indices.forEachIndexed { j, index ->
                val cell: Cell = target.getCell(anchor.columnIndex + j) ?: target.createCell(anchor.columnIndex + j)
                cell.cellStyle = styles[j]
                writeValue(cell, values[index], typed[j])
            }
        }
    }

    private fun writeValue(
        cell: Cell,
        value: String?,
        typed: Boolean,
    ) {
        if (value.isNullOrEmpty()) {
            cell.setBlank()
            return
        }
        checkTextLength(value)
        // POI retains inlineStr storage on a string-to-string update. Clear it before assigning.
        cell.setBlank()
        val format = cell.cellStyle.dataFormatString
        when {
            !typed || format == "General" || format == "@" -> cell.setCellValue(value)
            DateUtil.isADateFormat(cell.cellStyle.dataFormat.toInt(), format) -> {
                val date =
                    runCatching {
                        when {
                            value.length == 10 -> LocalDate.parse(value).atStartOfDay()
                            value.endsWith("Z") -> LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC)
                            else -> LocalDateTime.parse(value)
                        }
                    }.getOrNull()
                if (date != null) cell.setCellValue(date) else cell.setCellValue(value)
            }
            else -> {
                val number = value.toDoubleOrNull()?.takeIf { it.isFinite() }
                if (number != null) cell.setCellValue(number) else cell.setCellValue(value)
            }
        }
    }

    private fun checkTextLength(value: String) {
        if (value.length > SpreadsheetVersion.EXCEL2007.maxTextLength) {
            throw ReportException("value-too-long", "Cell value exceeds Excel's 32,767-character limit")
        }
    }

    private fun writeText(
        cell: Cell,
        value: String,
    ) {
        checkTextLength(value)
        cell.setBlank()
        cell.setCellValue(value)
    }

    /** Stable ZIP records across clocks/time zones/JDK compression versions; XML retains authored styles. */
    internal fun deterministicPackage(bytes: ByteArray): ByteArray {
        val parts = sortedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) parts[entry.name] = zip.readBytes()
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in parts) {
                val entry =
                    ZipEntry(name).apply {
                        // The DOS epoch boundary makes the JDK add a timezone-sensitive extended timestamp.
                        setTimeLocal(LocalDateTime.of(1980, 1, 2, 0, 0))
                        method = ZipEntry.STORED
                        size = content.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(content) }.value
                    }
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
