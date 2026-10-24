// SPDX-License-Identifier: Apache-2.0
export interface ChangedFile { path: string; text: string | null }
/** Whole file diff against the fetched base; both maps use repo-rooted paths. */
export function changedFiles(base: ReadonlyMap<string, string>, current: ReadonlyMap<string, string>): ChangedFile[] {
  return [...new Set([...base.keys(), ...current.keys()])].sort()
    .filter(path => base.get(path) !== current.get(path))
    .map(path => ({ path, text: current.get(path) ?? null }));
}
