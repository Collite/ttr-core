// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    MetadataCommand().subcommands(LoadCommand()).main(args)
}

class MetadataCommand : CliktCommand(name = "ttr-metadata") {
    override fun help(context: Context) = "Load and validate a Tatrman model checkout."

    override fun run() = Unit
}

class LoadCommand : CliktCommand(name = "load") {
    override fun help(context: Context) = "Load <root>/model with the Kotlin metadata loader."

    private val root by argument(help = "Checkout root containing modeler.toml")
    private val strict by option("--strict", help = "Fail on dropped files or reconciliation errors").flag()
    private val expectVersion by option("--expect-version", help = "Require this exact ttr-metadata version")
    private val json by option("--json", help = "Write a JSON report to stdout").flag()

    override fun run() {
        val result = MetadataLoadCli.run(Path.of(root), strict, expectVersion, json)
        print(result.stdout)
        System.err.print(result.stderr)
        exitProcess(result.exitCode)
    }
}
