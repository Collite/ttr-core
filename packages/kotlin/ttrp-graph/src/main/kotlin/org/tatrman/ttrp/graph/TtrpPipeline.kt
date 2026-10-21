// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph

import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.graph.build.GraphBuilder
import org.tatrman.ttrp.graph.capability.CapabilityChecker
import org.tatrman.ttrp.graph.capability.ExecutorManifestGate
import org.tatrman.ttrp.graph.capability.ClasspathManifestSource
import org.tatrman.ttrp.graph.capability.InvocationBindingResolver
import org.tatrman.ttrp.graph.capability.ManifestSource
import org.tatrman.ttrp.graph.capability.BoundWorld
import org.tatrman.ttrp.graph.capability.RejectsCapabilityChecker
import org.tatrman.ttrp.graph.capability.WorldBinder
import org.tatrman.ttrp.graph.collapse.ContainerCollapse
import org.tatrman.ttrp.graph.collapse.ExecutionGraph
import org.tatrman.ttrp.graph.explain.ExplainRenderer
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.graph.movement.MdReadHoist
import org.tatrman.ttrp.graph.movement.MovementSynthesizer
import org.tatrman.ttrp.graph.rewrite.AppliedRewrite
import org.tatrman.ttrp.graph.rewrite.NormalizeResult
import org.tatrman.ttrp.graph.rewrite.RejectEscalation
import org.tatrman.ttrp.graph.rewrite.RewriteEngine
import org.tatrman.ttrp.graph.rewrite.Rules
import org.tatrman.ttrp.graph.validate.StructureValidator
import org.tatrman.ttrp.graph.world.StagingResolver
import org.tatrman.ttrp.project.TtrpManifest
import org.tatrman.ttrp.resolve.MdRepo
import org.tatrman.ttrp.resolve.TtrpChecker
import org.tatrman.ttr.semantics.md.MdBindings
import org.tatrman.ttr.semantics.md.MdModel

/**
 * The Phase-2 compile pipeline (architecture §4): front-half → build graph → normalize
 * (T8) → staging → movement synthesis → collapse → explain. Everything offline (D-g).
 * `explain` is the Phase-2 deliverable (`ttrp explain`).
 */
