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

    /**
     * [UnknownKeys.ERROR]: the first unconsumed key or value is `ys/unknown-key`. [UnknownKeys.IGNORE] (✅D-MW-89): every
     * unconsumed node, in source order — each dropped whole, nothing beneath it visited — with its pointer segments,
     * list indices as `*`.
     */
    fun check(
        root: ForeignNode,
        unknown: UnknownKeys = UnknownKeys.ERROR,
    ): List<Pair<ForeignNode, List<String>>> {
        val dropped = mutableListOf<Pair<ForeignNode, List<String>>>()

        fun visit(
            node: ForeignNode,
            shape: List<String>,
        ) {
            if (node.path in consumed) return
            when (node.value) {
                is JsonObject ->
                    node.entries().forEach { child ->
                        val childShape = shape + escapePointer(child.mappingKey!!)
                        if (child.path !in consumed && child.path !in opened) {
                            if (unknown == UnknownKeys.ERROR) {
                                throw ProfileException(
                                    "ys/unknown-key",
                                    child.span(key = true),
                                    "Unconsumed key ${child.mappingKey}",
                                )
                            }
                            dropped += child to childShape
                        } else {
                            visit(child, childShape)
                        }
                    }
                is JsonArray -> node.entries().forEach { visit(it, shape + "*") }
                JsonNull -> Unit
                else ->
                    if (unknown == UnknownKeys.ERROR) {
                        throw ProfileException("ys/unknown-key", node.span(), "Unconsumed value ${node.path}")
                    } else {
                        dropped += node to shape
                    }
            }
        }
        visit(root, emptyList())
        return dropped
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
    private val skips = linkedMapOf<String, Pair<ForeignNode, Ignored>>()

    /**
     * ✅D-MW-81 (S5.2a): the nodes a `kinds` mapping value `null` skipped — no record, no error, each listed once in
     * `ignored[]`. A node another `kinds` op made a record of is not skipped (that record's keys stay checked).
     */
    lateinit var skipped: List<Ignored>
        private set

    /** ✅D-MW-87: the warnings the operations of this tree raised, in order (one per record and message). */
    val warnings = mutableListOf<ProfileWarning>()

    /**
     * ✅D-MW-89 (S5.2c): the keys [ConsumptionLedger.check] dropped under `unknown: ignore`, as one warning per (record
     * kind, key path) — the path relative to the innermost record (to the file when none holds it), list indices as `*`;
     * located at the first occurrence; counting the records it was dropped from (a file-scoped key: its occurrences).
     */
    fun dropped(nodes: List<Pair<ForeignNode, List<String>>>): List<ProfileWarning> {
        val byPath = records.groupBy { it.source.path }.mapValues { it.value.first() }

        fun holder(path: String): Record? {
            var prefix = path
            while (prefix.isNotEmpty()) {
                prefix = prefix.substringBeforeLast('/', "")
                byPath[prefix]?.let { return it }
            }
            return null
        }
        val groups = linkedMapOf<Pair<String?, String>, Pair<ForeignNode, MutableSet<Any>>>()
        nodes.forEach { (node, shape) ->
            val record = holder(node.path)
            val depth = record?.source?.path?.count { it == '/' } ?: 0
            val key = shape.drop(depth).joinToString("") { "/$it" }
            groups.getOrPut(record?.kind to key) { node to mutableSetOf() }.second += record ?: node.path
        }
        return groups.map { (group, first) ->
            ProfileWarning(
                first.first.span(key = first.first.mappingKey != null),
                "not in this profile",
                scope = group.first,
                key = group.second,
                count = first.second.size,
            )
        }
    }

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
                    .filter { it != JsonNull }
                    .map { kind -> kind.jsonPrimitive.content }
            }.distinct()

    init {
        val evaluated = mutableSetOf<Pair<Int, String>>()
        // Strict refusals wait for the fixpoint: one beneath a null-skip is no error, and the skip may be found later
        // in either op order (review-338 B1). The first refusal per node is kept, in discovery order.
        val refusals = linkedMapOf<String, ProfileException>()
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
                        if (!lenient) {
                            refusals.putIfAbsent(
                                selection.path,
                                ProfileException(
                                    "ys/profile-invalid",
                                    selection.span(key = true),
                                    "kinds.at must select a sequence or mapping",
                                ),
                            )
                        }
                        return@forEach
                    }
                    val sources = if (op.value.string("at").isEmpty()) listOf(selection) else selection.entries()
                    for (source in sources) {
                        val record =
                            try {
                                discover(op, source, parent)
                            } catch (e: ProfileException) {
                                if (!lenient) refusals.putIfAbsent(source.path, e)
                                continue
                            } ?: continue
                        found[source.path to record.kind] = record
                        madeBy[record] = index
                        changed = true
                    }
                }
            }
        } while (changed)
        val recorded = found.keys.map { it.first }.toSet()
        // Nothing beneath a skipped node is discovered, whichever op ran first (review-338 B1): a record or a nested
        // skip below it is dropped. A node another `kinds` op made a record of is not skipped.
        val skippedPaths = skips.keys.filter { it !in recorded }

        fun beneathSkip(path: String) = skippedPaths.any { path.startsWith("$it/") }
        refusals.entries.firstOrNull { !beneathSkip(it.key) }?.let { throw it.value }
        found.entries.removeIf { (key, record) ->
            beneathSkip(key.first).also { if (it) madeBy.remove(record) }
        }
        skipped =
            skips
                .filterKeys { it in skippedPaths && !beneathSkip(it) }
                .values
                .map { (node, ignored) ->
                    consumption.consume(node)
                    ignored
                }
    }

    /**
     * One record from [source], null when it is skipped — its kind maps to `null` (✅D-MW-81), or `unmapped: skip` meets
     * a kind not in `mapping` or no kind at all (✅D-MW-87) — or the error that refuses it; the ledgers change only when
     * it is a record. A kind that is a list (a `kind` pointer with a `*` segment, or one selecting a list of strings) takes
     * its first element in list order that `mapping` names; a `null` mapping counts as named.
     */
    private fun discover(
        op: PlannedOp,
        source: ForeignNode,
        parent: Record?,
    ): Record? {
        val mapping = op.value.getValue("mapping").jsonObject
        val kindText = op.value.optionalString("kind")
        val candidates = kindText?.let { kindCandidates(it, source) }.orEmpty()
        val kindNode =
            candidates.firstOrNull { (_, value) -> value in mapping }?.first ?: candidates.firstOrNull()?.first
        // Without `*`, the node the pointer selects (a string, a list, or null) is what an error names and what is consumed.
        val pointed =
            kindText?.takeUnless { "/*/" in "$it/" }?.let { JsonPointer(it).resolve(source, includeNull = true) }
        val foreignKind =
            if (kindText == null) {
                if (mapping.size == 1) mapping.keys.single() else null
            } else {
                candidates.firstOrNull { (_, value) -> value in mapping }?.second
            }
        val skipUnmapped = op.value.optionalString("unmapped") == "skip"

        fun skip(reason: String): Record? {
            val id = JsonPointer(op.value.optionalString("id") ?: "/\$key").resolve(source)
            skips.putIfAbsent(
                source.path,
                source to
                    Ignored(
                        source.span(key = true),
                        reason,
                        (id?.value as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    ),
            )
            return null
        }
        if (foreignKind != null && mapping[foreignKind] == JsonNull) {
            return skip("kind mapping null (${kindText ?: "kind"} = $foreignKind)")
        }
        if (foreignKind == null && skipUnmapped) {
            return if (candidates.isEmpty()) {
                skip("kind missing ($kindText)")
            } else {
                skip("kind unmapped (${candidates.joinToString(", ") { it.second }})")
            }
        }
        val kind =
            mapping[foreignKind]?.jsonPrimitive?.content ?: throw ProfileException(
                "ys/unknown-kind",
                (kindNode ?: pointed ?: source).span(key = true),
                if (candidates.isEmpty()) {
                    "Unmapped record kind: no string at ${kindText ?: ""}"
                } else {
                    "Unmapped record kind ${candidates.joinToString(", ") { it.second }}"
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
        (pointed ?: kindNode)?.let(consumption::consume)
        idNode?.let(consumption::consume)
        return Record(source, kind, id, parent)
    }

    /**
     * The strings a `kinds.kind` pointer reads from [source], in order: the string it selects; each string of a list it
     * selects; or, where one segment is `*`, the pointer after it read in each element of the list before it.
     */
    private fun kindCandidates(
        pointer: String,
        source: ForeignNode,
    ): List<Pair<ForeignNode, String>> {
        fun string(node: ForeignNode?) = (node?.value as? JsonPrimitive)?.takeIf { it.isString }?.content
        val segments = if (pointer.isEmpty()) emptyList() else pointer.drop(1).split('/')
        val star = segments.indexOf("*")
        val list =
            if (star < 0) {
                JsonPointer(pointer).resolve(source)
            } else {
                JsonPointer(segments.take(star).joinToString("") { "/$it" }).resolve(source)
            } ?: return emptyList()
        val within = if (star < 0) JsonPointer("") else JsonPointer(segments.drop(star + 1).joinToString("") { "/$it" })
        if (list.value !is JsonArray) {
            return if (star < 0) listOfNotNull(string(list)?.let { list to it }) else emptyList()
        }
        return list.entries().mapNotNull { element ->
            within.resolve(element)?.let { node -> string(node)?.let { node to it } }
        }
    }
}
