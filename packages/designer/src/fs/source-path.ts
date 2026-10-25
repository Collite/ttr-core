// SPDX-License-Identifier: Apache-2.0
/** Regular, repo-rooted TTR-M source only; never a dependency or URL-encoded path. */
export function isSourcePath(path: string): boolean {
  return (
    /^model\/.+\.ttrm$/.test(path) &&
    !/[\\\u0000-\u001f?#%]/.test(path) &&
    path.split('/').every((part) => part !== '' && part !== '.' && part !== '..')
  );
}
