# Shared conformance fixtures

`fixtures/` is the public, invented roster used by the TypeScript, Kotlin and Python
AST/semantics harnesses. Root `.ttrm` files are single-document scenarios. Each
positive directory is one project: load all its documents into the same symbol
table before resolving references. `*-negative` directories contain parser refusals.

Every positive text file has a committed `.yaml` twin beside it. The Kotlin
`CanonicalYamlConformanceSpec` discovers the current roster, refuses missing or
orphan twins, and checks AST and semantics equality plus canonical YAML byte and
native TTR dump fixed points. It preserves relative `.ttrm` URIs in both semantics
runs so file identity cannot change resolution. JSON object keys in AST dumps are
normalized; list order is retained and explicit vectors cover authored ordered slots.
The graph projection is checked separately because the existing typed parser folds it.

`yaml-cases/` adds paired syntax cases with expected parser diagnostics, such as
repeated CN properties. These must retain both the entries and diagnostics; they
are separate from the three-runtime positive roster's error-free input requirement.

To author twins after adding or changing text fixtures:

```sh
./gradlew :packages:kotlin:ttr-yaml:generateTwins
# Review and commit the resulting YAML; tests and CI never generate it.
./gradlew :packages:kotlin:ttr-yaml:test
pnpm --filter @tatrman/conformance dump-all
```

The blocking `canonical-yaml` Conformance job checks schema drift before builds,
generation negative tests, the live roster, unhandled grammar shapes, all YAML fixed
points and the enabled C-7 `DefsJsonRoundTripSpec`. That test starts with serialized
`defs.json` values, adapts the invented snapshot's C-7 aliases/defaults to canonical
syntax, writes and reads actual YAML, and compares the resulting export bytes with
the original header/connections context. It is a test adapter, not a general snapshot
import API. The codec stays below metadata: since MW S3.4 metadata reaches it at runtime
through `ttr-profile` (the `yaml` source kind), never the other way round.

## Dialect trees (`dialect/`)

`dialect/<case>/tree/` is a model root in a dialect: its `ttr.yaml` declares the profile,
`model.ignore` keeps the profile definitions out of the content. `dialect/<case>/canonical/`
is its canonical YAML twin. `DialectConformanceSpec` (ttr-metadata) loads both through the
`yaml` source kind and requires the same snapshot dump; a case without both folders is red.
The set lives beside `fixtures/`, not in it, so the TS/Python/Kotlin parser rosters and their
dump outputs never see it. The twins were written from the profile engine and checked byte for
byte against the hand-written `demo-cs/1` expectations in `ttr-profile` (the links case adds
its inline overlay's values).
