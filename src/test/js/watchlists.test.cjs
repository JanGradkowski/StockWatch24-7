const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const { StockWatchI18n } = require('./i18n-stub.cjs');

async function setup(selected = '', management = false) {
  const dom = new JSDOM(`<body><div data-named-watchlists data-selected-id="${selected}"${management ? ' data-management="true"' : ''}></div></body>`,
    { runScripts: 'outside-only', url: 'https://watchlist.test' });
  dom.window.fetch = async url => ({ ok: true, json: async () => url.includes('/members?')
    ? { total: 1, pageSize: 50, items: [{ symbol: 'ABC.L', companyName: 'Example', exchange: 'LSE', currency: 'GBP',
      unreadSignalCount: 3, monitoring: { unreadSignalCount: 99 }, signals: [
        { type: 'CANDLESTICK', direction: 'BUY', interval: 'DAILY' },
        { type: 'HARMONIC_FORMATION', direction: 'SELL', interval: 'WEEKLY' }
      ] }] }
    : [ { id: 1, name: 'Research', description: 'Long-term ideas', instruments: 1, monitored: 1, unread: 3, pinned: true },
        { id: 2, name: 'Other list', instruments: 0, monitored: 0, unread: 0 } ] });
  dom.window.StockWatchI18n = StockWatchI18n;
  for (const file of ['watchlist-picker', 'watchlists']) {
    dom.window.eval(fs.readFileSync(`src/main/resources/static/js/${file}.js`, 'utf8'));
  }
  await new Promise(resolve => setTimeout(resolve, 30));
  return dom;
}

test('opening a selected watchlist shows only its overview with paired follows and scoped signal links', async () => {
  const dom = await setup('1'), doc = dom.window.document;
  assert.equal(doc.querySelectorAll('.wl-overview').length, 1);
  assert.equal(doc.querySelectorAll('details').length, 0);
  assert.ok(!doc.body.textContent.includes('Other list'));
  // One chip per signal type: its direction (only buy or only sell here) and its intervals as letters.
  const candlestick = doc.querySelector('[aria-label="Candlestick: Buy · Daily"]');
  const harmonic = doc.querySelector('[aria-label="Harmonic: Sell · Weekly"]');
  assert.equal(candlestick.querySelector('.wl-dir.is-buy').textContent, 'Buy');
  assert.equal(candlestick.querySelector('.wl-interval').textContent, 'D');
  assert.equal(harmonic.querySelector('.wl-interval').textContent, 'W');
  const row = doc.querySelector('tbody tr');
  assert.equal(row.querySelector('.wl-unread-badge strong').textContent, '3');
  assert.equal(row.querySelector('.wl-unread-badge').getAttribute('href'), '/signals?watchlistId=1&ticker=ABC.L&state=unread');
  const menu = [...row.querySelectorAll('[role="menuitem"]')];
  assert.deepEqual(menu.map(item => item.textContent), ['View signals', 'Open chart', 'Manage follows', 'Remove from this list']);
  assert.equal(menu[1].getAttribute('href'), '/stock/ABC.L');
  assert.equal(row.querySelector('a').getAttribute('href'), '/signals?watchlistId=1&ticker=ABC.L');
  assert.equal([...doc.querySelectorAll('a')].find(a => a.textContent === 'View all signals').getAttribute('href'), '/signals?watchlistId=1');
  dom.window.close();
});

test('home lists each watchlist as one row that opens its own page, without embedding its instruments', async () => {
  const dom = await setup(), doc = dom.window.document;
  const rows = [...doc.querySelectorAll('.wl-home-row')];
  assert.equal(rows.length, 2);
  assert.equal(rows[0].getAttribute('href'), '/watchlists/1');
  assert.equal(doc.querySelectorAll('details, tbody').length, 0, 'the instruments and tools are on the list page');
  dom.window.close();
});

