// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttrp.emit.recipe.RecipeFingerprints
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * AU-core-P2 · S2.3 T1–T7 — a recipe builds into `<recipe>.bundle/` (contracts §5.2): one statement file per output,
 * manifest v3 with the `recipe` object (§5.1), the generated catalog (§5.3) — and the fingerprint rules: a parameter
 * value moves `values` only, a `because:` moves `labels` only, reformatting moves nothing, logic moves `text`.
 */
class RecipeBundleSpec :
    StringSpec({
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
        val manifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures)

        fun source(name: String) = Files.readString(recipes.resolve(name))

        fun build(
            name: String,
            text: String = source(name),
        ): BundleAssembler.BundleResult =
            BundleAssembler("1.0.0").build(
                source = text,
                fileName = name,
                pipelineManifest = manifest,
                modelsRoot = fixtures.resolve("model"),
                outDir = Files.createTempDirectory("ttrp-recipe"),
            )

        fun plan(text: String) = TtrpPipeline(manifest, fixtures.resolve("model")).plan(text, "release_order.ttrp")

        "release_order builds: a statement file per output, the catalog, manifest v3 with every file's sha256" {
            val r = build("release_order.ttrp")
            r.dir.fileName.toString() shouldBe "release_order.bundle"
            r.emitRequest shouldBe null
            val m = r.manifest
            m.schemaVersion shouldBe 3
            m.ttrpVersion shouldBe 2
            m.islands.map { it.name } shouldContainExactly
                listOf("placed_orders", "shortfall", "price_differences", "overdue", "verdict")
            m.islands.forEach {
                it.schema shouldBe "er"
                it.invocation shouldBe "sql-text"
            }
            m.islands
                .single { it.name == "verdict" }
                .outputs!!
                .map { it.file } shouldContain
                "islands/verdict.hold_sales.shortfall.sql"
            m.files.keys shouldContain "catalog/release_order.ttrm"
            m.files.keys shouldContain "catalog/release_order_verdict.ttrm"
            for ((rel, sha) in m.files) {
                val bytes = Files.readAllBytes(r.dir.resolve(rel))
                "sha256:" +
                    MessageDigest
                        .getInstance(
                            "SHA-256",
                        ).digest(bytes)
                        .joinToString("") { "%02x".format(it) } shouldBe
                    sha
            }
            // no launcher, no transfers
            Files.exists(r.dir.resolve("run.sh")) shouldBe false
            m.transfers.shouldBeEmpty()
        }

        "the manifest validates against bundle-manifest-v3.schema.json" {
            for (f in listOf("release_order.ttrp", "decide_return.ttrp", "monitor_inbound.ttrp", "settle.ttrp")) {
                val json = Files.readString(build(f).dir.resolve("manifest.json"))
                val schema =
                    JsonSchemaFactory
                        .getInstance(SpecVersion.VersionFlag.V202012)
                        .getSchema(javaClass.getResourceAsStream("/schemas/bundle-manifest-v3.schema.json")!!)
                schema.validate(ObjectMapper().readTree(json)).map { it.message }.shouldBeEmpty()
            }
        }

        "the recipe object: roles, routes, wiring in TTR-P's spelling, mode, the verdict schema, what it reads" {
            val rec = build("release_order.ttrp").manifest.recipe!!
            rec.name shouldBe "release_order"
            rec.key shouldContainExactly listOf("order.order_number")
            rec.document shouldBe "releasing-a-placed-order.md"
            rec.parameters.map { "${it.name}:${it.type}=${it.value}" } shouldContainExactly
                listOf("tolerance:decimal=0.01", "confirmation_threshold:decimal=50000")
            rec.bindings.map { it.name } shouldContainExactly listOf("as_of")
            rec.roles.candidates shouldBe "placed_orders"
            rec.roles.contexts.map { "${it.name}:${it.contextClass}" } shouldContainExactly
                listOf("shortfall:per-candidate", "price_differences:per-candidate", "overdue:per-candidate")
            rec.roles.switchMode shouldBe "overlapping"
            rec.roles.routes
                .single { it.name == "hold_sales" }
                .detail shouldContainExactly
                listOf("shortfall", "price_differences")
            rec.wiring.single { it.action == "ask" }.columns["on_due"] shouldBe
                "hold { order: order_number, department: \"Sales\" }"
            rec.wiring.single { it.action == "ask" }.columns["due"] shouldBe "2 working_days"
            rec.mode shouldBe "per-candidate"
            rec.verdictSchema.entity shouldBe "release_order_verdict"
            rec.verdictSchema.columns.map { it.name } shouldContainExactly
                listOf(
                    "run",
                    "as_of",
                    "order_number",
                    "route",
                    "verdict_reason",
                    "parameters_fp",
                    "run_as",
                    "written_at",
                )
            rec.objectsRead shouldContainExactly
                listOf(
                    "er.entity.customer_price",
                    "er.entity.inventory",
                    "er.entity.invoice",
                    "er.entity.order",
                    "er.entity.price_list",
                    "er.entity.sales_line",
                    "er.entity.warehouse_outage",
                )
            rec.protocol shouldBe "always"
            listOf(rec.fingerprints.text, rec.fingerprints.values, rec.fingerprints.labels).forEach {
                it shouldMatch Regex("sha256:[0-9a-f]{64}")
            }
        }

        "a derived value is bound by the runner; a lookup context's output is not keyed, a route's is" {
            val m = build("decide_return.ttrp").manifest
            m.recipe!!.bindings.map { "${it.name}:${it.source}:${it.derive?.amount}" } shouldContainExactlyInAnyOrder
                listOf(
                    "as_of:run:null",
                    "as_of_minus_return_window_days:derived:return_window",
                    "as_of_minus_365_days:derived:365",
                )
            val out = m.islands.flatMap { i -> i.outputs!!.map { "${i.name}.${it.port}" to it.keyed } }.toMap()
            out["request_value.rows"] shouldBe false
            out["bought_here.rows"] shouldBe true
            out["received.lines"] shouldBe true
            out["way.reject_late"] shouldBe true
        }

        "fingerprints: a value moves `values`, a `because:` `labels`, reformatting nothing, logic `text`" {
            val base = plan(source("release_order.ttrp"))
            val fp = RecipeFingerprints.of(base, base.recipe!!, "always", "now")

            val newValue =
                base.recipe!!.copy(
                    parameterDecls =
                        base.recipe!!.parameterDecls.map {
                            if (it.name ==
                                "tolerance"
                            ) {
                                it.copy(value = "0.05")
                            } else {
                                it
                            }
                        },
                )
            RecipeFingerprints.of(base, newValue, "always", "now").let {
                it.text shouldBe fp.text
                it.values shouldNotBe fp.values
                it.labels shouldBe fp.labels
            }

            val relabelled = plan(source("release_order.ttrp").replace("\"overdue invoices\"", "\"invoices past due\""))
            RecipeFingerprints.of(relabelled, relabelled.recipe!!, "always", "now").let {
                it.text shouldBe fp.text
                it.values shouldBe fp.values
                it.labels shouldNotBe fp.labels
            }

            val reformatted =
                plan(
                    source("release_order.ttrp")
                        .replace("recipe {", "// reformatted\nrecipe {\n")
                        .replace("hold_ar:           n_due > 0", "hold_ar:   n_due   >   0"),
                )
            RecipeFingerprints.of(reformatted, reformatted.recipe!!, "always", "now") shouldBe fp

            val changed =
                plan(
                    source(
                        "release_order.ttrp",
                    ).replace("hold_ar:           n_due > 0", "hold_ar:           n_due > 1"),
                )
            RecipeFingerprints.of(changed, changed.recipe!!, "always", "now").text shouldNotBe fp.text
        }

        "fingerprints: a window is logic, and so is a context's own `verdict_reason` (review-119 B3)" {
            val head =
                "uses world \"hartland.worlds.demo\"\nimport hartland.*\nimport hartland.automation.*\nrecipe {\n" +
                    "key: order.order_number\n" +
                    "candidates placed(out orders) target erp \"\"\"sql\n" +
                    "SELECT o.order_number, o.customer, o.channel, o.value FROM er.entity.order o\n\"\"\"\n"
            val tail =
                "decision v(in orders, in c) { orders -> switch { go: value > 0  because: \"x\" } }\n" +
                    "actions { v.go -> release { order: order_number } }\n}\n"

            fun text(context: String): String {
                // not `release_order.ttrp`: its `port: key` program would refuse a windowed recipe (REC-012)
                val p = TtrpPipeline(manifest, fixtures.resolve("model")).plan("$head$context\n$tail", "fp_probe.ttrp")
                withClue(p.diagnostics.joinToString("\n")) { p.ok shouldBe true }
                return RecipeFingerprints.of(p, p.recipe!!, "always", "now").text
            }

            fun windowed(window: String) =
                text(
                    "context c(in orders, out rows) target erp window { $window } {\n" +
                        "orders -> aggregate { group_by: customer  n = count(order_number) } -> rows\n}",
                )
            val day = windowed("by: customer over: day")
            windowed("by: customer over: week") shouldNotBe day
            windowed("by: customer over: rows: 5") shouldNotBe day
            windowed("by: customer over: day") shouldBe day

            // the name `verdict_reason` is reserved on the switch's input row only — a context's own is its logic
            fun ownReason(threshold: String) =
                text(
                    "context c(in orders, out rows) target erp {\n" +
                        "orders -> calc(verdict_reason = (value > $threshold)) -> filter(verdict_reason) " +
                        "-> select(order_number) -> rows\n}",
                )
            ownReason("1000000") shouldNotBe ownReason("100")
        }

        "kinds: one sorted list in manifest and catalog; `else: none` is \"none\" (review-119 M4, m3)" {
            for (f in listOf("release_order.ttrp", "decide_return.ttrp", "monitor_inbound.ttrp", "settle.ttrp")) {
                val r = build(f)
                r.manifest.recipe!!.kinds shouldContainExactly listOf("sql")
                Files.readString(r.dir.resolve("catalog/${f.removeSuffix(".ttrp")}.ttrm")) shouldContain
                    "kinds: [\"sql\"]"
            }
            build("monitor_inbound.ttrp")
                .manifest.recipe!!
                .roles.elseRoute shouldBe "none"
            build("decide_return.ttrp")
                .manifest.recipe!!
                .roles.elseRoute shouldBe "refund_after_return"
            build("settle.ttrp")
                .manifest.recipe!!
                .roles.elseRoute shouldBe null
        }

        "the detail entity's own columns win over a detail context's `run` / `row_no` (review-119 m4)" {
            val base = Files.readString(fixtures.resolve("base-order.ttrp"))
            val src =
                base.replace(
                    "SELECT o.order_number, i.invoice_id FROM orders o",
                    "SELECT o.order_number, i.invoice_id, i.issued AS run, i.amount AS row_no FROM orders o",
                )
            (src != base) shouldBe true
            val r = build("detail_probe.ttrp", src)
            val catalog = Files.readString(r.dir.resolve("catalog/detail_probe_verdict.ttrm"))
            val detail = catalog.substringAfter("def entity detail_probe_verdict_detail").substringBefore("def entity")
            val attrs = Regex("""def attribute (\w+)""").findAll(detail).map { it.groupValues[1] }.toList()
            attrs shouldBe attrs.distinct()
            attrs shouldContain "invoice_id"
            attrs.count { it == "run" } shouldBe 1
            attrs.count { it == "row_no" } shouldBe 1
        }

        "a `dry_run:` text moves `labels` only (review-119 m7, D-125)" {
            val base = plan(source("release_order.ttrp"))
            val fp = RecipeFingerprints.of(base, base.recipe!!, "always", "now")
            base.recipe!!.dryRuns.keys shouldContain "release"
            val reworded =
                base.recipe!!.copy(
                    dryRuns = base.recipe!!.dryRuns.mapValues { (_, t) -> t.mapValues { "${it.value}!" } },
                )
            RecipeFingerprints.of(base, reworded, "always", "now").let {
                it.text shouldBe fp.text
                it.values shouldBe fp.values
                it.labels shouldNotBe fp.labels
            }
        }

        "a build is deterministic: two builds write the same bytes" {
            for (f in listOf("release_order.ttrp", "decide_return.ttrp", "monitor_inbound.ttrp", "settle.ttrp")) {
                val a = build(f)
                val b = build(f)
                a.manifest.files shouldBe b.manifest.files
                Files.readString(a.dir.resolve("manifest.json")) shouldBe
                    Files.readString(b.dir.resolve("manifest.json"))
            }
        }

        "the four `17` catalogs load in one snapshot with hartland's model and automation package" {
            val out = Files.createTempDirectory("ttrp-recipes")
            for (f in listOf("release_order.ttrp", "decide_return.ttrp", "monitor_inbound.ttrp", "settle.ttrp")) {
                BundleAssembler("1.0.0").build(source(f), f, manifest, fixtures.resolve("model"), out)
            }
            val snapshots =
                listOf("model" to fixtures.resolve("model"), "bundles" to out).map { (id, root) ->
                    FileBasedSource(id, 100, LocalFsStorage(id, root)).load()
                }
            val result = ModelReconciler(ModelDescriptor(id = "recipes", name = "recipes")).reconcile(snapshots)
            (snapshots.flatMap { it.errors } + result.errors)
                .filter { it.sourceId == "bundles" && !it.message.startsWith("ttr/package-") }
                .map { it.message }
                .shouldBeEmpty()
            val entities =
                result.model.schemas.values
                    .flatMap { s -> (s as? org.tatrman.ttr.metadata.model.ErSchema)?.entities?.values.orEmpty() }
                    .map { it.qname.name }
            for (r in listOf("release_order", "decide_return", "monitor_inbound", "settle")) {
                entities shouldContain
                    "${r}_verdict"
            }
        }

        "the generated catalog loads through ttr-metadata beside hartland's model — the shape `/refresh` loads" {
            val r = build("release_order.ttrp")
            val catalog = r.dir.resolve("catalog")
            r.manifest.files.keys.filter { it.startsWith("catalog/") }.forEach {
                Files.readString(r.dir.resolve(it)) shouldStartWith "// GENERATED by `ttrp build`"
            }
            val snapshots =
                listOf("model" to fixtures.resolve("model"), "catalog" to catalog).map { (id, root) ->
                    FileBasedSource(id, 100, LocalFsStorage(id, root)).load()
                }
            val result = ModelReconciler(ModelDescriptor(id = "recipes", name = "recipes")).reconcile(snapshots)
            // the package-layout codes are never findings for the loader (ttr-server BundleLoader: hartland's packages
            // do not follow its directories, `layout = "off"`), so not here either
            val catalogErrors =
                (snapshots.flatMap { it.errors } + result.errors)
                    .filter { it.sourceId == "catalog" && !it.message.startsWith("ttr/package-") }
            catalogErrors.map { it.message }.shouldBeEmpty()
            val entities =
                result.model.schemas.values
                    .flatMap { s -> (s as? org.tatrman.ttr.metadata.model.ErSchema)?.entities?.values.orEmpty() }
                    .map { it.qname.name }
            entities shouldContain "release_order_verdict"
            entities shouldContain "release_order_verdict_detail"
            entities shouldContain "release_order_verdict_current"
        }
    })
