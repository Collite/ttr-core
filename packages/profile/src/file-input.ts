// SPDX-License-Identifier: Apache-2.0
import { closeSync, fstatSync, openSync, readSync } from 'node:fs';
import { ProfileException } from './loading.js';

export const MAX_PROFILE_FILE_BYTES = 24_000_000;

/** The start of the first malformed sequence, including incomplete trailing sequences. */
function malformedUtf8(bytes: Uint8Array): number | undefined {
  for (let i = 0; i < bytes.length; ) {
    const first = bytes[i];
    if (first < 0x80) {
      i++;
      continue;
    }
    const size =
      first >= 0xc2 && first <= 0xdf
        ? 2
        : first >= 0xe0 && first <= 0xef
          ? 3
          : first >= 0xf0 && first <= 0xf4
            ? 4
            : 0;
    if (!size || i + size > bytes.length) return i;
    for (let j = 1; j < size; j++) {
      const next = bytes[i + j];
      if (
        next < 0x80 ||
        next > 0xbf ||
        (j === 1 &&
          ((first === 0xe0 && next < 0xa0) ||
            (first === 0xed && next > 0x9f) ||
            (first === 0xf0 && next < 0x90) ||
            (first === 0xf4 && next > 0x8f)))
      )
        return i;
    }
    i += size;
  }
  return undefined;
}
function invalid(file: string, prefix: string, detail: string): never {
  let line = 1,
    column = 1;
  for (const character of prefix.replace(/\r\n?/g, '\n')) {
    // YAML 1.2 StreamReader marks skip BOMs; only CR/LF are line breaks.
    if (character === '\n') {
      line++;
      column = 1;
    } else if (character !== '\ufeff') column++;
  }
  throw new ProfileException(
    'ys/profile-invalid',
    { file, line, column, endLine: line, endColumn: column, path: '' },
    detail
  );
}

/** Bound every read before allocation/decoding; leave I/O error classification to the owning reader. */
export function readProfileFile(path: string, label = path): string {
  const fd = openSync(path, 'r');
  const chunks: Buffer[] = [];
  let total = 0;
  try {
    if (fstatSync(fd).size > MAX_PROFILE_FILE_BYTES)
      invalid(label, '', `Profile exceeds ${MAX_PROFILE_FILE_BYTES} bytes`);
    for (;;) {
      const chunk = Buffer.allocUnsafe(Math.min(64 * 1024, MAX_PROFILE_FILE_BYTES + 1 - total));
      const count = readSync(fd, chunk, 0, chunk.length, null);
      if (!count) break;
      total += count;
      if (total > MAX_PROFILE_FILE_BYTES)
        invalid(label, '', `Profile exceeds ${MAX_PROFILE_FILE_BYTES} bytes`);
      chunks.push(chunk.subarray(0, count));
    }
  } finally {
    closeSync(fd);
  }
  const bytes = Buffer.concat(chunks, total);
  const offset = malformedUtf8(bytes);
  // Preserve the decoded source (including a BOM), as the JVM decoder does.
  const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
  if (offset !== undefined)
    invalid(label, decoder.decode(bytes.subarray(0, offset)), 'Malformed UTF-8 in profile');
  return decoder.decode(bytes);
}
