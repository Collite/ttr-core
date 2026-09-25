# Feature: `semantics { }` block — grammar 4.2

TTR-M language-surface change enabling **deterministic grounding** in ai-platform
(time / geography / money semantic roles on model elements). Approved in the
grounding master-plan session, 2026-07-06 (see ai-platform repo root:
`feature-grounding-{architecture,contracts,plan}.md`; this feature is its Phase 1).
This README plus the six task lists below are the whole tatrman-side plan.

> **Branching:** work in a **separate worktree off `master`** (TTR-P development
> is in flight on `feature/ttr-p-v1-phase2`). Claim grammar **4.2** in the
> CHANGELOG in the first commit. Hard deadline: merged + published before TTR-P
> Phase 5 Stage 5.2 (`.ttrl` grammar lands in ttr-parser there).

## Goal

1. Add a `semantics { … }` block property, attachable to `entity`, `attribute`,
   `table`, `column` (narrower than `search` — widening later is additive).
2. Body is **free-form `object_`** — one new lexer token (`SEMANTICS`), one rule;
   ALL shape/vocabulary checking lives in semantics ("parser stays mechanical").
   New roles therefore need **no future grammar bump**.
3. Surface the validated result on `@tatrman/semantics`, Kotlin `ttr-semantics`,
   and `ttr-metadata`'s typed model; publish; mirror the new catalog functions
   in TTR-P's `BuiltinCatalog`.

### New surface (illustration)

```ttr
def entity AccountingPeriod {
  semantics { kind: period_table },
  attributes: [
    def attribute start_date { type: date,       semantics { role: period_start } },
    def attribute end_date   { type: date,       semantics { role: period_end } },
    def attribute period     { type: varchar(6), semantics { role: period_code, code_format: "yyyyMM" } }
  ]
}

def entity Transaction {
  attributes: [
    def attribute date       { type: date,    semantics { role: event_date, period: AccountingPeriod } },
    def attribute doc_date   { type: date,    semantics { role: document_date } },
    def attribute due        { type: date,    semantics { role: due_date } },
    def attribute amount     { type: decimal, semantics { role: amount, currency: currency_code } },
    def attribute amount_dom { type: decimal, semantics { role: amount_domestic } },
    def attribute currency_code { type: varchar(3), semantics { role: currency_code } }
  ]
}

def entity Poi {
  semantics { kind: poi },
  attributes: [
    def attribute lat { type: decimal, semantics { role: geo_lat } },
    def attribute lon { type: decimal, semantics { role: geo_lon } }
  ]
}
```

## Decisions (from the 2026-07-06 planning session)

| Decision | Choice |
|---|---|
| Body style | Free-form `object_` (world-model 4.1 / `attributesMapProperty` precedent); NOT search-style typed sub-properties. Vocabulary evolves without grammar bumps. |
| Primitive | ⚠ **Superseded in vocabulary v3 — see MS (mention semantics).** As decided in 2026-07-06: *"attribute-level `role` is the primitive; entity/table level carries only `kind` (+ future kind params). No entity-level attribute wiring (single source of truth)."* v3 keeps the first half and reverses the second: an entity/table block now also carries the **mention facet** — `name:`, `code:` and `measures:`, each naming an attribute of that same entity. It is not a second `role` table (`role:` is single-valued, and one column is routinely both an amount to convert and the measure people ask for), and the two facets are orthogonal. The §Vocabulary section below carries v3 in full; `packages/semantics/src/semantics-block/vocabulary.ts` remains the normative table it mirrors. |
| Attachment set | `entity`, `attribute`, `table`, `column` only (db-only period/POI tables supported). `relation`/`query`/`role`/project: NOT attachable in 4.2. |
| Project-level defaults | **Deferred.** Fiscal alignment is derivable: package declares a `period_table` ⇒ table-backed; otherwise calendar-aligned (formats from GroundingContext). |
| Keyword hygiene | `SEMANTICS` added to `idPart` (4.1 `WORLD` precedent) — 4.2 stays honestly additive. |
| Unknown keys / bad values | **Error** (closed vocabulary; evolution goes through ttr-semantics + proto releases anyway). Duplicate keys: error via the `duplicateProperties` walker pattern (search-block precedent). |
| MD coexistence | `semantics` is orthogonal to v3.1 `domain_ref`/`aggregation`; both may appear on one attribute; `semantics` NEVER implies aggregation. |

## Vocabulary (4.2 / ttr-semantics v3)

`packages/semantics/src/semantics-block/vocabulary.ts` is the normative table and this
section mirrors it exactly (the file header says so; keep them true to each other).
`SEMANTICS_VOCABULARY_VERSION` is the cross-repo sync key and bumps whenever a role,
kind, entity key or signature changes — **`vocabulary.ts` ⇄ `Vocabulary.kt` ⇄ the proto
release move in lock-step**, one change each. Current value: **3**.

