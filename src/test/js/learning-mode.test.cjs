const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const { StockWatchI18n } = require('./i18n-stub.cjs');
const source = fs.readFileSync('src/main/resources/static/js/learning-mode.js', 'utf8');

const PAGE = `
  <button id="learningModeToggle" aria-pressed="false" data-endpoint="/api/settings/learning-mode" data-csrf-token="csrf-test">Learning mode</button>
  <h2><span id="term" class="learn-term" data-term="rsi">RSI</span></h2>
  <h3>Setup <span id="help" class="learn-help" data-learn="analysis.rsiPeriod" data-learn-title="RSI period">?</span></h3>
  <label id="fieldLabel"><span>Include RSI</span> <span id="fieldHelp" class="learn-help" data-term="rsi">?</span><input id="field" type="checkbox"></label>
  <a id="row" href="#row"><span id="rowTerm" class="learn-term" data-term="atr">ATR</span></a>
  <button id="control" type="button" class="learn-control" data-term="macd">Add MACD</button>
  <span id="unknown" class="learn-term" data-term="noSuchTerm">Mystery</span>`;

async function setup(t, { on = false } = {}) {
  const dom = new JSDOM(`<!DOCTYPE html><html${on ? ' data-learning-mode="on"' : ''}><body>${PAGE}</body></html>`,
    { runScripts: 'outside-only', pretendToBeVisual: true, url: 'https://stockwatch.test/home' });
  t.after(() => dom.window.close());
  const requests = [];
  dom.window.StockWatchI18n = StockWatchI18n;
  dom.window.fetch = (url, options) => { requests.push({ url, options }); return Promise.resolve({ ok: true }); };
  dom.window.eval(source);
  const document = dom.window.document;
  if (document.readyState === 'loading') {
    await new Promise(resolve => document.addEventListener('DOMContentLoaded', resolve, { once: true }));
  }
  const $ = id => document.getElementById(id);
  const popover = () => document.getElementById('learnPopover');
  const open = () => {
    const element = popover();
    if (!element || !element.isConnected) return false;
    if (typeof element.showPopover === 'function') return element.matches(':popover-open');
    return !element.hidden;
  };
  const fire = (element, type, init = {}) => element.dispatchEvent(new dom.window.MouseEvent(type, { bubbles: true, cancelable: true, ...init }));
  const key = (element, name) => element.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }));
  return { dom, document, $, popover, open, fire, key, requests };
}

test('learning mode is off by default: terms stay plain text and hovering explains nothing', async t => {
  const { $, open, fire } = await setup(t);
  assert.equal($('term').hasAttribute('tabindex'), false);
  assert.equal($('term').hasAttribute('role'), false);
  fire($('term'), 'mouseover');
  assert.equal(open(), false);
});

test('the sidebar switch turns learning mode on and off and saves the choice to the account', async t => {
  const { document, $, requests } = await setup(t);
  $('learningModeToggle').click();
  assert.equal(document.documentElement.dataset.learningMode, 'on');
  assert.equal($('learningModeToggle').getAttribute('aria-pressed'), 'true');
  assert.equal($('term').getAttribute('tabindex'), '0');
  assert.equal($('term').getAttribute('role'), 'button');

  $('learningModeToggle').click();
  assert.equal(document.documentElement.hasAttribute('data-learning-mode'), false);
  assert.equal($('learningModeToggle').getAttribute('aria-pressed'), 'false');
  assert.equal($('term').hasAttribute('tabindex'), false);

  assert.deepEqual(requests.map(request => request.options.body), ['enabled=true', 'enabled=false']);
  assert.equal(requests[0].url, '/api/settings/learning-mode');
  assert.equal(requests[0].options.method, 'POST');
  assert.equal(requests[0].options.headers['X-CSRF-TOKEN'], 'csrf-test');
});

