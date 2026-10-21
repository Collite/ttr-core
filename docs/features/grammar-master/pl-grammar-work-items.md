# Grammar-master work items — Platform (PL) batch

Status: **filed 2026-07-19 (PL-P0.S1)** · Source of truth for scope/DoD: `project/platform/design-corpus/implementation/plan.md`; shapes: platform [`contracts.md`](../../../../project/platform/design-corpus/design/contracts.md).

Four `TTR.g4` grammar surfaces are **reserved** by the PL-P0 S1 amendment batch. Each is **additive** (new optional construct / sugar → minor bump, `@grammar-version` `0.9 → 0.10` when the first lands). **Specs are recorded here now; implementation lands in the phase that consumes each** — the grammar-master process (`new-grammar-version-process.md`) is followed per surface when it is implemented, not now. This is the filing the plan's PL-P0 DoD requires and PL-P0.S2.T5 confirms.

| # | Surface | Family | Owner (lane) | Target phase | Kind |
|---|---|---|---|---|---|
| 1 | `params` (runtime params, F-4-i) | TTR-P | senior2 | **PL-P2**.S1 | additive minor |
| 2 | `on-failure` island vocabulary (F-4-iv) | TTR-P | senior2 | **PL-P2**.S1 | additive minor |
| 3 | K `extends`-platform-world (world composition) | TTR-M | senior2 | **PL-P1**.S4 | additive minor |
| 4 | `security` block (H-1 reservation) | TTR-M | senior2 | **PL-P4**.S3 | additive minor |
| 5 | the conceptual-model definition forms — `TTRP.g4` 0.4 → **0.5** (CN-D29) | TTR-P | senior2 | **CN-P2**.S2.6 | additive minor — **IMPLEMENTED** (§5) |

The manifest **schema** already permits #1/#2 (see TTR-P [`contracts.md §5.1`](../ttr-p/architecture/contracts.md#manifest-v2)); these items cover the *grammar* that makes the toolchain **emit** them.

---

## 1. `params` — runtime params (F-4-i) · ~~lands PL-P2.S1~~ **IMPLEMENTED (PL-P2.S1, 2026-07-21)**

Trigger-time, declared-and-typed params on a TTR-P program. Types `{string, int, decimal, date, datetime, bool}`, binding trigger-time, builtin `run-date`. Compiles into manifest `params[]` (name/type/required/default) and `island.params[]`. Legal only against a world whose executor-type manifest declares `params` (else T6 compile error — §5.2). Determinism: params are declarations, not values; the bundle stays a pure function of resolved inputs (values arrive at trigger time via the envelope, not the artifact).

> **Implemented 2026-07-21 (PL-P2.S1).** Surface (finalized here, grammar-master process): **`TTRP.g4`** (NOT `TTR.g4` — params are a TTR-P construct; TTR-P is Kotlin-only, no antlr-ng/TS target) grows a program-level `paramDecl : PARAM identifier COLON typeName (ASSIGN paramDefault)?` and a `BUILTIN : '@' [a-zA-Z][a-zA-Z0-9-]*` token so `@run-date` lexes as one builtin name (the `@` sigil is collision-free). `@grammar-version 0.1 → 0.2` (additive minor). Diagnostics `TTRP-PARAM-001..003` (bad type / duplicate / builtin-on-non-date). The executor gate lives in **ttrp-graph** (`ExecutorManifestGate` + `ExecutorCapability` on the EXECUTION manifest; ships `tatrman.json`) → `TTRP-CAP-201`. Per-island `params[]` is derived by whole-word match of declared names in the rendered island payload (uniform for canonical + opaque SQL/py). The v2 JSON Schema already permitted `params` (no schema change).

## 2. `on-failure` island vocabulary (F-4-iv) · ~~lands PL-P2.S1~~ **IMPLEMENTED (PL-P2.S1, 2026-07-21)**

Island-scoped on-failure edges: an island declared to run **iff** a named source island failed. Compiles into manifest `island.onFailureOf` (+ `retries`, transient/permanent classification, wave-resume guarded by snapshot fingerprint). `absorbs` stays reserved (false in v1). Legal only against a world whose executor manifest declares `onFailure`/`retries`/`resume` (else T6 compile error).

> **Implemented 2026-07-21 (PL-P2.S1).** Surface: container-level clauses after `target <qname>` — `containerAttr : ON FAILURE OF identifier ABSORBS? | RETRIES INT`. `on` is a **soft** keyword (also in `identifier`) because the hero writes `join(…, on: …)`; `failure`/`of`/`retries`/`absorbs`/`param` are hard keywords. An on-failure island is excluded from `computeWaves` and carried as `island.onFailureOf` (an error edge). Diagnostics `TTRP-FAIL-001..003` (unknown island / on-failure cycle / `absorbs` reserved). Executor gate → `TTRP-CAP-202` (on-failure) / `TTRP-CAP-203` (retries).

