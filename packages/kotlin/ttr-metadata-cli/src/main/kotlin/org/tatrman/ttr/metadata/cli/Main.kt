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
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.core.UsageError
import org.tatrman.ttr.imports.XmiFrontEnd
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
            LintCommand { outcome = it },
            ProfileCommand().subcommands(ProfileApplyCommand { outcome = it }, ProfileCheckCommand { outcome = it }),
            SnapshotCommand { outcome = it },
            ImportSchemaCommand { outcome = it },
            ImportGlossaryCommand { outcome = it },
            ImportXmiCommand { outcome = it },
        )
    // MW S3.5: `lint` and `profile` exit 2 on usage (contracts §5); the older commands keep 64.
    val usage =
        if (args.firstOrNull { !it.startsWith("-") } in
            setOf("lint", "profile", "snapshot", "import-schema", "import-glossary", "import-xmi")
        ) {
            2
        } else {
            64
        }
    return try {
        command.parse(args)
        outcome
    } catch (error: ProgramResult) {
        CliOutcome(error.statusCode, "", "")
    } catch (error: CliktError) {
        val help = command.getFormattedHelp(error).orEmpty() + "\n"
        if (error is PrintHelpMessage && !error.error) CliOutcome(0, help, "") else CliOutcome(usage, "", help)
    }
}

/** MW S3.5 — `lint`: a strict load through the `yaml` or `ttr` source kind, rendered as text, JSON or GitHub commands. */
class LintCommand(
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "lint") {
    override fun help(context: Context) = "Load a model root strictly and report its diagnostics on authored positions."

    private val root by option("--root", help = "A checkout (modeler.toml + model/) or a model root")
    private val profile by option("--profile", help = PROFILE_HELP)
    private val strict by option("--strict", help = "Exit 1 on any error, as load --strict").flag()
    private val format by option("--format", help = "text, json or github")
        .choice("text" to LintFormat.TEXT, "json" to LintFormat.JSON, "github" to LintFormat.GITHUB)
        .default(LintFormat.TEXT)
    private val codes by option("--codes", help = "Print the diagnostic-code catalogue").flag()

    override fun run() {
        output(
            when {
                codes -> LintCli.codes()
                root == null -> throw UsageError("--root is required unless --codes is given")
                else -> LintCli.lint(Path.of(root!!), profile, strict, format)
            },
        )
    }
}

/** `--profile` on `lint` and `profile` (✅R269-B). */
private const val PROFILE_HELP =
    "Profile identity (canonical/1, or one <root>/profiles/ declares) or a file path relative to the working " +
        "directory; replaces ttr.yaml"

/** ✅R269-A: the profile commands fail on any error whatever `--strict` says; the flag stays for existing callers. */
private const val PROFILE_STRICT_HELP = "Accepted for compatibility; profile commands exit 1 on any error regardless"

class ProfileCommand : CliktCommand(name = "profile") {
    override fun help(context: Context) = "Apply or check a profile over a dialect YAML tree."

    override fun run() = Unit
}

class ProfileApplyCommand(
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "apply") {
    override fun help(context: Context) =
        "Write the tree as canonical YAML: <out>/<package>/<model>.yaml, <package>.sources.json and report.json."

    private val root by option("--root", help = "The dialect tree (or a checkout)").required()
    private val profile by option("--profile", help = PROFILE_HELP)
    private val out by option("--out", help = "Output directory; no output may land inside the root").required()

    @Suppress("unused")
    private val strict by option("--strict", help = PROFILE_STRICT_HELP).flag()

    override fun run() = output(LintCli.apply(Path.of(root), profile, Path.of(out), LintFormat.TEXT))
}

class ProfileCheckCommand(
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "check") {
    override fun help(context: Context) = "Run the apply pipeline and report its diagnostics; writes nothing."

    private val root by option("--root", help = "The dialect tree (or a checkout)").required()
    private val profile by option("--profile", help = PROFILE_HELP)

    @Suppress("unused")
    private val strict by option("--strict", help = PROFILE_STRICT_HELP).flag()
    private val format by option("--format", help = "text, json or github")
        .choice("text" to LintFormat.TEXT, "json" to LintFormat.JSON, "github" to LintFormat.GITHUB)
        .default(LintFormat.TEXT)

    override fun run() = output(LintCli.check(Path.of(root), profile, format))
}

/** MW S3.6 T5 — `snapshot`: the capture seam (contracts §6); P3's only adapter is `file:`. */
class SnapshotCommand(
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "snapshot") {
    override fun help(context: Context) =
        "Capture a foreign export through a profile: ignored keys, principal names and usage counters removed."

    private val from by option("--from", help = "The source: file:<path> (an existing export)").required()
    private val profile by option("--profile", help = PROFILE_HELP).required()
    private val out by option("--out", help = "Output directory; <out>/<name>.yaml").required()
    private val large by option("--large", help = "Write a snapshot over 5 MiB").flag()

    override fun run() = output(SnapshotCli.run(from, profile, Path.of(out), large))
}

