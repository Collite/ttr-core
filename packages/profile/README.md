# @tatrman/profile

The TypeScript implementation of the shared YAML profile contract. Operations read
immutable authored input; diagnostics and emitted documents retain authored locations.
Profile resolution uses an explicit root and catalogue, with no network lookups.

```ts
import { ForeignTree, ProfileResolver, DeclarationReader, ProfileEngine } from '@tatrman/profile';
const declaration = DeclarationReader.readTree(treeMarkerText, 'ttr.yaml');
const chain = new ProfileResolver(root, catalogue).resolve(declaration);
const result = ProfileEngine.apply([ForeignTree.read(text, 'entities.yaml')], chain, root);
for (const document of result.documents) console.log(document.write());
```

The shared dialect fixtures and glossary profile vectors are the Kotlin parity oracle.
Run `pnpm --filter @tatrman/profile test`, `typecheck`, and `lint` after building
workspace dependencies. `scripts/generate-resources.mjs --check` is a local staleness
guard for copied grammar resources and the diagnostic catalogue. CI regenerates them
in `prebuild`, so this does not detect cross-engine drift; shared fixture parity
provides the cross-engine evidence.

Numeric input values carry `NumericScalar { value, text }`: `value` is an approximate
JavaScript number, and `text` preserves Kotlin's resolved decimal spelling without
rounding. The canonical reader exposes numeric `text` on its AST as well. Generic
output mappings are `Map<string, Json>` so integer-like keys keep canonical UTF-8
ordering. Use `document.write()` for canonical bytes; `JSON.stringify` is not a
lossless serializer for these values.
