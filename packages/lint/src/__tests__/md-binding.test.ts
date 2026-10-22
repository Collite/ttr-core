// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { DiagnosticCode } from '@tatrman/parser';
import { recommendedConfig, lintProj, lintDocInProject, type ProjectFile } from './helpers.js';

const LOGICAL = `model md
def domain Day { type: date }
def domain Month { type: int, kind: calc, restrict: { range: 1..12 } }
def domain AccountKind { type: string, kind: bound }
def domain CCCode { type: string }
def domain CustCode { type: string }
def domain Money { type: decimal }
def dimension Customer { key: code, attributes: [def attribute code { domain: md.CustCode }] }
def dimension Time { key: day, attributes: [def attribute day { domain: md.Day }] }
def map cc_to_cust { from: md.CCCode, to: md.CustCode, cardinality: { from: "N", to: "1" } }
def map d2m { from: md.Day, to: md.Month, calc: monthOfDate }
def measure net { domain: md.Money, aggregation: sum }
def measure gross { domain: md.Money, aggregation: sum }
def cubelet sales { grain: [Customer.code, Time.day], measures: [net, gross] }`;

const mdFile = { uri: 'file:///model.ttrm', src: LOGICAL };

function allCodes(binding: string, logical = LOGICAL): string[] {
  const files: ProjectFile[] = [
    { uri: 'file:///model.ttrm', src: logical },
    { uri: 'file:///binding.ttrm', src: `model binding\n${binding}` },
  ];
  return [...lintProj(files).values()].flat().map((d) => d.code);
}

const CLEAN_BINDING = `def md2db_domain ak_src { domain: md.AccountKind, source: { table: db.dbo.A, column: K } }
def md2db_map cc_map { map: md.cc_to_cust, target: db.dbo.CCMAP, columns: { CCCode: C1, CustCode: C2 } }
def md2db_cubelet sales_w {
  cubelet: md.sales, target: db.dbo.SALES, shape: wide,
  attributes: { Customer.code: CUST, Time.day: DT },
  measures: { net: NET, gross: GROSS }
}`;

describe('Stage 3A — md2db_domain + md2db_map', () => {
  it('a clean binding set produces no binding diagnostics', () => {
    const c = allCodes(CLEAN_BINDING);
    for (const code of [
      DiagnosticCode.MdSourceOnUnboundDomain,
      DiagnosticCode.MdBoundDomainNoSource,
      DiagnosticCode.MdBindingOnCalcMap,
      DiagnosticCode.MdMapColumnsIncomplete,
    ]) {
      expect(c).not.toContain(code);
    }
  });

  it('md2db_domain on a non-bound domain → md/source-on-unbound-domain', () => {
    expect(
      allCodes('def md2db_domain s { domain: md.Day, source: { table: db.dbo.A, column: K } }')
    ).toContain(DiagnosticCode.MdSourceOnUnboundDomain);
  });

  it('a bound domain with no md2db_domain → md/bound-domain-no-source', () => {
    // No md2db_domain for AccountKind in this binding set.
    expect(allCodes('def md2db_map cc_map { map: md.cc_to_cust, target: db.dbo.M, columns: { CCCode: C1, CustCode: C2 } }')).toContain(
      DiagnosticCode.MdBoundDomainNoSource
    );
  });

  it('md2db_map on a calc map → md/binding-on-calc-map', () => {
    expect(allCodes('def md2db_map m { map: md.d2m, target: db.dbo.T, columns: { Day: C1, Month: C2 } }')).toContain(
      DiagnosticCode.MdBindingOnCalcMap
    );
  });

  it('md2db_map missing a from/to column → md/map-columns-incomplete', () => {
    expect(allCodes('def md2db_map m { map: md.cc_to_cust, target: db.dbo.T, columns: { CCCode: C1 } }')).toContain(
      DiagnosticCode.MdMapColumnsIncomplete
    );
  });
});

describe('Stage 3B — md2db_cubelet shape/columns/journaling', () => {
  it('a wide cubelet binding measures by column is clean', () => {
    expect(allCodes(CLEAN_BINDING)).not.toContain(DiagnosticCode.MdShapeMeasureMismatch);
  });

  it('a code-form measure under wide shape → md/shape-measure-mismatch', () => {
    expect(
      allCodes(
        'def md2db_cubelet c { cubelet: md.sales, target: db.dbo.S, shape: wide, attributes: { Customer.code: A, Time.day: B }, measures: { net: { code: N }, gross: G } }'
      )
    ).toContain(DiagnosticCode.MdShapeMeasureMismatch);
  });

  it('a long cubelet binding measures by code is clean', () => {
    expect(
      allCodes(
        'def md2db_cubelet c { cubelet: md.sales, target: db.dbo.S, shape: { long: { codeColumn: CC, valueColumn: VV } }, attributes: { Customer.code: A, Time.day: B }, measures: { net: { code: N }, gross: { code: G } } }'
      )
    ).not.toContain(DiagnosticCode.MdShapeMeasureMismatch);
  });

  it('a binding not covering the grain → md/cubelet-grain-uncovered', () => {
    expect(
      allCodes(
        'def md2db_cubelet c { cubelet: md.sales, target: db.dbo.S, shape: wide, attributes: { Customer.code: A }, measures: { net: N, gross: G } }'
      )
    ).toContain(DiagnosticCode.MdCubeletGrainUncovered);
  });

  it('invalidate journaling without validColumn → md/incomplete-journaling', () => {
    expect(
      allCodes(
        'def md2db_cubelet c { cubelet: md.sales, target: db.dbo.S, shape: wide, attributes: { Customer.code: A, Time.day: B }, measures: { net: N, gross: G }, journaling: { invalidate: {} } }'
      )
    ).toContain(DiagnosticCode.MdIncompleteJournaling);
  });
});

