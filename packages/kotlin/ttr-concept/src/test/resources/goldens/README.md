# `ttr-concept` goldens (CN-P3)

The acceptance cases of `ConceptService` (cn contracts §3), authored FIRST — CN-P3 S3.7, before any function body —
and turned from red to green stage by stage (S3.2 validate · S3.3 statement lower · S3.4 er renderer + materialize ·
S3.5 assertion lower · S3.6 explain); since S3.8 every case runs in `check`. Every expectation comes from an oracle
independent of the code under test.

## A case

`<group>/<case>/case.json` names the case; the files beside it are its input and its expectations.

| key | |
|---|---|
| `world` | the model it runs over: `hartland` · `clinic` · `sales` (`../worlds/`, see its README) |
| `call` | `statementValidate` · `statementLower` · `statementExplain` · `assertionValidate` · `assertionLower` · `assertionExplain` · `definitionRender` · `materialize` |
| `stage` | the CN-P3 stage that turned it green (a record — since S3.8 `check` runs every case) |
| `frame` | an S3.1 sample under `../frames/` (`statement-e0` …) — else the case's own `statement.json` / `assertion.json` |
| `locale` | the explain calls' locale |
| `target`, `layer`, `dialect`, `options` | `definitionRender` / `materialize`: the concept, `ER`, the dialect, `RenderOptions` as JSON |
| `tags` | `live` — also run on the live-PG harness (`ConceptLiveSpec`; S3.3 T7, the PR's live suite at S3.8) |
| `oracle` | where the expectation comes from (below) |

| file | compared with |
|---|---|
| `expected.diagnostics.json` | the diagnostics, in order: `slug` · `severity` · `subject` (the message is not compared); absent = none |
| `expected.dotpath.txt` · `expected.plan.json` | `LowerResult.dotPath` (the md resolver's canonical text) · `LowerResult.plan` (`plan.v1`, protobuf JSON) |
| `expected.provenance.json` | `LowerResult.provenance` — the keys it names |
| `expected.txt` | the explanation's text |
| `expected.rows.json` | `RowObjects` — the keys it names |
| `expected.sql` · `expected.rendering.json` | `Rendering.text`, byte-equal — the formatting contract (cn contracts §3, A-CN-42; one trailing newline in the file) · the rest of `Rendering`, the keys it names (`parametersRead` entries carry `derivation` for a derived bound, A-CN-22) |
| `expected/<path>` | `ModelText.files` (and `expected.diagnostics.json` its `diagnostics`) |
| `seed.sql` · `expected.live.json` | a `live` case: its invented rows (a `statementLower` case's go on top of ttrp-conform's `md_seed.sql`) · the rows the lowered plan returns on PostgreSQL (`{"rows": [["150.50"]]}`, numbers by value) |

A key the expectation names that the result type does not carry yet (an S3.4 `RenderOptions` field, S3.5's
`assertedAsOf`) fails the case — it is red until that field exists, never silently dropped.

## Pending

`<group>/<case>.pending` is a case whose lowering needs a primitive that does not exist yet; its one line is the
demand id it waits on (`G-ER-1`, `G-ER-2`, `G-MD-1/2/6`, `md/hop`, `CN-P5 S5.3` — see
`project/tatrman/features/conceptual/plan/demands/ttrp-catalog-dc-gaps.md` and plan.md). The runner reports it as
ignored, named with the id — never as passed. When the demand lands, the file is replaced by the real case.
`ConceptGoldenSpec.PENDING` pins the whole set (case → demand): a new `.pending` file is a deliberate edit there too,
never a quiet way to skip a red case.

## Oracles (`GoldenOracleSpec`, green from day one)

- **md** — `oracle.mdPath`: the expected dot-path and plan are what `DefaultMdPathResolver` + `MdPathLowering` make of
  that dot-path text; `oracle.semiJoin` (ActiveCustomer, ⚑CN-P9): two such reads composed as a grain-key semi-join. A
  read's group keys are the `*`s its text writes; a grain dimension the text leaves out is aggregated over and left out
  of the expected dot-path (the statement's shape, A-CN-39 — S3.3).
- **er** — every `expected.sql` translates (`ttr-translator` over the world's er tier): strict ER lane when it reads no
  parameter, lenient otherwise.
- **H-1** — `oracle.au`: AU's hand-written island; `"plan": "equal"` = the same ER plan bytes (A-H-2), `"rowset"` =
  the same rows on live PG (A-CN-15, the case's `seed.sql`).

## Running

```bash
./gradlew :packages:kotlin:ttr-concept:test                             # every case + the oracles
./gradlew :packages:kotlin:ttr-concept:test --tests 'org.tatrman.concept.Golden*' -Pcn.goldens.regen
                                                                        # rewrite the oracle-made files; review the diff
TTR_CONN_ERP_PG=postgresql://user:pass@host:port/db ./gradlew :packages:kotlin:ttr-concept:test --tests 'org.tatrman.concept.ConceptLiveSpec'
                                                                        # the `live` cases on PostgreSQL (else ignored)
```

Public repo: invented names and codes only (Hartland's public demo names; `X21`-style clinical codes).
