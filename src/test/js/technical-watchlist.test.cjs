const { test } = require('node:test');
const assert = require('node:assert/strict');
const { ranked, filtered, signed } = require('../../main/resources/static/js/technical-watchlist.js');

function ticker(symbol, score, overrides = {}) {
  return { symbol, companyName: `${symbol} Company`, intervals: [{ apiInterval: '1d', available: true,
    score, classification: score > 0 ? 'Strong buy outlook' : score < 0 ? 'Strong sell outlook' : 'Neutral outlook',
    stale: false, candleTimestamp: 200, changedAt: 100, priceChangePercent: score / 10, ...overrides }] };
}

test('rankings use only current qualifying followed intervals, limit to five, and break ties by ticker', () => {
  const tickers = [ticker('Z', 90), ticker('A', 90), ticker('B', 80), ticker('C', 70), ticker('D', 60), ticker('E', 50),
    ticker('STALE', 100, { stale: true }), ticker('MISSING', 100, { available: false }),
    ticker('WEEKLY', 100, { apiInterval: '1wk' }), ticker('NEUTRAL', 0), ticker('SELL', -80)];
  assert.deepEqual(ranked(tickers, '1d', 'buy').map(item => item.ticker.symbol), ['A', 'Z', 'B', 'C', 'D']);
  assert.deepEqual(ranked(tickers, '1d', 'sell').map(item => item.ticker.symbol), ['SELL']);
  assert.equal(ranked(tickers, '1mo', 'buy').length, 0);
  assert.deepEqual(ranked([ticker('A', -20), ticker('B', -80)], '1d', 'sell').map(item => item.ticker.symbol), ['B', 'A']);
});

test('interval and outlook filters must match the same subscription', () => {
  const mixed = ticker('MIXED', 80);
  mixed.intervals.push({ ...ticker('MIXED', -50).intervals[0], apiInterval: '1wk' });
  assert.equal(filtered([mixed], { interval: '1wk', outlook: 'buy' }).length, 0);
  assert.equal(filtered([mixed], { interval: 'all', outlook: 'buy', search: 'company' }).length, 1);
});

test('price and score sorts use the selected interval and put unavailable values last in either direction', () => {
  const a = ticker('A', 20), b = ticker('B', 80), c = ticker('C', 100, { available: false });
  assert.deepEqual(filtered([a, b, c], { sort: 'score-high' }).map(item => item.symbol), ['B', 'A', 'C']);
  assert.deepEqual(filtered([a, b, c], { sort: 'score-low' }).map(item => item.symbol), ['A', 'B', 'C']);
  a.intervals.push({ ...a.intervals[0], apiInterval: '1wk', priceChangePercent: -20 });
  b.intervals.push({ ...b.intervals[0], apiInterval: '1wk', priceChangePercent: -5 });
  assert.deepEqual(filtered([a, b, c], { sort: 'price-low', rankingInterval: '1wk' }).map(item => item.symbol), ['A', 'B', 'C']);
});

test('latest changes sort across followed intervals without treating missing history as a change', () => {
  const a = ticker('A', 20, { changedAt: null }), b = ticker('B', 80), c = ticker('C', 0, { changedAt: 500 });
  assert.deepEqual(filtered([a, b, c], { sort: 'latest' }).map(item => item.symbol), ['C', 'B', 'A']);
  assert.equal(signed(null), '—');
  assert.equal(signed(-8.4), '-8.4');
  assert.equal(signed(0), '0.0');
});

