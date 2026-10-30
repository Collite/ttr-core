# ttr-concept

The conceptual-model (`cn`) layer below the determinism frontier. An agent proposes a frame over concept IDs — a
`statement.v1` (read), an `assertion.v1` (write) or a recipe naming a phase — and this module validates, lowers,
explains and renders it, deterministically: `statement_*` lowers to `plan.v1` through the md path, `assertion_*`
lowers to an E-R row payload, and `definitionRender` renders a phase or defined quality on the md layer (a PL
predicate for `ttrp-emit`) or the er layer (SQL over entities in the shared dialect). No LLM, no server call, no I/O
mid-lower: the same model and frame always give the same bytes.

**CN-P8 S8.2:** rigid subkinds inherit their bearer; relator kinds carry participants and
validity; phases use episode tables or event bounds; `per:` / `from:` qualities support
DC-13 qualified calls with named arguments and a default `at: :as_of`. The new portable
lints run in TS, Kotlin and Python. English/Czech prose distinguishes `whose` subkinds,
`while` phases, episodes and projected values. See the [manual](../../../docs/manual/en/20-conceptual-model.md).

`assertion.v1` field 1 is now `concept`: binary encoding is unchanged; JSON clients
and generated API callers must migrate/rebuild. Claims may name an instant event,
a kind row with validity, or an entity-backed phase episode. Transfer row payloads
are unchanged. Derived phases cannot be asserted as rows.

**Status: CN-P3 complete; CN-P8 in progress.** Published as `org.tatrman:ttr-concept` in the `grammar/v*` bundle (A-CN-1) — first with
`grammar/v0.17.5` — beside `ttr-plan-proto` / `ttr-translator` at the pinned translator release.

## Loading a model

`ConceptService` is built over a `ResolvedCnModel`: the reconciled ttr-metadata `Model` (the cn tier beside the er,
db and automation tiers a definition reads), every `definition:` / `members:` / `filter:` parsed and name-resolved, the
snapshot hash every lower echoes, and the md tier a statement lowers through. A host loads its model root the way Veles
does, once:

```kotlin
val snapshot = FileBasedSource("models", 100, LocalFsStorage(id = "models", rootPath = root)).load()
val model = ModelReconciler(ModelDescriptor(id = "models", name = "models")).reconcile(listOf(snapshot)).model
val cn = ResolvedCnModel.of(model, md = MdRepo.loadFrom(root)) // snapshotHash defaults to the model version
val svc: ConceptService = CnConceptService(cn) // clock: Clock = Clock.systemUTC()
```

`md` is null when the root declares no cubelet (`statementLower` then refuses). The clock gives "today" only where a
frame names no day (a statement without `as_of` whose phase reads `:as_of`, an md rendering without `asOf`); pass a
fixed one to pin it.

## The three families (contracts §3)

| call | returns |
|---|---|
| `statementValidate(s)` | `cn/*` findings: references, ambiguity, missing time, reachability per consumer |
| `statementLower(s)` | `LowerResult` — the `plan.v1`, the md dot-path it came from, the provenance (binding paths, inlined definitions, parameters bound, as-of, grammar and catalog versions, snapshot hash) |
| `statementExplain(s, locale)` | `Explanation` — the sentence in `en` or `cs`, a phase filter's definition in words |
| `assertionValidate(a)` | `cn/*` findings — everything `assertionLower` would refuse |
| `assertionLower(a)` | `RowObjects` — the event's row (`entity`, attribute → value text), `assertedAsOf`, `meta`, `obligations` |
| `assertionExplain(a, locale)` | `Explanation` — the inspector's line |
| `definitionRender(phase, layer, dialect, options)` | `Rendering` — the text, the placeholders it reads, the definitions it inlined |
| `materialize(phase, dialect)` | `ModelText` — a `def query` + the entity stacked on it, as model text (CN-D1) |
| `hygieneReport()` | `HygieneReport` — grade, `via`, groundings, hop, redundant terms, definitions |

A frame the service cannot serve is refused with error-grade findings (no plan, no rows, no text), never an
exception; every lower calls its validate first.

- **Statements** bind each slot through its `cn2md` grounding, inline phases transitively (rendered on md) and bind
  `:name` from the world (R-cn-7) and `:as_of` from the frame.
- **Assertions** lower to one row, the event's (A-CN-47…49): FK values only — a participant carries a resolved member;
  a two-hop participant becomes an `Obligation` the writer checks; instants are written as the frame writes them (cn
  never converts), an imprecise stored upper bound as the next granule; an absent end is NULL with its end state in
  `meta`. **cn never writes**: the payload is data (`PuritySpec`: no JDBC, no DML in this module).
- **Explanations** (A-CN-51…54) come from the frame, never from a lowering; labels by locale label → locale lexicon
  term → English label → the name in words; a definition the verbaliser cannot say reads as its source in quotes; a
  refused frame is still explained and ends `— refused: <slug> (<subject>)`. Any other locale reads English with
  `cn/explain-locale-fallback`.

## Rendering definitions

`RenderOptions`:

