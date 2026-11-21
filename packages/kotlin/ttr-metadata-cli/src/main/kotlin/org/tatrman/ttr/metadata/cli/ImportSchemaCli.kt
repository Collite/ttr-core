// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import org.tatrman.ttr.imports.FrontEnd
import org.tatrman.ttr.imports.ImportProfile
import org.tatrman.ttr.imports.JsonFrontEnd
import org.tatrman.ttr.imports.Reader
import org.tatrman.ttr.imports.ReaderInputException
import org.tatrman.ttr.imports.ReaderRequest
import org.tatrman.ttr.metadata.export.Hashes
import org.tatrman.ttr.metadata.source.Captured
import org.tatrman.ttr.profile.Bytewise
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileException
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * MW-P5 S5.1 T5 — `import-schema --from file:<export.json> --profile <p> --out <dir>` (contracts §2): the snapshot
 * front-end of `import-schema`, run through the reader harness (`ttr-import`). The JDBC reader stays in
 * `ttr-import-schema`, untouched; `--from openmetadata` is S5.5's adapter.
 *
 * Writes `<out>/<package>/<model>.ttrm` (each with the generated header), `<out>/<package>.sources.json` and
 * `<out>/report.json`; prints the report as a Markdown PR-body section (`--format json`: the report JSON). `--dry-run`
 * writes nothing and prints, per file, its unified diff against what is under `--out` (with `--format json` on stderr,
 * so stdout stays one JSON value). `--out` follows `profile apply`'s
 * rules (✅R277-D): made real once, never written through a link below it, removed again when nothing is written.
 * Exit 0 generated, 1 a profile or engine error (located, on stderr), 2 usage (an unwritable `--out` included; an
 * `--out` this run created is removed again).
 */
object ImportSchemaCli {
    fun run(
        from: String,
        profile: String,
        out: Path,
        root: Path?,
        overlay: Path?,
        packageName: String?,
        dryRun: Boolean,
        json: Boolean,
        url: String? = null,
        tokenEnv: String? = null,
    ): CliOutcome {
        if (from == "openmetadata") {
            val captured = SnapshotCli.captureOpenMetadata(url, tokenEnv, profile, large = false, tablesOnly = true)
            val batch =
                when (captured) {
                    is Captured.Batch -> captured
                    is Captured.Usage -> return usage(captured.message)
                    is Captured.Refused ->
                        return CliOutcome(
                            1,
                            "",
                            "${captured.file}:${captured.line}:${captured.column}: error ${captured.message}\n",
                        )
                    is Captured.Written -> error("OpenMetadata capture produces a batch")
                }
            val staged =
                try {
                    Files.createTempDirectory("ttr-openmetadata-")
                } catch (_: IOException) {
                    return usage("cannot create a temporary OpenMetadata snapshot")
                }
            try {
                OpenMetadataSnapshots.write(staged, batch.files)
                return generate(
                    "import-schema",
                    SchemaSnapshots,
                    staged,
                    profile,
                    out,
                    root,
                    overlay,
                    packageName,
                    dryRun,
                    json,
                    snapshotHash = Hashes.tree(staged),
                )
            } catch (_: IOException) {
                return usage("cannot stage the OpenMetadata snapshot")
            } finally {
                Files.newDirectoryStream(staged).use { paths -> paths.forEach(Files::delete) }
                Files.delete(staged)
            }
        }
        if (url != null || tokenEnv != null) return usage("--url and --token-env require --from openmetadata")
        if (!from.startsWith("file:") || from == "file:") {
            return usage("--from must be openmetadata or file:<path> (an existing export)")
        }
        val input = Path.of(from.removePrefix("file:"))
        if (!Files.isDirectory(input)) {
            return generate(
                "import-schema",
                JsonFrontEnd,
                input,
                profile,
                out,
                root,
                overlay,
                packageName,
                dryRun,
                json,
            )
        }
        return try {
            val snapshot = input.toRealPath()
            OpenMetadataSnapshots.read(snapshot) {
                generate(
                    "import-schema",
                    SchemaSnapshots,
                    snapshot,
                    profile,
                    out,
                    root,
                    overlay,
                    packageName,
                    dryRun,
                    json,
                )
            }
        } catch (_: IOException) {
            usage("cannot read snapshot directory $input")
        }
    }

    /** Use the capture manifest when present. Older directories supply their visible JSON files. */
    private object SchemaSnapshots : FrontEnd {
        override val name = "json"

