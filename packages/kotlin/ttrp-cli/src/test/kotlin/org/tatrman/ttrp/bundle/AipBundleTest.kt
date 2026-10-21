// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifestReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * AG-P0 S1 — a program targeting an `erp` engine of type `mssql-2019` under the `aip` executor builds
 * into a host-executed bundle: one self-contained T-SQL statement per island OUT port (`sql-text`),
 * typed outputs + bound params in the manifest, display → island-port sources, and no launcher. The
 * model is split across the project's `models/` (the world) and a host-GENERATED root
 * (`extra-model-roots`), the shape a host's model repository uses. The generated model is a slice of
 * the Hartland demo database (TPC-DS); its logical names and the program's names are Czech on purpose —
 * this fixture is where Czech (diacritic) identifiers are carried end to end through `sql-text` (quoted
 * aliases, named params, island / port / statement-file names, displays). They are taken from Hartland's
 * Czech world (`hartland_cz`); physical names and descriptions stay TPC-DS / English.
 */
class AipBundleTest :
    FunSpec({
        val project = Paths.get("src/test/resources/fixtures/aip-project")
        val expected = project.resolve("expected")
        val manifest = TtrpManifestReader.resolve(project).manifest
        val heroSource = Files.readString(project.resolve("aip-hero.ttrp"))

        fun build(
            source: String = heroSource,
            out: Path = Files.createTempDirectory("ttrp-aip"),
        ) = BundleAssembler("1.0.0").build(
            source = source,
            fileName = "aip-hero.ttrp",
            pipelineManifest = manifest,
            modelsRoot = manifest.modelsRoot(),
            outDir = out,
        )

        val updating = System.getProperty("updateGolden") == "true"

        /** Compare [actual] with `expected/<name>`; under `-DupdateGolden=true` rewrite it and return false. */
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

        test("every island is a host-executed sql-text island of the aip executor") {
            val m = build().manifest
            m.islands.map { it.name } shouldContainExactlyInAnyOrder listOf("objednávka", "sklad", "skladem", "verdikt")
            m.islands.forEach {
                it.invocation shouldBe "sql-text"
                it.executor shouldBe "aip"
                it.connections shouldBe listOf("TTR_CONN_ERP")
            }
        }

        test("one statement file per OUT port, with the output row type and the params it binds") {
            val r = build()
            val obj = r.manifest.islands.first { it.name == "objednávka" }
            obj.outputs!!.map { it.port } shouldContainExactly listOf("hlavička", "řádky")
            val hl = obj.outputs!!.first { it.port == "hlavička" }
            // `select(...)` keeps exactly the listed columns (the SelectToProject fix).
            hl.columns.map { it.name } shouldContainExactly listOf("číslo_objednávky", "id_zákazníka", "datum_prodeje")
            hl.params shouldBe listOf("objednávka_id")
            Files.isRegularFile(r.dir.resolve(hl.file)) shouldBe true
            val sklad =
                r.manifest.islands
                    .first { it.name == "sklad" }
                    .outputs!!
                    .single()
            sklad.params shouldBe listOf("objednávka_id", "centrum", "stav_k")
            // a computed coalesce column keeps its argument's (physical, table-bound) type — not `text`
            sklad.columns.first { it.name == "disponibilní" }.type shouldBe "int"
        }

        test("the island statements match their goldens") {
            val r = build()
            val checked =
                r.manifest.islands.flatMap { it.outputs.orEmpty() }.map { o ->
                    golden(requireNotNull(o.file).removePrefix("islands/"), Files.readString(r.dir.resolve(o.file)))
                }
            // Updated goldens must be reviewed in `git diff`, never silently green.
            if (checked.any { !it }) fail("goldens updated — review git diff, re-run without -DupdateGolden")
        }

        test("statements are self-contained T-SQL: physical tables renamed, inline sources, named params") {
            val r = build()

            fun sql(
                island: String,
                port: String,
            ) = Files.readString(r.dir.resolve("islands/$island.$port.sql"))
            val rows = sql("objednávka", "řádky")
            // physical table (unqualified — the door resolves DB tables unqualified), renamed to the
            // logical attribute names; identifiers double-quoted (the door's parser reads no brackets)
            rows shouldContain "FROM \"catalog_sales\""
            rows shouldContain "\"cs_order_number\" AS \"číslo_objednávky\""
            // the query-bound entity's `__filter` source, inlined as a derived table
            rows shouldContain "SELECT * FROM catalog_orders WHERE ship_mode_type='EXPRESS'"
            rows shouldContain ") AS \"objednávka__filter\""
            // `on: relation řádek_objednávka` in its LOGICAL spelling (the loads keep logical names)
            rows shouldContain "\"t\".\"číslo_objednávky\" = \"t1\".\"číslo_objednávky\""
            // runtime params are named placeholders, never positional
            rows shouldContain ":objednávka_id"
            r.manifest.islands.flatMap { it.outputs.orEmpty() }.forEach { o ->
                val text = Files.readString(r.dir.resolve(o.file))
                text shouldNotContain "?"
                text shouldNotContain "_ttrp_inline"
                text shouldNotContain "_ttrp_table"
                // door dialect: no bracket-quoted identifiers, no schema-qualified tables
                text shouldNotContain "[dbo]"
                Regex("""\[[^\]]*]""").containsMatchIn(text) shouldBe false
                // the Calcite semi/anti double-alias shape (F-AG-8) never reaches a statement
                Regex("""\) AS "[^"]+" AS """").containsMatchIn(text) shouldBe false
            }
        }

        test("semi / anti joins lower to joins against the distinct right keys (no native EXISTS shape)") {
            val r = build()
            val anti = Files.readString(r.dir.resolve("islands/skladem.nevedené.sql"))
            anti shouldContain "LEFT JOIN"
            anti shouldContain "1 AS \"_ttrp_exists\""
            anti shouldContain "\"_ttrp_exists\" IS NULL"
            val semi = Files.readString(r.dir.resolve("islands/skladem.vedené.sql"))
            semi shouldContain "INNER JOIN (SELECT \"inv_item_sk\" AS \"id_produktu\""
            semi shouldContain "GROUP BY \"inv_item_sk\""
        }

        test("displays name the island port that feeds them; a host-executed bundle has no launcher") {
            val r = build()
            r.manifest.displays.associate { it.name to (it.source!!.island + "." + it.source!!.port) } shouldBe
                mapOf(
                    "nevedené" to "skladem.nevedené",
                    "uvolnit" to "verdikt.uvolnit",
                    "vedené" to "skladem.vedené",
                    "výpadky" to "sklad.výpadky",
                )
            Files.exists(r.dir.resolve("run.sh")) shouldBe false
            r.manifest.files.keys
                .any { it == "run.sh" } shouldBe false
        }

        test("params are declared in source order with the run-date-free defaults") {
            build().manifest.params!!.map { it.name to it.default } shouldContainExactly
                listOf("objednávka_id" to null, "centrum" to "1", "stav_k" to null)
        }

        test("the aip manifest validates against the v2 JSON Schema (sql-text additions)") {
            val json = build().manifest.toJson()
            val schemaStream = javaClass.getResourceAsStream("/schemas/bundle-manifest-v2.schema.json")!!
            val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaStream)
            schema.validate(ObjectMapper().readTree(json)) shouldBe emptySet()
        }

        test("the manifest records the extra (generated) model root's content fingerprint (S5, C-21)") {
            val roots = build().manifest.modelRoots!!
            roots.map { it.path } shouldContainExactly listOf("generated")
            roots.single().fingerprint shouldBe ModelFingerprint.of(project.resolve("generated"))
        }

        test("ModelFingerprint = sha256 over sorted `path\tsha256(content)` lines of .ttrm files (shared vector)") {
            // The SAME vector is pinned in the host generator's own spec — the two implementations must agree.
            val root = Files.createTempDirectory("fp-vector")
            Files.createDirectories(root.resolve("a"))
            Files.createDirectories(root.resolve("b"))
            Files.writeString(root.resolve("a/er.ttrm"), "package a\n")
            Files.writeString(root.resolve("b/db.ttrm"), "package b\ndef table T { }\n")
            Files.writeString(root.resolve("FINGERPRINT"), "ignored — not a .ttrm\n")
            ModelFingerprint.of(root) shouldBe "sha256:d367da3bec13b33d5279c58e578587636060ffc803fee81e40ae09fab3a91c63"
        }

        test("building twice is byte-identical (determinism)") {
            val a = build()
            val b = build()
            a.manifest.toJson() shouldBe b.manifest.toJson()
            a.manifest.files.keys.forEach { rel ->
                Files.readString(a.dir.resolve(rel)) shouldBe Files.readString(b.dir.resolve(rel))
            }
        }

        test("a `store` under the aip executor is TTRP-CAP-204") {
            val withStore = heroSource + "\nsklad.výpadky -> store(erp_db.vypadky)\n"
            val ex = shouldThrow<IllegalArgumentException> { build(withStore) }
            ex.message!! shouldContain "TTRP-CAP-204"
        }

        test("`retries` under the aip executor is TTRP-CAP-203 (the host owns retries)") {
            val withRetries =
                heroSource.replace(
                    "container sklad(in řádky, out výpadky) target erp {",
                    "container sklad(in řádky, out výpadky) target erp retries 2 {",
                )
            withRetries shouldContain "retries 2"
            val ex = shouldThrow<IllegalArgumentException> { build(withRetries) }
            ex.message!! shouldContain "TTRP-CAP-203"
        }

        test("the program checks clean — Czech identifiers, calc columns and IN-port joins raise nothing") {
            val plan = TtrpPipeline(manifest, manifest.modelsRoot()).plan(heroSource, "aip-hero.ttrp")
            plan.diagnostics.filter { it.severity.name == "ERROR" } shouldBe emptyList()
            plan.ok shouldBe true
        }
    })
