// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.lexicon.CompiledLexiconHeader
import org.tatrman.ttr.lexicon.ConceptLift
import org.tatrman.ttr.lexicon.SourceTag
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.TargetFacts
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * CN-P4 S4.1 — archive **v6**: the conceptual layer in the compiled lexicon (cn contracts §5, §8.3, A-CN-2…8).
 *
 * Over the `cn/` fixture estate (`src/test/resources/cn/` — the S0.2 spike's toy in grammar 0.17, loaded off disk the
 * way `ttr-lexicon build` loads an estate), and over the plain `estate/` fixture for the "no cn content" half. Every
 * concept of the fixture is one of the cases the spike's memo §2–§3 names: direct er (`Item`, `Customer`, `Invoice`),
 * md-hop (`Sale` — the cubelet an explicit `md2er_cubelet` lands), a value kind (`Segment`, `Channel` — on their
 * attributes), ungrounded (`Supplier`, `Party`), md-grounded with the hop missing (`revenue` — a measure's hop is
 * md/hop's), phases (`DebtorCustomer`, `OverdueInvoice` inherit their bearer's landing; `TopCustomer` grounds to a
 * stacked entity).
 */
class CnArchiveV6Spec :
    FunSpec({

        val snapshotId = "sha256:" + "ab".repeat(32)
        val builtAt = "2026-10-22T00:00:00Z"

        fun resource(name: String): Path = Path.of(CnArchiveV6Spec::class.java.getResource("/$name")!!.toURI())

        /** The model as the CLI loads it: stock roles + the estate's `model/`, a package/directory mismatch tolerated. */
        fun load(root: Path): Model {
            val result =
                MetadataLoader(
                    listOf(
                        BuiltinStockSource(),
                        FileBasedSource(
                            sourceId = "repo",
                            priority = 100,
                            storage = LocalFsStorage(id = "repo", rootPath = root.resolve("model")),
                        ),
                    ),
                ).load()
            val fatal = result.errors.filter { it.category != LoadIssue.Category.PACKAGE_MISMATCH }
            withClue(fatal.joinToString("\n") { "${it.file}:${it.line} ${it.message}" }) { fatal.shouldBeEmpty() }
            return result.model!!
        }

        fun build(root: Path = resource("cn")) =
            LexiconBuild.run(root, load(root), snapshotId, builtAt, "cn-v6-spec", includeStdlib = false)

        /** A writable copy of the cn fixture, for the variants. */
        fun copyOfCn(): Path {
            val from = resource("cn")
            val to = Files.createTempDirectory("cn-v6")
            Files.walk(from).use { paths ->
                for (p in paths.toList()) {
                    val dest = to.resolve(from.relativize(p).toString())
                    if (Files.isDirectory(p)) dest.createDirectories() else Files.copy(p, dest)
                }
            }
            return to
        }

        val outcome = build()
        val lexicon = outcome.result.lexicon
        val targets = lexicon.targets

        fun concept(
            stereotype: String,
            erTarget: String? = null,
            ownerRef: String? = null,
            via: String? = null,
            ofRef: String? = null,
        ) = TargetFacts(
            objectKind = TargetFacts.CONCEPT,
            ownerRef = ownerRef,
            stereotype = stereotype,
            erTarget = erTarget,
            via = via,
            ofRef = ofRef,
        )

        val direct = TargetFacts.VIA_DIRECT
        val mdHop = TargetFacts.VIA_MD_HOP

        // ---- T1 · the v6 shape ----------------------------------------------------------------------------------------

        test("the header is v6, and the fixture estate compiles with no violation and no dangling ref") {
            lexicon.header.schemaVersion shouldBe "ttr-lexicon-compiled/v6"
            CompiledLexiconHeader.SCHEMA_VERSION shouldBe "ttr-lexicon-compiled/v6"
            outcome.violations.shouldBeEmpty()
            outcome.result.warnings
                .filter { it.code == CompileWarning.DANGLING_REF }
                .shouldBeEmpty()
        }

        test("one concept entry per concept: direct er · md-hop · value kind · ungrounded · hop missing · phase") {
            targets.filterValues { it.objectKind == TargetFacts.CONCEPT } shouldBe
                mapOf(
                    "cn.Customer" to concept("role", "er.entity.customer", "er.entity.customer", direct, "cn.Party"),
                    "cn.DebtorCustomer" to
                        concept("phase", "er.entity.customer", "er.entity.customer", direct, "cn.Customer"),
                    "cn.Invoice" to concept("event", "er.entity.invoice", "er.entity.invoice", direct),
                    "cn.Item" to concept("kind", "er.entity.item", "er.entity.item", direct),
                    "cn.OverdueInvoice" to
                        concept("phase", "er.entity.invoice", "er.entity.invoice", direct, "cn.Invoice"),
                    "cn.Party" to concept("kind"),
                    // md-grounded on the cubelet; the explicit md2er_cubelet lands it on channel_sales.
                    "cn.Sale" to concept("event", "er.entity.channel_sales", "er.entity.channel_sales", mdHop),
                    // A value kind lands on its ATTRIBUTE; ownerRef is the entity around it (A-CN-3).
                    "cn.Channel" to
                        concept("kind", "er.entity.channel_sales.channel", "er.entity.channel_sales", direct),
                    "cn.Segment" to concept("kind", "er.entity.customer.segment", "er.entity.customer", direct),
                    "cn.Supplier" to concept("kind"),
                    "cn.TopCustomer" to
                        concept("phase", "er.entity.top_customer", "er.entity.top_customer", direct, "cn.Customer"),
                    // md-grounded on a measure: `via` says md-hop even though the hop is missing — which is what tells
                    // the resolver's advisory note `md/er-unmapped` from `cn/ungrounded`.
                    "cn.revenue" to concept("quality", via = mdHop, ofRef = "cn.Sale"),
                )
        }

        test("liftsTo on er AND md element targets — each element lists the concepts grounded on it or landing on it") {
            targets.filterValues { it.liftsTo.isNotEmpty() }.mapValues { it.value.liftsTo } shouldBe
                mapOf(
                    "er.entity.channel_sales" to listOf(ConceptLift("cn.Sale", mdHop)),
                    "er.entity.channel_sales.channel" to listOf(ConceptLift("cn.Channel", direct)),
                    "er.entity.customer" to listOf(ConceptLift("cn.Customer", direct)),
                    "er.entity.customer.segment" to listOf(ConceptLift("cn.Segment", direct)),
                    "er.entity.invoice" to listOf(ConceptLift("cn.Invoice", direct)),
                    "er.entity.item" to listOf(ConceptLift("cn.Item", direct)),
                    "md.cubelet.channelSales" to listOf(ConceptLift("cn.Sale", mdHop)),
                    "md.measure.revenue" to listOf(ConceptLift("cn.revenue", mdHop)),
                )
        }

        test("an md element gets an entry for the first time — liftsTo alone, no kind; an er element keeps its facts") {
            targets.getValue("md.measure.revenue") shouldBe
                TargetFacts(objectKind = "", liftsTo = listOf(ConceptLift("cn.revenue", mdHop)))
            targets.getValue("md.cubelet.channelSales").objectKind shouldBe ""
            // The member facet and the owner are untouched beside the new field.
            targets.getValue("er.entity.customer.segment").let {
                Triple(it.objectKind, it.ownerRef, it.memberVocabulary)
            } shouldBe Triple("attribute", "er.entity.customer", true)
            // channel_sales has no term and no member vocabulary of its own: it is here because a member hit on
            // channel_sales.channel lifts from the category's OWNER too (A-CN-7), and the owner lifts to Sale.
            targets.getValue("er.entity.channel_sales").let { it.objectKind to it.reachedFrom.isNotEmpty() } shouldBe
                ("entity" to false)
        }

        test("a phase is never in any liftsTo — not an inherited landing, not its own grounding (L17-2)") {
            val phases = targets.filterValues { it.stereotype == "phase" }.keys
            phases shouldBe setOf("cn.DebtorCustomer", "cn.OverdueInvoice", "cn.TopCustomer")
            targets.values
                .flatMap { it.liftsTo }
                .map { it.qname }
                .filter { it in phases }
                .shouldBeEmpty()
            // TopCustomer grounds to top_customer itself, and still nothing lifts there.
            targets["er.entity.top_customer"]?.liftsTo.orEmpty().shouldBeEmpty()
        }

        test("a concept entry never carries members, reach, a mention facet or lifts") {
            for ((ref, facts) in targets.filterValues { it.objectKind == TargetFacts.CONCEPT }) {
                withClue(ref) {
                    facts.memberVocabulary shouldBe false
                    facts.reachedFrom.shouldBeEmpty()
                    listOf(facts.nameRef, facts.codeRef, facts.codeFormat) shouldBe listOf(null, null, null)
                    facts.liftsTo.shouldBeEmpty()
                }
            }
            lexicon.entries
                .filter { it.targetRef.startsWith("cn.") }
                .map { it.targetClass }
                .toSet() shouldBe
                setOf(TargetClass.MODEL_OBJECT)
        }

        test("concept terms compile: canonical `def term`s and the inline `lexicon { }` (no row twice)") {
            val rows = lexicon.entries.filter { it.targetRef.startsWith("cn.") }
            rows.map { Triple(it.termNormalized, it.lang, it.targetRef) } shouldContainExactlyInAnyOrder
                listOf(
                    // inline on `def role Customer` — no locale, so the authoring default
                    Triple("zákazník", "cs|en", "cn.Customer"),
                    Triple("customer", "cs|en", "cn.Customer"),
                    Triple("zákazník po splatnosti", "cs", "cn.DebtorCustomer"),
                    Triple("dlužník", "cs", "cn.DebtorCustomer"),
                    Triple("overdue customer", "en", "cn.DebtorCustomer"),
                    Triple("debtor", "en", "cn.DebtorCustomer"),
                    Triple("položka", "cs", "cn.Item"),
                    Triple("zboží", "cs", "cn.Item"),
                    Triple("item", "en", "cn.Item"),
                    Triple("segment", "cs", "cn.Segment"),
                    Triple("kanál", "cs", "cn.Channel"),
                    Triple("prodejní kanál", "cs", "cn.Channel"),
                    Triple("channel", "en", "cn.Channel"),
                    Triple("dodavatel", "cs", "cn.Supplier"),
                    Triple("supplier", "en", "cn.Supplier"),
                    Triple("tržby", "cs", "cn.revenue"),
                    Triple("revenue", "en", "cn.revenue"),
                )
            rows.map { it.sourceTag }.toSet() shouldBe setOf(SourceTag.DECLARED)
            rows.first { it.targetRef == "cn.Customer" }.provenance.file shouldBe "model/cn/shop.ttrm"
        }

        test("the default sense rides the rows, written only where it was authored") {
            val byKey = lexicon.entries.associateBy { it.termNormalized to it.targetRef }
            byKey.getValue("zákazník" to "cn.Customer").defaultSense shouldBe true
            byKey.getValue("customer" to "cn.Customer").defaultSense shouldBe true
            byKey.getValue("položka" to "cn.Item").sensePriority shouldBe 1.0
            byKey.getValue("dlužník" to "cn.DebtorCustomer").let { it.defaultSense to it.sensePriority } shouldBe
                (false to null)
            val json = lexicon.toJson()
            Regex("\"defaultSense\"").findAll(json).count() shouldBe 2
            Regex("\"sensePriority\"").findAll(json).count() shouldBe 2
        }

        test("an archive with no cn content re-encodes as v6 — its entry table, and the lexicon hash, as in v5") {
            val estate = build(resource("estate")).result.lexicon
            estate.header.schemaVersion shouldBe "ttr-lexicon-compiled/v6"
            // Pinned from the v5 build of this fixture (same inputs, `includeStdlib = false`) before S4.1 touched the
            // code: the RV-39 lexicon hash is taken over the entries, and they did not move.
            estate.contentHash shouldBe "sha256:6aa357dc7cec886c28bbad96f0c7d49d27fdac4085ffb6e7bd77c83835fdb2dd"
            estate.targets.values.none { it.objectKind == TargetFacts.CONCEPT || it.liftsTo.isNotEmpty() } shouldBe true
            estate.toJson() shouldNotContain "\"defaultSense\""
        }

        test(
            "the lexicon hash covers the entries only: a concept-facts-only change keeps it, and moves the archive id",
        ) {
            val variant = copyOfCn()
            val binding = variant.resolve("model/binding/shop.ttrm")
            binding.writeText(binding.readText().replace(Regex("def cn2md_event sale_g \\{[^}]*\\}\n"), ""))
            val moved = build(variant)
            moved.result.lexicon.targets
                .getValue("cn.Sale")
                .via shouldBe null
            moved.result.lexicon.targets shouldNotContainKey "md.cubelet.channelSales"
            moved.result.lexicon.entries shouldBe lexicon.entries
            moved.result.lexicon.contentHash shouldBe lexicon.contentHash
            // ⇒ S4.2's caches key on the archive id (or its ETag), never on the lexicon hash (spike memo §1).
            moved.packed.id shouldNotBe outcome.packed.id
        }

        // ---- T4 · R-cn-1 at compile time ------------------------------------------------------------------------------

        test("RG-LEXC-004 is silent where the concept grounds to the element, and kept on divergence") {
            val collisions = outcome.result.warnings.filter { it.code == CompileWarning.FORM_COLLISION }
            // "zákazník"/"customer" (cn.Customer vs customer's label), "položka" (cn.Item vs item's label) — direct;
            // "tržby" (cn.revenue vs md.measure.revenue — the measure it is grounded on, the hop missing or not): all
            // one thing named twice. "kanál": cn.Channel lands on channel_sales.CHANNEL, and channel_sales lifts to
            // Sale — two things, so both declared rows still hear about it.
            collisions.map { it.message.substringBefore(" collides") } shouldContainExactlyInAnyOrder
                listOf(
                    "term \"kanál\" (for: cn.Channel)",
                    "term \"kanál\" (for: er.entity.channel_sales)",
                )
        }

        // ---- T5 · a bare term never defaults to a phase ---------------------------------------------------------------

        test(
            "a phase's default is dropped where a concept up its of: chain shares the form; a phase-only term keeps it",
        ) {
            val variant = copyOfCn()
            variant.resolve("model/lexicon/cs/terms.ttrm").appendText(
                """

                def term debtor_bare_cs { for: cn.DebtorCustomer, forms: ["zákazník"], default: true }
                def term debtor_alone_cs { for: cn.DebtorCustomer, forms: ["neplatič"], default: true }
                """.trimIndent(),
            )
            val r = build(variant).result
            val byKey = r.lexicon.entries.associateBy { it.termNormalized to it.targetRef }
            // cs "zákazník" on the phase meets the bearer's inline cs|en "zákazník": the default goes.
            byKey.getValue("zákazník" to "cn.DebtorCustomer").defaultSense shouldBe false
            byKey.getValue("zákazník" to "cn.Customer").defaultSense shouldBe true
            byKey.getValue("neplatič" to "cn.DebtorCustomer").defaultSense shouldBe true
            r.warnings.filter { it.code == CompileWarning.PHASE_AS_DEFAULT }.map { it.message } shouldBe
                listOf(
                    "term \"zákazník\" (for: cn.DebtorCustomer) is declared the default sense of a phase, and " +
                        "cn.Customer up its `of:` chain also names it — a bare term never defaults to a phase " +
                        "(AM-36); the archive drops this default",
                )
        }

        // ---- T7 · the fixture archive ---------------------------------------------------------------------------------

        test("the fixture's v6 lexicon is byte-stable — `expected/lexicon.json`, the S4.3 / S4.4 fixture") {
            val expected = resource("cn").resolve("expected/lexicon.json")
            val actual = lexicon.toJson()
            if (!expected.exists() || expected.readText() != actual) {
                val out = Path.of("build/cn-v6-actual").createDirectories().resolve("lexicon.json")
                out.writeText(actual)
                error("expected/lexicon.json differs from the build — review $out and copy it over")
            }
            build().result.lexicon.toJson() shouldBe actual
            targets shouldContainKey "cn.Customer"
        }
    })
