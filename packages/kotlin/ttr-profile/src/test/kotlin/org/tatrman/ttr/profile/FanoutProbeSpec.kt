// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.LoadResult
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Files

/**
 * S3.2b T1 (re-instrumented 2026-11-07, ✅D-MW-70): an entity with no inline binding, its table on a standalone
 * `er2db_entity` with `whereFilter: {sql}`, and attributes with an inline bare `binding`. The oracle is
 * ttr-metadata's loaded model — the conformance dump never lists attribute bindings.
 */
class FanoutProbeSpec :
    FunSpec({
        fun documents(entity: String): Map<String, String> =
            listOf("db", "er", "binding").associate { name ->
                "demo/$name.ttrm" to
                    javaClass
                        .getResource("/fanout-probe/$name.ttrm")!!
                        .readText()
                        .replace("entity: er.entity.E", "entity: $entity")
            }

        fun load(files: Map<String, String>): LoadResult {
            val root = Files.createTempDirectory("fanout-probe")
            for ((rel, content) in files) {
                val path = root.resolve(rel)
                Files.createDirectories(path.parent)
                Files.writeString(path, content)
            }
            val source = FileBasedSource(sourceId = "probe", priority = 100, storage = LocalFsStorage("probe", root))
            return MetadataLoader(source, ModelDescriptor(id = "probe", name = "probe")).load()
        }

        listOf("er.entity.E", "E").forEach { entity ->
            test("the three documents parse and round-trip canonical YAML with entity $entity") {
                for ((uri, text) in documents(entity)) {
                    TtrLoader.parseString(text, uri).errors shouldBe emptyList()
                    val bytes = CanonicalYaml.write(CanonicalYaml.fromTtr(text, uri))
                    CanonicalYaml.write(CanonicalYaml.read(bytes, uri)) shouldBe bytes
                }
            }
        }

        test("bare attribute bindings canonicalise to T's columns through the standalone er2db_entity") {
            val result = load(documents("er.entity.E"))
            withClue(result.issues.toString()) { result.errors.shouldBeEmpty() }
            val model = result.model.shouldNotBeNull()

            val entityMapping =
                model.mappings
                    .filterIsInstance<Er2DbEntityMapping>()
                    .shouldHaveSize(1)
                    .single()
            entityMapping.entity.dotted() shouldBe "er.entity.E"
            (entityMapping.target as MappingTarget.Table).qname.dotted() shouldBe "db.dbo.T"
            val whereFilter =
                result.files
                    .flatMap { it.definitions }
                    .filterIsInstance<Er2DbEntityDef>()
                    .single()
                    .whereFilter
                    .shouldNotBeNull()
            (whereFilter.entries["sql"] as PropertyValue.StringValue).raw shouldBe "STAV = 1"

            model.mappings
                .filterIsInstance<Er2DbAttributeMapping>()
                .map { it.attribute.dotted() to (it.target as AttributeMappingTarget.Column).qname.dotted() }
                .shouldContainExactlyInAnyOrder(
                    "er.entity.E.id" to "db.dbo.T.ID",
                    "er.entity.E.nazev" to "db.dbo.T.NAZEV",
                )
        }
    })
