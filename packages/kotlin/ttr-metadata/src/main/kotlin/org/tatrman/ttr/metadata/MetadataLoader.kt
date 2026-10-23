// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.reconcile.ReconciliationPolicy
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.ModelSource

/**
 * Thin composition (contracts §2, no new logic): parse ([ModelSource]s) →
 * reconcile (+ the reconciler's post-load reference-resolution pass) → [Model].
 * Mirrors the sequence the kantheon metadata service's `Application.kt` composes today, minus its
 * Ktor/env parts. Never throws on model errors — they come back in [LoadResult.issues]
 * as the finalized, id-free [LoadIssue] taxonomy (M2.2 T2.2.3).
 *
 * **RV-P3.2 — [sources] is a list**, because the reconciler has always taken one and a real model
 * needs at least two: the estate's files *and*
 * [org.tatrman.ttr.metadata.source.BuiltinStockSource], which supplies the `cnc.role.*` vocabulary
 * `roles: [fact]` resolves against. A single-source load of any estate that uses stock roles —
 * i.e. every one of them — reports `ttr/unimported-reference` for each such reference, which is
 * how this was found. The single-source constructor below is kept and delegates.
 */
class MetadataLoader(
    private val sources: List<ModelSource>,
    private val descriptor: ModelDescriptor = ModelDescriptor(id = "model", name = "model"),
    private val policy: ReconciliationPolicy = ReconciliationPolicy(),
) {
    constructor(
        source: ModelSource,
        descriptor: ModelDescriptor = ModelDescriptor(id = "model", name = "model"),
        policy: ReconciliationPolicy = ReconciliationPolicy(),
    ) : this(listOf(source), descriptor, policy)

    fun load(): LoadResult = load(LoadOptions())

    fun load(options: LoadOptions): LoadResult =
        runCatching {
            // Order is the caller's: the reconciler resolves by priority, and the stock source
            // registers ahead of user sources exactly as it does at the service's boot.
            val snapshots = sources.map { it.load() }
            val result = ModelReconciler(descriptor, policy).reconcile(snapshots)
            val issues =
                result.errors.map { LoadIssue.from(it, LoadIssue.Severity.ERROR) } +
                    result.warnings.map { LoadIssue.from(it, LoadIssue.Severity.WARNING) } +
                    snapshots.flatMap { it.mdHopIssues }
            LoadResult(
                model = result.model,
                issues = issues,
                report = if (options.strict) LoadReport.collect(snapshots, issues, options) else null,
            )
        }.getOrElse { e ->
            val issue =
                LoadIssue.from(
                    LoadWarning("loader", "", -1, -1, "load failed: ${e.message}"),
                    LoadIssue.Severity.ERROR,
                )
            LoadResult(
                model = null,
                issues = listOf(issue),
                report = if (options.strict) LoadReport.collect(emptyList(), listOf(issue), options) else null,
            )
        }
}

/** Outcome of [MetadataLoader.load]. `model` is null only on a catastrophic load failure. */
data class LoadResult(
    val model: Model?,
    val issues: List<LoadIssue>,
    /** Null unless [LoadOptions.strict] requested validation. */
    val report: LoadReport? = null,
) {
    val errors: List<LoadIssue> get() = issues.filter { it.severity == LoadIssue.Severity.ERROR }
    val warnings: List<LoadIssue> get() = issues.filter { it.severity == LoadIssue.Severity.WARNING }
}
