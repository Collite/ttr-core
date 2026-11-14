// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadReport
import org.tatrman.ttr.metadata.LoadReportIssue
import org.tatrman.ttr.metadata.PackageLayout
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ProfileApply
import org.tatrman.ttr.metadata.source.ProfileApplyResult
import org.tatrman.ttr.metadata.source.SourceKind
import org.tatrman.ttr.metadata.source.YamlFileSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/** `lint`'s output formats (contracts §5). */
enum class LintFormat { TEXT, JSON, GITHUB }

/** What `--root` and `--profile` resolve to: a target, a usage error, or a checkout without `model/`. */
internal sealed interface Resolved {
    class Ok(
        val target: Target,
    ) : Resolved

    class Usage(
        val message: String,
    ) : Resolved

    /** `load`'s `no-model-dir` report, so `lint` reports it the way `load --strict` does (review-269 F7). */
    class NoModel(
        val project: Path,
        val report: LoadReport,
    ) : Resolved
}

/**
 * What `--root` names. A checkout (`modeler.toml` beside `model/`) is read as `load` reads it; any other directory is
 * the model root itself. The kind is `yaml` when a profile is asked for, the tree is declared (`ttr.yaml`), or it holds
 * YAML and no `.ttr`/`.ttrm`. A `yaml` tree is served with layout `off` (§4 (f)), a checkout's included (review-269
 * F6); a `ttr` checkout keeps its manifest's layout, so `lint` on a `.ttrm` checkout is `load`.
 *
 * `--profile` (✅R269-B): a bundled identity (`canonical/1`) or one the tree's catalog names (`<model root>/profiles/
 * *.yaml` by the `profile:` identity each declares) passes as it is; anything else is a path, relative to the working
 * directory like `--root` and `--out`, and must be a file.
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
        fun of(
            root: Path,
            profile: String?,
        ): Resolved {
            val project = root.toAbsolutePath().normalize()
            if (!Files.isDirectory(project)) return Resolved.Usage("no such directory: $root")
            val manifest = project.resolve("modeler.toml")
            val checkout = manifest.isRegularFile()
            val (manifestLayout, issues) =
                if (checkout) MetadataLoadCli.readManifest(manifest) else PackageLayout.Off to emptyList()
            val modelRoot = if (checkout) project.resolve("model") else project
            if (!Files.isDirectory(modelRoot)) {
                return Resolved.NoModel(project, MetadataLoadCli.noModelDir(modelRoot, issues))
            }
            val extensions = files(modelRoot).map { it.extension }.toSet()
            val yaml =
                profile != null ||
                    modelRoot.resolve("ttr.yaml").isRegularFile() ||
                    (extensions.any { it == "yaml" || it == "yml" } && extensions.none { it == "ttr" || it == "ttrm" })
            if (!yaml) {
                // A bare .ttrm root keeps `load`'s default layout.
                val layout = if (checkout) manifestLayout else PackageLayout.Flexible
                return Resolved.Ok(Target(project, modelRoot, SourceKind.TTR, layout, issues, null, emptyMap()))
            }
            val catalog =
                when (val read = catalog(modelRoot)) {
                    is Catalog.Duplicate -> return Resolved.Usage(read.message)
                    is Catalog.Read -> read.entries
                }
            val reference =
                when {
                    profile == null -> null
                    YamlFileSource.isBundledProfile(profile) || profile in catalog -> profile
                    else -> {
                        val path = Path.of(profile).toAbsolutePath().normalize()
                        if (!path.isRegularFile()) return Resolved.Usage("--profile: no profile file $profile")
                        path.toString()
                    }
                }
            return Resolved.Ok(
                Target(project, modelRoot, SourceKind.YAML, PackageLayout.Off, issues, reference, catalog),
            )
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

        private sealed interface Catalog {
            class Read(
                val entries: Map<String, Path>,
            ) : Catalog

            class Duplicate(
                val message: String,
            ) : Catalog
        }

        /**
         * `<root>/profiles/<file>.yaml` by the identity it declares (`profile: <name>/<major>`); unreadable files skip.
         * Two files declaring one identity are a usage error naming both (review-269 F8).
         */
        private fun catalog(root: Path): Catalog {
            val directory = root.resolve("profiles")
            if (!Files.isDirectory(directory)) return Catalog.Read(emptyMap())
            val identity = Regex("""^profile:\s*['"]?([^'"\s#]+)""", RegexOption.MULTILINE)
            val declared =
                files(directory)
                    .filter { it.extension == "yaml" || it.extension == "yml" }
                    .sorted()
                    .mapNotNull { file ->
                        runCatching { identity.find(file.readText())?.groupValues?.get(1) }.getOrNull()?.let {
                            it to root.relativize(file)
                        }
                    }.groupBy({ it.first }, { it.second })
            declared.entries.firstOrNull { it.value.size > 1 }?.let { (name, paths) ->
                return Catalog.Duplicate(
                    "profiles ${paths.joinToString(" and ") { it.joinToString("/") }} both declare $name",
                )
            }
            return Catalog.Read(declared.mapValues { it.value.single() })
        }
    }
}

/**
 * MW S3.5 — `lint`, `profile check` and `profile apply` (contracts §5): one strict load through the `yaml` or `ttr`
 * source kind, its report rendered; 2 for usage.
 *
 * `lint`'s exit codes are `load`'s: 1 when the load fails or, with `--strict`, when the report holds an error, so
 * `lint --strict` on a `.ttrm` checkout is `load --strict`. The profile commands exit 1 on **any** error, with or
 * without `--strict`, which they accept and ignore (✅R269-A): a load issue, an apply conflict or a file the grouped run
 * dropped. `apply` then writes nothing. Warnings never fail.
 */