A `semantics { }` block carries **two orthogonal facets**:

| facet | declared on | answers | keys |
|---|---|---|---|
| **grounding** | attribute / column (`role:`), entity / table (`kind:`) | *what computation grounds on this column* — a date to filter on, a coordinate to measure from, an amount to convert | `role:` + its extra keys, `kind:` |
| **mention** (v3, MS) | entity / table only | *how humans refer to this entity* — by name, by code, or as a value to aggregate | `name:`, `code:`, `code_pattern:`, `measures:` |

They are orthogonal by construction. The mention facet is **not** a second `role:` table:
`role:` is single-valued and one column is routinely both an amount to convert (grounding)
and the measure people ask for (mention). So v3 adds **no role members** — it adds `facet`
and `family` metadata to every existing role, which is what forces a role added later to
state which question it answers.

### Grounding facet — entity/table kinds

`kind:`: `period_table`, `calendar`, `poi`, `fx_rate`.
(`calendar` = pre-materialized date dimension; not present at DF but supported.)

### Grounding facet — attribute/column roles

Every role below is `facet: 'grounding'`. `family` groups them the way this table is
grouped and is a required field on `RoleSpec`.

| Role | Family | Extra keys | Type constraint | Notes |
|---|---|---|---|---|
| `period_start` / `period_end` | dates | — | date | on `period_table` kinds |
| `period_code` | dates | `code_format` (string, default `"yyyyMM"`) | text | |
| `event_date` | dates | `period:` → entity ref (kind `period_table`) | date | **≤ 1 per entity** — THE default query date |
| `document_date` / `posting_date` / `due_date` | dates | optional `period:` | date | secondary dates, NL-targetable ("posted in May", "due in May") |
| `valid_from` / `valid_to` | dates | — | date | generic validity pair (both or neither); reused on fx tables + as an invalidate journal role |
| `calendar_date` | dates | — | date | the day key of a `calendar` kind |
| `valid_flag` | journal | — | *(unconstrained)* | **journal role** (v2) — SCD-2 live flag; satisfies `invalidate` journaling. Semantically boolean, but `TypeConstraint` has no boolean family so nothing is enforced |
| `version` | journal | — | numeric | **journal role** (v2) — monotonic per grain key on write |
| `authored_by` | journal | — | text | **journal role** (v2) — write principal (run identity) |
| `written_at` | journal | — | date | **journal role** (v2) — write clock (not `asof`) |
| `geo_lat` / `geo_lon` | geo | — | numeric | pair required together |
| `geo_point` | geo | — | text | XOR with lat/lon pair |
| `amount` | finance | `currency:` → sibling attribute ref (role `currency_code`) | numeric | |
| `amount_domestic` | finance | — | numeric | grounding's no-FX-join shortcut |
| `currency_code` | finance | — | text | |
| `fx_from_currency` / `fx_to_currency` | finance | — | text | on `fx_rate` kinds |
| `fx_rate` | finance | — | numeric | on `fx_rate` kinds |

The `date` constraint is the date/datetime family; `numeric` and `text` likewise name
families, not single types.

**Kind completeness rules** (validated on the owning entity/table):

- `period_table` ⇒ exactly one `period_start`, one `period_end`, one `period_code` among its attributes/columns.
- `calendar` ⇒ exactly one `calendar_date`.
- `poi` ⇒ exactly one `geo_point` XOR (exactly one `geo_lat` AND one `geo_lon`). The XOR is
  not expressible as a flat count clause, so the validator special-cases it (210) and
  `KIND_COMPLETENESS.poi` is deliberately an empty list.
- `fx_rate` ⇒ exactly one each of `fx_from_currency`, `fx_to_currency`, `fx_rate`; `valid_from`/`valid_to` optional as a pair.

**Journal-role family (v2, `SEMANTICS_VOCABULARY_VERSION` 1 → 2, MD dot-path S5C-B.4):**
the four roles above (`valid_flag`, `version`, `authored_by`, `written_at`) join the
reused `valid_from`/`valid_to` pair as the technical-column vocabulary of a journaled
cubelet's backing table (contracts §12 R30). They carry no kind-completeness clause here;
the *MD-layer* rule "`invalidate` journaling requires `valid_flag` **or** `valid_from`+`valid_to`
on the backing table" is checked in the dot-path frontend (`TTRP-MD-018`), not the
`TTR-SEM-2xx` validator. The shared `AttributeSemanticRole` proto-enum promotion (ids 60–63)
is the cross-repo half.

