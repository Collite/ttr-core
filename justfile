# Tatrman — task runner
# Run `just` to list recipes.
#
# Recipe conventions (synced across kantheon/modeler/tatrman/tatrman-server,
# 2026-07-16 — see project/ for the cross-repo decision):
#   lint / build / test    bare = everything; `just build ttr-parser` (name) or
#                           `just build packages/kotlin/ttr-parser` (path) = one module.
#   lint-ts/build-ts/test-ts, lint-kt/build-kt/test-kt   same name/path/bare rules,
#                           scoped to one language lane.
#   publish                 unified release entry point — see its own doc comment.

set shell := ["bash", "-uc"]

# pnpm-aware repo paths
vscode_ext   := "packages/vscode-ext"
ghblob       := "https://github.com/Collite/ttr-core/blob/master/packages/vscode-ext"
ghraw        := "https://raw.githubusercontent.com/Collite/ttr-core/master/packages/vscode-ext"
# IntelliJ plugin resource root (server bundle + grammars land here; gitignored)
intellij_res := "intellij-plugin/src/main/resources"

# List available recipes
default:
    @just --list

# ── Module resolution ─────────────────────────────────────────────────────────

# Resolve a bare module name to its on-disk path (packages/<name>,
# packages/kotlin/<name>, or packages/python/<name>). A path (contains "/") passes
# through unchanged. Errors on ambiguity — e.g. `ttr-parser` exists under both
# packages/kotlin/ and packages/python/; use the path form to disambiguate.
_resolve name:
    #!/usr/bin/env bash
    set -euo pipefail
    if [[ "{{name}}" == *"/"* ]]; then
        echo "{{name}}"
        exit 0
    fi
    matches=$(find packages -mindepth 1 -maxdepth 2 -type d -name "{{name}}" 2>/dev/null | sort)
    count=$(echo "$matches" | grep -c . || true)
    if [ "$count" -eq 0 ]; then
        echo "❌ Module '{{name}}' not found under packages/" >&2
        exit 1
    elif [ "$count" -gt 1 ]; then
        echo "❌ '{{name}}' is ambiguous:" >&2
        echo "$matches" | sed 's/^/    /' >&2
        echo "   Use the path form to disambiguate, e.g. packages/kotlin/{{name}}." >&2
        exit 1
    fi
    echo "$matches"

# Which lane a resolved path builds under (kt | py | ts).
_lang path:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -f "{{path}}/build.gradle.kts" ]; then echo kt
    elif [ -f "{{path}}/pyproject.toml" ]; then echo py
    elif [ -f "{{path}}/package.json" ]; then echo ts
    else
        echo "❌ Can't tell what language {{path}} is (no build.gradle.kts / pyproject.toml / package.json)" >&2
        exit 1
    fi

# ── lint / build / test — bare = everything, name/path = one module ───────────

# Lint everything (TS + Kotlin + Python). One module: `just lint ttr-parser`.
lint module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        just lint-ts
        just lint-kt
        just lint-py
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    lang=$(just _lang "$path")
    case "$lang" in
        ts) just lint-ts "$path" ;;
        kt) just lint-kt "$path" ;;
        py) just lint-py "$path" ;;
    esac

# Build everything (TS + Kotlin + Python). One module: `just build ttr-parser`.
build module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        just build-ts
        just build-kt
        just build-py
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    lang=$(just _lang "$path")
    case "$lang" in
        ts) just build-ts "$path" ;;
        kt) just build-kt "$path" ;;
        py) just build-py "$path" ;;
    esac

# Test everything (TS + Kotlin + Python). One module: `just test ttr-parser`.
test module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        just test-ts
        just test-kt
        just test-py
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    lang=$(just _lang "$path")
    case "$lang" in
        ts) just test-ts "$path" ;;
        kt) just test-kt "$path" ;;
        py) just test-py "$path" ;;
    esac

# Typecheck everything (TS only — kept alongside lint/build/test as its own gate,
# mirroring `pnpm -r typecheck`).
typecheck:
    pnpm -r typecheck

# ── TS lane (pnpm workspace) ────────────────────────────────────────────────────

