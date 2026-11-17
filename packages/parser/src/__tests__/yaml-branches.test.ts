// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { parseYamlDocument, parseString } from '../index.js';
import { canonicalSchema, canonicalMetadata } from '../generated/canonical-yaml.js';
import type { ConceptDef, QueryDef, WorldDef } from '../ast.js';

const doc = (model: string, kind: string, properties: unknown) => JSON.stringify({ttr:'0.22',model,defs:[{kind,id:'P',properties}]},null,2);
const portable = (value: unknown) => JSON.parse(JSON.stringify(value,(key,v) => ['source','tagSource','valueSource','textSource','lineOffsets','indentWidth','escapes','leadingTrivia','trailingTrivia'].includes(key) ? undefined : v));
const vectors = JSON.parse(readFileSync(new URL('../../../grammar/tests/canonical-schema-vectors.json',import.meta.url),'utf8')) as {name:string;schema:string;valid:boolean;value:unknown}[];

describe('canonical YAML branches and P2 section 7 rulings', () => {
  it('ships the P2 schema and metadata without vocabulary drift', () => {
    expect(canonicalSchema).toEqual(JSON.parse(readFileSync(new URL('../../../grammar/schemas/model-yaml.schema.json',import.meta.url),'utf8')));
    expect(canonicalMetadata).toEqual(JSON.parse(readFileSync(new URL('../../../grammar/schemas/canonical-metadata.json',import.meta.url),'utf8')));
  });
  it.each(vectors.filter((v) => v.schema === 'model-yaml' && v.valid))('accepts schema vector $name', ({value}) => {
    const result = parseYamlDocument(JSON.stringify(value,null,2));
    expect(result.diagnostics.filter((d) => d.code?.startsWith('ys/'))).toEqual([]);
    expect(result.ast).toBeDefined();
  });
  it.each(vectors.filter((v) => v.schema === 'model-yaml' && !v.valid))('rejects schema vector $name at a YAML span', ({value}) => {
    const text = JSON.stringify(value,null,2);
    const result = parseYamlDocument(text,'vector.yaml');
    expect(result.ast).toBeUndefined();
    expect(result.diagnostics[0]).toMatchObject({code:'ys/schema',source:{file:'vector.yaml'}});
    const span = result.diagnostics[0].source;
    expect(span.offsetStart).toBeGreaterThanOrEqual(0);
    expect(span.offsetEnd).toBeLessThanOrEqual(text.length);
  });
  it('preserves import wildcard identity, schema/locale context and security statement/block order', () => {
    const value = {ttr:'0.22',package:'demo',imports:['other.*','one.E'],model:'db',schema:'sales',locale:'cs',security:[[{owner:'admin',object:'db.table.T',verb:'own'},{classification:'private',object:'db.table.T',verb:'classify'}],[{to:'admin',object:'db.table.T',privilege:'read',verb:'grant'},{object:'db.table.T.x',verb:'mask'}]]};
    const yaml = parseYamlDocument(JSON.stringify(value));
    expect(yaml.diagnostics).toEqual([]);
    const native = parseString('package demo import other.* import one.E model db schema sales locale cs security {own db.table.T: admin, classify db.table.T: private} security {grant read on db.table.T to admin, mask db.table.T.x}');
    expect(portable(yaml.ast)).toEqual(portable(native.ast));
    expect(yaml.ast?.securityBlocks.map((block) => block.statements.map((s) => s.verb))).toEqual([['own','classify'],['grant','mask']]);
  });
  it('reads graph properties in grammar order regardless of record field order', () => {
    const yaml = parseYamlDocument(JSON.stringify({ttr:'0.22',graph:{properties:{layout:{x:1},objects:['er.entity.A'],tags:['one'],description:'Graph',model:'er'},id:'G'}}));
    expect(yaml.diagnostics).toEqual([]);
    expect(portable(yaml.ast)).toEqual(portable(parseString('graph G {model: er, description: "Graph", tags: ["one"], objects: [er.entity.A], layout: {x: 1}}').ast));
  });
  it('keeps typed-vs-free-form fallthrough, reserved schemas and insertion-ordered schema fields', () => {
    for (const via of ['engine',7]) {
      const yaml = parseYamlDocument(doc('world','world',{members:[{kind:'storage',id:'S',properties:{via,schemas:[{id:'T',fields:{z:'text',a:'int'}}]}}]}));
      expect(yaml.diagnostics).toEqual([]);
      const world = yaml.ast!.definitions[0] as WorldDef;
      expect(world.storages[0].schemas[0].fields.map((f) => f.name)).toEqual(['z','a']);
      expect(portable(yaml.ast)).toEqual(portable(parseString(`model world def world P {def storage S {via: ${via}, def schema T {z: text, a: int}}}`).ast));
    }
  });
  it('keeps CN repeated properties in the explicit sequence in authored order', () => {
    const yaml = parseYamlDocument(doc('cn','kind',[{x:'first'},{y:{ref:'value'}},{x:'last'}]));
    expect(yaml.diagnostics.map((d) => d.code)).toEqual(['ttr/duplicate-property']);
    expect(portable(yaml.ast)).toEqual(portable(parseString('model cn def kind P {x: "first", y: value, x: "last"}').ast));
    expect((yaml.ast!.definitions[0] as ConceptDef).properties.map((p) => p.key)).toEqual(['x','y','x']);
  });
  it('preserves escaped reserved generic shapes and raw semantics keys', () => {
    const yaml = parseYamlDocument(doc('er','entity',{semantics:{source:{source:'opaque',leadingTrivia:['data']},leadingTrivia:'data'},lexicon:{data:{object:{ref:'ordinary'}}}}));
    expect(yaml.diagnostics).toEqual([]);
    expect(portable(yaml.ast)).toEqual(portable(parseString('model er def entity P {semantics {source: {source: "opaque", leadingTrivia: ["data"]}, leadingTrivia: "data"}, lexicon {data: {ref: "ordinary"}}}').ast));
    expect(yaml.ast?.definitions[0]).toMatchObject({semantics:{entries:{source:{source:'opaque',leadingTrivia:['data']},leadingTrivia:'data'}}});
  });
  it('preserves arbitrary embedded text and maps literal content lines to the YAML file', () => {
    const text = 'ttr: "0.22"\nmodel: db\ndefs:\n- kind: query\n  id: P\n  properties:\n    sourceText:\n      tag: sql\n      block: |\n        select """ as x\n          from T\n';
    const yaml = parseYamlDocument(text,'sql.yaml');
    expect(yaml.diagnostics).toEqual([]);
    const block = (yaml.ast!.definitions[0] as QueryDef).sourceText!;
    expect(block).toMatchObject({kind:'taggedBlock',tag:'sql',value:'select """ as x\n  from T\n',valueSource:{file:'sql.yaml',line:10,column:8,offsetStart:text.indexOf('select')},indentWidth:8});
  });
  it('retains UTF-16 ranges after astral characters and CRLF line endings', () => {
    const text = doc('er','entity',{description:'😀',attributes:[{id:'name',properties:{type:'text'}}]}).replace(/\n/g,'\r\n');
    const yaml = parseYamlDocument(text,'emoji.yaml');
    expect(yaml.diagnostics).toEqual([]);
    const entity = yaml.ast!.definitions[0];
    expect(entity.kind).toBe('entity');
    if (entity.kind !== 'entity') throw new Error('entity expected');
    expect(entity.attributes![0].source.offsetStart).toBe(text.indexOf('{',text.indexOf('"attributes"')));
    expect(entity.attributes![0].type?.source.offsetStart).toBe(text.indexOf('"text"'));
  });
  it.each([
    'ttr: "0.22"\npackage: &pkg demo',
    'ttr: "0.22"\npackage: *missing',
    'ttr: "0.22"\n<<: {}',
    '%YAML 1.1\n---\nttr: "0.22"',
    '[]', 'null', '',
    'ttr: "0.22"\npackage: !pl demo',
    doc('cn','kind',{bad:Array.from({length:1},() => ({triple:'x'}))}).replace('"bad"','"<<"'),
  ])('refuses unsafe or noncanonical YAML without throwing: %s', (text) => {
    const result = parseYamlDocument(text,'unsafe.yaml');
    expect(result.ast).toBeUndefined();
    expect(result.diagnostics[0].code).toMatch(/^ys\//);
  });
  it('locates an unknown typed key on that key, and a refused anchor on its scalar', () => {
    const text = 'ttr: "0.22"\nmodel: er\ndefs:\n- kind: entity\n  id: P\n  properties:\n    typo: 1';
    expect(parseYamlDocument(text).diagnostics[0].source.offsetStart).toBe(text.indexOf('typo'));
    const anchored = 'ttr: "0.22"\npackage: &pkg demo';
    expect(parseYamlDocument(anchored).diagnostics[0].source.offsetStart).toBe(anchored.indexOf('demo'));
  });
  it('accepts one retained calculation value without changing its text', () => {
    const result = parseYamlDocument(doc('md','map',{calc:{name:'formula',args:{p:'sum(x)'}}}));
    expect(result.diagnostics).toEqual([]);
    expect(portable(result.ast)).toEqual(portable(parseString('model md def map P {calc: formula(p: sum(x))}').ast));
  });
  it.each(['x) } def entity Injected {} //', 'x y', 'x // comment', '['.repeat(70) + '1' + ']'.repeat(70)])('refuses calc-argument syntax injection: %s', (argument) => {
    const result = parseYamlDocument(doc('md','map',{calc:{name:'formula',args:{p:argument}}}));
    expect(result.ast).toBeUndefined();
    expect(result.diagnostics[0].code).toBe('ys/value');
    expect(result.diagnostics[0].message).toMatch(/retained|Retained/);
  });
  it('rejects deep collections and nonfinite or overlong numeric values', () => {
    const nesting = 'ttr: "0.22"\ndefs: ' + '['.repeat(80) + '1' + ']'.repeat(80);
    expect(parseYamlDocument(nesting).diagnostics[0].message).toContain('nesting');
    for (const number of ['.nan','.inf','9'.repeat(1001),'1e9999']) {
      expect(parseYamlDocument(`ttr: "0.22"\nmodel: cn\ndefs: [{kind: kind, id: P, properties: {x: ${number}}}]`).ast).toBeUndefined();
    }
  });
});