| field | default | meaning |
|---|---|---|
| `mode` | `PREDICATE` | `PREDICATE` — `SELECT <bearer key> FROM <bearer> WHERE …`; `CONTEXT` — the bearer joined to `candidates`, sub-terms lifted to joins and projected (AU's recipe islands, A-CN-20) |
| `placeholderStyle` | `CURLY` | `{name}` (the translator's) or `COLON` — `:name` (AU's islands) |
| `asOf` | null | the evaluation instant; null = today on the clock (an er `latest(…, by:)` is then unbounded) |
| `candidates` | null | CONTEXT: the phase whose bearer keys the context (H-1's `cn.PendingOrder`) |
| `columns` | `[]` | what the projection carries after the keys: a participant, a grounded quality, a defined quality |

The er text follows the formatting contract (A-CN-42) byte for byte. A caller translates it over **`ErModelHandle`**
(public since S3.8, A-CN-55) — the model's er and db tiers and their `er2db` mappings as the translator's
`ModelHandle`:

```kotlin
val phase = ConceptRef.newBuilder().setQname("cn.ShortLine").build()
val r = svc.definitionRender(phase, Layer.ER, "postgresql", options)
val plan = Translator(ErModelHandle(model)).parseToRelNode(r.text, Language.SQL,
    targetSchema = SchemaCode.DB, sourceSchema = SchemaCode.ER, parameters = /* r.parametersRead, bound */)
```

That is the whole of AU's need (AM-31): a scratch project on the published jars renders `cn.ShortLine` in CONTEXT mode,
gets the golden's text, and its ER-lane plan equals the golden's and AU's hand-written island's. The handle carries no
saved-query bodies, so an entity stacked on a logical query — what `materialize` writes — does not translate over it
(the translator reports `saved_query_body_missing`); a host that serves materialized phases supplies its own handle
with the bodies (P5's door).

## Guarantees, tested

- **Deterministic** (`DeterminismSpec`): every golden's whole result is byte-identical twice over one model and over an
  independent reload whose files load shuffled and whose every map is re-inserted shuffled; a frame that fixes its day
  never reads the clock.
- **Pure** (`PuritySpec`, over the compiled classes): no `java.net`, file I/O, resource read, `getenv` or process
  outside `cli/`; no wall clock but the injected one, no randomness.
- **No LLM** (`verifyNoLlmDependency` in `check`, `NoLlmDependencySpec`): no LLM, gateway, kantheon or HTTP-client
  artifact on the runtime classpath.

## Goldens

`src/test/resources/goldens/` — the acceptance cases, authored before the code (CN-P3 S3.7), each expectation from an
oracle independent of the code under test ([format](src/test/resources/goldens/README.md)). All of them run in `check`.
Sixteen wait on a primitive that does not exist yet, pinned with their demand (`ConceptGoldenSpec.PENDING`): G-ER-1 and
G-ER-2 (#201), G-MD-1/2/6, `md/hop` (⚑CN-P8), CN-P5 S5.3. The `live` cases also run on PostgreSQL (`ConceptLiveSpec`,
22 cases, a `cn_live` schema of their own) — in CI in `conformance-ttrp.yml`, locally with
`TTR_CONN_ERP_PG=postgresql://user:pass@host:port/db`.

## The CLI

```
ttr cn hygiene <model-dir> [--format text|json] [--fail-on error|warning]
ttr cn materialize <model-dir> <cn.Phase> [--dialect postgresql] [--out <dir>]
ttr cn render-types --model <model-dir> --entities review,claim --out <pack-dir>
```

From the ttr-core repository: `just cn-hygiene …` / `just cn-materialize …` / `just render-types …` (paths relative to
the repo root). The manual's chapter 20 has the first two.

## Extraction types (`org.tatrman.concept.extract`)

`SchemaRenderer` renders an er entity's document-extraction annotation types as JSON Schema 2020-12 —
`Statement[<entity>]` (one extracted assertion: `spans`, `verbatim`, `concept`, `attributes`, `modifiers`,
`confidence`) and `Record.<entity>` (the consolidated, gold-feeding shape: `attributes`, `modifiers`, `evidence` with
roles, `history`, `conflicts`, `consolidationRun`) — from the model, never by hand. Attributes are typed from the er
(keys excluded; a decimal's scale as `x-ttr-scale`, not `multipleOf`; a single-column relation join — a `join:` pair,
or an fk-only relation's FK read through the `er2db` bindings — becomes a `Ref`; a closed vocabulary an `enum`, a
data-backed one `x-ttr-vocabulary`); `modifiers` are the attributes behind the grounded concept's qualities tagged
`tags: ["modifier"]`, its own or inherited, that land on the entity. Entities are named `review` or
`er.entity.review`; the identities are bare, so a name two entities share is refused. `TypeRegistryRenderer.renderPack`
writes `types/generated/Statement.<e>.schema.json`, `Record.<e>.schema.json` and an `index.json` (each file's sha256,
and the model's content hash — `cli.ModelContentHash.of(<model root>)`, the same on every clone). Pure,
deterministic, canonical JSON (`SchemaJson.canonical` = `json.dumps(sort_keys=True, indent=2)`: keys in code-point
order, no exponent numerals; `src/test/resources/canonical.sample.json` pins the bytes). Designed in the DX effort
(`project/kantheon/features/hermes/`, outside this repo); goldens in `src/test/resources/worlds/hartland-dx/expected/`.

**Law order.** This module implements `project/tatrman/features/conceptual/` (outside this repo): `design/design.md`
v2 and the decision log › `plan/contracts.md` (§3 is this module's API) › `plan/architecture.md` › `plan/plan.md` ›
the task lists. Where this code and those documents disagree, the documents win.
