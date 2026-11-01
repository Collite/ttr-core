// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.serialization

import org.tatrman.ttr.parser.model.*

/** Shared definition projection. Snapshot resolution belongs to the caller's value adapter. */
object DefinitionProperties {
    fun project(
        d: Definition,
        parameter: (PropertyValue) -> Any? = { it },
    ): Map<String, Any?> {
        val raw: Map<String, Any?> =
            when (d) {
                is ProjectDef -> mapOf<String, Any?>("version" to d.version)
                is TableDef ->
                    mapOf<String, Any?>(
                        "primaryKey" to d.primaryKey,
                        "columns" to d.columns,
                        "indices" to d.indices,
                        "constraints" to d.constraints,
                        "search" to d.search,
                        "semantics" to d.semantics,
                        "lexicon" to d.lexicon,
                        "management" to d.management,
                        "changeSemantics" to d.changeSemantics,
                        "writebackReservation" to d.writeback,
                    )
                is ViewDef ->
                    mapOf<String, Any?>(
                        "columns" to d.columns,
                        "definitionSql" to sqlBlock(d.definitionSqlBlock, d.definitionSql),
                        "search" to d.search,
                    )
                is ColumnDef ->
                    mapOf<String, Any?>(
                        "type" to d.type,
                        "optional" to d.optional,
                        "isKey" to d.isKey,
                        "indexed" to d.indexed,
                        "search" to d.search,
                        "semantics" to d.semantics,
                        "lexicon" to d.lexicon,
                    )
                is IndexDef -> mapOf<String, Any?>("indexType" to d.indexType, "columnNames" to d.columns)
                is ConstraintDef ->
                    mapOf<String, Any?>(
                        "constraintType" to d.constraintType,
                        "columnNames" to d.columns,
                    )
                is FkDef -> mapOf<String, Any?>("from" to d.from, "to" to d.to)
                is ProcedureDef ->
                    mapOf<String, Any?>(
                        "parameters" to d.parameters.map { parameter(it) },
                        "resultColumns" to d.resultColumns,
                    )
                is EntityDef ->
                    mapOf<String, Any?>(
                        "labelPlural" to d.labelPlural,
                        "nameAttribute" to d.nameAttribute,
                        "codeAttribute" to d.codeAttribute,
                        "aliases" to d.aliases,
                        "attributes" to d.attributes,
                        "roles" to d.roles,
                        "displayLabel" to d.displayLabel,
                        "search" to d.search,
                        "semantics" to d.semantics,
                        "lexicon" to d.lexicon,
                        "binding" to d.binding,
                        "concept" to d.concept,
                    )
                is AttributeDef ->
                    mapOf<String, Any?>(
                        "type" to d.type,
                        "isKey" to d.isKey,
                        "optional" to d.optional,
                        "displayLabel" to d.displayLabel,
                        "valueLabels" to
                            d.valueLabels.mapValues { (key, label) ->
                                if (key in
                                    d.valueLabelAliases
                                ) {
                                    mapOf("label" to label, "aliases" to d.valueLabelAliases.getValue(key))
                                } else {
                                    label
                                }
                            },
                        "search" to d.search,
                        "semantics" to d.semantics,
                        "lexicon" to d.lexicon,
                        "binding" to d.binding,
                        "domainRef" to d.domainRef,
                        "aggregation" to d.aggregation,
                        "states" to d.states,
                        "transitions" to d.transitions,
                        "concept" to d.concept,
                    )
                is LexiconEntryDef ->
                    mapOf<String, Any?>(
                        "for" to d.target,
                        "forms" to d.forms,
                        "match" to d.match,
                        "text" to d.text,
                        "default" to d.default,
                        "sense_priority" to d.sensePriority,
                        "indexSecurity" to d.indexSecurity,
                    )
                is RelationDef ->
                    mapOf<String, Any?>(
                        "from" to d.from,
                        "to" to d.to,
                        "cardinality" to d.cardinality,
                        "join" to d.join,
                        "search" to d.search,
                        "binding" to d.binding,
                    )
                is Er2DbEntityDef ->
                    mapOf<String, Any?>(
                        "entity" to d.entity,
                        "target" to d.target,
                        "whereFilter" to d.whereFilter,
                    )
                is Er2DbAttributeDef -> mapOf<String, Any?>("attribute" to d.attribute, "target" to d.target)
                is Er2DbRelationDef -> mapOf<String, Any?>("relation" to d.relation, "fk" to d.fk)
                is QueryDef ->
                    mapOf<String, Any?>(
                        "language" to d.language,
                        "parameters" to d.parameters.map { parameter(it) },
                        "sourceText" to sqlBlock(d.sourceTextBlock, d.sourceText),
                        "skipSecurity" to d.skipSecurity,
                        "search" to d.search,
                    )
                is RoleDef ->
                    mapOf<String, Any?>("label" to d.label, "search" to d.search) +
                        d.entries.associate { it.key to it.value }
                is Er2CncRoleDef -> mapOf<String, Any?>("entity" to d.entity, "role" to d.role)
                is DrillMapDef ->
                    mapOf<String, Any?>(
                        "from" to d.from,
                        "to" to d.to,
                        "args" to d.args,
                        "display" to d.display,
                        "override" to d.overrideAuto,
                    )
                is AreaDef -> mapOf<String, Any?>("areaPackages" to d.packages, "areaEntities" to d.entities)
                is SchemaDef -> mapOf<String, Any?>("columns" to d.columns)
                is MdDomainDef ->
                    mapOf<String, Any?>(
                        "type" to d.type,
                        "kind" to d.domainKind,
                        "publish" to d.publishMembers,
                        "restrict" to d.restrictMembers,
                        "concept" to d.concept,
                    )
                is DimensionDef ->
                    mapOf<String, Any?>(
                        "key" to d.key,
                        "attributes" to d.attributes,
                        "hierarchies" to d.hierarchies,
                        "concept" to d.concept,
                    )
                is MdMapDef ->
                    mapOf<String, Any?>(
                        "from" to d.from,
                        "to" to d.to,
                        "cardinality" to d.cardinality,
                        "calc" to d.calc,
                    )
                is HierarchyDef -> mapOf<String, Any?>("dimensionRef" to d.dimensionRef, "levels" to d.levels)
                is MeasureDef ->
                    mapOf<String, Any?>(
                        "domainRef" to d.domainRef,
                        "class" to d.measureClass,
                        "aggregation" to d.aggregation,
                        "validBy" to d.validBy,
                        "concept" to d.concept,
                    )
                is CubeletDef ->
                    mapOf<String, Any?>(
                        "grain" to d.grain,
                        "measures" to d.measures,
                        "concept" to d.concept,
                    )
                is Md2dbCubeletDef ->
                    mapOf<String, Any?>(
                        "cubeletRef" to d.cubeletRef,
                        "target" to d.table,
                        "shape" to d.shape,
                        "attributesMap" to d.attributes,
                        "measuresMap" to d.measures,
                        "journaling" to d.journaling,
                        "allocation" to d.allocation,
                    )
                is Md2dbDomainDef -> mapOf<String, Any?>("domainRef" to d.domainRef, "source" to d.columnSource)
                is Md2dbMapDef ->
                    mapOf<String, Any?>(
                        "mapRef" to d.mapRef,
                        "target" to d.table,
                        "columnsMap" to d.columns,
                    )
                is Md2erDimensionDef ->
                    mapOf<String, Any?>(
                        "dimensionRef" to d.dimensionRef,
                        "entity" to d.entity,
                        "attributesMap" to d.attributes,
                    )
                is Md2erCubeletDef ->
                    mapOf<String, Any?>(
                        "cubeletRef" to d.cubeletRef,
                        "target" to d.entity,
                        "attributesMap" to d.attributes,
                        "measuresMap" to d.measures,
                    )
                is WorldDef ->
                    mapOf<String, Any?>(
                        "extends" to d.extends,
                        "engines" to d.engines,
                        "executors" to d.executors,
                        "storages" to d.storages,
                        "identities" to d.identities,
                        "grants" to d.grants,
                        "deployments" to d.deployments,
                        "processes" to d.processes,
                    )
                is ConceptDef -> concept(d.body)
                is ConceptBindingDef -> concept(d.body)
                is AutomationDef -> auto(d.body)
            }
        val common =
            mapOf(
                "description" to (d.descriptionLocalized.takeIf { it.byLanguage.isNotEmpty() } ?: d.description),
                "tags" to d.tags,
            )
        return common + raw
    }

