// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.tatrman.ttrp.ast.FragmentDecomposition
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.parser.TtrpAstDump
import org.tatrman.ttrp.parser.generated.TTRBParser

/**
 * Test-only helper: loads a TTR-B corpus fixture and runs the skin-classified TTR-B parse over
 * its verbatim bytes (the bare-program interior IS the fixture — `#` header comments ride the
 * hidden channel). The skin follows the fixture's extension: `*.ttrb-cs` reads Czech, anything
 * else English. Returns the parse tree + collected syntax errors so the parse specs can assert
 * clean acceptance without the full decompose pipeline.
 */
object TtrbCorpus {
    data class Parsed(
        val tree: TTRBParser.FragmentProgramContext,
        val syntaxErrors: List<String>,
    )

    fun read(rel: String): String =
        TtrbCorpus::class.java
            .getResourceAsStream("/ttrb/$rel")
            ?.readBytes()
            ?.decodeToString()
            ?: error("corpus fixture not found: /ttrb/$rel")

    /** The skin a fixture is written in — by its extension (`.ttrb-cs` ⇒ Czech). */
    fun skinOf(rel: String): TtrbSkin = if (rel.endsWith(".ttrb-cs")) TtrbSkin.CS else TtrbSkin.EN

    /** Parse arbitrary TTR-B source (a fixture body or a single sentence) under [skin]. */
    fun parse(
        source: String,
        skin: TtrbSkin = TtrbSkin.EN,
    ): Parsed {
        val parsed = TtrbSyntax.parse(source, skin)
        return Parsed(parsed.tree, parsed.syntaxErrors.map { "${it.line}:${it.column} ${it.message}" })
    }

    fun parseFixture(rel: String): Parsed = parse(read(rel), skinOf(rel))

    /** Decompose a bare fixture directly (no host container) — for the decomposition specs. */
    fun decompose(
        rel: String,
        outPort: String? = null,
        skin: TtrbSkin = skinOf(rel),
        inPorts: Set<String> = emptySet(),
        outPorts: Set<String>? = null,
    ): FragmentDecomposition {
        val src = read(rel)
        val interior =
            SourceLocation(
                file = "/ttrb/$rel",
                line = 1,
                column = 0,
                endLine = 1,
                endColumn = 0,
                offsetStart = 0,
                offsetEnd = src.length,
            )
        return TtrB.decompose(src, interior, outPort, skin, inPorts = inPorts, outPorts = outPorts)
    }

    /**
     * The sentence kinds of a fixture in order — the statement alternative of each sentence
     * (`LoadSentenceContext` → `load`, `KeepColumnsSentenceContext` → `keep-columns`).
     */
    fun sentenceKinds(rel: String): List<String> = kinds(parseFixture(rel).tree)

    fun kinds(tree: TTRBParser.FragmentProgramContext): List<String> {
        val out = mutableListOf<String>()

        fun walk(node: org.antlr.v4.runtime.tree.ParseTree) {
            if (node is TTRBParser.StatementContext) {
                out += kebab(node::class.java.simpleName.removeSuffix("SentenceContext"))
                return
            }
            if (node is TTRBParser.BlockContext) out += "block"
            for (i in 0 until node.childCount) walk(node.getChild(i))
            if (node is TTRBParser.BlockContext) out += "end-block"
        }
        walk(tree)
        return out
    }

    /**
     * A location-free rendering of lowered statements — the canonical tree two surfaces must
     * agree on (the deterministic AST dump with every `loc` span dropped).
     */
    fun canonical(statements: List<Statement>): String =
        TtrpAstDump
            .dump(TtrpDocument(statements, SourceLocation.UNKNOWN))
            .lineSequence()
            .filterNot { it.trimStart().startsWith("\"loc\":") }
            .joinToString("\n")

    private fun kebab(camel: String): String = camel.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
}
