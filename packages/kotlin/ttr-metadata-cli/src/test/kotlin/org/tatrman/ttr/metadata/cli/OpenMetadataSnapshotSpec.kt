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
    })

private fun withClueOutcome(result: CliOutcome) {
    withClue(result.stderr + result.stdout.takeIf { result.exitCode != 0 }.orEmpty()) {
        result.exitCode shouldBe 0
    }
}