test('selection persists across pages and filters and bulk follows/removal send only selected tickers', async () => {
  const dom = new JSDOM('<body><div data-named-watchlists data-selected-id="1"></div></body>',
    { runScripts: 'outside-only', url: 'https://watchlist.test' });
  const doc = dom.window.document, mutations = [];
  dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new dom.window.Event('close')); };
  dom.window.fetch = async (url, options = {}) => {
    const path = new URL(url, 'https://watchlist.test');
    let value;
    if (path.pathname.endsWith('/preview')) value = { selections: [], mixedKeys: ['CANDLESTICK:DAILY:BUY'], nonStocks: 0 };
    else if (options.method === 'POST') { mutations.push({ url, body: JSON.parse(options.body) }); value = {}; }
    else if (path.pathname.endsWith('/members')) {
      const symbol = path.searchParams.get('page') === '1' ? 'BRK.A' : 'ABC.L';
      value = { total: 2, pageSize: 1, items: [{ symbol, companyName: symbol, signals: [] }] };
    } else value = [{ id: 1, name: 'Research', instruments: 2 }];
    return { ok: true, status: 200, text: async () => JSON.stringify(value), json: async () => value };
  };
  dom.window.StockWatchI18n = StockWatchI18n;
  for (const file of ['watchlist-picker','watchlist-settings','watchlists']) dom.window.eval(fs.readFileSync(`src/main/resources/static/js/${file}.js`, 'utf8'));
  const settle = () => new Promise(resolve => setTimeout(resolve, 35));
  const button = (text, root = doc) => [...root.querySelectorAll('button')].find(b => b.textContent === text);
  await settle();
  assert.equal(doc.querySelector('.wl-bulk-actions').hidden,true,'Selection actions appear only once something is selected');
  assert.equal(button('Manage follows',doc.querySelector('.wl-bulk-actions')).disabled,true);
  doc.querySelector('[aria-label="Select ABC.L"]').click();
  button('Next').click(); await settle();
  doc.querySelector('[aria-label="Select all instruments on this page"]').click();
  const bulk = doc.querySelector('.wl-bulk-actions');
  assert.equal(bulk.querySelector('[role="status"]').textContent,'2 selected');
  assert.equal(bulk.hidden,false);
  assert.equal(new URL(bulk.querySelector('a').href).searchParams.get('ticker'),'ABC.L,BRK.A');
  // A row's own actions act on that row only, whatever else is selected.
  assert.equal(new URL(doc.querySelector('tbody [role="menuitem"]').href).searchParams.get('ticker'),'BRK.A');
  button('Previous').click(); await settle();
  assert.equal(doc.querySelector('[aria-label="Select ABC.L"]').checked,true);
  const group=doc.querySelector('[aria-label="Instrument type"]'); group.value='stocks'; group.dispatchEvent(new dom.window.Event('change')); await settle();
  assert.equal(doc.querySelector('.wl-bulk-actions [role="status"]').textContent,'2 selected');
  button('Manage follows',bulk).click(); await settle();
  const mixed=doc.querySelector('dialog [aria-label="Watch Candlestick buy daily"]');
  assert.equal(mixed.indeterminate,true);
  assert.equal(button('Apply to selected').disabled,true);
  mixed.click(); button('Apply to selected').click(); await settle();
  assert.deepEqual(mutations[0].body.symbols,['ABC.L','BRK.A']);
  assert.deepEqual(mutations[0].body.changes,[{ type:'CANDLESTICK',interval:'DAILY',direction:'BUY',watch:true,email:false }]);
  button('Remove',doc.querySelector('.wl-bulk-actions')).click();
  button('Cancel',doc.querySelector('dialog')).click(); await settle();
  assert.equal(mutations.length,1);
  button('Remove',doc.querySelector('.wl-bulk-actions')).click(); button('Remove selected').click(); await settle();
  assert.deepEqual(mutations[1].body.symbols,['ABC.L','BRK.A']);
  assert.equal(doc.querySelector('.wl-bulk-actions [role="status"]').textContent,'0 selected');
  assert.equal(doc.querySelector('.wl-bulk-actions').hidden,true);
  dom.window.close();
});

function harness(members) {
  const dom = new JSDOM('<body><div data-named-watchlists data-selected-id="1"></div></body>',
    { runScripts: 'outside-only', url: 'https://watchlist.test' });
  const requests = [];
  dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new dom.window.Event('close')); };
  dom.window.fetch = async (url, options = {}) => {
    const path = new URL(url, 'https://watchlist.test');
    requests.push({ path, body: options.body ? JSON.parse(options.body) : null });
    let value;
    if (path.pathname.endsWith('/imports/preview')) value = { uniqueCount: 3, duplicates: 0, alreadyPresent: 0, newlyMonitored: 0,
      capacityRemaining: 290, allowed: true, warning: null, skippedTickerInstruments: 0, congressRemaining: 5, insiderRemaining: 5 };
    else if (path.pathname.endsWith('/indexes')) value = [];
    else if (path.pathname.endsWith('/members')) value = members;
    else value = [{ id: 1, name: 'Research', instruments: 1 }];
    return { ok: true, status: 200, text: async () => JSON.stringify(value), json: async () => value };
  };
  dom.window.StockWatchI18n = StockWatchI18n;
  for (const file of ['watchlist-picker', 'watchlist-settings', 'watchlists']) dom.window.eval(fs.readFileSync(`src/main/resources/static/js/${file}.js`, 'utf8'));
  return { dom, requests };
}

