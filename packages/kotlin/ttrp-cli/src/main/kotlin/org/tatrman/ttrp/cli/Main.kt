// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.versionOption
import org.tatrman.ttrp.bundle.BundleAssembler
import org.tatrman.ttrp.bundle.EmitPluginLoader
import org.tatrman.ttrp.member.ConnectedMemberCatalog
import org.tatrman.ttrp.bundle.PlacementVariants
import org.tatrman.ttrp.conform.BundleInvoker
import org.tatrman.ttrp.conform.ConformRunner
import org.tatrman.ttrp.conform.EmitDeterminismKit
import org.tatrman.ttrp.conform.eval.EvalComparator
import org.tatrman.ttrp.conform.eval.EvalCorpus
import org.tatrman.ttrp.conform.eval.EvalRunner
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifestReader
import org.tatrman.ttrp.resolve.TtrpChecker
import java.nio.file.Files
import java.nio.file.Path

/** The `ttrp` command (S2): `build` / `run` / `explain` / `conform` / `deploy` (+ the legacy `check`). */
fun main(args: Array<String>) {
    TtrpCommand()
        .subcommands(
            BuildCommand(),
            RunCommand(),
            ExplainCommand(),
            CheckCommand(),
            ConformCommand(),
            EmitDeterminismCommand(),
            EvalCommand(),
            FetchCommand(),
            DeployCommand(),
        ).main(args)
}

class TtrpCommand : CliktCommand(name = "ttrp") {
    init {
        // `ttrp --version` — the toolchain version every bundle this build writes records.
        versionOption(ToolchainVersion.current, message = { "ttrp $it" })
    }

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "TTR-P toolchain: compile, bundle, run, and conform .ttrp programs."

    override fun run() = Unit
}

/** `ttrp build <file>.ttrp [--out <dir>]` → assemble the bundle; exit ≠ 0 on error diagnostics. */
class BuildCommand : CliktCommand(name = "build") {
    private val file by argument()
    private val out by option("--out").default(".")

    // PL-P1.S2: the connected-binding compile flags (contracts §3). --frozen is the CI default
    // (no network, fail on incomplete cache); --offline compiles from cache and records staleness.
    // PL-P1.S3: thread the resolved LockMode into the compile so BundleAssembler resolves via
    // MetadataServerSource (cache) rather than LocalFs when a connected binding is configured, and
    // the compile record captures staleness. The surface is declared here; the wiring lands in S3.
    private val frozen by
        option(
            "--frozen",
            help =
                "Compile from the pinned cache with no network. NOTE: declared seam — enforcement (fail on an " +
                    "incomplete cache) lands in PL-P1.S3; today the flag is accepted but not enforced.",
        ).flag()
    private val offline by
        option(
            "--offline",
            help =
                "Compile from cache and record staleness. NOTE: declared seam — wiring lands in PL-P1.S3; today " +
                    "the flag is accepted but does not yet change resolution.",
        ).flag()

    // Connected mode (S6-B): resolve members against a running ttr-designer-server over WS. Omitted ⇒
    // serverless/disconnected (R13) — bare members are illegal, qualified members defer to bind time.
    private val connected by option(
        "--connected",
        help = "ws:// URL of a ttr-designer-server for connected member resolution",
    )

    override fun run() {
        val abs = Path.of(file).toAbsolutePath()
        if (!Files.isRegularFile(abs)) {
            echo("ttrp build: no such file: $file", err = true)
            throw ProgramResult(2)
        }
        val manifestResult = TtrpManifestReader.resolve(abs.parent ?: abs)
        // Surface the H-6 signature knob: in v1 it can only be honored by the (not-yet-wired) isolated
        // third-party loader, so a project that sets it must be told it is currently inert — never silently.
        EmitPluginLoader.signaturePolicy(manifestResult.manifest.requireSignedPlugins) {
            echo("ttrp build: $it", err = true)
        }
        if (frozen || offline) {
            // PL-P1.S3 wires this into resolution (MetadataServerSource + compile-record staleness).
            echo(
                "ttrp build: compile mode = ${if (frozen) "frozen" else "offline"} (seam declared, S3 wires it)",
                err = true,
            )
        }
        val result =
            try {
                BundleAssembler(ToolchainVersion.current).build(
                    source = Files.readString(abs),
                    fileName = abs.toString(),
                    pipelineManifest = manifestResult.manifest,
                    modelsRoot = manifestResult.manifest.modelsRoot(),
                    outDir = Path.of(out),
                    memberCatalog = connected?.let { ConnectedMemberCatalog.forUrl(it) },
                )
            } catch (e: org.tatrman.ttr.md.resolve.CatalogUnavailable) {
                // GI-19 hard error: connected compile requested, but the catalog is unreachable.
                echo(
                    "ttrp build: ${e.message} — start the server, or omit --connected to compile disconnected",
                    err = true,
                )
                throw ProgramResult(1)
            } catch (e: IllegalArgumentException) {
                echo(e.message ?: "build failed", err = true)
                throw ProgramResult(1)
            } catch (e: org.tatrman.ttrp.emit.TtrpEmitException) {
                // An emit-time failure (a node an engine cannot run, a translator refusal) is a diagnostic,
                // not a crash: print the TTRP-EMT line (+ its suggestion) and exit 1.
                echo(e.message ?: "build failed", err = true)
                e.suggestedAlternative?.let { echo("  ↳ suggested: $it", err = true) }
                throw ProgramResult(1)
            }
        echo(result.dir.toString())
    }
}

