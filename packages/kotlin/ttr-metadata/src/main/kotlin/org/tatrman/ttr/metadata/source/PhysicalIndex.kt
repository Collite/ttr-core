// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.parser.model.*
import org.tatrman.ttr.semantics.*
import org.tatrman.ttr.semantics.md.resolveMdErRef

/** Canonical physical identities, including package and table/view kind; built once per hop snapshot. */
class PhysicalIndex(
    val docs: List<SemanticDocument>,
    val resolver: Resolver,
) {
    val columnToErAttr = linkedMapOf<String, MutableSet<String>>()
    val tableToErEntity = linkedMapOf<String, MutableSet<String>>()
    val erAttrToColumn = linkedMapOf<String, MutableSet<String>>()
    val entityToTable = linkedMapOf<String, MutableSet<String>>()
    val entities = linkedMapOf<String, Pair<SemanticDocument, EntityDef>>()
    val queries = linkedMapOf<String, Pair<SemanticDocument, QueryDef>>()
    val queryEntities = linkedMapOf<String, String>()
    val relations = mutableListOf<PhysicalRelation>()
    val attributeColumns = linkedMapOf<String, String>()

    fun boundColumn(attribute: String): String = attributeColumns[attribute] ?: attribute.substringAfterLast('.')

    private val fks = linkedMapOf<String, Pair<SemanticDocument, FkDef>>()

    fun resolve(
        doc: SemanticDocument,
        path: String,
    ): SymbolEntry? = resolveMdErRef(resolver, doc, path)

    fun key(
        doc: SemanticDocument,
        kind: String,
        name: String,
    ): String = buildCanonicalKey(doc.packageName, doc.namespace, kind, listOf(name), doc.schemaCode)

    fun column(
        doc: SemanticDocument,
        table: String,
        name: String,
    ): String? {
        val sym = resolver.getSymbol("$table.$name") ?: resolve(doc, name)
        return sym?.takeIf { it.kind == "column" && it.parent == table }?.qname
    }

    fun addOrigin(
        column: String,
        attribute: String,
    ) {
        columnToErAttr.getOrPut(column) { linkedSetOf() }.add(attribute)
        erAttrToColumn.getOrPut(attribute) { linkedSetOf() }.add(column)
    }

    private fun bindEntity(
        entity: String,
        target: TargetValue?,
        doc: SemanticDocument,
    ) {
        val ref = targetRef(target) ?: return
        val sym = resolve(doc, ref) ?: return
        when (sym.kind) {
            "table", "view" -> {
                tableToErEntity.getOrPut(sym.qname) { linkedSetOf() }.add(entity)
                entityToTable.getOrPut(entity) { linkedSetOf() }.add(sym.qname)
            }
            "query" -> if (sym.schemaCode == "er") queryEntities[entity] = sym.qname
        }
    }

    init {
        for (doc in docs) {
            for (def in doc.definitions) {
                when (def) {
                    is EntityDef -> {
                        val entity = key(doc, "entity", def.name)
                        entities[entity] = doc to def
                        bindEntity(entity, (def.binding as? BindingPropertyBlock)?.target, doc)
                    }
                    is QueryDef -> queries[key(doc, "query", def.name)] = doc to def
                    is FkDef -> fks[key(doc, "fk", def.name)] = doc to def
                    else -> {}
                }
            }
        }
        for (doc in docs) {
            for (def in doc.definitions.filterIsInstance<Er2DbEntityDef>()) {
                val entity =
                    def.entity
                        ?.path
                        ?.let { resolve(doc, it) }
                        ?.takeIf { it.kind == "entity" } ?: continue
                bindEntity(entity.qname, def.target, doc)
            }
        }
        for ((entity, pair) in entities) {
            if (entity !in entityToTable && entity !in queryEntities) {
                val (doc, def) = pair
                for (namespace in docs
                    .filter { it.schemaCode == "db" }
                    .map { it.namespace.ifEmpty { "dbo" } }
                    .distinct()) {
                    resolve(doc, "db.$namespace.${def.name}")?.takeIf { it.kind == "table" }?.let {
                        tableToErEntity.getOrPut(it.qname) { linkedSetOf() }.add(entity)
                        entityToTable.getOrPut(entity) { linkedSetOf() }.add(it.qname)
                    }
                }
            }
        }
        // Inline columns and per-attribute bindings are interpreted in the entity's physical table.
        for ((entity, pair) in entities) {
            val (doc, def) = pair
            val columns =
                (def.binding as? BindingPropertyBlock)?.columns.orEmpty().associate { entry ->
                    entry.name to
                        when (val v = entry.value) {
                            is BindingColumnBareId -> v.id.path
                            is BindingColumnObject -> id(inlineColumnEntries(v)["column"])
                        }
                }
            for (attr in def.attributes) {
                val name =
                    when (val binding = attr.binding) {
                        is BindingPropertyBareId -> binding.id.path
                        is BindingPropertyBlock -> targetRef(binding.target)
                        else -> columns[attr.name]
                    } ?: attr.name
                attributeColumns["$entity.${attr.name}"] = name.substringAfterLast('.')
                for (table in entityToTable[entity].orEmpty()) {
                    column(doc, table, name)?.let { addOrigin(it, "$entity.${attr.name}") }
                }
            }
        }
        for (doc in docs) {
            for (def in doc.definitions.filterIsInstance<Er2DbAttributeDef>()) {
                val attr =
                    def.attribute
                        ?.path
                        ?.let { resolve(doc, it) }
                        ?.takeIf { it.kind == "attribute" } ?: continue
                val path = targetRef(def.target) ?: continue
                attributeColumns[attr.qname] = path.substringAfterLast('.')
                // An explicit attribute mapping replaces the identity/inline convention.
                erAttrToColumn.remove(attr.qname)?.forEach { columnToErAttr[it]?.remove(attr.qname) }
                val col = resolve(doc, path)?.takeIf { it.kind == "column" }
                if (col != null) {
                    addOrigin(col.qname, attr.qname)
                } else {
                    for (table in entityToTable[attr.parent].orEmpty()) {
                        column(doc, table, path)?.let { addOrigin(it, attr.qname) }
                    }
                }
            }
        }
        val relationFks = linkedMapOf<String, String>()
        for (doc in docs) {
            for (def in doc.definitions.filterIsInstance<Er2DbRelationDef>()) {
                val rel = def.relation?.path?.let { resolve(doc, it) } ?: continue
                val fk = def.fk?.path?.let { resolve(doc, it) } ?: continue
                relationFks[rel.qname] = fk.qname
            }
        }
        for (doc in docs) {
            for (def in doc.definitions.filterIsInstance<RelationDef>()) {
                val from = id(def.from)?.let { resolve(doc, it) }?.takeIf { it.kind == "entity" } ?: continue
                val to = id(def.to)?.let { resolve(doc, it) }?.takeIf { it.kind == "entity" } ?: continue
                val pairs =
                    def.join
                        .mapNotNull { v ->
                            val obj = v as? PropertyValue.ObjectValue ?: return@mapNotNull null
                            val a = id(obj.entries["from"])?.let { resolve(doc, it) }
                            val b = id(obj.entries["to"])?.let { resolve(doc, it) }
                            if (a?.parent == from.qname && b?.parent == to.qname) a.qname to b.qname else null
                        }.toMutableList()
                val fkRef =
                    when (val binding = def.binding) {
                        is BindingPropertyBareId -> resolve(doc, binding.id.path)?.qname
                        is BindingPropertyBlock -> binding.fk?.path?.let { resolve(doc, it)?.qname }
                        else -> null
                    } ?: relationFks[key(doc, "relation", def.name)]
                fks[fkRef]?.let { (fkDoc, fk) ->
                    val left = fkColumns(fkDoc, fk.from)
                    val right = fkColumns(fkDoc, fk.to)
                    if (left.size == right.size) {
                        for ((a, b) in left.zip(right)) {
                            for (src in columnToErAttr[a].orEmpty().filter {
                                it.substringBeforeLast(
                                    '.',
                                ) == from.qname
                            }) {
                                for (dst in columnToErAttr[b].orEmpty().filter {
                                    it.substringBeforeLast(
                                        '.',
                                    ) == to.qname
                                }) {
                                    pairs += src to dst
                                }
                            }
                        }
                    }
                }
                relations +=
                    PhysicalRelation(
                        key(doc, "relation", def.name),
                        from.qname,
                        to.qname,
                        pairs.distinct(),
                        text(def.cardinality?.entries?.get("from")),
                        text(def.cardinality?.entries?.get("to")),
                    )
            }
        }
    }

    private fun fkColumns(
        doc: SemanticDocument,
        value: PropertyValue?,
    ): List<String> {
        if (value is PropertyValue.IdValue) {
            return listOfNotNull(
                resolve(doc, value.ref.path)
                    ?.takeIf {
                        it.kind ==
                            "column"
                    }?.qname,
            )
        }
        if (value is PropertyValue.ListValue) {
            val resolved =
                value.items.map {
                    id(it)?.let { path ->
                        resolve(doc, path)
                            ?.takeIf { sym ->
                                sym.kind ==
                                    "column"
                            }?.qname
                    }
                }
            return if (resolved.any { it == null }) emptyList() else resolved.filterNotNull()
        }
        val obj = value as? PropertyValue.ObjectValue ?: return emptyList()
        val table = id(obj.entries["table"])?.let { resolve(doc, it) } ?: return emptyList()
        val names = (obj.entries["columns"] as? PropertyValue.ListValue)?.items.orEmpty().mapNotNull(::id)
        val resolved = names.map { column(doc, table.qname, it) }
        return if (resolved.any { it == null }) emptyList() else resolved.filterNotNull()
    }
}

data class PhysicalRelation(
    val key: String,
    val from: String,
    val to: String,
    val pairs: List<Pair<String, String>>,
    val fromCardinality: String?,
    val toCardinality: String?,
)

internal fun id(value: PropertyValue?): String? = (value as? PropertyValue.IdValue)?.ref?.path

internal fun text(value: PropertyValue?): String? =
    (value as? PropertyValue.StringValue)?.raw
        ?: (value as? PropertyValue.NumberValue)?.raw?.let {
            if (it % 1.0 ==
                0.0
            ) {
                it.toLong().toString()
            } else {
                it.toString()
            }
        }
        ?: id(value)

internal fun targetRef(target: TargetValue?): String? =
    when (target) {
        is TargetReferenceValue -> target.ref.path
        is TargetObjectValue ->
            listOf(
                "table",
                "view",
                "query",
                "column",
            ).firstNotNullOfOrNull { id(target.obj.entries[it]) }
        else -> null
    }
