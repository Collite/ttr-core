// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadReport
import org.tatrman.ttr.metadata.LoadReportIssue
import org.tatrman.ttr.metadata.PackageLayout
import org.tatrman.ttr.metadata.source.GeneratedGuard
import org.tatrman.ttr.metadata.source.HeaderOptions
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ProfileApplyResult
import org.tatrman.ttr.metadata.source.ProfileCatalog
import org.tatrman.ttr.metadata.source.ProfileSession
import org.tatrman.ttr.metadata.source.SourceKind
import org.tatrman.ttr.metadata.source.YamlFileSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

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
            val read = ProfileCatalog.read(modelRoot)
            // review-269 N2: a duplicated identity is a usage error only for the run that names it; otherwise it is
            // left out of the catalog with one warning, and a lookup of it fails where it is made.
            profile?.let { name -> read.duplicates[name]?.let { return Resolved.Usage(duplicate(name, it)) } }
            val catalog = read.entries
            val warnings =
                read.duplicates.map { (name, paths) ->
                    LoadReportIssue(
                        "${modelRoot.resolve(paths.first())}",
                        1,
                        "profile-duplicate",
                        LoadIssue.Severity.WARNING,
                        "${duplicate(name, paths)}; left out of the catalog",
                        1,
                    )
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
                Target(
                    project,
                    modelRoot,
                    SourceKind.YAML,
                    PackageLayout.Off,
                    yamlLayout(issues) + warnings,
                    reference,
                    catalog,
                ),
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

        /** A yaml tree is served with layout `off` (review-269 F6), so the manifest's fallback does not apply (N3). */
        private fun yamlLayout(issues: List<LoadReportIssue>) =
            issues.map {
                if (it.code != "manifest-package-layout") {
                    it
                } else {
                    it.copy(
                        message =
                            it.message.replace(
                                "Falling back to flexible.",
                                "A yaml tree is served with layout off.",
                            ),
                    )
                }
            }

        private fun duplicate(
            name: String,
            paths: List<Path>,
        ) = "profiles ${paths.joinToString(" and ") { it.joinToString("/") }} both declare $name"
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
                val (result, loaded) = MetadataLoadCli.load(target.source(), target.layout, target.manifestIssues)
                val report = guarded(target, loaded)
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
        // One pass over the tree for both (S3.6): the load and the apply run share the session's plan.
        val session =
            ProfileSession(
                LocalFsStorage(MetadataLoadCli.CHECKOUT_SOURCE, target.modelRoot),
                target.profile,
                target.catalog,
                MetadataLoadCli.CHECKOUT_SOURCE,
            )
        val (result, loaded) = MetadataLoadCli.load(session.source(), target.layout, target.manifestIssues)
        val applied = session.apply(out?.let { HeaderOptions(it.toAbsolutePath().normalize(), MetadataVersion.value) })
        val directory = out?.toAbsolutePath()?.normalize()
        val output =
            directory?.let {
                try {
                    Output.of(it)
                } catch (e: IOException) {
                    return usage("--out $it: cannot create the directory (${e.javaClass.simpleName} ${e.message})")
                }
            }
        if (output != null) {
            refusal(target.modelRoot, output, applied.files.keys)?.let {
                output.discard()
                return usage(it)
            }
        }
        val report = merge(loaded, applied)
        val failed = result.model == null || report.issues.any { it.severity == LoadIssue.Severity.ERROR }
        val outcome = outcome(target.project, report, failed, format)
        if (output == null || failed) return outcome.also { output?.discard() }
        applied.files.forEach { (path, text) ->
            val file = output.real.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        return outcome.copy(stderr = outcome.stderr + "ttr-metadata: wrote ${applied.files.size} files to $out\n")
    }

    /**
     * `--out` made real once (✅R277-D): [given] may itself be a symbolic link, or lie under one, and is followed here
     * and only here. A missing directory is created first; [created] is the topmost directory this run made, removed
     * again by [discard] when nothing is written.
     */
    internal class Output private constructor(
        val given: Path,
        val real: Path,
        private val created: Path?,
    ) {
        fun discard() {
            created ?: return
            Files.walk(created).use { paths -> paths.sorted(Comparator.reverseOrder()).toList() }.forEach(Files::delete)
        }

        companion object {
            fun of(given: Path): Output {
                var missing: Path? = null
                var existing: Path? = given
                while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                    missing = existing
                    existing = existing.parent
                }
                Files.createDirectories(given)
                return Output(given, given.toRealPath(), missing)
            }
        }
    }

    /**
     * Why apply may not write [paths] under [output] (✅R277-D, review-269 F2): the real `--out` lies in the model root;
     * a path apply would write goes through a symbolic link at or below `--out` — dangling or not, pointing anywhere,
     * nothing is written through a link; or an output lands in the model root (an `--out` that contains the root).
     * Null when every output is a plain path outside the root.
     */
    private fun refusal(
        modelRoot: Path,
        output: Output,
        paths: Collection<String>,
    ): String? {
        val root = modelRoot.toRealPath()
        if (output.real.startsWith(root)) return "--out must be outside the model root $modelRoot"
        paths.sorted().forEach { path ->
            link(output.real, path)?.let { at ->
                return "--out ${output.given} contains a symbolic link at $at; apply does not write through links"
            }
        }
        val inside =
            paths.filter {
                output.real
                    .resolve(it)
                    .normalize()
                    .startsWith(root)
            }
        if (inside.isEmpty()) return null
        return "--out ${output.given} would write ${inside.joinToString(", ")} into the model root $modelRoot"
    }

    /**
     * The first component of [path] under [real] that is a symbolic link (`lstat`, no link followed), relative to
     * [real]; null when there is none. A path that leaves [real] lexically is refused as if it were a link.
     */
    internal fun link(
        real: Path,
        path: String,
    ): String? {
        val target = real.resolve(path).normalize()
        if (!target.startsWith(real)) return path
        var at = real
        for (segment in real.relativize(target)) {
            at = at.resolve(segment)
            if (Files.isSymbolicLink(at)) return real.relativize(at).joinToString("/")
            if (!Files.exists(at, LinkOption.NOFOLLOW_LINKS)) return null
        }
        return null
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

    /**
     * [loaded] plus the generated-file guard over the model root (S3.6 T3): `xm/generated-stale` and
     * `xm/generated-edited` on a file whose header no longer matches its inputs, as errors. A tree without generated
     * files is reported exactly as `load` reports it.
     */
    private fun guarded(
        target: Target,
        loaded: LoadReport,
    ): LoadReport {
        val guard = GeneratedGuard.check(target.modelRoot, MetadataLoadCli.CHECKOUT_SOURCE)
        if (guard.isEmpty()) return loaded
        return loaded.copy(
            issues =
                loaded.issues +
                    guard.map {
                        LoadReportIssue(
                            it.file,
                            it.line,
                            "generated-file",
                            LoadIssue.Severity.ERROR,
                            it.message,
                            it.column,
                        )
                    },
        )
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
