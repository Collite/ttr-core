// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
/** Host interactions on the installed package, with a controlled router and an offset mount. */
export async function checkEmbeddedPage(page) {
  const picker = page.getByTestId('skin-picker');
  await picker.selectOption('md.er-dialect');
  await page.locator('[data-testid="catalog-item"][data-ref="er"]').click();
  await page.getByRole('tab', { selected: true }).filter({ hasText: 'er' }).waitFor();
  await page.locator('[role="tab"][title="md"]').click();
  await page.waitForFunction(
    () => document.querySelector('[data-testid="skin-picker"]')?.value === 'md.er-dialect'
  );
  await picker.selectOption('md.star-glyph');
  await page.getByTestId('star-cube').first().waitFor();
  const root = await page.locator('.ttr-designer').boundingBox();
  const tab = await page.locator('[role="tab"][title="md"]').boundingBox();
  const point = { x: Math.round(tab.x + tab.width / 2), y: Math.round(tab.y + tab.height / 2) };
  await page.mouse.click(point.x, point.y, { button: 'right' });
  const tabMenu = await page.getByTestId('tab-menu').boundingBox();
  assert.ok(
    Math.abs(tabMenu.x - point.x) <= 4 && Math.abs(tabMenu.y - point.y) <= 4,
    'Tab menu must meet the pointer'
  );
  await page.keyboard.press('Escape');
  const node = page.locator('.react-flow__node').first();
  await node.evaluate(
    (element, point) =>
      element.dispatchEvent(
        new MouseEvent('contextmenu', { bubbles: true, clientX: point.x, clientY: point.y })
      ),
    { x: root.x + root.width - 2, y: root.y + root.height - 2 }
  );
  const menu = await page.getByTestId('node-context-menu').boundingBox();
  assert.ok(
    menu.x >= root.x &&
      menu.y >= root.y &&
      menu.x + menu.width <= root.x + root.width + 1 &&
      menu.y + menu.height <= root.y + root.height + 1,
    'Node menu must stay inside the page'
  );
  await picker.click();
  const reset = await page.evaluate(() => {
    const header = document.querySelector('[data-testid="studio-header"]');
    return {
      border: getComputedStyle(header).borderBottomWidth,
      style: getComputedStyle(header).borderBottomStyle,
    };
  });
  assert.equal(reset.style, 'solid');
  assert.ok(parseFloat(reset.border) > 0);
  await page.locator('.react-flow__pane').click({ position: { x: 4, y: 4 } });
  assert.equal(await page.locator('.ttr-designer').evaluate(element => element === document.activeElement), true);
  await page.keyboard.press('Control+k');
  assert.equal(await page.getByTestId('cmdk-input').count(), 1);
  await page.keyboard.press('Escape');
  await page.evaluate(() => window.setPageTheme('light'));
  await page.waitForFunction(() => getComputedStyle(document.querySelector('[data-testid="canvas-kernel"]')).backgroundColor === 'rgb(238, 244, 251)');
  await page.evaluate(() => window.setPageTheme('dark'));
  await page.waitForFunction(() => getComputedStyle(document.querySelector('[data-testid="canvas-kernel"]')).backgroundColor === 'rgb(22, 40, 63)');
  await page.locator('#host-button').focus();
  await page.keyboard.press('Control+k');
  assert.equal(await page.getByTestId('cmdk-input').count(), 0);
  return {
    controlledNotation: 'preserved',
    tabMenu: 'at pointer',
    nodeMenu: 'clamped to page',
    borderReset: reset,
    emptyCanvasShortcut: 'one palette',
    lightStarBackground: '#EEF4FB',
    darkBackground: '#16283F',
    hostShortcut: 'untouched',
  };
}
