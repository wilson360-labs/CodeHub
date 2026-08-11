import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['tests/**/*.test.mjs'],
    environment: 'node',
    testTimeout: 30000,
    hookTimeout: 30000,
  },
});
