// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import org.tatrman.ttr.parser.model.AttributeDef
import org.tatrman.ttr.parser.model.ColumnDef
import org.tatrman.ttr.parser.model.ConceptBindingDef
import org.tatrman.ttr.parser.model.ConceptBody
import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.parser.model.ConceptProperty
import org.tatrman.ttr.parser.model.ConstraintDef
import org.tatrman.ttr.parser.model.CubeletDef
import org.tatrman.ttr.parser.model.CubeletMeasure
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.DimensionDef
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Er2CncRoleDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.FkDef
import org.tatrman.ttr.parser.model.HierarchyDef
import org.tatrman.ttr.parser.model.IndexDef
import org.tatrman.ttr.parser.model.LexiconEntryDef
import org.tatrman.ttr.parser.model.MdMapDef
import org.tatrman.ttr.parser.model.MeasureDef
import org.tatrman.ttr.parser.model.ProcedureDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.Reference
import org.tatrman.ttr.parser.model.RelationDef
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TableDef
import org.tatrman.ttr.parser.model.TargetObjectValue
import org.tatrman.ttr.parser.model.TargetReferenceValue
import org.tatrman.ttr.parser.model.ViewDef
import org.tatrman.ttr.semantics.cn.conceptSugarOf
import org.tatrman.ttr.semantics.cn.sugarStereotypeKey

/**
 * One collected cross-reference: the dotted path, its dot-split parts, the source
 * span of the reference token, and the top-level def it belongs to. Mirrors TS
 * `CollectedReference` (`packages/semantics/src/references.ts`).
 *
 * The [Reference] now carries its own [SourceLocation] (matching the TS
 * `Reference`), so both `Reference`-typed slots (nameAttribute, entity, role, …)
 * and `IdValue`-based slots (relation/fk `from`/`to`) report the reference's own
 * span — not the enclosing def's.
 *
 * 0.17 — [cnContext] marks a reference written in a cn slot (cn contracts §1.1–§1.4): `is_a`, `of`, a role's
 * `event`, `involves`, `value_space`, `relations[].type` / `.to`, a binding's stereotype slot and its
 * `participants:` keys, every slot of the `concept { }` sugar but the one that declares. It resolves among
 * concepts only ([Resolver.resolveReference]). [soft] marks a bearer-local name — a concept's `qualities:` item
 * or `period:` value: one that names no concept is an implicit quality, never an unresolved reference. Mirrors
 * the TS `CnSlotReference`.
 */
data class CollectedRef(
    val path: String,
    val parts: List<String>,
    val source: SourceLocation,
    val ownerDef: Definition,
    val cnContext: Boolean = false,
    val soft: Boolean = false,
) {
    /** The reference as the resolver reads it — its cn context included. */
    fun toRef(): Resolver.Ref = Resolver.Ref(path, parts, cnContext)
}

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
        // Inline MD attributes carry their own `domain:` cross-references (TS parity).
        is DimensionDef -> def.attributes
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
        // v3.1 MD — the cross-references the TS walker collects as `crossRefs` (domain / map / dimension /
        // measure / hierarchy / grain), read off the typed fields.
        is AttributeDef -> def.domainRef?.let { out += refOf(it, owner) }
        is DimensionDef -> def.hierarchies.forEach { out += refOf(it, owner) }
        is MdMapDef -> (def.from + def.to).forEach { out += refOf(it, owner) }
        is HierarchyDef -> {
            def.dimensionRef?.let { out += refOf(it, owner) }
            def.levels.forEach { level -> level.via?.let { out += refOf(it, owner) } }
        }
        is MeasureDef -> def.domainRef?.let { out += refOf(it, owner) }
        is CubeletDef -> {
            def.grain.forEach { out += refOf(it, owner) }
            def.measures.forEach { m -> if (m is CubeletMeasure.Ref) out += refOf(m.ref, owner) }
        }
        // 0.17 — the cn model: a concept def's and a binding's slots.
        is ConceptDef -> collectConceptBody(def.body, BodyMode.Def, owner, out)
        is ConceptBindingDef -> collectConceptBody(def.body, BodyMode.Binding, owner, out)
        is ColumnDef, is IndexDef, is ConstraintDef -> {}
        else -> {}
    }
    // 0.17 — the `concept { }` sugar on an md / er host.
    conceptSugarOf(def)?.let { collectConceptBody(it.body, BodyMode.Sugar, owner, out) }
}

/** Whose cn body it is: a concept def, a binding, or the sugar (its stereotype slot declares — no reference). */
private enum class BodyMode { Def, Binding, Sugar }

/** `period:` names its instants through `start` / `end` / `at`; `end_open` and `precision` are enums. */
private val PERIOD_INSTANTS = setOf("start", "end", "at")

