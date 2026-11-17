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

The engine target asks this way for an `approval: required` action whose rows the Server holds: one whose
native sink is `transition`, `writer` or `obligation` (e.g. `native: transition` with `process_engine: door`
in grammar 0.22). It never performs such an action directly. An approval action with another native sink,
or none, has nothing held, so nobody is asked for it; it is performed as before.

The Server holds those rows when the run closes, and the run then waits for the answers. So the engine asks
after the close, never inside the routes:

| Close response `status` | Result |
| --- | --- |
| `closed` (nothing held: a dry run, or no such action selected) | The evaluation ends; nobody is asked |
| `waiting` | For each route that wires such an action, each row whose key has the action selected gets the user task and its answer Open |
| Anything else | `run refused; inspect evidence` |

The answer Open is the row's one call to the Server, which decides the approved key and closes the
answer run at that Open. Its `port.key` is the row's key as a column-named object (`{order_number: 4711}`),
the shape the Server matches. The step's outputs set row-local `ttrAnswerRunId` (`runId`), `ttrAnswerStatus`
(`status`) and `ttrAnswerDecision` (`answer.decision`, null when absent). The row reads no route again, so
the approval is never asked twice.

| Answer Open response | Result |
| --- | --- |
| 201, or 200 `replayed`, with `status: closed` and `answer.decision: commit` | Decided; the row continues |
| Any other 2xx (e.g. `status: waiting`, no decision) | `run refused; inspect evidence` |
| 409 (`answer-unmatched`, `*-stale`) and other 4xx except 408/429 (e.g. 403 `unverified-answer`) | `TTR_REFUSED` → `run refused; inspect evidence` |
| 202 (the same idempotency key is still in flight) | The completion rolls back; completing again gets the decision as `200 replayed` |
| 5xx, 408, 429 | The completion rolls back; the user completes again |

A non-2xx refusal keeps the Server's status and body in `ttrRefusedReason`. A 2xx answer that does not
commit (e.g. `status: waiting`) leaves `ttrRefusedReason` null; its `status` is in the history, as the
row-local `ttrAnswerStatus`. A refused answer is never counted as decided.

C7 HTTP scripts resolve `BpmnError` and Spin for Camunda 7 and CIB seven without relying on an execution
binding in connector output scripts. Both engines retain `TTR_STALE` and `TTR_REFUSED` boundary codes;
transient HTTP failures remain exceptions, retried by failed jobs only on asynchronous tasks.

Run the exporter suite:

```sh
./gradlew :packages:kotlin:ttr-emit-camunda:test
```

`EmbeddedEngineSpec` deploys generated BPMN and applies Hidden template scripts on isolated in-memory
Camunda 7.24 and CIB seven 2.2.0 engines with H2 and a local HTTP stub. It verifies response/error handling,
unclaimed completion, claimed answer identity and row-local variables. The approval scenario runs the whole
process against a stub that keeps each run's state: an answer Open matches only a run that its close left
`waiting`. It does not certify a deployed process-engine service.
