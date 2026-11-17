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

The engine target asks this way for every `approval: required` action the Server performs (a door or
writer sink, e.g. `native: transition` with `process_engine: door` in grammar 0.22); it never performs
such an action directly. The Server decides
the approved key's held group when it accepts the answer Open.

The answer step then finishes the answered run, without asking again:

| Step | Call | Variable |
| --- | --- | --- |
| Open (in the completion's transaction) | `POST /recipes/{recipe}/runs` | `ttrAnswerOpen` |
| Evaluate (asynchronous) | `POST /runs/{id}/evaluate` | `ttrAnswerEvaluation` |
| Status, until the verdict is ready (Retry-After, budget + 2 min) | `GET /runs/{id}` | `ttrAnswerEvaluation` |
| Close (asynchronous) | `POST /runs/{id}/close` | `ttrAnswerClosed` |

`{id}` is `ttrAnswerOpen.runId`. Evaluate and close carry no `engine` object. The step reads no route
and performs no action: the Server already applied the approved row. Every `ttrAnswer*` and `ttrHandle`
variable (with its `Code`, `Headers` and `RetryAfter`) is declared on the answer step, so none reaches
the process instance.

| Answer Open or close | Result |
| --- | --- |
| 201 | Evaluate, read status, close |
| 200 `replayed` | Close the existing run without evaluating |
| 202 | Poll the other execution's status; `closed` completes, anything else refuses |
| Close answers other than `closed` (the key held again) | `run refused; inspect evidence` |
| 409 (Open, evaluate, status or close) | `TTR_STALE` → `export outdated` |
| 403 `unverified-answer` and other 4xx except 408/429 | `TTR_REFUSED` → `run refused; inspect evidence` |
| 5xx, 408, 429 on the Open | The completion rolls back; the user completes again |
| 5xx, 408, 429 after the Open | The failed job retries (R3/PT15S), then an incident |

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
