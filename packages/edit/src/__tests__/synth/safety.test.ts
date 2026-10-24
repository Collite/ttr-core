// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { parseString } from '@tatrman/parser';
import { parseManifest, resolveManifest } from '@tatrman/semantics';
import { synthesize, type SynthContext, type SynthResult } from '../../synth/index.js';
import { applyWorkspaceEditToText } from '../../lint-fixes.js';

function context(er = '', extra: Record<string, string> = {}, required = true): SynthContext {
  return { rootUri: '', manifest: resolveManifest(parseManifest(`[bindings]\nrequired = ${required}\n[packages]\nlayout = "off"`), ''), files: { 'model/er/a.ttrm': 'package shop\nmodel er\n' + er, ...extra } };
}
function apply(result: SynthResult, ctx: SynthContext): Record<string, string> {
  expect(result.ok, JSON.stringify(result)).toBe(true);
  if (!result.ok) throw new Error(result.message);
  const out = { ...ctx.files };
  for (const uri of result.touched) {
    out[uri] = applyWorkspaceEditToText(ctx.files[uri] ?? '', result.edit, uri);
    if (uri.endsWith('.ttrm')) expect(parseString(out[uri], uri).errors.filter(e => e.severity === 'error')).toEqual([]);
  }
  return out;
}
const entities = 'def entity parent { attributes: [def attribute id {type: int}] }\ndef entity child { attributes: [def attribute id {type: int}] }';
const db = 'package shop\nmodel db schema dbo\ndef table parent {columns:[def column id {type:int}]}\ndef table child {columns:[def column id {type:int}]}';
const bindings = 'package shop\nmodel binding\ndef er2db_entity p {entity:er.entity.parent,target:{table:db.dbo.parent}}\ndef er2db_entity c {entity:er.entity.child,target:{table:db.dbo.child}}';

