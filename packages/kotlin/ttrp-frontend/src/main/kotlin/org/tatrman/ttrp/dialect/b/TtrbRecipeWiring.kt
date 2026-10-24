// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.tatrman.ttrp.ast.RouteRef
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Wiring
import org.tatrman.ttrp.ast.WiringArg
import org.tatrman.ttrp.ast.WiringValue
import org.tatrman.ttrp.dialect.sql.TtrSqlLoc
import org.tatrman.ttrp.parser.TtrpParser
import org.tatrman.ttrp.parser.generated.TTRBParser

/** Optional controlled wiring sentence. Routes come explicitly from the canonical skeleton; none are guessed. */
object TtrbRecipeWiring {
    fun parse(
        source: String,
        routes: List<RouteRef>,
        location: SourceLocation,
        skin: TtrbSkin = TtrbSkin.EN,
    ): List<Wiring> {
        require(routes.isNotEmpty()) { "a wiring sentence needs explicit skeleton routes" }
        val parser = TTRBParser(CommonTokenStream(ListTokenSource(TtrbSyntax.tokens(source, skin))))
        val errors = mutableListOf<String>()
        parser.removeErrorListeners()
        parser.addErrorListener(
            object : BaseErrorListener() {
                override fun syntaxError(
                    r: Recognizer<*, *>?,
                    sym: Any?,
                    line: Int,
                    col: Int,
                    msg: String,
                    e: RecognitionException?,
                ) {
                    errors +=
                        msg
                }
            },
        )
        val tree = parser.wiringProgram()
        require(errors.isEmpty()) { errors.joinToString("; ") }
        val loc = TtrSqlLoc(location)
        val expr = TtrbExpr(loc, TtrpParser.catalog, skin)
        return tree.wiringSentence().map { sentence ->
            Wiring(
                routes,
                sentence.action.text,
                sentence.wiringPair().map { pair ->
                    val value = expr.foldExpr(pair.value)
                    WiringArg(pair.column.text, WiringValue.Expr(value, loc.of(pair.value)), loc.of(pair))
                },
                null,
                loc.of(sentence),
                loc.of(sentence.action),
            )
        }
    }
}
