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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

/** MW-53: grammar 0.21 retains explicit policy; omission still defaults false. */
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

        "a query with no policy declaration loads with skipSecurity=false" {
            val result = load(mapOf("/db.ttr" to dbTtr))
            result.errors shouldHaveSize 0
            val loaded =
                result.model.queries.values
                    .single { it.qname.name == "find_customers" }
            loaded.skipSecurity shouldBe false
        }

        // ----- (b) older-consumer tolerance -----

        "the write path retains true and leaves the default false undeclared" {
            val renderOf = { q: Query ->
                TtrRenderer.renderFile(null, null, listOf(ModelToDefinitions.queryToQueryDef(q)))
            }
            val exempt = renderOf(query("find_customers", skipSecurity = true))

            exempt shouldContain "skipSecurity: true"
            renderOf(query("find_customers", skipSecurity = false)) shouldNotContain "skipSecurity"
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
            reloaded.skipSecurity shouldBe true
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