        override fun parse(files: List<Path>): List<ForeignTree> {
            val directory = files.single()
            val snapshots =
                try {
                    OpenMetadataSnapshots.inputs(directory)?.let { return JsonFrontEnd.parse(it) }
                    legacyInputs(directory)
                } catch (_: IOException) {
                    throw ReaderInputException("cannot list snapshot directory $directory")
                } catch (_: UncheckedIOException) {
                    throw ReaderInputException("cannot list snapshot directory $directory")
                }
            if (snapshots.isEmpty()) throw ReaderInputException("no JSON snapshots in $directory")
            return JsonFrontEnd.parse(snapshots)
        }

        fun legacyInputs(directory: Path): List<Path> =
            Files.list(directory).use { paths ->
                paths
                    .filter { file ->
                        val name = file.fileName.toString()
                        !name.startsWith(".") &&
                            name.endsWith(".json") &&
                            Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    }.toList()
                    .sortedWith(compareBy(Bytewise) { it.fileName.toString() })
            }
    }

    /** Replay the source directory named by a reader header, using the importer's snapshot selection. */
    internal fun replaySnapshot(
        snapshot: Path,
        overlay: Path?,
        profile: String,
        out: Path,
    ): Lazy<Result<Map<String, String>>>? {
        val resolved = ImportProfile.shipped(profile) ?: return null
        return try {
            if (!OpenMetadataSnapshots.hasManifest(snapshot) && SchemaSnapshots.legacyInputs(snapshot).isEmpty()) {
                return null
            }
            lazy {
                try {
                    val generated =
                        OpenMetadataSnapshots.read(snapshot) {
                            Reader.run(
                                SchemaSnapshots,
                                resolved,
                                ReaderRequest(listOf(snapshot), out, MetadataVersion.value, overlay = overlay),
                            )
                        }
                    Result.success(generated.files + generated.sidecars)
                } catch (e: ReaderInputException) {
                    Result.failure(e)
                } catch (e: ProfileException) {
                    Result.failure(e)
                } catch (e: IOException) {
                    Result.failure(e)
                } catch (e: UncheckedIOException) {
                    Result.failure(e)
                }
            }
        } catch (e: IOException) {
            lazy { Result.failure(e) }
        } catch (e: UncheckedIOException) {
            lazy { Result.failure(e) }
        }
    }

    /**
     * One reader run of [frontEnd] over [input] for [verb] (`import-schema`, `import-glossary`): resolve the profile, run
     * the reader, then print the report and write the outputs under [out] (or, `dryRun`, print their diff).
     */
    internal fun generate(
        verb: String,
        frontEnd: FrontEnd,
        input: Path,
        profile: String,
        out: Path,
        root: Path?,
        overlay: Path?,
        packageName: String?,
        dryRun: Boolean,
        json: Boolean,
        snapshotHash: String? = null,
    ): CliOutcome {
        val resolved =
            try {
                ImportProfile.resolve(profile).also { it.refuseOverlay(overlay) }
            } catch (e: ReaderInputException) {
                return usage(e.message)
            } catch (e: ProfileException) {
                return located(e)
            }
        val output =
            if (dryRun) {
                null
            } else {
                try {
                    LintCli.Output.of(out.toAbsolutePath().normalize())
                } catch (e: IOException) {
                    return usage("--out $out: cannot create the directory (${e.javaClass.simpleName} ${e.message})")
                }
            }
        val relativeSnapshot = snapshotHash?.let { ".openmetadata/$it" }
        val snapshot = relativeSnapshot?.let { (output?.real ?: out.toAbsolutePath().normalize()).resolve(it) }
        val generated =
            try {
                Reader.run(
                    frontEnd,
                    resolved,
                    ReaderRequest(
                        listOf(input),
                        output?.real ?: out.toAbsolutePath().normalize(),
                        MetadataVersion.value,
                        root?.toAbsolutePath()?.normalize(),
                        overlay,
                        packageName,
                    ),
                    retainedSnapshot = snapshot,
                )
            } catch (e: ReaderInputException) {
                output?.discard()
                return usage(e.message)
            } catch (e: ProfileException) {
                output?.discard()
                return located(e)
            }
        val files = generated.outputs()
        val report = if (json) generated.report.json() else generated.report.markdown()
        if (output == null) {
            // review-330 R4: with --format json stdout is the report JSON alone; the diff goes to stderr.
            return if (json) CliOutcome(0, report, diff(out, files)) else CliOutcome(0, diff(out, files) + report, "")
        }
        if (frontEnd === SchemaSnapshots && files.keys.any { output.real.resolve(it).startsWith(input) }) {
            output.discard()
            return usage("--out $out would write inside snapshot directory $input")
        }
        val paths = relativeSnapshot?.let { files.keys + it } ?: files.keys
        paths.forEach { path ->
            LintCli.link(output.real, path)?.let { at ->
                output.discard()
                return usage("--out $out contains a symbolic link at $at; $verb does not write through links")
            }
        }
        // Every directory first, so a path that cannot be one (a file in its place) fails before any file is written.
        var writing = ""
        try {
            files.keys.forEach { path ->
                writing = path
                Files.createDirectories(output.real.resolve(path).parent)
            }
            if (snapshot != null) {
                writing = requireNotNull(relativeSnapshot)
                DirectImportOutputs.publish(output.real, input, snapshot, snapshotHash, files)
            } else {
                files.forEach { (path, text) ->
                    writing = path
                    Files.writeString(output.real.resolve(path), text)
                }
            }
        } catch (e: ReaderInputException) {
            output.discard()
            return usage("--out $out: ${e.message}")
        } catch (e: IOException) {
            output.discard()
            return usage("--out $out: cannot write $writing (${e.javaClass.simpleName} ${e.message})")
        }
        return CliOutcome(0, report, "ttr-metadata: wrote ${files.size} files to $out\n")
    }

