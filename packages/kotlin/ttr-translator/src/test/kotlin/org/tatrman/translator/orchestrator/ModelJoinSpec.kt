// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import com.google.protobuf.TextFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.joiner.JoinerMessages
import org.tatrman.translator.joiner.JoinerWarning

/**
 * MJ (model joins) — end-to-end acceptance of the corpus in project `model-joins/plan/plan.md §4`.
 *
 * P0·S1 baseline (RED): H1–H3 fail at Calcite validation with `… join requires a condition`; H10 (comma
 * form) is GREEN already and pins today's plan bytes.
 */
class ModelJoinSpec :
    StringSpec({
        val translator = Translator(HartlandJoinModel.handle())

        fun mssql(sql: String): TranslateResult =
            translator.translate(
                source = sql,
                sourceLanguage = Language.SQL,
                targetLanguage = Language.SQL,
                targetSchema = SchemaCode.DB,
                targetDialect = SqlDialect.MSSQL,
                sourceSchema = SchemaCode.ER,
            )

        "H1 — the hero chain resolves to three model conditions and MSSQL" {
            val r = mssql(HartlandJoinModel.H1_HERO).shouldBeInstanceOf<TranslateResult.Success>()
            // s ⋈ sh, sh ⋈ h (NOT s ⋈ h), s ⋈ p — rendered on the physical columns.
            r.output shouldContain "[store_sales]"
            r.output shouldContain "[customer]"
            r.output shouldContain "hd_demo_sk" // household key crosses the rename
            r.output shouldContain "TOP (10)"
            r.output.count { it == '=' } shouldBe 3
            r.warnings shouldBe emptyList()
        }

        "H2 — aliases are used in the synthesised conditions" {
            val r = mssql(HartlandJoinModel.H2_ALIASES).shouldBeInstanceOf<TranslateResult.Success>()
            r.output.count { it == '=' } shouldBe 2
            r.warnings shouldBe emptyList()
        }

        "H3 — a bare LEFT JOIN keeps its join type" {
            val r = mssql(HartlandJoinModel.H3_LEFT).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "LEFT JOIN"
            r.output shouldNotContain "INNER JOIN"
            r.output.count { it == '=' } shouldBe 1
        }

        "H10 — the comma form is conditioned as before" {
            val r = mssql(HartlandJoinModel.H10_COMMA).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "INNER JOIN"
            r.output.count { it == '=' } shouldBe 1
            r.warnings shouldBe emptyList()
        }

        "H10 — the comma form's plan bytes are unchanged from the pre-MJ golden (P0·S1.3)" {
            val r = mssql(HartlandJoinModel.H10_COMMA).shouldBeInstanceOf<TranslateResult.Success>()
            // `MJ_DUMP=<file>` records what the engine actually produces (the golden is never written by hand).
            System.getenv("MJ_DUMP")?.takeIf { it.isNotBlank() }?.let {
                java.io.File(it).writeText(TextFormat.printer().printToString(r.plan))
            }
            val goldenText =
                checkNotNull(javaClass.getResourceAsStream("/mj/h10-comma-plan.txtpb")) { "golden missing" }
                    .bufferedReader()
                    .readText()
            val golden = PlanNode.newBuilder().also { TextFormat.merge(goldenText, it) }.build()
            r.plan shouldBe golden
            r.plan.toByteArray().contentEquals(golden.toByteArray()) shouldBe true
        }

        // ---- P1·S3: wiring, explain, two-half byte-equality, schema auto-correct ----

        "H1 — the synthesised conditions name the right entities (s↔sh, sh↔h, s↔p)" {
            val r = mssql(HartlandJoinModel.H1_HERO).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "[store_sales].[shopper_id] = [customer].[shopper_id]"
            // The renamed household key crosses the ER/DB boundary through MAP_TO_PHYSICAL's alias subquery:
            // `(SELECT [hd_demo_sk] AS [household_id] … FROM [dbo].[household_demographics]) AS [t]`.
            r.output shouldContain "[hd_demo_sk] AS [household_id]"
            r.output shouldContain "[customer].[current_household] = [t].[household_id]"
            r.output shouldContain "[store_sales].[product_id] = [item].[product_id]"
        }

        "H12 — target=ER → REL_NODE → target=DB gives the same bytes as single-call target=DB" {
            val singleCall =
                translator
                    .parseToRelNode(HartlandJoinModel.H1_HERO, Language.SQL, SchemaCode.DB, sourceSchema = SchemaCode.ER)
                    .shouldBeInstanceOf<ParseResult.Success>()
            val erHalf =
                translator
                    .parseToRelNode(HartlandJoinModel.H1_HERO, Language.SQL, SchemaCode.ER, sourceSchema = SchemaCode.ER)
                    .shouldBeInstanceOf<ParseResult.Success>()
            val twoHalf =
                translator
                    .parseToRelNode(
                        String(erHalf.plan.toByteArray(), Charsets.ISO_8859_1),
                        Language.REL_NODE,
                        SchemaCode.DB,
                    ).shouldBeInstanceOf<ParseResult.Success>()
            twoHalf.plan shouldBe singleCall.plan
            twoHalf.plan.toByteArray().contentEquals(singleCall.plan.toByteArray()) shouldBe true
            singleCall.warnings shouldBe emptyList()
            twoHalf.warnings shouldBe emptyList()
        }

        "explain — stage model_joins carries the rewritten SQL, or says so when nothing was bare" {
            val hero = translator.explain(HartlandJoinModel.H1_HERO, Language.SQL)
            val stage = hero.stages.single { it.code == "model_joins" }
            stage.summary shouldContain "ON"
            stage.summary shouldContain "current_household"
            hero.finalError shouldBe null

            val plain = translator.explain(HartlandJoinModel.H10_COMMA, Language.SQL)
            plain.stages.single { it.code == "model_joins" }.summary shouldBe "(no bare joins)"
        }

        "explain — the model_joins stage is present even when validation fails after the rewrite" {
            // The join resolves (s↔p) but the select list names a column that does not exist.
            val r = translator.explain("SELECT nope FROM sale JOIN product", Language.SQL)
            r.finalError shouldContain "nope"
            r.stages.single { it.code == "model_joins" }.summary shouldContain
                "\"sale\".\"product_id\" = \"product\".\"product_id\""
        }

        "schema auto-correct — an inconclusive detection validated against DB first still lands on ER" {
            // Entity and table share their names, so SchemaDetector answers AMBIGUOUS, source_schema stays
            // UNSPECIFIED and parseSql validates against targetSchema=DB first — where the bare join fails
            // (no rewriter for the DB catalog) — then auto-corrects to ER, where the rewriter conditions it.
            fun er(name: String) = HartlandJoinModel.er(name)

            fun db(name: String) = HartlandJoinModel.db(name)
            val model =
                org.tatrman.translator.framework.InMemoryModelHandle(
                    tables =
                        listOf(
                            org.tatrman.translator.framework.ModelTable(
                                db("sale"),
                                HartlandJoinModel.sale.attributes.map {
                                    org.tatrman.translator.framework
                                        .ModelColumn(it.name, it.surfaceType, it.nullable)
                                },
                            ),
                            org.tatrman.translator.framework.ModelTable(
                                db("product"),
                                HartlandJoinModel.product.attributes.map {
                                    org.tatrman.translator.framework
                                        .ModelColumn(it.name, it.surfaceType, it.nullable)
                                },
                            ),
                        ),
                    entities = listOf(HartlandJoinModel.sale, HartlandJoinModel.product),
                    relations =
                        HartlandJoinModel.relations.filter {
                            it.toEntity == er("product") &&
                                it.fromEntity == er("sale")
                        },
                    entityMappings =
                        mapOf(
                            er("sale") to
                                org.tatrman.translator.framework.EntityMapping
                                    .ToTable(db("sale")),
                            er("product") to
                                org.tatrman.translator.framework.EntityMapping
                                    .ToTable(db("product")),
                        ),
                )
            val r =
                Translator(model)
                    .translate(
                        source = "SELECT product.product_name FROM sale JOIN product",
                        sourceLanguage = Language.SQL,
                        targetLanguage = Language.SQL,
                        targetSchema = SchemaCode.DB,
                        targetDialect = SqlDialect.MSSQL,
                        sourceSchema = SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                    ).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "[sale].[product_id] = [product].[product_id]"
            r.warnings shouldBe emptyList()
        }

        // ---- P3·S1: warnings on the result, one verdict per join ----

        "H4 — first purchase + first shipment: one JOIN_AMBIGUOUS_RELATIONS on parseToRelNode, translate carries the same list" {
            val sql = "SELECT shopper.birth_country, calendar.day_name FROM shopper JOIN calendar"
            val parsed =
                translator
                    .parseToRelNode(sql, Language.SQL, SchemaCode.DB, sourceSchema = SchemaCode.ER)
                    .shouldBeInstanceOf<ParseResult.Success>()
            val w = parsed.warnings.single()
            JoinerMessages.code(w) shouldBe "JOIN_AMBIGUOUS_RELATIONS"
            JoinerMessages.text(w) shouldBe
                "2 relations could join entity.shopper and entity.calendar: " +
                "shopper → calendar (first_purchase_date = date_id), " +
                "shopper → calendar (first_shipment_date = date_id); write the ON"
            val translated = mssql(sql).shouldBeInstanceOf<TranslateResult.Success>()
            translated.warnings shouldBe parsed.warnings
            translated.output shouldNotContain "="
        }

        "H7 — no direct relation: one JOIN_NO_RELATION, cz wording verbatim, no physical duplicate" {
            val r = mssql("SELECT 1 FROM sale JOIN household").shouldBeInstanceOf<TranslateResult.Success>()
            val w = r.warnings.single()
            JoinerMessages.code(w) shouldBe "JOIN_NO_RELATION"
            JoinerMessages.severity(w) shouldBe JoinerMessages.Severity.WARNING
            JoinerMessages.text(w) shouldBe
                "no declared relation between entity.sale and entity.household; Cartesian product preserved"
        }

        "H6 — chain with an ambiguous last hop: resolved hops silent, the ambiguous one names the whole side" {
            val r =
                mssql("SELECT 1 FROM sale JOIN shopper JOIN address")
                    .shouldBeInstanceOf<TranslateResult.Success>()
            val w = r.warnings.single().shouldBeInstanceOf<JoinerWarning.AmbiguousRelations>()
            JoinerMessages.text(w) shouldContain
                "entity.sale, entity.shopper and entity.address"
        }

        "REL_NODE re-entry of an H4 target=ER plan reports the warning again, once" {
            val sql = "SELECT shopper.birth_country FROM shopper JOIN calendar"
            val erHalf =
                translator
                    .parseToRelNode(sql, Language.SQL, SchemaCode.ER, sourceSchema = SchemaCode.ER)
                    .shouldBeInstanceOf<ParseResult.Success>()
            erHalf.warnings.map { JoinerMessages.code(it) } shouldBe listOf("JOIN_AMBIGUOUS_RELATIONS")
            val dbHalf =
                translator
                    .parseToRelNode(
                        String(erHalf.plan.toByteArray(), Charsets.ISO_8859_1),
                        Language.REL_NODE,
                        SchemaCode.DB,
                    ).shouldBeInstanceOf<ParseResult.Success>()
            dbHalf.warnings.map { JoinerMessages.code(it) } shouldBe listOf("JOIN_AMBIGUOUS_RELATIONS")
        }

        "H11 — a derived table on one side: ON TRUE + JOIN_NO_RELATION naming 'a derived table' (review-097 R2)" {
            val r =
                mssql(
                    "SELECT 1 FROM sale JOIN " +
                        "(SELECT product_id FROM product WHERE product_name LIKE '%able%') p",
                ).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldNotContain "="
            val w = r.warnings.single()
            JoinerMessages.code(w) shouldBe "JOIN_NO_RELATION"
            JoinerMessages.text(w) shouldBe
                "no declared relation between entity.sale and a derived table; Cartesian product preserved"
        }

        "H13 — both calendar hops with explicit ONs, then a bare JOIN sale resolves sh ↔ s (C-1, review-097 R1)" {
            val r =
                mssql(
                    "SELECT 1 FROM shopper sh " +
                        "JOIN calendar c1 ON sh.first_purchase_date = c1.date_id " +
                        "JOIN calendar c2 ON sh.first_shipment_date = c2.date_id " +
                        "JOIN sale",
                ).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "[customer].[shopper_id] = [store_sales].[shopper_id]"
            r.output shouldNotContain "CROSS JOIN"
            r.warnings shouldBe emptyList()
        }

        "a pure-DB comma join with no FK still reports JOIN_NO_RELATION naming the tables" {
            val r =
                translator
                    .translate(
                        source = "SELECT 1 FROM store_sales, item",
                        sourceLanguage = Language.SQL,
                        targetLanguage = Language.SQL,
                        targetSchema = SchemaCode.DB,
                        targetDialect = SqlDialect.MSSQL,
                        sourceSchema = SchemaCode.DB,
                    ).shouldBeInstanceOf<TranslateResult.Success>()
            JoinerMessages.text(r.warnings.single()) shouldBe
                "no declared relation between dbo.store_sales and dbo.item; Cartesian product preserved"
        }

        // ---- MJ-P4·S2.6 (a served-estate trace): query-backed entities, FK fallback through MAP_TO_PHYSICAL's Project ----

        fun mssqlOn(
            model: org.tatrman.translator.framework.ModelHandle,
            sql: String,
        ): TranslateResult =
            Translator(model).translate(
                source = sql,
                sourceLanguage = Language.SQL,
                targetLanguage = Language.SQL,
                targetSchema = SchemaCode.DB,
                targetDialect = SqlDialect.MSSQL,
                sourceSchema = SchemaCode.ER,
            )

        "query-backed entity, no relations served: the FK still conditions a bare JOIN through the alias Project" {
            // On the served estate `relations()` was empty (metadata served descriptors only) and `product`-like entities
            // are `*__filter` queries: MAP_TO_PHYSICAL wraps the body in a Project, which the narrowed descent (P2) no
            // longer looked through — the FK fallback silently stopped and the join stayed a Cartesian product.
            val r =
                mssqlOn(
                    HartlandJoinModel.handleQueryBacked(),
                    "SELECT product.product_name FROM sale JOIN product",
                ).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "[product_id] = "
            r.output.count { it == '=' } shouldBe 1
            r.output shouldNotContain "CROSS"
            // physical conditioned it → the logical carrier's NoRelation is stale and dropped (one verdict per join)
            r.warnings shouldBe emptyList()
        }

        "query-backed entity, no relations served: the comma form too" {
            val r =
                mssqlOn(
                    HartlandJoinModel.handleQueryBacked(),
                    "SELECT product.product_name FROM sale, product",
                ).shouldBeInstanceOf<TranslateResult.Success>()
            r.output.count { it == '=' } shouldBe 1
            r.warnings shouldBe emptyList()
        }

        "query-backed entity WITH relations served: the SQL carrier resolves it, the physical carrier passes through" {
            val r =
                mssqlOn(
                    HartlandJoinModel.handleQueryBacked(relations = HartlandJoinModel.relations),
                    "SELECT product.product_name FROM sale JOIN product",
                ).shouldBeInstanceOf<TranslateResult.Success>()
            r.output.count { it == '=' } shouldBe 1
            r.warnings shouldBe emptyList()
        }
    })
