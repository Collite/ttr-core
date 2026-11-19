// SPDX-License-Identifier: Apache-2.0
import { readFileSync, readdirSync } from 'node:fs';
import { basename, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { parseString, parseYamlDocument } from '@tatrman/parser';
import { dump } from '../../../tests/conformance/dump.js';
import { jsonText } from './scalars.js';
import { ForeignTree } from './input.js';
import { DeclarationReader, ProfileReader, ProfileResolver } from './loading.js';
import { ProfileEngine } from './engine.js';
import * as resources from './generated/resources.js';

const repository = fileURLToPath(new URL('../../../', import.meta.url));
const conformance = join(repository, 'tests/conformance');
const read = (file: string) => readFileSync(file, 'utf8');
const json = (file: string): unknown => JSON.parse(read(file));
const yamlFiles = (root: string): string[] =>
  readdirSync(root, { withFileTypes: true })
    .flatMap((entry) => {
      const file = join(root, entry.name);
      return entry.isDirectory() ? yamlFiles(file) : /\.ya?ml$/.test(entry.name) ? [file] : [];
    })
    .sort();
const dataFiles = (root: string) =>
  yamlFiles(root).filter(
    (file) => !['ttr.yaml', 'profiles'].includes(relative(root, file).split('/')[0])
  );

function chain(root: string) {
  return new ProfileResolver(root, new Map()).resolve(
    DeclarationReader.readTree(read(join(root, 'ttr.yaml')), 'ttr.yaml')
  );
}

function apply(root: string, files = dataFiles(root)) {
  return ProfileEngine.apply(
    files.map((file) => ForeignTree.read(read(file), relative(root, file))),
    chain(root),
    root,
    true
  );
}

type Result = ReturnType<typeof ProfileEngine.apply>;
type Document = Result['documents'][number];
interface Report {
  documents: Array<{ package: string; model: string; imports: string[]; defs: number }>;
  ignored: Array<{
    file: string;
    line: number;
    column: number;
    path: string;
    record?: string;
    reason: string | null;
  }>;
  overridden: Array<{ qname: string; property: string; from: unknown; to: unknown }>;
  warnings?: Array<{ record?: string; message: string }>;
}
const reportOf = (result: Result) => result.reportJson() as unknown as Report;
const documentKey = (document: Document) =>
  `${document.file}:${String(document.value.package)}:${String(document.value.model)}`;

function canonicalTwins(result: Result, canonical: string) {
  const expected = yamlFiles(canonical);
  expect(expected.length).toBeGreaterThan(0);
  const actual = new Map<string, Buffer>();
  for (const document of result.documents) {
    // A second model from one input gets the same suffix as DialectConformanceSpec's twin.
    const stem = basename(document.file!, '.yaml');
    const candidates = [
      join(canonical, `${stem}.yaml`),
      join(canonical, `${stem}-${String(document.value.model)}.yaml`),
    ];
    const twin = candidates.find(
      (file) =>
        expected.includes(file) && read(file).includes(`model: "${String(document.value.model)}"\n`)
    );
    expect(twin, documentKey(document)).toBeDefined();
    expect(actual.has(twin!), `duplicate twin ${twin}`).toBe(false);
    actual.set(twin!, Buffer.from(document.write(), 'utf8'));
  }
  expect([...actual.keys()].sort()).toEqual(expected);
  for (const file of expected) expect(actual.get(file), file).toEqual(readFileSync(file));
}

describe('shared dialect corpus (Kotlin DemoCsSpec / DialectConformanceSpec)', () => {
  const root = join(conformance, 'dialect');
  const cases = readdirSync(root, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();

  test('the shared roster has tree/canonical twins and includes both demo-cs groups', () => {
    expect(cases).toEqual(expect.arrayContaining(['demo-cs', 'demo-cs-links']));
    for (const name of cases) {
      expect(yamlFiles(join(root, name, 'tree')).length, name).toBeGreaterThan(0);
      expect(yamlFiles(join(root, name, 'canonical')).length, name).toBeGreaterThan(0);
    }
  });

  for (const name of cases) {
    test(`${name}: every canonical twin is byte-equal, including cross-file links and overlays`, () => {
      canonicalTwins(apply(join(root, name, 'tree')), join(root, name, 'canonical'));
    });
  }

  const demo = join(root, 'demo-cs/tree');
  const inputs = dataFiles(demo).filter((file) => /^\d/.test(basename(file)));
  test('the expanded demo-cs roster includes the S5.2 each/split/null-skip vector', () => {
    expect(inputs).toHaveLength(11);
    expect(inputs.map((file) => basename(file))).toContain('11-pojmy.yaml');
  });
  for (const input of inputs) {
    test(`${basename(input)}: engine emission equals all of its canonical twins`, () => {
      // 10-vazby links to the entity/binding fan-out defined by 07, as in the shared tree run.
      const context =
        basename(input) === '10-vazby.yaml' ? [join(demo, '07-fanout.yaml'), input] : [input];
      const result = apply(demo, context);
      const documents = result.documents.filter((document) => document.file === basename(input));
      const canonical = join(root, 'demo-cs/canonical');
      const stem = basename(input, '.yaml');
      const twins = yamlFiles(canonical).filter((file) =>
        [stem, `${stem}-binding`].includes(basename(file, '.yaml'))
      );
      expect(documents).toHaveLength(twins.length);
      for (const document of documents) {
        const twin = twins.find((file) =>
          read(file).includes(`model: "${String(document.value.model)}"\n`)
        );
        expect(twin).toBeDefined();
        expect(Buffer.from(document.write(), 'utf8')).toEqual(readFileSync(twin!));
      }
    });
  }

  test('links/overlay report equals the committed Kotlin report', () => {
    const result = apply(join(root, 'demo-cs-links/tree'));
    const report = reportOf(result);
    // The guard exporter sorts documents; per-file engine emission preserves input order.
    const documents = [...report.documents].sort((a, b) =>
      `${a.package}:${a.model}`.localeCompare(`${b.package}:${b.model}`, 'en')
    );
    expect({ ...report, documents }).toEqual(
      json(join(conformance, 'guard/demo-cs-links/pristine/model/report.json'))
    );
  });

  test('combined emission retains authored locations after grouping by package/model', () => {
    const result = ProfileEngine.apply(
      inputs.map((file) => ForeignTree.read(read(file), basename(file))),
      chain(demo),
      demo
    );
    const er = result.documents.find((document) => document.value.model === 'er')!;
    expect(er).toBeDefined();
    expect(er.locations.get('/defs/0')).toMatchObject({
      file: '01-sequence.yaml',
      path: '/entity/0',
    });
    expect(er.locations.get('/defs/0/properties/description')).toMatchObject({
      file: '01-sequence.yaml',
      path: '/entity/0/popis',
    });
    expect(er.locations.get('/defs/0/properties/attributes/0')).toMatchObject({
      file: '01-sequence.yaml',
      path: '/entity/0/sloupce/kod',
    });
    expect(er.locations.get('/defs/4/properties/description')).toMatchObject({
      file: '05-unnest.yaml',
    });
    expect(reportOf(result).ignored).toContainEqual(
      expect.objectContaining({
        file: '11-pojmy.yaml',
        record: 'Koncept',
        reason: 'kind mapping null (/stav = navrh)',
      })
    );
  });
});

interface Annotation {
  severity: string;
  file: string;
  line?: number;
  column?: number;
  code: string;
}
function annotations(file: string): Annotation[] {
  return read(file)
    .trim()
    .split('\n')
    .map((line) => {
      const match = /^::(error|warning) file=([^,:]+)(?:,line=(\d+),col=(\d+))?::(\S+)/.exec(line);
      if (!match) throw new Error(`Unrecognised Kotlin annotation: ${line}`);
      return {
        severity: match[1],
        file: match[2],
        line: match[3] ? Number(match[3]) : undefined,
        column: match[4] ? Number(match[4]) : undefined,
        code: match[5],
      };
    });
}
function failure(run: () => unknown, expected: Annotation, root: string) {
  let caught: unknown;
  try {
    run();
  } catch (error) {
    caught = error;
  }
  expect(caught, `${expected.file}: expected ${expected.code}`).toBeDefined();
  const error = caught as {
    code: string;
    location: { file: string; line: number; column: number };
  };
  expect(error.code).toBe(expected.code);
  const file = error.location.file.startsWith('/')
    ? relative(root, error.location.file)
    : error.location.file;
  expect(file).toBe(expected.file);
  expect(error.location.line).toBe(expected.line);
  expect(error.location.column).toBe(expected.column);
}

describe('shared negative corpus: codes and one-based authored positions', () => {
  const directory = join(conformance, 'dialect-negative/demo-cs');
  const root = join(directory, 'tree');
  const expected = annotations(join(directory, 'expected-github.txt'));
  for (const item of expected.filter((item) => item.code !== 'ttr/unimported-reference')) {
    test(`${item.file}: ${item.code} at ${item.line}:${item.column}`, () => {
      failure(() => apply(root, [join(root, item.file)]), item, root);
    });
  }
  test('every negative is classified; unimported-reference belongs to the downstream metadata loader', () => {
    expect(
      dataFiles(root)
        .map((file) => relative(root, file))
        .sort()
    ).toEqual(expected.map((item) => item.file).sort());
    const item = expected.find((item) => item.code === 'ttr/unimported-reference')!;
    expect(item.file).toBe('unimported.yaml');
    // Kotlin's engine emits this file; MetadataLoader subsequently refuses the unreachable reference.
    expect(apply(root, [join(root, item.file)]).documents.length).toBeGreaterThan(0);
  });

  const profiles = join(conformance, 'dialect-negative/demo-cs-profiles');
  const profileRoot = join(profiles, 'tree');
  const profileAnnotations = annotations(join(profiles, 'expected-github.txt'));
  // The loader emits a second "dropped" annotation on the input for profile failures. The engine throws
  // the original profile span. Catalog duplicate warnings and canonical validation are loader-owned.
  for (const file of [
    'cycle.yaml',
    'invalid.yaml',
    'mismatch.yaml',
    'not-found.yaml',
    'unmapped.yaml',
  ]) {
    test(`${file}: file declaration overrides the tree declaration with the Kotlin failure span`, () => {
      const source = read(join(profileRoot, file));
      const declaration = DeclarationReader.select(
        source,
        file,
        DeclarationReader.readTree(read(join(profileRoot, 'ttr.yaml')), 'ttr.yaml')
      );
      const target =
        (
          {
            'cycle.yaml': 'profiles/cycle-b.yaml',
            'invalid.yaml': 'profiles/invalid.yaml',
            'mismatch.yaml': 'profiles/old.yaml',
          } as Record<string, string>
        )[file] ?? file;
      const item = profileAnnotations.find((entry) => entry.file === target)!;
      failure(
        () =>
          ProfileEngine.apply(
            [ForeignTree.read(source, file)],
            new ProfileResolver(profileRoot, new Map()).resolve(declaration),
            profileRoot
          ),
        item,
        profileRoot
      );
    });
  }
  test('invalid profile is refused directly at the committed operation position', () => {
    const file = 'profiles/invalid.yaml';
    failure(
      () => ProfileReader.read(read(join(profileRoot, file)), file),
      profileAnnotations.find((entry) => entry.file === file)!,
      profileRoot
    );
  });
});

// Already-normalized JSON from RdfFrontEndSpec's independently authored orchard expectation.
// Extracting this literal exercises the shared engine boundary without implementing an RDF front end.
function orchardTree(): string {
  const source = read(
    join(
      repository,
      'packages/kotlin/ttr-import/src/test/kotlin/org/tatrman/ttr/imports/RdfFrontEndSpec.kt'
    )
  );
  const literal = /"""\s*(\{"subjects":\s*\{[\s\S]*?\}\})\s*"""/.exec(source);
  if (!literal) throw new Error('RdfFrontEndSpec normalized orchard fixture is missing');
  return JSON.stringify(JSON.parse(literal[1]));
}
const shipped = (identity: string) => read(join(repository, 'profiles', `${identity}.yaml`));
function glossary(tree: object | string, identity = 'skos/1', extra = '') {
  const profile = ProfileReader.read(shipped(identity), `<shipped>/profiles/${identity}.yaml`);
  const profiles = extra ? [profile, ProfileReader.read(extra, 'overlay.yaml')] : [profile];
  return ProfileEngine.apply(
    [ForeignTree.read(typeof tree === 'string' ? tree : JSON.stringify(tree), 'g.json')],
    profiles,
    undefined,
    false,
    identity === 'skos/1' ? '/@id' : '/name*'
  );
}
function definitions(result: Result) {
  return result.documents.flatMap(
    (document) =>
      document.value.defs as Array<{
        id: string;
        kind: string;
        properties: Array<Record<string, unknown>>;
      }>
  );
}
function properties(definition: ReturnType<typeof definitions>[number]) {
  return JSON.parse(jsonText(Object.assign({}, ...definition.properties))) as Record<
    string,
    unknown
  >;
}
const subject = (
  name: string,
  types: string[] = ['Concept'],
  rest: Record<string, unknown> = {}
) => ({
  '@id': `https://example.org/g/${name}`,
  '@local': name,
  'rdf:type': types.map((type) => ({ '@local': type })),
  ...rest,
});

// Fixed normalized trees for the committed trade sources; no RDF/CSV front end runs in this suite.
const tradeSkos = {
  subjects: {
    'https://example.org/trade/Customer': {
      '@id': 'https://example.org/trade/Customer',
      '@local': 'Customer',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'customer', '@language': 'en' },
        { '@value': 'zákazník', '@language': 'cs' },
      ],
      'skos:definition': [
        { '@value': 'A party that buys from us.', '@language': 'en' },
        { '@value': 'Strana, která od nás nakupuje.', '@language': 'cs' },
      ],
      'skos:broader': [{ '@id': 'https://example.org/trade/Party', '@local': 'Party' }],
      'skos:related': [{ '@id': 'https://example.org/trade/Order', '@local': 'Order' }],
      'skos:altLabel': [
        { '@value': 'buyer', '@language': 'en' },
        { '@value': 'client', '@language': 'en' },
        { '@value': 'klient', '@language': 'cs' },
      ],
    },
    'https://example.org/trade/Invoice': {
      '@id': 'https://example.org/trade/Invoice',
      '@local': 'Invoice',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'faktura', '@language': 'cs' },
        { '@value': 'invoice', '@language': 'en' },
      ],
      'skos:definition': [
        { '@value': 'A demand for payment for an order.', '@language': 'en' },
        { '@value': 'Výzva k zaplacení objednávky.', '@language': 'cs' },
      ],
      'skos:related': [{ '@id': 'https://example.org/trade/Payment', '@local': 'Payment' }],
      'skos:altLabel': [{ '@value': 'bill', '@language': 'en' }],
    },
    'https://example.org/trade/Order': {
      '@id': 'https://example.org/trade/Order',
      '@local': 'Order',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'objednávka', '@language': 'cs' },
        { '@value': 'order', '@language': 'en' },
      ],
      'skos:definition': [
        { '@value': "A customer's request to buy products.", '@language': 'en' },
        { '@value': 'Požadavek zákazníka na nákup produktů.', '@language': 'cs' },
      ],
      'skos:related': [
        { '@id': 'https://example.org/trade/Invoice', '@local': 'Invoice' },
        { '@id': 'https://example.org/trade/Shipment', '@local': 'Shipment' },
      ],
    },
    'https://example.org/trade/Organisation': {
      '@id': 'https://example.org/trade/Organisation',
      '@local': 'Organisation',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'organisation', '@language': 'en' },
        { '@value': 'organizace', '@language': 'cs' },
      ],
      'skos:definition': [
        { '@value': 'A company or institution acting as a party.', '@language': 'en' },
        { '@value': 'Firma nebo instituce vystupující jako strana.', '@language': 'cs' },
      ],
      'skos:broader': [{ '@id': 'https://example.org/trade/Party', '@local': 'Party' }],
      'skos:altLabel': [
        { '@value': 'company', '@language': 'en' },
        { '@value': 'firma', '@language': 'cs' },
      ],
    },
    'https://example.org/trade/Party': {
      '@id': 'https://example.org/trade/Party',
      '@local': 'Party',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'party', '@language': 'en' },
        { '@value': 'strana', '@language': 'cs' },
      ],
      'skos:definition': [
        { '@value': 'Anyone the business deals with.', '@language': 'en' },
        { '@value': 'Kdokoli, s kým firma jedná.', '@language': 'cs' },
      ],
    },
    'https://example.org/trade/Payment': {
      '@id': 'https://example.org/trade/Payment',
      '@local': 'Payment',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'payment', '@language': 'en' },
        { '@value': 'platba', '@language': 'cs' },
      ],
      'skos:definition': [
        { '@value': 'Money received against an invoice.', '@language': 'en' },
        { '@value': 'Peníze přijaté na fakturu.', '@language': 'cs' },
      ],
      'skos:altLabel': [{ '@value': 'úhrada', '@language': 'cs' }],
    },
    'https://example.org/trade/Person': {
      '@id': 'https://example.org/trade/Person',
      '@local': 'Person',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'osoba', '@language': 'cs' },
        { '@value': 'person', '@language': 'en' },
      ],
      'skos:definition': [
        { '@value': 'A human being acting as a party.', '@language': 'en' },
        { '@value': 'Člověk vystupující jako strana.', '@language': 'cs' },
      ],
      'skos:broader': [{ '@id': 'https://example.org/trade/Party', '@local': 'Party' }],
    },
    'https://example.org/trade/Product': {
      '@id': 'https://example.org/trade/Product',
      '@local': 'Product',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'product', '@language': 'en' },
        { '@value': 'produkt', '@language': 'cs' },
      ],
      'skos:definition': [
        { '@value': 'Něco, co prodáváme.', '@language': 'cs' },
        { '@value': 'Something we sell.', '@language': 'en' },
      ],
      'skos:related': [{ '@id': 'https://example.org/trade/Warehouse', '@local': 'Warehouse' }],
      'skos:altLabel': [
        { '@value': 'article', '@language': 'en' },
        { '@value': 'item', '@language': 'en' },
        { '@value': 'zboží', '@language': 'cs' },
      ],
    },
    'https://example.org/trade/Purchase': {
      '@id': 'https://example.org/trade/Purchase',
      '@local': 'Purchase',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'nákup', '@language': 'cs' },
        { '@value': 'purchase', '@language': 'en' },
      ],
      'skos:definition': [
        {
          '@value': 'A customer buying from us: the event that makes a party a customer.',
          '@language': 'en',
        },
        {
          '@value': 'Nákup zákazníka: událost, která ze strany dělá zákazníka.',
          '@language': 'cs',
        },
      ],
      'skos:related': [{ '@id': 'https://example.org/trade/Order', '@local': 'Order' }],
      'skos:altLabel': [
        { '@value': 'koupě', '@language': 'cs' },
        { '@value': 'sale', '@language': 'en' },
      ],
    },
    'https://example.org/trade/Shipment': {
      '@id': 'https://example.org/trade/Shipment',
      '@local': 'Shipment',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'shipment', '@language': 'en' },
        { '@value': 'zásilka', '@language': 'cs' },
      ],
      'skos:definition': [
        { '@value': 'Products sent to a customer.', '@language': 'en' },
        { '@value': 'Produkty odeslané zákazníkovi.', '@language': 'cs' },
      ],
      'skos:altLabel': [
        { '@value': 'delivery', '@language': 'en' },
        { '@value': 'dodávka', '@language': 'cs' },
      ],
    },
    'https://example.org/trade/Supplier': {
      '@id': 'https://example.org/trade/Supplier',
      '@local': 'Supplier',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'dodavatel', '@language': 'cs' },
        { '@value': 'supplier', '@language': 'en' },
      ],
      'skos:definition': [
        { '@value': 'An organisation we buy from.', '@language': 'en' },
        { '@value': 'Organizace, od které nakupujeme.', '@language': 'cs' },
      ],
      'skos:broader': [
        { '@id': 'https://example.org/trade/Organisation', '@local': 'Organisation' },
      ],
      'skos:related': [{ '@id': 'https://example.org/trade/Product', '@local': 'Product' }],
      'skos:altLabel': [
        { '@value': 'liferant', '@language': 'cs' },
        { '@value': 'vendor', '@language': 'en' },
      ],
    },
    'https://example.org/trade/Trade': {
      '@id': 'https://example.org/trade/Trade',
      '@local': 'Trade',
      'rdf:type': [
        { '@id': 'http://www.w3.org/2004/02/skos/core#ConceptScheme', '@local': 'ConceptScheme' },
      ],
      'skos:prefLabel': [{ '@value': 'Trade glossary', '@language': 'en' }],
    },
    'https://example.org/trade/Warehouse': {
      '@id': 'https://example.org/trade/Warehouse',
      '@local': 'Warehouse',
      'rdf:type': [{ '@id': 'http://www.w3.org/2004/02/skos/core#Concept', '@local': 'Concept' }],
      'skos:inScheme': [{ '@id': 'https://example.org/trade/Trade', '@local': 'Trade' }],
      'skos:prefLabel': [
        { '@value': 'sklad', '@language': 'cs' },
        { '@value': 'warehouse', '@language': 'en' },
      ],
      'skos:definition': [
        { '@value': 'A place where products are stored.', '@language': 'en' },
        { '@value': 'Místo, kde jsou uloženy produkty.', '@language': 'cs' },
      ],
      'skos:altLabel': [{ '@value': 'depot', '@language': 'en' }],
    },
  },
};
const tradeOm = `{ "rows": [
{"name*": "Party", "displayName": "party", "description": "Anyone the business deals with.", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"parent": "Trade.Party", "name*": "Person", "displayName": "person", "description": "A human being acting as a party.", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"parent": "Trade.Party", "name*": "Organisation", "displayName": "organisation", "description": "A company or institution acting as a party.", "synonyms": "company", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"parent": "Trade.Party", "name*": "Customer", "displayName": "customer", "description": "A party that buys from us.", "synonyms": "client;buyer", "relatedTerms": "Trade.Order", "tags": "Tier.Tier2", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"parent": "Trade.Organisation", "name*": "Supplier", "displayName": "supplier", "description": "An organisation we buy from.", "synonyms": "vendor", "relatedTerms": "Trade.Product", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"name*": "Product", "displayName": "product", "description": "Something we sell.", "synonyms": "item;article", "relatedTerms": "Trade.Warehouse", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"name*": "Order", "displayName": "order", "description": "A customer's request to buy products.", "relatedTerms": "Trade.Invoice;Trade.Shipment", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"name*": "Purchase", "displayName": "purchase", "description": "A customer buying from us: the event that makes a party a customer.", "synonyms": "sale", "relatedTerms": "Trade.Order", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"name*": "Invoice", "displayName": "invoice", "description": "A demand for payment for an order.", "synonyms": "bill", "relatedTerms": "Trade.Payment", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"name*": "Payment", "displayName": "payment", "description": "Money received against an invoice.", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"name*": "Quotation", "displayName": "quotation", "description": "An offer of prices before an order.", "synonyms": "quote", "relatedTerms": "Trade.Order", "owner": "team:sales-ops", "glossaryStatus": "Draft"},
{"name*": "Shipment", "displayName": "shipment", "description": "Products sent to a customer.", "synonyms": "delivery", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"},
{"name*": "Warehouse", "displayName": "warehouse", "description": "A place where products are stored.", "synonyms": "depot", "reviewers": "user:anna.example", "owner": "team:sales-ops", "glossaryStatus": "Approved"}
]}`;

