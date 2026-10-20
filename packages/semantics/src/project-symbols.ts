// SPDX-License-Identifier: Apache-2.0
import type { Document } from '@tatrman/parser';
import { DocumentSymbolTable, type SymbolEntry } from './symbol-table.js';

export class ProjectSymbolTable {
  private byDocument: Map<string, DocumentSymbolTable> = new Map();
  private byQname: Map<string, SymbolEntry[]> = new Map();
  private synthesizedByDocument: Map<string, SymbolEntry[]> = new Map();

  /**
   * `packageName` is the document's **effective package** (PD1.3). When omitted
   * the symbol table falls back to the in-file `package` declaration. Callers on
   * the live path (LSP, lint, migrate CLI) pass `effectivePackage(...)` so
   * directory-derived and root-prefixed qnames are canonical.
   */
  upsertDocument(uri: string, ast: Document, schemaCode: string, namespace: string, packageName?: string): void {
    const existing = this.byDocument.get(uri);
    if (existing) {
      this.removeDocument(uri);
    }

    const table = new DocumentSymbolTable(uri, ast, schemaCode, namespace, packageName);
    this.byDocument.set(uri, table);

    for (const entry of table.all()) {
      const existing = this.byQname.get(entry.qname) ?? [];
      existing.push(entry);
      this.byQname.set(entry.qname, existing);
    }
  }

  removeDocument(uri: string): void {
    const table = this.byDocument.get(uri);
    if (!table) return;

    for (const entry of table.all()) {
      const existing = this.byQname.get(entry.qname);
      if (existing) {
        const filtered = existing.filter((e) => e.documentUri !== uri);
        if (filtered.length === 0) {
          this.byQname.delete(entry.qname);
        } else {
          this.byQname.set(entry.qname, filtered);
        }
      }
    }

    this.byDocument.delete(uri);

    const synth = this.synthesizedByDocument.get(uri) ?? [];
    for (const e of synth) {
      const list = this.byQname.get(e.qname);
      if (list) {
        const filtered = list.filter((x) => x.documentUri !== uri);
        if (filtered.length === 0) this.byQname.delete(e.qname);
        else this.byQname.set(e.qname, filtered);
      }
    }
    this.synthesizedByDocument.delete(uri);
  }

  /**
   * Add synthesized er2db_* symbol entries (from v2.1 inline mappings) attributed
   * to the host document. These appear in `byQname` queries but NOT in
   * `byDocument(uri).all()` — by design, inline-synthesized symbols live in the
   * project index only.
   */
  upsertSynthesizedSymbols(uri: string, entries: SymbolEntry[]): void {
    const prev = this.synthesizedByDocument.get(uri) ?? [];
    for (const e of prev) {
      const list = this.byQname.get(e.qname);
      if (list) {
        const filtered = list.filter((x) => !(x.documentUri === uri && x.mappingSource === 'inline' && x.qname === e.qname));
        if (filtered.length === 0) this.byQname.delete(e.qname);
        else this.byQname.set(e.qname, filtered);
      }
    }

    for (const e of entries) {
      const list = this.byQname.get(e.qname) ?? [];
      list.push(e);
      this.byQname.set(e.qname, list);
    }
    this.synthesizedByDocument.set(uri, entries);
  }

  /**
   * The entry a qname reads as. 0.17: an authored symbol over one the cn desugar synthesized
   * (`cnOrigin` — a concept a `concept { }` block declares where a cn def declares it too, S2.2);
   * among synthesized ones the first in document order, so the answer does not depend on the order
   * documents were opened in.
   */
  get(qname: string): SymbolEntry | undefined {
    const list = this.byQname.get(qname);
    if (!list || list.length < 2) return list?.[0];
    const authored = list.find((e) => e.cnOrigin === undefined);
    if (authored) return authored;
    return [...list].sort((a, b) =>
      a.documentUri < b.documentUri ? -1 : a.documentUri > b.documentUri ? 1 : a.source.offsetStart - b.source.offsetStart,
    )[0];
  }

  allQnames(): string[] {
    return [...this.byQname.keys()];
  }

  getAll(qname: string): SymbolEntry[] {
    return this.byQname.get(qname) ?? [];
  }

  all(): SymbolEntry[] {
    const result: SymbolEntry[] = [];
    const seen = new Set<string>();
    for (const entries of this.byQname.values()) {
      for (const entry of entries) {
        if (!seen.has(entry.qname)) {
          seen.add(entry.qname);
          result.push(entry);
        }
      }
    }
    return result;
  }

  findByName(name: string): SymbolEntry[] {
    const result: SymbolEntry[] = [];
    for (const entry of this.all()) {
      if (entry.name === name) {
        result.push(entry);
      }
    }
    return result;
  }

  duplicates(): Array<{ qname: string; entries: SymbolEntry[] }> {
    const result: Array<{ qname: string; entries: SymbolEntry[] }> = [];
    for (const [qname, entries] of this.byQname.entries()) {
      if (entries.length > 1) {
        result.push({ qname, entries });
      }
    }
    return result;
  }

  getByPackage(packageName: string): SymbolEntry[] {
    const result: SymbolEntry[] = [];
    for (const entry of this.all()) {
      if (entry.packageName === packageName) {
        result.push(entry);
      }
    }
    return result;
  }

  getBySuffix(suffix: string): SymbolEntry[] {
    const result: SymbolEntry[] = [];
    for (const entries of this.byQname.values()) {
      for (const entry of entries) {
        if (entry.qname.endsWith(`.${suffix}`) || entry.qname === suffix) {
          result.push(entry);
        }
      }
    }
    return result;
  }

  listPackages(): string[] {
    const packages = new Set<string>();
    for (const entry of this.all()) {
      packages.add(entry.packageName);
    }
    return Array.from(packages).sort();
  }
}