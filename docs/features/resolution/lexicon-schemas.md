# Lexicon area — schemas and error catalogue

> `ttr-lexicon/v1` · `ttr-skill/v1` — the authored files of a defining repo's **lexicon data
> area**, the sibling of `model/` (RV-36..38, RV-42). Normative shapes: the RV effort's
> `contracts.md` §2. This page documents what the schemas enforce and every error an author
> can hit, with an example of each.
>
> Implementation: `packages/kotlin/ttr-lexicon` (schemas packaged under
> `resources/schemas/`, so a consumer validates against the same bytes this documents).
>
> **The schemas are the contract; the Kotlin validator is the JVM enforcement of it.** They are
> two expressions of one ruleset, and `SchemaEquivalenceSpec` runs every fixture through both —
> a disagreement in either direction fails the build. The split exists so a published toolchain
> artifact, resolved by every TTR-P project, does not ship a JSON-Schema engine (and its Jackson
> stack) to enforce rules it already enforces; networknt stays test-scope here, as it is in
> `ttrp-emit`, `ttrp-cli` and `ttrp-lsp`.

## 1. Where files live

```
<defining-repo>/
├── model/                       # the TTR-M model — unchanged
└── lexicon/                     # this area
    ├── aliases/*.lex.yaml       # ttr-lexicon/v1 — terms → model refs
    ├── values/*.lex.yaml        # ttr-lexicon/v1 — terms → member refs
    ├── grounding/*.lex.yaml     # ttr-lexicon/v1 — terms → `ground:` classes (RV-42)
    ├── predicates/*.lex.yaml    # ttr-lexicon/v1 — terms → `pred:` classes (LP §3)
    └── skills/*.md              # ttr-skill/v1 frontmatter + a Golem-side behavior body
```

Files outside those five directories are ignored, not rejected — notes may live beside a
lexicon. **Entry kind is never authored**: alias vs value is derived from the target class
(RV-38), so a file cannot disagree with the model graph about what it declares.

## 2. `ttr-lexicon/v1` — data files

```yaml
schema: ttr-lexicon/v1
defaults: { lang: cs, method: TYPOS(1) }     # optional; a term's own keys always win
entries:
  - terms:
      - { text: "středisko" }
      - { text: "cost center", lang: en, method: EXACT }
    target: er.CostCenter
```

| Key | Required | Values |
|---|---|---|
| `schema` | yes | exactly `ttr-lexicon/v1` |
| `defaults.lang` / `defaults.method` | no | as per the term keys below |
| `entries[].terms[].text` | yes | non-empty string |
| `entries[].terms[].lang` | no | `cs` · `en` · `cs\|en` |
| `entries[].terms[].method` | no | `EXACT` · `TOKENS` · `TYPOS(1)`…`TYPOS(3)` |
| `defaults.match` / `entries[].terms[].match` | no | a **matching profile** — see §2.1. Mutually exclusive with `method` on the same node |
| `entries[].target` | yes | any model-graph ref, member ref, `ground:` class, or `pred:` class |

Both schemas are **closed** (`additionalProperties: false`): an unknown key is an error, so a
typo fails the build instead of silently contributing nothing.

**Fallbacks when neither the term nor `defaults` says:** `method` → `EXACT`, `lang` →
`cs|en`. ⚠ Assumed at RV-P1.1, not ruled — see the note in `LexiconValidator.DEFAULT_METHOD`.
`target` refs are **not** resolved here; dangling refs are the compiler's business (RV-P1.2,
drop + build warning per RV-20).

### 2.1 `match:` — declared matching profiles (RV-44)

Additive to `ttr-lexicon/v1` — **the schema id does not change**, and every file written before
profiles existed still parses. A profile says *which normalized forms count for this term, and how
strong each is*:

```yaml
defaults:
  lang: cs
  match:
    - { norm: canonical, exact: 1.00, typos: { distance: 1, penalty: 0.05 } }
    - { norm: folded,    exact: 0.90 }     # "zakaznik" finds "zákazník" as an AUTHORED fact
    - { norm: lemma,     exact: 0.80 }
entries:
  - terms: [ { text: "zákazník" } ]                                  # inherits defaults.match
  - terms: [ { text: "DC", match: [ { norm: canonical, exact: 1.00 } ] } ]   # its own, whole
    target: er.DistributionCentre
```

