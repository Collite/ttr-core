// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.cn

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.CollectedRef
import org.tatrman.ttr.semantics.ResolutionContext
import org.tatrman.ttr.semantics.ResolutionResult
import org.tatrman.ttr.semantics.ResolvedManifest
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.SymbolTable
import org.tatrman.ttr.semantics.Validator
import org.tatrman.ttr.semantics.collectAllReferences
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * CN-P2 S2.4 — the cn model in Kotlin semantics: keys, cn-context resolution, the desugar and the portable
 * `cn/…` checks. The conformance dump compares diagnostic codes only; the sites table here pins where each fires
 * (`file:line code`) — the same table the TS twin asserts (`packages/lint/src/__tests__/cn-rules.test.ts`),
 * the lexicon pair (100, 101) excepted.
 */
class CnSemanticsSpec :
    StringSpec({

        val fixtures = locateFixtures()

        class Project(
            val symbols: SymbolTable,
            val resolver: Resolver,
            val docs: List<SemanticDocument>,
        )

        fun project(vararg files: Pair<String, String>): Project {
            val symbols = SymbolTable()
            val docs =
                files.map { (uri, src) ->
                    val r = TtrLoader.parseString(src, uri)
                    r.errors shouldBe emptyList()
                    val schemaCode = r.modelDirective?.modelCode ?: ""
                    val namespace = r.modelDirective?.schema ?: ""
                    symbols.upsertDocument(uri, r.definitions, schemaCode, namespace, r.packageName ?: "")
                    SemanticDocument(
                        uri,
                        r.definitions,
                        schemaCode,
                        namespace,
                        r.packageName ?: "",
                        r.imports,
                        r.modelDirective?.source,
                    )
                }
            return Project(symbols, Resolver(symbols), docs)
        }

        fun scenario(dir: String): Project {
            val files =
                Files.list(fixtures.resolve(dir)).use { s -> s.filter { it.name.endsWith(".ttrm") }.sorted().toList() }
            return project(*files.map { "$dir/${it.name}" to it.readText() }.toTypedArray())
        }

        /** The cn diagnostics of a scenario (the project pass and each document's), as `file:line code`, sorted. */
        fun cnSites(dir: String): List<String> {
            val p = scenario(dir)
            val all = validateCn(p.docs, p.resolver).diagnostics + p.docs.flatMap { cncDeprecations(it) }
            return all.map { "${it.uri.substringAfter('/')}:${it.source.line} ${it.code.id}" }.sorted()
        }

        val expected =
            mapOf(
                "85-cn-duplicate-grounding" to
                    listOf(
                        "a-er.ttrm:19 cn/duplicate-grounding",
                        "a-er.ttrm:28 cn/duplicate-grounding",
                        "c-bindings.ttrm:5 cn/duplicate-grounding",
                    ),
                "86-cn-grounding-target-kind" to
                    listOf(
                        "c-concepts.ttrm:12 cn/grounding-target-kind",
                        "c-concepts.ttrm:20 cn/grounding-target-kind",
                        "c-concepts.ttrm:21 cn/grounding-target-kind",
                        "c-concepts.ttrm:21 cn/phase-no-definition",
                        "c-concepts.ttrm:23 cn/grounding-target-kind",
                        "c-concepts.ttrm:8 cn/grounding-target-kind",
                        "d-bindings.ttrm:5 cn/grounding-target-kind",
                        "d-bindings.ttrm:6 cn/grounding-target-kind",
                    ),
                "87-cn-role-no-event" to listOf("a-er.ttrm:16 cn/role-no-event", "b-concepts.ttrm:6 cn/role-no-event"),
                "88-cn-bearer-missing" to
                    listOf(
                        "a-er.ttrm:13 ttr/required-property-missing",
                        "b-concepts.ttrm:5 ttr/required-property-missing",
                        "b-concepts.ttrm:7 ttr/required-property-missing",
                    ),
                "89-cn-phase-double-definition" to
                    listOf(
                        "b-concepts.ttrm:5 cn/phase-double-definition",
                        "c-bindings.ttrm:4 cn/phase-double-definition",
                    ),
                "90-cn-phase-no-definition" to listOf("b-concepts.ttrm:5 cn/phase-no-definition"),
                "91-cn-event-no-period" to
                    listOf("a-er.ttrm:17 cn/event-no-period", "b-concepts.ttrm:4 cn/event-no-period"),
                "92-cn-ungrounded" to
                    listOf(
                        "b-concepts.ttrm:4 cn/ungrounded",
                        "b-concepts.ttrm:5 cn/ungrounded",
                        "b-concepts.ttrm:6 cn/ungrounded",
                        "b-concepts.ttrm:7 cn/ungrounded",
                    ),
                "93-cn-db-grounding" to
                    listOf(
                        "c-concepts.ttrm:4 cn/db-grounding",
                        "d-bindings.ttrm:4 cn/db-grounding",
                    ),
                "94-cn-involves-unlabelled-duplicate" to
                    listOf(
                        "b-concepts.ttrm:17 cn/involves-unlabelled-duplicate",
                        "b-concepts.ttrm:7 cn/involves-unlabelled-duplicate",
                    ),
                "95-cn-antirigid-supertype" to
                    listOf(
                        "b-concepts.ttrm:10 cn/antirigid-supertype",
                        "b-concepts.ttrm:8 cn/antirigid-supertype",
                        "b-concepts.ttrm:9 cn/antirigid-supertype",
                    ),
                "96-cn-unknown-relation-type" to
                    listOf("b-concepts.ttrm:7 cn/unknown-relation-type", "b-concepts.ttrm:8 cn/unknown-relation-type"),
                "97-cn-cnc-deprecated" to listOf("a-cnc.ttrm:7 cn/cnc-deprecated", "b-er.ttrm:4 cn/cnc-deprecated"),
                "98-cn-hierarchy-shape" to
                    listOf(
                        "b-concepts.ttrm:10 cn/hierarchy-shape",
                        "b-concepts.ttrm:14 cn/hierarchy-shape",
                        "c-bindings.ttrm:7 cn/hierarchy-shape",
                    ),
                "99-cn-participant-path-broken" to
                    listOf(
                        "d-bindings.ttrm:10 cn/participant-path-broken",
                        "d-bindings.ttrm:19 cn/participant-path-broken",
                        "d-bindings.ttrm:20 cn/participant-path-broken",
                    ),
            )

        expected.forEach { (dir, sites) ->
            "the cn slugs fire on $dir, at the sites its header names" { cnSites(dir) shouldBe sites }
        }

        "the S2.2 equivalence fixtures stay clean but for the advisory db grounding" {
            cnSites("81-cn-desugar-top-down") shouldBe listOf("d-concepts.ttrm:28 cn/db-grounding")
            cnSites("82-cn-desugar-top-down-authored") shouldBe listOf("e-bindings.ttrm:25 cn/db-grounding")
            cnSites("83-cn-desugar-bottom-up") shouldBe emptyList()
            cnSites("84-cn-desugar-bottom-up-authored") shouldBe emptyList()
        }

        "the top-down and bottom-up pairs ground the same concepts to the same targets" {
            fun groundings(dir: String): List<String> {
                val p = scenario(dir)
                return analyzeGroundings(p.docs, p.resolver)
                    .byConcept
                    .map { (key, list) -> "$key ${list[0].grounding.bindingKind} ${list[0].targetSymbol?.qname}" }
                    .sorted()
            }
            groundings("81-cn-desugar-top-down") shouldBe groundings("82-cn-desugar-top-down-authored")
            groundings("83-cn-desugar-bottom-up") shouldBe groundings("84-cn-desugar-bottom-up-authored")
        }

        "every message names the concept and says what to do" {
            val p = scenario("86-cn-grounding-target-kind")
            val messages =
                validateCn(p.docs, p.resolver).diagnostics.filter {
                    it.code ==
                        DiagnosticCode.CnGroundingTargetKind
                }
            messages.map { it.message } shouldContain
                "'cn2md_kind' grounds 'Brand' to a dimension or a domain — 'net_sales' is a measure"
            messages.map { it.message } shouldContain
                "'cn2er_phase' grounds 'HeldOrder' to an er entity stacked on a query (AM-2) — " +
                "'order' is an entity no query backs"
            messages.map { it.message } shouldContain "'cn2md_role' grounds a role, but 'Category' is a kind"
        }

        // ----- keys and resolution (S2.1's slot table) -----

        val er =
            "package p\nmodel er\n\n" +
                "def entity party { attributes: [ def attribute party_id { type: int, isKey: true } ] }\n"

        fun resolve(
            p: Project,
            uri: String,
            pick: (CollectedRef) -> Boolean,
        ): String? {
            val doc = p.docs.first { it.uri == uri }
            val ref = collectAllReferences(doc.definitions).first(pick)
            val ctx = ResolutionContext(doc.schemaCode, doc.namespace, null, doc.imports, doc.packageName)
            return (p.resolver.resolveReference(ref.toRef(), ctx) as? ResolutionResult.Resolved)?.symbol?.qname
        }

        "a concept keys `<pkg>.cn.concept.<Name>`, a binding by its keyword, an event's participants by label" {
            val p =
                project(
                    "er.ttrm" to er,
                    "cn.ttrm" to
                        "package p\nmodel cn\n\ndef kind Party { }\n" +
                        "def event Order { involves: [Party as buyer, Party as seller] }\n",
                    "b.ttrm" to
                        "package p\nmodel binding\n\ndef cn2er_kind party_g { kind: cn.Party, target: er.entity.party }\n",
                )
            val keys = p.symbols.all().map { it.qname }
            keys shouldContain "p.cn.concept.Party"
            keys shouldContain "p.cn.concept.Order.buyer"
            keys shouldContain "p.cn.concept.Order.seller"
            keys shouldContain "p.binding.cn2er_kind.party_g"
            p.symbols.get("p.cn.concept.Party")?.stereotype shouldBe "kind"
        }

        "a cn slot resolves among concepts only; an element ref never reaches a concept of the same name" {
            val p =
                project(
                    "er.ttrm" to er,
                    "cn.ttrm" to
                        "package p\nmodel cn\n\ndef kind party { grounds: er.entity.party }\ndef role Buyer { of: party }\n",
                    "er2.ttrm" to "package p\nmodel er\n\ndef relation r { from: party, to: party }\n",
                )
            resolve(p, "cn.ttrm") { it.path == "party" && it.cnContext } shouldBe "p.cn.concept.party"
            resolve(p, "er2.ttrm") { it.path == "party" } shouldBe "p.er.entity.party"
        }

        "the `cn.` head, a participant key, and the sugar's declaring slot (no reference)" {
            val p =
                project(
                    "er.ttrm" to
                        "package p\nmodel er\n\n" +
                        "def entity sale { attributes: [ def attribute id { type: int, isKey: true } ]," +
                        " concept { event: Sale, involves: [Party as buyer] } }\n" + er.substringAfter("model er\n"),
                    "cn.ttrm" to "package p\nmodel cn\n\ndef kind Party { grounds: er.entity.party }\n",
                    "lex.ttrm" to
                        "package p\nmodel lexicon locale en\n\ndef term party { for: cn.Party, forms: [\"party\"] }\n",
                    "b.ttrm" to
                        "package p\nmodel binding\n\ndef cn2er_event sale_g { event: Sale, target: er.entity.sale," +
                        " participants: { buyer: [] } }\n",
                )
            resolve(p, "lex.ttrm") { it.path == "cn.Party" } shouldBe "p.cn.concept.Party"
            resolve(p, "b.ttrm") { it.path == "Sale.buyer" } shouldBe "p.cn.concept.Sale.buyer"
            collectAllReferences(p.docs.first { it.uri == "er.ttrm" }.definitions).map { it.path } shouldNotContain
                "Sale"
            p.symbols.get("p.cn.concept.Sale")?.cnOrigin shouldBe GroundingOrigin.Sugar
            p.symbols.get("p.binding.cn2er_event.Sale.concept")?.cnOrigin shouldBe GroundingOrigin.Sugar
        }

        "a binding period's stored upper bounds start_hi / at_hi are er refs (⚑CN-P10)" {
            val p =
                project(
                    "er.ttrm" to er,
                    "cn.ttrm" to "package p\nmodel cn\n\ndef event Visit { period: { start: s } }\n",
                    "b.ttrm" to
                        "package p\nmodel binding\n\ndef cn2er_event visit_g { event: Visit, target: er.entity.party," +
                        " period: { start: er.entity.party.party_id, start_hi: er.entity.party.party_id," +
                        " at_hi: er.entity.party.nope } }\n",
                )
            val paths = collectAllReferences(p.docs.first { it.uri == "b.ttrm" }.definitions).map { it.path }
            paths.count { it == "er.entity.party.party_id" } shouldBe 2
            resolve(p, "b.ttrm") { it.path == "er.entity.party.nope" } shouldBe null
        }

        "an authored concept wins over the block that grounds it, whatever the document order" {
            val p =
                project(
                    "a.ttrm" to
                        "package p\nmodel er\n\ndef entity party { attributes: [ def attribute id { type: int } ], concept { kind: Party } }\n",
                    "b.ttrm" to "package p\nmodel cn\n\ndef kind Party { }\n",
                )
            p.symbols.get("p.cn.concept.Party")?.documentUri shouldBe "b.ttrm"
        }

        "soft names, md cross-references and synthesized names are no unresolved reference" {
            val p =
                project(
                    "er.ttrm" to er,
                    "cn.ttrm" to
                        "package p\nmodel cn\n\ndef event Order { qualities: [amount], period: { at: placed_on }," +
                        " grounds: er.entity.party }\n",
                    "md.ttrm" to "package p\nmodel md\n\ndef measure revenue { domain: md.Money, aggregation: sum }\n",
                )
            val v = Validator(p.symbols, p.resolver, ResolvedManifest())
            val unresolved =
                p.docs.flatMap { v.validateReferences(it) }.filter {
                    it.code ==
                        DiagnosticCode.UnresolvedReference
                }
            unresolved shouldBe emptyList()
        }

        "two cn defs of one name stay a duplicate definition; a block restating one does not" {
            val p =
                project(
                    "a.ttrm" to "package p\nmodel cn\n\ndef kind Party { }\n",
                    "b.ttrm" to "package p\nmodel cn\n\ndef kind Party { }\n",
                    "c.ttrm" to
                        "package p\nmodel er\n\ndef entity party { attributes: [ def attribute id { type: int } ], concept { kind: Party } }\n",
                )
            val v = Validator(p.symbols, p.resolver, ResolvedManifest())
            v.validateProject().count { it.code == DiagnosticCode.DuplicateDefinition } shouldBe 2
        }
    })

/** Walks up from the test working dir to find `tests/conformance/fixtures`. */
private fun locateFixtures(): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val candidate = dir.resolve("tests/conformance/fixtures")
        if (Files.isDirectory(candidate)) return candidate
        dir = dir.parent
    }
    error("could not locate tests/conformance/fixtures from ${Paths.get("").toAbsolutePath()}")
}
