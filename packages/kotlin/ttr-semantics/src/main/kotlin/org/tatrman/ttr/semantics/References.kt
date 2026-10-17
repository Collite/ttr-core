// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import org.tatrman.ttr.parser.model.AttributeDef
import org.tatrman.ttr.parser.model.ColumnDef
import org.tatrman.ttr.parser.model.ConstraintDef
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Er2CncRoleDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.FkDef
import org.tatrman.ttr.parser.model.IndexDef
import org.tatrman.ttr.parser.model.LexiconEntryDef
import org.tatrman.ttr.parser.model.ProcedureDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.Reference
import org.tatrman.ttr.parser.model.RelationDef
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TableDef
import org.tatrman.ttr.parser.model.ViewDef

/**
 * One collected cross-reference: the dotted path, its dot-split parts, the source
 * span of the reference token, and the top-level def it belongs to. Mirrors TS
 * `CollectedReference` (`packages/semantics/src/references.ts`).
 *
 * The [Reference] now carries its own [SourceLocation] (matching the TS
 * `Reference`), so both `Reference`-typed slots (nameAttribute, entity, role, …)
 * and `IdValue`-based slots (relation/fk `from`/`to`) report the reference's own
 * span — not the enclosing def's.
 */
data class CollectedRef(
    val path: String,
    val parts: List<String>,
    val source: SourceLocation,
    val ownerDef: Definition,
)

/**
 * Walk every definition (plus its nested children) and collect every
 * cross-reference, paired with the top-level def it came from.
 */
fun collectAllReferences(definitions: List<Definition>): List<CollectedRef> {
    val out = mutableListOf<CollectedRef>()
    for (def in definitions) {
        collectInto(def, def, out)
        for (child in nestedDefs(def)) collectInto(child, def, out)
    }
    return out
}

/** Nested per-def children (attributes, columns, indices, constraints, result columns). */
fun nestedDefs(def: Definition): List<Definition> =
    when (def) {
        is EntityDef -> def.attributes
        is TableDef -> def.columns + def.indices + def.constraints
        is ViewDef -> def.columns
        is ProcedureDef -> def.resultColumns
        else -> emptyList()
    }

private fun collectInto(
    def: Definition,
    owner: Definition,
    out: MutableList<CollectedRef>,
) {
    when (def) {
        is EntityDef -> {
            def.nameAttribute?.let { out += refOf(it, owner) }
            def.codeAttribute?.let { out += refOf(it, owner) }
        }
        is Er2DbEntityDef -> def.entity?.let { out += refOf(it, owner) }
        is Er2DbAttributeDef -> def.attribute?.let { out += refOf(it, owner) }
        is Er2DbRelationDef -> {
            def.relation?.let { out += refOf(it, owner) }
            def.fk?.let { out += refOf(it, owner) }
        }
        is Er2CncRoleDef -> {
            def.entity?.let { out += refOf(it, owner) }
            def.role?.let { out += refOf(it, owner) }
        }
        is RelationDef -> {
            pushIdValue(def.from, owner, out)
            pushIdValue(def.to, owner, out)
        }
        is FkDef -> {
            pushIdValue(def.from, owner, out)
            pushIdValue(def.to, owner, out)
        }
        // v4.4 lexicon entries — the `for:` target ref (er/db/md) resolves through
        // the standard path, giving goto-def + unresolved-reference for free (TS parity).
        is LexiconEntryDef -> def.target?.let { out += refOf(it, owner) }
        is AttributeDef, is ColumnDef, is IndexDef, is ConstraintDef -> {}
        else -> {}
    }
}

private fun refOf(
    ref: Reference,
    owner: Definition,
): CollectedRef = CollectedRef(ref.path, ref.parts, ref.source, owner)

private fun pushIdValue(
    value: PropertyValue?,
    owner: Definition,
    out: MutableList<CollectedRef>,
) {
    when (value) {
        is PropertyValue.IdValue -> out += CollectedRef(value.ref.path, value.parts, value.source, owner)
        is PropertyValue.ListValue -> value.items.forEach { pushIdValue(it, owner, out) }
        is PropertyValue.ObjectValue -> value.entries.values.forEach { pushIdValue(it, owner, out) }
        else -> {}
    }
}

private val ENCLOSING_KINDS =
    setOf(
        "entity",
        "table",
        "view",
        "procedure",
        "relation",
        "query",
        "role",
        "er2dbEntity",
        "er2dbAttribute",
        "er2dbRelation",
        "er2cncRole",
    )

/**
 * The qname of the def that lexically encloses a reference (for step-1 lexical
 * resolution). Mirrors TS `enclosingQnameOf` (`reference-index.ts`).
 */
fun enclosingQnameOf(
    def: Definition,
    schemaCode: String,
    namespace: String,
    packageName: String?,
): String? {
    val kind = kindOf(def)
    if (kind !in ENCLOSING_KINDS) return null
    // v4.0 uniform key: model/schema/kind derive from the def's kind; `namespace`
    // is the file `schema` id (db only). `schemaCode` (file model) only moves a
    // query declared under `model er` (a logical query, LQ L-1).
    return buildCanonicalKey(packageName ?: "", namespace, kind, listOf(def.name), schemaCode)
}
