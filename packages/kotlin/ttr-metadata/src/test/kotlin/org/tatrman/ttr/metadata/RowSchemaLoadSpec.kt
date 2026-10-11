// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.reconcile.ReconciliationResult
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path

/**
 * Grammar 0.14 — `def schema` row schemas surface on [org.tatrman.ttr.metadata.model.Model.rowSchemas]
 * keyed by package-qualified name (the `areas` precedent: not model objects, no tier). The kind is
 * tier-neutral, so it never trips `ttr/wrong-file-kind`; a re-declaration in one source is an error.
 */
class RowSchemaLoadSpec :
    StringSpec({
        fun write(
            root: Path,
            rel: String,
            content: String,
        ) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, content.trimIndent())
        }

        fun reconcile(root: Path): ReconciliationResult {
            val source =
                FileBasedSource(
                    sourceId = "rs",
                    priority = 100,
                    storage = LocalFsStorage(id = "rs", rootPath = root),
                )
            return ModelReconciler(ModelDescriptor(id = "d", name = "d")).reconcile(listOf(source.load()))
        }

        "a def schema lands on Model.rowSchemas under its package-qualified name" {
            val root = Files.createTempDirectory("rs-load")
            write(
                root,
                "shop/actions/schemas.ttrm",
                """
                package shop.actions
                def schema notify {
                    description: "Send a notification",
                    columns: [
                        def column recipient { type: text },
                        def column amount { type: decimal, optional: true },
                    ]
                }
                """,
            )
            val r = reconcile(root)
            r.ok shouldBe true
            val s = r.model.rowSchemas.getValue("shop.actions.notify")
            s.name shouldBe "notify"
            s.pkg shouldBe "shop.actions"
            s.description shouldBe "Send a notification"
            s.columns.map { Triple(it.name, it.type, it.optional) } shouldContainExactly
                listOf(Triple("recipient", "text", false), Triple("amount", "decimal", true))
            s.sourceFile.endsWith("shop/actions/schemas.ttrm") shouldBe true
        }

        "a column's structured type keeps its length and precision (a bare type name has neither)" {
            val root = Files.createTempDirectory("rs-precision")
            write(
                root,
                "shop/actions/schemas.ttrm",
                """
                package shop.actions
                def schema priced {
                    columns: [
                        def column amount { type: { type: decimal, length: 12, precision: 4 } },
                        def column code { type: { type: varchar, length: 40 }, optional: true },
                        def column note { type: text },
                    ]
                }
                """,
            )
            val cols =
                reconcile(root)
                    .model.rowSchemas
                    .getValue("shop.actions.priced")
                    .columns
            cols.map { listOf(it.name, it.type, it.length, it.precision) } shouldContainExactly
                listOf(
                    listOf("amount", "decimal", 12, 4),
                    listOf("code", "varchar", 40, null),
                    listOf("note", "text", null, null),
                )
        }

        "a def schema in a `model db` file is not a wrong-file-kind (tier-neutral)" {
            val root = Files.createTempDirectory("rs-wfk")
            write(
                root,
                "shop/db.ttrm",
                """
                model db schema dbo
                def table T { columns: [ def column a { type: int } ] }
                def schema s { columns: [ def column a { type: int } ] }
                """,
            )
            val r = reconcile(root)
            r.errors.filter { it.message.contains("wrong-file-kind") } shouldBe emptyList()
            r.model.rowSchemas.keys shouldContainExactly setOf("shop.s")
        }

        "a schema declared twice in one package is ttr/duplicate-schema" {
            val root = Files.createTempDirectory("rs-dup")
            write(root, "shop/a.ttrm", "def schema s { columns: [ def column a { type: int } ] }")
            write(root, "shop/b.ttrm", "def schema s { columns: [ def column b { type: int } ] }")
            val r = reconcile(root)
            r.errors.any { it.message.contains("ttr/duplicate-schema") } shouldBe true
        }
    })
