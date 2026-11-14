// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import java.nio.file.Path

internal interface Op {
    fun apply(
        plan: RecordPlan,
        builder: CanonicalBuilder,
        ignored: MutableList<Ignored>,
    )
}

internal class PackageOp(
    private val op: PlannedOp,
) {
    fun select(plan: RecordPlan): String {
        val selected = if ("at" in op.value) op.at.resolve(plan.tree.root, includeNull = true) else null
        selected?.let(plan.consumption::consume)
        val key =
            if ("at" in op.value) {
                (selected?.value as? JsonPrimitive)?.takeIf { it.isString }?.content
            } else {
                plan.tree.file
                    .substringAfterLast('/')
                    .substringBeforeLast('.', plan.tree.file.substringAfterLast('/'))
            }
        return op.value
            .getValue("mapping")
            .jsonObject[key]
            ?.jsonPrimitive
            ?.content
            ?: op.value.optionalString("default")
            ?: throw ProfileException(
                "ys/value-unmapped",
                ProfileSpan(plan.tree.label, 1, 1, 1, 1, ""),
                if (key ==
                    null
                ) {
                    "Unmapped package key: no string at ${op.value.string("at")}"
                } else {
                    "Unmapped package key $key"
                },
            )
    }
}

/** rename, nest and unnest all select the original input and place it at a schema-validated property path. */
internal class WriteOp(
    private val op: PlannedOp,
) : Op {
    override fun apply(
        plan: RecordPlan,
        builder: CanonicalBuilder,
        ignored: MutableList<Ignored>,
    ) {
        val path = op.targetPath()
        for (record in plan.records.filter { it.kind == op.scope }) {
            val source = op.at.resolve(record.source, includeNull = true) ?: continue
            plan.consumption.consume(source)
            if (source.value != JsonNull) builder.write(plan, record, path, source.value, source)
        }
    }
}

internal class IgnoreOp(
    private val op: PlannedOp,
) : Op {
    override fun apply(
        plan: RecordPlan,
        builder: CanonicalBuilder,
        ignored: MutableList<Ignored>,
    ) {
        val contexts =
            if (op.scope ==
                null
            ) {
                listOf(plan.tree.root)
            } else {
                plan.records.filter { it.kind == op.scope }.map { it.source }
            }
        contexts.forEach { context ->
            op.at.resolve(context, includeNull = true)?.let { source ->
                plan.records
                    .firstOrNull { record ->
                        record.source.path == source.path || record.source.path.startsWith("${source.path}/")
                    }?.let { record ->
                        throw ProfileException(
                            "ys/profile-conflict",
                            source.span(key = true),
                            "ignore covers record ${record.kind}.${record.id}",
                        )
                    }
                plan.consumption.consume(source)
                ignored += Ignored(source.span(key = true), op.value.optionalString("reason"))
            }
        }
    }
}

internal object StructuralExecutor {
    /** [root] is the model root: `<package>.sources.json` sidecars and `renames.yaml` are read from it when given. */
    fun apply(
        tree: List<ForeignTree>,
        operations: List<LocatedOp>,
        overlay: List<OverlayEntry> = emptyList(),
        root: Path? = null,
        perFile: Boolean = false,
    ): ApplyResult {
        val ops = operations.map(::PlannedOp)
        val ignored = mutableListOf<Ignored>()
        val codes = linkedMapOf<String, String>()
        ops.filter { it.name == "codes" }.forEach { op ->
            op.value
                .getValue("mapping")
                .jsonObject
                .forEach { (key, value) -> codes[key] = value.jsonPrimitive.content }
        }
        val plans =
            tree.mapNotNull { input ->
                val matches =
                    ops.filter {
                        it.name == "ignore" &&
                            it.scope == null &&
                            "at" !in it.value &&
                            it.matches(
                                input,
                            )
                    }
                if (matches.isNotEmpty()) {
                    matches.forEach { ignored += Ignored(input.root.span(), it.value.optionalString("reason")) }
                    null
                } else {
                    RecordPlan(input, ops).also { plan ->
                        val root = input.root.value as? JsonObject
                        if (root?.keys?.firstOrNull() == "ttr" && root["ttr"] is JsonObject) {
                            plan.consumption.consume(input.root.child("ttr", root.getValue("ttr")))
                        }
                    }
                }
            }
        val packages =
            plans.associateWith { plan ->
                val packageOps = ops.filter { it.name == "package" && it.matches(plan.tree) }
                val selections = packageOps.map { PackageOp(it).select(plan) }
                if (selections.size >
                    1
                ) {
                    throw ProfileException(
                        "ys/profile-conflict",
                        plan.tree.root.span(),
                        "Two package operations select the same file",
                    )
                }
                selections.singleOrNull() ?: if (plan.records.isEmpty()) {
                    ""
                } else {
                    throw ProfileException(
                        "ys/value-unmapped",
                        ProfileSpan(plan.tree.label, 1, 1, 1, 1, ""),
                        "No package operation for ${plan.tree.file}",
                    )
                }
            }
        val builder = CanonicalBuilder(plans, packages, perFile)
        val index = LinkIndex.build(plans, packages, root)
        plans.forEach { plan ->
            ops.filter { it.matches(plan.tree) }.forEach { op ->
                val implementation =
                    when (op.name) {
                        "kinds", "package", "codes" -> null
                        "rename", "nest", "unnest" -> WriteOp(op)
                        "values" -> ValuesOp(op)
                        "shape" -> ShapeOp(op)
                        "link" -> LinkOp(op, index, packages.getValue(plan))
                        "ignore" -> IgnoreOp(op)
                        else -> throw ProfileException(
                            "ys/profile-invalid",
                            op.located.location,
                            "Unknown operation ${op.name}",
                        )
                    }
                implementation?.apply(plan, builder, ignored)
            }
            plan.consumption.check(plan.tree.root)
        }
        val overridden = OverlayApplier.apply(overlay, index, builder, root)
        return ApplyResult(builder.documents(), ignored.distinct(), ApplyReport(codes, overridden), index.sources())
    }
}
