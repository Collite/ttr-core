// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import kotlinx.serialization.json.*
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
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
        val modelRoot = project.resolve("model")
        // Always collect a report, including in permissive mode; --strict controls the exit status.
        val result =
            MetadataLoader(
                listOf(
                    BuiltinStockSource(),
                    FileBasedSource("checkout", 100, LocalFsStorage("checkout", modelRoot)),
                ),
            ).load(LoadOptions(strict = true))
        val report = result.report

        fun relative(file: String?): String? =
            file?.let {
                runCatching {
                    val path = Path.of(it).toAbsolutePath().normalize()
                    if (path.startsWith(project)) project.relativize(path).joinToString("/") else it
                }.getOrDefault(it)
            }
        val document =
            buildJsonObject {
                put("version", version)
                putJsonArray("sources") {
                    report.sources.filter { Files.isRegularFile(Path.of(it.path)) }.forEach { source ->
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
                            put("message", issue.message)
                        }
                    }
                }
            }
        val diagnostics =
            buildString {
                report.issues.forEach { issue ->
                    val location = relative(issue.file) ?: "<loader>"
                    appendLine(
                        "$location:${issue.line}: ${issue.severity.name.lowercase()} ${issue.code}: ${issue.message}",
                    )
                }
                appendLine(
                    "ttr-metadata $version: ${document["sources"]!!.jsonArray.size} sources, ${report.issues.size} issues",
                )
            }
        return CliOutcome(
            if (result.model == null || (strict && !report.ok)) 1 else 0,
            if (json) "$document\n" else "",
            diagnostics,
        )
    }
}