// Exercise the actual page script with a small DOM harness, including async mutations.
function page(initialTickers, failDelete = false) {
  const fs = require('node:fs'), vm = require('node:vm');
  const nodes = new Map(), requests = [];
  let data = initialTickers;
  class Element {
    constructor(tag = 'div') { this.tagName = tag; this.children = []; this.listeners = {}; this.dataset = {}; this.attributes = {}; this.value = ''; this.hidden = false; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; this.copy = ''; }
    set textContent(value) { this.copy = value; this.children = []; }
    get textContent() { return (this.copy || '') + this.children.map(child => child.textContent).join(''); }
    setAttribute(key, value) { this.attributes[key] = value; }
    addEventListener(event, listener) { this.listeners[event] = listener; }
    focus() { this.focused = true; }
    showModal() { this.open = true; }
    close() { this.open = false; }
    async dispatch(event = 'click') { await this.listeners[event]?.({ preventDefault() {} }); }
  }
  const get = id => { if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id); };
  const root = get('technicalWatchlist');
  root.dataset = { endpoint: '/api/technical-watchlist', stockBase: '/stock/', changeBase: '/technical-outlook/changes/' };
  ['watchlistInterval', 'watchlistOutlook', 'changeInterval'].forEach(id => { get(id).value = 'all'; });
  get('watchlistSort').value = 'ticker';
  const tabs = ['1d', '1wk', '1mo'].map(api => { const button = new Element('button'); button.dataset.rankingInterval = api; return button; });
  function descendants(element) { return element.children.flatMap(child => [child, ...descendants(child)]); }
  const document = { hidden: false, getElementById: get, createElement: tag => new Element(tag),
    querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'test-csrf-token' }),
    querySelectorAll: selector => selector === '[data-ranking-interval]' ? tabs
      : descendants(get('watchlistRows')).filter(item => item.className === 'watchlist-unfollow') };
  const context = { document, Intl, Date, URL, AbortSignal, setInterval() {},
    async fetch(url, options) {
      requests.push({ url, ...options });
      if (options.method === 'DELETE') {
        if (failDelete) return { ok: false, status: 500 };
        const symbol = url.replace('/api/technical-watchlist', '').replace(/^\//, '');
        data = symbol ? data.filter(item => item.symbol !== decodeURIComponent(symbol)) : [];
        return { ok: true, json: async () => ({}) };
      }
      return { ok: true, json: async () => url.includes('/changes') ? [] : { fetchedAt: 1_700_000_000, tickers: data } };
    } };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/technical-watchlist.js', 'utf8'), context);
  return { get, tabs, requests, buttons: () => document.querySelectorAll('.watchlist-unfollow'),
    settle: () => new Promise(resolve => setImmediate(resolve)) };
}

test('page renders safely, links each interval, and removes a ticker using CSRF without touching other rows', async () => {
  const malicious = ticker('AAPL', 80); malicious.companyName = '<img src=x onerror=alert(1)>';
  const view = page([malicious, ticker('MSFT', 70)]); await view.settle();
  assert.equal(view.get('watchlistCount').textContent, '2 tickers followed');
  const row = view.get('watchlistRows').children[0];
  assert.equal(row.children[0].children[0].children[0].children[1].children[1].textContent, malicious.companyName);
  assert.equal(row.children[1].children[0].href, '/stock/AAPL/technical-outlook?interval=1d');
  await view.buttons()[0].dispatch();
  assert.equal(view.get('watchlistCount').textContent, '1 ticker followed');
  const mutation = view.requests.find(item => item.method === 'DELETE');
  assert.equal(mutation.url, '/api/technical-watchlist/AAPL');
  assert.equal(mutation.headers['X-CSRF-TOKEN'], 'test-csrf-token');
  assert.equal(view.get('watchlistRows').children.length, 1);
  assert.equal(view.get('watchlistSearch').focused, true);
});

test('unfollow-all explains account-wide scope even when filters hide tickers, and cancel never mutates', async () => {
  const view = page([ticker('AAPL', 80), ticker('MSFT', 70)]); await view.settle();
  view.get('watchlistSearch').value = 'AAPL'; await view.get('watchlistSearch').dispatch('input');
  await view.get('unfollowAll').dispatch();
  assert.match(view.get('unfollowAllDescription').textContent, /every ticker in your account/);
  assert.match(view.get('unfollowAllDescription').textContent, /every watchlist/);
  await view.get('cancelUnfollowAll').dispatch();
  assert.equal(view.requests.filter(item => item.method === 'DELETE').length, 0);
  await view.get('unfollowAll').dispatch(); await view.get('confirmUnfollowAll').dispatch();
  assert.equal(view.get('watchlistEmpty').hidden, false);
  assert.equal(view.get('unfollowAll').disabled, true);
  assert.equal(view.get('unfollowAllDialog').open, false);
});

test('a rejected unfollow preserves the ticker and restores controls for retry', async () => {
  const view = page([ticker('AAPL', 80)], true); await view.settle();
  await view.buttons()[0].dispatch();
  assert.equal(view.get('watchlistRows').children.length, 1);
  assert.equal(view.get('watchlistError').hidden, false);
  assert.equal(view.buttons()[0].disabled, false);
});
