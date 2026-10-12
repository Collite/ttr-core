// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

import org.tatrman.ttr.parser.model.LayoutDocument
import org.tatrman.ttr.parser.model.LayoutPoint

/** Where a layout's positions came from (V-11): the `.ttrg`'s in-file block or its `.ttrl` sidecar. */
enum class PositionsFrom(
    val wire: String,
) {
    IN_FILE("in-file"),
    SIDECAR("sidecar"),
}

/** [TtrgLayoutReader.read]'s result: the canonical document, or the reasons the pair could not be read. */
sealed interface TtrgLayoutRead {
    val warnings: List<TtrgIssue>

    data class Ok(
        val document: LayoutDocument,
        /** null when the document has no positions */
        val positionsFrom: PositionsFrom?,
        override val warnings: List<TtrgIssue>,
    ) : TtrgLayoutRead

    data class Failed(
        val errors: List<TtrgIssue>,
        override val warnings: List<TtrgIssue>,
    ) : TtrgLayoutRead
}

/**
 * A `.ttrg` and, optionally, its sibling `.ttrl` → one layout document (VL contracts V-11) — the JVM twin of
 * the viewer's `mergeTtr` (`@tatrman/parser/layout`, `packages/parser/src/layout/layout-ttr.ts`), held to it
 * by the shared vectors (`packages/package-sdk/schemas/layout-vectors/`).
 *
 * Positions come from the sidecar's `canvas <graph name>` — else its `canvas main`, the name the Studio's WS
 * store writes for a single-canvas document (A-VL-2) — when it has nodes (the sidecar wins), else from the
 * in-file block. The sidecar also contributes `skin` and `collapsed`, which the `.ttrg` block cannot carry. It
 * is view state the designer server rewrites and never prunes, so what it holds beyond the graph is dropped
 * with a warning rather than failing the pair (A-VL-3): nodes under a stated `mode: auto`, and keys the graph
 * does not list. The in-file block is the author's own: a key it positions that `objects` does not list is an
 * error (V-3).
 */
