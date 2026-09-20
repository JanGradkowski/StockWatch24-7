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
