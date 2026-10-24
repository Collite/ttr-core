// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.ProjectDef
import java.nio.file.Files
import java.nio.file.Path

class DefsJsonWriterSpec :
    StringSpec({
        "property order resource exactly matches generated grammar map" {
            val generated = Files.readString(Path.of("../../grammar/src/generated/property-map.ts"))
            val properties =
                Regex("^  (\\w+): \\[(.*)\\],?$", RegexOption.MULTILINE).findAll(generated).associate {
                    it.groupValues[1] to
                        Regex("name: '([^']+)'").findAll(it.groupValues[2]).map { key -> key.groupValues[1] }.toList()
                }
            DefsJsonWriter.propertyOrder shouldBe properties
        }
        "hand-authored C-7 sample pins header and property ordering" {
            val entries =
                listOf("c", "a", "b").map {
                    DefinedObject(
                        "project.$it",
                        "project",
                        "project",
                        ProjectDef(
                            it,
                            SourceLocation.UNKNOWN,
                            description = "Example",
                            tags = listOf("test"),
                            version = "1",
                        ),
                        "sample",
                        "sample.ttrm",
                        null,
                    )
                }
            val set = DefinitionSet(entries, emptyMap(), "sample", "0.18", "s1")
            golden("defs.json", "contract", DefsJsonWriter.write(set))
        }
        "canonical refs include sugar concepts; bodies stay opaque and nested identities survive" {
            val set = xmJsonSet()
            val doc =
                Json
                    .parseToJsonElement(
                        DefsJsonWriter.write(set).decodeToString(),
                    ).jsonObject
                    .getValue("definitions")
                    .jsonObject

            fun props(id: String) =
                doc
                    .getValue(id)
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
            props(
                "cn.event.Purchase",
            ).getValue("involves").jsonArray[1].jsonObject.getValue("ref").jsonPrimitive.content shouldBe
                "cn.kind.Product"
            props("cn.phase.LargePurchase").getValue("definition") shouldBe
                buildJsonObject {
                    put("block", "amount > 100")
                    put("tag", "pl")
                }
            props("er.entity.product")
                .getValue("concept")
                .jsonObject
                .getValue("kind")
                .jsonPrimitive.content shouldBe
                "cn.kind.Product"
            doc
                .getValue("lexicon.term.party_cs")
                .jsonObject
                .getValue("locale")
                .jsonPrimitive.content shouldBe "cs"
            props(
                "er.entity.buyer",
            ).getValue("attributes").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content shouldBe
                "er.entity.buyer.id"
            props("md.measure.balance").keys.toList() shouldBe
                listOf("tags", "domainRef", "class", "aggregation", "validBy")
        }
        "shuffled inputs and properties produce identical bytes" {
            val set = xmJsonSet()
            DefsJsonWriter.write(set.copy(entries = set.entries.reversed())).toList() shouldBe
                DefsJsonWriter.write(set).toList()
        }
        "free-form enums remain scalar identifiers and localized descriptions retain every language" {
            val source =
                """
                model cn
                def kind Example { tier: core, temporal: true, description: { de: "Beispiel", en: "Example" } }
                """.trimIndent()
            val parsed = TtrLoader.parseString(source)
            parsed.errors shouldBe emptyList()
            val entry =
                DefinedObject("cn.kind.Example", "cn", "kind", parsed.definitions.single(), "cn", "test.ttrm", null)
            val doc =
                Json
                    .parseToJsonElement(
                        DefsJsonWriter
                            .write(
                                DefinitionSet(listOf(entry), emptyMap(), "sample", "0.18", "s1"),
                            ).decodeToString(),
                    ).jsonObject
            val props =
                doc
                    .getValue(
                        "definitions",
                    ).jsonObject
                    .getValue(entry.qname)
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
            props.getValue("tier").jsonPrimitive.content shouldBe "core"
            props.getValue("temporal").jsonPrimitive.boolean shouldBe true
            props
                .getValue("description")
                .jsonObject.keys
                .toList() shouldBe listOf("de", "en")
        }
        "authored fuzzy false remains distinct from an absent fuzzy property" {
            val loc = SourceLocation.UNKNOWN
            val plain =
                org.tatrman.ttr.parser.model
                    .EntityDef("plain", loc)
            val authored =
                plain.copy(
                    name = "authored",
                    search =
                        org.tatrman.ttr.parser.model
                            .SearchHintsValue(fuzzyAuthored = true),
                )
            val entries =
                listOf(plain, authored).map {
                    DefinedObject("er.entity.${it.name}", "er", "entity", it, "er", "er.ttrm", null)
                }
            val set = DefinitionSet(entries, emptyMap(), "sample", "0.18", "s1")
            val doc =
                Json
                    .parseToJsonElement(
                        DefsJsonWriter.write(set).decodeToString(),
                    ).jsonObject
                    .getValue("definitions")
                    .jsonObject

            fun search(id: String) =
                doc
                    .getValue("er.entity.$id")
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("search")
                    .jsonObject
            search("plain").containsKey("fuzzy") shouldBe false
            search("authored").getValue("fuzzy").jsonPrimitive.boolean shouldBe false
        }
        "local attributes and bare binding references resolve in their expected model" {
            val set = xmJsonSet()
            val buyer = set.entries.single { it.qname == "er.entity.buyer" }
            val entity =
                (buyer.def as org.tatrman.ttr.parser.model.EntityDef).copy(
                    nameAttribute =
                        org.tatrman.ttr.parser.model
                            .Reference("name"),
                )
            val mapping = set.entries.single { it.qname == "er2db_entity.buyer_table" }
            val binding =
                (mapping.def as org.tatrman.ttr.parser.model.Er2DbEntityDef).copy(
                    entity =
                        org.tatrman.ttr.parser.model
                            .Reference("buyer"),
                    target =
                        org.tatrman.ttr.parser.model.TargetReferenceValue(
                            org.tatrman.ttr.parser.model
                                .Reference("buyers"),
                            SourceLocation.UNKNOWN,
                        ),
                )
            val updated =
                set.copy(
                    entries =
                        set.entries.map {
                            if (it.qname ==
                                buyer.qname
                            ) {
                                it.copy(def = entity)
                            } else if (it.qname == mapping.qname) {
                                it.copy(def = binding)
                            } else {
                                it
                            }
                        },
                )
            val defs =
                Json
                    .parseToJsonElement(
                        DefsJsonWriter.write(updated).decodeToString(),
                    ).jsonObject
                    .getValue("definitions")
                    .jsonObject

            fun props(id: String) =
                defs
                    .getValue(id)
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
            props(buyer.qname).getValue("nameAttribute").jsonPrimitive.content shouldBe "er.entity.buyer.name"
            props(mapping.qname).getValue("entity").jsonPrimitive.content shouldBe "er.entity.buyer"
            props(mapping.qname).getValue("target").jsonPrimitive.content shouldBe "db.retail.buyers"
        }
        "definition references for body-bearing sugar point to its retained declaring definition" {
            val set = xmJsonSet()
            val parsed =
                TtrLoader.parseString(
                    "model er def entity product { concept { kind: Product, definition: \"true\" } }",
                )
            parsed.errors shouldBe emptyList()
            val updated =
                set.copy(
                    entries =
                        set.entries.map {
                            if (it.qname ==
                                "er.entity.product"
                            ) {
                                it.copy(def = parsed.definitions.single())
                            } else {
                                it
                            }
                        },
                )
            val node = DefinitionGraph.build(updated).nodes.single { it.id == "cn.kind.Product" }
            node.hasDefinition shouldBe true
            node.definitionRef shouldBe "defs.json#er.entity.product"
            val definitions =
                Json
                    .parseToJsonElement(
                        DefsJsonWriter.write(updated).decodeToString(),
                    ).jsonObject
                    .getValue("definitions")
                    .jsonObject
            definitions.containsKey(node.definitionRef!!.substringAfter('#')) shouldBe true
        }
    })
