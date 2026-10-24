// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import kotlinx.serialization.json.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class ExporterSpec :
    StringSpec({
        "every model-format cell dispatches or reports its literal lossiness" {
            val loaded = loadXmWorld()
            val set = xmJsonSet()
            val exporter = Exporter()
            for (format in listOf("graph.json", "defs.json", "ttr", "yaml", "ttl", "xmi", "dot")) {
                for (model in listOf("world", "cn", "md", "er", "db", "bindings", "lexicon")) {
                    val result =
                        exporter.export(
                            set,
                            org.tatrman.ttr.metadata.graph.ModelGraph
                                .build(requireNotNull(loaded.model)),
                            ExportRequest(setOf(model), format),
                        )
                    val loss = LossinessLedger.cannotCarry(model, format)
                    when {
                        loss != null -> result shouldBe ExportResult.CannotCarry(loss)
                        format in setOf("xmi", "yaml") -> result shouldBe ExportResult.NotBuilt
                        format == "ttr" -> (result is ExportResult.Bundle) shouldBe true
                        else -> (result is ExportResult.Single) shouldBe true
                    }
                }
            }
        }
        "validation, previous snapshot, reserved formats and descriptors" {
            val set = xmJsonSet()
            val exporter = Exporter()
            exporter.export(set, null, ExportRequest(setOf("world"), "graph.json", since = "missing")) shouldBe
                ExportResult.SinceUnknown
            for (format in listOf("ossie", "uc", "tmdl", "pdm", "ldm", "cdm")) {
                exporter.export(set, null, ExportRequest(setOf("world"), format)) shouldBe
                    ExportResult.NotBuilt
            }
            shouldThrow<IllegalArgumentException> {
                exporter.export(
                    set,
                    null,
                    ExportRequest(setOf("world"), "unknown"),
                )
            }
            shouldThrow<IllegalArgumentException> {
                exporter.export(
                    set,
                    null,
                    ExportRequest(setOf("unknown"), "graph.json"),
                )
            }
            shouldThrow<IllegalArgumentException> {
                exporter.export(set, null, ExportRequest(setOf("cn"), "graph.json", profile = "owl"))
            }
            val connection =
                ConnectionDescriptor(
                    "northline-pg",
                    "northline",
                    "retail",
                    mapOf(
                        "openmetadata" to mapOf("service" to "northline-pg"),
                    ),
                )
            val result =
                exporter.export(
                    set,
                    null,
                    ExportRequest(setOf("world"), "defs.json", connections = listOf(connection)),
                ) as ExportResult.Single
            result.bytes.decodeToString().contains(
                "\"connections\":[{\"id\":\"northline-pg\",\"database\":\"northline\",\"schema\":\"retail\",\"locators\":{\"openmetadata\":{\"service\":\"northline-pg\"}}}]",
            ) shouldBe
                true
        }
        "ttr bundles preserve original package imports and lexicon locales" {
            val set = xmJsonSet()
            val bundle = Exporter().export(set, null, ExportRequest(format = "ttr")) as ExportResult.Bundle
            val er =
                bundle.files
                    .single { it.name == "er/logical.ttrm" }
                    .bytes
                    .decodeToString()
            er.contains("import cn.*") shouldBe true
            val cs =
                bundle.files
                    .single { it.name == "lexicon/cs.ttrm" }
                    .bytes
                    .decodeToString()
            cs.contains("model lexicon locale cs") shouldBe true
            bundle.files.forEach { file ->
                org.tatrman.ttr.parser.loader.TtrLoader
                    .parseString(file.bytes.decodeToString())
                    .errors shouldBe
                    emptyList()
            }
        }
        "binding families are model selectors and graph edges retain their endpoints" {
            val result =
                Exporter().export(
                    xmJsonSet(),
                    null,
                    ExportRequest(setOf("cn2md"), "graph.json"),
                ) as ExportResult.Single
            val json =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(result.bytes.decodeToString())
                    .jsonObject
            val edges = json.getValue("edges").jsonArray
            edges.size shouldBe 1
            edges
                .single()
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content shouldBe "cn2md_role.customer_dimension"
        }
        "area scope applies recursively to definitions omitted from tier 1 and keeps canonical refs" {
            val set = xmJsonSet()
            val exporter = Exporter()

            fun defs(request: ExportRequest) =
                (exporter.export(set, null, request) as ExportResult.Single).bytes.decodeToString().let {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject
                        .getValue("definitions")
                        .jsonObject
                }
            val scoped = defs(ExportRequest(setOf("world"), "defs.json", areas = setOf("retail")))
            scoped.containsKey("area.retail") shouldBe true
            scoped.containsKey("md.measure.balance") shouldBe true
            scoped.containsKey("cn2er_kind.party_entity") shouldBe true
            scoped.containsKey("db.retail.buyers") shouldBe false
            scoped
                .getValue(
                    "cn2er_kind.party_entity",
                ).jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("target")
                .jsonPrimitive.content shouldBe
                "er.entity.buyer"
            defs(ExportRequest(setOf("lexicon"), "defs.json", packages = setOf("lexicon"))).size shouldBe 5
            defs(ExportRequest(setOf("world"), "defs.json", packages = emptySet())).size shouldBe 0
            val child = set.entries.first { it.qname == "er.entity.buyer" }.copy(pkg = "er.child")
            val nested = set.copy(entries = listOf(child))
            val result =
                exporter.export(
                    nested,
                    null,
                    ExportRequest(setOf("er"), "defs.json", packages = setOf("er")),
                ) as ExportResult.Single
            Json
                .parseToJsonElement(
                    result.bytes.decodeToString(),
                ).jsonObject
                .getValue("definitions")
                .jsonObject
                .containsKey(child.qname) shouldBe
                true
        }
        "XM DOT includes MD and conceptual definitions even when a typed graph is supplied" {
            val loaded = loadXmWorld()
            val graph =
                org.tatrman.ttr.metadata.graph.ModelGraph
                    .build(requireNotNull(loaded.model))
            val result = Exporter().export(xmJsonSet(), graph, ExportRequest(format = "dot")) as ExportResult.Single
            result.bytes.decodeToString().contains("cn.kind.Party") shouldBe true
            result.bytes.decodeToString().contains("md.measure.revenue") shouldBe true
        }
    })
