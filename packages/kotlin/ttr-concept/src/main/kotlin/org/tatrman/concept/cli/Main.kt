// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import org.tatrman.concept.CnConceptService
import org.tatrman.concept.HygieneRender
import org.tatrman.concept.HygieneReport
import org.tatrman.concept.ResolvedCnModel
import org.tatrman.concept.Severity
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** The `ttr` command family. This module contributes `cn` (`hygiene`; `materialize` since CN-P3 S3.4). */
fun ttr(): CliktCommand = TtrCommand().subcommands(CnCommand().subcommands(CnHygieneCommand(), CnMaterializeCommand()))

fun main(args: Array<String>): Unit = exitProcess(runTtr(args.toList()))

/**
 * Run [ttr] on [args] and answer the exit code. Clikt exits 1 on a usage error (an unknown flag or value, a missing
 * argument or subcommand); the cn contracts (§7) pin 2, so a mistyped flag in CI never reads as "findings at
 * `--fail-on`". A model that cannot be read (an unreadable file) is 2 as well, with one line on stderr.
 */
fun runTtr(args: List<String>): Int {
    val command = ttr()
    return try {
        command.parse(args)
        0
    } catch (e: CliktError) {
        if (e is PrintHelpMessage && e.error) {
            // a missing subcommand: the help is the error message, so it goes where errors go
            command.getFormattedHelp(e)?.let { System.err.println(it) }
            2
        } else {
            command.echoFormattedHelp(e)
            if (e is UsageError) 2 else e.statusCode
        }
    } catch (e: Exception) {
        System.err.println("ttr: the model could not be read — ${e::class.simpleName}: ${e.message}")
        2
    }
}

class TtrCommand : CliktCommand(name = "ttr") {
    override fun help(context: Context) = "TTR toolchain — the standard's command-line front door."

    override fun run() = Unit
}

class CnCommand : CliktCommand(name = "cn") {
    override fun help(context: Context) = "The conceptual model (`model cn`)."

    override fun run() = Unit
}

/**
 * `ttr cn hygiene <dir> [--format text|json] [--fail-on error|warning]` (CN-P2 S2.7 T2; cn contracts §7) — load the
 * model under `<dir>` as one source (the way Veles loads one), and print the hygiene report v2: per concept, its
 * grade, `via`, grounding, hop and landing, core vs open, dangling groundings, redundant term duplicates,
 * phase-as-default and retired codes — then the definition-level findings, which only this tool reports (CN-D29).
 * The model's load errors go to stderr.
 *
 * Exit: 0 — the report printed, and nothing at or above `--fail-on` (no `--fail-on`: always 0) · 1 — a finding at or
 * above `--fail-on`: an error is a load error, a dangling grounding or an error-grade definition finding; a warning
 * a phase-as-default row, a retired code or a warning-grade definition finding · 2 — usage, or the model could not be
 * read.
 */
class CnHygieneCommand : CliktCommand(name = "hygiene") {
    override fun help(context: Context) = "Report the conceptual model's hygiene: groundings, hop, terms, definitions."

    private val dir by argument("dir", help = "root dir of the TTR-M model (scanned for *.ttrm)")
    private val format by option("--format", help = "text (markdown) or json").choice("text", "json").default("text")
    private val failOn by option("--fail-on", help = "exit 1 on a finding at or above this grade").choice(
        "error",
        "warning",
    )

    override fun run() {
        val root = Path.of(dir).toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) {
            echo("ttr cn hygiene: '$dir' is not a directory", err = true)
            throw ProgramResult(2)
        }
        val (model, loadErrors) = load(root, "cn-hygiene")
        loadErrors.forEach { echo("ttr cn hygiene: $it", err = true) }

        val report = CnConceptService(ResolvedCnModel.of(model)).hygieneReport()
        val fileName = { f: String -> relative(root, f) }
        when (format) {
            "json" -> echo(HygieneRender.jsonText(report, fileName), trailingNewline = false)
            else -> echo(HygieneRender.markdown(report, fileName), trailingNewline = false)
        }

