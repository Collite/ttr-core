// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.EmbeddedBlock
import org.tatrman.ttrp.expr.ExistsExpr
import org.tatrman.ttrp.parser.TtrpParser
import java.nio.file.Files
import java.util.IdentityHashMap
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString

/**
 * CN-P2 S2.6 T5 — every `definition:` / `members:` / `filter:` block parsed with the TTR-P 0.5 expression grammar and
 * name-resolved into a [ResolvedCnModel] (cn contracts §1.1 "The 0.5 spellings", §3). A syntax error lands in the
 * `.ttrm` through the block's source map; a name the definition's scope lacks is `cn/definition-unknown-name`.
 * Fixtures: `resources/definitions` (package `hartland.defs`) — each concept named after what it shows.
 */
class CnDefinitionsSpec :
    StringSpec({
        val m = DefinitionFixtures.defs

        fun def(name: String) = m.definitions.all.single { it.owner.name == name }

        val fixtureText by lazy {
            Files
                .readString(
                    DefinitionFixtures.repo.resolve(
                        "packages/kotlin/ttr-concept/src/test/resources/definitions/d-concepts.ttrm",
                    ),
                ).lines()
        }

        "every definition block in the repository parses with the TTRP 0.5 grammar" {
            // Every EmbeddedBlock the TTR-M parser builds — concept defs, bindings, and the `concept { }` sugar on er
            // and md defs — over every tracked model source that parses (the `*negative*` rosters fail by design).
            val roots =
                listOf("tests/conformance/fixtures", "samples", "docs/manual", "packages/kotlin")
                    .map { DefinitionFixtures.repo.resolve(it) }
            val files =
                roots
                    .flatMap { root -> Files.walk(root).use { s -> s.filter { it.extension == "ttrm" }.toList() } }
                    .filter { p ->
                        val s = p.invariantSeparatorsPathString
                        "/build/" !in s && "negative" !in s && "node_modules" !in s
                    }
            val blocks = mutableListOf<Pair<String, EmbeddedBlock>>()
            for (f in files) {
                val parsed = TtrLoader.parseString(Files.readString(f), f.toString())
                if (parsed.errors.isNotEmpty()) continue
                collectBlocks(parsed.definitions).forEach { blocks += f.toString() to it }
            }
            blocks.size shouldBeGreaterThan 40
            val failures =
                blocks.mapNotNull { (file, b) ->
                    val r = TtrpParser.parseExpression(b.text)
                    val first = r.diagnostics.firstOrNull() ?: return@mapNotNull null
                    "$file:${b.source.line}: `${b.text}` — ${first.message}"
                }
            // the one block that fails on purpose is this module's own syntax fixture
            failures.filterNot { "ttr-concept/src/test/resources/definitions" in it }.shouldBeEmpty()
        }

        "a ResolvedCnModel is the reconciled model, its cn tier, every block parsed, and the snapshot hash" {
            m.cn.concepts.size shouldBeGreaterThan 30
            m.snapshotHash shouldBe m.model.version.value
            m.definitions.all
                .map { it.kind }
                .toSet() shouldBe BlockKind.entries.toSet()
            def("HomeCategory").bearer?.name shouldBe "ProductCategory" // members: `this` is the parent kind
            def("OverdueInvoice").bearer?.name shouldBe "Invoicing" // definition: `this` is `of:`
            def("stock_count_g").kind shouldBe BlockKind.FILTER
            ResolvedCnModel.of(m.model, "sha256:x").snapshotHash shouldBe "sha256:x"
        }

        "names that resolve: labels, a phase as a predicate, qualities, periods, :as_of, :name, descendants, a filter" {
            listOf(
                "OverdueInvoice",
                "DebtorCustomer",
                "PendingOrder",
                "HomeCategory",
                "Mispriced",
                "open_amount",
                "OutOfStock",
                "stock_count_g",
            ).forEach { def(it).diagnostics.shouldBeEmpty() }

            def("DebtorCustomer").let {
                it.expression.shouldBeInstanceOf<ExistsExpr>()
                it.reads.concepts.map { q -> q.name } shouldContainExactly listOf("Invoicing")
                it.reads.definitions.map { q -> q.name } shouldContainExactly listOf("OverdueInvoice", "open_amount")
            }
            def("Mispriced").reads.parameters shouldBe setOf("tolerance")
            def("OverdueInvoice").reads.let {
                it.asOf shouldBe true
                it.comparisons.mapNotNull { c -> c.lhs as? InstantTerm.PeriodBound }.single().let { b ->
                    b.bound shouldBe "end"
                    b.quality shouldBe "due"
                }
            }
            def("HomeCategory").reads.concepts.map { it.name } shouldContainExactly listOf("ProductCategory")
        }

        "a syntax error lands in the .ttrm — a triple string, a string with escapes, a pl block's second line" {
            fun lineOf(marker: String) = fixtureText.indexOfFirst { marker in it }

            val broken = def("BrokenSyntax").diagnostics.single()
            broken.slug shouldBe CnDefinitions.SYNTAX
            broken.message shouldContain "TTRP-PRS-001"
            val bl = lineOf("def phase BrokenSyntax")
            broken.location.shouldNotBeNull().let {
                it.line shouldBe bl + 1
                it.column shouldBe fixtureText[bl].indexOf("= = 'held'") + 2 // the second `=`
            }
            // `"state = \"a\" )"` — two escapes before the `)`: the file column steps over both backslashes
            val el = lineOf("def phase EscapedSyntax")
            def("EscapedSyntax").diagnostics.single().location.shouldNotBeNull().let {
                it.line shouldBe el + 1
                it.column shouldBe fixtureText[el].indexOf(" )\"") + 1
            }
            val tl = lineOf("and placed_at )")
            def("TaggedSyntax").diagnostics.single().location.shouldNotBeNull().let {
                it.line shouldBe tl + 1
                it.column shouldBe fixtureText[tl].indexOf(")")
            }
            def("BrokenSyntax").expression shouldBe null
        }

        "names the scope lacks: cn/definition-unknown-name; a kind with no hierarchy: cn/descendants-no-hierarchy" {
            listOf(
                Triple(
                    "UnknownQuality",
                    CnDefinitions.UNKNOWN_NAME,
                    "`Order` has no quality, participant, period or phase `colour`",
                ),
                Triple("UnknownLabel", CnDefinitions.UNKNOWN_NAME, "`Invoicing` has no participant `buyer`"),
                Triple("UnknownConcept", CnDefinitions.UNKNOWN_NAME, "no concept `Refund`"),
                Triple("UnknownParam", CnDefinitions.UNKNOWN_NAME, "`:mode` reads no `def parameter`"),
                Triple("FancyBrand", CnDefinitions.NO_HIERARCHY, "`Brand` has none"),
                Triple("stock_probe_g", CnDefinitions.UNKNOWN_NAME, "the target entity has no attribute `readout`"),
            ).forEach { (owner, slug, fragment) ->
                val d = def(owner).diagnostics.single()
                d.slug shouldBe slug
                d.severity shouldBe Severity.ERROR
                d.message shouldContain fragment
                d.location.shouldNotBeNull()
            }
        }

        "fixture 75's definitions, with 76's bindings, all parse; two name what the fixture's model lacks" {
            val r = DefinitionFixtures.repo
            val p =
                ResolvedCnModel.of(
                    DefinitionFixtures.load(
                        DefinitionFixtures.dir(
                            r.resolve("packages/kotlin/ttr-metadata/src/test/resources/cn/concepts"),
                            "hartland/concepts",
                        ) +
                            mapOf(
                                "hartland/concepts/c-concepts.ttrm" to
                                    r.resolve("tests/conformance/fixtures/75-cn-concepts.ttrm"),
                                "hartland/concepts/d-bindings.ttrm" to
                                    r.resolve("tests/conformance/fixtures/76-cn-bindings.ttrm"),
                            ),
                    ),
                )
            p.definitions.all.size shouldBe 8
            p.definitions.all.all { it.expression != null } shouldBe true
            p.definitions.diagnostics.map { it.subject to it.message } shouldContainExactly
                listOf(
                    // `exists StockoutEpisode e …` — 75 never defines the episode event
                    "cn.OutOfStock" to "no concept `StockoutEpisode`",
                    // `this.from.period.at` — 75's StockTransfer moves between Warehouses, a kind with no period
                    "cn.transfer_delay" to "`Warehouse` has no `period:`",
                )
        }
    })

/** Every [EmbeddedBlock] under [roots] — a reflective walk of the parser's model classes (cn defs, bindings, sugar). */
private fun collectBlocks(roots: List<Any>): List<EmbeddedBlock> {
    val out = mutableListOf<EmbeddedBlock>()
    val seen = IdentityHashMap<Any, Unit>()

    fun visit(x: Any?) {
        when {
            x == null || seen.put(x, Unit) != null -> return
            x is EmbeddedBlock -> out += x
            x is Iterable<*> -> x.forEach { visit(it) }
            x is Map<*, *> -> x.values.forEach { visit(it) }
            x.javaClass.name.startsWith("org.tatrman.ttr.parser.model.") ->
                generateSequence<Class<*>>(x.javaClass) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .filterNot {
                        java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                    }.forEach { f ->
                        f.isAccessible = true
                        visit(f.get(x))
                    }
        }
    }
    roots.forEach { visit(it) }
    return out
}
