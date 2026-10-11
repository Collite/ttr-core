# TTR-P — Contracts (v1)

> **Status:** consolidated 2026-07-04. Source of truth for every TTR-P cross-component boundary: file formats, the project manifest, LSP methods, the bundle, emit payloads, diagnostics. Companion: [`architecture.md`](./architecture.md); decision IDs reference [`../design/00-control-room.md`](../design/00-control-room.md).
>
> **Changelog** at the end. Changes to anything here require a changelog entry.

---

## 1. File kinds & markers

| Kind | Extension | Dialect marker | Notes |
|---|---|---|---|
| Canonical program | `.ttrp` | extension | no `program` header (S12); identity = filename |
| Bare TTR-SQL program | `.ttr.sql` | double extension; override `-- ttr: dialect=sql` | source text never rewritten (C0) |
| Bare TTR-pandas program | `.ttr.py` | double extension; override `# ttr: dialect=pandas` | |
| Bare TTR-B program | `.ttrb` (English skin) · `.ttrb-cs` (Czech skin) | extension; override `# ttr: dialect=b [lang=en\|cs]` (S19, AG B2) | `dialect=ttrb` read as `dialect=b`; another `lang` = an unknown dialect (FRG-001) |
| View-state sidecar | `.ttrl` | — | family-wide; pairs by filename (`x.ttrp` + `x.ttrl`) |
| World / models | `.ttrm` (TTR-M) | — | world = `schema world` doc in the model repo (S22) |
| Bundle | `<program>.bundle/` (S1) | — | see §5 |

Embedded fragments in `.ttrp`: TTR tagged block literals — `"""sql`, `"""pandas`, `"""ttrb`, `"""ttrb-cs` (C3-g, C4-f, AG B2). Tag = dialect (for TTR-B, tag = keyword skin). Fragment interiors are byte-preserved (C2-f); comment lexis per dialect: `--` (TTR-SQL), `#` (TTR-pandas, TTR-B) (S19). **File-backed fragments (AG B5, TTRP.g4 0.3):** `container x(…) target e from "<path>"` — the file's whole content is the interior (byte-preserved), the path relative to the program file, the dialect from the file's extension / first-line marker; `TTRP-FRG-004` file not found, `TTRP-FRG-005` no dialect marker.

## 2. The project manifest — `[ttrp]` table (S5)

Lives in the project manifest file (`modeler.toml`-equivalent; resolution = walk up, same as TTR-M). All keys optional unless marked; P2: no user dotfiles in the chain; precedence for the world: `uses world` pin > `[ttrp] world` > error.

```toml
[ttrp]
world            = "acme.worlds.dev"      # REQUIRED unless every program pins
bare-target      = "erp_pg"               # bare-fragment container target
bare-shell       = "bash"                 # bare-fragment executor
split-policy     = "warn"                 # T5-b escalation: warn | error
display-default  = "arrow"                # bare-program final-result sink format
staging          = ""                     # only if world doesn't declare staging: true
rls-egress       = "warn"                 # warn | error (Q8)
assist-provenance = "none"                # none | comment (C4-d-iii)
default-imports  = ["erp.*"]              # bare-fragment implicit prelude ONLY (S18)
```

## 3. Language-surface contracts (summary — grammars are canonical)

- **Statement forms (C3-a γ):** chains (`a -> filter(…) -> sort(…)`) + SSA assignment, freely mixed; precedence `=` < `->` < call. `=` is the one equality, context-separated; `==` rejected with diagnostic `TTRP-EQ-001` except inside TTR-pandas (S9).
- **Multi-in:** named-only (`join(left: …, right: …)`); **Union: list form only**, `union(a, b, c)`, internal ports `in1..inN` (S11). Inside TTR-SQL, SQL's own positional syntax carries port meaning (C2-b-ii).
- **Reserved port names:** `in, out, err, rejects, true, false, else` — lowercase; port names share column lexical rules (S10).
- **Control:** `b after a` (FS), `a with b` (SS); optional `control {}` block; `finishes with` reserved, use = capability error `TTRP-CTL-001`.
- **Containers:** closed; `container <name>(in …, out …, err …) target <engine> { … }` or `container <name> target <engine> """<tag> … """` or `… target <engine> from "<file>"` (AG B5); cross-container wiring at program level only.
- **Fragments (C2/C4):** single default-out; `err` only (no rejects producers); document scope flows in (ports > imports > qnames; same-level ambiguity = error); TTR-SQL = one query expression (`WITH` + final `SELECT`; clause table in `11-fragments-options.md`; `SELECT *` expands statically; LIMIT/OFFSET require ordered input else `TTRP-SQL-014`, S15); TTR-pandas methods = `select calc filter join aggregate sort union limit load store display` — full words only (S17); TTR-B roster + verbose expression synonym table in `12-nl-options.md`, the current roster in both keyword skins (English / Czech), blocks, actions and their lowering in [`../ttrb/ROSTER.md`](../ttrb/ROSTER.md). A TTR-B action sentence (`send_email` / `update_field` / `manual_task`) is a sink: the checker adds the container OUT port and the program-level `<container>.<port> -> display(<kind>)` (marked `synthesized`), so the action display check (§8.2) applies.
- **Movement:** cross-engine data edge ⇒ synthesized Store+Transfer+Load via world staging (D-f); explicit `load`/`store` for sources/sinks and control (S14); `via <storage>` override.
- **Schemas:** declared only — world doc or program (`schema:` inline / named def); inline > program > world; same-level conflict = error (D-c). Types = TTR db-schema attribute types verbatim (S23).

