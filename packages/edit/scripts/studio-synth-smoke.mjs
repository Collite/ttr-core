// SPDX-License-Identifier: Apache-2.0
// Read-only checkout smoke: formatter round trips and a real-model Studio query stack, in memory.
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, relative, join } from 'node:path';
import assert from 'node:assert/strict';
import { parseString } from '@tatrman/parser';
import { parseManifest, resolveManifest } from '@tatrman/semantics';
import { format } from '@tatrman/format';
import { synthesize } from '../dist/index.js';
import { applyWorkspaceEditToText } from '../dist/lint-fixes.js';

const root=resolve(process.argv[2] ?? '../hartland');
const files={ 'modeler.toml':readFileSync(join(root,'modeler.toml'),'utf8') };
function walk(dir) {
  for(const e of readdirSync(dir,{withFileTypes:true})) {
    const path=join(dir,e.name);
    if(e.isDirectory()) walk(path);
    else if(path.endsWith('.ttrm')||path.endsWith('.mjs')) files[relative(root,path)]=readFileSync(path,'utf8');
  }
}
walk(join(root,'model'));
const strip=value=>Array.isArray(value)?value.map(strip):value && typeof value==='object'?Object.fromEntries(Object.entries(value).filter(([key,v])=>v!==undefined&&!['source','leadingTrivia','trailingTrivia','tagSource','valueSource'].includes(key)).map(([k,v])=>[k,strip(v)])):value;
let documents=0, definitions=0;
for(const [uri,text] of Object.entries(files)) {
  if(!uri.endsWith('.ttrm')) continue;
  const parsed=parseString(text,uri);
  assert.equal(parsed.errors.filter(e=>e.severity==='error').length,0,uri);
  const defs=parsed.ast.definitions.filter(d=>['entity','relation','er2dbEntity','er2dbAttribute','er2dbRelation','term','pattern','example'].includes(d.kind));
  if(!defs.length) continue;
  const printed=format(text,uri);
  const after=parseString(printed,uri);
  assert.equal(after.errors.filter(e=>e.severity==='error').length,0,uri);
  for(const def of defs) {
    const other=after.ast.definitions.find(d=>d.kind===def.kind&&d.name===def.name);
    assert.deepEqual(strip(other),strip(def),`${uri}: ${def.kind} ${def.name} round trip`);
  }
  assert.equal(format(printed,uri),printed,`${uri}: formatter idempotence`);
  documents++; definitions+=defs.length;
}
const fixture=new URL('../__tests__/synth/vectors/insert-entity/on-query/',import.meta.url);
const op=JSON.parse(readFileSync(new URL('op.json',fixture),'utf8'));
const query=readFileSync(new URL('world/model/er/returns-review.ttrm',fixture),'utf8');
assert.equal(files['model/er/returns-review.ttrm'],undefined,'smoke needs a world before the demo change');
files['model/er/returns-review.ttrm']=query;
const manifest=resolveManifest(parseManifest(files['modeler.toml']),'');
const result=synthesize(op.op,op.args,{files,rootUri:'',manifest});
assert.equal(result.ok,true,JSON.stringify(result));
assert.equal(result.touched.length,4);
for(const uri of result.touched) {
  const after=applyWorkspaceEditToText(files[uri]??'',result.edit,uri);
  assert.equal(parseString(after,uri).errors.filter(e=>e.severity==='error').length,0,uri);
}
const entityText=applyWorkspaceEditToText(query,result.edit,'model/er/returns-review.ttrm');
assert.equal(entityText,readFileSync(new URL('expected/model/er/returns-review.ttrm',fixture),'utf8'),'real-model entity bytes match the portable fixture');
const header=parseString(entityText,'x').ast.definitions.find(d=>d.kind==='entity'&&d.name==='returns_review');
assert.deepEqual(header.attributes.map(a=>a.name),['request_id','customer','category','requested_at','received_at','status','amount']);
console.log(JSON.stringify({root,roundTripDocuments:documents,roundTripDefinitions:definitions,touched:result.touched,outputColumns:header.attributes.map(a=>a.name)},null,2));
