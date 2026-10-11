// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

/**
 * Emits the runtime script for a **decomposed Postgres island** as a Python
 * `adbc_driver_postgresql` program (S3.5 T3.5.4). A PG island that must both ingest a CSV and
 * export Arrow can't be a plain `psql -f` — Arrow export needs the query and its session-local
 * temp tables on a **single ADBC connection** (`fetch_arrow_table`). So the invocation binding for
 * such an island is `python3` (run.sh already dispatches python3 islands), and the compute still
 * runs server-side in Postgres — a genuine second engine for A4.
 *
 * Structure:
 *  1. one connection from `TTR_CONN_<ENGINE>`;
 *  2. [SqlTemp]s — `CREATE TEMP TABLE <t> AS <sql>` (e.g. the same-engine `accounts` handoff:
 *     acc_prep's fragment SQL inlined, no cross-session staging);
 *  3. [CsvTemp]s — `CREATE TEMP TABLE` + typed `pyarrow.csv.read_csv` + `adbc_ingest` (COPY);
 *  4. [Output]s — one `execute` + `fetch_arrow_table` + Arrow-IPC write per container OUT port
 *     (`out/…` display, `staging/…` store — the same sinks the Polars variant writes).
 *
 * SQL is embedded in Python triple-quoted strings (the SQL uses `"` for identifiers and `'` for
 * literals — never `\"\"\"`), so no escaping is needed.
 */
class PgAdbcIslandEmitter {
    /** A temp table materialized from a SQL query (`CREATE TEMP TABLE <table> AS <sql>`). */
    data class SqlTemp(
        val table: String,
        val sql: String,
    )

    /** A temp table populated from a CSV via typed read + `adbc_ingest` (COPY, `temporary=True`). */
    data class CsvTemp(
        val table: String,
        val csvPath: String,
        val columns: List<PgColumn>,
    )

    /** One CSV column: name + the pyarrow read type (the temp table's PG types are inferred from it). */
    data class PgColumn(
        val name: String,
        val arrowType: String,
    )

    /**
     * One island output: the terminal SQL and the Arrow sink path (`out/…` / `staging/…`). An action display's
     * output carries its [arrowSchema] (`name → pyarrow type`, [org.tatrman.ttrp.emit.core.DisplayTypes.arrow]):
     * the fetched table is cast to it before it is written, so its file has the schema every other source of the
     * display writes (ADBC returns `NUMERIC` as an opaque extension type and text as `string`).
     */
    data class Output(
        val sql: String,
        val sinkPath: String,
        val arrowSchema: List<Pair<String, String>>? = null,
    )

