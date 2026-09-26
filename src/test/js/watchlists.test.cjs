const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

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
