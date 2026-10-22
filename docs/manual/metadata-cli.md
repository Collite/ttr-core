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
| 1 | Strict load has dropped files or reconciliation errors; also used for an unrecoverable storage/load failure in either mode. |
| 2 | `--expect-version` differs from the build-stamped module version. |
| 3 | The root has no `modeler.toml`. |

Diagnostics and a summary go to stderr. `--json` writes one JSON document to stdout, including on exit 1:

```json
{"version":"0.17.5","sources":[{"path":"model/items.ttrm","objects":1}],"issues":[]}
```

`sources` lists successfully parsed input documents, with paths relative to the checkout and counts of authored top-level definitions. The built-in stock vocabulary is omitted from that file list. Each issue has `file`, `line`, `code`, `severity` and `message`; line numbers are one-based when available, `-1` when unknown. Severities are `error`, `warning` and `info`. A parse failure is `dropped-file`; other load/reconciliation errors, including duplicate qualified names across files, are `reconcile-error`. Warnings retain a category code. Exits 2 and 3 print a human diagnostic without a load report.

The version comes from Gradle's `project.version`, stamped into the CLI jar during the build. Gate builds must use the deployed Veles library version:

```sh
./gradlew --no-configuration-cache -Pversion=0.17.5 :packages:kotlin:ttr-metadata-cli:installDist
packages/kotlin/ttr-metadata-cli/build/install/ttr-metadata/bin/ttr-metadata load /path/to/checkout --expect-version 0.17.5 --strict --json
```

The grammar release bundle publishes `org.tatrman:ttr-metadata-cli` at the same version as `ttr-metadata`, plus a `dist` ZIP containing `bin/ttr-metadata` and `lib/`. Internal tags publish to GitHub Packages; `-RELEASE` tags also publish to Maven Central. The ttr-demo gate builds from `grammar/v<[veles].ttr-metadata>`; its general `[ttr-core].ref` is independent. Enable `[veles].strict-load` when rolling Veles to a release containing this CLI.

Library consumers opt in with `MetadataLoader(...).load(LoadOptions(strict = true))`. `LoadResult.report` contains `issues`, `droppedFiles`, `sources` and `ok`. Strict reporting detects within-source duplicates from the canonical symbol table before they disappear into source maps. Existing `load()` callers retain their previous model and issue behavior; strict mode does not change refresh/registry swap policy.

The root manifest is currently a project-presence check. This command preserves the Kotlin loader's package checks; it does not add a TOML configuration interpreter. In particular, the loader currently reports package/directory mismatch errors even when `[packages].layout = "off"`. Those errors fail strict loading and must be resolved before enabling the deployment's strict gate. An existing checkout loading permissively does not establish that it will pass strict mode.
