# New Grammar Version — Process Checklist (template)

Status: **living template** · Copy this list into the PR/issue for each grammar change and tick it off.

> **⚠ Registry change (FO ⚑1 / FO-P0.S3, 2026-07-18):** the TS grammar now
> publishes to **public npmjs as `@tatrman/grammar`** — the `@collite/ttr-grammar`
> GitHub-Packages lane and its publish-time name-rewrite are **retired**.
> **[`PUBLISHING.md` § TypeScript](../../../PUBLISHING.md) is authoritative** for the
> current lane; where this checklist still says `@collite/ttr-grammar` / "GitHub
> Packages", read `@tatrman/grammar` / "public npmjs". The external publish is gated
> on Bora's go + the `NPM_TOKEN` secret (FO ⚑2 — validate first). Downstream
> consumer re-point (kantheon Maven URL, the grammar-distribution effort's
> consumer smoke, modeler) is coordinated separately, not by this checklist.

`packages/grammar/src/TTR.g4` is the **single canonical grammar**. It is **not vendored** anywhere.
Three parsers are generated from it and kept in lock-step by the conformance harness:

| Target | Generator | Lives in | Consumed as |
|---|---|---|---|
| TypeScript (in-repo) | `antlr-ng` | `packages/parser` | in-process (LSP, Designer, VS Code ext) |
| TypeScript (external) | consumer runs `antlr-ng` on the published `.g4` | `packages/grammar` | `@collite/ttr-grammar` on **GitHub Packages (npm)** |
| Kotlin | ANTLR Gradle plugin (reads `.g4` directly) | `packages/kotlin/ttr-parser` (+ `ttr-semantics`) | `org.tatrman:ttr-parser` on **Maven** |
| Python | reference ANTLR jar (`-Dlanguage=Python3`) | `packages/python/ttr-parser` | `ttr-parser` wheel on **PyPI** |

Downstream repos (e.g. `ai-platform`) **bump the published-artifact version** — they never copy the
grammar. Drift between targets is caught by `conformance.yml`, not by any sync step.

> Fill in: **version** `____` · **kind** ☐ additive (minor `X.Y+1`) ☐ breaking (major `X+1.0`) ·
> **PR** `____`

---

## 0. Pre-flight

- [ ] Decide the version bump: **additive** (new optional construct / sugar) → minor `Y`;
  **breaking** (renamed/removed token, changed shape) → major `X`. Record the rationale in the PR.
- [ ] Confirm the change keeps `TTR.g4` **target-neutral**: no `@members`/`@header`, no embedded
  `{…}` actions, no semantic predicates, no `options { language=… }`. (All three targets generate
  from this one file — anything target-specific breaks Kotlin/Python generation.)

## 1. Grammar

- [ ] Edit `packages/grammar/src/TTR.g4`. New keywords used in cross-references must also be added to
  `idPart` and to `cnKey` (preserve formerly valid free-form keys).
- [ ] Update the `@grammar-version:` marker comment to the new version and add a CHANGELOG entry in
  the header comment block. (The parser prebuild extracts this into `@modeler/grammar`'s
  `TTR_GRAMMAR_VERSION`; update any test asserting the string.)

## 2. Regenerate + wire each target

- [ ] **TS:** `cd packages/parser && pnpm run prebuild` (regenerates `src/generated/` via
  `scripts/generate-typescript-parser.sh`). Update `walker.ts` / `ast.ts` for new nodes (keep
  source locations accurate — edit-synthesizer invariant) and any `@modeler/semantics` handling.
- [ ] **TextMate (VS Code):** `cd packages/vscode-ext && node scripts/generate-tm-grammar.ts`
  (regenerates `syntaxes/*.tmLanguage.json` — the only generated grammar file that **is** committed).
- [ ] **Kotlin:** runs the ANTLR Gradle plugin on the same `.g4`; update the Kotlin walker +
  `ttr-semantics` port to mirror the TS change. `./gradlew :packages:kotlin:ttr-parser:test
  :packages:kotlin:ttr-semantics:test`.
- [ ] **Python:** the Hatchling build hook regenerates via `packages/python/ttr-parser/scripts/
  generate-python-parser.sh` (needs Java/the antlr4 jar). Update the Python walker + semantics port;
  `cd packages/python/ttr-parser && pytest` (and `ruff`, `mypy --strict`).

