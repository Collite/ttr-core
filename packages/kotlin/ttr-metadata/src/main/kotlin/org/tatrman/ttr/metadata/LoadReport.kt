// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.semantics.MappingSource
import org.tatrman.ttr.semantics.SymbolTable

/** Opt-in validation for a checkout gate; does not change model construction or registry swap policy. */
data class LoadOptions(
    val strict: Boolean = false,
)

data class LoadReportIssue(
    val file: String?,
    val line: Int,
    val code: String,
    val severity: LoadIssue.Severity,
    val message: String,
)

/** A successfully parsed input document. [objects] counts its authored top-level definitions. */
data class LoadSource(
    val path: String,
    val objects: Int,
)

data class LoadReport(
    val issues: List<LoadReportIssue> = emptyList(),
    val droppedFiles: List<String> = emptyList(),
    val sources: List<LoadSource> = emptyList(),
) {
    val ok: Boolean get() = issues.none { it.severity == LoadIssue.Severity.ERROR }

    companion object {
        internal fun collect(
            snapshots: List<SourceSnapshot>,
            issues: List<LoadIssue>,
        ): LoadReport {
            val dropped =
                snapshots
                    .flatMap { it.droppedFiles }
                    .map { it.file }
                    .distinct()
                    .sorted()
            val reportIssues =
                issues.map {
                    LoadReportIssue(
                        it.file,
                        it.line,
                        if (it.file in
                            dropped
                        ) {
                            "dropped-file"
                        } else if (it.severity == LoadIssue.Severity.ERROR) {
                            "reconcile-error"
                        } else {
                            it.category.name
                                .lowercase()
                                .replace('_', '-')
                        },
                        it.severity,
                        it.message,
                    )
                }
            // A FileBasedSource stores objects in maps, so duplicates within it have already been
            // overwritten before ModelReconciler sees them. Reuse the canonical symbol identities and
            // the Validator's inline-binding / concept-sugar exemptions over the retained ASTs.
            // Different source priorities deliberately override each other; only peers are duplicates.
            val duplicates =
                snapshots.groupBy { it.priority }.values.flatMap { peers ->
                    val symbols = SymbolTable()
                    peers.flatMap { it.loadedFiles }.forEach { file ->
                        symbols.upsertDocument(
                            file.storageFile.path,
                            file.definitions,
                            file.schemaCode,
                            file.namespace,
                            packageName = file.declaredPackage ?: file.computedPackage,
                        )
                    }
                    symbols.duplicates().flatMap { duplicate ->
                        val inlineBinding =
                            duplicate.entries.any { it.mappingSource == MappingSource.Inline } &&
                                duplicate.entries.first().kind in
                                setOf("er2dbEntity", "er2dbAttribute", "er2dbRelation")
                        val entries = duplicate.entries.filter { it.cnOrigin == null }
                        if (inlineBinding || entries.size < 2) {
                            emptyList()
                        } else {
                            entries.map { entry ->
                                LoadReportIssue(
                                    entry.documentUri,
                                    entry.source.line,
                                    "reconcile-error",
                                    LoadIssue.Severity.ERROR,
                                    "Duplicate definition of '${duplicate.qname}' (also at " +
                                        entries
                                            .filter {
                                                it !== entry
                                            }.joinToString { "${it.documentUri}:${it.source.line}" } +
                                        ")",
                                )
                            }
                        }
                    }
                }
            return LoadReport(
                issues = (reportIssues + duplicates).distinct(),
                droppedFiles = dropped,
                sources =
                    snapshots
                        .flatMap { it.loadedFiles }
                        .map {
                            LoadSource(it.storageFile.path, it.definitions.size)
                        }.sortedBy { it.path },
            )
        }
    }
}