**Cross-ref resolution:** `period:` resolves like entity refs (binding machinery);
`currency:` resolves like `name_attribute:` (sibling-attribute ref). Diagnostics
when the target is missing, or lacks the required kind/role.

### Mention facet — entity/table keys (v3, MS)

`ALL_ENTITY_KEYS` = `['kind', 'name', 'code', 'code_pattern', 'measures']` (was `['kind']`).
`name:`, `code:` and `measures:` are **optional and independent**; each names an attribute of
**that same** entity (a column of that same table — the block serves er attributes and db
columns uniformly). `code_pattern:` (LP review-103, D4) is optional too, but only **with**
`code:` — it describes the code attribute's values.

```ttrm
entity sales {
  attributes { ... }
  semantics {
    kind: …                          // existing, unchanged (period_table | calendar | poi | fx_rate)
    name: customer_name              // NEW — id ref to an attribute of THIS entity
    code: doc_no                     // NEW — id ref to an attribute of THIS entity
    code_pattern: "^[0-9]{4}-[0-9]{6}$"   // NEW (LP) — a Java regex every code matches; needs `code:`
    measures: [                      // NEW — ordered list; FIRST item = the default measure
      amount_czk,                    //   bare id ⇒ aggregation `sum`
      { attribute: quantity, aggregation: avg },   // item object: attribute + aggregation
    ]
  }
}
```

- A `measures:` item is either a bare id or an object with exactly `attribute` (required,
  id ref) and `aggregation` (optional).
- **Aggregation vocabulary** (closed): `sum | avg | min | max | count | last`; a bare id
  means `DEFAULT_AGGREGATION` = `sum`.
- **Order is preserved and load-bearing**: the FIRST item is the entity's default measure.
- `measures: []` is legal and means exactly what an absent key means — no
  `ResolvedEntitySemantics` measures at all. It is silent: every §Diagnostic code below is
  an ERROR, and an empty list is not a defect.
- An entity block may carry the mention facet and no `kind:` at all — `kind` is optional
  in the resolved model since v3.
- **`code_pattern:`** (LP review-103, D4) is a quoted **Java** regular expression that every
  value of the `code:` attribute matches. It is what lets a quoted literal be recognised as a
  *code* rather than a name — in particular a code with no digit in it (TPC-DS business keys
  such as `AAAAAAAABAAAAAAA`), which the resolver's fallback shape test (`^[A-Z0-9][A-Z0-9\-/.]*$`
  with at least one digit) accepts only on a head that declares a `code:` and no `name:`. It is
  compiled at analysis time with `java.util.regex.Pattern` (the JVM is where the resolver
  matches it); a pattern that does not compile, an empty one, or one in a block with no
  `code:` is `TTR-SEM-219`. Prefer character classes to backslash escapes (`[0-9]`, not
  `\d`): a backslash inside a TTR string is an escape character to the parsers, so `\d` must
  be written `\\d`. The lexicon compiler carries it to the resolver as the archive's
  `TargetFacts.codeFormat` — which, when no `code_pattern:` is declared, holds the code
  attribute's period `code_format:` mask translated to a regex (`yyyyMM` → `^\d{6}$`).
  No vocabulary-version bump: the version moves with the closed proto enums, and a free-text
  key adds no enum member.

> ⚠ **Three different `aggregation:` surfaces, deliberately kept apart.** The one above is
> the aggregation *of a measure*, declared where the measure is declared. It is NOT the
> def-level `aggregation:` attribute property (which says an attribute is DERIVED by an
> aggregation — EN-P1.2), and NOT md's measure `aggregation:` property. Same word, three
> meanings, three surfaces; `semantics` still never implies aggregation for md.

**Legacy entity properties — deprecation, not removal.** `nameAttribute: x` /
`codeAttribute: x` keep parsing:

| declared | outcome |
|---|---|
| legacy only | works as today + **WARN** `SemLegacyMentionDeprecated` (suggests the semantics key, and says quoting reads it only as a fallback) — the lexicon compiler falls back to it for the quoted-literal facet (`TargetFacts.nameRef`/`codeRef`, review-103 F16) |
| semantics only | the new source of truth |
| both, agreeing | **WARN** `SemLegacyMentionDeprecated` (redundant) |
| both, disagreeing | **ERROR** `SemLegacyMentionMismatch` — a disagreement is always a bug |

The comparison is **owner-scoped**: the semantics side is always a bare local id, the legacy
side is a `Reference` that may be written qualified, so last segments are compared — but only
when the qualifier is the owner itself. `nameAttribute: Other.customer_name` against
`semantics { name: customer_name }` is a MISMATCH, not a repeat. The matrix is evaluated
**independently of whether the rest of the block validated**: the legacy properties are their
own surface, and an unrelated error elsewhere in the block must not switch all four rows off.

