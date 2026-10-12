// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

/**
 * The tokenizer + scanner behind [TtrgGraphReader.scan] (VL-P0, contracts V-14) — the JVM twin of the
 * TypeScript reader (`packages/parser/src/layout/layout-ttr.ts`): words (an `id` with its
 * dots), numbers, strings (`"…"` with backslash escapes, `"""…"""`), punctuation; `//` and block
 * comments skipped. Properties take an optional `:` / `=` separator and optional commas, as the
 * grammar's `graphBlock` and `object_` rules do; node keys may be bare or quoted (V-12).
 */
internal object TtrgScanner {
    private enum class Kind { WORD, NUMBER, STRING, PUNCT }

    private data class Tok(
        val kind: Kind,
        val text: String,
        val line: Int,
    )

    private class Fail(
        val issue: TtrgIssue,
    ) : RuntimeException(issue.message)

    private val NUMBER = Regex("""-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""").toPattern()

    private fun isWordStart(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c == '_' || c in '\u00C0'..'\u024F'

    private fun isWordChar(c: Char) = isWordStart(c) || c in '0'..'9' || c == '.'

    private fun tokenize(src: String): List<Tok> {
        val toks = mutableListOf<Tok>()
        var line = 1
        var i = 0

        fun newlines(
            from: Int,
            to: Int,
        ) = (from until to).count { src[it] == '\n' }
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\n' -> {
                    line++
                    i++
                }
                c == ' ' || c == '\t' || c == '\r' || c == '\u000C' || c == '\uFEFF' -> i++
                c == '/' && src.getOrNull(i + 1) == '/' -> while (i < src.length && src[i] != '\n') i++
                c == '/' && src.getOrNull(i + 1) == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    if (end < 0) throw Fail(TtrgIssue("", "unterminated block comment", line))
                    line += newlines(i, end)
                    i = end + 2
                }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3)
                    if (end < 0) throw Fail(TtrgIssue("", "unterminated triple-quoted string", line))
                    toks += Tok(Kind.STRING, src.substring(i + 3, end), line)
                    line += newlines(i, end)
                    i = end + 3
                }
                c == '"' -> {
                    var j = i + 1
                    val out = StringBuilder()
                    while (j < src.length && src[j] != '"' && src[j] != '\n') {
                        if (src[j] == '\\' && j + 1 < src.length && src[j + 1] != '\n') {
                            out.append(src[j + 1])
                            j += 2
                        } else {
                            out.append(src[j++])
                        }
                    }
                    if (src.getOrNull(j) != '"') throw Fail(TtrgIssue("", "unterminated string", line))
                    toks += Tok(Kind.STRING, out.toString(), line)
                    i = j + 1
                }
                (c == '-' || c in '0'..'9') && NUMBER.matcher(src).region(i, src.length).lookingAt() -> {
                    val m = NUMBER.matcher(src).region(i, src.length)
                    m.lookingAt()
                    toks += Tok(Kind.NUMBER, m.group(), line)
                    i = m.end()
                }
                isWordStart(c) -> {
                    var j = i + 1
                    while (j < src.length && isWordChar(src[j])) j++
                    toks += Tok(Kind.WORD, src.substring(i, j), line)
                    i = j
                }
                else -> {
                    toks += Tok(Kind.PUNCT, c.toString(), line)
                    i++
                }
            }
        }
        return toks
    }

    private class Cursor(
        val toks: List<Tok>,
    ) {
        var p = 0

        fun peek(): Tok? = toks.getOrNull(p)

        fun next(): Tok? = toks.getOrNull(p++)

        fun atEnd() = p >= toks.size

        fun isPunct(ch: String): Boolean = peek()?.let { it.kind == Kind.PUNCT && it.text == ch } ?: false

        /** an optional `:` / `=` property separator (`propSep`) */
        fun sep() {
            if (isPunct(":") || isPunct("=")) p++
        }

        fun expect(
            ch: String,
            issue: TtrgIssue,
        ) {
            if (!isPunct(ch)) throw Fail(if (issue.line == null) issue.copy(line = peek()?.line) else issue)
            p++
        }

        /** skip one value: a scalar, a `word(…)` call, or a balanced `{…}` / `[…]` / `(…)` */
        fun skipValue() {
            val t = next() ?: return
            if (t.kind == Kind.WORD && isPunct("(")) {
                skipBalanced(next()!!)
            } else if (t.kind == Kind.PUNCT && t.text in OPEN) {
                skipBalanced(t)
            }
        }

        private fun skipBalanced(open: Tok) {
            var depth = 1
            while (depth > 0) {
                val t = next() ?: throw Fail(TtrgIssue("", "unbalanced `${open.text}`", open.line))
                if (t.kind == Kind.PUNCT && t.text in OPEN) depth++
                if (t.kind == Kind.PUNCT && t.text in CLOSE) depth--
            }
        }

        companion object {
            val OPEN = setOf("{", "[", "(")
            val CLOSE = setOf("}", "]", ")")
        }
    }

    private fun parseNodeMap(
        c: Cursor,
        nodes: MutableMap<String, Pair<Double, Double>>,
    ) {
        c.expect("{", TtrgIssue("nodes", "nodes must be { <ref>: { x, y } … }"))
        while (true) {
            if (c.isPunct("}")) {
                c.next()
                return
            }
            if (c.isPunct(",")) {
                c.next()
                continue
            }
            val key = c.next() ?: throw Fail(TtrgIssue("nodes", "unclosed nodes block"))
            if (key.kind != Kind.WORD && key.kind != Kind.STRING) {
                throw Fail(TtrgIssue("nodes", "expected a node key, got `${key.text}`", key.line))
            }
            val path = "positions.${key.text}"
            c.sep()
            c.expect("{", TtrgIssue(path, "$path must be { x, y }", key.line))
            var x: Double? = null
            var y: Double? = null
            while (true) {
                if (c.isPunct("}")) {
                    c.next()
                    break
                }
                if (c.isPunct(",")) {
                    c.next()
                    continue
                }
                val axis = c.next() ?: throw Fail(TtrgIssue(path, "unclosed $path", key.line))
                c.sep()
                val v = c.next()
                if (axis.text != "x" && axis.text != "y") {
                    throw Fail(
                        TtrgIssue("$path.${axis.text}", "a node coordinate is x or y (got ${axis.text})", axis.line),
                    )
                }
                if (v?.kind !=
                    Kind.NUMBER
                ) {
                    throw Fail(TtrgIssue("$path.${axis.text}", "${axis.text} must be a number", axis.line))
                }
                if (axis.text == "x") x = v.text.toDouble() else y = v.text.toDouble()
            }
            if (x == null) throw Fail(TtrgIssue("$path.x", "missing x", key.line))
            if (y == null) throw Fail(TtrgIssue("$path.y", "missing y", key.line))
            nodes[key.text] = x to y
        }
    }

    fun scan(content: String): TtrgScan {
        val warnings = mutableListOf<TtrgIssue>()
        return try {
            val toks = tokenize(content)
            val at =
                toks.indices.firstOrNull { k ->
                    toks[k].kind == Kind.WORD &&
                        toks[k].text == "graph" &&
                        toks.getOrNull(k + 1)?.kind == Kind.WORD &&
                        toks.getOrNull(k + 2)?.let { it.kind == Kind.PUNCT && it.text == "{" } == true
                } ?: return TtrgScan(null, listOf(TtrgIssue("", "no `graph <name> { … }` block")), warnings)
            val name = toks[at + 1].text
            val nameLine = toks[at].line
            var model: String? = null
            val objects = mutableListOf<String>()
            val nodes = linkedMapOf<String, Pair<Double, Double>>()
            val c = Cursor(toks)
            c.p = at + 3
            while (true) {
                if (c.atEnd()) throw Fail(TtrgIssue("", "unclosed graph $name", nameLine))
                if (c.isPunct("}")) break
                if (c.isPunct(",")) {
                    c.next()
                    continue
                }
                val key = c.next()!!
                if (key.kind !=
                    Kind.WORD
                ) {
                    throw Fail(TtrgIssue("", "expected a graph property, got `${key.text}`", key.line))
                }
                c.sep()
                when (key.text) {
                    "model" -> {
                        val v = c.next()
                        if (v?.kind !=
                            Kind.WORD
                        ) {
                            throw Fail(TtrgIssue("model", "model must be a model code (er, db, …)", key.line))
                        }
                        model = v.text
                    }
                    "objects" -> {
                        c.expect("[", TtrgIssue("objects", "objects must be a list", key.line))
                        while (true) {
                            if (c.isPunct("]")) {
                                c.next()
                                break
                            }
                            val item = c.next() ?: throw Fail(TtrgIssue("objects", "unclosed objects list", key.line))
                            if (item.kind != Kind.WORD && item.kind != Kind.STRING) {
                                throw Fail(TtrgIssue("objects", "unexpected `${item.text}` in objects", item.line))
                            }
                            objects += item.text
                            if (c.isPunct(",")) {
                                c.next()
                            } else if (!c.isPunct("]")) {
                                throw Fail(
                                    TtrgIssue(
                                        "objects",
                                        "expected `,` or `]` in objects",
                                        c.peek()?.line ?: item.line,
                                    ),
                                )
                            }
                        }
                    }
                    "layout" -> {
                        c.expect("{", TtrgIssue("layout", "layout must be { nodes: { … } }", key.line))
                        while (true) {
                            if (c.isPunct("}")) {
                                c.next()
                                break
                            }
                            if (c.isPunct(",")) {
                                c.next()
                                continue
                            }
                            val prop = c.next() ?: throw Fail(TtrgIssue("layout", "unclosed layout block", key.line))
                            c.sep()
                            if (prop.text == "nodes") {
                                parseNodeMap(c, nodes)
                            } else {
                                c.skipValue() // viewport (never read, C1-b) · edges (reserved)
                                if (prop.text != "viewport" && prop.text != "edges") {
                                    warnings +=
                                        TtrgIssue(
                                            "layout.${prop.text}",
                                            "unknown layout property `${prop.text}` — ignored",
                                            prop.line,
                                        )
                                }
                            }
                        }
                    }
                    else -> {
                        c.skipValue()
                        warnings +=
                            if (key.text == "description" || key.text == "tags") {
                                TtrgIssue(
                                    key.text,
                                    "`${key.text}` is graph metadata, not part of a layout — not kept",
                                    key.line,
                                )
                            } else {
                                TtrgIssue(key.text, "unknown graph property `${key.text}` — ignored", key.line)
                            }
                    }
                }
            }
            TtrgScan(TtrgGraph(name, model, objects, nodes, nameLine), emptyList(), warnings)
        } catch (e: Fail) {
            TtrgScan(null, listOf(e.issue), warnings)
        }
    }
}
