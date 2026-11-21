// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.export.GeneratedHeader
import org.tatrman.ttr.metadata.export.Hashes
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class OpenMetadataSnapshotSpec :
    FunSpec({
        val firstPage =
            """
            {"data":[{"name":"orders","fullyQualifiedName":"shop.sales.east.orders",
              "databaseSchema":{"fullyQualifiedName":"shop.sales.east"},
              "updatedAt":123,"updatedBy":"private-editor","owners":[{"name":"private-owner"}],
              "usageSummary":{"count":9},"sourceHash":"volatile","domains":[],"dataProducts":[],
              "extension":{"api_key":"private-api-key","setting":"public-setting"},
              "lifeCycle":{},"retentionPeriod":"P1Y","entityStatus":"Approved",
              "tableConstraints":[{"constraintType":"FOREIGN_KEY","columns":["customer_id","region"],
                  "referredColumns":["shop.sales.east.customers.id","shop.sales.east.customers.region"]}],
              "columns":[{"name":"customer_id","fullyQualifiedName":"shop.sales.east.orders.customer_id","dataType":"INT"},
                {"name":"region","fullyQualifiedName":"shop.sales.east.orders.region","dataType":"INT"},
                {"name":"amount","dataType":"DECIMAL","dataLength":6,"precision":12,"scale":2},
                {"name":"extras","dataType":"ARRAY","arrayDataType":"JSON","children":[],"jsonSchema":"{}"}]}],
             "paging":{"after":"next+/="}}
            """.trimIndent()
        val secondPage =
            """
            {"data":[{"name":"customers","fullyQualifiedName":"shop.sales.west.customers",
                "databaseSchema":{"fullyQualifiedName":"shop.sales.west"},
                "columns":[{"name":"id","fullyQualifiedName":"shop.sales.west.customers.id","dataType":"INT"}]},
              {"name":"customers","fullyQualifiedName":"shop.sales.east.customers",
                "databaseSchema":{"fullyQualifiedName":"shop.sales.east"},
                "columns":[{"name":"id","fullyQualifiedName":"shop.sales.east.customers.id","dataType":"INT"},
                  {"name":"region","fullyQualifiedName":"shop.sales.east.customers.region","dataType":"INT"}]}],
             "paging":{}}
            """.trimIndent()
        val glossaryPage =
            """
            {"data":[{"name":"Customer","fullyQualifiedName":"Trade.Party.Customer","description":"A buyer",
                "entityStatus":"Approved","parent":{"fullyQualifiedName":"Trade.Party"},
                "relatedTerms":[{"fullyQualifiedName":"Trade.Supplier"}],"synonyms":["Buyer","Customer; retail"]},
              {"name":"Party","fullyQualifiedName":"Trade.Party","entityStatus":"Approved"},
              {"name":"Supplier","fullyQualifiedName":"Trade.Supplier","entityStatus":"Approved"},
              {"name":"Draft","fullyQualifiedName":"Trade.Draft","entityStatus":"Draft"}],"paging":{}}
            """.trimIndent()

        fun exec(
            cwd: Path,
            vararg args: String,
        ): CliOutcome {
            val binary = Path.of(requireNotNull(System.getProperty("metadata.installedBinary"))).toAbsolutePath()
            val process =
                ProcessBuilder(listOf(binary.toString()) + args)
                    .directory(cwd.toFile())
                    .apply { environment()["OM_SPEC_TOKEN"] = "invented-test-token" }
                    .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            return CliOutcome(process.waitFor(), stdout, stderr)
        }

        fun withServer(
            response: (path: String, rawQuery: String) -> Pair<Int, String>,
            run: (String) -> Unit,
        ) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.use {
                    val (status, body) =
                        if (exchange.requestHeaders.getFirst("Authorization") == "Bearer invented-test-token") {
                            response(exchange.requestURI.path, exchange.requestURI.rawQuery.orEmpty())
                        } else {
                            401 to "credentials are missing or incorrect"
                        }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            }
            server.start()
            try {
                run("http://127.0.0.1:${server.address.port}")
            } finally {
                server.stop(0)
            }
        }

        fun tables(
            path: String,
            query: String,
        ): Pair<Int, String> =
            when {
                path != "/api/v1/tables" || "fields=" !in query -> 400 to "unexpected table request"
                "after=next%2B%2F%3D" in query -> 200 to secondPage
                "after=" !in query -> 200 to firstPage
                else -> 400 to "cursor must be encoded"
            }

        fun capture(
            work: Path,
            url: String,
            profile: String = "om-tables/1",
            out: String = "sources",
        ) = exec(
            work,
            "snapshot",
            "--from",
            "openmetadata",
            "--url",
            url,
            "--token-env",
            "OM_SPEC_TOKEN",
            "--profile",
            profile,
            "--out",
            out,
        )

        fun importTables(
            work: Path,
            out: String = "model",
            source: String = "sources",
        ) = exec(
            work,
            "import-schema",
            "--from",
            "file:$source",
            "--profile",
            "om-tables/1",
            "--out",
            out,
            "--format",
            "json",
        )

        fun readerGuardCase(
            manifest: Boolean = true,
            packages: Map<String, String> = mapOf("east" to "om.shop.sales.east", "west" to "om.shop.sales.west"),
            out: String = "model",
            companionYaml: Boolean = false,
        ): Path {
            val work = Files.createTempDirectory("om-reader-guard")
            val sources = work.resolve("sources")
            val snapshots =
                packages.entries.associate { (schema, pkg) ->
                    val fqn = "shop.sales.$schema"
                    "$fqn.json" to
                        """
                        {"package":"$pkg","data":[{"name":"orders","fullyQualifiedName":"$fqn.orders",
                        "description":"$schema orders","columns":[{"name":"id","dataType":"INT"}]}]}
                        """.trimIndent()
                }
            if (manifest) {
                OpenMetadataSnapshots.write(sources, snapshots)
                sources.resolve("unrelated.json").writeText("outside the capture manifest")
            } else {
                Files.createDirectory(sources)
                snapshots.forEach { (name, text) -> sources.resolve(name).writeText(text) }
            }
            if (companionYaml) sources.resolve("notes.yaml").writeText("notes: capture companion\n")
            withClueOutcome(importTables(work, out))
            return work
        }

        fun strictLint(
            work: Path,
            root: String = "model",
        ) = exec(work, "lint", "--root", root, "--strict", "--format", "json")

        fun guardIssues(result: CliOutcome): List<Pair<String, String>> =
            Json
                .parseToJsonElement(result.stdout)
                .jsonObject
                .getValue("issues")
                .jsonArray
                .map { it.jsonObject }
                .filter { it.getValue("code").jsonPrimitive.content == "generated-file" }
                .map { issue ->
                    issue.getValue("file").jsonPrimitive.content to
                        issue
                            .getValue("message")
                            .jsonPrimitive.content
                            .substringBefore(':')
                }

        val eastModel = "om/shop/sales/east/db.ttrm"
        val eastSidecar = "om.shop.sales.east.sources.json"

        test("strict lint accepts both captured and legacy directory imports with genuine provenance") {
            for (manifest in listOf(true, false)) {
                val work = readerGuardCase(manifest)
                val model = work.resolve("model/$eastModel")
                val header = requireNotNull(GeneratedHeader.read(model.readText()))
                model.parent.resolve(header.snapshot.path).toRealPath() shouldBe work.resolve("sources").toRealPath()
                header.snapshot.sha256 shouldBe Hashes.tree(work.resolve("sources"))
                val lint = strictLint(work)
                withClueOutcome(lint)
                guardIssues(lint) shouldBe emptyList()
            }
        }

        test("strict lint accepts legacy imports whose snapshot contains companion YAML") {
            val work = readerGuardCase(manifest = false, companionYaml = true)
            work.resolve("model/$eastModel").exists() shouldBe true
            work.resolve("model/$eastSidecar").exists() shouldBe true
            val lint = strictLint(work)
            withClueOutcome(lint)
            guardIssues(lint) shouldBe emptyList()
        }

        test("strict lint uses the actual output directory for overlapping package paths") {
            val out = "multi/archive"
            val work =
                readerGuardCase(
                    packages = mapOf("east" to "om.shop.sales.east", "west" to "archive.om.shop.sales.east"),
                    out = out,
                )
            val east = work.resolve("$out/$eastModel")
            val original = east.readText()
            val archive = work.resolve("$out/archive/$eastModel").readText()
            archive shouldContain "west orders"
            for (root in listOf(out, "multi")) {
                val lint = strictLint(work, root)
                withClueOutcome(lint)
                guardIssues(lint) shouldBe emptyList()
            }

            east.writeText(original.substringBefore('\n') + "\n" + GeneratedHeader.body(archive))
            for ((root, path) in listOf(out to eastModel, "multi" to "archive/$eastModel")) {
                val lint = strictLint(work, root)
                lint.exitCode shouldBe 1
                guardIssues(lint) shouldBe listOf(path to "xm/generated-edited")
            }

            east.writeText(original)
            val sidecar = work.resolve("$out/$eastSidecar")
            sidecar.writeText(sidecar.readText() + "invalid JSON")
            val lint = strictLint(work, "multi")
            lint.exitCode shouldBe 1
            guardIssues(lint) shouldBe listOf("archive/$eastSidecar" to "xm/generated-edited")
        }

        test("strict lint locates a model body edit with its genuine header and leaves the other package clean") {
            val work = readerGuardCase()
            val model = work.resolve("model/$eastModel")
            val original = model.readText()
            original shouldContain "east orders"
            model.writeText(original.replace("east orders", "edited orders"))
            GeneratedHeader.read(model.readText()) shouldBe GeneratedHeader.read(original)
            val lint = strictLint(work)
            lint.exitCode shouldBe 1
            guardIssues(lint) shouldBe listOf(eastModel to "xm/generated-edited")
            lint.stdout shouldContain "the body differs from a fresh generation of its inputs"

            val west = work.resolve("model/om/shop/sales/west/db.ttrm").readText()
            model.writeText(original.substringBefore('\n') + "\n" + GeneratedHeader.body(west))
            val swapped = strictLint(work)
            swapped.exitCode shouldBe 1
            guardIssues(swapped) shouldBe listOf(eastModel to "xm/generated-edited")
        }

        test("strict lint catches a sidecar body edit with its genuine header") {
            val work = readerGuardCase()
            val sidecar = work.resolve("model/$eastSidecar")
            val original = sidecar.readText()
            original shouldContain "shop.sales.east.orders"
            sidecar.writeText(original.replace("shop.sales.east.orders", "shop.sales.east.edited"))
            val lint = strictLint(work)
            lint.exitCode shouldBe 1
            guardIssues(lint) shouldBe listOf(eastSidecar to "xm/generated-edited")
        }

        test("strict lint reports a changed directory snapshot only as stale") {
            val work = readerGuardCase()
            val source = work.resolve("sources/shop.sales.east.json")
            source.writeText(source.readText().replace("east orders", "new source description"))
            val lint = strictLint(work)
            lint.exitCode shouldBe 1
            guardIssues(lint).toSet() shouldBe
                setOf(
                    eastModel to "xm/generated-stale",
                    "om/shop/sales/west/db.ttrm" to "xm/generated-stale",
                    eastSidecar to "xm/generated-stale",
                    "om.shop.sales.west.sources.json" to "xm/generated-stale",
                )
        }

        fun importGlossary(work: Path) =
            exec(
                work,
                "import-glossary",
                "--from",
                "sources/glossary.json",
                "--profile",
                "om-glossary/1",
                "--out",
                "model",
            )

        fun importLive(
            work: Path,
            url: String,
            vararg extra: String,
        ) = exec(
            work,
            "import-schema",
            "--from",
            "openmetadata",
            "--url",
            url,
            "--token-env",
            "OM_SPEC_TOKEN",
            "--profile",
            "om-tables/1",
            "--out",
            "model",
            "--format",
            "json",
            *extra,
        )

        fun durableInput(work: Path): Path {
            val out = work.resolve("model")
            val east = out.resolve("om/shop/sales/east/db.ttrm")
            val model = requireNotNull(GeneratedHeader.read(east.readText()))
            val snapshot = east.parent.resolve(model.snapshot.path).toRealPath()
            snapshot.startsWith(out.toRealPath()) shouldBe true
            val hash = Hashes.tree(snapshot)
            model.snapshot.sha256 shouldBe hash
            val source = Json.parseToJsonElement(out.resolve("om.shop.sales.east.sources.json").readText()).jsonObject
            val header = source.getValue("generated-from").jsonPrimitive.content
            val sidecar = requireNotNull(GeneratedHeader.parse("# $header"))
            out.resolve(sidecar.snapshot.path).toRealPath() shouldBe snapshot
            sidecar.snapshot.sha256 shouldBe hash
            return snapshot
        }

        test("fetches every page with a bearer token and writes one file for each schema") {
            val work = Files.createTempDirectory("om-snapshot-paging")
            withServer(::tables) { url ->
                val result = capture(work, "$url/api/v1/")
                withClueOutcome(result)
                val east = work.resolve("sources/shop.sales.east.json").readText()
                val west = work.resolve("sources/shop.sales.west.json").readText()
                Json
                    .parseToJsonElement(east)
                    .jsonObject
                    .getValue("data")
                    .jsonArray.size shouldBe 2
                Json
                    .parseToJsonElement(west)
                    .jsonObject
                    .getValue("data")
                    .jsonArray.size shouldBe 1
            }
            withServer({ _, query ->
                if ("after=" in query) 401 to "invented-test-token" else 200 to firstPage
            }) { url ->
                val result = capture(work, url, out = "refused")
                result.exitCode shouldBe 1
                result.stdout shouldContain "HTTP 401"
                (result.stdout + result.stderr) shouldNotContain "invented-test-token"
                work.resolve("refused").exists() shouldBe false
            }
        }

        test("glossary REST rows import with parent, related terms, approved state and synonyms") {
            val work = Files.createTempDirectory("om-snapshot-glossary")
            withServer({ path, _ -> if (path == "/api/v1/glossaryTerms") 200 to glossaryPage else 404 to "" }) { url ->
                withClueOutcome(capture(work, url, "om-glossary/1"))
                withClueOutcome(importGlossary(work))
                val model = work.resolve("model/glossary/cn.ttrm").readText()
                model shouldContain "def kind om_Trade__Party__Customer"
                model shouldContain "is_a: [\"om_Trade__Party\"]"
                model shouldContain "to: \"om_Trade__Supplier\""
                model shouldNotContain "def kind om_Trade__Draft"
                val lexicon = work.resolve("model/glossary/lexicon.en.ttrm").readText()
                lexicon shouldContain "Buyer"
                lexicon shouldContain "Customer; retail"
            }
        }

        test("qualified glossary identities keep namesakes and their references distinct") {
            val work = Files.createTempDirectory("om-glossary-namesakes")
            val response =
                """
                {"data":[{"name":"Customer","fullyQualifiedName":"Sales.Customer","entityStatus":"Approved",
                  "id":"sales-customer","synonyms":["Purchaser"]},
                 {"name":"Customer","fullyQualifiedName":"Finance.Customer","entityStatus":"Approved",
                  "id":"finance-customer","synonyms":["Account holder"]},
                 {"name":"Retail","fullyQualifiedName":"Sales.Customer.Retail","entityStatus":"Approved",
                  "parent":{"id":"sales-customer","name":"Customer"},
                  "relatedTerms":[{"fullyQualifiedName":"Finance.Customer","name":"Customer"}]}],"paging":{}}
                """.trimIndent()
            withServer({ _, _ -> 200 to response }) { url -> withClueOutcome(capture(work, url, "om-glossary/1")) }
            withClueOutcome(importGlossary(work))
            val model = work.resolve("model/glossary/cn.ttrm").readText()
            model shouldContain "def kind om_Sales__Customer "
            model shouldContain "def kind om_Finance__Customer "
            model shouldContain "is_a: [\"om_Sales__Customer\"]"
            model shouldContain "to: \"om_Finance__Customer\""
            val sidecar = work.resolve("model/glossary.sources.json").readText()
            sidecar shouldContain "\"sourceName\":\"Sales.Customer\""
            sidecar shouldContain "\"sourceName\":\"Finance.Customer\""
            val lexicon = work.resolve("model/glossary/lexicon.en.ttrm").readText()
            lexicon shouldContain "om_Sales__Customer"
            lexicon shouldContain "om_Finance__Customer"
        }

        test("capture prunes ignored fields before writing glossary snapshot bytes") {
            val work = Files.createTempDirectory("om-glossary-hygiene")
            val secret = "invented-reference-api-key"
            val response =
                """
                {"data":[{"name":"Party","fullyQualifiedName":"Trade.Party","entityStatus":"Approved",
                   "references":[{"name":"Docs","endpoint":"https://example.invalid/doc?apiKey=$secret"}]},
                 {"name":"Draft","fullyQualifiedName":"Trade.Draft","entityStatus":"Draft",
                   "references":[{"name":"Docs","endpoint":"https://example.invalid/doc?apiKey=$secret"}]}],"paging":{}}
                """.trimIndent()
            withServer({ _, _ -> 200 to response }) { url ->
                val result = capture(work, url, "om-glossary/1")
                withClueOutcome(result)
                (result.stdout + result.stderr) shouldNotContain secret
            }
            val snapshot = work.resolve("sources/glossary.json").readText()
            snapshot shouldNotContain "\"references\""
            snapshot shouldNotContain secret
            val result = importGlossary(work)
            withClueOutcome(result)
            (result.stdout + result.stderr) shouldNotContain secret
        }

        test("key order and record page order do not change the sanitized snapshot bytes") {
            val work = Files.createTempDirectory("om-snapshot-stable")
            withServer(::tables) { url -> withClueOutcome(capture(work, url)) }
            val allTables =
                listOf(secondPage, firstPage).flatMap {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject
                        .getValue("data")
                        .jsonArray
                }
            withServer({ _, _ -> 200 to """{"paging":{},"data":$allTables}""" }) { url ->
                withClueOutcome(capture(work, url, out = "recaptured"))
            }
            val first = work.resolve("sources/shop.sales.east.json").readText()
            first shouldBe work.resolve("recaptured/shop.sales.east.json").readText()
            for (removed in listOf(
                "invented-test-token",
                "private-editor",
                "private-owner",
                "private-api-key",
                "updatedAt",
                "usageSummary",
                "dataLength",
                "retentionPeriod",
                "jsonSchema",
            )) {
                first shouldNotContain removed
            }
            work.resolve("sources/.gitattributes").readText() shouldContain "linguist-generated=true"
        }

        test("recapture retires removed schemas and preserves files outside the capture") {
            val work = Files.createTempDirectory("om-removed-schema")
            withServer(::tables) { url -> withClueOutcome(capture(work, url)) }
            val west = work.resolve("sources/shop.sales.west.json")
            west.exists() shouldBe true
            withClueOutcome(importTables(work))
            val unrelated = work.resolve("sources/unrelated.json")
            unrelated.writeText("authored")
            val east =
                listOf(
                    firstPage,
                    secondPage,
                ).flatMap {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject
                        .getValue("data")
                        .jsonArray
                }.filter { "shop.sales.east" in it.toString() }
            withServer({ _, _ -> 200 to """{"data":$east,"paging":{}}""" }) { url ->
                withClueOutcome(capture(work, url))
            }
            west.exists() shouldBe false
            unrelated.readText() shouldBe "authored"
            withClueOutcome(importTables(work, "second-model"))
            val generated = work.resolve("second-model/om/shop/sales/east/db.ttrm")
            val header = requireNotNull(GeneratedHeader.parse(generated.readText().lineSequence().first()))
            val source = generated.parent.resolve(header.snapshot.path).normalize()
            source shouldBe work.resolve("sources")
            header.snapshot.sha256 shouldBe Hashes.tree(source)
            work.resolve("second-model/om/shop/sales/west/db.ttrm").exists() shouldBe false
            withServer({ _, _ -> 200 to """{"data":[],"paging":{}}""" }) { url -> withClueOutcome(capture(work, url)) }
            withClueOutcome(importTables(work, "empty-model"))
            work.resolve("empty-model/om").exists() shouldBe false
        }

        test("a directory import enumerates schema snapshots and keeps same-named tables in distinct packages") {
            val work = Files.createTempDirectory("om-snapshot-packages")
            withServer(::tables) { url -> withClueOutcome(capture(work, url)) }
            withClueOutcome(importTables(work))
            for (schema in listOf("east", "west")) {
                val generated = work.resolve("model/om/shop/sales/$schema/db.ttrm").readText()
                generated shouldContain "def table customers"
            }
        }

        test("capture refuses to replace both user-owned files and locally edited current snapshots") {
            val work = Files.createTempDirectory("om-name-collision")
            val owned = Files.createDirectory(work.resolve("owned")).resolve("shop.sales.west.json")
            owned.writeText("""{"authored":true}""")
            withServer(::tables) { url ->
                val collision = capture(work, url, out = "owned")
                collision.exitCode shouldBe 2
                collision.stderr shouldContain "shop.sales.west.json"
                owned.readText() shouldBe """{"authored":true}"""
                work.resolve("owned/shop.sales.east.json").exists() shouldBe false

                withClueOutcome(capture(work, url))
                val east = work.resolve("sources/shop.sales.east.json")
                val west = work.resolve("sources/shop.sales.west.json")
                val edited = west.readText() + "\n"
                west.writeText(edited)
                val unedited = east.readText()
                val recapture = capture(work, url)
                recapture.exitCode shouldBe 2
                recapture.stderr shouldContain "shop.sales.west.json"
                west.readText() shouldBe edited
                east.readText() shouldBe unedited
            }
        }

        test("directory import refuses output paths that would alter its snapshot") {
            val work = Files.createTempDirectory("om-import-inside-source")
            withServer(::tables) { url -> withClueOutcome(capture(work, url, out = "om")) }
            for (out in listOf("om/generated", ".")) {
                val result = importTables(work, out, source = "om")
                result.exitCode shouldBe 2
                result.stderr shouldContain "would write inside snapshot directory"
                work.resolve("om/generated").exists() shouldBe false
                work.resolve("om/shop/sales/east/db.ttrm").exists() shouldBe false
                work.resolve("report.json").exists() shouldBe false
            }
        }

        test("a snapshot imports composite foreign keys and reports lossy or ignored metadata") {
            val work = Files.createTempDirectory("om-snapshot-relations")
            withServer(::tables) { url -> withClueOutcome(capture(work, url)) }
            val result = importTables(work)
            withClueOutcome(result)
            val model = work.resolve("model/om/shop/sales/east/db.ttrm").readText()
            model shouldContain "def fk fk_orders_1"
            model shouldContain
                "from: [om.shop.sales.east.db.dbo.orders.customer_id, om.shop.sales.east.db.dbo.orders.region]"
            model shouldContain
                "to: [om.shop.sales.east.db.dbo.customers.id, om.shop.sales.east.db.dbo.customers.region]"
            result.stdout shouldContain "mapped to text"
            work.resolve("model/report.json").exists() shouldBe true
        }

        test("direct import retains its input and a dry run previews the same provenance without creating output") {
            val work = Files.createTempDirectory("om-live-dry-run")
            withServer(::tables) { url ->
                val preview = importLive(work, url, "--dry-run")
                withClueOutcome(preview)
                preview.stderr shouldContain "+++ b/om/shop/sales/east/db.ttrm"
                work.resolve("model").exists() shouldBe false

                withClueOutcome(importLive(work, url))
                durableInput(work)
                val lint = strictLint(work)
                withClueOutcome(lint)
                guardIssues(lint) shouldBe emptyList()
                val repeat = importLive(work, url, "--dry-run")
                withClueOutcome(repeat)
                repeat.stderr shouldContain "No changes under model."
            }
        }

        test("direct recapture preserves edited retired schemas and names the actual new input") {
            val work = Files.createTempDirectory("om-live-recapture")
            withServer(::tables) { url -> withClueOutcome(importLive(work, url)) }
            val previous = durableInput(work)
            val west = previous.resolve("shop.sales.west.json")
            val edited = west.readText() + "\n"
            west.writeText(edited)

            val east =
                listOf(firstPage, secondPage)
                    .flatMap {
                        Json
                            .parseToJsonElement(it)
                            .jsonObject
                            .getValue("data")
                            .jsonArray
                    }.filter { "shop.sales.east" in it.toString() }
            withServer({ _, _ -> 200 to """{"data":$east,"paging":{}}""" }) { url ->
                val result = importLive(work, url)
                withClueOutcome(result)
                val packages = Json.parseToJsonElement(result.stdout).jsonObject.getValue("packages")
                packages.toString() shouldNotContain "om.shop.sales.west"
                val retained = durableInput(work)
                retained.resolve("shop.sales.west.json").exists() shouldBe false
                west.readText() shouldBe edited
            }
        }

        test("direct import retains provenance when publication fails and can retry successfully") {
            val work = Files.createTempDirectory("om-live-retry")
            val report = Files.createDirectories(work.resolve("model/report.json"))
            withServer(::tables) { url ->
                val failed = importLive(work, url)
                failed.exitCode shouldBe 2
                failed.stderr shouldContain "report.json"
                val retained = durableInput(work)
                Files.delete(report)
                withClueOutcome(importLive(work, url))
                durableInput(work) shouldBe retained
            }
        }

        test("direct import preserves authored and edited models and can update its own unchanged outputs") {
            val modelPath = "model/om/shop/sales/east/db.ttrm"
            val authoredWork = Files.createTempDirectory("om-live-authored")
            val authored = authoredWork.resolve(modelPath)
            Files.createDirectories(authored.parent)
            authored.writeText("// authored model\n")
            withServer(::tables) { url ->
                val refusal = importLive(authoredWork, url)
                refusal.exitCode shouldBe 2
                refusal.stderr shouldContain "authored or edited output"
                authored.readText() shouldBe "// authored model\n"
                authoredWork.resolve("model/report.json").exists() shouldBe false
            }

            val work = Files.createTempDirectory("om-live-edited")
            val model = work.resolve(modelPath)
            withServer(::tables) { url ->
                withClueOutcome(importLive(work, url))
                val generated = model.readText()
                val edited = generated + "// local edit\n"
                model.writeText(edited)
                val refusal = importLive(work, url)
                refusal.exitCode shouldBe 2
                refusal.stderr shouldContain "authored or edited output"
                model.readText() shouldBe edited
                model.writeText(generated)
            }
            withServer({ path, query ->
                val (status, body) = tables(path, query)
                status to
                    body.replace(
                        "\"name\":\"orders\",\"fullyQualifiedName\"",
                        "\"name\":\"orders\",\"description\":\"Replacement description\",\"fullyQualifiedName\"",
                    )
            }) { url ->
                withClueOutcome(importLive(work, url))
                model.readText() shouldContain "Replacement description"
                durableInput(work)
            }
        }
    })

private fun withClueOutcome(result: CliOutcome) {
    withClue(result.stderr + result.stdout.takeIf { result.exitCode != 0 }.orEmpty()) {
        result.exitCode shouldBe 0
    }
}
