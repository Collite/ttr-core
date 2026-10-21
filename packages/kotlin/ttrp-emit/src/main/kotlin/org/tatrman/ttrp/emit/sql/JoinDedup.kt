// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.graph.model.PortNames

/**
 * The `right_on`-drop semantics shared by SQL join **emit** ([PlanNodeBuilder.join]) and join
 * **schema propagation** ([SqlGraphEmitter]). Keeping the two in one place guarantees the emitted
 * projection and the computed output row type agree.
 *
 * A TTR-P equi-join drops the right-side key columns (matching Polars `join(…, right_on=…)`; see
 * `hero_crunch.py`), so `join(a, b, on: a.x = b.y)` yields `a.* + b.(non-key)`. This is the
 * canonical TTR-P join output contract — the same schema must come out of every engine for A4
 * "identical results" to hold.
 */
internal object JoinDedup {
    /**
     * The right-side equi-join key column names, iff [on] is a pure conjunction of `eq`
     * comparisons (the equi-join shape); null otherwise (non-equi / cross → no `right_on` drop).
     * An `eq` with no right-port column contributes no key (empty set).
     */
    fun rightEquiKeys(on: Expression): Set<String>? =
        when (on) {
            is FunctionCall ->
                when (on.function.name) {
                    "and" -> {
                        val parts = on.args.map { rightEquiKeys(it) }
                        if (parts.any { it == null }) null else parts.filterNotNull().flatten().toSet()
                    }
                    "eq" -> {
                        val cols = on.args.filterIsInstance<ColumnRef>()
                        if (cols.size != on.args.size) {
                            null
                        } else {
                            cols.filter { it.port == PortNames.RIGHT }.map { it.column }.toSet()
                        }
                    }
                    else -> null
                }
            else -> null
        }

    /**
     * Surviving `(ordinal, name)` pairs of the deduped join output over the combined join row
     * (`leftCols ++ rightCols`): all left columns, then the right columns that are not
     * [rightKeys] — matching Polars' `left.* + right.(non-key)` output order. Ordinals index the
     * combined row (used as positional refs on emit; Calcite uniquifies duplicate names).
     */
    fun survivors(
        leftCols: List<String>,
        rightCols: List<String>,
        rightKeys: Set<String>,
        /** AU-core-P2: right keys kept all the same — a qualified reference reads them (`out_.warehouse`). */
        keep: Set<String> = emptySet(),
    ): List<Pair<Int, String>> =
        unique(
            leftCols.mapIndexed { i, name -> i to name } +
                rightCols.mapIndexedNotNull { j, name ->
                    if (name in rightKeys && name !in keep) null else (leftCols.size + j) to name
                },
        )

    /**
     * Each name made unique as Calcite names a join's row (AU-core-P2): a name already taken gets the first free
     * `<name>0`, `<name>1`, … — so a right column that repeats a left one (`quantity_on_hand` of a second join) stays
     * its own column instead of an alias the left one shadows.
     */
    fun unique(cols: List<Pair<Int, String>>): List<Pair<Int, String>> {
        val used = HashSet<String>()
        return cols.map { (i, name) ->
            if (used.add(name)) {
                i to name
            } else {
                var j = 0
                while (!used.add("$name$j")) j++
                i to "$name$j"
            }
        }
    }
}