object TtrgLayoutReader {
    private val MODELS = listOf("er", "db", "md", "cnc")
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F]")

    fun read(
        ttrg: String,
        ttrl: String? = null,
        fileName: String? = null,
    ): TtrgLayoutRead {
        val scan = TtrgGraphReader.scan(ttrg)
        val warnings = scan.warnings.toMutableList()
        val g = scan.graph ?: return TtrgLayoutRead.Failed(scan.errors, warnings)
        val model =
            g.model
                ?: return TtrgLayoutRead.Failed(
                    listOf(TtrgIssue("model", "the graph has no `model:`", g.nameLine)),
                    warnings,
                )

        var positions = g.nodes.mapValues { (_, p) -> LayoutPoint(p.first, p.second) }
        var positionsFrom = if (positions.isEmpty()) null else PositionsFrom.IN_FILE
        var lines: Map<String, Int> = g.nodeLines
        var skin: String? = null
        var collapsed: List<String> = emptyList()
        if (ttrl != null) {
            val side = TtrlLoader.parseString(ttrl, sidecarLabel(fileName), dropAutoNodes = true)
            warnings += side.warnings.map { TtrgIssue("ttrl", it.message, it.line) }
            val doc =
                side.document
                    ?: return TtrgLayoutRead.Failed(
                        side.errors.map { TtrgIssue("ttrl", it.message, it.line) },
                        warnings,
                    )
            val canvas = doc.canvases.firstOrNull { it.key == g.name } ?: doc.canvases.firstOrNull { it.key == "main" }
            if (canvas == null) {
                warnings += TtrgIssue("ttrl", "the sidecar has no canvas ${g.name} — its positions are not used")
            } else {
                val listed = g.objects.toSet()
                val kept = linkedMapOf<String, LayoutPoint>()
                for (n in canvas.nodes) {
                    if (n.zeta in listed) {
                        kept[n.zeta] = LayoutPoint(n.x, n.y)
                    } else {
                        warnings +=
                            TtrgIssue(
                                "ttrl.nodes.${n.zeta}",
                                "the sidecar positions ${n.zeta}, which the graph does not list — dropped",
                            )
                    }
                }
                if (kept.isNotEmpty()) {
                    positions = kept
                    positionsFrom = PositionsFrom.SIDECAR
                    lines = emptyMap()
                }
                canvas.skin?.takeIf { it.isNotEmpty() }?.let { skin = it }
                if (canvas.collapsed.isNotEmpty()) collapsed = canvas.collapsed
            }
        }

        val document = LayoutDocument(g.name, model, g.objects, positions, skin, collapsed)
        val errors = validate(document, g, lines)
        if (errors.isNotEmpty()) return TtrgLayoutRead.Failed(errors, warnings)
        warnings += duplicates(g.objects)
        stemWarning(fileName, g.name)?.let { warnings += it }
        return TtrgLayoutRead.Ok(document.canonical(), positionsFrom, warnings)
    }

    /** V-1…V-4 for a document read from TTR — the checks of the viewer's `validateDocument`. */
    private fun validate(
        doc: LayoutDocument,
        g: TtrgGraph,
        positionLines: Map<String, Int>,
    ): List<TtrgIssue> {
        val errors = mutableListOf<TtrgIssue>()
        if (!LayoutDocument.IDENTIFIER.matches(doc.name)) {
            errors +=
                TtrgIssue(
                    "name",
                    "name must be an identifier — letters, digits, underscore, not starting with a digit (got \"${doc.name}\")",
                    g.nameLine,
                )
        }
        if (doc.model !in MODELS) {
            errors += TtrgIssue("model", "model must be one of ${MODELS.joinToString(", ")} (got ${doc.model})")
        }
        doc.objects.forEachIndexed { i, ref -> refError("objects[$i]", ref)?.let { errors += it } }
        val listed = doc.objects.toSet()
        for ((key, p) in doc.positions) {
            if (key !in listed) {
                errors += TtrgIssue("positions.$key", "positions key $key is not in objects", positionLines[key])
                continue
            }
            if (!p.x.isFinite()) {
                errors +=
                    TtrgIssue("positions.$key.x", "x must be a finite number", positionLines[key])
            }
            if (!p.y.isFinite()) {
                errors +=
                    TtrgIssue("positions.$key.y", "y must be a finite number", positionLines[key])
            }
        }
        doc.collapsed.forEachIndexed { i, ref -> refError("collapsed[$i]", ref)?.let { errors += it } }
        if (doc.skin?.let { CONTROL.containsMatchIn(it) } == true) {
            errors += TtrgIssue("skin", "skin must be a string without control characters")
        }
        return errors
    }

    private fun refError(
        path: String,
        ref: String,
    ): TtrgIssue? =
        when {
            ref.isEmpty() -> TtrgIssue(path, "a ref must be a non-empty string")
            CONTROL.containsMatchIn(ref) -> TtrgIssue(path, "a ref must not contain control characters")
            else -> null
        }

    private fun duplicates(objects: List<String>): List<TtrgIssue> =
        objects
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map { TtrgIssue("objects", "duplicate object $it (kept once)") }

    /** V-4: the reader keeps the in-document name and warns when the file is named otherwise. */
    private fun stemWarning(
        fileName: String?,
        name: String,
    ): TtrgIssue? {
        val base = fileName?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        val stem = base.replace(Regex("\\.(ya?ml|ttrg|ttrl)$", RegexOption.IGNORE_CASE), "")
        return if (stem == name) null else TtrgIssue("name", "file name $base does not match the layout name $name")
    }

    private fun sidecarLabel(fileName: String?): String = fileName?.replace(Regex("\\.ttrg$"), ".ttrl") ?: "<sidecar>"
}
