// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.rel.core.Values
import org.apache.calcite.sql.type.SqlTypeName
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ValuesNode
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework

/**
 * ttr-core#148 — a `ValuesNode` with no rows is how the wire carries an empty relation: Calcite's
 * `RelBuilder.filter` folds a filter it proves always false (`a = 'x' AND a = 'y'`) into a `Values` that
 * keeps the row type and has no tuples, and [PlanNodeEncoder] writes it faithfully. Decoding it used to
 * throw `Value count must be a positive multiple of field count`, so a question whose answer is "no rows"
 * failed instead. With no rows to infer types from, the decoder rebuilds the row type from
 * `output_columns`.
 */
class EmptyValuesWireSpec :
    StringSpec({
        fun column(
            name: String,
            type: String,
        ): ColumnRef =
            ColumnRef
                .newBuilder()
                .setName(name)
                .setType(type)
                .build()

        val emptyValues: PlanNode =
            PlanNode
                .newBuilder()
                .setValues(
                    ValuesNode
                        .newBuilder()
                        .addOutputColumns(column("warehouse_id", "int"))
                        .addOutputColumns(column("warehouse_name", "text"))
                        .addOutputColumns(column("ext_sales_price", "float"))
                        .addOutputColumns(column("is_open", "bool"))
                        .addOutputColumns(column("cal_date", "datetime")),
                ).build()

        "an empty VALUES decodes to a Values with no rows and the wire's column names and types" {
            val rel = PlanNodeDecoder.decode(emptyValues, TranslatorFramework(FixtureModel.handle()))

            val values = rel.shouldBeInstanceOf<Values>()
            values.tuples.shouldBeEmpty()
            values.rowType.fieldNames shouldBe
                listOf("warehouse_id", "warehouse_name", "ext_sales_price", "is_open", "cal_date")
            // The same Calcite types a table scan of these surface types carries (TypeMapping.fromSurface).
            values.rowType.fieldList.map { it.type.sqlTypeName } shouldBe
                listOf(
                    SqlTypeName.BIGINT,
                    SqlTypeName.VARCHAR,
                    SqlTypeName.DOUBLE,
                    SqlTypeName.BOOLEAN,
                    SqlTypeName.TIMESTAMP,
                )
            // No row says otherwise, so every column may be NULL.
            values.rowType.fieldList.map { it.type.isNullable } shouldBe List(5) { true }
        }

        "an empty VALUES round-trips the wire byte for byte" {
            val rel = PlanNodeDecoder.decode(emptyValues, TranslatorFramework(FixtureModel.handle()))

            PlanNodeEncoder.encode(rel) shouldBe emptyValues
        }
    })
