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
        if (op.value["verbatim"] == JsonPrimitive(true)) return verbatim(plan, selected)
        val key =
            if ("at" in op.value) {
                (selected?.value as? JsonPrimitive)?.takeIf { it.isString }?.content
            } else {
                stem(plan.tree.label)
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

    /**
     * review-378 F4: a `mapping` without `at` keys on the stem of the **input** the tree was read from — its label, less
     * any `#part` (RDF's `#subjects`) — not of its `file`: the `xmi` front-end's per-package trees are files
     * `<stem>/<package path>` with the input's name as label. For every one-tree front-end the two stems agree.
     */
    private fun stem(label: String): String {
        val name = label.substringBefore('#').substringAfterLast('/')
        return name.substringBeforeLast('.', name)
    }

    /**
     * `verbatim: true` (✅D-MW-91, MW-P5 S5.3a): the string at `at` is the package path as written; its `/` separators
     * (the `xmi` front-end's UML package path) become `.`, the only change. A path that is then no TTR package path
     * (`[a-z][a-z0-9_]*(.[a-z][a-z0-9_]*)*`), or no string, is `ys/package-invalid` at the tree's first record; a tree
     * that holds no record names no package (`""`, as with no `package` operation).
     */
    private fun verbatim(
        plan: RecordPlan,
        selected: ForeignNode?,
    ): String {
        if (plan.records.isEmpty()) return ""
        val written = (selected?.value as? JsonPrimitive)?.takeIf { it.isString }?.content
        val path = written?.replace('/', '.')
        if (path != null && PACKAGE_PATH.matches(path)) return path
        throw ProfileException(
            "ys/package-invalid",
            plan.records
                .first()
                .source
                .span(key = true),
            if (written == null) {
                "no package path at ${op.value.string("at")}"
            } else {
                "\"$written\" is no TTR package path (verbatim: lower-case names, `/` → `.`)"
            },
        )
    }

    private companion object {
        val PACKAGE_PATH = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")
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
            val source =
                op.at.resolve(record.source, includeNull = true) ?: run {
                    op.missing(plan, record, path)
                    continue
                }
            plan.consumption.consume(source)
            if (source.value == JsonNull) {
                op.missing(plan, record, path)
            } else {
                builder.write(plan, record, path, source.value, source)
            }
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
    /**
     * [root] is the model root: `<package>.sources.json` sidecars and `renames.yaml` are read from it when given.
     * [sourceName] is the records' `sourceName` pointer ([LinkIndex.build]).
     */
    fun apply(
        tree: List<ForeignTree>,
        operations: List<LocatedOp>,
        overlay: List<OverlayEntry> = emptyList(),
        root: Path? = null,
        perFile: Boolean = false,
        sourceName: JsonPointer? = null,
        unknown: UnknownKeys = UnknownKeys.ERROR,
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
        val index = LinkIndex.build(plans, packages, root, sourceName)
        plans.forEach { plan ->
            ignored += plan.skipped
            ops.filter { it.matches(plan.tree) }.forEach { op ->
                val implementation =
                    when (op.name) {
                        "kinds", "package", "codes" -> null
                        "rename", "nest", "unnest" -> WriteOp(op)
                        "values" -> ValuesOp(op)
                        "shape" -> ShapeOp(op)
                        "link" -> LinkOp(op, index, packages.getValue(plan))
                        "join-pairs" -> JoinPairsOp(op)
                        "each" -> EachOp(op)
                        "ignore" -> IgnoreOp(op)
                        else -> throw ProfileException(
                            "ys/profile-invalid",
                            op.located.location,
                            "Unknown operation ${op.name}",
                        )
                    }
                implementation?.apply(plan, builder, ignored)
            }
            plan.warnings += plan.dropped(plan.consumption.check(plan.tree.root, unknown))
        }
        val overridden = OverlayApplier.apply(overlay, index, builder, root)
        return ApplyResult(
            builder.documents(),
            ignored.distinct(),
            ApplyReport(codes, overridden, plans.flatMap { it.warnings }.distinct()),
            index.sources(),
            plans.flatMap { plan ->
                val pkg = packages.getValue(plan)
                plan.records.map { RecordOrigin(Qnames.of(it, pkg), it.kind, pkg, plan.tree.label, it.source.path) }
            },
        )
    }
}
