// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import org.tatrman.ttr.metadata.source.*
import java.nio.file.Files
import java.nio.file.Path

/** Reused by the `ttr` front door; the implementation and report belong to metadata. */
fun mdCommand(): CliktCommand = MdCommand().subcommands(MdHopCommand())

class MdCommand : CliktCommand(name = "md") {
    override fun help(context: Context) = "The multidimensional model."

    override fun run() = Unit
}

class MdHopCommand : CliktCommand(name = "hop") {
    private val dir by argument(help = "Model directory (scanned for TTR-M files)")
    private val all by option(
        "--all",
        help = "Report lint findings for all MD elements, including ungrounded elements",
    ).flag()
    private val format by option("--format").choice("text", "json").default("text")
    private val failOn by option("--fail-on").choice("error", "warning")

    override fun help(context: Context) = "Report every MD element's ER landing and derived warehouse roles."

    override fun run() {
        val root = Path.of(dir).toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) {
            echo("ttr md hop: '$dir' is not a directory", err = true)
            throw ProgramResult(2)
        }
        val snapshot = FileBasedSource("md-hop", 100, LocalFsStorage("md-hop", root)).load()
        snapshot.errors.forEach { echo("${it.file}:${it.line}: ${it.message}", err = true) }
        val (docs, resolver) = CnBuilder.project(snapshot.loadedFiles)
        val report = mdHopReport(docs, resolver, all)
        echo(if (format == "json") MdHopRender.json(report) else MdHopRender.markdown(report), trailingNewline = false)
        if (failOn != null &&
            (snapshot.errors.isNotEmpty() || (failOn == "warning" && report.issues.any { it.severity == "warning" }))
        ) {
            throw ProgramResult(1)
        }
    }
}
