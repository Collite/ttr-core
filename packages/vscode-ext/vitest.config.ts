// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['scripts/__tests__/**/*.test.ts', 'src/yaml-language.test.ts'],
  },
});