        val errors = loadErrors.size + errors(report)
        val warnings = warnings(report)
        val fail =
            when (failOn) {
                "error" -> errors > 0
                "warning" -> errors + warnings > 0
                else -> false
            }
        if (fail) throw ProgramResult(1)
    }

    private fun relative(
        root: Path,
        file: String,
    ): String {
        val p = Path.of(file)
        return if (p.isAbsolute && p.startsWith(root)) root.relativize(p).toString() else file
    }

    private fun errors(r: HygieneReport): Int =
        r.concepts.sumOf { it.danglingGroundings.size } + r.definitions.count { it.severity == Severity.ERROR }

    private fun warnings(r: HygieneReport): Int =
        r.concepts.sumOf { it.phaseAsDefault.size + it.retiredCodes.size } +
            r.definitions.count { it.severity == Severity.WARNING }
}

/**
 * The model under [root] loaded as one source (the way Veles loads one) and reconciled, with its load errors
 * (`<file>:<line>: <message>`). The stock `cnc.role.*` vocabulary rides ahead of the model, as at the metadata
 * service's boot — without it every `roles: [fact]` is a load error (`ttr/unimported-reference`) the editor never shows.
 */
internal fun load(
    root: Path,
    id: String,
): Pair<Model, List<String>> {
    val snapshot = FileBasedSource("cn", 100, LocalFsStorage(id = "cn", rootPath = root)).load()
    val reconciled =
        ModelReconciler(ModelDescriptor(id = id, name = root.fileName?.toString() ?: "model"))
            .reconcile(listOf(BuiltinStockSource().load(), snapshot))
    val errors = (snapshot.errors + reconciled.errors).distinct().map { "${it.file}:${it.line}: ${it.message}" }
    return reconciled.model to errors
}

/**
 * `ttr cn materialize <dir> <cn.Phase> [--dialect …] [--out <dir>]` (CN-P3 S3.4 T6; cn contracts §3, CN-D1, A-CN-16) —
 * the phase's er rendering written as model text: a `def query <phase>_q` and the entity stacked on it, in the phase's
 * package. Printed, or written under `--out` (`<phase>.ttrm`). The author then replaces the phase's `definition:` by a
 * `cn2er_phase` grounding on the entity (AM-2). A definition reading a `:name` parameter or `:as_of` is refused
 * (`cn/materialize-parametric`) — a logical query takes none.
 *
 * Exit: 0 — written · 1 — refused (the diagnostic on stderr) · 2 — usage, or the model could not be read.
 */
class CnMaterializeCommand : CliktCommand(name = "materialize") {
    override fun help(context: Context) =
        "Write a phase's er rendering as model text: a logical query and the entity stacked on it."

    private val dir by argument("dir", help = "root dir of the TTR-M model (scanned for *.ttrm)")
    private val phase by argument("phase", help = "the phase, `cn.<Name>`")
    private val dialect by option(
        "--dialect",
        help = "the target dialect (the er text is the shared dialect's)",
    ).default("postgresql")
    private val out by option("--out", help = "write the files under this directory instead of printing them")

    override fun run() {
        val root = Path.of(dir).toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) {
            echo("ttr cn materialize: '$dir' is not a directory", err = true)
            throw ProgramResult(2)
        }
        val (model, loadErrors) = load(root, "cn-materialize")
        loadErrors.forEach { echo("ttr cn materialize: $it", err = true) }
        // never model text from a model read in part (review-125 re-review): exit 2, as documented
        if (loadErrors.isNotEmpty()) throw ProgramResult(2)
        val ref =
            ConceptRef
                .newBuilder()
                .setQname(if (phase.startsWith("cn.")) phase else "cn.$phase")
                .build()
        val text = CnConceptService(ResolvedCnModel.of(model)).materialize(ref, dialect)
        if (text.files.isEmpty()) {
            text.diagnostics.forEach { echo("ttr cn materialize: ${it.slug}: ${it.message}", err = true) }
            throw ProgramResult(1)
        }
        val target = out?.let { Path.of(it).toAbsolutePath().normalize() }
        for ((path, content) in text.files) {
            if (target == null) {
                echo(content, trailingNewline = false)
            } else {
                val file = target.resolve(path)
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
                echo("ttr cn materialize: wrote $file")
            }
        }
    }
}