    fun emit(
        connEnv: String,
        sqlTemps: List<SqlTemp>,
        csvTemps: List<CsvTemp>,
        outputs: List<Output>,
        counts: List<SiteCountQueries> = emptyList(),
    ): String =
        buildString {
            appendLine("import os")
            appendLine("import adbc_driver_postgresql.dbapi as _dbapi")
            appendLine("import pyarrow as _pa")
            appendLine("import pyarrow.csv as _pacsv")
            appendLine("import pyarrow.ipc as _paipc")
            appendLine()
            appendLine("def _write_ipc(_table, _path):")
            appendLine("    with _pa.OSFile(_path, \"wb\") as _f:")
            appendLine("        with _paipc.new_file(_f, _table.schema) as _w:")
            appendLine("            _w.write_table(_table)")
            appendLine()
            appendLine("_conn = _dbapi.connect(os.environ[${pyStr(connEnv)}])")
            appendLine("try:")
            appendLine("    _cur = _conn.cursor()")
            sqlTemps.forEach { t ->
                appendLine("    # temp table: ${t.table} (from SQL)")
                appendLine("    _cur.execute(${pySql("CREATE TEMP TABLE \"${t.table}\" AS\n${t.sql}")})")
            }
            csvTemps.forEach { t ->
                val types = t.columns.joinToString(", ") { "${pyStr(it.name)}: ${it.arrowType}" }
                appendLine("    # temp table: ${t.table} (typed CSV → adbc_ingest COPY, temporary)")
                // strings_can_be_null: an empty CSV field → null (matches Polars read_csv), so the
                // crunch's `customer is not null` filter drops it on both engines.
                appendLine(
                    "    _t_${t.table} = _pacsv.read_csv(${pyStr(t.csvPath)}, " +
                        "convert_options=_pacsv.ConvertOptions(column_types={$types}, strings_can_be_null=True))",
                )
                appendLine("    _cur.adbc_ingest(${pyStr(t.table)}, _t_${t.table}, mode=\"create\", temporary=True)")
            }
            outputs.forEachIndexed { i, o ->
                appendLine("    # output → ${o.sinkPath}")
                appendLine("    _cur.execute(${pySql(o.sql)})")
                val table =
                    o.arrowSchema?.let { fields ->
                        val schema = fields.joinToString(", ") { (name, type) -> "(${pyStr(name)}, $type)" }
                        "_cur.fetch_arrow_table().cast(_pa.schema([$schema]))"
                    } ?: "_cur.fetch_arrow_table()"
                appendLine("    _write_ipc($table, ${pyStr(o.sinkPath)})")
            }
            if (counts.isNotEmpty()) appendCounts(counts)
            appendLine("    _conn.commit()")
            appendLine("finally:")
            appendLine("    _conn.close()")
        }

    /**
     * A run-time `counts.json` writer (RJ-P5 eighth conform point): per reject site, run the three
     * independent `count(*)` queries on the same session (temp tables in scope) and write the
     * `(in, processed, rejects)` triple. Balances the same three numbers Polars writes, so the
     * cross-engine partition check has both engines' honest counts.
     */
    private fun StringBuilder.appendCounts(counts: List<SiteCountQueries>) {
        appendLine("    # --- ttrp partition counts (RJ-P5 eighth conform point) ---")
        appendLine("    import json as _json")
        appendLine("    _ttrp_sites = []")
        counts.forEach { c ->
            appendLine("    _cur.execute(${pySql(c.inCount)})")
            appendLine("    _ttrp_in = _cur.fetchone()[0]")
            appendLine("    _cur.execute(${pySql(c.processed)})")
            appendLine("    _ttrp_proc = _cur.fetchone()[0]")
            appendLine("    _cur.execute(${pySql(c.rejects)})")
            appendLine("    _ttrp_rej = _cur.fetchone()[0]")
            appendLine(
                "    _ttrp_sites.append({\"site\": ${pyStr(c.site)}, \"in\": _ttrp_in, " +
                    "\"processed\": _ttrp_proc, \"rejects\": _ttrp_rej})",
            )
        }
        appendLine("    with open(\"counts.json\", \"w\") as _ttrp_f:")
        appendLine("        _json.dump({\"sites\": _ttrp_sites}, _ttrp_f, indent=2)")
    }

    private fun pyStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** SQL in a Python triple-quoted string (SQL never contains `\"\"\"`). */
    private fun pySql(sql: String): String = "\"\"\"\n${sql.trimEnd()}\n\"\"\""

    companion object {
        /**
         * db-schema type spelling → the pyarrow read type for a CSV temp column (PG type inferred). A decimal
         * reads at its declared precision/scale, else the toolchain default `decimal128(19, 2)`
         * ([org.tatrman.ttrp.resolve.ColumnType]) — the same type the Polars island reads the CSV as.
         */
        fun pgColumn(
            name: String,
            spelling: String,
        ): PgColumn {
            val type =
                org.tatrman.ttrp.resolve.ColumnType
                    .parse(spelling)
            return when (
                spelling
                    .substringBefore('(')
                    .trim()
                    .lowercase()
                    .removePrefix("{")
            ) {
                "int", "integer", "bigint", "smallint", "tinyint", "long" -> PgColumn(name, "_pa.int64()")
                "float", "double", "real", "number" -> PgColumn(name, "_pa.float64()")
                "bool", "boolean" -> PgColumn(name, "_pa.bool_()")
                "date" -> PgColumn(name, "_pa.date32()")
                "time", "timestamp", "datetime" -> PgColumn(name, "_pa.timestamp(\"us\", \"UTC\")")
                else ->
                    if (type?.kind == org.tatrman.ttrp.resolve.ColumnType.Kind.DECIMAL) {
                        PgColumn(name, "_pa.decimal128(${type.precision}, ${type.scale})")
                    } else {
                        PgColumn(name, "_pa.string()")
                    }
            }
        }
    }
}
