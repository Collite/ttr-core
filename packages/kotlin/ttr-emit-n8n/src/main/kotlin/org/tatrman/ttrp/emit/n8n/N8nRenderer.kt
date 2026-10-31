// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.n8n

import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*

/** Orchestration only: route decisions and all SQL stay on the Server. */
internal class N8nRenderer(
    private val request: RecipeEmitRequest,
) {
    private val root = request.graph
    private val flow = Workflow(root.process?.name ?: root.program.name)
    private var timezone = "UTC"
    private val dollar = '$'

    fun render(): String {
        require(root.deployment.type == "n8n_l1") { "n8n exporter requires n8n_l1" }
        val process = root.process
        val graphs =
            process?.steps?.map { requireNotNull(it.graph) { "process step ${it.name} has no graph" } } ?: listOf(root)
        require(graphs.isNotEmpty()) { "process has no steps" }
        trigger(graphs.first())
        var previous = "trigger"
        val opens = mutableListOf<String>()
        process?.steps?.forEach { step ->
            require(
                step.graph!!.process == null &&
                    step.graph!!.program.name == step.program &&
                    step.graph!!.deployment.name == root.deployment.name,
            ) { "invalid step graph ${step.name}" }
        }
        graphs.forEachIndexed { index, graph ->
            val prefix =
                process
                    ?.steps
                    ?.get(index)
                    ?.name
                    ?.let { "$it " } ?: ""
            val step = process?.steps?.get(index)
            var skip: String? = null
            if (step != null && index > 0 && step.obligations.isNotEmpty()) {
                val previousId = "[${opens.joinToString(
                    ",",
                ) { literal(it) }}].reverse().map(n => $dollar(n)).find(n => n.isExecuted).first().json.body.runId"
                val status = http(graph, prefix + "previous status", "status", "GET", previousId)
                flow.link(previous, status)
                val kinds = arr(step.obligations.map(::str)).toString()
                val relevant = "(${dollar}json.body.obligations || []).filter(o => $kinds.includes(o.kind))"
                val exists = flow.condition(prefix + "obligation present", "$relevant.length > 0")
                flow.link(status, exists)
                val wait =
                    flow.node(
                        prefix + "obligation wait",
                        "wait",
                        json(
                            "resume" to str("webhook"),
                            "httpMethod" to str("POST"),
                            "limitWaitTime" to JsonPrimitive(true),
                            "limitType" to str("afterTimeInterval"),
                            "resumeAmount" to JsonPrimitive(1),
                            "resumeUnit" to str("minutes"),
                            "options" to json("webhookSuffix" to str(prefix.trim())),
                        ),
                    )
                val ready =
                    flow.condition(
                        prefix + "obligation ready",
                        "$relevant.every(o => ['fulfilled','due'].includes(o.state) || (o.dueAt && Date.parse(o.dueAt) <= Date.now()))",
                    )
                flow.link(exists, ready)
                flow.link(ready, wait, 1)
                flow.link(wait, status)
                previous = ready
                skip = if ("absent" in step.until) exists else null
                if (skip == null) flow.link(exists, wait, 1)
            } else if (step?.time != null) {
                val duration =
                    Regex("([0-9.]+)\\s+(seconds|minutes|hours|days)").matchEntire(requireNotNull(step.time))
                        ?: error("unsupported process wait duration ${step.time}")
                val wait =
                    flow.node(
                        prefix + "time wait",
                        "wait",
                        json(
                            "resume" to str("timeInterval"),
                            "amount" to JsonPrimitive(duration.groupValues[1].toDouble()),
                            "unit" to str(duration.groupValues[2]),
                        ),
                    )
                flow.link(previous, wait)
                previous = wait
            }
            val (entry, exit) = recipe(graph, prefix, index == 0)
            flow.link(previous, entry)
            // An absent obligation skips the associated recipe and joins the next process step.
            val join = flow.code(prefix + "step complete", "return [{json:{}}];")
            flow.link(exit, join)
            skip?.let { flow.link(it, join, 1) }
            previous = join
            opens += prefix + "open"
        }
        val meta =
            json(
                "schemaVersion" to JsonPrimitive(1),
                "deployment" to str(root.deployment.name),
                "recipes" to arr(graphs.map { str(it.recipe.name) }),
                "calls" to arr(flow.calls),
                "nodeVersions" to JsonObject(NodeFactory.versions.mapValues { JsonPrimitive(it.value) }),
            )
        return flow.render(meta, timezone)
    }

    private fun trigger(graph: RecipeGraph) {
        if (graph.program.mode == "batch") {
            val schedule = requireNotNull(graph.program.schedule) { "batch n8n export needs a schedule hint" }
            val match = Regex("(\\d{2}):(\\d{2})\\s+(.+)").matchEntire(schedule)
            val cron =
                if (match != null) {
                    val hour = match.groupValues[1].toInt()
                    val minute = match.groupValues[2].toInt()
                    require(hour in 0..23 && minute in 0..59) { "invalid schedule time" }
                    timezone =
                        java.time.ZoneId
                            .of(match.groupValues[3])
                            .id
                    "0 $minute $hour * * *"
                } else {
                    schedule
                }
            flow.node(
                "trigger",
                "scheduleTrigger",
                json(
                    "rule" to
                        json(
                            "interval" to
                                arr(
                                    listOf(json("field" to str("cronExpression"), "expression" to str(cron))),
                                ),
                        ),
                ),
            )
        } else {
            flow.node(
                "trigger",
                "webhook",
                json(
                    "httpMethod" to str("POST"),
                    "path" to str(root.deployment.name),
                    "responseMode" to str("onReceived"),
                    "options" to json(),
                ),
            )
        }
    }

    private fun recipe(
        graph: RecipeGraph,
        prefix: String,
        initial: Boolean,
    ): Pair<String, String> {
        require(graph.deployment.granularity in setOf("recipe", "parts", "islands")) { "unsupported n8n granularity" }
        val openName = prefix + "open"
        val input = "($dollar('trigger').first().json.body || $dollar('trigger').first().json)"
        val keys = graph.recipe.key.map { it.substringAfterLast('.') }
        val digestSources = mutableListOf<Pair<String, String>>()
        val key =
            if (keys.size ==
                1
            ) {
                "$input.key ?? $input[${literal(keys.single())}]"
            } else {
                "$input.key ?? {${keys.joinToString(",") { "${literal(it)}:$input[${literal(it)}]" }}}"
            }
        val port =
            when (graph.program.port) {
                "none", null -> "{none:true}"
                "key" -> "{key:($key)}"
                "keys" -> "{keys:$input.keys}"
                "event" -> "{event:$input}"
                else -> error("unsupported n8n port ${graph.program.port}")
            }
        val fp = graph.recipe.fingerprints
        val body =
            "(() => {const b = {deployment:${literal(
                root.deployment.name,
            )}, port:$port, origin:'engine', dryRun:${root.deployment.dryRun}, recipeFingerprint:${literal(
                fp.getValue("text"),
            )}, recipeValuesFingerprint:${literal(fp.getValue("values"))}}; " +
                (fp["program"]?.let { "b.programFingerprint=${literal(it)}; " } ?: "") +
                (fp["deployment"]?.let { "b.deploymentFingerprint=${literal(it)}; " } ?: "") +
                (if (graph.program.mode == "batch") "b.idempotencyKey=${dollar}execution.id; " else "") +
                (if (initial) "if ($input.at) b.port.at=$input.at; " else "b.port.at=new Date().toISOString(); ") +
                "return b;})()"
        val open = http(graph, openName, "open", "POST", body = body)
        val id = "$dollar(${literal(openName)}).first().json.body.runId"
        val complete = flow.code(prefix + "run complete", "return [{json:{}}];")
        val replayed = flow.condition(prefix + "replayed", "${dollar}json.body.replayed === true")
        flow.link(open, replayed)
        flow.link(replayed, complete)
        val fresh = flow.code(prefix + "fresh run", "return ${dollar}input.all();")
        flow.link(replayed, fresh, 1)
        var last = fresh

        fun chain(
            call: String,
            name: String,
            args: Map<String, String> = emptyMap(),
        ) {
            val node = http(graph, prefix + name, call, "POST", id, args)
            flow.link(last, node)
            last = node
        }
        when (graph.deployment.granularity) {
            "parts" -> {
                chain("part", "candidates", mapOf("part" to "candidates"))
                graph.roles.contexts.forEach { chain("part", it.name, mapOf("part" to it.name)) }
                if (graph.roles.decision != null) chain("part", "decision", mapOf("part" to "decision"))
            }
            "islands" -> {
                require(graph.islandWaves.isNotEmpty()) { "islands export needs verified island waves" }
                graph.islandWaves.flatten().forEach { chain("island", "island $it", mapOf("island" to it)) }
            }
        }
        chain("evaluate", "evaluate")
        // A 202 is never interpreted as an empty verdict. Poll the existing status endpoint.
        val checked =
            flow.code(
                prefix + "check evaluation",
                "if (${dollar}runIndex > 120) throw new Error('evaluation polling timed out'); const b=${dollar}input.first().json.body; if (['failed','abandoned','waiting'].includes(b.status)) throw new Error('evaluation refused'); return ${dollar}input.all();",
            )
        flow.link(last, checked)
        val evaluated =
            flow.condition(
                prefix + "evaluated",
                "!!${dollar}json.body.verdict && !['running','open'].includes(${dollar}json.body.status)",
            )
        flow.link(checked, evaluated)
        val polling =
            flow.node(
                prefix + "evaluation wait",
                "wait",
                json(
                    "resume" to str("timeInterval"),
                    "amount" to JsonPrimitive(1),
                    "unit" to str("seconds"),
                ),
            )
        val status = http(graph, prefix + "evaluation status", "status", "GET", id)
        flow.link(evaluated, polling, 1)
        flow.link(polling, status)
        flow.link(status, checked)
        val routeNames = arr(graph.routes.filterNot { it.isNone }.map { str(it.name) }).toString()
        val prepare =
            flow.code(
                prefix + "route items",
                """
                const b = ${dollar}input.first().json.body;
                if (['failed','abandoned'].includes(b.status)) throw new Error('recipe evaluation failed');
                const routes = $routeNames.filter(r => (b.verdict.routes[r]?.rowCount || 0) > 0);
                return (routes.length ? routes : [null]).map(route => ({json:{route}}));
                """.trimIndent(),
            )
        val actionStatus = http(graph, prefix + "action status", "status", "GET", id)
        flow.link(evaluated, actionStatus)
        flow.link(actionStatus, prepare)
        val loop =
            flow.node(
                prefix + "routes loop",
                "splitInBatches",
                json(
                    "batchSize" to JsonPrimitive(1),
                    "options" to json(),
                ),
            )
        flow.link(prepare, loop)
        val switch =
            flow.node(
                prefix + "routes",
                "switch",
                json(
                    "rules" to
                        json(
                            "values" to
                                arr(
                                    graph.routes.filterNot { it.isNone }.map { route ->
                                        json(
                                            "conditions" to
                                                conditions(
                                                    "${dollar}json.route === ${literal(route.name)}",
                                                ),
                                            "renameOutput" to JsonPrimitive(true),
                                            "outputKey" to str(route.name),
                                        )
                                    },
                                ),
                        ),
                    "options" to
                        json(
                            "allMatchingOutputs" to JsonPrimitive(graph.roles.switchMode == "overlapping"),
                            "fallbackOutput" to str("extra"),
                        ),
                ),
            )
        flow.link(loop, switch, 1)
        val routes = graph.routes.filterNot { it.isNone }
        flow.link(switch, loop, routes.size)
        val digest = graph.program.deliver?.contains("digest") == true
        routes.forEachIndexed { index, route ->
            val read =
                http(
                    graph,
                    prefix + route.name + " rows",
                    "route",
                    "GET",
                    id,
                    mapOf("route" to route.name),
                    paginate = true,
                )
            flow.link(switch, read, index)
            var tail =
                flow.code(
                    prefix + route.name + " collect",
                    "const rows = ${dollar}input.all().flatMap(i => i.json.body?.rows || i.json.rows || []); return [{json:{rows}}];",
                )
            flow.link(read, tail)
            val rowNode = tail
            graph.wirings
                .filter {
                    it.routes.any { r ->
                        r.substringAfterLast('.') == route.name
                    }
                }.distinctBy { it.action }
                .forEach { wiring ->
                    val action = graph.actions.getValue(wiring.action)
                    val sink =
                        action.sinks["n8n_l1"] ?: action.sinks["n8n"] ?: action.sinks["native"]
                            ?: error("no n8n sink for ${wiring.action}")
                    val routeRows = "($dollar(${literal(
                        rowNode,
                    )}).isExecuted ? $dollar(${literal(rowNode)}).first().json.rows : [])"
                    val statusBody = "$dollar(${literal(prefix + "action status")}).first().json.body"
                    val previewKey = "(a.key && typeof a.key === 'object' && !Array.isArray(a.key) ? ${keyProjection(
                        "a.key",
                        keys,
                    )} : a.key)"
                    val rows =
                        if (sink == "n8n-nodes-base.emailSend") {
                            "$routeRows.filter(r => ($statusBody.actions || []).some(a => a.action === ${literal(
                                wiring.action,
                            )} && JSON.stringify(${keyProjection("r", keys)}) === JSON.stringify($previewKey)))"
                        } else {
                            routeRows
                        }
                    if (digest && action.deliverable) {
                        digestSources += rows to (wiring.columns["who"] ?: "")
                        return@forEach
                    }
                    val selectedKeys = "$rows.map(r => ${keyProjection("r", keys)})"
                    val actionName = prefix + route.name + " " + wiring.action
                    if (sink == "n8n-nodes-base.emailSend") {
                        val mail = email(actionName, rows, literal(wiring.columns["who"] ?: ""))
                        val deliver = flow.condition("$actionName deliver", "!$statusBody.dryRun && $rows.length > 0")
                        flow.link(tail, deliver)
                        flow.link(deliver, mail)
                        tail = mail
                        val outcome =
                            http(
                                graph,
                                "$actionName outcome",
                                "outcome",
                                "POST",
                                id,
                                mapOf(
                                    "action" to wiring.action,
                                ),
                                body =
                                    "{outcomes:$selectedKeys.map(key => " +
                                        "({key,status:'delivered',at:new Date().toISOString()}))}",
                            )
                        flow.link(tail, outcome)
                        val delivered = flow.code("$actionName complete", "return [{json:{}}];")
                        flow.link(outcome, delivered)
                        flow.link(deliver, delivered, 1)
                        tail = delivered
                    } else {
                        require(
                            sink in
                                setOf(
                                    "door",
                                    "transition",
                                    "writer",
                                    "obligation",
                                    "task",
                                    "message",
                                    "start",
                                    "n8n-nodes-base.httpRequest",
                                ),
                        ) { "unsupported n8n action sink $sink" }
                        val suffix = actionName.replace(' ', '-')
                        val perform =
                            http(
                                graph,
                                actionName,
                                "perform",
                                "POST",
                                id,
                                mapOf("action" to wiring.action),
                                body =
                                    "{keys:$selectedKeys" +
                                        (
                                            if (wiring.action ==
                                                "ask"
                                            ) {
                                                ",callbackUrl:${dollar}execution.resumeUrl + '/$suffix'"
                                            } else {
                                                ""
                                            }
                                        ) +
                                        "}",
                            )
                        flow.link(tail, perform)
                        tail = perform
                        if (wiring.action == "ask") {
                            val wait =
                                flow.node(
                                    "$actionName wait",
                                    "wait",
                                    json(
                                        "resume" to str("webhook"),
                                        "httpMethod" to str("POST"),
                                        "options" to json("webhookSuffix" to str(suffix)),
                                    ),
                                )
                            val pending =
                                flow.condition(
                                    "$actionName task pending",
                                    "(${dollar}json.body.performed || []).some(p => p.taskId)",
                                )
                            val resumed = flow.code("$actionName resumed", "return [{json:{}}];")
                            flow.link(tail, pending)
                            flow.link(pending, wait)
                            flow.link(pending, resumed, 1)
                            flow.link(wait, resumed)
                            tail = resumed
                        }
                    }
                }
            flow.link(tail, loop)
        }
        var done = loop
        var doneOutput = 0
        if (digest) {
            val digests = http(graph, prefix + "digests", "digests", "GET", id)
            flow.link(done, digests, doneOutput)
            val sources =
                digestSources.joinToString(",") { (rows, who) ->
                    "...$rows.map(row => ({...row,who:${literal(who)}}))"
                }
            val groups =
                flow.code(
                    prefix + "digest groups",
                    "const rows=[$sources]; const groups = ($dollar(${literal(
                        prefix + "action status",
                    )}).first().json.body.dryRun ? [] : ${dollar}input.first().json.body.groups).map(g => ({...g,rowsRef:g.rows,rows:rows.filter(r => Object.entries(g.by).every(([k,v]) => r[k] === v))})); if(groups.some(g=>!g.rows.length)) throw new Error('digest group has no resolved rows'); return [{json:{groups:groups.length ? groups : [{empty:true}]}}];",
                )
            flow.link(digests, groups)
            val split =
                flow.node(
                    prefix + "digest split",
                    "splitOut",
                    json(
                        "fieldToSplitOut" to str("groups"),
                        "options" to json("destinationFieldName" to str("group")),
                    ),
                )
            flow.link(groups, split)
            val nonempty = flow.condition(prefix + "digest nonempty", "!${dollar}json.group.empty")
            flow.link(split, nonempty)
            val mail = email(prefix + "digest mail", "${dollar}json.group.rows", "${dollar}json.group.by.who")
            flow.link(nonempty, mail)
            val delivered =
                graph.wirings
                    .filter {
                        graph.actions
                            .getValue(
                                it.action,
                            ).deliverable
                    }.map { it.action }
                    .distinct()
            require(delivered.size == 1) { "digest rendering requires one deliverable action" }
            val outcome =
                http(
                    graph,
                    prefix + "digest outcome",
                    "outcome",
                    "POST",
                    id,
                    mapOf(
                        "action" to delivered.single(),
                    ),
                    body = "{outcomes:$dollar(${literal(
                        prefix + "digest split",
                    )}).item.json.group.rows.map(r => ({key:${keyProjection(
                        "r",
                        keys,
                    )},status:'delivered',at:new Date().toISOString()}))}",
                )
            flow.link(mail, outcome)
            val collect = flow.code(prefix + "digest complete", "return [{json:{}}];")
            flow.link(outcome, collect)
            flow.link(nonempty, collect, 1)
            done = collect
            doneOutput = 0
        }
        val close = http(graph, prefix + "close", "close", "POST", id)
        flow.link(done, close, doneOutput)
        flow.link(close, complete)
        return open to complete
    }

    private fun keyProjection(
        row: String,
        keys: List<String>,
    ): String =
        if (keys.size == 1) {
            "$row[${literal(keys.single())}]"
        } else {
            "{${keys.joinToString(",") { "${literal(it)}:$row[${literal(it)}]" }}}"
        }

    private fun email(
        name: String,
        rows: String,
        who: String,
    ): String =
        flow.node(
            name,
            "emailSend",
            json(
                "fromEmail" to str(expression("${dollar}env.TTR_MAIL_FROM")),
                "toEmail" to str(expression("JSON.parse(${dollar}env.TTR_MAIL_RECIPIENTS || '{}')[$who] || $who")),
                "subject" to str("Tatrman ${root.program.name}"),
                "emailFormat" to str("text"),
                "text" to str(expression("JSON.stringify($rows, null, 2)")),
                "options" to json("appendAttribution" to JsonPrimitive(false)),
            ),
        )

    private fun http(
        graph: RecipeGraph,
        node: String,
        call: String,
        method: String,
        id: String? = null,
        args: Map<String, String> = emptyMap(),
        body: String = "{}",
        paginate: Boolean = false,
    ): String {
        var path = graph.runApi.basePath.trimEnd('/') + graph.runApi.calls.getValue(call)
        (args + ("recipe" to graph.recipe.name)).forEach { (key, value) ->
            require(value.matches(Regex("[A-Za-z0-9_.-]+"))) { "invalid API identifier" }
            path = path.replace("{$key}", value)
        }
        val url =
            if ("{id}" in path) {
                requireNotNull(id)
                "${dollar}env.TTR_AUTOMATION_URL.replace(/\\/$/, '') + ${literal(
                    path.substringBefore("{id}"),
                )} + encodeURIComponent($id) + ${literal(path.substringAfter("{id}"))}"
            } else {
                "${dollar}env.TTR_AUTOMATION_URL.replace(/\\/$/, '') + ${literal(path)}"
            }
        val parameters =
            linkedMapOf<String, JsonElement>(
                "url" to str(expression(url)),
                "method" to str(method),
                "authentication" to str("genericCredentialType"),
                "genericAuthType" to str("httpBearerAuth"),
            )
        if (method ==
            "POST"
        ) {
            parameters +=
                mapOf(
                    "sendBody" to JsonPrimitive(true),
                    "specifyBody" to str("json"),
                    "jsonBody" to str(expression(body)),
                )
        }
        parameters["options"] =
            json(
                "response" to
                    json("response" to json("responseFormat" to str("json"), "fullResponse" to JsonPrimitive(true))),
            )
        if (paginate) {
            parameters["options"] =
                JsonObject(
                    parameters.getValue("options").jsonObject + (
                        "pagination" to
                            json(
                                "pagination" to
                                    json(
                                        "paginationMode" to str("updateAParameterInEachRequest"),
                                        "parameters" to
                                            json(
                                                "parameters" to
                                                    arr(
                                                        listOf(
                                                            json(
                                                                "type" to str("qs"),
                                                                "name" to str("offset"),
                                                                "value" to
                                                                    str(
                                                                        expression(
                                                                            "${dollar}pageCount === 0 ? 0 : Number(${dollar}request.qs.offset || 0) + ${dollar}response.body.rows.length",
                                                                        ),
                                                                    ),
                                                            ),
                                                            json(
                                                                "type" to str("qs"),
                                                                "name" to str("limit"),
                                                                "value" to str("1000"),
                                                            ),
                                                        ),
                                                    ),
                                            ),
                                        "paginationCompleteWhen" to str("other"),
                                        "completeExpression" to
                                            str(
                                                expression(
                                                    "Number(${dollar}request.qs.offset || 0) + ${dollar}response.body.rows.length >= ${dollar}response.body.rowCount",
                                                ),
                                            ),
                                        "limitPagesFetched" to JsonPrimitive(true),
                                        "maxRequests" to JsonPrimitive(10000),
                                    ),
                            )
                    ),
                )
        }
        flow.calls +=
            JsonObject(
                json(
                    "node" to str(node),
                    "name" to str(call),
                    "method" to str(method),
                    "path" to str(path),
                    "recipe" to str(graph.recipe.name),
                    "program" to str(graph.program.name),
                    "mode" to str(graph.program.mode ?: "on_event"),
                    "port" to str(graph.program.port ?: "none"),
                    "key" to arr(graph.recipe.key.map(::str)),
                    "parameters" to JsonObject(parameters),
                    "arguments" to JsonObject(args.mapValues { str(it.value) }),
                ) +
                    args.mapValues { str(it.value) },
            )
        return flow.node(node, "httpRequest", JsonObject(parameters))
    }

    fun readme(): String =
        """
        # ${root.deployment.name}

        Import `workflow.json` into n8n. Bind the `tatrman-automation` HTTP bearer credential to an identity granted
        this deployment and the `tatrman-mail` SMTP credential for engine-side mail. Set `TTR_AUTOMATION_URL` to the
        Server origin (without `/automation`), `TTR_MAIL_FROM`, and `TTR_MAIL_RECIPIENTS` to a JSON object mapping role
        names to email addresses. Enable environment access for these expressions on the n8n worker.
        The workflow imports inactive; review the credentials and schedule before activation.

        On-event input is posted to `/webhook/${root.deployment.name}` with the recipe key fields (or `key`), `keys`,
        or the declared event body. Batch runs derive their idempotency key from the n8n execution id; on-event
        runs let the Server derive it. `ask` performs on the Server and registers the Wait webhook as `callbackUrl`.
        Process obligation waits can be resumed by their webhook and also poll once per minute, including due checks.
        Route rows feed engine-side mail; outcomes are recorded on the Server. HTTP failures, including stale
        fingerprint refusals, stop the workflow before downstream nodes. Re-export after any stale refusal.

        Node parameter references: https://github.com/n8n-io/n8n/tree/master/packages/nodes-base/nodes
        The exact node `typeVersion`s are pinned in the plugin manifest and export metadata.

        ${request.readme.lines().lastOrNull { it.startsWith("Stale when") } ?: request.readme}
        """.trimIndent() + "\n"
}
