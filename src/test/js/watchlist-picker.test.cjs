const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { JSDOM } = require('jsdom');
const pickerSource = fs.readFileSync('src/main/resources/static/js/watchlist-picker.js', 'utf8');
const stockSource = fs.readFileSync('src/main/resources/static/js/stock-workspace.js', 'utf8');

function setup(lists = [], memberships = []) {
  const dom = new JSDOM('<body><button id="apply">Apply changes</button></body>', { runScripts: 'outside-only', url: 'https://stockwatch.test/stock/AAPL' });
  dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new dom.window.Event('close')); };
  dom.window.fetch = async url => ({ ok: true, json: async () => url.includes('/memberships/') ? memberships : lists });
  dom.window.eval(pickerSource);
  return dom;
}
const settled = () => new Promise(resolve => setImmediate(resolve));

test('incompatible watchlists explain restrictions and cannot receive new signal follows', async () => {
  const dom = setup([{ id: 1, name: 'Activity only', instruments: 1, allowedTypes: ['CONGRESS'] }, { id: 2, name: 'Technical', instruments: 0, allowedTypes: ['CANDLESTICK'] }], [1]);
  const w = dom.window;
  const result = w.StockWatchLists.choose('AAPL', { signalTypes: ['CANDLESTICK'] }); await settled();
  const checks = w.document.querySelectorAll('input[type=checkbox]');
  assert.equal(checks[0].disabled, true); assert.equal(checks[0].checked, false);
  assert.match(w.document.querySelector('.wl-picker').textContent, /Not allowed here: Candlestick/);
  checks[1].click(); w.document.querySelector('button[type=submit]').click();
  assert.deepEqual(Array.from((await result).watchlistIds), [2]);
  const membership = w.StockWatchLists.choose('AAPL', { mode: 'membership' }); await settled();
  assert.equal(w.document.querySelector('input[type=checkbox]').disabled, false);
  w.document.querySelector('button[type=button]').click(); await membership; dom.window.close();
});

test('new users can create their first list in the picker without a separate save', async () => {
  const dom = setup(), w = dom.window; const result = w.StockWatchLists.choose('AAPL'); await settled();
  w.document.querySelector('input[type=text]').value = 'US growth';
  w.document.querySelector('form').dispatchEvent(new w.Event('submit', { cancelable: true }));
  assert.equal((await result).newWatchlistName, 'US growth');
  assert.equal(w.document.querySelector('dialog'), null); dom.window.close();
});
test('existing memberships are preselected and multiple lists produce one selection', async () => {
  const dom = setup([{ id: 1, name: 'Growth', instruments: 4 }, { id: 2, name: 'Long term', instruments: 5 }], [1]);
  const w = dom.window, result = w.StockWatchLists.choose('AAPL'); await settled();
  const checks = w.document.querySelectorAll('input[type=checkbox]');
  assert.equal(checks[0].checked, true); assert.equal(checks[0].disabled, false); checks[1].checked = true;
  w.document.querySelector('form').dispatchEvent(new w.Event('submit', { cancelable: true }));
  assert.deepEqual(Array.from((await result).watchlistIds), [1, 2]); dom.window.close();
});
test('cancel and Escape do not save memberships', async () => {
  for (const escape of [false, true]) {
    const dom = setup(), w = dom.window; const result = w.StockWatchLists.choose('AAPL'); await settled();
    if (escape) w.document.querySelector('dialog').dispatchEvent(new w.Event('cancel', { cancelable: true }));
    else w.document.querySelector('button[type=button]').click();
    assert.equal(await result, null); dom.window.close();
  }
});
test('empty selection stays open and untrusted names are text, never markup', async () => {
  const dom = setup([{ id: 1, name: '<img src=x onerror=alert(1)>', instruments: 0 }]); const w = dom.window;
  const result = w.StockWatchLists.choose('AAPL'); await settled();
  w.document.querySelector('form').dispatchEvent(new w.Event('submit', { cancelable: true }));
  assert.equal(w.document.querySelector('dialog').open, true); assert.equal(w.document.querySelector('img'), null);
  assert.match(w.document.querySelector('[role=status]').textContent, /Select a watchlist/);
  w.document.querySelector('button[type=button]').click(); await result; dom.window.close();
});