# Lint every TS package, or one: `just lint-ts` / `just lint-ts packages/parser`.
lint-ts path="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{path}}" ]; then pnpm -r lint
    else name=$(node -p "require('./{{path}}/package.json').name"); pnpm --filter "$name" lint
    fi

# Build every TS package, or one.
build-ts path="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{path}}" ]; then pnpm -r build
    else name=$(node -p "require('./{{path}}/package.json').name"); pnpm --filter "$name..." build
    fi

# Test every TS package, or one.
test-ts path="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{path}}" ]; then pnpm -r test
    else name=$(node -p "require('./{{path}}/package.json').name"); pnpm --filter "$name" test
    fi

# ── Kotlin lane (Gradle) ────────────────────────────────────────────────────────

# ktlintCheck across every Kotlin module that applies it, or one module:
# `just lint-kt` / `just lint-kt ttr-parser` / `just lint-kt packages/kotlin/ttr-parser`.
lint-kt module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then ./gradlew ktlintCheck
    else
        path=$(just _resolve "{{module}}")
        gradle_path=":$(echo "$path" | sed 's|/|:|g')"
        ./gradlew "${gradle_path}:ktlintCheck"
    fi

# Build every Kotlin module, or one.
build-kt module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then ./gradlew build
    else
        path=$(just _resolve "{{module}}")
        gradle_path=":$(echo "$path" | sed 's|/|:|g')"
        ./gradlew "${gradle_path}:build"
    fi

# Test every Kotlin module, or one.
test-kt module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then ./gradlew test
    else
        path=$(just _resolve "{{module}}")
        gradle_path=":$(echo "$path" | sed 's|/|:|g')"
        ./gradlew "${gradle_path}:test"
    fi

# ── Python lane (uv + ruff + pytest) — packages/python/* ───────────────────────

# ruff-lint every Python package, or one: `just lint-py` / `just lint-py packages/python/ttr-parser`
# (path form — both packages/python module names collide with same-named Kotlin modules).
lint-py module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        for d in packages/python/*/; do just lint-py "${d%/}"; done
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    cd "$path" && uv run ruff check .

# Sync (install) every Python package's frozen lock, or one.
build-py module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        for d in packages/python/*/; do just build-py "${d%/}"; done
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    cd "$path" && uv sync

