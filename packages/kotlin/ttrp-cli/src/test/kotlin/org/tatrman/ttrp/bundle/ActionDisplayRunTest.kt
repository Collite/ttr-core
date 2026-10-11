// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.project.TtrpManifestReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Display fixture programs built for the bash-launched `shop.worlds.local` world and RUN: the Polars island
 * script executes under `python3` against the fixture's `files/orders.csv` (provisioned into the bundle), and
 * the Arrow files it writes are read back — what the engine produced, not the script text.
 *
 * Needs `python3` with `polars` + `pyarrow` (the bash executor's package list; CI's ttrp-conform job installs
 * them). Without them each test prints a SKIP line and passes — the offline bundle tests stay the gate there.
 */
class ActionDisplayRunTest :
    FunSpec({
        val project = ActionDisplayBundleTest.locate("packages/kotlin/ttrp-frontend/src/test/resources/display")
        val manifest = TtrpManifestReader.resolve(project).manifest

        fun build(
            program: String,
            source: String = Files.readString(project.resolve("programs/$program")),
            target: String? = null,
        ) = BundleAssembler("1.0.0").build(
            source = source,
            fileName = program,
            pipelineManifest = manifest,
            modelsRoot = manifest.modelsRoot(),
            outDir = Files.createTempDirectory("ttrp-action-run"),
            targetOverrides = target?.let { mapOf("review" to it) } ?: emptyMap(),
        )

        fun skip(): Boolean {
            val why = IslandRun.polarsMissing() ?: return false
            System.err.println("SKIP: $why")
            return true
        }

        fun runIsland(r: BundleAssembler.BundleResult): Path {
            val island = r.manifest.islands.single()
            val run = IslandRun.run(r.dir, island.file)
            withClue("${island.file}\n${Files.readString(r.dir.resolve(island.file))}\n---\n${run.output}") {
                run.exitCode shouldBe 0
            }
            return r.dir
        }

        test("a Polars CSV load reads a decimal field at a declared precision/scale — the island runs") {
            if (skip()) return@test
            val r = build("notify_local.ttrp")
            val py =
                Files.readString(
                    r.dir.resolve(
                        r.manifest.islands
                            .single()
                            .file,
                    ),
                )
            py shouldContain "\"amount\": pl.Decimal(19, 2)"
            val dir = runIsland(r)
            val late = IslandRun.read(dir, listOf("out/notify.arrow")).getValue("out/notify.arrow")
            late.fields.first { it.first == "amount" }.second shouldBe "decimal128(19, 2)"
            late.rows.map { it["amount"] } shouldBe listOf("120.50", "1500.25")
        }

        // `… -> late` inside the body writes the chain's value to the OUT port `late`. The graph used to leave the
        // chain dangling and map the port to the body's last assigned value — the island wrote the unfiltered head.
        val chainToPort =
            """
            uses world "shop.worlds.local"

            container review(out late) target pl {
                o = load(files.orders, schema: orders_csv)
                o -> filter(status = 1) -> select(order_id, email) -> late
            }
            review.late -> display(late)
            """.trimIndent() + "\n"

        test("chain-to-port, ordinary display: the Polars island writes the chain's filtered frame") {
            if (skip()) return@test
            val r = build("chain_port.ttrp", source = chainToPort)
            val out = IslandRun.read(runIsland(r), listOf("out/late.arrow")).getValue("out/late.arrow")
            out.fields.map { it.first } shouldBe listOf("order_id", "email")
            out.rows.map { it["order_id"] } shouldBe listOf("1", "3")
        }

        test("chain-to-port on Postgres and sql-text: the port statement is the chain's (filtered, selected)") {
            val pg = build("chain_port.ttrp", source = chainToPort, target = "pg")
            val py =
                Files.readString(
                    pg.dir.resolve(
                        pg.manifest.islands
                            .single()
                            .file,
                    ),
                )
            py shouldContain "WHERE"
            py shouldContain "\"status\" = 1"
            val host =
                build(
                    "chain_port_host.ttrp",
                    source =
                        """
                        uses world "shop.worlds.host"
                        import shop.orders.*

                        container review(out late) target erp {
                            o = load(orders)
                            o -> filter(status = 1) -> select(order_id, amount) -> late
                        }
                        review.late -> display(late)
                        """.trimIndent() + "\n",
                )
            val out =
                host.manifest.islands
                    .single()
                    .outputs!!
                    .single()
            out.columns.map { it.name } shouldBe listOf("order_id", "amount")
            Files.readString(host.dir.resolve(out.file)) shouldContain "WHERE"
        }

        // A row type the checker cannot see (here: a staged IN port) reaches the Polars island unchecked; the island
        // still never NULL-fills a REQUIRED schema column — it stops with TTRP-DSP-001 before writing the file.
        fun stagedDisplayIsland(dir: Path): String {
            val loc =
                org.tatrman.ttrp.ast.SourceLocation.UNKNOWN
            val schema =
                org.tatrman.ttrp.resolve.DisplaySchema(
                    "notify",
                    "shop.actions.notify",
                    listOf(
                        org.tatrman.ttrp.resolve
                            .DisplaySchemaColumn("recipient", "text", false),
                        org.tatrman.ttrp.resolve
                            .DisplaySchemaColumn("subject", "text", false),
                        org.tatrman.ttrp.resolve
                            .DisplaySchemaColumn("note", "text", true),
                    ),
                )
            val steps =
                listOf(
                    org.tatrman.ttrp.emit.polars.PolarsStep(
                        "rows",
                        org.tatrman.ttrp.graph.model
                            .Load("c~rows", "rows", loc, source = "rows"),
                        source =
                            org.tatrman.ttrp.emit.polars.PolarsSource
                                .Staged("rows"),
                    ),
                    org.tatrman.ttrp.emit.polars.PolarsStep(
                        "_",
                        org.tatrman.ttrp.graph.model
                            .Display("d", "~d", loc, "notify", schema = schema),
                        inputVars = listOf("rows"),
                        sinkPath = "out/notify.arrow",
                    ),
                )
            val text =
                org.tatrman.ttrp.emit.polars
                    .PolarsIslandEmitter()
                    .emit("c", steps)
                    .text
            Files.createDirectories(dir.resolve("islands"))
            Files.writeString(dir.resolve("islands/c.py"), text)
            return "islands/c.py"
        }

        fun stage(
            dir: Path,
            columns: String,
        ) {
            Files.createDirectories(dir.resolve("staging"))
            val p =
                ProcessBuilder(
                    "python3",
                    "-c",
                    "import polars as pl; pl.DataFrame({$columns}).write_ipc('staging/rows.arrow')",
                ).directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start()
            val out = p.inputStream.readBytes().decodeToString()
            withClue(out) { p.waitFor() shouldBe 0 }
        }

        test("Polars: a frame missing a REQUIRED schema column stops the island with TTRP-DSP-001 — no file") {
            if (skip()) return@test
            val dir = Files.createTempDirectory("ttrp-polars-guard")
            val island = stagedDisplayIsland(dir)
            stage(dir, "'recipient': ['a@x'], 'order_id': [1]")
            val run = IslandRun.run(dir, island)
            withClue(run.output) {
                (run.exitCode != 0) shouldBe true
                run.output shouldContain "TTRP-DSP-001"
                run.output shouldContain "subject"
            }
            Files.exists(dir.resolve("out/notify.arrow")) shouldBe false
        }

        test("Polars: a frame with every required column writes it, an absent optional column as NULL") {
            if (skip()) return@test
            val dir = Files.createTempDirectory("ttrp-polars-guard-ok")
            val island = stagedDisplayIsland(dir)
            stage(dir, "'recipient': ['a@x'], 'subject': ['hi'], 'order_id': [1]")
            val run = IslandRun.run(dir, island)
            withClue(run.output) { run.exitCode shouldBe 0 }
            val out = IslandRun.read(dir, listOf("out/notify.arrow")).getValue("out/notify.arrow")
            out.fields.map { it.first } shouldBe listOf("recipient", "subject", "note")
            out.rows.single() shouldBe mapOf("recipient" to "a@x", "subject" to "hi", "note" to null)
        }

        // One display, two sources (`late` carries `amount`, `large` does not): every column is cast to the schema's
        // type, so both files have ONE Arrow schema — they used to differ (`amount: decimal128(19, 2)` vs a NULL fill
        // typed `double`) and `pa.concat_tables` failed with ArrowInvalid.
        val notifyFields =
            listOf(
                "recipient" to "large_string",
                "subject" to "large_string",
                "order_id" to "int64",
                "amount" to "decimal128(19, 2)",
                "note" to "large_string",
            )
        val notifyFiles = listOf("out/notify.arrow", "out/notify~2.arrow")

        test("every per-source file of one action display has the same Arrow schema (Polars, run)") {
            if (skip()) return@test
            val dir = runIsland(build("notify_local.ttrp"))
            val files = IslandRun.read(dir, notifyFiles)
            files.getValue("out/notify.arrow").fields shouldBe notifyFields
            files.getValue("out/notify~2.arrow").fields shouldBe notifyFields
            IslandRun.concatError(dir, notifyFiles) shouldBe null
            files.getValue("out/notify~2.arrow").rows.map { it["order_id"] to it["amount"] } shouldBe
                listOf("2" to null, "3" to null)
        }

        test("Postgres writes the same Arrow schema as Polars for the display (live PG, TTRP_CONFORM_PG=1)") {
            if (skip()) return@test
            if (System.getenv("TTRP_CONFORM_PG") != "1") {
                System.err.println("SKIP: TTRP_CONFORM_PG != 1 — the live Postgres display run is not run.")
                return@test
            }
            val conn = System.getenv("TTR_CONN_ERP_PG") ?: error("TTRP_CONFORM_PG=1 but TTR_CONN_ERP_PG is unset")
            val r = build("notify_local.ttrp", target = "pg")
            val island = r.manifest.islands.single()
            val run = IslandRun.run(r.dir, island.file, mapOf("TTR_CONN_PG" to conn))
            withClue("${Files.readString(r.dir.resolve(island.file))}\n---\n${run.output}") { run.exitCode shouldBe 0 }
            val files = IslandRun.read(r.dir, notifyFiles)
            files.getValue("out/notify.arrow").fields shouldBe notifyFields
            files.getValue("out/notify~2.arrow").fields shouldBe notifyFields
            IslandRun.concatError(r.dir, notifyFiles) shouldBe null
        }

        // A cast calc on a Postgres island is rendered raw by the CTE planner; it named the TTR-P spelling
        // (`CAST("order_id" AS string)` — invalid PostgreSQL). It now names Postgres types.
        val pgCasts =
            """
            uses world "shop.worlds.local"

            container review(out late) target pg {
                late = load(files.orders, schema: orders_csv) -> filter(status = 1) ->
                    calc { s = cast(order_id as string)  d = cast(status as double)  t = cast(order_id as datetime) } ->
                    select(order_id, s, d)
            }
            review.late -> display(late)
            """.trimIndent() + "\n"

        test("Postgres: a cast calc names Postgres types (text, double precision, timestamp)") {
            val r = build("pg_casts.ttrp", source = pgCasts)
            val py =
                Files.readString(
                    r.dir.resolve(
                        r.manifest.islands
                            .single()
                            .file,
                    ),
                )
            py shouldContain "CAST(\"order_id\" AS text)"
            py shouldContain "CAST(\"status\" AS double precision)"
            py shouldContain "CAST(\"order_id\" AS timestamp)"
        }

        test("Postgres: the cast calc runs (live PG, TTRP_CONFORM_PG=1)") {
            if (skip()) return@test
            if (System.getenv("TTRP_CONFORM_PG") != "1") {
                System.err.println("SKIP: TTRP_CONFORM_PG != 1 — the live Postgres cast run is not run.")
                return@test
            }
            val conn = System.getenv("TTR_CONN_ERP_PG") ?: error("TTRP_CONFORM_PG=1 but TTR_CONN_ERP_PG is unset")
            val r =
                build("pg_casts.ttrp", source = pgCasts.replace("  t = cast(order_id as datetime)", ""))
            val island = r.manifest.islands.single()
            val run = IslandRun.run(r.dir, island.file, mapOf("TTR_CONN_PG" to conn))
            withClue("${Files.readString(r.dir.resolve(island.file))}\n---\n${run.output}") { run.exitCode shouldBe 0 }
            val out = IslandRun.read(r.dir, listOf("out/late.arrow")).getValue("out/late.arrow")
            out.rows.map { it["s"] to it["d"] } shouldBe listOf("1" to "1.0", "3" to "1.0")
        }
    })
