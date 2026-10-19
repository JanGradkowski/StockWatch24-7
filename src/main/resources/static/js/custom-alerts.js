(() => {
  'use strict';
  // The Custom alerts page: every alert as a card (click it to see its triggers), its switches and menu,
  // the stocks it watches with their state, the chart alerts tab and the starter templates.
  const root = document.querySelector('[data-ca-list]');
  if (!root) return;
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  // The shared request helper (watchlist-picker.js) loads with the navigation, after this script.
  const api = (...args) => window.StockWatchLists.api(...args);
  const el = text.element;
  // Learning-mode explanations (js.learn.<key>) used on this page.
  const LEARN_OPTIONS = { active: 'customAlerts.active', checking: 'customAlerts.checking', convert: 'customAlerts.convert', matching: 'customAlerts.matching', triggers: 'customAlerts.triggers', MATCHING: 'customAlerts.state.MATCHING', NOT_MATCHING: 'customAlerts.state.NOT_MATCHING', CHECKING: 'customAlerts.state.CHECKING', WAITING: 'customAlerts.state.WAITING', SKIPPED: 'customAlerts.state.SKIPPED', STOPPED: 'customAlerts.state.STOPPED' };
  const page = document.querySelector('.custom-alerts-page');
  const search = document.querySelector('[data-ca-search]');
  const guide = document.querySelector('[data-ca-guide]');
  let alerts = [];
  let chartAlerts = null;
  let definitions = new Map();
  let filter = 'all';

  // Learning mode opens the guide; afterwards the reader decides.
  if (guide && document.documentElement.dataset.learningMode === 'on') guide.open = true;

  const date = value => value ? i18n.date(value, { day: 'numeric', month: 'short', year: 'numeric' }) : '';
  const link = (label, href, className = 'text-link') => { const a = el('a', className, label); a.href = href; return a; };
  const button = (label, onClick, className = 'btn-secondary') => {
    const b = el('button', className, label); b.type = 'button'; b.addEventListener('click', onClick); return b;
  };

  async function load() {
    try {
      const [list, catalog] = await Promise.all([api('/api/custom-alerts'), text.catalog().catch(() => null)]);
      alerts = list;
      definitions = catalog ? catalog.byType : new Map();
      render();
    } catch (error) {
      root.replaceChildren(el('p', 'wl-status', error.message), button(i18n.t('common.retry'), load));
    }
  }

  function matches(alert, query) {
    if (!query) return true;
    const haystack = [alert.name, alert.description, ...alert.targets.flatMap(target => [target.watchlistName, ...target.symbols])]
      .join(' ').toLowerCase();
    return haystack.includes(query);
  }

  function render() {
    document.querySelectorAll('[data-ca-tab]').forEach(tab => tab.setAttribute('aria-pressed', String(tab.dataset.caTab === filter)));
    if (filter === 'chart') { renderChartAlerts(); return; }
    const query = (search?.value || '').trim().toLowerCase();
    if (!alerts.length) { root.replaceChildren(emptyState()); return; }
    const visible = alerts.filter(alert => (filter === 'all' || (filter === 'active') === alert.active) && matches(alert, query));
    const list = el('div', 'ca-cards');
    visible.forEach(alert => list.append(card(alert)));
    const nodes = [];
    const activeCount = alerts.filter(alert => alert.active).length;
    nodes.push(el('p', 'ca-count', i18n.plural('ca.list.count', alerts.length, alerts.length, activeCount, page.dataset.maximumActive)));
    nodes.push(visible.length ? list : el('p', 'wl-muted', i18n.t('ca.list.noMatches')));
    nodes.push(templateGallery(true));
    root.replaceChildren(...nodes);
    refreshWhileChecking();
  }

  // While stocks of a new or edited alert are being checked, the counts are re-read every few seconds; a tick
  // is skipped while the user is in a menu, a dialog, an expanded state list or a field, so nothing jumps.
  let refreshTimer;
  function refreshWhileChecking() {
    clearTimeout(refreshTimer);
    if (!alerts.some(alert => alert.active && alert.checking > 0)) return;
    refreshTimer = setTimeout(async () => {
      const busy = document.hidden || document.querySelector('details.ca-menu[open], dialog[open], .ca-card-states:not([hidden])')
        || root.contains(document.activeElement) || document.activeElement?.matches?.('input, textarea, select');
      if (busy) { refreshWhileChecking(); return; }
      try {
        alerts = await api('/api/custom-alerts');
        render();
      } catch (ignored) {
        refreshWhileChecking();
      }
    }, 4000);
  }

  /** How far the checks of a newly covered alert are, with a native progress bar (no inline styles under the CSP). */
  function progress(alert) {
    const box = el('div', 'ca-progress');
    const done = Math.max(0, alert.stocks - alert.checking);
    const bar = el('progress');
    bar.max = Math.max(1, alert.stocks);
    bar.value = done;
    bar.setAttribute('aria-label', i18n.t('ca.card.progressAria', alert.name));
    box.append(el('span', null, i18n.plural('ca.card.progress', alert.stocks, done, alert.stocks)), bar);
    return box;
  }

  function emptyState() {
    const box = el('div', 'ca-empty');
    box.append(el('h3', null, i18n.t('ca.empty.title')), el('p', 'wl-muted', i18n.t('ca.empty.text')));
    const actions = el('div', 'wl-actions');
    actions.append(link(i18n.t('ca.empty.scratch'), '/custom-alerts/new', 'btn-accent'));
    box.append(actions, templateGallery(false));
    return box;
  }

  /** Starter rules; under the list they fold away, on an empty page they are the main way in. */
  function templateGallery(folded) {
    const grid = el('div', 'ca-templates');
    text.templates.forEach(template => {
      const item = el('a', 'ca-template');
      item.href = `/custom-alerts/new?template=${encodeURIComponent(template.id)}`;
      item.append(el('strong', null, i18n.t(`ca.template.${template.id}.name`)),
        el('span', null, i18n.t(`ca.template.${template.id}.text`)));
      item.append(text.chips(template.definition, definitions, 3));
      grid.append(item);
    });
    const heading = el(folded ? 'summary' : 'h3', 'ca-templates-heading', i18n.t('ca.templates.title'));
    const note = el('p', 'wl-muted', i18n.t('ca.templates.note'));
    if (!folded) { const box = el('section', 'ca-templates-section'); box.append(heading, note, grid); return box; }
    const details = el('details', 'ca-templates-section');
    details.append(heading, note, grid);
    return details;
  }

  function targetsText(alert) {
    if (!alert.targets.length) return null;
    return alert.targets.map(target => target.scope === 'ALL_MEMBERS'
      ? i18n.t('ca.target.all', target.watchlistName, target.stocks)
      : i18n.t('ca.target.selected', target.watchlistName, target.symbols.slice(0, 3).join(', ')
        + (target.symbols.length > 3 ? ` +${target.symbols.length - 3}` : ''))).join(' · ');
  }

  function card(alert) {
    const article = el('article', `ca-card${alert.active ? '' : ' is-paused'}`);
    const archive = `/signals?customAlertId=${alert.id}`;
    // The whole card opens the alert's triggers; its own controls keep their clicks.
    article.addEventListener('click', event => {
      if (event.target.closest('a, button, summary, details, input, label, .ca-card-states')) return;
      window.location.assign(archive);
    });

    const main = el('div', 'ca-card-main');
    const title = el('div', 'ca-card-title');
    const name = link(alert.name, archive, 'ca-card-name');
    name.setAttribute('aria-label', i18n.t('ca.card.openAria', alert.name));
    title.append(name);
    if (alert.pinned) title.append(el('span', 'ca-pin', i18n.t('ca.card.pinned')));
    if (alert.unread) title.append(el('span', 'ca-unread', i18n.plural('ca.card.unread', alert.unread, alert.unread)));
    const tags = el('div', 'ca-card-tags');
    tags.append(el('span', `ca-status ${alert.active ? 'active' : 'paused'}`, i18n.t(alert.active ? 'ca.status.active' : 'ca.status.paused')));
    tags.append(el('span', 'dashboard-chip ca-interval', alert.activityOnly ? i18n.t('ca.card.onFilings', text.intervalLabel(alert.interval)) : text.intervalLabel(alert.interval)));
    tags.append(el('span', `ca-mode`, i18n.t(`ca.mode.short.${alert.triggerMode}`)));
    if (!alert.emailEnabled) tags.append(el('span', 'ca-mode', i18n.t('ca.card.noEmail')));
    main.append(title, tags);
    if (alert.description) main.append(el('p', 'ca-card-description', alert.description));
    main.append(text.chips(alert.definition, definitions, 4));
    const targets = targetsText(alert);
    const where = el('p', `ca-card-targets${targets ? '' : ' is-warning'}`, targets
      ? i18n.t('ca.card.appliedTo', targets) : i18n.t('ca.card.notApplied'));
    main.append(where);
    if (alert.active && alert.checking > 0 && alert.stocks > 0) main.append(progress(alert));

    const stats = el('div', 'ca-card-stats');
    const matching = el('button', 'ca-stat ca-stat-button');
    matching.type = 'button';
    matching.setAttribute('aria-expanded', 'false');
    matching.append(el('strong', null, String(alert.matching)), explained(i18n.t('ca.card.matchingNow'), LEARN_OPTIONS.matching));
    const statesBox = el('div', 'ca-card-states');
    statesBox.hidden = true;
    matching.addEventListener('click', () => toggleStates(alert, matching, statesBox));
    stats.append(matching);
    if (alert.checking) stats.append(stat(alert.checking, i18n.t('ca.card.checking'), LEARN_OPTIONS.checking));
    stats.append(stat(alert.triggers, i18n.plural('ca.card.triggers', alert.triggers), LEARN_OPTIONS.triggers));
    stats.append(stat(alert.lastTriggeredAt ? date(alert.lastTriggeredAt) : '—', i18n.t('ca.card.lastTriggered')));

    const actions = el('div', 'ca-card-actions');
    actions.append(link(i18n.t('ca.card.edit'), `/custom-alerts/${alert.id}/edit`, 'btn-secondary'));
    actions.append(switchButton(alert));
    actions.append(menu(alert));
    article.append(main, stats, actions, statesBox);
    return article;
  }

  /** A label that learning mode explains (js.learn.<key>). */
  function explained(label, key) {
    const span = el('span', key ? 'learn-term' : null, label);
    if (key) { span.dataset.learn = key; span.dataset.learnTitle = label; }
    return span;
  }

  const stat = (value, label, key) => {
    const box = el('div', 'ca-stat'); box.append(el('strong', null, String(value)), explained(label, key)); return box;
  };

  function switchButton(alert) {
    const control = el('button', 'ca-switch');
    control.type = 'button';
    control.setAttribute('role', 'switch');
    control.setAttribute('aria-checked', String(alert.active));
    control.append(el('span', 'ca-switch-track'), el('span', 'ca-switch-label', i18n.t(alert.active ? 'ca.switch.on' : 'ca.switch.off')));
    control.setAttribute('aria-label', i18n.t('ca.switch.aria', alert.name));
    control.classList.add('learn-control');
    control.dataset.learn = LEARN_OPTIONS.active;
    control.dataset.learnTitle = i18n.t('ca.switch.title');
    control.addEventListener('click', async () => {
      control.disabled = true;
      try { replace(await api(`/api/custom-alerts/${alert.id}/active?value=${!alert.active}`, { method: 'POST' })); }
      catch (error) { notify(error.message); control.disabled = false; }
    });
    return control;
  }

  function menu(alert) {
    const details = el('details', 'row-menu ca-menu');
    const summary = el('summary');
    summary.setAttribute('aria-label', i18n.t('ca.menu.aria', alert.name));
    summary.title = i18n.t('ca.menu.title');
    summary.append(el('span', null, '···'));
    const panel = el('div', 'row-menu-panel');
    const action = (label, handler, className = '') => {
      const b = el('button', className, label); b.type = 'button';
      b.addEventListener('click', async () => { details.open = false; await handler(); });
      panel.append(b);
    };
    action(i18n.t('ca.menu.duplicate'), () => post(`/api/custom-alerts/${alert.id}/duplicate`, created => alerts.unshift(created)));
    ['DAILY', 'WEEKLY', 'MONTHLY'].filter(interval => interval !== alert.interval).forEach(interval =>
      action(i18n.t('ca.menu.duplicateAs', text.intervalLabel(interval).toLowerCase()),
        () => post(`/api/custom-alerts/${alert.id}/duplicate?interval=${interval}`, created => alerts.unshift(created))));
    action(i18n.t(alert.emailEnabled ? 'ca.menu.emailOff' : 'ca.menu.emailOn'),
      () => post(`/api/custom-alerts/${alert.id}/email?value=${!alert.emailEnabled}`, replace));
    action(i18n.t(alert.pinned ? 'ca.menu.unpin' : 'ca.menu.pin'),
      () => post(`/api/custom-alerts/${alert.id}/pinned?value=${!alert.pinned}`, replace));
    action(i18n.t('ca.menu.delete'), () => confirmDelete(alert), 'danger-text');
    details.append(summary, panel);
    return details;
  }

  async function post(url, after) {
    try { after(await api(url, { method: 'POST' })); render(); }
    catch (error) { notify(error.message); }
  }

  function replace(updated) {
    alerts = alerts.map(alert => alert.id === updated.id ? updated : alert);
    render();
  }

  function notify(message) {
    const status = document.querySelector('[data-ca-read-status]');
    if (status) status.textContent = message;
  }

  function confirmDelete(alert) {
    const dialog = el('dialog', 'wl-dialog ca-confirm');
    const heading = el('h2', null, i18n.t('ca.delete.title', alert.name));
    heading.id = 'ca-delete-title';
    dialog.setAttribute('aria-labelledby', heading.id);
    const detail = el('p', 'wl-muted', alert.triggers
      ? i18n.plural('ca.delete.withTriggers', alert.triggers, alert.triggers) : i18n.t('ca.delete.text'));
    const hint = el('p', 'wl-muted', i18n.t('ca.delete.pauseHint'));
    const status = el('p', 'wl-status');
    const actions = el('div', 'wl-actions');
    const cancel = button(i18n.t('common.cancel'), () => dialog.close());
    const pause = button(i18n.t('ca.delete.pauseInstead'), async () => {
      dialog.close();
      if (alert.active) await post(`/api/custom-alerts/${alert.id}/active?value=false`, replace);
    });
    const remove = button(i18n.t('ca.menu.delete'), async () => {
      remove.disabled = true;
      try {
        await fetchDelete(`/api/custom-alerts/${alert.id}`);
        alerts = alerts.filter(item => item.id !== alert.id);
        dialog.close();
        render();
      } catch (error) { status.textContent = error.message; remove.disabled = false; }
    }, 'danger-outline-button');
    actions.append(cancel, alert.active ? pause : el('span'), remove);
    dialog.append(heading, detail, hint, status, actions);
    dialog.addEventListener('close', () => dialog.remove());
    document.body.append(dialog);
    dialog.showModal();
  }

  async function fetchDelete(url) {
    const token = document.querySelector('meta[name="_csrf"]')?.content || document.getElementById('accountThemeSync')?.dataset.csrfToken;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    const response = await fetch(url, { method: 'DELETE', credentials: 'same-origin', headers: token ? { [header]: token } : {} });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.error || i18n.t('common.saveFailed'));
    }
  }

  /** The stocks an alert watches, matching ones first, each with "stop for this stock". */
  async function toggleStates(alert, trigger, box) {
    const open = trigger.getAttribute('aria-expanded') !== 'true';
    trigger.setAttribute('aria-expanded', String(open));
    box.hidden = !open;
    if (!open) return;
    box.replaceChildren(el('p', 'wl-status', i18n.t('ca.states.loading')));
    try {
      const states = await api(`/api/custom-alerts/${alert.id}/states`);
      if (!states.length) { box.replaceChildren(el('p', 'wl-muted', i18n.t('ca.states.none'))); return; }
      const table = el('table', 'ca-states-table');
      const head = el('tr');
      ['stock', 'state', 'action'].forEach(key => { const th = el('th', null, i18n.t(`ca.states.column.${key}`)); th.scope = 'col'; head.append(th); });
      const thead = el('thead'); thead.append(head);
      const body = el('tbody');
      states.forEach(state => {
        const row = el('tr', `ca-state-${state.state.toLowerCase()}`);
        const stock = el('td');
        stock.append(link(state.symbol, `/stock/${encodeURIComponent(state.symbol)}`), el('small', null, state.companyName || ''));
        const cell = el('td');
        const pill = el('span', `ca-state-pill learn-term ${state.state.toLowerCase()}`, i18n.t(`ca.state.${state.state}`));
        pill.dataset.learn = LEARN_OPTIONS[state.state];
        pill.dataset.learnTitle = i18n.t(`ca.state.${state.state}`);
        cell.append(pill);
        if (state.detail && state.state !== 'MATCHING') cell.append(el('small', null, state.detail));
        const act = el('td');
        const toggle = button(i18n.t(state.excluded ? 'ca.states.resume' : 'ca.states.stop'), async () => {
          toggle.disabled = true;
          try {
            replaceQuiet(await api(`/api/custom-alerts/${alert.id}/stocks/${encodeURIComponent(state.symbol)}?stopped=${!state.excluded}`, { method: 'POST' }));
            trigger.setAttribute('aria-expanded', 'false');
            await toggleStates(alerts.find(item => item.id === alert.id), trigger, box);
          } catch (error) { toggle.disabled = false; notify(error.message); }
        }, 'wl-row-action');
        act.append(toggle);
        row.append(stock, cell, act);
        body.append(row);
      });
      table.append(thead, body);
      const wrap = el('div', 'wl-table-wrap'); wrap.append(table);
      box.replaceChildren(wrap);
    } catch (error) {
      box.replaceChildren(el('p', 'wl-status', error.message));
    }
  }

  function replaceQuiet(updated) {
    alerts = alerts.map(alert => alert.id === updated.id ? updated : alert);
  }

  // ------------------------------------------------------------------ chart alerts

  async function renderChartAlerts() {
    if (chartAlerts == null) {
      root.replaceChildren(el('p', 'wl-status', i18n.t('ca.loading')));
      try { chartAlerts = (await api('/api/chart-alerts/all')).alerts; }
      catch (error) { root.replaceChildren(el('p', 'wl-status', error.message), button(i18n.t('common.retry'), renderChartAlerts)); return; }
    }
    const query = (search?.value || '').trim().toLowerCase();
    const intro = el('p', 'wl-muted ca-chart-intro', i18n.t('ca.chart.intro'));
    if (!chartAlerts.length) { root.replaceChildren(intro, el('p', 'wl-muted', i18n.t('ca.chart.none'))); return; }
    const list = el('div', 'ca-chart-list');
    chartAlerts.filter(alert => !query || `${alert.label} ${alert.symbol}`.toLowerCase().includes(query)).forEach(alert => {
      const row = el('article', `ca-chart-row${alert.active ? '' : ' is-paused'}`);
      const main = el('div', 'ca-chart-main');
      main.append(el('strong', null, alert.label));
      const meta = el('span', 'wl-muted', [alert.symbol, i18n.t(`interval.${({ '1d': 'DAILY', '1wk': 'WEEKLY', '1mo': 'MONTHLY' })[alert.interval] || 'DAILY'}`),
        i18n.t(alert.sourceKind === 'DRAWING' ? 'ca.chart.drawing' : 'ca.chart.indicator'),
        i18n.has(`chartAlert.condition.${alert.condition}`) ? i18n.t(`chartAlert.condition.${alert.condition}`) : alert.condition,
        i18n.plural('ca.chart.triggers', alert.triggerCount, alert.triggerCount)].join(' · '));
      main.append(meta);
      const actions = el('div', 'ca-card-actions');
      actions.append(link(i18n.t('ca.chart.open'), `/stock/${encodeURIComponent(alert.symbol)}`, 'btn-secondary'));
      if (convertible(alert)) {
        const add = link(i18n.t('ca.chart.convert'), `/custom-alerts/new?chartAlert=${alert.id}`, 'btn-secondary learn-control');
        add.dataset.learn = LEARN_OPTIONS.convert;
        add.dataset.learnTitle = i18n.t('ca.chart.convert');
        actions.append(add);
      }
      const toggle = button(i18n.t(alert.active ? 'ca.chart.pause' : 'ca.chart.resume'), async () => {
        toggle.disabled = true;
        try {
          const updated = await api(`/api/chart-alerts/${alert.id}`, { method: 'PATCH', body: JSON.stringify({ active: !alert.active }) });
          chartAlerts = chartAlerts.map(item => item.id === updated.id ? updated : item);
          renderChartAlerts();
        } catch (error) { toggle.disabled = false; notify(error.message); }
      });
      const remove = button(i18n.t('ca.menu.delete'), async () => {
        remove.disabled = true;
        try {
          await fetchDelete(`/api/chart-alerts/${alert.id}`);
          chartAlerts = chartAlerts.filter(item => item.id !== alert.id);
          renderChartAlerts();
        } catch (error) { remove.disabled = false; notify(error.message); }
      }, 'danger-outline-button');
      actions.append(toggle, remove);
      row.append(main, actions);
      list.append(row);
    });
    root.replaceChildren(intro, list);
  }

  /** Indicator alerts and horizontal lines become ordinary conditions; sloped drawings stay on their chart. */
  const convertible = alert => alert.sourceKind === 'INDICATOR' || alert.definition?.geometry === 'HORIZONTAL';

  const saved = new URLSearchParams(window.location.search).get('saved');
  if (saved) {
    notify(i18n.t('ca.list.saved'));
    window.history.replaceState(null, '', window.location.pathname);
  }
  document.querySelectorAll('[data-ca-tab]').forEach(tab => tab.addEventListener('click', () => { filter = tab.dataset.caTab; render(); }));
  let timer;
  search?.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(render, 150); });
  document.querySelector('[data-ca-read-all]')?.addEventListener('click', async event => {
    const control = event.currentTarget;
    control.disabled = true;
    try {
      const result = await api('/api/custom-alerts/read-all', { method: 'POST' });
      notify(i18n.plural('ca.readAll.done', result.updated, result.updated));
      alerts = alerts.map(alert => ({ ...alert, unread: 0 }));
      render();
    } catch (error) { notify(error.message); }
    control.disabled = false;
  });
  // Deferred scripts run in document order, so the navigation's request helper may not exist yet.
  if (window.StockWatchLists) load();
  else document.addEventListener('DOMContentLoaded', load, { once: true });
})();
