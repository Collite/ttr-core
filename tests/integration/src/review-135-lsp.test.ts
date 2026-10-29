// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { PassThrough } from 'node:stream';
import * as lsp from 'vscode-languageserver/node';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, join, relative } from 'node:path';
import { parseManifest, resolveManifest } from '@tatrman/semantics';
import { stageWorkspaceEdit } from '../../../packages/lsp/dist/edit-cache.js';
import { synthesize, type EditOp, type EditArgs } from '../../../packages/edit/dist/index.js';
import * as parser from '@tatrman/parser';
import { createServerConnection } from '@tatrman/lsp/server';
import { applyWorkspaceEditToText } from '../../../packages/edit/dist/index.js';
const clean: {dispose(): void}[]=[];
afterEach(()=>{for(const d of clean.splice(0)) d.dispose();vi.restoreAllMocks();});
async function boot(root: string, files: Record<string,string>, manifest='[packages]\nlayout="off"', disk=false) {
 const a=new PassThrough(),b=new PassThrough();
 const client=lsp.createConnection(new lsp.StreamMessageReader(a),new lsp.StreamMessageWriter(b));
 const server=lsp.createConnection(new lsp.StreamMessageReader(b),new lsp.StreamMessageWriter(a));
 clean.push(client,server);
 createServerConnection(server,disk?{scanProjectFiles:async()=>Object.entries(files).map(([uri,text])=>({uri,text})),readProjectFile:async(uri)=>files[uri]}:{});
 client.listen();server.listen();
 await client.sendRequest('initialize',{processId:null,rootUri:disk?root:null,capabilities:{}});
 await client.sendRequest('modeler/setProjectRoot',{projectRoot:root,manifest});
 if(!disk)for(const [uri,text]of Object.entries(files))client.sendNotification('textDocument/didOpen',{textDocument:{uri,languageId:'ttr',version:1,text}});
 await client.sendRequest('modeler/listSymbols',{});
 return client;
}
it.each(['My Model','Účetnictví'])('R7 edits existing files under the raw browser root %s',async name=>{
 // ttr-core#326: `er/a.ttrm`'s stem names the existing destination.
 const root=`file:///${name}`,uri=root+'/model/lexicon/en/a.ttrm';
 const client=await boot(root,{[root+'/model/er/a.ttrm']:'package shop\nmodel er\ndef entity a {}\n',[uri]:'package shop\nmodel lexicon locale en\n'});
 const result=await client.sendRequest('modeler/edit',{op:'add-term',args:{lang:'en',term:'A',target:'er.entity.a'}}) as {ok:boolean;touched:string[]};
 expect(result).toMatchObject({ok:true,touched:[uri]});
});
it('R13 renames member binding refs in a layout-derived package',async()=>{
 const root='file:///repo',uri=root+'/sales/entity.ttrm',bindingUri=root+'/sales/binding.ttrm';
 const entity='model er\ndef entity old_name {attributes:[def attribute id {type:int}]}\n';
 const binding='model binding\ndef er2db_attribute map_id {attribute:er.entity.old_name.id,target:{column:db.dbo.t.id}}\n';
 const client=await boot(root,{[uri]:entity,[bindingUri]:binding},'[packages]\nroot="shop"');
 const edit=await client.sendRequest('textDocument/rename',{textDocument:{uri},position:{line:1,character:14},newName:'new_name'}) as lsp.WorkspaceEdit;
 expect(applyWorkspaceEditToText(binding,edit,bindingUri)).toContain('attribute:er.entity.new_name.id');
});
it('R10 a watched change parses at most linearly across 50 disk documents',async()=>{
 const spy=vi.spyOn(parser,'parseString');
 const root='file:///repo';
 const files=Object.fromEntries(Array.from({length:50},(_,i)=>[`${root}/model/er/e${i}.ttrm`,`package shop\nmodel er\ndef entity e${i} {}\n`]));
 const client=await boot(root,files,'[packages]\nlayout="off"',true);
 const uri=root+'/model/er/e0.ttrm';
 let published=0;client.onNotification('textDocument/publishDiagnostics',()=>published++);
 spy.mockClear();
 files[uri]+='// external edit\n';
 client.sendNotification('workspace/didChangeWatchedFiles',{changes:[{uri,type:lsp.FileChangeType.Changed}]});
 await vi.waitFor(()=>expect(published).toBeGreaterThanOrEqual(50),{timeout:30000});
 expect(spy.mock.calls.length).toBeLessThanOrEqual(100);
},40000);

it('R6 every successful CRLF vector produces ranges accepted by the LSP',()=>{
 const root='file:///crlf';
 const vectors=resolve('../../packages/edit/__tests__/synth/vectors');
 const files=(dir:string)=>Object.fromEntries(readdirSync(dir,{recursive:true,withFileTypes:true}).filter(e=>e.isFile()).map(e=>{
   const path=join(e.parentPath,e.name);return [relative(dir,path),readFileSync(path,'utf8').replace(/\r?\n/g,'\r\n')];
 }));
 for(const op of readdirSync(vectors))for(const name of readdirSync(join(vectors,op))) {
   const dir=join(vectors,op,name), world=files(join(dir,'world'));
   const input=Object.fromEntries(Object.entries(world).map(([path,text])=>[root+'/'+path,text]));
   const command=JSON.parse(readFileSync(join(dir,'op.json'),'utf8')) as {op:EditOp;args:EditArgs};
   const result=synthesize(command.op,command.args,{files:input,rootUri:root,manifest:resolveManifest(parseManifest(world['modeler.toml']),'/crlf')});
   if(!result.ok)continue;
   const staged=stageWorkspaceEdit(new Map(Object.entries(input)),result.edit,root,()=>undefined);
   const expected=readdirSync(dir).includes('expected') ? files(join(dir,'expected')) : {};
   for(const [path,text]of Object.entries(expected))expect(staged.files.get(root+'/'+path),op+'/'+name+'/'+path).toBe(text);
 }
});
