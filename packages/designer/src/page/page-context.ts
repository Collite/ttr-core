// SPDX-License-Identifier: Apache-2.0
import { createContext, useContext } from 'react';
import type { RuntimeConfig } from '../runtime-config.js';
import type { AuthoringLoader } from '../ext/authoring-registry.js';
export interface PageEnvironment {
  config: RuntimeConfig;
  theme: 'light' | 'dark';
  authoringLoader: AuthoringLoader | null;
  location: {
    current(): string;
    replace(path: string): void;
    subscribe(handler: (path: string) => void): () => void;
    absolute(path: string): string;
  };
}
export const PageContext = createContext<PageEnvironment | null>(null);
export const usePageEnvironment = () => useContext(PageContext);
