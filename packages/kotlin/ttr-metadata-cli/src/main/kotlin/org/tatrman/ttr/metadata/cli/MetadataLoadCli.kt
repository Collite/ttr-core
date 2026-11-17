// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import kotlinx.serialization.json.*
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.LoadReport
import org.tatrman.ttr.metadata.LoadReportIssue
import org.tatrman.ttr.metadata.PackageLayout
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.PackagesManifest
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class CliOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

object MetadataVersion {
    val value: String by lazy {
        val properties = Properties()
        val resource =
            requireNotNull(javaClass.getResourceAsStream("version.properties")) {
                "missing build-stamped ttr-metadata version"
            }
        resource.use { properties.load(it) }
        properties.getProperty("version")
    }
}

/** The CLI's flag-independent entry point. No registry swaps, network calls or output-file writes. */
object MetadataLoadCli {
    fun run(
        root: Path,
        strict: Boolean = false,
        expectVersion: String? = null,
        json: Boolean = false,
    ): CliOutcome {
        val version = MetadataVersion.value
        if (expectVersion != null && expectVersion != version) {
            return CliOutcome(2, "", "ttr-metadata: expected $expectVersion, built with $version\n")
        }
        val project = root.toAbsolutePath().normalize()
        if (!Files.isRegularFile(project.resolve("modeler.toml"))) {
            return CliOutcome(3, "", "ttr-metadata: no modeler.toml under $project\n")
        }
        val (packageLayout, manifestIssues) = readManifest(project.resolve("modeler.toml"))
        val modelRoot = project.resolve("model")
        if (!Files.isDirectory(modelRoot)) {
            return render(project, version, noModelDir(modelRoot, manifestIssues), json, failed = true)
        }
        // Always collect a report, including in permissive mode; --strict controls the exit status.
        val (result, report) =
            load(
                FileBasedSource(CHECKOUT_SOURCE, 100, LocalFsStorage(CHECKOUT_SOURCE, modelRoot)),
                packageLayout,
                manifestIssues,
            )
        return render(project, version, report, json, result.model == null || (strict && !report.ok))
    }

    internal const val CHECKOUT_SOURCE = "checkout"

    /** A checkout without `model/`: `load`'s one ERROR, which `lint` reports the same way (review-269 F7). */
    internal fun noModelDir(
        modelRoot: Path,
        manifestIssues: List<LoadReportIssue>,
    ) = LoadReport(
        issues =
            manifestIssues +
                LoadReportIssue(
                    modelRoot.toString(),
                    -1,
                    "no-model-dir",
                    LoadIssue.Severity.ERROR,
                    "no model/ directory in the checkout",
                ),
    )

    /** The one strict load `load` and `lint` share: the stock vocabulary plus [source], a report always collected. */
    internal fun load(
        source: org.tatrman.ttr.metadata.source.ModelSource,
        packageLayout: PackageLayout,
        manifestIssues: List<LoadReportIssue> = emptyList(),
    ): Pair<org.tatrman.ttr.metadata.LoadResult, LoadReport> {
        val result =
            MetadataLoader(listOf(BuiltinStockSource(), source))
                .load(LoadOptions(strict = true, packageLayout = packageLayout))
        return result to requireNotNull(result.report).let { it.copy(issues = manifestIssues + it.issues) }
    }

    /**
     * `[packages].layout` of a `modeler.toml` and the manifest's own issues — [PackagesManifest.read], the library's one
     * reading of the rule (moved there in AU-export S1.5.9, D-235, so `ttrp-frontend` reads it without this CLI).
     */
    fun readManifest(manifest: Path): Pair<PackageLayout, List<LoadReportIssue>> = PackagesManifest.read(manifest)

    private fun render(
        project: Path,
        version: String,
        report: LoadReport,
        json: Boolean,
        failed: Boolean,
    ): CliOutcome {
        val rendered = Rendered(project, version, report)
        return CliOutcome(
            if (failed) 1 else 0,
            if (json) "${rendered.document()}\n" else "",
            rendered.text(),
        )
    }
}

/**
 * A load report as the CLI prints it: paths relative to the checkout, also inside messages. `load` and `lint` share it,
 * so `lint` on a `.ttrm` checkout prints what `load` prints.
 */
internal class Rendered(
    private val project: Path,
    private val version: String,
    val report: LoadReport,
) {
    fun relative(file: String?): String? = checkoutRelative(project, file)

    // Existing reconciler messages may also embed source paths (for example duplicate mappings).
    private val sourcePaths =
        (report.sources.map { it.path } + report.droppedFiles + report.issues.mapNotNull { it.file })
            .distinct()
            .sortedByDescending { it.length }

    fun message(text: String): String =
        sourcePaths.fold(text) { value, path ->
            value.replace(path, relative(path) ?: path)
        }

    private fun checkoutSources() = report.sources.filter { it.sourceId == MetadataLoadCli.CHECKOUT_SOURCE }

    /** `load --json`'s document; [columns] adds each issue's column (`lint --format json`). */
    fun document(columns: Boolean = false): JsonObject =
        buildJsonObject {
            put("version", version)
            putJsonArray("sources") {
                checkoutSources().forEach { source ->
                    addJsonObject {
                        put("path", relative(source.path))
                        put("objects", source.objects)
                    }
                }
            }
            putJsonArray("issues") {
                report.issues.forEach { issue ->
                    addJsonObject {
                        put("file", relative(issue.file))
                        put("line", issue.line)
                        if (columns) put("column", issue.column)
                        put("code", issue.code)
                        put("severity", issue.severity.name.lowercase())
                        put("message", message(issue.message))
                    }
                }
            }
        }

    /** `load`'s diagnostics: one line per issue and a summary line. */
    fun text(): String =
        buildString {
            report.issues.forEach { issue ->
                val location = relative(issue.file) ?: "<loader>"
                val detail = message(issue.message)
                appendLine("$location:${issue.line}: ${issue.severity.name.lowercase()} ${issue.code}: $detail")
            }
            appendLine(
                "ttr-metadata $version: ${checkoutSources().size} sources, ${report.issues.size} issues",
            )
        }
}

/** Normalize report paths against their checkout; relative inputs must never depend on the process CWD. */
internal fun checkoutRelative(
    project: Path,
    file: String?,
): String? =
    file?.let {
        runCatching {
            val path = project.resolve(it).normalize()
            if (path.startsWith(project)) project.relativize(path).joinToString("/") else it
        }.getOrDefault(it)
    }
