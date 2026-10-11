// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.format

import org.tatrman.ttrp.ast.Arg
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ChainElem
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ConfigBlock
import org.tatrman.ttrp.ast.ContainerBody
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.ControlBlock
import org.tatrman.ttrp.ast.ControlDep
import org.tatrman.ttrp.ast.ControlKind
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.GroupByEntry
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.ImportDecl
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.PortDecl
import org.tatrman.ttrp.ast.PortKind
import org.tatrman.ttrp.ast.RelationArg
import org.tatrman.ttrp.ast.SchemaColumn
import org.tatrman.ttrp.ast.SchemaDecl
import org.tatrman.ttrp.ast.SchemaLiteralArg
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.ast.Trivia
import org.tatrman.ttrp.ast.UsesWorld
import org.tatrman.ttrp.parser.TtrpParser

/**
 * The canonical-text formatter (C3-a). Operates on the lossless parse (AST + trivia +
 * verbatim fragment `sourceText`), never on regex. Deterministic — the same input
 * always yields the same output (C1-d; the Designer depends on it in Stage 5.4).
 *
 * Rules:
 *  - **Chain reflow (C3-a):** a variable assigned once, used once, and consumed as a
 *    chain source is inlined into that chain (the intermediate name disappears); a
 *    variable that fans out (multi-use), is reassigned (SSA > 1), or is consumed as a
 *    port/arg keeps its name.
 *  - **Width (C3-a-iii):** a chain that fits in [MAX_LINE] stays one line; otherwise
 *    each `->` segment breaks onto its own 2-space continuation line. Config blocks
 *    (`aggregate { … }`) always render as multiline blocks. The config-vs-args *choice*
 *    is author-preserved (both are grammatical and semantically identical; converting is
 *    a rewrite, out of the formatter's remit).
 *  - **Fragments (C2-f):** tagged-block interiors are copied byte-for-byte; the fence
 *    and header normalize, the interior never changes. Bare-fragment files
 *    (`.ttr.sql`/`.ttr.py`/`.ttrb`/`.ttrb-cs`) are returned unchanged (never formatted).
 */
class TtrpFormatter {
    companion object {
        const val MAX_LINE = 100
        private const val INDENT = "  "

        fun isBareFragmentFile(uri: String): Boolean =
            uri.endsWith(".ttr.sql") || uri.endsWith(".ttr.py") || uri.endsWith(".ttrb") || uri.endsWith(".ttrb-cs")
    }

    /** Returns the formatted document text, or [source] unchanged when it must not be formatted. */
    fun format(
        source: String,
        uri: String = "<memory>",
    ): String {
        if (isBareFragmentFile(uri)) return source
        val parsed = TtrpParser.parseString(source, uri)
        // Never reflow a document that failed to parse — round-tripping is only safe over a clean tree.
        if (parsed.diagnostics.any { it.severity == org.tatrman.ttrp.diagnostics.Severity.ERROR }) return source
        val body = renderStatements(parsed.document.statements, indent = 0)
        return if (body.isEmpty()) "" else body + "\n"
    }

    // ---- statement lists (program level or a container body) ----

    private fun renderStatements(
        statements: List<Statement>,
        indent: Int,
    ): String {
        val reflow = Reflow(statements)
        val out = mutableListOf<String>()
        val carried = mutableListOf<Trivia>()
        for (stmt in statements) {
            if (reflow.isAbsorbed(stmt)) {
                // The producer statement disappears into its consumer, but its comments must
                // not (C2-f lossless) — carry them onto the next rendered statement.
                carried += stmt.leadingTrivia
                carried += stmt.trailingTrivia
                continue
            }
            out += renderStatement(stmt, indent, reflow, carried)
            carried.clear()
        }
        // Defensive: comments from a trailing absorbed statement with no consumer after it.
        if (carried.isNotEmpty()) {
            val pad = INDENT.repeat(indent)
            out += carried.joinToString("\n") { "$pad${it.text.trim()}" }
        }
        return out.joinToString("\n")
    }

