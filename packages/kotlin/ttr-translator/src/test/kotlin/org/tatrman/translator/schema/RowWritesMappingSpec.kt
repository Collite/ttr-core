// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.schema

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.*
import org.tatrman.translator.framework.*
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translate.v1.Language
import java.nio.file.Path

class RowWritesMappingSpec :
    FunSpec({
        val entity =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName("order")
                .build()
        val table =
            QualifiedName
                .newBuilder()
                .setSchemaCode(
                    SchemaCode.DB,
                ).setNamespace("dbo")
                .setName("sales_order")
                .build()
        val renames = mapOf("order_number" to "so_order_number", "state" to "so_state", "held_for" to "so_held_for")
        val model =
            InMemoryModelHandle(
                tables =
                    listOf(
                        ModelTable(
                            table,
                            renames.values.map {
                                ModelColumn(it, SurfaceType.TEXT)
                            } + ModelColumn("identity", SurfaceType.TEXT),
                            listOf("so_order_number"),
                        ),
                    ),
                entities =
                    listOf(
                        ModelEntity(
                            entity,
                            (renames.keys + "identity" + "missing").map {
                                ModelAttribute(
                                    it,
                                    SurfaceType.TEXT,
                                    isKey =
                                        it == "order_number",
                                )
                            },
                        ),
                    ),
                entityMappings = mapOf(entity to EntityMapping.ToTable(table)),
                attributeRenames = mapOf(entity to renames),
            )

        fun col(name: String) = Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)).build()

        fun param(name: String) = Expression.newBuilder().setParameter(ParameterRef.newBuilder().setName(name)).build()

        fun call(
            op: String,
            vararg args: Expression,
        ) = Expression
            .newBuilder()
            .setFunction(
                FunctionCall.newBuilder().setOperation(op).addAllOperands(args.toList()),
            ).build()

        fun node(row: RowWrite.Builder) =
            PlanNode.newBuilder().setRowWrites(RowWrites.newBuilder().addRows(row.setTarget(entity))).build()

        fun update(attr: String = "state") =
            RowWrite
                .newBuilder()
                .setOp(RowWriteOp.UPDATE)
                .addValues(ColumnValue.newBuilder().setName(attr).setValue(param("next")))
                .addReadBefore("state")
                .setWhere(
                    call("and", call("eq", col("order_number"), param("k")), call("in", col("state"), param("from"))),
                )

        test("the hartland binding fixture agrees with the registered ModelHandle binding") {
            var root = Path.of("").toAbsolutePath()
            while (!root.resolve("packages/kotlin/ttrp-graph").toFile().exists()) root = requireNotNull(root.parent)
            val text =
                root
                    .resolve(
                        "packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes/model/hartland/binding/er2db.ttrm",
                    ).toFile()
                    .readText()
            for (attr in renames.keys) text shouldContain "attribute: er.entity.order.$attr,"
            for (column in renames.values) text shouldContain "target: { column: db.dbo.sales_order.$column }"
        }
        test("values, read_before and nested key plus CAS predicates lower through the binding") {
            val input =
                node(
                    update()
                        .addReadBefore(
                            "held_for",
                        ).addValues(ColumnValue.newBuilder().setName("held_for").setValue(param("reason"))),
                )
            val result = MapToPhysical.apply(input, model).shouldBeInstanceOf<MapToPhysicalResult.Success>().plan
            val row = result.rowWrites.rowsList.single()
            row.target shouldBe table
            row.valuesList.map { it.name } shouldBe listOf("so_state", "so_held_for")
            row.valuesList.map { it.value.parameter.name } shouldBe listOf("next", "reason")
            row.readBeforeList shouldBe listOf("so_state", "so_held_for")
            row.priorKeysList shouldBe listOf("state", "held_for")
            row.where.function.operandsList[0]
                .function.operandsList[0]
                .columnRef.name shouldBe "so_order_number"
            row.where.function.operandsList[1]
                .function.operandsList[0]
                .columnRef.name shouldBe "so_state"
            MapToPhysical.apply(result, model).shouldBeInstanceOf<MapToPhysicalResult.Success>().plan shouldBe result
        }
        test("two prior attributes sharing one column refuse and name both attributes") {
            val aliased =
                InMemoryModelHandle(
                    tables = listOf(ModelTable(table, listOf(ModelColumn("so_state", SurfaceType.TEXT)))),
                    entities =
                        listOf(
                            ModelEntity(
                                entity,
                                listOf(
                                    ModelAttribute("state", SurfaceType.TEXT),
                                    ModelAttribute("held_for", SurfaceType.TEXT),
                                ),
                            ),
                        ),
                    entityMappings = mapOf(entity to EntityMapping.ToTable(table)),
                    attributeRenames = mapOf(entity to mapOf("state" to "so_state", "held_for" to "so_state")),
                )
            val result =
                MapToPhysical
                    .apply(node(update().addReadBefore("held_for")), aliased)
                    .shouldBeInstanceOf<MapToPhysicalResult.Failure>()
            result.code shouldBe "row_writes_prior"
            result.message shouldContain "state"
            result.message shouldContain "held_for"
        }
        test("an identity-mapped attribute uses its own physical column name") {
            MapToPhysical
                .apply(node(update("identity")), model)
                .shouldBeInstanceOf<MapToPhysicalResult.Success>()
                .plan.rowWrites.rowsList
                .single()
                .valuesList
                .single()
                .name shouldBe "identity"
        }
        test("a missing physical column names both the attribute and attempted column") {
            val result =
                MapToPhysical
                    .apply(
                        node(update("missing")),
                        model,
                    ).shouldBeInstanceOf<MapToPhysicalResult.Failure>()
            result.message shouldContain "missing"
            val badRename =
                InMemoryModelHandle(
                    tables = listOf(ModelTable(table, listOf(ModelColumn("so_state", SurfaceType.TEXT)))),
                    entityMappings = mapOf(entity to EntityMapping.ToTable(table)),
                    attributeRenames =
                        mapOf(
                            entity to mapOf("state" to "lost_column"),
                        ),
                )
            val renamed =
                MapToPhysical
                    .apply(
                        node(update()),
                        badRename,
                    ).shouldBeInstanceOf<MapToPhysicalResult.Failure>()
            renamed.message shouldContain "state"
            renamed.message shouldContain "lost_column"
        }
        test("unknown entity and query-backed targets refuse with a diagnostic") {
            val unknown =
                node(
                    update(),
                ).toBuilder()
                    .setRowWrites(
                        RowWrites.newBuilder().addRows(update().setTarget(entity.toBuilder().setName("unknown"))),
                    ).build()
            MapToPhysical.apply(unknown, model).shouldBeInstanceOf<MapToPhysicalResult.Failure>().message shouldContain
                "unknown"
            val queryModel =
                InMemoryModelHandle(
                    tables = emptyList(),
                    entityMappings =
                        mapOf(
                            entity to EntityMapping.ToQuery(table),
                        ),
                )
            MapToPhysical.apply(node(update()), queryModel).shouldBeInstanceOf<MapToPhysicalResult.Failure>()
        }
        test("REL_NODE two-half re-entry maps row writes without a read-RelNode round trip") {
            val input = node(update())
            val translator = Translator(model)
            val logical =
                translator
                    .parseToRelNode(
                        input.toByteArray().toString(Charsets.ISO_8859_1),
                        Language.REL_NODE,
                        SchemaCode.ER,
                    ).shouldBeInstanceOf<ParseResult.Success>()
                    .plan
            logical shouldBe input
            translator
                .parseToRelNode(logical.toByteArray().toString(Charsets.ISO_8859_1), Language.REL_NODE, SchemaCode.DB)
                .shouldBeInstanceOf<ParseResult.Success>()
                .plan.rowWrites.rowsList
                .single()
                .target shouldBe table
        }
        test("read walkers, fingerprint columns and read-only codecs recognize a row-write root") {
            val input = node(update())
            org.tatrman.translator.joiner.JoinerPlanWalker
                .rewriteChildren(input) { error("No read child") } shouldBe
                input
            org.tatrman.translator.joiner.JoinerLogical
                .apply(input, model)
                .plan shouldBe input
            org.tatrman.translator.joiner.JoinerPhysical
                .apply(input, model)
                .plan shouldBe input
            Unfold.apply(input, model).shouldBeInstanceOf<UnfoldResult.Success>().plan shouldBe input
            Translator.outputColumnNames(input) shouldBe emptyList()
            val translator = Translator(model)
            val physical = MapToPhysical.apply(input, model).shouldBeInstanceOf<MapToPhysicalResult.Success>().plan
            for (language in listOf(Language.TRANSFORMATION_DSL, Language.DATAFRAME_DSL)) {
                translator
                    .unparseFromRelNode(physical, language, org.tatrman.translate.v1.SqlDialect.POSTGRESQL)
                    .shouldBeInstanceOf<org.tatrman.translator.orchestrator.UnparseResult.Failure>()
                    .code shouldBe "operation_not_supported_in_target_language"
            }
            translator
                .unparseFromRelNode(input, Language.SQL, org.tatrman.translate.v1.SqlDialect.POSTGRESQL)
                .shouldBeInstanceOf<org.tatrman.translator.orchestrator.UnparseResult.Failure>()
                .code shouldBe "unparse_rejects_pre_physical"
        }
    })