describe('Studio synthesizer safety', () => {
  it('requires attribute and FK bindings on physically bound entities', () => {
    const ctx = context(entities, { 'model/db/a.ttrm': db, 'model/binding/a.ttrm': bindings });
    expect(synthesize('add-attribute', {entity:'er.entity.parent',name:'extra',type:'text'}, ctx)).toMatchObject({ok:false,code:'attribute-needs-binding'});
    expect(synthesize('add-relation', {from:'er.entity.child',to:'er.entity.parent',kind:'many-to-one'}, ctx)).toMatchObject({ok:false,code:'fk-needs-binding'});
  });
  it('detects a query binding declared outside the entity', () => {
    const ctx = context(entities, {'model/binding/a.ttrm': bindings.replace('table:db.dbo.parent', 'query:er.query.q')});
    expect(synthesize('add-attribute',{entity:'er.entity.parent',name:'extra',type:'text'},ctx)).toMatchObject({ok:false,code:'entity-is-query-backed'});
  });
  it('does not bind an attribute to another table', () => {
    const ctx = context(entities, { 'model/db/a.ttrm': db, 'model/binding/a.ttrm': bindings });
    expect(synthesize('add-attribute',{entity:'er.entity.parent',name:'extra',type:'int',column:'db.dbo.child.id'},ctx)).toMatchObject({ok:false,code:'column-unknown'});
  });
  it('resolves a foreign-key column to its FK definition', () => {
    const ctx = context(entities, { 'model/db/a.ttrm': db + '\ndef fk fk_child_parent {from:[db.dbo.child.id],to:[db.dbo.parent.id]}', 'model/binding/a.ttrm':bindings });
    const out = apply(synthesize('add-relation',{from:'er.entity.child',to:'er.entity.parent',kind:'many-to-one',fk:'db.dbo.child.id'},ctx),ctx);
    expect(out['model/er/relations.ttrm']).toContain('binding: { fk: shop.db.dbo.fk.fk_child_parent }');
  });
  it('refuses an FK connecting other bound tables', () => {
    const ctx = context(entities, { 'model/db/a.ttrm': db + '\ndef fk fk_wrong {from:[db.dbo.parent.id],to:[db.dbo.parent.id]}', 'model/binding/a.ttrm':bindings });
    expect(synthesize('add-relation',{from:'er.entity.child',to:'er.entity.parent',kind:'many-to-one',fk:'db.dbo.fk_wrong'},ctx)).toMatchObject({ok:false,code:'source-unknown'});
  });
  it.each(['def entity parent {}','def entity parent {attributes: []}','def entity parent {attributes: [/* keep ] */]}'])('inserts the first attribute safely: %s', body => {
    const ctx = context(body, {}, false);
    const out = apply(synthesize('add-attribute',{entity:'er.entity.parent',name:'id',type:'int'},ctx),ctx)['model/er/a.ttrm'];
    expect(out).toContain('def attribute id');
    if(body.includes('/*')) expect(out).toContain('/* keep ] */');
  });
  it('preserves inline mappings and comments when adding an attribute', () => {
    const ctx = context('// outside 😀\ndef entity parent {\n    // keep the primary key\n    attributes: [def attribute id { type:int, binding: id }],\n    binding: {target:{table:db.dbo.parent}}\n} // tail\n', {'model/db/a.ttrm':db});
    const out=apply(synthesize('add-attribute',{entity:'er.entity.parent',name:'extra',type:'text',column:'db.dbo.parent.id'},ctx),ctx)['model/er/a.ttrm'];
    expect(out).toContain('// outside 😀'); expect(out).toContain('// tail'); expect(out).toContain('// keep the primary key');
    const entity=parseString(out,'x').ast!.definitions[0];
    expect(entity.kind).toBe('entity');
    if(entity.kind==='entity') { expect(entity.binding).toBeDefined(); expect(entity.attributes![0].binding).toBeDefined(); }
  });
  it('uses UTF-16 ranges after non-BMP text and preserves neighbouring definitions', () => {
    const ctx=context('// 😀 before\ndef entity parent {description:"😀",attributes:[def attribute id {type:int}]}\n\ndef entity child {}\n');
    const out=apply(synthesize('set-source',{nodeRef:'er.entity.child',text:'def entity child {description:"changed"}'},ctx),ctx)['model/er/a.ttrm'];
    expect(out.startsWith(ctx.files['model/er/a.ttrm'].split('def entity child')[0])).toBe(true); expect(out).toContain('description:"changed"');
  });
  it('creates files explicitly and leaves the input snapshot immutable', () => {
    const ctx=context('',{},false); const before=structuredClone(ctx.files);
    const result=synthesize('insert-entity',{name:'empty',file:'model/er/empty.ttrm'},ctx);
    expect(ctx.files).toEqual(before); expect(result.ok).toBe(true);
    if(result.ok) expect(result.edit.documentChanges?.[0]).toEqual({kind:'create',uri:'model/er/empty.ttrm'});
    apply(result,ctx);
  });
  it('derives the package for the first definition under a URI root',()=>{
    const ctx={ ...context('',{},false),rootUri:'file:///snapshot',files:{} };
    const out=apply(synthesize('insert-entity',{name:'first',terms:{en:'first entity'}},ctx),ctx);
    expect(out['file:///snapshot/model/er/first.ttrm']).toContain('package model.er');
    expect(out['file:///snapshot/model/lexicon/en/first.ttrm']).toContain('for: model.er.er.entity.first');
  });
  it('refuses ambiguous root-elided names across packages', () => {
    const ctx=context('def entity x {}',{'model/other.ttrm':'package other\nmodel er\ndef entity x {}'});
    expect(synthesize('add-term',{lang:'en',term:'x',target:'er.entity.x'},ctx)).toMatchObject({ok:false,code:'ambiguous-reference'});
    apply(synthesize('add-term',{lang:'en',term:'x',target:'shop.er.entity.x'},ctx),ctx);
  });
  it('refuses duplicate physical declarations before editing', () => {
    const ctx=context('def entity x {}',{'model/other.ttrm':'package shop\nmodel er\ndef entity x {}'});
    expect(synthesize('set-source',{nodeRef:'shop.er.entity.x',text:'def entity x {}'},ctx)).toMatchObject({ok:false,code:'ambiguous-reference'});
  });
  it.each(['../outside.ttrm','/tmp/out.ttrm','file:///tmp/out.ttrm','model/../out.ttrm'])('refuses destination traversal %s', file=>{
    expect(synthesize('insert-entity',{name:'new_entity',file},context('',{},false))).toMatchObject({ok:false,code:'invalid-args'});
  });
  it('refuses a destination with a different package/model',()=>{
    const ctx=context('def entity parent {}',{'model/db/a.ttrm':db});
    expect(synthesize('insert-entity',{name:'new_entity',from:'db.dbo.parent',file:'model/db/a.ttrm'},ctx)).toMatchObject({ok:false,code:'invalid-args'});
  });
  it.each(['x } def entity injected {','int','with-hyphen'])('refuses malformed or reserved names %s',name=>{
    expect(synthesize('insert-entity',{name},context('',{},false))).toMatchObject({ok:false,code:'invalid-args'});
  });
  it('refuses source text with extra definitions/directives',()=>{
    const ctx=context('def entity x {}');
    for(const text of ['def entity x {} def entity y {}','package other\ndef entity x {}','model db\ndef entity x {}']) expect(synthesize('set-source',{nodeRef:'er.entity.x',text},ctx)).toMatchObject({ok:false,code:'text-unparseable'});
  });
  it.each(['int, isKey: true', 'int } def entity injected {} def attribute x {type:int'])('refuses a type containing extra syntax: %s', type => {
    expect(synthesize('add-attribute',{entity:'er.entity.x',name:'id',type},context('def entity x {}',{},false))).toMatchObject({ok:false,code:'invalid-args'});
  });
  it('locates replacement parse errors in the candidate document',()=>{
    const ctx=context('// 😀 header\ndef entity x {}');
    const result=synthesize('set-source',{nodeRef:'er.entity.x',text:'def entity x { type: }'},ctx);
    expect(result).toMatchObject({ok:false,code:'text-unparseable'});
    if(!result.ok) { expect(result.message).not.toBe('Provide exactly one parseable definition.');expect(result.refs![0].range.start.line).toBe(3); }
  });
  it('rolls back a multi-file insert when its final term conflicts',()=>{
    const ctx=context('def entity other {}',{'model/db/a.ttrm':db,'model/lexicon/en/a.ttrm':'package shop\nmodel lexicon locale en\ndef term other {for:er.entity.other,forms:["taken"]}'});
    const before=structuredClone(ctx.files);
    expect(synthesize('insert-entity',{name:'copy',from:'db.dbo.parent',terms:{en:'taken'}},ctx)).toMatchObject({ok:false,code:'term-exists'});
    expect(ctx.files).toEqual(before);
  });
  it('cleans member bindings and relation bindings transitively on force',()=>{
    const ctx=context(entities+'\ndef relation r {from:er.entity.child,to:er.entity.parent}',{'model/binding/a.ttrm':bindings+'\ndef er2db_attribute a {attribute:er.entity.parent.id,target:{column:db.dbo.parent.id}}\ndef er2db_relation rmap {relation:er.relation.r,fk:db.dbo.fk_x}'});
    const out=apply(synthesize('remove-definition',{qname:'er.entity.parent',force:true},ctx),ctx);
    expect(out['model/er/a.ttrm']).not.toContain('def entity parent'); expect(out['model/er/a.ttrm']).not.toContain('def relation');
    expect(out['model/binding/a.ttrm']).not.toContain('def er2db_attribute'); expect(out['model/binding/a.ttrm']).not.toContain('rmap'); expect(out['model/binding/a.ttrm']).toContain('er.entity.child');
  });
  it('removes an inline query binding without removing the entity',()=>{
    const ctx=context('def query q {sourceText:"SELECT 1 AS id"}\ndef entity e {attributes:[def attribute id {type:int}],binding:{target:{query:er.query.q}}}');
    const out=apply(synthesize('remove-definition',{qname:'er.query.q',force:true},ctx),ctx)['model/er/a.ttrm'];
    expect(out).toContain('def entity e');expect(out).not.toContain('binding:');
  });
  it('removes inline member mappings when their physical table is removed',()=>{
    const ctx=context('def entity parent {binding:{target:{table:db.dbo.parent}},attributes:[def attribute id {type:int,binding:id}]}', {'model/db/a.ttrm':db});
    const out=apply(synthesize('remove-definition',{qname:'db.dbo.parent',force:true},ctx),ctx)['model/er/a.ttrm'];
    expect(out).toContain('def entity parent');expect(out).toContain('def attribute id');expect(out).not.toContain('binding:');
  });
  it('preserves a same-named attribute when deleting the top-level definition',()=>{
    const ctx=context('def entity customer {}\ndef entity order {attributes:[def attribute customer {type:int}]}\ndef relation places {from:order,to:customer}');
    const out=apply(synthesize('remove-definition',{qname:'er.entity.customer',force:true},ctx),ctx)['model/er/a.ttrm'];
    expect(out).toContain('def attribute customer');expect(out).toContain('def entity order');expect(out).not.toContain('def relation');
  });
  it('no-op repeated terms have an empty edit',()=>{
    const ctx=context('def entity x {}');
    const first=apply(synthesize('add-term',{lang:'en',term:'quoted "term"',target:'er.entity.x'},ctx),ctx);
    const result=synthesize('add-term',{lang:'en',term:'quoted "term"',target:'er.entity.x'},{...ctx,files:first});
    expect(result).toMatchObject({ok:true,touched:[],edit:{documentChanges:[]}});
  });
});

describe('known query outputs',()=>{
  function run(sql:string) { const ctx=context('def entity source_row {attributes:[def attribute id {type:int},def attribute amount {type:decimal}]}\ndef query q {sourceText:"""sql\n'+sql+'\n"""}');return {ctx,result:synthesize('insert-entity',{name:'stacked',on:'er.query.q'},ctx)}; }
  it.each(['SELECT amount * 2 AS total FROM source_row','SELECT s.id, SUM(s.amount) AS total FROM source_row s GROUP BY s.id','SELECT s.* FROM source_row s','SELECT COUNT(*) AS total FROM source_row','SELECT 1 AS id, \'literal\' AS label'])('infers a known output: %s',sql=>{const {ctx,result}=run(sql);apply(result,ctx);});
  it.each(['SELECT id, id FROM source_row','SELECT unknown FROM source_row','SELECT COALESCE(amount, 0) AS total FROM source_row','WITH q AS (SELECT id FROM source_row) SELECT id FROM q','SELECT SUM(amount) FROM source_row','SELECT id FROM missing'])('refuses an unknown/ambiguous output: %s',sql=>{expect(run(sql).result).toMatchObject({ok:false,code:'source-unknown'});});
});
