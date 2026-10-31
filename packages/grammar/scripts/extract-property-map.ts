// SPDX-License-Identifier: Apache-2.0
// Generates packages/grammar/src/generated/property-map.ts from TTR.g4.
// Run via `pnpm --filter @tatrman/grammar prebuild` (tsx). Paths are resolved
// from this script's own location so it works regardless of cwd.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const grammarPath = resolve(scriptDir, '../src/TTR.g4');
const outputPath = resolve(scriptDir, '../src/generated/property-map.ts');
const versionOutputPath = resolve(scriptDir, '../src/generated/version.ts');

const grammar = readFileSync(grammarPath, 'utf-8');

// Extract the canonical grammar version from the `@grammar-version:` marker
// in TTR.g4's header. This keeps the .g4 file as the single source of truth —
// the marker survives sync-to-ai-platform.sh so the Kotlin side sees the same
// version string when it regenerates from the vendored copy.
const versionMatch = grammar.match(/^\/\/\s*@grammar-version:\s*(\d+\.\d+)\s*$/m);
if (!versionMatch) {
  throw new Error(
    'TTR.g4 is missing the `// @grammar-version: X.Y` marker. ' +
      'Add it to the header comment before regenerating.',
  );
}
const grammarVersion = versionMatch[1];

// A `<x>Property` (or `<x>Property_`) sub-rule maps to a property name by
// stripping the suffixes, with two grammar-rule names that don't match their
// property name 1:1.
const RULE_NAME_OVERRIDES: Record<string, string> = {
  searchBlock: 'search',
  cnFrom: 'from',
  cnUntil: 'until',
  cnPer: 'per',
  semanticsBlock: 'semantics',
  lexiconBlock: 'lexicon',
  columnNamesList: 'columnNames',
  // 0.17 — the cn rules whose name is not the key they carry. `cnRefProperty` is the stereotype-ref
  // rule (`kind|role|quality|event|phase|concept:`); on a role — the only advertised kind carrying it —
  // the one ref a role takes is its defining `event:`.
  cnRef: 'event',
  conceptSugar: 'concept',
  conceptMembers: 'members',
  valueSpace: 'value_space',
  sensePriority: 'sense_priority',
};

function ruleToPropertyName(rule: string): string {
  const base = rule.replace(/_$/, '').replace(/Property$/, '');
  return RULE_NAME_OVERRIDES[base] ?? base;
}

// Ordered property names referenced by a `<ruleName> : aProperty | bProperty ;`
// rule. Anchored at line start so e.g. `entityProperty` does not match the tail
// of `er2dbEntityProperty`. Handles single- and multi-line rule bodies.
function extractProps(ruleName: string): string[] {
  const m = grammar.match(new RegExp(`^${ruleName}\\s*:\\s*([^;]+);`, 'm'));
  if (!m) return [];
  const subRules = m[1].match(/\w+Property_?/g) ?? [];
  return subRules.map(ruleToPropertyName);
}

const typeMap: Record<string, string> = {
  indexSecurity: 'identifier',
  skipSecurity: 'boolean',
  // 0.13 (NLS-P10 / ⚑GXP-D7) — both forms are legal: `description: "…"` and
  // `description: { en: "…", cs: "…" }`. The property map is what the LSP and the
  // Designer show an author, so it advertises the union, not just the older half.
  description: 'localized string or string',
  tags: 'list of strings',
  version: 'string',
  primaryKey: 'list of strings',
  columns: 'list of column definitions',
  indices: 'list of index definitions',
  constraints: 'list of constraint definitions',
  definitionSql: 'string',
  type: 'data type',
  optional: 'boolean',
  isKey: 'boolean',
  // 0.12 (RV-32): the boolean is optional (bare `searchable` = included) and an
  // optional `method: EXACT | TYPOS(n) | TOKENS` attribute rides it.
  searchable: 'inclusion marker (optional boolean, optional method)',
  indexed: 'boolean',
  from: 'reference',
  until: 'reference',
  per: 'reference',
  to: 'reference',
  parameters: 'list of parameter definitions',
  resultColumns: 'list of column definitions',
  labelPlural: 'string',
  nameAttribute: 'reference',
  name: 'identifier',
  codeAttribute: 'reference',
  aliases: 'list of strings',
  attributes: 'list of attribute definitions',
  cardinality: 'object',
  join: 'list',
  entity: 'reference',
  attribute: 'reference',
  relation: 'reference',
  fk: 'reference',
  target: 'object',
  whereFilter: 'object',
  language: 'language',
  sourceText: 'string',
  length: 'number',
  precision: 'number',
  label: 'localized string',
  paramLabel: 'string',
  direction: 'identifier',
  displayLabel: 'localized string',
  roles: 'list of identifiers',
  valueLabels: 'value labels',
  role: 'reference',
  keywords: 'localized string list',
  patterns: 'list of strings',
  descriptions: 'localized string list',
  examples: 'list of strings',
  fuzzy: 'boolean',
  search: 'search block',
  semantics: 'semantics block',
  lexicon: 'lexicon block',
  binding: 'binding block or reference',
  // 0.16 — the automation body's typed alternatives (the free-form entries are not advertised).
  transition: 'state transition',
  set: 'assignment',
  // 0.16 — attribute lifecycle (D-67).
  states: 'list of identifiers',
  // 0.16 — the automation role's typed properties (review-114 M5).
  resolve: 'role membership',
  delivery: 'object',
  members: 'list of strings',
  transitions: 'state transition map',
  // 0.17 — the conceptual model (cn contracts §1).
  event: 'reference',
  grounds: 'reference',
  concept: 'concept block',
  involves: 'list of participants',
  period: 'period',
  extent: 'identifier',
  basis: 'identifier',
  definition: 'expression block',
  value_space: 'reference',
  default: 'boolean',
  sense_priority: 'number',
};

