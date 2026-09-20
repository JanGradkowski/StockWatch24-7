/* Browser checks use real rendered templates and production JS/CSS with deterministic API fixtures.
 * Generate templates with NamedWatchlistIntegrationTest -Ddesign.preview.capture=true first. */
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const repo = path.resolve(__dirname, '..');
const previews = path.join(repo, 'target/design-preview');
const output = path.join(repo, 'target/watchlist-browser');
fs.mkdirSync(output, { recursive: true });
function template(prefix, containing = '') {
  const files = fs.readdirSync(previews).filter(f => f.startsWith(prefix) && f.endsWith('.html') && (!containing || fs.readFileSync(path.join(previews, f), 'utf8').includes(containing)));
  files.sort((a, b) => fs.statSync(path.join(previews, b)).mtimeMs - fs.statSync(path.join(previews, a)).mtimeMs);
  assert.ok(files.length, `Missing rendered ${prefix} fixture; run NamedWatchlistIntegrationTest first.`);
  return fs.readFileSync(path.join(previews, files[0]), 'utf8').replace(/data-selected-id="\d+"/, 'data-selected-id="1"');
}
const instruments = [
  { symbol: 'AAPL', unreadSignalCount: 1, companyName: 'Apple Inc.', exchange: 'NASDAQ', currency: 'USD', monitoring: { ruleCount: 2, intervalLabels: ['Daily'], familyLabels: ['Candlestick'], tradeSignals: ['BUY', 'SELL'], unreadSignalCount: 1, representativeAlertId: 10 } },
  { symbol: 'MSFT', unreadSignalCount: 9, companyName: 'Microsoft Corporation', exchange: 'NASDAQ', currency: 'USD', monitoring: null },
];
instruments[0].signals = ['BUY', 'SELL'].map(direction => ({ type: 'CANDLESTICK', interval: 'DAILY', direction }));
instruments[0].signals.push({ type: 'CANDLESTICK', interval: 'WEEKLY', direction: 'BUY' },
  { type: 'HARMONIC_FORMATION', interval: 'MONTHLY', direction: 'SELL' });
instruments[1].signals = ['CONGRESS', 'INSIDER'].map(type => ({ type, interval: 'DAILY', direction: 'ANY' }));

