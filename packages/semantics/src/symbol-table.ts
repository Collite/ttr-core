// SPDX-License-Identifier: Apache-2.0
import type { SourceLocation, Document, Definition, ConceptStereotype, ConceptBindingKind, ConceptProperty } from '@tatrman/parser';
import { buildCanonicalKey, modelForKind } from './qname.js';
import { keyKindOf } from './default-schema.js';
import { desugarConcepts } from './cn/desugar.js';
import type { ResolvedSemantics } from './semantics-block/model.js';

export interface SymbolEntry {
  qname: string;
  // Nested world members (engine/executor/storage/worldSchema, and from 0.16 the
  // automation members identity/deployment/process) are not top-level Definition
  // kinds but register as symbols under their world (v4.1). An event's participant
  // labels register under the event (0.17, `…cn.concept.Order.fulfilling`).
  kind:
    | Definition['kind']
    | 'engine'
    | 'executor'
    | 'storage'
    | 'worldSchema'
    | 'identity'
    | 'deployment'
    | 'process'
    | 'participant';
  name: string;
  source: SourceLocation;
  documentUri: string;
  parent?: string;
  packageName: string;
  schemaCode: string;
  /** 0.17 — a concept's stereotype (`kind: 'concept'` only); every stereotype shares one namespace. */
  stereotype?: ConceptStereotype;
  /** 0.17 — a cn binding's keyword (`kind: 'conceptBinding'` only); its key's kind segment. */
  bindingKind?: ConceptBindingKind;
  /**
   * 0.17 — set on what the cn desugar synthesized (CN-P2 S2.2): a concept a `concept { }` block
   * declares (and its participant labels), or the binding a `grounds:` / `concept { }` stands for.
   * Element references never reach these; an authored concept of the same key is preferred.
   */
  cnOrigin?: 'grounds' | 'sugar';
  /**
   * For er2db_* symbols only: distinguishes explicit `def er2db_*` declarations
   * from symbols synthesized from inline `binding:` properties on def entity /
   * attribute / relation (v2.1). Used by the duplicate-mapping validator.
   * Undefined for all non-er2db_* kinds.
   */
  mappingSource?: 'explicit' | 'inline';
  /**
   * For MD dimension attributes only: the `domain:` ref the attribute ranges
   * over (opaque). Lets the leaf/grain lattice (2E) lower an attribute-level map
   * to the map over the attributes' underlying domains without re-walking the AST.
   */
  domainRef?: string;
  /**
   * For MD domain symbols (`mdDomain`) only: the domain's `type` name and (when a
   * `range` restrict is present) its bounds. Lets the calc-catalog type-check
   * (2D) verify a map's `from`/`to` against the entry signature cross-file.
   */
  domainType?: string;
  domainRange?: { lo: number; hi: number };
  /**
   * For explicit `def er2db_entity` symbols only: the dotted reference path of
   * the db table (or view) it targets (e.g. `db.dbo.promotion`), taken from
   * `target: { table: … }` or `target: { view: … }`. Lets inline
   * attribute/column mappings on an entity with no inline mapping block resolve
   * their columns against this table/view.
   */
  targetTableRef?: string;
  /**
   * Grounding Phase 1 (grammar 4.2), entity/table symbols only: the RAW `kind:`
   * declared in the symbol's `semantics { }` block (e.g. `period_table`),
   * unvalidated. Lets the semantics-block validator resolve a cross-document
   * `period:` ref to a period-table entity without re-walking the target's AST.
   */
  semanticsKind?: string;
  /**
   * Grounding Phase 1: the VALIDATED `semantics` result, populated by consumers
   * (e.g. ttr-metadata) only when the block is diagnostics-free. Undefined here
   * until decorated — the symbol-table build stays mechanical.
   */
  semantics?: ResolvedSemantics;
}

export class DocumentSymbolTable {
  private entries: Map<string, SymbolEntry> = new Map();
  private documentUri: string;
  private schemaCode: string;
  private namespace: string;
  private packageName: string;

