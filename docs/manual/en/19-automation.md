# Automation

[Recipes](18-recipes.md) decide; the **automation model** says what a decision may *do*, who may run it, and where it
runs. It is TTR-M, reviewed like the rest of the model: a parameter value, an action or a grant changes by a pull request.

Two places hold it:

| where | what |
|---|---|
| a `model automation` file (e.g. `hartland/automation/automation.ttrm`) | `def parameter` · `def action` · `def service` · `def role` · `def approval` · `def program` |
| the world (`model world`, inside `def world <name> { … }`) | `def identity` · `def grant` · `def deployment` — the parts that differ per environment |

The flow around a decision — waiting, asking a person, scheduling, sending — is a process in the process engine
(BPMN), not part of this model. See [Removed in grammar 0.22](#removed-in-grammar-022).

## Parameters

```ttrm
package hartland.automation
model automation

def parameter tolerance { type: decimal, value: 0.01, label: { en: "Price tolerance", cs: "Cenová tolerance" } }
def parameter settlement { type: working_days, value: 5 }
```

A recipe lists the parameters it uses (`parameters: [tolerance]`) and reads them as `:tolerance`. Types: `decimal`,
`int`, `text`, `date`, `days`, `working_days`, `bool`.

## Actions

An action is what a route can trigger — its columns (the wiring fills them), how it changes the model, and how each
target performs it:

```ttrm
def action hold {
    columns:    [def column order { type: order.order_number }, def column department { type: text }]
    set:        order.held_for = department
    transition: order.state -> held { from: placed }
    sinks:      { native: transition, n8n: door, camunda: door }
    approval:   none
    dry_run:    { en: "would hold order {order} for {department}" }
}
```

- `transition: <entity>.<attribute> -> <state> { from: [...] }` — a state change, checked against the attribute's
  `states:`; `-> {to}` takes the state from an action column.
- `set: <entity>.<attribute> = <column>` — a plain write.
- `sinks:` — per target: `native` (`transition`, `writer`, `obligation` — what the Server writes), or a node/task name
  for an exported target, or `door`. An action without a `native` sink is an action row the engine acts on.
- `approval: none | required`, `deliverable: true`, `due: 2 working_days` — data on the action row (a required approval
  is a user task in the emitted BPMN, `due` its deadline).
- Column types: a model attribute (`order.order_number`), `text`, `int`, `decimal`, `date`, and `rows` (context rows),
  `role`, `action`.

## Roles, approvals, services

```ttrm
def role sales        { resolve: keycloak_group "hartland-sales" }
def approval values   { by: role.process_owner, quorum: 1 }
def service pricing   { request: price_request, response: price_answer, timeout: 30 seconds, budget: { per_run: 200 } }
```

## Programs

A program binds a recipe to how it is invoked — the whole candidate set, one key, or a list of keys:

```ttrm
def program morning_release      { recipe: release_order, port: none, mode: batch,    as_of: run }
def program release_on_placement { recipe: release_order, port: key,  mode: on_event, as_of: event }
```

`port: none | key | keys` · `mode: batch | on_event | windowed` · `protocol: always | on_route`. The engine starts a
run: its timer for a batch, its message (carrying the key) for an on-event program.

## The world: identities, grants, deployments

```ttrm
package hartland.worlds
model world

def world demo {
    def identity automation_decider { principal: "svc-hartland-automation", credential: TTR_CONN_AUTOMATION_DECIDER }
    def executor engine { extends: process_engine, url: "http://process-engine.process-engine.svc/engine-rest",
                          egress_url: "http://process-engine-egress.process-engine.svc/automation", connection: engine_bp_dsk }
    def executor n8n    { extends: n8n_l1, url: "https://n8n.hartland.example", connection: n8n_hartland }

    def grant { who: role.ops, program: *, as: identity.automation_decider }

    def deployment release_morning { program: morning_release, target: engine, run_as: identity.automation_decider }
    def deployment release_event   { program: release_on_placement, target: engine, run_as: identity.automation_decider,
                                     startable_by: role.sales_manager }
}
```

- **Identity** — a service principal; its credential is an environment variable (`TTR_CONN_<NAME>` holding
  `<client id>:<secret>`), never a value in the model.
- **Grant** — `who` (a role, an identity or `*`) may run `program` (or `*`) **as** an identity or `author`. Unnamed; a
  grant is its triple.
- **Deployment** — a program on a target (an executor of the world), with `run_as: caller | author | identity.<n>`.
- **Executor types** are names: `process_engine`, `n8n_l1`, `camunda_7`, `camunda_8`, `python_client`,
  `python_offline`, `sql_job` — `extends: n8n_l1`. `process_engine` is the estate's BPMN engine (D-145): `url:` is its
  REST base (`…/engine-rest`), `egress_url:` the run API as the engine's HTTP steps reach it (the egress adds the
  bearer, D-158). The `native` type is retired: an executor that still `extends: native` is refused `TTRP-TGT-006`.