/** `ttrp run <file>.ttrp | <bundle>` → build if source, then `bash run.sh`, propagating exit 0/1/2. */
class RunCommand : CliktCommand(name = "run") {
    private val target by argument()

    override fun run() {
        val p = Path.of(target).toAbsolutePath()
        val bundleDir =
            when {
                Files.isDirectory(p) && Files.exists(p.resolve("run.sh")) -> p
                Files.isRegularFile(p) && p.toString().endsWith(".ttrp") -> {
                    val manifestResult = TtrpManifestReader.resolve(p.parent ?: p)
                    BundleAssembler(ToolchainVersion.current)
                        .build(
                            Files.readString(p),
                            p.toString(),
                            manifestResult.manifest,
                            manifestResult.manifest.modelsRoot(),
                            p.parent ?: Path.of("."),
                        ).dir
                }
                else -> {
                    echo("ttrp run: expected a .ttrp file or a <program>.bundle dir", err = true)
                    throw ProgramResult(2)
                }
            }
        val proc =
            ProcessBuilder("bash", "run.sh")
                .directory(bundleDir.toFile())
                .inheritIO()
                .start()
        throw ProgramResult(proc.waitFor())
    }
}

/** `ttrp explain <file>.ttrp` — delegates to the Stage 2.3 explain rendering (S4). */
class ExplainCommand : CliktCommand(name = "explain") {
    private val file by argument()

    override fun run(): Unit = throw ProgramResult(TtrpCli.runExplain(Path.of(file), ::echo))
}

/** `ttrp check <file>.ttrp` — the front-half checker (P1). */
class CheckCommand : CliktCommand(name = "check") {
    private val file by argument()

    override fun run(): Unit = throw ProgramResult(TtrpCli.runCheck(Path.of(file), ::echo))
}

/**
 * `ttrp conform <file>.ttrp [--tolerance <col>=<eps>...]` — builds the placement variants, runs each
 * bundle's `run.sh`, and compares `out/` displays under the Q9 seven-point procedure. Exits 0 all-
 * pass · 1 comparison failure · 2 invocation/pre-flight failure (mirrors the run contract).
 */
class ConformCommand : CliktCommand(name = "conform") {
    private val file by argument()
    private val tolerance by option("--tolerance", help = "per-column float64 tolerance, <col>=<eps>").multiple()

    override fun run() {
        val abs = Path.of(file).toAbsolutePath()
        if (!Files.isRegularFile(abs)) {
            echo("ttrp conform: no such file: $file", err = true)
            throw ProgramResult(2)
        }
        val manifestResult = TtrpManifestReader.resolve(abs.parent ?: abs)
        val outDir = Files.createTempDirectory("ttrp-conform")
        val variants =
            PlacementVariants.build(
                Files.readString(abs),
                abs.toString(),
                manifestResult.manifest,
                manifestResult.manifest.modelsRoot(),
                outDir,
                toolchainVersion = ToolchainVersion.current,
            )
        val tolerances =
            tolerance
                .mapNotNull { spec ->
                    val (c, e) = spec.split("=", limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
                    if (c != null && e?.toDoubleOrNull() != null) c to e.toDouble() else null
                }.toMap()
        val env = System.getenv().filterKeys { it.startsWith("TTR_CONN_") }
        val outcome = ConformRunner(BundleInvoker(env), tolerances = tolerances).run(variants)
        echo(outcome.summary())
        throw ProgramResult(outcome.exitCode)
    }
}

/**
 * `ttrp emit-determinism --plugin <coords> <file.ttrp>...` — the H-6 determinism kit as a conformance verb: for
 * each program it compiles the §8 EmitRequest, re-emits it TWICE through the plugin, and byte-compares. This is
 * the third-party emit-plugin CERTIFICATION requirement (contracts §8/EQ-2) — a plugin that reads a clock /
 * random / process state fails; a pure one passes. Exit 0 on PASS, 1 on a divergence, 2 on a usage error.
 */
class EmitDeterminismCommand : CliktCommand(name = "emit-determinism") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "H-6: double-emit each program through the emit plugin and byte-compare — the plugin certification check."

