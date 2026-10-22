// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import kotlinx.serialization.json.*
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.LoadReport
import org.tatrman.ttr.metadata.LoadReportIssue
import org.tatrman.ttr.metadata.PackageLayout
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tomlj.Toml
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
            return render(
                project,
                version,
                LoadReport(
                    issues =
                        manifestIssues +
                            LoadReportIssue(
                                "model",
                                -1,
                                "no-model-dir",
                                LoadIssue.Severity.ERROR,
                                "no model/ directory under $project",
                            ),
                ),
                json,
                failed = true,
            )
        }
        // Always collect a report, including in permissive mode; --strict controls the exit status.
        val result =
            MetadataLoader(
                listOf(
                    BuiltinStockSource(),
                    FileBasedSource(CHECKOUT_SOURCE, 100, LocalFsStorage(CHECKOUT_SOURCE, modelRoot)),
                ),
            ).load(LoadOptions(strict = true, packageLayout = packageLayout))
        val report = requireNotNull(result.report).let { it.copy(issues = manifestIssues + it.issues) }
        return render(project, version, report, json, result.model == null || (strict && !report.ok))
    }

    private const val CHECKOUT_SOURCE = "checkout"

    private fun readManifest(manifest: Path): Pair<PackageLayout, List<LoadReportIssue>> {
        val parsed =
            runCatching { Toml.parse(manifest) }.getOrElse { error ->
                return PackageLayout.Flexible to
                    listOf(
                        LoadReportIssue(
                            "modeler.toml",
                            -1,
                            "manifest-invalid",
                            LoadIssue.Severity.ERROR,
                            "cannot read modeler.toml: ${error.message}",
                        ),
                    )
            }
        if (parsed.hasErrors()) {
            val error = parsed.errors().first()
            return PackageLayout.Flexible to
                listOf(
                    LoadReportIssue(
                        "modeler.toml",
                        error.position().line(),
                        "manifest-invalid",
                        LoadIssue.Severity.ERROR,
                        error.toString(),
                    ),
                )
        }
        val layout = parsed.get("packages.layout") ?: return PackageLayout.Flexible to emptyList()
        val policy =
            when (layout) {
                "off" -> PackageLayout.Off
                "strict" -> PackageLayout.Strict
                "flexible" -> PackageLayout.Flexible
                else -> null
            }
        return if (policy != null) {
            policy to emptyList()
        } else {
            PackageLayout.Flexible to
                listOf(
                    LoadReportIssue(
                        "modeler.toml",
                        parsed.inputPositionOf("packages.layout")?.line() ?: -1,
                        "manifest-package-layout",
                        LoadIssue.Severity.WARNING,
                        "Unknown [packages].layout value '$layout'; expected flexible, strict, or off. Falling back to flexible.",
                    ),
                )
        }
    }

    private fun render(
        project: Path,
        version: String,
        report: LoadReport,
        json: Boolean,
        failed: Boolean,
    ): CliOutcome {
        fun relative(file: String?): String? =
            file?.let {
                runCatching {
                    val path = Path.of(it).toAbsolutePath().normalize()
                    if (path.startsWith(project)) project.relativize(path).joinToString("/") else it
                }.getOrDefault(it)
            }
        // Existing reconciler messages may also embed source paths (for example duplicate mappings).
        val sourcePaths =
            (report.sources.map { it.path } + report.droppedFiles + report.issues.mapNotNull { it.file })
                .distinct()
                .sortedByDescending { it.length }

        fun message(text: String): String =
            sourcePaths.fold(text) { value, path ->
                value.replace(path, relative(path) ?: path)
            }
        val document =
            buildJsonObject {
                put("version", version)
                putJsonArray("sources") {
                    report.sources.filter { it.sourceId == CHECKOUT_SOURCE }.forEach { source ->
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
                            put("code", issue.code)
                            put("severity", issue.severity.name.lowercase())
                            put("message", message(issue.message))
                        }
                    }
                }
            }
        val diagnostics =
            buildString {
                report.issues.forEach { issue ->
                    val location = relative(issue.file) ?: "<loader>"
                    val detail = message(issue.message)
                    appendLine("$location:${issue.line}: ${issue.severity.name.lowercase()} ${issue.code}: $detail")
                }
                appendLine(
                    "ttr-metadata $version: ${document["sources"]!!.jsonArray.size} sources, ${report.issues.size} issues",
                )
            }
        return CliOutcome(
            if (failed) 1 else 0,
            if (json) "$document\n" else "",
            diagnostics,
        )
    }
}
