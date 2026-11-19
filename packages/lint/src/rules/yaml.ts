// SPDX-License-Identifier: Apache-2.0
import type { Rule, LintCode } from '../rule.js';

const codes: [LintCode, string][] = [
  ['other', 'Profile value-op warning (Kotlin lint’s generic warning code)'],
  [
    'ys/profile-invalid',
    'Invalid profile syntax, schema, operands or safety policy; an invalid model.ignore',
  ],
  ['ys/profile-grammar-mismatch', 'A profile or declaration excludes the running grammar'],
  [
    'ys/profile-not-found',
    'A profile, overlay or ttr.yaml cannot be resolved or read within the root',
  ],
  ['ys/profile-cycle', 'The profile inheritance chain contains a cycle'],
  [
    'ys/unknown-key',
    'An authored foreign key was neither consumed nor ignored (profile `unknown: error`, the default; under `unknown: ignore` it is a `not in this profile` warning)',
  ],
  ['ys/unknown-kind', 'A record discriminator has no kind mapping'],
  ['ys/profile-conflict', 'Two operations or records own the same canonical target'],
  [
    'ys/value-unmapped',
    'A selected value (a package key, a value map) has no mapping and no default',
  ],
  [
    'ys/package-invalid',
    'A `package: {verbatim: true}` path is no TTR package path, or there is no string at `at`',
  ],
  [
    'ys/key-missing',
    'An operation with `required: warn` selects nothing at `at` on a record (a warning; the run goes on)',
  ],
  [
    'ys/profile-duplicate',
    'Two catalogue profiles declare one identity: left out of the catalog (a warning); usage for a run naming it',
  ],
  ['ys/record-id-missing', 'A record has no string ID at kinds.id or the default /$key'],
  ['ys/canonical-invalid', 'A file read as canonical YAML is not valid canonical YAML'],
  ['ys/each-not-a-list', "An each op's `at` selects something other than a list"],
  ['ys/split-not-a-string', 'A values op with `split` selects something other than a string'],
  ['xm/link-unresolved', 'A link names nothing in the run or its sidecars'],
  ['xm/link-ambiguous', 'A link names more than one target'],
  [
    'xm/link-kind-missing',
    "A link's kind operand names a kind the reference has no record of (it names only other kinds)",
  ],
  [
    'xm/join-pair-shape',
    'A join-pairs value is not exactly `a.x = b.y [AND c.z = d.w \u2026]` (two-part references, equality only)',
  ],
  [
    'xm/overlay-orphan',
    'An overlay entry names no emitted definition (its suggestion may carry a rename)',
  ],
  ['ys/yaml-syntax', 'Invalid YAML syntax or safety policy'],
  ['ys/schema', 'Canonical YAML schema violation'],
  ['ys/value', 'Canonical YAML value cannot be represented'],
];

export const YAML_RULES: Rule[] = codes.map(([code, docs]) => ({
  id: code,
  code,
  docs,
  scope: 'input',
  category: 'correctness',
  defaultSeverity:
    code === 'other' || code === 'ys/key-missing' || code === 'ys/profile-duplicate' ? 'warning' : 'error',
  check(ctx) {
    if (ctx.scope !== 'input') return;
    for (const d of ctx.diagnostics) if (d.code === code) ctx.report(d);
  },
}));