// Exercise the production save function with its chart-independent dependencies.
function stockSave(selection, changes, responseOk = true, outlookChanges = []) {
  const start = stockSource.indexOf('  async function performAlertDraftSave()');
  const end = stockSource.indexOf('\n  function hasAnyTechnicalFollow()', start);
  const status = { textContent: '', innerText: '' }, sent = [], calls = [];
  const context = { changedAlertInputs: () => changes, changedOutlookInputs: () => outlookChanges, document: { getElementById: () => status },
    setOutlookFollowInputsDisabled: disabled => calls.push(disabled), setAlertInputsDisabled: disabled => calls.push(disabled), updateAlertDraftUi: () => {},
    window: { StockWatchLists: { choose: async () => { calls.push('choose'); return selection; } } },
    encodedTicker: 'AAPL', secureJsonHeaders: () => ({}), alertChangePayload: i => i,
    fetch: async (url, options) => { sent.push({ url, body: JSON.parse(options.body) }); return { ok: responseOk, json: async () => ({ error: 'Test failure' }) }; },
    hydrateAlertDraft: () => calls.push('hydrate'), trackedAlertSummary: '1 rule', technicalFollowAllBusy: false,
    outlookFollowStateLoaded: true, alertStateLoaded: true, console, decodeURIComponent, alertSaveInProgress: false };
  vm.createContext(context); vm.runInContext(stockSource.slice(start, end), context);
  return { run: () => context.performAlertDraftSave(), status, sent, calls, context };
}
test('Apply changes asks first and submits membership and enabled rules together', async () => {
  const save = stockSave({ watchlistIds: [2, 3], newWatchlistName: null }, [{ checked: true, active: true }]);
  assert.equal(await save.run(), true); assert.equal(save.sent.length, 1);
  assert.deepEqual(save.sent[0].body.watchlistIds, [2, 3]); assert.equal(save.calls.indexOf('choose') < save.calls.indexOf('hydrate'), true);
});
test('cancelling Apply changes retains stars and makes no mutation request', async () => {
  const inputs = [{ checked: true, active: true }], save = stockSave(null, inputs);
  assert.equal(await save.run(), false); assert.equal(save.sent.length, 0); assert.equal(inputs[0].checked, true);
  assert.equal(save.context.alertSaveInProgress, false); assert.equal(save.calls.includes('hydrate'), false);
});
test('failed saves preserve the draft and disabling-only edits do not ask for a list', async () => {
  const save = stockSave({}, [{ checked: false, active: false }], false);
  assert.equal(await save.run(), false); assert.equal(save.calls.includes('choose'), false);
  assert.equal(save.calls.includes('hydrate'), false); assert.equal(save.context.alertSaveInProgress, false);
});

test('an existing membership can be the only destination for new signal follows', async () => {
  const dom = setup([{ id: 1, name: 'Activity alerts', instruments: 1 }], [1]);
  const w = dom.window, result = w.StockWatchLists.choose('AAPL'); await settled();
  assert.match(w.document.querySelector('h2').textContent, /signal changes/);
  assert.equal(w.document.querySelector('button[type=submit]').textContent, 'Apply changes');
  w.document.querySelector('button[type=submit]').click();
  assert.deepEqual(Array.from((await result).watchlistIds), [1]); dom.window.close();
});

test('deselecting existing memberships targets only the remaining list without sending removals', async () => {
  const dom = setup([{ id: 1, name: 'Activity alerts', instruments: 1 }, { id: 2, name: 'Research', instruments: 1 }], [1, 2]);
  const w = dom.window, result = w.StockWatchLists.choose('AAPL'); await settled();
  const checks = w.document.querySelectorAll('input[type=checkbox]');
  checks[0].click(); checks[1].click();
  w.document.querySelector('button[type=submit]').click();
  assert.equal(w.document.querySelector('dialog').open, true, 'At least one destination is required');
  checks[1].click(); w.document.querySelector('button[type=submit]').click();
  const selection = await result;
  assert.deepEqual(Array.from(selection.watchlistIds), [2]);
  assert.deepEqual(Object.keys(selection).sort(), ['newWatchlistName', 'watchlistIds']); dom.window.close();
});

test('membership-only actions retain membership wording', async () => {
  const dom = setup([{ id: 1, name: 'Research', instruments: 1 }], [1]);
  const w = dom.window, result = w.StockWatchLists.choose('AAPL', { mode: 'membership' }); await settled();
  assert.equal(w.document.querySelector('h2').textContent, 'Add AAPL to watchlists');
  assert.equal(w.document.querySelector('button[type=submit]').textContent, 'Add to watchlists');
  w.document.querySelector('button[type=button]').click(); assert.equal(await result, null); dom.window.close();
});

