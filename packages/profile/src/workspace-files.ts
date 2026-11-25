// SPDX-License-Identifier: Apache-2.0

export interface WorkspaceEntry {
  name: string;
  isDirectory(): boolean;
  isFile(): boolean;
}

export interface ProfileFiles {
  path: Pick<
    typeof import('node:path'),
    'basename' | 'dirname' | 'isAbsolute' | 'join' | 'relative' | 'resolve' | 'sep'
  >;
  realpath(file: string): string;
  isFile(file: string): boolean;
  exists(file: string): boolean;
  entries(directory: string): WorkspaceEntry[];
  read(file: string, label?: string): string;
}
