// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.*
import org.tatrman.report.*
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.system.exitProcess

data class CliOutcome(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
)

fun main(args: Array<String>) {
    val result = runReportCommand(args.asList())
    print(result.stdout)
    System.err.print(result.stderr)
    exitProcess(result.exitCode)
}

fun runReportCommand(
    args: List<String>,
    clock: Clock = Clock.systemUTC(),
): CliOutcome {
    var result = CliOutcome(0)
    val command = ReportCommand().subcommands(RenderCommand(clock) { result = it }, NewCommand { result = it })
    return try {
        command.parse(args)
        result
    } catch (e: CliktError) {
        val help = command.getFormattedHelp(e).orEmpty() + "\n"
        if (e is PrintHelpMessage && !e.error) CliOutcome(0, help) else CliOutcome(64, stderr = help)
    } catch (e: ReportException) {
        CliOutcome(1, stderr = "ttr-report: ${e.code}: ${e.message}\n")
    } catch (e: java.io.IOException) {
        CliOutcome(1, stderr = "ttr-report: io-error: ${e.message}\n")
    }
}

private class ReportCommand : CliktCommand(name = "ttr-report") {
    override fun help(context: Context) =
        "Create and render open report templates. Door execution awaits ttr-server#286."

    override fun run() = Unit
}

