// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.emit.recipe.RecipeCatalogEmitter
import org.tatrman.ttrp.emit.recipe.RecipeActionDependencies
import org.tatrman.ttrp.emit.recipe.RecipeFingerprints
import org.tatrman.ttrp.emit.recipe.RecipeStatement
import org.tatrman.ttrp.emit.recipe.RecipeStatements
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.capability.BoundWorld
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.graph.recipe.ContextClass
import org.tatrman.ttrp.graph.recipe.RecipeModel
import org.tatrman.ttrp.graph.recipe.SwitchLowering
import org.tatrman.ttrp.resolve.AutomationScope
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Assembles a recipe's `<recipe>.bundle/` (AU-core-P2 · S2.3 T5; contracts §5.2) — what the Server's `/refresh` loads
 * and its runner executes, island by island, through the door:
 *
 * ```
 * manifest.json                       v3 (schemaVersion 3, ttrpVersion 2, the `recipe` object) — authoritative
 * islands/<island>.<port>.sql         one statement per output, SQL over E-R (`schema: er`) — the decision's routes
 *                                     `islands/<decision>.<route>.sql`, their detail `…<route>.<context>.sql`
 * catalog/<recipe>.ttrm               the generated `def recipe` (model automation)
 * catalog/<recipe>_verdict.ttrm       the verdict entities (model er)
 * ```
 *
 * No launcher (the Server runs it), no transfers (one engine, the door), no README before P3 (S3.2 renders it).
 * Every file's sha256 is in `files`; `manifest.json` is not (it cannot hold its own hash).
 */
