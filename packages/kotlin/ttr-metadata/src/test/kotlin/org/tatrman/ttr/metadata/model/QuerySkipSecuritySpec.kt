// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

import org.tatrman.ttr.metadata.export.ModelToDefinitions
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.StorageFile
import org.tatrman.ttr.writer.TtrRenderer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

/**
 * ai-platform v2.1 · S0·T2 — `Query.skipSecurity`.
 *
 * The field is a **carrier for a host dialect**, not a TTR language feature: ai-platform's YAML
 * pattern files declare `skipSecurity: true` for queries that bypass parse/validation and go
 * down the raw execution lane (PP-17/PP-19). `ttr-metadata` owns the typed model those files
 * are loaded into, so the field lives here — but TTR-M's grammar is deliberately **not**
 * touched (S0·T3: "grammar version stays untouched"), which makes three properties contractual
 * and each is asserted below:
 *
 *  a. **Default false.** Absent from the source ⇒ `false`, both for direct construction and
 *     for a model loaded through the real TTR loader. Security-relevant defaults fail closed:
 *     nothing becomes exempt from validation by omission.
 *  b. **Older-consumer tolerance.** The field is appended with a default, and nothing in the
 *     write path emits it — so TTR text produced by this version is byte-identical to what the
 *     previous version produced, and any consumer ignoring the field is unaffected.
 *  c. **`copy` preserves it.** The data-class copy paths ai-platform's loader uses to enrich a
 *     Query must not silently reset the flag to its default.
 */
class QuerySkipSecuritySpec :
    StringSpec({

        // Minimal in-memory ModelStorage so the loader can be driven without touching disk
        // (same shape as InlineMappingSynthesisSpec's).
        class InMemoryStorage(
            override val id: String,
            private val files: Map<String, String>,
        ) : ModelStorage {
            override fun fetchVersion(): String = "test"

            override fun listFiles(
                extensions: List<String>,
                prefixes: List<String>,
            ): List<StorageFile> =
                files.keys
                    .filter { p -> extensions.any { p.endsWith(".$it") } }
                    .map { StorageFile(path = it, sizeBytes = 0L, rootPath = Path.of("/")) }

            override fun read(file: StorageFile): String = files[file.path] ?: ""
        }

        fun load(files: Map<String, String>) =
            ModelReconciler(ModelDescriptor(id = "skipsec-test", name = "skipsec-test"))
                .reconcile(
                    listOf(
                        FileBasedSource(
                            sourceId = "skipsec-test",
                            priority = 100,
                            storage = InMemoryStorage(id = "skipsec-test", files = files),
                        ).load(),
                    ),
                )

        fun qn(name: String): QualifiedName = QualifiedName(SchemaCode.DB, namespace = "dbo", name = name)

        fun query(
            name: String,
            skipSecurity: Boolean,
        ): Query =
            Query(
                internalId = "q-$name",
                qname = qn(name),
                description = "Find customers by name",
                sourceLanguage = "SQL",
                sourceText = "SELECT * FROM customers WHERE name LIKE @name",
                parameters = listOf(QueryParameterDef(name = "name", type = "text", label = "Name pattern")),
                skipSecurity = skipSecurity,
            )

        // Hand-authored, in the shape the committed seed uses: a **directive-less** file
        // (`def query` is schema-pinned to db.dbo there), `language:` + a triple-quoted
        // sourceText. Not generated from the writer, so it can actually disagree with it.
        val dbTtr =
            "def query find_customers { description: \"Find customers by name\", language: SQL, " +
                "sourceText: \"\"\"SELECT * FROM customers WHERE name LIKE @name\"\"\" }"

        // ----- (a) default false -----

        "skipSecurity defaults to false when the constructor omits it" {
            val q =
                Query(
                    internalId = "q1",
                    qname = qn("find_customers"),
                    sourceLanguage = "SQL",
                    sourceText = "SELECT 1",
                )
            q.skipSecurity shouldBe false
        }

        "a query loaded from TTR has skipSecurity=false — the grammar carries no such attribute" {
            val result = load(mapOf("/db.ttr" to dbTtr))
            result.errors shouldHaveSize 0
            val loaded =
                result.model.queries.values
                    .single { it.qname.name == "find_customers" }
            loaded.skipSecurity shouldBe false
        }

        // ----- (b) older-consumer tolerance -----

        "the write path never emits skipSecurity — rendered TTR is identical either way" {
            val renderOf = { q: Query ->
                TtrRenderer.renderFile(null, null, listOf(ModelToDefinitions.queryToQueryDef(q)))
            }
            val exempt = renderOf(query("find_customers", skipSecurity = true))

            exempt shouldBe renderOf(query("find_customers", skipSecurity = false))
            exempt shouldNotContain "skipSecurity"
        }

        "a model rendered by this version reloads through the loader unchanged (older-consumer tolerance)" {
            val rendered =
                TtrRenderer.renderFile(
                    null,
                    null,
                    listOf(ModelToDefinitions.queryToQueryDef(query("find_customers", skipSecurity = true))),
                )

            val result = load(mapOf("/db.ttr" to rendered))
            result.errors shouldHaveSize 0
            val reloaded =
                result.model.queries.values
                    .single { it.qname.name == "find_customers" }
            // The flag is host-dialect state, not TTR text: a TTR round-trip returns the
            // default. ai-platform re-derives it from its YAML on every load, so nothing is
            // lost there — but a future consumer that expects TTR to carry it would be wrong,
            // and this assertion is where it finds out.
            reloaded.skipSecurity shouldBe false
            reloaded.sourceText shouldBe "SELECT * FROM customers WHERE name LIKE @name"
            reloaded.parameters.single().name shouldBe "name"
        }

        // ----- (c) copy preserves it -----

        "copy preserves skipSecurity when an unrelated field changes" {
            val exempt = query("find_customers", skipSecurity = true)

            exempt.copy(description = "renamed").skipSecurity shouldBe true
            exempt.copy(parseStatus = ParseStatus.ParseFailure("unparseable")).skipSecurity shouldBe true
            exempt.copy(skipSecurity = false).skipSecurity shouldBe false
        }
    })
