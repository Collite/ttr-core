// SPDX-License-Identifier: Apache-2.0
import type { FkDef, PropertyValue } from '@tatrman/parser';
import { ModelIndex, type IndexedNode } from './model-index.js';
import { Refusal } from './refusals.js';

function paths(value: PropertyValue | undefined): string[] {
  return value?.kind === 'id' ? [value.path] : value?.kind === 'list' ? value.items.flatMap(v => paths(v)) : [];
}

/** A column argument names its declared FK; binding.fk always points to an FK definition. */
export function foreignKey(index: ModelIndex, ref: string, from: IndexedNode, to: IndexedNode): IndexedNode {
  let key = index.lookup(ref, ['fk', 'column']);
  if (!key) throw new Refusal('source-unknown', `Foreign key ${ref} is not loaded.`);
  if (key.def.kind === 'column') {
    const column = key;
    const candidates = index.symbols.all().filter(s => s.kind === 'fk').map(s => index.node(s)).filter(n => paths((n.def as FkDef).from).some(path => index.lookup(path, ['column'], n.doc)?.symbol.qname === column.symbol.qname));
    if (candidates.length !== 1) throw new Refusal('source-unknown', `${ref} must belong to exactly one declared foreign key.`);
    key = candidates[0];
  }
  const parent = (entity: IndexedNode) => {
    const binding = index.binding(entity);
    return binding && index.lookup(binding.path, ['table', 'view'], entity.doc)?.symbol.qname;
  };
  const fromTable = parent(from), toTable = parent(to);
  const fk = key.def as FkDef;
  const tables = (value: PropertyValue | undefined) => paths(value).map(path => index.lookup(path, ['column'], key!.doc)?.symbol.parent);
  const a = tables(fk.from), b = tables(fk.to);
  // Cardinality/direction can be authored either way; physical endpoints must still be the two bound tables.
  if (fromTable && toTable) {
    const forward = a.every(t => t === fromTable) && b.every(t => t === toTable);
    const reverse = a.every(t => t === toTable) && b.every(t => t === fromTable);
    if (!a.length || !b.length || !(forward || reverse)) throw new Refusal('source-unknown', 'The FK must connect the two entities’ bound tables.');
  }
  return key;
}
