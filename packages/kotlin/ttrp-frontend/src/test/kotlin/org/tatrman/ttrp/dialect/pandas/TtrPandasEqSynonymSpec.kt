// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.pandas

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.dialect.sql.TtrSqlLoc
import org.tatrman.ttrp.parser.TtrpParser
import org.tatrman.ttrp.parser.generated.TTRPandasLexer
import org.tatrman.ttrp.parser.generated.TTRPandasParser

/**
 * S9 — `==` is a closed dialect synonym for `=` inside TTR-pandas ONLY, folding to the
 * SAME tree; `TTRP-EQ-001` still fires for `==` in canonical `.ttrp` and inside `"""sql`.
 */
class TtrPandasEqSynonymSpec :
    StringSpec({
        fun structure(e: Expression): String =
            when (e) {
                is ColumnRef -> "col(${e.port?.plus(".") ?: ""}${e.column})"
                is Literal -> "lit(${e.value})"
                is FunctionCall -> "${e.function.value}(${e.args.joinToString(",") { structure(it) }})"
                is AggregateCall -> "agg:${e.function.value}(${e.args.joinToString(",") { structure(it) }})"
                is Cast -> "cast(${structure(e.expr)})"
                is CaseWhen -> "case"
                is InList -> "in(${structure(e.expr)})"
                is IsNull -> "isnull(${structure(e.expr)})"
                is MdPath -> "mdpath(${e.components.size})" // not produced by the pandas dialect
                is org.tatrman.ttrp.expr.ParamRef, is org.tatrman.ttrp.expr.DurationExpr -> "recipe-leaf" // nor these
            }

        fun foldPandas(expr: String): Expression {
            val interior = SourceLocation("<e>", 1, 0, 1, 0, 0, expr.length)
            val parser = TTRPandasParser(CommonTokenStream(TTRPandasLexer(CharStreams.fromString(expr))))
            return TtrPandasExpr(TtrSqlLoc(interior), TtrpParser.catalog).fold(parser.expr())
        }

        "`==` and `=` fold to byte-identical expression trees (both op.eq)" {
            structure(foldPandas("status == \"open\"")) shouldBe structure(foldPandas("status = \"open\""))
            (foldPandas("a == b") as FunctionCall).function.value shouldBe "op.eq"
        }

        "`==` in a canonical .ttrp flow container is still rejected (TTRP-EQ-001)" {
            val src =
                """
                container c(in t, out r) target polars {
                  r = filter(t, status == "open")
                }
                """.trimIndent()
            TtrpParser.parseString(src, "canon.ttrp").diagnostics.map { it.id.id } shouldBe listOf("TTRP-EQ-001")
        }

        "`==` inside a \"\"\"sql fragment is rejected through the SQL path (not silently accepted)" {
            val src =
                "container c(in t, out r) target erp_pg \"\"\"sql\nSELECT id FROM t WHERE id == 1\n\"\"\""
            val ids = TtrpParser.parseString(src, "sqleq.ttrp").diagnostics.map { it.id.id }
            (ids.any { it.startsWith("TTRP-SQL-") }) shouldBe true
        }
    })
