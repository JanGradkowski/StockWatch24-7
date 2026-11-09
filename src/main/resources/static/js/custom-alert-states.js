(() => {
  'use strict';
  // The stocks a custom alert watches, matching ones first, each with its state and "Stop" (or "Watch again") for that
  // stock. Shared by the alert list and an alert's own page.
  if (window.StockWatchCustomAlertStates) return;
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  const el = text.element;
  // The shared request helper (watchlist-picker.js) loads with the navigation, after this script.
  const api = (...args) => window.StockWatchLists.api(...args);
  // Learning-mode explanations (js.learn.<key>) of each state.
  const LEARN_OPTIONS = { MATCHING: 'customAlerts.state.MATCHING', HALFWAY: 'customAlerts.state.HALFWAY', NOT_MATCHING: 'customAlerts.state.NOT_MATCHING',
    CHECKING: 'customAlerts.state.CHECKING', WAITING: 'customAlerts.state.WAITING', SKIPPED: 'customAlerts.state.SKIPPED', STOPPED: 'customAlerts.state.STOPPED' };

  /**
   * Draws the alert's stocks into {@code box}. {@code onChange} gets the alert after a stock was stopped or brought
   * back; {@code onError} gets a message to show.
   */
  /** Stocks that need a look come first: matching, part-way through a sequence, then those still being checked. */
  const ORDER = { MATCHING: 0, HALFWAY: 1, CHECKING: 2, WAITING: 3, SKIPPED: 4, NOT_MATCHING: 5, STOPPED: 6 };
  /** Over this many rows, the stocks that simply do not match wait behind a "Show" button. */
  const FOLD_AT = 10;

  async function render(box, alert, { onChange = () => {}, onError = () => {}, unfolded = false } = {}) {
    box.setAttribute('aria-busy', 'true');
    if (!box.firstChild) box.replaceChildren(el('p', 'ca-loading', i18n.t('ca.states.loading')));
    try {
      const states = (await api(`/api/custom-alerts/${alert.id}/states`))
        .sort((a, b) => (ORDER[a.state] ?? 9) - (ORDER[b.state] ?? 9) || a.symbol.localeCompare(b.symbol));
      if (!states.length) { box.replaceChildren(el('p', 'ca-muted', i18n.t('ca.states.none'))); return; }
      const quiet = states.filter(state => state.state === 'NOT_MATCHING');
      // One rule on every page: past FOLD_AT stocks, the ones that do not match wait behind "Show N…".
      const fold = !unfolded && states.length > FOLD_AT && quiet.length > 0;
      const shown = fold ? states.filter(state => state.state !== 'NOT_MATCHING') : states;
      const table = el('table', 'ca-states-table');
      const caption = el('caption', 'visually-hidden', i18n.t('ca.states.caption', alert.name));
      const head = el('tr');
      [['stock', false], ['state', false], ['action', true]].forEach(([key, hidden]) => {
        const th = el('th', null);
        th.scope = 'col';
        th.append(hidden ? el('span', 'visually-hidden', i18n.t(`ca.states.column.${key}`)) : i18n.t(`ca.states.column.${key}`));
        head.append(th);
      });
      const thead = el('thead');
      thead.append(head);
      const body = el('tbody');
      shown.forEach(state => body.append(row(alert, state, box, { onChange, onError, unfolded })));
      table.append(caption, thead, body);
      const wrap = el('div', 'ca-states-wrap');
      wrap.append(table);
      box.replaceChildren(wrap);
      if (fold) {
        const more = el('button', 'ca-text-button ca-states-more');
        more.type = 'button';
        more.append(text.icon('chevron'), el('span', null, i18n.plural('ca.states.showQuiet', quiet.length, quiet.length)));
        more.addEventListener('click', async () => {
          await render(box, alert, { onChange, onError, unfolded: true });
          box.querySelectorAll('.ca-state-row')[shown.length]?.querySelector('.ca-state-symbol')?.focus();
        });
        box.append(more);
      }
    } catch (error) {
      box.replaceChildren(el('p', 'ca-muted', error.message));
    } finally {
      box.removeAttribute('aria-busy');
    }
  }

  function row(alert, state, box, handlers) {
    const tr = el('tr', `ca-state-row is-${state.state.toLowerCase()}`);
    const stock = el('td', 'ca-state-stock');
    const name = el('span', 'ca-state-name');
    const symbol = el('a', 'ca-state-symbol', state.symbol);
    symbol.href = `/stock/${encodeURIComponent(state.symbol)}`;
    name.append(symbol, el('span', 'ca-state-company', state.companyName || ''));
    stock.append(name);
    const cell = el('td', 'ca-state-cell');
    const pill = el('span', `ca-state-pill learn-term ${state.state.toLowerCase()}`, text.stateLabel(state.state, state.stateIntervals, alert.intervals));
    pill.dataset.learn = LEARN_OPTIONS[state.state];
    pill.dataset.learnTitle = i18n.t(`ca.state.${state.state}`);
    cell.append(pill);
    const detail = state.progress ? text.progressText(state.progress) : state.detail && state.state !== 'MATCHING' ? state.detail : '';
    if (detail) cell.append(el('span', 'ca-state-detail', detail));
    const act = el('td', 'ca-state-action');
    const toggle = el('button', 'ca-text-button', i18n.t(state.excluded ? 'ca.states.resume' : 'ca.states.stop'));
    toggle.type = 'button';
    toggle.setAttribute('aria-label', i18n.t(state.excluded ? 'ca.states.resumeAria' : 'ca.states.stopAria', state.symbol));
    toggle.addEventListener('click', async () => {
      toggle.disabled = true;
      try {
        const updated = await api(`/api/custom-alerts/${alert.id}/stocks/${encodeURIComponent(state.symbol)}?stopped=${!state.excluded}`, { method: 'POST' });
        handlers.onChange(updated);
        await render(box, updated, handlers);
        // The keyboard stays on the same stock's button, now saying the opposite.
        [...box.querySelectorAll('.ca-state-row')].find(item => item.querySelector('.ca-state-symbol')?.textContent === state.symbol)
          ?.querySelector('.ca-text-button')?.focus();
      } catch (error) {
        toggle.disabled = false;
        handlers.onError(error.message);
      }
    });
    act.append(toggle);
    tr.append(stock, cell, act);
    return tr;
  }

  window.StockWatchCustomAlertStates = Object.freeze({ render });
})();