## 4. LSP & Designer protocol — `ttrp/*`

One Kotlin LSP; transports stdio + WebSocket (same methods). Standard LSP (diagnostics, hover, definition, rename, formatting) plus:

| Method | Params → Result | Notes |
|---|---|---|
| `ttrp/getGraph` | `{uri, version}` → `{graph, provenance, derived, orchestration, autoLayout}` | authored graph (containers + authored node kinds + ports + edges); nodes carry ζ + source ranges + er provenance (E-d); `orchestration` = derived islands/transfers/waves; **`autoLayout`** = per-canvas ζ → abstract `{layer, index}` (deterministic Kotlin-side layout, C1-b — the client maps to pixels per skin orientation) |
| `ttrp/applyGraphEdit` | `{uri, version, edits[]}` → `WorkspaceEdit` | β vocabulary (C1-d); formatter-owned placement; stale version ⇒ error, client replays |
| `ttrp/getLayout` / `ttrp/setLayout` | `{uri}` → sidecar content · `{uri, layout}` → ok | sidecar rewritten wholesale; ζ keys; pair-integrity diagnostics |
| `ttrp/getWorld` | `{uri}` → `{world, engines[], executors[], storages[], staging}` | resolved world for the document |
| `ttrp/transpile` | `{uri, version}` → `{bundlePath, manifest}` | = `ttrp build` |
| `ttrp/run` | `{uri, version}` → `{runId, exitCode, out[]}` | shells out to the bundle (C1-e); Designer fetches outputs over the loopback `GET /out/{name}` route (browser can't read the FS; path-traversal-guarded, serves the current bundle `out/` only — S24) |
| `ttrp/explain` | `{uri, version, node?}` → normalized graph, placements, rewrites applied, island→payload map | S4 |
| `ttrp/authoringContext` | `{uri?, position?}` → context bundle (§7) | S8; serves assist hosts and agents |
| `ttrp/validate` | `{source or uri, dialect?}` → `{diagnostics[]}` | full front-half check of candidate text; the assist repair loop's gate |

## 5. Bundle contract (F-f)

```
<program>.bundle/
├── run.sh                  # wave-parallel bash; set -euo pipefail; wait -n abort
├── manifest.json           # see below
├── islands/<name>.{sql,py} # one file per island; SSA/CTE names preserved
├── transfers/<name>.py     # generated ADBC/connectorx movement scripts
├── schemas/*.json          # Arrow schema fingerprints per staging boundary (Q9-1)
└── plans/*.pb              # plan.v1 payloads — Kantheon-target worlds only (E-a)
# created at runtime, wiped on restart (F-e): logs/ staging/ out/
```

`manifest.json` (F-f-i; machine record — the narrative is run.sh + islands):

```json
{
  "ttrpVersion": 1,                     // spec version (S6)
  "toolchain": "org.tatrman:ttrp:<semver>",
  "program": "<filename>",
  "world": {"qname": "acme.worlds.dev", "fingerprint": "sha256:<semantic-hash>"},
  "islands": [{"name": "...", "engine": "...", "executor": "bash",
               "invocation": "psql|python3", "file": "islands/...", "sha256": "..."}],
  "transfers": [{"from": "...", "to": "...", "via": "<staging>", "file": "...", "sha256": "..."}],
  "waves": [["island-a","island-b"], ["island-c"]],
  "connections": ["TTR_CONN_ERP_PG", "TTR_CONN_FILES"],
  "displays": [{"name": "main_result", "file": "out/main_result.arrow"}],
  "files": {"<path>": "sha256:..."}
}
```

Exit contract: `0` ok · `1` island failure · `2` pre-flight failure (missing `TTR_CONN_*`, world-incompatibility). Credentials **only** via `TTR_CONN_<NAME>` env vars; the artifact is secret-free. Staging format = Arrow IPC everywhere (F-c-i). The world fingerprint is **recorded** by the artifact and **verified** by capable invokers (T6 split); bash pre-flight checks env/connections only.

### 5.1 Bundle manifest v2 (E-5 graduation, PL-P0.S1) {#manifest-v2}

The E-5 graduation (platform [`contracts.md §6`](../../../../../project/platform/design-corpus/design/contracts.md)) promotes the F-lite `manifest.json` above to a **documented, versioned, stable** contract. Normative schema (JSON Schema draft 2020-12): [`bundle-manifest-v2.schema.json`](../../../packages/kotlin/ttrp-emit/src/main/resources/schemas/bundle-manifest-v2.schema.json) (`$id: https://tatrman.org/schemas/ttrp/bundle-manifest/v2`), validated by `BundleManifestV2SchemaTest` against `manifest-v2-hero.json`.

Door execution keys on **`schemaVersion` (const 2)** — the E-5 version key (FQ-7 acceptance window, platform §20). v2 adds, over v1:

- **`params[]`** (F-4-i): top-level declared, typed, defaulted-or-required trigger-time params — `{name, type ∈ {string,int,decimal,date,datetime,bool}, required, default?}`; islands list the param names they consume (`island.params[]`).
- **island `retries`** (F-4): manifest-declared, transient-class retry count.
- **island `onFailureOf`** (F-4-iv): when set, the island runs **iff** the named source island failed (on-failure islands); `null`/absent for ordinary islands. The **wave-resume guard** is the snapshot fingerprint (platform §7 `resume: { scope: wave, guard: snapshot-fingerprint }`) — a run resumes a parked wave iff the fingerprint is unchanged.
- **per-island `connections[]`**: connection refs moved from bundle-global to per-island (H-5 least exposure); the bundle-global `connections[]` stays as the union.
- **`displays[]` — one entry per display leaf** (grammar 0.14 action displays, additive). Several entries may share a `name` — an **action display** (a `def schema` row schema in an imported package, §8.2) takes one source per wire; an ordinary display name with several sources is `TTRP-DSP-004`. Entries sort by `name`, then **wiring order** (the order a host concatenates one action display's rows in); each keeps **its own** `source {island, port}` (`sql-text` bundles), and each `file` is unique — `out/<name>.arrow`, then `out/<name>~2.arrow`, … (`~` never appears in a TTR-P identifier). An action display's entry also carries `schema` (the package-qualified schema, e.g. `shop.actions.notify`) and `columns[] {name, type, optional?}` (the schema's, in order; `type` is the schema's own spelling — `decimal(12,4)` for a structured `{ type: decimal, length: 12, precision: 4 }`); an ordinary display's entry omits both, so a program with unique ordinary display names is byte-identical to before. The rows an action display receives are exactly the schema's columns in schema order, **every column cast to the type the schema column declares**, so all sources of one display — any port, island or engine — share **one** row type and the host can concatenate their files: a carried column is cast, an absent `optional` one is a `NULL` of that type, a column the schema does not name is dropped (`TTRP-DSP-003`). The Arrow files (Polars and Postgres islands) carry text as `large_string`, every integer kind as `int64`, `decimal(p,s)` as `decimal128(p, s)` (an unsized decimal is `decimal128(19, 2)`), `float` / `real` as `double`, `bool`, `date32`, `time64[ns]`, `datetime` / `timestamp` as `timestamp[us, tz=UTC]`; a `sql-text` statement casts to the same SQL type in every statement (text to `VARCHAR(MAX)` — a declared text length is reported, not cast to). A `sql-text` output projected to a schema reports the schema's spellings in `islands[].outputs[].columns[].type`. In a `sql-text` bundle the source port's statement (`islands[].outputs[]`) is projected in place when every display it feeds is that one action display; a port that also feeds a differently-shaped display keeps its own statement and gains a projected output `<port>~<schema-name>` (file `islands/<island>.<port>~<schema-name>.sql`), which the action display's `source.port` names.
- **`lineage`** (CQ-5): STATIC, compile-derived column lineage — `columns[].{output{island,relation,column}, inputs[]{qname,column}, transform}`. `transform` draws the ⚑ transform-tag vocabulary `identity | expression | aggregate:<fn> | join-key | filter-only`. Maps 1:1 onto the OpenLineage `columnLineage` facet (export lives in Veles connectors, platform §18).

**No `provenance` block in the manifest** — provenance is the *envelope's* job (F-7): binding-dependent content inside the artifact would break hard parity (B-3). The compile record (below) is a bundle-adjacent sidecar.

The schema **permits** `params`/`onFailureOf`/`retries` from day one; the toolchain **emits** them only from **PL-P2** (the F-4 grammar for params/on-failure lands there). Using reserved vocabulary against a world whose executor-type manifest lacks it is an ordinary compile error (T6, §5.2). The manifest's **wave graph is authoritative; `run.sh` is a rendering** (drift = emit bug, conformance-caught).

**Manifest v1** (§5 above) is **accepted by doors during the SZ-3 window** (platform §20): capable invokers read `schemaVersion` and accept `1` for the migration window, `2` thereafter.

**Compile record** (platform §5, B-3-α/F-7): a bundle-**adjacent** sidecar `<program>.compile-record.json` — **never inside the bundle, never in the manifest's `files{}`** (it is legitimately binding-dependent: `mode`, `staleness`, so it must not be hashed into the artifact). Carries `{recordVersion, toolchain, program, mode, lock{hash,path}, snapshot{world,models,manifests}, worldFingerprint, plugins[], statsUsed[], staleness, objectsRead[]}`. The envelope cites `{lock hash, compile-record sha256}`; replay re-injects `statsUsed` and byte-compares.

### 5.2 Executor capability manifests (FQ-4 + T6 format pin, PL-P0.S1) {#executor-manifests}

**Format pin (FQ-4, discharges the "Stage 2.2 owns the format" debt):** engine/executor **type** manifests are **TTR-M documents** — `schema world` vocabulary, `def executor <id> { … }` with free-form property blocks (the shape ttr-metadata already transports as `Map<String, PropertyValue>`), shipped as classpath resources.

**T6 ownership amendment (this batch):** the original "type manifests ship with the compiler" wording is **amended** — **core engine types ship with the compiler; executor types ship with their emit plugin** (§6/§8 emit-SPI). Rationale: robots write through git, TTR text is the family's one canon format, and the transport is already built.

Tatrman-the-executor's manifest (FQ-4 concrete artifact — content = F-4 verbatim; shipped with `cz.tatrman:radegast`, qname `tatrman.manifests.executor.tatrman`):

```ttrm
schema world

def executor tatrman {
    control:    [fs, ss],                  # FF stays reserved — absent ⇒ compile error on use
    params:     { types: [string, int, decimal, date, datetime, bool],
                  binding: trigger-time, builtins: [run-date] },
    retries:    { scope: island, classification: [transient, permanent] },
    resume:     { scope: wave, guard: snapshot-fingerprint },
    onFailure:  { scope: island, absorbs: false },
    events:     [cron, manual, upstream-run],
    invocation: { psql: true, python3: true },
    manifestSchema: { accepts: [1, 2] }    # FQ-7 window (platform §20)
}
```

Programs targeting a platform world compile **against this manifest**; vocabulary the manifest lacks ⇒ ordinary T6 compile error. The **bash** executor-type manifest (extracted plugin, §6/§8, PL-P5) declares the strict F-lite subset: `control: [fs, ss]`, **no** params/retries/resume/onFailure, `events: []`, `manifestSchema: { accepts: [1, 2] }`.

## 6. Emit contracts (E)

- **SQL:** CTE-per-node; node → named CTE (SSA/CTE name); trivial islands flat SELECT (deterministic rule); every Sort emits explicit `NULLS LAST` (or authored placement); dialect v1 = Postgres.
- **Polars:** straight-line script; generated inline prelude containing only needed enforcement helpers (3VL NULL, decimal, UTC-µs datetimes — Q9 items 4–6); no runtime library dependency.
- **PlanNode:** when the container's target resolves to a Kantheon world engine, the invocation binding delivers `plan.v1.PlanNode` protobuf payloads under `plans/` — produced by `org.tatrman:ttr-translator`; `plan.v1` proto **vendored** in ttr-translator (S25 lean; final call in the extraction arc).
- **Invocation bindings v1 (F-c):** pg×bash `psql -v ON_ERROR_STOP=1 --no-psqlrc -f` · polars×bash `python3` (interpreter+packages from executor-type manifest) · display×bash file drop `out/<name>.<fmt>` + printed notice.

## 7. Authoring-context bundle (`ttrp/authoringContext`, C4-d/S8)

Deterministic, prompt-ready serialization — **normative schema: [`authoring-context.schema.json`](./authoring-context.schema.json)** (`$id: https://tatrman.org/schemas/ttrp/authoring-context/v1`; two example instances under [`examples/authoring-context/`](./examples/authoring-context/)). The bundle carries: `version` · `world` (resolved summary — qname, fingerprint, engines/executors/storages, staging, per-storage rls flag) · `capabilities` (per-engine node/function support tables — T6 manifests) · `modelObjects` (db + er, with schemas) · `scope` (imports, variables with resolved column schema, ports at cursor — present only when a cursor `position` was given) · `grammar` (spec version, statement summary, per-dialect rosters for ttrp/sql/pandas/ttrb) · `diagnostics` (the named-diagnostic catalogue = the repair vocabulary). Every object is closed (`additionalProperties: false`); determinism is the product (C4-d-ii). The **schema is final** (Stage 4.2); some content grows in later phases — the capability node/function rosters, model-object enumeration, and the TTR-SQL/TTR-B grammar rosters + TTR-B diagnostic rows land in P6/P7 with the shapes already pinned. Consumed by any LLM host; paired with `ttrp/validate` in a generate→validate→repair→review loop; generated text arrives as a proposed edit, never applied silently (C4-d-iii).

## 8. Diagnostics convention

Named ids, stable, documented: `TTRP-<AREA>-<NNN>` (areas: EQ, SQL, PD (pandas), B, CTL, CAP, MOV, SCH, WLD, RLS, **LAY** (`.ttrl` view-state pair integrity), **EDIT** (graphical edit), **FRG** (fragment/bare-program: unknown dialect tag, missing dialect marker, missing `[ttrp]` bare defaults, a file-backed fragment's file missing / unmarked), **RJ** (rejects / erroneous-rows producer), **DSP** (action displays, §8.2)…). Every rejected form carries a suggested alternative (`TOP 10` → "use LIMIT 10"; `agg` → "use aggregate"; `==` → "use ="). The reject tables per dialect are versioned fixtures (test + assist repair vocabulary). **`LAY`** ids (Stage 5.2): `TTRP-LAY-001` layout entries no longer match the graph (orphaned → reset/re-place), `TTRP-LAY-002` sidecar parse error, `TTRP-LAY-003` sidecar references an unknown canvas.

### 8.1 Rejects diagnostics (`RJ`)

Two families (RJ-P6). **Authoring/rewrite diagnostics** — surfaced during compilation, each with a suggested alternative (`TtrpDiagnosticId`, `ttrp-frontend/.../diagnostics/Diagnostics.kt`):

| Id | When | Suggested alternative |
|---|---|---|
| `TTRP-RJ-101` | `rejects` wired on a node that can never reject (dead wire) | remove the `rejects` wire (the stream is always empty; R-A2-α) |
| `TTRP-RJ-102` | reject cluster moved off its engine (knob=escalate) | the rejects cluster was moved off this engine because it cannot produce rejects (knob=escalate) |
| `TTRP-RJ-103` | a user column uses the reserved `_ttrp_` prefix | the `_ttrp_` column prefix is reserved for synthesized rejects columns — rename this column (RS-5) |
| `TTRP-RJ-104` | a volatile (impure) function in a reject-capable position | a volatile (impure) function cannot appear in a reject-capable position (R-C2-b) |
| `TTRP-RJ-105` | (reserved) an ON expression spans both join inputs — superseded in v1 by RJ-108 (join-ON rejects are fail-closed, not a pair-schema fallback) | this ON expression spans both inputs — its rejects fall back to the pair schema (R-B3-β) |
| `TTRP-RJ-106` | the placed engine cannot produce rejects (knob=produce/error) | this engine cannot produce rejects; set `[ttrp] rejects-in-sql = escalate` (or move the site to a capable engine) |
| `TTRP-RJ-107` | `rejects` wired on a reject-capable cast whose type v1 does not emit faithfully (only `text->int64` + `op.div` are supported) | remove the `rejects` wire, or change the target type — bool/date/decimal/float/timestamp casts are fail-closed in v1 (RJ-P5 review, B1) |
| `TTRP-RJ-108` | a reject-capable expression appears in a join `on:` with a wired `rejects` port | move the `cast`/`op.div` into a `calc` before the join and wire `rejects` there — join-ON rejects are fail-closed in v1 (RJ-P5 review, B2) |

**Row reject-codes** — the per-row `_ttrp_reject_code` a reject producer emits into the erroneous-rows stream, one per catalogue-defined validity site (the `code:` field of each `ttrp/validity/*.yaml`):

| Code | Reject-capable site |
|---|---|
| `TTRP-RJ-001` | `cast text→int64` |
| `TTRP-RJ-002` | `cast text→decimal(18,4)` |
| `TTRP-RJ-003` | `cast text→float64` |
| `TTRP-RJ-004` | `cast text→date` |
| `TTRP-RJ-005` | `cast text→timestamp` |
| `TTRP-RJ-006` | `cast text→bool` |
| `TTRP-RJ-007` | `op.div numeric,numeric→numeric` (zero denominator) |
| `TTRP-RJ-008` | `fn.to_date` (unparseable) |
| `TTRP-RJ-009` | `fn.to_timestamp` (unparseable) |

Completeness is guarded by a test (`RejectDiagnosticsTableSpec`): every `TTRP-RJ-1xx` enum id and every validity-YAML `code:` appears here, and vice-versa.

### 8.2 Action displays (`DSP`, grammar 0.14)

A TTR-M **`def schema <name> { columns: [ def column <c> { type: <t>, optional?: true }, … ] }`** in a package the program imports (`import <pkg>.*`, scoped like entities) makes `display(<name>)` an **action display**: the rows flowing into it — across program-level wiring, `select` narrowing included — are held to the schema. **Assignable** = the same type (decimal precision ignored), an integer → any integer / decimal / float, `datetime` ⇄ `timestamp`, or any scalar → text (the host renders action fields as text); a source whose type the checker cannot infer is not judged. A schema column's type is one of the scalars every engine can produce — the TTR-M type keywords (not `object` / `list`) plus the SQL spellings a generated model carries as type ids (`bigint`, `smallint`, `tinyint`, `long`, `numeric`, `money`, `smallmoney`, `real`, `bit`, `time`, `datetime2`, `smalldatetime`, `nvarchar`, …), with the structured form's length / precision (`{ type: decimal, length: 19, precision: 2 }`); an unsized decimal is `DECIMAL(19, 2)`. Row types follow container ports through wiring, and a chain that ends in an OUT port (`… -> late`) gives the port its row type exactly as `late = …` does. An unknown input row type defers the check; the emitted display still never NULL-fills a **required** column — the `sql-text` and Postgres emitters refuse the build (`TTRP-DSP-001`, from the port's static columns), and a Polars island (whose frames have no static columns) stops with `TTRP-DSP-001` before it writes the display file. Only an absent `optional` column is a typed `NULL`. A display whose name is not a declared schema is unchanged.

| Id | Severity | When | Suggested alternative |
|---|---|---|---|
| `TTRP-DSP-001` | error | a non-optional schema column is missing from the display's rows (one per column) | produce it (e.g. `calc { <col> = … }`) before the display, or mark it `optional: true` in the schema |
| `TTRP-DSP-002` | error | a column's type is not assignable to the schema's | convert it with `cast(x as <type>)` before the display |
| `TTRP-DSP-003` | warning | the rows carry columns the schema does not name — dropped from the display's output (one per display, listing them) | `select` the schema's columns |
| `TTRP-DSP-004` | error | a display name with several sources that is NOT a declared row schema (ambiguous) | rename the displays, or declare `def schema <name>` and import it |
| `TTRP-DSP-005` | error | a schema column's type is not a scalar an engine can produce (`object`, `list`, an unknown type id, a decimal wider than 38) — once per display | declare it as text, int, bigint, smallint, decimal, float, bool, date, time, datetime or timestamp |
| `TTRP-RES-002` | error | two imported packages both declare a schema with the display's name | import only one |

### 8.3 TTR-B sentences (`B`)

One reject table per keyword skin — `ttrp-frontend` resources `ttrb/rejects.en.yaml` and `ttrb/rejects.cs.yaml` (they replace `rejects/ttr-b.rejects.toml`): the same ids in both, each row a message in the skin's language and a **suggested correct sentence**; every row of both tables has a fixture that triggers it. One primary diagnostic per fragment.

| Id | When | Suggested alternative (English skin) |
|---|---|---|
| `TTRP-B-001` | update (`Update` / `Aktualizuj`) | `Set <attribute> of <entity> with key <key> to <value> with reason "<reason>".` / `Store that to <model-ref>.` |
| `TTRP-B-002` | insert | `Combine that with <name>.` / `Store that to <model-ref>.` |
| `TTRP-B-003` | DDL (drop / truncate / alter) | model changes belong in TTR-M; `Store that to <model-ref>.` |
| `TTRP-B-004` | off-roster verb; a sentence the parser cannot read | a roster verb, e.g. `Keep only the rows where <condition>.` |
| `TTRP-B-005` | `//` / `/*` comment | `# a comment` |
| `TTRP-B-006` | a sentence in the other skin's language | mark the fragment `"""ttrb-cs` (or `"""ttrb`) |
| `TTRP-B-007` | a comparison outside the closed table | a listed form or an operator |
| `TTRP-B-008` | pivot | author it in canonical TTR-P |
| `TTRP-B-101` | a character outside TTR-B | remove it or quote the text |
| `TTRP-B-102` | a block header without `:` at its line end / without an indented sentence | `If <condition>:` + indented sentences |
| `TTRP-B-103` | an indentation matching no open block | indent a block's sentences alike |
| `TTRP-B-104` | a malformed e-mail sentence | `Send an e-mail to <recipient> with subject "…", template "…", key <key> [and attachments …].` |
| `TTRP-B-105` | a malformed set sentence | `Set <attribute> of <entity> with key <key> to <value> with reason "…".` |
| `TTRP-B-106` | a malformed manual-task sentence | `Create a manual task for <recipient> "<title>" with description "…".` |
| `TTRP-B-107` | a malformed count sentence | `Count the rows of <table> as <name>.` |
| `TTRP-B-108` | a malformed attach sentence | `Attach <table> to the result.` |
| `TTRP-B-109` | a keyword where a name belongs | choose another name |
| `TTRP-B-110` | a block inside a block | `If <a> and <b>:` |
| `TTRP-B-111` | a malformed output sentence | `Send that to output <port>.` |
| `TTRP-B-112` | an output to a port the container does not declare | declare `out <port>` in the container header |
| `TTRP-B-113` | a value a sentence produces that nothing reads (no later sentence, port or display) | `Call that <name>.` and use the name, continue with `that`, or `Send that to output <port>.` |

`TTRP-EQ-001` (`==`) is reported by the TTR-B scanner too. The LSP publishes these on embedded fragments, on bare `ttrb` / `ttrb-cs` documents (sentence by sentence unless the project sets `[ttrp] bare-target`), and — for a file-backed fragment — on the program's `from` clause as `<file>:<line>:<col>: …`.

## 9. Conformance — `ttrp-conform` (S3, Q9)

Invoker contract: reads `manifest.json` → provisions `TTR_CONN_*` → runs `run.sh` per engine placement variant → collects `out/` + staged Arrow (incl. the `rejects`/`bad` streams named in the manifest reject sites) → compares under the seven-point per-stream procedure (fingerprint schemas; multiset rows, canonical-sort under terminal Sort; NULLS LAST; decimal exact / float64 declared tolerance; UTC-µs; binary collation), **plus an eighth (partition) point** when the program produces rejects (RJ-P5): per reject site, `in == processed + rejects` per engine **and** the `(in, processed, rejects)` triple agrees across engines. `in`/`processed`/`rejects` are counted independently at run time (guard-input / guard-clean-output / reject terminal) into a `counts.json` beside the Arrow exports; the cross-engine triple-match turns red for a broken producer even when the accepted-row displays agree. Doubles as emit regression suite and standalone-vs-Kantheon drift guard. Invoked as `ttrp conform`.

## 10. Published artifacts

| Artifact | Content | Consumer |
|---|---|---|
| `org.tatrman:ttr-parser` / `:ttr-writer` / `:ttr-semantics` | TTR-M toolchain (existing; parses `.ttrl` too) | kantheon (Ariadne), TTR-P compiler |
| `org.tatrman:ttr-metadata` | model graph + queries + world resolution | Ariadne wrapper, TTR-P compiler, Designer server |
| `org.tatrman:ttr-translator` | Proteus core: island→RelNode→SQL/`plan.v1` | TTR-P compiler; kantheon Proteus (thin wrapper) |
| `org.tatrman:ttrp-*` (front-half, emit, lsp, cli, conform — module cut in plan Phase 0) | the TTR-P toolchain | editors, Designer server, agents (authoring seam, C4-e) |

Publishing: tag-driven per `PUBLISHING.md`; spec version via grammar-master process (S6). npm scope: `@tatrman/*` (S7, plan Phase 0).

---

## Changelog

- **v2.5 · 2026-10-10 — TTR-B grouping, naming, unused results (AG B8).** `( … )` groups any sub-condition in TTR-B (filters, join conditions, `If` headers, conditional values); `and` binds tighter than `or`. `Call that <name>.` / `Pojmenuj to jako <jméno>.` names the current value; `Load <name>.` of a bound name makes it current again. `TTRP-B-113` (error): a value a sentence produces that nothing reads.
- **v2.4 · 2026-10-10 — TTR-B ports, joins, conditional values (AG B7).** A container's IN ports are names in TTR-B sentences (never `load(<port>)`); `Send that to output <port>.` / `Pošli to na výstup <port>.` binds a declared OUT port (`TTRP-B-111` malformed, `TTRP-B-112` undeclared port); optional (left) joins, joins on a model relation, keep-rows-that-have / have-no-match (semi / anti), `Compute x as 1 when <p>, otherwise 2.` (`case when`). Emit (`plan.v1` / `sql-text`): an UNQUALIFIED column in a join condition binds to the left input when the left has it, else the right (it bound to the wrong input before); an island IN port fed by a program-level model `load(…)` reads the model object in a host statement.
- **v2.3 · 2026-10-10 — TTR-B skins, blocks, actions, file-backed fragments (AG B1–B6).** TTR-B keyword skins: ONE `TTRB.g4`, the keyword token types spelled by `ttrb/roster.en.yaml` / `roster.cs.yaml` (case- and diacritic-insensitive keywords; exact Latin-extended identifiers); markers `"""ttrb-cs`, `*.ttrb-cs`, `# ttr: dialect=b lang=cs` (§1). `If …:` / `Když …:` blocks = one `filter` output each, overlapping. Count / attach sentences; the action sentences `send_email` / `update_field` / `manual_task` lower to `calc`/`select` and are routed to their action displays through synthesized container OUT ports + program-level wiring (§3, §8.2). `TTRP.g4` `@grammar-version 0.2 → 0.3` (additive minor): `container … from "<file>"` (soft keyword `from`), `TTRP-FRG-004/005`. A TTR-B container emits from its decomposed members on every SQL path (only `sql` / `pandas` fragment text is emitted verbatim). Reject tables per skin, new `TTRP-B-101…110` (§8.3). `authoringContext`: `grammar.dialectRosters["ttrb-cs"]` and the `ttrb-cs` insertion dialect (schema additive).
- **v2.2 · 2026-10-10 — Action displays (TTR-M grammar 0.14 `def schema`).** A named row schema declared in TTR-M and imported like an entity turns `display(<name>)` into an action display, held to the schema (§8.2: `TTRP-DSP-001..004`). §5.1 manifest v2, additive: one `displays[]` entry per display leaf — each with its own `source`, same-named entries in wiring order, unique `file`s (`out/<name>~<k>.arrow`), and `schema` + `columns[]` on an action display; a `sql-text` port feeding an ordinary and an action display gains a projected `<port>~<schema>` output. Fixes the several-sources bug (every same-named entry carried the first one's source). `cast(x as <type>)` now lowers to the translator's function form wherever the translator renders it — `sql-text` and every translator-rendered CTE node (it failed `sql-text` builds with `TTRP-EMT-004`); the Postgres CTE path's **raw** rendering (a cast calc, a reject guard — `RejectGuardSql`) names the cast target with its Postgres type (`text`, `integer`, `double precision`, `numeric`, `decimal(p,s)`, `boolean`, `timestamp`, …) — it used to print the TTR-P spelling (`CAST("order_id" AS string)`, invalid PostgreSQL); `cast(x, string)` is `TTRP-PRS-001`, not an NPE.
- **v2.1 · 2026-07-21 — PL-P2.S1: F-4-i params + F-4-iv on-failure islands + F-4-ii retries IMPLEMENTED (grammar → manifest v2 emission).** Discharges grammar-master work items #1/#2 (v2 reserved them). `TTRP.g4` `@grammar-version 0.1 → 0.2` (additive minor): program-level `paramDecl : PARAM identifier COLON typeName (ASSIGN paramDefault)?` with a `BUILTIN : '@' [a-zA-Z][a-zA-Z0-9-]*` token (`@run-date`); container-level `containerAttr : ON FAILURE OF identifier ABSORBS? | RETRIES INT` after `target <qname>`. `on` is a **soft** keyword (also in `identifier` — preserves `join(…, on: …)`); `param`/`failure`/`of`/`retries`/`absorbs` are hard. New diagnostics: `TTRP-PARAM-001..003` (bad scalar type / duplicate / `@run-date`-on-non-date), `TTRP-FAIL-001..003` (unknown on-failure island / on-failure cycle / `absorbs` reserved), and the **T6 executor gate** `TTRP-CAP-201..203` (params/on-failure/retries used against a world whose executor manifest lacks the capability — `ExecutorManifestGate` in ttrp-graph; the `tatrman` executor manifest `tatrman.json` declares all three, `bash` none). Manifest v2 emission (§5.1, the schema already permitted the fields): top-level `params[]` (name/type/required/default), per-island `retries`/`onFailureOf`/`params` (per-island `params` = whole-word match of declared names in the rendered island payload — uniform for canonical + opaque fragments). An on-failure island is **excluded from wave levelling** and carried as `island.onFailureOf` (error edge). A param-free program's manifest is byte-identical to pre-feature (`explicitNulls=false`; mode-drift stays green). TTR-P is **Kotlin-only** (no antlr-ng/TS parser — the "both parsers" premise of the pre-generated task list does not apply).

- **v2 · 2026-07-19 — PL-P0.S1 amendment batch (one batch, per the family amendment discipline).** Records the platform strangler's contract seam ([platform `contracts.md`](../../../../../project/platform/design-corpus/design/contracts.md)) against the TTR-P/TTR-M surface, **no behaviour change** (all suites stay green; the toolchain emits these only from the phases below). Seven amendments:
  - **F-4-i params** — runtime params grammar surface reserved (grammar-master work item #1, lands **PL-P2**); manifest `params[]`/`island.params[]` shape (§5.1).
  - **F-4-iv on-failure** — island on-failure vocabulary reserved (work item #2, lands **PL-P2**); manifest `island.onFailureOf`/`retries`/wave-resume guard (§5.1).
  - **FQ-4 executor capability manifest** — the Tatrman-executor type manifest recorded verbatim (§5.2); `def executor tatrman { … }` + the bash F-lite subset.
  - **E-5 manifest v2 + lineage** — the load-bearing bundle-manifest **v2** graduation (§5.1): `schemaVersion` const 2, JSON Schema at `packages/kotlin/ttrp-emit/src/main/resources/schemas/bundle-manifest-v2.schema.json` (`BundleManifestV2SchemaTest`), static column `lineage` (CQ-5); **no provenance block** (rides the envelope + compile-record sidecar, §5.1). v1 accepted by doors through the SZ-3 window.
  - **T6 ownership + format pin** — type manifests are TTR-M documents; **executor types ship with their emit plugin** (amends "ship with the compiler"), core engine types with the compiler (§5.2).
  - **K `extends`-platform-world** — world-composition grammar surface reserved (work item #3, lands **PL-P1.S4**); platform world authoritative, contradiction = compile error, fingerprint-fed order-insensitive ([grammar-master `contracts.md §8`](../../grammar-master/contracts.md)).
  - **H-1 `security` block** — grammar reservation (work item #4, lands **PL-P4**); deny-overrides, fingerprint-neutral, one-way MIT generator (grammar-master §8).

- **v1.5 · 2026-07-19** — Rejects / erroneous-rows producer (design/rejects arc, RJ-P0…P6). Catalogue-defined canonical validity (`ttrp/validity/*.yaml`) + a graph-rewrite guard-and-branch elaboration stratum turn a wired `rejects` port into a real reject producer: SQL emits one more terminal SELECT (guard CTE + first-error CASE ladder), Polars a mask-and-split. §9 conformance gains an **eighth (partition) point** — per reject site, `in == processed + rejects` per engine + the triple agrees across engines (`counts.json` beside the Arrow exports; `processed` counted at the guard's clean output). New diagnostics area **`RJ`** (§8.1): authoring/rewrite `TTRP-RJ-101..106` + row reject-codes `TTRP-RJ-001..009` (from the validity YAMLs). `ttrp/getGraph` gains an `elaborated` flag (serves the normalized graph with the synth reject cluster, each node `synthesized` + `synthOf`-back-referenced); the authoring-context capability roster gains a per-engine `rejects` boolean (additive; schema stays v1). Fail-fast (unwired-rejects) programs stay byte-identical (R-P3). **Live-sealed PG↔Polars** (SQL Server target parked — [#44](https://github.com/Collite/ttr-core/issues/44)).

- **v1.4 · 2026-07-08** — Fragment dialects (Phase 6). `"""sql` / `"""pandas` fragment interiors now decompose clause-wise into the standard node set (C2-a-β): the dialect grammars (`TTRSql.g4`, `TTRPandas.g4`, own grammars per C2-g α) lower each fragment to canonical TTR-P AST, so resolution + graph construction are shared and **bare ≡ embedded ≡ canonical produce byte-identical normalized graphs** (the KEY GATE; `NormalizedGraphJson` is the canonical serializer). S16 clarified: the shared keyword/operator table is Kotlin-hosted (`KeywordTable` + `CatalogId` + the one `Expression` IR) — the dialect grammars SKIN it and carry sibling drift specs, NOT an ANTLR grammar unit. Fragments still **emit verbatim** (`SqlIslandEmitter` unchanged) — decomposition is for graph structure/identity, not re-emission; interiors stay byte-verbatim (C2-f). New diagnostics area **`FRG`** (`TTRP-FRG-001..003`, §8): unknown dialect tag, missing dialect marker (bare programs), missing `[ttrp]` bare-target. Reject tables `ttr-sql.rejects.toml` (SQL-001..015) + `ttr-pandas.rejects.toml` (PD-001..010) are versioned fixtures (§8). Grammar files added to `packages/grammar/src/` (Kotlin-only generation, G-b). **Remaining P6 work** (bare-program wrapper synthesis from `[ttrp]` defaults, conform-three-ways, Designer fragment drill-in) noted in the Phase-6 progress doc.

- **v1 · 2026-07-04** — initial consolidation from the design log (A…H, C0–C4, F-lite, S1–S25).
- **v1.1 · 2026-07-07** — authoringContext v1 schema finalized (Stage 4.2); §7 is now normative via `authoring-context.schema.json` (+ two example instances). The C4 leftover ("concrete schema = plan work item") is closed. Diagnostics area `EMT` (SQL/emit, `TTRP-EMT-001..006`) recorded in Phase 3.
- **v1.3 · 2026-07-07** — Designer edit + run (Phase 5.4). `ttrp/applyGraphEdit` implemented: the closed β vocabulary (C1-d-i) → a formatter-owned whole-document `WorkspaceEdit`; stale version ⇒ `ContentModified` carrying **`TTRP-EDIT-001`** (client re-pulls + replays). New diagnostics area **`EDIT`** (`TTRP-EDIT-001..004`, §8): stale version, fragment/derived target rejected, unknown op, invalid target. New loopback **`GET /out/{name}`** route on `ttr-designer-server` (§4/§5, path-traversal-guarded, serves the current bundle `out/` only — the browser's transport for run outputs). v1 applyGraphEdit cut synthesizes the additive hero-build ops (createContainer/addNode/connect/assignTarget); mutating/rename ops return `TTRP-EDIT-003` pending their synthesis (rename already available via `textDocument/rename` + the 5.2 sidecar-atomic participant).
- **v1.2 · 2026-07-07** — Designer surface (Phase 5.1/5.2). §4: `ttrp/getGraph` result gains `orchestration` (derived islands/transfers/waves) + **`autoLayout`** (per-canvas abstract `{layer, index}` — the deterministic Kotlin-side layout contract, C1-b); `ttrp/getWorld` shape pinned (`{world, fingerprint, engines[], executors[], storages[], staging}`). New diagnostics area **`LAY`** (`TTRP-LAY-001..003`, §8) for `.ttrl` pair integrity. The `.ttrl` sidecar body is grammar-hosted in `TTR.g4` v4.3 (C1-c-iii; separate `ttrlDocument` entry rule, `chains` records SSA chain lengths for deterministic orphaning). The WS-LSP transport mounts at `/lsp` on `ttr-designer-server` (MD8).
