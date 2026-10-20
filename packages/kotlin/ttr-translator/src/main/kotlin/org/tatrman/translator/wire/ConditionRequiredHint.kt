// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import org.apache.calcite.rel.hint.RelHint

/**
 * ttr-server#232 — how `JoinNode.condition_required` crosses a Calcite round trip, the way [ExpandedFromHint] carries
 * `ProjectNode.expanded_from`.
 *
 * The flag marks a bare `JOIN` (no `ON`) that the SQL carrier could not condition, on a parse asked to refuse such a
 * join should it stay a Cartesian product. It reaches Calcite twice: the SQL carrier's marker becomes this hint on the
 * converted `LogicalJoin` (`codec.sql.BareJoinMarker`), and a REL_NODE re-entry's decoder attaches it from the field.
 * A Join is `Hintable` and `copy()` keeps hints, so it survives the front-half rel passes; the encoder turns it back
 * into the field, on a join still without a condition.
 */
internal object ConditionRequiredHint {
    const val NAME: String = "TTR_CONDITION_REQUIRED"

    fun of(): RelHint = RelHint.builder(NAME).build()

    fun isOn(hints: List<RelHint>): Boolean = hints.any { it.hintName == NAME }
}