describe('Stage 3C — multi-source, completeness, md2er', () => {
  it('writeback journaling leaving a measure unbound → md/incomplete-journaling', () => {
    expect(
      allCodes(
        'def md2db_cubelet c { cubelet: md.sales, target: db.dbo.S, shape: wide, attributes: { Customer.code: A, Time.day: B }, measures: { net: N }, journaling: overwrite }'
      )
    ).toContain(DiagnosticCode.MdIncompleteJournaling);
  });

  it('two bindings disagreeing on grain → md/multisource-grain-mismatch', () => {
    const two =
      'def md2db_cubelet a { cubelet: md.sales, target: db.dbo.S1, shape: wide, attributes: { Customer.code: A, Time.day: B }, measures: { net: N } }\n' +
      'def md2db_cubelet b { cubelet: md.sales, target: db.dbo.S2, shape: wide, attributes: { Customer.code: A }, measures: { gross: G } }';
    expect(allCodes(two)).toContain(DiagnosticCode.MdMultisourceGrainMismatch);
  });

  it('a structural md2er_cubelet is clean; a physical prop → md/md2er-physical-prop', () => {
    const clean = allCodes('def md2er_cubelet x { cubelet: md.sales, target: er.entity.S, attributes: { Customer.code: cc } }');
    expect(clean).not.toContain(DiagnosticCode.MdMd2erPhysicalProp);
    const bad = allCodes('def md2er_cubelet x { cubelet: md.sales, target: er.entity.S, attributes: {}, shape: wide }');
    expect(bad).toContain(DiagnosticCode.MdMd2erPhysicalProp);
  });
});

// keep the logical file referenced (lints the same in every project build)
void mdFile;

const ER_HOP = `model er
 def entity customer { attributes: [def attribute sk { type: int }, def attribute amount { type: decimal }] }
 def entity address { attributes: [def attribute state { type: text }] }
 def relation address { from: er.entity.customer, to: er.entity.address, cardinality: { from: "N", to: "1" } }`;

function hopCodes(binding: string): string[] {
  return [...lintProj([
    { uri: 'file:///md.ttrm', src: LOGICAL },
    { uri: 'file:///er.ttrm', src: ER_HOP },
    { uri: 'file:///binding.ttrm', src: `model binding\n${binding}` },
  ]).values()].flat().map(d => d.code);
}

describe('HP-P1 explicit MD to ER bindings', () => {
  it('requires the dimension key in the explicit attribute map', () => {
    expect(hopCodes('def md2er_dimension c { dimension: md.Customer, entity: er.entity.customer, attributes: {} }')).toContain('md/md2er-dimension-key');
    expect(hopCodes('def md2er_dimension c { dimension: md.Customer, entity: er.entity.customer, attributes: { code: sk, state: address.state } }')).not.toContain('md/md2er-dimension-key');
  });
  it('admits structural measures and checks their target attributes', () => {
    expect(hopCodes('def md2er_cubelet c { cubelet: md.sales, target: er.entity.customer, measures: { net: amount } }')).not.toContain('md/md2er-physical-prop');
    expect(hopCodes('def md2er_cubelet c { cubelet: md.sales, target: er.entity.customer, measures: { net: missing } }')).toContain('md/md2er-measure-unknown');
  });
  it('rejects duplicate dimension bindings', () => {
    expect(hopCodes('def md2er_dimension a { dimension: md.Customer, entity: er.entity.customer, attributes: { code: sk } }\ndef md2er_dimension b { dimension: md.Customer, entity: er.entity.customer, attributes: { code: sk } }')).toContain('md/duplicate-binding');
  });
});

