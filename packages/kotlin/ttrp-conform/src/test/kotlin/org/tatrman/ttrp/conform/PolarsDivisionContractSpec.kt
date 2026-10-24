// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.emit.polars.PolarsExprRenderer
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.FunctionCall

class PolarsDivisionContractSpec :
    FunSpec({
        test("non-recipe decimal division retains Float64 precision rather than the source decimal scale")
            .config(enabled = RecipePolars.enabled) {
                val at = SourceLocation.UNKNOWN
                val expression =
                    PolarsExprRenderer().render(
                        FunctionCall(
                            CatalogId("op.div"),
                            listOf(ColumnRef(null, "a", at), ColumnRef(null, "b", at)),
                            at,
                        ),
                    )
                val code =
                    """
                    import polars as pl
                    from decimal import Decimal
                    frame = pl.DataFrame({'a': [Decimal('1.00')], 'b': [Decimal('6.00')]}, schema={'a': pl.Decimal(12, 2), 'b': pl.Decimal(12, 2)})
                    value = frame.select($expression).item()
                    assert isinstance(value, float), type(value)
                    assert abs(value - 1.0 / 6.0) < 1e-12, value
                    """.trimIndent()
                val process =
                    ProcessBuilder(
                        System.getenv("TTRP_PYTHON") ?: "python3",
                        "-c",
                        code,
                    ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { "division probe timed out" }
                check(process.exitValue() == 0) { output }
                process.exitValue() shouldBe 0
            }
    })
