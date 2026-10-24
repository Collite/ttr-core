// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { parseString } from '@tatrman/parser';
import { format } from '../index.js';

describe('inline binding round trips',()=>{
  it.each([
    'def entity x {binding:{target:{query:er.query.q}},attributes:[def attribute id {type:int,binding:id}]}',
    'def entity x {binding:db.dbo.x}',
    'def relation r {from:er.entity.x,to:er.entity.y,binding:{fk:db.dbo.fk_x}}',
  ])('retains bindings: %s',definition=>{
    const source='package shop\nmodel er\n'+definition.replace('{', '{description:{en:"English",cs:"Česky"},');
    const before=parseString(source,'x').ast!;
    const printed=format(source,'x');
    const after=parseString(printed,'x');
    expect(after.errors.filter(e=>e.severity==='error')).toEqual([]);
    const strip=(v:unknown):unknown=>Array.isArray(v)?v.map(strip):v&&typeof v==='object'?Object.fromEntries(Object.entries(v).filter(([k])=>!['source','leadingTrivia','trailingTrivia'].includes(k)).map(([k,x])=>[k,strip(x)])):v;
    expect(strip(after.ast)).toEqual(strip(before));
    expect(format(printed,'x')).toBe(printed);
  });
});
