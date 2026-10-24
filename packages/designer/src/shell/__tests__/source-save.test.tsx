// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ShellFrame } from '../ShellFrame.js';
import { fakeDataSource } from './fake-data-source.js';
import type { ShellEditContext } from '../edit-context.js';
import type { GetGraphResponse } from '@tatrman/lsp';
vi.mock('../../canvas/Kernel.js',async()=> (await import('./layout-harness.js')).kernelMock);
afterEach(()=>{cleanup();vi.restoreAllMocks();window.history.replaceState(null,'','/');});
const graph:GetGraphResponse={schema:'er',edges:[],layout:{nodes:{},edges:{}},imports:[],missingObjects:[],
 nodes:[{qname:'er.entity.a',kind:'entity',name:'a',schemaCode:'er',label:'A',sourceUri:'file:///repo/a.ttrm',sourceLocation:{line:1,column:0},rows:[]}]};
async function open(save:'host'|'none',extension?:ShellEditContext) {
 window.history.replaceState(null,'','/w/ws/s/er');
 const source=fakeDataSource({getGraph:graph,capabilities:{save},getSymbolDetail:{qname:'er.entity.a',kind:'entity',sourceText:'def entity a {}',sourceUri:'file:///repo/a.ttrm'}});
 source.edit=vi.fn(async()=>({ok:true as const,edit:{},touched:[],diagnostics:[]}));
 render(<ShellFrame dataSource={source} workspace="ws" catalog={[{kind:'schema',label:'Schemas',items:[{ref:'er',qname:'er',kind:'schema',schemaCode:'er',label:'ER'}]}]} files={[]} displayMode="just-names" editContext={extension}/>);
 fireEvent.click(await screen.findByTestId('mock-node'));
 fireEvent.click(await screen.findByTestId('peek-escalate'));
 await waitFor(()=>expect(screen.getByTestId('peek-source')).toHaveTextContent('def entity a'));
 fireEvent.click(screen.getByTestId('drawer-edit'));
 return source;
}
it.each(['none','host'] as const)('R5 only offers open source saves with a host path: %s',async save=>{
 await open(save);
 if(save==='none') {expect(screen.queryByTestId('drawer-editor')).toBeNull();expect(screen.queryByTestId('drawer-save')).toBeNull();}
 else expect(screen.getByTestId('drawer-editor')).toBeInTheDocument();
});
it('R11 gives the extension saveNode precedence over an editing data source',async()=>{
 const saveNode=vi.fn(async()=>({ok:true}));
 const context:ShellEditContext={editable:true,saveNode,removeNode:vi.fn(),renderToolbarActions:()=>null,renderNodeMenu:()=>null,renderMissingObjects:()=>null};
 const source=await open('host',context);
 fireEvent.change(screen.getByTestId('drawer-editor'),{target:{value:'def entity a {description:"edit"}'}});
 fireEvent.click(screen.getByTestId('drawer-save'));
 await waitFor(()=>expect(saveNode).toHaveBeenCalledWith('er.entity.a','def entity a {description:"edit"}'));
 expect(source.edit).not.toHaveBeenCalled();
});
