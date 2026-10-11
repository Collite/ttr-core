// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import org.tatrman.ttr.semantics.md.MdBindings
import org.tatrman.ttr.semantics.md.MdModel
import org.tatrman.ttrp.emit.spi.CoreOwnedPaths
import org.tatrman.ttrp.emit.spi.EmitDisplay
import org.tatrman.ttrp.emit.spi.EmitIsland
import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.EmitTransfer
import org.tatrman.ttrp.emit.spi.IslandPayload
import org.tatrman.ttrp.emit.spi.OrchestrationGraph
import org.tatrman.ttrp.emit.spi.ProgramMeta
import org.tatrman.ttrp.emit.spi.ResolvedManifest
import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.emit.polars.PolarsGraphEmitter
import org.tatrman.ttrp.emit.polars.PolarsIslandEmitter
import org.tatrman.ttrp.emit.sql.MdSourceIslandEmitter
import org.tatrman.ttrp.emit.sql.SqlIslandEmitter
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.capability.BoundWorld
import org.tatrman.ttrp.graph.collapse.ExecutionGraph
import org.tatrman.ttrp.graph.collapse.Island
import org.tatrman.ttrp.graph.model.Display
import org.tatrman.ttrp.graph.model.DisplayLeaves
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortKind
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.project.CompileRecord
import org.tatrman.ttrp.project.RecordStaleness
import org.tatrman.ttrp.project.StatsEntry
import org.tatrman.ttrp.project.TtrLock
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Assembles `<program>.bundle/` (contracts §5) from a `.ttrp` source: emits island payloads
 * (SQL fragments verbatim / Polars scripts), transfer scripts, `manifest.json` (with the semantic
 * world fingerprint), and `run.sh`. Everything is content-generated — no live engine is touched.
 *
 * **v1 cross-engine execution model (recorded for review):** a PG→Polars transfer reads the source
 * SQL island's result directly via ADBC — the transfer's query embeds the island SQL as a subquery
 * (`SELECT * FROM (<island-sql>) AS _ttrp_src`) — staging it to Arrow IPC. The island's `.sql`
 * file is retained for provenance and psql-direct inspection. Connection env names are derived
 * `TTR_CONN_<ENGINE>` (uppercased engine-instance name).
 */