class TtrpPipeline(
    private val manifest: TtrpManifest,
    private val modelsRoot: java.nio.file.Path = manifest.modelsRoot(),
    private val manifests: ManifestSource = ClasspathManifestSource(),
    // Connected-mode member catalog (S6-B): threaded to [TtrpChecker], which snapshots it once per pass.
    // Null ⇒ disconnected (R13), the prior behaviour.
    private val memberCatalog: org.tatrman.ttr.md.resolve.MemberCatalog? = null,
) {
    /**
     * The project's MD tier (logical model + physical `md2db_*` bindings), loaded once from
     * [modelsRoot]. Null when the repo declares no cubelets — then MD resolution and lowering stay
     * inert exactly as before this seam was wired. The [MdModel] feeds the front-half resolver
     * ([TtrpChecker]); the [MdBindings] ride out on [PlanResult.mdBindings] for the bundle emit.
     * Member snapshot loading is a further seam (S6-B), so resolution runs disconnected (R13).
     */
    private val mdRepo: MdRepo.Loaded? by lazy { MdRepo.loadFrom(modelsRoot) }

    data class ExplainOutput(
        val text: String,
        val diagnostics: List<TtrpDiagnostic>,
        val ok: Boolean,
    )

    /**
     * The structured Phase-2 output that Phase-3 emit/bundle consumes: the normalized,
     * movement-synthesized graph, its derived execution graph (islands/waves/transfers),
     * the bound world, and the applied-rewrite log. `ok == false` (with null graph/exec/bound)
     * when the front-half or structural validation produced an error-severity diagnostic.
     */
    data class PlanResult(
        val fileName: String,
        val graph: TtrpGraph?,
        val exec: ExecutionGraph?,
        val bound: BoundWorld?,
        val rewrites: List<AppliedRewrite>,
        val diagnostics: List<TtrpDiagnostic>,
        val ok: Boolean,
        /**
         * The **authored** build graph — post-resolution + er-rewrite, but BEFORE T8
         * normalize (no capability lowering, no movement synthesis). This is the
         * structure the graphical surface renders and edits (A4: the canvas is a second
         * *authoring* surface, so it must show `Branch`, not the polars `branch→filter`
         * lowering). Null exactly when [graph] is (front-half/structure error). The
         * derived orchestration overlay (transfers/waves) still comes from [exec].
         */
        val authoredGraph: TtrpGraph? = null,
        /**
         * The project's MD physical bindings (`md2db_*`), loaded from the models root when the repo
         * declares cubelets, else null. Carried here so the bundle emit
         * ([org.tatrman.ttrp.bundle.BundleAssembler]) can lower resolved MD dot-paths to SQL without
         * re-parsing the model tier — the read-lowering counterpart of the graph's `mdResolutions`.
         */
        val mdBindings: MdBindings? = null,
        /**
         * The project's logical MD model, loaded from the models root when the repo declares cubelets,
         * else null. Carried alongside [mdBindings] because the read lowering needs it for anything past
         * a grain-direct column read (hop joins, calc/viaCalc drills, diff-journal grain).
         */
        val mdModel: MdModel? = null,
        /**
         * The resolved MD `asof` (D17) for the bundle manifest (S4-B5, decision-13 staleness): the
         * `[ttrp] md-asof` value, else the compile-pass clock. Null for a non-MD program. Carried from
         * the checker so [org.tatrman.ttrp.bundle.BundleAssembler] can record it without re-resolving.
         */
        val mdAsof: java.time.Instant? = null,
        /** The [MemberSnapshot] fingerprint paired with [mdAsof] — null in disconnected mode (S6-B seam). */
        val memberFingerprint: String? = null,
        /**
         * PL-P2.S1 (F-4-i): the program's runtime-param declarations, in source order. Carried so the
         * bundle emit records them in the manifest `params[]` (and per-island consumption) without
         * re-parsing. Empty for a param-free program (byte-identical manifests).
         */
        val params: List<org.tatrman.ttrp.ast.ParamDecl> = emptyList(),
        /**
         * AU-core-P1: what the recipe checker learned about the document's recipe (roles, context classes, the mode
         * verdict, routes, wiring) — null for a program without a recipe, or when the front half failed.
         */
        val recipe: org.tatrman.ttrp.graph.recipe.RecipeModel? = null,
    )

    /**
     * Run the full Phase-2 pipeline and hand back structured artifacts (no rendering).
     *
     * [targetOverrides] (container **label** → engine-instance name) programmatically re-assigns a
     * container's execution target *before* normalize, so T8 re-lowers against the new engine
     * (e.g. retarget the hero `crunch` to `erp_pg` → Branch lowers to Filters, movement
     * re-synthesizes). This is the build-API hook the A4 placement variants use (S3.5 T3.5.5);
     * empty for the authored placement. An override label matching no container is ignored.
     */
    fun plan(
        source: String,
        fileName: String = "<memory>",
        targetOverrides: Map<String, String> = emptyMap(),
    ): PlanResult {
        // MD front-half resolution fires only when the repo carries an [MdModel] (else the seam stays
        // inert). In connected mode [memberCatalog] supplies the member snapshot (S6-B); disconnected
        // (null) leaves MD paths to resolve per R13 (qualified members become deferred coordinates).
        val report =
            TtrpChecker(manifest, modelsRoot, mdModel = mdRepo?.model, memberCatalog = memberCatalog)
                .check(source, fileName)
        val diags = report.diagnostics.toMutableList()
        // MD dot-path S5C-B.4 (R30): model-level journal-role diagnostics (TTRP-MD-018) computed at MD
        // model load — an invalidate-journaled cubelet whose backing table declares no valid role.
        mdRepo?.journalRoleDiagnostics?.let { diags += it }
        val world = report.world
        if (report.errors.isNotEmpty() || world == null) {
            return PlanResult(fileName, null, null, null, emptyList(), diags, ok = false)
        }

        val bound = WorldBinder(manifests).bind(world)
        diags += bound.diagnostics
        val build = GraphBuilder().build(report)
        diags += build.diagnostics
        // PL-P2.S1 T6 gate: F-4 vocabulary (params / on-failure / retries) is legal only against a
        // world whose executor-type manifest declares it (contracts §7). Runs on the authored graph
        // (pre-movement) so container on-failure/retries attributes are intact.
        diags +=
            ExecutorManifestGate.check(
                report.document,
                build.graph,
                bound.executors.values.map { it.manifest },
            )
        val retargeted = applyTargetOverrides(build.graph, targetOverrides)
        val structure = StructureValidator().validate(retargeted)
        diags += structure
        // AU-core-P1: the recipe checker, over the built graph and the resolved model (AU contracts §4).
        val recipe =
            build.recipe?.let {
                org.tatrman.ttrp.graph.recipe
                    .RecipeChecker(report, build, fileName)
                    .check()
            }
        recipe?.let { diags += it.diagnostics }
        if (diags.any { it.severity == Severity.ERROR }) {
            return PlanResult(fileName, null, null, null, emptyList(), diags, ok = false, recipe = recipe?.model)
        }
        // AU-core-P2: a checked recipe's switch lowers to the node set (routes, verdict columns, detail) before
        // normalize, so every engine and emitter sees filters, calcs and joins. The authored graph keeps the switch.
        var compiled = retargeted
        var recipeModel = recipe?.model
        if (recipeModel != null && build.recipe != null) {
            val switched =
                org.tatrman.ttrp.graph.recipe.SwitchLowering
                    .lower(retargeted, build.recipe, recipeModel)
            diags += switched.diagnostics
            // The time sugar (S2.2): latest / within / working_days / age and `:as_of ± duration` to the node set.
            val sugared =
                org.tatrman.ttrp.graph.recipe
                    .SugarLowering(build.recipe, recipeModel, report.modelIndex)
                    .lower(switched.graph)
            diags += sugared.diagnostics
            recipeModel = recipeModel.copy(derivedParams = sugared.derived, reasonNodes = switched.reasonNodes)
            if (diags.any { it.severity == Severity.ERROR }) {
                return PlanResult(fileName, null, null, null, emptyList(), diags, ok = false, recipe = recipeModel)
            }
            compiled = sugared.graph
        }

        if (recipeModel != null) {
            compiled =
                compiled.copy(
                    loadSources =
                        compiled.loadSources.mapValues { (loadId, src) ->
                            if (src.storage != null) {
                                src
                            } else {
                                val placements =
                                    org.tatrman.ttrp.resolve.RecipePlacement.storages(
                                        src,
                                        bound.world,
                                        report.modelIndex,
                                    )
                                val failure =
                                    org.tatrman.ttrp.resolve.RecipePlacement.failure(
                                        src,
                                        bound.world,
                                        placements,
                                    )
                                if (failure != null) {
                                    diags +=
                                        TtrpDiagnostic(
                                            org.tatrman.ttrp.diagnostics.TtrpDiagnosticId.REC_090,
                                            Severity.ERROR,
                                            failure,
                                            compiled.nodes[loadId]?.location
                                                ?: org.tatrman.ttrp.ast.SourceLocation.UNKNOWN,
                                        )
                                    src
                                } else {
                                    src.copy(storage = placements.single())
                                }
                            }
                        },
                )
        }
        if (diags.any { it.severity == Severity.ERROR }) {
            return PlanResult(fileName, null, null, null, emptyList(), diags, ok = false, recipe = recipeModel)
        }
        val norm: NormalizeResult = RewriteEngine(Rules.ALL, bound).normalize(compiled)
        // RJ-P1 elaboration authoring diagnostics (dead-wire RJ-101; fail-closed RJ-107 unsupported
        // reject type + RJ-108 reject-capable join ON — RJ-P5 review).
        diags += norm.diagnostics
        // RJ-P2 rejects capability: a cluster on an engine that cannot produce rejects is resolved
        // per the `[ttrp] rejects-in-sql` knob — produce/error ⇒ TTRP-RJ-106 error; escalate ⇒ the
        // cluster's container retargets to a capable engine (+ TTRP-RJ-102) and movement wraps it.
        val rejectMisses = RejectsCapabilityChecker(bound).check(norm.graph)
        val escalation = RejectEscalation.resolve(norm.graph, bound, manifest.rejectsInSql, rejectMisses)
        diags += escalation.diagnostics
        val planned = escalation.graph
        // Capability-miss info + rewrite log surface as informational diagnostics.
        val capabilityChecker = CapabilityChecker(bound)
        diags += capabilityChecker.diagnostics(capabilityChecker.check(planned))
        // MD-read hoist (S4-B4): an MD dot-path placed on a non-SQL (Polars) engine is moved into its
        // own db island + staged, turning it into a genuine engine crossing the strata below already
        // know how to island/move/wave. Inert for SQL-placed / MD-free programs (returns the graph
        // unchanged), so it precedes staging + movement without disturbing them otherwise.
        val hoisted = MdReadHoist(bound).hoist(planned)
        val staging = StagingResolver(bound, manifest.staging).resolve(hoisted)
        diags += staging.diagnostics
        val moved = MovementSynthesizer(bound, staging.staging?.qname?.name).synthesize(hoisted)
        val inv = InvocationBindingResolver(bound).resolve(moved.graph)
        diags += inv.diagnostics
        val exec = ContainerCollapse(inv).collapse(moved.graph)

        val ok = diags.none { it.severity == Severity.ERROR }
        return PlanResult(
            fileName,
            moved.graph,
            exec,
            bound,
            norm.log,
            diags,
            ok,
            authoredGraph = retargeted,
            mdBindings = mdRepo?.bindings,
            mdModel = mdRepo?.model,
            mdAsof = report.mdAsof,
            memberFingerprint = report.memberFingerprint,
            params = report.document.statements.filterIsInstance<org.tatrman.ttrp.ast.ParamDecl>(),
            recipe = recipeModel,
        )
    }

    /** Re-target containers whose **label** appears in [overrides] (both the flat node map + the container map). */
    private fun applyTargetOverrides(
        graph: TtrpGraph,
        overrides: Map<String, String>,
    ): TtrpGraph {
        if (overrides.isEmpty()) return graph
        val containers =
            graph.containers.mapValues { (_, c) ->
                overrides[c.label]?.let { c.copy(target = it) } ?: c
            }
        val nodes =
            graph.nodes.mapValues { (id, n) ->
                containers[id] ?: n
            }
        return graph.copy(nodes = nodes, containers = containers)
    }

    fun explain(
        source: String,
        fileName: String = "<memory>",
    ): ExplainOutput {
        val plan = plan(source, fileName)
        if (!plan.ok || plan.graph == null || plan.exec == null || plan.bound == null) {
            return ExplainOutput("", plan.diagnostics, ok = false)
        }
        val text = ExplainRenderer.render(fileName, plan.graph, plan.exec, plan.rewrites, plan.bound)
        return ExplainOutput(text, plan.diagnostics, ok = plan.ok)
    }
}
