# Canonical YAML schemas

The grammar package generates a canonical document schema, a profile schema and
shape metadata from `TTR.g4`. They describe grammar **0.21**, including its exact
57 top-level definition kinds and nested world/automation declarations. Schema
validation checks syntax and value shapes; required domain fields, reference
resolution and profile execution belong to semantic validation. The Kotlin YAML
codec is a later delivery.

## Resources

| Consumer | Path |
|---|---|
| npm | `@tatrman/grammar/schemas/model-yaml.schema.json` |
| npm | `@tatrman/grammar/schemas/profile.schema.json` |
| npm | `@tatrman/grammar/schemas/canonical-metadata.json` |
| `org.tatrman:ttr-parser` jar | `/schemas/model-yaml.schema.json` |
| `org.tatrman:ttr-parser` jar | `/schemas/profile.schema.json` |
| `org.tatrman:ttr-parser` jar | `/schemas/canonical-metadata.json` |

Both schemas use JSON Schema draft 2020-12 and local `$defs` references. They can
be validated offline. `x-ttr-version` (or `version` in the metadata) comes from the
grammar marker. npm subpaths resolve through the package exports:

```js
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
const require = createRequire(import.meta.url);
const schema = JSON.parse(readFileSync(
  require.resolve('@tatrman/grammar/schemas/model-yaml.schema.json'), 'utf8',
));
```

The schema inputs are normalized JSON values from YAML 1.2. Duplicate YAML keys,
source positions, multiple documents and `!pl` scalar normalization are codec
responsibilities. A JSON Schema validator cannot detect a duplicate key after a
YAML parser has already discarded it.

## Document shape

```yaml
ttr: "0.21"
package: demo.sales
imports: [demo.common.*]
model: er
defs:
  - kind: entity
    id: Item
    properties:
      description: {en: Item}
      attributes:
        - id: code
          properties: {type: text, isKey: true}
```

Definitions preserve order in `defs`. Kind names use the grammar's literal
spelling: `er2db_entity`, `domain`, `md2er_cubelet`, `subkind`, and so on. Nested
slots with a fixed kind use `{id, properties}`. Parameters are property records
such as `{name: code, type: text, defaultValue: '', binds: [er.entity.Item.code]}`.
World members have `{kind, id?, properties}`; an unnamed `grant` omits `id`.
Storage schemas use `{id, fields}` under `schemas`, and process steps stay under
`steps`.

`model` and `graph` are exclusive. A graph is `{id, properties}`, with its own
model/description/tags/objects/layout keys. `schema` and `locale` require `model`.
Imports retain their terminal wildcard. `security` is an ordered sequence of
blocks, each an ordered sequence of `own`, `classify`, `grant` or `mask` records.

Typed properties are closed. Open keys occur where the grammar declares an
entry/object body, including semantics, lexicon, manifests, CN and automation.
A malformed typed value cannot fall back to an open entry. CN and automation
bodies also accept a sequence of single-entry property mappings to preserve
order and repeated declarations:

```yaml
ttr: "0.21"
model: cn
defs:
  - kind: kind
    id: Item
    properties:
      - of: {ref: BaseItem}
      - description: An item
```

Typed references are authored qname strings. Generic values distinguish a quoted
string from an identifier with `{ref: <qname>}`; triple strings use
`{triple: <text>}`, calls use `{call: <name>, args: [...]}`. Use the explicit
`{object: {...}}` escape for generic data colliding with a reserved wrapper.
Automation objects retain ordered entries inside `{object: [...]}`. Their
duration, tagged, call, transition, set and wildcard forms have distinct records.

Embedded source uses `{block: <text>, tag?: <tag>, form?: string|tripleString}`.
A tag selects tagged source; an absent tag/form selects untagged triple text.
`form: string` retains a quoted expression block. Tags and `form` are exclusive.

## Profile shape

```yaml
profile: demo/1
grammar: ">=0.21 <1.0"
operations:
  - op: rename
    at: /records/title
    to: {kind: entity, property: description}
  - op: values
    at: /records/key
    to: {kind: entity, path: [attributes, isKey]}
    mapping: {yes: true, no: false}
```

The root declaration in `ttr.yaml` has just `profile` and `grammar`. A profile
adds ordered `operations` and optional `extends`, `overlay` and `writable`.
Operations are `kinds`, `package`, `rename`, `values`, `shape`, `nest`/`unnest`,
`ignore`, `codes` and `link`. Selectors use JSON Pointers (`at`) and optional file
globs (`files`). A shape template uses explicit `{input: <JSON Pointer>}`
substitutions. A link declares `by: sourceName | iri | path`.

Targets use an exact grammar kind and either `property` or an ordered `path`.
Each path step follows its typed value; open tails require a grammar-declared
open body. `values` mappings and defaults must match the endpoint's type. For
example, `attributes → isKey` accepts booleans and rejects quoted `"true"`.
The schema also covers nested declaration targets such as engine properties.
Range matching, inheritance, invertibility and overlay/link resolution happen
when profiles are loaded and executed.

## Regeneration and checks

From the repository root:

```sh
pnpm --filter @tatrman/grammar build
pnpm --filter @tatrman/grammar schemas:check
pnpm --filter @tatrman/grammar test
./gradlew :packages:kotlin:ttr-parser:test :packages:kotlin:ttr-parser:jar
node scripts/grammar-consumer-smoke.mjs
```

`extract-property-map.ts` is the shared generation entry point. The canonical
metadata emitter reads production membership and literal keys; structural AST
adapters name supported value forms and guard their helper productions. A new
unsupported body/value shape fails generation. Existing C-7 advertised property
order stays unchanged.

Generated source files live in `packages/grammar/schemas/` and are committed.
`schemas:check` compares freshly generated bytes without overwriting stale
files; the dedicated CI job runs before builds can regenerate them. Tests include
unsupported-shape and stale/missing-resource failures.

Standalone Gradle builds require Node.js 20 or later for the dependency-free
emitter. Declared grammar/script inputs regenerate jar resources under the parser's
build directory; no pnpm installation is needed for that task. Tests compare
source, classpath and jar bytes; the isolated npm consumer checks export paths,
versions and byte hashes.

These resources keep grammar version 0.21. Internal bundle versions remain
0.21.x. Publication follows the normal bundle process; the existing JVM rebuild
notice for grammar 0.21's appended model fields still applies.
