// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import org.tatrman.ttr.md.resolve.MemberSnapshot
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.semantics.md.MdModel
import org.tatrman.ttr.metadata.world.ResolvedStorage
import org.tatrman.ttr.metadata.world.ResolvedWorld
import org.tatrman.ttrp.SchemaSource
import org.tatrman.ttrp.TtrpFrontend
import org.tatrman.ttrp.ast.Arg
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.ImportDecl
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.PortKind
import org.tatrman.ttrp.ast.RelationArg
import org.tatrman.ttrp.ast.SchemaColumn
import org.tatrman.ttrp.ast.SchemaLiteralArg
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.ast.UsesWorld
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.Column
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.ExpressionTypechecker
import org.tatrman.ttrp.expr.MdContext
import org.tatrman.ttrp.expr.MdResolution
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.parser.TtrpParser
import org.tatrman.ttrp.project.TtrpManifest

/**
 * The binding tier (Stage 1.3): parse → world/model resolution → position typing →
 * er→db early rewrite (provenance) → declared-schema handling → expression typing.
 * The `ttrp check` front-half. Everything model/world goes through ttr-metadata (D-g).
 */
class TtrpChecker(
    private val manifest: TtrpManifest,
    modelsRoot: java.nio.file.Path = manifest.modelsRoot(),
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val mdModel: MdModel? = null,
    private val memberSnapshot: MemberSnapshot? = null,
    // Connected-mode member catalog (S6-B): when present (and no snapshot is injected directly), a
    // snapshot is taken once per pass at the resolved `asof` — the ModelHandle "capture at construction
    // of the pass" idiom. Null ⇒ disconnected (R13). `memberSnapshot` (direct) still wins, for the S3-A
    // unit fixtures that inject a snapshot without a catalog.
    private val memberCatalog: org.tatrman.ttr.md.resolve.MemberCatalog? = null,
) {
    private val modelIndex: ModelIndex? =
        ModelRepo.snapshotOf(modelsRoot, manifest.extraModelRootPaths())?.let { ModelIndex(it) }
    private val typechecker = ExpressionTypechecker()

    data class Report(
        val document: TtrpDocument,
        val diagnostics: List<TtrpDiagnostic>,
        val world: ResolvedWorld?,
        val rewrites: List<ErRewrite>,
        /**
         * Resolved output schema per SSA-variable / container-port name (the dataflow
         * pass's result), keyed **by scope then by name** so same-named vars in different
         * containers never collide. The outer key is the enclosing scope label — `""` for
         * program level, the container name for a container body (matching
         * `SourceNav.scopeLabel`). The inner column list is null when typing was deferred
         * (unresolved source). Exposed for LSP hover / authoring-context (Stage 4.1 T4.1.5).
         */
        val schemas: Map<String, Map<String, List<Column>?>> = emptyMap(),
        /**
         * The resolved model repo (db tables / er entities), if one loaded. Exposed for
         * authoring-context's `modelObjects` enumeration (Stage 7.2 tail).
         */
        val modelIndex: ModelIndex? = null,
        /**
         * MD dot-paths that resolved in expression positions (S3-A): canonical form + shape +
         * explanation, for the frontend API / future ttrp-lsp hover. Empty until an [MdModel] is
         * injected (production model loading is a later seam).
         */
        val mdResolutions: List<MdResolution> = emptyList(),
        /**
         * The resolved MD `asof` (D17) for the bundle manifest (S4-B5, decision-13 staleness): the
         * `[ttrp] md-asof` value, else the compile-pass clock. Null when no [MdModel] is in play (a
         * non-MD program records no asof). Paired with [memberFingerprint].
         */
        val mdAsof: java.time.Instant? = null,
        /**
         * The [MemberSnapshot] fingerprint recorded alongside [mdAsof] (decision-13 staleness). Null in
         * disconnected mode (no snapshot) — production snapshot loading is the S6-B seam.
         */
        val memberFingerprint: String? = null,
        /**
         * AG-P0: the physical source of every model-object `load(...)`, keyed by the load **op**'s
         * location (the graph builder's Load node location) — the er→db binding resolved to a table or
         * an inline query/view text, with logical→physical columns. Lets a SQL emitter name real tables
         * without re-resolving the model. Empty when no model repo loaded.
         */
        val loadSources: Map<org.tatrman.ttrp.ast.SourceLocation, LoadSource> = emptyMap(),
        /**
         * Action displays (grammar 0.14): every `display(<name>)` op whose name is a row schema declared in an
         * imported package, keyed by the display **op**'s location (= the graph's Display node location) →
         * that schema. A display absent here is an ordinary (evidence) display. The graph carries it onto the
         * Display node; the emitters project the display's rows to it.
         */
        val displaySchemas: Map<org.tatrman.ttrp.ast.SourceLocation, DisplaySchema> = emptyMap(),
    ) {
        val errors: List<TtrpDiagnostic> get() = diagnostics.filter { it.severity == Severity.ERROR }
    }

    fun check(
        source: String,
        fileName: String = "<memory>",
        manifestDiagnostics: List<TtrpDiagnostic> = emptyList(),
    ): Report {
        // A marked bare fragment file (.ttr.sql/.ttr.py/.ttrb) desugars to a canonical wrapper program
        // (T6.3.3, C0); the wrapper is derived — the file's source text is never rewritten (C2-f).
        val synth =
            org.tatrman.ttrp.dialect.bare.WrapperSynthesizer
                .synthesize(fileName, source, manifest)
        val effectiveSource = synth?.wrapperSource ?: source

        val parsed = TtrpParser.parseString(effectiveSource, fileName)
        val diags = mutableListOf<TtrpDiagnostic>()
        diags += manifestDiagnostics
        synth?.let { diags += it.diagnostics }
        diags += parsed.diagnostics
        // TTR-B action sentences become container OUT ports + program-level display wiring (AG B4).
        val doc =
            org.tatrman.ttrp.dialect.b.FragmentActionWiring
                .apply(parsed.document)

        // ---- world selection + resolution (WLD) ----
        val pin = doc.statements.filterIsInstance<UsesWorld>().firstOrNull()
        val selection =
            TtrpWorldResolver.resolve(modelIndex?.snapshot, manifest, pin?.world, pin?.location)
        diags += selection.diagnostics
        val world = selection.world

        // ---- imports (RES-006) ----
        val imports = mutableListOf<ImportScope>()
        for (imp in doc.statements.filterIsInstance<ImportDecl>()) {
            val scope = ModelIndex.importScope(imp.qname.parts, imp.qname.text)
            if (modelIndex != null && !modelIndex.packageExists(scope.pkg)) {
                diags +=
                    diag(TtrpDiagnosticId.RES_006, "import `${imp.qname.text}.*` resolves to no package", imp.location)
            } else {
                imports += scope
            }
        }

        // ---- program-level declared schemas (SCH-001 duplicates, SCH-003 bad types) ----
        val programSchemas = mutableMapOf<String, List<Column>>()
        val seenSchemaNames = mutableSetOf<String>()
        for (s in doc.statements.filterIsInstance<org.tatrman.ttrp.ast.SchemaDecl>()) {
            if (!seenSchemaNames.add(s.name)) {
                diags += diag(TtrpDiagnosticId.SCH_001, "duplicate program schema `${s.name}`", s.location)
            }
            programSchemas[s.name] = columnsOf(s.columns, diags)
        }

        val rewrites = mutableListOf<ErRewrite>()
        val varSchema = mutableMapOf<String, List<Column>?>()
        val schemasByScope = mutableMapOf<String, MutableMap<String, List<Column>?>>()
        val ctx = Ctx(world, imports, programSchemas, varSchema, schemasByScope, rewrites, diags)

        // ---- resolution + dataflow pass ----
        for (stmt in doc.statements) {
            when (stmt) {
                is ContainerDecl -> resolveContainer(stmt, ctx)
                is Assignment -> assign(stmt.target, stmt.chain.elements, ctx, scope = "")
                is ChainStmt -> evalChain(stmt.chain.elements, ctx, varSchema)
                else -> Unit
            }
        }

        // ---- action displays (DSP, grammar 0.14): display(<name>) held to an imported row schema ----
        val displaySchemas = checkDisplays(doc, world, imports, programSchemas, diags)

        // ---- expression typing via the resolved schema source (EXP/FN/AGG/TYP) + MD dot-paths ----
        // `asof` is the compile-time parameter (D17): the manifest's declared value, else defaulted
        // from the injectable compile-pass clock; threaded verbatim to the resolver. The MdModel /
        // member snapshot are injection seams (production loading is a later stage) — MD resolution
        // is a no-op until a model is supplied.
        val asof = manifest.mdAsof ?: clock.instant()
        // Connected mode: take the catalog's snapshot once, at the resolved compile-pass `asof`
        // (contracts §7.1). A directly-injected `memberSnapshot` wins (S3-A fixtures); else the catalog;
        // else disconnected (null). GI-19 degradation (S6-B): a catalog unreachable at pass start throws
        // CatalogUnavailable — a hard error, let it propagate (the CLI surfaces it cleanly); a mid-session
        // loss serves a held snapshot + signals staleness, which becomes a TTRP-MD-013 warning below.
        val staleSignals = mutableListOf<org.tatrman.ttr.md.resolve.StaleSnapshot>()
        val snapshot = memberSnapshot ?: memberCatalog?.snapshot(asof) { staleSignals += it }
        val mdContext = MdContext(mdModel, snapshot, asof)
        val resolved = ResolvedSchemaSource(varSchema)
        val exprCheck = TtrpFrontend.checkExpressions(doc, resolved, mdContext)
        diags += exprCheck.diagnostics
        for (sig in staleSignals) {
            diags +=
                TtrpDiagnostic(
                    id = TtrpDiagnosticId.MD_013,
                    severity = Severity.WARNING,
                    message =
                        "member catalog lost mid-session — compiling against the held snapshot " +
                            "(fingerprint ${sig.heldFingerprint}, asof ${sig.heldAsof})",
                    location = org.tatrman.ttrp.ast.SourceLocation.UNKNOWN,
                    suggestedAlternative = "re-run once the catalog is reachable to refresh members",
                )
        }

        return Report(
            doc,
            diags,
            world,
            rewrites,
            schemasByScope.mapValues { it.value.toMap() },
            modelIndex,
            exprCheck.mdResolutions,
            // Record the resolved asof + snapshot fingerprint only when an MD model is active — a
            // non-MD program carries no MD staleness anchor (BundleAssembler emits no `md` block).
            mdAsof = if (mdModel != null) asof else null,
            memberFingerprint = snapshot?.fingerprint,
            loadSources = ctx.loadSources.toMap(),
            displaySchemas = displaySchemas,
        )
    }

    private class Ctx(
        val world: ResolvedWorld?,
        val imports: List<ImportScope>,
        val programSchemas: Map<String, List<Column>>,
        val varSchema: MutableMap<String, List<Column>?>,
        /** Resolved schemas partitioned by scope label (`""` = program, else container name). */
        val schemasByScope: MutableMap<String, MutableMap<String, List<Column>?>>,
        val rewrites: MutableList<ErRewrite>,
        val diags: MutableList<TtrpDiagnostic>,
        val varEntity: MutableMap<String, Entity> = mutableMapOf(),
        /** The er entity a chain just loaded (set by `load`, consumed by the enclosing assignment). */
        var pendingEntity: Entity? = null,
        /**
         * The display pass ([checkDisplays]) re-evaluates the program in a throwaway context with sharper row
         * types than the main pass keeps: `select` narrows, a program-level `c.port` ref reads the container's
         * port schema, and an IN port is seeded from the OUT port wired into it. Off for the main pass, whose
         * schemas feed expression typing (unchanged).
         */
        val shadow: Boolean = false,
        val containerNames: Set<String> = emptySet(),
        /** Display pass: (container, IN port) → the (container, OUT port) wired into it at program level. */
        val inPortFeeds: Map<Pair<String, String>, Pair<String, String>> = emptyMap(),
        /** Display pass: every `display(...)` op met, with the row type flowing into it (null = unknown). */
        val displaySites: MutableList<DisplaySite>? = null,
    ) {
        /** AG-P0: load-op location → the model object's physical source (see [Report.loadSources]). */
        val loadSources = LinkedHashMap<org.tatrman.ttrp.ast.SourceLocation, LoadSource>()

        /** Record a name→schema binding both in the flat resolution map and its scope partition. */
        fun bindSchema(
            scope: String,
            name: String,
            cols: List<Column>?,
        ) {
            varSchema[name] = cols
            schemasByScope.getOrPut(scope) { mutableMapOf() }[name] = cols
        }
    }

    // ----- containers -----

    private fun resolveContainer(
        c: ContainerDecl,
        ctx: Ctx,
    ) {
        // target position: engine (RES-003).
        resolveTarget(c.target.parts.last(), c.target.location, ctx)
        val body = c.body
        // A FlowBody, or a P6-decomposed fragment, resolves its (canonical) statements
        // against the container in-ports (C2-d-iii: ports-as-tables). An undecomposed
        // fragment (ttrb — P7) stays opaque here.
        val statements =
            when {
                body is FlowBody -> body.statements
                body is org.tatrman.ttrp.ast.FragmentBody -> body.decomposition?.statements
                else -> null
            }
        if (statements != null) {
            // in-ports start unknown (fragment/wiring-fed; interior schema deferred to Stage 2) — except in the
            // display pass, which seeds each from the OUT port wired into it (resolved earlier, topo order).
            for (p in c.ports) {
                if (p.kind != PortKind.IN) continue
                val feed = ctx.inPortFeeds[c.name to p.name]
                ctx.bindSchema(c.name, p.name, feed?.let { ctx.schemasByScope[it.first]?.get(it.second) })
            }
            var last: List<Column>? = null
            // `… -> <out port>` is `<out port> = …` (ChainToPort) — the port carries the chain's row type.
            val outPorts =
                c.ports
                    .filter { it.kind == PortKind.OUT }
                    .map { it.name }
                    .toSet()
            for (authored in statements) {
                val stmt =
                    (authored as? ChainStmt)?.let {
                        org.tatrman.ttrp.ast.ChainToPort
                            .asAssignment(it, outPorts)
                    }
                        ?: authored
                when (stmt) {
                    is Assignment -> {
                        assign(stmt.target, stmt.chain.elements, ctx, scope = c.name)
                        last = ctx.schemasByScope[c.name]?.get(stmt.target)
                    }
                    is ChainStmt -> last = evalChain(stmt.chain.elements, ctx, ctx.varSchema)
                    else -> Unit
                }
            }
            // The single default DATA out maps to the body's final value when no `<out> = …` bound it (the
            // graph builder's rule) — the display pass needs that port's row type too.
            if (ctx.shadow) {
                val out = c.ports.firstOrNull { it.kind == PortKind.OUT }?.name
                if (out != null &&
                    ctx.schemasByScope[c.name]?.containsKey(out) != true
                ) {
                    ctx.bindSchema(c.name, out, last)
                }
            }
        }
    }

    /** Binds a chain's output schema to [target] in [scope], recording the underlying er entity (if any). */
    private fun assign(
        target: String,
        elements: List<org.tatrman.ttrp.ast.ChainElem>,
        ctx: Ctx,
        scope: String,
    ) {
        ctx.pendingEntity = null
        ctx.bindSchema(scope, target, evalChain(elements, ctx, ctx.varSchema))
        ctx.pendingEntity?.let { ctx.varEntity[target] = it }
    }

    private fun resolveTarget(
        name: String,
        loc: SourceLocation,
        ctx: Ctx,
    ) {
        val world = ctx.world ?: return
        if (world.engines.any { it.qname.name == name }) return
        val asStorage = world.storages.firstOrNull { it.qname.name == name }
        if (asStorage != null) {
            ctx.diags +=
                diag(
                    TtrpDiagnosticId.RES_003,
                    "`target` expects an engine; `$name` is a storage",
                    loc,
                )
        } else {
            ctx.diags += diag(TtrpDiagnosticId.RES_003, "no engine named `$name` in the world", loc)
        }
    }

    // ----- chain evaluation -----

    /** Evaluates a chain to its output schema, resolving refs and threading dataflow. */
    private fun evalChain(
        elements: List<org.tatrman.ttrp.ast.ChainElem>,
        ctx: Ctx,
        scope: MutableMap<String, List<Column>?>,
    ): List<Column>? {
        var prevOut: List<Column>? = null
        for (elem in elements) {
            prevOut =
                when (elem) {
                    is OpCall -> resolveOp(elem, prevOut, ctx, scope)
                    is DottedRef -> refSchema(elem, ctx, scope)
                }
        }
        return prevOut
    }

    /**
     * A chain-element ref's row type: a variable / in-scope port by its head (`x`, `b.true` → `b`). In the
     * display pass a program-level `container.port` ref reads that container's port schema; the main pass
     * leaves it unknown (wiring `node.port` → null), as before.
     */
    private fun refSchema(
        ref: DottedRef,
        ctx: Ctx,
        scope: MutableMap<String, List<Column>?>,
    ): List<Column>? {
        val head = ref.parts.first()
        if (ctx.shadow && ref.parts.size >= 2 && head in ctx.containerNames && !scope.containsKey(head)) {
            return ctx.schemasByScope[head]?.get(ref.parts[1])
        }
        return scope[head]
    }

    private fun resolveOp(
        op: OpCall,
        prevOut: List<Column>?,
        ctx: Ctx,
        scope: MutableMap<String, List<Column>?>,
    ): List<Column>? =
        when (op.name) {
            "load" -> resolveLoad(op, ctx, scope)
            "store" -> {
                resolveStore(op, ctx)
                null
            }
            // A display is a sink: its argument is the display NAME, never a source. The display pass records
            // the row type flowing into it (the chain predecessor).
            "display" -> {
                ctx.displaySites?.add(DisplaySite(op, prevOut))
                null
            }
            "select" -> if (ctx.shadow) selectOutput(op, prevOut, scope) else inputOf(op, prevOut, scope)
            "join" -> resolveJoin(op, ctx, scope)
            "aggregate" -> aggregateOutput(op, inputOf(op, prevOut, scope), ctx)
            "calc" -> calcOutput(op, inputOf(op, prevOut, scope))
            "union" -> firstSource(op, scope) ?: prevOut
            "branch", "filter", "sort", "distinct", "limit", "sample", "head", "tail" -> {
                sourceVar(op)?.let { ctx.varEntity[it] }?.let { recordAttributeRewrites(op, it, ctx) }
                inputOf(op, prevOut, scope)
            }
            else -> inputOf(op, prevOut, scope)
        }

    /** The bare source variable name of a single-input op (its first unnamed arg). */
    private fun sourceVar(op: OpCall): String? {
        val first = op.args.firstOrNull { it.name == null }?.value as? ExprArg ?: return null
        return (first.expr as? ColumnRef)?.takeIf { it.port == null }?.column
    }

    /**
     * er→db attribute rewrite (E-d): for an op whose input is an er entity, every
     * attribute reference in a predicate/config expr is rewritten to its db column
     * (mandatory provenance). An attribute with no er2db binding on a bound entity is
     * `TTRP-RES-005` (the entity-load path covers the unbound-entity case).
     */
    private fun recordAttributeRewrites(
        op: OpCall,
        entity: Entity,
        ctx: Ctx,
    ) {
        val entityBound = modelIndex?.erToDb(entity.qname)?.dbQname != null
        if (!entityBound) return
        val refs = mutableListOf<ColumnRef>()
        val sourceArg = op.args.firstOrNull { it.name == null }
        for (arg in op.args) {
            if (arg === sourceArg) continue // the data source, not a predicate
            (arg.value as? ExprArg)?.let { refs += exprColumnRefs(it.expr) }
        }
        op.config?.entries?.forEach { e ->
            if (e is org.tatrman.ttrp.ast.AssignEntry) refs += exprColumnRefs(e.value)
        }
        for (ref in refs) {
            val attr =
                entity.attributes.firstOrNull { it.qname.name.substringAfterLast('.') == ref.column } ?: continue
            val binding = modelIndex!!.erToDb(attr.qname)
            if (binding.dbQname == null) {
                ctx.diags +=
                    diag(
                        TtrpDiagnosticId.RES_005,
                        "attribute `${entity.qname.name}.${ref.column}` has no er2db binding reachable",
                        ref.location,
                    )
            } else {
                ctx.rewrites +=
                    ErRewrite(
                        erSpelling = ref.column,
                        dbSpelling = binding.dbQname!!.name.substringAfterLast('.'),
                        provenance =
                            Provenance("er.entity.${attr.qname.name}", ref.column, ref.location),
                        location = ref.location,
                    )
            }
        }
    }

    private fun exprColumnRefs(e: org.tatrman.ttrp.expr.Expression): List<ColumnRef> =
        when (e) {
            is ColumnRef -> listOf(e)
            is org.tatrman.ttrp.expr.FunctionCall -> e.args.flatMap { exprColumnRefs(it) }
            is org.tatrman.ttrp.expr.AggregateCall -> e.args.flatMap { exprColumnRefs(it) }
            is org.tatrman.ttrp.expr.Cast -> exprColumnRefs(e.expr)
            is org.tatrman.ttrp.expr.CaseWhen ->
                e.branches.flatMap { exprColumnRefs(it.first) + exprColumnRefs(it.second) } +
                    (e.elseExpr?.let { exprColumnRefs(it) } ?: emptyList())
            is org.tatrman.ttrp.expr.InList -> exprColumnRefs(e.expr) + e.items.flatMap { exprColumnRefs(it) }
            is org.tatrman.ttrp.expr.IsNull -> exprColumnRefs(e.expr)
            is org.tatrman.ttrp.expr.Literal -> emptyList()
            // MD dot-path is not a column ref; MD resolution is a separate pass (S3, R23).
            is org.tatrman.ttrp.expr.MdPath -> emptyList()
        }

    /** The single-input schema of an op: its bare source arg, else the chain predecessor. */
    private fun inputOf(
        op: OpCall,
        prevOut: List<Column>?,
        scope: MutableMap<String, List<Column>?>,
    ): List<Column>? = firstSource(op, scope) ?: prevOut

    private fun firstSource(
        op: OpCall,
        scope: MutableMap<String, List<Column>?>,
    ): List<Column>? {
        val first = op.args.firstOrNull { it.name == null }?.value
        if (first is ExprArg) {
            val ref = first.expr as? ColumnRef ?: return null
            if (ref.port == null) return scope[ref.column]
        }
        return null
    }

    // ----- load -----

    private fun resolveLoad(
        op: OpCall,
        ctx: Ctx,
        scope: MutableMap<String, List<Column>?>,
    ): List<Column>? {
        val srcArg = op.args.firstOrNull { it.name == null } ?: return null
        val parts = refParts(srcArg) ?: return null
        val loc = srcArg.location
        val schemaArg = op.args.filter { it.name == "schema" }

        if (schemaArg.size > 1) {
            ctx.diags += diag(TtrpDiagnosticId.SCH_001, "more than one `schema:` on one load", loc)
        }

        if (parts.size == 1) {
            // Bare load: a model object (db table / er entity) by imported simple name (D-b-iii).
            val head = parts[0]
            // No-first-wins (C2-d/D-b): a case-insensitive same-name clash across imports is ambiguous.
            val clash = modelIndex?.findLoadableCi(head, ctx.imports)?.distinctBy { it.qname.name } ?: emptyList()
            if (clash.size > 1) {
                ctx.diags +=
                    diag(
                        TtrpDiagnosticId.RES_002,
                        "`$head` is ambiguous — it matches ${clash.joinToString(", ") { it.qname.name }}; qualify it",
                        loc,
                    )
                return null
            }
            val objs = ctx.imports.let { modelIndex?.findLoadable(head, it) } ?: emptyList()
            when {
                objs.size == 1 -> return loadModelObject(objs[0], loc, ctx, op.location)
                objs.size > 1 -> {
                    ctx.diags +=
                        diag(
                            TtrpDiagnosticId.RES_002,
                            "`$head` is ambiguous — exported by ${objs.size} imports; qualify it",
                            loc,
                        )
                    return null
                }
                else -> {
                    // A storage bare-load, or nothing.
                    val storage = ctx.world?.storages?.firstOrNull { it.qname.name == head }
                    if (storage == null) {
                        ctx.diags += diag(TtrpDiagnosticId.RES_001, "no storage or model object named `$head`", loc)
                    }
                    return null
                }
            }
        }

        // Dotted head.member.
        val head = parts[0]
        val member = parts.drop(1).joinToString(".")
        val storage = ctx.world?.storages?.firstOrNull { it.qname.name == head }
        if (storage != null) {
            return resolveStorageLoad(storage, member, schemaArg.firstOrNull(), loc, ctx)
        }
        // Full-qname model object (e.g. erp.accounts): pkg = all-but-last, name = last.
        val pkg = parts.dropLast(1).joinToString(".")
        val objs = modelIndex?.findByPackage(pkg, parts.last()) ?: emptyList()
        if (objs.size == 1) return loadModelObject(objs[0], loc, ctx, op.location)
        ctx.diags +=
            diag(TtrpDiagnosticId.RES_001, "no storage or model object named `${parts.joinToString(".")}`", loc)
        return null
    }

    private fun loadModelObject(
        obj: org.tatrman.ttr.metadata.model.ModelObject,
        loc: SourceLocation,
        ctx: Ctx,
        opLocation: SourceLocation? = null,
    ): List<Column>? {
        if (opLocation != null) {
            modelIndex?.let { idx -> LoadSourceResolver.resolve(obj, idx)?.let { ctx.loadSources[opLocation] = it } }
        }
        return loadModelObjectSchema(obj, loc, ctx)
    }

    private fun loadModelObjectSchema(
        obj: org.tatrman.ttr.metadata.model.ModelObject,
        loc: SourceLocation,
        ctx: Ctx,
    ): List<Column>? =
        when (obj) {
            is DbTable -> modelIndex!!.tableColumns(obj).map { Column(it.first, TtrpType.parse(it.second)) }
            is Entity -> {
                ctx.pendingEntity = obj
                // er entity → db table via er2db (E-d early rewrite, mandatory provenance).
                val binding = modelIndex!!.erToDb(obj.qname)
                if (binding.dbQname == null) {
                    ctx.diags +=
                        diag(
                            TtrpDiagnosticId.RES_005,
                            "entity `${obj.qname.namespace}.${obj.qname.name}` has no er2db binding reachable" +
                                (ctx.world?.let { " in world `${it.qname.`package`}.${it.qname.name}`" } ?: ""),
                            loc,
                        )
                } else {
                    ctx.rewrites +=
                        ErRewrite(
                            erSpelling = obj.qname.name,
                            dbSpelling = binding.dbQname!!.name,
                            provenance = Provenance("er.${obj.qname.namespace}.${obj.qname.name}", obj.qname.name, loc),
                            location = loc,
                        )
                }
                // Schema for typing stays er-named (logical attributes) at Stage 1.3.
                modelIndex.entityAttributes(obj).map { Column(it.first, TtrpType.parse(it.second)) }
            }
            else -> null
        }

    private fun resolveStorageLoad(
        storage: ResolvedStorage,
        member: String,
        schemaArg: Arg?,
        loc: SourceLocation,
        ctx: Ctx,
    ): List<Column>? {
        // Schema precedence (D-c): inline > named-in-program > world-declared.
        val inline = schemaArg?.value as? SchemaLiteralArg
        if (inline != null) return columnsOf(inline.columns, ctx.diags)

        val schemaName = (schemaArg?.value as? ExprArg)?.let { (it.expr as? ColumnRef)?.column } ?: member
        ctx.programSchemas[schemaName]?.let { return it }
        val worldSchema = storage.schemas.firstOrNull { it.qname.name.substringAfterLast('.') == schemaName }
        if (worldSchema != null) {
            return worldSchema.fields.map { Column(it.key, TtrpType.parse(it.value)) }
        }
        // No schema resolved anywhere.
        if (schemaArg != null) {
            ctx.diags +=
                diag(TtrpDiagnosticId.RES_001, "no schema named `$schemaName` — checked program and world", loc)
        } else if (storage.schemas.isNotEmpty()) {
            ctx.diags +=
                diag(
                    TtrpDiagnosticId.RES_001,
                    "no dataset `$member` on storage `${storage.qname.name}` and no schema given",
                    loc,
                )
        } else {
            ctx.diags +=
                diag(
                    TtrpDiagnosticId.SCH_002,
                    "ad-hoc load of `${storage.qname.name}.$member` has no schema anywhere",
                    loc,
                )
        }
        return null
    }

    // ----- store -----

    private fun resolveStore(
        op: OpCall,
        ctx: Ctx,
    ) {
        val srcArg = op.args.firstOrNull { it.name == null } ?: return
        val parts = refParts(srcArg) ?: return
        val head = parts[0]
        val world = ctx.world ?: return
        if (world.storages.any { it.qname.name == head }) return
        if (world.engines.any { it.qname.name == head }) {
            ctx.diags +=
                diag(TtrpDiagnosticId.MOV_001, "`store` expects a storage; `$head` is an engine", srcArg.location)
        } else {
            ctx.diags += diag(TtrpDiagnosticId.RES_001, "no storage named `$head`", srcArg.location)
        }
    }

    // ----- join + relation -----

    private fun resolveJoin(
        op: OpCall,
        ctx: Ctx,
        scope: MutableMap<String, List<Column>?>,
    ): List<Column>? {
        val left = (op.args.firstOrNull { it.name == "left" }?.value as? ExprArg)?.let { colName(it) }
        val right = (op.args.firstOrNull { it.name == "right" }?.value as? ExprArg)?.let { colName(it) }
        // on: relation X → er relation between the joined entities (RES-004) + rewrite.
        val onArg = op.args.firstOrNull { it.name == "on" }
        val rel = onArg?.value as? RelationArg
        if (rel != null) {
            resolveRelation(rel, left, right, ctx)
        }
        val leftCols = left?.let { scope[it] }
        val rightCols = right?.let { scope[it] }
        return merge(leftCols, rightCols)
    }

    private fun resolveRelation(
        rel: RelationArg,
        leftVar: String?,
        rightVar: String?,
        ctx: Ctx,
    ) {
        val name = rel.qname.parts.last()
        val relations = modelIndex?.findRelations(name, ctx.imports) ?: emptyList()
        if (relations.isEmpty()) {
            ctx.diags +=
                diag(TtrpDiagnosticId.RES_004, "no relation named `$name` between the joined entities", rel.location)
            return
        }
        val leftEntity = leftVar?.let { ctx.varEntity[it] }
        val rightEntity = rightVar?.let { ctx.varEntity[it] }
        if (leftEntity != null && rightEntity != null) {
            val endpoints = setOf(leftEntity.qname.name, rightEntity.qname.name)
            val match =
                relations.firstOrNull {
                    setOf(it.fromEntity.name, it.toEntity.name) == endpoints
                }
            if (match == null) {
                val r = relations.first()
                ctx.diags +=
                    diag(
                        TtrpDiagnosticId.RES_004,
                        "relation `$name` is between `${r.fromEntity.name}` and `${r.toEntity.name}`, " +
                            "not `${leftEntity.qname.name}` and `${rightEntity.qname.name}`",
                        rel.location,
                    )
                return
            }
            synthesizeJoinCondition(match, leftEntity, rightEntity, rel, ctx)
        }
    }

    /**
     * The `on: relation X` → port-qualified join-condition `Expression` synthesis
     * (T2.1.0, review-001 1.3-A). For each of the relation's `joinPairs`, resolve
     * both er attributes to their db columns via er2db (E-d) and emit a
     * `left.<col> = right.<col>` equality (`op.eq`); AND them together (`op.and`).
     * The `left`/`right` ports follow which join arm loaded the relation's from/to
     * entity. Mandatory provenance (E-d) lets the condition render er-first. A
     * binding miss on any endpoint is `TTRP-RES-005` (E-d "bind it or reference db").
     */
    private fun synthesizeJoinCondition(
        match: Relation,
        leftEntity: Entity,
        rightEntity: Entity,
        rel: RelationArg,
        ctx: Ctx,
    ) {
        val name = match.qname.name

        // Map the relation's fromEntity/toEntity onto the join's left/right ports. For a
        // self-relation (both arms load the same entity) entity identity can't disambiguate
        // orientation, so fall back to the relation's own orientation: from → left, to → right.
        val fromPort: String?
        val toPort: String?
        if (leftEntity.qname.name == rightEntity.qname.name) {
            fromPort = "left"
            toPort = "right"
        } else {
            fun portFor(entity: String): String? =
                when (entity) {
                    leftEntity.qname.name -> "left"
                    rightEntity.qname.name -> "right"
                    else -> null
                }
            fromPort = portFor(match.fromEntity.name)
            toPort = portFor(match.toEntity.name)
        }
        val eqs = mutableListOf<org.tatrman.ttrp.expr.Expression>()
        val logicalEqs = mutableListOf<org.tatrman.ttrp.expr.Expression>()
        val erSides = mutableListOf<String>()
        val dbSides = mutableListOf<String>()
        for (pair in match.joinPairs) {
            val fromCol = modelIndex?.erToDb(pair.fromAttr)?.dbQname
            val toCol = modelIndex?.erToDb(pair.toAttr)?.dbQname
            if (fromCol == null) {
                ctx.diags +=
                    diag(
                        TtrpDiagnosticId.RES_005,
                        "join key `${pair.fromAttr.name}` has no er2db binding reachable",
                        rel.location,
                    )
                return
            }
            if (toCol == null) {
                ctx.diags +=
                    diag(
                        TtrpDiagnosticId.RES_005,
                        "join key `${pair.toAttr.name}` has no er2db binding reachable",
                        rel.location,
                    )
                return
            }
            val fromColName = fromCol.name.substringAfterLast('.')
            val toColName = toCol.name.substringAfterLast('.')
            eqs +=
                org.tatrman.ttrp.expr.FunctionCall(
                    function = org.tatrman.ttrp.expr.CatalogId.EQ,
                    args =
                        listOf(
                            ColumnRef(fromPort, fromColName, rel.location),
                            ColumnRef(toPort, toColName, rel.location),
                        ),
                    location = rel.location,
                )
            logicalEqs +=
                org.tatrman.ttrp.expr.FunctionCall(
                    function = org.tatrman.ttrp.expr.CatalogId.EQ,
                    args =
                        listOf(
                            ColumnRef(fromPort, pair.fromAttr.name.substringAfterLast('.'), rel.location),
                            ColumnRef(toPort, pair.toAttr.name.substringAfterLast('.'), rel.location),
                        ),
                    location = rel.location,
                )
            erSides += "${pair.fromAttr.name} = ${pair.toAttr.name}"
            dbSides += "${fromPort ?: "?"}.$fromColName = ${toPort ?: "?"}.$toColName"
        }
        val condition =
            eqs.reduceOrNull { a, b ->
                org.tatrman.ttrp.expr
                    .FunctionCall(org.tatrman.ttrp.expr.CatalogId.AND, listOf(a, b), rel.location)
            }
        ctx.rewrites +=
            ErRewrite(
                erSpelling = erSides.joinToString(" and ").ifEmpty { name },
                dbSpelling = dbSides.joinToString(" and ").ifEmpty { "join-condition($name)" },
                provenance = Provenance("er.relation.$name", name, rel.location),
                location = rel.location,
                joinCondition = condition,
                logicalJoinCondition =
                    logicalEqs.reduceOrNull { a, b ->
                        org.tatrman.ttrp.expr
                            .FunctionCall(org.tatrman.ttrp.expr.CatalogId.AND, listOf(a, b), rel.location)
                    },
            )
    }

    // ----- aggregate output -----

    private fun aggregateOutput(
        op: OpCall,
        input: List<Column>?,
        @Suppress("UNUSED_PARAMETER") ctx: Ctx,
    ): List<Column> {
        val config = op.config ?: return input ?: emptyList()
        val out = mutableListOf<Column>()
        val schemaMap = input?.let { mapOf("" to it) }
        for (entry in config.entries) {
            when (entry) {
                is org.tatrman.ttrp.ast.GroupByEntry ->
                    entry.keys.forEach { key ->
                        val t = input?.firstOrNull { it.name == key }?.type ?: TtrpType.Str
                        out += Column(key, t)
                    }
                is org.tatrman.ttrp.ast.AssignEntry -> {
                    val t =
                        typechecker.check(entry.value, schemaMap, aggregatesAllowed = true).type
                            ?: TtrpType.Named("agg")
                    out += Column(entry.name, t)
                }
            }
        }
        return out
    }

    /**
     * `select(a, b, …)` keeps exactly the listed columns, in that order (display pass only — the main pass keeps
     * the input row type, as it always has). A bare first arg naming an in-scope variable is the source when the
     * chain supplies none (`select(v, a, b)`); a listed column the input lacks comes out untyped.
     */
    private fun selectOutput(
        op: OpCall,
        prevOut: List<Column>?,
        scope: MutableMap<String, List<Column>?>,
    ): List<Column>? {
        var refs = op.args.mapNotNull { (it.value as? ExprArg)?.expr as? ColumnRef }.filter { it.port == null }
        var input = prevOut
        if (input == null) {
            val first = refs.firstOrNull()
            if (first != null && op.args.firstOrNull()?.name == null && scope.containsKey(first.column)) {
                input = scope[first.column]
                refs = refs.drop(1)
            }
        }
        if (input == null) return null
        val byName = input.associateBy { it.name }
        return refs.map { byName[it.column] ?: Column(it.column, TtrpType.Named("")) }
    }

    // ----- action displays (DSP, grammar 0.14) -----

    /**
     * The display pass. Re-evaluates the program in a throwaway context ([Ctx.shadow]: sharper row types, its
     * diagnostics/rewrites discarded) — containers in wiring order so each IN port sees the OUT port feeding
     * it — collecting every `display(...)` site with the row type flowing into it. Then, per site whose name is
     * a row schema an import brings into scope, holds the rows to it (DSP-001/002/003); a name with several
     * sources that is NOT such a schema is DSP-004; a name two imports declare is RES-002. An unknown input
     * row type is deferred (no diagnostic here); the emitted display still never NULL-fills a required column:
     * the `sql-text` and Postgres emitters refuse the build (TTRP-DSP-001, from the port's static columns) and a
     * Polars island, whose frames carry no static columns, stops before it writes the display file.
     */
    private fun checkDisplays(
        doc: TtrpDocument,
        world: ResolvedWorld?,
        imports: List<ImportScope>,
        programSchemas: Map<String, List<Column>>,
        diags: MutableList<TtrpDiagnostic>,
    ): Map<SourceLocation, DisplaySchema> {
        val containers = doc.statements.filterIsInstance<ContainerDecl>()
        val names = containers.map { it.name }.toSet()
        // Program-level wiring `a.p -> b.q`: (b, q) is fed by (a, p).
        val feeds = LinkedHashMap<Pair<String, String>, Pair<String, String>>()
        for (stmt in doc.statements) {
            val elems = (stmt as? ChainStmt)?.chain?.elements ?: continue
            for ((x, y) in elems.zipWithNext()) {
                val from = x as? DottedRef ?: continue
                val to = y as? DottedRef ?: continue
                if (from.parts.size == 2 && to.parts.size == 2 && from.parts[0] in names && to.parts[0] in names) {
                    feeds.putIfAbsent(to.parts[0] to to.parts[1], from.parts[0] to from.parts[1])
                }
            }
        }
        val ctx =
            Ctx(
                world,
                imports,
                programSchemas,
                mutableMapOf(),
                mutableMapOf(),
                mutableListOf(),
                mutableListOf(),
                shadow = true,
                containerNames = names,
                inPortFeeds = feeds,
                displaySites = mutableListOf(),
            )
        for (c in wiringOrder(containers, feeds)) resolveContainer(c, ctx)
        for (stmt in doc.statements) {
            when (stmt) {
                is Assignment -> assign(stmt.target, stmt.chain.elements, ctx, scope = "")
                is ChainStmt -> evalChain(stmt.chain.elements, ctx, ctx.varSchema)
                else -> Unit
            }
        }
        val sites = ctx.displaySites!!.sortedWith(compareBy({ it.op.location.line }, { it.op.location.column }))

        val out = LinkedHashMap<SourceLocation, DisplaySchema>()
        for ((name, group) in sites.groupBy { displayName(it.op) }) {
            val candidates = modelIndex?.findRowSchemas(name, imports) ?: emptyList()
            if (candidates.size > 1) {
                val all = candidates.joinToString(", ") { it.qualifiedName }
                diags +=
                    diag(
                        TtrpDiagnosticId.RES_002,
                        "display `$name` is ambiguous — schemas $all are all imported; import only one",
                        group.first().op.location,
                    )
                continue
            }
            val record = candidates.singleOrNull()
            if (record == null) {
                // An ordinary (evidence) display: unchanged — but one name, several sources, is ambiguous.
                group.drop(1).forEach { site ->
                    diags +=
                        diag(
                            TtrpDiagnosticId.DSP_004,
                            "display `$name` has ${group.size} sources but `$name` is not a declared row schema — " +
                                "an ordinary display takes one source (first at line ${group.first().op.location.line})",
                            site.op.location,
                        )
                }
                continue
            }
            val schema =
                DisplaySchema(
                    name = record.name,
                    qualifiedName = record.qualifiedName,
                    columns =
                        record.columns.map {
                            DisplaySchemaColumn(it.name, it.type, it.optional, it.length, it.precision)
                        },
                )
            // A column no engine can produce (object / list / an unknown type id) — every build of this display
            // would fail at emit, pointing at a cast the author never wrote. Reported once, at the first source.
            for (col in schema.columns.filter { it.columnType == null }) {
                diags +=
                    diag(
                        TtrpDiagnosticId.DSP_005,
                        "display `$name`: column `${col.name}` of schema `${schema.qualifiedName}` is " +
                            "`${col.spelling}`, not a scalar column type",
                        group.first().op.location,
                    )
            }
            for (site in group) {
                out[site.op.location] = schema
                checkDisplayRows(name, schema, site, diags)
            }
        }
        return out
    }

    /** DSP-001/002/003 for one action-display site; an unknown input row type is deferred. */
    private fun checkDisplayRows(
        name: String,
        schema: DisplaySchema,
        site: DisplaySite,
        diags: MutableList<TtrpDiagnostic>,
    ) {
        val input = site.input ?: return
        val loc = site.op.location
        val byName = input.associateBy { it.name }
        for (col in schema.columns) {
            val src = byName[col.name]
            if (src == null) {
                if (!col.optional) {
                    diags +=
                        diag(
                            TtrpDiagnosticId.DSP_001,
                            "display `$name` is missing column `${col.name}` (${col.spelling}) required by schema " +
                                "`${schema.qualifiedName}`",
                            loc,
                        )
                }
                continue
            }
            val target = col.columnType ?: continue // DSP-005, reported once for the schema
            if (DisplaySchema.assignable(src.type, target) == false) {
                diags +=
                    diag(
                        TtrpDiagnosticId.DSP_002,
                        "display `$name` column `${col.name}` is `${src.type}`, not assignable to `${col.spelling}` " +
                            "declared by schema `${schema.qualifiedName}`",
                        loc,
                    )
            }
        }
        val declared = schema.columns.map { it.name }.toSet()
        val extra = input.map { it.name }.filter { it !in declared }.distinct()
        if (extra.isNotEmpty()) {
            diags +=
                TtrpDiagnostic(
                    TtrpDiagnosticId.DSP_003,
                    Severity.WARNING,
                    "display `$name`: ${extra.joinToString(", ") { "`$it`" }} " +
                        (if (extra.size == 1) "is" else "are") + " not in schema `${schema.qualifiedName}` — " +
                        "dropped from the display's output",
                    loc,
                )
        }
    }

    /** Containers ordered so a container comes after every container wired into it (cycles: source order). */
    private fun wiringOrder(
        containers: List<ContainerDecl>,
        feeds: Map<Pair<String, String>, Pair<String, String>>,
    ): List<ContainerDecl> {
        val deps =
            containers.associate { c ->
                c.name to
                    feeds
                        .filterKeys { it.first == c.name }
                        .values
                        .map { it.first }
                        .toSet()
            }
        val done = LinkedHashSet<String>()
        val byName = containers.associateBy { it.name }
        var progress = true
        while (progress && done.size < containers.size) {
            progress = false
            for (c in containers) {
                if (c.name in done) continue
                if (deps.getValue(c.name).all { it in done || it == c.name || it !in byName }) {
                    done += c.name
                    progress = true
                }
            }
        }
        containers.forEach { done += it.name } // a wiring cycle (TTRP-CTL-002 downstream): fall back to source order
        return done.mapNotNull { byName[it] }
    }

    // ----- helpers -----

    private fun merge(
        left: List<Column>?,
        right: List<Column>?,
    ): List<Column>? {
        // AG-P0 F-AG: an UNKNOWN side (an untyped container IN port, a deferred source) makes the join's
        // row type unknown — returning the known side alone made a partial schema look complete, so every
        // column of the unknown side was then reported "not in scope" (TTRP-EXP-001) downstream.
        if (left == null || right == null) return null
        val combined = left + right
        return combined.distinctBy { it.name }
    }

    /**
     * `calc { x = … }` — add-semantics: the input row type plus each assigned column (an assignment to an
     * existing name re-types it). Unknown input ⇒ unknown output. (AG-P0: previously the calc output was
     * the input alone, so a later reference to `x` was a false TTRP-EXP-001.)
     */
    private fun calcOutput(
        op: OpCall,
        input: List<Column>?,
    ): List<Column>? {
        if (input == null) return null
        val config = op.config ?: return input
        val schemaMap = mapOf("" to input)
        val out = LinkedHashMap<String, Column>()
        input.forEach { out[it.name] = it }
        for (entry in config.entries) {
            if (entry is org.tatrman.ttrp.ast.AssignEntry) {
                val t = typechecker.check(entry.value, schemaMap).type ?: TtrpType.Named("calc")
                out[entry.name] = Column(entry.name, t)
            }
        }
        return out.values.toList()
    }

    private fun columnsOf(
        columns: List<SchemaColumn>,
        diags: MutableList<TtrpDiagnostic>,
    ): List<Column> =
        columns.map { c ->
            val spelling = c.type.substringBefore('(')
            val t = TtrpType.parse(spelling)
            if (t is TtrpType.Named) {
                diags +=
                    diag(
                        TtrpDiagnosticId.SCH_003,
                        "unknown schema type `${c.type}` for column `${c.name}`",
                        c.location,
                    )
            }
            Column(c.name, t)
        }

    private fun refParts(arg: Arg): List<String>? {
        val v = arg.value
        if (v !is ExprArg) return null
        val ref = v.expr as? ColumnRef ?: return null
        return (ref.port?.split('.') ?: emptyList()) + ref.column
    }

    private fun colName(arg: ExprArg): String? = (arg.expr as? ColumnRef)?.takeIf { it.port == null }?.column

    private fun diag(
        id: TtrpDiagnosticId,
        message: String,
        loc: SourceLocation,
        suggestion: String? = id.suggestedAlternative,
    ) = TtrpDiagnostic(id, Severity.ERROR, message, loc, suggestion)
}