  /**
   * `schemaCode` is the file's explicit `schema` directive code, or `''` when
   * the file has no directive. In the empty case each entry's effective schema
   * is derived per-definition from its kind (see {@link effectiveSchema}).
   *
   * `packageName` is the file's **effective package** (PD1.3) — declaration if
   * present, else directory-derived with the configured root prefix. When
   * omitted it falls back to the in-file declaration (the pre-PD1 behaviour),
   * which keeps direct constructions in tests working.
   */
  constructor(
    documentUri: string,
    ast: Document,
    schemaCode: string,
    namespace: string,
    packageName?: string
  ) {
    this.documentUri = documentUri;
    this.schemaCode = schemaCode;
    this.namespace = namespace;
    this.packageName = packageName ?? ast.packageDecl?.name ?? '';

    for (const def of ast.definitions) {
      this.addEntry(def);
    }
    this.addDesugared(ast);
  }

  /**
   * 0.17 — the concepts this document's `concept { }` blocks declare and the bindings its `grounds:`
   * and `concept { }` stand for (`cn/desugar.ts`). A synthesized symbol never replaces one the
   * document declares: an authored concept of the same name is the concept the block grounds.
   */
  private addDesugared(ast: Document): void {
    const { concepts, groundings } = desugarConcepts(ast, this.packageName);
    for (const c of concepts) {
      const entry: SymbolEntry = {
        qname: this.makeQname('concept', [c.name]),
        kind: 'concept',
        name: c.name,
        // The name in the declaring slot, not the slot — what a rename edits (review-119 M-5).
        source: c.declaring.ref.source,
        documentUri: this.documentUri,
        packageName: this.packageName,
        schemaCode: 'cn',
        stereotype: c.stereotype,
        cnOrigin: 'sugar',
      };
      if (this.entries.has(entry.qname)) continue;
      this.entries.set(entry.qname, entry);
      if (c.stereotype === 'event') this.addParticipants(entry, c.body.properties, 'sugar');
    }
    for (const g of groundings) {
      if (g.origin === 'binding' || !g.bindingKind) continue;
      const qname = this.makeQname(g.bindingKind, g.name.split('.'));
      if (this.entries.has(qname)) continue;
      this.entries.set(qname, {
        qname,
        kind: 'conceptBinding',
        name: g.name,
        source: g.source,
        documentUri: this.documentUri,
        packageName: this.packageName,
        schemaCode: 'binding',
        bindingKind: g.bindingKind,
        cnOrigin: g.origin,
      });
    }
  }

  /**
   * 0.17 — an event's participants register by label (`Warehouse as fulfilling` → `fulfilling`;
   * unlabelled → the concept name lower-cased, cn contracts §1.1), so a binding's `participants:`
   * keys and a definition's `this.<label>` resolve. Two unlabelled participants of one concept
   * share a key — `cn/involves-unlabelled-duplicate` (S2.3), not a duplicate definition.
   */
  private addParticipants(event: SymbolEntry, properties: ConceptProperty[], cnOrigin?: 'sugar'): void {
    const involves = properties.find((p) => p.kind === 'involves');
    if (involves?.kind !== 'involves') return;
    for (const participant of involves.participants) {
      const label = participant.label ?? participant.ref.parts[participant.ref.parts.length - 1].toLowerCase();
      const pQname = this.makeQnameChild(event, label);
      if (cnOrigin && this.entries.has(pQname)) continue;
      this.entries.set(pQname, {
        qname: pQname,
        kind: 'participant',
        name: label,
        source: participant.source,
        documentUri: this.documentUri,
        parent: event.qname,
        packageName: this.packageName,
        schemaCode: event.schemaCode,
        ...(cnOrigin ? { cnOrigin } : {}),
      });
    }
  }

  /**
   * The v4.0 uniform canonical key for a top-level def. The model is derived
   * from the def's own kind (D12: a file's `model` directive no longer dictates
   * its symbols' model), the schema slot is db-only (the file `schema` id, else
   * `dbo`), and the kind segment is always present. Stock cnc no longer doubles
   * (`cnc.role.*`, not `cnc.cnc.role.*` — D15).
   */
  private makeQname(kind: string, parts: string[]): string {
    return buildCanonicalKey({
      packageName: this.packageName,
      schemaId: this.namespace,
      kind,
      parts,
      declaringModel: this.schemaCode,
    });
  }

