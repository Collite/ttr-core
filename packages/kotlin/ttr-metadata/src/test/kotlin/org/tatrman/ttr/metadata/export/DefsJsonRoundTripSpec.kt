// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.writer.TtrRenderer

class DefsJsonRoundTripSpec :
    StringSpec({
        "all fixture files render and reparse to identical defs bytes" {
            val loaded = loadXmWorld()
            val reparsed =
                loaded.files.map { file ->
                    val schema = file.schemaCode.takeIf { it.isNotEmpty() }
                    val text =
                        TtrRenderer.renderFile(
                            schema,
                            file.namespace.takeIf {
                                it.isNotEmpty()
                            },
                            file.definitions,
                            locale = file.locale,
                        )
                    val parsed = TtrLoader.parseString(text, file.storageFile.path)
                    parsed.errors shouldBe emptyList()
                    file.copy(definitions = parsed.definitions)
                }
            val original = DefinitionSet.from(loaded.files, loaded.model, "0.18", "fixture-1")
            val roundTrip = DefinitionSet.from(reparsed, loaded.model, "0.18", "fixture-1")
            DefsJsonWriter
                .write(
                    roundTrip,
                    ExportHeader.from(roundTrip, connections = fixtureConnections()),
                ).toList() shouldBe
                DefsJsonWriter.write(original, ExportHeader.from(original, connections = fixtureConnections())).toList()
            golden(
                "defs.json",
                "world",
                DefsJsonWriter.write(original, ExportHeader.from(original, connections = fixtureConnections())),
            )
        }
        "YS canonical YAML fixed point when YS lands".config(enabled = false) { }
    })
