// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

/**
 * Plain-text `.ttrg` graph-block READER (T3, TP-5). Deliberately **not** an AST
 * parser — checked the existing TS edit synthesizer (`@tatrman/edit/src/
 * graph-edits.ts`) before assuming one was needed, and found that even the real,
 * shipped TS implementation is plain keyword/bracket text-scanning, not
 * `@tatrman/parser`-based — there is no Kotlin `.ttrg` AST walker to reuse or
 * port (only the `.ttrl` sidecar has one, `TtrlLoader`/T1). Matching the repo's
 * own actual precedent rather than inventing a new parsing layer.
 *
 * FO-21 (FO-P0.S2.T5): this is the read-half / shared parse lib (move manifest §6).
 * The MUTATORS (`addObject`/`removeObject`/`createContent`) split out to
 * `TtrgGraphMutations` (ttr-designer-server) — the edit half. `parseHeader`/
 * `findObjectsBrackets`/`parseObjects` STAY here: they back the designer server's
 * read handlers (`listGraphs`/`getGraph`) AND the mutators, so they are the shared
 * substrate both halves depend on.
 *
 * VL-P0 (viewer layouts, contracts V-14): moved here from `ttr-designer-server`
 * (`graph/TtrgGraphFile.kt`) so the metadata service reads `.ttrg` LAYOUT files through
 * the same reader, and grown [scan] / [parseLayoutBlock] / [parseObjectRefs] for the
 * in-file `layout: { nodes: { … } }` block. Those three use a small tokenizer that skips
 * comments and strings and reads bare or quoted keys (V-12) — the same approach as the
 * viewer's TypeScript reader (`packages/designer/src/layout/layout-ttr.ts`); the shared
 * vectors (`packages/package-sdk/schemas/layout-vectors/`) hold the two together.
 */
object TtrgGraphReader {
    private val GRAPH_HEADER = Regex("""graph\s+(\w+)\s*\{""")
    private val MODEL_PROPERTY = Regex("""\bmodel\s*:\s*(\w+)""")

    data class Header(
        val name: String,
        val schema: String?,
    )

    /** `graph <name> { model: <schema>, … }` header — null if the text isn't a graph file. */
    fun parseHeader(content: String): Header? {
        val m = GRAPH_HEADER.find(content) ?: return null
        val schema = MODEL_PROPERTY.find(content)?.groupValues?.get(1)
        return Header(name = m.groupValues[1], schema = schema)
    }

    /** The `objects: [ … ]` bracket extent (brace/bracket-depth matched from the FIRST `objects` keyword). */
    fun findObjectsBrackets(content: String): IntRange? {
        val kwIdx = content.indexOf("objects")
        if (kwIdx == -1) return null
        val openIdx = content.indexOf('[', kwIdx)
        if (openIdx == -1) return null
        var depth = 0
        var closeIdx = -1
        for (i in openIdx until content.length) {
            when (content[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        closeIdx = i
                        break
                    }
                }
            }
        }
        if (closeIdx == -1) return null
        return openIdx..closeIdx
    }

    /** The qnames currently listed in `objects: […]` (comma-split, trimmed, blanks dropped). */
    fun parseObjects(content: String): List<String> {
        val brackets = findObjectsBrackets(content) ?: return emptyList()
        val inner = content.substring(brackets.first + 1, brackets.last)
        return inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Mutators (addObject/removeObject/createContent + hasImportFor) split out to
    // TtrgGraphMutations (FO-P0.S2.T5, move manifest §6).

    // ── VL-P0: the layout reads ──────────────────────────────────────────────

    /**
     * Scan the graph block: header, `model`, `objects` (string literals unquoted) and the in-file
     * `layout.nodes` block, in file order. Comments and strings never fake a block. `description`
     * and `tags` are graph metadata, reported as warnings; a `viewport` (never persisted, C1-b) and
     * `edges` (reserved) are skipped silently.
     */
    fun scan(content: String): TtrgScan = TtrgScanner.scan(content)

    /**
     * The in-file `layout: { nodes: { <ref>: { x, y } … } }` block as ref → (x, y), in file order;
     * empty when the graph has no layout block. Keys bare or quoted, coordinates integer or decimal.
     *
     * @throws TtrgParseException when the text is not a graph file or the block is malformed.
     */
    fun parseLayoutBlock(content: String): Map<String, Pair<Double, Double>> = scanOrThrow(content).nodes

    /** The `objects: [ … ]` refs with string literals unquoted (V-12) — unlike [parseObjects]. */
    fun parseObjectRefs(content: String): List<String> = scanOrThrow(content).objects

    private fun scanOrThrow(content: String): TtrgGraph {
        val s = scan(content)
        s.errors.firstOrNull()?.let { throw TtrgParseException(it) }
        return s.graph!!
    }
}

/** One finding while scanning a `.ttrg` — `path` names the field (`objects`, `positions.<ref>.x`). */
data class TtrgIssue(
    val path: String,
    val message: String,
    val line: Int? = null,
)

class TtrgParseException(
    val issue: TtrgIssue,
) : IllegalArgumentException(issue.line?.let { "line $it: ${issue.message}" } ?: issue.message)

/** A `.ttrg` graph block as written — before any layout-document rule is applied. */
data class TtrgGraph(
    val name: String,
    val model: String?,
    val objects: List<String>,
    /** the in-file `layout.nodes` block, verbatim, in file order */
    val nodes: Map<String, Pair<Double, Double>>,
    val nameLine: Int,
)

/** [TtrgGraphReader.scan]'s result: `graph` is null iff `errors` is non-empty. */
data class TtrgScan(
    val graph: TtrgGraph?,
    val errors: List<TtrgIssue>,
    val warnings: List<TtrgIssue>,
)
