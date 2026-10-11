// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.TtrpType

/**
 * A cast calc on a Postgres island renders raw (the CTE planner's raw project body, [RejectGuardSql.renderArg]) —
 * and named the TTR-P canonical spelling as the target: `cast(order_id as string)` became
 * `CAST("order_id" AS string)`, which Postgres rejects. Every target is now a Postgres type name.
 */
class PgCastRenderSpec :
    StringSpec({
        fun cast(t: TtrpType) =
            RejectGuardSql.renderArg(Cast(ColumnRef(null, "x", SourceLocation.UNKNOWN), t, SourceLocation.UNKNOWN))

        "every TTR-P cast target renders as a Postgres type" {
            cast(TtrpType.Str) shouldBe "CAST(\"x\" AS text)"
            cast(TtrpType.Integer) shouldBe "CAST(\"x\" AS integer)"
            cast(TtrpType.Float) shouldBe "CAST(\"x\" AS float)"
            cast(TtrpType.Double) shouldBe "CAST(\"x\" AS double precision)"
            cast(TtrpType.Number) shouldBe "CAST(\"x\" AS numeric)"
            cast(TtrpType.Decimal()) shouldBe "CAST(\"x\" AS decimal)"
            cast(TtrpType.Decimal(12, 2)) shouldBe "CAST(\"x\" AS decimal(12,2))"
            cast(TtrpType.Bool) shouldBe "CAST(\"x\" AS boolean)"
            cast(TtrpType.Date) shouldBe "CAST(\"x\" AS date)"
            cast(TtrpType.Datetime) shouldBe "CAST(\"x\" AS timestamp)"
            cast(TtrpType.Timestamp) shouldBe "CAST(\"x\" AS timestamp)"
            cast(TtrpType.Named("bigint")) shouldBe "CAST(\"x\" AS bigint)"
        }

        "a cast to object / list / an unknown type id is a clear emit error" {
            shouldThrow<TtrpEmitException> { cast(TtrpType.Lst) }
            shouldThrow<TtrpEmitException> { cast(TtrpType.Named("geography")) }
        }
    })
