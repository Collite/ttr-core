// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.cli

import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.LexiconArchive
import org.tatrman.ttr.lexicon.LexiconViolation
import org.tatrman.ttr.lexicon.OperatorLibrary
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.TargetFacts
import org.tatrman.ttr.lexicon.compile.CompileWarning
import org.tatrman.ttr.lexicon.compile.LexiconBuild
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.snapshot.SnapshotId
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/** What one `ttr-lexicon build` invocation did. The command prints it; the tests assert on it. */
data class CliOutcome(
    val exitCode: Int,
    /** The archive's content id, or null when nothing was built. */
    val id: String?,
    val violations: List<LexiconViolation>,
    val warnings: List<CompileWarning>,
    val stdout: String,
    val stderr: String,
)

/** The archive read back the way a SERVING consumer reads it (lex-matcher, the grounding kernels). */
data class ArchiveContents(
    val id: String,
    val lexicon: CompiledLexicon,
    val operators: OperatorLibrary,
)

/**
 * RV-P3.1 — the estate lexicon build.
 *
 * `LexiconBuild.run` has existed since P1.2 as a library function whose only callers were tests,
 * so until this command nothing anywhere could produce a lexicon archive and every reader
 * (lex-matcher's `LexiconArchiveSource`, the P1.6 grounding kernels) ran its `unset ⇒ disabled`
 * path. This is the producer half.
 *
 * Kept separate from the clikt command so the behaviour is testable without a process: [run]
 * takes plain values and returns a [CliOutcome], and `Main.kt` does nothing but map flags in and
 * streams + an exit code out.
 *
 * **Output is buffered and printed at the end** rather than streamed. A build is short, and the
 * alternative — printing progress before knowing the exit code — risks reporting an archive id
 * for a build that then fails and writes nothing.
 */
object LexiconBuildCli {
    /** Same exit-code vocabulary as `resolve-packages`, which is the recipe an estate already runs. */
    const val EXIT_OK: Int = 0

    /** The build failed: schema violations, or a model that would not load. Nothing was written. */
    const val EXIT_FAILED: Int = 2

    /** `--check` only: the committed archive is stale, or absent. */
    const val EXIT_DRIFT: Int = 3

    /**
     * T1 ruling 3 — version-**less** on purpose. Stamping the toolchain version would invalidate
     * every committed archive on every patch release while detecting nothing extra: a compiler
     * change that actually alters the output already changes the entry table, and with it the id.
     */
    const val DEFAULT_PRODUCED_BY: String = "ttr-lexicon-cli"

    /** Where a repo keeps its model. The same name `LexiconBuild` resolves its `.ttrm` sugar under. */
    const val MODEL_DIR: String = "model"

    private const val SOURCE_DATE_EPOCH = "SOURCE_DATE_EPOCH"

