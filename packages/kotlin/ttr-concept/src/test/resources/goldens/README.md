# `ttr-concept` goldens (CN-P3)

The acceptance cases of `ConceptService` (cn contracts §3), authored FIRST — CN-P3 S3.7, before any function body —
and turned from red to green stage by stage (S3.2 validate · S3.3 statement lower · S3.4 er renderer + materialize ·
S3.5 assertion lower · S3.6 explain). Every expectation comes from an oracle independent of the code under test.

## A case

`<group>/<case>/case.json` names the case; the files beside it are its input and its expectations.

| key | |
|---|---|
| `world` | the model it runs over: `hartland` · `clinic` · `sales` (`../worlds/`, see its README) |
| `call` | `statementValidate` · `statementLower` · `statementExplain` · `assertionValidate` · `assertionLower` · `assertionExplain` · `definitionRender` · `materialize` |
| `stage` | the CN-P3 stage that turns it green; `check` runs it once that stage has landed |
| `frame` | an S3.1 sample under `../frames/` (`statement-e0` …) — else the case's own `statement.json` / `assertion.json` |
| `locale` | the explain calls' locale |
| `target`, `layer`, `dialect`, `options` | `definitionRender` / `materialize`: the concept, `ER`, the dialect, `RenderOptions` as JSON |
| `tags` | `live` — also checked on the live-PG harness (S3.8) |
| `oracle` | where the expectation comes from (below) |

| file | compared with |
|---|---|
| `expected.diagnostics.json` | the diagnostics, in order: `slug` · `severity` · `subject` (the message is not compared); absent = none |
| `expected.dotpath.txt` · `expected.plan.json` | `LowerResult.dotPath` (the md resolver's canonical text) · `LowerResult.plan` (`plan.v1`, protobuf JSON) |
| `expected.provenance.json` | `LowerResult.provenance` — the keys it names |
| `expected.txt` | the explanation's text |
| `expected.rows.json` | `RowObjects` — the keys it names |
| `expected.sql` · `expected.rendering.json` | `Rendering.text` (whitespace-collapsed until S3.4 pins the formatter, then byte-equal) · the rest of `Rendering`, the keys it names |
| `expected/<path>` | `ModelText.files` |

A key the expectation names that the result type does not carry yet (an S3.4 `RenderOptions` field, S3.5's
`assertedAsOf`) fails the case — it is red until that field exists, never silently dropped.

## Pending

`<group>/<case>.pending` is a case whose lowering needs a primitive that does not exist yet; its one line is the
demand id it waits on (`G-ER-1`, `G-ER-2`, `G-MD-1/2/6`, `md/hop`, `CN-P4 S4.2`, `CN-P10` — see
`project/tatrman/features/conceptual/plan/demands/ttrp-catalog-dc-gaps.md` and plan.md). The runner reports it as
ignored, named with the id — never as passed. When the demand lands, the file is replaced by the real case.

## Oracles (`GoldenOracleSpec`, green from day one)

- **md** — `oracle.mdPath`: the expected dot-path and plan are what `DefaultMdPathResolver` + `MdPathLowering` make of
  that dot-path text; `oracle.semiJoin` (ActiveCustomer, ⚑CN-P9): two such reads composed as a grain-key semi-join.
- **er** — every `expected.sql` translates (`ttr-translator` over the world's er tier): strict ER lane when it reads no
  parameter, lenient otherwise.
- **H-1** — `oracle.au`: AU's hand-written island; `"plan": "equal"` = the same ER plan bytes (A-H-2), `"rowset"` =
  the same rows on live PG (A-CN-15, the case's `seed.sql`).

## Running

```bash
./gradlew :packages:kotlin:ttr-concept:test                             # the landed stages' cases + the oracles
./gradlew :packages:kotlin:ttr-concept:test -Pcn.goldens=all            # every case — red ones included (TDD view)
./gradlew :packages:kotlin:ttr-concept:test --tests 'org.tatrman.concept.Golden*' -Pcn.goldens.regen
                                                                        # rewrite the oracle-made files; review the diff
```

Public repo: invented names and codes only (Hartland's public demo names; `X21`-style clinical codes).
