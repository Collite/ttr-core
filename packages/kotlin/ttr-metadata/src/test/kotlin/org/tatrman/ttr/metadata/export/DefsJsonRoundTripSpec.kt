// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.writer.TtrRenderer
import org.tatrman.ttr.yaml.CanonicalYaml
import kotlinx.serialization.json.*
import org.tatrman.ttr.metadata.source.underRoot

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
        "YS defs.json through actual canonical YAML reparses to identical defs bytes" {
            val loaded = loadXmWorld()
            val original = DefinitionSet.from(loaded.files, loaded.model, "0.18", "fixture-1")
            val header = ExportHeader.from(original, connections = fixtureConnections())
            val before = DefsJsonWriter.write(original, header)
            val records =
                Json
                    .parseToJsonElement(before.decodeToString())
                    .jsonObject
                    .getValue("definitions")
                    .jsonObject
            val reparsed =
                loaded.files.map { file ->
                    // C-7 intentionally omits document envelopes. The caller supplies the original file/import context.
                    val entries =
                        original.entries.filter {
                            it.file == file.storageFile.underRoot() &&
                                it.parentQname == null
                        }
                    val input =
                        buildJsonObject {
                            put("ttr", ExportGrammar.version)
                            file.declaredPackage?.takeIf { it.isNotEmpty() }?.let { put("package", it) }
                            if (file.imports.isNotEmpty()) {
                                put(
                                    "imports",
                                    JsonArray(
                                        file.imports.map {
                                            JsonPrimitive(
                                                it.target + if (it.wildcard) ".*" else "",
                                            )
                                        },
                                    ),
                                )
                            }
                            file.schemaCode.takeIf { it.isNotEmpty() }?.let { put("model", it) }
                            file.namespace.takeIf { it.isNotEmpty() }?.let { put("schema", it) }
                            file.locale?.let { put("locale", it) }
                            put(
                                "defs",
                                JsonArray(
                                    entries.map { entry ->
                                        canonicalExportRecord(
                                            entry.qname,
                                            records.getValue(entry.qname).jsonObject,
                                            records,
                                        )
                                    },
                                ),
                            )
                        }
                    val document = CanonicalYaml.read(input.toString(), file.storageFile.path)
                    document.ast.errors shouldBe emptyList()
                    val yaml = CanonicalYaml.write(document)
                    val parsed = CanonicalYaml.read(yaml, file.storageFile.path)
                    parsed.ast.errors shouldBe emptyList()
                    CanonicalYaml.write(parsed) shouldBe yaml
                    file.copy(definitions = parsed.ast.definitions)
                }
            val after = DefinitionSet.from(reparsed, loaded.model, original.grammar, original.snapshot)
            DefsJsonWriter.write(after, header).toList() shouldBe before.toList()
            golden("defs.json", "world", before)
        }
    })
