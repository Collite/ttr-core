# Camunda recipe exporter

`process_engine` renders Camunda 7 BPMN and element templates whose HTTP connectors call the executor's
`egress_url`. Customer `camunda_7` and `camunda_8` exports keep their own connector bindings.

Every process-engine Open includes an `engine` object:

| Field | Camunda source |
| --- | --- |
| `instanceId` | Running process instance ID |
| `businessKey` | Process business key |
| `deploymentId` | Running definition's deployment ID, read through the repository service |

The Open's top-level `deployment` names the Server deployment. Start the process with a nonblank business
key; the Server requires all three engine identifiers. Asynchronous Open retries reuse the saved body.
The C7 element template's Hidden payload uses the same engine fields and preserves `ttrInput.at` on keyed
ports. A batch port remains `{none: true}`. Query payloads and customer exports carry no engine object.

An approval task must be claimed before completion. Its completion listener captures `task.id` and
`task.assignee` in row-local `ttrTaskId` and `ttrAnsweredBy`. The answer Open adds those values as
`engine.taskId` and `engine.answeredBy`, with `idempotencyKey = ttrAnchor + ':answer:' + ttrTaskId`
and `port.key` from the approved row. Its subprocess saves the body and clock; the HTTP task has no
`asyncBefore`, so it runs in the transaction that completes the user task. A transient failure of the
answer Open rolls back the completion; the user completes again.
The Server accepts an asserted answer only from its configured engine client and verifies the task and
assignee against engine history. Customer exports and template program Opens assert no answer identity.

This version opens the answer run; it does not yet evaluate or close it.

C7 HTTP scripts resolve `BpmnError` and Spin for Camunda 7 and CIB seven without relying on an execution
binding in connector output scripts. Both engines retain `TTR_STALE` and `TTR_REFUSED` boundary codes;
transient HTTP failures remain exceptions, retried by failed jobs only on asynchronous tasks.

Run the exporter suite:

```sh
./gradlew :packages:kotlin:ttr-emit-camunda:test
```

`EmbeddedEngineSpec` deploys generated BPMN and applies Hidden template scripts on isolated in-memory
Camunda 7.24 and CIB seven 2.2.0 engines with H2 and a local HTTP stub. It verifies response/error handling,
unclaimed completion, claimed answer identity and row-local variables. It does not certify a deployed
process-engine service or the answer run's evaluation and close.
