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
 * ai-platform PB · S3a — `QueryParameterDef.binds`.
 *
 * The field is a **carrier for a host dialect**, exactly like [Query.skipSecurity]: ai-platform's
 * YAML pattern files declare `binds: [entity.attribute, …]` on a parameter — the model attributes
 * whose resolved value fills that slot — and `ttr-metadata` owns the typed model those files are
 * loaded into. TTR-M's grammar is deliberately **not** touched, which makes the same three
 * properties contractual:
 *
 *  a. **Default empty.** Absent ⇒ `emptyList()`, for direct construction and for a query loaded
 *     through the real TTR loader (the grammar has no such attribute to read).
 *  b. **Older-consumer tolerance.** Appended last with a default; nothing in the write path emits
 *     it, so rendered TTR is byte-identical whatever the field holds, and positional construction
 *     still compiles.
 *  c. **`copy` preserves it.** The copy paths a loader uses to enrich a parameter must not reset it.
 */
class QueryParameterBindsSpec :
    StringSpec({

        // Minimal in-memory ModelStorage so the loader can be driven without touching disk
        // (same shape as QuerySkipSecuritySpec's — copied, not shared, so the specs stay independent).
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
            ModelReconciler(ModelDescriptor(id = "binds-test", name = "binds-test"))
                .reconcile(
                    listOf(
                        FileBasedSource(
                            sourceId = "binds-test",
                            priority = 100,
                            storage = InMemoryStorage(id = "binds-test", files = files),
                        ).load(),
                    ),
                )

        fun qn(name: String): QualifiedName = QualifiedName(SchemaCode.DB, namespace = "dbo", name = name)

        val customerName = "zákazník.název_zákazníka"

        fun query(binds: List<String>): Query =
            Query(
                internalId = "q-nesplacene_faktury",
                qname = qn("nesplacene_faktury"),
                description = "Unpaid invoices of a customer",
                sourceLanguage = "SQL",
                sourceText = "SELECT * FROM faktury WHERE zakaznik LIKE @nazev_zakaznika",
                parameters =
                    listOf(
                        QueryParameterDef(
                            name = "nazev_zakaznika",
                            type = "text",
                            label = "Customer name",
                            binds = binds,
                        ),
                    ),
            )

        fun render(q: Query): String = TtrRenderer.renderFile(null, null, listOf(ModelToDefinitions.queryToQueryDef(q)))

        // ----- (a) default empty -----

        "binds defaults to empty on direct construction" {
            QueryParameterDef("x", "text").binds shouldBe emptyList()
        }

        "binds defaults to empty for a query loaded through the TTR loader" {
            // Rendered from a parameter that DOES carry binds, then loaded: the grammar has no such
            // attribute, so the loaded parameter reads the default. ai-platform re-derives it from
            // its YAML on every load; a consumer expecting TTR text to carry it finds out here.
            val result = load(mapOf("/db.ttr" to render(query(binds = listOf(customerName)))))
            result.errors shouldHaveSize 0
            val loaded =
                result.model.queries.values
                    .single { it.qname.name == "nesplacene_faktury" }
            loaded.parameters shouldHaveSize 1
            loaded.parameters.single().name shouldBe "nazev_zakaznika"
            loaded.parameters.single().binds shouldBe emptyList()
        }

        // ----- (b) older-consumer tolerance -----

        "the write path never emits binds" {
            val bound = render(query(binds = listOf(customerName)))

            bound shouldNotContain "binds"
            bound shouldNotContain "název_zákazníka"
            bound shouldBe render(query(binds = emptyList()))
        }

        "positional construction still compiles" {
            // A compile-time assertion kept as a test so the intent is visible: the field is LAST
            // and defaulted, so every three-argument caller in every consumer survives.
            QueryParameterDef("n", "t", "l").binds shouldBe emptyList()
        }

        // ----- (c) copy preserves it -----

        "copy preserves binds" {
            val def = QueryParameterDef("n", "t", "l", binds = listOf("a.b"))

            def.copy(label = "L").binds shouldBe listOf("a.b")
            def.copy(binds = emptyList()).binds shouldBe emptyList()
        }
    })
