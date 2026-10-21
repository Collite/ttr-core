// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import org.tatrman.translate.v1.SqlDialect
import org.tatrman.ttrp.emit.recipe.RecipeStatement
import org.tatrman.ttrp.emit.recipe.RecipeStatements
import org.tatrman.ttrp.emit.sql.SqlTier
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.recipe.RecipeModel
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The values a recipe run binds (AU-core-P2 · S2.4; contracts §5.2 `bindings`): `:as_of`, every parameter's declared
 * value, every derived instant (`:as_of_minus_365_days`, AU-C-4) computed from `:as_of` — what the Server's runner
 * binds, here for running a recipe's statements outside it.
 */
object RecipeBindings {
    fun of(
        model: RecipeModel,
        asOf: LocalDateTime,
    ): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out[RecipeStatements.AS_OF] = asOf
        model.parameterDecls.forEach { p ->
            out[p.name] =
                when (p.type) {
                    "decimal" -> p.value?.let { BigDecimal(it) }
                    "days", "working_days", "int" -> p.value?.toInt()
                    else -> p.value
                }
        }
        model.derivedParams.forEach { d ->
            val n =
                d.amount.toLongOrNull() ?: model.parameterDecls
                    .first { it.name == d.amount }
                    .value!!
                    .toLong()
            val signed = if (d.op == "minus") -n else n
            out[d.name] =
                when (d.unit) {
                    "days" -> asOf.plusDays(signed)
                    "weeks" -> asOf.plusWeeks(signed)
                    "months" -> asOf.plusMonths(signed)
                    "hours" -> asOf.plusHours(signed)
                    else -> asOf.plusMinutes(signed)
                }
        }
        return out
    }
}

/** JDBC plumbing shared by the recipe engines: a script loaded, a statement bound and read as a [ConformTable]. */
object JdbcStatements {
    /** `:name` placeholders → `?` in order of appearance (outside quotes), with the names in that order. */
    fun positional(sql: String): Pair<String, List<String>> {
        val out = StringBuilder()
        val names = mutableListOf<String>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' || c == '"' -> {
                    var j = i + 1
                    while (j < sql.length && !(sql[j] == c && (j + 1 >= sql.length || sql[j + 1] != c))) {
                        j += if (sql[j] == c) 2 else 1
                    }
                    out.append(sql, i, minOf(j + 1, sql.length))
                    i = j + 1
                }
                c == ':' && i + 1 < sql.length && sql[i + 1] == ':' -> {
                    out.append("::")
                    i += 2
                }
                c == ':' && i + 1 < sql.length && (sql[i + 1].isLetter() || sql[i + 1] == '_') -> {
                    var j = i + 1
                    while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                    names += sql.substring(i + 1, j)
                    out.append('?')
                    i = j
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString() to names
    }

