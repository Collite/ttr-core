// SPDX-License-Identifier: Apache-2.0
// Loaded in a platform-parent classloader with exactly one engine distribution, never the test JVM's Spin.
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import javax.xml.parsers.DocumentBuilderFactory

assert Class.forName(enginePackage + '.spin.Spin')
def other = enginePackage == 'org.camunda' ? 'org.cibseven' : 'org.camunda'
try {
    Class.forName(other + '.spin.Spin')
    assert false: 'the other distribution leaked into this engine'
} catch (ClassNotFoundException expected) { }

def configuration = Class.forName(enginePackage + '.bpm.engine.ProcessEngineConfiguration')
    .createStandaloneInMemProcessEngineConfiguration()
configuration.setProcessEngineName('export-' + UUID.randomUUID())
configuration.setJdbcUrl('jdbc:h2:mem:' + UUID.randomUUID() + ';DB_CLOSE_DELAY=-1')
configuration.setDatabaseSchemaUpdate('create-drop')
configuration.setHistory('full')
configuration.setJobExecutorActivate(false)
configuration.setProcessEnginePlugins([
    Class.forName(enginePackage + '.connect.plugin.impl.ConnectProcessEnginePlugin').getConstructor().newInstance(),
    Class.forName(enginePackage + '.spin.plugin.impl.SpinProcessEnginePlugin').getConstructor().newInstance()
])
def engine = configuration.buildProcessEngine()
def engineLoader = Thread.currentThread().contextClassLoader
def server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
def requests = Collections.synchronizedList([])
def stubFailures = Collections.synchronizedList([])
def responseCode = 200
def responseBody = [runId: 'run-1', replayed: true]
// One fixed answer by default; a scenario may model the Server instead ([code, body] from method, path and body).
def respond = { String method, String path, Object body -> [responseCode, responseBody] }
server.createContext('/automation') { exchange ->
    Thread.currentThread().contextClassLoader = engineLoader
    try {
        def text = exchange.requestBody.getText('UTF-8')
        def path = exchange.requestURI.toString()
        def body = text ? new JsonSlurper().parseText(text) : null
        requests.add([path: path, body: body])
        def (code, reply) = respond(exchange.requestMethod, path, body)
        def bytes = JsonOutput.toJson(reply).getBytes('UTF-8')
        exchange.responseHeaders.set('Content-Type', 'application/json')
        exchange.sendResponseHeaders(code, bytes.length)
        exchange.responseBody.withCloseable { it.write(bytes) }
    } catch (Throwable failure) {
        stubFailures.add(failure)
        failure.printStackTrace()
        exchange.close()
    }
}
server.start()
def endpoint = 'http://127.0.0.1:' + server.address.port + '/automation'
def runtime = engine.runtimeService
def tasks = engine.taskService
def management = engine.managementService
def repository = engine.repositoryService
def spin = Class.forName(enginePackage + '.spin.Spin')
def variables = [ttrInput: [key: 4711, at: '2026-11-16T10:00:00Z'], TTR_CONN_AUTOMATION: [url: endpoint, token: 'test']]
def xml = bpmn.replace('http://127.0.0.1:1/automation', endpoint)
def doc = DocumentBuilderFactory.newInstance().with { namespaceAware = true; newDocumentBuilder() }.parse(new ByteArrayInputStream(xml.bytes))
def nodes = { name ->
    def found = doc.getElementsByTagNameNS('http://www.omg.org/spec/BPMN/20100524/MODEL', name)
    (0..<found.length).collect { found.item(it) }
}
def activity = { type, name -> nodes(type).find { it.getAttribute('name') == name }.getAttribute('id') }
def deploy = { text -> repository.createDeployment().addString('export.bpmn', text).deploy() }
def definition = { deployment -> repository.createProcessDefinitionQuery().deploymentId(deployment.id).singleResult() }
def drainJobs = { instance ->
    int count = 0
    while (true) {
        def jobs = management.createJobQuery().processInstanceId(instance.id).executable().list().findAll { !it.duedate || !it.duedate.after(new Date()) }
        if (jobs.empty) break
        assert ++count < 50: 'unexpected job loop'
        jobs.each { management.executeJob(it.id) }
    }
}
try {
    if (scenario == 'http') {
        def deployment = deploy(xml)
        def process = definition(deployment)
        for (code in [200, 409, 422, 503]) {
            requests.clear()
            responseCode = code
            def instance = runtime.startProcessInstanceById(process.id, 'order-4711', variables)
            if (code == 503) {
                def failure = null
                try { drainJobs(instance) } catch (Exception e) { failure = e }
                assert failure != null
                assert (failure.toString() + failure.cause).contains('run API HTTP 503'): failure
                assert management.createJobQuery().processInstanceId(instance.id).count() > 0
                assert tasks.createTaskQuery().processInstanceId(instance.id).count() == 0
            } else {
                drainJobs(instance)
                def names = tasks.createTaskQuery().processInstanceId(instance.id).list()*.name
                assert names == (code == 409 ? ['export outdated'] : code == 422 ? ['run refused; inspect evidence'] : []): names
                if (code == 200) assert runtime.createProcessInstanceQuery().processInstanceId(instance.id).count() == 0
                if (code == 409 || code == 422) {
                    assert runtime.getVariable(instance.id, 'ttrAnchor') == null
                    assert runtime.createIncidentQuery().processInstanceId(instance.id).count() == 0
                }
            }
            assert requests.size() == 1: requests
            def identity = requests[0].body.engine
            assert identity == [instanceId: instance.id, businessKey: 'order-4711', deploymentId: deployment.id]: identity
            if (runtime.createProcessInstanceQuery().processInstanceId(instance.id).count()) runtime.deleteProcessInstance(instance.id, 'test complete')
        }
    } else if (scenario == 'approval') {
        def deployment = deploy(xml)
        def process = definition(deployment)
        def history = engine.historyService
        def openPath = '/automation/recipes/release_order/runs'
        // The Server as S3.5 has it: a run is `waiting` only once its close held rows, and an answer Open matches only a
        // waiting run of the same engine instance; otherwise 409 answer-unmatched {no-waiting-run}.
        def runs = [:]
        def closeStatus = 'waiting'
        def answers = []
        def counts = [hold_sales: 0, hold_ar: 0, release_confirmed: 1, release: 0]
        respond = { String method, String path, Object body ->
            if (path == openPath && body?.engine?.taskId) {
                def waiting = runs.values().any { it.instance == body.engine.instanceId && it.status == 'waiting' }
                if (!waiting) return [409, [error: 'answer-unmatched', reason: 'no-waiting-run']]
                return answers.size() > 1 ? answers.remove(0) : answers[0]
            }
            if (path == openPath) {
                def id = 'run-' + (runs.size() + 1)
                runs[id] = [instance: body.engine.instanceId, status: 'open']
                return [201, [runId: id, status: 'open']]
            }
            def matcher = path =~ '^/automation/runs/([^/?]+)(.*)$'
            assert matcher.matches(): path
            def run = runs[matcher.group(1)]
            assert run: path
            def call = matcher.group(2)
            if (call == '/close') {
                assert method == 'POST'
                run.status = closeStatus
                return [200, [status: closeStatus, batch: [id: 'batch-1', state: closeStatus == 'waiting' ? 'held' : 'committed', rows: 1],
                              protocolRef: '/automation/runs/' + matcher.group(1) + '/protocol']]
            }
            if (call == '') {
                return [200, [status: 'evaluated', dryRun: false, verdict: [routes: counts.collectEntries { k, v -> [k, [rowCount: v]] }],
                              actions: [[action: 'approve_release', key: [order_number: 4711]]]]]
            }
            if (call.startsWith('/routes/release_confirmed?')) return [200, [rows: [[order_number: 4711]], rowCount: 1]]
            assert call.startsWith('/parts/') || call == '/evaluate': path
            [200, [ok: true]]
        }
        def label = { request ->
            def call = request.path == openPath ? (request.body?.engine?.taskId ? 'answer' : 'open') : request.path.replaceFirst('^/automation/runs/[^/?]+', '')
            call == '' ? 'status' : call.replaceFirst('\\?.*$', '')
        }
        // The engine runs the generated BPMN from its start: Open, parts, evaluation, routes, close, then the approval.
        def startRun = {
            runs.clear()
            requests.clear()
            def instance = runtime.startProcessInstanceById(process.id, 'order-4711', variables)
            drainJobs(instance)
            def order = requests.collect(label)
            // The contexts fork runs in parallel: its three parts may come in any order.
            def contexts = ['/parts/shortfall', '/parts/price_differences', '/parts/overdue']
            assert order.take(2) == ['open', '/parts/candidates']: order
            assert order.subList(2, 5).sort() == contexts.sort(): order
            assert order.drop(5) == ['/parts/decision', '/evaluate', 'status', 'status', '/routes/release_confirmed', '/close']: order
            requests.clear()
            instance
        }
        def approvalTask = {
            def instance = startRun()
            def task = tasks.createTaskQuery().processInstanceId(instance.id).singleResult()
            assert task?.name == 'approve approve_release': task
            [instance, task]
        }
        // The answer Open as the Server answers it (D-233): decided and closed at Open; the row makes no other call.
        def decided = { boolean replayed ->
            [runId: 'run-a', status: 'closed', replayed: replayed,
             answer: [original: 'run-1', key: [order_number: 4711], decision: 'commit', applied: 1, skipped: 0, unknown: 0, replayed: replayed]]
        }
        def rootVariables = { instance ->
            history.createHistoricVariableInstanceQuery().processInstanceId(instance.id).list()
                .findAll { it.activityInstanceId == instance.id }*.name
        }
        def noAnswerOnRoot = { instance ->
            def leaked = rootVariables(instance).findAll { it.startsWith('ttrAnswer') || it in ['ttrTaskId', 'ttrAnsweredBy'] }
            assert leaked.empty: leaked
        }
        def answered = { instance, name ->
            def values = history.createHistoricVariableInstanceQuery().processInstanceId(instance.id).variableName(name).list()
            assert values.every { it.activityInstanceId != instance.id }: values
            values*.value
        }
        def onlyTheAnswer = {
            assert requests.collect(label) == ['answer']: requests*.path
        }
        def ended = { instance -> runtime.createProcessInstanceQuery().processInstanceId(instance.id).count() == 0 }

        // Nothing held (a close that answers `closed`): nobody is asked, and the instance ends.
        closeStatus = 'closed'
        def unheld = startRun()
        assert tasks.createTaskQuery().processInstanceId(unheld.id).count() == 0
        assert ended(unheld)
        assert runtime.createIncidentQuery().processInstanceId(unheld.id).count() == 0
        closeStatus = 'waiting'

        def (instance, task) = approvalTask()
        for (assignee in [null, '   ']) {
            tasks.setAssignee(task.id, assignee)
            def failure = null
            try { tasks.complete(task.id) } catch (Exception e) { failure = e }
            assert failure != null: 'unclaimed completion was accepted'
            def causes = []; for (def cause = failure; cause != null; cause = cause.cause) causes.add(cause.toString())
            assert causes.join('\n').contains('approval task must be claimed before it is completed'): causes
            assert tasks.createTaskQuery().taskId(task.id).count() == 1
            assert requests.empty: requests
        }
        tasks.setAssignee(task.id, null)
        tasks.claim(task.id, 'alice')
        // (f) Synchronous transient failures roll back completion; there is no answer job to retry it.
        answers = [[503, [error: 'unavailable']]]
        def transientFailure = null
        try { tasks.complete(task.id) } catch (Exception e) { transientFailure = e }
        assert transientFailure != null
        assert tasks.createTaskQuery().taskId(task.id).count() == 1
        onlyTheAnswer()
        assert !runtime.getVariablesLocal(instance.id).containsKey('ttrTaskId')
        assert !runtime.getVariablesLocal(instance.id).containsKey('ttrAnsweredBy')
        requests.clear()

        // (a) The answer Open decides: one call after the close, the row ends decided, nothing else is requested.
        answers = [[201, decided(false)]]
        tasks.complete(task.id)
        onlyTheAnswer()
        def open = requests[0].body
        assert open.engine == [instanceId: instance.id, businessKey: 'order-4711', deploymentId: deployment.id, taskId: task.id, answeredBy: 'alice']: open.engine
        assert open.port.key == [order_number: 4711]: open.port
        assert open.idempotencyKey == 'run-1:answer:' + task.id
        assert tasks.createTaskQuery().processInstanceId(instance.id).count() == 0
        assert history.createHistoricActivityInstanceQuery().processInstanceId(instance.id)
            .activityId(activity('exclusiveGateway', 'approve_release complete')).finished().count() == 1
        assert answered(instance, 'ttrAnswerDecision') == ['commit']
        assert answered(instance, 'ttrAnswerRunId') == ['run-a']
        assert answered(instance, 'ttrAnswerStatus') == ['closed']
        assert runtime.createIncidentQuery().processInstanceId(instance.id).count() == 0
        assert ended(instance)
        noAnswerOnRoot(instance)

        // (b) An idempotent replay (200, replayed) is the same decision.
        (instance, task) = approvalTask()
        tasks.claim(task.id, 'alice')
        answers = [[200, decided(true)]]
        tasks.complete(task.id)
        onlyTheAnswer()
        assert tasks.createTaskQuery().processInstanceId(instance.id).count() == 0
        assert answered(instance, 'ttrAnswerDecision') == ['commit']
        assert ended(instance)
        noAnswerOnRoot(instance)

        // (h) R3: 202, the same key still in flight, rolls the completion back like a 5xx (never refused); the next
        // completion of the same task is the same idempotency key and gets the decision as a 200 replay.
        (instance, task) = approvalTask()
        tasks.claim(task.id, 'alice')
        answers = [[202, [runId: 'run-a', status: 'open']], [200, decided(true)]]
        def inFlight = null
        try { tasks.complete(task.id) } catch (Exception e) { inFlight = e }
        assert inFlight != null: 'a 202 answer was accepted'
        assert tasks.createTaskQuery().taskId(task.id).count() == 1
        assert runtime.getVariable(instance.id, 'ttrRefusedReason') == null
        onlyTheAnswer()
        def firstKey = requests[0].body.idempotencyKey
        requests.clear()
        tasks.complete(task.id)
        onlyTheAnswer()
        assert requests[0].body.idempotencyKey == firstKey
        assert answered(instance, 'ttrAnswerDecision') == ['commit']
        assert ended(instance)
        assert runtime.createIncidentQuery().processInstanceId(instance.id).count() == 0

        // (c) A Server that holds the key again (201 waiting, no decision) refuses; never counted as decided.
        // (d)/(e) 409 answer-unmatched or *-stale and (g) 403 identity refuse with the Server's reason.
        def refusals = [
            [201, [runId: 'run-a', status: 'waiting'], null],
            [409, [error: 'answer-unmatched', reason: 'no-held-key'], 'answer-unmatched'],
            [409, [error: 'deployment-stale', differs: ['deployment']], 'deployment-stale'],
            [403, [error: 'identity', reason: 'unverified-answer'], 'unverified-answer'],
        ]
        for (refusal in refusals) {
            (instance, task) = approvalTask()
            tasks.claim(task.id, 'alice')
            answers = [[refusal[0], refusal[1]]]
            tasks.complete(task.id)
            drainJobs(instance)
            onlyTheAnswer()
            assert tasks.createTaskQuery().taskId(task.id).count() == 0
            def names = tasks.createTaskQuery().processInstanceId(instance.id).list()*.name
            assert names == ['run refused; inspect evidence']: [refusal, names]
            assert runtime.createIncidentQuery().processInstanceId(instance.id).count() == 0
            def reason = runtime.getVariable(instance.id, 'ttrRefusedReason')
            if (refusal[2]) assert reason?.contains(refusal[2]): [refusal, reason]
            else assert reason == null: [refusal, reason]
            assert !answered(instance, 'ttrAnswerDecision').contains('commit')
            noAnswerOnRoot(instance)
            runtime.deleteProcessInstance(instance.id, 'test complete')
        }
    } else {
        templates.eachWithIndex { text, index ->
            requests.clear()
            responseBody = [ok: true]
            def deployment = deploy(text.replace('http://127.0.0.1:1/automation', endpoint))
            def instance = runtime.startProcessInstanceById(definition(deployment).id, 'order-4711', variables + [limit: 3])
            assert requests.size() == 1
            def body = requests[0].body
            if (index == 0) {
                assert body.engine == [instanceId: instance.id, businessKey: 'order-4711', deploymentId: deployment.id]
                assert body.port == [key: 4711, at: '2026-11-16T10:00:00Z']
            } else {
                assert body == [query: 'er.query.order_totals', parameters: [limit: 3]]
            }
            def execution = runtime.createExecutionQuery().processInstanceId(instance.id).activityId(tasks.createTaskQuery().processInstanceId(instance.id).singleResult().taskDefinitionKey).singleResult()
            assert runtime.getVariable(execution.id, 'ttr_response').prop('ok').boolValue()
            runtime.deleteProcessInstance(instance.id, 'test complete')
        }
    }
} finally {
    server.stop(0)
    engine.close()
    assert stubFailures.empty: stubFailures
}
