# TTR-P — Architecture (v1)

> **Status:** consolidated 2026-07-04 from the design effort recorded in [`../design/00-control-room.md`](../design/00-control-room.md) (decision log = ground truth; this doc is the readable synthesis). Companion: [`contracts.md`](./contracts.md) (wire contracts, file formats, schemas) and [`../implementation/v1/plan.md`](../implementation/v1/plan.md) (phased plan).
>
> Read this before making non-trivial changes to anything TTR-P. Decision IDs (`B-T9`, `C3-b`, `S15`, …) reference the control-room log.

---

## 1. What TTR-P is

**TTR-P** is the processing language of the Tatrman (TTR) family: an imperative-dataflow language for multi-engine table transformation programs. One program compiles to **one graph** and executes across **multiple engines** (v1: Postgres + Polars, orchestrated by generated bash) producing **identical results** wherever a computation lands (A4).

The family split: **TTR-M** models data (declarative `def <kind> {props}`); **TTR-P** processes it and *references* TTR-M models (A2 — sibling language, not a sixth model code). Runtime platforms (Kantheon) consume TTR-P's **compiled artifacts**, never its compiler as a service (G-g, with the one C4-e seam: agents may also *author* source via published toolchain artifacts).

### 1.1 v1 scope

- **Hero scenario (A5):** accounts (Postgres) + sales (CSV via Polars) → join → aggregate → branch on threshold, with an error path; DB work in SQL, file work in Polars, movement synthesized between. One program, two engines, one graph. An er-flavored variant exercises the logical tier (D-a).
- **Success (A4):** the hero authored in ≥2 surfaces → one graph → executes across ≥2 engines → identical results (verified by `ttrp-conform`, Q9).
- **Personas (A1):** data engineer + data analyst.
- **Out of v1 (A3/A3-bis and later decisions):** writes beyond `store` (no DML sinks), streaming/CDC, incremental refresh, UDFs, window functions, Explode/Unnest, FF control edges (F-b), retries/resume (F-e), runtime parameters, the optimizer (Z), md-tier references (D-h deferred), the Kantheon orchestrator (F proper).

### 1.2 Design principles

- **P1 · Small core, rich edges.** Minimal node set; expressiveness lives in surfaces and transpilers.
- **P2 · No miracles.** Everything explicit or deterministically derived from project defaults; a miss is an error, never a guess. No content sniffing, no heuristics, no LLM anywhere in the compiler.

---

## 2. Surface architecture — tiered at the container (C0-γ)

```
                    ┌──────────────────────────────────────────────┐
                    │           one internal graph (B)             │
                    └──────────────────────────────────────────────┘
   full coverage           ▲                ▲                ▲
   ┌────────────────┐  ┌───┴────────────┐   │   read/write via ttrp/*
   │ canonical text │  │ graphical      │   │  ┌─────────────────────┐
   │ .ttrp (C3)     │  │ canvas (C1)    │   │  │ LLM assist / agents │
   └────────────────┘  └────────────────┘   │  │ (contracts only,C4-d)│
   container content only                   │  └─────────────────────┘
   ┌──────────┐ ┌────────────┐ ┌─────────┐  │
   │ TTR-SQL  │ │ TTR-pandas │ │ TTR-B   │──┘   three fragment
   │ """sql   │ │ """pandas  │ │ """ttrb │      dialects, one
   │ .ttr.sql │ │ .ttr.py    │ │ .ttrb   │      regime (C2/C4-a)
   └──────────┘ └────────────┘ └─────────┘
```

