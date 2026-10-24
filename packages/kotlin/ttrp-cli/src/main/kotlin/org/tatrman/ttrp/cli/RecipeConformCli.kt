// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import org.tatrman.ttrp.conform.DuckDbRecipeEngine
import org.tatrman.ttrp.conform.PostgresRecipeEngine
import org.tatrman.ttrp.conform.RecipeConform
import org.tatrman.ttrp.conform.RecipePolars
import org.tatrman.ttrp.conform.RecipeEngine
import org.tatrman.ttrp.graph.TtrpPipeline
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * `ttrp conform <recipe>.ttrp --engines pg,duckdb --fixture <sql>… --as-of <instant>` (AU-core-P2 · S2.4 T3) — a
 * recipe conforms across engines. Scoped `<storage>=<sql>` fixtures use separate source databases and DuckDB
 * combines; Polars currently accepts only the unscoped single-database oracle. Every engine gets its own
 * database loaded with the fixture scripts in order (Postgres: a scratch database on the server `TTR_CONN_<ENGINE>`
 * names, dropped afterwards; DuckDB: in memory), runs every output's statement with the run's bindings at `--as-of`,
 * and every engine's outputs are compared with the first's on the seven points. One engine: the statements run, and
 * each output's row count is printed. Exits 0 every output agrees · 1 an output differs · 2 a statement or an engine
 * failed (mirrors the bundle conform).
 */
object RecipeConformCli {
    fun run(
        plan: TtrpPipeline.PlanResult,
        engines: List<String>,
        fixtures: List<Path>,
        asOf: LocalDateTime,
        tolerances: Map<String, Double>,
        env: Map<String, String>,
        numericDeltas: Map<String, Double> = emptyMap(),
        storageFixtures: Map<String, List<Path>> = emptyMap(),
        echo: (String) -> Unit,
    ): Int {
        if (fixtures.isEmpty() && storageFixtures.isEmpty()) {
            echo("ttrp conform: a recipe conforms over a fixture — pass --fixture <script.sql> (schema, then rows)")
            return 2
        }
        // Refuse the complete request before opening any fixture or engine, independent of engine order.
        if (storageFixtures.isNotEmpty() && engines.any { it.equals("polars", ignoreCase = true) }) {
            echo("ttrp conform: polars does not run cross-storage recipes yet")
            return 2
        }
        val scripts = fixtures.map { Files.readString(it) }
        val opened = mutableListOf<RecipeEngine>()
        try {
            val conform = RecipeConform(tolerances, numericDeltas)
            if (storageFixtures.isNotEmpty()) {
                require(fixtures.isEmpty()) { "use either unscoped fixtures or <storage>=<sql>, not both" }
                val bound = requireNotNull(plan.bound)
                val used =
                    requireNotNull(plan.graph)
                        .loadSources.values
                        .mapNotNull { it.storage }
                        .distinct()
                require(storageFixtures.keys == used.toSet()) {
                    "fixtures must name exactly the recipe's storages: ${used.joinToString()}"
                }
                val runs =
                    engines.mapIndexed { i, e ->
                        val sources =
                            used.associateWith { storage ->
                                val scriptsForStorage = storageFixtures.getValue(storage).map { Files.readString(it) }
                                val engine = requireNotNull(bound.storages.single { it.qname.name == storage }.via)
                                val source =
                                    when (e.lowercase()) {
                                        "duckdb" -> DuckDbRecipeEngine(scriptsForStorage, storage)
                                        "pg", "postgres" ->
                                            postgres(plan, env, scriptsForStorage, echo, engine, "_${i}_$storage")
                                                ?: return 2
                                        else -> {
                                            echo(
                                                "ttrp conform: unknown engine '$e' (storage-scoped recipes run on pg, duckdb)",
                                            )
                                            return 2
                                        }
                                    }
                                opened += source
                                source
                            }
                        conform.runAcross(sources, plan, asOf)
                    }
                return report(conform, runs, echo)
            }
            for (e in engines) {
                opened +=
                    when (e.lowercase()) {
                        "duckdb", "polars" -> DuckDbRecipeEngine(scripts)
                        "pg", "postgres" -> postgres(plan, env, scripts, echo) ?: return 2
                        else -> {
                            echo("ttrp conform: unknown engine '$e' (a recipe runs on pg, duckdb, polars)")
                            return 2
                        }
                    }
            }
            val runs =
                opened.zip(engines).map { (engine, name) ->
                    if (name.lowercase() == "polars") {
                        RecipePolars.run(engine.connection, plan, asOf)
                    } else {
                        conform.runOn(engine, plan, asOf)
                    }
                }
            return report(conform, runs, echo)
        } catch (e: Exception) {
            echo("ttrp conform: ${e.message}")
            return 2
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun report(
        conform: RecipeConform,
        runs: List<RecipeConform.EngineRun>,
        echo: (String) -> Unit,
    ): Int {
        if (runs.size == 1) {
            val run = runs.single()
            run.outputs.forEach { (out, t) -> echo("$out: ${t.rows.size} row(s)") }
            run.failures.forEach { (out, why) -> echo("$out FAILED on ${run.engine}: $why") }
            return if (run.failures.isEmpty()) 0 else 2
        }
        val outcome = conform.compare(runs)
        echo(outcome.summary())
        return outcome.exitCode
    }

    internal fun databaseName(
        pid: Long,
        suffix: String,
    ): String {
        val raw = "ttrp_conform_${pid}$suffix"
        val safe = raw.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_")
        val hash =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(raw.toByteArray())
                .take(6)
                .joinToString("") { "%02x".format(it) }
        // Hash the original suffix, so case/sanitization/truncation cannot merge two storages.
        return safe.take(50) + "_" + hash
    }

    internal fun scopedFixture(value: String): Boolean = Regex("[A-Za-z_][A-Za-z0-9_]*=.+").matches(value)

    /** Postgres from the recipe's island engine's `TTR_CONN_<ENGINE>` (`postgresql://user:pass@host:port/db`). */
    private fun postgres(
        plan: TtrpPipeline.PlanResult,
        env: Map<String, String>,
        scripts: List<String>,
        echo: (String) -> Unit,
        storageEngine: String? = null,
        databaseSuffix: String = "",
    ): RecipeEngine? {
        val names =
            if (storageEngine !=
                null
            ) {
                listOf("TTR_CONN_" + storageEngine.uppercase().replace(Regex("[^A-Z0-9]"), "_"))
            } else {
                requireNotNull(plan.exec)
                    .islands
                    .map { "TTR_CONN_" + it.engine.uppercase().replace(Regex("[^A-Z0-9]"), "_") }
                    .distinct()
            }
        val name = names.singleOrNull()
        val url = name?.let { env[it] }
        if (url == null) {
            echo(
                "ttrp conform: pg needs the server in ${names.joinToString(
                    " / ",
                )} (postgresql://user:pass@host:port/db)",
            )
            return null
        }
        val uri = URI(url)
        val (user, password) = (uri.userInfo ?: "").split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val server = "jdbc:postgresql://${uri.host}:${if (uri.port > 0) uri.port else 5432}"
        return PostgresRecipeEngine(
            server,
            user,
            password,
            databaseName(ProcessHandle.current().pid(), databaseSuffix),
            scripts,
        )
    }
}