    /** Both projections share ordering, while ordered syntax bodies retain every occurrence. */
    fun <T, R> serialize(
        entries: List<Pair<String, T>>,
        order: List<String>,
        ordered: Boolean = false,
        encode: (String, T) -> R,
    ): List<Pair<String, R>> {
        val retained =
            if (ordered) {
                entries
            } else {
                entries.sortedWith { a, b ->
                    val ai = order.indexOf(a.first).takeIf { it >= 0 } ?: order.size
                    val bi = order.indexOf(b.first).takeIf { it >= 0 } ?: order.size
                    if (ai != bi) ai.compareTo(bi) else compareUtf8(a.first, b.first)
                }
            }
        return retained.map { (key, value) -> key to encode(key, value) }
    }

    private fun compareUtf8(
        a: String,
        b: String,
    ): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val cmp = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255)
            if (cmp != 0) return cmp
        }
        return x.size.compareTo(y.size)
    }

    fun concept(body: ConceptBody): Map<String, Any?> =
        body.properties.associate {
            it.key to
                when (it) {
                    is ConceptProperty.Description -> it.localized ?: it.value
                    is ConceptProperty.Label -> it.value
                    is ConceptProperty.Tags -> it.value
                    is ConceptProperty.Ref -> it.ref
                    is ConceptProperty.From -> it.ref
                    is ConceptProperty.Until -> it.ref
                    is ConceptProperty.Per -> it.ref
                    is ConceptProperty.Grounds -> it.ref
                    is ConceptProperty.ValueSpace -> it.ref
                    is ConceptProperty.Involves -> it.participants
                    is ConceptProperty.Period -> it.value
                    is ConceptProperty.Extent -> it.value
                    is ConceptProperty.Basis -> it.value
                    is ConceptProperty.DefinitionBlock -> it.block
                    is ConceptProperty.MembersBlock -> it.block
                    is ConceptProperty.FilterBlock -> it.block
                    is ConceptProperty.Lexicon -> it.block
                    is ConceptProperty.Target -> it.value
                    is ConceptProperty.Entry -> it.value
                }
        }

    fun auto(body: AutoBody): Map<String, Any?> =
        mapOf(
            "description" to (body.descriptionLocalized.takeIf { it.byLanguage.isNotEmpty() } ?: body.description),
            "tags" to body.tags,
            "label" to body.label,
            "type" to body.type,
            "columns" to body.columns,
            "target" to body.target,
            "transition" to body.transition,
            "set" to body.set,
        ) +
            body.entries.associate { it.key to it.value }

    private fun sqlBlock(
        structured: PropertyValue?,
        text: String?,
    ): Any? =
        text?.let {
            mapOf(
                "block" to it,
                "tag" to (structured as? TaggedBlockValue)?.tag,
            )
        }
}