(async () => {
  const browser = await chromium.launch({ channel: process.env.PW_BROWSER || (process.platform === 'win32' ? 'msedge' : 'chromium'), headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    page.setDefaultTimeout(15000);
    const errors = [], mutations = [];
    page.on('pageerror', error => { errors.push(error.message); console.error('Page error:', error.message); });
    let lists = [
      { id: 1, name: 'US growth', description: 'Technology and long-term opportunities', instruments: 2, monitored: 1, unread: 1, pinned: true },
      { id: 2, name: 'European leaders', description: 'Established businesses across Europe', instruments: 40, monitored: 30, unread: 4, pinned: false },
      { id: 3, name: 'Dividend research', description: '', instruments: 18, monitored: 0, unread: 0, pinned: false },
    ];
    const settings = new Map([[1, [{ type: 'CANDLESTICK', interval: 'DAILY', direction: 'BUY', watch: true, email: true }]]]);
    const allowedTypes = new Map();
    const rawCatalog = JSON.parse(fs.readFileSync(path.join(repo, 'src/main/resources/market/watchlist-indexes.json'), 'utf8'));
    const catalog = (Array.isArray(rawCatalog) ? rawCatalog : rawCatalog.indexes).map(i => ({ ...i, count: i.constituents.length, constituents: undefined }));
    await page.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      const pathname = url.pathname;
      const json = body => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
      if (url.hostname !== 'watchlist.test') return route.abort();
      if (pathname.startsWith('/api/')) {
        if (request.method() !== 'GET') mutations.push({ pathname, query: url.search, method: request.method(), body: request.postDataJSON() });
        if (pathname === '/api/notifications/read-all') {
          if (url.searchParams.get('watchlistId') === '1') {
            lists = lists.map(list => list.id === 1 ? { ...list, unread: 0 } : list);
            instruments.forEach(item => { item.unreadSignalCount = 0; });
          }
          return json({ updated: 10 });
        }
        if (pathname === '/api/watchlists/indexes') return json(catalog);
        if (pathname === '/api/stocks/search') return json([{ symbol: '^GSPC', name: 'S&P 500', region: 'United States' }]);
        if (pathname.startsWith('/api/watchlists/memberships/')) return json([1]);
        if (pathname.endsWith('/members')) return json({ items: instruments, total: 2, page: 0, pageSize: 50 });
        if (pathname.endsWith('/settings')) return json({ selections: settings.get(Number(pathname.split('/')[3])) || [], allowedTypes: allowedTypes.get(Number(pathname.split('/')[3])), followCounts: { CANDLESTICK: 1 }, configured: true, mixed: false });
        if (pathname === '/api/watchlists/save') {
          const body = request.postDataJSON(), id = body.id || 4;
          settings.set(id, body.selections);
          allowedTypes.set(id, body.allowedTypes);
          const saved = { id, name: body.name, description: body.description, pinned: body.pinned, instruments: body.id ? 2 : 503, monitored: 1, unread: 0 };
          lists = lists.filter(l => l.id !== id).concat(saved);
          return json({ id, importId: body.applyMonitoring ? 9 : null });
        }
        if (pathname.endsWith('/imports/preview')) return json({ uniqueCount: 503, duplicates: 1, alreadyPresent: 0, newlyMonitored: 0, capacityRemaining: 300, allowed: true, warning: 'Adds a dated snapshot. Index membership will not update automatically.' });
        if (/\/watchlists\/\d+\/imports$/.test(pathname)) return json({ id: 9 });
        if (pathname === '/api/watchlists/imports/9') return json({ id: 9, total: 503, completed: 503, failed: 0, pending: 0, errors: [] });
        if (pathname === '/api/watchlists/imports') return json([{ id: 9, total: 503, completed: 503, failed: 0, pending: 0, errors: [] }]);
        if (pathname === '/api/watchlists') {
          if (request.method() === 'POST') { lists.push({ id: 4, ...request.postDataJSON(), instruments: 503, monitored: 0, unread: 0 }); return json({ id: 4 }); }
          return json(lists);
        }
        return json({});
      }
      if (pathname === '/watchlists/1') return route.fulfill({ contentType: 'text/html', body: template('_watchlists_') });
      if (pathname === '/home') return route.fulfill({ contentType: 'text/html', body: template('_home-') });
      if (pathname === '/signals') return route.fulfill({ contentType: 'text/html', body: template('_signals-', url.searchParams.has('watchlistId') ? 'name="signalKeys"' : 'Mark every notification in your account') });
      const relative = pathname.replace(/-[a-f0-9]{32}(?=\.(js|css)$)/, '');
      const file = path.join(repo, 'src/main/resources/static', relative);
      if (file.startsWith(path.join(repo, 'src/main/resources/static')) && fs.existsSync(file) && fs.statSync(file).isFile()) {
        const contentType = file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'image/svg+xml';
        return route.fulfill({ contentType, body: fs.readFileSync(file) });
      }
      return route.fulfill({ status: 404, body: '' });
    });
    await page.goto('http://watchlist.test/watchlists/1');
    await page.locator('.wl-table').waitFor();
    assert.equal(await page.locator('.wl-accordion').count(), 0);
    assert.equal(await page.locator('.wl-overview').count(), 1);
    assert.equal(await page.getByRole('link', { name: 'View all signals', exact: true }).getAttribute('href'), '/signals?watchlistId=1');
    assert.equal(await page.getByRole('link', { name: 'AAPL', exact: true }).getAttribute('href'), '/signals?watchlistId=1&ticker=AAPL');
    assert.equal(await page.locator('.wl-table tbody tr').count(), 2);
    const searchBounds = await page.getByRole('searchbox', { name: 'Search US growth', exact: true }).boundingBox();
    const filterBounds = await page.getByLabel('Instrument type', { exact: true }).boundingBox();
    assert.ok(Math.abs(searchBounds.y - filterBounds.y) < 2, 'Search and instrument filter align vertically');
    assert.ok(Math.abs(searchBounds.height - filterBounds.height) < 2, 'Filter controls have matching heights');
    assert.equal(await page.locator('.wl-table .wl-follow-group').count(), 5);
    assert.equal(await page.locator('.wl-table .wl-unread-badge.has-unread').count(), 2);
    assert.ok((await page.locator('.wl-table tbody tr').nth(1).boundingBox()).height < 135, 'Two follows use a compact row');
    assert.equal(await page.locator('[data-watchlist-shortcuts] a').count(), 3, 'Watchlists section shows its shortcuts');
    await page.screenshot({ path: path.join(output, 'watchlists-desktop.png'), fullPage: true });

    await page.getByRole('button', { name: 'Create watchlist', exact: true }).click();
    const dialog = page.locator('dialog.wl-dialog[open]:not(.wl-index-dialog)');
    await dialog.getByLabel('Name', { exact: true }).fill('US index research');
    const indexDialog = page.locator('dialog.wl-index-dialog[open]');
    await dialog.getByRole('button', { name: 'Europe', exact: true }).click();
    await dialog.getByRole('button', { name: 'Germany', exact: true }).click();
    assert.equal(await dialog.getByRole('checkbox', { name: 'DAX 40', exact: true }).count(), 1);
    assert.equal(await dialog.getByRole('checkbox', { name: 'S&P 500', exact: true }).count(), 0);
    await dialog.getByRole('checkbox', { name: 'DAX 40', exact: true }).check();
    await page.keyboard.press('Escape');
    await indexDialog.waitFor({ state: 'detached' });
    // The native dialog loses [open] before its queued close event restores the checkbox.
    await page.waitForFunction(() => document.querySelector('input[aria-label="DAX 40"]')?.checked === false);
    assert.equal(await dialog.getByRole('checkbox', { name: 'DAX 40', exact: true }).isChecked(), false, 'Cancelling restores the checkbox');
    assert.equal(await dialog.evaluate(el => el.contains(document.activeElement)), true, 'Focus returns to the editor');
    await dialog.getByRole('button', { name: 'North America', exact: true }).click();
    assert.equal(await dialog.getByRole('checkbox', { name: 'DAX 40', exact: true }).count(), 0);
    await dialog.getByRole('button', { name: 'United States', exact: true }).click();
    assert.equal(await dialog.locator('.wl-index-option').count(), 3, 'All available indexes for the country are shown');
    await dialog.getByRole('checkbox', { name: 'S&P 500', exact: true }).check();
    await indexDialog.getByRole('radio', { name: 'Both', exact: false }).check();
    await page.screenshot({ path: path.join(output, 'watchlist-index-choice-desktop.png'), fullPage: true });
    await indexDialog.getByRole('button', { name: 'Confirm selection' }).click();
    assert.ok(await dialog.locator('.wl-selection-summary').textContent().then(text => text.includes('Both')));
    await dialog.getByRole('button', { name: 'Change S&P 500 selection' }).click();
    await indexDialog.getByRole('radio', { name: 'All stocks in the index', exact: false }).check();
    await indexDialog.getByRole('button', { name: 'Confirm selection' }).click();
    assert.equal(mutations.length, 0, 'Choosing and changing an index only updates the draft');
    await dialog.getByLabel('Watch Candlestick buy daily', { exact: true }).check();
    await dialog.getByLabel('Enable email notifications', { exact: true }).check();
    await dialog.getByLabel('Watch Automated technical outlook weekly', { exact: true }).check();
    await dialog.locator('.wl-editor-instruments, .wl-editor-settings').evaluateAll(panes => panes.forEach(pane => { pane.scrollTop = 0; }));
    await page.screenshot({ path: path.join(output, 'watchlist-signal-settings-desktop.png'), fullPage: true });
    await dialog.getByRole('button', { name: 'Review changes' }).click();
    await dialog.getByRole('button', { name: 'Save watchlist changes' }).waitFor();
    const editorBounds = await dialog.boundingBox();
    assert.ok(Math.abs(editorBounds.x - (1440 - editorBounds.width) / 2) < 2, 'Desktop dialog is centered');
    assert.ok(editorBounds.width > editorBounds.height * 1.3, 'Editor has a wide rectangular layout');
    assert.equal(mutations.length, 1, 'Preview must not create a list or enqueue an import');
    await page.screenshot({ path: path.join(output, 'watchlist-create-desktop.png'), fullPage: true });
    await dialog.getByRole('button', { name: 'Save watchlist changes' }).click();
    await page.getByText('503 of 503 updated', { exact: false }).waitFor();
    assert.deepEqual(mutations.map(m => m.pathname), ['/api/watchlists/imports/preview', '/api/watchlists/save']);
    assert.equal(mutations[1].body.selections.filter(v => v.watch).length, 2);
    assert.equal(mutations[1].body.selections.filter(v => v.email).length, 23);
    assert.deepEqual(mutations[1].body.symbols, [], 'Stocks-only does not include the index instrument');
    assert.deepEqual(mutations[1].body.indexIds, ['sp500']);
    assert.equal(mutations[1].body.allowedTypes.length, 6, 'New lists allow every signal type');
    await page.getByRole('button', { name: 'Close', exact: false }).click();
    await page.getByRole('button', { name: 'Recent imports' }).click();
    await page.getByRole('button', { name: /Import 9:/ }).click();
    await page.getByText('503 of 503 updated', { exact: false }).waitFor();
    await page.getByRole('button', { name: 'Close', exact: false }).click();

    await page.locator('.wl-overview').getByRole('button', { name: 'Add instruments / edit' }).click();
    await dialog.getByLabel('Enable email notifications', { exact: true }).waitFor();
    await dialog.locator('.wl-signal-settings:not([disabled])').waitFor();
    assert.equal(await dialog.getByLabel('Watch Candlestick buy daily', { exact: true }).isChecked(), true);
    await dialog.getByLabel('Enable email notifications', { exact: true }).uncheck();
    await dialog.getByRole('button', { name: 'Save watchlist', exact: true }).click();
    await dialog.waitFor({ state: 'detached' });
    assert.equal(mutations.at(-1).body.applyMonitoring, false, 'Email-only edit preserves individual follows');
    assert.equal(mutations.at(-1).body.settingsChanged, true);
    await page.locator('.wl-overview').getByRole('button', { name: 'Add instruments / edit' }).click();
    await dialog.getByLabel('Watch Candlestick buy daily', { exact: true }).waitFor();
    await dialog.locator('.wl-signal-settings:not([disabled])').waitFor();
    assert.equal(await dialog.getByLabel('Enable email notifications', { exact: true }).isChecked(), false, 'Email edit reloads');
    await dialog.getByLabel('Allow Candlestick', { exact: true }).uncheck();
    assert.equal(await dialog.getByLabel('Watch Candlestick buy daily', { exact: true }).isDisabled(), true);
    await dialog.getByRole('button', { name: 'Review changes', exact: true }).click();
    assert.match(await dialog.locator('.wl-editor-preview').textContent(), /Remove 1 existing follows/);
    await dialog.getByRole('button', { name: 'Save watchlist changes', exact: true }).click();
    await dialog.waitFor({ state: 'detached' });
    assert.equal(mutations.at(-1).body.applyMonitoring, false, 'Restrictions preserve unrelated individual follows');
    assert.equal(mutations.at(-1).body.allowedTypes.includes('CANDLESTICK'), false);
    await page.locator('.wl-overview').getByRole('button', { name: 'Add instruments / edit' }).click();
    await dialog.locator('.wl-signal-settings:not([disabled])').waitFor();
    assert.equal(await dialog.getByLabel('Allow Candlestick', { exact: true }).isChecked(), false, 'Saved restrictions reload');
    await page.setViewportSize({ width: 390, height: 844 });
    await dialog.getByRole('heading', { name: 'Technical signals', exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(output, 'watchlist-signal-settings-mobile.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Settings editor fits mobile width');
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.setViewportSize({ width: 1440, height: 1000 });

    await page.evaluate(() => { window.picked = window.StockWatchLists.choose('AAPL'); });
    await dialog.getByRole('button', { name: 'Apply changes', exact: true }).waitFor();
    await dialog.locator('input[value="2"]').check();
    assert.equal(await dialog.locator('input[value="1"]').isDisabled(), false);
    assert.equal(await dialog.locator('input[value="1"]').isChecked(), true);
    await dialog.locator('input[value="1"]').uncheck();
    await page.screenshot({ path: path.join(output, 'stock-watchlist-picker.png'), fullPage: true });
    await dialog.getByRole('button', { name: 'Apply changes', exact: true }).click();
    assert.deepEqual(await page.evaluate(async () => { const result = await window.picked; result.watchlistIds.sort(); return result; }), { watchlistIds: [2], newWatchlistName: null });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(output, 'watchlists-mobile.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'No page-wide horizontal overflow on mobile');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto('http://watchlist.test/home');
    const accountSignals = page.getByRole('link', { name: 'All signals', exact: true });
    assert.equal(await accountSignals.getAttribute('href'), '/signals', 'Account-wide link has no ticker or watchlist filter');
    const greetingBounds = await page.locator('.app-hero-copy').boundingBox();
    const allSignalsBounds = await accountSignals.boundingBox();
    const statusBounds = await page.locator('.hero-status').boundingBox();
    assert.ok(allSignalsBounds.x >= greetingBounds.x + greetingBounds.width && allSignalsBounds.x < statusBounds.x, 'All signals sits between the greeting and alert status');
    await page.locator('.wl-accordion').first().waitFor();
    assert.equal(await page.locator('[data-watchlist-shortcuts]').count(), 0, 'Watchlist shortcuts disappear outside the Watchlists section');
    await page.locator('[data-nav-section="alerts"]').click();
    assert.equal(await page.locator('[data-watchlist-shortcuts]').count(), 0, 'Ticker alerts do not retain watchlist shortcuts');
    const activityRead = page.locator('#latestTickerNotifications [data-notifications-read-all]');
    assert.equal(await activityRead.isVisible(), true);
    assert.equal(await activityRead.getAttribute('data-read-scope'), 'ACTIVITY');
    await Promise.all([page.waitForEvent('load'), activityRead.click()]);
    assert.equal(mutations.at(-1).pathname, '/api/notifications/read-all');
    assert.equal(mutations.at(-1).query, '?scope=ACTIVITY');
    await page.locator('[data-nav-section="home"]').click();
    await page.locator('.wl-accordion').first().waitFor();
    await page.locator('.wl-accordion[data-id="1"] summary').click();
    await page.locator('.wl-table').waitFor();
    await page.screenshot({ path: path.join(output, 'homepage-watchlists.png'), fullPage: true });
    const notification = page.locator('.latest-signal-grid.latest-signal-row').first();
    if (await notification.count()) {
      const company = await notification.locator('.latest-company').boundingBox();
      const arrow = await notification.locator('.latest-signal-arrow').boundingBox();
      assert.ok(arrow.y < company.y + company.height && arrow.y + arrow.height > company.y, `Notification columns stay in one row: ${JSON.stringify({company, arrow})}`);
    }
    await page.evaluate(() => document.documentElement.dataset.theme = 'light');
    await page.screenshot({ path: path.join(output, 'homepage-watchlists-light.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    const mobileSearch = await page.getByRole('searchbox', { name: 'Search US growth', exact: true }).boundingBox();
    const mobileFilter = await page.getByLabel('Instrument type', { exact: true }).boundingBox();
    assert.ok(mobileFilter.y >= mobileSearch.y + mobileSearch.height, 'Filters stack on mobile');
    await page.screenshot({ path: path.join(output, 'homepage-watchlists-mobile.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Homepage fits mobile width');
    assert.ok(await page.locator('.wl-table-wrap').first().evaluate(el => el.scrollWidth <= el.clientWidth), 'Mobile ticker rows show all badges and actions without sideways scrolling');
    await page.getByRole('link', { name: 'Open watchlist', exact: true }).first().click();
    await page.locator('.wl-overview .wl-table').waitFor();
    assert.equal(await page.getByRole('link', { name: 'AAPL', exact: true }).getAttribute('href'), '/signals?watchlistId=1&ticker=AAPL');
    await Promise.all([page.waitForEvent('load'), page.locator('.wl-overview [data-notifications-read-all]').click()]);
    await page.locator('.wl-overview .wl-table').waitFor();
    assert.equal(mutations.at(-1).query, '?scope=ALL&watchlistId=1');
    assert.equal(await page.locator('.wl-unread-badge.has-unread').count(), 0, 'Watchlist unread badges update after the action');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto('http://watchlist.test/signals?watchlistId=1');
    assert.equal(await page.locator('.signal-archive-record').count(), 3, 'Technical and both activity types share one history');
    assert.equal(await page.locator('a[href*="/activity-signals/"][href*="returnTo="]').count(), 2, 'Both activity details preserve the return filter');
    await page.getByText('Select this page', { exact: true }).click();
    assert.equal(await page.locator('[data-selected-count]').textContent(), '3');
    await page.getByRole('button', { name: 'Delete selected', exact: true }).click();
    await page.locator('[data-delete-dialog][open]').waitFor();
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.screenshot({ path: path.join(output, 'watchlist-mixed-signals-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(output, 'watchlist-mixed-signals-mobile.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Mixed archive fits mobile width');
    const archiveRead = page.locator('[data-notifications-read-all]');
    const archiveList = await archiveRead.getAttribute('data-read-watchlist');
    await Promise.all([page.waitForEvent('load'), archiveRead.click()]);
    assert.equal(mutations.at(-1).query, `?scope=ALL&watchlistId=${archiveList}`);
    await page.goto('http://watchlist.test/signals?ticker=AAPL&state=unread');
    assert.equal(await page.locator('[data-notifications-read-all]').getAttribute('data-read-watchlist'), null);
    await Promise.all([page.waitForEvent('load'), page.locator('[data-notifications-read-all]').click()]);
    assert.equal(mutations.at(-1).query, '?scope=ALL', 'Account action ignores current ticker/state/page filters');
    assert.ok(page.url().includes('ticker=AAPL&state=unread'), 'Refreshing preserves the current archive URL');
    lists = [];
    await page.goto('http://watchlist.test/watchlists/1');
    await page.getByRole('heading', { name: 'Create your first watchlist' }).waitFor();
    await page.evaluate(() => { window.picked = window.StockWatchLists.choose('AAPL'); });
    await dialog.getByText('You have no watchlists yet.', { exact: false }).waitFor();
    await dialog.getByPlaceholder('Watchlist name').fill('My first list');
    await page.screenshot({ path: path.join(output, 'first-watchlist-mobile.png'), fullPage: true });
    await page.keyboard.press('Escape');
    assert.equal(await page.evaluate(() => window.picked), null);

    for (const [mode, expectedSymbols, expectedIndexes] of [
      ['Index only', ['^GSPC'], []], ['Both', ['^GSPC'], ['sp500']]
    ]) {
      await page.locator('[data-create-watchlist]').click();
      await dialog.getByLabel('Name', { exact: true }).fill(`${mode} research`);
      await dialog.getByRole('button', { name: 'North America', exact: true }).click();
      await dialog.getByRole('button', { name: 'United States', exact: true }).click();
      await dialog.getByRole('checkbox', { name: 'S&P 500', exact: true }).check();
      await indexDialog.getByRole('radio', { name: mode, exact: false }).check();
      assert.ok(await indexDialog.evaluate(el => el.scrollWidth <= el.clientWidth), 'Index popup fits mobile width');
      await page.screenshot({ path: path.join(output, `index-choice-${mode.toLowerCase().replaceAll(' ', '-')}-mobile.png`), fullPage: true });
      await indexDialog.getByRole('button', { name: 'Confirm selection' }).click();
      await dialog.getByRole('button', { name: 'Europe', exact: true }).click();
      assert.ok((await dialog.locator('.wl-selection-summary').textContent()).includes('S&P 500'), 'Selections survive continent changes');
      await dialog.getByRole('button', { name: 'Review changes' }).click();
      await dialog.getByRole('button', { name: 'Save watchlist changes' }).waitFor();
      assert.deepEqual(mutations.at(-1).body.symbols, expectedSymbols);
      assert.deepEqual(mutations.at(-1).body.indexIds, expectedIndexes);
      await page.screenshot({ path: path.join(output, 'watchlist-index-browser-mobile.png'), fullPage: true });
      // Removing an index choice must not remove a separately selected ticker with the same symbol.
      await dialog.getByLabel('Search individual tickers').fill('GSPC');
      await dialog.locator('.wl-results button').click();
      await dialog.getByRole('button', { name: 'Remove S&P 500', exact: true }).click();
      await dialog.getByRole('button', { name: 'Review changes' }).click();
      await dialog.getByRole('button', { name: 'Save watchlist changes' }).waitFor();
      assert.deepEqual(mutations.at(-1).body.symbols, ['^GSPC']);
      assert.deepEqual(mutations.at(-1).body.indexIds, []);
      await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    }
    assert.deepEqual(errors, [], 'No browser JavaScript errors');
    console.log(`Watchlist browser checks passed. Screenshots: ${output}`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
