import { test, expect } from '@playwright/test';

const pageErrors = new WeakMap();
test.beforeEach(async ({ page }) => {
  const errors = [];
  pageErrors.set(page, errors);
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await expect(page.locator('search-card input')).toBeVisible();
});

test.afterEach(async ({ page }) => { expect(pageErrors.get(page)).toEqual([]); });

async function search(page, text = 'sensor.bulk') {
  await page.locator('search-card input').fill(text);
  await expect(page.locator('#results')).toBeVisible();
  await expect.poll(() => page.locator('#results').evaluate(node => node.matches(':popover-open'))).toBe(true);
}

test('overlays existing content without resizing the card and reaches all 250 matches', async ({ page }) => {
  const before = await page.locator('ha-card').boundingBox();
  const following = await page.locator('#following').boundingBox();
  await search(page);
  await expect(page.locator('#count')).toHaveText('250 results');
  expect((await page.locator('ha-card').boundingBox()).height).toBe(before.height);
  expect((await page.locator('#following').boundingBox()).y).toBe(following.y);
  await expect(page.locator('.entity-row')).toHaveCount(100);
  const list = page.locator('#results');
  for (const count of [200, 250]) {
    await list.evaluate(node => { node.scrollTop = node.scrollHeight; });
    await expect(page.locator('.entity-row')).toHaveCount(count);
  }
  await page.getByText('sensor.bulk_249', { exact: true }).click();
  expect(await page.evaluate(() => window.openedEntities)).toEqual(['sensor.bulk_249']);
  await expect(list).toBeHidden();
});

test('opening near viewport bottom scrolls the shadow dashboard and fits the dropdown', async ({ page }) => {
  await page.evaluate(() => window.mount({ top: window.innerHeight - 100 }));
  await search(page);
  await expect.poll(() => page.locator('#scroller').evaluate(node => node.scrollTop)).toBeGreaterThan(0);
  const input = await page.locator('ha-input').boundingBox();
  const popup = await page.locator('#results').boundingBox();
  expect(popup.y).toBeCloseTo(input.y + input.height + 4, 0);
  expect(popup.y + popup.height).toBeLessThanOrEqual(page.viewportSize().height - 7);
  expect(popup.width).toBeCloseTo(input.width, 0);
  // Real click proves it is above the transformed/clipped card and higher-z-index sibling.
  await page.getByText('sensor.bulk_000', { exact: true }).click();
  expect(await page.evaluate(() => window.openedEntities)).toEqual(['sensor.bulk_000']);
});

test('query reset, keyboard selection, Escape, outside tap, and disconnect close the popup', async ({ page }) => {
  await search(page);
  await page.locator('#results').evaluate(node => { node.scrollTop = node.scrollHeight; });
  await expect(page.locator('.entity-row')).toHaveCount(200);
  await search(page, 'sensor.bulk_0');
  await expect(page.locator('.entity-row')).toHaveCount(100);
  await expect.poll(() => page.locator('#results').evaluate(node => node.scrollTop)).toBe(0);
  await page.locator('search-card input').press('ArrowDown');
  await expect(page.locator('.entity-row').first()).toBeFocused();
  await page.keyboard.press('Enter');
  expect(await page.evaluate(() => window.openedEntities)).toEqual(['sensor.bulk_000']);
  await page.locator('search-card input').focus();
  await expect(page.locator('#results')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('#results')).toBeHidden();
  await page.locator('search-card input').press('ArrowDown');
  await expect(page.locator('#results')).toBeVisible();
  await page.mouse.click(2, 2);
  await expect(page.locator('#results')).toBeHidden();
  await search(page);
  await page.evaluate(() => window.card.remove());
  expect(await page.evaluate(() => document.querySelectorAll(':popover-open').length)).toBe(0);
});