private class RenderCommand(
    private val clock: Clock,
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "render") {
    private val root by argument(help = "Checkout root containing modeler.toml and model/")
    private val id by argument(help = "Report folder id under model/reports/")
    private val params by option("--param", help = "Named parameter k=v; repeat for each value").multiple()
    private val rows by option(
        "--rows",
        help = "Offline table ANCHOR=file.csv; paths relative to the current directory",
    ).multiple()
    private val out by option("--out", help = "Output workbook; defaults to <id>.xlsx in the current directory")
    private val html by option("--html", help = "Print the first declared table as escaped HTML").flag()
    private val door by option(
        "--door",
        help = "Unavailable until server-proto publishes RunSavedQuery (ttr-server#286)",
    )
    private val bearer by option("--bearer", help = "Reserved for the future door runner")

    override fun run() {
        if (door != null ||
            bearer != null
        ) {
            throw UsageError("--door/--bearer require unpublished RunSavedQuery stubs; see ttr-server#286")
        }
        if (rows.isEmpty()) throw UsageError("Supply at least one --rows ANCHOR=file.csv")
        if (html && out != null) throw UsageError("--html writes stdout; --out is for XLSX")
        val values = pairs(params, "--param")
        val files = pairs(rows, "--rows").mapValues { Path.of(it.value) }
        val project = Path.of(root).toAbsolutePath().normalize()
        val (model, preferred) = loadCheckout(project)
        val bytes =
            RenderService(
                model,
                preferred,
                clock,
            ).render(project, id, values, RowsProgramRunner(files), html = html)
        if (html) {
            output(CliOutcome(0, bytes.toString(Charsets.UTF_8) + "\n"))
        } else {
            val target = Path.of(out ?: "$id.xlsx").toAbsolutePath().normalize()
            // Generate all bytes before touching the output. Refusals leave an existing file intact.
            val temp = Files.createTempFile(target.parent, ".ttr-report-", ".xlsx")
            try {
                Files.write(temp, bytes)
                Files.move(
                    temp,
                    target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                Files.deleteIfExists(temp)
            }
            output(CliOutcome(0))
        }
    }

    private fun pairs(
        args: List<String>,
        flag: String,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (arg in args) {
            val name = arg.substringBefore('=')
            if (!arg.contains('=') ||
                name.isBlank() ||
                (flag == "--rows" && arg.substringAfter('=').isBlank())
            ) {
                throw UsageError("$flag expects NAME=value")
            }
            if (result.put(name, arg.substringAfter('=')) != null) throw UsageError("$flag repeats '$name'")
        }
        return result
    }
}

/** Author against a local snapshot; the returned pair is produced entirely by the public report API. */
private class NewCommand(
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "new") {
    private val root by argument(help = "Checkout root; defaults to the current directory").default(".")
    private val program by option("--from", help = "Saved ER or DB query qualified name").required()
    private val id by option("--id", help = "New report id").required()
    private val title by option("--title", help = "English report title").required()
    private val titleCs by option("--title-cs", help = "Optional Czech report title")
    private val roles by option(
        "--role",
        help = "Explicit report role; repeat as needed; defaults to no roles",
    ).multiple()
    private val out by option("--out", help = "Output directory; defaults to <root>/model/reports/<id>")
    private val force by option("--force", help = "Replace an existing report.yaml or template.xlsx").flag()

    override fun run() {
        val project = Path.of(root).toAbsolutePath().normalize()
        val (model, preferred) = loadCheckout(project)
        val titles = linkedMapOf("en" to title).apply { titleCs?.let { put("cs", it) } }
        val generated = TemplateGenerator.fromQuery(model, program, id, titles, roles)
        ReportYaml.read(generated.yaml, id, preferred)
        val target = Path.of(out ?: project.resolve("model/reports/$id").toString()).toAbsolutePath().normalize()
        writePair(target, generated, force)
        output(
            CliOutcome(0, stderr = generated.warnings.joinToString("") { "ttr-report: ${it.code}: ${it.message}\n" }),
        )
    }
}

private fun loadCheckout(project: Path): Pair<SnapshotModelLookup, String> {
    val manifest = project.resolve("modeler.toml")
    if (!Files.isRegularFile(manifest)) throw ReportException("manifest-missing", "No modeler.toml under $project")
    val config = Toml.parse(manifest)
    if (config.hasErrors()) throw ReportException("manifest-invalid", config.errors().joinToString())
    val preferred = config.get("language.preferred") ?: "en"
    if (preferred !is String ||
        preferred.isBlank()
    ) {
        throw ReportException("manifest-invalid", "language.preferred must be a nonempty string")
    }
    val modelRoot = project.resolve("model")
    if (!Files.isDirectory(modelRoot)) throw ReportException("model-missing", "No model/ directory under $project")
    val loaded =
        MetadataLoader(
            listOf(BuiltinStockSource(), FileBasedSource("checkout", 100, LocalFsStorage("checkout", modelRoot))),
        ).load()
    val model = loaded.model ?: throw ReportException("model-invalid", loaded.issues.joinToString { it.message })
    return SnapshotModelLookup(model) to preferred
}

/** Stage both complete files before replacement. Ordinary IO failure restores the prior pair. */
private fun writePair(
    target: Path,
    generated: GeneratedTemplate,
    force: Boolean,
) {
    val files =
        linkedMapOf(
            "report.yaml" to generated.yaml.toByteArray(Charsets.UTF_8),
            "template.xlsx" to generated.xlsx,
        )
    val noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
    if (Files.isSymbolicLink(target) || (Files.exists(target) && !Files.isDirectory(target))) {
        throw ReportException("output-invalid", "Output must be a directory: $target")
    }
    for (name in files.keys) {
        val file = target.resolve(name)
        if (Files.exists(file, noFollow)) {
            if (!force) throw ReportException("output-exists", "Output exists: $file; use --force to replace")
            if (!Files.isRegularFile(
                    file,
                    noFollow,
                )
            ) {
                throw ReportException("output-invalid", "Output is not a regular file: $file")
            }
        }
    }
    Files.createDirectories(target.parent)
    val stage = Files.createTempDirectory(target.parent, ".ttr-report-new-")
    var created = false
    val replaced = mutableListOf<String>()
    val old = mutableSetOf<String>()
    try {
        for ((name, bytes) in files) {
            Files.write(stage.resolve(name), bytes)
            if (Files.exists(target.resolve(name), noFollow)) {
                Files.copy(target.resolve(name), stage.resolve("old-$name"))
                old += name
            }
        }
        if (!Files.exists(target)) {
            Files.createDirectory(target)
            created = true
        }
        for (name in files.keys) {
            if (force) {
                Files.move(
                    stage.resolve(name),
                    target.resolve(name),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                // No REPLACE_EXISTING: a concurrently created file is a refusal too.
                Files.move(stage.resolve(name), target.resolve(name))
            }
            replaced += name
        }
    } catch (failure: java.io.IOException) {
        for (name in replaced.asReversed()) {
            try {
                if (name in old) {
                    Files.move(
                        stage.resolve("old-$name"),
                        target.resolve(name),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } else {
                    Files.deleteIfExists(target.resolve(name))
                }
            } catch (restore: java.io.IOException) {
                failure.addSuppressed(restore)
            }
        }
        if (created) {
            try {
                Files.deleteIfExists(target)
            } catch (cleanup: java.io.IOException) {
                failure.addSuppressed(cleanup)
            }
        }
        throw failure
    } finally {
        Files.list(stage).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(stage)
    }
}