/** One `display(...)` op met by the display pass, with the row type flowing into it (null = unknown). */
internal data class DisplaySite(
    val op: OpCall,
    val input: List<Column>?,
)

/** The display name of a `display(<name>)` op — its first bare argument's text (`""` for `display()`). */
fun displayName(op: OpCall): String {
    val expr = (op.args.firstOrNull { it.name == null }?.value as? ExprArg)?.expr ?: return ""
    return when (expr) {
        is ColumnRef -> (expr.port?.let { "$it." } ?: "") + expr.column
        is org.tatrman.ttrp.expr.Literal ->
            when (val v = expr.value) {
                is org.tatrman.ttrp.expr.LiteralValue.Str -> v.value
                is org.tatrman.ttrp.expr.LiteralValue.Num -> v.raw
                is org.tatrman.ttrp.expr.LiteralValue.Bool -> v.value.toString()
                org.tatrman.ttrp.expr.LiteralValue.Null -> "null"
            }
        else -> ""
    }
}

/**
 * An action display's declared row schema (grammar 0.14 `def schema`, resolved through the program's imports):
 * the shape `display(<name>)` rows are held to — and the one shape every source of the display is written in: each
 * engine casts every column to the type [DisplaySchemaColumn.columnType] maps it to.
 */
data class DisplaySchema(
    val name: String,
    val qualifiedName: String,
    val columns: List<DisplaySchemaColumn>,
) {
    companion object {
        /**
         * Is a [source] column assignable to a [target] schema column? Judged on the column vocabulary
         * ([ColumnType.accepts]): the same type (decimal precision/scale ignored); an integer → any integer,
         * decimal or float; any scalar → text (the host renders action fields as text); `datetime` ⇄ `timestamp`.
         * An object / list source is never assignable. Null when the source's type is unknown (an untyped calc /
         * aggregate value, a custom type id) — not judged here.
         */
        fun assignable(
            source: TtrpType,
            target: ColumnType,
        ): Boolean? {
            if (source is TtrpType.Obj || source is TtrpType.Lst) return false
            val from = ColumnType.of(source) ?: return null
            return target.accepts(from)
        }
    }
}