    /** `--dry-run`: each output's unified diff against the file under [out] (a missing file diffs against nothing). */
    private fun diff(
        out: Path,
        files: Map<String, String>,
    ): String =
        buildString {
            val directory = out.toAbsolutePath().normalize()
            files.forEach { (path, text) ->
                val existing =
                    directory.resolve(path).takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }?.let {
                        Files.readString(it)
                    }
                if (existing != text) append(UnifiedDiff.of(path, existing, text))
            }
            if (isEmpty()) append("No changes under $out.\n")
            append("\n")
        }

    private fun located(e: ProfileException) =
        CliOutcome(
            1,
            "",
            "${e.location.file}:${e.location.line}:${e.location.column}: error ${e.code} ${e.location.path}: ${e.detail}\n",
        )

    private fun usage(message: String?) = CliOutcome(2, "", "ttr-metadata: $message\n")
}

/**
 * A unified diff for `--dry-run`: the common head and tail are trimmed, the rest compared line by line (LCS) when it is
 * small and shown as one replaced block when it is not, with three lines of context. Enough for a reviewer to see a
 * snapshot's change before writing it; `git diff` remains the authority once written.
 */
internal object UnifiedDiff {
    private const val CONTEXT = 3
    private const val LCS_CELLS = 4_000_000L

    fun of(
        path: String,
        before: String?,
        after: String,
    ): String {
        val a = before?.lines()?.dropLastWhile { it.isEmpty() }.orEmpty()
        val b = after.lines().dropLastWhile { it.isEmpty() }
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        // The whole edit script: ' ' kept, '-' removed, '+' added, each with its old and new line number.
        val script =
            a.subList(0, head).map { " $it" } + edits(a.subList(head, a.size - tail), b.subList(head, b.size - tail)) +
                a.subList(a.size - tail, a.size).map { " $it" }
        val changed = script.indices.filter { script[it][0] != ' ' }
        return buildString {
            appendLine("--- ${if (before == null) "/dev/null" else "a/$path"}")
            appendLine("+++ b/$path")
            var i = 0
            while (i < changed.size) {
                var last = i
                while (last + 1 < changed.size && changed[last + 1] - changed[last] <= 2 * CONTEXT) last++
                val from = maxOf(0, changed[i] - CONTEXT)
                val to = minOf(script.size, changed[last] + CONTEXT + 1)
                val oldStart = script.subList(0, from).count { it[0] != '+' }
                val newStart = script.subList(0, from).count { it[0] != '-' }
                val hunk = script.subList(from, to)
                val oldCount = hunk.count { it[0] != '+' }
                val newCount = hunk.count { it[0] != '-' }
                appendLine(
                    "@@ -${if (oldCount == 0) oldStart else oldStart + 1},$oldCount " +
                        "+${if (newCount == 0) newStart else newStart + 1},$newCount @@",
                )
                hunk.forEach { appendLine(it) }
                i = last + 1
            }
        }
    }

    private fun edits(
        a: List<String>,
        b: List<String>,
    ): List<String> {
        if (a.isEmpty() || b.isEmpty() || a.size.toLong() * b.size > LCS_CELLS) {
            return a.map { "-$it" } + b.map { "+$it" }
        }
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val out = mutableListOf<String>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> out += " ${a[i++]}".also { j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> out += "-${a[i++]}"
                else -> out += "+${b[j++]}"
            }
        }
        while (i < a.size) out += "-${a[i++]}"
        while (j < b.size) out += "+${b[j++]}"
        return out
    }
}
