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
                is JsonObject ->
                    node.entries().forEach { child ->
                        if (child.path !in consumed && child.path !in opened) {
                            throw ProfileException(
                                "ys/unknown-key",
                                child.span(key = true),
                                "Unconsumed key ${child.mappingKey}",
                            )
                        }
                        visit(child)
                    }
                is JsonArray -> node.entries().forEach(::visit)
                JsonNull -> Unit
                else -> throw ProfileException("ys/unknown-key", node.span(), "Unconsumed value ${node.path}")
            }
        }
        visit(root)
    }
}

/** A target prefix owns its entire subtree; siblings remain independently writable. */
internal class ConflictLedger {
    private val writes = mutableMapOf<Record, MutableList<List<String>>>()
    private val targets = mutableMapOf<Record, Pair<Record, List<String>>>()

    fun register(
        record: Record,
        root: Record,
        path: List<String>,
    ) {
        targets[record] = root to path
    }

    fun claim(
        record: Record,
        path: List<String>,
        source: ForeignNode,
    ) {
        val (root, prefix) = targets[record] ?: (record to emptyList())
        val target = prefix + path
        val previous = writes.getOrPut(root) { mutableListOf() }
        if (previous.any { target.take(it.size) == it || it.take(target.size) == target }) {
            throw ProfileException(
                "ys/profile-conflict",
                source.span(key = true),
                "Two operations write ${record.kind}.${record.id}/${path.joinToString("/")}",
            )
        }
        previous += target
    }
}

internal class PlannedOp(
    val located: LocatedOp,
) {
    val value = located.value
    val name = value.string("op")
    val scope = value.optionalString("in")
    val at = JsonPointer(value.optionalString("at") ?: "")
    private val files = value["files"]?.jsonArray?.map { it.jsonPrimitive.content }?.let(::FileSelector)

    fun matches(tree: ForeignTree) = files?.matches(tree.file) != false
}

/**
 * Record discovery is a separate pass, so child selectors never depend on writing-operation order.
 *
 * S3.2b (✅D-MW-67 c′): a record is identified by (node path, canonical kind), so one node may become one record per
 * canonical kind — an `entity` and its `er2db_entity` — each going to its own kind's model document.
 *
 * [lenient] (S3.7 T0, review-277 N1) is the capture's mode: a source or selection discovery would refuse (unmapped
 * kind, missing id, self-nesting, two kinds on one record, a `kinds.at` that selects a scalar) is skipped — it is not a
 * record, and discovery goes on. `apply` keeps the strict plan: it is the gate (contracts §6).
 */
internal class RecordPlan(
    val tree: ForeignTree,
    private val operations: List<PlannedOp>,
    private val lenient: Boolean = false,
) {
    val consumption = ConsumptionLedger()
    val conflicts = ConflictLedger()
    private val found = linkedMapOf<Pair<String, String>, Record>()
    private val madeBy = mutableMapOf<Record, Int>()

    /** Source position first; records of one node follow the index of the `kinds` operation that made them. */
    val records: List<Record> by lazy {
        val order = mutableMapOf<String, Int>()

        fun walk(node: ForeignNode) {
            order[node.path] = order.size
            node.entries().forEach(::walk)
        }
        walk(tree.root)
        found.values.sortedWith(compareBy({ order.getValue(it.source.path) }, { madeBy.getValue(it) }))
    }

    fun nestedKinds(parent: Record): List<String> =
        operations
            .filter {
                it.name == "kinds" && it.scope == parent.kind && it.matches(tree)
            }.flatMap {
                it.value
                    .getValue("mapping")
                    .jsonObject.values
                    .map { kind -> kind.jsonPrimitive.content }
            }.distinct()

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
                    if (op.value.string("at").isNotEmpty() &&
                        selection.value !is JsonObject &&
                        selection.value !is JsonArray
                    ) {
                        if (lenient) return@forEach
                        throw ProfileException(
                            "ys/profile-invalid",
                            selection.span(key = true),
                            "kinds.at must select a sequence or mapping",
                        )
                    }
                    val sources = if (op.value.string("at").isEmpty()) listOf(selection) else selection.entries()
                    for (source in sources) {
                        val record =
                            try {
                                discover(op, source, parent)
                            } catch (e: ProfileException) {
                                if (lenient) continue
                                throw e
                            }
                        found[source.path to record.kind] = record
                        madeBy[record] = index
                        changed = true
                    }
                }
            }
        } while (changed)
    }

    /** One record from [source], or the error that refuses it; the ledgers change only when it is a record. */
    private fun discover(
        op: PlannedOp,
        source: ForeignNode,
        parent: Record?,
    ): Record {
        val mapping = op.value.getValue("mapping").jsonObject
        val kindNode =
            op.value
                .optionalString(
                    "kind",
                )?.let { JsonPointer(it).resolve(source, includeNull = true) }
        val foreignKind =
            kindNode?.value?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: if ("kind" !in op.value && mapping.size == 1) mapping.keys.single() else null
        val kind =
            mapping[foreignKind]?.jsonPrimitive?.content ?: throw ProfileException(
                "ys/unknown-kind",
                (kindNode ?: source).span(key = true),
                if (foreignKind ==
                    null
                ) {
                    "Unmapped record kind: no string at ${op.value.optionalString("kind") ?: ""}"
                } else {
                    "Unmapped record kind $foreignKind"
                },
            )
        val idNode = JsonPointer(op.value.optionalString("id") ?: "/\$key").resolve(source)
        val id =
            (idNode?.value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw ProfileException(
                    "ys/record-id-missing",
                    source.span(),
                    "Record needs a string id",
                )
        // A kind selecting itself would create an infinite nested record graph.
        if (parent?.source?.path == source.path) {
            throw ProfileException(
                "ys/profile-conflict",
                source.span(key = true),
                "A record cannot nest inside itself",
            )
        }
        if ((source.path to kind) in found) {
            throw ProfileException(
                "ys/profile-conflict",
                source.span(key = true),
                "Two kinds operations select the same record",
            )
        }
        consumption.open(source)
        kindNode?.let(consumption::consume)
        idNode?.let(consumption::consume)
        return Record(source, kind, id, parent)
    }
}
