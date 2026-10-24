// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { parseManifest, resolveManifest } from '@tatrman/semantics';
import { synthesize, type SynthContext } from '../../synth/index.js';
import { applyWorkspaceEditToText } from '../../lint-fixes.js';

function context(extra: Record<string, string> = {}, config = ''): SynthContext {
  return { rootUri: '', files: { 'model/er/a.ttrm': 'package shop\nmodel er\ndef entity a {}\ndef entity b {}\n', ...extra },
    manifest: resolveManifest(parseManifest('[packages]\nlayout="off"\n' + config), '') };
}
describe('review-135 synthesis regressions', () => {
  it.each(['constructor','toString','__proto__'])('R14 refuses inherited cardinality %s', kind => {
    expect(synthesize('add-relation', { from:'er.entity.a',to:'er.entity.b',kind:kind as 'one-to-many' }, context())).toMatchObject({ok:false,code:'invalid-args'});
  });
  it.each(['[defaults]\nschema="sales"', '[packages.shop]\ndefault-schema="sales"'])('R8 uses effective schema: %s', config => {
    const ctx=context({'model/db/a.ttrm':'package shop\nmodel db\ndef table orders {columns:[def column id {type:int}]}\n'},config);
    for(const from of ['shop.db.sales.table.orders','db.sales.orders','db.orders']) {
      const r=synthesize('insert-entity',{name:'order',from},ctx);
      expect(r.ok,JSON.stringify(r)).toBe(true);
      if(r.ok) expect(applyWorkspaceEditToText('',r.edit,'model/binding/er2db.ttrm')).toContain('shop.db.sales.table.orders');
    }
  });
  it('R12 ignores inert bare-id entity bindings', () => {
    const ctx=context({'model/er/a.ttrm':'package shop\nmodel er\ndef entity a {binding:db.dbo.t}\n','model/db/a.ttrm':'package shop\nmodel db schema dbo\ndef table t {columns:[def column id {type:int}]}\n'},'[bindings]\nrequired=true');
    expect(synthesize('add-attribute',{entity:'er.entity.a',name:'more',type:'text'},ctx).ok).toBe(true);
  });
  it.each(['def entity half {','def entity same {}\ndef entity same {}'])('R9 tolerates an unrelated broken document: %s', broken => {
    const ctx=context({'model/er/draft.ttrm':'package shop\nmodel er\n'+broken});
    expect(synthesize('add-term',{lang:'en',term:'A',target:'er.entity.a'},ctx).ok).toBe(true);
    if(broken.includes('same')) expect(synthesize('add-term',{lang:'en',term:'Same',target:'er.entity.same'},ctx)).toMatchObject({ok:false,code:'ambiguous-reference'});
    const remove=synthesize('remove-definition',{qname:'er.entity.a'},ctx);
    expect(remove).toMatchObject({ok:false,code:'text-unparseable',message:expect.stringContaining('draft.ttrm')});
  });
  it('R9 refuses writing a broken destination', () => {
    const ctx=context({'model/lexicon/en/er.ttrm':'package shop\nmodel lexicon locale en\ndef term half {'});
    expect(synthesize('add-term',{lang:'en',term:'A',target:'er.entity.a'},ctx)).toMatchObject({ok:false,code:'text-unparseable',message:expect.stringContaining('er.ttrm')});
  });
  it('R7 reuses an encoded existing file key for an explicit Unicode path', () => {
    const root='file:///My%20Model';
    const uri=root+'/model/er/%C3%9A%C4%8Detnictv%C3%AD.ttrm';
    const ctx=context();ctx.rootUri=root;ctx.files={[uri]:'package shop\nmodel er\ndef entity a {}\n'};
    const r=synthesize('insert-entity',{name:'new_entity',file:'model/er/Účetnictví.ttrm'},ctx);
    expect(r.ok,JSON.stringify(r)).toBe(true);
    if(r.ok) {expect(r.touched).toEqual([uri]);expect(r.edit.documentChanges?.some(c=>'kind' in c && c.kind==='create')).toBe(false);}
  });
  it('R6 preserves CRLF in an existing file when appending', () => {
    const uri='model/er/relations.ttrm';
    const ctx=context({[uri]:'package shop\r\nmodel er\r\n\r\ndef relation first {from:er.entity.a,to:er.entity.a}\r\n'});
    const r=synthesize('add-relation',{from:'er.entity.a',to:'er.entity.b',kind:'many-to-one'},ctx);
    expect(r.ok,JSON.stringify(r)).toBe(true);
    if(r.ok) expect(applyWorkspaceEditToText(ctx.files[uri],r.edit,uri)).not.toMatch(/(?<!\r)\n/);
  });
});