/**
 * One column of a [DisplaySchema]: its TTR-M [type] name, [optional], and — from the structured form
 * `{ type: decimal, length: 19, precision: 2 }` — [length] / [precision] (for a decimal: precision and scale).
 */
data class DisplaySchemaColumn(
    val name: String,
    val type: String,
    val optional: Boolean,
    val length: Int? = null,
    val precision: Int? = null,
) {
    /** The schema's own spelling of the type: the name, with `(length[,precision])` when the schema declares them. */
    val spelling: String
        get() =
            when {
                length != null && precision != null -> "$type($length,$precision)"
                length != null -> "$type($length)"
                else -> type
            }

    /** The scalar type every engine casts this column to; null when it is not one (TTRP-DSP-005). */
    val columnType: ColumnType? get() = ColumnType.of(type, length, precision)
}

/**
 * Feeds the Stage 1.2 typechecker real column lists (T1.3.6): resolved SSA-variable
 * and container-port schemas computed by [TtrpChecker]'s dataflow pass — replacing
 * the hand-fed `DeclaredSchemaSource` seam. A ref with no resolved schema returns
 * null, which the typechecker treats as "deferred" (no false EXP-001).
 */
class ResolvedSchemaSource(
    private val varSchema: Map<String, List<Column>?>,
) : SchemaSource {
    override fun schemaFor(ref: DottedRef): List<Column>? = varSchema[ref.parts.joinToString(".")]
}
