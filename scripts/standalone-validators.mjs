// SPDX-License-Identifier: Apache-2.0
import { createRequire } from 'node:module';

// Resolve Ajv from the workspace that owns each schema. Standalone output
// preserves Ajv's errors while removing compilation from the consumer runtime.
export function standaloneValidators(owner, schemas, options) {
  const require = createRequire(owner);
  const Ajv = require('ajv/dist/2020.js').default;
  const standalone = require('ajv/dist/standalone/index.js').default;
  const ajv = new Ajv({ ...options, code: { source: true, esm: true, lines: true } });
  for (const [name, schema] of Object.entries(schemas)) ajv.addSchema(schema, name);

  let output = standalone(
    ajv,
    Object.fromEntries(Object.keys(schemas).map((name) => [name, name]))
  );
  const imports = new Map();
  // Ajv emits CJS imports for runtime helpers even with ESM exports.
  output = output.replace(/require\("([^"]+)"\)\.default/g, (_call, module) => {
    if (!imports.has(module)) imports.set(module, `runtime${imports.size}`);
    const binding = imports.get(module);
    return `(${binding}.default ?? ${binding})`;
  });
  if (/\brequire\(/.test(output)) throw new Error('Unexpected Ajv standalone runtime dependency');
  const bindings = [...imports].map(([module, name]) => `import ${name} from "${module}.js";\n`);
  for (const name of Object.keys(schemas))
    output = output.replace(`export const ${name} =`, `export const ${name}: ValidateFunction =`);
  return (
    '// @ts-nocheck\n// Generated from JSON Schema by Ajv. Do not edit.\n' +
    'import type { ValidateFunction } from "ajv";\n' +
    bindings.join('') +
    output
  );
}
