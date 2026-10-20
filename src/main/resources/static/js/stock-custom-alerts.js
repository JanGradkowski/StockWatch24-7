(() => {
  'use strict';
  // The stock page's custom alerts: tick the alerts that should watch this stock, then save. Applying puts the
  // stock in a watchlist (the alerts watch stocks through lists); removing keeps the alert for its other stocks.
  const box = document.querySelector('[data-stock-custom-alerts]');
  if (!box) return;
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  const symbol = box.dataset.symbol;
  const listNode = box.querySelector('[data-stock-custom-list]');
  const status = box.querySelector('[data-stock-custom-status]');
  const save = box.querySelector('[data-stock-custom-save]');
  // Learning-mode explanations (js.learn.<key>) used here.
  const LEARN_OPTIONS = { MATCHING: 'customAlerts.state.MATCHING', NOT_MATCHING: 'customAlerts.state.NOT_MATCHING',
    CHECKING: 'customAlerts.state.CHECKING', WAITING: 'customAlerts.state.WAITING', SKIPPED: 'customAlerts.state.SKIPPED' };
  const api = (...args) => window.StockWatchLists.api(...args);
  const el = (tag, className, content) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content != null) node.textContent = content;
    return node;
  };
  let alerts = [];
  const chosen = new Map();

  function changes() {
    const apply = [], remove = [];
    alerts.forEach(alert => {
      const want = chosen.get(alert.id);
      if (want && !alert.applied) apply.push(alert.id);
      if (!want && alert.applied) remove.push(alert.id);
    });
    return { apply, remove };
  }

  function refreshSave() {
    const { apply, remove } = changes();
    save.disabled = !apply.length && !remove.length;
    status.textContent = save.disabled ? '' : i18n.t('stockCustom.unsaved');
  }

  function row(alert) {
    const item = el('li', `stock-custom-item${alert.active ? '' : ' is-paused'}`);
    const label = el('label', 'stock-custom-check');
    const input = el('input');
    input.type = 'checkbox';
    input.checked = chosen.get(alert.id);
    input.addEventListener('change', () => { chosen.set(alert.id, input.checked); refreshSave(); });
    const name = el('span', 'stock-custom-name', alert.name);
    label.append(input, name);
    item.append(label);
    const facts = el('div', 'stock-custom-facts');
    facts.append(el('span', null, i18n.t(`interval.${alert.interval}`)));
    if (alert.applied) {
      const state = el('span', `stock-custom-state learn-term ${alert.state.toLowerCase()}`, i18n.t(`ca.state.${alert.state}`));
      if (LEARN_OPTIONS[alert.state]) { state.dataset.learn = LEARN_OPTIONS[alert.state]; state.dataset.learnTitle = i18n.t(`ca.state.${alert.state}`); }
      if (alert.detail) state.title = alert.detail;
      facts.append(state);
    }
    if (!alert.active) facts.append(el('span', 'stock-custom-paused', i18n.t('stockCustom.paused')));
    const triggers = el('a', 'text-link', i18n.t('stockCustom.triggers'));
    triggers.href = `/signals?${new URLSearchParams({ customAlertId: alert.id, ticker: symbol })}`;
    const edit = el('a', 'text-link', i18n.t('stockCustom.edit'));
    edit.href = `/custom-alerts/${alert.id}/edit`;
    facts.append(triggers, edit);
    item.append(facts);
    // The rule in words, so the choice does not depend on remembering what each name meant.
    const conditions = (alert.definition?.groups || []).flatMap(group => group.conditions || []);
    if (conditions.length) {
      const rule = el('p', 'stock-custom-rule');
      rule.textContent = conditions.slice(0, 3).map(condition => text.describe(condition)).join(' · ')
        + (conditions.length > 3 ? ` · ${i18n.t('ca.moreConditions', conditions.length - 3)}` : '');
      item.append(rule);
    }
    return item;
  }

  function render() {
    if (!alerts.length) {
      listNode.replaceChildren(el('p', 'wl-muted', i18n.t('stockCustom.none')));
      save.hidden = true;
      return;
    }
    save.hidden = false;
    const list = el('ul', 'stock-custom-list');
    alerts.forEach(alert => list.append(row(alert)));
    listNode.replaceChildren(list);
    refreshSave();
  }

  async function load() {
    try {
      alerts = await api(`/api/custom-alerts/for-stock/${encodeURIComponent(symbol)}`);
      chosen.clear();
      alerts.forEach(alert => chosen.set(alert.id, alert.applied));
      render();
    } catch (error) {
      listNode.replaceChildren(el('p', 'wl-status', error.message));
    }
  }

  save.addEventListener('click', async () => {
    const { apply, remove } = changes();
    let target = { watchlistIds: [], newWatchlistName: null };
    if (apply.length) {
      // Alerts watch stocks through watchlists: ask which list this stock belongs in (its current lists are ticked).
      target = await window.StockWatchLists.choose(symbol, { mode: 'signals', signalTypes: ['CUSTOM'], texts: {
        title: i18n.t('ca.picker.title', symbol), intro: i18n.t('ca.picker.intro', symbol), submit: i18n.t('ca.picker.submit') } });
      if (!target) return;
    }
    save.disabled = true;
    status.textContent = i18n.t('stockCustom.saving');
    const before = new Set(alerts.filter(alert => alert.applied).map(alert => alert.id));
    try {
      alerts = await api(`/api/custom-alerts/for-stock/${encodeURIComponent(symbol)}`, { method: 'PUT', body: JSON.stringify({
        apply, remove, watchlistIds: target.watchlistIds, newWatchlistName: target.newWatchlistName }) });
      chosen.clear();
      alerts.forEach(alert => chosen.set(alert.id, alert.applied));
      render();
      // Alerts that cover a whole list start watching the stock too once it joins that list: say so.
      const along = alerts.filter(alert => alert.applied && !before.has(alert.id) && !apply.includes(alert.id)).map(alert => alert.name);
      status.textContent = along.length ? i18n.t('stockCustom.savedAlong', along.join(', ')) : i18n.t('stockCustom.saved');
    } catch (error) {
      status.textContent = error.message;
      save.disabled = false;
    }
  });

  // Deferred scripts run in document order, so the navigation's request helper may not exist yet.
  if (window.StockWatchLists) load();
  else document.addEventListener('DOMContentLoaded', load, { once: true });
})();
