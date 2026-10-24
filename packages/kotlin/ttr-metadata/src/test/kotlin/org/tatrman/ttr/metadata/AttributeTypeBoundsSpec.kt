// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.export.ModelToDefinitions
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.parser.model.DataType
import org.tatrman.ttr.writer.TtrRenderer
import java.nio.file.Files

class AttributeTypeBoundsSpec :
    StringSpec({
        "metadata export preserves authored attribute bounds without inventing bare-type defaults" {
            val root = Files.createTempDirectory("attribute-type-bounds")
            try {
                Files.writeString(
                    root.resolve("er.ttrm"),
                    """
                    model er
                    def entity proposal {
                        attributes: [
                            def attribute id { type: int, isKey: true },
                            def attribute amount { type: { type: decimal, length: 12, precision: 2 } },
                            def attribute fraction { type: { type: decimal, length: 2, precision: 2 } },
                            def attribute integral { type: { type: decimal, length: 8, precision: 0 } },
                            def attribute code { type: { type: text, length: 40 }, optional: true },
                            def attribute bare { type: decimal },
                            def attribute partial { type: { type: decimal, length: 9 } },
                            def attribute scale_only { type: { type: decimal, precision: 3 } }
                        ]
                    }
                    """.trimIndent(),
                )
                val result = MetadataLoader(FileBasedSource("bounds", 100, LocalFsStorage("bounds", root))).load()
                result.errors shouldBe emptyList()
                val entity =
                    result.model!!
                        .objectByQname()
                        .values
                        .filterIsInstance<Entity>()
                        .single()
                val bounds =
                    entity.attributes.associate {
                        it.qname.name.substringAfterLast('.') to
                            (it.length to it.precision)
                    }
                bounds shouldBe
                    mapOf(
                        "id" to (null to null),
                        "amount" to (12 to 2),
                        "fraction" to (2 to 2),
                        "integral" to (8 to 0),
                        "code" to (40 to null),
                        "bare" to (null to null),
                        "partial" to (9 to null),
                        "scale_only" to (null to 3),
                    )
                entity.attributes.map { it.type } shouldBe
                    listOf("int", "decimal", "decimal", "decimal", "text", "decimal", "decimal", "decimal")
                val attributes = ModelToDefinitions.entityToEntityDef(entity).attributes.associateBy { it.name }
                attributes.getValue("id").type shouldBe DataType("int")
                attributes.getValue("id").isKey shouldBe true
                attributes.getValue("amount").type shouldBe DataType("decimal", 12, 2)
                attributes.getValue("fraction").type shouldBe DataType("decimal", 2, 2)
                attributes.getValue("integral").type shouldBe DataType("decimal", 8, 0)
                attributes.getValue("code").type shouldBe DataType("text", 40)
                attributes.getValue("code").optional shouldBe true
                attributes.getValue("bare").type shouldBe DataType("decimal")
                attributes.getValue("partial").type shouldBe DataType("decimal", 9)
                attributes.getValue("scale_only").type shouldBe DataType("decimal", precision = 3)
                // Exercise the full producer chain, not just fields on a manually built model.
                val rendered = TtrRenderer.renderDef(ModelToDefinitions.entityToEntityDef(entity))
                Files.writeString(root.resolve("er.ttrm"), "model er\n$rendered")
                val reload = MetadataLoader(FileBasedSource("bounds", 100, LocalFsStorage("bounds", root))).load()
                reload.errors shouldBe emptyList()
                val roundTrip =
                    reload.model!!
                        .objectByQname()
                        .values
                        .filterIsInstance<Entity>()
                        .single()
                roundTrip.attributes.map { Triple(it.type, it.length, it.precision) } shouldBe
                    entity.attributes.map { Triple(it.type, it.length, it.precision) }
                roundTrip.attributes.map { it.isKey to it.nullable } shouldBe
                    entity.attributes.map { it.isKey to it.nullable }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })
