// SPDX-License-Identifier: Apache-2.0
import { configDefaults, defineConfig } from 'vitest/config';
import path from 'path';

export default defineConfig({
  test: {
    hookTimeout: 30000,
    testTimeout: 30000,
    // Wall-clock regression guards run after unit files, without competing parser workers.
    projects: [
      {
        extends: true,
        test: {
          name: 'unit',
          exclude: [...configDefaults.exclude, 'src/__tests__/yaml-performance.test.ts'],
          sequence: { groupOrder: 0 },
        },
      },
      {
        extends: true,
        test: {
          name: 'yaml-size',
          include: ['src/__tests__/yaml-performance.test.ts'],
          sequence: { groupOrder: 1 },
          fileParallelism: false,
        },
      },
    ],
  },
  resolve: {
    alias: {
      '@tatrman/grammar': path.resolve(__dirname, '../grammar/src'),
    },
  },
});
