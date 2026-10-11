// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.translator.codec.sql.ParseResult
import org.tatrman.translator.codec.sql.SqlParser
import org.tatrman.ttrp.project.TtrpManifestReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Action displays in a host-executed (`sql-text`) bundle (grammar 0.14 `def schema`):
 *  - several sources may feed one action display — every `displays[]` entry keeps its OWN source
 *    `{island, port}`, same-named entries sit in program (wiring) order, and each `file` is unique
 *    (`out/<name>.arrow`, then `out/<name>~2.arrow`, …);
 *  - the statement the host runs for an action display is projected to the schema's columns, in schema
 *    order, each CAST to the schema column's type — an absent optional column a NULL of that type — so every
 *    source reports the same row type; the entry carries the schema name + columns;
 *  - an ordinary display is unchanged; a port feeding both kinds keeps its own statement and gains a
 *    projected `<port>~<schema>` output that the action display names.
 * The fixture project (shared with ttrp-frontend's DisplaySchemaCheckSpec) is located by walk-up.
 */
class ActionDisplayBundleTest :
    FunSpec({
        val project = locate("packages/kotlin/ttrp-frontend/src/test/resources/display")
        val expected = locate("packages/kotlin/ttrp-cli/src/test/resources/fixtures/action-expected")
        val manifest = TtrpManifestReader.resolve(project).manifest

        fun build(
            program: String = "notify.ttrp",
            source: String = Files.readString(project.resolve("programs/$program")),
            out: Path = Files.createTempDirectory("ttrp-action"),
        ) = BundleAssembler("1.0.0").build(
            source = source,
            fileName = program,
            pipelineManifest = manifest,
            modelsRoot = manifest.modelsRoot(),
            outDir = out,
        )

        val updating = System.getProperty("updateGolden") == "true"

        fun golden(
            name: String,
            actual: String,
        ): Boolean {
            val file = expected.resolve(name)
            if (updating) {
                Files.createDirectories(file.parent)
                Files.writeString(file, actual)
                return false
            }
            if (!Files.exists(file)) fail("golden missing: $name — generate with -DupdateGolden=true\n$actual")
            Files.readString(file) shouldBe actual
            return true
        }

        test("two sources into one action display: own sources, wiring order, unique files") {
            val d = build().manifest.displays
            d.map { it.name } shouldContainExactly listOf("notify", "notify", "overdue")
            d.map { it.source!!.island + "." + it.source!!.port } shouldContainExactly
                listOf("review.late", "review.large", "review.overdue")
            d.map { it.file } shouldContainExactly listOf("out/notify.arrow", "out/notify~2.arrow", "out/overdue.arrow")
        }

        test("an action display entry carries its schema name and declared columns; an ordinary one does not") {
            val d = build().manifest.displays
            d[0].schema shouldBe "shop.actions.notify"
            d[0].columns!!.map { it.name } shouldContainExactly
                listOf("recipient", "subject", "order_id", "amount", "note")
            d[0].columns!!.map { it.type } shouldContainExactly listOf("text", "text", "int", "decimal", "text")
            d[0].columns!!.map { it.optional } shouldContainExactly listOf(null, null, null, true, true)
            d[2].schema shouldBe null
            d[2].columns shouldBe null
        }

        test("the action display's statement is projected to the schema columns, absent optional ones as NULL") {
            val r = build()
            val outs =
                r.manifest.islands
                    .single()
                    .outputs!!
            val late = outs.first { it.port == "late" }
            val large = outs.first { it.port == "large" }
            late.columns.map { it.name } shouldContainExactly
                listOf("recipient", "subject", "order_id", "amount", "note")
            large.columns.map { it.name } shouldContainExactly
                listOf("recipient", "subject", "order_id", "amount", "note")
            // every column — carried or NULL-filled — is cast to the schema's type and reported in the schema's
            // spelling, so the two sources of `notify` have one row type (a carried `note` used to say `string`)
            late.columns shouldBe large.columns
            late.columns.map { it.type } shouldContainExactly listOf("text", "text", "int", "decimal", "text")
            val lateSql = Files.readString(r.dir.resolve(late.file))
            lateSql shouldContain "CAST(NULL AS VARCHAR(MAX)) AS \"note\""
            lateSql shouldContain "CAST(\"amount\" AS DECIMAL(19, 2)) AS \"amount\""
            val largeSql = Files.readString(r.dir.resolve(large.file))
            largeSql shouldContain "CAST(NULL AS DECIMAL(19, 2)) AS \"amount\""
            // the authored `cast(order_id as string)` reaches the statement (the sql-text cast fix)
            largeSql shouldContain "CAST(\"order_id\" AS VARCHAR"
        }

        test("an ordinary display is unchanged — its port statement keeps the port's own columns") {
            val overdue =
                build()
                    .manifest.islands
                    .single()
                    .outputs!!
                    .first { it.port == "overdue" }
            overdue.columns.map { it.name } shouldContainExactly listOf("order_id", "due_date", "amount")
        }

        test("a port feeding an ordinary and an action display: whole statement + a projected <port>~<schema> output") {
            val r = build("mixed.ttrp")
            val outs =
                r.manifest.islands
                    .single()
                    .outputs!!
            outs.map { it.port } shouldContainExactly listOf("overdue", "overdue~flag_order")
            outs[0].columns.map { it.name } shouldContainExactly listOf("order_id", "reason", "due", "amount")
            outs[1].columns.map { it.name } shouldContainExactly listOf("order_id", "reason", "due")
            outs[1].file shouldBe "islands/review.overdue~flag_order.sql"
            r.manifest.displays.associate { it.name to it.source!!.port } shouldBe
                mapOf("flag_order" to "overdue~flag_order", "overdue" to "overdue")
        }

        test("the island statements match their goldens") {
            val checked =
                listOf("notify.ttrp", "mixed.ttrp").flatMap { p ->
                    val r = build(p)
                    r.manifest.islands.flatMap { it.outputs.orEmpty() }.map { o ->
                        golden(
                            p.removeSuffix(".ttrp") + "/" + o.file.removePrefix("islands/"),
                            Files.readString(r.dir.resolve(o.file)),
                        )
                    }
                }
            if (checked.any { !it }) fail("goldens updated — review git diff, re-run without -DupdateGolden")
        }

        test("every statement parses back with the translator's SQL parser (the door reads it the same way)") {
            listOf("notify.ttrp", "mixed.ttrp").forEach { p ->
                val r = build(p)
                r.manifest.islands.flatMap { it.outputs.orEmpty() }.forEach { o ->
                    val sql = Files.readString(r.dir.resolve(o.file))
                    withClue("${o.file}\n$sql") { (SqlParser.parseQuery(sql) is ParseResult.Success) shouldBe true }
                    sql shouldNotContain "["
                }
            }
        }

        test("the manifest validates against the v2 JSON Schema (display schema/columns are additive)") {
            listOf("notify.ttrp", "mixed.ttrp").forEach { p ->
                val json = build(p).manifest.toJson()
                val schemaStream = javaClass.getResourceAsStream("/schemas/bundle-manifest-v2.schema.json")!!
                val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaStream)
                schema.validate(ObjectMapper().readTree(json)) shouldBe emptySet()
            }
        }

        test("building twice is byte-identical (determinism)") {
            val a = build()
            val b = build()
            a.manifest.toJson() shouldBe b.manifest.toJson()
            a.manifest.files.keys.forEach { rel ->
                Files.readString(a.dir.resolve(rel)) shouldBe Files.readString(b.dir.resolve(rel))
            }
        }

        // ---- a bash-launched world: Polars / Postgres islands WRITE the display files themselves ----

        fun buildLocal(target: String) =
            BundleAssembler("1.0.0").build(
                source = Files.readString(project.resolve("programs/notify_local.ttrp")),
                fileName = "notify_local.ttrp",
                pipelineManifest = manifest,
                modelsRoot = manifest.modelsRoot(),
                outDir = Files.createTempDirectory("ttrp-action-local"),
                targetOverrides = mapOf("review" to target),
            )

        test("bash world: each source of one action display writes its own out/ file (Polars island)") {
            val r = buildLocal("pl")
            r.manifest.displays.map { it.file } shouldContainExactly listOf("out/notify.arrow", "out/notify~2.arrow")
            r.manifest.displays.forEach { it.source shouldBe null }
            val py =
                Files.readString(
                    r.dir.resolve(
                        r.manifest.islands
                            .single()
                            .file,
                    ),
                )
            py shouldContain "write_ipc(\"out/notify.arrow\""
            py shouldContain "write_ipc(\"out/notify~2.arrow\""
            // projected to the schema's columns: a required one is read (never NULL-filled), an absent optional one
            // a typed null; a frame missing a required column stops the island before it writes the file
            py shouldContain
                "pl.col(\"recipient\").cast(pl.String), pl.col(\"subject\").cast(pl.String), " +
                "pl.col(\"order_id\").cast(pl.Int64)"
            py shouldContain "(pl.col(\"amount\").cast(pl.Decimal(19, 2)) if \"amount\" in"
            py shouldContain "else pl.lit(None, dtype=pl.Decimal(19, 2)).alias(\"amount\"))"
            py shouldContain "else pl.lit(None, dtype=pl.String).alias(\"note\"))"
            py shouldContain "raise SystemExit(\"TTRP-DSP-001: display notify (schema shop.actions.notify)"
        }

        test("bash world: each source of one action display writes its own out/ file (Postgres island)") {
            val r = buildLocal("pg")
            r.manifest.displays.map { it.file } shouldContainExactly listOf("out/notify.arrow", "out/notify~2.arrow")
            val py =
                Files.readString(
                    r.dir.resolve(
                        r.manifest.islands
                            .single { it.engine == "pg" }
                            .file,
                    ),
                )
            py shouldContain "])), \"out/notify.arrow\")"
            py shouldContain "])), \"out/notify~2.arrow\")"
            // late carries amount but no note; large neither — every column cast to the schema's type, absent
            // optional columns NULLs of that type; the fetched Arrow table is cast to the display's Arrow schema
            val cast =
                "SELECT CAST(\"recipient\" AS TEXT) AS \"recipient\", CAST(\"subject\" AS TEXT) AS \"subject\", " +
                    "CAST(\"order_id\" AS BIGINT) AS \"order_id\", "
            py shouldContain cast + "CAST(\"amount\" AS NUMERIC(19, 2)) AS \"amount\", CAST(NULL AS TEXT) AS \"note\""
            py shouldContain cast + "CAST(NULL AS NUMERIC(19, 2)) AS \"amount\", CAST(NULL AS TEXT) AS \"note\""
            py shouldContain
                "_cur.fetch_arrow_table().cast(_pa.schema([(\"recipient\", _pa.large_string()), " +
                "(\"subject\", _pa.large_string()), (\"order_id\", _pa.int64()), " +
                "(\"amount\", _pa.decimal128(19, 2)), (\"note\", _pa.large_string())]))"
        }

        test("chain-to-port missing a required column does not build on Postgres either (TTRP-DSP-001)") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    BundleAssembler("1.0.0").build(
                        source = Files.readString(project.resolve("programs/negative/chain-port-missing-local.ttrp")),
                        fileName = "chain-port-missing-local.ttrp",
                        pipelineManifest = manifest,
                        modelsRoot = manifest.modelsRoot(),
                        outDir = Files.createTempDirectory("ttrp-action-pg"),
                        targetOverrides = mapOf("review" to "pg"),
                    )
                }
            ex.message!! shouldContain "TTRP-DSP-001"
            ex.message!! shouldContain "`subject`"
        }

        test(
            "the Postgres projection refuses an absent REQUIRED column (TTRP-DSP-001) and NULL-fills an optional one",
        ) {
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
            val ex =
                shouldThrow<org.tatrman.ttrp.emit.TtrpEmitException> {
                    PgIslandScript.displayProjection("SELECT 1", schema, setOf("recipient"), "review", "late")
                }
            ex.message!! shouldContain "TTRP-DSP-001"
            ex.message!! shouldContain "`subject`"
            val ok =
                PgIslandScript.displayProjection(
                    "SELECT 1",
                    schema,
                    setOf("recipient", "subject"),
                    "review",
                    "late",
                )
            ok shouldContain "CAST(NULL AS TEXT) AS \"note\""
            ok shouldNotContain "NULL AS TEXT) AS \"subject\""
        }

        test("sql-text: an omitted optional bigint / numeric / money / time column builds as a typed NULL") {
            // These are TTR-M type ids `TtrpType.parse` does not know; the build used to die with
            // `TTRP-EMT-006 … no SQL cast to 'bigint'` though `ttrp check` was clean.
            val src =
                """
                uses world "shop.worlds.host"
                import shop.orders.*
                import shop.actions.*

                container review(out late) target erp {
                    late = load(orders) -> filter(status = 1) -> select(order_id)
                }
                review.late -> display(audit)
                """.trimIndent() + "\n"
            val r = build(program = "audit.ttrp", source = src)
            val out =
                r.manifest.islands
                    .single()
                    .outputs!!
                    .single()
            val sql = Files.readString(r.dir.resolve(out.file))
            sql shouldContain "CAST(NULL AS BIGINT) AS \"batch_no\""
            sql shouldContain "CAST(NULL AS DECIMAL(19, 2)) AS \"ratio\""
            sql shouldContain "CAST(NULL AS DECIMAL(19, 4)) AS \"fee\""
            sql shouldContain "CAST(NULL AS TIME(0)) AS \"logged_at\""
            // a structured type keeps its precision/scale — in the cast and in the manifest's spelling
            sql shouldContain "CAST(NULL AS DECIMAL(12, 4)) AS \"total\""
            out.columns.map { it.type } shouldContainExactly
                listOf("int", "bigint", "numeric", "money", "time", "decimal(12,4)")
            r.manifest.displays
                .single()
                .columns!!
                .last()
                .type shouldBe "decimal(12,4)"
            withClue(sql) { (SqlParser.parseQuery(sql) is ParseResult.Success) shouldBe true }
        }

        test("a schema column no engine can produce does not build (TTRP-DSP-005)") {
            val src =
                Files
                    .readString(project.resolve("programs/notify.ttrp"))
                    .replace("review.late    -> display(notify)", "review.late    -> display(raw_payload)")
            val ex = shouldThrow<IllegalArgumentException> { build(source = src) }
            ex.message!! shouldContain "TTRP-DSP-005"
        }

        test("a duplicated ordinary display name does not build (TTRP-DSP-004)") {
            val src =
                Files
                    .readString(
                        project.resolve("programs/notify.ttrp"),
                    ).replace("display(notify)", "display(evidence)")
            val ex = shouldThrow<IllegalArgumentException> { build(source = src) }
            ex.message!! shouldContain "TTRP-DSP-004"
        }
    }) {
    companion object {
        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                if (Files.isDirectory(dir.resolve(rel))) return dir.resolve(rel)
                dir = dir.parent
            }
            // A golden dir that does not exist yet (first -DupdateGolden run) resolves under the module.
            val module = Path.of("").toAbsolutePath()
            return module.resolve(rel.substringAfter("ttrp-cli/"))
        }
    }
}
