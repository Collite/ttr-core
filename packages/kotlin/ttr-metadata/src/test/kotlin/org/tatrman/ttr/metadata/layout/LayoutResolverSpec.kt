// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * VL-P0 T8 — `LayoutResolver` (contracts §4, V-15, V-16): resolution over every shared vector's
 * `model.json` gives its `expect.json` (`packages/package-sdk/schemas/layout-vectors/`, read in place),
 * and the index built from a really loaded model has the catalog's node shape.
 */
class LayoutResolverSpec :
    StringSpec({
        // the test working dir is the module dir (packages/kotlin/ttr-metadata)
        val vectors = Path.of("../../package-sdk/schemas/layout-vectors")
        val cases = vectors.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }

        fun json(
            case: Path,
            file: String,
        ) = Json.parseToJsonElement(Files.readString(case.resolve(file))).jsonObject

        fun entries(a: JsonArray?) =
            a.orEmpty().map {
                val e = it.jsonObject

                fun f(k: String) = e[k]!!.jsonPrimitive.content
                LayoutIndexEntry(f("fullQname"), f("qname"), f("kind"), f("name"), f("pkg"), f("schema"))
            }

        fun asJson(r: LayoutResolution): Map<String, Any> =
            mapOf(
                "resolved" to r.resolved,
                "missing" to r.missing,
                "ambiguous" to r.ambiguous.map { mapOf("ref" to it.ref, "candidates" to it.candidates) },
                "ignored" to r.ignored,
            )

        fun expected(o: JsonObject): Map<String, Any> =
            mapOf(
                "resolved" to o["resolved"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
                "missing" to o["missing"]!!.jsonArray.map { it.jsonPrimitive.content },
                "ambiguous" to
                    o["ambiguous"]!!.jsonArray.map { a ->
                        mapOf(
                            "ref" to a.jsonObject["ref"]!!.jsonPrimitive.content,
                            "candidates" to a.jsonObject["candidates"]!!.jsonArray.map { it.jsonPrimitive.content },
                        )
                    },
                "ignored" to o["ignored"]!!.jsonArray.map { it.jsonPrimitive.content },
            )

        "the shared vectors are there (≥ 8 cases)" {
            cases shouldHaveAtLeastSize 8
        }

        "resolve(document.objects, model.json, document.model) == expect.json for every vector" {
            for (case in cases) {
                val doc = json(case, "document.json")
                val model = json(case, "model.json")
                val index = LayoutModelIndex(entries(model["cards"]?.jsonArray), entries(model["edges"]?.jsonArray))
                val r =
                    LayoutResolver.resolve(
                        doc["objects"]!!.jsonArray.map { it.jsonPrimitive.content },
                        index,
                        doc["model"]!!.jsonPrimitive.content,
                    )
                val exp = expected(json(case, "expect.json"))
                withClue(case.name) {
                    asJson(r) shouldBe exp
                    // and in the same (alphabetical) order
                    r.resolved.keys.toList() shouldBe (exp.getValue("resolved") as Map<*, *>).keys.toList()
                }
            }
        }

        // ── the index from a really loaded model (the catalog's node shape) ──

        val root = Files.createTempDirectory("vl-layout-resolver")
        Files.createDirectories(root.resolve("er"))
        Files.createDirectories(root.resolve("db"))
        Files.writeString(
            root.resolve("er/model.ttrm"),
            """
            package hartland

            model er

            def entity item { attributes: [ def attribute sk { type: int, isKey: true } ] }
            def entity store { attributes: [ def attribute sk { type: int, isKey: true } ] }
            def entity store_sales { attributes: [ def attribute item { type: int }, def attribute store { type: int } ] }
            def relation rel_store_sales_item { from: er.entity.store_sales, to: er.entity.item, cardinality: { from: "0..*", to: "1" } }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("db/model.ttrm"),
            """
            package hartland

            model db schema dbo

            def table item { primaryKey: [i_item_sk] columns: [ def column i_item_sk { type: int, isKey: true } ] }
            def table store_sales { columns: [ def column ss_item_sk { type: int } ] }
            def view channel_sales { columns: [ def column item_sk { type: int } ] }
            def fk fk_ss_item { from: [db.dbo.store_sales.ss_item_sk], to: [db.dbo.item.i_item_sk] }
            """.trimIndent(),
        )
        val source =
            FileBasedSource(sourceId = "vl", priority = 100, storage = LocalFsStorage(id = "vl", rootPath = root))
        val model = MetadataLoader(source, ModelDescriptor(id = "m", name = "m")).load().model.shouldNotBeNull()
        val snapshot = RegistrySnapshot(model, ModelGraph.build(model), Instant.EPOCH, emptyList())
        val packages = LayoutResolver.packagesFrom(listOf(source.load()))

        "indexOf gives the cards and edges in the catalog's shape (pkg + dotted qname, leaf name, kind)" {
            val index = LayoutResolver.indexOf(snapshot, packages)
            index.cards.map { listOf(it.fullQname, it.qname, it.kind, it.name, it.pkg, it.schema) } shouldBe
                listOf(
                    listOf(
                        "hartland.db.dbo.channel_sales",
                        "db.dbo.channel_sales",
                        "view",
                        "channel_sales",
                        "hartland",
                        "db",
                    ),
                    listOf("hartland.db.dbo.item", "db.dbo.item", "table", "item", "hartland", "db"),
                    listOf(
                        "hartland.db.dbo.store_sales",
                        "db.dbo.store_sales",
                        "table",
                        "store_sales",
                        "hartland",
                        "db",
                    ),
                    listOf("hartland.er.entity.item", "er.entity.item", "entity", "item", "hartland", "er"),
                    listOf("hartland.er.entity.store", "er.entity.store", "entity", "store", "hartland", "er"),
                    listOf(
                        "hartland.er.entity.store_sales",
                        "er.entity.store_sales",
                        "entity",
                        "store_sales",
                        "hartland",
                        "er",
                    ),
                )
            index.edges.map { listOf(it.fullQname, it.kind) } shouldBe
                listOf(
                    listOf("hartland.db.dbo.fk_ss_item", "foreign_key"),
                    listOf("hartland.er.entity.rel_store_sales_item", "relation"),
                )
        }

        "resolve against a snapshot: each form, per the layout's model; relations and fks ignored" {
            val er =
                LayoutResolver.resolve(
                    listOf(
                        "hartland.er.entity.item",
                        "er.entity.store",
                        "entity.store_sales",
                        "rel_store_sales_item",
                        "nope",
                    ),
                    snapshot,
                    "er",
                    packages,
                )
            er.resolved shouldBe
                mapOf(
                    "entity.store_sales" to "hartland.er.entity.store_sales",
                    "er.entity.store" to "hartland.er.entity.store",
                    "hartland.er.entity.item" to "hartland.er.entity.item",
                )
            er.ignored shouldBe listOf("rel_store_sales_item")
            er.missing shouldBe listOf("nope")

            val db =
                LayoutResolver.resolve(
                    listOf("item", "view.channel_sales", "fk_ss_item"),
                    snapshot,
                    "db",
                    packages,
                )
            db.resolved shouldBe
                mapOf("item" to "hartland.db.dbo.item", "view.channel_sales" to "hartland.db.dbo.channel_sales")
            db.ignored shouldBe listOf("fk_ss_item")
        }

        "the package comes from the loader's per-file rule — the reconciled qnames carry none" {
            model
                .objectByQname()
                .keys
                .map { it.`package` }
                .toSet() shouldBe setOf("")
            LayoutResolver
                .indexOf(snapshot) { "elsewhere" }
                .cards
                .first()
                .fullQname shouldBe
                "elsewhere.db.dbo.channel_sales"
        }
    })
