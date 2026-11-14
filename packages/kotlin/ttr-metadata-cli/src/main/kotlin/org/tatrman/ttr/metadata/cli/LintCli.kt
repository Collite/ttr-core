// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadReport
import org.tatrman.ttr.metadata.LoadReportIssue
import org.tatrman.ttr.metadata.LoadResult
import org.tatrman.ttr.metadata.PackageLayout
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ProfileApply
import org.tatrman.ttr.metadata.source.SourceKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/** `lint`'s output formats (contracts §5). */
enum class LintFormat { TEXT, JSON, GITHUB }

/**
 * What `--root` names. A checkout (`modeler.toml` beside `model/`) is read exactly as `load` reads it, its layout from
 * the manifest; any other directory is the model root itself, a dialect tree served with layout `off` (§4 (f)). The
 * kind is `yaml` when a profile is asked for, the tree is declared (`ttr.yaml`), or it holds YAML and no `.ttr`/`.ttrm`.
 * [catalog] names the profiles under `<model root>/profiles/` by their identity, so `--profile demo-cs/1` resolves.
 */
internal class Target(
    val project: Path,
    val modelRoot: Path,
    val kind: SourceKind,
    val layout: PackageLayout,
    val manifestIssues: List<LoadReportIssue>,
    val profile: String?,
    val catalog: Map<String, Path>,
) {
    fun source() =
        kind.source(
            MetadataLoadCli.CHECKOUT_SOURCE,
            100,
            LocalFsStorage(MetadataLoadCli.CHECKOUT_SOURCE, modelRoot),
            profile,
            catalog,
        )

    companion object {
        /** Null with a usage message when [root] is not a directory. */
        fun of(
            root: Path,
            profile: String?,
        ): Pair<Target?, String?> {
            val project = root.toAbsolutePath().normalize()
            if (!Files.isDirectory(project)) return null to "no such directory: $root"
            val manifest = project.resolve("modeler.toml")
            val (layout, issues, modelRoot) =
                if (manifest.isRegularFile()) {
                    val (layout, issues) = MetadataLoadCli.readManifest(manifest)
                    Triple(layout, issues, project.resolve("model"))
                } else {
                    Triple(PackageLayout.Off, emptyList(), project)
                }
            if (!Files.isDirectory(modelRoot)) return null to "no model/ directory under $project"
            val extensions = files(modelRoot).map { it.extension }.toSet()
            val yaml =
                profile != null ||
                    modelRoot.resolve("ttr.yaml").isRegularFile() ||
                    (extensions.any { it == "yaml" || it == "yml" } && extensions.none { it == "ttr" || it == "ttrm" })
            val kind = if (yaml) SourceKind.YAML else SourceKind.TTR
            if (!yaml && !manifest.isRegularFile()) {
                // A bare .ttrm root keeps `load`'s default layout.
                return Target(project, modelRoot, kind, PackageLayout.Flexible, issues, null, emptyMap()) to null
            }
            return Target(
                project,
                modelRoot,
                kind,
                layout,
                issues,
                profile,
                if (yaml) catalog(modelRoot) else emptyMap(),
            ) to
                null
        }

        private fun files(root: Path): List<Path> =
            Files.walk(root).use { paths ->
                paths
                    .filter {
                        it.isRegularFile() &&
                            root.relativize(it).none { segment ->
                                segment.name.startsWith(".")
                            }
                    }.toList()
            }

        /** `<root>/profiles/<file>.yaml` by the identity it declares (`profile: <name>/<major>`); unreadable files skip. */
        private fun catalog(root: Path): Map<String, Path> {
            val directory = root.resolve("profiles")
            if (!Files.isDirectory(directory)) return emptyMap()
            val identity = Regex("""^profile:\s*['"]?([^'"\s#]+)""", RegexOption.MULTILINE)
            return files(directory)
                .filter { it.extension == "yaml" || it.extension == "yml" }
                .sorted()
                .mapNotNull { file ->
                    runCatching { identity.find(file.readText())?.groupValues?.get(1) }.getOrNull()?.let {
                        it to root.relativize(file)
                    }
                }.toMap()
        }
    }
}

/**
 * MW S3.5 — `lint`, `profile check` and `profile apply` (contracts §5): one strict load through the `yaml` or `ttr`
 * source kind, its report rendered. Exit codes are `load`'s: 1 when the load fails or, with [strict], when the report
 * holds an error (warnings never fail); 2 for usage. So `lint --strict` on a `.ttrm` checkout is `load --strict`.
 */
object LintCli {
    fun lint(
        root: Path,
        profile: String?,
        strict: Boolean,
        format: LintFormat,
    ): CliOutcome {
        val (target, usage) = Target.of(root, profile)
        target ?: return usage(usage)
        val (result, report) = MetadataLoadCli.load(target.source(), target.layout, target.manifestIssues)
        return outcome(target, result, report, strict, format)
    }

    fun check(
        root: Path,
        profile: String?,
        strict: Boolean,
        format: LintFormat,
    ): CliOutcome = profile(root, profile, strict, format, out = null)

