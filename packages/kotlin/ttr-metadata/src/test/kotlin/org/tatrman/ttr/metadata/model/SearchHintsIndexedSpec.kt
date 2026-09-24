// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.export.ModelToDefinitions
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.writer.TtrRenderer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * MV-T0 T1/T2 (member-vocabulary contracts §2.1) — `indexed` and `matchMethod` are two facts.
 *
 * `indexed` answers "does this carrier have a member vocabulary?"; `matchMethod` answers "how are
 * its values matched?". Before MV one bit, `fuzzy`, answered the first question under the name of
 * the second, and `method: EXACT` therefore indexed nothing.
 */
class SearchHintsIndexedSpec :
    StringSpec({

        fun load(root: Path): Model =
            MetadataLoader(
                FileBasedSource(sourceId = "t", priority = 100, storage = LocalFsStorage(id = "t", rootPath = root)),
            ).load().model ?: error("model failed to load from $root")

        fun Model.columns(): Map<String, SearchHints> =
            schemas.values
                .filterIsInstance<DbSchema>()
                .flatMap { it.tables.values }
                .flatMap { it.columns }
                .associate { it.qname.name.substringAfterLast('.') to it.search }

        val fixture = Path.of("src/test/resources/fixture-indexed")

        "an authored method of any kind indexes — EXACT included" {
            val cols = load(fixture).columns()
            cols.getValue("exact_code").indexed shouldBe true
            cols.getValue("exact_code").matchMethod shouldBe "EXACT"
            cols.getValue("typo_name").indexed shouldBe true
            cols.getValue("typo_name").matchMethod shouldBe "TYPOS(1)"
            cols.getValue("token_title").indexed shouldBe true
            cols.getValue("token_title").matchMethod shouldBe "TOKENS"
        }

        "a bare `searchable` is a hint, not a vocabulary" {
            val bare = load(fixture).columns().getValue("bare_label")
            bare.searchable shouldBe true
            bare.indexed shouldBe false
            bare.matchMethod shouldBe null
        }

        "deprecated `fuzzy: true` indexes with the method grammar 0.12 maps it to, TYPOS(1)" {
            val legacy = load(fixture).columns().getValue("legacy_fuzzy")
            legacy.indexed shouldBe true
            legacy.matchMethod shouldBe "TYPOS(1)"
            legacy.fuzzyAuthored shouldBe true
        }

        "deprecated `fuzzy: false` indexes nothing (no method was authored)" {
            val legacy = load(fixture).columns().getValue("legacy_not_fuzzy")
            legacy.indexed shouldBe false
            legacy.matchMethod shouldBe null
            legacy.fuzzyAuthored shouldBe true
        }

        "an authored method wins over the deprecated boolean" {
            val both = load(fixture).columns().getValue("method_wins")
            both.indexed shouldBe true
            both.matchMethod shouldBe "EXACT"
        }

        "an unknown method is still authored: indexed, verbatim, not partial" {
            val unknown = load(fixture).columns().getValue("unknown_method")
            unknown.indexed shouldBe true
            unknown.matchMethod shouldBe "SOUNDEX"
            MatchMethods.isPartial(unknown.matchMethod) shouldBe false
        }

        "no search block ⇒ EMPTY" {
            load(fixture).columns().getValue("plain") shouldBe SearchHints.EMPTY
        }

        "MatchMethods.isPartial: TYPOS and TOKENS are partial, EXACT / unknown / null are not" {
            MatchMethods.isPartial("TYPOS(1)") shouldBe true
            MatchMethods.isPartial("typos(2)") shouldBe true
            MatchMethods.isPartial("TOKENS") shouldBe true
            MatchMethods.isPartial("EXACT") shouldBe false
            MatchMethods.isPartial("SOUNDEX") shouldBe false
            MatchMethods.isPartial(null) shouldBe false
        }

        "the deprecated `fuzzy` alias reads as 'the method is partial'" {
            @Suppress("DEPRECATION")
            val cols = load(fixture).columns().mapValues { (_, s) -> s.fuzzy }
            cols.getValue("typo_name") shouldBe true
            cols.getValue("token_title") shouldBe true
            cols.getValue("legacy_fuzzy") shouldBe true
            cols.getValue("exact_code") shouldBe false
            cols.getValue("method_wins") shouldBe false
            cols.getValue("bare_label") shouldBe false
            cols.getValue("legacy_not_fuzzy") shouldBe false
        }

        "isEmpty sees `indexed`" {
            SearchHints(indexed = true).isEmpty shouldBe false
        }

        // T2 — `ModelToDefinitions` writes what the model means, and reads back the same facts.
        "round-trip: every shape reloads with the same indexed + matchMethod; `fuzzy: true` is never written back" {
            val model = load(fixture)
            val root = Files.createTempDirectory("mv-indexed-rt")
            val rendered = StringBuilder()
            for (file in ModelToDefinitions.convert(model).files) {
                val content =
                    TtrRenderer.renderFile(
                        file.schemaCode,
                        file.namespace,
                        file.definitions,
                        file.packageName,
                        file.imports,
                    )
                rendered.append(content)
                val dir = if (file.packageName != null) root.resolve(file.packageName) else root
                Files.createDirectories(dir)
                Files.writeString(dir.resolve(file.filename), content)
            }
            val before = model.columns()
            val after = load(root).columns()
            for ((name, hints) in before) {
                val back = after.getValue(name)
                Pair(name, back.indexed) shouldBe Pair(name, hints.indexed)
                Pair(name, back.matchMethod) shouldBe Pair(name, hints.matchMethod)
                Pair(name, back.searchable) shouldBe Pair(name, hints.searchable)
            }
            rendered.toString() shouldNotContain "fuzzy: true"
            // `fuzzy: false` is the one deprecated spelling with no lossless replacement: its
            // 0.12 replacement `method: EXACT` would now index the column. So it survives.
            rendered.toString() shouldContain "fuzzy: false"
        }

        "an indexed carrier with no method is written as `method: EXACT`" {
            val qn = { name: String ->
                QualifiedName(schemaCode = SchemaCode.DB, namespace = "dbo", name = name)
            }
            val tableQn = qn("t")
            val col =
                DbColumn(
                    internalId = "c1",
                    qname = qn("t.v"),
                    table = tableQn,
                    dataType = "text",
                    search = SearchHints(searchable = true, indexed = true),
                )
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "t", name = "t", description = ""),
                    version = ModelVersion(value = "v", swappedAt = Instant.EPOCH),
                    schemas =
                        mapOf(
                            "db" to
                                DbSchema(
                                    tables =
                                        mapOf(
                                            tableQn to
                                                DbTable(
                                                    internalId = "t1",
                                                    qname = tableQn,
                                                    primaryKey = emptyList(),
                                                    columns = listOf(col),
                                                ),
                                        ),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val text =
                ModelToDefinitions.convert(model).files.joinToString("\n") { f ->
                    TtrRenderer.renderFile(f.schemaCode, f.namespace, f.definitions, f.packageName, f.imports)
                }
            text shouldContain "method: EXACT"
        }
    })
