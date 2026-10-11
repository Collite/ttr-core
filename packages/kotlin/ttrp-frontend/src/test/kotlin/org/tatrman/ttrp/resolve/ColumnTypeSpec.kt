// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.resolve.ColumnType.Kind

/**
 * [ColumnType] — the scalar column vocabulary an engine produces a declared column in: the TTR-M keywords
 * plus the SQL spellings a generated model carries, in all three written forms (bare, `decimal(19,2)`, the
 * TTR-M structured `{ type: decimal, length: 19, precision: 2 }`); a decimal without a precision is
 * DECIMAL(19, 2).
 */
class ColumnTypeSpec :
    StringSpec({
        "a bare decimal is DECIMAL(19, 2); numeric and number are decimals too" {
            ColumnType.parse("decimal") shouldBe ColumnType(Kind.DECIMAL, precision = 19, scale = 2)
            ColumnType.parse("numeric") shouldBe ColumnType(Kind.DECIMAL, precision = 19, scale = 2)
            ColumnType.parse("number") shouldBe ColumnType(Kind.DECIMAL, precision = 19, scale = 2)
        }

        "a declared precision/scale is kept in every written form" {
            val d = ColumnType(Kind.DECIMAL, precision = 12, scale = 4)
            ColumnType.parse("decimal(12,4)") shouldBe d
            ColumnType.parse("decimal(12, 4)") shouldBe d
            ColumnType.parse("{type:decimal,length:12,precision:4}") shouldBe d
            ColumnType.parse("{ type: decimal, length: 12, precision: 4 }") shouldBe d
            ColumnType.of("decimal", length = 12, precision = 4) shouldBe d
            ColumnType.parse("decimal(12)") shouldBe ColumnType(Kind.DECIMAL, precision = 12, scale = 0)
        }

        "money is DECIMAL(19, 4), smallmoney DECIMAL(10, 4)" {
            ColumnType.parse("money") shouldBe ColumnType(Kind.DECIMAL, precision = 19, scale = 4)
            ColumnType.parse("smallmoney") shouldBe ColumnType(Kind.DECIMAL, precision = 10, scale = 4)
        }

        "an invalid decimal is not a column type" {
            ColumnType.parse("decimal(40,2)").shouldBeNull()
            ColumnType.parse("decimal(4,6)").shouldBeNull()
        }

        "text spellings carry their length" {
            ColumnType.parse("text") shouldBe ColumnType(Kind.TEXT)
            ColumnType.parse("varchar(40)") shouldBe ColumnType(Kind.TEXT, length = 40)
            ColumnType.parse("{type:varchar,length:40}") shouldBe ColumnType(Kind.TEXT, length = 40)
            ColumnType.parse("nvarchar") shouldBe ColumnType(Kind.TEXT)
        }

        "the SQL spellings a generated model carries" {
            ColumnType.parse("bigint")!!.kind shouldBe Kind.BIGINT
            ColumnType.parse("smallint")!!.kind shouldBe Kind.SMALLINT
            ColumnType.parse("tinyint")!!.kind shouldBe Kind.TINYINT
            ColumnType.parse("real")!!.kind shouldBe Kind.REAL
            ColumnType.parse("bit")!!.kind shouldBe Kind.BOOL
            ColumnType.parse("time")!!.kind shouldBe Kind.TIME
            ColumnType.parse("datetime2")!!.kind shouldBe Kind.TIMESTAMP
            ColumnType.parse("BigInt")!!.kind shouldBe Kind.BIGINT
        }

        "object, list and unknown ids are not column types" {
            ColumnType.parse("object").shouldBeNull()
            ColumnType.parse("list").shouldBeNull()
            ColumnType.parse("geography").shouldBeNull()
            ColumnType.of(TtrpType.Lst).shouldBeNull()
            ColumnType.of(TtrpType.Named("calc")).shouldBeNull()
        }

        "a TTR-P expression type maps to its column type; a known SQL spelling in a Named does too" {
            ColumnType.of(TtrpType.Integer)!!.kind shouldBe Kind.INT
            ColumnType.of(TtrpType.Str)!!.kind shouldBe Kind.TEXT
            ColumnType.of(TtrpType.Decimal(12, 2)) shouldBe ColumnType(Kind.DECIMAL, precision = 12, scale = 2)
            ColumnType.of(TtrpType.Named("bigint"))!!.kind shouldBe Kind.BIGINT
        }

        "accepts: text takes any scalar; numerics widen from integers; timestamps take either kind" {
            val text = ColumnType.parse("text")!!
            val int = ColumnType.parse("int")!!
            val bigint = ColumnType.parse("bigint")!!
            val dec = ColumnType.parse("decimal")!!
            val float = ColumnType.parse("float")!!
            val date = ColumnType.parse("date")!!
            val ts = ColumnType.parse("timestamp")!!
            val dt = ColumnType.parse("datetime")!!
            listOf(int, dec, float, date, ts).forEach { text.accepts(it) shouldBe true }
            bigint.accepts(int) shouldBe true
            int.accepts(bigint) shouldBe true
            dec.accepts(int) shouldBe true
            float.accepts(int) shouldBe true
            int.accepts(dec) shouldBe false
            float.accepts(dec) shouldBe false
            int.accepts(text) shouldBe false
            ts.accepts(dt) shouldBe true
            ts.accepts(date) shouldBe false
            date.accepts(ts) shouldBe false
        }
    })
