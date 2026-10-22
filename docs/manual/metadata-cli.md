# Metadata loader CLI

`ttr-metadata` loads a checkout through the same Kotlin `MetadataLoader`, `FileBasedSource` and stock vocabulary used by serving consumers. It validates cached local inputs; it makes no service calls.

Build from the ttr-core root:

```sh
just build-metadata-cli
packages/kotlin/ttr-metadata-cli/build/install/ttr-metadata/bin/ttr-metadata load /path/to/checkout --strict --json
```

A project has `modeler.toml` at its root and model documents under `model/`. The loader recursively reads `.ttrm` and legacy `.ttr` documents. Layouts and sidecars are outside this gate.

```text
ttr-metadata load <root> [--strict] [--expect-version <v>] [--json]
```

| Exit | Meaning |
|---|---|
| 0 | Loaded. Without `--strict`, recoverable model errors remain in the report. |
| 1 | Strict report has errors (including dropped files); also used for a missing `model/` directory or an unrecoverable storage/load failure in either mode. |
| 2 | `--expect-version` differs from the build-stamped module version. |
| 3 | The root has no `modeler.toml`. |
| 64 | Invalid command usage, such as an unknown flag or a missing/extra root argument. `--help` exits 0. |

Diagnostics and a summary go to stderr. `--json` writes one JSON document to stdout, including on exit 1:

```json
{"version":"0.17.5","sources":[{"path":"model/items.ttrm","objects":1}],"issues":[]}
```

`sources` lists successfully parsed input documents, with paths relative to the checkout and counts of authored top-level definitions. The built-in stock vocabulary is omitted from that file list. Each issue has `file`, `line`, `code`, `severity` and `message`; line numbers are one-based when available, `-1` when unknown. Severities are `error`, `warning` and `info`. Parse failures use `dropped-file`. Every other severity retains the category slug: `duplicate-qname`, `package-mismatch`, `semantics-invalid`, `unresolved-reference`, `ambiguous-reference`, `unimported-reference`, `wrong-file-kind`, `protected-qname`, `binding-inconsistency`, `world-malformed`, `storage-unreadable`, `parse-error`, or `other`. CLI input diagnostics additionally use `manifest-invalid`, `manifest-package-layout`, and `no-model-dir`. Duplicate messages use storage-relative locations such as `model/b.ttrm:2`. Exits 2 and 3 print a human diagnostic without a load report.

The version comes from Gradle's `project.version`, stamped into the CLI jar during the build. Gate builds must use the deployed Veles library version:

```sh
./gradlew --no-configuration-cache -Pversion=0.17.5 :packages:kotlin:ttr-metadata-cli:installDist
packages/kotlin/ttr-metadata-cli/build/install/ttr-metadata/bin/ttr-metadata load /path/to/checkout --expect-version 0.17.5 --strict --json
```

The grammar release bundle publishes `org.tatrman:ttr-metadata-cli` at the same version as `ttr-metadata`, plus a `dist` ZIP containing `bin/ttr-metadata` and `lib/`. Internal tags publish to GitHub Packages; `-RELEASE` tags also publish to Maven Central. The ttr-demo gate builds from `grammar/v<[veles].ttr-metadata>`; its general `[ttr-core].ref` is independent. Enable `[veles].strict-load` when rolling Veles to a release containing this CLI.

Library consumers opt in with `MetadataLoader(...).load(LoadOptions(strict = true))`. `LoadResult.report` is null unless strict reporting was requested; a collected report contains `issues`, `droppedFiles`, `sources` (including source identity) and `ok`. Strict reporting detects within-source duplicates from the canonical symbol table before they disappear into source maps, using the same duplicate-definition rule as the semantic Validator. Existing `load()` callers retain their previous model and issue behavior; strict mode does not change refresh/registry swap policy.

The CLI reads `[packages].layout` from `modeler.toml` using TOML parsing. Package-declaration mismatches follow TypeScript lint policy: `"off"` suppresses them, `"flexible"` (the default) reports warnings, and `"strict"` reports errors. Malformed TOML applies the default policy and adds located `manifest-invalid` errors; strict loading fails. An unknown layout value applies the default and adds a `manifest-package-layout` warning. A missing `model/` directory returns `no-model-dir` and exit 1 in either mode.

Library callers pass this policy through `LoadOptions(strict = true, packageLayout = PackageLayout.Off)` (or `Flexible` / `Strict`). The Studio BFF must read it from the branch's own manifest. This grading changes only the strict report: legacy `LoadResult.issues`, declared-package model construction, and serving refresh behavior remain unchanged (Bora's ruling ⚑R126-A).
