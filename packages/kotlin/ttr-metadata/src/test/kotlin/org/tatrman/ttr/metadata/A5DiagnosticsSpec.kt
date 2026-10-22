// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.reconcile.ReconciliationResult
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stage 10 Task 4 — the A5 package/import diagnostics fire with the right severity.
 *
 * Error-severity codes (`wrong-file-kind`, `package-declaration-mismatch`) land on
 * `errors` (reconcile not ok); warning/info codes (`unused-import`,
 * `wildcard-with-no-matches`, `duplicate-import`, `circular-package-dependency`,
 * `missing-package-declaration`) land on `warnings` and never block the load.
 * (`unimported-reference` / `ambiguous-reference` are covered by
 * PackageResolutionSpec / ReferenceResolverSpec / StockRoleResolutionSpec.)
 */
class A5DiagnosticsSpec :
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
                    sourceId = "diag",
                    priority = 100,
                    storage = LocalFsStorage(id = "diag", rootPath = root),
                )
            return ModelReconciler(ModelDescriptor(id = "d", name = "d")).reconcile(listOf(source.load()))
        }

        fun ReconciliationResult.errHas(code: String) = errors.any { it.message.contains(code) }

        fun ReconciliationResult.warnHas(code: String) = warnings.any { it.message.contains(code) }

        "ttr/wrong-file-kind (ERROR) — a `def role` in a `model db` file blocks load" {
            val root = Files.createTempDirectory("diag-wfk")
            write(
                root,
                "bad.ttr",
                """
                model db schema dbo
                def role myrole { }
                """,
            )
            val r = reconcile(root)
            r.errHas("ttr/wrong-file-kind") shouldBe true
            r.ok shouldBe false
        }

        "a `def query` in a `model db` file is a db query, not a wrong-file-kind (ttr-core#193)" {
            val root = Files.createTempDirectory("diag-dbq")
            write(
                root,
                "db.ttr",
                """
                model db schema dbo
                def table customers { columns: [ def column id { type: int, isKey: true } ] }
                def query all_customers { sourceText: ""${'"'}sql
                SELECT id FROM customers
                ""${'"'} }
                """,
            )
            val r = reconcile(root)
            r.errHas("ttr/wrong-file-kind") shouldBe false
            r.ok shouldBe true
            r.model.queries.keys
                .map { "${it.schemaCode}.${it.namespace}.${it.name}" } shouldBe
                listOf("DB.dbo.all_customers")
        }

        // The allowed kinds come from `modelForKind`, so every kind the manual places in a model is legal
        // there — not only the ones a hand-kept list remembered (drill_map, er2cnc_role were missing).
        "a `def drill_map` in a `model db` file and a `def er2cnc_role` in a `model cnc` file are legal" {
            val root = Files.createTempDirectory("diag-kinds")
            write(
                root,
                "db.ttrm",
                """
                model db schema dbo
                def drill_map store_to_sales {
                    from: db.dbo.store_totals,
                    to:   db.dbo.store_sales,
                    args: { store: "s_store_id" },
                }
                """,
            )
            write(
                root,
                "cnc.ttrm",
                """
                model cnc
                def er2cnc_role store_is_master { entity: er.entity.store, role: cnc.role.master }
                """,
            )
            reconcile(root).errHas("ttr/wrong-file-kind") shouldBe false
        }

        "a kind of another model is still a wrong-file-kind (entity in db, er2db_entity in er)" {
            val root = Files.createTempDirectory("diag-other")
            write(
                root,
                "db.ttrm",
                """
                model db schema dbo
                def entity store { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            write(
                root,
                "er.ttrm",
                """
                model er
                def er2db_entity store { entity: er.entity.store, target: { table: db.dbo.store } }
                """,
            )
            val r = reconcile(root)
            r.errors.count { it.message.contains("ttr/wrong-file-kind") } shouldBe 2
            r.ok shouldBe false
        }

        "a tier-neutral `def area` beside a model directive is not a wrong-file-kind" {
            val root = Files.createTempDirectory("diag-area")
            write(
                root,
                "er.ttrm",
                """
                model er
                def area sales { packages: [sales] }
                def entity store { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            reconcile(root).errHas("ttr/wrong-file-kind") shouldBe false
        }

        "ttr/package-declaration-mismatch (ERROR) — declared package != directory blocks load" {
            val root = Files.createTempDirectory("diag-pdm")
            write(
                root,
                "foo/x.ttr",
                """
                package bar
                model er schema entity
                def entity E { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.errHas("ttr/package-declaration-mismatch") shouldBe true
            r.ok shouldBe false
        }

        "ttr/missing-package-declaration (Info/warn) — subdir file with no package, non-blocking" {
            val root = Files.createTempDirectory("diag-mpd")
            write(
                root,
                "foo/x.ttr",
                """
                model er schema entity
                def entity E { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.warnHas("ttr/missing-package-declaration") shouldBe true
            r.errHas("ttr/missing-package-declaration") shouldBe false
        }

        "N1: a named relation import used by an MD/ER path is not unused" {
            val root = Files.createTempDirectory("diag-mder-import")
            try {
                write(
                    root,
                    "md.ttrm",
                    "model md\ndef domain Code { type: string }\ndef dimension Customer { key: code, attributes: [def attribute code { domain: md.Code }] }",
                )
                write(
                    root,
                    "er.ttrm",
                    "model er\ndef entity customer { attributes: [def attribute sk { type: int }] }\ndef entity addr { attributes: [def attribute state { type: text }] }",
                )
                write(
                    root,
                    "east/er.ttrm",
                    "package east\nmodel er\ndef relation address { from: er.entity.customer, to: er.entity.addr }",
                )
                write(
                    root,
                    "binding.ttrm",
                    "import east.er.relation.address\nmodel binding\ndef md2er_dimension c { dimension: md.Customer, entity: er.entity.customer, attributes: { code: address.state } }",
                )
                reconcile(root).warnings.any {
                    it.message.contains("unused-import") &&
                        it.message.contains("east.er.relation.address")
                } shouldBe
                    false
                write(
                    root,
                    "binding.ttrm",
                    "import east.er.relation.address\nmodel binding\ndef md2er_dimension c { dimension: md.Customer, entity: er.entity.customer, attributes: { code: sk } }",
                )
                reconcile(root).warnings.any {
                    it.message.contains("unused-import") &&
                        it.message.contains("east.er.relation.address")
                } shouldBe
                    true
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "ttr/unused-import (warn) — a named import no reference uses, non-blocking" {
            val root = Files.createTempDirectory("diag-unused")
            write(
                root,
                "x.ttr",
                """
                import er.entity.Customer
                model er schema entity
                def entity E { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.warnHas("ttr/unused-import") shouldBe true
        }

        "ttr/wildcard-with-no-matches (warn) — wildcard import matching nothing, non-blocking" {
            val root = Files.createTempDirectory("diag-wnm")
            write(
                root,
                "x.ttr",
                """
                import er.nothinghere.*
                model er schema entity
                def entity E { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.warnHas("ttr/wildcard-with-no-matches") shouldBe true
        }

        "ttr/duplicate-import (warn) — the same import twice, non-blocking" {
            val root = Files.createTempDirectory("diag-dup")
            write(
                root,
                "x.ttr",
                """
                import er.entity.*
                import er.entity.*
                model er schema entity
                def entity E { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.warnHas("ttr/duplicate-import") shouldBe true
        }

        "ttr/circular-package-dependency (warn) — package A imports B imports A, non-blocking" {
            val root = Files.createTempDirectory("diag-cycle")
            write(
                root,
                "alpha/a.ttr",
                """
                package alpha
                import beta.entity.*
                model er schema entity
                def entity A { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            write(
                root,
                "beta/b.ttr",
                """
                package beta
                import alpha.entity.*
                model er schema entity
                def entity B { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.warnHas("ttr/circular-package-dependency") shouldBe true
        }
    })