interface GlossaryDump {
  definitions: Array<{
    kind: string;
    name: string;
    descriptionLocalized: Record<string, string>;
    properties: Record<string, unknown>;
  }>;
}

/** The complete, committed Kotlin reader output is the oracle, not another TS engine run.
 * Reader.skeleton adds abstract:true; ImportReport/Reader sort definitions before writing.
 * OM's ruled projection (GlossarySpec) has English text and no is_a/relations.
 * The corpus has no S5.2 glossary YAML twin, so compare canonical conformance JSON bytes.
 */
function glossaryOracleBytes(result: Result, englishOnly = false) {
  const file = join(conformance, 'readers/glossary/model/glossary/cn.ttrm');
  const native = parseString(read(file), file);
  expect(native.errors).toEqual([]);
  expect(native.ast).toBeDefined();
  const expected = JSON.parse(dump(native)) as GlossaryDump;
  expect(expected.definitions).toHaveLength(12);
  for (const definition of expected.definitions) {
    expect(definition.kind, definition.name).toBe('kind');
    expect(definition.properties.abstract, definition.name).toEqual({ kind: 'bool', value: true });
    delete definition.properties.abstract;
    if (englishOnly) {
      expect(typeof definition.descriptionLocalized.en, definition.name).toBe('string');
      definition.descriptionLocalized = { en: definition.descriptionLocalized.en };
      const label = definition.properties.label as Record<string, string>;
      expect(typeof label.en, definition.name).toBe('string');
      definition.properties.label = { en: label.en };
      delete definition.properties.is_a;
      delete definition.properties.relations;
    }
  }
  expect(result.documents).toHaveLength(1);
  const yaml = parseYamlDocument(result.documents[0].write(), 'engine-cn.yaml');
  expect(yaml.errors).toEqual([]);
  expect(yaml.ast).toBeDefined();
  const actual = JSON.parse(dump(yaml)) as GlossaryDump;
  // CSV engine order is source order; Kotlin Reader sorts it for the committed model.
  const byName = (a: { name: string }, b: { name: string }) =>
    a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
  actual.definitions.sort(byName);
  expected.definitions.sort(byName);
  expect(Buffer.from(JSON.stringify(actual), 'utf8')).toEqual(
    Buffer.from(JSON.stringify(expected), 'utf8')
  );
}

