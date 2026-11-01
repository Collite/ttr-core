// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*

/** L1 BPMN skeleton. All governed computation/writes are named run-API calls; engine adapters stay external. */
internal class CamundaRenderer(
    private val request: RecipeEmitRequest,
    private val d: Dialect,
) {
    private val root = request.graph
    private val model = BpmnModel(root.process?.name ?: root.program.name, d)
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
    ): BpmnModel.Node {
        var path = graph.runApi.basePath.trimEnd('/') + graph.runApi.calls.getValue(call)
        (args + ("recipe" to graph.recipe.name)).forEach { (key, value) ->
            require(value.matches(Regex("[A-Za-z0-9_.-]+"))) { "invalid API identifier" }
            path = path.replace("{$key}", value)
        }
        val method = if (call in setOf("status", "route", "digests")) "GET" else "POST"
        val base = expr("TTR_CONN_AUTOMATION.url", "TTR_AUTOMATION_URL")
        val url =
            if ("{id}" in
                path
            ) {
                d.concat(base, quote(path.substringBefore("{id}")), run, quote(path.substringAfter("{id}")))
            } else {
                d.concat(base, quote(path))
            }
        val task = scope.node("serviceTask", label)
        task.extensions += d.rest(method, url, if (method == "POST") body ?: obj() else null, result)
        if (d == Dialect.C7) {
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
    ): String {
        val port =
            when (graph.program.port) {
                null, "none" -> obj("none" to "true")
                "key" -> obj("key" to expr("ttrInput['key']", "ttrInput.key"))
                "keys" -> obj("keys" to expr("ttrInput['keys']", "ttrInput.keys"))
                "event" -> obj("event" to expr("ttrInput['event']", "ttrInput.event"))
                else -> error("unsupported Camunda port ${graph.program.port}")
            }
        val timed =
            if (graph.program.port in
                setOf(null, "none")
            ) {
                port
            } else {
                expr("$port.prop('at', ttrStepAt)", "context merge([$port,{at:ttrStepAt}])")
            }
        val fields =
            linkedMapOf(
                "deployment" to quote(root.deployment.name),
                "port" to timed,
                "origin" to quote("engine"),
                "dryRun" to root.deployment.dryRun.toString(),
            )
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
        if (!first) {
            fields["idempotencyKey"] = d.concat("ttrAnchor", quote(":$step"))
        } else if (graph.program.mode ==
            "batch"
        ) {
            fields["idempotencyKey"] = expr("execution.processInstanceId", "ttrExecutionId")
        }
        return d.objectValue(fields)
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
        inputs["ttrStepAt"] =
            if (first &&
                graph.program.port !in setOf(null, "none")
            ) {
                expr(
                    "ttrInput['at'] != null ? ttrInput['at'] : dateTime().toString()",
                    "if ttrInput.at != null then ttrInput.at else string(now())",
                )
            } else {
                expr("dateTime().toString()", "string(now())")
            }
        inputs["ttrOpenBody"] = openBody(graph, name, first)
        val outputs = linkedMapOf("ttrPreviousRunId" to d.string("ttrOpen", "runId"))
        if (first) outputs["ttrAnchor"] = d.string("ttrOpen", "runId")
        sub.extensions += d.mapping(inputs, outputs)
        val start = scope.node("startEvent", "start $name")
        val end = scope.node("endEvent", "step complete")
        val refused = error(scope, "run refused", "run_refused")
        val stale = error(scope, "stale export", "stale_export")
        val open = http(scope, graph, "open", "open", "ttrOpen", body = "ttrOpenBody")
        scope.link(start, open)
        val codes = scope.node("exclusiveGateway", "Open ownership")
        scope.link(open, codes)
        scope.link(codes, stale, "409 stale", eq("ttrOpenCode", "409"))
        scope.link(codes, end, "200 replayed", d.and(eq("ttrOpenCode", "200"), d.bool("ttrOpen", "replayed")))
        val fresh = scope.node("exclusiveGateway", "claimed run")
        scope.link(codes, fresh, "201 claimed", eq("ttrOpenCode", "201"))
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
        scope.link(reopened, stale, "409 stale", eq("ttrOpenCode", "409"))
        scope.default(reopened, refused)
        val work = BpmnModel.Scope(stableId("${scope.id}/work"))
        val decide = scope.node("subProcess", "Decide", child = work)
        decide.extensions += d.mapping(mapOf("ttrRunId" to d.string("ttrOpen", "runId")))
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
        val evaluate = http(evaluation, graph, "evaluate", "evaluate", "ttrEvaluation")
        evaluation.link(last, evaluate)
        val evaluated = evaluation.node("exclusiveGateway", "evaluation complete")
        evaluation.link(evaluate, evaluated)
        val poll =
            timer(
                evaluation,
                "evaluation poll",
                d.condition(
                    expr(
                        "'PT'.concat(ttrEvaluationRetryAfter.toString()).concat('S')",
                        "\"PT\" + string(ttrEvaluationRetryAfter) + \"S\"",
                    ),
                ),
            )
        val progress = http(evaluation, graph, "evaluation status", "status", "ttrEvaluation")
        evaluation.default(evaluated, poll)
        evaluation.link(poll, progress)
        evaluation.link(progress, evaluated)
        val evaluationEnd = evaluation.node("endEvent", "verdict ready")
        evaluation.link(evaluated, evaluationEnd, "verdict ready", d.exists("ttrEvaluation", "verdict"))
        val statusActions = http(work, graph, "selected actions", "status", "ttrStatus")
        work.link(boundedEvaluation, statusActions)
        val valid = work.node("exclusiveGateway", "per-key actions required")
        work.link(statusActions, valid)
        val wfail = error(work, "missing or failed run evidence", "run_refused")
        work.default(valid, wfail)
        val switchKind = if (graph.roles.switchMode == "overlapping") "inclusiveGateway" else "exclusiveGateway"
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
        work.link(close, wend)
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

    private fun budget(graph: RecipeGraph): Long {
        val raw = graph.program.budget[if (graph.program.mode == "batch") "batch" else "sync"]
        val m = raw?.let { Regex("([0-9.]+)\\s*(milliseconds|seconds|minutes|hours)").matchEntire(it) }
        val seconds =
            if (m == null) {
                if (graph.program.mode == "batch") 1800L else 60L
            } else {
                (
                    m.groupValues[1].toDouble() *
                        mapOf(
                            "milliseconds" to .001,
                            "seconds" to 1.0,
                            "minutes" to 60.0,
                            "hours" to 3600.0,
                        ).getValue(m.groupValues[2])
                ).toLong()
            }
        return seconds + 120
    }

    private fun rowKey(graph: RecipeGraph): String {
        val fields = graph.recipe.key.map { it.substringAfterLast('.') }

        fun value(key: String) = expr("${d.field("ttrRow", key)}.value()", "ttrRow.$key")
        return if (fields.size == 1) value(fields.single()) else d.objectValue(fields.associateWith(::value))
    }

    private fun route(
        scope: BpmnModel.Scope,
        graph: RecipeGraph,
        route: RouteMeta,
        fork: BpmnModel.Node,
        join: BpmnModel.Node,
    ) {
        val rows = "ttrRows_${stableId(route.name)}"
        val read = http(scope, graph, "route ${route.name}", "route", rows, mapOf("route" to route.name))
        val verdictRoutes = d.field(d.field("ttrEvaluation", "verdict"), "routes")
        val count =
            expr(
                "$verdictRoutes.prop(${quote(route.name)}).prop('rowCount').numberValue() > 0",
                "get value($verdictRoutes,${quote(route.name)}).rowCount > 0",
            )
        val branch = scope.link(fork, read, route.name, count)
        model.routes +=
            buildJsonObject {
                put("recipe", graph.recipe.name)
                put("route", route.name)
                put("flow", branch.id)
                put("gateway", fork.id)
                put("task", read.id)
            }
        val complete = scope.node("exclusiveGateway", "route ${route.name} rows complete")
        scope.link(read, complete)
        val truncated = error(scope, "route ${route.name} evidence truncated", "run_refused")
        scope.default(complete, truncated)
        val rowScope = BpmnModel.Scope(stableId("${scope.id}/route/${route.name}"))
        val each = scope.node("subProcess", "each ${route.name} row", child = rowScope)
        val collection = expr("$rows.prop('rows').elements()", "$rows.rows")
        each.tail +=
            if (d ==
                Dialect.C7
            ) {
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
                                        mapOf(
                                            "inputCollection" to d.condition(collection),
                                            "inputElement" to "ttrRow",
                                        ),
                                    ),
                                ),
                        ),
                    ),
                )
            }
        scope.link(
            complete,
            each,
            "all rows available",
            eq(d.number(rows, "rowCount"), expr("$rows.prop('rows').elements().size()", "count($rows.rows)")),
        )
        scope.link(each, join)
        var last = rowScope.node("startEvent", "row start")
        graph.wirings
            .filter {
                it.routes.any { r ->
                    r.substringAfterLast('.') == route.name
                }
            }.distinctBy { it.action }
            .forEach { wiring ->
                val action = graph.actions.getValue(wiring.action)
                val sink =
                    action.sinks[graph.deployment.type] ?: action.sinks["camunda"] ?: action.sinks["native"]
                        ?: error("missing Camunda sink for ${wiring.action}")
                when {
                    sink == "user task" || action.approval == "required" && action.sinks["native"] == "task" -> {
                        val task = rowScope.node("userTask", "approve ${wiring.action}")
                        task.extensions +=
                            d.mapping(mapOf("ttrAction" to quote(wiring.action), "ttrKey" to rowKey(graph)))
                        if (d == Dialect.C8) task.extensions += Xml("zeebe:userTask")
                        rowScope.link(last, task)
                        last = task
                    }
                    sink == "send task" -> {
                        val gate = rowScope.node("exclusiveGateway", "${wiring.action} selected and wet")
                        val done = rowScope.node("exclusiveGateway", "${wiring.action} complete")
                        rowScope.link(last, gate)
                        rowScope.default(gate, done)
                        val columns = action.columns.filter { it.value == "role" || it.key == "to" && '.' in it.value }
                        require(columns.size == 1) { "mail ${wiring.action} needs exactly one address column" }
                        val (column, type) = columns.entries.single()
                        val source = requireNotNull(wiring.columns[column]) { "mail address wiring missing" }
                        val recipient =
                            if (type ==
                                "role"
                            ) {
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
                        val wet =
                            if (graph.deployment.dryRun) {
                                "false"
                            } else {
                                expr(
                                    "ttrStatus.hasProp('dryRun') && !ttrStatus.prop('dryRun').boolValue()",
                                    "ttrStatus.dryRun = false",
                                )
                            }
                        rowScope.link(gate, task, "selected and wet", d.and(wet, selected(graph, wiring.action)))
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
                                                "at" to expr("dateTime().toString()", "string(now())"),
                                            ),
                                        ),
                                ),
                            )
                        rowScope.link(task, outcome)
                        rowScope.link(outcome, done)
                        last = done
                    }
                    sink in
                        setOf(
                            "door",
                            "service task",
                            "transition",
                            "writer",
                            "obligation",
                            "task",
                            "message",
                            "start",
                            "http",
                        ) -> {
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

    private fun waitBefore(
        graph: RecipeGraph,
        step: StepMeta,
        previous: BpmnModel.Node,
        next: BpmnModel.Node,
        join: BpmnModel.Node,
        failure: BpmnModel.Node,
        outdated: BpmnModel.Node,
    ) {
        val scope = model.root
        if (step.obligations.isEmpty()) {
            val duration = step.time?.let { duration(it) }
            if (duration == null) {
                scope.link(previous, next)
            } else {
                val pause = timer(scope, "${step.name} time wait", duration)
                scope.link(previous, pause)
                scope.link(pause, next)
            }
            return
        }
        val status =
            http(
                scope,
                graph,
                "${step.name} previous obligations",
                "status",
                "ttrObligations",
                run = "ttrPreviousRunId",
            )
        val failedStatus =
            boundary(
                scope,
                status,
                "${step.name} invalid obligation evidence",
                Xml(
                    "bpmn:errorEventDefinition",
                    mapOf(
                        "errorRef" to "run_refused",
                    ),
                ),
            )
        val staleStatus =
            boundary(
                scope,
                status,
                "${step.name} stale obligation status",
                Xml(
                    "bpmn:errorEventDefinition",
                    mapOf(
                        "errorRef" to "stale_export",
                    ),
                ),
            )
        scope.link(failedStatus, failure)
        scope.link(staleStatus, outdated)
        if (d == Dialect.C7) {
            val script =
                """
                def body = execution.getVariable('ttrObligations')
                if (!body.hasProp('obligations') || !body.prop('obligations').isArray()) {
                    throw new org.camunda.bpm.engine.delegate.BpmnError('TTR_REFUSED', 'run status has no obligations array')
                }
                def kinds = [${step.obligations.joinToString(",") { quote(it) }}]
                def relevant = body.prop('obligations').elements().findAll { kinds.contains(it.prop('kind').stringValue()) }
                def now = java.time.Instant.now()
                def ready = relevant.every { o ->
                    def state = o.prop('state').stringValue()
                    (${"fulfilled" in step.until} && state == 'fulfilled') ||
                    (${"due" in step.until} && (state == 'due' || (o.hasProp('dueAt') && !o.prop('dueAt').isNull() && !java.time.OffsetDateTime.parse(o.prop('dueAt').stringValue()).toInstant().isAfter(now))))
                }
                def future = relevant.findAll { it.hasProp('dueAt') && !it.prop('dueAt').isNull() }.collect {
                    java.time.Duration.between(now, java.time.OffsetDateTime.parse(it.prop('dueAt').stringValue()).toInstant()).seconds
                }.findAll { it > 0 }
                execution.setVariable('ttrObligationReady', ready)
                execution.setVariable('ttrObligationDelay', 'PT' + Math.max(1L, Math.min(3600L, future ? future.min() : 3600L)) + 'S')
                """.trimIndent()
            status.extensions +=
                Xml(
                    "camunda:executionListener",
                    mapOf("event" to "end"),
                    listOf(
                        Xml(
                            "camunda:script",
                            mapOf(
                                "scriptFormat" to "groovy",
                            ),
                            text = script,
                        ),
                    ),
                )
        }
        scope.link(previous, status)
        val present = scope.node("exclusiveGateway", "${step.name} obligation absent")
        scope.link(status, present)
        val kinds = step.obligations.joinToString(",") { quote(it) }
        val relevant =
            expr(
                "ttrObligations.jsonPath(${quote("$.obligations[?(@.kind in [$kinds])]")}).elementList()",
                "ttrObligations.obligations[list contains([$kinds],kind)]",
            )
        val hasArray =
            expr(
                "ttrObligations.hasProp('obligations') && ttrObligations.prop('obligations').isArray()",
                "ttrObligations.obligations instance of list",
            )
        val checked = scope.node("exclusiveGateway", "${step.name} obligations ready")
        scope.link(present, checked, "obligations array", hasArray)
        scope.default(present, failure)
        val any = expr("$relevant.size() > 0", "count($relevant) > 0")
        if ("absent" in
            step.until
        ) {
            scope.link(checked, join, "absent", expr("$relevant.isEmpty()", "count($relevant) = 0"))
        }
        val readyC8 = mutableListOf<String>()
        if ("fulfilled" in step.until) readyC8 += "o.state = \"fulfilled\""
        if ("due" in step.until) readyC8 += "o.state = \"due\" or (o.dueAt != null and date and time(o.dueAt) <= now())"
        val readiness =
            expr(
                "ttrObligationReady",
                "every o in $relevant satisfies (${readyC8.joinToString(" or ").ifEmpty { "false" }})",
            )
        scope.link(checked, next, "ready", d.and(any, readiness))
        val waiting = BpmnModel.Scope(stableId("${scope.id}/${step.name}/wait"))
        val sub = scope.node("subProcess", "${step.name} obligation wait", child = waiting)
        scope.default(checked, sub)
        val ws = waiting.node("startEvent", "wait start")
        val wg = waiting.node("eventBasedGateway", "obligation messages")
        val we = waiting.node("endEvent", "obligation changed")
        waiting.link(ws, wg)
        step.obligations.forEach { kind ->
            step.until.filter { it in setOf("fulfilled", "due") }.forEach { state ->
                val message = "obligation.$kind.$state"
                val id = stableId("message/$message")
                model.messages[id] =
                    Xml(
                        "bpmn:message",
                        mapOf("id" to id, "name" to message),
                        if (d ==
                            Dialect.C8
                        ) {
                            listOf(
                                Xml(
                                    "bpmn:extensionElements",
                                    children =
                                        listOf(
                                            Xml(
                                                "zeebe:subscription",
                                                mapOf(
                                                    "correlationKey" to "=ttrPreviousRunId",
                                                ),
                                            ),
                                        ),
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    )
                val catch = waiting.node("intermediateCatchEvent", message)
                catch.tail += Xml("bpmn:messageEventDefinition", mapOf("messageRef" to id))
                waiting.link(wg, catch)
                waiting.link(catch, we)
            }
        }
        val delay =
            d.condition(
                expr(
                    "ttrObligationDelay",
                    "{future:(for o in $relevant[dueAt != null] return date and time(o.dueAt))[item > now()], nearest:min(append(future,now()+duration(\"PT1H\"))), delay:max([duration(\"PT1S\"),nearest-now()])}.delay",
                ),
            )
        val backstop = timer(waiting, "obligation nearest due or hourly backstop", delay)
        waiting.link(wg, backstop)
        waiting.link(backstop, we)
        scope.link(sub, status)
        if ("due" in step.until) {
            val due =
                boundary(
                    scope,
                    sub,
                    "${step.name} due recheck",
                    Xml("bpmn:timerEventDefinition", children = listOf(Xml("bpmn:timeDuration", text = delay))),
                )
            scope.link(due, status)
        }
    }

    private fun duration(raw: String): String {
        val m =
            Regex("([0-9.]+)\\s+(seconds|minutes|hours|days)").matchEntire(raw)
                ?: error("unsupported process wait $raw")
        val seconds =
            m.groupValues[1].toDouble() *
                mapOf("seconds" to 1, "minutes" to 60, "hours" to 3600, "days" to 86400).getValue(m.groupValues[2])
        return "PT${kotlin.math.ceil(seconds).toLong()}S"
    }

    fun render(): String {
        require(
            root.deployment.type ==
                if (d ==
                    Dialect.C7
                ) {
                    "camunda_7"
                } else {
                    "camunda_8"
                },
        ) { "Camunda target/dialect mismatch" }
        val steps =
            root.process?.steps
                ?: listOf(
                    StepMeta(
                        root.program.name,
                        root.program.name,
                        root.program.port,
                        emptyList(),
                        null,
                        emptyList(),
                        root,
                    ),
                )
        require(steps.isNotEmpty()) { "empty process" }
        val scope = model.root
        var last =
            scope.node(
                "startEvent",
                if (steps
                        .first()
                        .graph!!
                        .program.mode ==
                    "batch"
                ) {
                    "scheduled start"
                } else {
                    "start with ttrInput key"
                },
            )
        if (steps
                .first()
                .graph!!
                .program.mode == "batch"
        ) {
            val schedule =
                CamundaSchedule.parse(
                    requireNotNull(
                        steps
                            .first()
                            .graph!!
                            .program.schedule,
                    ),
                    d,
                )
            scheduleZone = schedule.zone
            last.tail +=
                Xml("bpmn:timerEventDefinition", children = listOf(Xml("bpmn:timeCycle", text = schedule.cycle)))
        }
        val terminate =
            scope.node("endEvent", "stopped for repair").also {
                it.tail +=
                    Xml("bpmn:terminateEventDefinition")
            }
        val outdated = scope.node("userTask", "export outdated")
        val refused = scope.node("userTask", "run refused; inspect evidence")
        if (d ==
            Dialect.C8
        ) {
            outdated.extensions += Xml("zeebe:userTask")
            refused.extensions += Xml("zeebe:userTask")
        }
        scope.link(outdated, terminate)
        scope.link(refused, terminate)
        steps.forEachIndexed { index, step ->
            val graph = requireNotNull(step.graph) { "missing graph for ${step.name}" }
            require(graph.process == null || root.process == null) { "recursive process" }
            require(
                graph.deployment == root.deployment &&
                    graph.program.name == step.program &&
                    (step.port ?: graph.program.port) == graph.program.port,
            ) { "invalid process step ${step.name}" }
            val sub = step(graph, step.name, index == 0)
            val done = scope.node("exclusiveGateway", "${step.name} step complete")
            if (index == 0) scope.link(last, sub) else waitBefore(graph, step, last, sub, done, refused, outdated)
            scope.link(sub, done)
            val stale =
                boundary(
                    scope,
                    sub,
                    "${step.name} stale export",
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
                    "${step.name} run refused",
                    Xml(
                        "bpmn:errorEventDefinition",
                        mapOf(
                            "errorRef" to "run_refused",
                        ),
                    ),
                )
            scope.link(stale, outdated)
            scope.link(fail, refused)
            last = done
        }
        scope.link(last, scope.node("endEvent", "complete"))
        val contract =
            buildJsonObject {
                put("schemaVersion", 1)
                put("target", root.deployment.type)
                put("deployment", root.deployment.name)
                put("recipes", JsonArray(steps.map { JsonPrimitive(it.graph!!.recipe.name) }))
                put("calls", JsonArray(model.calls))
                put("routes", JsonArray(model.routes))
                scheduleZone?.let { put("scheduleTimezone", it) }
            }
        return model.render(contract)
    }

    fun readme(): String =
        """
        # ${root.deployment.name} — Camunda ${if (d == Dialect.C7) "7" else "8"} BPMN file

        Import `${root.recipe.name}.${d.suffix}.bpmn` in the matching Camunda Modeler. This is an L1 orchestration
        skeleton; SQL and governed writes stay on the Server. No live Camunda is deployed by this export.
        The Modeler opening is Bora's manual acceptance; XSD/structure checks do not certify a live engine.

        C7 requires Camunda Connect's HTTP connector, Spin JSON and Groovy for Retry-After normalization and process
        obligation readiness. Configure TTR_CONN_AUTOMATION
        with url (origin, no trailing slash) and token. C8 uses the standard REST outbound connector
        io.camunda:http-json:1; supply TTR_AUTOMATION_URL and connector secret TTR_CONN_AUTOMATION.
        Credentials are referenced by name and never embedded. C7 cron timers use the engine JVM timezone:
        ${scheduleZone ?: "UTC (no batch timer)"}. C8 calendar timers use zoned ISO repeating intervals.

        Start with ttrInput={key:..., at:"offset ISO instant"}, keys, or event. Event payload is nested under event;
        at is separate. On-event deduplication requires a stable at; absent at is a new run. First batch steps use
        the engine execution id (C8 saves ttrExecutionId, generating one if absent). Every later step uses
        ttrAnchor + ':<step>', anchored on the first run id, including its recovered re-open id. A duplicate may
        claim a later step before the original; the original follows its 202 handle without repeating effects.
        Clocks and Open bodies are saved after process waits, once per step; retries reuse the saved body.

        201 alone drives evaluation. 200 replay completes the step; 202 polls, closed/waiting completes,
        abandoned/failed allows one re-open. A second non-201 recovery refuses. 409 raises TTR_STALE and reaches
        export outdated. Transport errors retain connector retries/incidents. Polls are bounded by budget + 2 min.
        Missing obligations/actions and truncated route pages refuse, never count as empty evidence.

        Send tasks are customer template nodes using TTR_CONN_MAIL (C7 external task / C8 job type). Their worker
        belongs to Platform and is not bundled. Resolve TTR_MAIL_RECIPIENTS for role recipients; per-row addresses
        come from the declared route column. The worker must refuse a missing/empty recipient and report success
        only after delivery; only then does BPMN report delivered to the Server. Both deployment and status must
        explicitly be wet, with the action selected for this key. Required approval is a Camunda user task carrying
        ttrAction/ttrKey; connect its completion to the exchange's task/approval integration before deployment.

        Stale when any step's recipe text/values/labels, program, deployment, world, toolchain or target version
        differs from the served bundle: re-export. The manifest covers all steps. The structure driver checks
        route branches, tasks, connections and named calls only; it does not execute connectors or compare verdicts.
        """.trimIndent() + "\n"
}
