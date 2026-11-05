// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainAll
import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.conformance.SemanticsConformanceDump
import org.tatrman.ttr.yaml.CanonicalYaml

class FanoutProbeSpec :
    FunSpec({
        listOf("er.entity.E", "E").forEach { entity ->
            test("inline attribute bindings resolve through er2db_entity with entity $entity") {
                val docs =
                    listOf("db", "er", "binding").map { name ->
                        val uri = "fanout-probe/$name.ttrm"
                        val source =
                            javaClass
                                .getResource("/$uri")!!
                                .readText()
                                .replace("entity: er.entity.E", "entity: $entity")
                        val parsed = TtrLoader.parseString(source, uri)
                        parsed.errors shouldBe emptyList()
                        val canonical = CanonicalYaml.fromTtr(source, uri)
                        val bytes = CanonicalYaml.write(canonical)
                        CanonicalYaml.write(CanonicalYaml.read(bytes, uri)) shouldBe bytes
                        SemanticsConformanceDump.DocInput(uri, parsed)
                    }
                val dump = Json.parseToJsonElement(SemanticsConformanceDump.dumpDocs(docs)).jsonObject
                withClue(dump.toString()) {
                    dump["diagnostics"] shouldBe JsonArray(emptyList())
                    dump.getValue("resolved").jsonArray.map { it.jsonPrimitive.content }.shouldContainAll(
                        "ID => demo.db.dbo.table.T.ID",
                        "NAZEV => demo.db.dbo.table.T.NAZEV",
                    )
                }
            }
        }
    })