class BundleAssembler(
    private val toolchainVersion: String = "0.0.0-dev",
    // PL-P5.S1 — the orchestration launcher (`run.sh`) is emitted by a §8 emit plugin, resolved by target. The
    // built-in bash plugin (`org.tatrman:ttr-emit-bash`, on the classpath) is the default; a caller overrides
    // to target Kestra/Airflow (PL-P5.S3/S4). Core still writes manifest.json / islands / transfers / schemas.
    private val emitPlugin: TtrEmitPlugin = EmitPluginLoader.builtin("bash"),
) {
    data class BundleResult(
        val dir: Path,
        val manifest: RunManifest,
        // PL-P5.S2 — the §8 EmitRequest handed to the emit plugin for this bundle. Exposed so the H-6
        // determinism kit (`ttrp emit-determinism`) can re-emit it through the plugin and byte-compare.
        val emitRequest: EmitRequest,
    )

    /**
     * The connected-compile inputs for the compile record (§5). Null ⇒ a standalone (LocalFs) compile,
     * whose record carries `mode: standalone` and no lock/snapshot/plugins.
     */
    class CompileRecordSpec(
        private val lockBytes: ByteArray,
        private val lock: TtrLock,
        private val statsUsed: List<StatsEntry>,
        private val staleness: RecordStaleness,
    ) {
        fun build(
            toolchain: String,
            program: String,
            worldFingerprint: String,
            objectsRead: List<String>,
        ): CompileRecord =
            CompileRecord.connected(
                toolchain = toolchain,
                program = program,
                worldFingerprint = worldFingerprint,
                lockBytes = lockBytes,
                lock = lock,
                statsUsed = statsUsed,
                staleness = staleness,
                objectsRead = objectsRead,
            )
    }

    fun build(
        source: String,
        fileName: String,
        pipelineManifest: TtrpManifest,
        modelsRoot: Path,
        outDir: Path,
        targetOverrides: Map<String, String> = emptyMap(),
        compileRecord: CompileRecordSpec? = null,
        // Connected-mode member catalog (S6-B): null ⇒ disconnected (R13). Threaded to the pipeline,
        // which snapshots it once per pass and records the fingerprint in the run manifest's `md` block.
        memberCatalog: org.tatrman.ttr.md.resolve.MemberCatalog? = null,
    ): BundleResult {
        val plan =
            TtrpPipeline(
                pipelineManifest,
                modelsRoot,
                memberCatalog = memberCatalog,
            ).plan(source, fileName, targetOverrides)
        require(plan.ok && plan.graph != null && plan.exec != null && plan.bound != null) {
            "cannot build a bundle from a program with errors: " +
                plan.diagnostics.filter { it.severity.name == "ERROR" }.joinToString { it.render() }
        }
        // AG-P0 S5: fingerprint each host-generated model root the program compiled against.
        val modelRoots =
            pipelineManifest.extraModelRoots
                .map { rel ->
                    ModelRootRef(rel, ModelFingerprint.of(pipelineManifest.manifestDir.resolve(rel).normalize()))
                }.ifEmpty { null }
        return assemble(
            plan.graph!!,
            plan.exec!!,
            plan.bound!!,
            plan.mdBindings,
            plan.mdModel,
            plan.mdAsof,
            plan.memberFingerprint,
            plan.params,
            fileName,
            outDir,
            pipelineManifest.manifestDir,
            compileRecord,
            modelRoots,
        )
    }

    private fun assemble(
        graph: TtrpGraph,
        exec: ExecutionGraph,
        bound: BoundWorld,
        mdBindings: MdBindings?,
        mdModel: MdModel?,
        mdAsof: java.time.Instant?,
        memberFingerprint: String?,
        paramDecls: List<org.tatrman.ttrp.ast.ParamDecl>,
        program: String,
        outDir: Path,
        manifestDir: Path,
        compileRecord: CompileRecordSpec?,
        modelRoots: List<ModelRootRef>? = null,
    ): BundleResult {
        val bundleDir = outDir.resolve(program.substringAfterLast('/').removeSuffix(".ttrp") + ".bundle")
        Files.createDirectories(bundleDir.resolve("islands"))
        Files.createDirectories(bundleDir.resolve("transfers"))
        Files.createDirectories(bundleDir.resolve("schemas"))

        val files = sortedMapOf<String, String>()
        provisionLocalFiles(graph, bound, manifestDir, bundleDir, files)
        val islandNameById = exec.islands.associate { it.id to it.name }
        val islandSql = mutableMapOf<String, String>()
        // PL-P2.S1 (F-4-i): declared param names, matched (whole-word) against each island's rendered
        // payload to record which params it consumes (§6 least exposure — the executor injects only
        // these beside `TTR_CONN_*`). Uniform across canonical + opaque SQL/py islands: a param renders
        // as an env-substituted identifier, so its name appears in the island file iff the island uses it.
        val paramNames = paramDecls.map { it.name }

        // AG-P0 — `sql-text` delivery (the `aip` executor): the host runs each island output itself, one
        // self-contained statement per OUT port through its own SQL door. Planned once per bundle.
        val planParams =
            paramDecls
                .mapIndexed { i, p ->
                    p.name to
                        org.tatrman.ttrp.emit.sql
                            .PlanParam(
                                i,
                                org.tatrman.ttrp.emit.sql.PlanParam
                                    .tagOf(p.type),
                            )
                }.toMap()
        val sqlTextExecutor =
            bound.executors.values
                .firstOrNull { ex -> ex.manifest.invocations.any { it.delivery == SQL_TEXT } }
                ?.executor
                ?.qname
                ?.name

        // Grammar 0.14 action displays: Display node id → the island output its rows come from (sql-text only;
        // filled while the sql-text islands are planned — an action display may name a derived output).
        val displaySources = LinkedHashMap<String, DisplaySource>()

        // --- islands ---
        val islandEntries =
            exec.islands.map { island ->
                if (island.invocation == SQL_TEXT) {
                    return@map sqlTextIsland(
                        island,
                        graph,
                        bound,
                        planParams,
                        sqlTextExecutor ?: "host",
                        bundleDir,
                        files,
                        displaySources,
                    )
                }
                val type = bound.engines[island.engine]?.manifest?.type
                val isFragment = graph.containers[island.id]?.verbatimFragment != null
                // A SQL engine hosts two island shapes: an authored `"""sql` FRAGMENT emits its
                // interior verbatim and is run by `psql`; a DECOMPOSED relational container (e.g.
                // the hero `crunch` retargeted to PG) emits a `python3` + adbc script — Arrow export
                // needs the query and its temp tables on one ADBC connection (S3.5 T3.5.4).
                val (relPath, text, invocation) =
                    when {
                        // An md-source island (S4-B4): the db island the hoist synthesized to compute a
                        // Polars container's MD reads. Emitted as `.sql` (psql) so it lands in
                        // `islandSql` and the existing fragment→transfer path stages its 1-row result.
                        island.id in graph.mdSourceContainers ->
                            Triple(
                                "islands/${island.name}.sql",
                                MdSourceIslandEmitter(bound, mdBindings, mdModel).emit(island, graph),
                                "psql",
                            )
                        type == "polars" -> Triple("islands/${island.name}.py", polars(island, graph, bound), "python3")
                        isFragment ->
                            Triple(
                                "islands/${island.name}.sql",
                                sql(island, graph, bound, mdBindings, mdModel),
                                "psql",
                            )
                        else ->
                            Triple(
                                "islands/${island.name}.py",
                                PgIslandScript.build(island, graph, bound, connEnv(island.engine), mdBindings, mdModel),
                                "python3",
                            )
                    }
                if (relPath.endsWith(".sql")) islandSql[island.id] = text
                write(bundleDir, relPath, text, files)
                IslandEntry(
                    name = island.name,
                    engine = island.engine,
                    // The emitted island shape (fragment→psql, polars/decomposed-PG→python3) is
                    // authoritative over the resolver's default `delivery`: a decomposed PG island
                    // is an adbc python3 script even though its engine's default binding is psql.
                    executor = "bash",
                    invocation = invocation,
                    file = relPath,
                    sha256 = files.getValue(relPath),
                    // v2 (§6, H-5): the connection(s) this island alone touches (polars islands: none).
                    connections =
                        if (bound.engines[island.engine]?.manifest?.type != "polars") {
                            listOf(connEnv(island.engine))
                        } else {
                            emptyList()
                        },
                    // PL-P2.S1 (F-4): manifest-declared retries + on-failure edge + consumed params.
                    retries = island.retries,
                    onFailureOf = island.onFailureOf,
                    // NOTE (review-072 R2-6, tracked): this whole-word text match over-approximates — a param
                    // whose name coincides with an unrelated SQL column/identifier is attributed to (and its
                    // NON-secret value injected into) an island that never uses it (§6 over-exposure, never
                    // under-exposure). Precise attribution needs AST/graph param-reference tracking threaded
                    // through the render pipeline; deferred as a proper follow-up over a heuristic tweak.
                    params = paramNames.filter { wordIn(it, text) }.ifEmpty { null },
                )
            }

        // --- transfers ---
        val transferEntries =
            exec.transfers.map { t ->
                val token = tokenFor(t.id)
                val relPath = "transfers/$token.py"
                val sourceIslandName = t.fromIsland?.let { islandNameById[it] } ?: "src"
                val sourceEngine = exec.islands.firstOrNull { it.id == t.fromIsland }?.engine ?: "erp_pg"
                val srcSql = t.fromIsland?.let { islandSql[it] }
                write(bundleDir, relPath, transferScript(t.via, sourceEngine, srcSql), files)
                val targetEngine = exec.islands.firstOrNull { it.id == t.toIsland }?.engine
                val transferConns =
                    listOfNotNull(sourceEngine, targetEngine)
                        .filter { bound.engines[it]?.manifest?.type != "polars" }
                        .map { connEnv(it) }
                        .distinct()
                        .sorted()
                TransferEntry(
                    from = sourceIslandName,
                    to = t.toIsland?.let { islandNameById[it] } ?: "dst",
                    via = t.via ?: "stage",
                    file = relPath,
                    sha256 = files.getValue(relPath),
                    connections = transferConns,
                )
            }

        // --- schemas (one per staging boundary; minimal Arrow-schema JSON + fingerprint) ---
        exec.transfers.forEach { t ->
            val name = t.via ?: "stage"
            val rel = "schemas/${tokenFor(t.id)}.json"
            write(bundleDir, rel, schemaJson(name), files)
        }

        // --- waves: island ids → names, transfer ids → file tokens ---
        val waves =
            exec.waves.map { wave ->
                wave.map { id -> islandNameById[id] ?: tokenFor(id) }
            }
        val connections =
            exec.islands
                .filter { bound.engines[it.engine]?.manifest?.type != "polars" }
                .map { connEnv(it.engine) }
                .distinct()
                .sorted()
        val connectionByIsland =
            exec.islands.filter { (it.invocation ?: "") == "psql" }.associate { it.name to connEnv(it.engine) }
        val hostExecuted = exec.islands.isNotEmpty() && exec.islands.all { it.invocation == SQL_TEXT }
        // One entry per display leaf (several may share an action display's name): by name, then wiring order —
        // the order the host concatenates an action display's rows in — each with its own source and file.
        val displays =
            DisplayLeaves.of(graph).map { leaf ->
                val d = leaf.display
                DisplayEntry(
                    name = d.name,
                    file = "out/${leaf.stem}.arrow",
                    source = if (hostExecuted) displaySources[d.id] ?: displaySource(graph, d) else null,
                    schema = d.schema?.qualifiedName,
                    columns =
                        // the schema's own type spelling (`decimal(12,4)` for a structured type) — the same one
                        // its `sql-text` outputs report, whatever type the source column had
                        d.schema?.columns?.map {
                            DisplayColumn(
                                it.name,
                                it.spelling,
                                if (it.optional) true else null,
                            )
                        },
                )
            }
        val rejectSites = rejectSites(graph)
        // MD compile parameters for bind-time staleness (S4-B5, decision 13). Recorded only for an MD
        // program (mdModel present) with something to anchor on — else null, and omitted from the JSON,
        // so non-MD manifests are byte-identical. memberFingerprint is null in disconnected mode (S6-B).
        val md =
            if (mdModel != null && (mdAsof != null || memberFingerprint != null)) {
                MdManifest(asof = mdAsof?.toString(), memberFingerprint = memberFingerprint)
            } else {
                null
            }

        // PL-P2.S1 (F-4-i): top-level declared params (name/type/required/default), source order. Null ⇒
        // omitted, so a param-free program's manifest is byte-identical to pre-feature.
        val params =
            paramDecls
                .map { p ->
                    Param(
                        name = p.name,
                        type = p.type.substringBefore('(').trim(),
                        required = p.required,
                        default = renderParamDefault(p.default),
                    )
                }.ifEmpty { null }

        val worldFingerprint = WorldFingerprint.of(bound.world)
        // v2 (§6, CQ-5): static column lineage, compile-derived. Null ⇒ omitted (explicitNulls=false).
        val lineage = LineageExtractor.extract(graph, exec.islands).takeIf { it.columns.isNotEmpty() }
        val manifest =
            RunManifest(
                toolchain = "org.tatrman:ttrp:$toolchainVersion",
                program = program.substringAfterLast('/'),
                world = WorldRef(worldQname(bound), worldFingerprint),
                islands = islandEntries,
                transfers = transferEntries,
                waves = waves,
                connections = connections,
                displays = displays,
                params = params,
                lineage = lineage,
                rejectSites = rejectSites,
                md = md,
                modelRoots = modelRoots,
                files = files.toMap(),
            )

        // PL-P5.S1 — the orchestration launcher is emitted by the resolved emit plugin (bash by default) through
        // the §8 SPI: core builds the manifest → the plugin renders the launcher from the projected graph → core
        // re-hashes the launcher file(s) into `files{}` (chicken-and-egg avoided: hash then re-add) → core writes
        // `manifest.json`. The plugin owns ONLY the launcher; a core-owned path in its result is a hard error.
        val request =
            EmitRequest(
                program = ProgramMeta(manifest.program, manifest.world.qname, manifest.toolchain),
                graph =
                    OrchestrationGraph(
                        waves = manifest.waves,
                        islands =
                            manifest.islands.map {
                                EmitIsland(
                                    name = it.name,
                                    engine = it.engine,
                                    invocation = it.invocation,
                                    file = it.file,
                                    connections = it.connections,
                                    retries = it.retries,
                                    onFailureOf = it.onFailureOf,
                                    params = it.params ?: emptyList(),
                                )
                            },
                        transfers =
                            manifest.transfers.map {
                                EmitTransfer(it.from, it.to, it.via, it.file, it.connections)
                            },
                        connections = manifest.connections,
                        displays = manifest.displays.map { EmitDisplay(it.name, it.file) },
                        connectionByIsland = connectionByIsland,
                    ),
                islandPayloads = islandEntries.map { payloadOf(bundleDir, it.name, it.file, it.sha256) },
                transferPayloads = transferEntries.map { payloadOf(bundleDir, fileToken(it.file), it.file, it.sha256) },
                executorType = ResolvedManifest(emitPlugin.executorTypeManifest()),
                // PL-P5.S4 — the world's executor INSTANCE entry for this target, rendered to TTR-M text (contracts
                // §7/§8). Empty when the world declares no matching executor (the standalone default). Plugins that
                // need world-declared bindings (airflow3: delegation/doorConnection, E-3-γ) parse this; bash/kestra
                // ignore it.
                executorInstance = ResolvedManifest(ExecutorInstanceResolver.resolve(bound, emitPlugin.targetId)),
                manifestJson = manifest.toJson(),
            )
        // A host-executed (`sql-text`) bundle has no launcher: the host reads manifest.json + the island
        // statements and runs them itself (AG-P0). Every other bundle is launched by the emit plugin.
        val emitted =
            if (hostExecuted) {
                org.tatrman.ttrp.emit.spi
                    .EmitResult(sortedMapOf())
            } else {
                emitPlugin.emit(request)
            }
        CoreOwnedPaths.check(emitted)
        val launcherHashes = sortedMapOf<String, String>()
        emitted.files.forEach { (rel, bytes) ->
            val p = bundleDir.resolve(rel)
            Files.createDirectories(p.parent)
            Files.write(p, bytes)
            if (rel == "run.sh") p.toFile().setExecutable(true)
            launcherHashes[rel] = sha256(bytes)
        }
        val withRunSh = manifest.copy(files = (files + launcherHashes).toSortedMap().toMap())
        Files.writeString(bundleDir.resolve("manifest.json"), withRunSh.toJson())

        // Compile record (§5): a bundle-ADJACENT sidecar beside .bundle/ — NEVER inside the bundle,
        // NEVER in files{} (it is binding-dependent: mode/staleness, so it must not be hashed, B-3).
        // Exclude MOVEMENT-synthesized loads (`<stem>~load`, whose `source` is a container IN-port name
        // like "accounts", not a real object): the F-7 provenance slice must report only authored objects
        // the program actually reads. Mirrors ContainerCollapse's `~store` filter.
        val objectsRead =
            graph.nodes.values
                .filterIsInstance<Load>()
                .filterNot { it.id.contains("~load") }
                .map { it.source }
                .distinct()
                .sorted()
        val record =
            compileRecord?.build(
                toolchain = "org.tatrman:ttrp:$toolchainVersion",
                program = program.substringAfterLast('/'),
                worldFingerprint = worldFingerprint,
                objectsRead = objectsRead,
            ) ?: CompileRecord.standalone(
                toolchain = "org.tatrman:ttrp:$toolchainVersion",
                program = program.substringAfterLast('/'),
                worldFingerprint = worldFingerprint,
                objectsRead = objectsRead,
            )
        Files.writeString(
            bundleDir.parent.resolve(program.substringAfterLast('/').removeSuffix(".ttrp") + ".compile-record.json"),
            record.toJson(),
        )
        return BundleResult(bundleDir, withRunSh, request)
    }

    /**
     * Stages `local_dir`-storage CSV inputs (e.g. `load(files.sales_2026, ...)`) into the bundle
     * at the same relative path the emitters already hard-code (`<storage>/<member>.csv` — see
     * `PolarsGraphEmitter.path` / `PgIslandScript`'s csv-temp convention): a project author lays
     * their input files out under `<manifestDir>/<storage>/<member>.csv`, mirroring the bundle's
     * own internal layout. Best-effort: a source not found there is left for `run.sh` to report
     * (e.g. a live/remote-provisioned input) — this only closes the common local-file case that
     * previously required manual staging (only the test harness did this, not the shipped CLI).
     */
    private fun provisionLocalFiles(
        graph: TtrpGraph,
        bound: BoundWorld,
        manifestDir: Path,
        bundleDir: Path,
        files: MutableMap<String, String>,
    ) {
        val localDirStorages =
            bound.storages
                .filter { it.type == "local_dir" }
                .map { it.qname.name }
                .toSet()
        graph.nodes.values
            .filterIsInstance<Load>()
            .filter { it.source.substringBefore('.') in localDirStorages }
            .forEach { load ->
                val rel = load.source.replace('.', '/') + ".csv"
                val src = manifestDir.resolve(rel)
                if (Files.isRegularFile(src)) {
                    val dst = bundleDir.resolve(rel)
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                    files[rel] = sha256(Files.readAllBytes(dst))
                }
            }
    }

    /**
     * The elaborated reject sites (RJ-P3, contracts §7). The RJ-P1 rewire is the discriminator:
     * a reject producer's `out` is the ONLY synthesized node mapped onto a container port (guard and
     * branch stay internal), so a portMapping entry whose target is in [TtrpGraph.synthProvenance]
     * marks the container's `rejects` port and names its authored site. The sibling DATA OUT ports
     * are the processed streams.
     */
    private fun rejectSites(graph: TtrpGraph): List<RejectSiteEntry> =
        graph.containers.values.flatMap { c ->
            c.portMapping.mapNotNull { (port, ref) ->
                val authored = graph.synthProvenance[ref.nodeId] ?: return@mapNotNull null
                val processed =
                    c.declaredPorts
                        .filter { it.direction == PortDirection.OUT && it.kind == PortKind.DATA && it.name != port }
                        .map { it.name }
                RejectSiteEntry(
                    site = graph.nodes[authored]?.label?.substringBefore('#') ?: authored,
                    container = c.label,
                    rejectsPort = port,
                    processedPorts = processed,
                )
            }
        }

    /**
     * AG-P0 — a `sql-text` island: one `islands/<island>.<port>.sql` per OUT port, each a self-contained
     * statement in the engine's dialect with `:name` param placeholders ([org.tatrman.ttrp.emit.sql.SqlTextPlanner]).
     */
    private fun sqlTextIsland(
        island: Island,
        graph: TtrpGraph,
        bound: BoundWorld,
        params: Map<String, org.tatrman.ttrp.emit.sql.PlanParam>,
        executor: String,
        bundleDir: Path,
        files: MutableMap<String, String>,
        displaySources: MutableMap<String, DisplaySource>,
    ): IslandEntry {
        val container = graph.containers.getValue(island.id)
        val planner =
            org.tatrman.ttrp.emit.sql
                .SqlTextPlanner(graph, bound, SqlIslandEmitter(bound).dialect(island), params)
        val ports =
            container.declaredPorts
                .filter { it.direction == PortDirection.OUT && it.kind == PortKind.DATA && it.name != "rejects" }
                .map { it.name }

        fun output(
            port: String,
            out: org.tatrman.ttrp.emit.sql.SqlTextOutput,
        ): IslandOutput {
            val rel = "islands/${island.name}.$port.sql"
            write(bundleDir, rel, out.sql + "\n", files)
            return IslandOutput(
                port = port,
                file = rel,
                sha256 = files.getValue(rel),
                columns = out.columns.map { OutputColumn(it.name, it.type) },
                params = out.params.ifEmpty { null },
            )
        }
        // Grammar 0.14 action displays: the statement the host runs for an action display is projected to its
        // row schema. A port whose every display is the same action display is projected in place; a port that
        // also feeds a differently-shaped display keeps its own statement and gains one projected output per
        // action schema, `<port>~<schema>`, which those action displays name as their source.
        val outputs =
            ports.flatMap { port ->
                val fed =
                    graph.edges
                        .filter { it.from == PortRef(container.id, port) }
                        .mapNotNull { graph.nodes[it.to.nodeId] as? Display }
                val schemas = fed.mapNotNull { it.schema }.distinctBy { it.qualifiedName }
                val inPlace = schemas.size == 1 && fed.all { it.schema != null }
                fed.forEach { displaySources[it.id] = DisplaySource(island.name, port) }
                val own = output(port, planner.emit(container, port, if (inPlace) schemas.single() else null))
                if (inPlace) return@flatMap listOf(own)
                listOf(own) +
                    schemas.map { schema ->
                        val derived = "$port~${schema.name}"
                        fed.filter { it.schema?.qualifiedName == schema.qualifiedName }.forEach {
                            displaySources[it.id] = DisplaySource(island.name, derived)
                        }
                        output(derived, planner.emit(container, port, schema))
                    }
            }
        val first = outputs.firstOrNull() ?: error("sql-text island '${island.name}' has no OUT port")
        return IslandEntry(
            name = island.name,
            engine = island.engine,
            executor = executor,
            invocation = SQL_TEXT,
            file = first.file,
            sha256 = first.sha256,
            connections = listOf(connEnv(island.engine)),
            retries = island.retries,
            onFailureOf = island.onFailureOf,
            params = outputs.flatMap { it.params.orEmpty() }.distinct().ifEmpty { null },
            outputs = outputs,
        )
    }

    /** The island OUT port a display leaf is fed by (AG-P0 host-executed bundles) — each leaf its own. */
    private fun displaySource(
        graph: TtrpGraph,
        leaf: Display,
    ): DisplaySource? {
        val feed = graph.edges.firstOrNull { it.to.nodeId == leaf.id } ?: return null
        val container = graph.containers[feed.from.nodeId] ?: return null
        return DisplaySource(container.label, feed.from.port)
    }

    private fun sql(
        island: Island,
        graph: TtrpGraph,
        bound: BoundWorld,
        mdBindings: MdBindings?,
        mdModel: MdModel?,
    ): String = SqlIslandEmitter(bound, mdBindings, mdModel).emit(island, graph).text

    private fun polars(
        island: Island,
        graph: TtrpGraph,
        bound: BoundWorld,
    ): String {
        val container = graph.containers.getValue(island.id)
        val emitter = PolarsGraphEmitter(graph, bound)
        val steps = emitter.steps(container)
        val rejects =
            bound.engines[island.engine]?.manifest?.rejectsSupport()
                ?: org.tatrman.ttrp.graph.capability.RejectsSupport.NONE
        // graph.mdStaging tells the renderer where each hoisted MD read's staged scalar lives (S4-B4).
        return PolarsIslandEmitter(
            graph.mdStaging,
        ).emit(island.name, steps, rejects, emitter.partitions(container)).text
    }

    private fun transferScript(
        via: String?,
        sourceEngine: String,
        sourceSql: String?,
    ): String {
        val conn = connEnv(sourceEngine)
        val edge = via ?: "stage"
        val query = sourceSql?.let { "SELECT * FROM (\n${it.trimEnd()}\n) AS _ttrp_src" } ?: "SELECT 1"
        val q = query.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return buildString {
            appendLine("import os")
            appendLine("import polars as pl")
            appendLine("uri = os.environ[\"$conn\"]")
            appendLine("df = pl.read_database_uri(\"$q\", uri, engine=\"adbc\")")
            appendLine("df.write_ipc(\"staging/$edge.arrow\")")
        }.trimEnd('\n') + "\n"
    }

    private fun schemaJson(name: String): String =
        "{\n  \"name\": \"$name\",\n  \"fields\": [],\n  \"fingerprint\": \"sha256:${"0".repeat(64)}\"\n}\n"

    private fun write(
        bundleDir: Path,
        relPath: String,
        text: String,
        files: MutableMap<String, String>,
    ) {
        val p = bundleDir.resolve(relPath)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        files[relPath] = sha256(text.toByteArray())
    }

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A finished, on-disk payload (PL-P5.S1) — bytes read back from the just-written file, sha256 from `files{}`. */
    private fun payloadOf(
        bundleDir: Path,
        name: String,
        relPath: String,
        sha256: String,
    ): IslandPayload = IslandPayload(name, relPath, Files.readAllBytes(bundleDir.resolve(relPath)), sha256)

    /** The bare token of a bundle file path (e.g. `transfers/x.py` → `x`) — the payload's informational name. */
    private fun fileToken(file: String): String = file.substringAfterLast('/').substringBeforeLast('.')

    private fun tokenFor(id: String): String = id.replace("~", "_").replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun connEnv(engine: String): String = "TTR_CONN_" + engine.uppercase().replace(Regex("[^A-Z0-9]"), "_")

    /** PL-P2.S1: whole-word (identifier-boundary) match of a param name in a rendered island payload. */
    private fun wordIn(
        name: String,
        text: String,
    ): Boolean = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])").containsMatchIn(text)

    /** PL-P2.S1: the manifest `default` string for a param — the `@builtin`, or an unquoted literal; null ⇒ required. */
    private fun renderParamDefault(default: org.tatrman.ttrp.ast.ParamDefault?): String? =
        when (default) {
            null -> null
            is org.tatrman.ttrp.ast.ParamDefault.Builtin -> "@" + default.name
            is org.tatrman.ttrp.ast.ParamDefault.Literal -> {
                val t = default.text
                if (t.length >= 2 && (t.first() == '"' || t.first() == '\'') && t.last() == t.first()) {
                    t.substring(1, t.length - 1)
                } else {
                    t
                }
            }
        }

    private companion object {
        /** The host-executed island delivery (AG-P0, the `aip` executor manifest). */
        const val SQL_TEXT = "sql-text"
    }

    private fun worldQname(bound: BoundWorld): String =
        bound.world.qname.let { q ->
            val prefix = q.`package`.ifBlank { q.namespace }
            if (prefix.isBlank()) q.name else "$prefix.${q.name}"
        }
}
