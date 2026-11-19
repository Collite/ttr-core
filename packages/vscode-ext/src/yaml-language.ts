// SPDX-License-Identifier: Apache-2.0
import * as path from 'path';

/** No filename association: a nearest marker or an explicit dialect header opts in. */
export async function isTtrYaml(
  file: string,
  text: string,
  workspaceRoot: string,
  read: (file: string) => Promise<string | undefined>
): Promise<boolean> {
  if (!/\.ya?ml$/i.test(file) || /(?:^|[/\\])(?:ttr|ttr-profile)\.ya?ml$/.test(file)) return false;
  if (/^ttr:\s*(?:\{[^\n]*\bprofile\s*:|\r?\n(?:[ \t]+[^\n]*\r?\n)*?[ \t]+profile\s*:)/m.test(text))
    return true;
  let dir = path.dirname(file);
  while (dir === workspaceRoot || dir.startsWith(workspaceRoot + path.sep)) {
    const marker = await read(path.join(dir, 'ttr.yaml'));
    // The nearest marker is authoritative even when it does not declare a profile.
    if (marker !== undefined) return /^profile\s*:/m.test(marker);
    if (dir === workspaceRoot) break;
    dir = path.dirname(dir);
  }
  return false;
}
