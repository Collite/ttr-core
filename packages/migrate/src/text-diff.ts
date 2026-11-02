// SPDX-License-Identifier: Apache-2.0
// Minimal, dependency-free unified diff (for migrator `--dry-run` output).
//
// LCS-based line diff rendered as standard unified-diff hunks with 3 lines of
// context. Good enough for a human to review a codemod's changes; not intended
// to be byte-compatible with GNU diff in every edge case.

// 4 Mi cells bounds the table while retaining the 2,002-line compatibility vector.
const MAX_LCS_CELLS = 4_194_304;

interface Op {
  tag: 'eq' | 'del' | 'add';
  line: string;
  /** 1-based line number in the BEFORE text (0 for pure additions). */
  a: number;
  /** 1-based line number in the AFTER text (0 for pure deletions). */
  b: number;
}

/** Longest-common-subsequence line ops between two files, line-numbered. */
function diffLines(a: string[], b: string[], offset: number, ops: Op[]): void {
  const n = a.length;
  const m = b.length;
  const width = m + 1;
  const lcs = new Uint32Array((n + 1) * width);
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i * width + j] =
        a[i] === b[j]
          ? lcs[(i + 1) * width + j + 1] + 1
          : Math.max(lcs[(i + 1) * width + j], lcs[i * width + j + 1]);
    }
  }
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      ops.push({ tag: 'eq', line: a[i], a: offset + i + 1, b: offset + j + 1 });
      i++;
      j++;
    } else if (lcs[(i + 1) * width + j] >= lcs[i * width + j + 1]) {
      ops.push({ tag: 'del', line: a[i], a: offset + i + 1, b: 0 });
      i++;
    } else {
      ops.push({ tag: 'add', line: b[j], a: 0, b: offset + j + 1 });
      j++;
    }
  }
  for (; i < n; i++) ops.push({ tag: 'del', line: a[i], a: offset + i + 1, b: 0 });
  for (; j < m; j++) ops.push({ tag: 'add', line: b[j], a: 0, b: offset + j + 1 });
}

/**
 * A unified diff for one file. Returns '' when the texts are identical. Header
 * lines use `--- a/<path>` / `+++ b/<path>`; hunks carry up to `context` lines of
 * surrounding equal lines.
 */
export function unifiedDiff(path: string, before: string, after: string, context = 3): string {
  if (before === after) return '';
  const a = before.split('\n');
  const b = after.split('\n');
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  // Tier 1 keeps the suffix in the LCS table to preserve base tie-breaking.
  // Only above that budget may tiers 2/3 trim a common suffix.
  let suffix = 0;
  if ((a.length - start) * (b.length - start) > MAX_LCS_CELLS) {
    while (
      suffix < a.length - start &&
      suffix < b.length - start &&
      a[a.length - suffix - 1] === b[b.length - suffix - 1]
    )
      suffix++;
  }
  const n = a.length - start - suffix;
  const m = b.length - start - suffix;
  if (n * m > MAX_LCS_CELLS) {
    return [
      `--- a/${path}`,
      `+++ b/${path}`,
      `@@ -${n ? start + 1 : start},${n} +${m ? start + 1 : start},${m} @@`,
      ...a.slice(start, a.length - suffix).map((line) => `-${line}`),
      ...b.slice(start, b.length - suffix).map((line) => `+${line}`),
    ].join('\n');
  }
  // Equal leading lines are always taken first by the original LCS walk.
  const ops: Op[] = [];
  for (let i = 0; i < start; i++) ops.push({ tag: 'eq', line: a[i], a: i + 1, b: i + 1 });
  diffLines(a.slice(start, start + n), b.slice(start, start + m), start, ops);
  for (let i = 0; i < suffix; i++) {
    ops.push({
      tag: 'eq',
      line: a[a.length - suffix + i],
      a: a.length - suffix + i + 1,
      b: b.length - suffix + i + 1,
    });
  }

  // Mark which ops to include: every change plus `context` equal lines around it.
  const include = new Uint8Array(ops.length);
  for (let k = 0; k < ops.length; k++) {
    if (ops[k].tag === 'eq') continue;
    for (let d = -context; d <= context; d++) {
      const idx = k + d;
      if (idx >= 0 && idx < ops.length) include[idx] = 1;
    }
  }

  const out: string[] = [`--- a/${path}`, `+++ b/${path}`];
  let k = 0;
  while (k < ops.length) {
    if (!include[k]) {
      k++;
      continue;
    }
    let end = k;
    while (end < ops.length && include[end]) end++;
    let firstA = 0;
    let firstB = 0;
    let aCount = 0;
    let bCount = 0;
    for (let i = k; i < end; i++) {
      const op = ops[i];
      firstA ||= op.a;
      firstB ||= op.b;
      if (op.tag !== 'add') aCount++;
      if (op.tag !== 'del') bCount++;
    }
    out.push(`@@ -${firstA},${aCount} +${firstB},${bCount} @@`);
    for (let i = k; i < end; i++) {
      const op = ops[i];
      const sigil = op.tag === 'eq' ? ' ' : op.tag === 'del' ? '-' : '+';
      out.push(`${sigil}${op.line}`);
    }
    k = end;
  }
  return out.join('\n');
}
