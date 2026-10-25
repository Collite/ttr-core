// SPDX-License-Identifier: Apache-2.0
import { useLayoutEffect } from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useCmdKShortcut } from '../CommandPalette.js';
afterEach(cleanup);
it('the command shortcut is bound before the visible page can receive a key', () => {
  const open = vi.fn();
  let prevented = false;
  function Harness() {
    useCmdKShortcut(open);
    useLayoutEffect(() => {
      const event = new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, cancelable: true });
      window.dispatchEvent(event);
      prevented = event.defaultPrevented;
    }, []);
    return null;
  }
  render(<Harness />);
  expect(prevented).toBe(true);
  expect(open).toHaveBeenCalledOnce();
});
