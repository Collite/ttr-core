// SPDX-License-Identifier: Apache-2.0
// 0.17 (CN-P2 review-119 M-5, L-7(c)) — where a rename meets an event's participant labels. A label
// (`Store as seller`) or a default label (`involves: [Customer]` reads `customer`) is a symbol of its
// own, `<event>.<label>`, that a binding's `participants:` key refers to; renaming it edits the label,
// never the participant ref (which names the concept the event involves).

import type { ConceptParticipant, ConceptProperty, Definition, Document, SourceLocation } from '@tatrman/parser';
import { conceptSugarOf, nestedDefs, sugarDeclaringRef } from '@tatrman/semantics';

/** A participant a document writes, with the name of the event that involves it. */
export interface WrittenParticipant {
  participant: ConceptParticipant;
  /** The event's name — a cn `def event`'s, or the one a `concept { }` block declares. */
  event: string;
}

/** Every participant a document's events write: a cn `def event`'s `involves:` and every `concept { }` block's. */
export function participantsOf(ast: Document): WrittenParticipant[] {
  const out: WrittenParticipant[] = [];
  const add = (properties: readonly ConceptProperty[], event: string | undefined) => {
    if (!event) return;
    for (const p of properties) {
      if (p.kind === 'involves') for (const participant of p.participants) out.push({ participant, event });
    }
  };
  for (const def of ast.definitions) {
    if (def.kind === 'concept') {
      if (def.stereotype === 'event') add(def.properties, def.name);
      continue;
    }
    for (const host of [def, ...nestedDefs(def)] as Definition[]) {
      const sugar = conceptSugarOf(host);
      const declaring = sugarDeclaringRef(host);
      if (sugar && declaring) add(sugar.properties, declaring.parts[declaring.parts.length - 1]);
    }
  }
  return out;
}

/** The span of a participant's written label (`seller` in `Store as seller`); `undefined` for a default label. */
export function labelSource(p: ConceptParticipant): SourceLocation | undefined {
  if (p.label === undefined) return undefined;
  const s = p.source;
  return { ...s, line: s.endLine, column: s.endColumn - p.label.length, offsetStart: s.offsetEnd - p.label.length };
}
