# @tatrman/parser

TypeScript parser for TTR (Tatrman) language, generated from `TTR.g4` via `antlr-ng`.

## API

```ts
import { parseString, parseFile } from '@tatrman/parser';

const result = parseString('def entity foo {}', 'example.ttrm');
// result: { ast?: Document, errors: ParseError[], sourceFile: string }

const result2 = await parseFile('/path/to/model.ttrm');
```

## Types

```ts
interface ParseResult {
  ast?: Document;
  errors: ParseError[];
  sourceFile: string;
}

interface Document {
  schemaDirective?: SchemaDirective;
  definitions: Definition[];
  source: SourceLocation;
}

type DefinitionKind =
  | 'model' | 'table' | 'view' | 'column' | 'index' | 'constraint' | 'fk' | 'procedure'
  | 'entity' | 'attribute' | 'relation'
  | 'er2dbEntity' | 'er2dbAttribute' | 'er2dbRelation'
  | 'query' | 'role' | 'er2cncRole';

interface Definition {
  kind: DefinitionKind;
  name: string;
  source: SourceLocation;
}
```

## Generate Parser

```bash
cd packages/parser
pnpm run prebuild
```

This runs `bash ../grammar/scripts/generate-typescript-parser.sh` which invokes `antlr-ng` on `TTR.g4` and generates the TypeScript parser in `src/generated/`.

## `@tatrman/parser/layout`

The layout document of the Studio Viewer layouts (VL) and its TTR form: the document and its canonical
order (`canonicalize`, `compareAlphabetical`), `validateDocument`, the `.ttrg` writer (`writeTtrg`, which
refuses a ref that is not a TTR `id` — `ttrUnwritable`) and the `.ttrg` + `.ttrl` reader (`readTtrg`,
`mergeTtr`). It never imports the ANTLR runtime, so the Studio Viewer bundles it without the generated parser;
`isTtrId` uses the keyword list `prebuild` generates from `TTR.g4`. The shared test vectors
(`packages/package-sdk/schemas/layout-vectors/`) hold it to the JVM twins in ttr-parser and ttr-writer.