// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.AttributeDef
import org.tatrman.ttr.parser.model.ConceptBindingDef
import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.parser.model.ConceptProperty
import org.tatrman.ttr.parser.model.ConceptSugar
import org.tatrman.ttr.parser.model.CubeletDef
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.MeasureDef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Grammar 0.17 — the conceptual model survives write → reparse (CN-P2 S2.4 T6). `render∘parse` is a fixed point,
 * and every cn property comes back as it was written (positions aside) — its value, an expression block's text
 * and escapes. The formatter keeps every cn def the writer prints as it is: `cn-writer-golden.ttrm` is pinned here
 * and passed through `modeler-fmt` unchanged — blank lines between defs aside — by `packages/format`'s
 * `cn-writer-golden.test.ts`.
 *
 * Out of scope (pre-existing, not cn's — ttr-core#227): the writer renders no md `domain` / `dimension` and no
 * lexicon entry, so their `concept { }` and fixture 78 cannot round-trip.
 */
class CnRoundTripSpec :
    StringSpec({

        fun roundTrip(src: String): Pair<ParseResult, String> {
            val r1 = TtrLoader.parseString(src)
            r1.errors shouldBe emptyList()
            val text1 = TtrRenderer.render(r1)
            val r2 = TtrLoader.parseString(text1)
            r2.errors shouldBe emptyList()
            TtrRenderer.render(r2) shouldBe text1
            return r2 to text1
        }

        /** A cn body's properties with every position dropped, in a stable order (the writer reorders them). */
        fun props(properties: List<ConceptProperty>): List<String> = properties.map { positionless(it) }.sorted()

        fun cnBodies(defs: List<Definition>): Map<String, List<String>> =
            defs
                .mapNotNull {
                    when (it) {
                        is ConceptDef -> "${it.stereotype} ${it.name}" to props(it.body.properties)
                        is ConceptBindingDef -> "${it.bindingKind} ${it.name}" to props(it.body.properties)
                        else -> null
                    }
                }.toMap()

        fun sugars(defs: List<Definition>): Map<String, List<String>> {
            val out = mutableMapOf<String, List<String>>()

            fun add(
                key: String,
                s: ConceptSugar?,
            ) {
                if (s != null) out[key] = props(s.body.properties)
            }
            for (d in defs) {
                when (d) {
                    is EntityDef -> {
                        add("entity ${d.name}", d.concept)
                        d.attributes.forEach { a -> add("attribute ${d.name}.${a.name}", a.concept) }
                    }
                    is AttributeDef -> add("attribute ${d.name}", d.concept)
                    is MeasureDef -> add("measure ${d.name}", d.concept)
                    is CubeletDef -> add("cubelet ${d.name}", d.concept)
                    else -> {}
                }
            }
            return out
        }

        listOf("75-cn-concepts.ttrm", "76-cn-bindings.ttrm").forEach { name ->
            "conformance fixture $name round-trips, every cn property as written" {
                val src = Files.readString(locate("tests/conformance/fixtures").resolve(name))
                val (r, _) = roundTrip(src)
                cnBodies(r.definitions) shouldBe cnBodies(TtrLoader.parseString(src).definitions)
            }
        }

        "fixture 77 round-trips the sugar of every host the writer renders (entity, attribute, measure, cubelet)" {
            val src = Files.readString(locate("tests/conformance/fixtures").resolve("77-cn-sugar.ttrm"))
            val (r, text) = roundTrip(src)
            val before = sugars(TtrLoader.parseString(src).definitions)
            sugars(r.definitions) shouldBe before
            before.keys shouldBe
                setOf(
                    "cubelet storeReturns",
                    "measure returnAmount",
                    "entity invoice",
                    "attribute invoice.status",
                    "entity order",
                    "entity customer",
                    "attribute channel_sales.channel",
                )
            // The contracts' spelling — no `:` after `concept`; the stereotype ref leads.
            text shouldContain "concept { role: Customer, event: Order }"
            text shouldNotContain "concept:"
        }

        "a concept def prints in the canonical order, on one line" {
            val (_, text) =
                roundTrip(
                    "model cn\ndef quality amount { grounds: er.entity.sale.amount, description: \"The amount.\", of: Sale }",
                )
            text shouldBe
                "model cn\n\ndef quality amount { description: \"The amount.\", of: Sale, grounds: er.entity.sale.amount }\n"
        }

        "every concept property and every binding property round-trips" {
            val (r, text) = roundTrip(golden())
            cnBodies(r.definitions) shouldBe cnBodies(TtrLoader.parseString(GOLDEN_SOURCE).definitions)
            text shouldBe golden()
        }

        "an inline lexicon block keeps its sense fields (cn contracts §1.5)" {
            val (r, text) =
                roundTrip(
                    "model cn\ndef kind Item { lexicon: { terms: [\"product\"], default: true, sense_priority: 2 } }",
                )
            text shouldContain "lexicon: { terms: [\"product\"], default: true, sense_priority: 2 }"
            val block =
                r.definitions
                    .filterIsInstance<ConceptDef>()
                    .single()
                    .body
                    .property<ConceptProperty.Lexicon>()!!
                    .block
            block.default shouldBe true
            block.sensePriority shouldBe 2.0
        }

        "the golden is what the writer renders from its source" {
            TtrRenderer.render(TtrLoader.parseString(GOLDEN_SOURCE)) shouldBe golden()
        }

        "expression blocks re-parse byte-exact, escapes kept, in all three forms" {
            val src =
                "model cn\n" +
                    "def phase Held { of: Order, definition: \"state = \\\"held\\\" and note = 'a\\\\b'\" }\n" +
                    "def phase Open { of: Order, definition: \"\"\"\n" +
                    "        state in ('placed', 'held')\n          and open\n    \"\"\" }\n" +
                    "def phase Late { of: Order, definition: \"\"\"pl\n        this.period.at > today()\n    \"\"\" }\n"
            val r1 = TtrLoader.parseString(src)
            r1.errors shouldBe emptyList()
            val (r2, _) = roundTrip(src)

            fun blocks(r: ParseResult) =
                r.definitions.filterIsInstance<ConceptDef>().associate { d ->
                    val b =
                        d.body.properties
                            .filterIsInstance<ConceptProperty.DefinitionBlock>()
                            .single()
                            .block
                    d.name to Triple(b.form, b.text, b.escapes)
                }
            blocks(r2) shouldBe blocks(r1)
            blocks(r1).getValue("Held").third.size shouldBe 3
        }
    })

/** The golden's source: one of each concept stereotype and binding kind, every property, the broken layouts. */
private val GOLDEN_SOURCE =
    """
    package hartland.stores
    model cn

    def relation_type supplies { description: "One party supplies another." }
    def kind Party { label: { en: "Party", cs: "Strana" }, abstract: true, tags: ["core"] }
    def kind Item {
        lexicon: { terms: ["product", "item"] },
        relations: [ { type: supplies, to: Warehouse } ],
        hierarchy: { closure: er.entity.category_closure, ancestor: ancestor_code, descendant: descendant_code },
        grounds: er.entity.item,
        sources: ["doc://hartland/handbook#products"],
        is_a: Party,
        description: { en: "A product.", cs: "Produkt." },
        qualities: [category, brand]
    }
    def kind HomeItem { is_a: Item, members: ""${'"'}pl
        descendants(Item, 'H10-H19')
    ""${'"'} }
    def kind Warehouse { }
    def role Customer { of: Party, event: Order, grounds: er.entity.customer }
    def event Order {
        extent: durative,
        involves: [Customer, Warehouse as fulfilling],
        period: { start: placed_date, end: delivered_date, end_open: open },
        grounds: er.entity.order
    }
    def event Transfer { involves: [Item, Warehouse as from, Warehouse as to, Customer as requester], period: { at: shipped_at } }
    def quality category { of: Item, value_space: Party, imprecision: { unit: day }, valued_state: true }
    def quality open_amount { of: Order, basis: current_state, definition: "amount - paid", custom_key: 3 }
    def phase Pending { of: Order, definition: ""${'"'}
        state in ('placed', 'held')
    ""${'"'} }

    def cn2er_event order_g {
        participants: { customer: [order_customer], fulfilling: [order_warehouse] },
        event: Order,
        target: er.entity.order,
        description: "The order grounding.",
        period: { start: er.entity.order.placed_date }
    }
    def cn2md_kind item_g { kind: cn.Item, target: md.Product, tags: ["md"] }
    def cn2er_quality state_g { quality: state, target: er.entity.order.state, filter: "state = 'held'" }
    def cn2er_kind category_g {
        kind: ProductCategory,
        target: er.entity.category_code,
        hierarchy: { closure: er.entity.category_closure, ancestor: ancestor_code, descendant: descendant_code }
    }
    def cn2db_concept legacy_g { concept: Warehouse, target: { table: db.dbo.warehouse }, via: [r1, r2] }
    """.trimIndent() + "\n"

private fun golden(): String =
    Files.readString(locate("packages/kotlin/ttr-writer/src/test/resources/cn").resolve("cn-writer-golden.ttrm"))

/**
 * A value's text with its positions dropped — every `SourceLocation` (printed `file:line:column`), an expression
 * block's line offsets and indent.
 */
private fun positionless(x: Any): String =
    x
        .toString()
        .replace(Regex("(source|textSource)=[^,()\\[\\]]*:-?\\d+:-?\\d+"), "$1=_")
        .replace(Regex("lineOffsets=\\[[^]]*]"), "lineOffsets=_")
        .replace(Regex("indentWidth=\\d+"), "indentWidth=_")

private fun locate(rel: String): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val candidate = dir.resolve(rel)
        if (Files.isDirectory(candidate)) return candidate
        dir = dir.parent
    }
    error("could not locate $rel")
}
