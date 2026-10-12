// SPDX-License-Identifier: Apache-2.0
// ttr-core#169 — the runtime config: read once from config.json beside index.html; every failure
// (no file, the dev server's SPA fallback, bad JSON) yields the defaults, and a bad field is dropped
// on its own rather than taking the viewer down.
import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  configUrl, defaultBasePath, getRuntimeConfig, loadRuntimeConfig, parseRuntimeConfig, setRuntimeConfig,
  type RuntimeConfig,
} from '../runtime-config.js';

const DEFAULTS: RuntimeConfig = { basePath: '/', veles: null, lang: null, languages: [], layoutFormat: 'yaml' };

function fetchReturning(status: number, body: string) {
  return vi.fn(async () => new Response(body, { status })) as unknown as typeof fetch;
}

describe('runtime config', () => {
  afterEach(() => setRuntimeConfig(null));

  it('parses the file the entrypoint renders', () => {
    const raw = { basePath: '/models/', veles: '/models/veles', lang: 'cs', languages: ['en', 'cs'], layoutFormat: 'ttr' };
    expect(parseRuntimeConfig(raw, DEFAULTS)).toEqual(raw);
  });

  it('VIEWER_LAYOUT_FORMAT: yaml or ttr, anything else keeps the default (VL V-32)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(parseRuntimeConfig({ layoutFormat: 'yaml' }, DEFAULTS).layoutFormat).toBe('yaml');
    expect(parseRuntimeConfig({ layoutFormat: 'ttr' }, DEFAULTS).layoutFormat).toBe('ttr');
    expect(parseRuntimeConfig({ layoutFormat: 'TTRG' }, DEFAULTS).layoutFormat).toBe('yaml');
    expect(parseRuntimeConfig({}, DEFAULTS).layoutFormat).toBe('yaml');
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });

  it('keeps the default for each invalid field, and only that field', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const parsed = parseRuntimeConfig({ basePath: 'models', veles: '/veles', lang: 'english!', languages: ['en', 7] }, DEFAULTS);
    expect(parsed).toEqual({ basePath: '/', veles: '/veles', lang: null, languages: [], layoutFormat: 'yaml' });
    expect(warn).toHaveBeenCalledTimes(3);
    warn.mockRestore();
  });

  it('reads an empty or null catalog as "no catalog", and lowercases language tags', () => {
    expect(parseRuntimeConfig({ veles: '' }, DEFAULTS).veles).toBeNull();
    expect(parseRuntimeConfig({ veles: null }, DEFAULTS).veles).toBeNull();
    expect(parseRuntimeConfig({ lang: 'CS', languages: ['EN', 'cs', 'en'] }, DEFAULTS)).toMatchObject({ lang: 'cs', languages: ['en', 'cs'] });
  });

  it('rejects a base path with no trailing slash or with unsafe characters', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(parseRuntimeConfig({ basePath: '/models' }, DEFAULTS).basePath).toBe('/');
    expect(parseRuntimeConfig({ basePath: '/a b/' }, DEFAULTS).basePath).toBe('/');
    expect(parseRuntimeConfig({ basePath: '/"/' }, DEFAULTS).basePath).toBe('/');
    warn.mockRestore();
  });

  it('loads config.json from under the build base', async () => {
    const fetchImpl = fetchReturning(200, JSON.stringify({ basePath: '/models/', veles: '/models/veles' }));
    const cfg = await loadRuntimeConfig(fetchImpl);
    expect(cfg).toMatchObject({ basePath: '/models/', veles: '/models/veles' });
    expect((fetchImpl as unknown as { mock: { calls: unknown[][] } }).mock.calls[0][0]).toBe('/config.json');
  });

  it('falls back to the defaults on a 404, the SPA fallback, bad JSON, or a network error', async () => {
    expect(await loadRuntimeConfig(fetchReturning(404, 'not found'))).toEqual(DEFAULTS);
    expect(await loadRuntimeConfig(fetchReturning(200, '<!DOCTYPE html><html></html>'))).toEqual(DEFAULTS);
    expect(await loadRuntimeConfig(fetchReturning(200, '{ nope'))).toEqual(DEFAULTS);
    const failing = vi.fn(async () => { throw new TypeError('network'); }) as unknown as typeof fetch;
    expect(await loadRuntimeConfig(failing)).toEqual(DEFAULTS);
  });

  it('derives the default base path from an absolute build base, else the document base', () => {
    expect(defaultBasePath('/')).toBe('/');
    expect(defaultBasePath('/models/')).toBe('/models/');
    // the container build (`./`): the entrypoint's <base href> is the document base
    expect(defaultBasePath('./', 'https://apps.example.com/models/')).toBe('/models/');
    expect(defaultBasePath('./', 'https://apps.example.com/models/index.html')).toBe('/models/');
    expect(configUrl('/models/')).toBe('/models/config.json');
  });

  it('serves the defaults until a config is installed', () => {
    expect(getRuntimeConfig()).toEqual(DEFAULTS);
    setRuntimeConfig({ ...DEFAULTS, veles: '/veles' });
    expect(getRuntimeConfig().veles).toBe('/veles');
  });
});
