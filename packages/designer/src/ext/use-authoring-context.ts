// SPDX-License-Identifier: Apache-2.0
// FO-P0.S4.T5 — resolve the injected authoring context for the active backend. Open build: no loader
// registered → null (edit-absent Viewer). Commercial build: the registered loader resolves grants +
// drives the extension. Fail-closed: a throwing loader yields null (no edit) — never fail-open.

import { useEffect, useState } from 'react';
import type { ModelDataSource } from '../data/model-data-source.js';
import type { ShellEditContext } from '../shell/edit-context.js';
import { usePageEnvironment } from '../page/page-context.js';
import { getAuthoringLoader } from './authoring-registry.js';

export function useAuthoringContext(dataSource: ModelDataSource): ShellEditContext | null {
  const page = usePageEnvironment();
  const loader = page ? page.authoringLoader : getAuthoringLoader();
  const [context, setContext] = useState<ShellEditContext | null>(null);
  useEffect(() => {
    if (!loader) {
      setContext(null);
      return;
    }
    let cancelled = false;
    void loader({ dataSource })
      .then((resolved) => {
        if (!cancelled) setContext(resolved);
      })
      .catch(() => {
        if (!cancelled) setContext(null);
      });
    return () => {
      cancelled = true;
    };
  }, [dataSource, loader]);
  return context;
}
