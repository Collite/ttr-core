// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.doc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ReadmeRenderSpec :
    FunSpec({
        val root =
            generateSequence(
                java.nio.file.Path
                    .of("")
                    .toAbsolutePath(),
            ) { it.parent }
                .first {
                    java.nio.file.Files
                        .isDirectory(it.resolve("packages/kotlin/ttrp-cli"))
                }
        val recipe =
            org.tatrman.ttrp.bundle.RunManifest
                .fromJson(
                    java.nio.file.Files.readString(
                        root.resolve(
                            "packages/kotlin/ttrp-cli/src/test/resources/fixtures/release-order-before-golden-field.json",
                        ),
                    ),
                ).recipe!!
        val labels =
            ReadmeLabels(
                mapOf(
                    "release_order" to mapOf("en" to "Releasing a placed order", "cs" to "Uvolnění objednávky"),
                    "er.entity.order" to mapOf("en" to "Order", "cs" to "Objednávka"),
                    "tolerance" to mapOf("en" to "Tolerance", "cs" to "Tolerance ceny"),
                    "release" to mapOf("en" to "Release order", "cs" to "Uvolnit objednávku"),
                ),
                mapOf("release" to mapOf("en" to "Would release this order.", "cs" to "Objednávka by byla uvolněna.")),
            )
        test("catalogue labels come from the published metadata snapshot in the imported package") {
            val snapshot =
                org.tatrman.ttrp.resolve.ModelRepo
                    .snapshotOf(
                        java.nio.file.Path
                            .of("src/test/resources/model"),
                    )
            check(snapshot != null)
            val actual =
                ReadmeLabels.from(
                    org.tatrman.ttrp.resolve.ModelIndex(snapshot).objectsInScope(
                        listOf(
                            org.tatrman.ttrp.resolve.ModelIndex.importScope(
                                listOf("hartland", "automation"),
                                "hartland.automation",
                            ),
                        ),
                    ),
                )
            actual.labels.getValue("tolerance").getValue("cs") shouldBe "Tolerance ceny"
            actual.labels.getValue("release_order").getValue("cs") shouldBe "Uvolnění objednávky"
            ReadmeRenderer.render(recipe, actual, lang = "cs") shouldContain "Uvolnit objednávku"
        }
        test("tier imports retain entity labels in what the recipe reads") {
            val snapshot =
                org.tatrman.ttrp.resolve.ModelRepo.snapshotOf(
                    java.nio.file.Path
                        .of("src/test/resources/model"),
                )!!
            val index =
                org.tatrman.ttrp.resolve
                    .ModelIndex(snapshot)
            val imports =
                org.tatrman.ttrp.parser.TtrpParser
                    .parseString("import hartland.er.*")
                    .document.statements
                    .filterIsInstance<org.tatrman.ttrp.ast.ImportDecl>()
                    .map {
                        org.tatrman.ttrp.resolve.ModelIndex
                            .importScope(it.qname.parts, it.qname.text)
                    }
            val actual = ReadmeLabels.from(index.objectsInScope(imports))
            ReadmeRenderer.render(recipe, actual) shouldContain "Order (`er.entity.order`)"
        }
        test("single-object imports are refused by the checker rather than inventing README scope") {
            val report =
                org.tatrman.ttrp.resolve
                    .TtrpChecker(
                        org.tatrman.ttrp.project
                            .TtrpManifest(manifestDir = java.nio.file.Path.of(".")),
                        java.nio.file.Path
                            .of("src/test/resources/model"),
                    ).check("import hartland.er.order")
            report.errors.any { it.id == org.tatrman.ttrp.diagnostics.TtrpDiagnosticId.RES_006 } shouldBe true
        }
        for (lang in listOf("en", "cs")) {
            test("H-1 README $lang: routes, reasons, labeled reads, actions, parameter values and mode") {
                val text = ReadmeRenderer.render(recipe, labels, lang = lang)
                text shouldBe ReadmeRenderer.render(recipe, labels, lang = lang)
                text shouldContain "stock shortfall or price difference"
                recipe.roles.routes.forEach { route -> text shouldContain route.because!! }
                text shouldContain "`tolerance`"
                text shouldContain "`0.01`"
                text shouldContain "`50000`"
                text shouldContain "`per-candidate`"
                text shouldContain if (lang == "en") "Order (`er.entity.order`)" else "Objednávka (`er.entity.order`)"
                val golden =
                    java.nio.file.Path
                        .of("src/test/resources/docs/README.$lang.md")
                if (System.getProperty("updateGolden") == "true") {
                    java.nio.file.Files
                        .writeString(golden, text)
                }
                text shouldBe
                    java.nio.file.Files
                        .readString(golden)
            }
        }
    })
