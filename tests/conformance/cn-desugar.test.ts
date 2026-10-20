// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll } from 'vitest';
import { parseFile, type Document } from '@tatrman/parser';
import {
  analyzeGroundings,
  ProjectSymbolTable,
  propertyFingerprint,
  Resolver,
  type CnDocument,
  type GroundingAnalysis,
} from '@tatrman/semantics';
import { loadStockVocabularies } from '@tatrman/semantics/node-only';
import { promises as fs } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { dumpSemDocs, type SemDump } from './dump-sem.js';

/**
 * CN-P2 S2.2 T1 — the equivalence pairs (CN-D9: both sugars desugar to the binding an author would
 * write). `81` grounds every concept with `grounds:`, `82` with authored bindings; `83` declares and
 * grounds with `concept { }` blocks, `84` with cn defs and authored bindings. Each pair must yield the
 * same canonical groundings (concept → binding kind, target element, binding properties) and the same
 * symbols — a binding's name aside: an authored binding is named by its author, a synthesized one
 * `<Concept>.grounds` / `<Concept>.concept`, and nothing refers to a binding by name.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const fixtures = path.join(here, 'fixtures');

interface Project {
  docs: CnDocument[];
  analysis: GroundingAnalysis;
  sem: SemDump;
  symbols: ProjectSymbolTable;
}

let stock: Map<string, Document>;

async function load(dir: string): Promise<Project> {
  const dirPath = path.join(fixtures, dir);
  const files = (await fs.readdir(dirPath)).filter((f) => f.endsWith('.ttrm')).sort();
  const docs: CnDocument[] = [];
  for (const f of files) {
    const result = await parseFile(path.join(dirPath, f));
    expect(result.errors.filter((e) => e.severity === 'error'), f).toEqual([]);
    const ast = result.ast!;
    docs.push({
      uri: `${dir}/${f}`,
      ast,
      packageName: ast.packageDecl?.name ?? '',
      schemaCode: ast.modelDirective?.modelCode ?? '',
      namespace: ast.modelDirective?.schema ?? '',
    });
  }
  const symbols = new ProjectSymbolTable();
  for (const [name, doc] of stock) symbols.upsertDocument(`stock://${name}.ttrm`, doc, 'cnc', 'role', '');
  for (const d of docs) symbols.upsertDocument(d.uri, d.ast, d.schemaCode, d.namespace, d.packageName);
  const analysis = analyzeGroundings(docs, new Resolver(symbols));
  const sem = dumpSemDocs(docs.map(({ ast, uri }) => ({ ast, uri })), stock);
  return { docs, analysis, sem, symbols };
}

/** Concept key → its one grounding, as what it says (not where or under which name it was written). */
function canonical(p: Project): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, list] of p.analysis.byConcept) {
    out[key] = list
      .map((g) => [g.bindingKind, g.targetSymbol?.qname ?? '?', ...g.properties.map(propertyFingerprint)].join(' '))
      .join(' | ');
  }
  return out;
}

/** The scenario's symbols with every binding's name masked. */
function maskedSymbols(p: Project): string[] {
  return p.sem.symbols.map((q) => q.replace(/(\.binding\.[a-z0-9_]+)\..*$/, '$1.*')).sort();
}

const PAIRS: Array<[string, string]> = [
  ['81-cn-desugar-top-down', '82-cn-desugar-top-down-authored'],
  ['83-cn-desugar-bottom-up', '84-cn-desugar-bottom-up-authored'],
];

describe('CN-P2 S2.2 — both sugars desugar to the binding an author would write', () => {
  const projects = new Map<string, Project>();
  beforeAll(async () => {
    stock = await loadStockVocabularies(['cnc-roles']);
    for (const dir of PAIRS.flat()) projects.set(dir, await load(dir));
  });

  it.each(PAIRS.flat())('%s: clean, every concept grounded exactly once', (dir) => {
    const p = projects.get(dir)!;
    expect(p.sem.diagnostics).toEqual([]);
    expect(p.analysis.diagnostics).toEqual([]);
    const concepts = p.symbols.all().filter((e) => e.kind === 'concept').map((e) => e.qname).sort();
    expect(Object.keys(canonical(p)).sort()).toEqual(concepts);
    for (const list of p.analysis.byConcept.values()) expect(list).toHaveLength(1);
    for (const g of p.analysis.groundings) expect(g.targetSymbol, `${g.uri} ${g.name}`).toBeDefined();
  });

  it.each(PAIRS)('%s ≡ %s: the same groundings', (sugar, authored) => {
    expect(canonical(projects.get(sugar)!)).toEqual(canonical(projects.get(authored)!));
  });

  it.each(PAIRS)('%s ≡ %s: the same symbols, binding names aside', (sugar, authored) => {
    expect(maskedSymbols(projects.get(sugar)!)).toEqual(maskedSymbols(projects.get(authored)!));
  });

  it('the pairs cover every binding kind', () => {
    const kinds = new Set<string>();
    for (const dir of PAIRS.flat()) for (const g of projects.get(dir)!.analysis.groundings) kinds.add(g.bindingKind!);
    expect([...kinds].sort()).toEqual([
      'cn2db_concept',
      'cn2er_event', 'cn2er_kind', 'cn2er_phase', 'cn2er_quality', 'cn2er_role',
      'cn2md_event', 'cn2md_kind', 'cn2md_quality', 'cn2md_role',
    ]);
  });
});