    /** [script]'s statements, split at every `;` outside a quote or a `--` comment (comments dropped). */
    fun split(script: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < script.length) {
            val c = script[i]
            when {
                c == '-' && i + 1 < script.length && script[i + 1] == '-' -> {
                    while (i < script.length && script[i] != '\n') i++
                }
                c == '\'' || c == '"' -> {
                    var j = i + 1
                    while (j < script.length && !(script[j] == c && (j + 1 >= script.length || script[j + 1] != c))) {
                        j += if (script[j] == c) 2 else 1
                    }
                    cur.append(script, i, minOf(j + 1, script.length))
                    i = j + 1
                }
                c == ';' -> {
                    cur
                        .toString()
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { out += it }
                    cur.clear()
                    i++
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        cur
            .toString()
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { out += it }
        return out
    }

    fun load(
        conn: Connection,
        script: String,
    ) {
        conn.createStatement().use { st -> split(script).forEach { st.execute(it) } }
    }

    /** [sql] with its `:name` placeholders bound from [bound], its rows read as a [ConformTable]. */
    fun query(
        conn: Connection,
        sql: String,
        bound: Map<String, Any?>,
    ): ConformTable {
        val (text, names) = positional(sql)
        conn.prepareStatement(text).use { ps ->
            names.forEachIndexed { i, n ->
                require(n in bound) { "nothing binds :$n" }
                ps.setObject(i + 1, bound[n])
            }
            ps.executeQuery().use { return table(it) }
        }
    }

    /**
     * [rs] as a [ConformTable], in the engine-neutral reading the seven points compare: every integer width one
     * `int64`, every decimal one `decimal` (an engine's precision is its own), CHAR's pad dropped (SQL compares CHAR
     * PAD SPACE), a date its epoch day, a timestamp its UTC epoch µs (a recipe's timestamps carry no zone).
     */
    fun table(rs: ResultSet): ConformTable {
        val md = rs.metaData
        val n = md.columnCount
        val kinds = (1..n).map { kind(md.getColumnType(it), md.getColumnTypeName(it)) }
        val columns = (1..n).map { ConformColumn(md.getColumnLabel(it), kinds[it - 1], true) }
        val rows = mutableListOf<List<Any?>>()
        while (rs.next()) {
            rows +=
                (1..n).map { i ->
                    cell(rs, i, kinds[i - 1], md.getColumnType(i))
                }
        }
        return ConformTable(columns, rows)
    }

    private fun kind(
        type: Int,
        name: String,
    ): String =
        when (type) {
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> "int64"
            Types.NUMERIC, Types.DECIMAL -> "decimal"
            Types.REAL, Types.FLOAT, Types.DOUBLE -> "float64"
            Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR -> "utf8"
            Types.BOOLEAN, Types.BIT -> "bool"
            Types.DATE -> "date32"
            Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "timestamp(microsecond,UTC)"
            else ->
                when (name.uppercase()) {
                    "HUGEINT", "UHUGEINT", "UBIGINT", "UINTEGER", "USMALLINT", "UTINYINT" -> "int64"
                    "TEXT", "BPCHAR" -> "utf8"
                    else -> name.lowercase()
                }
        }

    private fun cell(
        rs: ResultSet,
        i: Int,
        kind: String,
        type: Int,
    ): Any? {
        val v: Any? =
            when (kind) {
                "int64" -> rs.getBigDecimal(i)?.longValueExact()
                "decimal" -> rs.getBigDecimal(i)
                "float64" -> rs.getDouble(i)
                "utf8" ->
                    rs.getString(i)?.let {
                        if (type == Types.CHAR ||
                            type == Types.NCHAR
                        ) {
                            it.trimEnd(' ')
                        } else {
                            it
                        }
                    }
                "bool" -> rs.getBoolean(i)
                "date32" -> rs.getObject(i, LocalDate::class.java)?.toEpochDay()
                "timestamp(microsecond,UTC)" ->
                    rs.getObject(i, LocalDateTime::class.java)?.let {
                        val instant = it.toInstant(ZoneOffset.UTC)
                        instant.epochSecond * 1_000_000 + instant.nano / 1_000
                    }
                else -> rs.getObject(i)?.toString()
            }
        return if (rs.wasNull()) null else v
    }
}

/**
 * A SQL engine a recipe's statements run on (AU-core-P2 · S2.4 T3) — its own database, loaded with a fixture, and
 * the dialect the statements are rendered in for it. [close] releases (and, for a server, drops) the database.
 */
interface RecipeEngine : AutoCloseable {
    val name: String
    val dialect: SqlDialect
    val connection: Connection
}

/** Embedded DuckDB, in memory (D-18/D-40: the native runner's second engine) — [fixtures] run in order. */
class DuckDbRecipeEngine(
    fixtures: List<String>,
    attachment: String? = null,
) : RecipeEngine {
    override val name = "duckdb"
    override val dialect = SqlDialect.DUCKDB
    override val connection: Connection = DriverManager.getConnection("jdbc:duckdb:")

    init {
        try {
            if (attachment != null) {
                val name = "\"" + attachment.replace("\"", "\"\"") + "\""
                connection.createStatement().use {
                    it.execute("ATTACH ':memory:' AS $name")
                    it.execute("USE $name")
                }
            }
            fixtures.forEach { JdbcStatements.load(connection, it) }
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    override fun close() = connection.close()
}

/**
 * Postgres: a scratch database [database] created on [server] (`jdbc:postgresql://host:port`), [fixtures] run in
 * order, dropped on [close].
 */
class PostgresRecipeEngine(
    private val server: String,
    private val user: String,
    private val password: String,
    private val database: String,
    fixtures: List<String>,
) : RecipeEngine {
    override val name = "pg"
    override val dialect = SqlDialect.POSTGRESQL
    override val connection: Connection

    init {
        admin { it.execute("DROP DATABASE IF EXISTS $database") }
        admin { it.execute("CREATE DATABASE $database") }
        connection = DriverManager.getConnection("$server/$database", user, password)
        fixtures.forEach { JdbcStatements.load(connection, it) }
    }

    override fun close() {
        connection.close()
        admin { it.execute("DROP DATABASE IF EXISTS $database") }
    }

    private fun admin(block: (java.sql.Statement) -> Unit) =
        DriverManager.getConnection("$server/postgres", user, password).use { c -> c.createStatement().use(block) }
}

/**
 * A recipe run on every engine, every output compared (AU-core-P2 · S2.4 T1/T3): each engine renders the recipe's
 * statements in its dialect over the physical tables ([SqlTier.DB] — the door's translation stood in for by the
 * fixture's model-shaped tables), binds the run's values and reads every output; the first engine is the reference,
 * every other's output must agree with it on the seven points (rows as a multiset — a statement carries no order).
 */
class RecipeConform(
    private val tolerances: Map<String, Double> = emptyMap(),
    /**
     * Declared engine numeric deltas, `<output>/<column>` (one output) or `<column>` (every output) → tolerance — the
     * columns one engine computes in DECIMAL and another in DOUBLE ([SevenPointComparator]'s `numericDeltas`).
     */
    private val numericDeltas: Map<String, Double> = emptyMap(),
) {
    /** One engine's run: every output (`island.port`) read, or the statement and the engine's refusal. */
    data class EngineRun(
        val engine: String,
        val statements: List<RecipeStatement>,
        val outputs: Map<String, ConformTable>,
        val failures: Map<String, String>,
    )

    fun runOn(
        engine: RecipeEngine,
        plan: TtrpPipeline.PlanResult,
        asOf: LocalDateTime,
    ): EngineRun {
        val bound = RecipeBindings.of(requireNotNull(plan.recipe), asOf)
        val statements = RecipeStatements.plan(plan, SqlTier.DB, engine.dialect)
        val outputs = linkedMapOf<String, ConformTable>()
        val failures = linkedMapOf<String, String>()
        for (s in statements) {
            val key = "${s.island}.${s.port}"
            try {
                outputs[key] = JdbcStatements.query(engine.connection, s.sql, bound)
            } catch (e: SQLException) {
                failures[key] = "${e.message}\n${s.sql}"
            }
        }
        return EngineRun(engine.name, statements, outputs, failures)
    }

    /** S2.5: each part is read once on its storage; only its rows reach the embedded combine engine. */
    fun runAcross(
        engines: Map<String, RecipeEngine>,
        plan: TtrpPipeline.PlanResult,
        asOf: LocalDateTime,
    ): EngineRun = RecipeCrossStorage.run(engines, plan, asOf)

    /** Exit 0 every output agrees · 1 an output differs · 2 a statement failed on an engine. */
    fun compare(runs: List<EngineRun>): ConformOutcome {
        require(runs.size >= 2) { "a recipe conforms across ≥2 engines" }
        runs.firstOrNull { it.failures.isNotEmpty() }?.let { r ->
            val (out, why) = r.failures.entries.first()
            return ConformOutcome(2, emptyMap(), "$out failed on ${r.engine}: $why")
        }
        val reference = runs.first()
        val reports = linkedMapOf<String, ConformReport>()
        for ((out, table) in reference.outputs) {
            val deltas =
                numericDeltas
                    .filterKeys { '/' !in it || it.substringBefore('/') == out }
                    .mapKeys { it.key.substringAfter('/') }
            val comparator = SevenPointComparator(tolerances = tolerances, numericDeltas = deltas)
            for (other in runs.drop(1)) {
                val theirs =
                    other.outputs[out]
                        ?: return ConformOutcome(1, reports, "${other.engine} has no output '$out'")
                val report = comparator.compare(table, theirs)
                reports[if (runs.size > 2) "$out@${other.engine}" else out] = report
                if (!report.pass) return ConformOutcome(1, reports, "output '$out' differs on ${other.engine}")
            }
        }
        val engines = runs.joinToString(", ") { it.engine }
        return ConformOutcome(0, reports, "every output agrees on $engines (${reports.size} comparisons)")
    }
}
