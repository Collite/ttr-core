// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';
export default defineConfig({
  root: fileURLToPath(new URL('../', import.meta.url)),
  test: {
    environment: 'node',
    include: ['scripts/publish-family.test.ts', 'scripts/designer-publish.test.ts'],
  },
});
