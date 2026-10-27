// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path

class DeclaredPackageScopeSpec :
    StringSpec({
        fun write(
            root: Path,
            relative: String,
            source: String,
        ) {
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, source)
        }
        val root = tempdir("package-scope").toPath().resolve("misleading/cache")
        write(
            root,
            "entity.ttrm",
            """
            package acme.sales
            model er
            def entity order { attributes: [def attribute id { type: int, isKey: true }] }
            """.trimIndent(),
        )
        write(
            root,
            "table.ttrm",
            """
            package acme.sales
            model db schema dbo
            def table orders { columns: [def column id { type: int, isKey: true }] }
            """.trimIndent(),
        )
        write(
            root,
            "action.ttrm",
            """
            package acme.sales.automation
            model automation
            def action release { columns: [def column order { type: order.id }] }
            """.trimIndent(),
        )
        write(
            root,
            "row.ttrm",
            """
            package acme.sales.displays
            model db
            def schema notice { columns: [def column id { type: int }] }
            """.trimIndent(),
        )
        val index = ModelRepo.indexOf(root)!!

        fun imports(pkg: String) = listOf(ModelIndex.importScope(pkg.split('.'), pkg))

        "ordinary entities tables actions and row schemas use declared package segments" {
            for (pkg in listOf("acme", "acme.sales")) {
                index.packageExists(pkg) shouldBe true
                index.findEntities("order", imports(pkg)).size shouldBe 1
                index.findByPackage(pkg, "orders").size shouldBe 1
                index.automationInScope(imports(pkg)).map { it.qname.name } shouldBe listOf("release")
                index.findRowSchemas("notice", imports(pkg)).size shouldBe 1
                index.rowSchemasInScope(imports(pkg)).size shouldBe 1
            }
        }
        "checkout folders and partial package segments cannot widen import scope" {
            for (pkg in listOf("misleading", "cache", "acm", "acme.sale", "acme.salesman")) {
                index.packageExists(pkg) shouldBe false
                index.findEntities("order", imports(pkg)).size shouldBe 0
                index.findByPackage(pkg, "orders").size shouldBe 0
                index.automationInScope(imports(pkg)).size shouldBe 0
                index.findRowSchemas("notice", imports(pkg)).size shouldBe 0
                index.rowSchemasInScope(imports(pkg)).size shouldBe 0
            }
        }
        "an explicit package overrides a package-shaped source directory" {
            val other = tempdir("directory-scope").toPath()
            write(other, "wrong/path/entity.ttrm", "package correct.pkg\nmodel er\ndef entity item { attributes: [] }")
            val loaded = ModelRepo.indexOf(other)!!
            loaded.packageExists("correct.pkg") shouldBe true
            loaded.packageExists("wrong") shouldBe false
            loaded.findEntities("item", imports("wrong")).size shouldBe 0
        }
        val tierRoot = tempdir("qualified-tier-scope").toPath()
        write(
            tierRoot,
            "acme/db/orders.ttrm",
            "package acme\nmodel db schema dbo\ndef table orders { columns: [def column id { type: int }] }",
        )
        write(
            tierRoot,
            "acme/binding/extra.ttrm",
            "package acme\nmodel db schema dbo\ndef table extra { columns: [] }",
        )
        write(
            tierRoot,
            "world.ttrm",
            """
            package acme.worlds
            model world
            def world dev {
                def engine pg { type: postgres }
                def storage data { type: postgres, via: pg, hosts: [acme] }
            }
            """.trimIndent(),
        )

        fun checkTier(source: String) =
            TtrpChecker(TtrpManifest(world = "acme.worlds.dev", manifestDir = tierRoot), tierRoot)
                .check(source, "qualified.ttrp")

        "a qualified db tier load resolves a table by its declared package" {
            val report = checkTier("container c target pg { s = load(acme.db.orders) }")
            report.errors.map { it.render() } shouldBe emptyList()
            report.schemas["c"]?.get("s")?.map { it.name } shouldBe listOf("id")
        }
        "a qualified er tier load cannot resolve a db table" {
            val report = checkTier("container c target pg { s = load(acme.er.orders) }")
            report.errors.map { it.id.name } shouldBe listOf("RES_001")
        }
        "a non-tier directory cannot add a package import" {
            val report = checkTier("import acme.binding.*\ncontainer c target pg { }")
            report.errors.map { it.id.name } shouldBe listOf("RES_006")
        }
        "undeclared files retain the loader's computed package" {
            val legacy = tempdir("legacy-packages").toPath()
            write(legacy, "legacy/nested/entity.ttrm", "model er\ndef entity item { attributes: [] }")
            val loaded = ModelRepo.indexOf(legacy)!!
            loaded.packageExists("legacy") shouldBe true
            loaded.packageExists("legacy.nested") shouldBe true
            loaded.findEntities("item", imports("legacy.nested")).size shouldBe 1
        }
    })
