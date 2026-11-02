// SPDX-License-Identifier: Apache-2.0
// Minimal, dependency-free unified diff (for migrator `--dry-run` output).
//
// LCS-based line diff rendered as standard unified-diff hunks with 3 lines of
// context. Good enough for a human to review a codemod's changes; not intended
// to be byte-compatible with GNU diff in every edge case.

interface Op {
  tag: 'eq' | 'del' | 'add';
  line: string;
  /** 1-based line number in the BEFORE text (0 for pure additions). */
  a: number;
  /** 1-based line number in the AFTER text (0 for pure deletions). */
  b: number;
}

/** Longest-common-subsequence line ops between two files, line-numbered. */
function diffLines(a: string[], b: string[]): Op[] {
  const n = a.length;
  const m = b.length;
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const ops: Op[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      ops.push({ tag: 'eq', line: a[i], a: i + 1, b: j + 1 });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      ops.push({ tag: 'del', line: a[i], a: i + 1, b: 0 });
      i++;
    } else {
      ops.push({ tag: 'add', line: b[j], a: 0, b: j + 1 });
      j++;
    }
  }
  for (; i < n; i++) ops.push({ tag: 'del', line: a[i], a: i + 1, b: 0 });
  for (; j < m; j++) ops.push({ tag: 'add', line: b[j], a: 0, b: j + 1 });
  return ops;
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
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--;
    endB--;
  }
  const n = endA - start;
  const m = endB - start;
  if (n * m > 4_000_000) {
    return [
      `--- a/${path}`,
      `+++ b/${path}`,
      `@@ -${n ? start + 1 : start},${n} +${m ? start + 1 : start},${m} @@`,
      ...a.slice(start, endA).map((line) => `-${line}`),
      ...b.slice(start, endB).map((line) => `+${line}`),
    ].join('\n');
  }
  // Preserve established LCS tie-breaking for small diffs; trim large unchanged regions.
  const ops =
    a.length * b.length <= 4_000_000
      ? diffLines(a, b)
      : [
          ...a.slice(0, start).map((line, i): Op => ({ tag: 'eq', line, a: i + 1, b: i + 1 })),
          ...diffLines(a.slice(start, endA), b.slice(start, endB)).map((op) => ({
            ...op,
            a: op.a ? op.a + start : 0,
            b: op.b ? op.b + start : 0,
          })),
          ...a
            .slice(endA)
            .map((line, i): Op => ({ tag: 'eq', line, a: endA + i + 1, b: endB + i + 1 })),
        ];
  const changed = ops.map((o) => o.tag !== 'eq');

  // Mark which ops to include: every change plus `context` equal lines around it.
  const include = new Array<boolean>(ops.length).fill(false);
  for (let k = 0; k < ops.length; k++) {
    if (!changed[k]) continue;
    for (let d = -context; d <= context; d++) {
      const idx = k + d;
      if (idx >= 0 && idx < ops.length) include[idx] = true;
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
    const hunk = ops.slice(k, end);

    const firstA = hunk.find((o) => o.a > 0)?.a ?? 0;
    const firstB = hunk.find((o) => o.b > 0)?.b ?? 0;
    const aCount = hunk.filter((o) => o.tag !== 'add').length;
    const bCount = hunk.filter((o) => o.tag !== 'del').length;
    out.push(`@@ -${firstA},${aCount} +${firstB},${bCount} @@`);
    for (const op of hunk) {
      const sigil = op.tag === 'eq' ? ' ' : op.tag === 'del' ? '-' : '+';
      out.push(`${sigil}${op.line}`);
    }
    k = end;
  }
  return out.join('\n');
}