    fun apply(
        root: Path,
        profile: String?,
        out: Path,
        strict: Boolean,
        format: LintFormat,
    ): CliOutcome = profile(root, profile, strict, format, out)

    /** `profile check` and `profile apply`: the loader's diagnostics, then the documents; [out] null writes nothing. */
    private fun profile(
        root: Path,
        profile: String?,
        strict: Boolean,
        format: LintFormat,
        out: Path?,
    ): CliOutcome {
        val (target, usage) = Target.of(root, profile)
        target ?: return usage(usage)
        if (target.kind !=
            SourceKind.YAML
        ) {
            return usage("profile commands need a yaml tree; ${target.modelRoot} holds .ttrm")
        }
        val (result, loaded) = MetadataLoadCli.load(target.source(), target.layout, target.manifestIssues)
        val applied =
            ProfileApply.run(
                LocalFsStorage(MetadataLoadCli.CHECKOUT_SOURCE, target.modelRoot),
                target.profile,
                target.catalog,
                MetadataLoadCli.CHECKOUT_SOURCE,
            )
        val report = loaded.copy(issues = loaded.issues + applied.conflicts.map(::conflict))
        val outcome = outcome(target, result, report, strict, format)
        if (out == null || outcome.exitCode != 0) return outcome
        val directory = out.toAbsolutePath().normalize()
        if (directory.startsWith(
                target.modelRoot,
            )
        ) {
            return usage("--out must be outside the model root ${target.modelRoot}")
        }
        applied.files.forEach { (path, text) ->
            val file = directory.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        return outcome.copy(stderr = outcome.stderr + "ttr-metadata: wrote ${applied.files.size} files to $out\n")
    }

    /** The diagnostic-code catalogue (`lint --codes`). */
    fun codes(): CliOutcome = CliOutcome(0, DiagnosticCodes.resource(), "")

    private fun conflict(warning: LoadWarning) =
        LoadReportIssue(warning.file, warning.line, "other", LoadIssue.Severity.ERROR, warning.message, warning.column)

    private fun usage(message: String?) = CliOutcome(2, "", "ttr-metadata: $message\n")

    private fun outcome(
        target: Target,
        result: LoadResult,
        report: LoadReport,
        strict: Boolean,
        format: LintFormat,
    ): CliOutcome {
        val rendered = Rendered(target.project, MetadataVersion.value, report)
        val failed = result.model == null || (strict && !report.ok)
        val stdout =
            when (format) {
                LintFormat.TEXT -> rendered.text()
                LintFormat.JSON -> "${rendered.document(columns = true)}\n"
                LintFormat.GITHUB -> github(target, rendered)
            }
        return CliOutcome(if (failed) 1 else 0, stdout, "")
    }

    /**
     * GitHub workflow commands: `::error file=<file>,line=<l>,col=<c>::<code> <message>` per error, `::warning` per
     * warning. Files are relative to the working directory (the CI checkout) where they lie under it; a fan-out
     * document (`<file>#<model>`) is annotated on its file. The code is the message's own `ys/…`, `xm/…` or `ttr/…` code,
     * else the report's.
     */
    private fun github(
        target: Target,
        rendered: Rendered,
    ): String {
        val cwd = Path.of("").toAbsolutePath().normalize()
        val lines = linkedSetOf<String>()
        rendered.report.issues.forEach { issue ->
            if (issue.severity == LoadIssue.Severity.INFO) return@forEach
            val command = if (issue.severity == LoadIssue.Severity.ERROR) "error" else "warning"
            val properties =
                buildList {
                    issue.file?.let { file ->
                        val absolute = target.project.resolve(file.substringBefore('#')).normalize()
                        val shown =
                            if (absolute.startsWith(
                                    cwd,
                                )
                            ) {
                                cwd.relativize(absolute).joinToString("/")
                            } else {
                                "$absolute"
                            }
                        add("file=${property(shown)}")
                    }
                    if (issue.line > 0) add("line=${issue.line}")
                    if (issue.column > 0) add("col=${issue.column}")
                }
            val message = rendered.message(issue.message)
            val code = codePattern.find(message)
            val text =
                if (code != null) {
                    val rest = message.removeRange(code.range).removePrefix(":").trim()
                    val dropped = if (message.startsWith("dropped: ")) "dropped: " else ""
                    "${code.groupValues[1]} $dropped$rest".trim()
                } else {
                    "${issue.code} $message"
                }
            lines += "::$command ${properties.joinToString(",")}::${data(text)}"
        }
        // A fan-out file's documents raise the same issue once each; the file is annotated once.
        return lines.joinToString("") { "$it\n" }
    }

    /** A code at the start of a message, after an optional `dropped: `. */
    private val codePattern = Regex("""^(?:dropped: )?((?:ys|xm|ttr)/[a-z0-9-]+)""")

    private fun data(text: String) = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

    private fun property(text: String) = data(text).replace(":", "%3A").replace(",", "%2C")
}
