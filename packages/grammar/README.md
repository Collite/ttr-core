# @tatrman/grammar

Canonical source for the TTR (Tatrman) language grammar.

## Grammar 0.20 — things in time

The conceptual-model reader adds `def subkind`, `from:` / `until:` episode bounds,
and `per:` / `from:` valued-state projections. Kinds, subkinds and CN roles can
carry labelled `involves:` participants and a `period:` lifetime. The four temporal
shapes are instant events, kinds with lifetimes, phases with episodes, and qualities
with values over time. See [the conceptual-model manual](../../docs/manual/en/20-conceptual-model.md#grammar-020--things-in-time).

This grammar Stage preserves these shapes in all three parsers and the writer;
CN-P8 S8.2 supplies their validation and lowering. The shared CN body remains
permissive: required bearers and valid source combinations are semantic checks.
`extent:` still parses during its retirement window. The new keywords remain
legal identifier parts. JVM consumers must rebuild against the new parser model.

## Canonical YAML schemas — grammar 0.21

`prebuild` generates `schemas/model-yaml.schema.json`, `schemas/profile.schema.json`
and `schemas/canonical-metadata.json` from the grammar productions and explicit
structural value adapters. They are exported at `@tatrman/grammar/schemas/<filename>`
and packaged at `/schemas/<filename>` in the Kotlin `ttr-parser` jar.

Run `pnpm --filter @tatrman/grammar schemas:check` to reject drift without
rewriting files, and `pnpm --filter @tatrman/grammar test` for generation checks.
Both schemas use draft 2020-12 with local references. See the
[canonical schema manual](../../docs/manual/en/canonical-yaml-schemas.md) for the
ordered envelope, typed/open bodies, profile targets and resource/build checks.
The grammar stays 0.21; this delivery adds schema resources. The Kotlin YAML
codec and profile execution follow in later stages.

## Files

- `src/TTR.g4` — ANTLR4 grammar file (canonical source)

## Scripts

### `generate-typescript-parser.sh`

Generates the TypeScript parser in `@tatrman/parser/src/generated/`. Run from `@tatrman/parser` package after installing `antlr4ng-cli`.

```bash
cd packages/parser
pnpm install
bash ../grammar/scripts/generate-typescript-parser.sh
```

It ends by running `generate-ttr-id-words.mjs`, which writes `src/generated/ttr-id-words.ts`: the lexer keywords
the `idPart` rule does not admit (`text`, `date`, `true`, …). `@tatrman/parser/layout` decides whether a string
is a TTR `id` from that list, without loading the ANTLR runtime; the parser's tests hold the list to the
generated parser's own `id` rule.

### `sync-to-ai-platform.sh <ai-platform-path>`

Copies `TTR.g4` to the ai-platform vendored location:
```
<ai-platform-path>/shared/libs/kotlin/ttr-parser/src/main/antlr/shared/ttr/parser/generated/TTR.g4
```

Adds a vendoring header comment with the commit hash.

### `check-sync.sh <ai-platform-path>`

Compares local and remote grammar files by hash. Exits non-zero if they differ.

## Versioning

The TTR grammar uses an `X.Y` scheme:

- **X** — breaking change (new required syntax, removed/renamed constructs, anything that breaks previously-valid `.ttrm` files).
- **Y** — additive change (new optional constructs, syntactic sugar, parser bug fixes).

The canonical version is the `// @grammar-version: X.Y` marker in the header of `src/TTR.g4`. The `prebuild` script extracts it into `src/generated/version.ts`, which is re-exported from this package as `TTR_GRAMMAR_VERSION` for runtime use (LSP status, tests, ai-platform compatibility checks).

To bump the version: edit the marker in `TTR.g4`, add an entry to `CHANGELOG.md`, and run `pnpm --filter @tatrman/grammar build` (the prebuild hook regenerates `version.ts`). The marker is part of the file body, so `sync-to-ai-platform.sh` propagates it to the Kotlin side unchanged.

## Policy

The grammar file lives here (canonical source). Any changes to the grammar must be made here and then propagated via `sync-to-ai-platform.sh` to ai-platform.

The grammar is **target-neutral** — it contains no `options { language = ... }` block, so it can be vendored into ai-platform's Kotlin ANTLR4 toolchain without modification.

The TypeScript target is selected via the `-Dlanguage=TypeScript` flag in `generate-typescript-parser.sh`. The same grammar fed to a Java/Kotlin ANTLR4 toolchain (with no `-D` override) produces the Kotlin parser.
