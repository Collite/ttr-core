// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.metadata.source.underRoot
import org.tatrman.ttr.semantics.duplicateDefinitions
import org.tatrman.ttr.semantics.SymbolTable

/** Package-declaration mismatch policy from `modeler.toml`, matching the TypeScript lint. */
enum class PackageLayout { Flexible, Strict, Off }

/** Opt-in validation for a checkout gate; does not change model construction or registry swap policy. */
data class LoadOptions(
    val strict: Boolean = false,
    val packageLayout: PackageLayout = PackageLayout.Flexible,
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
    val sourceId: String,
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
            options: LoadOptions,
        ): LoadReport {
            val dropped =
                snapshots
                    .flatMap { it.droppedFiles }
                    .map { it.file }
                    .distinct()
                    .sorted()
            val reportIssues =
                issues.mapNotNull { issue ->
                    val severity =
                        if (issue.category == LoadIssue.Category.PACKAGE_MISMATCH) {
                            when (options.packageLayout) {
                                PackageLayout.Off -> return@mapNotNull null
                                PackageLayout.Flexible -> LoadIssue.Severity.WARNING
                                PackageLayout.Strict -> LoadIssue.Severity.ERROR
                            }
                        } else {
                            issue.severity
                        }
                    LoadReportIssue(
                        issue.file,
                        issue.line,
                        if (issue.file in
                            dropped
                        ) {
                            "dropped-file"
                        } else {
                            issue.category.name
                                .lowercase()
                                .replace('_', '-')
                        },
                        severity,
                        issue.message,
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
                    val sourceNames =
                        peers.flatMap { it.loadedFiles }.associate { file ->
                            val storage = file.storageFile
                            storage.path to
                                listOfNotNull(
                                    storage.rootPath?.fileName?.toString(),
                                    storage.underRoot(),
                                ).joinToString("/")
                        }
                    duplicateDefinitions(symbols.duplicates()) { entry ->
                        "${sourceNames[entry.documentUri] ?: entry.documentUri}:${entry.source.line}"
                    }.map { diagnostic ->
                        LoadReportIssue(
                            diagnostic.source.file,
                            diagnostic.source.line,
                            "duplicate-qname",
                            LoadIssue.Severity.ERROR,
                            diagnostic.message,
                        )
                    }
                }
            return LoadReport(
                issues = (reportIssues + duplicates).distinct(),
                droppedFiles = dropped,
                sources =
                    snapshots
                        .flatMap { snapshot ->
                            snapshot.loadedFiles.map {
                                LoadSource(snapshot.sourceId, it.storageFile.path, it.definitions.size)
                            }
                        }.sortedBy { it.path },
            )
        }
    }
}
