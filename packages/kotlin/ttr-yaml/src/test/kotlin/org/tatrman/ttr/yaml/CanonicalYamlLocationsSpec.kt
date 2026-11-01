// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.parser.model.ConceptProperty

class CanonicalYamlLocationsSpec :
    FunSpec({
        test("definition and nested reference spans point to YAML nodes") {
            val source =
                """
                ttr: "0.21"
                model: er
                defs:
                  - kind: entity
                    id: Customer
                    properties:
                      nameAttribute: code
                      attributes:
                        - id: code
                          properties: {type: text}
                """.trimIndent()
            val entity =
                CanonicalYaml
                    .read(source, "model.yaml")
                    .ast.definitions
                    .single() as EntityDef
            entity.source.file shouldBe "model.yaml"
            entity.source.line shouldBe 4
            entity.nameAttribute!!.source.line shouldBe 7
            entity.nameAttribute!!.source.column shouldBe 21
            entity.attributes
                .single()
                .source.line shouldBe 9
        }
        test("schema failures locate the offending YAML value") {
            val error =
                shouldThrow<CanonicalYamlException> {
                    CanonicalYaml.read("ttr: \"0.21\"\nmodel: typo\n", "bad.yaml")
                }
            error.file shouldBe "bad.yaml"
            error.line shouldBe 2
            error.column shouldBe 8
        }
        test("repeated CN syntax survives with the original duplicate diagnostic") {
            val doc =
                CanonicalYaml.read(
                    """
                    ttr: "0.21"
                    model: cn
                    defs:
                      - kind: kind
                        id: Order
                        properties:
                          - of: {ref: Other}
                          - of: {ref: Again}
                    """.trimIndent(),
                    "repeated.yaml",
                )
            doc.ast.errors
                .single()
                .line shouldBe 8
            val bytes = CanonicalYaml.write(doc)
            CanonicalYaml.write(CanonicalYaml.read(bytes)) shouldBe bytes
        }
        test("literal expression locations begin at YAML content rather than the block indicator") {
            val doc =
                CanonicalYaml.read(
                    """
                    ttr: "0.21"
                    model: cn
                    defs:
                      - kind: kind
                        id: Order
                        properties:
                          definition: !pl |
                            a + b
                    """.trimIndent(),
                    "block.yaml",
                )
            val def = doc.ast.definitions.single() as ConceptDef
            val block =
                def.body.properties
                    .filterIsInstance<ConceptProperty.DefinitionBlock>()
                    .single()
                    .block
            block.location(0, 0).line shouldBe 8
            block.location(0, 0).column shouldBe 8
        }
    })
