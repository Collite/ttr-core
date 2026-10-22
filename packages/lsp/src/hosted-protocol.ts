// SPDX-License-Identifier: Apache-2.0
import type { ResolvedManifest } from '@tatrman/semantics';

export interface ProjectRootResult {
  ok: true;
  manifest: ResolvedManifest;
  /** Kept for existing hosts. */
  projectRoot: string;
}

export interface SchemaSubject {
  schema: 'er' | 'db' | 'md' | 'cn';
  objectCount: number;
  packageCount: number;
}

export interface ListedSymbol {
  qname: string;
  kind: string;
  name: string;
  packageName: string | null;
  memberPath?: string[];
  parent?: string;
}

export interface ListSymbolsParams {
  kind?: string;
  /** Existing picker API; `kind` is the singular shorthand. */
  kinds?: string[];
  package?: string;
  cursor?: string;
  limit?: number;
  includeMembers?: boolean;
}

export interface SymbolPage {
  symbols: ListedSymbol[];
  nextCursor?: string;
  total: number;
}
