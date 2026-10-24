// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val outcome = runMetadataCommand(args.asList())
    print(outcome.stdout)
    System.err.print(outcome.stderr)
    exitProcess(outcome.exitCode)
}

/** Parse through the real command tree while keeping usage errors separate from model failures. */
fun runMetadataCommand(args: List<String>): CliOutcome {
    var outcome = CliOutcome(0, "", "")
    val command =
        MetadataCommand().subcommands(
            LoadCommand { outcome = it },
            ExportCommand { outcome = it },
            mdCommand(),
        )
    return try {
        command.parse(args)
        outcome
    } catch (error: ProgramResult) {
        CliOutcome(error.statusCode, "", "")
    } catch (error: CliktError) {
        val help = command.getFormattedHelp(error).orEmpty() + "\n"
        if (error is PrintHelpMessage && !error.error) CliOutcome(0, help, "") else CliOutcome(64, "", help)
    }
}

class MetadataCommand : CliktCommand(name = "ttr-metadata") {
    override fun help(context: Context) = "Load, validate and export a Tatrman model checkout."

    override fun run() = Unit
}

class LoadCommand(
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "load") {
    override fun help(context: Context) = "Load <root>/model with the Kotlin metadata loader."

    private val root by argument(help = "Checkout root containing modeler.toml")
    private val strict by option("--strict", help = "Fail on dropped files or report errors").flag()
    private val expectVersion by option("--expect-version", help = "Require this exact ttr-metadata version")
    private val json by option("--json", help = "Write a JSON report to stdout").flag()

    override fun run() {
        val result = MetadataLoadCli.run(Path.of(root), strict, expectVersion, json)
        output(result)
    }
}