## States on attributes

Any entity attribute may declare its lifecycle; actions' transitions are checked against it:

```ttrm
def attribute state { type: text, states: [placed, held, released, cancelled],
                      transitions: { placed: [held, released, cancelled], held: [released, cancelled] } }
```

Without `transitions:` any state may follow any other; a transition to the same state is always allowed and silent.

## What the toolchain ships

Projects that use automation get three entities without writing them — `obligation` (an expected follow-up, written by
an `expect`-style action and fulfilled by a later run), `state_history` (one row per state change an action wrote) and
`approvals` — in the stock package `automation`. A recipe reads them like any entity (`er.entity.obligation`).

The values in these objects are checked by the validator (`ttr/automation-*`, `ttr/states-*` diagnostics): required
properties, enumerated values, references (a program's recipe, a service's schemas, a grant's identity), duration
units, a deployment's program, and duplicate grants.

## Removed in grammar 0.22

The process engine in the estate (BPMN) owns the flow around a decision (D-142); the automation model keeps the
decision layer. These constructs were removed on 2026-11-01. For one release they still parse and are refused with
their own diagnostic — `ttr/automation-removed` from the validator, `TTRP-CUT-nnn` from `ttrp build` — naming what
models them now:

| id | removed | model it as |
|---|---|---|
| `TTRP-CUT-001` | `def process` (`def step`, `after:`) | a BPMN process: a service task per step calling the run API; a wait is a message catch with a timer boundary event |
| `TTRP-CUT-002` | a deployment's `process:` | deploy the program; the BPMN process calls it |
| `TTRP-CUT-003` | a deployment's `trigger:` | a BPMN timer start event |
| `TTRP-CUT-004` | `target: native` | an executor of the world — the estate's engine is `def executor engine { extends: process_engine, … }` |
| `TTRP-CUT-005` | a deployment's `connectors:` | the engine's tasks call the connectors |
| `TTRP-CUT-006` | a program's `deliver:` and digests | a BPMN send task; the Server renders a digest on request |
| `TTRP-CUT-007` | a program's `schedule:` | a BPMN timer start event |
| `TTRP-CUT-008` | a program's `ask_due:` | the BPMN user task's due date with a timer boundary event |
| `TTRP-CUT-009` / `-010` | `port: event`, a program's `event:` schema | `port: key` — the engine's message carries the key |
| `TTRP-CUT-011…014` | the native sinks `deliver`, `task`, `start`, `emit` | a send task, a user task (`approval: required`), a call activity, a message — acting on the action row |
| `TTRP-CUT-015` | `on_due:` (on an action or in a wiring) | a timer boundary event on the BPMN user task |
| `TTRP-CUT-016` | a role's `delivery:` | the BPMN send task addresses the role's members |

Exports follow: a deployment exports one program (no process exports), and an n8n or Camunda batch export needs a
schedule the model no longer carries — it refuses until the exporters take the timer from the engine.

## Exporting a deployment

`ttrp emit` renders a deployment for its executor's type. Run it from the model project, or pass `--project <root>`:

```sh
ttrp emit --deployment release_morning --out exports
# Reuse an existing compiler bundle:
ttrp emit --deployment release_morning --bundle automation/release_order/release_order.bundle --out exports
```

The output directory is `release_morning.process_engine.export/`: `manifest.json`, `README.md`,
`conformance.json`, the rendered files (for the engine: `release_order.c7.bpmn`, `templates/c7/`, `dmn/`), and the
bundle's golden YAML files under `tests/`, when present. `--json` prints the
export identity and stale rule as JSON. `--granularity recipe|parts|islands` overrides the deployment's
export granularity; the default is `parts`. Existing export directories are refused. Files are checked
against the bundle's hashes and a complete directory is published only after every file is written.

The export reads the model as the services do. Every model root in `modeler.toml` that exists is one source:
`models/` and each `[ttrp] extra-model-roots` entry, for example `["model", "automation"]`. The stock roles
(`fact`, `dimension` and the rest) and the automation stdlib are loaded with them, so the project does not copy
them. A file whose declared package differs from its directory follows `[packages].layout`, as `ttr-metadata load`
does: `off` accepts it, `flexible` (the default) warns and `strict` refuses the export. Any other load error, such as
one qualified name defined in two roots, refuses the export before anything is written, with the files that cause it.

An export becomes stale when its recipe text, parameter values, labels, program, deployment, world,
toolchain or target version differs from the served bundle. Rebuild and export again after such a change.
Executor manifests are plugin-owned TTR-M resources. All seven target manifests ship with the toolchain;
the process engine, n8n L1, Camunda and `python_client` render recipes; `python_offline` remains manifest-only.

## Python client exports

`ttrp emit --deployment release_py --out exports` writes `release_py.python_client.export/worker/`:
a typed client with `Run.parts.<name>()`, `Run.routes.<route>()`, `Run.actions.<action>.perform(keys)`,
`outcome(items)` and `Run.close()`,
a command-line entry point, a stdin/stdout service and route tests.
Declare the action's Python sink explicitly (`python_client: door`) when it has no native fallback.
All HTTP paths come from the SPI run-API projection. The Server executes the recipe and enforces grants,
fingerprints and dry-run policy; the package contains no SQL or process engine. The wire is the HTTP
run API (⚑AU-EXP-3), with no gRPC service or proto wheel.

From the generated `worker/`, set `TTR_SERVER` to the Server origin and `TTR_BEARER` to the authorized bearer:

```sh
uv run python -m release_order_client.main --key 4711 --as-of 2026-11-02T08:00:00Z --dry-run
uv run --group dev pytest
```

Python is pinned to 3.13.9, pytest to 8.3.5; the runtime uses only the standard library. `--key` accepts JSON;
composite keys are objects. For a batch program, `--batch '[4711,4712]'` selects keys and `--batch` selects all.
The port must match the exported program. On-event redeliveries reuse the caller's explicit clock in `port.at`;
dry runs also send it as an `asOf` override. Use `--as-of now` for the Server's current clock.
A real run of a run-clock program takes `now`; a literal instant needs a debug deployment.
The typed API defaults to `dry_run=True`; pass `dry_run=False` for real execution. The CLI uses
`--dry-run` for previews; omit it for a real run. The Open reply's stored `dryRun` must match the
request; if absent from the 201 body, status is read before parts or evaluate. The client reads
batch evidence before perform and reports each action's results in `perform_effects`. Perform
keys are objects; duplicate preview entries stay distinct while the request sends each key once.
Outcomes use `delivered|failed|skipped`, while the run is open; after seal they refuse with HTTP 409.
A stranded-key service error includes `retryable: true`, `runId` and `retryAfter` for redelivery.

The contracted perform seam (2026-11-14) governs this behavior:

`POST /automation/runs/{id}/actions/{a}/perform { keys: [ { keyColumn: value, … } ], group? }` — keys are always objects, one call per action. Answer `200 { performed: [{ key, batchRows }], skipped: [{ key, reason }] }`; `performed[].key` and `skipped[].key` are the same objects as sent. **Hold is decided per preview entry (⚑R254-J, 2026-11-14):** the request sends each key once; the answer carries one `(key, reason)` per **preview entry** of that action (one key can have two entries — non-exclusive routes × wirings); an entry is `held` iff any of its own `batchRows` carries an approval `group` (`GET …/batch` rows, `batchRows` on the status preview action, are the wire for it). An entry sharing a deduplicated grouped row with a held entry is held too. The export's `held_actions` list is a cross-check only.

On a dry-run-only deployment the client refuses a real run; pass `--dry-run` (typed API: `dry_run=True`).

Hold marks: a batch row carries an approval `group` only for an action with `approval: required` on a native `transition`/`writer`/`obligation` sink; a route-level approval (D-145's user task) marks no batch row.

A lost perform response on a real run: the client does **not** close; the run stays for inspection and perform is idempotent per key, so an operator re-drives it or the abandonment clock ends it.

Dry run: `performed: []`; every held key `skipped {reason: "held"}` (held precedes dry-run); every other selected key `skipped {reason: "dry-run"}`. Nothing is applied; the client closes a dry run after its evidence is read.

Real run: an unheld key answers `performed`, or `skipped {reason: "failed"}` (its row failed in Apply) or `skipped {reason: "not-applied"}` (still staged). These are **legal results for the caller**, reported with their reason — not client errors, and the run continues and closes normally. Perform applies ahead of close and is idempotent per key; close never resends a performed row.

A client that rejects evidence or fails mid-run does **not** close a real run while nothing has been performed on it (the run stays for inspection; the Server's abandonment ends it). Once at least one perform returned a `performed` entry, the client closes the run (best effort) before re-raising, so the batch stays consistent with what was applied.

An Open that answers a foreign claim (`200` non-terminal or `202 + Location`) is polled inside the client's own window (its budget + 2 min). When the window ends the client POSTs the same Open **once** more; a `201` is a fresh claim, a `202` or a non-terminal `200` raises a distinct retryable error that carries the run id ("key held by run <id>; retry later") for the event system to redeliver. A stranded real run blocks its key until the Server abandons it.

Abandonment clock: today `Ledger.abandonExpiredRuns` compares every run against `COALESCE(budget_batch, budget_sync, default)` and Open always stores `budget_batch`, so an on-event run is abandoned after `budget_batch` + 2 min (~32 min by default). **Contract:** a run whose program `mode` is on-event abandons by **`budget_sync` + 2 min**; a batch run by `budget_batch` + 2 min. The client polls inside its own budget + 2 min. The merged fixture baseline `c2f2f049` includes the mode-aware follow-up. Its `retryAfter` uses the mode's budget + 2 min: 180 seconds for a default on-event run, 1920 seconds for a default batch run.

## Deploying to the process engine

A `process_engine` deployment renders the Camunda 7 dialect for the estate's engine (CIB seven / Operaton, D-145):

- every HTTP step is Camunda Connect's `http-connector` calling the executor's `egress_url` — the egress injects the
  `process-engine` client's bearer, so the BPMN carries no token and no connector secret (D-158);
- `startable_by: role.<r>` becomes `camunda:candidateStarterGroups` on the process, from each role's Keycloak group;
  the engine's provisioner grants START_INSTANCE from that attribute. A role without a unique group refuses
  `TTRP-EXPORT-ROLE`; `startable_by: []` writes no attribute (no role may start it);
- the element templates carry their implementation: an `http-connector` to the egress, which opens a run of the
  World deployment property for a program step, or posts `{query, parameters}` to `/query` for a query step.

`manifest.json` records `fingerprints.bundle`: sha256 over the BPMN and the `dmn/` files, in file-name byte order,
each as `<name>\n<bytes>`. `--deploy` posts exactly those files as one engine deployment:

```sh
export TTR_BEARER=…            # a personal bearer of an engine operator (or --token-env <variable>)
ttrp emit --deployment release_event --deploy --check     # absent | current <id> | stale <old> → <new> <id> | foreign <id>
ttrp emit --deployment release_event --deploy             # deployed <id> | current <id>
kubectl -n process-engine port-forward svc/process-engine 18081:80 &
ttrp emit --deployment release_event --deploy --engine-url http://127.0.0.1:18081/engine-rest
```

The deployment is named after the world deployment and carries `deployment-source = ttrp:<first 16 hex of the
bundle fingerprint>`, with the engine's duplicate filtering off. `--deploy --check` reads the newest deployment of the
name (`GET /deployment?name=…`) and changes nothing: `absent` and `current` exit 0, `stale` and `foreign` exit 2.
`--deploy` creates for `absent` or `stale` (a new definition version; running instances finish on theirs), posts
nothing for `current`, and refuses `TTRP-DEPLOY-FOREIGN` for a deployment of the name that `ttrp emit` did not make,
unless `--force`. Any transport failure or engine refusal is `TTRP-DEPLOY-ENGINE <status|cause>` with exit 2 and never
claims a deployment. The CLI owns idempotency: the read decides whether to post, and after a create it reads the newest
deployment of the name again and prints `deployed <id>` only when that is the created deployment with this bundle's
source; anything else (another deployer in between) is `TTRP-DEPLOY-ENGINE the engine did not make the bundle current …`.
Re-deploying an earlier bundle behind a newer one (A → B → A, a rollback) makes a new version; two deployers of the same
bundle at once leave two deployments of the name. (The engine's own duplicate filter compares a bundle only with the
newest deployment of the same source and then answers the newest deployment of the name, so it would never redeploy an
earlier bundle; that is why it stays off.) With `--deploy` the export folder is written only when
`--out` is given. The bearer is read from the environment, must be an RFC 6750 token (a pasted newline or space is
refused before any request), and appears in no output or file. Customer `camunda_7` / `camunda_8` exports stay files: `--deploy`
refuses them.

> **Grammar 0.22 (D-142).** The paragraphs below on process exports, step bundles, digests and batch schedule hints
> describe the exporters as built before the cut. The model no longer carries `def process`, `deliver:` or
> `schedule:` (see [Removed in grammar 0.22](#removed-in-grammar-022)), so `ttrp emit` exports one program per
> deployment and refuses a batch n8n / Camunda export until the exporters take the timer from the engine.

## n8n L1 exports

`ttrp emit --deployment release_n8n --project . --out exports` produces an inactive `workflow.json`,
credential/import instructions, conformance references and an exact named call list in `manifest.json`.
`--granularity recipe|parts|islands` selects whole-recipe evaluation, named parts or the verified island waves.
The exporter carries no SQL. Every Open includes recipe and program/deployment fingerprints; stale refusals
stop the workflow. A replayed Open skips the action path so a duplicate event does not send mail again.

Batch schedules accept `HH:MM Zone`, `Mon|Tue|Wed|Thu|Fri|Sat|Sun HH:MM Zone`, `1st HH:MM Zone`,
or `*/N` (1–60 minutes). Unsupported hints refuse export. Named zones use IANA timezone identifiers, while
key/event programs expose `/webhook/<deployment>`. HTTP nodes use the `tatrman-automation` bearer credential
and three retries. Engine-side mail uses `tatrman-mail` SMTP, `TTR_MAIL_FROM` and the `TTR_MAIL_RECIPIENTS`
JSON role-to-address mapping. Missing role mappings fail. A customer-addressed email action instead declares
`to` typed by an E-R address attribute, wired to a bare route-row column. Rows are grouped by recipient;
empty addresses fail with the key. Both the deployment and Server must explicitly permit wet delivery.
The Server must return its per-key `actions` selection array for every run; missing fields fail. Set `TTR_AUTOMATION_URL` to the Server
origin, without `/automation`. The generated README lists the required n8n worker environment access.

On-event callers must supply a stable `at` ISO instant with an offset to recognise duplicate events. Without
it every POST is a new run. `at` supplies the port clock and is excluded from event columns; a schema column
named `at` refuses export. A 202 Open handle is polled without repeating its actions: closed/waiting completes
the duplicate, failed/abandoned allows one re-open with the saved body. A second 202 refuses. After a lost Open
response this can wait for the Server's budget plus recovery grace before recovering the orphan.

Process exports verify and fingerprint every step before writing a package, preserve each step's recipe,
effective port and parent deployment, and save later-step clocks after their waits. Every later step, in either
mode, uses the first step's run ID plus `:<step name>` as its idempotency key, using its re-opened run ID after
abandonment. Redeliveries share these keys; a duplicate may claim a later step first, leaving the original to
handle its 202 response. Step 1 keeps the execution ID for batch and the derived key for on-event.
Obligation waits require an explicit `obligations` array; missing
fields fail, and only an empty array means absent. `until` controls fulfilled/due readiness. Webhook waits also
wake at the nearest future dueAt, capped at an hour. Required task approvals wait again after each resume or
timeout while the run remains waiting. When status carries `tasks`, resolved task dueAt sets the timeout;
otherwise ordinary declared due durations apply, with an hourly backstop for calendar-based due rules that lack
an instant. Evaluation and handle polls honour Retry-After (default 15 s)
with a program-budget plus two-minute recovery-grace bound.

`--bundle` supplies the selected program's verified recipe bundle. Camunda exports also read other deployed
programs' `.ttrp` parameter declarations from the project and resolve their types/values through metadata.
SPI v2 JVM callers must rebuild with this toolchain and declare `spiRevision = 3` (revision 3 adds
`DeploymentMeta.startableGroups`); the host refuses an older revision before rendering. SPI v1 stays compatible; this API change does not change the grammar's 0.22 minor.
The removed process, delivery and schedule SPI fields have no compatibility shims.
Hartland H-2/winback customer-mail exports refuse until its model supplies the address column. H-2 live
certification also requires the Server's planned obligations status field.

## Camunda BPMN file exports

`ttrp emit --deployment release_bpmn --project <root> --out <directory>` emits
`release_order.c8.bpmn` for a `camunda_8` executor; `camunda_7` emits the C7 dialect as
`release_order.c7.bpmn`. Open the matching file in Camunda Modeler. Both dialects share stable SHA-1-derived
node IDs and BPMN DI. They contain named run-API calls and fingerprints, with no SQL or credentials.
The definitions declare Camunda Platform 7.24.0 or Camunda Cloud 8.6.0 for the Modeler. C8 needs at least 8.6 for
[converging inclusive gateways](https://unsupported.docs.camunda.io/8.6/docs/components/modeler/bpmn/inclusive-gateways/).
Its [Zeebe user tasks](https://unsupported.docs.camunda.io/8.6/docs/components/modeler/bpmn/user-tasks/) and
[FEEL timezone conversion](https://unsupported.docs.camunda.io/8.5/docs/components/modeler/feel/builtin-functions/feel-built-in-functions-conversion/)
are also available by that version. Server clocks use offset ISO instants.

C7 uses Camunda Connect HTTP, Spin JSON and Groovy for response-header handling. Its
`TTR_CONN_AUTOMATION` runtime bean supplies the Server origin and bearer. C8 uses the standard REST outbound
connector, the `TTR_AUTOMATION_URL` variable and connector secret `TTR_CONN_AUTOMATION`. The generated README
names the complete input and adapter requirements. Start the exported one-program workflow externally;
author schedules and larger compositions in the engine.

Only a newly claimed run drives effects. Replays finish that program; live handles poll and permit one re-open
after abandonment/failure. Batch caller keys use the engine execution ID. The diagram includes parallel contexts,
route branches, per-row action tasks, bounded evaluation and stale-export boundaries. Missing action evidence or
incomplete route pages refuses rather than finishing with partial effects. Every route is paged with offset using
the Server's default page size, then all rows are processed. Route forks and joins are inclusive in every mode;
exclusivity is enforced per row by the Server. Grammar 0.22 removed recipe process, delivery and schedule constructs.
Obligation watching and digest policies belong to engine composition.

HTTP 409 takes the stale boundary; other 4xx except 408/429 take the drawn refusal task. HTTP 408/429, 5xx and
transport errors retry, then become incidents. C8 error retries honour numeric or HTTP-date Retry-After,
with a 15 s fallback, a 1 s minimum and a cap of the program budget + 2 min. C7 Connect failed jobs use
R3/PT15S because an output exception cannot commit a dynamic retry delay. No failed response continues into
an action outcome or close. C7 `ttrInput` must be a Java Map; the generated README states the REST variable
contract and the limits of expression-only verification.

Mail and approval nodes are customer integration templates. The mail worker (`TTR_CONN_MAIL`) and the exchange's
approval integration belong to Platform; this export does not bundle or certify them. The file and its Modeler
opening are the Camunda acceptance artefacts; no live Camunda deployment is part of this exporter.

The export also contains `templates/c7/<step>.json` or `templates/c8/<step>.json`, plus
`dmn/<query>_decision.dmn` for each projectable metadata `def query`. Queries that cannot be compiled, encoded or
typed are omitted and named in the README and `manifest.authoring.omitted`; deployed-program failures still refuse. Copy the matching templates into your Modeler project's
`.camunda/element-templates` directory and restart Modeler. The choice dropdown lists this world's deployed programs
and model queries. Inputs bind by parameter name; query outputs expose their typed row columns and `row_count`.
Configure the governed HTTP task and its variables derivation in Modeler before running it.

Each DMN 1.3 skeleton has typed inputs, hit policy FIRST and no rules. Author the business rules and outcome in
Modeler; inline SQL and Golem questions have no generated skeleton. Templates carry identity
`tatrman.<world>.<step>`, `metadata.fingerprint` and an explicit positive integer `version`. Pass
`--template-version <n>` (default 1) and raise it whenever the regenerated fingerprint differs; Modeler prefers the
highest version for an id. After model/bundle changes, regenerate into a new export folder and reinstall the templates. Preserve authored rules separately: regeneration creates empty skeletons.
Approval tasks use the approving action's `by: role.<r>` Keycloak group as C7 `candidateGroups` or C8 assignment
candidates. Roles without a unique group refuse with `TTRP-EXPORT-ROLE`. The README names `startable_by` roles and their
Keycloak groups for START_INSTANCE authorization; operators configure that authorization in a customer's engine.
C7 processes and DMN decisions carry a 30-day history TTL. Customer templates bind IO only; implementation bindings
are configured in Modeler (the `process_engine` export's templates carry them, above).

The README lists artifact paths, omitted queries, and authoring cautions for C7 DMN types, parameter defaults,
batch external starts and unverified other-program sources. The manifest records hashes and step/DMN associations.

Offline structure conformance needs the export directory and a pinned fixture, with no Server or token:

```sh
ttrp conform --replay replay-release.yaml --cells camunda_8.on_event --export release_bpmn.camunda_8.export
```

The driver checks the manifest call inventory against actual BPMN tasks, connector paths, route branches and
graph reachability, template bindings, DMN associations and artifact hashes. Success is `structure-agree`, which
makes no verdict/action execution claim. A removed task
or branch returns exit 3 with a structural diff; malformed/unsafe XML returns driver-error exit 2. XML validation
uses the bundled standard BPMN schemas and explicitly local schemas for the supported extension subset.

## Conformance of a recipe

Replay compares a pinned golden case with native dry-run evidence. Batch opens one `port: keys` run;
on-event opens one `port: key` run per candidate. No connector action is performed.

```sh
export TTR_AUTOMATION_TOKEN='<bearer>'
ttrp conform --replay automation/release_order/tests/release.yaml \
  --cells native.batch native.on-event \
  --server https://automation.example.org --token-env TTR_AUTOMATION_TOKEN \
  --world us --out replay-report.json
```

`--cells` also accepts a comma-separated list or repeated options. The token is read from the named
environment variable. The Server must expose exactly one enabled, granted, current native deployment
for each recipe/mode. Fixture parameters must match the served recipe; replay cannot override approved
parameter values. On-event fixtures need explicit keys; a batch fixture without `keys` selects all candidates.
`--world` filters fixtures by their declared world; it does not change the Server's world or deployments.

n8n dry replay interprets the supported generated node types and JavaScript expressions with Node 22+;
it does not start n8n, send SMTP or wait for a person. Pass `--cells n8n-l1.on-event` and repeat
`--export <deployment>.n8n_l1.export` for each recipe/mode. The exported deployment must be enabled,
granted and current. The driver checks the manifest's call list against the actual HTTP parameters,
executes with `dryRun: true` and the fixture's asOf/origin, and reads exact Server evidence. A keys fixture
substitutes its batch selection; an all fixture sends the rendered none port unchanged. Observed actions come
from executed perform/outcome bodies and mail-node rows/recipient, with SMTP replaced by a dry stand-in, then
are cross-checked against the Server's action selection multiset. Dropped/wrong actions disagree. This proves
orchestration and dry evidence parity; committed writes, actual SMTP, human timing and a deployed n8n engine
remain live certification. Use trusted generated exports; arbitrary Code-node workflows are executable artifacts. Process lifecycle
replay and event-body fixtures remain separate from the H-1 key-based dry replay.

Python replay runs the generated package itself in a uv/Python subprocess, using `--cells python-client.on-event`
(or `--driver python-client`) plus `--export release_py.python_client.export`. Batch exports use
`--cells python-client.batch`. One service process handles the fixture's ordered per-key requests (or one batch
request), retaining errors per key. The service forces dryRun and the fixture clock, reads complete route/detail
pages and the dry batch, preserving action previews without executing them. Declared budgets apply
separately to each run, including a re-opened run and a completed foreign claim's evidence reads; the fixture's
process timeout sums those budgets with allowance for one re-open per request. Live handles poll using
Retry-After (15 seconds by default), allowing one final status read before timing out. Malformed Server keys, HTTP failures,
truncated details and missing exports are driver errors. `python-client` remains an HTTP run-API consumer.
Errors on owned dry runs receive a best-effort close. A re-claim resets status; until status is read,
cleanup uses the Open response/body dry flag.
Live Python certification belongs to the P2 certification stage.

Golden YAML uses `recipe`, `world`, `as_of`, `parameters`, `keys`, `expected`, and optional `pinned_from`
(`run`, `at`, `by`). A multi-recipe replay uses `recipes: [release_order, settle]` and stores each collection
under its recipe name, for example `expected.verdicts.release_order: [...]`. Detail assertions use
`{key, route, context, rows: [...]}` entries in `expected.details`; golden `detail_counts` is also supported.
Omitting actions/details means the golden does not assert that collection; an empty list asserts zero rows.
Demo dates and offset-free timestamps in `as_of` are interpreted as UTC.
A case without `pinned_from` is reported as **seed**, exits zero and opens no run.

Verdict, action and detail comparisons preserve duplicate rows, exact decimals, null values and UTC
microseconds. Reasons compare after named `{fills}` from the fixture parameters or observed row's fill
values. Final-state assertions use `expected.final_states: {entity: er.entity.order, attr: {state: {4711: released}}}`
and compare the dry preview's ordered transition effects; they do not attest persisted model writes.
This native replay does not simulate intermediate model writes between multiple recipe runs.
Incomplete, truncated or failed evidence is a driver error. HTTP polling and paging are bounded.

Exit codes are **0** for agreement/seeds/world skips, **3** for disagreement, and **2** for fixture, driver or
HTTP errors. The JSON report identifies every cell and its missing/extra rows. The current local HTTP
suite uses contract-derived responses; the captured Server H-1 responses and live Hartland certification
are required before AU-export P0 exits.

### Engine attributes (CG-2)

`def action.by: role.<name>` names the group that approves an action. It is required
when `approval: required`; the role must have a `def role` declaration. The compiler
reports `TTRP-ACT-001` for a missing, malformed or unknown approving role. The
engine uses this role for its user task's candidate group (D-144/D-161).

`def action.due: 2 working_days` sets the engine user task's due date (D-144).
The optional duration must have a whole, non-negative amount and a unit of
`seconds`, `minutes`, `hours`, `days`, `working_days`, `weeks` or `months`.
Invalid values produce `TTRP-ACT-003`. A timer boundary and its fallback remain
part of the authored BPMN process. This is the action's declared default (D-98).
A wiring call's `due:` wins for that call and is carried in `wiring[].columns.due`.

`def deployment.startable_by: role.sales_manager` limits who can start that
deployment (D-161). A list such as `[role.sales_manager, role.ops]` is also accepted;
every entry must resolve to a declared role (`TTRP-ACT-002`). An explicit empty
list is retained and permits no role to start the deployment. Absence leaves
start permissions unspecified. `recipe.deployments` is a build-time copy for exporters.
No bundle fingerprint covers deployments, so the start check reads `startable_by`
from the live world, never from a bundle.

`def executor.egress_url: "http://process-engine-egress/automation"` declares the
engine's egress endpoint (D-158). The optional value must be a string
(`TTRP-ACT-004`); the compiler carries it as data for consumers.

The `process_engine` executor type (D-145) ships a capability manifest. Declare
`def executor engine { extends: process_engine }` inside the world. It supports
`none`, `key` and `keys` ports and the C7 modes, kinds and control capabilities;
its sinks include `user: "user task"` and `call: "call activity"` and exclude the
removed `task`, `ask`, `start` and `wait` sinks. Rendering and deployment belong
to the exporters' adoption stage.

Manifest v3 adds optional `recipe.actions` (`by`, `due: { amount, unit }`),
`recipe.deployments` (`startableBy`) and `recipe.executors` (`egressUrl`) maps.
Role references retain their `role.` prefix. The generated recipe catalog carries
the same data with TTR-M property spelling. Empty additions are omitted.
A bundle keeps its bytes when its wired actions declare no `by`/`due`, and its deployments and their executors
declare no `startable_by`/`egress_url`. A `due:` written before CG-2 now adds `recipe.actions.<a>.due` and the
catalog's `actions:` line. JVM consumers must
rebuild against the released grammar bundle because metadata and SPI data-class
constructors gain fields; the grammar remains 0.22.
