// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { parseManifest, resolveManifest } from '@tatrman/semantics';
import { synthesize, type SynthContext } from '../../synth/index.js';
import { ModelIndex } from '../../synth/model-index.js';
import { utf16Offset } from '../../synth/print.js';
import { applyWorkspaceEditToText } from '../../lint-fixes.js';
const ER='package shop\nmodel er\n';
function ctx(files:Record<string,string>):SynthContext {return {rootUri:'',files,manifest:resolveManifest(parseManifest('[packages]\nlayout="off"'),'')};}
it('R1 preserves management, change semantics, writeback, comments and author spacing verbatim',()=>{
 const table='def table t {\n  management: canon, // keep this\n  changeSemantics: scd2 { validFrom: vf, validTo: vt },\n  writeback { mapping: vf },\n  columns: [def column id {type: int}, def column vf {type: date}, def column vt {type: date}]\n}';
 const uri='model/db/a.ttrm',old='package shop\nmodel db schema dbo\n'+table+'\n';
 const next=table.replace('type: int','type: bigint');
 const r=synthesize('set-source',{nodeRef:'db.dbo.t',text:next},ctx({[uri]:old}));
 expect(r.ok,JSON.stringify(r)).toBe(true);
 if(r.ok)expect(applyWorkspaceEditToText(old,r.edit,uri)).toBe(old.replace(table,next));
});
describe('R1 identity edits for every top-level definition in the recorded Hartland snapshot',()=>{
 const fixture=JSON.parse(readFileSync(new URL('../../../__tests__/synth/fixtures/hartland-snapshot.json',import.meta.url),'utf8')) as {files:Record<string,string>};
 const context={rootUri:'',files:fixture.files,manifest:resolveManifest(parseManifest(fixture.files['modeler.toml']),'')};
 const index=new ModelIndex(context);
 const definitions=index.symbols.all().filter(s=>!s.parent).map(symbol=>{
   const node=index.node(symbol);
   return {qname:symbol.qname,text:node.doc.text.slice(utf16Offset(node.doc.text,node.def.source.offsetStart),utf16Offset(node.doc.text,node.def.source.offsetEnd))};
 });
 it('the snapshot is complete and covers more than 300 definitions',()=>{index.assertComplete();expect(definitions.length).toBeGreaterThan(300);});
 it.each(definitions)('$qname',({qname,text})=>{
   expect(synthesize('set-source',{nodeRef:qname,text},context)).toMatchObject({ok:true,touched:[]});
 });
});
it.each(['def entity y {}','def entity z {}','def query x {language: SQL, sourceText:"select 1"}'])('R3 refuses replacement identity change: %s',text=>{
 const c=ctx({'model/er/a.ttrm':ER+'def entity x {}\ndef entity y {}\ndef relation r {from:er.entity.x,to:er.entity.y}\n'});
 expect(synthesize('set-source',{nodeRef:'er.entity.x',text},c)).toMatchObject({ok:false,code:'invalid-args'});
});
it('R3 refuses member saves and removal of a referenced member',()=>{
 const c=ctx({'model/er/a.ttrm':ER+'def entity x {attributes:[def attribute id {type:int}]}\n',
 'model/binding/a.ttrm':'package shop\nmodel binding\ndef er2db_attribute xid {attribute:er.entity.x.id,target:{column:db.dbo.t.id}}\n'});
 expect(synthesize('set-source',{nodeRef:'er.entity.x.id',text:'def attribute id {type:text}'},c)).toMatchObject({ok:false,code:'invalid-args'});
 expect(synthesize('set-source',{nodeRef:'er.entity.x',text:'def entity x {}'},c)).toMatchObject({ok:false,code:'would-orphan',refs:[expect.objectContaining({uri:'model/binding/a.ttrm'})]});
});
it.each(["['customer','int',false,'sk_customer'],","const ALL = ['customer'];"])('R2 force refuses manual JS reference: %s',line=>{
 const c=ctx({'model/er/a.ttrm':ER+'def entity customer {}\n','model/tests/a.mjs':line+'\n'});
 expect(synthesize('remove-definition',{qname:'er.entity.customer',force:true},c)).toMatchObject({ok:false,code:'would-orphan',refs:[expect.objectContaining({kind:'roster-manual'})]});
});
it('R2 force only removes pure roster lines, including transitive relation names',()=>{
 const uri='model/tests/a.mjs';
 const c=ctx({'model/er/a.ttrm':ER+'def entity customer {}\ndef entity other {}\ndef relation r {from:er.entity.customer,to:er.entity.other}\n',[uri]:"const entries = [\n  'customer', // customer roster\n  'r',\n  'other',\n];\n"});
 const r=synthesize('remove-definition',{qname:'er.entity.customer',force:true},c);
 expect(r.ok,JSON.stringify(r)).toBe(true);
 if(r.ok)expect(applyWorkspaceEditToText(c.files[uri],r.edit,uri)).toBe("const entries = [\n  'other',\n];\n");
});
it('R2 refuses atomically when only a transitive dependent has a manual roster reference',()=>{
 const c=ctx({'model/er/a.ttrm':ER+'def entity customer {}\ndef entity other {}\ndef relation r {from:er.entity.customer,to:er.entity.other}\n','model/tests/a.mjs':"'customer',\nconst RELATIONS = ['r'];\n"});
 expect(synthesize('remove-definition',{qname:'er.entity.customer',force:true},c)).toMatchObject({ok:false,code:'would-orphan',refs:expect.arrayContaining([expect.objectContaining({kind:'roster-manual'})])});
});
it('R2 real Hartland JS fixture is refused without deleting unrelated code',()=>{
 const text=readFileSync(new URL('../../../__tests__/synth/fixtures/hartland-automation-extension.mjs.txt',import.meta.url),'utf8');
 const c=ctx({'model/er/a.ttrm':ER+'def entity customer {}\n','model/tests/automation-extension.test.mjs':text});
 const before=JSON.stringify(c.files),r=synthesize('remove-definition',{qname:'er.entity.customer',force:true},c);
 expect(r).toMatchObject({ok:false,code:'would-orphan'});
 if(!r.ok)expect(r.refs?.filter(ref=>ref.kind==='roster-manual')).toHaveLength(18);
 expect(JSON.stringify(c.files)).toBe(before);
});

it('R3 refuses duplicate members introduced by source replacement',()=>{
 const c=ctx({'model/er/a.ttrm':ER+'def entity x {attributes:[def attribute id {type:int}]}\n'});
 expect(synthesize('set-source',{nodeRef:'er.entity.x',text:'def entity x {attributes:[def attribute id {type:int},def attribute id {type:text}]}'},c)).toMatchObject({ok:false,code:'text-unparseable'});
});

it('R3 a trailing line comment cannot swallow a neighboring definition',()=>{
 const c=ctx({'model/er/a.ttrm':ER+'def entity x {} def entity y {}\n'});
 expect(synthesize('set-source',{nodeRef:'er.entity.x',text:'def entity x {} // trailing comment'},c)).toMatchObject({ok:false,code:'text-unparseable'});
});
