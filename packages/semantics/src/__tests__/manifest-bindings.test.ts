// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { parseManifest, resolveManifest, validateManifest } from '../manifest.js';

describe('Studio binding policy', () => {
  it('defaults to optional bindings and reads the explicit boolean', () => {
    expect(resolveManifest(undefined, '/world').bindings).toEqual({ required: false });
    expect(resolveManifest(parseManifest('[bindings]\nrequired = true'), '/world').bindings).toEqual({ required: true });
    expect(resolveManifest(parseManifest('[bindings]\nrequired = false'), '/world').bindings).toEqual({ required: false });
  });

  it('surfaces unknown binding keys as manifest warnings', () => {
    const manifest = resolveManifest(parseManifest('[bindings]\nrequire = true'), '/world');
    expect(validateManifest(manifest)).toEqual([expect.objectContaining({ code: 'manifest/unknown-key', severity: 'warning' })]);
    expect(manifest.bindings.required).toBe(false);
  });
});