    private val plugin by
        option("--plugin", help = "emit-plugin Maven coordinates, e.g. org.tatrman:ttr-emit-bash").required()
    private val files by argument(name = "PROGRAM").multiple(required = true)

    override fun run() {
        val target =
            BUILTIN_TARGETS[plugin]
                ?: run {
                    echo(
                        "emit-determinism: '$plugin' is not a built-in plugin — isolated plugin-dir loading is a " +
                            "follow-up; S2 certifies the in-tree plugins (${BUILTIN_TARGETS.keys.sorted()}).",
                        err = true,
                    )
                    throw ProgramResult(2)
                }
        val emitPlugin = EmitPluginLoader.builtin(target)
        val tmp = Files.createTempDirectory("ttrp-emit-determinism")
        val requests =
            files.map { f ->
                val abs = Path.of(f).toAbsolutePath()
                if (!Files.isRegularFile(abs)) {
                    echo("emit-determinism: no such file: $f", err = true)
                    throw ProgramResult(2)
                }
                val manifest = TtrpManifestReader.resolve(abs.parent ?: abs)
                BundleAssembler(ToolchainVersion.current, emitPlugin)
                    .build(
                        source = Files.readString(abs),
                        fileName = abs.toString(),
                        pipelineManifest = manifest.manifest,
                        modelsRoot = manifest.manifest.modelsRoot(),
                        outDir = tmp,
                    ).emitRequest
            }
        val report = EmitDeterminismKit.check(emitPlugin, requests) { i -> files[i] }
        echo(report.render())
        if (!report.deterministic) throw ProgramResult(1)
    }

    companion object {
        /** In-tree (built-in) plugin coordinates → their `targetId`. */
        private val BUILTIN_TARGETS =
            mapOf(
                "org.tatrman:ttr-emit-bash" to "bash",
                // PL-P5.S3 — Kestra ships in-tree too, so it is certifiable via `emit-determinism` out of the box.
                "org.tatrman:ttr-emit-kestra" to "kestra",
                // PL-P5.S4 — Airflow 3 ships in-tree; its native binding is exercised by the standalone corpus.
                "org.tatrman:ttr-emit-airflow3" to "airflow3",
            )
    }
}

/**
 * `ttrp eval` (T7.2.5): score host-produced candidates against the assist eval corpus (C4-e).
 * The toolchain NEVER generates candidates (no LLM in the compiler, P2/C4-d-ii) — it only scores.
 * Named `eval` (top-level), not `conform eval`, to avoid restructuring the existing `conform <file>`
 * leaf; the substance (corpus/comparator/runner) is unchanged. Compiles both candidate and expected
 * through the front-half (`TtrpPipeline`), so it needs a resolvable project world like `ttrp check`.
 */
