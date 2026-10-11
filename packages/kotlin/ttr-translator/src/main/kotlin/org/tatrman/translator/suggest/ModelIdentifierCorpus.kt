// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.suggest

import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.ModelHandle

/**
 * Phase 08 C2 — extracts a flat identifier corpus from a [ModelHandle] for the
 * [IdentifierSuggester]. Covers:
 *   - **table local names** in `db.dbo` (the v1 default DB namespace)
 *   - **column local names** for every table in `db.dbo`
 *   - **entity local names** in `er.entity`
 *   - **attribute local names** for every entity in `er.entity`
 *
 * Returns a `Set<String>` for cheap membership-style lookups; the suggester scans linearly so
 * the iteration order is irrelevant. Duplicate names across different parents (e.g. `id` appears
 * in many tables) are de-duplicated.
 *
 * Bounded: the corpus is built once per call and is small (~thousands of identifiers in a
 * realistic model). Callers that want to cache it across requests can hold the result of
 * [collect] alongside the snapshot version.
 */
object ModelIdentifierCorpus {
    fun collect(handle: ModelHandle): Set<String> =
        buildSet {
            for ((qname, table) in handle.tables(SchemaCode.DB, "dbo")) {
                add(qname.name)
                for (col in table.columns) add(col.name)
            }
            for ((qname, entity) in handle.entities(SchemaCode.ER, "entity")) {
                add(qname.name)
                for (attr in entity.attributes) add(attr.name)
            }
        }

    fun collect(
        handle: ModelHandle,
        schemaCode: SchemaCode,
        namespace: String,
    ): Set<String> =
        buildSet {
            when (schemaCode) {
                SchemaCode.DB -> {
                    for ((qname, table) in handle.tables(schemaCode, namespace)) {
                        add(qname.name)
                        for (col in table.columns) add(col.name)
                    }
                }
                SchemaCode.ER -> {
                    for ((qname, entity) in handle.entities(schemaCode, namespace)) {
                        add(qname.name)
                        for (attr in entity.attributes) add(attr.name)
                    }
                }
                else -> {}
            }
        }

    fun locate(
        handle: ModelHandle,
        name: String,
    ): Set<SchemaCode> =
        buildSet {
            for ((qname, table) in handle.tables(SchemaCode.DB, "dbo")) {
                if (qname.name.equals(name, ignoreCase = true)) {
                    add(SchemaCode.DB)
                    break
                }
                for (col in table.columns) {
                    if (col.name.equals(name, ignoreCase = true)) {
                        add(SchemaCode.DB)
                        break
                    }
                }
            }
            for ((qname, entity) in handle.entities(SchemaCode.ER, "entity")) {
                if (qname.name.equals(name, ignoreCase = true)) {
                    add(SchemaCode.ER)
                    break
                }
                for (attr in entity.attributes) {
                    if (attr.name.equals(name, ignoreCase = true)) {
                        add(SchemaCode.ER)
                        break
                    }
                }
            }
        }
}