  private makeQnameChild(parentEntry: SymbolEntry, childName: string): string {
    // Members are grouped under their parent def's model/schema/kind; only the
    // name path grows (`…db.dbo.table.Orders.id`, `…er.entity.customer.code`).
    return buildCanonicalKey({
      packageName: this.packageName,
      schemaId: this.namespace,
      kind: parentEntry.kind,
      parts: [parentEntry.name, childName],
    });
  }

  private addEntry(def: Definition, parentQname?: string): void {
    const qnameStr = this.makeQname(keyKindOf(def), [def.name]);

    const entry: SymbolEntry = {
      qname: qnameStr,
      kind: def.kind,
      name: def.name,
      source: def.source,
      documentUri: this.documentUri,
      parent: parentQname,
      packageName: this.packageName,
      schemaCode: modelForKind(def.kind, this.schemaCode),
    };

    if (def.kind === 'er2dbEntity' || def.kind === 'er2dbAttribute' || def.kind === 'er2dbRelation') {
      entry.mappingSource = 'explicit';
    }
    if (def.kind === 'concept') entry.stereotype = def.stereotype;
    if (def.kind === 'conceptBinding') entry.bindingKind = def.bindingKind;
    // Grounding Phase 1: carry the raw `kind:` off an entity/table semantics block
    // so cross-document `period:` refs can be kind-checked from the symbol alone.
    if ((def.kind === 'entity' || def.kind === 'table') && def.semantics) {
      const k = def.semantics.entries.kind;
      if (typeof k === 'string') entry.semanticsKind = k;
    }
    if (def.kind === 'mdDomain') {
      if (def.type) entry.domainType = def.type.kind === 'simple' ? def.type.name : def.type.typeName;
      const rangeClause = def.restrict?.find((c) => c.clause === 'range');
      if (rangeClause && !Array.isArray(rangeClause.value) && rangeClause.value.kind === 'rangeLiteral') {
        entry.domainRange = { lo: rangeClause.value.lo, hi: rangeClause.value.hi };
      }
    }
    if (def.kind === 'er2dbEntity' && def.target) {
      const t = def.target;
      if ('kind' in t) {
        // ObjectValue: `target: { table: db.dbo.X }` or `target: { view: db.dbo.V }`
        const tableEntry = t.entries.find((e) => e.key === 'table' || e.key === 'view');
        if (tableEntry && tableEntry.value.kind === 'id') entry.targetTableRef = tableEntry.value.path;
      } else {
        // Reference: `target: db.dbo.X`
        entry.targetTableRef = t.path;
      }
    }

    this.entries.set(qnameStr, entry);

    if (def.kind === 'entity' && def.attributes) {
      const entityEntry = entry;
      for (const attr of def.attributes) {
        const attrQnameStr = this.makeQnameChild(entityEntry, attr.name);
        this.entries.set(attrQnameStr, {
          qname: attrQnameStr,
          kind: 'attribute',
          name: attr.name,
          source: attr.source,
          documentUri: this.documentUri,
          parent: entityEntry.qname,
          packageName: this.packageName,
          schemaCode: entry.schemaCode,
        });
      }
    }

    // v3.1 MD — dimension attributes register dimension-qualified
    // (`md.dimension.<Dim>.<attr>`), addressable both dotted (`Customer.code`)
    // and bare within the dimension. Mirrors the entity→attribute block.
    if (def.kind === 'dimension' && def.attributes) {
      const dimEntry = entry;
      for (const attr of def.attributes) {
        const attrQnameStr = this.makeQnameChild(dimEntry, attr.name);
        this.entries.set(attrQnameStr, {
          qname: attrQnameStr,
          kind: 'attribute',
          name: attr.name,
          source: attr.source,
          documentUri: this.documentUri,
          parent: dimEntry.qname,
          packageName: this.packageName,
          schemaCode: entry.schemaCode,
          domainRef: attr.domainRef,
        });
      }
    }

    // v4.1 world — engines/executors/storages register under the world
    // (`…world.<world>.<member>`); nested storage schemas register one level
    // deeper (`…world.<world>.<storage>.<schema>`). Mirrors the entity→attribute
    // nesting; duplicates reuse the existing duplicate-definition diagnostic.
    if (def.kind === 'world') {
      const worldEntry = entry;
      const members: Array<{
        name: string;
        kind: 'engine' | 'executor' | 'storage' | 'identity' | 'deployment' | 'process';
        source: SourceLocation;
      }> = [
        ...def.engines.map((e) => ({ name: e.name, kind: 'engine' as const, source: e.source })),
        ...def.executors.map((e) => ({ name: e.name, kind: 'executor' as const, source: e.source })),
        ...def.storages.map((s) => ({ name: s.name, kind: 'storage' as const, source: s.source })),
        // 0.16 — the named automation members (grants are unnamed: keyed by their triple, no symbol).
        ...(def.identities ?? []).map((m) => ({ name: m.name, kind: 'identity' as const, source: m.source })),
        ...(def.deployments ?? []).map((m) => ({ name: m.name, kind: 'deployment' as const, source: m.source })),
        ...(def.processes ?? []).map((m) => ({ name: m.name, kind: 'process' as const, source: m.source })),
      ];
      for (const m of members) {
        const mQname = this.makeQnameChild(worldEntry, m.name);
        this.entries.set(mQname, {
          qname: mQname,
          kind: m.kind,
          name: m.name,
          source: m.source,
          documentUri: this.documentUri,
          parent: worldEntry.qname,
          packageName: this.packageName,
          schemaCode: entry.schemaCode,
        });
      }
      for (const s of def.storages) {
        for (const sc of s.schemas) {
          const scQname = buildCanonicalKey({
            packageName: this.packageName,
            schemaId: this.namespace,
            kind: worldEntry.kind,
            parts: [worldEntry.name, s.name, sc.name],
          });
          this.entries.set(scQname, {
            qname: scQname,
            kind: 'worldSchema',
            name: sc.name,
            source: sc.source,
            documentUri: this.documentUri,
            parent: this.makeQnameChild(worldEntry, s.name),
            packageName: this.packageName,
            schemaCode: entry.schemaCode,
          });
        }
      }
    }

    if (def.kind === 'concept' && def.stereotype === 'event') this.addParticipants(entry, def.properties);

    if (def.kind === 'table' && def.columns) {
      const tableEntry = entry;
      for (const col of def.columns) {
        const colQnameStr = this.makeQnameChild(tableEntry, col.name);
        this.entries.set(colQnameStr, {
          qname: colQnameStr,
          kind: 'column',
          name: col.name,
          source: col.source,
          documentUri: this.documentUri,
          parent: tableEntry.qname,
          packageName: this.packageName,
          schemaCode: entry.schemaCode,
        });
      }
    }

    if (def.kind === 'view' && def.columns) {
      const viewEntry = entry;
      for (const col of def.columns) {
        const colQnameStr = this.makeQnameChild(viewEntry, col.name);
        this.entries.set(colQnameStr, {
          qname: colQnameStr,
          kind: 'column',
          name: col.name,
          source: col.source,
          documentUri: this.documentUri,
          parent: viewEntry.qname,
          packageName: this.packageName,
          schemaCode: entry.schemaCode,
        });
      }
    }

    if (def.kind === 'procedure' && def.resultColumns) {
      const procEntry = entry;
      for (const col of def.resultColumns) {
        const colQnameStr = this.makeQnameChild(procEntry, col.name);
        this.entries.set(colQnameStr, {
          qname: colQnameStr,
          kind: 'column',
          name: col.name,
          source: col.source,
          documentUri: this.documentUri,
          parent: procEntry.qname,
          packageName: this.packageName,
          schemaCode: entry.schemaCode,
        });
      }
    }
  }

  get(qname: string): SymbolEntry | undefined {
    return this.entries.get(qname);
  }

  all(): SymbolEntry[] {
    return Array.from(this.entries.values());
  }

  /** What the table was built with: the file's model directive code, namespace and effective package. */
  context(): { schemaCode: string; namespace: string; packageName: string } {
    return { schemaCode: this.schemaCode, namespace: this.namespace, packageName: this.packageName };
  }

  inDocument(): SymbolEntry[] {
    return this.all();
  }
}