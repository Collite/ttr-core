// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import org.apache.calcite.rel.hint.RelHint
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * LQ (logical queries, contract L-11) — how `ProjectNode.expanded_from` crosses a Calcite round trip.
 *
 * The stamp is wire data, but a REL_NODE re-entry decodes the plan into Calcite and re-encodes it. The decoder
 * attaches the stamp to the decoded `LogicalProject` as a [RelHint] (a Project is `Hintable`; `copy()` keeps
 * hints, so the stamp survives the front-half rel passes), and the encoder turns it back into the field — the
 * same carry table hints use on scans. MAP_TO_PHYSICAL ends the stamp (it closes the boundary into its own
 * physical form), so no rendering of a physical plan ever sees the hint.
 */
internal object ExpandedFromHint {
    const val NAME: String = "TTR_LQ_EXPANDED_FROM"

    fun of(qname: QualifiedName): RelHint =
        RelHint
            .builder(NAME)
            .hintOption("schema_code", qname.schemaCode.name)
            .hintOption("namespace", qname.namespace)
            .hintOption("name", qname.name)
            .hintOption("package", qname.`package`)
            .build()

    fun from(hints: List<RelHint>): QualifiedName? {
        val hint = hints.firstOrNull { it.hintName == NAME } ?: return null
        val kv = hint.kvOptions
        return QualifiedName
            .newBuilder()
            .setSchemaCode(kv["schema_code"]?.let { SchemaCode.valueOf(it) } ?: SchemaCode.SCHEMA_CODE_UNSPECIFIED)
            .setNamespace(kv["namespace"].orEmpty())
            .setName(kv["name"].orEmpty())
            .setPackage(kv["package"].orEmpty())
            .build()
    }
}
