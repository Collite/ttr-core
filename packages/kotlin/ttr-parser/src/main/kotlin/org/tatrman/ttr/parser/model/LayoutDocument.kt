// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.model

import kotlin.math.floor

/**
 * The layout document (VL contracts §1) — one named picture of a schema: the CARDS it shows and,
 * optionally, where they sit. Mirrors the viewer's TypeScript `LayoutDocument`
 * (`packages/designer/src/layout/layout-document.ts`); the two are held together by the shared vectors
 * (`packages/package-sdk/schemas/layout-vectors/`).
 *
 * Derived, never stored (V-2): `mode` (manual iff [positions] is non-empty), the edges, the hop edges,
 * missing / ambiguous / ignored refs — and never the viewport (C1-b).
 */
data class LayoutDocument(
    /** the `.ttrg` graph-name rule ([IDENTIFIER]); also the file stem (V-4) */
    val name: String,
    /** `er` | `db` (`md` / `cnc` admitted, untested — G-5) */
    val model: String,
    /** cards (entities; tables / views) as refs — full qnames when a viewer wrote them (V-1, V-15) */
    val objects: List<String>,
    /** manual positions keyed by a ref also in [objects] (V-3); empty = auto layout */
    val positions: Map<String, LayoutPoint> = emptyMap(),
    /** notation id; not carried by the `.ttrg` block (V-10) */
    val skin: String? = null,
    /** reserved — carried, not acted on in v1 */
    val collapsed: List<String> = emptyList(),
) {
    /**
     * The canonical form (V-5): objects de-duplicated and sorted by code point, positions keys sorted,
     * coordinates rounded ([roundCoordinate]), an empty skin dropped, collapsed sorted + de-duplicated.
     */
    fun canonical(): LayoutDocument =
        LayoutDocument(
            name = name,
            model = model,
            objects = objects.distinct().sortedWith(CODE_POINT_ORDER),
            positions =
                positions.keys.sortedWith(CODE_POINT_ORDER).associateWith { k ->
                    val p = positions.getValue(k)
                    LayoutPoint(roundCoordinate(p.x), roundCoordinate(p.y))
                },
            skin = skin?.takeIf { it.isNotEmpty() },
            collapsed = collapsed.distinct().sortedWith(CODE_POINT_ORDER),
        )

    companion object {
        /** The `.ttrg` graph-name rule (V-4, V-13): ASCII letters, digits, underscore; no leading digit. */
        val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /** Unicode code point order (V-5) — not locale order, and not UTF-16 code-unit order. */
        val CODE_POINT_ORDER: Comparator<String> =
            Comparator { a, b ->
                var i = 0
                var j = 0
                while (i < a.length && j < b.length) {
                    val ca = a.codePointAt(i)
                    val cb = b.codePointAt(j)
                    if (ca != cb) return@Comparator ca.compareTo(cb)
                    i += Character.charCount(ca)
                    j += Character.charCount(cb)
                }
                (a.length - i).compareTo(b.length - j)
            }

        /** A coordinate rounded to an integer, halves toward +∞ — `floor(x + 0.5)`; never `-0.0` (V-3). */
        fun roundCoordinate(v: Double): Double = floor(v + 0.5) + 0.0
    }
}

data class LayoutPoint(
    val x: Double,
    val y: Double,
)
