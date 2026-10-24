// SPDX-License-Identifier: Apache-2.0
import { expect, it, vi } from 'vitest';
import { hostedAuthoring } from '../hosted-extension.js';
const init = { type: 'ttr.host.init' as const, api: '/api/model', basePath: '/m/', workspace: 'world', token: '', scope: { kind: 'change' as const, id: 'model%2Fdan%2F1' }, lang: 'en' as const, theme: 'light' as const, features: ['modeler'], extensions: ['authoring'] };
it('never imports commercial code without both a modeler grant and a change scope', async () => {
 const load = vi.fn();
 for (const denied of [{ ...init, features: [] }, { ...init, extensions: [] }, { ...init, scope: { kind: 'served' as const } }]) expect(await hostedAuthoring(denied, true, load)).toBeNull();
 expect(load).not.toHaveBeenCalled();
});
it('imports only the fixed same-origin hook, supplies the source and binding policy, and fails closed', async () => {
 const activate = vi.fn(async () => ({ editable: true, removeNode: async () => false, saveNode: async () => ({ ok: false }), renderToolbarActions: () => null, renderNodeMenu: () => null, renderMissingObjects: () => null })); const load = vi.fn(async () => ({ activate }));
 const loader = await hostedAuthoring(init, true, load);
 const source = {};
 expect(await loader!({ dataSource: source as never })).toMatchObject({ editable: true });
 expect(load).toHaveBeenCalledExactlyOnceWith('/m/_designer/ext/authoring.js');
 expect(activate).toHaveBeenCalledWith({ dataSource: source, bindingsRequired: true });
 await expect(hostedAuthoring(init, true, async () => { throw new Error('missing'); })).rejects.toThrow('missing');
});