- **Canonical flow-DSL** (`.ttrp`) is the file format and full-coverage language: γ-hybrid statements (chains `->` + SSA assignment, C3-a), named-only multi-in (C3-c), closed containers with program-level wiring (C3-d-iii), keyword control edges `after`/`with` (C3-e; `finishes with` reserved), two error ports `err`/`rejects` (C3-f), synthesized movement on cross-engine edges (C3-d-iv). No `program` header — identity is the filename (S12).
- **Three fragment dialects, one regime (C2, C4-a):** container content via tagged blocks (`"""sql`, `"""pandas`, `"""ttrb`) or bare files (`.ttr.sql`, `.ttr.py`, `.ttrb` — valid programs via wrapper synthesis from `[ttrp]` defaults). All three: decompose fully into the standard node set; document scope flows in (in-ports > imports > qnames); `err` only, single default-out; interiors formatter-untouchable; own ANTLR grammars. **TTR-SQL** = one `WITH`+`SELECT` query expression (CTE names = SSA labels — E-b's inverse); **TTR-pandas** = method-chain over the op vocabulary ("dataframe-shaped TTR-P"); **TTR-B** = controlled sentences (Byx evolved) with a verbose expression skin (closed synonym table over the one expression grammar, C4-c).
- **Graphical surface (C1):** two-level canvas (orchestration view = container-collapsed derived execution graph + program leaves; drill-in recurses), pluggable **skins** per canvas, binary auto/manual layout, view state in the `.ttrl` sidecar (ζ SSA-name keys), β minimal edit vocabulary through formatter-owned `WorkspaceEdit`s over `ttrp/*`.
- **Assist layer (C4-d):** the toolchain ships `ttrp/authoringContext` + `ttrp/validate` (deterministic); the LLM call lives at the host. Generated text is never applied silently. Agents (Q1) author canonical TTR-P through the same pair.

**Text is canonical** for everything (G-e as amended): the graphical surface edits text through the LSP; layout lives in the sidecar, never the program file (C3-h).

---

## 3. Internal model (workstream B)

One acyclic graph per document (T4). No logical/physical program split — ops sit on a **physicality spectrum** and are rewritten toward executability (B-T9).

- **Nodes (T10):** Project (sugar: Select, Calc), Filter, Branch, Switch, Join (incl. semi/anti types), Aggregate (HAVING sugar; `AggregateCall` distinct arm), Sort, Union, Intersect, Except, Values, Limit, Pivot (static-declared), Distinct (sugar→Aggregate); movement/IO: **Load, Store, Transfer** (generalized — delivery is an invocation binding, E-g), Index; **Container** (function-shaped, port-mapped, bears the execution target in v1 — author-assigned, no auto-placement); **Display** (sink-only leaf, dynamic-schema exception, Q11). Materialize = macro, not surface syntax (S13).
- **Ports (T2):** typed (data|control), named, default port; multicast out; no implicit union. Two reserved error ports per node: `err` (signal) + `rejects` (rows). Port schemas fully static (T7); schema-on-read banned; ad-hoc files need declared schemas (D-c).
- **Control (T2, F-b):** v1 vocabulary = **FS + SS** (FF dropped from v1; grammar keyword reserved). Constraints are hard on their effect. Cross-container data edges imply FS at collapse.
- **Primitive-vs-macro is engine-relative (T10):** one node set; per-engine capability manifests (T6, parameterized declarative entries); two rewrite kinds — authoring sugar (engine-independent) and capability lowering (engine-relative). Expression-level misses escalate at node granularity (T5-b): split-with-warning default, `[ttrp]` policy knob.
- **Expressions (T5):** own IR twin of `plan.v1.Expression`; one expression grammar across all surfaces (T5-e; TTR-B's verbose skin and TTR-pandas's `==` are closed synonym tables over it, C4-c/S9); catalogue function ids (T5-c); explicit Cast; **canonical SQL 3VL NULL everywhere** — engines that differ get enforcing codegen (forced by A4).
- **Variables (Q7-γ):** sugar for named edges, SSA reassignment; names survive as labels → CTE names (E-b), ζ canvas keys (C1-c), diff stability. Data only, never containers.
- **The world (T6/T4, D):** a TTR-M document (`schema world`: `def world` / `def engine` / `def executor` / `def storage`, instance `extends` type manifest, storage `hosts:` model packages, one `staging: true`). The world is a **compile target**; runtime environments *verify compatibility*, never define it. Worlds live in the model repo (S22); selection via `[ttrp] world` + optional `uses world` pin.
- **Two layers, derived not authored:** the execution/orchestration graph is container-collapse of the one graph (B-T6). Engine taxonomy: data engines vs execution engines, both manifest-described; **invocation bindings** pick the delivery channel per (data engine, executor) pair.

## 4. Compile pipeline

```
parse (.ttrp + fragment grammars)          er refs → db refs, provenance kept (E-d)
  → attach trivia (fragments verbatim)     sugar expansion (Select/Calc/HAVING/Distinct/…)
  → resolve names                          capability normalize: native? rewrite? escalate (T5-b/T8)
    (imports/qnames, position-typed D-b;   node fission + author-assigned placement check
     models via ttr-metadata; world)       movement synthesis (Store+Transfer+Load, staging D-f)
  → build graph (SSA)                      → emit islands + bundle (E, F-lite)
```

All offline: the compiler embeds the metadata component and reads the model repo + world doc from paths in project defaults (D-g). Deterministic throughout (P2); T8 termination measure and fission rules are named compiler work items.

## 5. Emit & execution (E, F-lite)

- **SQL islands:** CTE-per-node, SSA names as CTE names, trivial islands flatten (E-b). Dialect v1 = Postgres. When a container's target is a Kantheon world, the invocation binding delivers `plan.v1` PlanNodes instead — **world-driven** (E-a); translation via **`org.tatrman:ttr-translator`** (the Proteus core, extracted from kantheon — the metadata/Ariadne pattern; `plan.v1` proto vendored at extraction, S25).
- **Dataframe islands:** straight-line Polars script + generated inline prelude (only needed enforcement helpers; dependency-free; SSA names carried) (E-c).
- **Bundle (F-f):** `<program>.bundle/` (S1) — `run.sh` + `manifest.json` + `islands/` + `transfers/` + `schemas/` + `plans/`; sha256 per file; semantic world fingerprint (record; capable invokers verify). Runtime dirs `logs/`/`staging/`/`out/` created, wiped on restart (F-e).
- **Execution v1 = bash (F-a):** wave-parallel (`&` + pid-checked `wait`, `wait -n` early abort), FS = wave order, SS = co-launch; fail-fast (`set -euo pipefail`, exit 0/1/2); credentials via **`TTR_CONN_<NAME>`** env vars only; **Arrow IPC at every staging boundary** = Q9's fingerprint format. Invocation bindings v1: pg×bash = `psql -v ON_ERROR_STOP=1 --no-psqlrc -f`; polars×bash = `python3`; display×bash = file drop `out/<name>.<fmt>`.
- **Equivalence (Q9):** the seven-point procedure enforced by **`ttrp-conform`** (S3) over Arrow IPC exports — also the emit regression suite.
- **RLS stance (Q8):** trusted principal + tripwire (`rls: true` storage → egress warning; `[ttrp] rls-egress = warn|error`).

## 6. Component architecture (G)

Two build domains, as TTR-M already has: Gradle/Kotlin (compiler, LSP, Designer server) and pnpm/TS (Designer frontend, VS Code shim). **TTR-P is Kotlin-only** — no KMP, no TS parser, single parser, no cross-target conformance burden (G-b).

| Component | Form | Role |
|---|---|---|
| TTR-P grammars | `TTRP.g4` + `TTRSql.g4` + `TTRPandas.g4` + `TTRB.g4` (+ `.ttrl` grammar hosted in TTR-M's parser) | canonical sources beside `TTR.g4`; antlr-ng/Kotlin generation only (C2-g/C4) |
| Compiler front-half | Kotlin lib | parse → resolve → graph → normalize (T8); embeds **ttr-metadata**; serves `ttrp check`/`validate`/`authoringContext` |
| ttr-metadata | Kotlin lib (extraction from kantheon's Ariadne — feature `docs/ttr-metadata/`, approved 2026-07-05; core + `-git` artifacts; in this repo from its Phase M1) | TTR model graph + queries; world resolution |
| ttr-translator | Kotlin lib (Proteus core, extraction arc) | relational island → RelNode → dialect SQL / `plan.v1` payload (Calcite lives here only) |
| Emit + bundle | Kotlin lib | island codegen, movement synthesis, bundle assembly |
| `ttrp` CLI | Kotlin binary (S2) | `build` / `run` / `explain` / `conform` |
| TTR-P LSP | Kotlin, transports **stdio** (VS Code, IntelliJ) + **WS** (Designer server) | one LSP across hosts; standard methods + `ttrp/*` |
| Designer server | JVM, repo-attached (G-b) | hosts WS-LSP; serves the Designer; v1 loopback-only, no auth (S24); **TTR-P-only in v1** — TTR-M converges with its `.ttrl` migration arc (C1-f) |
| Designer frontend | TS/React (fork of the TTR-M designer stack) | thin WS client; canvas, skins, property panel |
| vscode-ext | TS shim | language registration + LSP client; no business logic |
| `ttrp-conform` | Kotlin module | Q9 harness; reads manifest, runs bundle, compares Arrow |

The dependency arrow between repos is one-way: kantheon consumes `org.tatrman:*` artifacts (ttr-parser/writer/semantics/translator + the TTR-P libs as published); tatrman never depends on kantheon artifacts (S25 keeps `plan.v1` vendored, not consumed).

## 7. Graphical & view-state architecture (C1)

Two-level viewing (orchestration + drill-in, recursing); **skins** (built-in v1 roster: "Alteryx/KNIME" icons, "Enso" text-nodes; per-skin edge orientation; per-canvas, family-generic field); binary auto/manual layout (auto persists nothing; derived fragment sub-canvases auto-only, read-only). View state in the **`.ttrl` sidecar** (same name, different suffix; committed shared truth; TTR-M-hosted grammar; inventory: per-canvas {key, skin, mode, manual nodes with ζ keys, collapsed}; no viewport). Node identity = **ζ SSA-qualified name keys** with atomic pair rewrite on rename and deterministic orphaning (never mis-attach). Editing = β minimal vocabulary (hero buildable on canvas); text placement formatter-owned; LSP document versioning for concurrency. Run = `ttrp/run` shells out to the bundle; display sinks land as Arrow files in `out/`; the Designer watches and renders (C1-e; streaming = v2).

## 8. Versioning (S6)

TTR-P **spec version** = integer, cut through the existing grammar-master process (`docs/grammar-master/`); Maven artifacts semver per `PUBLISHING.md` (tag-driven); the bundle `manifest.json` records `ttrpVersion` + toolchain version; `.ttrl` carries its own header version. No second versioning mechanism.

## 9. Testing strategy

- Unit + component per package (Kotest, mirroring the TTR-M Kotlin conventions).
- **Golden emit corpus:** per-dialect snapshot tests of emitted SQL/Polars (work item — home decided in the plan).
- **`ttrp-conform`:** result equivalence across engines (Q9's seven points); doubles as the standalone-vs-Kantheon drift guard.
- **Diagnostics tables:** every rejected form (dialect rejects, out-of-roster sentences, `==`, abbreviations) has a named diagnostic with a suggested alternative — the tables are test fixtures and the assist layer's repair vocabulary.
- **Assist/agent eval corpus:** NL request → expected graph shape; `ttrp-conform`-adjacent.

## 10. Deferred register (v1.x / v2 heads-up)

FF + orchestrator proper (F), events/loops, runtime parameters, window functions, Explode, md-sugar (D-h), streaming display, semantic zoom, skin authoring format, multi-user Designer server + auth, TTR-B localization (verb + expression tables together, S20), world-extends-world (S21), TTR-M Designer-server convergence + `.ttrl` migration (one arc, C1-f), optimizer (Z).

**Shipped since:** *the `entry` stdlib* — **implemented** (EN arc, EN-P0…P6, sealed 2026-07-23; docs in `project/tatrman/features/ttr-p/implementation/entry/`). The post-v1 row-entry write path: md `change-semantics`/`management` declarations + the §7 writability classifier; the six `entry` verbs with compile-time md-derived lowering (`ENTRY_LOWERING`) → deterministic parameterized-PG-SQL apply plans run by the FO-P2 door; the review-003 F3 (`-rev<n>`/`-rep<n>`) + F4 (identifier case / typed binds) fixes; §10 optimistic guard + md5 version-advance (⚑EN-5); and `call-fn` (pure, deploy-resolved, entry-record-pinned) against the FO-P4 `CanonFunctionRegistry` seam. Generated programs proven drop-in behind the `ApplyProgram` seam (`foP2Dod` green). Unblocks FO-P4 S3.T3. *erroneous-rows producer semantics* — **implemented** (design/rejects arc, RJ-P0…P6; live-sealed PG↔Polars 2026-07-19). Catalogue-defined canonical validity + a guard-and-branch elaboration stratum; SQL rejects = one more terminal SELECT, Polars = mask-and-split; conform grows an eighth (partition) point. This **unlocks the parked fragment reject taps (C2-e-β)** — that revisit is now gated open, not yet acted on. SQL Server target parked ([#44](https://github.com/Collite/ttr-core/issues/44)).
