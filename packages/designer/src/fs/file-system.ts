// SPDX-License-Identifier: Apache-2.0
export interface ProjectFiles {
  rootName: string;
  files: Map<string, string>;
}

export async function loadProjectViaUpload(input: HTMLInputElement): Promise<ProjectFiles> {
  const files = new Map<string, string>();
  const entries = input.files;
  if (!entries) return { rootName: 'project', files };

  let rootName = 'project';
  for (const file of Array.from(entries)) {
    if (!file.name.endsWith('.ttrm') && !file.name.endsWith('.ttrg') && !file.name.endsWith('.toml')) {
      continue;
    }
    const content = await file.text();
    const name = file.webkitRelativePath?.split('/')[0] ?? file.name;
    if (!rootName || rootName === 'project') {
      rootName = name;
    }
    const relativePath = file.webkitRelativePath
      ? file.webkitRelativePath.split('/').slice(1).join('/')
      : file.name;
    files.set(relativePath, content);
  }

  return { rootName, files };
}

export async function loadProjectViaFileSystemAccessAPI(): Promise<ProjectFiles | null> {
  if (!('showDirectoryPicker' in window)) return null;
  try {
    const dirHandle = await (window as unknown as { showDirectoryPicker: () => Promise<FileSystemDirectoryHandle> }).showDirectoryPicker();
    const files = new Map<string, string>();
    for await (const [name, handle] of dirHandle.entries()) {
      if (handle.kind === 'file') {
        if (!name.endsWith('.ttrm') && !name.endsWith('.ttrg') && !name.endsWith('.toml')) continue;
        const fileHandle = handle as FileSystemFileHandle;
        const file = await fileHandle.getFile();
        const content = await file.text();
        files.set(name, content);
      }
    }
    return { rootName: dirHandle.name, files };
  } catch {
    return null;
  }
}

/**
 * Ask the user for files (Open layout… — VL V-31): a transient `<input type=file>`. Resolves with the
 * picked files, or `[]` when the dialog is cancelled.
 */
export function pickFiles(accept: string, multiple: boolean): Promise<File[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.multiple = multiple;
    input.addEventListener('change', () => resolve(input.files ? Array.from(input.files) : []));
    input.addEventListener('cancel', () => resolve([]));
    input.click();
  });
}

/** Download `content` as `filename` — `text/plain` unless a MIME type is named (`application/yaml`, VL V-9). */
export async function downloadFile(filename: string, content: string, mimeType = 'text/plain'): Promise<void> {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}