// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
    testTimeout: 30000,
    hookTimeout: 30000,
    // Each suite imports the canonical parser; avoid competing parser instances on CI.
    fileParallelism: false,
  },
});