test('hovering an underlined term shows its glossary entry, and leaving it hides the entry', async t => {
  const { $, popover, open, fire } = await setup(t, { on: true });
  fire($('term'), 'mouseover');
  assert.equal(open(), true);
  assert.equal(popover().querySelector('strong').textContent, StockWatchI18n.t('glossary.rsi.term'));
  assert.equal(popover().querySelector('p').textContent, StockWatchI18n.t('glossary.rsi.text'));
  assert.equal($('term').getAttribute('aria-describedby'), 'learnPopover');

  fire($('term'), 'mouseout');
  assert.equal(open(), false);
  assert.equal($('term').hasAttribute('aria-describedby'), false);
});

test('a "?" outside a label is a button that pins its explanation until Escape', async t => {
  const { $, popover, open, key } = await setup(t, { on: true });
  const help = $('help');
  assert.equal(help.getAttribute('role'), 'button');
  assert.equal(help.getAttribute('tabindex'), '0');
  assert.equal(help.getAttribute('aria-label'), StockWatchI18n.t('learning.explain', 'RSI period'));

  help.click();
  assert.equal(open(), true);
  assert.equal(help.getAttribute('aria-expanded'), 'true');
  assert.equal(popover().querySelector('strong').textContent, 'RSI period');
  assert.equal(popover().querySelector('p').textContent, StockWatchI18n.t('learn.analysis.rsiPeriod'));

  key(help, 'Escape');
  assert.equal(open(), false);
  assert.equal(help.getAttribute('aria-expanded'), 'false');
});

test('a "?" inside a label stays out of the field name and does not toggle the field', async t => {
  const { $, open } = await setup(t, { on: true });
  const help = $('fieldHelp');
  assert.equal(help.getAttribute('aria-hidden'), 'true');
  assert.equal(help.hasAttribute('tabindex'), false);

  help.click();
  assert.equal(open(), true);
  assert.equal($('field').checked, false, 'the click explains instead of ticking the checkbox');
});

test('a labelled field explains itself on keyboard focus and forgets the description on blur', async t => {
  const { $, open } = await setup(t, { on: true });
  $('field').focus();
  assert.equal(open(), true);
  assert.equal($('field').getAttribute('aria-describedby'), 'learnPopover');
  $('field').blur();
  assert.equal(open(), false);
  assert.equal($('field').hasAttribute('aria-describedby'), false);
});

test('a control that explains itself keeps its own click', async t => {
  const { dom, $, open, fire } = await setup(t, { on: true });
  let clicks = 0;
  $('control').addEventListener('click', () => clicks++);
  fire($('control'), 'mouseover');
  assert.equal(open(), true);
  const event = new dom.window.MouseEvent('click', { bubbles: true, cancelable: true });
  $('control').dispatchEvent(event);
  assert.equal(clicks, 1);
  assert.equal(event.defaultPrevented, false);
});

test('a term inside a link explains itself on hover but is not a separate tab stop', async t => {
  const { dom, $, open, fire } = await setup(t, { on: true });
  assert.equal($('rowTerm').hasAttribute('tabindex'), false);
  fire($('rowTerm'), 'mouseover');
  assert.equal(open(), true);
  const event = new dom.window.MouseEvent('click', { bubbles: true, cancelable: true });
  $('rowTerm').dispatchEvent(event);
  assert.equal(event.defaultPrevented, false, 'the link still navigates');
});

test('a term without a definition stays inert', async t => {
  const { $, open, fire } = await setup(t, { on: true });
  assert.equal($('unknown').hasAttribute('tabindex'), false);
  fire($('unknown'), 'mouseover');
  assert.equal(open(), false);
});

test('terms added after load by page scripts are prepared too', async t => {
  const { document, $ } = await setup(t, { on: true });
  const added = document.createElement('span');
  added.className = 'learn-term';
  added.dataset.term = 'vwap';
  added.textContent = 'VWAP';
  document.body.append(added);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(added.getAttribute('tabindex'), '0');
  assert.equal($('term').getAttribute('tabindex'), '0');
});