const allKinds = [
  'project',
  'table',
  'view',
  'column',
  'index',
  'constraint',
  'fk',
  'procedure',
  'entity',
  'attribute',
  'relation',
  'er2dbEntity',
  'er2dbAttribute',
  'er2dbRelation',
  'query',
  'role',
  'er2cncRole',
  // 0.14 — named row schema (`def schema`): description / tags / columns.
  'schema',
  // 0.16 — the automation model: six kinds sharing ONE body rule (`autoProperty`).
  'parameter',
  'action',
  'service',
  'approval',
  'program',
  'recipe',
  // 0.17 — the conceptual model: the concept defs (every stereotype, a `model cn` role included) share
  // `cnProperty`, the ten cn bindings `cnBindingProperty`; keyed by the AST kind.
  'concept',
  'conceptBinding',
];

// XM: include every grammar definition body, including MD, areas and world plumbing.
// The advertised subset used to omit those families, so it could not guard export kind drift.
for (const match of grammar.matchAll(/^(\w+)Def\s*:/gm)) {
  const kind = match[1];
  if (kind === 'lexiconEntry') {
    allKinds.push('term', 'pattern', 'example');
  } else if (!allKinds.includes(kind)) {
    allKinds.push(kind);
  }
}

// 0.16 — kinds whose property rule is not `<kind>Property` (the automation kinds share `autoProperty`).
const KIND_RULE: Record<string, string> = {
  parameter: 'autoProperty',
  action: 'autoProperty',
  service: 'autoProperty',
  approval: 'autoProperty',
  program: 'autoProperty',
  recipe: 'autoProperty',
  term: 'lexiconEntryProperty',
  pattern: 'lexiconEntryProperty',
  example: 'lexiconEntryProperty',
  engine: 'enginePartProperty',
  executor: 'enginePartProperty',
  concept: 'cnProperty',
  conceptBinding: 'cnBindingProperty',
};

// 0.17 — where a kind reads one rule differently from the name/type the shared maps give it: a cn
// binding's stereotype-ref rule carries all six keys (`kind: cn.Item`, `phase: cn.PlacedOrder`, …,
// `concept:` on cn2db_concept), and a concept's `members:` is an expression block, not the automation
// role's list.
const STEREOTYPE_REFS = ['kind', 'role', 'quality', 'event', 'phase', 'concept'];
const KIND_OVERRIDES: Record<string, Record<string, { name: string; type: string }[]>> = {
  concept: { members: [{ name: 'members', type: 'expression block' }] },
  conceptBinding: { event: STEREOTYPE_REFS.map((name) => ({ name, type: 'reference' })) },
};

const searchSubProperties = extractProps('searchSubProperty');

let output = `// AUTO-GENERATED by packages/grammar/scripts/extract-property-map.ts
// Do not edit manually — regenerate with: pnpm --filter @tatrman/grammar prebuild

export type DefinitionKind =
`;

for (let i = 0; i < allKinds.length; i++) {
  output += `  | '${allKinds[i]}'`;
}
output += ';\n\n';

output += 'export interface PropertyInfo {\n  name: string;\n  type: string;\n}\n\n';

const propertiesByKind = Object.fromEntries(allKinds.map((kind) => [kind,
  extractProps(KIND_RULE[kind] ?? `${kind}Property`).flatMap(
    (p) => KIND_OVERRIDES[kind]?.[p] ?? [{ name: p, type: typeMap[p] ?? 'unknown' }],
  ),
]));

output += 'export const PROPERTY_MAP: Record<DefinitionKind, PropertyInfo[]> = {\n';
for (const kind of allKinds) {
  const props = propertiesByKind[kind];
  const infos = props.map((p) => `{ name: '${p.name}', type: '${p.type}' }`);
  output += `  ${kind}: [${infos.join(', ')}],\n`;
}
output += '};\n\n';

output += `export const SEARCH_SUB_PROPERTIES: string[] = [${searchSubProperties.map((p) => `'${p}'`).join(', ')}];\n`;

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, output);
console.log(`Generated property-map.ts with ${allKinds.length} kinds`);

const versionOutput = `// AUTO-GENERATED by packages/grammar/scripts/extract-property-map.ts
// Do not edit manually — bump the \`@grammar-version\` marker in TTR.g4 and
// regenerate with: pnpm --filter @tatrman/grammar prebuild

export const TTR_GRAMMAR_VERSION = '${grammarVersion}' as const;
`;

writeFileSync(versionOutputPath, versionOutput);
console.log(`Generated version.ts (TTR grammar version ${grammarVersion})`);

// Optional C-7 resource regeneration for a full source checkout. The default
// grammar build stays a leaf: Designer's Docker context contains no Kotlin tree.
// Run `pnpm --filter @tatrman/grammar prebuild --export-order` when updating C-7.
if (process.argv.includes('--export-order')) {
  const exportOrderPath = resolve(scriptDir, '../../kotlin/ttr-metadata/src/main/resources/export/property-map.json');
  const exportOrder = Object.fromEntries(allKinds.map((kind) => [kind,
    propertiesByKind[kind].map((p) => p.name),
  ]));
  mkdirSync(dirname(exportOrderPath), { recursive: true });
  writeFileSync(exportOrderPath, JSON.stringify(exportOrder, null, 2) + '\n');
}
