// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi } from 'vitest';
import { loadProjectViaUpload } from '../file-system';

describe('loadProjectViaUpload', () => {
  function makeFile(name: string, webkitRelativePath: string, content: string): File {
    const file = new File([content], name, { type: 'text/plain' });
    (file as File & { webkitRelativePath: string }).webkitRelativePath = webkitRelativePath;
    return file;
  }

  it('filters out non-.ttrm/.ttrg/.toml files', async () => {
    const input = {
      files: [
        makeFile('artikl.ttrm', 'project/artikl.ttrm', 'def entity artikl {}'),
        makeFile('overview.ttrg', 'project/overview.ttrg', 'graph overview {}'),
        makeFile('modeler.toml', 'project/modeler.toml', '[project]'),
        makeFile('diagram.png', 'project/diagram.png', 'fake png content'),
      ],
    } as unknown as HTMLInputElement & { files: FileList };

    const result = await loadProjectViaUpload(input);
    const keys = Array.from(result.files.keys());
    expect(keys).toHaveLength(3);
    expect(keys).not.toContain('diagram.png');
    expect(keys).toContain('artikl.ttrm');
    expect(keys).toContain('overview.ttrg'); // .ttrg carries layout now (v1.1 D4); .ttrl is dead
    expect(keys).toContain('modeler.toml');
  });

  it('returns ProjectFiles with both .ttrm and .toml entries', async () => {
    const input = {
      files: [
        makeFile('er.ttrm', 'project/er.ttrm', 'def entity foo {}'),
        makeFile('modeler.toml', 'project/modeler.toml', '[project]'),
      ],
    } as unknown as HTMLInputElement & { files: FileList };

    const result = await loadProjectViaUpload(input);
    expect(result.files.has('er.ttrm')).toBe(true);
    expect(result.files.has('modeler.toml')).toBe(true);
    expect(result.files.get('er.ttrm')).toBe('def entity foo {}');
  });

  it('keys do not start with /', async () => {
    const input = {
      files: [
        makeFile('artikl.ttrm', 'project/artikl.ttrm', 'def entity artikl {}'),
      ],
    } as unknown as HTMLInputElement & { files: FileList };

    const result = await loadProjectViaUpload(input);
    const keys = Array.from(result.files.keys());
    expect(keys.every(k => !k.startsWith('/'))).toBe(true);
  });
});

describe('loadProjectViaFileSystemAccessAPI', () => {
  it('walks nested project files and retains the selected handle for a later save', async () => {
    const dir = (name: string, children: unknown[]) => ({ name, kind: 'directory', async *entries() { for (const child of children) yield [(child as { name: string }).name, child]; } });
    const file = (name: string, text: string) => ({ name, kind: 'file', getFile: async () => ({ text: async () => text }) });
    const handle = dir('world', [file('modeler.toml', '[bindings]\nrequired = true'), dir('model', [
      dir('er', [file('items.ttrm', 'model er')]),
      dir('layouts', [file('sales.ttrg', 'graph sales {}'), file('sales.ttrl', 'layout {}')]),
      file('photo.png', 'no'),
    ]), dir('.git', [file('config.toml', 'no')]), dir('node_modules', [file('package.toml', 'no')])]);
    Object.defineProperty(window, 'showDirectoryPicker', { value: vi.fn().mockResolvedValue(handle), configurable: true });
    try {
      const { loadProjectViaFileSystemAccessAPI } = await import('../file-system');
      const project = await loadProjectViaFileSystemAccessAPI();
      expect(project?.directoryHandle).toBe(handle);
      expect([...project!.files.keys()]).toEqual(['modeler.toml', 'model/er/items.ttrm', 'model/layouts/sales.ttrg', 'model/layouts/sales.ttrl']);
    } finally {
      delete (window as unknown as Record<string, unknown>).showDirectoryPicker;
    }
  });

  it('returns null when showDirectoryPicker is undefined', async () => {
    const { loadProjectViaFileSystemAccessAPI } = await import('../file-system');
    const result = await loadProjectViaFileSystemAccessAPI();
    expect(result).toBeNull();
  });
});