const settle = () => new Promise(resolve => setTimeout(resolve, 35));

test('member rows show the latest stored close, its change, a sparkline and the followed outlook', async () => {
  const { dom, requests } = harness({ total: 2, pageSize: 50, items: [
    { symbol: 'AAPL', companyName: 'Apple Inc.', exchange: 'NASDAQ', currency: 'USD', signals: [],
      quote: { close: 343.91, changePercent: -1.1, closeTimestamp: 1793059200, sparkline: 'M0.0 2.0 L100.0 30.0',
        outlookLabel: 'Moderate buy outlook', outlookTone: 'buy' } },
    { symbol: 'NEW', companyName: 'New listing', exchange: 'NYSE', currency: 'USD', signals: [], quote: null }] });
  await settle();
  const doc = dom.window.document, rows = doc.querySelectorAll('tbody tr');
  assert.deepEqual([...doc.querySelectorAll('thead th')].map(th => th.textContent).slice(1, 4), ['Instrument', 'Last close', 'Daily outlook']);
  const price = rows[0].querySelector('.wl-price-cell');
  assert.equal(price.querySelector('.wl-price strong').textContent, '343.91');
  assert.equal(price.querySelector('.wl-price-change').textContent, '−1.10%');
  assert.ok(price.querySelector('.wl-price-change').classList.contains('is-down'));
  assert.equal(price.querySelector('.wl-spark path').getAttribute('d'), 'M0.0 2.0 L100.0 30.0');
  assert.equal(rows[0].querySelector('.wl-outlook-cell .wl-price-outlook').textContent, 'Moderate buy outlook');
  assert.ok(rows[1].querySelector('.wl-outlook-cell').classList.contains('is-empty'));
  assert.equal(rows[1].querySelector('.wl-price-cell').textContent, 'No daily closes stored yet');

  const sort = doc.querySelector('[aria-label="Sort by"]');
  sort.value = 'loss'; sort.dispatchEvent(new dom.window.Event('change')); await settle();
  assert.equal(requests.filter(request => request.path.pathname === '/api/watchlists/1/members').at(-1).path.searchParams.get('sort'), 'loss');
  dom.window.close();
});

test('pasted tickers are cleaned, de-duplicated and sent with the import preview', async () => {
  const { dom, requests } = harness({ total: 0, pageSize: 50, items: [] });
  await settle();
  const doc = dom.window.document, button = (text, root = doc) => [...root.querySelectorAll('button')].find(b => b.textContent === text);
  button('Add instruments').click(); await settle();
  const dialog = doc.querySelector('dialog.wl-editor');
  const textarea = dialog.querySelector('.wl-paste textarea');
  textarea.value = 'Symbol\n"aapl", MSFT;msft\tbrk.b\n$$$ bad/one';
  button('Add tickers', dialog).click();
  assert.equal(dialog.querySelector('.wl-paste [role="status"]').textContent, '3 tickers added. Not valid tickers: $$$, BAD/ONE');
  assert.equal(textarea.value, '');
  const pastedRow = [...dialog.querySelectorAll('.wl-selection-row')].find(row => row.textContent.includes('3 pasted tickers'));
  assert.ok(pastedRow.textContent.includes('AAPL, MSFT, BRK.B'));
  dialog.querySelector('form').dispatchEvent(new dom.window.Event('submit', { cancelable: true })); await settle();
  const preview = requests.find(request => request.path.pathname.endsWith('/imports/preview'));
  assert.deepEqual(preview.body.symbols, ['AAPL', 'MSFT', 'BRK.B']);
  dom.window.close();
});

