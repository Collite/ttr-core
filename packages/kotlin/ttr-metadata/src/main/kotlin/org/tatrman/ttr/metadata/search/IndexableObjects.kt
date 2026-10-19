// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.search

import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.CncSchema
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.Role
import org.tatrman.ttr.metadata.model.SearchHints
import org.tatrman.ttr.metadata.registry.RegistrySnapshot

/**
 * Flat carrier for any model object the search feature consumes. Removes
 * the per-kind sealed-interface dance from algorithm code: every algorithm
 * iterates a `Sequence<IndexableObject>` and reads only what it needs.
 *
 * Kept inside the search package because the projection here is shaped
 * for search; other consumers should walk the model directly.
 */
internal data class IndexableObject(
    val qname: QualifiedName,
    val kind: String,
    val description: String,
    val displayLabel: LocalizedText,
    val search: SearchHints,
)

internal fun RegistrySnapshot.searchableObjects(): Sequence<IndexableObject> =
    sequence {
        for ((_, schema) in model.schemas) {
            when (schema) {
                is ErSchema -> {
                    for (e in schema.entities.values) yield(e.toIndexable())
                    for (e in schema.entities.values) for (a in e.attributes) yield(a.toIndexable())
                }
                is CncSchema -> for (r in schema.roles.values) yield(r.toIndexable())
                // Grammar 0.16 — automation objects are findable by name, description and label.
                is org.tatrman.ttr.metadata.model.AutomationSchema ->
                    for (o in schema.objects.values) {
                        yield(IndexableObject(o.qname, o.kind, o.description, o.label, SearchHints.EMPTY))
                    }
                else -> {}
            }
        }
        for (q in model.queries.values) yield(q.toIndexable())
    }

private fun Entity.toIndexable(): IndexableObject = IndexableObject(qname, kind, description, displayLabel, search)

private fun Attribute.toIndexable(): IndexableObject = IndexableObject(qname, kind, description, displayLabel, search)

private fun Role.toIndexable(): IndexableObject = IndexableObject(qname, kind, description, label, search)

private fun Query.toIndexable(): IndexableObject =
    IndexableObject(qname, kind, description, LocalizedText.EMPTY, search)
