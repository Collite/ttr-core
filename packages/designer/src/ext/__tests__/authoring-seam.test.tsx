// SPDX-License-Identifier: Apache-2.0
// FO-P0.S4.T5 — the authoring injection seam, end-to-end within the core. Proves: the open build
// (nothing registered) stays edit-off; a commercial build that registers a granting loader flips
// edit-on; a refusing loader (null) or a throwing loader (fail-closed) stays edit-off; the active
// data source is handed to the loader.
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import {
  registerAuthoringLoader,
  getAuthoringLoader,
  __resetAuthoringLoader,
  type AuthoringLoader,
} from '../authoring-registry.js';
import { useAuthoringContext } from '../use-authoring-context.js';
import type { ModelDataSource } from '../../data/model-data-source.js';
import type { ShellEditContext } from '../../shell/edit-context.js';

afterEach(() => {
  cleanup();
  __resetAuthoringLoader();
});

const fakeDs = (): ModelDataSource => ({}) as ModelDataSource;
const fakeCtx = (): ShellEditContext => ({
  editable: true,
  removeNode: vi.fn().mockResolvedValue(true),
  saveNode: vi.fn().mockResolvedValue({ ok: true }),
  renderToolbarActions: () => null,
  renderNodeMenu: () => null,
  renderMissingObjects: () => null,
});

function Probe({ ds }: { ds: ModelDataSource }) {
  const ctx = useAuthoringContext(ds);
  return <div data-testid="probe">{ctx ? 'edit-on' : 'edit-off'}</div>;
}

describe('authoring registry', () => {
  it('starts empty — the open Viewer registers nothing (FO-21)', () => {
    expect(getAuthoringLoader()).toBeNull();
  });

  it('registers and returns a loader', () => {
    const loader: AuthoringLoader = vi.fn().mockResolvedValue(null);
    registerAuthoringLoader(loader);
    expect(getAuthoringLoader()).toBe(loader);
  });
});

describe('useAuthoringContext — the injection seam', () => {
  it('is edit-off with no loader registered (open build)', () => {
    render(<Probe ds={fakeDs()} />);
    expect(screen.getByTestId('probe')).toHaveTextContent('edit-off');
  });

  it('flips edit-on when a granting loader resolves a context (commercial build)', async () => {
    registerAuthoringLoader(vi.fn().mockResolvedValue(fakeCtx()));
    render(<Probe ds={fakeDs()} />);
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('edit-on'));
  });

  it('stays edit-off when the loader refuses (returns null)', async () => {
    const loader = vi.fn().mockResolvedValue(null);
    registerAuthoringLoader(loader);
    render(<Probe ds={fakeDs()} />);
    await waitFor(() => expect(loader).toHaveBeenCalled());
    expect(screen.getByTestId('probe')).toHaveTextContent('edit-off');
  });

  it('fails closed (edit-off) when the loader throws', async () => {
    const loader = vi.fn().mockRejectedValue(new Error('boom'));
    registerAuthoringLoader(loader);
    render(<Probe ds={fakeDs()} />);
    await waitFor(() => expect(loader).toHaveBeenCalled());
    expect(screen.getByTestId('probe')).toHaveTextContent('edit-off');
  });

  it('hands the active data source to the loader', async () => {
    const loader = vi.fn().mockResolvedValue(null);
    registerAuthoringLoader(loader);
    const ds = fakeDs();
    render(<Probe ds={ds} />);
    await waitFor(() => expect(loader).toHaveBeenCalledWith({ dataSource: ds }));
  });
});

it('drops the old context synchronously when a new loader or source is pending', async () => {
  const original = fakeCtx(), ds = fakeDs(), renders: Array<ShellEditContext | null> = [];
  registerAuthoringLoader(async () => original);
  function Observed({ source }: { source: ModelDataSource }) {
    const context = useAuthoringContext(source);
    renders.push(context);
    return <div>{context ? 'on' : 'off'}</div>;
  }
  const view = render(<Observed source={ds} />);
  await waitFor(() => expect(renders.at(-1)).toBe(original));
  registerAuthoringLoader(() => new Promise(() => {}));
  renders.length = 0;
  view.rerender(<Observed source={ds} />);
  expect(renders.length).toBeGreaterThan(0);
  expect(renders.every(context => context === null)).toBe(true);
});