describe('review-125 namespace and mapping validation', () => {
  const codes = (binding: string, logical = LOGICAL) => [...lintProj([
    { uri: 'file:///md.ttrm', src: logical }, { uri: 'file:///er.ttrm', src: ER_HOP },
    { uri: 'file:///binding.ttrm', src: `model binding\n${binding}` },
  ]).values()].flat().filter(d => d.code.startsWith('md/md2er-') || d.code === 'md/duplicate-binding').map(d => d.code);

  it('R6 resolves dimension shorthand by namespace when a domain shares its name', () => {
    const logical = LOGICAL + '\ndef domain Customer { type: text }';
    expect(codes('def md2er_dimension c { dimension: md.Customer, entity: er.entity.customer, attributes: { code: sk } }', logical)).toEqual([]);
    expect(codes('def md2er_dimension c { dimension: md.Customer, entity: er.entity.customer, attributes: {} }', logical)).toEqual(['md/md2er-dimension-key']);
  });
  it('R8 requires an own mapping for prototype-named keys', () => {
    const logical = LOGICAL.replace('key: code, attributes: [def attribute code', 'key: toString, attributes: [def attribute toString');
    expect(codes('def md2er_dimension c { dimension: md.Customer, entity: er.entity.customer, attributes: {} }', logical)).toContain('md/md2er-dimension-key');
  });
  it.each([
    ['missing cubelet', 'target: er.entity.customer'],
    ['unknown cubelet', 'cubelet: md.typo, target: er.entity.customer'],
    ['unknown measure', 'cubelet: md.sales, target: er.entity.customer, measures: { revnue: amount }'],
    ['foreign measure', 'cubelet: md.sales, target: er.entity.customer, measures: { bonus: amount }'],
    ['unknown MD attribute', 'cubelet: md.sales, target: er.entity.customer, attributes: { Customer.bogus: sk }'],
    ['unknown ER attribute', 'cubelet: md.sales, target: er.entity.customer, attributes: { Customer.code: absent }'],
  ])('R10 rejects %s', (_name, body) => {
    expect(codes(`def md2er_cubelet c { ${body} }`, LOGICAL + '\ndef measure bonus { domain: md.Money, aggregation: sum }')).toEqual(['md/md2er-reference']);
  });
  it('R10 rejects an unknown dimension attribute key', () => {
    expect(codes('def md2er_dimension c { dimension: md.Customer, entity: er.entity.customer, attributes: { code: sk, bogus: sk } }')).toEqual(['md/md2er-reference']);
  });
  it('R9 never uses a wrong-kind dimension for key/duplicate checks', () => {
    expect(codes('def md2er_dimension a { dimension: md.sales, entity: er.entity.customer }\ndef md2er_dimension b { dimension: md.sales, entity: er.entity.customer }')).toEqual(['md/md2er-reference', 'md/md2er-reference']);
  });
});


describe('review-125 configured MD/ER lint severities', () => {
  it('R5 honors warning, info and off independently', () => {
    const diagnostics = [...lintProj([
      { uri: 'file:///md.ttrm', src: LOGICAL }, { uri: 'file:///er.ttrm', src: ER_HOP },
      { uri: 'file:///binding.ttrm', src: `model binding
        def md2er_dimension d { dimension: md.Customer, entity: er.entity.missing, attributes: {} }
        def md2er_cubelet c { cubelet: md.sales, target: er.entity.customer, measures: { net: missing } }` },
    ], { config: recommendedConfig({ 'md-md2er-dimension-key': 'warning', 'md-md2er-reference': 'info', 'md-md2er-measure-unknown': 'off' }) }).values()].flat();
    expect(diagnostics.find(d => d.code === 'md/md2er-dimension-key')?.severity).toBe('warning');
    expect(diagnostics.find(d => d.code === 'md/md2er-reference')?.severity).toBe('info');
    expect(diagnostics.some(d => d.code === 'md/md2er-measure-unknown')).toBe(false);
  });
});


describe('re-review N1 named relation import usage', () => {
  const uri = 'file:///binding.ttrm';
  const files = (value: string): ProjectFile[] => [
    { uri: 'file:///md.ttrm', src: LOGICAL },
    { uri: 'file:///er.ttrm', src: ER_HOP.replace('def relation address', 'def relation local_address') },
    { uri: 'file:///east.ttrm', src: `package east
      model er
      def relation address { from: er.entity.customer, to: er.entity.address }` },
    { uri: 'file:///west.ttrm', src: `package west
      model er
      def relation address { from: er.entity.address, to: er.entity.address }` },
    { uri, src: `import east.er.relation.address
      import west.er.relation.address
      model binding
      def md2er_dimension c { dimension: md.Customer, entity: er.entity.customer, attributes: { code: ${value} } }` },
  ];
  it('counts the named relation actually traversed, retaining an unused same-label import', () => {
    const unused = lintDocInProject(files('address.state'), uri).filter(d => d.code === 'ttr/unused-import');
    expect(unused.map(d => d.message)).toEqual(["Import 'west.er.relation.address' is not referenced"]);
    expect([...lintProj(files('address.state')).values()].flat().some(d => d.code === 'md/md2er-reference')).toBe(false);
  });
  it('still warns when no path uses the relation or its target attribute does not exist', () => {
    for (const value of ['sk', 'address.missing']) {
      expect(lintDocInProject(files(value), uri).filter(d => d.code === 'ttr/unused-import')).toHaveLength(2);
    }
  });
});