class RecipeBundleAssembler(
    private val toolchainVersion: String = "0.0.0-dev",
    private val writeArtifacts: Boolean = true,
) {
    fun assemble(
        plan: TtrpPipeline.PlanResult,
        program: String,
        outDir: Path,
        statements: List<RecipeStatement> = RecipeStatements.plan(plan),
        recipeDir: Path? = null,
        readmeLabels: org.tatrman.ttrp.doc.ReadmeLabels =
            org.tatrman.ttrp.doc
                .ReadmeLabels(),
        documentTitle: String? = null,
        language: String = "en",
    ): BundleAssembler.BundleResult {
        val model = requireNotNull(plan.recipe) { "$program holds no recipe" }
        val bound = requireNotNull(plan.bound)
        val graph = requireNotNull(plan.graph)
        val exec = requireNotNull(plan.exec)
        val bundleDir = outDir.resolve(model.name + ".bundle")
        if (writeArtifacts) {
            Files.createDirectories(bundleDir.resolve("islands"))
            Files.createDirectories(bundleDir.resolve("catalog"))
        }
        val files = sortedMapOf<String, String>()

        // --- statements, one per output, grouped by island ---
        val parts =
            statements
                .flatMap { it.parts }
                .groupBy { it.name }
                .map { (_, copies) ->
                    val p = copies.first()
                    require(
                        copies.all { it.storage == p.storage && it.sql == p.sql },
                    ) { "part hash collision: ${p.name}" }
                    write(bundleDir, p.file, p.sql + "\n", files)
                    RecipePartEntry(
                        p.name,
                        p.storage,
                        p.engine,
                        p.file,
                        files.getValue(p.file),
                        p.columns.map { OutputColumn(it.name, it.type) },
                        p.params,
                        model.keyColumns.all { k -> p.columns.any { it.name == k } } &&
                            statements.filter { s -> s.parts.any { it.name == p.name } }.all { keyed(model, it) },
                    )
                }.sortedBy { it.name }
        val islands =
            exec.islands.mapNotNull { island ->
                val own = statements.filter { it.island == island.name }
                if (own.isEmpty()) return@mapNotNull null
                val outputs =
                    own.map { s ->
                        write(bundleDir, s.file, s.sql + "\n", files)
                        IslandOutput(
                            port = s.port,
                            file = s.file,
                            sha256 = files.getValue(s.file),
                            columns = s.columns.map { OutputColumn(it.name, it.type) },
                            params = s.params.ifEmpty { null },
                            keyed = keyed(model, s),
                            storage = s.storage.takeIf { s.parts.isEmpty() },
                            combine =
                                s.parts.takeIf { it.isNotEmpty() }?.let { ps ->
                                    RecipeCombine(
                                        s.file,
                                        files.getValue(s.file),
                                        ps.map { it.name }.distinct(),
                                        s.params,
                                    )
                                },
                        )
                    }
                IslandEntry(
                    name = island.name,
                    engine = if (own.first().parts.isNotEmpty()) "duckdb" else island.engine,
                    executor = NATIVE,
                    invocation = if (own.first().parts.isNotEmpty()) "duckdb" else SQL_TEXT,
                    file = own.first().file,
                    sha256 = files.getValue(own.first().file),
                    connections =
                        own
                            .flatMap { s ->
                                if (s.parts.isEmpty()) {
                                    val engine =
                                        bound.storages.firstOrNull { it.qname.name == s.storage }?.via ?: island.engine
                                    listOf(connEnv(engine))
                                } else {
                                    s.parts.map { connEnv(it.engine) }
                                }
                            }.distinct()
                            .sorted(),
                    params = outputs.flatMap { it.params.orEmpty() }.distinct().ifEmpty { null },
                    outputs = outputs,
                    schema = if (own.first().parts.isNotEmpty()) "db" else ER,
                )
            }

        // --- the recipe object: fingerprints, the verdict schema, the generated catalog ---
        val actions =
            checkNotNull(RecipeActionDependencies.wiredActions(plan, model)) {
                "recipe `${model.name}` has no checked automation scope"
            }
        val captured = RecipeFingerprints.capture(plan, model, model.protocol, model.asOf, actions)
        val fingerprints = captured.fingerprints
        val keyTypes = keyTypes(model, statements)
        val verdictColumns =
            listOf(OutputColumn("run", "text"), OutputColumn("as_of", "timestamp")) +
                model.keyColumns.zip(keyTypes).map { (k, t) -> OutputColumn(k, t) } +
                listOf(
                    OutputColumn(SwitchLowering.ROUTE, "text"),
                    OutputColumn(SwitchLowering.VERDICT_REASON, "text"),
                    OutputColumn("parameters_fp", "text"),
                    OutputColumn("run_as", "text"),
                    OutputColumn("written_at", "timestamp"),
                )
        val detailExtra =
            statements
                .filter { s ->
                    model.routes.any { r -> r.detail.any { s.port == SwitchLowering.detailPort(r.name, it) } }
                }.flatMap { s -> s.columns.map { it.name } }
                // the detail entity's own columns win: a context's `run` / `row_no` is not stored twice (review-119 m4)
                .filter { it !in model.keyColumns && it !in DETAIL_OWN }
                .distinct()
                .sorted()
        val detailColumns =
            listOf(OutputColumn("run", "text")) +
                model.keyColumns.zip(keyTypes).map { (k, t) -> OutputColumn(k, t) } +
                listOf(
                    OutputColumn(SwitchLowering.ROUTE, "text"),
                    OutputColumn(SwitchLowering.CONTEXT, "text"),
                    OutputColumn("row_no", "int"),
                ) +
                detailExtra.map { OutputColumn(it, "text") }
        val catalog =
            RecipeCatalogEmitter.emit(
                model,
                keyTypes,
                verdictColumns,
                detailColumns,
                model.document,
                fingerprints,
            )
        write(bundleDir, catalog.recipeFile, catalog.recipeText, files)
        write(bundleDir, catalog.verdictFile, catalog.verdictText, files)

        val diagnostics = mutableListOf<TtrpDiagnostic>()

        fun fixtureDiagnostic(
            message: String,
            severity: Severity,
        ) = TtrpDiagnostic(
            TtrpDiagnosticId.REC_080,
            severity,
            message,
            SourceLocation.UNKNOWN.copy(file = program),
        )
        val testsDir = recipeDir?.resolve("tests")
        if (recipeDir == null) {
            diagnostics +=
                fixtureDiagnostic("golden fixtures were not collected: recipe has no file location", Severity.INFO)
        }
        val tests =
            if (testsDir != null && Files.isDirectory(testsDir)) {
                Files.list(testsDir).use { paths ->
                    paths
                        .filter {
                            val n = it.fileName.toString()
                            Files.isRegularFile(it) &&
                                !n.startsWith(".") &&
                                n.substringAfterLast('.', missingDelimiterValue = "") in setOf("yaml", "yml")
                        }.sorted()
                        .map { path ->
                            val text =
                                try {
                                    Charsets.UTF_8
                                        .newDecoder()
                                        .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
                                        .toString()
                                } catch (_: CharacterCodingException) {
                                    throw BundleAssembler.BundleRefused(
                                        listOf(fixtureDiagnostic("golden fixture is not UTF-8: $path", Severity.ERROR)),
                                    )
                                }
                            val relative = "tests/${path.fileName}"
                            write(bundleDir, relative, text, files)
                            relative
                        }.toList()
                }
            } else {
                emptyList()
            }

        val recipe =
            RecipeManifest(
                name = model.name,
                key = model.keyAttributes,
                asOf = model.asOf,
                document = model.document,
                parameters = model.parameterDecls.map { RecipeParameter(it.name, it.type ?: "", it.value) },
                bindings =
                    listOf(RecipeBinding(RecipeStatements.AS_OF, "datetime", "run")) +
                        model.derivedParams.map {
                            RecipeBinding(
                                it.name,
                                "datetime",
                                "derived",
                                RecipeDerive(it.base, it.op, it.amount, it.unit),
                            )
                        },
                roles =
                    RecipeRoles(
                        candidates = model.candidates,
                        contexts = model.contexts.map { RecipeContext(it.name, it.contextClass.spelling, it.kind) },
                        decision = model.decision,
                        routes = model.routes.map { RecipeRoute(it.name, it.because, it.detail, it.turnFed) },
                        switchMode = model.switchMode,
                        elseRoute = model.routes.firstOrNull { it.isElse }?.name ?: "none".takeIf { model.elseNone },
                    ),
                wiring = model.wirings.map { RecipeWiring(it.routes, it.action, it.columns, it.whenText) },
                kinds = model.kinds,
                mode = model.mode.spelling,
                windows =
                    model.contexts.mapNotNull { c ->
                        c.window?.let { w -> RecipeWindow(c.name, w.by.map { it.text }, over(w)) }
                    },
                verdictSchema =
                    VerdictSchema(
                        entity = "${model.name}_verdict",
                        columns = verdictColumns,
                        detail = "${model.name}_verdict_detail",
                        current = "${model.name}_verdict_current",
                    ),
                objectsRead = (objectsRead(graph, actions) + model.actionObjectsRead).distinct().sorted(),
                fingerprints = fingerprints,
                protocol = model.protocol,
                parts = parts.ifEmpty { null },
                tests = tests.ifEmpty { null },
                approvalDependencies = captured.approvalDependencies,
            )

        val readme =
            org.tatrman.ttrp.doc.ReadmeRenderer
                .render(recipe, readmeLabels, documentTitle, language)
        write(bundleDir, "README.md", readme, files)
        val manifest =
            RunManifest(
                schemaVersion = 3,
                ttrpVersion = 2,
                toolchain = "org.tatrman:ttrp:$toolchainVersion",
                program = program.substringAfterLast('/'),
                world = WorldRef(worldQname(bound), WorldFingerprint.of(bound.world)),
                islands = islands,
                transfers = emptyList(),
                waves =
                    exec.waves.map { wave ->
                        wave.mapNotNull { id -> exec.islands.firstOrNull { it.id == id }?.name }
                    },
                connections = islands.flatMap { it.connections }.distinct().sorted(),
                displays = emptyList(),
                recipe = recipe,
                files = files.toMap(),
            )
        if (writeArtifacts) Files.writeString(bundleDir.resolve("manifest.json"), manifest.toJson())
        // no launcher: the Server runs the statements
        return BundleAssembler.BundleResult(bundleDir, manifest, null, diagnostics, readme)
    }

    /**
     * A recipe output carries the key (AU-C-3: the runner filters it on the run's key) when its rows are per candidate
     * — the candidates', a per-candidate context's, every route and every detail — and it has every key column by
     * name (the checker refuses a per-candidate output without them, REC-004). A lookup or a set-level context's
     * rows are not keyed.
     */
    private fun keyed(
        model: RecipeModel,
        s: RecipeStatement,
    ): Boolean {
        val carries = model.keyColumns.all { k -> s.columns.any { it.name == k } }
        val context = model.contexts.firstOrNull { it.name == s.island }
        return carries && (context == null || context.contextClass == ContextClass.PER_CANDIDATE)
    }

    /** The key columns' types, from the candidates' statement (the key attribute's type), else text. */
    private fun keyTypes(
        model: RecipeModel,
        statements: List<RecipeStatement>,
    ): List<String> {
        val candidates = statements.firstOrNull { it.island == model.candidates }
        return model.keyColumns.map { k -> catalogType(candidates?.columns?.firstOrNull { it.name == k }?.type) }
    }

    /** An emitted column type as a TTR-M attribute type. */
    private fun catalogType(t: String?): String =
        when (t?.substringBefore('(')?.lowercase()) {
            null, "text", "varchar", "char", "string" -> "text"
            "int", "integer", "bigint", "smallint" -> "int"
            "decimal", "numeric", "float", "double" -> "decimal"
            "date" -> "date"
            "timestamp", "datetime" -> "timestamp"
            "bool", "boolean" -> "bool"
            else -> "text"
        }

    /**
     * The model objects the recipe reads (D-94's out-of-scope-read check): every statement's load
     * (an entity as `er.entity.<name>`, a table by its qname), plus `er.entity.obligation` when a
     * directly wired action [AutomationScope.needsObligationLookup]. Checked action-time calendar reads
     * from [RecipeModel.actionObjectsRead] are merged into this list at assembly.
     */
    private fun objectsRead(
        graph: TtrpGraph,
        actions: Map<String, AutomationAction>,
    ): List<String> {
        val statementReads =
            graph.nodes.values
                .filterIsInstance<Load>()
                .mapNotNull { l ->
                    val src = graph.loadSources[l.id] ?: return@mapNotNull null
                    if (src.objectQname.startsWith(
                            "er.",
                        )
                    ) {
                        "er.entity.${src.objectQname.substringAfterLast('.')}"
                    } else {
                        src.objectQname
                    }
                }
        val needsObligationLookup = actions.values.any(AutomationScope::needsObligationLookup)
        val actionReads = if (needsObligationLookup) listOf("er.entity.obligation") else emptyList()
        return (statementReads + actionReads).distinct().sorted()
    }

    private fun over(w: org.tatrman.ttrp.ast.Window): String =
        when (val o = w.over) {
            null -> ""
            is org.tatrman.ttrp.ast.WindowOver.Calendar -> o.unit
            is org.tatrman.ttrp.ast.WindowOver.Rows -> "rows ${o.count}"
            is org.tatrman.ttrp.ast.WindowOver.Gap -> "gap ${o.duration.amount} ${o.duration.unit.spelling}"
        }

    private fun write(
        dir: Path,
        rel: String,
        text: String,
        files: MutableMap<String, String>,
    ) {
        val p = dir.resolve(rel)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (writeArtifacts) {
            Files.createDirectories(p.parent)
            Files.write(p, bytes)
        }
        files[rel] =
            "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun connEnv(engine: String): String = "TTR_CONN_" + engine.uppercase().replace(Regex("[^A-Z0-9]"), "_")

    private fun worldQname(bound: BoundWorld): String =
        bound.world.qname.let { q ->
            val prefix = q.`package`.ifBlank { q.namespace }
            if (prefix.isBlank()) q.name else "$prefix.${q.name}"
        }

    private companion object {
        /** The columns `<recipe>_verdict_detail` writes itself (besides the key). */
        val DETAIL_OWN = setOf("run", SwitchLowering.ROUTE, SwitchLowering.CONTEXT, "row_no")
        const val NATIVE = "native"
        const val SQL_TEXT = "sql-text"
        const val ER = "er"
    }
}
