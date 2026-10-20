// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import org.tatrman.ttrp.conform.DuckDbRecipeEngine
import org.tatrman.ttrp.conform.PostgresRecipeEngine
import org.tatrman.ttrp.conform.RecipeConform
import org.tatrman.ttrp.conform.RecipeEngine
import org.tatrman.ttrp.graph.TtrpPipeline
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * `ttrp conform <recipe>.ttrp --engines pg,duckdb --fixture <sql>… --as-of <instant>` (AU-core-P2 · S2.4 T3) — a
 * recipe has no placement variants (one engine, the door): it conforms ACROSS engines. Every engine gets its own
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
        echo: (String) -> Unit,
    ): Int {
        if (fixtures.isEmpty()) {
            echo("ttrp conform: a recipe conforms over a fixture — pass --fixture <script.sql> (schema, then rows)")
            return 2
        }
        val scripts = fixtures.map { Files.readString(it) }
        val opened = mutableListOf<RecipeEngine>()
        try {
            for (e in engines) {
                opened +=
                    when (e.lowercase()) {
                        "duckdb" -> DuckDbRecipeEngine(scripts)
                        "pg", "postgres" -> postgres(plan, env, scripts, echo) ?: return 2
                        "polars" -> {
                            echo("ttrp conform: a recipe's Polars statements are deferred past the rc (AU-C-8)")
                            return 2
                        }
                        else -> {
                            echo("ttrp conform: unknown engine '$e' (a recipe runs on pg, duckdb)")
                            return 2
                        }
                    }
            }
            val conform = RecipeConform(tolerances, numericDeltas)
            val runs = opened.map { conform.runOn(it, plan, asOf) }
            if (runs.size == 1) {
                val run = runs.single()
                run.outputs.forEach { (out, t) -> echo("$out: ${t.rows.size} row(s)") }
                run.failures.forEach { (out, why) -> echo("$out FAILED on ${run.engine}: $why") }
                return if (run.failures.isEmpty()) 0 else 2
            }
            val outcome = conform.compare(runs)
            echo(outcome.summary())
            return outcome.exitCode
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    /** Postgres from the recipe's island engine's `TTR_CONN_<ENGINE>` (`postgresql://user:pass@host:port/db`). */
    private fun postgres(
        plan: TtrpPipeline.PlanResult,
        env: Map<String, String>,
        scripts: List<String>,
        echo: (String) -> Unit,
    ): RecipeEngine? {
        val names =
            requireNotNull(plan.exec)
                .islands
                .map { "TTR_CONN_" + it.engine.uppercase().replace(Regex("[^A-Z0-9]"), "_") }
                .distinct()
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
        return PostgresRecipeEngine(server, user, password, "ttrp_conform_${ProcessHandle.current().pid()}", scripts)
    }
}
