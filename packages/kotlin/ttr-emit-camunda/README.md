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

The Server's optional answer fields, `taskId` and `answeredBy`, identify the completed user task and
its assignee. The Server accepts an asserted answer only from its configured engine client and verifies
it against task history. The current one-program rendering has no answer Open; adding those fields
requires an explicit answer path with task context.

Run the exporter suite on bp-dsk:

```sh
./gradlew :packages:kotlin:ttr-emit-camunda:test
```

`EngineOpenSpec` evaluates generated Open expressions with Camunda JUEL/Spin; `ProcessEngineRenderSpec`
executes Hidden payload scripts with Groovy. These checks do not certify a live process-engine run.
