// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** review-125 R125-6 — one reading of an er type spelling, shared by the planner, the handle and the lint. */
class ErTypeSpec :
    FunSpec({
        test("every spelling reads as one family, with or without a size, in any case") {
            val table =
                mapOf(
                    ErType.INTEGER to listOf("int", "INTEGER", "bigint", "smallint", "tinyint", "long"),
                    ErType.DECIMAL to listOf("decimal(12,2)", "numeric", "number", "float", "double", "real", "money"),
                    ErType.BOOLEAN to listOf("bool", "boolean", "bit"),
                    ErType.DATE to listOf("date"),
                    ErType.TIMESTAMP to listOf("datetime", "datetime2", "timestamp", "timestamptz", "smalldatetime"),
                    ErType.TEXT to listOf("text", "string", "varchar(40)", "nvarchar", "char", "nchar"),
                )
            for ((type, spellings) in table) for (s in spellings) ErType.of(s) shouldBe type
            ErType.of("geometry") shouldBe null
            ErType.of(null) shouldBe null
        }

        test("the lint's granularity follows it: a date is a day, a timestamp a second, nothing else") {
            CnDefinitionLints.ofType("date") shouldBe CnDefinitionLints.Granularity.DAY
            CnDefinitionLints.ofType("timestamptz") shouldBe CnDefinitionLints.Granularity.SECOND
            CnDefinitionLints.ofType("number") shouldBe null
        }
    })
