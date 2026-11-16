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
server.createContext('/automation') { exchange ->
    Thread.currentThread().contextClassLoader = engineLoader
    try {
        def body = exchange.requestBody.getText('UTF-8')
        requests.add([path: exchange.requestURI.toString(), body: body ? new JsonSlurper().parseText(body) : null])
        def bytes = JsonOutput.toJson(responseBody).getBytes('UTF-8')
        exchange.responseHeaders.set('Content-Type', 'application/json')
        exchange.sendResponseHeaders(responseCode, bytes.length)
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
        // The engine instantiates the actual generated row scope and user task; it supplies all script bindings.
        def instance = runtime.startProcessInstanceById(process.id, 'order-4711', variables + [ttrAnchor: 'run-1', ttrRunId: 'run-1', ttrOpen: spin.JSON('{"runId":"run-1"}'),
                ttrRow: spin.JSON('{"order_number":4711}'), ttrStatus: spin.JSON('{"dryRun":false,"actions":[]}')])
        runtime.createProcessInstanceModification(instance.id)
            .cancelAllForActivity(activity('startEvent', 'start with ttrInput key'))
            .startBeforeActivity(activity('userTask', 'approve approve_release')).execute()
        def task = tasks.createTaskQuery().processInstanceId(instance.id).singleResult()
        requests.clear()
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
        // Synchronous transient failures roll back completion; there is no answer job to retry it.
        responseCode = 503
        def transientFailure = null
        try { tasks.complete(task.id) } catch (Exception e) { transientFailure = e }
        assert transientFailure != null
        assert tasks.createTaskQuery().taskId(task.id).count() == 1
        assert requests.size() == 1: requests
        assert !runtime.getVariablesLocal(instance.id).containsKey('ttrTaskId')
        assert !runtime.getVariablesLocal(instance.id).containsKey('ttrAnsweredBy')
        requests.clear()
        responseCode = 200
        tasks.complete(task.id)
        assert requests.size() == 1: requests
        assert requests[0].body.engine.taskId == task.id
        assert requests[0].body.engine.answeredBy == 'alice'
        assert requests[0].body.port.key == 4711
        assert requests[0].body.idempotencyKey == 'run-1:answer:' + task.id
        assert !runtime.getVariablesLocal(instance.id).containsKey('ttrTaskId')
        assert !runtime.getVariablesLocal(instance.id).containsKey('ttrAnsweredBy')
        runtime.deleteProcessInstance(instance.id, 'test complete')
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
