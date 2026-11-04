// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*

internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal class Record(
    val source: ForeignNode,
    val kind: String,
    val id: String,
    val parent: Record?,
)

/** Selected subtrees are consumed; merely opening a container never hides its other keys. */
internal class ConsumptionLedger {
    private val consumed = mutableSetOf<String>()
    private val opened = mutableSetOf<String>()
    fun open(node: ForeignNode) {
        var path = node.path
        while (true) {
            opened += path
            if (path.isEmpty()) break
            path = path.substringBeforeLast('/', "")
        }
    }
    fun consume(node: ForeignNode) {
        open(node)
        if (!node.keySelection) consumed += node.path
    }
    fun check(root: ForeignNode) {
        fun visit(node: ForeignNode) {
            if (node.path in consumed) return
            when (node.value) {
                is JsonObject -> node.entries().forEach { child ->
                    if (child.path !in consumed && child.path !in opened) {
                        throw ProfileException("ys/unknown-key", child.span(key = true), "Unconsumed key ${child.mappingKey}")
                    }
                    visit(child)
                }
                is JsonArray -> node.entries().forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
    }
}

/** A target prefix owns its entire subtree; siblings remain independently writable. */
internal class ConflictLedger {
    private val writes = mutableMapOf<Record, MutableList<List<String>>>()
    fun claim(record: Record, path: List<String>, source: ForeignNode) {
        val previous = writes.getOrPut(record) { mutableListOf() }
        if (previous.any { path.take(it.size) == it || it.take(path.size) == path }) {
            throw ProfileException("ys/profile-conflict", source.span(key = true), "Two operations write ${record.kind}.${record.id}/${path.joinToString("/")}")
        }
        previous += path
    }
}

internal class PlannedOp(val located: LocatedOp) {
    val value = located.value
    val name = value.string("op")
    val scope = value.optionalString("in")
    val at = JsonPointer(value.optionalString("at") ?: "")
    private val files = value["files"]?.jsonArray?.map { it.jsonPrimitive.content }?.let(::FileSelector)
    fun matches(tree: ForeignTree) = files?.matches(tree.file) != false
}

/** Record discovery is a separate pass, so child selectors never depend on writing-operation order. */
internal class RecordPlan(val tree: ForeignTree, private val operations: List<PlannedOp>) {
    val consumption = ConsumptionLedger()
    val conflicts = ConflictLedger()
    private val found = linkedMapOf<String, Record>()
    val records: List<Record> get() {
        val order = mutableMapOf<String, Int>()
        fun walk(node: ForeignNode) {
            order[node.path] = order.size
            node.entries().forEach(::walk)
        }
        walk(tree.root)
        return found.values.sortedBy { order.getValue(it.source.path) }
    }
    init {
        val evaluated = mutableSetOf<Pair<Int, String>>()
        var changed: Boolean
        do {
            changed = false
            operations.forEachIndexed { index, op ->
                if (op.name != "kinds" || !op.matches(tree)) return@forEachIndexed
                val contexts = if (op.scope == null) listOf(null) else found.values.filter { it.kind == op.scope }
                contexts.forEach { parent ->
                    val context = parent?.source ?: tree.root
                    if (!evaluated.add(index to context.path)) return@forEach
                    val selection = op.at.resolve(context, includeNull = true) ?: return@forEach
                    consumption.open(selection)
                    if (selection.value == JsonNull) return@forEach
                    val sources = if (op.value.string("at").isEmpty()) listOf(selection) else selection.entries()
                    for (source in sources) {
                        val mapping = op.value.getValue("mapping").jsonObject
                        val kindNode = op.value.optionalString("kind")?.let { JsonPointer(it).resolve(source) }
                        val foreignKind = kindNode?.value?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                            ?: if ("kind" !in op.value && mapping.size == 1) mapping.keys.single() else null
                        val kind = mapping[foreignKind]?.jsonPrimitive?.content ?: throw ProfileException(
                            "ys/unknown-kind", (kindNode ?: source).span(key = true), "Unmapped record kind $foreignKind",
                        )
                        val idNode = JsonPointer(op.value.optionalString("id") ?: "/\$key").resolve(source)
                        val id = (idNode?.value as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: throw ProfileException("ys/record-id-missing", source.span(), "Record needs a string id")
                        if (source.path in found) throw ProfileException(
                            "ys/profile-conflict", source.span(key = true), "Two kinds operations select the same record",
                        )
                        // A kind selecting itself would create an infinite nested record graph.
                        if (parent?.source?.path == source.path) throw ProfileException(
                            "ys/profile-conflict", source.span(key = true), "A record cannot nest inside itself",
                        )
                        consumption.open(source)
                        kindNode?.let(consumption::consume)
                        idNode?.let(consumption::consume)
                        found[source.path] = Record(source, kind, id, parent)
                        changed = true
                    }
                }
            }
        } while (changed)
    }
}
