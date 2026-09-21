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
import org.tatrman.translator.framework.DfpJoinModel

/**
 * MJ (model joins) — end-to-end acceptance of the corpus in project `model-joins/plan/plan.md §4`.
 *
 * P0·S1 baseline (RED): H1–H3 fail at Calcite validation with `… join requires a condition`; H10 (comma
 * form) is GREEN already and pins today's plan bytes.
 */
class ModelJoinSpec :
    StringSpec({
        val translator = Translator(DfpJoinModel.handle())

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
            val r = mssql(DfpJoinModel.H1_HERO).shouldBeInstanceOf<TranslateResult.Success>()
            // kp ⋈ dm, dm ⋈ z (NOT kp ⋈ z), kp ⋈ p — rendered on the physical columns.
            r.output shouldContain "QKUMPRODEJ"
            r.output shouldContain "QDODMISTO"
            r.output shouldContain "IDSUBJEKT" // zákazník key crosses the rename
            r.output shouldContain "TOP (10)"
            r.output.count { it == '=' } shouldBe 3
            r.warnings shouldBe emptyList()
        }

        "H2 — aliases are used in the synthesised conditions" {
            val r = mssql(DfpJoinModel.H2_ALIASES).shouldBeInstanceOf<TranslateResult.Success>()
            r.output.count { it == '=' } shouldBe 2
            r.warnings shouldBe emptyList()
        }

        "H3 — a bare LEFT JOIN keeps its join type" {
            val r = mssql(DfpJoinModel.H3_LEFT).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "LEFT JOIN"
            r.output shouldNotContain "INNER JOIN"
            r.output.count { it == '=' } shouldBe 1
        }

        "H10 — the comma form is conditioned as before" {
            val r = mssql(DfpJoinModel.H10_COMMA).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "INNER JOIN"
            r.output.count { it == '=' } shouldBe 1
            r.warnings shouldBe emptyList()
        }

        "H10 — the comma form's plan bytes are unchanged from the pre-MJ golden (P0·S1.3)" {
            val r = mssql(DfpJoinModel.H10_COMMA).shouldBeInstanceOf<TranslateResult.Success>()
            val goldenText =
                checkNotNull(javaClass.getResourceAsStream("/mj/h10-comma-plan.txtpb")) { "golden missing" }
                    .bufferedReader()
                    .readText()
            val golden = PlanNode.newBuilder().also { TextFormat.merge(goldenText, it) }.build()
            r.plan shouldBe golden
            r.plan.toByteArray().contentEquals(golden.toByteArray()) shouldBe true
        }

        // ---- P1·S3: wiring, explain, two-half byte-equality, schema auto-correct ----

        "H1 — the synthesised conditions name the right entities (kp↔dm, dm↔z, kp↔p)" {
            val r = mssql(DfpJoinModel.H1_HERO).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "[QKUMPRODEJ].[id_dodacího_místa] = [QDODMISTO].[id_dodacího_místa]"
            // The renamed customer key crosses the ER/DB boundary through MAP_TO_PHYSICAL's alias subquery
            // (DF-T05): `(SELECT [IDSUBJEKT] AS [id_zákazníka] … FROM [dbo].[QSUBJEKT]) AS [t]`.
            r.output shouldContain "[IDSUBJEKT] AS [id_zákazníka]"
            r.output shouldContain "[QDODMISTO].[id_subjektu] = [t].[id_zákazníka]"
            r.output shouldContain "[QKUMPRODEJ].[id_produktu] = [QPRODUKT].[id_produktu]"
        }

        "H12 — target=ER → REL_NODE → target=DB gives the same bytes as single-call target=DB" {
            val singleCall =
                translator
                    .parseToRelNode(DfpJoinModel.H1_HERO, Language.SQL, SchemaCode.DB, sourceSchema = SchemaCode.ER)
                    .shouldBeInstanceOf<ParseResult.Success>()
            val erHalf =
                translator
                    .parseToRelNode(DfpJoinModel.H1_HERO, Language.SQL, SchemaCode.ER, sourceSchema = SchemaCode.ER)
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
            val hero = translator.explain(DfpJoinModel.H1_HERO, Language.SQL)
            val stage = hero.stages.single { it.code == "model_joins" }
            stage.summary shouldContain "ON"
            stage.summary shouldContain "id_subjektu"
            hero.finalError shouldBe null

            val plain = translator.explain(DfpJoinModel.H10_COMMA, Language.SQL)
            plain.stages.single { it.code == "model_joins" }.summary shouldBe "(no bare joins)"
        }

        "explain — the model_joins stage is present even when validation fails after the rewrite" {
            // The join resolves (kp↔p) but the select list names a column that does not exist.
            val r = translator.explain("SELECT nope FROM kumulovaný_prodej JOIN produkt", Language.SQL)
            r.finalError shouldContain "nope"
            r.stages.single { it.code == "model_joins" }.summary shouldContain
                "\"kumulovaný_prodej\".\"id_produktu\" = \"produkt\".\"id_produktu\""
        }

        "schema auto-correct — an inconclusive detection validated against DB first still lands on ER" {
            // Entity and table share their names, so SchemaDetector answers AMBIGUOUS, source_schema stays
            // UNSPECIFIED and parseSql validates against targetSchema=DB first — where the bare join fails
            // (no rewriter for the DB catalog) — then auto-corrects to ER, where the rewriter conditions it.
            fun er(name: String) = DfpJoinModel.er(name)

            fun db(name: String) = DfpJoinModel.db(name)
            val model =
                org.tatrman.translator.framework.InMemoryModelHandle(
                    tables =
                        listOf(
                            org.tatrman.translator.framework.ModelTable(
                                db("kumulovaný_prodej"),
                                DfpJoinModel.kumulovanyProdej.attributes.map {
                                    org.tatrman.translator.framework
                                        .ModelColumn(it.name, it.surfaceType, it.nullable)
                                },
                            ),
                            org.tatrman.translator.framework.ModelTable(
                                db("produkt"),
                                DfpJoinModel.produkt.attributes.map {
                                    org.tatrman.translator.framework
                                        .ModelColumn(it.name, it.surfaceType, it.nullable)
                                },
                            ),
                        ),
                    entities = listOf(DfpJoinModel.kumulovanyProdej, DfpJoinModel.produkt),
                    relations =
                        DfpJoinModel.relations.filter {
                            it.toEntity == er("produkt") &&
                                it.fromEntity == er("kumulovaný_prodej")
                        },
                    entityMappings =
                        mapOf(
                            er("kumulovaný_prodej") to
                                org.tatrman.translator.framework.EntityMapping
                                    .ToTable(db("kumulovaný_prodej")),
                            er("produkt") to
                                org.tatrman.translator.framework.EntityMapping
                                    .ToTable(db("produkt")),
                        ),
                )
            val r =
                Translator(model)
                    .translate(
                        source = "SELECT produkt.název_produktu FROM kumulovaný_prodej JOIN produkt",
                        sourceLanguage = Language.SQL,
                        targetLanguage = Language.SQL,
                        targetSchema = SchemaCode.DB,
                        targetDialect = SqlDialect.MSSQL,
                        sourceSchema = SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                    ).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "[kumulovaný_prodej].[id_produktu] = [produkt].[id_produktu]"
            r.warnings shouldBe emptyList()
        }
    })