object LintCli {
    fun lint(
        root: Path,
        profile: String?,
        strict: Boolean,
        format: LintFormat,
    ): CliOutcome =
        when (val resolved = Target.of(root, profile)) {
            is Resolved.Usage -> usage(resolved.message)
            is Resolved.NoModel -> outcome(resolved.project, resolved.report, failed = true, format)
            is Resolved.Ok -> {
                val target = resolved.target
                val (result, report) = MetadataLoadCli.load(target.source(), target.layout, target.manifestIssues)
                outcome(target.project, report, result.model == null || (strict && !report.ok), format)
            }
        }

    fun check(
        root: Path,
        profile: String?,
        format: LintFormat,
    ): CliOutcome = profile(root, profile, format, out = null)

    fun apply(
        root: Path,
        profile: String?,
        out: Path,
        format: LintFormat,
    ): CliOutcome = profile(root, profile, format, out)

    /** `profile check` and `profile apply`: the loader's diagnostics, then the documents; [out] null writes nothing. */
    private fun profile(
        root: Path,
        profile: String?,
        format: LintFormat,
        out: Path?,
    ): CliOutcome {
        val target =
            when (val resolved = Target.of(root, profile)) {
                is Resolved.Usage -> return usage(resolved.message)
                is Resolved.NoModel -> return outcome(resolved.project, resolved.report, failed = true, format)
                is Resolved.Ok -> resolved.target
            }
        if (target.kind != SourceKind.YAML) {
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
        val directory = out?.toAbsolutePath()?.normalize()
        if (directory != null) refusal(target.modelRoot, directory, applied.files.keys)?.let { return usage(it) }
        val report = merge(loaded, applied)
        val failed = result.model == null || report.issues.any { it.severity == LoadIssue.Severity.ERROR }
        val outcome = outcome(target.project, report, failed, format)
        if (directory == null || failed) return outcome
        applied.files.forEach { (path, text) ->
            val file = directory.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        return outcome.copy(stderr = outcome.stderr + "ttr-metadata: wrote ${applied.files.size} files to $out\n")
    }

    /**
     * Why apply may not write [paths] under [directory]: the directory or any output lies in the model root, compared
     * on real paths so a symlink cannot hide it (review-269 F2). Null when every output lands outside.
     */
    private fun refusal(
        modelRoot: Path,
        directory: Path,
        paths: Collection<String>,
    ): String? {
        val root = modelRoot.toRealPath()
        if (real(directory).startsWith(root)) return "--out must be outside the model root $modelRoot"
        val inside = paths.filter { real(directory.resolve(it).normalize()).startsWith(root) }
        if (inside.isEmpty()) return null
        return "--out $directory would write ${inside.joinToString(", ")} into the model root $modelRoot"
    }

    /** [path] with its nearest existing ancestor made real; the part that does not exist yet appended as it is. */
    private fun real(path: Path): Path {
        var existing: Path? = path
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        existing ?: return path
        return existing.toRealPath().resolve(existing.relativize(path))
    }

    /**
     * The profile commands' report: the load's, plus apply's conflicts and the files only the grouped run dropped. A
     * grouped-run error the per-file load already reports (same file, same message) is not repeated (review-269 F1).
     */
    internal fun merge(
        loaded: LoadReport,
        applied: ProfileApplyResult,
    ): LoadReport {
        val known = loaded.issues.map { it.file to it.message }.toSet()
        val extra = applied.problems.filter { (it.file to it.message) !in known } + applied.conflicts
        return loaded.copy(issues = loaded.issues + extra.map(::error))
    }

    /** The diagnostic-code catalogue (`lint --codes`). */
    fun codes(): CliOutcome = CliOutcome(0, DiagnosticCodes.resource(), "")

    private fun error(warning: LoadWarning) =
        LoadReportIssue(warning.file, warning.line, "other", LoadIssue.Severity.ERROR, warning.message, warning.column)

    private fun usage(message: String?) = CliOutcome(2, "", "ttr-metadata: $message\n")

    private fun outcome(
        project: Path,
        report: LoadReport,
        failed: Boolean,
        format: LintFormat,
    ): CliOutcome {
        val rendered = Rendered(project, MetadataVersion.value, report)
        val stdout =
            when (format) {
                LintFormat.TEXT -> rendered.text()
                LintFormat.JSON -> "${rendered.document(columns = true)}\n"
                LintFormat.GITHUB -> github(project, rendered)
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
        project: Path,
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
                        val absolute = project.resolve(annotated(project, file)).normalize()
                        val shown =
                            if (absolute.startsWith(cwd)) cwd.relativize(absolute).joinToString("/") else "$absolute"
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

    /**
     * The file a report path annotates: a fan-out document `<file>#<model>` is its file. Only that form is cut — the
     * suffix a model code and the part before it a file that exists — since `#` is legal in a file name (review-269 F8).
     */
    private fun annotated(
        project: Path,
        file: String,
    ): String {
        val cut = file.substringBeforeLast('#', "")
        if (cut.isEmpty() || !modelCode.matches(file.substringAfterLast('#'))) return file
        return if (!Files.exists(project.resolve(file)) && Files.isRegularFile(project.resolve(cut))) cut else file
    }

    private val modelCode = Regex("[A-Za-z0-9_]+")

    /** A code at the start of a message, after an optional `dropped: `. */
    private val codePattern = Regex("""^(?:dropped: )?((?:ys|xm|ttr)/[a-z0-9-]+)""")

    private fun data(text: String) = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

    private fun property(text: String) = data(text).replace(":", "%3A").replace(",", "%2C")
}
