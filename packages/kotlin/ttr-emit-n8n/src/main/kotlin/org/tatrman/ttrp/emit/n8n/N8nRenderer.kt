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
                    step.graph!!.deployment == root.deployment &&
                    step.graph!!.program.port == (step.port ?: step.graph!!.program.port),
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
                val obligations =
                    flow.code(
                        prefix + "previous obligations",
                        "if (!Array.isArray(${dollar}json.body.obligations)) throw new Error('run status has no obligations field (ttr-server §1.2 addition); this process export needs it'); return ${dollar}input.all();",
                    )
                flow.link(status, obligations)
                val relevant = "${dollar}json.body.obligations.filter(o => $kinds.includes(o.kind))"
                val exists = flow.condition(prefix + "obligation present", "$relevant.length > 0")
                flow.link(obligations, exists)
                val wait =
                    flow.node(
                        prefix + "obligation wait",
                        "wait",
                        json(
                            "resume" to str("webhook"),
                            "httpMethod" to str("POST"),
                            "limitWaitTime" to JsonPrimitive(true),
                            "limitType" to str("afterTimeInterval"),
                            "resumeAmount" to
                                str(
                                    expression(
                                        "Math.max(1, Math.min(3600, ...$relevant.filter(o => o.dueAt && Date.parse(o.dueAt) > Date.now()).map(o => Math.ceil((Date.parse(o.dueAt) - Date.now()) / 1000))))",
                                    ),
                                ),
                            "resumeUnit" to str("seconds"),
                            "options" to json("webhookSuffix" to str(prefix.trim())),
                        ),
                    )
                val ready =
                    flow.condition(
                        prefix + "obligation ready",
                        "$relevant.every(o => " +
                            listOfNotNull(
                                "(o.state === 'fulfilled')".takeIf { "fulfilled" in step.until },
                                "(o.state === 'due' || (o.dueAt && Date.parse(o.dueAt) <= Date.now()))".takeIf {
                                    "due" in
                                        step.until
                                },
                            ).joinToString(" || ").ifEmpty { "false" } + ")",
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
            opens += prefix + "reopen"
        }
        val meta =
            json(
                "schemaVersion" to JsonPrimitive(1),
                "deployment" to str(root.deployment.name),
                "recipes" to arr(graphs.map { str(it.recipe.name) }),
                "calls" to arr(flow.calls),
                "mail" to arr(flow.mail),
                "roles" to
                    arr(
                        graphs
                            .flatMap { graph ->
                                graph.wirings.flatMap { wiring ->
                                    graph.actions
                                        .getValue(wiring.action)
                                        .columns
                                        .filterValues {
                                            it ==
                                                "role"
                                        }.keys
                                        .mapNotNull { wiring.columns[it] }
                                }
                            }.distinct()
                            .sorted()
                            .map(::str),
                    ),
                "nodeVersions" to JsonObject(NodeFactory.versions.mapValues { JsonPrimitive(it.value) }),
            )
        return flow.render(meta, timezone)
    }

    private fun trigger(graph: RecipeGraph) {
        if (graph.program.mode == "batch") {
            val schedule = requireNotNull(graph.program.schedule) { "batch n8n export needs a schedule hint" }
            val parsed = ScheduleHint.parse(schedule)
            timezone = parsed.timezone
            val cron = parsed.cron
            flow.node(
                "trigger",
                "scheduleTrigger",
                json(
                    "rule" to
                        json(
                            "interval" to
                                arr(
                                    listOf(
                                        parsed.minutes?.let {
                                            json(
                                                "field" to str("minutes"),
                                                "minutesInterval" to JsonPrimitive(it),
                                            )
                                        }
                                            ?: json("field" to str("cronExpression"), "expression" to str(cron)),
                                    ),
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
        val digestSources = mutableListOf<String>()
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
                "event" -> "{event:Object.fromEntries(Object.entries($input).filter(([k]) => k !== 'at'))}"
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
                (
                    if (graph.program.mode == "batch") {
                        "b.idempotencyKey=${dollar}execution.id" +
                            (if (initial) "" else " + ${literal(":" + prefix.trim())}") +
                            "; "
                    } else {
                        ""
                    }
                ) +
                (
                    if (graph.program.port in
                        setOf(null, "none")
                    ) {
                        ""
                    } else if (initial) {
                        "if ($input.at) b.port.at=$input.at; "
                    } else {
                        "b.port.at=$dollar(${literal(
                            prefix + "clock",
                        )}).first().json.at; "
                    }
                ) +
                "return b;})()"
        val prepared = flow.code(prefix + "open input", "return [{json:{openBody:$body}}];")
        val open =
            http(
                graph,
                openName,
                "open",
                "POST",
                body = "$dollar(${literal(prefix + "open input")}).first().json.openBody",
            )
        flow.link(prepared, open)
        val id = "($dollar(${literal(
            prefix + "reopen",
        )}).isExecuted ? $dollar(${literal(
            prefix + "reopen",
        )}) : $dollar(${literal(openName)})).first().json.body.runId"
        val clock =
            if (initial) {
                null
            } else {
                flow.code(
                    prefix + "clock",
                    "return [{json:{at:new Date().toISOString(), started:Date.now()}}];",
                )
            }
        clock?.let { flow.link(it, prepared) }
        val complete = flow.code(prefix + "run complete", "return [{json:{}}];")
        val fresh = flow.code(prefix + "fresh run", "return [{json:{started:Date.now()}}];")
        val claimed = flow.condition(prefix + "claimed", "${dollar}json.statusCode === 201")
        flow.link(open, claimed)
        flow.link(claimed, fresh)
        val replayed =
            flow.condition(
                prefix + "replayed",
                "${dollar}json.statusCode === 200 && ${dollar}json.body.replayed === true",
            )
        flow.link(claimed, replayed, 1)
        flow.link(replayed, complete)
        val awaiting =
            flow.code(
                prefix + "await handle",
                "if (${dollar}json.statusCode !== 202) throw new Error('unexpected Open response'); return [{json:{...${dollar}json, started:Date.now()}}];",
            )
        flow.link(replayed, awaiting, 1)
        val handleWait =
            flow.node(
                prefix + "handle wait",
                "wait",
                json(
                    "resume" to str("timeInterval"),
                    "amount" to str(expression(retryAfter())),
                    "unit" to str("seconds"),
                ),
            )
        val handleStatus = http(graph, prefix + "handle status", "status", "GET", id)
        val handleStart = "$dollar(${literal(prefix + "await handle")}).first().json.started"
        val timeout = pollBudget(graph)
        val handleCheck =
            flow.code(
                prefix + "check handle",
                "if(!['closed','waiting','failed','abandoned'].includes(${dollar}json.body.status) && Date.now() - $handleStart > $timeout) throw new Error('live handle polling timed out'); return ${dollar}input.all();",
            )
        val terminal =
            flow.condition(
                prefix + "handle complete",
                "['closed','waiting'].includes(${dollar}json.body.status)",
            )
        val abandoned =
            flow.condition(
                prefix + "handle abandoned",
                "['abandoned','failed'].includes(${dollar}json.body.status)",
            )
        // A second 202 is an error: the duplicate must never drive a run another execution owns.
        val reopen =
            http(
                graph,
                prefix + "reopen",
                "open",
                "POST",
                body = "$dollar(${literal(prefix + "open input")}).first().json.openBody",
            )
        val reopened =
            flow.code(
                prefix + "check reopen",
                "if (${dollar}json.statusCode !== 201) throw new Error('re-open did not claim run (second live handle)'); return ${dollar}input.all();",
            )
        flow.link(awaiting, handleWait)
        flow.link(handleWait, handleStatus)
        flow.link(handleStatus, handleCheck)
        flow.link(handleCheck, terminal)
        flow.link(terminal, complete)
        flow.link(terminal, abandoned, 1)
        flow.link(abandoned, reopen)
        flow.link(abandoned, handleWait, 1)
        flow.link(reopen, reopened)
        flow.link(reopened, fresh)
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
                graph.roles.contexts.forEach { chain("part", "context ${it.name}", mapOf("part" to it.name)) }
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
                "if (Date.now() - $dollar(${literal(
                    prefix + "fresh run",
                )}).first().json.started > ${pollBudget(
                    graph,
                )}) throw new Error('evaluation polling timed out'); const b=${dollar}input.first().json.body; if (['failed','abandoned','waiting'].includes(b.status)) throw new Error('evaluation refused'); return ${dollar}input.all();",
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
                    "amount" to str(expression(retryAfter())),
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
        val actionCheck =
            flow.code(
                prefix + "check actions",
                "if (!Array.isArray(${dollar}json.body.actions)) throw new Error('run status has no actions field; per-key selection required'); return ${dollar}input.all();",
            )
        flow.link(evaluated, actionStatus)
        flow.link(actionStatus, actionCheck)
        flow.link(actionCheck, prepare)
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
                    prefix + "route " + route.name + " rows",
                    "route",
                    "GET",
                    id,
                    mapOf("route" to route.name),
                    paginate = true,
                )
            flow.link(switch, read, index)
            var tail =
                flow.code(
                    prefix + "route " + route.name + " collect",
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
                            "$routeRows.filter(r => ($statusBody.actions).some(a => a.action === ${literal(
                                wiring.action,
                            )} && JSON.stringify(${keyProjection("r", keys)}) === JSON.stringify($previewKey)))"
                        } else {
                            routeRows
                        }
                    if (digest && action.deliverable) {
                        val address = mailAddress(action, wiring)
                        digestSources +=
                            "...$rows.map(row => ({...row,who:${address.role?.let(
                                ::literal,
                            ) ?: "row[${literal(
                                requireNotNull(address.column),
                            )}]"},to:${address.recipient("row", keys)}}))"
                        return@forEach
                    }
                    val selectedKeys = "$rows.map(r => ${keyProjection("r", keys)})"
                    val actionName = prefix + "action " + route.name + " " + wiring.action
                    if (sink == "n8n-nodes-base.emailSend") {
                        val address = mailAddress(action, wiring)
                        val recipientExpression = address.recipient("row", keys)
                        val groups =
                            flow.code(
                                "$actionName groups",
                                "const rows=$rows; const groups=new Map(); for(const row of rows) {const to=$recipientExpression; if(!groups.has(to)) groups.set(to,[]); groups.get(to).push(row);} return [{json:{groups:groups.size ? [...groups].map(([to,rows])=>({to,rows})) : [{to:null,rows:[]}]}}];",
                            )
                        flow.link(tail, groups)
                        val split =
                            flow.node(
                                "$actionName split",
                                "splitOut",
                                json(
                                    "fieldToSplitOut" to str("groups"),
                                    "options" to json("destinationFieldName" to str("mail")),
                                ),
                            )
                        flow.link(groups, split)
                        val mailRows = "$dollar(${literal(split)}).item.json.mail.rows"
                        val recipient = "$dollar(${literal(split)}).item.json.mail.to"
                        val mail = email(graph, actionName, mailRows, recipient, wiring.action, keys)
                        val deliver =
                            flow.condition(
                                "$actionName deliver",
                                "${root.deployment.dryRun} === false && $statusBody.dryRun === false && ${dollar}json.mail.rows.length > 0",
                            )
                        flow.link(split, deliver)
                        flow.link(deliver, mail)
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
                                body = "{outcomes:$mailRows.map(r => ({key:${keyProjection(
                                    "r",
                                    keys,
                                )},status:'delivered',at:new Date().toISOString()}))}",
                            )
                        flow.link(mail, outcome)
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
                        val asking = action.sinks["native"] == "task" && action.approval == "required"
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
                                            if (asking) {
                                                ",callbackUrl:${dollar}execution.resumeUrl + '/$suffix'"
                                            } else {
                                                ""
                                            }
                                        ) +
                                        "}",
                            )
                        flow.link(tail, perform)
                        tail = perform
                        if (asking) {
                            val taskAction = literal(wiring.action)
                            val fallbackDue =
                                action.due?.takeUnless { "working_days" in it }?.let(::durationSeconds) ?: 3600
                            val wait =
                                flow.node(
                                    "$actionName wait",
                                    "wait",
                                    json(
                                        "resume" to str("webhook"),
                                        "httpMethod" to str("POST"),
                                        "limitWaitTime" to JsonPrimitive(true),
                                        "limitType" to str("afterTimeInterval"),
                                        "resumeAmount" to
                                            str(
                                                expression(
                                                    "(() => {const due=(${dollar}json.body.tasks || []).filter(t => t.action === $taskAction && t.dueAt).map(t => Date.parse(t.dueAt)).filter(Number.isFinite); return due.length ? Math.max(1, Math.ceil((Math.min(...due)-Date.now())/1000)) : $fallbackDue;})()",
                                                ),
                                            ),
                                        "resumeUnit" to str("seconds"),
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

                            flow.link(pending, resumed, 1)
                            val taskStatus = http(graph, "$actionName task status", "status", "GET", id)
                            val stillWaiting =
                                flow.condition(
                                    "$actionName still waiting",
                                    "${dollar}json.body.status === 'waiting'",
                                )
                            flow.link(pending, taskStatus)
                            flow.link(wait, taskStatus)
                            flow.link(taskStatus, stillWaiting)
                            flow.link(stillWaiting, wait)
                            flow.link(stillWaiting, resumed, 1)
                            tail = resumed
                        }
                    }
                }
            flow.link(tail, loop)
        }
        var done = flow.code(prefix + "routes complete", "return [{json:{}}];")
        flow.link(loop, done)
        var doneOutput = 0
        if (digest) {
            val digests = http(graph, prefix + "digests", "digests", "GET", id)
            flow.link(done, digests, doneOutput)
            val sources =
                digestSources.joinToString(",")
            val groups =
                flow.code(
                    prefix + "digest groups",
                    "const rows=[$sources]; const groups = ${dollar}input.first().json.body.groups.map(g => ({...g,rowsRef:g.rows,rows:rows.filter(r => Object.entries(g.by).every(([k,v]) => r[k] === v))})); if(groups.some(g=>!g.rows.length)) throw new Error('digest group has no resolved rows'); const addressed=groups.flatMap(g=>[...new Set(g.rows.map(r=>r.to))].map(to=>({...g,to,rows:g.rows.filter(r=>r.to===to)}))); return [{json:{groups:addressed.length ? addressed : [{empty:true,rows:[]}]}}];",
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
            val nonempty =
                flow.condition(
                    prefix + "digest deliver",
                    "${root.deployment.dryRun} === false && $dollar(${literal(
                        prefix + "action status",
                    )}).first().json.body.dryRun === false && ${dollar}json.group.rows.length > 0",
                )
            flow.link(split, nonempty)
            val digestAction =
                graph.wirings
                    .filter {
                        graph.actions.getValue(it.action).deliverable
                    }.map { it.action }
                    .distinct()
                    .single()
            val mail =
                email(
                    graph,
                    prefix + "digest mail",
                    "$dollar(${literal(prefix + "digest split")}).item.json.group.rows",
                    "$dollar(${literal(
                        prefix + "digest split",
                    )}).item.json.group.to",
                    digestAction,
                    keys,
                )
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
        val closeInput = flow.code(prefix + "close input", "return [{json:{}}];")
        flow.link(done, closeInput, doneOutput)
        val close = http(graph, prefix + "close", "close", "POST", id)
        flow.link(closeInput, close)
        flow.link(close, complete)
        return (clock ?: prepared) to complete
    }

    private fun retryAfter(): String =
        "(() => {const h=${dollar}json.headers || {}; const v=h['retry-after'] ?? h['Retry-After']; const n=Number(v); return Number.isFinite(n) && n > 0 ? n : (v && Number.isFinite(Date.parse(v)) ? Math.max(1,Math.ceil((Date.parse(v)-Date.now())/1000)) : 15);})()"

    private fun pollBudget(graph: RecipeGraph): Long =
        (
            graph.program.budget[if (graph.program.mode == "batch") "batch" else "sync"]?.let(::durationSeconds)
                ?: if (graph.program.mode == "batch") 1800 else 60
        ) * 1000L + 120_000L

    private fun durationSeconds(raw: String): Long {
        val m =
            Regex("([0-9.]+)\\s+(milliseconds|seconds|minutes|hours|days)").matchEntire(raw)
                ?: error("unsupported duration $raw")
        val scale =
            mapOf(
                "milliseconds" to 0.001,
                "seconds" to 1.0,
                "minutes" to 60.0,
                "hours" to 3600.0,
                "days" to 86400.0,
            ).getValue(m.groupValues[2])
        return kotlin.math
            .ceil(m.groupValues[1].toDouble() * scale)
            .toLong()
            .coerceAtLeast(1)
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

    private data class MailAddress(
        val role: String?,
        val column: String?,
    ) {
        fun recipient(
            row: String,
            keys: List<String>,
        ): String {
            if (role != null) {
                val roleName = literal(role)
                return "(() => {const role=$roleName; " +
                    "const to=JSON.parse(${'$'}env.TTR_MAIL_RECIPIENTS || '{}')[role]; " +
                    "if(typeof to !== 'string' || !to.trim()) throw new Error('no address for role ' + role); " +
                    "return to;})()"
            }
            val columnName = literal(requireNotNull(column))
            val key = keys.joinToString(",") { "$row[${literal(it)}]" }
            return "(() => {const to=$row[$columnName]; " +
                "if(typeof to !== 'string' || !to.trim()) " +
                "throw new Error('no address for key ' + JSON.stringify([$key])); return to;})()"
        }
    }

    private fun mailAddress(
        action: ActionMeta,
        wiring: WiringMeta,
    ): MailAddress {
        val columns = action.columns.filter { (name, type) -> type == "role" || (name == "to" && '.' in type) }
        require(columns.size == 1) {
            "emailSend action ${wiring.action} needs exactly one addressing column (role or to: E-R address attribute)"
        }
        val (name, type) = columns.entries.single()
        val value = requireNotNull(wiring.columns[name]) { "emailSend action ${wiring.action} has no address wiring" }
        return if (type == "role") {
            require(
                value.matches(Regex("role\\.[A-Za-z_][A-Za-z0-9_]*")),
            ) { "emailSend role must be a role reference: $value" }
            MailAddress(value, null)
        } else {
            require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                "emailSend address must name a bare route-row column: $value"
            }
            MailAddress(null, value)
        }
    }

    private fun email(
        graph: RecipeGraph,
        name: String,
        rows: String,
        recipient: String,
        action: String,
        keys: List<String>,
    ): String {
        flow.mail +=
            json(
                "node" to str(name),
                "action" to str(action),
                "rows" to str(expression(rows)),
                "recipient" to str(expression(recipient)),
                "key" to arr(keys.map(::str)),
                "addressColumn" to
                    str(
                        graph.actions
                            .getValue(action)
                            .columns.entries
                            .single { it.value == "role" || it.key == "to" }
                            .key,
                    ),
                "addressKind" to
                    str(
                        if (graph.actions
                                .getValue(action)
                                .columns.values
                                .any { it == "role" }
                        ) {
                            "role"
                        } else {
                            "row"
                        },
                    ),
            )
        return flow.node(
            name,
            "emailSend",
            json(
                "fromEmail" to str(expression("${dollar}env.TTR_MAIL_FROM")),
                "toEmail" to str(expression(recipient)),
                "subject" to str("Tatrman ${graph.program.name}"),
                "emailFormat" to str("text"),
                "text" to str(expression("JSON.stringify($rows, null, 2)")),
                "options" to json("appendAttribution" to JsonPrimitive(false)),
            ),
        )
    }

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
        or the declared event body. On-event callers must send `at` as an ISO instant with an offset for duplicate events
        to be recognised. Without `at`, every POST is a new run. The clock is excluded from event columns; an event
        schema declaring `at` is refused. Batch runs use the n8n execution id; later batch steps append their step name.
        A 202 Open handle belongs to another execution: wait for closed/abandoned, and never repeat its actions.
        A waiting handle ends the duplicate execution. After failure/abandonment there is one re-open with the same
        saved body; a second 202 refuses. A lost Open response can wait until the Server's budget plus recovery grace
        abandons the orphan, then recover through that single re-open. Later step clocks are saved after waits.
        Task actions with required approval register a Wait webhook as `callbackUrl` and re-read status after every
        resume or timeout until all tasks have left waiting. Task dueAt bounds the timeout; calendar-based due rules
        without a resolved dueAt use an hourly backstop.
        Obligation waits require an explicit `obligations` array on Server status. Missing fields fail; an empty
        array is absent. `until` selects fulfilled/due readiness. Waits wake at the nearest future dueAt, capped at 1 h.
        `emailSend` needs exactly one role-typed action column or `to` typed by an E-R address attribute and wired to
        a bare route-row column. Row addresses are grouped per recipient; missing addresses name the key and fail.
        Role addresses must exist in `TTR_MAIL_RECIPIENTS`; there is no literal fallback. Status must include the
        per-key `actions` selection array for every run; missing is an error. Both deployment and status must
        explicitly permit wet delivery. Process manifests fingerprint every step; any step can make the export stale.
        Schedule hints: `HH:MM Zone`, `Mon|Tue|Wed|Thu|Fri|Sat|Sun HH:MM Zone`, `1st HH:MM Zone`, or `*/N`
        (1–60 minutes). Unsupported hints refuse export.
        Route rows feed engine-side mail; outcomes are recorded on the Server. HTTP failures, including stale
        fingerprint refusals, stop the workflow before downstream nodes. Re-export after any stale refusal.

        Node parameter references: https://github.com/n8n-io/n8n/tree/master/packages/nodes-base/nodes
        The exact node `typeVersion`s are pinned in the plugin manifest and export metadata.

        ${request.readme.lines().lastOrNull { it.startsWith("Stale when") } ?: request.readme}
        """.trimIndent() + "\n"
}