class EvalCommand : CliktCommand(name = "eval") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Score host-produced candidates against the assist eval corpus (C4-e); the toolchain never generates them."

    private val corpus by option("--corpus", help = "corpus dir (corpus.toml + fixtures/)").required()
    private val candidates by option("--candidates", help = "dir of <corpus-id>.ttrp candidate files").required()
    private val report by option("--report", help = "write the verdict report (TOML) here")

    override fun run() {
        val loaded = EvalCorpus.load(Path.of(corpus).toAbsolutePath())
        val candDir = Path.of(candidates).toAbsolutePath()
        val manifestResult = TtrpManifestReader.resolve(candDir)
        val pipeline = TtrpPipeline(manifestResult.manifest, manifestResult.manifest.modelsRoot())
        val runner =
            EvalRunner { src, fn ->
                val plan = pipeline.plan(src, fn)
                EvalRunner.CompileOutcome(
                    plan.graph,
                    plan.diagnostics.filter { it.severity == Severity.ERROR }.map { it.id.id },
                )
            }
        val candMap =
            loaded.entries
                .mapNotNull { e ->
                    val f =
                        listOf("${e.id}.ttrp", "${e.id}.ttrb")
                            .map { candDir.resolve(it) }
                            .firstOrNull { Files.isRegularFile(it) }
                    f?.let { e.id to Files.readString(it) }
                }.toMap()
        val rep = runner.score(loaded, candMap)
        echo("eval: pass=${rep.pass} shape-mismatch=${rep.shapeMismatch} invalid=${rep.invalid}")
        rep.entries.forEach { echo("  ${it.id}: ${verdictLabel(it.verdict)}") }
        report?.let { Files.writeString(Path.of(it), reportToml(rep)) }
        throw ProgramResult(if (rep.invalid == 0 && rep.shapeMismatch == 0) 0 else 1)
    }

    private fun verdictLabel(v: EvalComparator.Verdict): String =
        when (v) {
            is EvalComparator.Verdict.Pass -> "pass"
            is EvalComparator.Verdict.ShapeMismatch -> "shape-mismatch (${v.diff})"
            is EvalComparator.Verdict.Invalid -> "invalid (${v.diagnostics.joinToString(",")})"
        }

    private fun reportToml(rep: EvalRunner.Report): String =
        buildString {
            append("pass = ${rep.pass}\n")
            append("shapeMismatch = ${rep.shapeMismatch}\n")
            append("invalid = ${rep.invalid}\n")
            rep.entries.forEach {
                val verdict = verdictLabel(it.verdict).substringBefore(" ")
                append("\n[[entry]]\nid = \"${it.id}\"\nverdict = \"$verdict\"\n")
            }
        }
}

/**
 * Component-test entry point (no process spawn / no clikt): the front-half + explain dispatch used
 * by the Phase-1/2 CLI specs. The clikt commands above delegate here for explain/check.
 */
object TtrpCli {
    fun dispatch(
        args: Array<String>,
        out: (String) -> Unit,
    ): Int {
        if (args.isEmpty() || args[0] !in setOf("check", "explain")) {
            out("usage: ttrp <check|explain> <file>.ttrp")
            return 2
        }
        if (args.size < 2) {
            out("ttrp ${args[0]}: missing <file>.ttrp")
            return 2
        }
        return when (args[0]) {
            "explain" -> runExplain(Path.of(args[1]), out)
            else -> runCheck(Path.of(args[1]), out)
        }
    }

    fun runExplain(
        file: Path,
        out: (String) -> Unit,
        modelsRootOverride: Path? = null,
    ): Int {
        val abs = file.toAbsolutePath()
        if (!Files.isRegularFile(abs)) {
            out("ttrp explain: no such file: $file")
            return 2
        }
        val startDir = abs.parent ?: abs
        val manifestResult = TtrpManifestReader.resolve(startDir)
        val modelsRoot = modelsRootOverride ?: manifestResult.manifest.modelsRoot()
        val output = TtrpPipeline(manifestResult.manifest, modelsRoot).explain(Files.readString(abs), abs.toString())
        if (!output.ok) {
            for (d in output.diagnostics
                .filter { it.severity == Severity.ERROR }
                .sortedWith(compareBy({ it.location.line }, { it.location.column }))) {
                out(d.render())
            }
            return 1
        }
        out(output.text.trimEnd())
        return 0
    }

    fun runCheck(
        file: Path,
        out: (String) -> Unit,
        modelsRootOverride: Path? = null,
    ): Int {
        val abs = file.toAbsolutePath()
        if (!Files.isRegularFile(abs)) {
            out("ttrp check: no such file: $file")
            return 2
        }
        val startDir = abs.parent ?: abs
        val manifestResult = TtrpManifestReader.resolve(startDir)
        val modelsRoot = modelsRootOverride ?: manifestResult.manifest.modelsRoot()
        val checker = TtrpChecker(manifestResult.manifest, modelsRoot)
        val report =
            checker.check(
                source = Files.readString(abs),
                fileName = abs.toString(),
                manifestDiagnostics = manifestResult.diagnostics,
            )
        for (d in report.diagnostics.sortedWith(compareBy({ it.location.line }, { it.location.column }))) {
            out(d.render())
            d.suggestedAlternative?.let { out("  ↳ suggested: $it") }
        }
        return if (report.errors.any { it.severity == Severity.ERROR }) 1 else 0
    }
}
