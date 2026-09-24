# Publishing — Kotlin artifacts

This repo publishes the modeler-owned Kotlin libraries under the `org.tatrman:*`
group to **two lanes** (SV-P1 S4, 2026-07-12; lane-gating polarity flipped
2026-07-16 — justfile sync):

- **Maven Central** (Central Portal, `central.sonatype.com`) — the **public
  lane** (RO-17). Anonymous, no auth to consume; this is what **external**
  readers resolve. Signed + full POMs + sources/javadoc, via the
  `com.vanniktech.maven.publish` plugin. **Only a tag explicitly marked
  `-RELEASE` reaches here** — see [§ Release lanes](#release-lanes--internal-vs-release-2026-07-16).
- **GitHub Packages** (`https://maven.pkg.github.com/Collite/ttr-core`) — the
  **internal lane**. **Every tag** lands here, `-RELEASE`-marked or not, which is
  why it is what every internal consumer reads: `tatrman-server`, `kantheon`,
  `tatrman-platform` and `ai-platform` all resolve `org.tatrman:*` from here and
  **exclude the group from Central** (2026-09-24 — see
  [§ Consumer setup](#consumer-setup-ai-platform-and-other-repos)). It needs auth
  even for public reads (Gotcha 1), which is exactly why it can't be the *public*
  lane — and why every consumer needs a `read:packages` token.

See [§ Maven Central — the public lane](#maven-central--the-public-lane-sv-p1-s4) below.

> Two build domains coexist in this repo. The **pnpm/TypeScript** workspace
> (`packages/*`, built with `pnpm -r build`) ships the LSP, VS Code extension,
> and Designer. The **Gradle/Kotlin** build (`packages/kotlin/*`) ships the
> published Maven artifacts described here. They share only the canonical
> grammar at `packages/grammar/src/TTR.g4`; nothing else crosses between them.

## What is published

| Module (Gradle path) | Coordinate | Phase | Why it's published |
|---|---|---|---|
| `:packages:kotlin:ttr-parser` | `org.tatrman:ttr-parser` | 1 | ANTLR parser + typed AST; ai-platform's `infra/metadata` consumes the model types |
| `:packages:kotlin:ttr-writer` | `org.tatrman:ttr-writer` | 1 | Deterministic AST → TTR-source renderer; depends on `ttr-parser` (api) |
| `:packages:kotlin:ttr-semantics` | `org.tatrman:ttr-semantics` | 2 | Symbol table + resolver + per-kind validator + bundled stock CNC vocab; depends on `ttr-parser` (api) |
| `:packages:kotlin:ttr-metadata` | `org.tatrman:ttr-metadata` | 1 (M1) | Typed model, storage SPI (+ fs/classpath), reconciler, resolver, graph, search, registry, refresher mechanism, export, world resolution (M2). Pins the in-repo `ttr-parser`/`ttr-writer`/`ttr-semantics` and re-exports them as `api` deps (contracts §1) |
| `:packages:kotlin:ttr-metadata-git` | `org.tatrman:ttr-metadata-git` | 1 (M1) | `GitArchiveStorage` (jgit) behind the core `ModelStorage` SPI — Ariadne only; keeps jgit off the compiler/Designer-server classpath (MD3) |
| `:packages:kotlin:ttr-snapshot` | `org.tatrman:ttr-snapshot` | 1 (PL-P1.S1) | Deterministic content-addressed snapshot archives (tar+zstd, contracts §2) + `SnapshotArchiveStorage : ModelStorage` + immutable-by-id cache. The ② seam's transport unit; published lockstep with `metadata/v*` |
| `:packages:kotlin:ttr-md-resolver` | `org.tatrman:ttr-md-resolver` | 1 (M1, S6-A) | MemberCatalog / MemberSnapshot / MemberIndex — the member-catalog resolver interfaces (MDS1). `ttr-metadata` `api`s it (the metadata → resolver one-way arrow), so it's re-exported to consumers → published lockstep in the `metadata/v*` bundle |
| `:packages:kotlin:ttr-plan-proto` | `org.tatrman:ttr-plan-proto` | ttr-translator arc | Canonical `plan.v1`/`transdsl.v1`/`dfdsl.v1` wire formats (+ the `proteus.v1` enum stub and `plan.v1.SchemaCodes`), with the `.proto` files bundled as jar resources for the protoc include-path contract. tatrman owns the wire format (TR-3); consumed by kantheon `:shared:proto` + the translator |
| `:packages:kotlin:ttr-translator` | `org.tatrman:ttr-translator` | ttr-translator arc | Calcite-backed translation core (island ↔ RelNode ↔ SQL / `plan.v1`); consumed by tatrman `ttrp-emit` and kantheon Proteus/Ariadne. `api` deps: `ttr-plan-proto` + `calcite-core`. Ships an `InMemoryModelHandle` test-fixtures jar (ModelHandle SPI double, contracts §3) |
| `:packages:kotlin:ttrp-frontend` | `org.tatrman:ttrp-frontend` | TTR-P P3 | compiler front-half: parse → resolve → typecheck; serves `ttrp check`/`validate`/`authoringContext` (contracts §10) |
| `:packages:kotlin:ttrp-graph`    | `org.tatrman:ttrp-graph`    | TTR-P P3 | graph construction + normalizer (T8 rewrites) |
| `:packages:kotlin:ttrp-emit`     | `org.tatrman:ttrp-emit`     | TTR-P P3 | island codegen, movement synthesis, bundle assembly |
| `:packages:kotlin:ttrp-lsp`      | `org.tatrman:ttrp-lsp`      | TTR-P P4 | one TTR-P LSP; stdio + WS transports |
| `:packages:kotlin:ttrp-cli`      | `org.tatrman:ttrp-cli`      | TTR-P P3 | the `ttrp` binary (S2): build/run/explain/conform |
| `:packages:kotlin:ttrp-conform`  | `org.tatrman:ttrp-conform`  | TTR-P P3 | Q9 conformance harness (S3) |

Coordinates and public API surfaces are normative in
[`docs/grammar-master/contracts.md`](docs/grammar-master/contracts.md) §1.

### Not published (internal tooling)

| Module (Gradle path) | Why it's internal |
|---|---|
| `:packages:kotlin:ttr-designer-server` | Repo-attached Designer backend (`ttrm/…` WS-JSON-RPC over loopback, MD8); consumes `ttr-metadata` as a normal project dep. Ships as an application, not a library artifact — no `maven-publish`, no publication block, no release tag. |

## How to publish (release)

Versioning is **tag-driven** (consistent with the constellation's
`<name>/v<x.y.z>` convention). Pushing one of these tags triggers
`.github/workflows/publish.yml`. Tag prefixes were renamed 2026-07-16 (justfile
sync) from `kotlin*` to the module's own directory name or an explicit bundle
name — nobody pins a git tag name for Maven consumption, only the published
coordinate/version, so this rename is not a breaking change for consumers:

| Tag | Modules published | `just publish` |
|---|---|---|
| `grammar/v<x.y.z>[-RELEASE]` | **THE unified TTR toolchain bundle** (2026-07-30): `ttr-parser` + `ttr-writer` + `ttr-semantics` + `ttr-metadata` + `ttr-metadata-git` + `ttr-snapshot` + `ttr-md-resolver` — plus `ttr-lexicon` + `ttr-lexicon-compile` + `ttr-lexicon-cli`, which joined the lane on 2026-08-03 (`publish.yml` publishes all ten; this row listed seven until MH-P0 corrected it). They are one **`api` closure**, so they share one version — see [Why one bundle](#why-one-bundle-2026-07-30) below | `just publish bundle grammar` |
| `ttr-parser/v<x.y.z>[-RELEASE]` | `ttr-parser` only (rare; parser-only patch) | `just publish ttr-parser` |
| `ttr-semantics/v<x.y.z>[-RELEASE]` | `ttr-semantics` only (Phase 2 cadence) | `just publish ttr-semantics` |
| `metadata/v<x.y.z>[-RELEASE]` | **RETIRED 2026-07-30** — folded into `grammar/v*`. The workflow trigger is kept deliberately, so a stale tag **fails loudly** instead of falling through and publishing the wrong module set at a metadata version number | ~~`just publish bundle metadata`~~ → errors with a pointer |
| `translator/v<x.y.z>[-RELEASE]` | **both** `ttr-plan-proto` + `ttr-translator` (lockstep; ttr-translator arc). First real tag `translator/v0.8.0`. Wire-format changes follow `docs/ttr-translator/architecture/contracts.md` §2 (append-only within `v1`) | `just publish bundle translator` |
| `ttrp/v<x.y.z>[-RELEASE]` | bundle: all `org.tatrman:ttrp-*` modules (first cut in TTR-P Phase 3; workflow wiring lands there) | *(not yet wired into `just publish`)* |

```bash
just publish bundle grammar            # internal only, patch bump
just publish ttr-parser release        # + Maven Central, patch bump
```

### Why one bundle (2026-07-30)

The `grammar` and `metadata` bundles were merged. They were never independently
releasable, and treating them as if they were produced two dead versions on Maven
Central.

**The seven modules are one `api` closure:**

```
ttr-metadata      api→ ttr-parser, ttr-writer, ttr-semantics, ttr-md-resolver
ttr-md-resolver   api→ ttr-parser, ttr-semantics
ttr-snapshot      api→ ttr-metadata
ttr-metadata-git  api→ ttr-metadata
```

`api(project(...))` writes the **exact** version into the published POM. So a version
that exists in one half and not the other is not "skew" — it is an unresolvable build
for every consumer, permanently, because registry versions cannot be deleted.

**Two separate tags let that happen in two different ways, and both did:**

1. **Drift.** `metadata/v0.10.2` (07-26) and `metadata/v0.10.3` (07-27) were cut alone
   while grammar sat at `0.10.1`. Nothing forced them together.
2. **Partial failure.** On 07-29 the `0.10.4` pair went out as two tags:
   `metadata/v0.10.4-RELEASE` published, and `grammar/v0.10.4-RELEASE`'s workflow run
   **failed** ([run 30438898653](https://github.com/Collite/ttr-core/actions/runs/30438898653)).
   Half a version shipped.

**Consequence:** `org.tatrman:ttr-metadata` **0.10.3 and 0.10.4 are permanently
unresolvable** — they reference a `ttr-parser` at their own version that was never
built. Pinning `0.10.3` broke `tatrman-platform`'s `:services:veles:compileKotlin` for
three days (2026-07-27 → 07-30). It went unnoticed because it presents as a
dependency-resolution error in one module, not a test failure, so a test-only check
stays green.

**One tag closes both mechanisms.** Drift becomes arithmetically impossible — there is a
single version line. And a failing run now fails the whole set instead of half of it,
since all seven modules publish in one Gradle invocation to one Central deployment
bundle. It also still satisfies RO-24's original concern (the double-`PUT` race), because
each module appears in the list exactly once.

**Why the name stayed `grammar`:** the version *is* the grammar version — the workflow's
version-sync gate requires the tag minor to equal `@grammar-version` in `TTR.g4`, and the
metadata family always rode that minor by convention. Keeping the prefix also preserves
the version line: both halves were at `0.11.0`, so the unified bundle continues from
`0.11.0` with nothing going backwards. If a more accurate name is wanted later
(`ttr`, `toolchain`), it is a pure rename of the `WHAT →  PREFIX` mapping plus the
sync-gate `case` pattern.

**Do not pin `ttr-metadata`/`ttr-snapshot`/`ttr-md-resolver` at 0.10.3 or 0.10.4 from any
repo.** Safe versions are the ones both halves share — today `0.9.4` and `0.11.0`.

The workflow publishes in two steps: first the **GitHub Packages** staging lane
(`<modules>:publishAllPublicationsToGitHubPackagesRepository` with the
auto-provisioned `GITHUB_TOKEN` — no PAT in CI, runs for every tag), then the
**Maven Central** lane (`<modules>:publishToMavenCentral`, guarded on both the
Central secrets AND the tag's `-RELEASE` marker — see below).

> **Use the GH-Packages-*specific* task, never the generic `:module:publish`.**
> Since vanniktech registers a `mavenCentral` repository, the aggregate `publish`
> now also pulls in `prepareMavenCentralPublishing`, which fails the GH-Packages
> step with "mavenCentralUsername not found" (that step carries only the
> `GITHUB_TOKEN`). Fixed 2026-07-12 — see the workflow comments.

### Release lanes — internal vs RELEASE (2026-07-16)

Maven Central's free tier is quota-limited **per namespace per month, on a
3-month rolling average**: roughly **1,167 files / 78 MB / 7 releases**
([publishing limits](https://central.sonatype.org/publish/maven-central-publishing-limits/)).
Every published module contributes jar + sources + javadoc + POM + Gradle module
metadata, each multiplied by signatures and checksums (~25–40 files per module),
so a multi-module tag burns hundreds of files and one of the ~7 releases in a
single push. Fast iteration through Central is therefore not viable — three days
of it consumed 70% of a month's quota (2026-07-13).

The rule (**flipped 2026-07-16** from the original version-string-picks-the-lane
scheme): **a tag reaches Central only when explicitly marked `-RELEASE`.**
Internal patches now vastly outnumber real releases, so the default (a bare,
un-marked tag) had to become internal-only — inferring "public" from the mere
*absence* of a prerelease suffix meant every routine patch was one `just publish`
away from burning Central quota.

| Tag | Example | Lanes |
|---|---|---|
| Bare `x.y.z` (default — `just publish X`) | `ttr-parser/v0.9.5` | GH Packages **only** (staging/internal) |
| `-RELEASE`-marked (`just publish X release`) | `ttr-parser/v0.9.6-RELEASE` | GH Packages **and** Maven Central — published to **both** as bare `0.9.6` (the marker is stripped before it ever reaches a registry) |

`publish.yml` implements this by only running the Central step when
`github.ref_name` ends in `-RELEASE`. Practically:

- **Iterating?** Cut bare patches as fast as you like:
  `just publish ttr-parser` (or `just publish ttr-parser set 0.9.5`). Internal
  consumers pin whichever internal version they need — they resolve the GH
  Packages repo and *only* that one for this group (see
  [§ Consumer setup](#consumer-setup-ai-platform-and-other-repos)), so a bare cut
  is consumable everywhere in the ecosystem minutes after `publish.yml` finishes.
  **A `-RELEASE` is never needed to unblock internal work**; if one looks
  necessary, the consumer is reading the wrong lane.
- **Going public?** `just publish ttr-parser release` (or `release minor` /
  `release set X.Y.Z`) — mints a **brand-new** version number (never reuses one
  already spent by a prior internal tag, so the stripped Central/GH-Packages
  version never collides with anything already published) and pushes it to both
  lanes. Prefer **batching** families into one coordinated release — the release
  *count* is a quota metric too.
- **Same-machine loop?** Skip publishing entirely — `publishToMavenLocal`
  (below).

This also composes with RO-24's version freeze: every published version
(internal or RELEASE) is immutable once cut — the recipe refuses to reuse a
number, in either form, for the same module.

### Python wheels (PyPI)

Pure-Python wheels ship to **public PyPI** via Trusted Publishing (OIDC — no
token), driven by [`.github/workflows/publish-python.yml`](.github/workflows/publish-python.yml).
PyPI has no internal-registry equivalent in this repo, so — unlike the Maven
lane above — a bare tag doesn't publish anywhere at all; **only a `-RELEASE`-marked
tag builds and publishes** (2026-07-16, justfile sync):

| Tag | Wheel (PyPI project) |
|---|---|
| `python/v<x.y.z>-RELEASE` | `ttr-parser` — the ANTLR parser + stock vocab (pure-Python) |
| `python-plan/v<x.y.z>-RELEASE` | `ttr-plan-proto` — pre-generated `plan.v1`/`transdsl.v1`/`dfdsl.v1` `*_pb2.py` |

```bash
just publish packages/python/ttr-plan-proto release   # ttr-plan-proto wheel (path form — bare
                                                        # "ttr-plan-proto" is unambiguous, but
                                                        # "ttr-parser" collides with the Kotlin
                                                        # module of the same name and needs the path)
```

Each PyPI project registers this repo + `publish-python.yml` + the `pypi`
environment as its Trusted Publisher **before** the first tag is pushed (PyPI UI
→ project → Publishing). The `ttr-plan-proto` wheel carries the same version as
the Kotlin `kotlin-translator/v*` pair by convention.

### TypeScript grammar (public npmjs) — `@tatrman/grammar`

> **FO ⚑1 (A-managed, 2026-07-18) + FO-P0.S3.** The grammar moved from GitHub
> Packages (`@collite/ttr-grammar`) to **public npmjs under `@tatrman/grammar`** —
> the open-kit registry. The `@collite` publish-time name-rewrite is **retired**.
> **External cut is gated (FO ⚑2):** the first real public publish happens only
> after the pipeline is set up and validated and Bora green-lights it; two
> prerequisites are Bora's — claim the `tatrman` npm org (bora-legal) and
> provision the `NPM_TOKEN` secret (an npmjs automation token with publish rights
> on `@tatrman`). Until then the machinery is validated **unpublished** by the
> `grammar-consumer-smoke` CI job (packs + installs the tarball locally).

The canonical grammar ships to **public npmjs** so external TypeScript consumers
depend on a published, versioned package instead of vendoring a frozen copy of
`TTR.g4` — the same story Kotlin gets via Maven and Python via PyPI. Driven by
[`.github/workflows/publish-ts.yml`](.github/workflows/publish-ts.yml):

| Tag | Package (registry) |
|---|---|
| `ts-grammar/v<x.y.z>` | `@tatrman/grammar` (public npmjs) — raw `TTR.g4` + built `PROPERTY_MAP` / `TTR_GRAMMAR_VERSION` |

```bash
just publish ts-grammar release 0.9.0   # cuts @tatrman/grammar@0.9.0 to public npmjs
                                          # (deliberate maintainer action; needs NPM_TOKEN)
```

The workflow injects one value at publish (nothing is committed with it — mirrors
the Python `0.0.0`→tag injection): the `0.0.0` placeholder in
`packages/grammar/package.json` is replaced by the tag version. No name rewrite is
needed — public npmjs hosts `@tatrman/*` directly (unlike GitHub Packages, which
tied a scope to the owning account and forced the old `@collite` rename).
**Align the published minor with the grammar version** (grammar
`@grammar-version: 0.9` → `ts-grammar/v0.9.0`) so a consumer's `package.json`
shows at a glance which grammar line it tracks.

The published tarball is guarded to be **self-contained** — it must carry both
`src/TTR.g4` (consumers regenerate their own parser) and the built `dist/` that
re-exports `TTR_GRAMMAR_VERSION` (the workflow greps the tarball and fails
otherwise, mirroring the Python wheel's `unzip -l | grep` check). The
`grammar-consumer-smoke` CI job runs the same pack plus a real
`npm install <tarball>` in a scratch consumer — the validation leg that lets the
external cut wait behind a green, published-artifact-shaped check. Only the
**grammar** is published, not `@tatrman/parser`: consumers run their own ANTLR
generation from the shipped `.g4` (exactly what modeler does).

In-repo TS consumers (Designer, VS Code ext) build from `packages/parser`
directly and need no publish — this lane is for **external** repos only.

## Semver discipline

Per [`contracts.md`](docs/grammar-master/contracts.md) §7:

- **Patch** — bug fix only; no public API surface change.
- **Minor** — additive: new def kinds, new properties, new diagnostic codes.
  While version `< 1.0.0`, minor bumps **may** contain breaking changes —
  document them in `CHANGELOG.md`.
- **Major** — removing/renaming any public type, removing a `DiagnosticCode`,
  or changing the conformance JSON dump schema (§5). Post-1.0 this is strict.
- **No SNAPSHOTs.** They consume package storage with no auto-cleanup. Cut real
  versions even for early iteration — but WIP iteration versions carry a
  **prerelease suffix** (`-rc.N`, `-dev.N`) so they stay on the GH Packages
  staging lane and off the Central quota (§ Release lanes). Local builds
  without `-Pversion` get `0.0.1-LOCAL` (see below).

## Local iteration — `publishToMavenLocal`

Tight cross-repo iteration uses Maven Local instead of a real publish:

```bash
./gradlew -Pversion=0.0.1-LOCAL \
    :packages:kotlin:ttr-parser:publishToMavenLocal \
    :packages:kotlin:ttr-writer:publishToMavenLocal \
    :packages:kotlin:ttr-semantics:publishToMavenLocal
# → ~/.m2/repository/org/tatrman/{ttr-parser,ttr-writer,ttr-semantics}/0.0.1-LOCAL/
```

Point ai-platform at `mavenLocal()` for that window, then switch back to a real
version for anything that leaves your machine.

## Consumer setup (ai-platform and other repos)

**The rule (2026-09-24): an internal consumer resolves `org.tatrman:*` from
GitHub Packages and excludes the group from Maven Central.** Central takes only
`-RELEASE`-marked tags, and that marker is reserved for genuinely public
releases — so a build that can also see Central is a build that can silently
come to depend on one, and then wait for one. Leaving both lanes open is worse
than either alone: whichever registry happens to hold the pinned version wins,
which is not a decision anyone makes on purpose.

This is not theoretical. Between 2026-07-12 and 2026-09-24 kantheon consumed the
group from Central, and for the last two of those weeks could not move its
`server-libs` pin at all: the version it wanted dragged in
`org.tatrman:ttr-plan-proto:0.10.3`, cut on a bare `translator/v0.10.3` tag and
therefore living only in GitHub Packages. Nothing on the consumer side could fix
that, and the "fix" on this side would have been a public release of a
transitive — the same treadmill, one level down.

In the consuming repo's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        // Central serves everything EXCEPT org.tatrman.
        mavenCentral {
            content { excludeGroup("org.tatrman") }
        }
        // The TTR toolchain — every `grammar/v*`, `translator/v*`, `validator/v*` tag.
        maven {
            name = "ColliteTatrman"
            url = uri("https://maven.pkg.github.com/Collite/ttr-core")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
            content { includeGroup("org.tatrman") }
        }
        // The open read spine's libs + proto stubs — every `server-libs/v*` tag. A SECOND block
        // because GitHub Packages is per-repository; one credential serves both. Omit it if the
        // repo consumes no tatrman-server artifact.
        maven {
            name = "ColliteTatrmanServer"
            url = uri("https://maven.pkg.github.com/Collite/ttr-server")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
            content { includeGroup("org.tatrman") }
        }
        // If a repo keeps mavenLocal() for cross-repo iteration, put it LAST. Ahead of the
        // registries, a stale ~/.m2 jar shadows a real cut under the same coordinate.
    }
}
```

Then add a `gradle/libs.versions.toml` entry + `implementation(...)` line, e.g.
`implementation("org.tatrman:ttr-parser:0.13.5")`.

Live examples, all on this shape: `tatrman-server`, `kantheon`,
`tatrman-platform`, `ai-platform`. The one deliberate exception is
tatrman-server's `scripts/verify-public-resolution`, whose only repository is
`mavenCentral()` — its entire job is to prove the public lane really is
anonymously resolvable.

### Local-developer authentication

Each developer who consumes or manually publishes needs a GitHub Personal
Access Token (classic): `read:packages` to consume, `write:packages` to publish
by hand. Put it in `~/.gradle/gradle.properties` (**never committed**):

```
gpr.user=<github-username>
gpr.token=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

In Actions, `GITHUB_ACTOR` + `GITHUB_TOKEN` stand in for the pair — but the
workflow must declare `permissions: packages: read`, and the env must reach the
Gradle step (it is not exported automatically). A workflow in ANOTHER
organisation (ai-platform lives under `DFPartner`) cannot use its own
`GITHUB_TOKEN` for this and needs a real PAT in a secret; ai-platform's
`_reusable-build-gradle.yml` shows the shape, including a preflight that fails
with a rotate-the-token message rather than an opaque 401 mid-resolution.

Note that **GitHub Packages serves nothing anonymously** — not even a public
repository's packages (Gotcha 1). Public-ness buys you a token with no scopes
beyond `read:packages`; it does not buy you no token.

### Consumer setup — TypeScript / npm (`@tatrman/grammar`)

`@tatrman/grammar` is on **public npmjs** (FO ⚑1), so a public consumer needs no
`.npmrc` and no token — a plain `npm i @tatrman/grammar` resolves it. (Commercial
`@tatrman/*` packages served from the managed private registry DO need an
`.npmrc` + token — see `tatrman-platform/PUBLISHING.md` for that estate recipe.)

Depend on the grammar and resolve the raw `.g4` from it:

```jsonc
// packages/{parser,lsp}/package.json
"dependencies": { "@tatrman/grammar": "^0.9.0" }
```
```bash
# regenerate the parser from the dependency's grammar, not a vendored copy:
node -p "require.resolve('@tatrman/grammar/grammar')"   # → …/node_modules/@tatrman/grammar/src/TTR.g4
```

Auth token: a GitHub PAT (classic) with `read:packages` locally
(`export NODE_AUTH_TOKEN=ghp_…`); in CI the auto-provisioned `GITHUB_TOKEN`
(with `permissions: packages: read`) works. GitHub Packages **requires auth even
for reads** (Gotcha 1), so the token is not optional. The named exports
(`PROPERTY_MAP`, `SEARCH_SUB_PROPERTIES`, `TTR_GRAMMAR_VERSION`, types
`DefinitionKind` / `PropertyInfo`) import from `@collite/ttr-grammar` directly.

## Maven Central — the public lane (SV-P1 S4)

The `org.tatrman:*` coordinates are published to **Maven Central** via the
**Central Portal** (`central.sonatype.com`), which is what makes them anonymously
resolvable (RO-17). Wiring landed at SV-P1 S4 (2026-07-12); the Central debut
line is **0.9.4**.

**Plugin.** Each published module applies `com.vanniktech.maven.publish` (Central
Portal mode is its default). The convention adds `publishToMavenCentral()`,
`signAllPublications()` (gated on the signing key's presence so the GH Packages
lane + local builds don't fail unsigned), the full Apache-2.0 POM, and the
sources + javadoc jars Central requires. The root build declares `kotlin.jvm` +
the plugin `apply false` so the shared `MavenCentralBuildService` registers once
across the multi-module build.

**Namespace.** `org.tatrman` is verified to Collite on the Portal via a DNS TXT
record on `tatrman.org` (S0·T2).

**CI secrets** (repo or org secrets on `Collite/ttr-core` + `Collite/ttr-server`;
the names ARE the Gradle property names, so they pass straight through as
`ORG_GRADLE_PROJECT_*` env):

| Secret | Value |
|---|---|
| `ORG_GRADLE_PROJECT_mavenCentralUsername` | Portal **User Token** username (Account → Generate User Token — **not** the login) |
| `ORG_GRADLE_PROJECT_mavenCentralPassword` | Portal User Token password |
| `ORG_GRADLE_PROJECT_signingInMemoryKey` | ASCII-armored GPG **secret** key (`gpg --export-secret-keys --armor <id>`) |
| `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` | that key's passphrase |

The Portal auth is `Authorization: Bearer base64(username:password)`. To check the
secrets without publishing, run the **Validate Maven Central token** workflow
(Actions → Run workflow): HTTP 401 = bad token, 400/404 = valid.

**Release flow (manual, first-run-safe).** A tag push runs
`publishToMavenCentral`, which uploads one signed bundle to the Portal where it
sits **VALIDATED** awaiting a manual **Publish** click (central.sonatype.com →
Deployments). Once trusted, switch the workflow task to
`publishAndReleaseToMavenCentral` for one-click. Central sync (search + CDN) lags
a release by ~15–120 min.

**Proof.** `tatrman-server/scripts/verify-public-resolution/` resolves the whole
spine from `mavenCentral()` only, anonymously — the standing public-access test.

## Gotchas

1. **GitHub Packages requires authentication even for "public" packages** — the
   most-cited quirk, and the reason **Maven Central (above), not GH Packages, is
   the public lane**. Always supply a PAT (`gpr.token`) or `GITHUB_TOKEN` for the
   staging lane, even for a read. Anonymous pulls fail.
2. **No automatic version cleanup** — old versions stay until manually deleted.
   Delete test versions (e.g. `kotlin/v0.0.1-test`) after exercising the
   workflow; sweep stale versions periodically.
3. **PAT rotation** — classic PATs expire (default 90 days). Refresh in
   `~/.gradle/gradle.properties` when consumers start failing auth.
4. **First-time publish bootstrap** — the first publish may need the repo's
   package settings configured (Settings → Packages → "Inherit access from
   source repository"). Confirm on the first tag push.
5. **The ANTLR-tool leak** — `ttr-parser` strips the `antlr` configuration from
   its `api` so only `antlr4-runtime` (not the full `antlr4` code-gen tool)
   appears in the POM. If you touch the parser build, re-inspect the published
   POM. Likewise the test-only conformance dumper lives in `src/test/` so
   `kotlinx-serialization` stays off the runtime classpath.
6. **One tag per module family (RO-24) — no module has two publishers.** *Still
   true, but the families were redrawn on 2026-07-30: the grammar and metadata
   families are ONE family, because they are one `api` closure. See
   [Why one bundle](#why-one-bundle-2026-07-30). The rest of this note is the
   original 2026-07-11 reconciliation and is kept for the history.* The
   `grammar/v*` bundle (was `kotlin/v*` before the 2026-07-16 justfile-sync
   rename) shipped exactly the three grammar-toolchain modules
   (`ttr-parser` + `ttr-writer` + `ttr-semantics`); `ttr-metadata(-git)` was
   published **only** by `metadata/v*` (was `kotlin-metadata/v*`), and
   `ttr-plan-proto` + `ttr-translator` **only** by `translator/v*` (was
   `kotlin-translator/v*`). Historically (through the 0.9.1 grounding release)
   the `else` branch also ran `:ttr-metadata(-git):publish`, so a `grammar/v*`
   and a same-version `metadata/v*` would race to `PUT` the same immutable jar
   and the loser went **409 Conflict** (red run, harmless but noisy). Reconciled
   at **SV-P1·S1·T2** (2026-07-11) by trimming the `else` branch — this table,
   the workflow header, and the workflow logic now agree. Because RO-24 also
   freezes versions once public, **never re-cut an existing `<family>/v<x.y.z>`**
   — bump the version instead. The `just publish` recipe enforces this: it
   refuses a version number already used by either the bare or `-RELEASE` tag
   for that family.

## First-time setup checklist

- [ ] Verify the artifacts build locally:
      `./gradlew -Pversion=0.0.1-LOCAL :packages:kotlin:ttr-parser:publishToMavenLocal :packages:kotlin:ttr-writer:publishToMavenLocal`
      — produces `~/.m2/repository/org/tatrman/*` jars + POMs; confirm
      `ttr-writer`'s POM references `org.tatrman:ttr-parser`. ✅ (done 2026-06-03)
- [ ] Push `grammar/v0.0.1-test` (internal-only — no `release`) to exercise
      `.github/workflows/publish.yml`; confirm it runs green and the packages
      appear under the repo's Packages tab; **delete the test version** afterwards.
- [ ] Configure the repo's GitHub package settings (access inheritance) if the
      first publish needs it.
- [ ] Cut the real `just publish bundle grammar release set 0.1.0` and confirm
      `org.tatrman:ttr-parser:0.1.0` + `org.tatrman:ttr-writer:0.1.0` resolve
      from a fresh Gradle project (both GH Packages and Maven Central).

---

# Publishing — Editor extensions (VS Code / IntelliJ)

The VS Code `.vsix` and IntelliJ plugin `.zip` are released as **GitHub
Releases** (download the asset and install it), tag-driven like the Kotlin
artifacts above. They also follow the **same RELEASE gate** as the registry
lanes: a **bare** `<kind>/vX.Y.Z` tag is an **internal** build (GitHub Release
only, for test installs); a **`<kind>/vX.Y.Z-RELEASE`** tag *additionally*
publishes to the public Marketplace — the **VS Code Marketplace**
(`collite.ttr-modeler-vsc`) for `vscode`, the **JetBrains Marketplace** for
`intellij`. The `-RELEASE` marker is stripped before the version reaches the
Marketplace (published bare X.Y.Z). Unlike the Kotlin version — which lives only
in the tag — each extension's version lives in a tracked file:
`packages/vscode-ext/package.json` (`version`) and
`intellij-plugin/gradle.properties` (`pluginVersion`).

> **Canonical publisher (2026-07-17).** tatrman owns the grammar + IDE support,
> so the `collite.ttr-modeler-vsc` Marketplace listing is published from **this
> repo**. The frozen `modeler` fork no longer publishes to the Marketplace (a
> single id must have one publisher) — its release workflow builds a `.vsix`
> GitHub asset only. tatrman's extension version was reconciled to resume the
> series **above** the last modeler-published `0.9.8`.

## How to release

Run the recipe; it (bumps the version if needed,) builds the version-stamped
artifact, commits the bump, and pushes the branch + a `<kind>/vX.Y.Z[-RELEASE]`
tag (with a confirm prompt that names the lane before pushing). The tag push
triggers [`.github/workflows/release-extensions.yml`](.github/workflows/release-extensions.yml),
which rebuilds the artifact in a clean runner, attaches it to a Release, and — on
a `-RELEASE` tag — publishes to the Marketplace.

**Internal builds** (bare tag → GitHub Release only, for test installs):

```bash
just publish vscode              # patch bump (0.9.8 -> 0.9.9), internal
just publish vscode minor        # 0.9.8 -> 0.10.0, internal
just publish vscode set 0.9.9    # explicit version, internal
just publish intellij            # same forms for the IntelliJ plugin
```

**Public releases** (`-RELEASE` tag → Marketplace + GitHub Release). Add the
`release` keyword. With **no** level it *promotes the current version* — tags the
exact `x.y.z` you already cut/tested internally as `-RELEASE`, no re-bump — which
is the intended "test internally, then ship the same build" flow. With a level it
bumps first:

```bash
just publish vscode release            # promote current x.y.z → Marketplace (no bump)
just publish vscode release patch      # bump patch, then → Marketplace
just publish intellij release set 1.0.0  # set 1.0.0, then → JetBrains Marketplace
```

| Tag | Lane | Built + released asset | Marketplace |
|---|---|---|---|
| `vscode/vX.Y.Z` | internal | `ttr-modeler-vsc-X.Y.Z.vsix` | — |
| `vscode/vX.Y.Z-RELEASE` | public | `ttr-modeler-vsc-X.Y.Z.vsix` | ✅ VS Code (`collite.ttr-modeler-vsc`) |
| `intellij/vX.Y.Z` | internal | `intellij-plugin-X.Y.Z.zip` | — |
| `intellij/vX.Y.Z-RELEASE` | public | `intellij-plugin-X.Y.Z.zip` | ✅ JetBrains Marketplace |

Each Release lands at `https://github.com/Collite/ttr-core/releases` (or
`gh release download <kind>/vX.Y.Z[-RELEASE]`); its notes carry the install
instructions and say whether it reached the Marketplace.

## Notes

- **One build path.** The workflow runs the *same* private `just` recipes
  (`_build-vsix` / `_build-intellij`) the release recipes use locally, so CI and
  local builds never drift. `just _build-vsix <x.y.z>` builds a `.vsix` with no
  version bump or git side-effects.
- **`vsce` is a dev dependency** (`@vscode/vsce` in `packages/vscode-ext`),
  invoked via `pnpm exec vsce` — no global install needed. Its native deps
  (`@vscode/vsce-sign`, `keytar`) are marked `false` under `allowBuilds` in
  `pnpm-workspace.yaml`; their build scripts aren't needed for `vsce package`.
- **VS Code Marketplace publish** (`vsce publish`, publisher `collite`) runs in
  the same `vscode` job **only on a `-RELEASE` tag**, publishing the *same* `.vsix`
  just built (via `--packagePath`) so the Marketplace and the GitHub Release asset
  are byte-identical. It is also gated on the `VSCE_PAT` repo secret — an Azure
  DevOps PAT scoped to *Marketplace → Manage* on the `collite` publisher; if the
  secret is absent the step is skipped and only the GitHub Release is produced.
  ⚠️ Microsoft retires all-accessible-orgs classic PATs in **December 2026** —
  before then this step must move to `vsce publish --azure-credential` (Entra ID /
  OIDC, no PAT).
- **JetBrains Marketplace publish** (`./gradlew publishPlugin`) runs in the
  `intellij` job **only on a `-RELEASE` tag** and only when the token secret is
  present — see the one-time setup below.
- **Branch protection:** the recipe does `git push origin <branch>` for the bump
  commit. If the default branch requires PRs, release from a feature branch (the
  recipe warns when you're not on `master`).
- Smoke-test the workflow with a throwaway **bare** `vscode/v0.0.1-test` tag (it
  builds + creates a real internal Release, no Marketplace push); **delete that
  Release + tag** afterwards.

## IntelliJ Marketplace — one-time setup

The `intellij` job publishes with the IntelliJ Platform Gradle Plugin's
`publishPlugin` task (wired in `intellij-plugin/build.gradle.kts` under
`intellijPlatform { publishing { … } signing { … } }`). It's inert until you
create the listing and add four repo secrets. Do this once:

1. **JetBrains account + Marketplace vendor.** Sign in at
   <https://plugins.jetbrains.com> with the JetBrains account that should own the
   plugin, and create (or join) the **vendor** profile it will be published under.
2. **Create the plugin listing manually — first upload only.** Automated
   `publishPlugin` **cannot create** a plugin; it can only update an existing one.
   Build a signed zip locally and upload it via **Upload plugin** on the
   Marketplace:
   ```bash
   # generate a signing cert + key once (see step 3), export the three env vars, then:
   cd intellij-plugin
   CERTIFICATE_CHAIN="$(cat chain.crt)" PRIVATE_KEY="$(cat private.pem)" \
     PRIVATE_KEY_PASSWORD=… ./gradlew signPlugin
   # → build/distributions/*-<version>.zip  (upload this on the Marketplace)
   ```
   The first upload goes to **moderation** (can take a few business days). After
   it's approved and the plugin has a page, `publishPlugin` updates it on every
   `-RELEASE` tag.
3. **Signing key.** The Marketplace requires signed uploads. Generate a private
   key + certificate chain once (JetBrains' documented recipe):
   ```bash
   openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   ```
   Keep `private.pem`, `chain.crt`, and the key password safe (a password manager /
   the org secret store) — they are **not** committed.
4. **Permanent Marketplace token.** In your Marketplace profile →
   **My Tokens** → generate a permanent token for the vendor.
5. **Add the four repo secrets** (Settings → Secrets and variables → Actions) —
   the workflow maps them to the env vars the Gradle plugin reads:

   | Repo secret | Workflow env | Purpose |
   |---|---|---|
   | `JETBRAINS_MARKETPLACE_TOKEN` | `PUBLISH_TOKEN` | Marketplace upload auth (gates the step) |
   | `JETBRAINS_CERTIFICATE_CHAIN` | `CERTIFICATE_CHAIN` | signing — `chain.crt` contents |
   | `JETBRAINS_PRIVATE_KEY` | `PRIVATE_KEY` | signing — `private.pem` contents |
   | `JETBRAINS_PRIVATE_KEY_PASSWORD` | `PRIVATE_KEY_PASSWORD` | signing — the key password |

   Paste the **contents** of `chain.crt` / `private.pem` (PEM text), not paths.

Once those exist, `just publish intellij release` (or `release <level>`) tags
`-RELEASE`; the workflow signs and uploads the bare `X.Y.Z` (which must not already
be published on the Marketplace). Until the secrets are set, a `-RELEASE` tag still
succeeds — it just produces the GitHub Release only and skips the Marketplace step.
