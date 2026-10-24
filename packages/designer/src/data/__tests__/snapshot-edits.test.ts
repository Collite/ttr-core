// SPDX-License-Identifier: Apache-2.0
import { expect, it, vi } from 'vitest';
import { BffFilesDataSource } from '../bff-files-data-source.js';
import { createTestClient } from './lsp-harness.js';
const path='model/er/a.ttrm';
const original='package shop\nmodel er\ndef entity a {description:"base"}\n';
async function harness() {
 let sha='one',files={'modeler.toml':'[packages]\nlayout="off"',[path]:original};
 const clients: Awaited<ReturnType<typeof createTestClient>>[]=[];
 const createClient=vi.fn(async()=>{const c=await createTestClient();clients.push(c);return c;});
 const source=new BffFilesDataSource({api:'/api/model',token:'token',scope:{kind:'served'}},{fetch:vi.fn(async()=>({ok:true,json:async()=>({sha,files})} as Response)),createClient});
 await source.refresh();
 const edit=(value:string)=>source.edit({op:'set-source',args:{nodeRef:'er.entity.a',text:`def entity a {description:"${value}"}`}});
 return {source,clients,createClient,edit,snapshot:(next:Record<string,string>,nextSha='two')=>{files=next as typeof files;sha=nextSha;},files:()=>files};
}
it('R4 post-save init keeps edits made after collect on the same file',async()=>{
 const h=await harness();try {
  await h.edit('collected');const collected=h.source.collectChangedFiles();
  await h.edit('pending');h.snapshot({...h.files(),[path]:collected[0].text!});
  await h.source.refresh({reason:'save'});
  expect(h.source.sha).toBe('two');expect(h.source.project?.files.get(path)).toBe(collected[0].text);
  expect(h.source.changedFiles()).toEqual([{path,text:expect.stringContaining('pending')}]);
 } finally {h.source.dispose();}
});
it('R4 a post-collect undo back to the original base survives the save acknowledgement',async()=>{
 const h=await harness();try {
  await h.edit('collected');const collected=h.source.collectChangedFiles();await h.edit('base');
  const undone=h.source.changedFiles()[0]?.text ?? original;
  h.snapshot({...h.files(),[path]:collected[0].text!});await h.source.refresh({reason:'save'});
  expect(h.source.changedFiles()).toEqual([{path,text:undone}]);
 } finally {h.source.dispose();}
});
it('R4 preserves a new file authored after collect',async()=>{
 const h=await harness();try {
  await h.edit('collected');const collected=h.source.collectChangedFiles();
  expect((await h.source.edit({op:'add-term',args:{lang:'en',term:'A',target:'er.entity.a'}})).ok).toBe(true);
  h.snapshot({...h.files(),[path]:collected[0].text!});await h.source.refresh({reason:'save'});
  expect(h.source.changedFiles()).toEqual([{path:'model/lexicon/en/er.ttrm',text:expect.stringContaining('forms: ["A"]')}]);
 } finally {h.source.dispose();}
});
it('R4 conflicts retain the old snapshot and pending whole-file bytes',async()=>{
 const h=await harness();try {
  await h.edit('collected');h.source.collectChangedFiles();await h.edit('pending');
  const pending=h.source.changedFiles();h.snapshot({...h.files(),[path]:original.replace('base','remote')});
  await expect(h.source.refresh({reason:'save'})).rejects.toMatchObject({code:'frame/edit-conflict'});
  expect(h.source.sha).toBe('one');expect(h.source.changedFiles()).toEqual(pending);
 } finally {h.source.dispose();}
});
it('R4 refresh refuses dirty SHA replacement unless discard is explicit',async()=>{
 const h=await harness();try {
  await h.edit('pending');h.snapshot({...h.files()});
  await expect(h.source.refresh()).rejects.toMatchObject({code:'frame/unsaved-edits'});
  expect(h.source.sha).toBe('one');expect(h.source.changedFiles()).toHaveLength(1);
  await h.source.refresh({discard:true});expect(h.source.sha).toBe('two');expect(h.source.changedFiles()).toEqual([]);
 } finally {h.source.dispose();}
});
it('R4 a post-save init without collect cannot discard dirty files',async()=>{
 const h=await harness();try {
  await h.edit('pending');h.snapshot({...h.files()});
  await expect(h.source.refresh({reason:'save'})).rejects.toMatchObject({code:'frame/unsaved-edits'});
  expect(h.source.sha).toBe('one');expect(h.source.changedFiles()).toHaveLength(1);
 } finally {h.source.dispose();}
});
it('R4 rechecks late edits while a replacement worker loads',async()=>{
 const h=await harness();let release!:()=>void;try {
  await h.edit('collected');const collected=h.source.collectChangedFiles();h.snapshot({...h.files(),[path]:collected[0].text!});
  const gate=new Promise<void>(resolve=>{release=resolve;});
  h.createClient.mockImplementationOnce(async()=>{await gate;return createTestClient();});
  const refresh=h.source.refresh({reason:'save'});
  await vi.waitFor(()=>expect(h.createClient).toHaveBeenCalledTimes(2));
  await h.edit('late');release();await refresh;
  expect(h.source.sha).toBe('two');expect(h.source.changedFiles()).toEqual([{path,text:expect.stringContaining('late')}]);
 } finally {release?.();h.source.dispose();}
});
it('R4 an in-flight old-worker edit prevents swapping until the host retries',async()=>{
 const h=await harness();let release!:()=>void;try {
  await h.edit('collected');const collected=h.source.collectChangedFiles();h.snapshot({...h.files(),[path]:collected[0].text!});
  const edit=h.clients[0].edit.bind(h.clients[0]),gate=new Promise<void>(resolve=>{release=resolve;});
  h.clients[0].edit=vi.fn(async params=>{await gate;return edit(params);});
  const editing=h.edit('late');await vi.waitFor(()=>expect(h.clients[0].edit).toHaveBeenCalled());
  await expect(h.source.refresh({reason:'save'})).rejects.toMatchObject({code:'frame/unsaved-edits'});
  expect(h.source.sha).toBe('one');release();await editing;
  await h.source.refresh({reason:'save'});expect(h.source.changedFiles()).toEqual([{path,text:expect.stringContaining('late')}]);
 } finally {release?.();h.source.dispose();}
});