describe('S5.2 glossary engine boundary (GlossarySpec / GenericGlossarySpec)', () => {
  test('merged generic profile headers drop extension predicates with counted warnings', () => {
    const rdf = glossary({
      subjects: {
        A: subject('A', ['Concept'], { 'dct:created': [{ '@value': '2024-01-01' }] }),
        B: subject('B', ['Concept'], { 'dct:created': [{ '@value': '2024-01-02' }] }),
      },
    });
    expect(reportOf(rdf).warnings).toEqual([
      expect.objectContaining({
        in: 'kind',
        key: '/dct:created',
        count: 2,
        message: 'not in this profile',
      }),
    ]);
    expect(reportOf(rdf).ignored).not.toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: expect.stringContaining('dct:created') }),
      ])
    );
    const csv = glossary(
      { rows: [{ 'name*': 'A', glossaryStatus: 'Approved', extraColumn: 'x' }] },
      'om-glossary/1'
    );
    expect(reportOf(csv).warnings).toEqual([
      expect.objectContaining({
        in: 'kind',
        key: '/extraColumn',
        count: 1,
        message: 'not in this profile',
      }),
    ]);
  });
  test('every SKOS trade concept matches committed cn.ttrm dump bytes and the IRI sidecar', () => {
    const result = glossary(tradeSkos);
    glossaryOracleBytes(result);
    expect(definitions(result)).toHaveLength(12);
    expect(
      properties(definitions(result).find((definition) => definition.id === 'Supplier')!)
    ).toEqual({
      description: { cs: 'Organizace, od které nakupujeme.', en: 'An organisation we buy from.' },
      label: { cs: 'dodavatel', en: 'supplier' },
      is_a: ['Organisation'],
      relations: [{ type: 'related', to: 'Product' }],
    });
    const sidecar = json(join(conformance, 'readers/glossary/model/glossary.sources.json')) as {
      entries: unknown[];
    };
    expect(
      result.sources.map(({ qname, kind, sourceName, locator }) => ({
        qname,
        kind,
        sourceName,
        locator,
      }))
    ).toEqual(sidecar.entries);
    expect(
      reportOf(result)
        .ignored.filter((item) => item.record != null)
        .map((item) => [item.record, item.reason])
    ).toEqual([['Trade', 'kind mapping null (/rdf:type/*/@local = ConceptScheme)']]);
  });

  test('the shared OM trade input is the English concept skeleton and skips Draft at its CSV line', () => {
    const csv = glossary(tradeOm, 'om-glossary/1');
    glossaryOracleBytes(csv, true);
    expect(definitions(csv).map((definition) => definition.id)).toEqual([
      'Party',
      'Person',
      'Organisation',
      'Customer',
      'Supplier',
      'Product',
      'Order',
      'Purchase',
      'Invoice',
      'Payment',
      'Shipment',
      'Warehouse',
    ]);
    expect(
      reportOf(csv)
        .ignored.filter((item) => item.record != null)
        .map((item) => [item.record, item.line, item.reason])
    ).toEqual([['Quotation', 12, 'kind mapping null (/glossaryStatus = Draft)']]);
  });

  test('the existing normalized RDF fixture emits labels, parents, related concepts and ignored subjects', () => {
    const result = glossary(
      orchardTree(),
      'skos/1',
      "profile: orchard/1\ngrammar: '>=0.22 <1.0'\noperations:\n" +
        "  - {op: ignore, in: kind, at: '/https:~1~1example.org~1x~1rank', reason: Orchard extension}\n"
    );
    const defs = definitions(result);
    expect(defs.map((definition) => definition.id)).toEqual(['Fruit', 'Pear']);
    expect(properties(defs[1])).toEqual({
      label: { cs: 'hruška', en: 'pear' },
      is_a: ['Fruit'],
      relations: [
        { type: 'related', to: 'Quince' },
        { type: 'related', to: 'Apple' },
      ],
    });
    expect(result.sources.map((entry) => [entry.qname, entry.sourceName, entry.locator])).toEqual([
      ['glossary.cn.kind.Fruit', 'https://example.org/orchard/Fruit', 'Fruit'],
      ['glossary.cn.kind.Pear', 'https://example.org/orchard/Pear', 'Pear'],
    ]);
    expect(reportOf(result).ignored).toContainEqual(
      expect.objectContaining({
        reason: 'kind missing (/rdf:type/*/@local)',
      })
    );
  });

  test('generic SKOS skips missing, null-mapped and unmapped kinds; a mapped type wins', () => {
    const tree = {
      subjects: {
        '_:blank': { '@id': '_:blank', 'skos:prefLabel': [{ '@value': 'aside' }] },
        Agent: subject('Agent', ['Concept', 'Class']),
        Coll: subject('Coll', ['Collection']),
        Onto: subject('Onto', ['Ontology']),
        Ordered: subject('Ordered', ['OrderedCollection']),
        Party: subject('Party', ['Concept'], { 'skos:note': [{ '@id': '_:blank' }] }),
        Term: subject('Term', ['TradeTerm', 'Concept']),
        Thing: subject('Thing', ['Other', 'Thing']),
        Untyped: subject('Untyped', []),
      },
    };
    const result = glossary(tree);
    expect(definitions(result).map((definition) => definition.id)).toEqual([
      'Agent',
      'Party',
      'Term',
    ]);
    expect(
      reportOf(result)
        .ignored.filter((item) => item.reason?.startsWith('kind '))
        .map((item) => [item.record ?? null, item.reason])
    ).toEqual([
      [null, 'kind missing (/rdf:type/*/@local)'],
      ['Coll', 'kind mapping null (/rdf:type/*/@local = Collection)'],
      ['Onto', 'kind unmapped (Ontology)'],
      ['Ordered', 'kind mapping null (/rdf:type/*/@local = OrderedCollection)'],
      ['Thing', 'kind unmapped (Other, Thing)'],
      ['Untyped', 'kind missing (/rdf:type/*/@local)'],
    ]);
  });

  test('missing languages default to en and repeated keys keep the bytewise-first with warnings', () => {
    const result = glossary({
      subjects: {
        Party: subject('Party', ['Concept'], {
          'skos:prefLabel': [{ '@value': 'party' }],
          'skos:definition': [
            { '@value': 'jedna', '@language': 'cs' },
            { '@value': 'one', '@language': 'en' },
            { '@value': 'two', '@language': 'en' },
          ],
        }),
      },
    });
    expect(properties(definitions(result)[0])).toEqual({
      description: { cs: 'jedna', en: 'one' },
      label: { en: 'party' },
    });
    expect(reportOf(result).warnings?.map((item) => [item.record, item.message])).toEqual([
      ['Party', 'each key missing → en'],
      ['Party', 'each key repeated (en)'],
    ]);
  });

  test('locale-normalized labels survive; alternative labels are explicitly left for the reader lexicon', () => {
    const result = glossary({
      subjects: {
        Lorry: subject('Lorry', ['Concept'], {
          'skos:prefLabel': [
            { '@value': 'lorry', '@language': 'en_gb' },
            { '@value': 'truck', '@language': 'en' },
          ],
          'skos:altLabel': [
            { '@value': 'wagon', '@language': 'en_gb' },
            { '@value': '', '@language': 'en' },
            { '@value': '  ', '@language': 'en' },
            { '@value': 'rig', '@language': 'en' },
          ],
        }),
      },
    });
    expect(properties(definitions(result)[0])).toEqual({ label: { en: 'truck', en_gb: 'lorry' } });
    expect(reportOf(result).ignored).toContainEqual(
      expect.objectContaining({
        path: '/subjects/Lorry/skos:altLabel',
        reason: "lexicon entries (the reader's lexicon key)",
      })
    );
    expect(result.documents.map((document) => document.value.model)).toEqual(['cn']);
  });

  test('OM Approved terms remain; missing and unknown statuses are ignored', () => {
    const result = glossary(
      {
        rows: [
          {
            'name*': 'Customer',
            displayName: 'customer',
            description: 'A party that buys.',
            synonyms: 'buyer;client',
            glossaryStatus: 'Approved',
          },
          { 'name*': 'Blank', displayName: 'blank', description: 'No state.' },
          {
            'name*': 'Odd',
            displayName: 'odd',
            description: 'An unlisted state.',
            glossaryStatus: 'Unprocessed',
          },
        ],
      },
      'om-glossary/1'
    );
    expect(definitions(result).map((definition) => definition.id)).toEqual(['Customer']);
    expect(properties(definitions(result)[0])).toEqual({
      label: { en: 'customer' },
      description: { en: 'A party that buys.' },
    });
    expect(
      reportOf(result)
        .ignored.filter((item) => item.record != null)
        .map((item) => [item.record, item.reason])
    ).toEqual([
      ['Blank', 'kind missing (/glossaryStatus)'],
      ['Odd', 'kind unmapped (Unprocessed)'],
    ]);
  });

  test('the committed IRI overlay changes cn keywords while retaining transform qnames and sources', () => {
    const tree = { subjects: { Customer: subject('Customer'), Purchase: subject('Purchase') } };
    // Same normalized identifiers as GlossarySpec's trade input; overlay is the committed fixture.
    for (const value of Object.values(tree.subjects))
      value['@id'] = value['@id'].replace('/g/', '/trade/');
    const overlay = read(join(conformance, 'readers/glossary/overlay.yaml'));
    const result = glossary(
      tree,
      'skos/1',
      `profile: glossary-overlay/1\ngrammar: '>=0.22 <1.0'\noperations: []\noverlay:\n` +
        overlay
          .split('\n')
          .filter((line) => line && !line.startsWith('#'))
          .map((line) => `  ${line}`)
          .join('\n')
    );
    expect(definitions(result).map((definition) => [definition.id, definition.kind])).toEqual([
      ['Customer', 'role'],
      ['Purchase', 'event'],
    ]);
    // Raw engine order is authored order; ImportReport (GlossarySpec) sorts qname/property afterward.
    expect(reportOf(result).overridden).toEqual([
      { qname: 'glossary.cn.kind.Customer', property: 'kind', from: 'kind', to: 'role' },
      { qname: 'glossary.cn.kind.Customer', property: 'of', from: null, to: 'Person' },
      { qname: 'glossary.cn.kind.Customer', property: 'event', from: null, to: 'Purchase' },
      { qname: 'glossary.cn.kind.Purchase', property: 'kind', from: 'kind', to: 'event' },
    ]);
    expect(result.sources.map((entry) => entry.qname)).toEqual([
      'glossary.cn.kind.Customer',
      'glossary.cn.kind.Purchase',
    ]);
  });
});

describe('generated profile resource staleness', () => {
  test('diagnosticCodes equals the authoritative CLI catalogue, including metadata', () => {
    expect(resources.diagnosticCodes).toEqual(
      json(
        join(
          repository,
          'packages/kotlin/ttr-metadata-cli/src/main/resources/org/tatrman/ttr/metadata/cli/diagnostic-codes.json'
        )
      )
    );
  });
  test('generated schemas equal the grammar sources', () => {
    expect(resources.profileSchema).toEqual(
      json(join(repository, 'packages/grammar/schemas/profile.schema.json'))
    );
    expect(resources.canonicalMetadata).toEqual(
      json(join(repository, 'packages/grammar/schemas/canonical-metadata.json'))
    );
    expect(resources.canonicalSchema).toEqual(
      json(join(repository, 'packages/grammar/schemas/model-yaml.schema.json'))
    );
  });
});