/** MW-P5 S5.1 T5 — `import-schema --from file:`: a catalog export through the reader harness (contracts §2). */
class ImportSchemaCommand(
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "import-schema") {
    override fun help(context: Context) =
        "Generate a db package from a catalog export: <out>/<package>/<model>.ttrm, <package>.sources.json, report.json."

    private val from by option("--from", help = "The source: file:<path> (an export, e.g. OpenMetadata's tables)")
        .required()
    private val profile by option("--profile", help = "A shipped profile (om-tables/1) or a profile file").required()
    private val out by option("--out", help = "Output directory").required()
    private val root by option("--root", help = "The model root whose sidecars links and overlays read")
    private val overlay by option(
        "--overlay",
        help = "An authored overlay {<qname|sourceName>: {<property>: <value>}}; refused when the profile declares one",
    )
    private val packageName by option("--package", help = "The package every input lands in (replaces the profile's)")
    private val dryRun by option("--dry-run", help = "Write nothing; print each file's diff against --out").flag()
    private val format by option("--format", help = "text (the PR-body Markdown) or json (the report)")
        .choice("text", "json")
        .default("text")

    override fun run() =
        output(
            ImportSchemaCli.run(
                from,
                profile,
                Path.of(out),
                root?.let(Path::of),
                overlay?.let(Path::of),
                packageName,
                dryRun,
                format == "json",
            ),
        )
}

/** MW-P5 S5.2 T5 — `import-glossary`: a SKOS (Turtle/RDF-XML) or OpenMetadata (CSV) glossary → a cn skeleton + lexicon. */
class ImportGlossaryCommand(
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "import-glossary") {
    override fun help(context: Context) =
        "Generate a cn skeleton and lexicon from a glossary export: <out>/<package>/cn.ttrm, lexicon.<locale>.ttrm, " +
            "<package>.sources.json, report.json."

    private val from by option("--from", help = "The glossary file (Turtle, RDF/XML or CSV)").required()
    private val syntax by option("--syntax", help = "The file's syntax; default from its extension")
        .choice("turtle", "rdfxml", "csv")
    private val profile by option("--profile", help = "A shipped profile (skos/1, om-glossary/1) or a profile file")
        .required()
    private val out by option("--out", help = "Output directory").required()
    private val root by option("--root", help = "The model root whose sidecars links and overlays read")
    private val overlay by option(
        "--overlay",
        help = "An authored overlay {<qname|sourceName>: {<property>: <value>}}; refused when the profile declares one",
    )
    private val packageName by option("--package", help = "The package every input lands in (replaces the profile's)")
    private val dryRun by option("--dry-run", help = "Write nothing; print each file's diff against --out").flag()
    private val format by option("--format", help = "text (the PR-body Markdown) or json (the report)")
        .choice("text", "json")
        .default("text")

    override fun run() =
        output(
            ImportGlossaryCli.run(
                from,
                syntax,
                profile,
                Path.of(out),
                root?.let(Path::of),
                overlay?.let(Path::of),
                packageName,
                dryRun,
                format == "json",
            ),
        )
}

/** MW-P5 S5.3 T4 — `import-xmi`: an XMI 2.1 export (EA flavour) → TTR packages through `ttr-mdg/1` or `ea-erd/1`. */
class ImportXmiCommand(
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "import-xmi") {
    override fun help(context: Context) =
        "Generate TTR packages from an XMI 2.1 export (Enterprise Architect flavour): <out>/<package>/<model>.ttrm, " +
            "<package>.sources.json, report.json."

    private val from by option("--from", help = "The XMI file").required()
    private val profile by option("--profile", help = "A shipped profile (ttr-mdg/1, ea-erd/1) or a profile file")
        .required()
    private val out by option("--out", help = "Output directory").required()
    private val root by option("--root", help = "The model root whose sidecars links and overlays read")
    private val overlay by option(
        "--overlay",
        help = "An authored overlay {<qname|sourceName>: {<property>: <value>}}; refused when the profile declares one",
    )
    private val packageName by option("--package", help = "The package every input lands in (replaces the profile's)")
    private val dryRun by option("--dry-run", help = "Write nothing; print each file's diff against --out").flag()
    private val format by option("--format", help = "text (the PR-body Markdown) or json (the report)")
        .choice("text", "json")
        .default("text")

    override fun run() =
        output(
            ImportSchemaCli.generate(
                "import-xmi",
                XmiFrontEnd,
                Path.of(from.removePrefix("file:")),
                profile,
                Path.of(out),
                root?.let(Path::of),
                overlay?.let(Path::of),
                packageName,
                dryRun,
                format == "json",
            ),
        )
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