## 3. K `extends`-platform-world — world composition · ~~lands PL-P1.S4~~ **SURFACE ALREADY EXISTS**

> **Amended 2026-07-19 (PL-P1.S4 re-validation):** the world-level `extends` **grammar surface already
> exists** — `worldProperty : … | extendsProperty` and `id : idPart (DOT idPart)*` (so a dotted
> platform-world qname parses), populating `WorldDef.extends`. **No `TTR.g4` change / antlr regeneration
> is needed.** The value is a **bare dotted id**, NOT a quoted string (the PL-P0 sketch below showed
> `"quoted"` — that is wrong; `world-negative/neg-05-extends-string` confirms strings are rejected).
> PL-P1.S4 implements the **composition semantics** (`WorldComposer`), not the grammar.

A TTR-M world document may declare, at `def world` level (bare dotted id):

```ttrm
def world dev {
    extends: acme.platform.prod        # platform-world qname (bare dotted id); platform is AUTHORITATIVE
    # project may ADD members and EXTEND (non-contradicting) declarations
}
```

**Composition semantics** (platform [`contracts.md §16`](../../../../project/platform/design-corpus/design/contracts.md) + the C-6/K rule):
- The platform world is **authoritative**; the project world **adds** objects and **extends** non-contradicting declarations.
- A **contradiction** with the platform world is a **compile error** (`TTRP-LCK-004` at lock/resolve time — platform §21).
- Composition is fed into the **fingerprint** and must be **order-insensitive**; contradiction detection is exact (property-test target — plan Risks).
- Veles serves the **RESOLVED composed world** as TTR text, so standalone re-resolution of the archive is the identity (`snapshot.json.resolvedFrom` is informative only, never hashed).

Consumed by: the ttr-metadata `WorldResolver` composition (PL-P1.S4) and `ttr.lock` (`TTRP-LCK-004`).

## 4. `security` block (H-1 reservation) · lands PL-P4.S3

TTR-M grammar reservation (shape only — exact grammar lands via this process at PL-P4):

```ttrm
security {
    own       sales: team_sales                     # ownership declaration
    classify  order_line.customer_email: pii        # classifications = native grant vocabulary (HQ-1)
    grant     read on sales to accounting           # role- or classification-mapped grant
    mask      order_line.customer_email             # column mask (default: all; policy maps exceptions)
}
```

Invariants the reservation pins:
- **Fingerprint-neutral:** `security` blocks are **excluded** from the world/T6 semantic fingerprint (access changes must not churn world verification) and never alter emitted plans.
- **Generator is one-way + deterministic (MIT):** `security` → Rego fragments + structured data, package `tatrman.generated.<sanitized-qname>`, `# GENERATED — do not edit` header, same input ⇒ same bytes. Never hand-edited.
- **Composition = deny-overrides:** sugar grants; hand Rego can always take away. Row-level predicates stay Rego-side in v1.
- Verbatim role names legal; classification→role mapping is org policy data; **unknown roles fail closed at bundle build** (Perun, platform §19), advisory lint standalone (H-8).

Consumed by: the MIT generator + Perun bundle pipeline (PL-P4).

## 5. The conceptual-model definition forms — `TTRP.g4` 0.5 · **IMPLEMENTED (CN-P2 S2.6, 2026-10-20)**

A cn `definition:` / `members:` / `filter:` block (TTR-M 0.17, an `embeddedBlock`) is parsed with the TTR-P
expression grammar (CN-D28 ⚑CN-P5), so the forms it needs and TTR-P lacked join `TTRP.g4` as the additive 0.4 → 0.5
cut (CN-D29: where 0.4 already spells a construct its spelling wins — `:as_of`, `:name`, `hours(24)`,
`within(x, last: d)`, `<>`). Spelling table: `project/tatrman/features/conceptual/plan/contracts.md` §1.1 "The 0.5
spellings" (A-CN-25…31). Surface: `[not] exists C [v] (, C [v])* involving p [as label] (, …)* [where e]`; the concept
aggregate `count|sum|…(C [v] involving … [where …] (, name: e)*)` + `.quality`; `x [not] in <call>`; the temporal words
`at_or_before before contains ended_before overlaps` infix at the comparison level; `this` (+ a path); `end` after a
dot. New tokens `EXISTS INVOLVING WHERE THIS AT_OR_BEFORE BEFORE CONTAINS ENDED_BEFORE OVERLAPS`, all in `identifier`;
eight are soft, `THIS` is not (a 0.4 column named `this` is now `TTRP-CN-001`). Every other 0.4 document parses to the
same AST (proved over the repo's 230 `.ttrp` documents and the expression corpora) — a temporal word followed by `:` in
a document stays a route or key (`temporalInfix()`). A TTR-P document refuses the forms wherever they sit,
`TTRP-CN-001`. With it, `standaloneExpr : expr EOF` — `parseExpression`
anchors EOF (ttr-core#202). Kotlin only (`ttrp-frontend`); the parsed definitions live in `ttr-concept`.

