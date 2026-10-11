import { describe, it, expect, vi, beforeEach } from 'vitest';
import { loadDemoFiles } from '../fs/demo-loader';

describe('loadDemoFiles', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('fetches index.json from the correct demo path', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ['modeler.toml', 'db.ttrm'] as string[],
      text: async () => '',
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    await loadDemoFiles('v1-metadata');

    expect(fetchMock).toHaveBeenCalledWith('/samples/v1-metadata/index.json');
  });

  it('returns a ProjectFiles-compatible structure with rootName and files map', async () => {
    const fetchMock = vi.fn((url: string | URL | Request) => {
      const urlStr = typeof url === 'string' ? url : url.toString();
      if (urlStr.endsWith('index.json')) {
        return Promise.resolve({
          ok: true,
          json: async () => ['modeler.toml'] as string[],
        });
      }
      if (urlStr.endsWith('modeler.toml')) {
        return Promise.resolve({
          ok: true,
          text: async () => 'content',
        });
      }
      return Promise.reject(new Error('unexpected URL'));
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    const result = await loadDemoFiles('v1-metadata');

    expect(result.rootName).toBe('v1-metadata');
    expect(result.files).toBeInstanceOf(Map);
    expect(result.files.get('modeler.toml')).toBe('content');
  });

  it('throws when index.json is not found', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
    } as unknown as Response);
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(loadDemoFiles('nonexistent')).rejects.toThrow('Demo manifest not found');
  });
});
describe('loadDemoFiles under a base path (ttr-core#171)', () => {
  it('fetches the sample from under the configured base path', async () => {
    const { setRuntimeConfig } = await import('../runtime-config');
    setRuntimeConfig({ basePath: '/models/', veles: null, lang: null, languages: [] });
    try {
      const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] as string[], text: async () => '' });
      global.fetch = fetchMock as unknown as typeof fetch;
      await loadDemoFiles('v1.1-mini');
      expect(fetchMock).toHaveBeenCalledWith('/models/samples/v1.1-mini/index.json');
    } finally {
      setRuntimeConfig(null);
    }
  });
});