| Key | Required | Values |
|---|---|---|
| `norm` | yes | `canonical` (NFC + lowercase, diacritics **kept**) · `folded` (+ diacritics stripped) · `lemma` (per-token lemmatization). **Closed set** — there is deliberately no case-sensitive stratum |
| `exact` | see below | equality on that norm scores here; `(0,1]` |
| `typos` | no | `{ distance: ≥1, penalty: >0 }` — fires at `exact − d·penalty`. Needs a sibling `exact` **on the same norm** as its anchor |
| `tokens` | no | `{}` — token-set matching on that norm (the engine's algorithm, with the RV-32 uniqueness margin) |

A rule must declare at least one of `exact` / `typos` / `tokens`; order is convention
(strongest first) and **combination is `max`**, so a mis-ordered list gives identical results.
The declared score is the **within-class** score — RV-14's evidence classes still decide what
binds, and a score only orders rows *inside* one class.

**`method:` is sugar for a profile**, and always remains valid:

| `method:` | compiles to |
|---|---|
| `EXACT` | `[{ norm: canonical, exact: 1.00 }]` |
| `TYPOS(d)` | `[{ norm: canonical, exact: 1.00, typos: { distance: d, penalty: 0.05 } }]` |
| `TOKENS` | `[{ norm: canonical, tokens: {} }]` |

A term's own `match:` (or `method:`) **replaces** the file default whole — it never merges into it.
Profiles are an authoring surface only (⚑M-2): rows harvested from model labels, and the member
index, keep the engine's own scores. TTR-M `lexicon{}` sugar keeps `method` only (⚑M-3).

**Short-term guard (⚑M-4).** `typos` never fires when the authored term's canonical form is ≤ 3
characters — a one-edit neighbourhood around a two-character token reaches most of its siblings.
Declaring one anyway is a **warning** (`RG-LEX-101`), not an error: the file compiles, ships, and
simply does not fuzz that term.

## 3. `ttr-skill/v1` — skill frontmatter

```markdown
---
schema: ttr-skill/v1
op: op:trend                       # the `op:` ref class
triggers:
  - { text: "vývoj", lang: cs, method: TYPOS(1) }
  - { text: "trend", lang: cs|en, method: EXACT }
requires: [ time-grain ]           # applicability, validated at compose time
version: 1
---
Retrieval: group by the finest requested time grain; order chronologically.
Formatting: line chart default; period column first.
```

The **frontmatter compiles into the lexicon** as ordinary vocabulary — operator words get
match methods, aliasing and learning like any other term. The **body is not vocabulary**: it
is a Golem-side behavior artifact, kept verbatim, packaged into the operator-library artifact
by op id. The matcher never reads a body.

Frontmatter must open with `---` as the first non-blank line and close at the next line that
is exactly `---`. A `---` later in the body is a horizontal rule and is left alone. A BOM and
leading blank lines are tolerated; CRLF is normalised so a Windows-authored skill compiles to
the same bytes as a Unix one.

## 4. Error catalogue (`RG-LEX-*`)

Every `RG-LEX-0xx` code is an **ERROR** — each rejects a file at build time, so none has a
degraded mode; warnings live in their own band (§4.1), so a code alone tells you whether it stops
a build. Ids
follow the `RG-<AREA>-<NNN>` convention the resolution & grounding services use, and every one is
constructed in `LexiconErrors` (whose `ALL` is the catalogue's own index), so the set cannot grow
an undocumented member. Messages quote the authored value and the line it was written on.

| Code | Condition | Example that triggers it |
|---|---|---|
| `RG-LEX-001` | Unknown match method | `{ text: "středisko", method: FUZZY }` |
| `RG-LEX-002` | Required key missing | an entry with `terms:` but no `target:` |
| `RG-LEX-003` | `TYPOS` without a distance | `method: TYPOS` — write `TYPOS(1)` |
| `RG-LEX-004` | Skill `op` is not an `op:` ref | `op: trend` instead of `op: op:trend` |
| `RG-LEX-005` | Skill declares no triggers | `triggers: []` |
| `RG-LEX-006` | Duplicate term in one file | `"středisko"`/`cs` declared under two targets |
| `RG-LEX-007` | Unknown key (closed schema) | `entrys:` instead of `entries:` |
| `RG-LEX-008` | `schema:` id mismatch | `schema: ttr-lexicon/v2` in a v1 file |
| `RG-LEX-009` | Frontmatter missing or unterminated | a skill file that is only prose |
| `RG-LEX-010` | Unsupported `lang` | `lang: czech` — use `cs` |
| `RG-LEX-011` | File does not parse as YAML | a tab-indented block, an unclosed quote |
| `RG-LEX-012` | Unknown grounding kind (RV-42) | `target: ground:weather` — the set is closed: `ground:chrono` \| `ground:money` \| `ground:geo` |
| `RG-LEX-013` | Unknown `norm` (RV-44) | `{ norm: verbatim, exact: 1.0 }` — the set is closed: `canonical` \| `folded` \| `lemma` |
| `RG-LEX-014` | `typos` with no sibling `exact` on the same norm | `[{ norm: folded, typos: { distance: 1, penalty: 0.05 } }]` |
| `RG-LEX-015` | `method` and `match` on one node | `{ text: "DC", method: EXACT, match: [ … ] }` |
| `RG-LEX-016` | Score / distance / penalty out of range | `exact: 1.4`, `distance: 0`, `penalty: 0` |
| `RG-LEX-017` | `typos` budget reaches or passes its `exact` anchor | `exact: 0.9` with `typos: { distance: 3, penalty: 0.3 }` — scores 0 at the widest edit |
| `RG-LEX-030` | Unknown string predicate (LP §3.1) | `target: pred:like` — the set is closed: `pred:starts_with` \| `pred:ends_with` \| `pred:contains` \| `pred:equals` \| `pred:not_starts_with` \| `pred:not_ends_with` \| `pred:not_contains` \| `pred:not_equals` |
| `RG-LEX-031` | A `pred:` form is a single character, a function word, or a phrase of function words only | `{ text: "s" }` (cs), `{ text: "with" }` or `{ text: "with the" }` (en) under a `pred:` target — write `s textem`, `starts with` |
| `RG-LEX-032` | A `pred:` form is wider than `LexiconValidator.MAX_PREDICATE_FORM_TOKENS` (3) words | `{ text: "does not start with" }` — the resolver looks at most three words left of a quoted literal, so a wider form could only ever match as a fragment |

### 4.1 Warning catalogue (`RG-LEX-1xx`)

The `1xx` band is **warnings**: the file is valid, compiles and ships, but one thing in it will not
behave as written. They ride out of the loader on `LexiconLoad.Ok.warnings` and are folded into the
build's warning stream beside RV-20's dangling refs, so an author reads one list, not two.

| Code | Condition | Example that triggers it |
|---|---|---|
| `RG-LEX-101` | Short-term typos guard (RV-44 ⚑M-4) | `{ text: "DC", method: TYPOS(1) }` — 3 chars or fewer, so the typos rule never fires |

Every row is backed by a fixture under
`packages/kotlin/ttr-lexicon/src/test/resources/lexicon-schema-fixtures/`, and the spec
asserts the **specific** code per fixture — "it failed somehow" is not the contract.

**A whole area reports every bad file, not just the first.** Lexicons are authored in bulk;
failing on file 1 of 40 turns one editing session into forty.

## 5. What this library does not do

Compilation (normalization, the uniform entry table, model-snapshot ref checking, packing) is
RV-P1.2 — `ttr-lexicon-compile`, documented in [`lexicon-compile.md`](lexicon-compile.md).
Layered serving is RV-P1.4. This library stops at *"the authored files are well-formed, and here
they are as typed objects with provenance"* — plus the compiled artifact's **model and codec**
(`Artifact.kt`), which live here rather than in the compiler so a serving consumer reads a
lexicon archive without resolving `ttr-parser`, `ttr-metadata` or the compiler itself.
