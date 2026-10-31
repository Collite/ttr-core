// SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deriveMetadata, readGrammar } from '../scripts/canonical-metadata.mjs';
import { generatedFiles, generateCanonicalSchemas } from '../scripts/generate-canonical-schemas.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(join(root, 'src/TTR.g4'), 'utf8');
const metadata = deriveMetadata(source);
const files = generatedFiles(source);
const hashes = (values) => Object.fromEntries(Object.entries(values).map(([name, bytes]) => [name, createHash('sha256').update(bytes).digest('hex')]));

test('exact grammar vocabulary includes all 57 top-level kinds and 8 distinct nested-only kinds', () => {
  assert.equal(metadata.kinds.length, 57);
  assert.equal(new Set(metadata.kinds.map((k) => k.kind)).size, 57);
  for (const kind of ['domain', 'map', 'er2db_entity', 'md2er_dimension', 'md2er_cubelet', 'subkind', 'cn2db_concept', 'term', 'pattern', 'example']) assert.ok(metadata.kinds.some((k) => k.kind === kind), kind);
  for (const kind of ['engine', 'executor', 'storage', 'identity', 'grant', 'deployment', 'process', 'step']) {
    assert.ok(metadata.nestedKinds.some((k) => k.kind === kind), kind);
    assert.ok(!metadata.kinds.some((k) => k.kind === kind), kind);
  }
});

test('body metadata derives literal property keys, distinct contexts and grammar-only free-form entries', () => {
  const props = (body) => metadata.bodies[body].properties.map((p) => p.key);
  assert.ok(props('indexDef').includes('columns'));
  assert.ok(!props('indexDef').includes('columnNames'));
  assert.ok(props('mdDomainDef').includes('publish'));
  assert.ok(props('md2erDimensionDef').includes('dimension'));
  assert.deepEqual(props('parameterInline'), ['name', 'type', 'label', 'direction', 'defaultValue', 'binds']);
  assert.equal(metadata.bodies.entityDef.freeForm.length, 0);
  assert.equal(metadata.bodies.autoBody.freeForm[0].value, 'autoValue');
  assert.equal(metadata.bodies.cnBody.freeForm[0].key, 'cnKey');
  assert.equal(metadata.bodies.engineDef.freeForm[0].value, 'value');
  assert.equal(metadata.bodies.worldDef.members.find((m) => m.kind === 'grant').named, false);
});

test('identifiers follow lexer keywords and cnKey compatibility rather than accepting arbitrary strings', () => {
  const id = new RegExp(metadata.identifiers.id);
  for (const value of ['Customer', 'er.entity.Customer.code', 'Číslo', 'skipSecurity']) assert.ok(id.test(value), value);
  for (const value of ['two words', 'text', 'x.text', 'x.true', 'x..y', 'a-b', '0.21']) assert.ok(!id.test(value), value);
  const cn = new RegExp(metadata.identifiers.cnKey);
  for (const value of ['custom', 'hierarchy', 'binds', 'defaultValue', 'skipSecurity', 'indexSecurity']) assert.ok(cn.test(value), value);
  for (const value of ['grounds', 'type', 'from', 'a.b']) assert.ok(!cn.test(value), value);
});

test('comments and quoted comment tokens do not change rule discovery', () => {
  const amended = source.replace('queryProperty ', '/* inventedProperty : FAKE propSep? missing; */\nqueryProperty ');
  assert.deepEqual(hashes(generatedFiles(amended)), hashes(files));
  assert.ok(readGrammar(source).rules.LINE_COMMENT.includes("'//'"));
});

test('reached new unsupported kind/value shapes fail instead of emitting a catch-all', () => {
  const future = source.replace('  : PROJECT        id  projectDef', '  : FUTURE_KIND id futureDef\n  | PROJECT        id  projectDef') + "\nFUTURE_KIND: 'future_kind';\nfutureDef : LBRACE (futureProperty COMMA?)* RBRACE;\nfutureProperty : FUTURE_KIND propSep? futureValue;\nfutureValue : id ARROW NUMBER_LITERAL;\n";
  assert.throws(() => generatedFiles(future), /Unhandled value shape in futureValue/);
});

test('changes inside structural adapters require deliberate coverage updates', () => {
  for (const [old, changed, name] of [
    ['matchMethodValue          : id ( LPAREN NUMBER_LITERAL RPAREN )?', 'matchMethodValue          : id | object_', 'matchMethodValue'],
    ['id ( AS id )?', 'id ( AS id )? object_?', 'participant'],
    ['( id | DATA_TYPE | PRECISION ) propSep? cnValue', '( id | DATA_TYPE | PRECISION | BOOLEAN_LITERAL ) propSep? cnValue', 'cnObjectEntry'],
  ]) {
    assert.ok(source.includes(old), old);
    assert.throws(() => generatedFiles(source.replace(old, changed)), new RegExp(`Unhandled grammar shape in ${name}`));
  }
});

test('a property using an already handled value shape is generated with its exact new literal', () => {
  const amended = source.replace('queryProperty            :', 'queryProperty            : futureProperty |') + "\nFUTURE_PROP: 'futureProp';\nfutureProperty : FUTURE_PROP propSep? listOfIds;\n";
  const next = JSON.parse(generatedFiles(amended)['model-yaml.schema.json']);
  assert.deepEqual(next.$defs.body_queryDef.properties.futureProp, { $ref: '#/$defs/value_listOfIds' });
});

test('all three generated outputs are deterministic UTF-8/LF JSON with the grammar version', () => {
  assert.deepEqual(hashes(generatedFiles(source)), hashes(files));
  for (const [name, bytes] of Object.entries(files)) {
    assert.ok(bytes.endsWith('\n'), name);
    assert.ok(!bytes.includes('\r'), name);
    const value = JSON.parse(bytes);
    assert.equal(value['x-ttr-version'] ?? value.version, '0.21');
    if (name.endsWith('schema.json')) assert.equal(value.$schema, 'https://json-schema.org/draft/2020-12/schema');
  }
});

test('drift checker detects a stale or missing schema and never overwrites it', () => {
  const directory = mkdtempSync(join(tmpdir(), 'ttr-schema-drift-'));
  try {
    generateCanonicalSchemas({ outputDirectory: directory });
    generateCanonicalSchemas({ outputDirectory: directory, check: true });
    const path = join(directory, 'profile.schema.json');
    writeFileSync(path, '{}\n');
    assert.throws(() => generateCanonicalSchemas({ outputDirectory: directory, check: true }), /Stale generated schema/);
    assert.equal(readFileSync(path, 'utf8'), '{}\n');
    rmSync(path);
    assert.throws(() => generateCanonicalSchemas({ outputDirectory: directory, check: true }), /Stale generated schema/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('every top-level and nested definition body has an explicit schema handler', () => {
  const schema = JSON.parse(files['model-yaml.schema.json']);
  for (const { kind, body } of [...metadata.kinds, ...metadata.nestedKinds]) assert.ok(schema.$defs[`body_${body}`], `${kind}/${body}`);
  assert.ok(!Object.values(schema.$defs).some((entry) => Object.keys(entry).length === 0));
  assert.ok(JSON.parse(files['canonical-metadata.json']).handledValueRules.includes('embeddedBlock'));
});