/** A binding's `period:` may also name a stored upper bound — `start_hi` / `at_hi` (⚑CN-P10, ruled 2026-10-22). */
private val BINDING_PERIOD_INSTANTS = PERIOD_INSTANTS + setOf("start_hi", "at_hi")

/** The id values of a value that is one id or a list of them. */
internal fun idsOf(value: PropertyValue?): List<PropertyValue.IdValue> =
    when (value) {
        is PropertyValue.IdValue -> listOf(value)
        is PropertyValue.ListValue -> value.items.flatMap { idsOf(it) }
        else -> emptyList()
    }

/** The value under [key] of an object value, or null. */
internal fun entryOf(
    value: PropertyValue?,
    key: String,
): PropertyValue? = (value as? PropertyValue.ObjectValue)?.entries?.get(key)

/**
 * The references of one cn body, in authored order. Groundings and binding targets name an md / er / db element
 * and resolve the generic way; `hierarchy:`'s `ancestor` / `descendant` are read relative to its `closure`
 * entity. Mirrors TS `collectConceptBodyRefs` (`references.ts`).
 */
private fun collectConceptBody(
    body: ConceptBody,
    mode: BodyMode,
    owner: Definition,
    out: MutableList<CollectedRef>,
) {
    fun cn(
        path: String,
        parts: List<String>,
        source: SourceLocation,
        soft: Boolean = false,
    ) {
        out += CollectedRef(path, parts, source, owner, cnContext = true, soft = soft)
    }

    fun cn(
        id: PropertyValue.IdValue,
        soft: Boolean = false,
    ) = cn(id.ref.path, id.parts, id.source, soft)

    fun plain(id: PropertyValue.IdValue) {
        out += CollectedRef(id.ref.path, id.parts, id.source, owner)
    }

    val declaring = if (mode == BodyMode.Sugar) sugarStereotypeKey(body) else null
    val eventRef =
        if (mode == BodyMode.Binding) {
            body.properties.firstOrNull { it is ConceptProperty.Ref && it.key == "event" } as? ConceptProperty.Ref
        } else {
            null
        }
    for (p in body.properties) {
        when (p) {
            is ConceptProperty.Ref -> if (p.key != declaring) cn(p.ref.path, p.ref.parts, p.ref.source)
            is ConceptProperty.Grounds -> out += refOf(p.ref, owner)
            is ConceptProperty.ValueSpace -> cn(p.ref.path, p.ref.parts, p.ref.source)
            is ConceptProperty.Involves -> p.participants.forEach { cn(it.ref.path, it.ref.parts, it.ref.source) }
            is ConceptProperty.Period ->
                for ((key, value) in p.value.entries) {
                    if (key !in (if (mode == BodyMode.Binding) BINDING_PERIOD_INSTANTS else PERIOD_INSTANTS)) continue
                    idsOf(value).forEach { if (mode == BodyMode.Binding) plain(it) else cn(it, soft = true) }
                }
            is ConceptProperty.Target ->
                when (val v = p.value) {
                    is TargetReferenceValue -> out += refOf(v.ref, owner)
                    is TargetObjectValue -> pushIdValue(v.obj, owner, out)
                }
            is ConceptProperty.Entry ->
                when (p.key) {
                    "is_a", "of" -> idsOf(p.value).forEach { cn(it) }
                    "qualities" -> idsOf(p.value).forEach { cn(it, soft = true) }
                    "relations" ->
                        (p.value as? PropertyValue.ListValue)?.items?.forEach { item ->
                            for (key in listOf("type", "to")) idsOf(entryOf(item, key)).forEach { cn(it) }
                        }
                    "hierarchy" -> {
                        val closure = idsOf(entryOf(p.value, "closure")).firstOrNull() ?: continue
                        plain(closure)
                        for (key in listOf("ancestor", "descendant")) {
                            for (id in idsOf(entryOf(p.value, key))) {
                                out +=
                                    CollectedRef(
                                        "${closure.ref.path}.${id.ref.path}",
                                        closure.parts + id.parts,
                                        id.source,
                                        owner,
                                    )
                            }
                        }
                    }
                    "participants" -> {
                        val participants = p.value as? PropertyValue.ObjectValue
                        if (mode == BodyMode.Binding && participants != null) {
                            for ((key, value) in participants.entries) {
                                eventRef?.ref?.let { ev -> cn("${ev.path}.$key", ev.parts + key, value.source) }
                                idsOf(value).forEach { plain(it) }
                            }
                        }
                    }
                    "via" -> idsOf(p.value).forEach { plain(it) }
                    else -> {}
                }
            else -> {}
        }
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
