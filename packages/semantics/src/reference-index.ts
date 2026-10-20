// SPDX-License-Identifier: Apache-2.0
import type { Definition, Document, SourceLocation } from '@tatrman/parser';
import { collectAllReferences } from './references.js';
import { defaultSchemaForKind, keyKindOf } from './default-schema.js';
import { buildCanonicalKey } from './qname.js';
import type { Resolver } from './resolver.js';
import { collectBindingReferences, collectBindingTargetReferences } from './mapping-references.js';

/**
 * The canonical key of a container def that can host references (so the
 * reference index can attribute each ref to its referrer). `schemaCode` is the
 * file's `model` directive code (the model is derived from `def.kind`; the
 * directive only moves a `query` declared under `model er`, LQ L-1),
 * `namespace` the file's `schema` id (db only). Returns
 * undefined for kinds that never enclose references.
 */
export function enclosingQnameOf(def: Definition, schemaCode: string, namespace: string, packageName?: string): string | undefined {
  if (
    def.kind === 'entity' || def.kind === 'table' || def.kind === 'view' || def.kind === 'procedure' ||
    def.kind === 'relation' || def.kind === 'query' || def.kind === 'role' ||
    def.kind === 'er2dbEntity' || def.kind === 'er2dbAttribute' ||
    def.kind === 'er2dbRelation' || def.kind === 'er2cncRole' ||
    def.kind === 'concept' || def.kind === 'conceptBinding'
  ) {
    return buildCanonicalKey({ packageName, schemaId: namespace, kind: keyKindOf(def), parts: [def.name], declaringModel: schemaCode });
  }
  return undefined;
}

export interface ReferenceLocation {
  documentUri: string;
  source: SourceLocation;
  /** The canonical qname this reference resolved to. */
  targetQname: string;
  /** The qname of the def that contains the reference (null when the ref is not inside a def). */
  referrerQname: string | null;
  /**
   * True for references discovered inside an inline `binding:` property. These
   * aren't reachable via `collectReferences`, so navigation features locate them
   * through the index by position rather than the AST walk.
   */
  viaMapping?: boolean;
}

/**
 * Reverse index: for each target qname, the list of reference locations that
 * resolve to it. Built incrementally as documents are upserted.
 */
export class ReferenceIndex {
  private byDocument: Map<string, ReferenceLocation[]> = new Map();
  private byTargetQname: Map<string, ReferenceLocation[]> = new Map();

  upsertDocument(
    uri: string,
    ast: Document,
    schemaCode: string,
    namespace: string,
    resolver: Resolver,
    packageName?: string
  ): void {
    this.removeDocument(uri);
    const locations: ReferenceLocation[] = [];

    for (const { ref, ownerDef } of collectAllReferences(ast)) {
      // When the file has no `schema` directive, `schemaCode` is '' and the
      // effective schema is derived from the referring def's kind.
      const effSchema = schemaCode || defaultSchemaForKind(ownerDef.kind);
      const referrerQname = enclosingQnameOf(ownerDef, effSchema, namespace, packageName) ?? null;
      // The ref itself, not a copy of its path: a cn slot's ref carries its context (`isCnContextRef`).
      const res = resolver.resolveReference(
        ref,
        { schemaCode: effSchema, namespace, enclosingQname: referrerQname ?? undefined, packageName }
      );
      if (!res.resolved) continue;
      const loc: ReferenceLocation = {
        documentUri: uri,
        source: ref.source,
        targetQname: res.symbol.qname,
        referrerQname,
      };
      locations.push(loc);
      const list = this.byTargetQname.get(res.symbol.qname) ?? [];
      list.push(loc);
      this.byTargetQname.set(res.symbol.qname, list);
    }

    // Inline mapping column references resolve against the enclosing entity's
    // target table, not the generic er scope, so they're collected separately;
    // so are binding targets (the table / view / query an entity binds to).
    const pkg = packageName ?? '';
    for (const m of [
      ...collectBindingReferences(ast, resolver, schemaCode, namespace, pkg),
      ...collectBindingTargetReferences(ast, resolver, schemaCode, namespace, pkg),
    ]) {
      const loc: ReferenceLocation = {
        documentUri: uri,
        source: m.ref.source,
        targetQname: m.targetQname,
        referrerQname: m.referrerQname,
        viaMapping: true,
      };
      locations.push(loc);
      const list = this.byTargetQname.get(m.targetQname) ?? [];
      list.push(loc);
      this.byTargetQname.set(m.targetQname, list);
    }

    this.byDocument.set(uri, locations);
  }

  removeDocument(uri: string): void {
    const old = this.byDocument.get(uri);
    if (!old) return;
    for (const loc of old) {
      const list = this.byTargetQname.get(loc.targetQname);
      if (!list) continue;
      const filtered = list.filter((l) => l.documentUri !== uri);
      if (filtered.length === 0) {
        this.byTargetQname.delete(loc.targetQname);
      } else {
        this.byTargetQname.set(loc.targetQname, filtered);
      }
    }
    this.byDocument.delete(uri);
  }

  findByQname(qname: string): ReferenceLocation[] {
    return this.byTargetQname.get(qname) ?? [];
  }

  /** All resolved references that occur within `uri` (for semantic tokens). */
  getForDocument(uri: string): ReferenceLocation[] {
    return this.byDocument.get(uri) ?? [];
  }

  /**
   * The resolved reference whose source span contains the given position
   * (`line` 1-indexed, `char` 0-indexed), or undefined. Returns the tightest
   * match. Used to navigate inline-mapping references that the AST walk in
   * `findNodeAtPosition` can't reach.
   */
  findAtPosition(uri: string, line: number, char: number): ReferenceLocation | undefined {
    let best: ReferenceLocation | undefined;
    let bestArea = Number.POSITIVE_INFINITY;
    for (const loc of this.byDocument.get(uri) ?? []) {
      const s = loc.source;
      if (line < s.line || line > s.endLine) continue;
      if (line === s.line && char < s.column) continue;
      if (line === s.endLine && char > s.endColumn) continue;
      const area = (s.endLine - s.line) * 1000 + (s.endColumn - s.column);
      if (area < bestArea) {
        best = loc;
        bestArea = area;
      }
    }
    return best;
  }
}
