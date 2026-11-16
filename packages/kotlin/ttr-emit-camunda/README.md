# Camunda recipe exporter

`process_engine` renders Camunda 7 BPMN and element templates whose HTTP connectors call the executor's
`egress_url`. Customer `camunda_7` and `camunda_8` exports keep their own connector bindings.

Every process-engine Open includes an `engine` object:

| Field | Camunda source |
| --- | --- |
| `instanceId` | `execution.processInstanceId` |
| `businessKey` | `execution.processBusinessKey` |
| `deploymentId` | `execution.processEngineServices.repositoryService.getProcessDefinition(execution.processDefinitionId).deploymentId` |

`deploymentId` is the Camunda deployment of the running definition. The Open's top-level `deployment`
still names the Server deployment. Start the process with a nonblank business key; the Server requires
all three engine identifiers. Open retries reuse the saved body. The C7 element template's Hidden
connector payload uses the same engine fields and preserves `ttrInput.at` on keyed ports. A batch port
remains `{none: true}`. Query payloads and customer C7/C8 exports carry no engine object.

An approval user task's completion listener captures `task.id` and `task.assignee` in `ttrTaskId` and
`ttrAnsweredBy`. The answer Open between that task and its completion gateway adds those values as
`engine.taskId` and `engine.answeredBy`, with `idempotencyKey = ttrAnchor + ':answer'`. Its subprocess
saves the body and clock before the HTTP task's asynchronous boundary, so retries reuse the same Open.
The Server accepts an asserted answer only from its configured engine client and verifies the task and
assignee against engine history. Customer exports and template program Opens assert no answer identity.

C7 HTTP output scripts resolve `BpmnError` from the execution's engine package (`org.camunda` or
`org.cibseven`). Both engines retain the `TTR_STALE` and `TTR_REFUSED` codes and their drawn boundaries;
transient HTTP failures remain retryable exceptions.

Run the exporter suite on bp-dsk:

```sh
./gradlew :packages:kotlin:ttr-emit-camunda:test
```

`EngineOpenSpec` evaluates generated Open expressions with Camunda JUEL/Spin; `ProcessEngineRenderSpec`
executes Hidden payload scripts with Groovy. These checks do not certify a live process-engine run.
