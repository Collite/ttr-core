# PL Design — H Naming Sweep (workstream H)

> The accumulated naming queue, collected from every `→ H` marker in docs 02–08. Session opened 2026-07-03.
> Strategy: **decide the language name and the extension *scheme* first; almost everything else derives.** H is low-stakes individually but the placeholders now nag every doc.

## The queue (provenance)

| # | Item | Working placeholder | Deferred from |
|---|---|---|---|
| H-1 | The language's name (canonical flow-DSL + the family umbrella "PL") | "PL", "flow-DSL" | C0/C3 |
| H-2a | Program file extension | — | C3 |
| H-2b | Fragment dialect extensions | `.ttrsql` | C3-g-ii |
| H-2c | View-state sidecar suffix | "same name, different suffix" | C3-h |
| H-2d | World file extension | world = model file | D-d |
| H-2e | Artifact bundle extension | `.plb` | F-f |
| H-3a | Extracted translation lib | "Proteus core", `org.tatrman:?` | E-a |
| H-3b | Storage-grouping keyword in world docs | `namespace` (off Tatrman's `TableContainer`) | D-d |
| H-3c | Connection env-var prefix | `PL_CONN_` | F-c-ii |
| H-4a | Byx rename (strict NL surface) | "Byx" (brief promises a rename) | A/C0 |
| H-4b | Docs folder name | `docs/ttr-p/` | H row |
| H-4c | Language/spec versioning stance | — | H row |

Fixed points (already Bora-confirmed, not reopened): **TTR-SQL / TTR-pandas** as the fragment *dialect names* (C0); `org.tatrman` as the Maven group; Kyx/RAE stay as-is (historical PoCs, nothing ships under those names).

---

## H-1 · The language name

The canonical DSL needs a name that works in three positions: prose ("a ___ program"), extensions, and the umbrella ("the TTR ___ toolchain"). Candidates:

- **α · TTR Flow** ("flow" as the language's proper name). Systematic, self-describing — the canonical DSL *is* the flow language (C0). Prose: "a flow program", "TTR Flow". Extensions derive as `.ttrf`-family. Cost: "flow" is a crowded word (Airflow, Prefect flows, flowcharts); low distinctiveness.
- **β · Tok** (Czech: *tok* = flow/stream/current — a river's flow). The family is already Czech-rooted (Tatrman/Tatra); rivers flow *from* the Tatras, which is almost embarrassingly on-theme. Short, ASCII, pronounceable in both languages; unique enough to grep. Prose: "a Tok program", "TTR Tok". Extensions could be `.tok` (distinctive) or `.ttrt` (systematic). Cost: "tok" collides with nothing serious (TikTok jokes will happen exactly once).
- **γ · TPL / "Tatrman Processing Language"** — formalize the working shorthand. Zero renaming effort in the docs. Cost: an acronym, not a name; `.tpl` is a taken extension (templates); "PL" was always scaffolding.
- **δ · Something else entirely** — your call beats any catalogue here; naming is the one workstream where the fifth option is the expected outcome.

## H-2 · Extension scheme (one scheme, five slots)

- **α · Systematic `ttr?` family:** program `.ttrf`/`.ttrt`, world stays `.ttrm` (a world IS a model file — D-d needs no new extension unless wanted), sidecar `.ttrv` (view), bundle `.ttrb/`. Fragments `.ttrsql` / `.ttrpd`. One recognizable family in a directory listing.
- **β · Double extension for fragments, systematic elsewhere:** fragments become **`report.ttr.sql` / `prep.ttr.py`** — foreign editors get SQL/Python syntax highlighting *for free*, which is exactly C3-g-ii's "files stay valid for foreign tools" instinct, extended to tooling. The `# pl:` comment override still covers generic extensions. Cost: "double extension" surprises some tools (glob patterns, upload filters); `.ttr.py` mildly overstates (TTR-pandas ≠ Python, though it is Python-shaped).
- **γ · Name-derived:** if H-1 = Tok: program `.tok`, sidecar `.tokv`, bundle `.tokb/`; fragments per α or β. Distinctive, breaks the `ttr*` visual family (may be fine — TTR models and Tok programs *are* different things).

## H-3 · Code names

- **H-3a · Translation lib** (`org.tatrman:<?>` — island → RelNode → dialect SQL; sits beside `ttr-parser`, `ttr-writer`, `ttr-semantics`): candidates **`<lang>-translate`** (says what it does; e.g. `tok-translate`), **`<lang>-relational`** (says what it covers), **`pl-translate`** (if PL survives as the umbrella), keeping **`proteus-core`** (mythology leaks out of kantheon — against modeler's non-persona convention). Note: whatever the compiler-side packages are named (`<lang>-compiler`, `<lang>-emit`…) should follow the same prefix — this pick sets it.
- **H-3b · Storage-grouping keyword** (inside `def storage`, groups hosted tables — Tatrman's `TableContainer`): **`namespace`** (current working; accurate, boring-good) · **`catalog`** (DB-native word for exactly this) · **`schema`** (most accurate DB word but collides with TTR's `schema` keyword — bad) · **`container`** (heritage, but PL already has Container *nodes* — worse collision). Realistically `namespace` vs `catalog`.
- **H-3c · Env-var prefix:** derives from H-1 — `TOK_CONN_<NAME>` / `TTRF_CONN_` / keep `PL_CONN_`. Mechanical once H-1 lands.

## H-4 · Byx rename + leftovers

- **H-4a · The strict NL surface** (Byx; brief: "we will rename this"): follow H-1's pattern — e.g. **`<lang> Say`/`Tell`** flavor, a second Czech word (e.g. **`Řeč`** — no, diacritics; **`Slovo`** — word), or **keep Byx** as a legacy-charm proper noun (it *is* distinctive and only names one surface). This one is pure taste.
- **H-4b · Docs folder:** `docs/ttr-p/` → rename to the language name, or leave (folder names are cheap to fix at consolidation).
- **H-4c · Versioning:** propose **parking to consolidation** — the language spec version wants to be designed with the artifact/manifest versioning and the grammar-master process, not as a naming afterthought.

---

## RESOLVED (2026-07-03/04 — Bora's direction, superseding the H-1 catalogue)

- **H-1 · The FAMILY is "tatrman" (TTR = "table transformation manager"); modeling = TTR-M, processing = TTR-P.** The product = modeler (TTR-M + metadata) + TTR-P (+ compiler, later optimizer) under one name. Disambiguation convention in prose: **Tatrman** = the product/repo · **TTR** = the family · **TTR-M / TTR-P** = the languages · TTR-SQL / TTR-pandas = TTR-P's fragment dialects (unchanged). "PL" retires as scaffolding shorthand (docs keep it historically).
- **H-1-bis · REPO FORK: modeler → `~/Dev/collite/tatrman` (`Collite/ttr-core`), whole content, continue there.** Mechanics + consequence sweep below (§Fork consequences).
- **H-2a · Program extension = `.ttrp`** (free again — 07's `plan.ttrp` manifest placeholder was superseded by `manifest.json` in F-f). `.ttrm` (model) and `.ttrg` (designer graph/package) stand.
- **H-2c · ONE layout/view-state scheme + ONE extension `.ttrl` for the whole family** (Bora's "try this" — taken): per-document sidecar `<doc>.ttrl` beside `.ttrp` programs, bare fragments, **and TTR-M documents** (`.ttrm`/`.ttrg`). This exercises exactly the branch C3-h left open ("same block grammar, different hosting; TTR may migrate later") — **TTR-M migrates to the sidecar**, i.e. the v1.1 in-file `layout` block moves out of `.ttrg` into the sibling `.ttrl`. Not a D4 revert (D4 killed the *project-global* aggregate; per-document pairing is preserved — `.ttrl` even reclaims the old extension with new, per-document semantics). One block grammar, one suffix, one pair-integrity toolset (orphan diagnostics + atomic renames) serving both languages — better ROI than two schemes. **Consequence: a TTR-M-side design amendment (v1.1 layout hosting), to be recorded in the modeler/TTR docs, not just here.**
- **H-3a · Translation lib = `org.tatrman` + "translator"** (artifact-id form — `ttr-translator` vs bare `translator` — settled below; Kotlin root `org.tatrman.translator.*`).
- **H-3b · `namespace` confirmed** as the storage-grouping keyword (round 1).
- **H-3c · Env prefix follows the family: `TTR_CONN_<NAME>`** (derived; replaces `PL_CONN_`).

### Fork consequences (H-1-bis sweep — track these, they bite silently)

1. **Kantheon consumption re-points.** GitHub Packages URLs are per-repo: kantheon's `settings.gradle.kts` (`ColliteModeler` repository → `Collite/ttr-core`), the `gpr.*` PAT (fine-grained PATs are repo-scoped — needs re-granting), and kantheon's CLAUDE.md §7.3 text all reference `Collite/modeler`. Kantheon-side task, natural companion to the already-queued Proteus-extraction arc.
2. **Publish workflow travels**: tag-driven `publish.yml` publishes to the *new* repo's package registry; first publish from `Collite/ttr-core` must happen before kantheon re-points.
3. **Old-modeler disposition**: the ai-platform→kantheon precedent says copy-not-move, old repo frozen/maintenance until consumers re-point, then archive.
4. **Name shadowing**: `~/Dev/tatrman` (the historical PoC repo — JGraphT DAG prior art) already carries the name. Recommend renaming it (e.g. `tatrman-poc`) so "tatrman" unambiguously means the product.
5. **`@modeler/*` npm scope**: the TS workspace packages are unpublished, so renaming to `@tatrman/*` is cheap churn — decide opportunistically, not blocking.
6. **Docs folder (H-4b)**: `docs/ttr-p/` → `docs/ttr-p/` (or similar) lands naturally with the fork's first commit.

## RESOLVED (round 2) — H converged 🟢

- **H-1-bis mechanics = CLONE WITH FULL HISTORY** (git clone → push to `Collite/ttr-core`; old `Collite/modeler` frozen/maintenance until kantheon re-points, then archived — the ai-platform→kantheon precedent).
- **H-3a = `org.tatrman:ttr-translator`** (matches the `ttr-*` sibling row; Kotlin root `org.tatrman.translator.*`).
- **H-2b = DOUBLE EXTENSIONS for fragments: `report.ttr.sql` / `prep.ttr.py`** — free foreign-editor highlighting; the `# pl:`-style comment override (C3-g-ii) still serves generic extensions.
- **H-4a = TTR-B** (the strict NL surface joins the scheme; the B honors Byx; name formally lands with C4's grammar session).

### Final name & extension table

| Thing | Name / extension |
|---|---|
| The product / repo | **Tatrman** (`Collite/ttr-core`, `~/Dev/collite/tatrman`) |
| The language family | **TTR** ("table transformation manager") |
| Modeling language | **TTR-M** · `.ttrm` (docs, incl. `schema world`) · `.ttrg` (designer graph/package) |
| Processing language | **TTR-P** · `.ttrp` |
| Fragment dialects | **TTR-SQL** `.ttr.sql` · **TTR-pandas** `.ttr.py` |
| NL surface | **TTR-B** (ex-Byx) · `.ttrb` (reserved; C4 confirms) |
| Layout/view-state sidecar | `.ttrl` — **one scheme family-wide**, per-document (`x.ttrp`+`x.ttrl`, `x.ttrg`+`x.ttrl`, `report.ttr.sql`+`report.ttrl`) |
| Artifact bundle | `<program>.bundle/` (proposed — `.ttrb` is taken by TTR-B) |
| Translation lib | `org.tatrman:ttr-translator` |
| Connection env vars | `TTR_CONN_<NAME>` |
| Storage grouping keyword | `namespace` |
| Docs folder (post-fork) | `docs/ttr-p/` |

### Leftovers (opportunistic, non-blocking)

- `@modeler/*` npm scope → `@tatrman/*` (unpublished workspace packages; rename with the fork or whenever).
- Bundle-dir name `<program>.bundle/` — ratify or veto at consolidation.
- H-4c versioning stance → parked to consolidation (design with artifact/manifest versioning + grammar-master process).
- `~/Dev/tatrman` PoC repo rename (`tatrman-poc`) to free the name.
- TTR-M's `.ttrl` sidecar migration = a modeler-side design amendment (v1.1 layout hosting) — record in TTR-M docs post-fork.
