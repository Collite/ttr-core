# Model migrations

## Canonical YAML

`migrate-yaml` sends each explicit input file to the Kotlin canonical codec. It recognizes the
required `ttr` marker through that codec and refuses unmarked foreign YAML, profile declarations,
unsupported marker versions and unregistered version pairs. TypeScript does not parse YAML.

Canonical YAML first exists at grammar **0.21**. The only registered pair is currently **0.21 → 0.21**:
normalize spelling/layout and reach an idempotent fixed point. There is no historical YAML or dialect
transform to infer. A future grammar change must register and test its transform explicitly.
Versions are grammar major.minor strings; a bundle patch such as `0.21.0` is not a syntax version.

Build the local bridge (Java 21) and the migrator:

```sh
./gradlew :packages:kotlin:ttr-yaml:installDist
pnpm --filter '@tatrman/migrate...' build
export TTR_YAML_BRIDGE="$PWD/packages/kotlin/ttr-yaml/build/install/ttr-yaml-migrate/bin/ttr-yaml-migrate"
pnpm --filter @tatrman/migrate cli -- migrate-yaml /path/to/model.yaml --from 0.21 --to 0.21 --dry-run
pnpm --filter @tatrman/migrate cli -- migrate-yaml /path/to/model.yaml --from 0.21 --to 0.21
```

Alternatively pass `--bridge /path/to/ttr-yaml-migrate`. The launcher path is executed directly,
including when it contains spaces. The bridge processes one UTF-8 JSON request on stdin and returns
`{source, changed}` on stdout; errors are located on stderr with exit status 2. Requests are limited
to 64 MiB; the codec's existing 3,000,000-code-point YAML limit also applies. No release is required.

All input files are validated before staging any change. Dry-run validates and prints diffs without
writes. Successful writes use same-directory temporary files and atomic replacement per file,
preserving permissions; unchanged files are untouched. Symlinks and hard-linked inputs are refused.
All inputs are checked before replacements, and each is checked again immediately before its own replacement; an edit that lands while that same file is being replaced can still be lost. This is not a filesystem transaction
across a batch: an I/O failure during the replacement phase is reported and may leave earlier files
migrated; re-running is safe. Temporary files are removed on failure.

Tests:

```sh
pnpm --filter @tatrman/migrate test
node --test scripts/yaml-migration.integration.test.mjs
```

The second command runs the real built CLI and packaged JVM bridge. Conformance CI builds both and
runs it, alongside the codec's full fixture-roster migration, AST and semantics checks.

A file whose typed parse reports errors (for example a repeated property) is refused; fix it first.