**Derived mention kind.** Nothing in the model declares a mention *kind* — it is derived,
by exactly one table (`MentionKinds`, in `ttr-semantics`, with a TS mirror kept honest by a
parity test) from declared facts alone, never from a ref string:

| the object is | ⇒ kind |
|---|---|
| an attribute listed in its owner's `measures:` | `measure` |
| any other attribute/column | `attribute` |
| an entity/table whose `measures:` is non-empty | `entity_with_measures` |
| any other entity/table | `entity` |

The values travel as **strings** and consumers tolerate unknowns; downstream, the resolver
reads them (frame roles, governed-value gating) and never re-derives them.

**Diagnostic codes:** `TTR-SEM-2xx` range. Grounding facet: 200 unknown key, 201 unknown
role, 202 unknown kind, 203 duplicate key (including a repeat inside a nested object,
reported with its dotted/indexed path, e.g. `measures[0].attribute`), 204 kind on attribute /
role on entity, 205 type-constraint violation, 206 completeness violation, 207 multiple
`event_date`, 208 dangling/miskinded `period:` ref, 209 dangling/roleless `currency:` ref,
210 geo pair violation, 211 valid pair violation. Mention facet (v3): 212
`SemMentionRefUnresolved` (a `name:`/`code:`/measure `attribute:` that is not an attribute of
THIS owner), 213 `SemMeasureNotNumeric`, 214 `SemMeasureDuplicate`, 215 `SemBadAggregation`,
216 `SemMentionShape`, 217 `SemLegacyMentionMismatch`, 218 `SemLegacyMentionDeprecated`, 219
`SemBadCodePattern` (LP review-103: a `code_pattern:` that is not a Java regex, is empty, or has
no `code:` beside it). Each carries a suggested alternative where meaningful
(closed-vocabulary nearest match).

216 `SemMentionShape` is the shape code for the **whole block**, not just the three mention
keys: shape is decided before vocabulary, at every key. A list or nested object on a key the
vocabulary reads as a single value (`kind:`, `role:`, `period:`, `currency:`, `code_format:`,
`code_pattern:`, a measures item's `aggregation:`) is a shape error — *"'<key>:' takes a single value, not a
list"* — rather than a bogus "unknown kind 'period_table'". Everything except 218 is an
ERROR, and an entity block with any semantics ERROR degrades: the whole block becomes a load
issue and is served without semantics. Veles never guesses.

## Consumer contract (downstream, for reference)

`ttr-metadata` exposes the validated result on the typed model; ai-platform's
Ariadne maps it to `com.tatrman.metadata.v1` `AttributeSemantics` /
`EntitySemantics` (closed proto enums mirroring the tables above — see
ai-platform `feature-grounding-contracts.md` §4). The vocabulary here and the
proto enums version **together**.

The **open wire** (tatrman-server side, RG-P3.S0 / RS-33) is
`org.tatrman.meta.v1` string-vocabulary messages — `EntitySemantics{kind, params,
measures}` / `AttributeSemantics{role, code_format, period, currency_attribute,
aggregation}`, projected by Veles from the typed model (ttr-metadata ≥ 0.9.4).
`kind`/`role` are strings, so this vocabulary evolves without a proto bump;
consumers tolerate unknown values. The closed-enum sentence above is the
**legacy** (ai-platform) note — that mapping stays legacy-side and dies at the
SV-P5 cutover.

The mention facet reaches that wire additively (MS-P2): `EntitySemantics.measures`
carries attribute LOCAL names in **declared order** (first = the default measure;
empty = none declared), and `AttributeSemantics.aggregation` is set only on
attributes listed in their owner's `measures:` — veles fills it *from the owner's
list*, matching `MeasureRef.attribute.path` against the member's local name. The
mention side of `name:`/`code:` travels on `EntityDetail.name_attribute` /
`code_attribute` (semantics wins over the legacy properties where declared) and,
for db tables, on `DbTableDetail.name_attribute` / `code_attribute`. Estates that
declare nothing are byte-identical on this wire.

## Task lists

Execute in order; each ≤8 checkboxed steps; tick each box right after the task
completes. Tests are written **before** implementation within each list (TDD).

1. [T1 — Grammar 4.2 + regeneration](./T1-grammar.md)
2. [T2 — TS parser AST + walker](./T2-parser-ast-walker.md)
3. [T3 — TS semantics validation](./T3-semantics-validation.md)
4. [T4 — Kotlin parity (parser / writer / semantics)](./T4-kotlin-parity.md)
5. [T5 — ttr-metadata typed-model surface + publish](./T5-ttr-metadata.md)
6. [T6 — TTR-P BuiltinCatalog twin entries](./T6-ttrp-twin.md)