test('outlook-only and mixed drafts use one Apply request and one watchlist selection', async () => {
  for (const patterns of [[], [{ checked: true, active: true }]]) {
    const outlook = [{ checked: true, dataset: { outlookFollowInterval: 'DAILY' } }];
    const save = stockSave({ watchlistIds: [2] }, patterns, true, outlook);
    assert.equal(await save.run(), true);
    assert.equal(save.calls.filter(call => call === 'choose').length, 1);
    assert.equal(save.sent.length, 1);
    assert.deepEqual(save.sent[0].body.outlookChanges, [{ interval: 'DAILY', active: true }]);
    assert.equal(save.sent[0].body.changes.length, patterns.length);
  }
});

test('cancelled or failed outlook saves retain draft stars; unfollow-only skips the picker', async () => {
  for (const [selection, ok] of [[null, true], [{ watchlistIds: [1] }, false]]) {
    const outlook = [{ checked: true, dataset: { outlookFollowInterval: 'WEEKLY', persisted: 'false' } }];
    const save = stockSave(selection, [], ok, outlook);
    assert.equal(await save.run(), false);
    assert.equal(outlook[0].checked, true);
    assert.equal(outlook[0].dataset.persisted, 'false');
    assert.equal(save.calls.includes('hydrate'), false);
    if (selection === null) assert.equal(save.sent.length, 0);
  }
  const save = stockSave({}, [], true, [{ checked: false, dataset: { outlookFollowInterval: 'MONTHLY' } }]);
  assert.equal(await save.run(), true);
  assert.equal(save.calls.includes('choose'), false);
});

test('outlook star changes only mark the draft and activate Apply and the navigation guard', () => {
  const dom = new JSDOM('<div class="alert-panel"><button id="applyAlertChangesBtn"></button><span id="alertDraftStatus"></span><span id="outlookFollowStatus"></span><input type="checkbox" data-outlook-follow-interval="DAILY"></div>', { runScripts: 'outside-only' });
  const w = dom.window;
  w.changedAlertInputs = () => [];
  w.alertInputs = () => [];
  w.updateTechnicalFollowAllButton = () => {};
  w.setAlertBeforeUnloadGuard = enabled => { w.guarded = enabled; };
  w.fetch = () => { throw new Error('Starring must not save'); };
  // Keep state and functions in one evaluation so lexical bindings remain available.
  const names = ['outlookFollowInputs', 'hydrateOutlookFollowState', 'changedOutlookInputs', 'hasUnappliedAlertChanges', 'updateAlertDraftUi', 'hasAnyTechnicalFollow', 'toggleAllTechnicalMonitoring'];
  const functions = names.map(name => {
    const start = stockSource.indexOf(`  function ${name}(`);
    const rest = stockSource.slice(start + 1);
    const next = rest.search(/\n  (?:async )?function /);
    return stockSource.slice(start, next < 0 ? stockSource.length : start + 1 + next);
  }).join('\n');
  w.eval('var outlookFollowStateLoaded = false, alertStateLoaded = true, alertSaveInProgress = false, technicalFollowAllBusy = false, pageExitAllowed = false;\n' + functions);
  w.hydrateOutlookFollowState({ intervals: { DAILY: false } });
  const star = w.document.querySelector('input');
  star.click();
  assert.equal(star.checked, true); assert.equal(star.dataset.persisted, 'false');
  assert.equal(w.document.getElementById('applyAlertChangesBtn').disabled, false);
  assert.equal(w.hasUnappliedAlertChanges(), true); assert.equal(w.guarded, true);
  star.click();
  assert.equal(w.document.getElementById('applyAlertChangesBtn').disabled, true);
  assert.equal(w.hasUnappliedAlertChanges(), false); assert.equal(w.guarded, false);
  w.toggleAllTechnicalMonitoring();
  assert.equal(star.checked, true); assert.equal(star.dataset.persisted, 'false');
  assert.equal(w.hasUnappliedAlertChanges(), true);
  w.toggleAllTechnicalMonitoring();
  assert.equal(star.checked, false); assert.equal(w.hasUnappliedAlertChanges(), false);
  dom.window.close();
});
