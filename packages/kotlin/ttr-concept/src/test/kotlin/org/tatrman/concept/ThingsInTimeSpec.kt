// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.assertion.v1.Assertion
import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

class ThingsInTimeSpec :
    StringSpec({
        val world = GoldenWorlds["time"]
        val service = CnConceptService(world.cn)

        fun ref(name: String) = ConceptRef.newBuilder().setQname("cn.$name").build()

        "a subkind inherits its bearer and materializes without parameters" {
            val web = world.cn.cn.byName("WebTicket")!!
            web.erTarget shouldBe "er.entity.ticket"
            val result = service.definitionRender(ref("WebTicket"), Layer.ER, "postgresql", RenderOptions())
            result.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            result.text.trim() shouldBe
                Files.readString(GoldenCases.root.resolve("things-in-time/web-ticket.sql")).trim()
            service.materialize(ref("WebTicket"), "postgresql").diagnostics shouldBe emptyList()
        }

        "an episode table renders a subject predicate at the evaluation instant" {
            val result = service.definitionRender(ref("OutOfService"), Layer.ER, "postgresql", RenderOptions())
            result.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            result.text shouldContain "EXISTS"
            result.text shouldContain "outage.warehouse = warehouse.id"
            result.text shouldContain "outage.since <= {as_of}"
            result.text shouldContain "outage.until IS NULL"
        }

        "event-bounded episodes: a start at or before the as-of with no termination in [start, as-of] (⚑A)" {
            val result = service.definitionRender(ref("OpenTicket"), Layer.ER, "postgresql", RenderOptions())
            result.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            result.text.trim() shouldBe
                Files.readString(GoldenCases.root.resolve("things-in-time/open-ticket.sql")).trim()
            // review-175 F4/F13: `>=` closes a same-instant termination; no window, no grouped derived table
            result.text shouldContain "NOT EXISTS"
            result.text shouldContain "terminations.\"at\" >= starts.\"at\""
            result.text shouldNotContain "LEAD("
            result.text shouldNotContain "GROUP BY"
            result.text shouldContain "FROM finished"
        }

        "a valued-state call is the existing validity lookup with a default as-of" {
            val result = service.definitionRender(ref("quoted_price"), Layer.ER, "postgresql", RenderOptions())
            result.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            result.text.trim() shouldBe
                Files.readString(GoldenCases.root.resolve("things-in-time/quoted-price.sql")).trim()
            result.text shouldContain "price.channel = 'web'"
            result.text shouldContain "price.since <= {as_of}"
            result.text shouldContain "price.until IS NULL"
            world.cn.definitionDiagnostics().filter {
                it.subject == "cn.quoted_price" && it.severity == Severity.ERROR
            } shouldBe
                emptyList()
        }

        "state assertions name the phase, its subject and the claimed as-of" {
            val source = Files.readString(GoldenCases.root.resolve("things-in-time/state-assertion.json"))
            val assertion = Assertion.newBuilder().also { JsonFormat.parser().merge(source, it) }.build()
            val result = service.assertionLower(assertion)
            result.diagnostics shouldBe emptyList()
            val expected =
                Json
                    .parseToJsonElement(
                        Files.readString(GoldenCases.root.resolve("things-in-time/state-row.json")),
                    ).jsonObject
                    .mapValues { (_, value) -> value.jsonPrimitive.content.takeUnless { it == "null" } }
            result.rows.single().values shouldBe expected
            service.assertionValidate(assertion.toBuilder().clearAssertedAsOf().build()).map { it.slug } shouldBe
                listOf("cn/missing-as-of")
        }

        "a projection and bounded phase each expose one canonical source" {
            val projected = world.cn.cn.byName("list_price")!!
            projected.grounding!!.projection shouldBe projected.projection
            projected.grade.name shouldBe "ER_GROUNDED"
            world.cn.cn
                .byName("OpenTicket")!!
                .grounding!!
                .bounds!!
                .from.name shouldBe "Started"
            world.cn.cn
                .byName("OutOfService")!!
                .grounding!!
                .episodes shouldBe "er.entity.outage"
        }

        "new SQL oracles translate to PostgreSQL and MSSQL without a live environment" {
            for (name in listOf("WebTicket", "OutOfService", "OpenTicket", "quoted_price")) {
                val sql = service.definitionRender(ref(name), Layer.ER, "postgresql", RenderOptions()).text
                for (dialect in listOf(
                    org.tatrman.translate.v1.SqlDialect.POSTGRESQL,
                    org.tatrman.translate.v1.SqlDialect.MSSQL,
                )) {
                    ErSql.unparse(world.cn.model, sql, dialect).first.isNotBlank() shouldBe true
                }
            }
        }

        "a subkind of the carrying event retains the MD reverse-hop predicate" {
            val source =
                Files
                    .readString(GoldenWorlds["channel-reverse"].dir.resolve("cn.ttrm"))
                    .replace("def phase WebSale", "def subkind WebSale")
            val model = StatementLowerSpec.world("channel-reverse", mapOf("cn.ttrm" to source))
            val result =
                CnConceptService(
                    model,
                ).definitionRender(ref("WebSale"), Layer.MD, "postgresql", RenderOptions())
            result.diagnostics shouldBe emptyList()
            result.text shouldBe "channel.code = 'web'"
        }

        "a kind row with validity lowers its interval on the same row" {
            val model =
                StatementLowerSpec.world(
                    "time",
                    mapOf(
                        "validity.ttrm" to
                            """
                            model cn
                            def kind ValidTicket { grounds: er.entity.ticket, period: { start: created, end: closed, end_open: open } }
                            """.trimIndent(),
                    ),
                )
            val assertion =
                Assertion
                    .newBuilder()
                    .also {
                        JsonFormat.parser().merge(
                            """
                            { "concept": { "qname": "cn.ValidTicket" }, "identity": { "new": true },
                              "period": { "start": { "value": "2026-10-12", "granularity": "DAY" }, "endState": "END_OPEN" } }
                            """.trimIndent(),
                            it,
                        )
                    }.build()
            val result = CnConceptService(model).assertionLower(assertion)
            result.diagnostics shouldBe emptyList()
            result.rows.single().values shouldBe mapOf("created" to "2026-10-12", "closed" to null)
        }

        "episodes support context and explicitly refuse the MD lane" {
            val result =
                service.definitionRender(
                    ref("OutOfService"),
                    Layer.ER,
                    "postgresql",
                    RenderOptions(mode = RenderMode.CONTEXT),
                )
            result.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            result.text shouldContain "outage.warehouse = warehouse.id"
            for (name in listOf("OutOfService", "OpenTicket", "list_price")) {
                val refused = service.definitionRender(ref(name), Layer.MD, "postgresql", RenderOptions())
                refused.text shouldBe ""
                refused.diagnostics.single().message shouldContain "G-MD-1"
            }
        }

        "a parametric subkind reports its lint when materialization is requested" {
            val source = "model cn\ndef subkind TemporalTicket { of: Ticket, definition: \"\"\"created < :as_of\"\"\" }"
            val model = StatementLowerSpec.world("time", mapOf("invalid.ttrm" to source))
            CnConceptService(
                model,
            ).materialize(ref("TemporalTicket"), "postgresql").diagnostics.map { it.slug } shouldBe
                listOf("cn/subkind-temporal-definition")
        }

        "Hartland hygiene grades the new families through one grounding source" {
            val names =
                setOf(
                    "Invoice",
                    "Order",
                    "ListPrice",
                    "PriceAgreement",
                    "OpenOrder",
                    "OutOfService",
                    "item_list_price",
                    "agreed_price",
                    "WebSale",
                )
            val rows =
                CnHygiene.report(GoldenWorlds["hartland"].cn).concepts.filter {
                    it.qname.removePrefix("cn.") in
                        names
                }
            rows.flatMap { it.danglingGroundings } shouldBe emptyList()
            val actual = rows.joinToString("\n") { "${it.qname} ${it.stereotype} ${it.grade} ${it.erTarget}" }
            actual shouldBe Files.readString(GoldenCases.root.resolve("things-in-time/hartland-hygiene.txt")).trim()
            // review-175: a grade proves nothing about rendering — every graded family must also render on E-R; a
            // relator kind has no definition of its own, so it renders as the existential over it from a participant
            val q = "\"\"\""
            val existentials =
                "model cn\n" +
                    "def phase HasOrder { of: Customer, definition: ${q}exists Order involving this$q }\n" +
                    "def phase HasListPrice { of: Item, definition: ${q}exists ListPrice involving this$q }\n" +
                    "def phase HasAgreement { of: Customer," +
                    " definition: ${q}exists PriceAgreement involving this$q }\n" +
                    "def quality listed { of: Item, definition: ${q}this.item_list_price(channel: 'web')$q }\n" +
                    "def quality line_agreed { of: OrderLine, definition: ${q}this.order.customer.agreed_price(item: this.item)$q }\n"
            val hartland =
                CnConceptService(StatementLowerSpec.world("hartland", mapOf("review175.ttrm" to existentials)))
            val relators =
                mapOf(
                    "Order" to "HasOrder",
                    "ListPrice" to "HasListPrice",
                    "PriceAgreement" to "HasAgreement",
                    "item_list_price" to "listed",
                    "agreed_price" to "line_agreed",
                )
            for (name in names) {
                val rendered =
                    hartland.definitionRender(ref(relators[name] ?: name), Layer.ER, "postgresql", RenderOptions())
                withClue(name) {
                    rendered.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
                    rendered.text.isNotBlank() shouldBe true
                }
            }
        }

        // ---- review-175 ------------------------------------------------------------------------------------------

        "F1: Hartland's OpenOrder renders over the order row itself — `order: []` takes no relation hop" {
            val hartland = CnConceptService(GoldenWorlds["hartland"].cn)
            val result = hartland.definitionRender(ref("OpenOrder"), Layer.ER, "postgresql", RenderOptions())
            result.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            result.text shouldContain "NOT EXISTS"
            result.text shouldContain "placed_date"
            result.text shouldContain "delivered_date"
            result.text shouldNotContain "JOIN"
        }

        "F1: `exists` and `count` over an event whose participant is its own row" {
            val extra =
                "model cn\n" +
                    "def phase Placed { of: Order, definition: \"\"\"exists OrderPlaced involving this\"\"\" }\n" +
                    "def quality placements { of: Order, definition: \"\"\"count(OrderPlaced involving this)\"\"\" }\n"
            val model = StatementLowerSpec.world("hartland", mapOf("review175.ttrm" to extra))
            val s = CnConceptService(model)
            for (name in listOf("Placed", "placements")) {
                val result = s.definitionRender(ref(name), Layer.ER, "postgresql", RenderOptions())
                withClue(name) {
                    result.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
                    result.text shouldContain "\"order\""
                }
            }
        }

        "F2: a `this.` projection in an inner scope reads the bearer; an unknown one refuses, not a SQL function" {
            val extra =
                "model cn\n" +
                    "def phase PricedWeb { of: Item," +
                    " definition: \"\"\"exists ListPrice involving this" +
                    " where amount >= this.list_price(channel: 'web')\"\"\" }\n" +
                    "def phase NopeWeb { of: Item," +
                    " definition: \"\"\"exists ListPrice involving this" +
                    " where amount >= this.nope(channel: 'web')\"\"\" }\n" +
                    "def phase Repriced { of: ListPrice, definition: \"\"\"exists ListPrice involving this.item as item where this.item.list_price(channel: 'web') > 0\"\"\" }\n"
            val model = StatementLowerSpec.world("time", mapOf("review175.ttrm" to extra))
            val s = CnConceptService(model)
            val priced = s.definitionRender(ref("PricedWeb"), Layer.ER, "postgresql", RenderOptions())
            priced.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            priced.text shouldNotContain "LIST_PRICE("
            // the lookup is correlated to the OUTER item, on the web channel, at the as-of
            priced.text shouldContain "LEFT JOIN price"
            priced.text shouldContain "channel = 'web'"
            // the inner row can walk `item` too: `this.item` still reads the OUTER price's item
            val outer = s.definitionRender(ref("Repriced"), Layer.ER, "postgresql", RenderOptions())
            outer.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            outer.text shouldContain "LEFT JOIN price price_3 ON price_3.item = price.item AND"
            outer.text shouldNotContain "price_3.item = price_2.item"
            val nope = s.definitionRender(ref("NopeWeb"), Layer.ER, "postgresql", RenderOptions())
            nope.text shouldNotContain "NOPE("
            nope.diagnostics.map { it.message }.single { "names no projected quality" in it }
            model.definitionDiagnostics().filter { it.subject == "cn.NopeWeb" }.map { it.message } shouldContainElement
                "`this.nope(…)` names no projected quality (DC-13) in scope"
        }

        "F3 + F5: an authored episode label joins its relation; an ambiguous value-kind participant refuses" {
            val extra =
                "model cn\n" +
                    "def phase SiteDown { of: Warehouse, involves: [Warehouse as site], qualities: [site_note] }\n" +
                    "def quality site_note { of: SiteDown }\n" +
                    "def kind Quote { involves: [Item, Channel], qualities: [first_channel, second_channel] }\n" +
                    "def quality first_channel { of: Quote, value_space: Channel }\n" +
                    "def quality second_channel { of: Quote, value_space: Channel }\n" +
                    "def phase WebQuoted { of: Item, definition: \"\"\"exists Quote involving this where channel = 'web'\"\"\" }\n"
            val binding =
                "model binding\n" +
                    "def cn2er_phase site_down { phase: cn.SiteDown, episodes: er.entity.outage," +
                    " period: { start: er.entity.outage.since, end: er.entity.outage.until, end_open: open }," +
                    " participants: { site: [er.relation.outage_warehouse] } }\n" +
                    "def cn2er_quality site_note_g { quality: cn.site_note, target: er.entity.outage.reason }\n" +
                    "def cn2er_kind quote_g { kind: cn.Quote, target: er.entity.price," +
                    " participants: { item: [er.relation.price_item] } }\n" +
                    "def cn2er_quality first_g { quality: cn.first_channel, target: er.entity.price.channel }\n" +
                    "def cn2er_quality second_g { quality: cn.second_channel, target: er.entity.price.id }\n"
            val model =
                StatementLowerSpec.world(
                    "time",
                    mapOf("review175.ttrm" to extra, "review175-b.ttrm" to binding),
                )
            val s = CnConceptService(model)
            val down = s.definitionRender(ref("SiteDown"), Layer.ER, "postgresql", RenderOptions())
            down.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            down.text shouldContain "outage.warehouse = warehouse.id"
            down.text shouldNotContain "outage.reason ="
            val quoted = s.definitionRender(ref("WebQuoted"), Layer.ER, "postgresql", RenderOptions())
            quoted.text shouldBe ""
            quoted.diagnostics.single().message shouldContain "cn/participant-path-broken"
        }

        "R2: a dotted call ending in a built-in is no built-in — it refuses as a projection read and renders no SQL" {
            val extra =
                "model cn\n" +
                    "def phase RecentT { of: Ticket, definition: \"\"\"this.within(created, last: days(7))\"\"\" }\n" +
                    "def phase TyposT { of: Ticket, definition: \"\"\"this.tckt.within(created, last: days(7))\"\"\" }\n"
            val model = StatementLowerSpec.world("time", mapOf("review175-r2.ttrm" to extra))
            val s = CnConceptService(model)
            for (name in listOf("RecentT", "TyposT")) {
                withClue(name) {
                    val r = s.definitionRender(ref(name), Layer.ER, "postgresql", RenderOptions())
                    r.text shouldBe ""
                    r.diagnostics.map { it.message }.single { "names no projected quality (DC-13) in scope" in it }
                }
            }
        }

        "R3: a bearer that involves its own type leaves the episode's subject to the phase's own participant" {
            val source =
                Files
                    .readString(world.dir.resolve("cn.ttrm"))
                    .replace(
                        "def kind Warehouse { grounds: er.entity.warehouse }",
                        "def kind Warehouse { grounds: er.entity.warehouse, involves: [Warehouse as parent] }",
                    ).replace(
                        "def kind Ticket { grounds: er.entity.ticket,",
                        "def kind Ticket { grounds: er.entity.ticket, involves: [Ticket as parent],",
                    )
            val selfish = CnConceptService(StatementLowerSpec.world("time", mapOf("cn.ttrm" to source)))
            for (name in listOf("OutOfService", "OpenTicket")) {
                withClue(name) {
                    val r = selfish.definitionRender(ref(name), Layer.ER, "postgresql", RenderOptions())
                    r.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
                    r.text shouldBe service.definitionRender(ref(name), Layer.ER, "postgresql", RenderOptions()).text
                }
            }
        }

        "F12: a stacked-entity subkind is a subset test; a binding-only period phase is an episode table" {
            val extra =
                "model er\n" +
                    "def entity flagged { binding: { target: { table: db.dbo.table.flagged } }, attributes: [def attribute id { type: text, isKey: true }, def attribute since { type: date }, def attribute until { type: date, optional: true }] }\n"
            val cn =
                "model cn\n" +
                    "def subkind Flagged { of: Ticket, grounds: er.entity.flagged," +
                    " period: { start: since, end: until, end_open: open } }\n" +
                    "def phase Watched { of: Ticket, definition: \"\"\"Flagged\"\"\" }\n"
            val model = StatementLowerSpec.world("time", mapOf("review175-er.ttrm" to extra, "review175.ttrm" to cn))
            val watched =
                CnConceptService(
                    model,
                ).definitionRender(ref("Watched"), Layer.ER, "postgresql", RenderOptions())
            watched.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            watched.text shouldContain "FROM flagged"
            watched.text shouldContain "flagged.id = ticket.id"
            watched.text shouldNotContain "{as_of}"
            // the period written on the binding only: the same episode table as the concept's own `grounds:` + period
            val source =
                Files
                    .readString(world.dir.resolve("cn.ttrm"))
                    .replace(
                        "grounds: er.entity.outage, period: { start: since, end: until, end_open: open }",
                        "involves: [Warehouse]",
                    )
            val bindings =
                Files.readString(world.dir.resolve("bindings.ttrm")) +
                    "\ndef cn2er_phase outage_g { phase: cn.OutOfService, target: er.entity.outage, period: { start: er.entity.outage.since, end: er.entity.outage.until, end_open: open }, participants: { warehouse: [er.relation.outage_warehouse] } }\n"
            val bound = StatementLowerSpec.world("time", mapOf("cn.ttrm" to source, "bindings.ttrm" to bindings))
            bound.cn
                .byName("OutOfService")!!
                .grounding!!
                .episodes shouldBe "er.entity.outage"
            CnConceptService(
                bound,
            ).definitionRender(ref("OutOfService"), Layer.ER, "postgresql", RenderOptions()).text shouldBe
                service.definitionRender(ref("OutOfService"), Layer.ER, "postgresql", RenderOptions()).text
            val words = ExplainWords.of("en")!!
            DefinitionProse(bound, words, ExplainLabels(bound, words), java.time.LocalDate.of(2026, 10, 12))
                .of(bound.cn.byName("OutOfService")!!)!! shouldContain "during an episode"
        }

        "canonical bindings and top-down temporal sources render identically" {
            val source =
                Files
                    .readString(world.dir.resolve("cn.ttrm"))
                    .replace("from: Started, until: Finished", "abstract: true")
                    .replace(
                        "grounds: er.entity.outage, period: { start: since, end: until, end_open: open }",
                        "abstract: true",
                    ).replace("per: Channel, from: ListPrice.amount", "abstract: true")
            val bindings =
                Files.readString(world.dir.resolve("bindings.ttrm")) +
                    """
            
def cn2er_phase bounded { phase: cn.OpenTicket, bounds: { from: cn.Started, until: cn.Finished } }
            def cn2er_phase episodes { phase: cn.OutOfService, episodes: er.entity.outage, period: { start: er.entity.outage.since, end: er.entity.outage.until, end_open: open } }
            def cn2er_quality projected { quality: cn.list_price, via: ListPrice.amount, per: Channel }
                    """.trimIndent()
            val canonical = StatementLowerSpec.world("time", mapOf("cn.ttrm" to source, "bindings.ttrm" to bindings))
            val other = CnConceptService(canonical)
            for (name in listOf("OpenTicket", "OutOfService", "quoted_price")) {
                val result = other.definitionRender(ref(name), Layer.ER, "postgresql", RenderOptions())
                result.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
                result.text shouldBe service.definitionRender(ref(name), Layer.ER, "postgresql", RenderOptions()).text
            }
        }

        "the temporal families have deterministic table-free English and Czech prose" {
            for (locale in listOf("en", "cs")) {
                val words = ExplainWords.of(locale)!!
                val labels = ExplainLabels(world.cn, words)
                val prose = DefinitionProse(world.cn, words, labels, java.time.LocalDate.of(2026, 10, 12))
                prose.of(world.cn.cn.byName("WebTicket")!!)!! shouldContain if (locale == "en") "whose" else "jehož"
                prose.of(world.cn.cn.byName("OutOfService")!!)!! shouldContain
                    if (locale == "en") "during an episode" else "během epizody"
                prose.of(world.cn.cn.byName("quoted_price")!!)!! shouldContain
                    if (locale == "en") "list price" else "list price"
            }
        }
    })