    /**
     * T1 ruling 3 — the default is a **fixed stamp**, never the clock.
     *
     * `builtAt` lands in the lexicon header, which is inside the archive, which is inside
     * `SnapshotId.of`. Since `--check` compares ids, a wall-clock default would move the id on
     * every invocation and the drift gate would report drift forever — noise from the day it
     * shipped. `SOURCE_DATE_EPOCH` is the reproducible-builds convention the header's own
     * docstring names, and the rule `SnapshotWriter` already follows one layer down (`mtime=0`).
     */
    fun defaultBuiltAt(env: Map<String, String> = System.getenv()): String {
        val epoch = env[SOURCE_DATE_EPOCH]?.trim()?.toLongOrNull() ?: 0L
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(epoch))
    }

    @Suppress("LongParameterList", "LongMethod", "ReturnCount")
    fun run(
        repoRoot: Path,
        out: Path,
        check: Boolean = false,
        includeStdlib: Boolean = true,
        verbose: Boolean = false,
        builtAt: String = defaultBuiltAt(),
        producedBy: String = DEFAULT_PRODUCED_BY,
    ): CliOutcome {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        fun failed(message: String): CliOutcome {
            stderr.appendLine(message)
            return CliOutcome(EXIT_FAILED, null, emptyList(), emptyList(), stdout.toString(), stderr.toString())
        }

        val model =
            when (val loaded = loadModel(repoRoot)) {
                is ModelLoad.Failed -> {
                    loaded.messages.forEach { stderr.appendLine(it) }
                    return failed(
                        "ttr-lexicon build: the model under ${repoRoot.resolve(MODEL_DIR)} did not load; " +
                            "fix the model first (every lexicon target ref is checked against it).",
                    )
                }

                is ModelLoad.Ok -> {
                    loaded.warnings.forEach { stderr.appendLine("warning: $it") }
                    loaded.model
                }
            }

        val modelSnapshotId = ModelContentId.of(repoRoot.resolve(MODEL_DIR))
        if (verbose) stderr.appendLine("ttr-lexicon build: model $modelSnapshotId")

        val outcome =
            LexiconBuild.run(
                repoRoot = repoRoot,
                model = model,
                modelSnapshotId = modelSnapshotId,
                builtAt = builtAt,
                producedBy = producedBy,
                includeStdlib = includeStdlib,
            )

        // Violations come first and are terminal: a file that did not parse is an author's bug,
        // and compiling around it would emit an archive missing rows nobody asked to drop.
        if (!outcome.ok) {
            outcome.violations.forEach {
                stderr.appendLine("${it.code} ${it.provenance.file}:${it.provenance.line}: ${it.message}")
            }
            return CliOutcome(
                EXIT_FAILED,
                null,
                outcome.violations,
                outcome.result.warnings,
                stdout.toString(),
                // No archive is written, so say so — an author who sees only error lines cannot
                // tell whether a half-built artifact is now sitting at --out.
                stderr
                    .appendLine("ttr-lexicon build: ${outcome.violations.size} violation(s); no archive written.")
                    .toString(),
            )
        }

        // RV-20: a dangling ref drops its row and warns. Never fatal — an estate mid-refactor
        // still needs the rest of its vocabulary.
        outcome.result.warnings.forEach {
            stderr.appendLine("${it.code} ${it.provenance.file}:${it.provenance.line}: ${it.message}")
        }

        val id = outcome.packed.id
        val lexicon = outcome.result.lexicon
        val entries = lexicon.entries
        stdout.appendLine("ttr-lexicon build: $id")
        // CN-P4 — the archive's schema, said out loud: v5 → v6 is a readers-first rollout (cn contracts §5, A-CN-2),
        // and the person running the build is the one who has to know which side of it this archive is.
        stdout.appendLine("  schema: ${lexicon.header.schemaVersion}")
        stdout.appendLine("  entries: ${entries.size}")
        TargetClass.entries
            .map { cls -> cls to entries.count { it.targetClass == cls } }
            .filter { (_, count) -> count > 0 }
            .forEach { (cls, count) -> stdout.appendLine("    $cls: $count") }
        val concepts = lexicon.targets.values.count { it.objectKind == TargetFacts.CONCEPT }
        if (concepts > 0) stdout.appendLine("  concepts: $concepts")
        stdout.appendLine("  operators: ${outcome.result.operators.operators.size}")
        if (outcome.result.warnings.isNotEmpty()) stdout.appendLine("  warnings: ${outcome.result.warnings.size}")

        if (check) {
            return check(out, id, outcome, stdout, stderr)
        }

        out.parent?.createDirectories()
        out.writeBytes(outcome.packed.bytes)
        if (verbose) stderr.appendLine("ttr-lexicon build: wrote $out")

        return CliOutcome(EXIT_OK, id, emptyList(), outcome.result.warnings, stdout.toString(), stderr.toString())
    }

    /**
     * T4 — the drift gate, comparing **ids** and never raw bytes.
     *
     * `SnapshotId.of` over what is on disk versus the id of what we just compiled: the archive is
     * compressed, and comparing freshly-compressed bytes against a file would make the gate a test
     * of the zstd version rather than of the vocabulary. Mirrors `resolve-packages --check`'s exit
     * codes and message shape, which is the recipe hartland's `check-model` already runs.
     */
    private fun check(
        out: Path,
        id: String,
        outcome: org.tatrman.ttr.lexicon.compile.LexiconBuildOutcome,
        stdout: StringBuilder,
        stderr: StringBuilder,
    ): CliOutcome {
        if (!out.exists()) {
            stderr.appendLine(
                "ttr-lexicon build --check: no archive at $out; run 'ttr-lexicon build' to generate it.",
            )
            return CliOutcome(
                EXIT_DRIFT,
                id,
                emptyList(),
                outcome.result.warnings,
                stdout.toString(),
                stderr.toString(),
            )
        }

        val onDisk = SnapshotId.of(out.readBytes())
        if (onDisk != id) {
            stderr.appendLine(
                "ttr-lexicon build --check: $out is stale (committed $onDisk, sources compile to $id). " +
                    "Re-run 'ttr-lexicon build --out $out'.",
            )
            return CliOutcome(
                EXIT_DRIFT,
                id,
                emptyList(),
                outcome.result.warnings,
                stdout.toString(),
                stderr.toString(),
            )
        }

        stderr.appendLine("ttr-lexicon build --check: $out is up to date.")
        return CliOutcome(EXIT_OK, id, emptyList(), outcome.result.warnings, stdout.toString(), stderr.toString())
    }

    /**
     * The consumer path, verbatim: `SnapshotReader.read` → `kind == lexicon` →
     * `CompiledLexicon.fromJson`. Exposed so the round-trip is asserted against what
     * `LexiconArchiveSource` and `GroundingSliceSource` actually do, rather than against the
     * in-memory `CompileResult` the producer happened to hold.
     */
    fun readBack(archive: Path): ArchiveContents {
        val bytes = archive.readBytes()
        val contents =
            when (val read = SnapshotReader.read(bytes)) {
                is SnapshotReadResult.Ok -> read.contents
                is SnapshotReadResult.Failure -> error("not a readable snapshot archive: ${read.reason}")
            }
        require(contents.manifest.kind == LexiconArchive.KIND) {
            "expected a ${LexiconArchive.KIND} archive, got ${contents.manifest.kind}"
        }
        return ArchiveContents(
            id = SnapshotId.of(bytes),
            lexicon = CompiledLexicon.fromJson(contents.docs.getValue(LexiconArchive.LEXICON)),
            operators = OperatorLibrary.fromJson(contents.docs.getValue(LexiconArchive.OPERATORS)),
        )
    }

    private sealed interface ModelLoad {
        data class Ok(
            val model: Model,
            val warnings: List<String>,
        ) : ModelLoad

        data class Failed(
            val messages: List<String>,
        ) : ModelLoad
    }

    /**
     * The one model diagnostic this command deliberately builds through.
     *
     * `ModelRefIndex` keys on `QualifiedName.dotted()`, which renders `<schema>.<namespace>.<name>`
     * and **drops the package entirely** — so a package/directory layout complaint provably cannot
     * change a single lexicon lookup. It is also not hypothetical: hartland declares
     * `package hartland` in all 26 of its model files on purpose (BM-9 — one flat package over a
     * kind-directory tree, so cross-kind refs need no import) and sets `[packages] layout = "off"`
     * in `modeler.toml` to say so. That switch is read by the TypeScript linter and not by
     * `FileBasedSource`, which raises the mismatch unconditionally; treating it as fatal here would
     * make this command unable to build the estate it exists for.
     */
    private val TOLERATED_MODEL_ISSUES = setOf(LoadIssue.Category.PACKAGE_MISMATCH)

    /**
     * T1 ruling 1 — the composition that already exists (`MetadataLoader` over `FileBasedSource` /
     * `LocalFsStorage`), not a third model loader.
     *
     * Errors are **fatal** unless they are in [TOLERATED_MODEL_ISSUES], and the default is fatal on
     * purpose: `ModelRefIndex.of(model)` is what tells RV-20 whether a `targetRef` exists, so over
     * a half-loaded model every ref looks dangling and the build would drop the whole declared
     * layer, warn, exit zero, and emit a valid, plausible, nearly-empty archive. Silent and
     * well-formed is the worst way for this command to be wrong, so an unrecognised diagnostic
     * stops the build rather than being assumed harmless.
     */
    private fun loadModel(repoRoot: Path): ModelLoad {
        val modelRoot = repoRoot.resolve(MODEL_DIR)
        if (!modelRoot.exists()) {
            return ModelLoad.Failed(listOf("no $MODEL_DIR/ directory under $repoRoot"))
        }

        val result =
            MetadataLoader(
                listOf(
                    // The `cnc.role.*` vocabulary — `roles: [fact]`, `roles: [dimension]` — which
                    // the metadata service registers ahead of user sources at boot. Without it every
                    // such reference in an estate's model is `ttr/unimported-reference`, which is
                    // fatal here and would leave the CLI unable to load any real estate's model.
                    BuiltinStockSource(),
                    FileBasedSource(
                        sourceId = "repo",
                        priority = 100,
                        storage = LocalFsStorage(id = "repo", rootPath = modelRoot),
                    ),
                ),
            ).load()

        val model = result.model
        val (tolerated, fatal) = result.errors.partition { it.category in TOLERATED_MODEL_ISSUES }
        if (fatal.isNotEmpty() || model == null) {
            return ModelLoad.Failed(fatal.map { describe(it) }.ifEmpty { listOf("the model loaded to nothing") })
        }
        return ModelLoad.Ok(model, (tolerated + result.warnings).map { describe(it) })
    }

    /**
     * `file:line: message`, or `file: message` when the issue carries no line.
     *
     * A model issue's line is -1 when the diagnostic is about the FILE rather than a span in it —
     * the package/directory mismatch every hartland file raises is 26 of those, and printing
     * `…/er/sales.ttrm:-1:` reads as a bug in this command on the way to a message that is not
     * about this command at all.
     */
    private fun describe(issue: LoadIssue): String =
        if (issue.line > 0) "${issue.file}:${issue.line}: ${issue.message}" else "${issue.file}: ${issue.message}"
}
