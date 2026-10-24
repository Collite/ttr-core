// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.doc

import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** Offsets use UTF-16, start inclusive/end exclusive, matching CommonMark and LSP; canonical parser offsets are converted explicitly. */
data class DocText(
    val text: String,
    val span: List<Int>,
    val level: Int? = null,
)

data class DocBlock(
    val index: Int,
    val tag: String,
    val info: String,
    val body: String,
    val span: List<Int>,
    val bodySpan: List<Int>,
)

data class DocModel(
    val title: String,
    val paragraphs: List<DocText>,
    val headings: List<DocText>,
    val blocks: List<DocBlock>,
) {
    companion object {
        fun read(source: String): DocModel {
            val starts = listOf(0) + source.indices.filter { source[it] == '\n' }.map { it + 1 }

            fun span(n: Node): List<Int> {
                val ss = n.sourceSpans
                if (ss.isEmpty()) return listOf(0, 0)
                val first = ss.first()
                val last = ss.last()
                return listOf(
                    starts[first.lineIndex] + first.columnIndex,
                    starts[last.lineIndex] + last.columnIndex + last.length,
                )
            }

            fun text(n: Node): String =
                buildString {
                    fun walk(node: Node) {
                        if (node is Text) append(node.literal)
                        var c = node.firstChild
                        while (c != null) {
                            walk(c)
                            c = c.next
                        }
                    }
                    walk(n)
                }
            val blocks = mutableListOf<DocBlock>()
            val paragraphs = mutableListOf<DocText>()
            val headings = mutableListOf<DocText>()
            val root =
                Parser
                    .builder()
                    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                    .build()
                    .parse(source)

            fun walk(n: Node) {
                val at = span(n)
                when (n) {
                    is Paragraph -> paragraphs += DocText(text(n), at)
                    is Heading -> headings += DocText(text(n), at, n.level)
                    is FencedCodeBlock -> {
                        val info = n.info.trim().split(Regex("\\s+"), limit = 2)
                        val from = source.indexOf('\n', at[0]).let { if (it < 0) at[1] else it + 1 }
                        val lastLine = source.lastIndexOf('\n', (at[1] - 1).coerceAtLeast(0)) + 1
                        val closing = source.substring(lastLine, at[1]).trim()
                        val to =
                            if (closing.startsWith(
                                    n.fenceChar.toString().repeat(n.openingFenceLength),
                                )
                            ) {
                                lastLine
                            } else {
                                at[1]
                            }
                        blocks +=
                            DocBlock(
                                blocks.size,
                                info[0],
                                info.getOrElse(1) {
                                    ""
                                },
                                source.substring(from.coerceAtMost(to), to),
                                at,
                                listOf(from.coerceAtMost(to), to),
                            )
                    }
                }
                var child = n.firstChild
                while (child != null) {
                    walk(child)
                    child = child.next
                }
            }
            walk(root)
            return DocModel(headings.firstOrNull { it.level == 1 }?.text ?: "Recipe", paragraphs, headings, blocks)
        }
    }
}
