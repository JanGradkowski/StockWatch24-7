const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

const stock = fs.readFileSync('src/main/resources/static/js/stock-workspace.js', 'utf8');
const picker = fs.readFileSync('src/main/resources/static/js/watchlist-picker.js', 'utf8');
const template = fs.readFileSync('src/main/resources/templates/stock.html', 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));

// Run the real alert controls, navigation guards and picker together, without charts.
function setup(t) {
  const dom = new JSDOM(template, { runScripts: 'outside-only', url: 'https://stockwatch.test/stock/AAPL' });
  t.after(() => dom.window.close());
  const w = dom.window;
  w.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  w.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new w.Event('close')); };
  const requests = [];
  let fail = false;
  w.fetch = async (url, options = {}) => {
    if (url === '/api/watchlists') return { ok: true, json: async () => [{ id: 7, name: 'My stocks', instruments: 1 }] };
    if (url.includes('/memberships/')) return { ok: true, json: async () => [7] };
    assert.equal(url, '/api/alerts/AAPL');
    assert.equal(options.method, 'PUT');
    const body = JSON.parse(options.body);
    requests.push(body);
    const families = {}, intervals = {};
    for (const change of body.changes) {
      ((families[change.patternFamily] ||= {})[change.interval] ||= {})[change.signal] = change.active;
    }
    for (const change of body.outlookChanges) intervals[change.interval] = change.active;
    return { ok: !fail, json: async () => ({ error: 'Test save failure', families, trackedStocks: 1, maxTrackedStocks: 100, outlookState: { intervals } }) };
  };
  w.eval(picker);
  w.eval(`
    var alertStateLoaded = false, outlookFollowStateLoaded = false, alertSaveInProgress = false;
    var technicalFollowAllBusy = false, pageExitAllowed = false, alertBeforeUnloadRegistered = false;
    var alertSavePromise = null, pendingNavigationAction = null, trackedAlertSummary = '';
    var persistedAlertState = new Map(), encodedTicker = 'AAPL';
    function secureJsonHeaders() { return {}; }
    ${stock.slice(stock.indexOf('  function outlookFollowInputs()'), stock.indexOf('  async function loadOutlookFollowState()'))}
    ${stock.slice(stock.indexOf('  function alertInputs()'), stock.indexOf('  const STOCK_WORKSPACE_SECTIONS'))}
    hydrateAlertDraft({});
    hydrateOutlookFollowState({});
    setAlertInputsDisabled(false);
    setOutlookFollowInputsDisabled(false);
    bindUnsavedAlertGuards();
  `);
  const click = id => w.document.getElementById(id).click();
  return { w, requests, click, fail: value => { fail = value; },
    dialog: () => w.document.getElementById('unsavedAlertDialog'),
    submitPicker: () => w.document.querySelector('.wl-picker button[type=submit]').click() };
}

test('Follow all then Apply saves once and closes the picker without a leave-page prompt', async t => {
  const ui = setup(t), { w } = ui;
  ui.click('toggleAllTechnicalMonitoringBtn');
  ui.click('applyAlertChangesBtn');
  await settle();
  ui.submitPicker();
  await settle();
  assert.equal(ui.dialog().open, false);
  assert.equal(w.document.querySelector('.wl-picker'), null);
  assert.equal(ui.requests.length, 1);
  assert.deepEqual(ui.requests[0].watchlistIds, [7]);
  assert.equal(ui.requests[0].changes.length, w.alertInputs().length);
  assert.equal(ui.requests[0].outlookChanges.length, w.outlookFollowInputs().length);
  assert.ok(ui.requests[0].changes.every(change => change.active));
  assert.equal(w.hasUnappliedAlertChanges(), false);
  assert.equal(w.alertSaveInProgress, false);
  assert.equal(w.document.getElementById('applyAlertChangesBtn').textContent, 'Apply changes');
});

test('Save and leave completes after choosing a watchlist', async t => {
  const ui = setup(t), { w } = ui;
  let navigated = 0;
  ui.click('toggleAllTechnicalMonitoringBtn');
  w.requestGuardedNavigation(() => { navigated++; });
  ui.click('saveAlertChangesBeforeLeaveBtn');
  await settle();
  ui.submitPicker();
  await settle();
  assert.equal(navigated, 1);
  assert.equal(ui.requests.length, 1);
  assert.equal(w.document.querySelector('dialog[open]'), null);
  assert.equal(w.document.getElementById('saveAlertChangesBeforeLeaveBtn').disabled, false);
});

test('cancelling the picker or a failed save preserves the draft and permits retry', async t => {
  const ui = setup(t), { w } = ui;
  let navigated = 0;
  ui.click('toggleAllTechnicalMonitoringBtn');
  w.requestGuardedNavigation(() => { navigated++; });
  ui.click('saveAlertChangesBeforeLeaveBtn');
  await settle();
  w.document.querySelector('.wl-picker button[type=button]').click();
  await settle();
  assert.equal(ui.requests.length, 0);
  assert.equal(w.hasUnappliedAlertChanges(), true);
  assert.equal(w.document.getElementById('saveAlertChangesBeforeLeaveBtn').disabled, false);
  ui.fail(true);
  ui.click('saveAlertChangesBeforeLeaveBtn');
  await settle();
  ui.submitPicker();
  await settle();
  assert.equal(navigated, 0);
  assert.equal(w.hasUnappliedAlertChanges(), true);
  assert.equal(w.document.getElementById('saveAlertChangesBeforeLeaveBtn').disabled, false);
  ui.fail(false);
  ui.click('saveAlertChangesBeforeLeaveBtn');
  await settle();
  ui.submitPicker();
  await settle();
  assert.equal(navigated, 1);
  assert.equal(w.hasUnappliedAlertChanges(), false);
});

test('ordinary navigation forms still prompt and submit only after discarding the draft', t => {
  const ui = setup(t), { w } = ui;
  const form = w.document.createElement('form');
  form.action = '/logout';
  const button = w.document.createElement('button');
  button.type = 'submit';
  form.append(button);
  w.document.body.append(form);
  let submitted = 0;
  form.requestSubmit = submitter => { assert.equal(submitter, button); submitted++; };
  ui.click('toggleAllTechnicalMonitoringBtn');
  button.click();
  assert.equal(ui.dialog().open, true);
  assert.equal(submitted, 0);
  ui.click('discardAlertChangesBtn');
  assert.equal(submitted, 1);
  assert.equal(ui.dialog().open, false);
});
