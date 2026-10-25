// SPDX-License-Identifier: Apache-2.0
/** Match the raw document identity used by the initial file-map load. */
export function uriFor(root: string, path: string): string { return root.replace(/\/$/, '') + '/' + path; }
