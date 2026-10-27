// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        "event-bounded episodes use the subject stream and first subsequent termination" {
            val result = service.definitionRender(ref("OpenTicket"), Layer.ER, "postgresql", RenderOptions())
            result.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            result.text.trim() shouldBe
                Files.readString(GoldenCases.root.resolve("things-in-time/open-ticket.sql")).trim()
            result.text shouldContain "LEAD("
            result.text shouldContain "MIN("
            result.text shouldContain "terminations"
            result.text shouldContain "FROM finished"
            result.text shouldContain "{as_of}"
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
