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
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

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

        fun importTables(work: Path) =
            exec(
                work,
                "import-schema",
                "--from",
                "file:sources",
                "--profile",
                "om-tables/1",
                "--out",
                "model",
                "--format",
                "json",
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
                val result =
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
                withClueOutcome(result)
                val model = work.resolve("model/glossary/cn.ttrm").readText()
                model shouldContain "def kind Customer"
                model shouldContain "is_a: [\"Party\"]"
                model shouldContain "to: \"Supplier\""
                model shouldNotContain "def kind Draft"
                val lexicon = work.resolve("model/glossary/lexicon.en.ttrm").readText()
                lexicon shouldContain "Buyer"
                lexicon shouldContain "Customer; retail"
            }
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
            )) {
                first shouldNotContain removed
            }
            work.resolve("sources/.gitattributes").readText() shouldContain "linguist-generated=true"
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
            result.stdout shouldContain "retentionPeriod"
            result.stdout shouldContain "jsonSchema"
            work.resolve("model/report.json").exists() shouldBe true
        }
    })

private fun withClueOutcome(result: CliOutcome) {
    withClue(result.stderr + result.stdout.takeIf { result.exitCode != 0 }.orEmpty()) {
        result.exitCode shouldBe 0
    }
}
