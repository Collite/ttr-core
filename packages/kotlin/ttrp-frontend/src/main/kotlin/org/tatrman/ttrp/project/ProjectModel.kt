// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.project

import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.LoadReport
import org.tatrman.ttr.metadata.LoadReportIssue
import org.tatrman.ttr.metadata.LoadResult
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.PackageLayout
import org.tatrman.ttr.metadata.cli.MetadataLoadCli
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path

/**
 * AU-export S1.5.4 (D-173): a TTR-P project's model, loaded as the services load it. Every model root the manifest
 * declares (`models/` and `[ttrp] extra-model-roots`, a missing one skipped) is one file source; the built-in stock
 * roles load ahead of them (`roles: [fact]` resolves as it does in Metadata); the automation stdlib is the
 * reconciler's own injection. The verdict is the [LoadReport] under the manifest's `[packages].layout`, read by
 * [MetadataLoadCli.readManifest] — so a hartland-shaped repository (`model/er/x.ttrm` declaring `package hartland`
 * under `layout = "off"`) loads, and `strict` still refuses the mismatch.
 */
object ProjectModel {
    /** The source id of root [index]: `ttrp` for the first, `ttrp-<i>` after it (the ids `ModelRepo` always used). */
    fun sourceId(index: Int): String = if (index == 0) "ttrp" else "ttrp-$index"

    /** The manifest's existing model roots, in declaration order, symlinks resolved (`Files.walk` does not follow a root link). */
    fun roots(manifest: TtrpManifest): List<Path> =
        existing(
            listOf(manifest.modelsRoot()) + manifest.extraModelRootPaths(),
        )

    /** `[packages].layout` of the manifest's `modeler.toml` and its issues; no file → `flexible`, no issue. */
    fun layout(manifest: TtrpManifest): Pair<PackageLayout, List<LoadReportIssue>> {
        val file = manifest.manifestDir.resolve("modeler.toml")
        return if (Files.isRegularFile(file)) {
            MetadataLoadCli.readManifest(file)
        } else {
            PackageLayout.Flexible to
                emptyList()
        }
    }

    /** The project model with its report; the manifest's own issues lead the report's. */
    fun load(manifest: TtrpManifest): Loaded {
        val (layout, manifestIssues) = layout(manifest)
        val roots = roots(manifest)
        val result = load(roots, layout)
        val report = requireNotNull(result.report)
        return Loaded(result, report.copy(issues = manifestIssues + report.issues), roots)
    }

    /** [roots] (existing directories) plus the stock roles, every root a peer, reported under [layout]. */
    fun load(
        roots: List<Path>,
        layout: PackageLayout,
    ): LoadResult =
        MetadataLoader(
            listOf(BuiltinStockSource()) +
                existing(roots).mapIndexed { i, root ->
                    val id = sourceId(i)
                    FileBasedSource(
                        sourceId = id,
                        priority = ROOT_PRIORITY,
                        storage = LocalFsStorage(id = id, rootPath = root),
                    )
                },
        ).load(LoadOptions(strict = true, packageLayout = layout))

    /** The roots' priority: one for all, as the services give theirs, so a qname two roots define is a duplicate. */
    const val ROOT_PRIORITY = 100

    private fun existing(roots: List<Path>): List<Path> =
        roots
            .filter { Files.isDirectory(it) }
            .map { runCatching { it.toRealPath() }.getOrDefault(it.toAbsolutePath().normalize()) }
            .distinct()

    data class Loaded(
        val result: LoadResult,
        val report: LoadReport,
        val roots: List<Path>,
    ) {
        val ok: Boolean get() = result.model != null && report.ok

        /** The report's errors, one per line, paths relative to [base]. */
        fun errors(base: Path): String {
            val real = runCatching { base.toRealPath() }.getOrDefault(base)
            return report.issues
                .filter { it.severity == LoadIssue.Severity.ERROR }
                .joinToString("\n") { issue ->
                    val file =
                        issue.file?.let { f ->
                            runCatching {
                                val p = Path.of(f)
                                if (p.isAbsolute && p.startsWith(real)) real.relativize(p).toString() else f
                            }.getOrDefault(f)
                        } ?: "<loader>"
                    val at = if (issue.line > 0) ":${issue.line}" else ""
                    "$file$at: ${issue.code}: ${issue.message}"
                }
        }
    }
}