    private fun renderStatement(
        stmt: Statement,
        indent: Int,
        reflow: Reflow,
        carried: List<Trivia> = emptyList(),
    ): String {
        val pad = INDENT.repeat(indent)
        val leading = (carried + stmt.leadingTrivia).joinToString("") { "$pad${it.text.trim()}\n" }
        val trailing = stmt.trailingTrivia.joinToString("") { "  ${it.text.trim()}" }
        val core =
            when (stmt) {
                is UsesWorld -> "${pad}uses world \"${stmt.world}\""
                is ImportDecl -> "${pad}import ${stmt.qname.text}${if (stmt.wildcard) ".*" else ""}"
                is SchemaDecl -> "${pad}def schema ${stmt.name} { ${stmt.columns.joinToString(", ") { col(it) }} }"
                is ContainerDecl -> renderContainer(stmt, indent)
                is ControlDep -> pad + renderControl(stmt)
                is ControlBlock ->
                    "${pad}control {\n" +
                        stmt.deps.joinToString("\n") { "$pad$INDENT${renderControl(it)}" } +
                        "\n$pad}"
                is Assignment ->
                    "$pad${stmt.target} = " +
                        renderChain(reflow.expand(stmt.chain.elements), indent, "${stmt.target} = ".length)
                is ChainStmt -> pad + renderChain(reflow.expand(stmt.chain.elements), indent, 0)
                else -> pad + "" // ProgramHeader is a reject; never emitted.
            }
        return leading + core + trailing
    }

    private fun renderControl(c: ControlDep): String =
        when (c.kind) {
            ControlKind.FS -> "${c.subject} after ${c.reference}"
            ControlKind.SS -> "${c.subject} with ${c.reference}"
            ControlKind.FF -> "${c.subject} finishes with ${c.reference}"
        }

    private fun renderContainer(
        c: ContainerDecl,
        indent: Int,
    ): String {
        val pad = INDENT.repeat(indent)
        val ports = if (c.ports.isEmpty()) "" else "(${c.ports.joinToString(", ") { port(it) }})"
        val header = "${pad}container ${c.name}$ports target ${c.target.text}"
        return when (val b: ContainerBody = c.body) {
            is FragmentBody -> renderFragment(header, b)
            is FlowBody -> {
                val inner = renderStatements(b.statements, indent + 1)
                "$header {\n$inner\n$pad}"
            }
            else -> header
        }
    }

    /**
     * Fence + header normalize; the interior bytes (`sourceText`) are copied verbatim (C2-f). A
     * file-backed body (AG B5) keeps its `from "<path>"` clause — the interior lives in the file.
     */
    private fun renderFragment(
        header: String,
        b: FragmentBody,
    ): String =
        b.sourceFile?.let { "$header from \"$it\"" }
            ?: "$header \"\"\"${b.tag}\n${b.sourceText}\"\"\""

    private fun port(p: PortDecl): String {
        val kind =
            when (p.kind) {
                PortKind.IN -> "in"
                PortKind.OUT -> "out"
                PortKind.ERR -> "err"
            }
        return "$kind ${p.name}"
    }

    private fun col(c: SchemaColumn): String = "${c.name}: ${c.type}"

    // ---- chains ----

    private fun renderChain(
        elements: List<ChainElem>,
        indent: Int,
        prefixLen: Int,
    ): String {
        val pad = INDENT.repeat(indent)
        val hasBlock = elements.any { it is OpCall && it.config != null }
        val oneLine = elements.joinToString(" -> ") { renderElemInline(it) }
        if (!hasBlock && pad.length + prefixLen + oneLine.length <= MAX_LINE) {
            return oneLine
        }
        // Multiline: head on the current line, each `-> segment` on its own continuation line.
        val contPad = pad + INDENT
        val head = renderElem(elements.first(), indent + 1)
        val rest =
            elements.drop(1).joinToString("") { e ->
                "\n$contPad-> ${renderElem(e, indent + 1)}"
            }
        return head + rest
    }

