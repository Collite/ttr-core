// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect as SqlDialectProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.params.SqlParam

class TranslatorSpec :
    StringSpec({

        val translator = Translator(FixtureModel.handle())

        "translate SQL to SQL via the orchestrator round-trips" {
            val r =
                translator.translate(
                    source = "SELECT id, name FROM customers",
                    sourceLanguage = Language.SQL,
                    targetLanguage = Language.SQL,
                    targetDialect = SqlDialectProto.MSSQL,
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            r.output.shouldContainIgnoringCase("select")
            r.output.shouldContainIgnoringCase("customers")
        }

        "parseToRelNode produces a PlanNode for valid SQL" {
            val r = translator.parseToRelNode("SELECT id FROM customers", Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Success>()
            // PlanNode's top is a Project (over a TableScan).
            r.plan.hasProject() shouldBe true
        }

        "parseToRelNode produces a Union PlanNode for a UNION ALL query (full pipeline)" {
            // End-to-end through every orchestrator pass (validate → passes → encode).
            // Guards that the v1 Union op survives the whole pipeline, not just the
            // isolated wire round-trip.
            val r =
                translator.parseToRelNode(
                    "SELECT id FROM customers UNION ALL SELECT customer_id FROM orders",
                    Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
            r.plan.hasUnion() shouldBe true
            r.plan.union.all shouldBe true
            r.plan.union.inputsCount shouldBe 2
        }

        // Regression (RG audit): schema auto-correction. `pololetka` exists in BOTH db.dbo and
        // er.entity, so detection is AMBIGUOUS (inconclusive) and parseSql falls back to the
        // caller's target=ER (query-runner's pass-1 default). ER validation fails — `db_only` is a
        // db column, not an er attribute — and the auto-correction retries against DB and succeeds,
        // rather than surfacing a misleading wrong-catalog "object/column not found".
        "parseToRelNode auto-corrects the catalog to DB when target=ER validation fails on a DB-only column" {
            val ambiguousDbTable =
                ModelTable(
                    qname =
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName("pololetka")
                            .build(),
                    columns = listOf(ModelColumn("db_only", SurfaceType.INT)),
                )
            val ambiguousErEntity =
                ModelEntity(
                    qname =
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.ER)
                            .setNamespace("entity")
                            .setName("pololetka")
                            .build(),
                    attributes = listOf(ModelAttribute("er_attr", SurfaceType.INT)),
                )
            val t =
                Translator(InMemoryModelHandle(tables = listOf(ambiguousDbTable), entities = listOf(ambiguousErEntity)))
            val r =
                t.parseToRelNode("SELECT db_only FROM pololetka", Language.SQL, targetSchema = SchemaCode.ER)
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "parseToRelNode surfaces validation_failed on unknown column" {
            val r = translator.parseToRelNode("SELECT does_not_exist FROM customers", Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Failure>()
            r.code shouldBe "validation_failed"
        }

        "unparseFromRelNode emits SQL for a PlanNode" {
            val parsed =
                translator.parseToRelNode("SELECT id FROM customers", Language.SQL)
                    as ParseResult.Success
            val r =
                translator.unparseFromRelNode(
                    parsed.plan,
                    Language.SQL,
                    SqlDialectProto.MSSQL,
                )
            r.shouldBeInstanceOf<UnparseResult.Success>()
            r.output.shouldContainIgnoringCase("customers")
        }

        "unparseFromRelNode emits TransDSL JSON for TRANSFORMATION_DSL target" {
            val parsed =
                translator.parseToRelNode("SELECT id FROM customers", Language.SQL)
                    as ParseResult.Success
            val r =
                translator.unparseFromRelNode(
                    parsed.plan,
                    Language.TRANSFORMATION_DSL,
                    SqlDialectProto.MSSQL,
                )
            r.shouldBeInstanceOf<UnparseResult.Success>()
            r.output.shouldContainIgnoringCase("customers")
        }

        "explain captures stage artefacts" {
            val r = translator.explain("SELECT id FROM customers", Language.SQL)
            // parse_and_to_rel, model_joins (MJ), optimize_and_unparse
            r.stages.size shouldBe 3
            r.finalOutput!!.shouldContainIgnoringCase("customers")
        }

        "translate from TransDSL JSON to SQL works end-to-end" {
            // The fixture model registers `customers` flat under schema "db"
            // (no namespace subschema), so the QualifiedName the parser
            // emits must use an empty `namespace`. The PlanNode wire codec
            // encodes Calcite TableScans the same way (see
            // PlanNodeEncoder.encodeTableScan).
            val transDslJson =
                """
                {
                  "core": [{"dataObject": {"schemaCode": "db", "name": "customers"}}],
                  "columns": [{"name": "id"}]
                }
                """.trimIndent()
            val r =
                translator.translate(
                    source = transDslJson,
                    sourceLanguage = Language.TRANSFORMATION_DSL,
                    targetLanguage = Language.SQL,
                    targetDialect = SqlDialectProto.MSSQL,
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            r.output.shouldContainIgnoringCase("customers")
        }

        // ---- Parameter rail (ParameterBridge / ParameterTyper wiring) -------------------
        // Regression guard for the fork-dropped wiring (master-plan §7, Golem S2.4 T7):
        // pattern queries send SQL with `{name}` placeholders + typed `parameters`; the
        // orchestrator must rewrite `{name}` → Calcite `?` before parsing. Before this wiring
        // the `{name}` token reached Calcite and produced an opaque "Incorrect syntax near …"
        // parse error (the reported golem/translator failure).

        "parseToRelNode rewrites {name} placeholders to ? when typed parameters are supplied" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name LIKE {q}",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "DF%")),
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "parseToRelNode validates a placeholder as the first || operand via the typed-CAST fallback" {
            // A bare `?` is untypeable as an operand of `||`/CONCAT (its operand-type inference is
            // null, unlike LIKE/BETWEEN). Bare validation fails; the orchestrator retries with
            // CAST(? AS …). This is the prefix-LIKE form `LIKE {q} || '%'` — param FIRST.
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name LIKE {q} || '%'",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "DF")),
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "validates a many-param query with mixed first/middle || operands" {
            // A prefix-LIKE (param FIRST: `{a} || '%'`) OR an infix-LIKE (param MIDDLE:
            // `'%' || {a} || '%'`), AND a second infix-LIKE on another param — every `?` an
            // untypeable `||` operand. All must validate via the typed fallback.
            val r =
                translator.parseToRelNode(
                    source =
                        "SELECT id FROM customers " +
                            "WHERE (name LIKE {a} || '%' OR name LIKE '%' || {a} || '%') " +
                            "AND name LIKE '%' || {b} || '%'",
                    sourceLanguage = Language.SQL,
                    parameters =
                        listOf(
                            SqlParam(name = "a", type = "text", value = "DF ADNAK"),
                            SqlParam(name = "b", type = "text", value = "2026.04"),
                        ),
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "a `||` placeholder query unparses without a synthetic CAST (crutch is unwrapped)" {
            // Infix-LIKE form `LIKE '%' || {q} || '%'` — param in a middle operand.
            val parsed =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name LIKE '%' || {q} || '%'",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "DF")),
                ) as ParseResult.Success
            // The plan carries one `?`; supply its binding (the realistic worker path) so the
            // positional expansion runs — an unparse with placeholders but no bindings is a contract
            // violation guarded by PositionalParameters (H1).
            val qBinding =
                org.tatrman.plan.v1.ParameterBinding
                    .newBuilder()
                    .setName("q")
                    .setType("text")
                    .setValue(
                        org.tatrman.plan.v1.Value
                            .newBuilder()
                            .setStringValue("DF"),
                    ).build()
            val r =
                translator.unparseFromRelNode(
                    parsed.plan,
                    Language.SQL,
                    SqlDialectProto.MSSQL,
                    parameters = listOf(qBinding),
                )
            r.shouldBeInstanceOf<UnparseResult.Success>()
            // The validation-only CAST(? AS VARCHAR) must not leak into the executed SQL — otherwise
            // a short default VARCHAR length would truncate the search term on some dialects.
            r.output.shouldNotContainIgnoringCase("cast")
        }

        "named {name} SQL round-trips to ordered ? + a positional parametersList" {
            // The T7 DONE shape: `{name}` SQL + typed parameters → unparsed SQL whose `?` are
            // bound 1:1 by a positional ParameterBinding list (the form the MSSQL worker JDBC-binds).
            // A name used twice yields two `?` and two positional entries from one named binding.
            val binding =
                org.tatrman.plan.v1.ParameterBinding
                    .newBuilder()
                    .setName("q")
                    .setType("text")
                    .setValue(
                        org.tatrman.plan.v1.Value
                            .newBuilder()
                            .setStringValue("DF")
                            .build(),
                    ).build()
            val parsed =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name = {q} OR name = {q}",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "DF")),
                ) as ParseResult.Success
            val r =
                translator.unparseFromRelNode(
                    parsed.plan,
                    Language.SQL,
                    SqlDialectProto.MSSQL,
                    parameters = listOf(binding),
                )
            r.shouldBeInstanceOf<UnparseResult.Success>()
            r.output.shouldContainIgnoringCase("?")
            // Two `?` positions, both bound to the single named value.
            r.parameters.size shouldBe 2
            r.parameters.all { it.name == "q" && it.value.stringValue == "DF" } shouldBe true
        }

        "parseToRelNode dedups a repeated {name} to one binding identity" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name = {q} OR name = {q}",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "x")),
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "parseToRelNode fails with parameter_unknown when a {name} has no binding" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name = {missing}",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "x")),
                )
            r.shouldBeInstanceOf<ParseResult.Failure>()
            r.code shouldBe "parameter_unknown"
        }

        "a quoted LIKE pattern placeholder binds positionally as [q, q] (TF-P4, G C2)" {
            // The legacy search idiom written with the placeholder inside the pattern literal: the bridge
            // rewrites `'%{q}%'` to `CONCAT('%', {q}, '%')`, so the `?` is a real marker, not pattern text.
            val parsed =
                translator.parseToRelNode(
                    source =
                        "SELECT id FROM customers WHERE ({q} = '' OR name COLLATE Latin1_General_CI_AI LIKE '%{q}%')",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "varchar", value = "DF")),
                )
            parsed.shouldBeInstanceOf<ParseResult.Success>()
            val binding =
                org.tatrman.plan.v1.ParameterBinding
                    .newBuilder()
                    .setName("q")
                    .setType("varchar")
                    .setValue(
                        org.tatrman.plan.v1.Value
                            .newBuilder()
                            .setStringValue("DF"),
                    ).build()
            val r =
                translator.unparseFromRelNode(
                    parsed.plan,
                    Language.SQL,
                    SqlDialectProto.MSSQL,
                    parameters = listOf(binding),
                )
            r.shouldBeInstanceOf<UnparseResult.Success>()
            r.output shouldBe
                "SELECT [id]\nFROM [dbo].[customers]\n" +
                "WHERE LOWER(?) = '' OR [name] COLLATE Latin1_General_CI_AI LIKE CONCAT('%', ?, '%')"
            r.parameters.map { it.name } shouldBe listOf("q", "q")
        }

        "parseToRelNode fails with parameter_in_string_literal for a placeholder baked into a literal (TF-P4)" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name = 'prefix {q} suffix'",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "varchar", value = "x")),
                )
            r.shouldBeInstanceOf<ParseResult.Failure>()
            r.code shouldBe "parameter_in_string_literal"
        }

        "parseToRelNode leaves {…} verbatim when no parameters are supplied (free-SQL)" {
            // No bindings → the bridge is bypassed so JDBC escapes / literal `{` survive; here a
            // bare `{q}` is therefore handed to Calcite as-is and rejected (i.e. we did NOT
            // silently rewrite it).
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name LIKE {q}",
                    sourceLanguage = Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Failure>()
        }
    })
