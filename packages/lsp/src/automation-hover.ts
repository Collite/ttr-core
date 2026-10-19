// SPDX-License-Identifier: Apache-2.0
import type { AutomationDef, AutoValue, Definition } from '@tatrman/parser';

/**
 * Grammar 0.16 — the hover body of an automation def (AU-core-P0 S0.2 T4): a parameter shows its
 * type, value and label; an action its columns (name: type) and sinks. Other kinds show nothing
 * extra (the generic qname/description/location hover still applies).
 */
export function automationHoverLines(def: Definition): string[] {
  if (def.kind === 'parameter') return parameterLines(def);
  if (def.kind === 'action') return actionLines(def);
  return [];
}

function parameterLines(def: AutomationDef): string[] {
  const lines: string[] = [];
  const type = def.type ? (def.type.kind === 'simple' ? def.type.name : def.type.typeName) : undefined;
  const value = valueOf(def, 'value');
  if (value !== undefined) lines.push(`- **Value:** \`${value}\`${type ? ` *(${type})*` : ''}`);
  else if (type) lines.push(`- **Type:** \`${type}\``);
  const label = def.label?.entries;
  if (label && Object.keys(label).length) {
    lines.push(`- **Label:** ${Object.entries(label).map(([lang, text]) => `${lang}: ${text}`).join(' · ')}`);
  }
  return lines;
}

function actionLines(def: AutomationDef): string[] {
  const lines: string[] = [];
  const columns = (def.columns ?? []).map((c) => {
    const t = c.type ? (c.type.kind === 'simple' ? c.type.name : c.type.typeName) : '?';
    return `\`${c.name}: ${t}${c.optional ? '?' : ''}\``;
  });
  if (columns.length) lines.push(`- **Columns:** ${columns.join(', ')}`);
  const sinks = lastEntry(def, 'sinks');
  if (sinks?.kind === 'autoObject') {
    lines.push(`- **Sinks:** ${sinks.entries.map((e) => `${e.key} → ${render(e.value)}`).join(' · ')}`);
  }
  return lines;
}

function valueOf(def: AutomationDef, key: string): string | undefined {
  const v = lastEntry(def, key);
  return v ? render(v) : undefined;
}

/** The last value written under [key] — the rule the validator, the dumps and the metadata use. */
function lastEntry(def: AutomationDef, key: string): AutoValue | undefined {
  for (let i = def.entries.length - 1; i >= 0; i--) if (def.entries[i].key === key) return def.entries[i].value;
  return undefined;
}

function render(v: AutoValue): string {
  switch (v.kind) {
    case 'string':
    case 'tripleString':
      return v.value;
    case 'number':
    case 'bool':
      return String(v.value);
    case 'null':
      return 'null';
    case 'id':
      return v.path;
    case 'duration':
      return `${v.amount} ${v.unit}`;
    case 'tagged':
      return `${v.tag} "${v.value}"`;
    case 'call':
      return `${v.name} { … }`;
    case 'star':
      return '*';
    case 'autoObject':
      return `{ ${v.entries.map((e) => `${e.key}: ${render(e.value)}`).join(', ')} }`;
    case 'autoList':
      return `[${v.items.map(render).join(', ')}]`;
  }
}
