// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.loader.TtrLoader

/** #258 / review-128: same-package candidates and MD roles do not depend on indexing order. */
class SamePackageResolutionSpec :
    StringSpec({
        fun table(texts: List<String>): SymbolTable =
            Fixtures.symbolTable(*texts.mapIndexed { i, text -> "$i.ttrm" to text }.toTypedArray())

        fun ref(
            path: String,
            role: MdReferenceRole? = null,
        ) = Resolver.Ref(path, path.split('.'), mdContext = role)

        fun ctx(
            model: String = "er",
            pkg: String = "shop",
        ) = ResolutionContext(model, "", packageName = pkg)

        "top-level wins over members, members remain reachable, and the current model wins" {
            val texts =
                listOf(
                    "package shop\nmodel er\ndef entity customer {}",
                    "package shop\nmodel er\ndef entity order { attributes: [def attribute customer { type: int }] }",
                )
            for (order in listOf(texts, texts.reversed())) {
                val resolver = Resolver(table(order))
                for (model in listOf("er", "lexicon")) {
                    resolver
                        .resolveReference(
                            ref("customer"),
                            ctx(model),
                        ).shouldBeInstanceOf<ResolutionResult.Resolved>()
                        .symbol.qname shouldBe
                        "shop.er.entity.customer"
                }
                resolver
                    .resolveReference(
                        ref("customer"),
                        ctx().copy(enclosingQname = "shop.er.entity.order"),
                    ).shouldBeInstanceOf<ResolutionResult.Resolved>()
                    .viaStep shouldBe
                    ResolutionStep.Lexical
            }
            Resolver(
                table(texts.drop(1)),
            ).resolveReference(
                ref("customer"),
                ctx(),
            ).shouldBeInstanceOf<ResolutionResult.Resolved>()
                .symbol.qname shouldBe
                "shop.er.entity.order.customer"
            val models =
                listOf(
                    "package shop\nmodel er\ndef entity revenue {}",
                    "package shop\nmodel md\ndef measure revenue {}",
                )
            for (order in listOf(models, models.reversed())) {
                Resolver(
                    table(order),
                ).resolveReference(
                    ref("revenue"),
                    ctx(),
                ).shouldBeInstanceOf<ResolutionResult.Resolved>()
                    .symbol.qname shouldBe
                    "shop.er.entity.revenue"
            }
        }

        "multiple members remain ambiguous and sorted in either order" {
            val texts =
                listOf("sale", "order").map {
                    "package shop\nmodel er\ndef entity $it { attributes: [def attribute revenue { type: decimal }] }"
                }
            for (order in listOf(texts, texts.reversed())) {
                val result =
                    Resolver(
                        table(order),
                    ).resolveReference(ref("revenue"), ctx("lexicon")).shouldBeInstanceOf<ResolutionResult.Unresolved>()
                result.reason shouldBe ResolutionResult.Reason.Ambiguous
                result.candidates.map { it.qname } shouldBe
                    listOf("shop.er.entity.order.revenue", "shop.er.entity.sale.revenue")
            }
        }

        val texts =
            listOf(
                "package shop\nmodel er\ndef entity sale { attributes: [def attribute revenue { type: decimal }] }",
                "package shop\nmodel md\ndef domain Money { type: decimal }\ndef measure revenue { domain: md.Money }",
                "package shop\nmodel md\ndef cubelet Sales { measures: [revenue] }",
            )
        for (order in listOf(
            listOf(0, 1, 2),
            listOf(0, 2, 1),
            listOf(1, 0, 2),
            listOf(1, 2, 0),
            listOf(2, 0, 1),
            listOf(2, 1, 0),
        )) {
            "MD measure slot in document order $order" {
                val resolver = Resolver(table(order.map { texts[it] }))
                val collected =
                    collectAllReferences(
                        TtrLoader.parseString(texts[2], "cubelet.ttrm").definitions,
                    ).single()
                collected.mdContext shouldBe MdReferenceRole.Measure
                val result =
                    resolver
                        .resolveReference(
                            collected.toRef(),
                            ctx("md"),
                        ).shouldBeInstanceOf<ResolutionResult.Resolved>()
                result.symbol.qname shouldBe "shop.md.measure.revenue"
                result.viaStep shouldBe ResolutionStep.SamePackage
            }
        }

        "MD local, qualified, root-elided and imported targets retain precedence" {
            val resolver =
                Resolver(table(listOf("east", "west").map { "package $it\nmodel md\ndef measure revenue {}" }), "root")
            for ((path, target) in listOf(
                "revenue" to "west",
                "east.md.revenue" to "east",
                "east.md.measure.revenue" to "east",
                "root.east.md.revenue" to "east",
            )) {
                resolver
                    .resolveReference(
                        ref(path, MdReferenceRole.Measure),
                        ctx("md", "west"),
                    ).shouldBeInstanceOf<ResolutionResult.Resolved>()
                    .symbol.qname shouldBe
                    "$target.md.measure.revenue"
            }
            for ((imports, step) in listOf(
                "import west.md.measure.revenue" to ResolutionStep.NamedImport,
                "import west.*" to ResolutionStep.WildcardImport,
            )) {
                val doc = TtrLoader.parseString("package app\n$imports\nmodel md", "app.ttrm")
                val result =
                    resolver
                        .resolveReference(
                            ref("revenue", MdReferenceRole.Measure),
                            ctx("md", "app").copy(imports = doc.imports),
                        ).shouldBeInstanceOf<ResolutionResult.Resolved>()
                result.symbol.qname shouldBe "west.md.measure.revenue"
                result.viaStep shouldBe step
            }
            val imports =
                TtrLoader
                    .parseString(
                        "package app\nimport east.*\nimport west.*\nmodel md",
                        "app.ttrm",
                    ).imports
            resolver
                .resolveReference(
                    ref("revenue", MdReferenceRole.Measure),
                    ctx("md", "app").copy(imports = imports),
                ).shouldBeInstanceOf<ResolutionResult.Unresolved>()
                .reason shouldBe
                ResolutionResult.Reason.Ambiguous
            val wrongKinds =
                Resolver(
                    table(
                        listOf(
                            "package shop\nmodel er\ndef entity revenue {}",
                            "package shop\nmodel cn\ndef kind Revenue {}",
                        ),
                    ),
                )
            for (path in listOf("revenue", "cn.Revenue")) {
                wrongKinds
                    .resolveReference(
                        ref(path, MdReferenceRole.Measure),
                        ctx("md"),
                    ).shouldBeInstanceOf<ResolutionResult.Unresolved>()
                    .reason shouldBe
                    ResolutionResult.Reason.NotFound
            }
        }
    })