# pytest every Python package, or one: `just test-py ttr-parser` (path form —
# bare "ttr-parser" collides with the Kotlin module of the same name).
test-py module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        for d in packages/python/*/; do just test-py "${d%/}"; done
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    cd "$path" && uv run pytest

# ── Conformance (TS ⟷ Kotlin ⟷ Python grammar parity) ───────────────────────────

conformance:
    pnpm --filter @tatrman/integration-tests run conformance

# ── Grammar regeneration ────────────────────────────────────────────────────────

# Regenerate the TS parser from TTR.g4, then the TextMate grammar. See CLAUDE.md
# § Grammar regeneration for the full procedure and when to run each step.
regen-grammar:
    pnpm --filter @tatrman/parser run prebuild
    cd packages/vscode-ext && node scripts/generate-tm-grammar.ts

# ── Editor extensions (GitHub Releases — no internal/external duality) ─────────

# Build the self-contained .vsix at the version already in package.json (no bump,
# no git). `just publish vscode` calls this after bumping; call it directly for a
# plain local build, e.g. `just _build-vsix 0.1.0`.
#
# vsce can't follow pnpm's symlinked node_modules, so instead of shipping the
# dependency tree we inline everything into self-contained bundles and package
# with `--no-dependencies`. Steps:
#   1. build @tatrman/lsp and its workspace deps (parser/semantics/edit/...).
#   2. compile the extension's own TypeScript (typecheck + .d.ts).
#   3. bundle the extension entry (inlines vscode-languageclient) → dist/extension.js.
#   4. bundle a fully self-contained LSP server (all deps inlined) to
#      dist/server/server-stdio.mjs — what extension.ts loads when packaged.
#   5. vsce package --no-dependencies (tree is self-contained; skip pnpm).
_build-vsix version:
    pnpm --filter @tatrman/lsp... build
    pnpm --filter ttr-modeler-vsc run build
    just _bundle-extension
    just _bundle-lsp-server
    cd {{vscode_ext}} && pnpm exec vsce package --no-dependencies \
        --baseContentUrl {{ghblob}} \
        --baseImagesUrl {{ghraw}} \
        --out ttr-modeler-vsc-{{version}}.vsix
    @echo "✓ Packaged {{vscode_ext}}/ttr-modeler-vsc-{{version}}.vsix"

# Bundle the extension entry into a self-contained CJS file. The `tsc` build
# above emits `dist/extension.js` with a bare `require("vscode-languageclient")`,
# but `vsce package --no-dependencies` ships no node_modules — so that require
# would throw at activation and the language client (and its output channel)
# would never start. Re-emit `dist/extension.js` with the client inlined; only
# `vscode` stays external (the editor provides it at runtime).
_bundle-extension:
    pnpm --filter @tatrman/lsp exec esbuild "$PWD/{{vscode_ext}}/src/extension.ts" \
        --bundle --platform=node --format=cjs --target=es2022 \
        --external:vscode \
        --outfile="$PWD/{{vscode_ext}}/dist/extension.js"

# Inline the LSP server (all @tatrman/* + antlr4ng + vscode-languageserver) into
# one ESM file the packaged extension loads directly — no node_modules needed.
# Output stays ESM (the server uses import.meta.url); the createRequire banner
# lets the bundled CJS deps require() Node builtins.
#
# The server reads stock vocabulary (.ttrm) from disk at runtime via
# @tatrman/semantics' stock-loader, whose first search path is `<dir>/stock/`
# relative to the server file. esbuild can't inline those data files, so copy
# them next to the bundle — without them, all `cnc.role.*` references go
# unresolved in the packaged extension.
_bundle-lsp-server server_dir=(vscode_ext / "dist/server"):
    mkdir -p {{server_dir}}/stock
    pnpm --filter @tatrman/lsp exec esbuild src/server-stdio.ts \
        --bundle --platform=node --format=esm --target=es2022 \
        --external:vscode \
        --banner:js="import{createRequire as ___cr}from'node:module';const require=___cr(import.meta.url);" \
        --outfile="$PWD/{{server_dir}}/server-stdio.mjs"
    cp packages/semantics/src/stock/*.ttrm {{server_dir}}/stock/

# Build the plugin .zip at the version already in gradle.properties (no bump, no
# git). `just publish intellij` calls this after bumping; call it directly for a
# plain local build.
#
# The plugin is a thin LSP4IJ launcher around the SAME fully-inlined LSP server
# the .vsix ships (server-stdio.mjs + stock/*.ttrm). Steps:
#   1. build @tatrman/lsp and its workspace deps.
#   2. bundle the inlined server (all deps inlined; only `vscode` external) into
#      the plugin's resources — this is the build input the Gradle build verifies.
#   3. gradle buildPlugin — copyLspBundle pulls in both TextMate grammars, then
#      the server + grammars are shipped UNPACKED in the plugin home (node runs
#      the .mjs from disk; the TextMate engine reads the grammars from a dir).
# Build order matters: the bundle step MUST precede gradle (copyLspBundle fails
# fast if server-stdio.mjs is absent). See docs/features/intellij/.
_build-intellij:
    pnpm --filter @tatrman/lsp... build
    just _bundle-lsp-server {{intellij_res}}/server
    cd intellij-plugin && ./gradlew buildPlugin
    @echo "✓ Packaged intellij-plugin/build/distributions/intellij-plugin-*.zip"

# Shared release flow for the editor extensions (used by `publish vscode` /
# `publish intellij`; call those, not this). Unlike the Maven/PyPI/npm lanes below
# — whose version lives only in the git tag — the extension version lives in a
# tracked file (package.json / gradle.properties), so this bumps that file, builds
# the version-stamped artifact, then commits the bump and pushes the commit + tag.
# No RELEASE concept applies here: every vscode/intellij tag already produces a
# public GitHub Release (release-extensions.yml), there is no internal-only lane.
#
#   1. refuse a dirty tree (the bump must be the only change we commit)
#   2. read the current version from the file; compute the next (patch default,
#      or minor / major / set <ver>)
#   3. confirm, then bump the file and build the artifact (file restored if the
#      build fails, so an aborted release never leaves the tree dirty)
#   4. commit the bump, tag `<kind>/v<x.y.z>`, push the branch + tag
_release-ext kind release="false" level="" version="":
    #!/usr/bin/env bash
    set -euo pipefail

    KIND="{{kind}}"
    RELEASE="{{release}}"
    LEVEL="{{level}}"
    CUSTOM_VERSION="{{version}}"

    case "$KIND" in
        vscode)
            FILE="{{vscode_ext}}/package.json"
            CURRENT=$(node -p "require('./$FILE').version") ;;
        intellij)
            FILE="intellij-plugin/gradle.properties"
            CURRENT=$(sed -n 's/^pluginVersion=//p' "$FILE") ;;
        *) echo "❌ Unknown extension '$KIND' (expected 'vscode' or 'intellij')."; exit 1 ;;
    esac

    # Mode. With no level: a `release` PROMOTES the current version (tag the
    # already-cut x.y.z as -RELEASE, no bump — this is how you push the exact build
    # you tested internally to the Marketplace); an internal cut defaults to patch.
    PROMOTE=false
    if [ -z "$LEVEL" ]; then
        if [ "$RELEASE" = "true" ]; then PROMOTE=true; else LEVEL="patch"; fi
    fi
    if [ "$PROMOTE" = false ]; then
        case "$LEVEL" in
            major|minor|patch|set) ;;
            *) echo "❌ Level must be 'major', 'minor', 'patch', or 'set'."; exit 1 ;;
        esac
        if [ "$LEVEL" = "set" ] && [ -z "$CUSTOM_VERSION" ]; then
            echo "❌ 'set' requires a version. E.g. just publish $KIND release set 0.3.0"; exit 1
        fi
    fi

    # The bump commit (if any) and the tag are pushed, so start from a clean tree.
    if [ -n "$(git status --porcelain)" ]; then
        echo "❌ Working tree is dirty — commit or stash before cutting a release."; exit 1
    fi

    BRANCH=$(git rev-parse --abbrev-ref HEAD)
    if [ "$BRANCH" != "master" ] && [ "$BRANCH" != "main" ]; then
        read -p "⚠️  On branch '$BRANCH', not master. Release from here anyway? [y/N] " -n 1 -r; echo ""
        [[ ${REPLY:-} =~ ^[Yy]$ ]] || { echo "❌ Aborting."; exit 1; }
    fi

    # Target version.
    if [ "$PROMOTE" = true ]; then
        NEW_VERSION="${CURRENT:-0.0.0}"
    elif [ "$LEVEL" = "set" ]; then
        if ! [[ "$CUSTOM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "❌ '$CUSTOM_VERSION' is not a valid X.Y.Z version."; exit 1
        fi
        NEW_VERSION="$CUSTOM_VERSION"
    else
        BASE="${CURRENT:-0.0.0}"
        IFS='.' read -r MAJOR MINOR PATCH <<< "${BASE%%-*}"
        case "$LEVEL" in
            major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
            minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
            patch) PATCH=$((PATCH + 1)) ;;
        esac
        NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
    fi

    # Internal builds are bare x.y.z; a release adds the -RELEASE marker the
    # workflow uses to gate the public Marketplace push (stripped before the
    # version reaches any Marketplace — published bare, like the registry lanes).
    SUFFIX=""; [ "$RELEASE" = "true" ] && SUFFIX="-RELEASE"
    NEW_TAG="${KIND}/v${NEW_VERSION}${SUFFIX}"
    if git rev-parse -q --verify "refs/tags/${NEW_TAG}" >/dev/null; then
        echo "❌ Tag ${NEW_TAG} already exists."; exit 1
    fi

    if [ "$RELEASE" = "true" ]; then LANE="PUBLIC → Marketplace + GitHub Release"; else LANE="internal → GitHub Release only"; fi
    echo "────────────────────────────────────────────────────────────"
    echo "  Extension        : ${KIND}"
    echo "  Current version  : ${CURRENT:-0.0.0}"
    echo "  Target version   : ${NEW_VERSION}$([ "$PROMOTE" = true ] && echo '   (promote — no bump)' || true)"
    echo "  Tag              : ${NEW_TAG}"
    echo "  Lane             : ${LANE}"
    echo "  Push to          : ${BRANCH}"
    echo "────────────────────────────────────────────────────────────"
    read -p "Proceed — build, tag ${NEW_TAG}, and push? [y/N] " -n 1 -r; echo ""
    [[ ${REPLY:-} =~ ^[Yy]$ ]] || { echo "❌ Aborting."; exit 1; }

    # Bump the version file unless promoting. Restore on failure so an aborted
    # release never leaves a dirty tree.
    if [ "$PROMOTE" = false ]; then
        trap 'git checkout -- "$FILE" 2>/dev/null || true' ERR
        case "$KIND" in
            vscode)   perl -0777 -pi -e 's/("version":\s*)"[^"]*"/${1}"'"$NEW_VERSION"'"/' "$FILE" ;;
            intellij) perl -pi -e "s/^pluginVersion=.*/pluginVersion=${NEW_VERSION}/" "$FILE" ;;
        esac
    fi
    case "$KIND" in
        vscode)   just _build-vsix "$NEW_VERSION" ;;
        intellij) just _build-intellij ;;
    esac
    [ "$PROMOTE" = false ] && trap - ERR || true

    # Commit only if the bump changed the file (promotion / set-to-same leaves it
    # unchanged — nothing to commit, just tag the current HEAD).
    if [ -n "$(git status --porcelain "$FILE")" ]; then
        git add "$FILE"
        git commit -m "${KIND}: release v${NEW_VERSION}${SUFFIX}"
        git push origin "${BRANCH}"
    fi
    git tag -a "${NEW_TAG}" -m "Release ${KIND} ${NEW_VERSION}${SUFFIX}"
    git push origin "${NEW_TAG}"
    echo "✅ Released ${NEW_TAG} — ${LANE}."

