// SPDX-License-Identifier: Apache-2.0
// ttr-core#171 — the address bar keeps the base path and the session parameters across every
// rewrite. Pure mapping between browser locations and the shell's APP paths (contracts §6).
import { afterEach, describe, it, expect } from 'vitest';
import { stripBasePath, toAppPath, toBrowserPath, stickyParams, currentAppPath, replaceAppPath, absoluteUrlFor } from '../app-location.js';
import { defaultRuntimeConfig, setRuntimeConfig } from '../../runtime-config.js';
import { parsePath, formatPath } from '../url.js';

describe('app-location (ttr-core#171)', () => {
  afterEach(() => { window.history.replaceState(null, '', '/'); setRuntimeConfig(null); });
  it('keeps hosted app navigation in the hash and makes shareable links enter the parent', () => {
    window.history.replaceState(null, '', '/m/_designer/index.html?backend=host&lang=cs#/w/hartland/s/er');
    setRuntimeConfig({ ...defaultRuntimeConfig(), basePath: '/m/' });
    expect(currentAppPath()).toBe('/w/hartland/s/er');
    expect(absoluteUrlFor('/s/viewer?object=er')).toBeNull();
    replaceAppPath('/w/hartland/s/md?skin=md.star');
    expect(window.location.pathname).toBe('/m/_designer/index.html');
    expect(window.location.search).toBe('?backend=host&lang=cs');
    expect(window.location.hash).toBe('#/w/hartland/s/md?skin=md.star');
    expect(absoluteUrlFor('/w/hartland/s/layout%3Asales')).toBe(`${window.location.origin}/m/hartland/s/layout%3Asales`);
  });
  it('strips the base path, leaving the shell its own grammar', () => {
    expect(stripBasePath('/w/veles/s/er', '/')).toBe('/w/veles/s/er');
    expect(stripBasePath('/models/w/veles/s/er', '/models/')).toBe('/w/veles/s/er');
    expect(stripBasePath('/models/', '/models/')).toBe('/');
    expect(stripBasePath('/models', '/models/')).toBe('/');
    expect(stripBasePath('/apps/models/w/a', '/apps/models/')).toBe('/w/a');
  });

  it('removes the session parameters from the app path, the dev bearer included', () => {
    expect(toAppPath('/models/w/veles/s/er', '?veles=/models/veles&skin=er.crow&lang=cs', '/models/'))
      .toBe('/w/veles/s/er?skin=er.crow');
    expect(toAppPath('/w/x/s/y', '?veles=wss://v.example&velesToken=abc', '/')).toBe('/w/x/s/y');
    expect(toAppPath('/w/ws/p/lineage', '?root=a.b&demo=v1.1-mini&scope=neighborhood', '/'))
      .toBe('/w/ws/p/lineage?root=a.b&scope=neighborhood');
  });

  it('keeps the sticky parameters in a fixed order, and never the dev bearer', () => {
    expect(stickyParams('?lang=cs&skin=x&veles=/veles&velesToken=t')).toEqual([['veles', '/veles'], ['lang', 'cs']]);
    expect(stickyParams('')).toEqual([]);
  });

  it('rebuilds the browser path under the base path, with the session parameters appended readably', () => {
    const sticky = stickyParams('?veles=http://localhost:7260&lang=cs');
    expect(toBrowserPath('/w/localhost:7260/s/er', '/', sticky))
      .toBe('/w/localhost:7260/s/er?veles=http://localhost:7260&lang=cs');
    expect(toBrowserPath('/w/veles/s/er?skin=er.crow', '/models/', stickyParams('?veles=/models/veles')))
      .toBe('/models/w/veles/s/er?skin=er.crow&veles=/models/veles');
    expect(toBrowserPath('/', '/models/', [])).toBe('/models/');
    // a value with `&`/`=` stays one parameter
    expect(toBrowserPath('/w/a', '/', [['demo', 'a&b=c']])).toBe('/w/a?demo=a%26b%3Dc');
  });

  it('round-trips every shell URL state under a sub-path with a catalog selected', () => {
    const states = [
      { kind: 'none' as const, workspace: 'veles' },
      { kind: 'subject' as const, workspace: 'veles', subjectRef: 'er' },
      { kind: 'subject' as const, workspace: 'veles', subjectRef: 'file:///g/er_sales.ttrg', skin: 'er.crow' as const },
      { kind: 'perspective' as const, workspace: 'veles', perspective: 'lineage' as const, params: { root: 'er.entity.item', scope: 'neighborhood' } },
    ];
    const sticky = stickyParams('?veles=/models/veles&lang=en');
    for (const state of states) {
      const browser = toBrowserPath(formatPath(state), '/models/', sticky);
      expect(browser.startsWith('/models/w/veles')).toBe(true);
      expect(browser).toContain('veles=/models/veles');
      const q = browser.indexOf('?');
      const app = toAppPath(q === -1 ? browser : browser.slice(0, q), q === -1 ? '' : browser.slice(q), '/models/');
      expect(parsePath(app)).toEqual(state);
    }
  });
});
