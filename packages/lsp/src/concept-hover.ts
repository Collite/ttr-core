// SPDX-License-Identifier: Apache-2.0
import type { CnConcept, CnHygieneReport } from '@tatrman/semantics';

/**
 * Grammar 0.17 — the hover body of a concept (CN-P2 S2.7 T4): its stereotype and label, then its hygiene row
 * (`@tatrman/semantics` `cnHygieneReport`, cn contracts §7): the grounding, and the grade with where the concept
 * lands on E-R — directly, through the hop, or inherited from its bearer — or the md/hop slug saying why it does not.
 */
export function conceptHoverLines(c: CnConcept | undefined, report: CnHygieneReport, key: string): string[] {
  if (!c) return [];
  const lines = [`- **Stereotype:** ${c.stereotype}`];
  const label = c.properties.find((s) => s.property.kind === 'label')?.property;
  if (label?.kind === 'label' && Object.keys(label.value.entries).length > 0) {
    lines.push(`- **Label:** ${Object.entries(label.value.entries).map(([lang, text]) => `${lang}: ${text}`).join(' · ')}`);
  }
  const pkg = key.slice(0, Math.max(0, key.indexOf('.cn.concept.')));
  const row = report.concepts.find((r) => r.package === pkg && r.concept === `cn.${c.name}`);
  if (!row) return lines;
  if (row.grounding) {
    lines.push(`- **Grounding:** \`${row.grounding.target ?? 'unresolved'}\` *(${row.grounding.binding})*`);
  }
  let landing = '';
  if (row.landsOn) {
    const how = row.landingFrom ? ` (inherited from \`${row.landingFrom}\`)` : row.hop ? ' through the hop' : '';
    landing = ` — lands on \`${row.landsOn}\`${how}`;
  } else if (row.hop?.slug) {
    landing = ` — \`${row.hop.slug}\``;
  }
  lines.push(`- **Grade:** ${row.grade}${landing}`);
  return lines;
}
