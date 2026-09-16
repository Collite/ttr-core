// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.tf

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.config.NullCollation
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.rex.RexInputRef
import org.apache.calcite.sql.SqlAggFunction
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.`fun`.SqlLibraryOperators
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.type.SqlTypeFactoryImpl
import org.apache.calcite.sql.validate.SqlValidator
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.tools.Frameworks
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework
import java.math.BigDecimal

/**
 * TF-P0.5 — the Calcite 1.41 facts the translator-fidelity plan relies on, pinned against the jar on
 * the classpath (the reference card in `plan/tasks/00-task-management.md`). A failing fact means the
 * plan's mechanism is wrong: stop, amend `contracts.md` + the affected stage list, then continue.
 */
class CalciteFactsSpec :
    StringSpec({
        "(a) DECIMAL literal: getValueAs(BigDecimal) is the scaled value, value2 the unscaled long (G A1)" {
            val rexBuilder = RexBuilder(SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT))
            val lit = rexBuilder.makeExactLiteral(BigDecimal("1.5"))
            lit.getValueAs(BigDecimal::class.java)!!.compareTo(BigDecimal("1.5")) shouldBe 0
            lit.value2 shouldBe 15L
        }

        "(b) RelBuilder: join row type uniquified, frame not; field(name) fails, field(ordinal) resolves (G A3)" {
            val builder = TranslatorFramework(FixtureModel.tfHandle()).newRelBuilder()
            builder.scan("A").scan("B")
            builder.join(
                JoinRelType.INNER,
                builder.equals(builder.field(2, 0, "B_ID"), builder.field(2, 1, "ID")),
            )
            builder.peek().rowType.fieldNames shouldBe listOf("ID", "NAME", "B_ID", "ID0", "NAME0")
            shouldThrow<IllegalArgumentException> { builder.field("NAME0") }
            val ref = builder.field(4)
            ref.shouldBeInstanceOf<RexInputRef>()
            ref.index shouldBe 4
        }

        "(c) SqlValidator.Config.withDefaultNullCollation(LOW) is accepted by Frameworks.newConfigBuilder()" {
            val validatorConfig = SqlValidator.Config.DEFAULT.withDefaultNullCollation(NullCollation.LOW)
            val config = Frameworks.newConfigBuilder().sqlValidatorConfig(validatorConfig).build()
            config.sqlValidatorConfig.defaultNullCollation() shouldBe NullCollation.LOW
        }

        "(d) the ranking window functions are SqlAggFunctions" {
            listOf(
                SqlStdOperatorTable.ROW_NUMBER,
                SqlStdOperatorTable.RANK,
                SqlStdOperatorTable.DENSE_RANK,
                SqlStdOperatorTable.NTILE,
                SqlStdOperatorTable.LAG,
                SqlStdOperatorTable.LEAD,
                SqlStdOperatorTable.FIRST_VALUE,
                SqlStdOperatorTable.LAST_VALUE,
            ).forEach { it.shouldBeInstanceOf<SqlAggFunction>() }
        }

        "(e) SqlLibraryOperators.SAFE_CAST has kind SAFE_CAST" {
            SqlLibraryOperators.SAFE_CAST.kind shouldBe SqlKind.SAFE_CAST
        }

        "(f) SqlStdOperatorTable.LISTAGG has kind LISTAGG" {
            SqlStdOperatorTable.LISTAGG.kind shouldBe SqlKind.LISTAGG
        }
    })
