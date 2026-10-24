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
import com.github.ajalt.clikt.parameters.options.convert
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
    private val out by option("--out")
    private val check by option("--check", help = "verify derived document artifacts without writing").flag()
    private val deriveOnly by option(
        "--derive-only",
        help = "Markdown only: derive/check canonical and provenance without compiling a bundle",
    ).flag()
    private val language by option(
        "--lang",
        help = "README language: en or cs",
    ).convert(
        metavar = "(en|cs)",
        completionCandidates =
            com.github.ajalt.clikt.completion.CompletionCandidates
                .Fixed(setOf("en", "cs")),
    ) {
        // Clikt 5.0.3 choice() exits 1; the build contract requires usage exit 2.
        if (it !in
            setOf("en", "cs")
        ) {
            throw com.github.ajalt.clikt.core
                .UsageError("--lang must be en or cs", statusCode = 2)
        }
        it
    }.default("en")

    // AU-core-P2 (contracts §9): the manifest + the compile's diagnostics as JSON on stdout — the hartland suites read it.
    private val json by option("--json", help = "print the bundle's manifest and the diagnostics as JSON").flag()

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
        withBuildErrors {
            val manifestResult = TtrpManifestReader.resolve(abs.parent ?: abs)
            EmitPluginLoader.signaturePolicy(manifestResult.manifest.requireSignedPlugins) {
                echo("ttrp build: $it", err = true)
            }
            if (frozen || offline) {
                echo(
                    "ttrp build: compile mode = ${if (frozen) "frozen" else "offline"} (seam declared, S3 wires it)",
                    err = true,
                )
            }
            val members = connected?.let { ConnectedMemberCatalog.forUrl(it) }
            if (abs.fileName.toString().endsWith(".md")) {
                LiterateBuild.run(
                    abs,
                    out?.let { Path.of(it).toAbsolutePath() },
                    check,
                    language,
                    json,
                    deriveOnly = deriveOnly,
                    memberCatalog = members,
                    warning = { echo(it, err = true) },
                ) { echo(it) }
                return@withBuildErrors
            }
            if (deriveOnly) {
                echo("ttrp build: --derive-only requires a Markdown document", err = true)
                throw ProgramResult(2)
            }
            if (check) {
                val project = manifestResult.manifest
                val plan =
                    TtrpPipeline(project, project.modelsRoot(), memberCatalog = members)
                        .plan(Files.readString(abs), abs.toString())
                val diagnostics =
                    plan.diagnostics +
                        if (plan.ok &&
                            plan.recipe != null
                        ) {
                            org.tatrman.ttrp.bundle.RecipeBundleCheck
                                .inspect(plan)
                                .diagnostics
                        } else {
                            emptyList()
                        }
                if (json) echo(BuildJson.failure(diagnostics)) else diagnostics.forEach { echo(it.render()) }
                if (diagnostics.any { it.severity == Severity.ERROR }) throw ProgramResult(1)
                return@withBuildErrors
            }
            val result =
                BundleAssembler(ToolchainVersion.current).build(
                    source = Files.readString(abs),
                    recipeDir = abs.parent,
                    fileName = abs.toString(),
                    pipelineManifest = manifestResult.manifest,
                    modelsRoot = manifestResult.manifest.modelsRoot(),
                    outDir = Path.of(out ?: "."),
                    language = language,
                    memberCatalog = members,
                )
            if (json) echo(BuildJson.success(result)) else echo(result.dir.toString())
        }
    }

    /** The same failure contract applies to Markdown and canonical sources, including read-only checks. */
    private fun withBuildErrors(action: () -> Unit) {
        try {
            action()
        } catch (e: org.tatrman.ttr.md.resolve.CatalogUnavailable) {
            if (json) echo(BuildJson.failure(emptyList()))
            echo("ttrp build: ${e.message} — start the server, or omit --connected to compile disconnected", err = true)
            throw ProgramResult(1)
        } catch (e: BundleAssembler.BundleRefused) {
            if (json) echo(BuildJson.failure(e.diagnostics))
            echo(e.message ?: "build failed", err = true)
            throw ProgramResult(1)
        } catch (e: IllegalArgumentException) {
            if (json) echo(BuildJson.failure(emptyList()))
            echo(e.message ?: "build failed", err = true)
            throw ProgramResult(1)
        } catch (e: org.tatrman.ttrp.emit.TtrpEmitException) {
            if (json) echo(BuildJson.failure(e))
            echo(e.message ?: "build failed", err = true)
            e.suggestedAlternative?.let { echo("  ↳ suggested: $it", err = true) }
            throw ProgramResult(1)
        }
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
 * pass · 1 comparison failure · 2 invocation/pre-flight failure (mirrors the run contract). A recipe
 * (AU-core-P2 · S2.4) conforms across `--engines` over `--fixture` scripts at `--as-of` ([RecipeConformCli]).
 */
class ConformCommand : CliktCommand(name = "conform") {
    private val file by argument()
    private val tolerance by option("--tolerance", help = "per-column float64 tolerance, <col>=<eps>").multiple()
    private val engines by option(
        "--engines",
        help = "a recipe's engines, comma-separated: pg, duckdb, polars (Polars requires unscoped fixtures)",
    ).default(
        "pg,duckdb",
    )
    private val fixture by option(
        "--fixture",
        help = "SQL fixture, or <storage>=<sql> for cross-storage recipes (repeatable)",
    ).multiple()
    private val asOf by option("--as-of", help = "a recipe's :as_of, an ISO local date-time (2026-11-02T08:00)")
    private val engineDelta by option(
        "--engine-delta",
        help = "a recipe column one engine computes in DECIMAL and another in DOUBLE, [<output>/]<col>=<eps>",
    ).multiple()

    override fun run() {
        val abs = Path.of(file).toAbsolutePath()
        if (!Files.isRegularFile(abs)) {
            echo("ttrp conform: no such file: $file", err = true)
            throw ProgramResult(2)
        }
        val manifestResult = TtrpManifestReader.resolve(abs.parent ?: abs)
        val source = Files.readString(abs)
        val planned =
            TtrpPipeline(
                manifestResult.manifest,
                manifestResult.manifest.modelsRoot(),
            ).plan(source, abs.toString())
        // a recipe with errors reports them — never the placement-variant path (review-119 m11)
        if (!planned.ok && (planned.recipe != null || RECIPE_BLOCK.containsMatchIn(source))) {
            planned.diagnostics.filter { it.severity.name == "ERROR" }.forEach { echo(it.render(), err = true) }
            throw ProgramResult(2)
        }
        // … and so does one whose islands do not compile (`ttrp check`'s REC-080, review-119 M5)
        if (planned.ok && planned.recipe != null) {
            val refused =
                org.tatrman.ttrp.bundle.RecipeBundleCheck
                    .check(planned)
            if (refused.isNotEmpty()) {
                refused.forEach { echo(it.render(), err = true) }
                throw ProgramResult(2)
            }
        }
        val recipePlan = planned.takeIf { it.ok && it.recipe != null }
        if (recipePlan != null) {
            val instant =
                asOf?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() } ?: run {
                    echo("ttrp conform: a recipe runs at an instant — pass --as-of 2026-11-02T08:00", err = true)
                    throw ProgramResult(2)
                }
            throw ProgramResult(
                RecipeConformCli.run(
                    recipePlan,
                    engines.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    fixture.filterNot { RecipeConformCli.scopedFixture(it) }.map { Path.of(it).toAbsolutePath() },
                    instant,
                    tolerances(),
                    System.getenv().filterKeys { it.startsWith("TTR_CONN_") },
                    numericDeltas = pairs(engineDelta),
                    storageFixtures =
                        fixture.filter { RecipeConformCli.scopedFixture(it) }.groupBy(
                            { it.substringBefore('=') },
                            { Path.of(it.substringAfter('=')).toAbsolutePath() },
                        ),
                ) { echo(it) },
            )
        }
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
        val env = System.getenv().filterKeys { it.startsWith("TTR_CONN_") }
        val outcome = ConformRunner(BundleInvoker(env), tolerances = tolerances()).run(variants)
        echo(outcome.summary())
        throw ProgramResult(outcome.exitCode)
    }

    private fun tolerances(): Map<String, Double> = pairs(tolerance)

    private fun pairs(specs: List<String>): Map<String, Double> =
        specs
            .mapNotNull { spec ->
                val (c, e) = spec.split("=", limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
                if (c != null && e?.toDoubleOrNull() != null) c to e.toDouble() else null
            }.toMap()

    private companion object {
        val RECIPE_BLOCK = Regex("""(?m)^\s*recipe\s*\{""")
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
                        recipeDir = abs.parent,
                        fileName = abs.toString(),
                        pipelineManifest = manifest.manifest,
                        modelsRoot = manifest.manifest.modelsRoot(),
                        outDir = tmp,
                    ).emitRequest ?: run {
                    echo("ttrp emit-determinism: ${abs.fileName} is a recipe — its bundle has no launcher", err = true)
                    throw ProgramResult(2)
                }
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
        // A recipe document explains as its skeleton (AU-core-P0, from the parse) and the checker's section (AU-core-P1:
        // the mode verdict, each context's class, the routes, every finding). The compiled islands come with P2.
        val parsed =
            org.tatrman.ttrp.parser.TtrpParser
                .parseFile(abs)
        val recipe =
            parsed.document.statements
                .filterIsInstance<org.tatrman.ttrp.ast.RecipeDecl>()
                .firstOrNull()
        val startDir = abs.parent ?: abs
        val manifestResult = TtrpManifestReader.resolve(startDir)
        val modelsRoot = modelsRootOverride ?: manifestResult.manifest.modelsRoot()
        if (recipe != null) {
            val errors = parsed.diagnostics.filter { it.severity == Severity.ERROR }
            if (errors.isNotEmpty()) {
                errors.sortedWith(compareBy({ it.location.line }, { it.location.column })).forEach { out(it.render()) }
                return 1
            }
            out(
                org.tatrman.ttrp.recipe.RecipeExplain
                    .render(recipe),
            )
            val (model, findings) = checkRecipe(abs, manifestResult.manifest, modelsRoot)
            if (model != null) {
                out(
                    org.tatrman.ttrp.graph.recipe.RecipeCheckExplain
                        .render(model),
                )
                // AU-core-P2 (S2.3 T6): what the build writes — the statements, the values the run binds, the fingerprints.
                if (findings.none { it.severity == Severity.ERROR }) {
                    val plan =
                        TtrpPipeline(
                            manifestResult.manifest,
                            modelsRoot,
                        ).plan(Files.readString(abs), abs.toString())
                    if (plan.ok) out(recipeBundleSection(plan))
                }
            } else {
                out("checker · not run — the front half reported errors:")
            }
            for (d in findings) {
                out(d.render())
                d.suggestedAlternative?.let { out("  ↳ suggested: $it") }
            }
            return if (findings.any { it.severity == Severity.ERROR }) 1 else 0
        }
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

    /** The bundle section of a recipe's `ttrp explain` (AU-core-P2, S2.3 T6): statements, bindings, fingerprints. */
    private fun recipeBundleSection(plan: TtrpPipeline.PlanResult): String {
        val model = plan.recipe!!
        val statements =
            org.tatrman.ttrp.emit.recipe.RecipeStatements
                .plan(plan)
        val fp =
            org.tatrman.ttrp.emit.recipe.RecipeFingerprints
                .of(plan, model, model.protocol, model.asOf)
        val bound =
            listOf("as_of") + model.derivedParams.map { "${it.name} (${it.base} ${it.op} ${it.amount} ${it.unit})" }
        return buildString {
            appendLine("bundle · ${statements.size} statements (SQL over E-R) · verdict ${model.name}_verdict")
            statements.flatMap { it.parts }.distinctBy { it.name }.sortedBy { it.name }.forEach { p ->
                appendLine("  part ${p.name}: storage ${p.storage} · engine ${p.engine} · ${p.file}")
            }
            statements.filter { it.parts.isNotEmpty() }.forEach { s ->
                appendLine(
                    "  combine ${s.island}.${s.port}: DuckDB · ${s.file} · parts ${s.parts.joinToString { it.name }}",
                )
            }
            appendLine("  the run binds: ${bound.joinToString(", ")}")
            appendLine("  fingerprints: text ${fp.text} · values ${fp.values} · labels ${fp.labels}")
        }.trimEnd()
    }

    /**
     * The recipe checker over [file] (AU-core-P1): what it learned, and every finding in source order — with what the
     * build would refuse (an island that does not compile, review-119 M5).
     */
    private fun checkRecipe(
        file: Path,
        manifest: org.tatrman.ttrp.project.TtrpManifest,
        modelsRoot: Path,
    ): Pair<org.tatrman.ttrp.graph.recipe.RecipeModel?, List<org.tatrman.ttrp.diagnostics.TtrpDiagnostic>> {
        val plan = TtrpPipeline(manifest, modelsRoot).plan(Files.readString(file), file.toString())
        val refused =
            if (plan.ok) {
                org.tatrman.ttrp.bundle.RecipeBundleCheck
                    .check(plan)
            } else {
                emptyList()
            }
        val findings = (plan.diagnostics + refused).sortedWith(compareBy({ it.location.line }, { it.location.column }))
        return plan.recipe to findings
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
        // A recipe is checked through the recipe checker (AU-core-P1), which runs over the built graph.
        val source = Files.readString(abs)
        if (source.contains("recipe") &&
            org.tatrman.ttrp.parser.TtrpParser
                .parseString(source, abs.toString())
                .document.statements
                .any { it is org.tatrman.ttrp.ast.RecipeDecl }
        ) {
            val findings = manifestResult.diagnostics + checkRecipe(abs, manifestResult.manifest, modelsRoot).second
            for (d in findings) {
                out(d.render())
                d.suggestedAlternative?.let { out("  ↳ suggested: $it") }
            }
            return if (findings.any { it.severity == Severity.ERROR }) 1 else 0
        }
        val checker = TtrpChecker(manifestResult.manifest, modelsRoot)
        val report =
            checker.check(
                source = source,
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