test('viewport resize keeps results reachable and empty/unmatched queries dismiss', async ({ page }) => {
  await search(page);
  await page.setViewportSize({ width: 320, height: 360 });
  await expect.poll(async () => {
    const box = await page.locator('#results').boundingBox();
    return box ? box.y + box.height : Infinity;
  }).toBeLessThanOrEqual(353);
  await page.locator('search-card input').fill('[');
  await expect(page.locator('#results')).toBeHidden();
  await search(page);
  await page.locator('search-card input').fill('');
  await expect(page.locator('#results')).toBeHidden();
});

test('max_results sizes the initial popup without truncating matches', async ({ page }) => {
  await page.evaluate(() => window.card.setConfig({ max_results: 2 }));
  await search(page);
  const popup = page.locator('#results');
  await expect.poll(async () => (await popup.boundingBox()).height).toBeLessThan(190);
  const small = (await popup.boundingBox()).height;
  expect(small).toBeGreaterThan(120);
  await expect(page.locator('#count')).toHaveText('250 results');
  await expect(page.locator('.entity-row')).toHaveCount(100);
  expect(await popup.evaluate(node => node.scrollHeight > node.clientHeight)).toBe(true);

  // Changing the editor/configuration while searching must also resize the popup.
  await page.evaluate(() => window.card.setConfig({ max_results: 5 }));
  await expect.poll(async () => (await popup.boundingBox()).height).toBeGreaterThan(small + 140);
  expect((await popup.boundingBox()).height).toBeLessThan(small + 190);
  await expect(page.locator('#count')).toHaveText('250 results');
});

test('device metadata refreshes word matches and isolates failures and old connections', async ({ page }) => {
  await page.evaluate(async () => {
    const card = window.card;
    const states = {
      'sensor.opaque': { attributes: { friendly_name: 'temperature' } },
      'sensor.average': { attributes: { friendly_name: 'Rumpus Average Temperature' } },
    };
    const registry = { entities: [{ ei: 'sensor.opaque', di: 'device' }, { ei: 'sensor.average', lb: ['priority'] }] };
    const devices = [{ id: 'device', name: 'Original', name_by_user: 'Rumpus Motion' }];
    window.metadataConnection = (failed, deviceData = devices) => ({
      sendMessagePromise: async ({ type }) => {
        if (type === failed) throw new Error('Unavailable');
        if (type.includes('entity_registry')) return registry;
        if (type.includes('device_registry')) return deviceData;
        return [{ label_id: 'priority', name: 'search_priority' }];
      },
    });
    window.searchStates = states;
    card._searchValue = 'Rumpus temp';
    card.hass = { states, services: {}, connection: window.metadataConnection() };
  });
  await expect.poll(() => page.evaluate(() => window.card._results.map(r => r.entity_id)))
    .toEqual(['sensor.average', 'sensor.opaque']);
  await page.evaluate(() => {
    window.card.hass = { states: window.searchStates, services: {}, connection: window.metadataConnection('config/device_registry/list') };
  });
  await expect.poll(() => page.evaluate(() => [window.card._results.map(r => r.entity_id), [...window.card._searchPriorityEntityIds]]))
    .toEqual([['sensor.average'], ['sensor.average']]);
  await page.evaluate(() => {
    window.card.hass = { states: window.searchStates, services: {}, connection: window.metadataConnection('config/label_registry/list', [{ id: 'device', name: 'Rumpus Motion' }]) };
  });
  await expect.poll(() => page.evaluate(() => window.card._entityDeviceNames.get('sensor.opaque'))).toBe('Rumpus Motion');
  await page.evaluate(async () => {
    const pending = [];
    window.card.hass = { states: window.searchStates, services: {}, connection: { sendMessagePromise: () => new Promise(resolve => pending.push(resolve)) } };
    window.card.hass = { states: window.searchStates, services: {} };
    pending.forEach(resolve => resolve([]));
    await Promise.resolve();
    await Promise.resolve();
  });
  await expect.poll(() => page.evaluate(() => window.card._entityDeviceNames.size)).toBe(0);
  await expect.poll(() => page.evaluate(() => window.card._results.map(r => r.entity_id))).toEqual(['sensor.average']);
});