    /** Inline (single-line) render of a chain element — used for the fits-on-one-line path. */
    private fun renderElemInline(e: ChainElem): String =
        when (e) {
            is DottedRef -> e.parts.joinToString(".")
            is OpCall -> "${e.name}(${argList(e.args)})"
        }

    /** Render an element, allowing multiline config blocks at continuation [indent]. */
    private fun renderElem(
        e: ChainElem,
        indent: Int,
    ): String =
        when (e) {
            is DottedRef -> e.parts.joinToString(".")
            is OpCall -> {
                val cfg = e.config
                if (cfg != null) "${e.name} ${renderConfig(cfg, indent)}" else "${e.name}(${argList(e.args)})"
            }
        }

    private fun argList(args: List<Arg>): String = args.joinToString(", ") { arg(it) }

    private fun arg(a: Arg): String {
        val name = a.name?.let { "$it: " } ?: ""
        return name + argValue(a)
    }

    private fun argValue(a: Arg): String =
        when (val v = a.value) {
            is RelationArg -> "relation ${v.qname.text}"
            is SchemaLiteralArg -> "{ ${v.columns.joinToString(", ") { col(it) }} }"
            is ExprArg -> ExprRenderer.render(v.expr)
        }

    private fun renderConfig(
        config: ConfigBlock,
        indent: Int,
    ): String {
        val pad = INDENT.repeat(indent)
        val entryPad = pad + INDENT
        val entries =
            config.entries.joinToString("\n") { e ->
                entryPad +
                    when (e) {
                        is GroupByEntry -> "group by ${e.keys.joinToString(", ")}"
                        is AssignEntry -> "${e.name} = ${ExprRenderer.render(e.value)}"
                        else -> ""
                    }
            }
        return "{\n$entries\n$pad}"
    }

    /**
     * Chain-inlining analysis over one statement list. A variable is inlinable when it is
     * assigned exactly once, used exactly once, and that single use is a bare chain source
     * (`x -> …`). Inlinable assignments are absorbed into their consumer.
     */
    private inner class Reflow(
        statements: List<Statement>,
    ) {
        private val producers = mutableMapOf<String, Assignment>()
        private val assignCount = mutableMapOf<String, Int>()
        private val chainSourceUses = mutableMapOf<String, Int>()
        private val totalUses = mutableMapOf<String, Int>()

        init {
            for (s in statements) {
                if (s is Assignment) {
                    assignCount.merge(s.target, 1, Int::plus)
                    producers[s.target] = s
                }
            }
            for (s in statements) {
                val chain =
                    when (s) {
                        is Assignment -> s.chain.elements
                        is ChainStmt -> s.chain.elements
                        else -> continue
                    }
                chain.forEachIndexed { i, e ->
                    when (e) {
                        is DottedRef -> {
                            val head = e.parts.first()
                            totalUses.merge(head, 1, Int::plus)
                            if (i == 0 && e.parts.size == 1) chainSourceUses.merge(head, 1, Int::plus)
                        }
                        is OpCall ->
                            e.args.forEach { a ->
                                val ref = (a.value as? ExprArg)?.expr as? org.tatrman.ttrp.expr.ColumnRef
                                if (ref != null && ref.port == null) totalUses.merge(ref.column, 1, Int::plus)
                            }
                    }
                }
            }
        }

        private fun inlinable(name: String): Boolean =
            assignCount[name] == 1 && totalUses[name] == 1 && chainSourceUses[name] == 1

        fun isAbsorbed(stmt: Statement): Boolean = stmt is Assignment && inlinable(stmt.target)

        /** Expand a chain, splicing inlinable producers in front of their consumer's chain. */
        fun expand(elements: List<ChainElem>): List<ChainElem> {
            val head = elements.firstOrNull() as? DottedRef ?: return elements
            if (head.parts.size != 1 || !inlinable(head.parts.first())) return elements
            val producer = producers[head.parts.first()] ?: return elements
            return expand(producer.chain.elements) + elements.drop(1)
        }
    }
}
