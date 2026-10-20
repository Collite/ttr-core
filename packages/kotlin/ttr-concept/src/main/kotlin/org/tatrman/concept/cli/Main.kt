// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
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
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path

/** The `ttr` command family. This module contributes `cn` (`hygiene`; `materialize` joins it at CN-P3). */
fun main(args: Array<String>) {
    TtrCommand()
        .subcommands(CnCommand().subcommands(CnHygieneCommand()))
        .main(args)
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
 * a phase-as-default row, a retired code or a warning-grade definition finding · 2 — usage.
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
        val snapshot = FileBasedSource("cn", 100, LocalFsStorage(id = "cn", rootPath = root)).load()
        val reconciled =
            ModelReconciler(ModelDescriptor(id = "cn-hygiene", name = root.fileName?.toString() ?: "model"))
                .reconcile(listOf(snapshot))
        val loadErrors = (snapshot.errors + reconciled.errors).distinct()
        loadErrors.forEach { echo("ttr cn hygiene: ${it.file}:${it.line}: ${it.message}", err = true) }

        val report = CnConceptService(ResolvedCnModel.of(reconciled.model)).hygieneReport()
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