> `generated/` / `_generated/` are gitignored on every target — never hand-edit them. Only `TTR.g4`,
> the generation scripts, and `vscode-ext/syntaxes/*.tmLanguage.json` are committed.

## 3. Conformance (the lock-step gate)

- [ ] Add/adjust fixtures under `tests/conformance/fixtures/` to exercise the new construct (plus a
  negative fixture if a token was removed).
- [ ] Refresh the committed TS baselines: `pnpm --filter @modeler/conformance dump-all`, commit
  `tests/conformance/out-ts/` and `out-ts-sem/`.
- [ ] Run cross-target diffs (mirrors `conformance.yml`): TS dumps vs Kotlin
  (`./gradlew … *ConformanceSpec*`, `*SemanticsConformanceSpec*`) and Python (`py-dump` /
  `py-sem-dump`); `pnpm --filter @modeler/conformance diff` + `diff-sem`. All three targets must agree.

## 4. Docs

- [ ] CHANGELOG: `packages/grammar/CHANGELOG.md` (+ repo `CHANGELOG.md` if present), with the
  breaking-change migration steps for a major bump.
- [ ] Update `CLAUDE.md` only if an **invariant** changed (new schema code, file kind, etc.).
- [ ] Update `docs/grammar-master/contracts.md` if data shapes changed.

## 5. Repo gates

- [ ] `pnpm -r typecheck && pnpm -r lint && pnpm -r build && pnpm -r test`
- [ ] `pnpm --filter @modeler/integration-tests test`

## 6. Publish (tag-driven — see `PUBLISHING.md`)

> **Unified version policy (2026-07-16).** Grammar-coupled artifacts share **one**
> version line with the grammar: an artifact's `<major.minor>` **equals** the
> grammar's `@grammar-version`; the **patch** digit is code-only (a fix that does
> not change the grammar). So `ttr-parser@0.9.6` unambiguously parses grammar `0.9`.
> When the grammar bumps (`0.9 → 0.10`), **re-cut every grammar-coupled artifact at
> the new `<major.minor>.0`**. Coupled = `@collite/ttr-grammar`, Kotlin
> `ttr-parser`/`ttr-writer`/`ttr-semantics`, Python `ttr-parser`. The publish
> workflows **enforce** this — a coupled tag whose minor ≠ `@grammar-version` fails.
> The plan **protos** (`ttr-plan-proto`, `translator`/`python-plan`) follow the same
> minor **by convention** but are grammar-independent, so they are **not** gated.

- [ ] **Kotlin grammar toolchain:** push `grammar/v<major.minor>.0` (bundle:
  ttr-parser+writer+semantics) or a per-artifact tag (`ttr-parser/v*`,
  `ttr-semantics/v*`, `ttr-writer/v*`) — `publish.yml` publishes to Maven. The tag's
  `<major.minor>` must equal `@grammar-version` (enforced).
- [ ] **Python parser:** push `python/v<major.minor>.0[-RELEASE]` — `publish-python.yml`
  builds + publishes the `ttr-parser` wheel. Minor must equal `@grammar-version` (enforced).
- [ ] **TypeScript grammar:** push `ts-grammar/v<major.minor>.0` — `publish-ts.yml` builds
  `@tatrman/grammar`, rewrites the name to `@collite/ttr-grammar` + the tag version, and
  publishes to GitHub Packages (npm). Minor must equal `@grammar-version` (enforced).
  (In-repo TS consumers — Designer, VS Code ext — build from `packages/parser` directly
  and need no publish.)
- [ ] **Protos (if the bump touches them):** re-cut `ttr-plan-proto` at the same
  `<major.minor>.0` via `translator/v*` (Kotlin) + `python-plan/v*` (Python) — by
  convention, not enforced.
- [ ] Verify the artifacts are resolvable at the new version before notifying consumers.

## 7. Downstream

- [ ] In `ai-platform` (and any other consumer): bump the `ttr-parser` Maven / PyPI **dependency
  version** to the new release. **No grammar copy, no sync.** Run that repo's own test suite against
  the new artifact.
- [ ] In `modeler` (and any other external TS consumer): bump the `@collite/ttr-grammar` **dependency
  version** in `packages/{parser,lsp}/package.json`, `pnpm install`, and regenerate the parser
  (`pnpm --filter @modeler/parser run prebuild` resolves the `.g4` from the dependency). **No grammar
  copy, no sync.** Run that repo's own suite + conformance against the new artifact.
