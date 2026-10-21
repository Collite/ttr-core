// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * AU-core-P2 · S2.4 T3 — `ttrp conform` over a recipe: `--engines` the engines, `--fixture` the scripts each loads,
 * `--as-of` the run's instant. Over DuckDB alone (no server): the statements run and each output's rows are counted;
 * twice over DuckDB: every output compared on the seven points. Postgres ⇄ DuckDB is RecipeLivePgSpec's (ttrp-conform).
 */
class RecipeConformCliTest :
    FunSpec({
        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val c = dir.resolve(rel)
                if (Files.exists(c)) return c
                dir = dir.parent
            }
            error("could not locate $rel")
        }

        val fixtures = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes")
        val recipes = locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes")
        val sample = locate("packages/kotlin/ttrp-conform/src/test/resources/recipes/hartland")

        /** A project around `settle.ttrp`: `modeler.toml` naming the hartland world, `models/` the fixture model. */
        val project =
            Files.createTempDirectory("ttrp-recipe-conform").also { dir ->
                Files.writeString(dir.resolve("modeler.toml"), "[ttrp]\nworld = \"hartland.worlds.demo\"\n")
                Files.createSymbolicLink(dir.resolve("models"), fixtures.resolve("model"))
                Files.copy(recipes.resolve("settle.ttrp"), dir.resolve("settle.ttrp"))
            }
        val recipe = project.resolve("settle.ttrp")
        val fixtureArgs = "--fixture ${sample.resolve("schema.sql")} --fixture ${sample.resolve("sample.sql")}"

        fun ttrp() = TtrpCommand().subcommands(ConformCommand())

        test("scoped fixtures: separate storage databases, all outputs compared") {
            val args = "--fixture erp_db=${sample.resolve(
                "erp.sql",
            )} --fixture automation_db=${sample.resolve("automation.sql")}"
            val r = ttrp().test("conform $recipe --engines duckdb,duckdb $args --as-of 2026-11-02T08:00")
            io.kotest.assertions.withClue(r.stdout + r.stderr) { r.statusCode shouldBe 0 }
            r.stdout shouldContain "every output agrees on duckdb-parts, duckdb-parts"
        }
        test("scoped fixtures reject a missing storage before running") {
            val r =
                ttrp().test(
                    "conform $recipe --engines duckdb --fixture erp_db=${sample.resolve(
                        "erp.sql",
                    )} --as-of 2026-11-02T08:00",
                )
            r.statusCode shouldBe 2
            r.stdout shouldContain "fixtures must name exactly"
        }
        test("explain names parts and storage and the DuckDB combine") {
            val r = TtrpCommand().subcommands(ExplainCommand()).test("explain $recipe")
            r.statusCode shouldBe 0
            r.stdout shouldContain "storage automation_db"
            r.stdout shouldContain "storage erp_db"
            r.stdout shouldContain "DuckDB"
        }

        test("scoped pg,duckdb fixtures exercise the runner path through the CLI").config(
            enabled =
                System.getenv("TTRP_CONFORM_PG") == "1",
        ) {
            val plan =
                org.tatrman.ttrp.graph
                    .TtrpPipeline(
                        org.tatrman.ttrp.project
                            .TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures),
                        fixtures.resolve("model"),
                    ).plan(Files.readString(recipe), recipe.toString())
            val messages = mutableListOf<String>()
            val url = System.getenv("TTR_CONN_ERP_PG") ?: "postgresql://postgres:ttrp@localhost:55432/postgres"
            val status =
                RecipeConformCli.run(
                    plan,
                    listOf("pg", "duckdb"),
                    emptyList(),
                    java.time.LocalDateTime.of(2026, 11, 2, 8, 0),
                    emptyMap(),
                    mapOf("TTR_CONN_ERP" to url, "TTR_CONN_AUTOMATION" to url),
                    storageFixtures =
                        mapOf(
                            "erp_db" to listOf(sample.resolve("erp.sql")),
                            "automation_db" to listOf(sample.resolve("automation.sql")),
                        ),
                    echo = messages::add,
                )
            io.kotest.assertions.withClue(messages.joinToString("\n")) { status shouldBe 0 }
            messages.joinToString() shouldContain "pg-parts, duckdb-parts"
        }

        test("one engine: every statement runs on DuckDB, each output's rows counted") {
            val r = ttrp().test("conform $recipe --engines duckdb $fixtureArgs --as-of 2026-11-02T08:00")
            r.statusCode shouldBe 0
            r.stdout shouldContain "settlement.money_overdue: 2 row(s)"
            r.stdout shouldContain "settlement.closed: 2 row(s)"
        }

        test("two engines: every output compared on the seven points") {
            val r = ttrp().test("conform $recipe --engines duckdb,duckdb $fixtureArgs --as-of 2026-11-02T08:00")
            r.statusCode shouldBe 0
            r.stdout shouldContain "every output agrees on duckdb, duckdb"
            r.stdout shouldContain "[PASS] Q9-2 row-multiset"
        }

        test("a recipe needs an instant and a fixture; Polars is deferred (AU-C-8)") {
            ttrp().test("conform $recipe --engines duckdb $fixtureArgs").statusCode shouldBe 2
            ttrp().test("conform $recipe --engines duckdb --as-of 2026-11-02T08:00").statusCode shouldBe 2
            val polars = ttrp().test("conform $recipe --engines duckdb,polars $fixtureArgs --as-of 2026-11-02T08:00")
            polars.statusCode shouldBe 2
            polars.stdout shouldContain "AU-C-8"
        }

        test("pg without its server named: exit 2, the variable it reads named")
            .config(enabled = System.getenv().keys.none { it.startsWith("TTR_CONN_") }) {
                val r = ttrp().test("conform $recipe --engines pg $fixtureArgs --as-of 2026-11-02T08:00")
                r.statusCode shouldBe 2
                r.stdout shouldContain "TTR_CONN_ERP"
            }
    })
