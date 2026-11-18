// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.WhereFilter
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files

/**
 * MW S4.2a T3b (⚑MW-P4-S42-2, S4.2 Found (d)): an explicit `er2db_entity`'s `whereFilter` reaches the typed
 * [Er2DbEntityMapping] — the `{sql: …}` form as its text, any other form as the authored map — with the authored
 * position, so a server can synthesise `<entity>__filter` (✅D-MW-67 (c′)) and locate a warning on the other form.
 * Additive: a mapping without one, and every inline mapping, has none.
 */
class WhereFilterMappingSpec :
    StringSpec({
        "whereFilter reaches the typed er2db_entity mapping: the sql form, the map form, none" {
            val root = Files.createTempDirectory("where-filter")
            Files.writeString(
                root.resolve("db.ttrm"),
                "model db schema dbo\n\ndef table T { columns: [ def column STAV { type: int } ] }\n",
            )
            Files.writeString(
                root.resolve("er.ttrm"),
                "model er\n\ndef entity A { attributes: [] }\ndef entity B { attributes: [] }\n" +
                    "def entity C { attributes: [] }\n" +
                    "def entity D { binding: { target: { table: db.dbo.T } }, attributes: [] }\n",
            )
            Files.writeString(
                root.resolve("binding.ttrm"),
                """
                model binding

                def er2db_entity A_map {
                    entity: er.entity.A
                    target: { table: db.dbo.T }
                    whereFilter: { sql: "STAV = 1" }
                }

                def er2db_entity B_map {
                    entity: er.entity.B
                    target: { table: db.dbo.T }
                    whereFilter: { STAV: 1, KIND: "x" }
                }

                def er2db_entity C_map {
                    entity: er.entity.C
                    target: { table: db.dbo.T }
                }
                """.trimIndent() + "\n",
            )
            val source = FileBasedSource(sourceId = "s", priority = 100, storage = LocalFsStorage("s", root))
            val result = MetadataLoader(source, ModelDescriptor(id = "s", name = "s")).load()
            withClue(result.issues.toString()) { result.errors.shouldBeEmpty() }
            val byEntity =
                result.model
                    .shouldNotBeNull()
                    .mappings
                    .filterIsInstance<Er2DbEntityMapping>()
                    .associateBy { it.entity.name }

            val sql = byEntity.getValue("A").whereFilter as WhereFilter.Sql
            sql.sql shouldBe "STAV = 1"
            sql.line shouldBe 6
            val fields = byEntity.getValue("B").whereFilter as WhereFilter.Fields
            fields.fields shouldBe mapOf("STAV" to "1", "KIND" to "x")
            fields.line shouldBe 12
            byEntity.getValue("C").whereFilter shouldBe null
            byEntity.getValue("D").whereFilter shouldBe null
        }
    })
