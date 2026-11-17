// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType

/**
 * The join where a column entered a recipe's chain (S2.4e, ttr-server#434; ⚑R352-A): a bare `latest(by: d)` reads the
 * `d` with the most recent entry — the instance the last join brought, on either side.
 *
 * The rule, one for the checker ([RecipeRows.entries]) and the SQL ([org.tatrman.ttrp.graph.recipe.SugarLowering] hands
 * the checker's pick to the emitter):
 *  - a column enters at the first [stamping][stamps] join it passes through; a load's, a values' and a container
 *    input's columns have not entered yet (a container boundary starts afresh);
 *  - at a join, a column of the name that enters **there** is more recent than one that entered upstream; when both
 *    sides' columns enter there, or both entered upstream, the LEFT one is taken — as everywhere else in TTR-P;
 *  - a select or calc carries a column it passes under its own name with its entry; a computed column has none.
 */
object JoinEntry {
    /** The reserved qualifier prefix of a `latest`'s date: `_ttrp_entry:<join id>` (`_ttrp_`-named, RJ-103). */
    const val PREFIX = "_ttrp_entry:"

    /** The qualifier naming the `d` that entered at [join]. */
    fun qualifier(join: String) = PREFIX + join

    /** The join id [qualifier] names, or null when it is no entry qualifier. */
    fun joinOf(qualifier: String?): String? = qualifier?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    /**
     * True for a join a column can enter at: an authored one that keeps the right side's columns. A semi or anti join
     * keeps only the left row, and the joins the lowering makes (`~` in the id: a `latest`'s or a calendar count's) are
     * not in the checker's graph — neither stamps.
     */
    fun stamps(join: Join): Boolean = join.type != JoinType.SEMI && join.type != JoinType.ANTI && '~' !in join.id
}
