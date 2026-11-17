// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*

/** L1 BPMN skeleton. All governed computation/writes are named run-API calls; engine adapters stay external. */
internal class CamundaRenderer(
    private val request: RecipeEmitRequest,
    private val d: Dialect,
    /** The estate's process engine (D-145): C7, egress-bound HTTP steps (D-158), start groups (⚑R226-A). */
    private val engine: Boolean = false,
) {
    init {
        require(!engine || d == Dialect.C7) { "the process engine target renders the C7 dialect" }
    }

    /**
     * The engine's egress (D-158): the run API's address as the engine's HTTP steps see it. The egress injects the
     * `process-engine` bearer, so these steps carry none. Engine target only; refused when absent or not a plain URL.
     */
    private val egress: String by lazy {
        val where = "deployment ${root.deployment.name}: executor ${root.deployment.target}"
        val raw =
            requireNotNull(root.deployment.egressUrl?.takeIf { it.isNotBlank() }) {
                "TTRP-EXPORT-EGRESS: $where declares no egress_url (D-158); the engine's HTTP steps call the egress"
            }
        val uri = runCatching { java.net.URI(raw) }.getOrNull()
        require(
            uri != null &&
                uri.scheme in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawUserInfo == null,
        ) { "TTRP-EXPORT-EGRESS: $where egress_url $raw must be an http(s) URL without query, fragment or credentials" }
        raw.trimEnd('/')
    }

    private val root = request.graph
    private val model = BpmnModel(root.program.name, d)
    private var scheduleZone: String? = null

    private fun expr(
        c7: String,
        c8: String,
    ) = d.choose(c7, c8)

    private fun obj(vararg entries: Pair<String, String>) = d.objectValue(linkedMapOf(*entries))

    private fun eq(
        a: String,
        b: String,
    ) = d.eq(a, b)

    private fun state(
        v: String,
        name: String,
    ) = eq(d.string(v, "status"), quote(name))

    private fun error(
        scope: BpmnModel.Scope,
        name: String,
        ref: String,
    ): BpmnModel.Node =
        scope.node("endEvent", name).also { it.tail += Xml("bpmn:errorEventDefinition", mapOf("errorRef" to ref)) }

    private fun timer(
        scope: BpmnModel.Scope,
        name: String,
        duration: String,
    ): BpmnModel.Node =
        scope.node("intermediateCatchEvent", name).also {
            it.tail +=
                Xml("bpmn:timerEventDefinition", children = listOf(Xml("bpmn:timeDuration", text = duration)))
        }

    private fun boundary(
        scope: BpmnModel.Scope,
        activity: BpmnModel.Node,
        name: String,
        definition: Xml,
    ): BpmnModel.Node =
        scope.node("boundaryEvent", name).also {
            it.attrs +=
                mapOf("attachedToRef" to activity.id, "cancelActivity" to "true")
            ; it.tail += definition
        }

    private fun http(
        scope: BpmnModel.Scope,
        graph: RecipeGraph,
        label: String,
        call: String,
        result: String,
        args: Map<String, String> = emptyMap(),
        body: String? = null,
        run: String = "ttrRunId",
        query: String? = null,
        asyncBefore: Boolean = true,
        // The answer Open (D-233): see [Dialect.rest].
        answer: Boolean = false,
    ): BpmnModel.Node {
        var path = graph.runApi.basePath.trimEnd('/') + graph.runApi.calls.getValue(call)
        (args + ("recipe" to graph.recipe.name)).forEach { (key, value) ->
            require(value.matches(Regex("[A-Za-z0-9_.-]+"))) { "invalid API identifier" }
            path = path.replace("{$key}", value)
        }
        val method = if (call in setOf("status", "route", "digests")) "GET" else "POST"
        val url =
            if (engine) {
                // The egress stands for the run API's base path: `<egress_url>` + the call's path below it.
                val below = egress + path.removePrefix(graph.runApi.basePath.trimEnd('/'))
                if ("{id}" in below) {
                    d.concat(quote(below.substringBefore("{id}")), run, quote(below.substringAfter("{id}")))
                } else {
                    quote(below)
                }
            } else {
                val base = expr("TTR_CONN_AUTOMATION.url", "TTR_AUTOMATION_URL")
                if ("{id}" in
                    path
                ) {
                    d.concat(base, quote(path.substringBefore("{id}")), run, quote(path.substringAfter("{id}")))
                } else {
                    d.concat(base, quote(path))
                }
            }
        val task = scope.node("serviceTask", label)
        task.extensions +=
            d.rest(
                method,
                if (query ==
                    null
                ) {
                    url
                } else {
                    d.concat(url, query)
                },
                if (method == "POST") body ?: obj() else null,
                result,
                budget(graph),
                bearer = !engine,
                answer = answer,
            )
        if (d == Dialect.C7 && asyncBefore) {
            task.attrs["camunda:asyncBefore"] = "true"
            task.extensions += Xml("camunda:failedJobRetryTimeCycle", text = "R3/PT15S")
        }
        model.calls +=
            buildJsonObject {
                put("task", task.id)
                put("name", call)
                put("method", method)
                put("path", path)
                put("recipe", graph.recipe.name)
                put("program", graph.program.name)
                put("mode", graph.program.mode)
                put("arguments", JsonObject(args.mapValues { JsonPrimitive(it.value) }))
            }
        return task
    }

    private fun openBody(
        graph: RecipeGraph,
        step: String,
        first: Boolean,
        answer: Boolean = false,
    ): String {
        val portType = if (answer) "key" else graph.program.port
        val port =
            when (portType) {
                null, "none" -> obj("none" to "true")
                // The Server matches an answer's key against `DryRunAction.key`: always the column-named object.
                "key" -> obj("key" to if (answer) rowKeyObject(graph) else expr("ttrInput['key']", "ttrInput.key"))
                "keys" -> obj("keys" to expr("ttrInput['keys']", "ttrInput.keys"))
                "event" -> obj("event" to expr("ttrInput['event']", "ttrInput.event"))
                else -> error("unsupported Camunda port ${graph.program.port}")
            }
        val timed =
            if (portType in
                setOf(null, "none")
            ) {
                port
            } else {
                expr("$port.prop('at', ${stepClock(graph, first)})", "context merge([$port,{at:ttrStepAt}])")
            }
        val fields =
            linkedMapOf(
                "deployment" to quote(root.deployment.name),
                "port" to timed,
                "origin" to quote("engine"),
                "dryRun" to root.deployment.dryRun.toString(),
            )
        if (engine) {
            val engineFields = C7Runtime.engineIdentity.toMutableMap()
            if (answer) {
                engineFields["taskId"] = "ttrTaskId"
                engineFields["answeredBy"] = "ttrAnsweredBy"
            }
            fields["engine"] =
                d.objectValue(engineFields)
        }
        mapOf(
            "text" to "recipeFingerprint",
            "values" to "recipeValuesFingerprint",
            "program" to "programFingerprint",
            "deployment" to "deploymentFingerprint",
        ).forEach { (fp, field) ->
            graph.recipe.fingerprints[fp]?.let {
                fields[field] =
                    quote(it)
            }
        }
        if (answer) {
            fields["idempotencyKey"] = d.concat("ttrAnchor", quote(":answer:"), "ttrTaskId")
        } else if (!first) {
            fields["idempotencyKey"] = d.concat("ttrAnchor", quote(":$step"))
        } else if (graph.program.mode ==
            "batch"
        ) {
            fields["idempotencyKey"] = expr("execution.processInstanceId", "ttrExecutionId")
        }
        return d.objectValue(fields)
    }

    // C7 evaluates sibling inputs in the parent scope. Inline this once into the saved Open body.
    private fun stepClock(
        graph: RecipeGraph,
        first: Boolean,
    ): String =
        if (first && graph.program.port !in setOf(null, "none")) {
            expr(
                "ttrInput['at'] != null ? ttrInput['at'] : dateTime().toString()",
                "if ttrInput.at != null then ttrInput.at else string(date and time(now(), \"Z\"))",
            )
        } else {
            expr("dateTime().toString()", "string(date and time(now(), \"Z\"))")
        }

    private fun step(
        graph: RecipeGraph,
        name: String,
        first: Boolean,
    ): BpmnModel.Node {
        require(graph.deployment.granularity in setOf("recipe", "parts")) { "unsupported Camunda granularity" }
        require(graph.program.mode in setOf("on_event", "batch")) { "unsupported Camunda mode" }

        val scope = BpmnModel.Scope(stableId("${model.processId}/step/$name"))
        val sub =
            model.root.node(
                "subProcess",
                if (graph.program.mode ==
                    "batch"
                ) {
                    "Run batch"
                } else {
                    name
                },
                "step $name",
                scope,
            )
        val inputs = linkedMapOf<String, String>()
        if (first &&
            graph.program.mode == "batch"
        ) {
            inputs["ttrExecutionId"] =
                expr("execution.processInstanceId", "if ttrExecutionId != null then ttrExecutionId else uuid()")
        }
        if (d == Dialect.C8) inputs["ttrStepAt"] = stepClock(graph, first)
        inputs["ttrOpenBody"] = openBody(graph, name, first)
        val openedRun = guardedString("ttrOpen", "runId")
        val outputs = linkedMapOf("ttrPreviousRunId" to openedRun)
        if (first) outputs["ttrAnchor"] = openedRun
        sub.extensions += d.mapping(inputs, outputs)
        val start = scope.node("startEvent", "start $name")
        val end = scope.node("endEvent", "step complete")
        val refused = error(scope, "run refused", "run_refused")
        val open = http(scope, graph, "open", "open", "ttrOpen", body = "ttrOpenBody")
        scope.link(start, open)
        val codes = scope.node("exclusiveGateway", "Open ownership")
        scope.link(open, codes)
        scope.link(codes, end, "200 replayed", d.and(eq("ttrOpenCode", "200"), d.bool("ttrOpen", "replayed")))
        val fresh = scope.node("exclusiveGateway", "claimed run")
        scope.link(codes, fresh, "201 claimed", eq("ttrOpenCode", "201"))
        val awaiting = awaitHandle(scope, graph, codes, refused)
        val restart = scope.node("exclusiveGateway", "bounded restart")
        scope.link(awaiting, restart)
        scope.link(restart, end, "closed or waiting", d.or(state("ttrHandle", "closed"), state("ttrHandle", "waiting")))
        val reopen = http(scope, graph, "reopen once", "open", "ttrOpen", body = "ttrOpenBody")
        scope.link(
            restart,
            reopen,
            "abandoned or failed",
            d.or(state("ttrHandle", "abandoned"), state("ttrHandle", "failed")),
        )
        scope.default(restart, refused)
        val reopened = scope.node("exclusiveGateway", "re-open claimed")
        scope.link(reopen, reopened)
        scope.link(reopened, fresh, "201 recovered", eq("ttrOpenCode", "201"))
        scope.default(reopened, refused)
        val work = BpmnModel.Scope(stableId("${scope.id}/work"))
        val decide = scope.node("subProcess", "Decide", child = work)
        val workInputs = linkedMapOf("ttrRunId" to d.string("ttrOpen", "runId"))
        if (engine && first) workInputs["ttrAnchor"] = d.string("ttrOpen", "runId")
        decide.extensions += d.mapping(workInputs)
        scope.link(fresh, decide)
        val wstart = work.node("startEvent", "start evaluation")
        val evaluation = BpmnModel.Scope(stableId("${work.id}/evaluation"))
        val boundedEvaluation = work.node("subProcess", "Evaluate within budget", child = evaluation)
        boundedEvaluation.extensions += d.mapping(outputs = mapOf("ttrEvaluation" to "ttrEvaluation"))
        work.link(wstart, boundedEvaluation)
        var last = evaluation.node("startEvent", "evaluation start")
        if (graph.deployment.granularity == "parts") {
            val candidates =
                http(
                    evaluation,
                    graph,
                    graph.roles.candidates ?: "candidates",
                    "part",
                    "ttrCandidates",
                    mapOf(
                        "part" to "candidates",
                    ),
                )
            evaluation.link(last, candidates)
            last = candidates
            if (graph.roles.contexts.isNotEmpty()) {
                val fork = evaluation.node("parallelGateway", "contexts fork")
                val join = evaluation.node("parallelGateway", "contexts join")
                evaluation.link(last, fork)
                graph.roles.contexts.forEach { context ->
                    val part =
                        http(
                            evaluation,
                            graph,
                            "context ${context.name}",
                            "part",
                            "ttrContext_${stableId(context.name)}",
                            mapOf(
                                "part" to context.name,
                            ),
                        )
                    evaluation.link(fork, part)
                    evaluation.link(part, join)
                }
                last = join
            }
            if (graph.roles.decision != null) {
                val decision =
                    http(
                        evaluation,
                        graph,
                        "decision ${graph.roles.decision}",
                        "part",
                        "ttrDecision",
                        mapOf(
                            "part" to "decision",
                        ),
                    )
                evaluation.link(last, decision)
                last = decision
            }
        }
        evaluateUntilReady(evaluation, graph, last)
        val statusActions = http(work, graph, "selected actions", "status", "ttrStatus")
        work.link(boundedEvaluation, statusActions)
        val valid = work.node("exclusiveGateway", "per-key actions required")
        work.link(statusActions, valid)
        val wfail = error(work, "missing or failed run evidence", "run_refused")
        work.default(valid, wfail)
        val switchKind = "inclusiveGateway" // switchMode is exclusive per row, never per run.
        val forkRoutes = work.node(switchKind, "routes")
        val joinRoutes = work.node(switchKind, "routes complete")
        val actionsArray =
            expr(
                "ttrStatus.hasProp('actions') && ttrStatus.prop('actions').isArray()",
                "ttrStatus.actions instance of list",
            )
        work.link(valid, forkRoutes, "actions array", actionsArray)
        work.default(forkRoutes, joinRoutes)
        graph.routes.filterNot { it.isNone }.forEach { route -> route(work, graph, route, forkRoutes, joinRoutes) }
        val close = http(work, graph, "close", "close", "ttrClosed")
        val wend = work.node("endEvent", "evaluation ended")
        work.link(joinRoutes, close)
        val asking = graph.routes.filterNot { it.isNone }.filter { wired(graph, it).any { (_, a) -> asksApproval(a) } }
        if (asking.isEmpty()) work.link(close, wend) else approvals(work, graph, asking, close, wend, wfail)
        scope.link(decide, end)
        val evalBound =
            boundary(
                work,
                boundedEvaluation,
                "evaluation budget exceeded",
                Xml(
                    "bpmn:timerEventDefinition",
                    children = listOf(Xml("bpmn:timeDuration", text = "PT${budget(graph)}S")),
                ),
            )
        work.link(evalBound, wfail)
        return sub
    }

    /**
     * The step's Open (`ttrOpen`) answered 202: another execution holds the run. Poll its status (honouring Retry-After)
     * until terminal, within the budget; the budget's boundary refuses. Links `202 handle` from [codes] and refuses every
     * other code by default.
     */
    private fun awaitHandle(
        scope: BpmnModel.Scope,
        graph: RecipeGraph,
        codes: BpmnModel.Node,
        refused: BpmnModel.Node,
    ): BpmnModel.Node {
        val handle = BpmnModel.Scope(stableId("${scope.id}/handle"))
        val awaiting = scope.node("subProcess", "await other execution", child = handle)
        awaiting.extensions +=
            d.mapping(mapOf("ttrHandleRetryAfter" to "ttrOpenRetryAfter"), mapOf("ttrHandle" to "ttrHandle"))
        scope.link(codes, awaiting, "202 handle", eq("ttrOpenCode", "202"))
        scope.default(codes, refused)
        val hstart = handle.node("startEvent", "handle start")
        val hend = handle.node("endEvent", "handle settled")
        val pause =
            timer(
                handle,
                "honour Retry-After",
                d.condition(
                    expr(
                        "'PT'.concat(ttrHandleRetryAfter.toString()).concat('S')",
                        "\"PT\" + string(ttrHandleRetryAfter) + \"S\"",
                    ),
                ),
            )
        val status = http(handle, graph, "handle status", "status", "ttrHandle", run = d.string("ttrOpen", "runId"))
        val settled = handle.node("exclusiveGateway", "handle terminal")
        handle.link(hstart, pause)
        handle.link(pause, status)
        handle.link(status, settled)
        val terminal = listOf("closed", "waiting", "abandoned", "failed").map { state("ttrHandle", it) }.reduce(d::or)
        handle.link(settled, hend, "terminal", terminal)
        handle.default(settled, pause)
        val bound =
            boundary(
                scope,
                awaiting,
                "handle budget exceeded",
                Xml(
                    "bpmn:timerEventDefinition",
                    children = listOf(Xml("bpmn:timeDuration", text = "PT${budget(graph)}S")),
                ),
            )
        scope.link(bound, refused)
        return awaiting
    }

    /**
     * Evaluate the step's run (`ttrRunId`) after [last] and read its status (RunProgress: a synchronous EvaluationResult
     * has no status) until the verdict is ready, honouring Retry-After. Failed or abandoned evidence refuses.
     * `ttrEvaluation` holds the last status read.
     */
    private fun evaluateUntilReady(
        evaluation: BpmnModel.Scope,
        graph: RecipeGraph,
        last: BpmnModel.Node,
    ) {
        val result = "ttrEvaluation"
        val evaluate = http(evaluation, graph, "evaluate", "evaluate", result)
        evaluation.link(last, evaluate)
        val evaluated = evaluation.node("exclusiveGateway", "evaluation complete")
        val poll =
            timer(
                evaluation,
                "evaluation poll",
                d.condition(
                    expr(
                        "'PT'.concat(${result}RetryAfter.toString()).concat('S')",
                        "\"PT\" + string(${result}RetryAfter) + \"S\"",
                    ),
                ),
            )
        val progress = http(evaluation, graph, "evaluation status", "status", result)
        evaluation.default(evaluated, poll)
        evaluation.link(evaluate, progress)
        evaluation.link(poll, progress)
        evaluation.link(progress, evaluated)
        val evaluationEnd = evaluation.node("endEvent", "verdict ready")
        val evaluationFailed = error(evaluation, "evaluation failed or abandoned", "run_refused")
        evaluation.link(
            evaluated,
            evaluationFailed,
            "failed or abandoned",
            d.or(state(result, "failed"), state(result, "abandoned")),
        )
        val ready =
            d.and(
                d.exists(result, "verdict"),
                expr(
                    "!(${state(result, "running")}) && !(${state(result, "open")})",
                    "not(${state(result, "running")}) and not(${state(result, "open")})",
                ),
            )
        evaluation.link(evaluated, evaluationEnd, "verdict ready", ready)
    }

    private fun budget(graph: RecipeGraph): Long {
        val raw = graph.program.budget[if (graph.program.mode == "batch") "batch" else "sync"]
        val seconds = raw?.let(::durationSeconds) ?: if (graph.program.mode == "batch") 1800L else 60L
        return seconds + 120
    }

    private fun rowKeyValue(key: String) = expr("${d.field("ttrRow", key)}.value()", "ttrRow.$key")

    private fun rowKeyFields(graph: RecipeGraph) = graph.recipe.key.map { it.substringAfterLast('.') }

    private fun rowKey(graph: RecipeGraph): String {
        val fields = rowKeyFields(graph)
        return if (fields.size == 1) rowKeyValue(fields.single()) else rowKeyObject(graph)
    }

    private fun rowKeyObject(graph: RecipeGraph): String =
        d.objectValue(rowKeyFields(graph).associateWith(::rowKeyValue))

    /**
     * D-229: C7 runs output mappings on cancellation too, before [variable] holds a response, so [path] is read only once
     * it is set. [shaped]: the response may also lack the path or carry another type (a 2xx that does not decide), so
     * each step is checked as well. C8 reads the path as is.
     */
    private fun guardedString(
        variable: String,
        vararg path: String,
        shaped: Boolean = false,
    ): String {
        val nodes = path.toList().runningFold(variable, d::field)
        val value = d.string(nodes[path.size - 1], path.last())
        val checks =
            listOf("execution.getVariable('$variable') != null") +
                if (shaped) {
                    path.mapIndexed { i, name -> "${nodes[i]}.isObject() && ${nodes[i]}.hasProp(${quote(name)})" } +
                        "${nodes.last()}.isString()"
                } else {
                    emptyList()
                }
        return expr("${checks.joinToString(" && ")} ? $value : null", value)
    }

    /**
     * ✅R336-A/R2: the engine asks for an approval-required action only when the Server holds its rows, which it does for
     * an action whose native sink is one of its held sinks ([HELD_SINKS]). Those rows are held at the root close.
     */
    private fun asksApproval(action: ActionMeta): Boolean =
        engine && action.approval == "required" && action.sinks["native"] in HELD_SINKS

    /** The actions wired on [route]'s rows, once each. */
    private fun wired(
        graph: RecipeGraph,
        route: RouteMeta,
    ): List<Pair<WiringMeta, ActionMeta>> =
        graph.wirings
            .filter { it.routes.any { r -> r.substringAfterLast('.') == route.name } }
            .distinctBy { it.action }
            .map { it to graph.actions.getValue(it.action) }

    private fun expectedRows(route: RouteMeta): String {
        val verdictRoutes = d.field(d.field("ttrEvaluation", "verdict"), "routes")
        return expr(
            "$verdictRoutes.prop(${quote(route.name)}).prop('rowCount').numberValue()",
            "get value($verdictRoutes,${quote(route.name)}).rowCount",
        )
    }

    /** A sequential multi-instance over the route rows paged into [rows], each as `ttrRow`. */
    private fun rowsLoop(rows: String): Xml {
        val collection = expr("$rows.elements()", rows)
        return if (d == Dialect.C7) {
            Xml(
                "bpmn:multiInstanceLoopCharacteristics",
                mapOf(
                    "isSequential" to "true",
                    "camunda:collection" to d.condition(collection),
                    "camunda:elementVariable" to "ttrRow",
                ),
            )
        } else {
            Xml(
                "bpmn:multiInstanceLoopCharacteristics",
                mapOf("isSequential" to "true"),
                listOf(
                    Xml(
                        "bpmn:extensionElements",
                        children =
                            listOf(
                                Xml(
                                    "zeebe:loopCharacteristics",
                                    mapOf("inputCollection" to d.condition(collection), "inputElement" to "ttrRow"),
                                ),
                            ),
                    ),
                ),
            )
        }
    }

    /**
     * ✅R336-A: the root close holds the asking actions' rows and leaves the run `waiting`; only then can an answer find
     * a held key. `closed` (nothing held: a dry run, or no asking action selected) ends the evaluation, `waiting` asks
     * per row of each route in [routes], anything else refuses.
     */
    private fun approvals(
        work: BpmnModel.Scope,
        graph: RecipeGraph,
        routes: List<RouteMeta>,
        close: BpmnModel.Node,
        end: BpmnModel.Node,
        refused: BpmnModel.Node,
    ) {
        val outcome = work.node("exclusiveGateway", "close outcome")
        work.link(close, outcome)
        work.link(outcome, end, "closed", state("ttrClosed", "closed"))
        val fork = work.node("inclusiveGateway", "approvals")
        val join = work.node("inclusiveGateway", "approvals complete")
        work.link(outcome, fork, "waiting", state("ttrClosed", "waiting"))
        work.default(outcome, refused)
        work.default(fork, join)
        routes.forEach { route ->
            val rowScope = BpmnModel.Scope(stableId("${work.id}/ask/${route.name}"))
            val each = work.node("subProcess", "ask each ${route.name} row", child = rowScope)
            // Row-local (R323-4): the answer's identity and decision never reach the process instance.
            each.extensions +=
                d.mapping(
                    listOf("ttrTaskId", "ttrAnsweredBy", "ttrAnswerRunId", "ttrAnswerStatus", "ttrAnswerDecision")
                        .associateWith { "null" },
                )
            each.tail += rowsLoop("ttrRows_${stableId(route.name)}")
            work.link(fork, each, "ask ${route.name}", "${expectedRows(route)} > 0")
            work.link(each, join)
            var last = rowScope.node("startEvent", "ask row start")
            wired(graph, route).filter { (_, action) -> asksApproval(action) }.forEach { (wiring, action) ->
                last = ask(rowScope, graph, wiring, action, last)
            }
            rowScope.link(last, rowScope.node("endEvent", "ask row complete"))
        }
        work.link(join, end)
    }

    /**
     * One row's approval: user task → `complete` listener → the answer Open → the decision. D-233: the answer Open is the
     * row's one call; the Server decides and closes the answer run there, and its response is the decision.
     */
    private fun ask(
        rowScope: BpmnModel.Scope,
        graph: RecipeGraph,
        wiring: WiringMeta,
        action: ActionMeta,
        last: BpmnModel.Node,
    ): BpmnModel.Node {
        val (gate, done) = selectedAndWet(rowScope, graph, wiring.action, last)
        val task = approvalTask(rowScope, wiring.action, action, graph)
        rowScope.link(
            gate,
            task,
            "${wiring.action} selected and wet",
            d.and(wet(graph), selected(graph, wiring.action)),
        )
        task.extensions +=
            Xml(
                "camunda:taskListener",
                mapOf("event" to "complete"),
                listOf(
                    Xml(
                        "camunda:script",
                        mapOf("scriptFormat" to "groovy"),
                        text =
                            """
                            if (!task.assignee?.trim()) throw new IllegalStateException('approval task must be claimed before it is completed')
                            task.execution.setVariable('ttrTaskId', task.id)
                            task.execution.setVariable('ttrAnsweredBy', task.assignee)
                            """.trimIndent(),
                    ),
                ),
            )
        val answerScope = BpmnModel.Scope(stableId("${task.id}/answer"))
        val answerStep = rowScope.node("subProcess", "answer ${wiring.action} step", child = answerScope)
        answerStep.extensions +=
            d.mapping(
                linkedMapOf("ttrAnswerOpenBody" to openBody(graph, "answer", first = false, answer = true)) +
                    listOf("", "Code", "Headers", "RetryAfter").associate { "ttrAnswerOpen$it" to "null" },
                linkedMapOf(
                    "ttrAnswerRunId" to guardedString("ttrAnswerOpen", "runId", shaped = true),
                    "ttrAnswerStatus" to guardedString("ttrAnswerOpen", "status", shaped = true),
                    "ttrAnswerDecision" to guardedString("ttrAnswerOpen", "answer", "decision", shaped = true),
                ),
            )
        val answer =
            http(
                answerScope,
                graph,
                "answer ${wiring.action}",
                "open",
                "ttrAnswerOpen",
                body = "ttrAnswerOpenBody",
                asyncBefore = false,
                answer = true,
            )
        answerScope.link(answerScope.node("startEvent", "answer start"), answer)
        answerScope.link(answer, answerScope.node("endEvent", "answer opened"))
        rowScope.link(task, answerStep)
        val decided = rowScope.node("exclusiveGateway", "answer ${wiring.action} decided")
        rowScope.link(answerStep, decided)
        rowScope.link(
            decided,
            done,
            "closed and committed",
            d.and(eq("ttrAnswerStatus", quote("closed")), eq("ttrAnswerDecision", quote("commit"))),
        )
        rowScope.default(decided, error(rowScope, "answer ${wiring.action} refused", "run_refused"))
        return done
    }

    /** The `<action> selected and wet` gate after [last] and its `<action> complete` join; unselected rows skip. */
    private fun selectedAndWet(
        rowScope: BpmnModel.Scope,
        graph: RecipeGraph,
        action: String,
        last: BpmnModel.Node,
    ): Pair<BpmnModel.Node, BpmnModel.Node> {
        val gate = rowScope.node("exclusiveGateway", "$action selected and wet")
        val done = rowScope.node("exclusiveGateway", "$action complete")
        rowScope.link(last, gate)
        rowScope.default(gate, done)
        return gate to done
    }

    private fun approvalTask(
        rowScope: BpmnModel.Scope,
        name: String,
        action: ActionMeta,
        graph: RecipeGraph,
    ): BpmnModel.Node {
        val task = rowScope.node("userTask", "approve $name")
        task.extensions += d.mapping(mapOf("ttrAction" to quote(name), "ttrKey" to rowKey(graph)))
        if (d == Dialect.C8) task.extensions += Xml("zeebe:userTask")
        action.by?.let { role ->
            val group =
                requireNotNull(action.candidateGroup?.takeIf { it.isNotBlank() }) {
                    "TTRP-EXPORT-ROLE: action $name: $role has no Keycloak group"
                }
            if (d == Dialect.C7) {
                task.attrs["camunda:candidateGroups"] = group
            } else {
                task.extensions += Xml("zeebe:assignmentDefinition", mapOf("candidateGroups" to "=[${quote(group)}]"))
            }
        }
        return task
    }

    private fun route(
        scope: BpmnModel.Scope,
        graph: RecipeGraph,
        route: RouteMeta,
        fork: BpmnModel.Node,
        join: BpmnModel.Node,
    ) {
        val rows = "ttrRows_${stableId(route.name)}"
        val expected = expectedRows(route)
        val paging = BpmnModel.Scope(stableId("${scope.id}/pages/${route.name}"))
        val pages = scope.node("subProcess", "page ${route.name} rows", child = paging)
        pages.extensions +=
            d.mapping(
                mapOf(
                    "ttrPageState" to obj("rows" to d.array(), "offset" to "0", "size" to "0", "reads" to "0"),
                    "ttrPage" to obj(), // Keep connector results inside this route's scope across inclusive branches.
                    "ttrExpectedRows" to expected,
                ),
                mapOf(rows to d.field("ttrPageState", "rows")),
            )
        val branch = scope.link(fork, pages, route.name, "$expected > 0")
        val read =
            http(
                paging,
                graph,
                "route ${route.name}",
                "route",
                "ttrPage",
                mapOf("route" to route.name),
                query =
                    d.concat(
                        quote("?offset="),
                        expr("ttrPageState.prop('offset').numberValue().toString()", "string(ttrPageState.offset)"),
                    ),
            )
        model.routes +=
            buildJsonObject {
                put("recipe", graph.recipe.name)
                put("route", route.name)
                put("flow", branch.id)
                put("gateway", fork.id)
                put("entry", pages.id)
                put("task", read.id)
            }
        paging.link(paging.node("startEvent", "pages start"), read)
        val validPage = paging.node("exclusiveGateway", "route ${route.name} page valid")
        paging.link(read, validPage)
        val truncated = error(paging, "route ${route.name} evidence truncated", "run_refused")
        paging.default(validPage, truncated)
        val append = paging.node("scriptTask", "append ${route.name} page")
        val pageCount = expr("ttrPage.prop('rows').elements().size()", "count(ttrPage.rows)")
        val valid =
            expr(
                "ttrPage.hasProp('rows') && ttrPage.prop('rows').isArray() && " +
                    "ttrPage.prop('rowCount').numberValue() == ttrExpectedRows && " +
                    "$pageCount > 0 && $pageCount + ttrPageState.prop('offset').numberValue() <= ttrExpectedRows",
                "if ttrPage.rows instance of list then ttrPage.rowCount = ttrExpectedRows and " +
                    "$pageCount > 0 and $pageCount + ttrPageState.offset <= ttrExpectedRows else false",
            )
        paging.link(validPage, append, "consistent nonempty page", valid)
        if (d == Dialect.C7) {
            append.attrs["scriptFormat"] = "groovy"
            append.tail +=
                Xml(
                    "bpmn:script",
                    text =
                        """
                        def state = execution.getVariable('ttrPageState')
                        def page = execution.getVariable('ttrPage')
                        def values = page.prop('rows').elements()
                        values.each { state.prop('rows').append(it) }
                        state.prop('offset', state.prop('offset').numberValue().longValue() + values.size())
                        if (state.prop('size').numberValue().longValue() == 0) state.prop('size', values.size())
                        state.prop('reads', state.prop('reads').numberValue().longValue() + 1)
                        execution.setVariable('ttrPageState', state)
                        """.trimIndent(),
                )
        } else {
            append.extensions +=
                Xml(
                    "zeebe:script",
                    mapOf(
                        "resultVariable" to "ttrPageState",
                        "expression" to
                            "={rows:concatenate(ttrPageState.rows,ttrPage.rows), " +
                            "offset:ttrPageState.offset+count(ttrPage.rows), " +
                            "size:if ttrPageState.size = 0 then count(ttrPage.rows) else ttrPageState.size, " +
                            "reads:ttrPageState.reads+1}",
                    ),
                )
        }
        val complete = paging.node("exclusiveGateway", "route ${route.name} rows complete")
        paging.link(append, complete)
        val all = paging.node("endEvent", "all route rows available")
        paging.link(
            complete,
            all,
            "all rows available",
            eq(expr("ttrPageState.prop('offset').numberValue()", "ttrPageState.offset"), "ttrExpectedRows"),
        )
        val more =
            expr(
                "$pageCount == ttrPageState.prop('size').numberValue() && " +
                    "ttrPageState.prop('offset').numberValue() < ttrExpectedRows && " +
                    "ttrPageState.prop('reads').numberValue() * " +
                    "ttrPageState.prop('size').numberValue() < ttrExpectedRows",
                "$pageCount = ttrPageState.size and ttrPageState.offset < ttrExpectedRows and " +
                    "ttrPageState.reads < ceiling(ttrExpectedRows / ttrPageState.size)",
            )
        paging.link(complete, read, "next full page within bound", more)
        paging.default(complete, truncated)
        val rowScope = BpmnModel.Scope(stableId("${scope.id}/route/${route.name}"))
        val each = scope.node("subProcess", "each ${route.name} row", child = rowScope)
        each.tail += rowsLoop(rows)
        scope.link(pages, each)
        scope.link(each, join)
        var last = rowScope.node("startEvent", "row start")
        wired(graph, route)
            .forEach { (wiring, action) ->
                val sink =
                    action.sinks[graph.deployment.type] ?: action.sinks["camunda"] ?: action.sinks["native"]
                        ?: error("missing Camunda sink for ${wiring.action}")
                when {
                    // ✅R336-A: the engine asks after the root close (approvals), never performing the action directly;
                    // the Server applies it once the answer is verified.
                    asksApproval(action) -> Unit
                    // An explicit user-task sink (customer targets, or an approval the Server holds no rows for).
                    sink == "user task" || (action.approval == "required" && action.sinks["native"] == "task") -> {
                        val (gate, done) = selectedAndWet(rowScope, graph, wiring.action, last)
                        val task = approvalTask(rowScope, wiring.action, action, graph)
                        rowScope.link(
                            gate,
                            task,
                            "${wiring.action} selected and wet",
                            d.and(wet(graph), selected(graph, wiring.action)),
                        )
                        rowScope.link(task, done)
                        last = done
                    }
                    sink == "send task" -> {
                        val (gate, done) = selectedAndWet(rowScope, graph, wiring.action, last)
                        val columns = action.columns.filter { it.value == "role" || it.key == "to" && '.' in it.value }
                        require(columns.size == 1) { "mail ${wiring.action} needs exactly one address column" }
                        val (column, type) = columns.entries.single()
                        val source = requireNotNull(wiring.columns[column]) { "mail address wiring missing" }
                        val recipient =
                            if (type ==
                                "role"
                            ) {
                                require(source.matches(Regex("role\\.[A-Za-z_][A-Za-z0-9_]*"))) {
                                    "mail role address must reference role.<name>"
                                }
                                expr(
                                    "TTR_MAIL_RECIPIENTS[${quote(source)}]",
                                    "get value(TTR_MAIL_RECIPIENTS,${quote(source)})",
                                )
                            } else {
                                require(
                                    source.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")),
                                ) { "mail address must be a bare route-row column" }
                                d.string("ttrRow", source)
                            }
                        val task = rowScope.node("sendTask", "send ${wiring.action}")
                        if (d ==
                            Dialect.C7
                        ) {
                            task.attrs +=
                                mapOf("camunda:type" to "external", "camunda:topic" to "TTR_CONN_MAIL")
                        } else {
                            task.extensions +=
                                Xml("zeebe:taskDefinition", mapOf("type" to "TTR_CONN_MAIL", "retries" to "3"))
                        }
                        task.extensions +=
                            d.mapping(
                                mapOf(
                                    "ttrRecipient" to recipient,
                                    "ttrRow" to "ttrRow",
                                    "ttrAction" to quote(wiring.action),
                                    "ttrKey" to rowKey(graph),
                                ),
                            )
                        rowScope.link(
                            gate,
                            task,
                            "${wiring.action} selected and wet",
                            d.and(wet(graph), selected(graph, wiring.action)),
                        )
                        val outcome =
                            http(
                                rowScope,
                                graph,
                                "${wiring.action} delivered",
                                "outcome",
                                "ttrOutcome",
                                mapOf(
                                    "action" to wiring.action,
                                ),
                                obj(
                                    "outcomes" to
                                        d.array(
                                            obj(
                                                "key" to rowKey(graph),
                                                "status" to quote("delivered"),
                                                "at" to
                                                    expr(
                                                        "dateTime().toString()",
                                                        "string(date and time(now(), \"Z\"))",
                                                    ),
                                            ),
                                        ),
                                ),
                            )
                        rowScope.link(task, outcome)
                        rowScope.link(outcome, done)
                        last = done
                    }
                    sink in SERVER_PERFORMED -> {
                        val perform =
                            http(
                                rowScope,
                                graph,
                                "perform ${wiring.action}",
                                "perform",
                                "ttrPerformed",
                                mapOf(
                                    "action" to wiring.action,
                                ),
                                obj("keys" to d.array(rowKey(graph))),
                            )
                        rowScope.link(last, perform)
                        last = perform
                    }
                    else -> error("unsupported Camunda sink $sink")
                }
            }
        rowScope.link(last, rowScope.node("endEvent", "row complete"))
    }

    private fun wet(graph: RecipeGraph): String =
        if (graph.deployment.dryRun) {
            "false"
        } else {
            expr(
                "ttrStatus.hasProp('dryRun') && !ttrStatus.prop('dryRun').boolValue()",
                "ttrStatus.dryRun = false",
            )
        }

    private fun selected(
        graph: RecipeGraph,
        action: String,
    ): String {
        val fields = graph.recipe.key.map { it.substringAfterLast('.') }
        val named = d.objectValue(fields.associateWith { "ttrRow.$it" })
        val scalar = if (fields.size == 1) "a.key = ttrRow.${fields.single()}" else "false"
        val feel =
            "some a in ttrStatus.actions satisfies a.action = ${quote(action)} and " +
                "(if a.key instance of context then a.key = $named else $scalar)"
        val queryParts = mutableListOf(quote("$.actions[?(@.action == ${quote(action)} && ("))
        fields.forEachIndexed { i, key ->
            if (i > 0) queryParts += quote(" && ")
            queryParts += quote("@.key.$key == ")
            queryParts += "ttrRow.prop(${quote(key)}).toString()"
        }
        if (fields.size == 1) {
            queryParts += quote(" || @.key == ")
            queryParts += "ttrRow.prop(${quote(fields.single())}).toString()"
        }
        queryParts += quote("))]")
        return expr("ttrStatus.jsonPath(${d.concat(*queryParts.toTypedArray())}).elementList().size() > 0", feel)
    }

    fun render(): String {
        require(
            root.deployment.type ==
                when {
                    engine -> "process_engine"
                    d == Dialect.C7 -> "camunda_7"
                    else -> "camunda_8"
                },
        ) { "Camunda target/dialect mismatch" }
        if (engine) {
            val roles = root.deployment.startableBy
            val groups = root.deployment.startableGroups
            require(
                (roles == null) == (groups == null) && (roles == null || roles.size == groups!!.size),
            ) { "TTRP-EXPORT-ROLE: deployment ${root.deployment.name}: startable_by groups are not resolved" }
            groups?.forEach {
                require(it.isNotBlank() && it.none { c -> c == ',' || c.isWhitespace() }) {
                    "TTRP-EXPORT-ROLE: start group `$it` cannot be listed in camunda:candidateStarterGroups"
                }
            }
            // ⚑R226-A: the engine's provisioner grants START_INSTANCE from this static attribute only.
            if (!groups.isNullOrEmpty()) {
                model.processAttributes["camunda:candidateStarterGroups"] =
                    groups.joinToString(",")
            }
        }
        val scope = model.root
        val start = scope.node("startEvent", "start with ttrInput key")
        if (d == Dialect.C7) start.attrs["camunda:asyncAfter"] = "true"
        val terminate =
            scope.node("endEvent", "stopped for repair").also {
                it.tail +=
                    Xml("bpmn:terminateEventDefinition")
            }
        val outdated = scope.node("userTask", "export outdated")
        val refused = scope.node("userTask", "run refused; inspect evidence")
        if (d == Dialect.C8) {
            outdated.extensions += Xml("zeebe:userTask")
            refused.extensions += Xml("zeebe:userTask")
        }
        scope.link(outdated, terminate)
        scope.link(refused, terminate)
        val sub = step(root, root.program.name, true)
        scope.link(start, sub)
        val done = scope.node("exclusiveGateway", "${root.program.name} step complete")
        scope.link(sub, done)
        val stale =
            boundary(
                scope,
                sub,
                "${root.program.name} stale export",
                Xml(
                    "bpmn:errorEventDefinition",
                    mapOf(
                        "errorRef" to "stale_export",
                    ),
                ),
            )
        val fail =
            boundary(
                scope,
                sub,
                "${root.program.name} run refused",
                Xml(
                    "bpmn:errorEventDefinition",
                    // The engine keeps a non-2xx refusal's message (the Server's status and body) for the person
                    // who inspects it; a 2xx that does not commit has none.
                    mapOf("errorRef" to "run_refused") +
                        if (engine) mapOf("camunda:errorMessageVariable" to "ttrRefusedReason") else emptyMap(),
                ),
            )
        scope.link(stale, outdated)
        scope.link(fail, refused)
        scope.link(done, scope.node("endEvent", "complete"))
        val contract =
            buildJsonObject {
                put("schemaVersion", 1)
                put("target", root.deployment.type)
                put("deployment", root.deployment.name)
                put("recipes", JsonArray(listOf(JsonPrimitive(root.recipe.name))))
                put("calls", JsonArray(model.calls))
                put("routes", JsonArray(model.routes))
            }
        return model.render(contract)
    }

    private fun customerConnection(): String =
        """
        C7 requires Camunda Connect's HTTP connector, Spin JSON and Groovy for Retry-After normalization. Configure TTR_CONN_AUTOMATION
        with url (origin, no trailing slash) and token. C8 uses the standard REST outbound connector
        io.camunda:http-json:1; supply TTR_AUTOMATION_URL and connector secret TTR_CONN_AUTOMATION.
        Credentials are referenced by name and never embedded. Start this one-program BPMN externally;
        configure scheduling and composition in the engine modeller.
        """.trimIndent().replace("\n", "\n        ")

    private fun engineConnection(): String =
        """
        Every HTTP step is Camunda Connect's http-connector calling the executor's egress, $egress.
        The egress injects the process-engine client's bearer; this BPMN carries no token and no connector secret.
        The engine needs Spin JSON and Groovy for Retry-After normalization. Start this one-program BPMN through
        the engine (REST, Tasklist or a message); configure scheduling and composition in the engine modeller.
        Start with a nonblank business key: every Open records engine.instanceId, engine.businessKey and
        engine.deploymentId (the running process definition's deployment id, read through the repository service).
        The engine templates also retain ttrInput.at in keyed ports; batch ports remain {none: true}.
        Approval completion captures task.id/assignee; its answer Open additionally carries engine.taskId and
        engine.answeredBy, with idempotencyKey = ttrAnchor + ':answer:' + ttrTaskId and the approved row's key.
        An approval-required action whose rows the Server holds (native sink transition, writer or obligation) becomes
        this user task; the engine never performs it directly. The Server holds those rows at the run's close, so the
        engine asks after the close: closed ends the evaluation, waiting asks each row whose key has the action
        selected, anything else refuses. The answer Open is the row's one call. It runs within task completion (no
        asyncBefore); a transient failure, or a 202 for the same key still in flight, rolls back completion and the
        user completes again. Its port.key is the row's key as a column-named object. The Server decides the approved
        key and closes the answer run at that Open. The row continues only when the response says status closed and
        answer.decision commit; anything else (waiting, 409, another 4xx) reaches run refused and is never counted as
        decided. A non-2xx refusal keeps the Server's status and body in ttrRefusedReason; a 2xx that does not commit
        leaves it null, and the answer's status is the row-local ttrAnswerStatus in the history. The row reads no
        route again, so the approval is never asked twice. ttrAnswerRunId, ttrAnswerStatus and ttrAnswerDecision are
        row-local. Only the engine client may assert an answer.
        Deploy: `ttrp emit --deployment ${root.deployment.name} --deploy` posts this BPMN and the DMN files as one engine
        deployment named ${root.deployment.name}, with duplicate filtering and deployment-source ttrp:<bundle fingerprint>.
        The bearer comes from TTR_BEARER (or --token-env); it is never written to output or export files.
        `--deploy --check` reads the newest deployment of the name and answers absent, current, stale or foreign;
        `--deploy` creates for absent or stale, does nothing for current and refuses foreign unless --force.
        `--engine-url` overrides the executor's url, e.g. for a port-forward.
        """.trimIndent().replace("\n", "\n        ")

    private fun startHint(): String {
        val roles =
            root.deployment.startableBy
                ?: return "No startable_by restriction is declared; " +
                    "configure START_INSTANCE authorization in the engine."
        if (roles.isEmpty()) {
            return if (engine) {
                "startable_by: [] — no role may start this process; it carries no camunda:candidateStarterGroups."
            } else {
                "startable_by: [] — no role may start this process; grant no START_INSTANCE authorization."
            }
        }
        val groups = root.deployment.startableGroups
        val named =
            roles
                .mapIndexed {
                    i,
                    role,
                    ->
                    groups?.getOrNull(i)?.let { "$role (Keycloak group $it)" } ?: role
                }.joinToString()
        return if (engine) {
            "startable_by: $named. The process carries camunda:candidateStarterGroups=\"${groups.orEmpty().joinToString(
                ",",
            )}\"; " +
                "the engine's provisioner grants START_INSTANCE to these groups (D-161)."
        } else {
            "startable_by: $named. Grant START_INSTANCE authorization to these groups in the engine; this file does not provision permissions."
        }
    }

    fun readme(): String =
        """
        # ${root.deployment.name} — ${if (engine) "the estate's process engine (Camunda 7 dialect)" else "Camunda ${if (d == Dialect.C7) "7" else "8"} BPMN file"}

        ${if (engine) {
            "`${root.recipe.name}.${d.suffix}.bpmn` and the DMN files deploy to the process engine with `ttrp emit --deployment ${root.deployment.name} --deploy`. This is an L1 orchestration\n" +
                "        skeleton; SQL and governed writes stay on the Server."
        } else {
            "Import `${root.recipe.name}.${d.suffix}.bpmn` in the matching Camunda Modeler. This is an L1 orchestration\n" +
                "        skeleton; SQL and governed writes stay on the Server. No live Camunda is deployed by this export."
        }}
        The Modeler opening follows the repair re-review and is Bora's manual acceptance; XSD/structure checks
        do not certify a live engine. The file declares Camunda Platform 7.24.0 or Camunda Cloud 8.6.0 as its
        execution platform. C8 requires at least 8.6: [converging inclusive gateways](https://unsupported.docs.camunda.io/8.6/docs/components/modeler/bpmn/inclusive-gateways/),
        [zeebe:userTask](https://unsupported.docs.camunda.io/8.6/docs/components/modeler/bpmn/user-tasks/) and
        [FEEL timezone conversion](https://unsupported.docs.camunda.io/8.5/docs/components/modeler/feel/builtin-functions/feel-built-in-functions-conversion/)
        are available there (timezone conversion is already documented in 8.5).

        ${if (engine) engineConnection() else customerConnection()}
        ${startHint()}

        Start with ttrInput={key:..., at:"offset ISO instant"}, keys, or event. Event payload is nested under event;
        at is separate. On C7, ttrInput is a Java Map, including nested maps/lists for events or keys; a REST
        Object variable must deserialize to a Map, rather than a Spin Json variable. This input contract is
        expression-tested; no REST start on a live C7 engine is certified here. On-event deduplication requires a
        stable at; absent at is a new run. A batch program uses the engine execution id
        (C8 saves ttrExecutionId, generating one if absent). The Open body and its clock are saved once;
        retries reuse that body. Start each batch once: retrying an external start on C7 or n8n
        creates another engine instance and caller key; deduplication across those instances is not provided.

        201 alone drives evaluation. 200 replay completes the step; 202 polls, closed/waiting completes,
        abandoned/failed allows one re-open. A second non-201 recovery refuses. 409 raises TTR_STALE and reaches
        export outdated. The HTTP response policy for every call, including perform/outcome/close, is:

        | Response | Result |
        | --- | --- |
        | 2xx | Continue with the response body |
        | 409 | TTR_STALE boundary → export outdated |
        | Other 4xx except 408/429 | TTR_REFUSED boundary → run refused; inspect evidence |
        | 408/429, 5xx, transport errors | Connector retries, then incident |

        C8 uses the error response's numeric or HTTP-date Retry-After when retrying HTTP errors, defaulting to
        15 seconds, with a 1 second minimum and a cap of the program budget + 2 min.
        C7 Connect failed jobs use R3/PT15S: its output exception cannot commit a dynamic retry delay.
        Successful handle/evaluation polls in both dialects honour Retry-After and are bounded by budget + 2 min.
        Failed/abandoned evaluation refuses immediately; running/open or null-verdict evidence keeps polling.
        Run-level route forks and joins are inclusive for every switchMode: exclusivity holds per row and is
        enforced by the Server. Read every route page using offset and the Server's default page size; do not
        override its displayRows limit. Empty/short pages before rowCount or inconsistent counts refuse before
        any row action. Missing obligations/actions refuse, never count as empty evidence.
        Compose obligation watching and digest policies in the engine; recipe process/delivery/schedule
        constructs were removed in grammar 0.22.

        Send tasks are customer template nodes using TTR_CONN_MAIL (C7 external task / C8 job type). Their worker
        belongs to Platform and is not bundled. Resolve TTR_MAIL_RECIPIENTS for role recipients; per-row addresses
        come from the declared route column. The worker must refuse a missing/empty recipient and report success
        only after delivery; only then does BPMN report delivered to the Server. Both deployment and status must
        explicitly be wet, with the action selected for this key. Required approval is a Camunda user task carrying
        ttrAction/ttrKey; connect its completion to the exchange's task/approval integration before deployment.

        Stale when model, recipe text/values/labels, program, deployment, world, toolchain or target version
        differs from the served bundle: re-export. The manifest inventories all emitted artifacts. The structure
        driver checks routes, tasks, named calls, template bindings and DMN associations offline; it does not
        execute connectors or compare verdicts.
        """.trimIndent() + "\n"

    private companion object {
        /** Sinks whose action the row hands to the Server's `perform` (the engine performs no writer action itself). */
        val SERVER_PERFORMED =
            setOf("door", "service task", "transition", "writer", "obligation", "task", "message", "start", "http")

        /**
         * The native sinks whose `approval: required` rows the Server holds at close for an answer: a local copy of the
         * Server's `ApplyPlanner.HELD_SINKS` (ttr-server, automation). An approval action with another native sink, or
         * none, has nothing held, so the engine never asks for it.
         */
        val HELD_SINKS = setOf("transition", "writer", "obligation")
    }
}
