// SPDX-License-Identifier: Apache-2.0
import { createRef } from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { CanvasKernel } from '../Kernel.js';
import { createSkinRegistry } from '../../skins/index.js';
import { PageContext, type PageEnvironment } from '../../page/page-context.js';
vi.mock('@xyflow/react', () => ({
  ReactFlowProvider: ({ children }: { children: React.ReactNode }) => children,
  ReactFlow: ({ nodes }: { nodes: Array<{ data: { theme: string } }> }) => (
    <span data-testid="render-theme">{nodes[0]?.data.theme}</span>
  ),
  Background: () => null,
  Controls: () => null,
  MarkerType: { ArrowClosed: 'arrow' },
  useReactFlow: () => ({ screenToFlowPosition: vi.fn() }),
  Handle: () => null,
  Position: {},
  BaseEdge: () => null,
  EdgeLabelRenderer: () => null,
}));
afterEach(cleanup);
it.each([
  ['light', 'stage-navy'],
  ['dark', 'ice'],
] as const)(
  'keeps final node theme and surface consistent in a %s page',
  (pageTheme, requested) => {
    const environment: PageEnvironment = {
      root: createRef(),
      theme: pageTheme,
      authoringLoader: null,
      config: { basePath: '/m/', lang: 'en', languages: ['en'], veles: null, layoutFormat: 'yaml' },
      location: { current: () => '/', replace() {}, subscribe: () => () => {}, absolute: (p) => p },
    };
    render(
      <PageContext.Provider value={environment}>
        <CanvasKernel
          theme={requested}
          skinId="script"
          positions={{}}
          registry={createSkinRegistry()}
          graph={{
            id: 'theme',
            face: 'processing',
            containers: [],
            nodes: [
              {
                id: 'step',
                qname: 'demo.step',
                kind: 'script',
                label: 'step',
                ports: [],
                slotData: {},
              },
            ],
            edges: [],
          }}
        />
      </PageContext.Provider>
    );
    expect(screen.getByTestId('render-theme')).toHaveTextContent('stage-navy');
    expect(screen.getByTestId('canvas-kernel')).toHaveStyle({ background: '#16283F' });
  }
);
