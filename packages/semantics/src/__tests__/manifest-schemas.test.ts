// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseManifest, resolveManifest, validateManifest } from '../manifest.js';

const TOML = `
[project]
name = "hartland-metadata"
version = "0.1.0"

[schemas.dbo]
database  = "WH"
db-schema = "dbo"
dialect   = "tsql"

[schemas.sales]
database  = "WH"
db-schema = "sales"
dialect   = "tsql"

[schemas.core]
database  = "core"
db-schema = "public"
dialect   = "postgres"

[packages."shop.sales"]
default-schema = "sales"

[packages."shop.catalog"]
default-schema = "dbo"

[defaults]
schema = "dbo"

[lint]
require-qualified-refs = true
`;

describe('v4.0 manifest — named schemas + package bindings (qname-redesign §1–§2)', () => {
  const resolved = resolveManifest(parseManifest(TOML), '/proj');

  it('parses [schemas.<name>] bindings into SchemaBinding records (db-schema kept distinct)', () => {
    expect(resolved.schemas.sales).toEqual({
      name: 'sales',
      database: 'WH',
      dbSchema: 'sales',
      dialect: 'tsql',
    });
    expect(resolved.schemas.core.dialect).toBe('postgres');
    expect(resolved.schemas.core.dbSchema).toBe('public');
    expect(Object.keys(resolved.schemas).sort()).toEqual(['core', 'dbo', 'sales']);
  });

  it('parses per-package default-schema', () => {
    expect(resolved.packageConfigs['shop.sales']).toEqual({ name: 'shop.sales', defaultSchema: 'sales' });
    expect(resolved.packageConfigs['shop.catalog'].defaultSchema).toBe('dbo');
  });

  it('parses [defaults] schema and [lint] require-qualified-refs', () => {
    expect(resolved.defaults.schema).toBe('dbo');
    expect(resolved.lint.requireQualifiedRefs).toBe(true);
  });

  it('does not put per-package configs in the [packages] root/layout config', () => {
    // The legacy `m.packages` (root/layout) stays a PackagesConfig.
    expect(resolved.packages).toEqual({ root: '', layout: 'flexible' });
  });

  it('valid config produces no manifest diagnostics', () => {
    expect(validateManifest(resolved)).toEqual([]);
  });

  it('schema-name-collision: a schema handle equal to a model code is an error (D9)', () => {
    const r = resolveManifest(parseManifest('[schemas.db]\ndatabase = "WH"\ndb-schema = "dbo"\n'), '/p');
    const diags = validateManifest(r);
    expect(diags).toHaveLength(1);
    expect(diags[0].code).toBe('schema-name-collision');
  });

  it('schema-name-collision: a schema handle equal to a kind keyword is an error (D9)', () => {
    const r = resolveManifest(parseManifest('[schemas.table]\ndatabase = "WH"\ndb-schema = "dbo"\n'), '/p');
    expect(validateManifest(r).some((d) => d.code === 'schema-name-collision')).toBe(true);
  });

  it('schema-name-collision: a schema handle equal to a package name is an error (D9)', () => {
    const r = resolveManifest(
      parseManifest('[schemas.shop]\ndatabase = "WH"\ndb-schema = "dbo"\n[packages."shop"]\ndefault-schema = "shop"\n'),
      '/p',
    );
    expect(validateManifest(r).some((d) => d.code === 'schema-name-collision')).toBe(true);
  });

  it('unknown-package-schema: a package default-schema absent from [schemas] is an error', () => {
    const r = resolveManifest(parseManifest('[packages."shop.sales"]\ndefault-schema = "nope"\n'), '/p');
    const diags = validateManifest(r);
    expect(diags).toHaveLength(1);
    expect(diags[0].code).toBe('unknown-package-schema');
  });

  it('embedded-SQL namespace-map equivalence: a [schemas.sales] binding carries database + db-schema + dialect (contracts §1.1)', () => {
    // The old `[[sql.namespace-map]] { namespace="sales", database="WH", schema="dbo" }`
    // maps to `[schemas.sales] { database="WH", db-schema=..., dialect=... }` — one source of truth.
    const b = resolved.schemas.sales;
    expect(b.name).toBe('sales'); // was the namespace handle
    expect(b.database).toBe('WH');
    expect(typeof b.dbSchema).toBe('string');
    expect(b.dialect).toBe('tsql');
  });

  it('no [schemas]/[packages] block → empty records, no diagnostics', () => {
    const r = resolveManifest(parseManifest('[project]\nname = "x"\n'), '/p');
    expect(r.schemas).toEqual({});
    expect(r.packageConfigs).toEqual({});
    expect(r.defaults.schema).toBeUndefined();
    expect(validateManifest(r)).toEqual([]);
  });
});