test('the Watchlists page shows one card per list with its figures, and the search filters the cards', async () => {
  const dom = new JSDOM(`<body><input data-watchlist-search><span data-watchlist-count></span>
    <div data-named-watchlists data-management="true"></div></body>`, { runScripts: 'outside-only', url: 'https://watchlist.test' });
  dom.window.fetch = async () => ({ ok: true, json: async () => [
    { id: 1, name: 'Research', description: 'Long-term ideas', instruments: 12, monitored: 4, unread: 3, pinned: true },
    { id: 2, name: 'Dividends', description: '', instruments: 0, monitored: 0, unread: 0 }] });
  dom.window.StockWatchI18n = StockWatchI18n;
  for (const file of ['watchlist-picker', 'watchlists']) dom.window.eval(fs.readFileSync(`src/main/resources/static/js/${file}.js`, 'utf8'));
  await settle();
  const doc = dom.window.document, cards = doc.querySelectorAll('.wl-card');
  assert.equal(doc.querySelectorAll('details').length, 0, 'No expandable rows on the Watchlists page');
  assert.equal(cards.length, 2);
  assert.equal(cards[0].querySelector('.wl-card-link').getAttribute('href'), '/watchlists/1');
  assert.deepEqual([...cards[0].querySelectorAll('.wl-card-stat dd')].map(dd => dd.textContent), ['12', '4', '3']);
  assert.ok(cards[0].querySelector('.wl-card-stat:last-child').classList.contains('has-unread'));
  assert.equal(cards[0].querySelector('.wl-pin-button').getAttribute('aria-label'), 'Unpin');
  assert.equal(cards[1].querySelector('.wl-card-description').textContent, 'No description');
  assert.deepEqual([...cards[0].querySelectorAll('[role="menuitem"]')].map(item => item.textContent),
    ['Open watchlist', 'View all signals', 'Add instruments', 'Edit watchlist', 'Delete watchlist']);
  assert.equal(doc.querySelector('[data-watchlist-count]').textContent, '2 watchlists');
  const search = doc.querySelector('[data-watchlist-search]');
  search.value = 'divid'; search.dispatchEvent(new dom.window.Event('input'));
  assert.deepEqual([...doc.querySelectorAll('.wl-card-link')].map(a => a.textContent), ['Dividends']);
  assert.equal(doc.querySelector('[data-watchlist-count]').textContent, '1 watchlist');
  dom.window.close();
});

test('follow chips merge buy and sell with the same intervals and split them when the intervals differ', async () => {
  const { dom } = harness({ total: 1, pageSize: 50, items: [{ symbol: 'AAPL', companyName: 'Apple', signals: [
    ...['DAILY', 'WEEKLY'].flatMap(interval => ['BUY', 'SELL'].map(direction => ({ type: 'CANDLESTICK', interval, direction }))),
    { type: 'ELLIOTT_WAVE', interval: 'DAILY', direction: 'BUY' }, { type: 'ELLIOTT_WAVE', interval: 'MONTHLY', direction: 'SELL' },
    { type: 'OUTLOOK', interval: 'WEEKLY', direction: 'ANY' }, { type: 'INSIDER', interval: 'DAILY', direction: 'ANY' }] }] });
  await settle();
  const chips = [...dom.window.document.querySelectorAll('.wl-following-cell .wl-follow-group')];
  assert.deepEqual(chips.map(chip => chip.getAttribute('aria-label')), [
    'Candlestick: Buy and sell · Daily, Weekly',
    'Elliott Wave: Buy · Daily; Sell · Monthly',
    'Automated outlook: Weekly',
    'Insider trades']);
  assert.equal(chips[0].querySelectorAll('.wl-dir').length, 0, 'Both directions need no direction word');
  assert.deepEqual([...chips[1].querySelectorAll('.wl-dir')].map(dir => dir.textContent), ['Buy', 'Sell']);
  dom.window.close();
});

test('an instrument without follows offers to choose signals, and an empty list offers to add instruments', async () => {
  const { dom } = harness({ total: 1, pageSize: 50, items: [{ symbol: 'NEW', companyName: 'New listing', signals: [] }] });
  await settle();
  const doc = dom.window.document;
  assert.equal(doc.querySelector('.wl-no-follows span').textContent, 'No follows in this list');
  assert.equal(doc.querySelector('.wl-no-follows button').textContent, 'Choose signals');
  dom.window.close();
  const empty = harness({ total: 0, pageSize: 50, items: [] });
  await settle();
  assert.equal(empty.dom.window.document.querySelector('.wl-table-empty strong').textContent, 'This watchlist is empty');
  empty.dom.window.close();
});
