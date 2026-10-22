// SPDX-License-Identifier: Apache-2.0
import type { CrossRef, Definition } from '@tatrman/parser';
import type { ProjectSymbolTable } from './project-symbols.js';
import type { SymbolEntry } from './symbol-table.js';

/**
 * Every span-carrying MD cross-reference reachable from a top-level def
 * (including inline dimension attributes and inline cubelet measures). Used by
 * the LSP for go-to-definition / hover on MD refs.
 */
export function mdCrossRefsOf(def: Definition): CrossRef[] {
  const out: CrossRef[] = [];
  const push = (crs?: CrossRef[]) => {
    if (crs) out.push(...crs);
  };
  switch (def.kind) {
    case 'dimension':
      push(def.crossRefs);
      for (const attr of def.attributes) push(attr.crossRefs);
      break;
    case 'cubelet':
      push(def.crossRefs);
      for (const m of def.measures) if (typeof m !== 'string') push(m.crossRefs);
      break;
    case 'mdMap':
    case 'hierarchy':
    case 'measure':
      push(def.crossRefs);
      break;
    default:
      break;
  }
  return out;
}

/**
 * Role → target namespace for an MD cross-reference (contracts §5). `grain`
 * resolves to a dimension attribute (`md.dimension.<Dim>.<attr>`).
 */
const ROLE_NS: Record<CrossRef['role'], string> = {
  domain: 'domain',
  map: 'map',
  dimension: 'dimension',
  measure: 'measure',
  hierarchy: 'hierarchy',
  grain: 'dimension',
};

/**
 * Candidate qnames for an authored MD ref under a target namespace. Authors omit
 * the namespace segment (`from: md.Day`, `grain: [Customer.code]`); symbols are
 * registered with it (`md.domain.Day`, `md.dimension.Customer.code`). We insert
 * the namespace after the `md` schema prefix (or prepend `md.<ns>` for a
 * namespace-less ref), retaining an explicit package prefix. The last candidate is the canonical form used for the
 * package-qualified suffix fallback.
 */
function candidates(path: string, ns: string): string[] {
  const parts = path.split('.');
  const out: string[] = [];
  const model = parts.lastIndexOf('md');
  if (model >= 0 && parts[model + 1] === ns) out.push(path); // already canonical
  const canonical =
    model >= 0 ? [...parts.slice(0, model + 1), ns, ...parts.slice(model + 1)].join('.') : ['md', ns, ...parts].join('.');
  out.push(canonical);
  return [...new Set(out)];
}

/**
 * Resolve a single MD cross-reference to its symbol, or undefined. Tries the
 * role's namespace; for a `domain`-role ref that is dotted (the attribute→domain
 * map sugar, design §5.3) it also accepts a dimension attribute. Falls back to a
 * package-qualified suffix match (`<pkg>.md.<ns>.<rest>`).
 */
export function resolveMdRef(
  symbols: ProjectSymbolTable,
  path: string,
  role: CrossRef['role'],
  packageName?: string
): SymbolEntry | undefined {
  const namespaces = [ROLE_NS[role]];
  // map `from`/`to` may name an attribute instead of a domain (the sugar).
  if (role === 'domain' && path.includes('.') && !path.startsWith('md.')) namespaces.push('dimension');

  for (const ns of namespaces) {
    const cands = candidates(path, ns);
    // Match the reference's own package before widening to the project. The
    // generic resolver and reverse index already have that source context.
    if (packageName) {
      for (const c of cands) {
        const hit = symbols.get(`${packageName}.${c}`);
        if (hit) return hit;
      }
    }
    for (const c of cands) {
      const hit = symbols.get(c);
      if (hit) return hit;
    }
    // Package-qualified suffix fallback (reuses the shared index). Mirror the
    // generic resolver's uniqueness rule: a suffix that matches more than one
    // distinct qname is ambiguous (e.g. two packages each defining `Day`) —
    // don't silently bind to whichever the iteration order surfaces first; skip
    // it so md/unknown-ref surfaces rather than a wrong resolution.
    const want = cands[cands.length - 1];
    const matches = symbols.getBySuffix(want);
    const distinct = new Set(matches.map((e) => e.qname));
    if (distinct.size === 1) return matches[0];
  }
  return undefined;
}

/**
 * The underlying **domain** an MD ref ranges over, for the leaf/grain lattice
 * (2E): a domain ref resolves to itself; an attribute ref (the sugar) lowers to
 * its `domain:`. Returns the resolved domain symbol, or undefined if neither the
 * ref nor its domain resolves.
 */
export function underlyingDomainOf(
  symbols: ProjectSymbolTable,
  path: string,
  role: CrossRef['role'] = 'domain'
): SymbolEntry | undefined {
  const sym = resolveMdRef(symbols, path, role);
  if (!sym) return undefined;
  if (sym.kind === 'mdDomain') return sym;
  if (sym.kind === 'attribute' && sym.domainRef) {
    return resolveMdRef(symbols, sym.domainRef, 'domain');
  }
  return sym;
}