# ── publish — unified release entry point ───────────────────────────────────────
#
# Tags the repo; the matching GitHub Actions workflow (publish.yml / publish-
# python.yml / publish-ts.yml) does the actual build+publish when it sees the tag.
# `vscode`/`intellij` bump a tracked version file instead of the tag — see
# _release-ext above — but follow the SAME RELEASE gate: a bare tag is an internal
# build (GitHub Release only), a `-RELEASE` tag also publishes to the public
# Marketplace (VS Code / JetBrains).
#
# Internal targets (GH Packages staging / GH Packages npm / GitHub Release) get
# EVERY tag. External targets (Maven Central / PyPI / VS Code + JetBrains
# Marketplace) only fire when the tag is marked RELEASE — a published RELEASE
# version is ALWAYS the bare `x.y.z` (the `-RELEASE` marker is stripped before it
# ever reaches a registry/marketplace; see publish.yml/publish-python.yml/
# release-extensions.yml).
# This is the 2026-07-16 change: previously bare tags went public and `-rc`
# suffixes stayed internal — inverted, because internal patches vastly outnumber
# real releases, and a release now needs to be marked explicitly.
#
# `what`:
#   a module name or path — ttr-parser, ttr-semantics, ttr-writer, ttr-metadata,
#     ttr-metadata-git, ttr-plan-proto, ttr-translator (Kotlin/Maven); ttr-parser,
#     ttr-plan-proto under packages/python/ (PyPI — use the PATH form, bare
#     `ttr-parser` is ambiguous with the Kotlin module of the same name)
#   ts-grammar       the published TS grammar (packages/grammar), @tatrman/grammar
#                    (public npmjs, FO ⚑1 — note the tag prefix ts-grammar/v*, distinct from
#                    the Kotlin `bundle grammar` below despite the similar name; external cut
#                    gated on Bora + NPM_TOKEN per FO ⚑2)
#   vscode | intellij  editor extensions (GitHub Release always; Marketplace on RELEASE)
#   bundle <name>    a named release lane — a lockstep set published together at one version:
#                    grammar    THE TTR toolchain: ttr-parser + ttr-writer + ttr-semantics +
#                               ttr-metadata + ttr-metadata-git + ttr-snapshot + ttr-md-resolver +
#                               ttr-lexicon + ttr-lexicon-compile + ttr-lexicon-cli.
#                               Unified 2026-07-30 (absorbed the old `metadata` bundle) because
#                               all of them are one `api` closure — see the case arm below.
#                               The two lexicon modules joined 2026-08-03 (RV-P1.2/P1.3), the
#                               lexicon CLI 2026-08-06 (RV-P3.1).
#                    translator ttr-plan-proto + ttr-translator (grammar-INDEPENDENT: the plan
#                               wire format may break on its own schedule)
#                    validator  org.tatrman:ttr-validator-spi — the ⑤ C-5-i plugin SPI, one module
#                               today, named a bundle so more can join its lockstep later
#                    security-gen  org.tatrman:ttr-security-gen — the ⑤ H-1 security-block → Rego
#                               generator; Perun consumes it as a PUBLISHED artifact, P2
#
# Usage:
#   just publish ttr-parser                          # internal, patch bump
#   just publish ttr-parser minor                     # internal, minor bump
#   just publish ttr-parser set 0.6.0                  # internal, explicit version
#   just publish ttr-parser release                    # RELEASE (+ Central), patch bump
#   just publish ttr-parser release minor               # RELEASE, minor bump
#   just publish ttr-parser release set 0.6.0            # RELEASE, explicit version
#   just publish packages/python/ttr-parser release       # PyPI release (path form)
#   just publish ts-grammar set 4.4.0                       # TS grammar, explicit version
#   just publish bundle grammar release set 1.0.0            # Kotlin bundle release
#   just publish vscode                                        # ext: internal build, patch bump
#   just publish vscode minor                                   # ext: internal build, minor bump
#   just publish vscode release                                  # ext: promote current x.y.z → Marketplace
#   just publish intellij release minor                          # ext: bump minor, publish to Marketplace
publish *args:
    #!/usr/bin/env bash
    set -euo pipefail

    ARGS=({{args}})
    WHAT="${ARGS[0]:-}"
    NEXT=1
    if [ "$WHAT" = "bundle" ]; then
        WHAT="bundle ${ARGS[1]:-}"
        NEXT=2
    fi
    if [ -z "$WHAT" ] || [ "$WHAT" = "bundle " ]; then
        echo "❌ Usage: just publish <module|path|grammar|vscode|intellij|bundle NAME> [release] [major|minor|patch|set VERSION]" >&2
        exit 1
    fi
    REST=("${ARGS[@]:$NEXT}")

    RELEASE=false
    if [ "${REST[0]:-}" = "release" ]; then
        RELEASE=true
        REST=("${REST[@]:1}")
    fi
    LEVEL="${REST[0]:-patch}"
    CUSTOM_VERSION="${REST[1]:-}"

    # vscode / intellij — file-bump flow (_release-ext). RELEASE-gated like the
    # registry lanes: a bare tag is an INTERNAL build (GitHub Release only, for
    # test installs); a `release` cut tags `-RELEASE`, which the workflow promotes
    # to the public Marketplace (VS Code / JetBrains). `release` with no level
    # PROMOTES the current version (tags the already-cut x.y.z as -RELEASE); with a
    # level it bumps first. The `-RELEASE` marker is stripped before the version
    # reaches any Marketplace (published bare, same as the registry lanes).
    if [ "$WHAT" = "vscode" ] || [ "$WHAT" = "intellij" ]; then
        exec just _release-ext "$WHAT" "$RELEASE" "${REST[0]:-}" "${REST[1]:-}"
    fi

    case "$LEVEL" in
        major|minor|patch|set) ;;
        *) echo "❌ Level must be 'major', 'minor', 'patch', or 'set'."; exit 1 ;;
    esac
    if [ "$LEVEL" = "set" ] && [ -z "$CUSTOM_VERSION" ]; then
        echo "❌ 'set' requires a version. E.g. just publish $WHAT set 0.6.0"; exit 1
    fi

    # Resolve WHAT -> tag PREFIX + human description of what it publishes.
    case "$WHAT" in
        "bundle grammar")
            # THE unified TTR toolchain bundle (2026-07-30): grammar + the metadata family.
            # These are one `api` closure — ttr-metadata api's ttr-parser/writer/semantics
            # and ttr-md-resolver, ttr-snapshot/ttr-metadata-git api ttr-metadata — so `api`
            # writes the exact version into every published POM. Publishing half of them is an
            # unresolvable build for consumers, which is exactly what metadata 0.10.3/0.10.4 did
            # (they reference a ttr-parser:0.10.x that was never cut, and they are permanently
            # dead on Central). One bundle makes that skew impossible.
            #
            # JOINED 2026-08-03 (RV-P1.2/P1.3): ttr-lexicon + ttr-lexicon-compile. The compiler
            # `implementation`s ttr-parser/ttr-metadata/ttr-snapshot and `api`s ttr-lexicon, and
            # BOTH scopes write exact versions into its POM — so it is as version-coupled to this
            # set as ttr-metadata is. Giving ttr-lexicon its own lane would recreate the 0.10.3
            # failure exactly: a ttr-lexicon-compile POM naming a ttr-lexicon version nothing
            # forced to be cut. One version line, one tag.
            #
            # JOINED 2026-08-06 (RV-P3.1): ttr-lexicon-cli — the `ttr-lexicon build` command. It
            # `api`s ttr-lexicon-compile and `implementation`s ttr-metadata + ttr-snapshot, i.e.
            # the same closure again; it is also the ONE artifact an estate actually runs, so a
            # cut where the CLI lags the compiler it drives is the worst possible skew.
            PREFIX=grammar
            DESC="org.tatrman:{ttr-parser, ttr-writer, ttr-semantics, ttr-metadata, ttr-metadata-git, ttr-snapshot, ttr-md-resolver, ttr-lexicon, ttr-lexicon-compile, ttr-lexicon-cli}" ;;
        "bundle metadata")
            echo "❌ The 'metadata' bundle no longer exists — it was folded into 'grammar' on 2026-07-30." >&2
            echo "   ttr-metadata \`api\`s the grammar artifacts, so the two MUST share a version;" >&2
            echo "   separate bundles allowed metadata 0.10.3/0.10.4 to be cut against a grammar" >&2
            echo "   version that was never published (broke tatrman-platform for 3 days)." >&2
            echo "   Use: just publish bundle grammar ${REST[*]:-}" >&2
            exit 1 ;;
        "bundle translator")
            PREFIX=translator
            DESC="org.tatrman:{ttr-plan-proto, ttr-translator}" ;;
        "bundle validator")
            PREFIX=validator
            DESC="org.tatrman:ttr-validator-spi (⑤ C-5-i plugin SPI)" ;;
        "bundle security-gen")
            PREFIX=security-gen
            DESC="org.tatrman:ttr-security-gen (⑤ H-1 security-block → Rego generator)" ;;
        bundle*)
            echo "❌ Unknown bundle '${WHAT#bundle }'. Valid: grammar | translator | validator | security-gen" >&2
            exit 1 ;;
        ts-grammar)
            PREFIX=ts-grammar
            DESC="@tatrman/grammar (public npmjs)" ;;
        *)
            MOD_PATH=$(just _resolve "$WHAT")
            MOD_NAME=$(basename "$MOD_PATH")
            case "$MOD_PATH" in
                packages/kotlin/ttr-parser|packages/kotlin/ttr-semantics|packages/kotlin/ttr-writer)
                    PREFIX="$MOD_NAME"; DESC="org.tatrman:${MOD_NAME}" ;;
                packages/kotlin/ttr-metadata|packages/kotlin/ttr-metadata-git|packages/kotlin/ttr-snapshot|packages/kotlin/ttr-md-resolver)
                    echo "❌ '$MOD_NAME' publishes lockstep only — use: just publish bundle grammar" >&2
                    echo "   (the metadata family joined the grammar bundle on 2026-07-30 — one api closure, one version)" >&2
                    exit 1 ;;
                packages/kotlin/ttr-plan-proto|packages/kotlin/ttr-translator)
                    echo "❌ '$MOD_NAME' publishes lockstep only — use: just publish bundle translator" >&2
                    exit 1 ;;
                packages/kotlin/ttr-validator-spi)
                    echo "❌ '$MOD_NAME' publishes via its bundle — use: just publish bundle validator" >&2
                    exit 1 ;;
                packages/python/ttr-parser)
                    PREFIX=python; DESC="ttr-parser (PyPI)" ;;
                packages/python/ttr-plan-proto)
                    PREFIX=python-plan; DESC="ttr-plan-proto (PyPI)" ;;
                *)
                    echo "❌ '$WHAT' ($MOD_PATH) has no publish lane defined." >&2
                    exit 1 ;;
            esac
            ;;
    esac

    # A release must come from a clean, committed state — CI checks out the tag,
    # and pushing the tag carries its commit to the remote.
    if [ -n "$(git status --porcelain)" ]; then
        echo "❌ Working tree is dirty — commit or stash before cutting a release."; exit 1
    fi

    BRANCH=$(git rev-parse --abbrev-ref HEAD)
    if [ "$BRANCH" != "master" ] && [ "$BRANCH" != "main" ]; then
        read -p "⚠️  On branch '$BRANCH', not master. Tag this commit anyway? [y/N] " -n 1 -r; echo ""
        [[ ${REPLY:-} =~ ^[Yy]$ ]] || { echo "❌ Aborting."; exit 1; }
    fi

    # Single version line per prefix — internal and RELEASE tags share it (a
    # RELEASE tag always mints a brand-new number, never reuses one already spent
    # by an internal tag), so a stripped RELEASE version never collides with an
    # already-published internal one on the same registry.
    LATEST=$(git tag -l "${PREFIX}/v*" | sed -E "s|^${PREFIX}/v||; s/-RELEASE\$//" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1 || true)
    LATEST="${LATEST:-0.0.0}"

    if [ "$LEVEL" = "set" ]; then
        if ! [[ "$CUSTOM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "❌ '$CUSTOM_VERSION' is not a valid bare semver (X.Y.Z) — RELEASE markers are added automatically, don't include one."; exit 1
        fi
        NEW_VERSION="$CUSTOM_VERSION"
    else
        IFS='.' read -r MAJOR MINOR PATCH <<< "$LATEST"
        case "$LEVEL" in
            major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
            minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
            patch) PATCH=$((PATCH + 1)) ;;
        esac
        NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
    fi

    if git rev-parse -q --verify "refs/tags/${PREFIX}/v${NEW_VERSION}" >/dev/null || \
       git rev-parse -q --verify "refs/tags/${PREFIX}/v${NEW_VERSION}-RELEASE" >/dev/null; then
        echo "❌ Version ${NEW_VERSION} already used (as a bare or RELEASE tag) for ${PREFIX}."; exit 1
    fi

    NEW_TAG="${PREFIX}/v${NEW_VERSION}"
    [ "$RELEASE" = true ] && NEW_TAG="${NEW_TAG}-RELEASE"

    if [ "$RELEASE" = true ]; then
        LANES="GH Packages/PyPI (internal) + the public registry (Maven Central / PyPI) — published as bare ${NEW_VERSION}"
    else
        LANES="GH Packages/PyPI (internal) ONLY — not marked RELEASE, no public-registry step runs"
    fi

    echo "────────────────────────────────────────────────────────────"
    echo "  Latest published : ${LATEST}"
    echo "  New version      : ${NEW_VERSION}   →  tag ${NEW_TAG}"
    echo "  Commit           : $(git rev-parse --short HEAD) on ${BRANCH}"
    echo "  Publishes        : ${DESC}"
    echo "  Lanes            : ${LANES}"
    echo "  ⚠️  Published registry versions are PERMANENT — they cannot be deleted."
    echo "────────────────────────────────────────────────────────────"
    read -p "Create and push ${NEW_TAG}? [y/N] " -n 1 -r; echo ""
    [[ ${REPLY:-} =~ ^[Yy]$ ]] || { echo "❌ Aborting."; exit 1; }

    git tag -a "${NEW_TAG}" -m "Release ${NEW_VERSION}"
    git push origin "${NEW_TAG}"
    echo "✅ Pushed ${NEW_TAG} — the matching workflow will publish: ${LANES}"
    echo "   Watch it: gh run watch  (or the repo's Actions tab)"
