const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const { StockWatchI18n } = require('./i18n-stub.cjs');

async function setup(selected = '') {
  const dom = new JSDOM(`<body><div data-named-watchlists data-selected-id="${selected}"></div></body>`,
    { runScripts: 'outside-only', url: 'https://watchlist.test' });
  dom.window.fetch = async url => ({ ok: true, json: async () => url.includes('/members?')
    ? { total: 1, pageSize: 50, items: [{ symbol: 'ABC.L', companyName: 'Example', exchange: 'LSE', currency: 'GBP',
      unreadSignalCount: 3, monitoring: { unreadSignalCount: 99 }, signals: [
        { type: 'CANDLESTICK', direction: 'BUY', interval: 'DAILY' },
        { type: 'HARMONIC_FORMATION', direction: 'SELL', interval: 'WEEKLY' }
      ] }] }
    : [ { id: 1, name: 'Research', instruments: 1, monitored: 1, unread: 3 },
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
  const candlestick = doc.querySelector('[aria-label="Candlestick buy follows"]');
  const harmonic = doc.querySelector('[aria-label="Harmonic sell follows"]');
  assert.equal(candlestick.querySelector('.wl-interval-chip').textContent, 'Daily');
  assert.equal(harmonic.querySelector('.wl-interval-chip').textContent, 'Weekly');
  const row = doc.querySelector('tbody tr');
  assert.equal(row.querySelector('.wl-unread-badge strong').textContent, '3');
  assert.equal(row.querySelector('a').getAttribute('href'), '/signals?watchlistId=1&ticker=ABC.L');
  assert.equal([...doc.querySelectorAll('a')].find(a => a.textContent === 'View all signals').getAttribute('href'), '/signals?watchlistId=1');
  dom.window.close();
});

test('home and watchlists accordions open the same overview and scoped ticker archive', async () => {
  const dom = await setup(), doc = dom.window.document;
  assert.equal(doc.querySelectorAll('details').length, 2);
  const list = doc.querySelector('details');
  list.open = true;
  list.dispatchEvent(new dom.window.Event('toggle'));
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal([...list.querySelectorAll('a')].find(a => a.textContent === 'Open watchlist').getAttribute('href'), '/watchlists/1');
  assert.equal(list.querySelector('tbody a').getAttribute('href'), '/signals?watchlistId=1&ticker=ABC.L');
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
  assert.equal(button('Manage follows',doc.querySelector('.wl-bulk-actions')).disabled,true);
  doc.querySelector('[aria-label="Select ABC.L"]').click();
  button('Next').click(); await settle();
  doc.querySelector('[aria-label="Select all instruments on this page"]').click();
  const bulk = doc.querySelector('.wl-bulk-actions');
  assert.equal(bulk.querySelector('[role="status"]').textContent,'2 selected');
  assert.equal(new URL(bulk.querySelector('a').href).searchParams.get('ticker'),'ABC.L,BRK.A');
  assert.equal(new URL(doc.querySelector('.wl-view-action').href).searchParams.get('ticker'),'ABC.L,BRK.A');
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
  assert.equal(new URL(doc.querySelector('.wl-view-action').href).searchParams.get('ticker'),'ABC.L');
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
  assert.deepEqual([...doc.querySelectorAll('thead th')].map(th => th.textContent).slice(0, 2), ['Instrument', 'Last close']);
  const price = rows[0].querySelector('.wl-price-cell');
  assert.equal(price.querySelector('.wl-price strong').textContent, '343.91');
  assert.equal(price.querySelector('.wl-price-change').textContent, '−1.10%');
  assert.ok(price.querySelector('.wl-price-change').classList.contains('is-down'));
  assert.equal(price.querySelector('.wl-spark path').getAttribute('d'), 'M0.0 2.0 L100.0 30.0');
  assert.equal(price.querySelector('.wl-price-outlook').textContent, 'Daily: Moderate buy outlook');
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
  button('Add instruments / edit').click(); await settle();
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
