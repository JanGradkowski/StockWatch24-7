(() => {
  'use strict';
  // The Custom alerts page: every alert as a row (its name opens the alert's own page), its counts, switch and menu,
  // the stocks it watches with their state, the starter examples and the alerts set on charts.
  const root = document.querySelector('[data-ca-list]');
  if (!root) return;
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  // The shared request helper (watchlist-picker.js) loads with the navigation, after this script.
  const api = (...args) => window.StockWatchLists.api(...args);
  const el = text.element;
  // Learning-mode explanations (js.learn.<key>) used on this page.
  const LEARN_OPTIONS = { active: 'customAlerts.active', checking: 'customAlerts.checking', convert: 'customAlerts.convert', matching: 'customAlerts.matching',
    triggers: 'customAlerts.triggers', HALFWAY: 'customAlerts.state.HALFWAY' };
  const page = document.querySelector('.custom-alerts-page');
  const search = document.querySelector('[data-ca-search]');
  const toolbar = document.querySelector('[data-ca-toolbar]');
  const guide = document.querySelector('[data-ca-guide]');
  const guideToggle = document.querySelector('[data-ca-guide-toggle]');
  const readAll = document.querySelector('[data-ca-read-all]');
  const tabs = [...document.querySelectorAll('[data-ca-tab]')];
  let alerts = [];
  let definitions = new Map();
  let filter = 'all';
  let loaded = false;

  const compact = () => window.matchMedia('(max-width: 768px)').matches;
  const alertUrl = alert => `/custom-alerts/${alert.id}`;
  const link = (label, href, className = 'text-link') => { const a = el('a', className, label); a.href = href; return a; };
  const button = (label, onClick, className = 'btn-secondary') => {
    const b = el('button', className, label); b.type = 'button'; b.addEventListener('click', onClick); return b;
  };

  // ------------------------------------------------------------------ the guide

  function setGuide(open) {
    guide.hidden = !open;
    guideToggle.setAttribute('aria-expanded', String(open));
  }
  guideToggle?.addEventListener('click', () => {
    setGuide(guide.hidden);
    if (!guide.hidden) guide.querySelector('[data-ca-guide-close]')?.focus();
  });
  document.querySelector('[data-ca-guide-close]')?.addEventListener('click', () => { setGuide(false); guideToggle.focus(); });
  // Learning mode opens the guide; afterwards the reader decides.
  if (guide && document.documentElement.dataset.learningMode === 'on') setGuide(true);

  // ------------------------------------------------------------------ loading and the list

  async function load() {
    try {
      const [list, catalog] = await Promise.all([api('/api/custom-alerts'), text.catalog().catch(() => null)]);
      alerts = list;
      definitions = catalog ? catalog.byType : new Map();
      loaded = true;
      render();
      loadChartAlerts();
    } catch (error) {
      root.replaceChildren(el('p', 'ca-muted', error.message), button(i18n.t('common.retry'), load));
    }
  }

  function matches(alert, query) {
    if (!query) return true;
    const haystack = [alert.name, alert.description, ...alert.targets.flatMap(target => [target.watchlistName, ...target.symbols])]
      .join(' ').toLowerCase();
    return haystack.includes(query);
  }

  function render() {
    const has = alerts.length > 0;
    toolbar.hidden = !has;
    // With no alerts the empty state carries the way in (and the assistant's button), so the header stays empty.
    document.querySelector('[data-ca-page-actions]').hidden = !has;
    readAll.hidden = !alerts.some(alert => alert.unread);
    const active = alerts.filter(alert => alert.active).length;
    const counts = { all: alerts.length, active, paused: alerts.length - active };
    tabs.forEach(tab => {
      const selected = tab.dataset.caTab === filter;
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
      tab.querySelector('[data-ca-count]').textContent = String(counts[tab.dataset.caTab]);
    });
    document.querySelector('[data-ca-slots]').textContent = i18n.t('ca.list.slots', active, page.dataset.maximumActive);
    if (!has) { root.replaceChildren(emptyState()); return; }
    const query = (search?.value || '').trim().toLowerCase();
    const visible = alerts.filter(alert => (filter === 'all' || (filter === 'active') === alert.active) && matches(alert, query));
    if (!visible.length) {
      root.replaceChildren(el('p', 'ca-empty-filter', i18n.t(query ? 'ca.list.noSearchMatches' : `ca.list.none.${filter}`, query)));
      return;
    }
    const list = el('div', 'ca-rows');
    list.setAttribute('role', 'list');
    visible.forEach(alert => list.append(row(alert)));
    root.replaceChildren(list);
    refreshWhileChecking();
  }

  // While stocks of a new or edited alert are being checked, the counts are re-read every few seconds; a tick
  // is skipped while the user is in a menu, a dialog, an expanded state list or a field, so nothing jumps.
  let refreshTimer;
  function refreshWhileChecking() {
    clearTimeout(refreshTimer);
    if (!alerts.some(alert => alert.active && alert.checking > 0)) return;
    refreshTimer = setTimeout(async () => {
      const busy = document.hidden || document.querySelector('details.ca-menu[open], .ca-row-states:not([hidden])') || text.modalOpen()
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

  /** "Daily · Growth (25 stocks) · Once per stock": the timeframes, the stocks and any firing that is not the usual. */
  function meta(alert) {
    const parts = [alert.activityOnly ? i18n.t('ca.card.onFilings', text.intervalsLabel(alert.intervals)) : text.intervalsLabel(alert.intervals)];
    alert.targets.forEach(target => parts.push(target.scope === 'ALL_MEMBERS'
      ? i18n.plural('ca.target.allStocks', target.stocks, target.watchlistName, target.stocks)
      : i18n.t('ca.target.selected', target.watchlistName, target.symbols.slice(0, 3).join(', ')
        + (target.symbols.length > 3 ? ` +${target.symbols.length - 3}` : ''))));
    if (alert.triggerMode !== 'BECOMES_TRUE') parts.push(i18n.t(`ca.mode.short.${alert.triggerMode}`));
    return parts.join(' · ');
  }

  /** How far the checks of a newly covered alert are, with a native progress bar (no inline styles under the CSP). */
  function progress(alert) {
    const box = el('div', 'ca-progress');
    const done = Math.max(0, alert.stocks - alert.checking);
    const bar = el('progress');
    bar.max = Math.max(1, alert.stocks);
    bar.value = done;
    bar.setAttribute('aria-label', i18n.t('ca.card.progressAria', alert.name));
    box.append(bar, el('span', null, i18n.plural('ca.card.progress', alert.stocks, done, alert.stocks)));
    return box;
  }

  /** A label that learning mode explains (js.learn.<key>). */
  function explained(label, key, className = '') {
    const span = el('span', `${className}${key ? ' learn-term' : ''}`.trim() || null, label);
    if (key) { span.dataset.learn = key; span.dataset.learnTitle = label; }
    return span;
  }

  function row(alert) {
    const item = el('article', `ca-row${alert.active ? '' : ' is-paused'}`);
    item.setAttribute('role', 'listitem');
    item.setAttribute('aria-labelledby', `ca-row-name-${alert.id}`);

    const main = el('div', 'ca-row-main');
    const title = el('div', 'ca-row-title');
    const name = link(alert.name, alertUrl(alert), 'ca-row-name');
    name.id = `ca-row-name-${alert.id}`;
    title.append(name);
    if (alert.pinned) {
      const pin = el('span', 'ca-pin');
      pin.title = i18n.t('ca.card.pinned');
      pin.append(text.icon('pin'), el('span', 'visually-hidden', i18n.t('ca.card.pinned')));
      title.append(pin);
    }
    if (!alert.active) title.append(el('span', 'ca-badge', i18n.t('ca.status.paused')));
    if (alert.unread) {
      const unread = el('span', 'ca-unread');
      unread.append(el('i', 'ca-dot'), i18n.plural('ca.card.unread', alert.unread, alert.unread));
      title.append(unread);
    }
    const info = el('p', 'ca-row-meta');
    info.append(el('span', null, meta(alert)));
    if (alert.emailEnabled) {
      const mail = el('span', 'ca-row-mail');
      mail.title = i18n.t('ca.card.emailOn');
      mail.append(text.icon('mail'), el('span', 'visually-hidden', i18n.t('ca.card.emailOn')));
      info.append(mail);
    }
    main.append(title, info);
    if (alert.description) main.append(el('p', 'ca-row-description', alert.description));
    main.append(text.chips(alert.definition, definitions, 2));
    if (!alert.targets.length) main.append(el('p', 'ca-row-warning', i18n.t('ca.card.notApplied')));
    if (alert.active && alert.checking > 0 && alert.stocks > 0) main.append(progress(alert));

    const stats = el('div', 'ca-row-stats');
    const states = el('div', 'ca-row-states');
    states.hidden = true;
    const matching = el('button', `ca-stat ca-stat-button${alert.matching ? ' is-positive' : ''}`);
    matching.type = 'button';
    matching.setAttribute('aria-expanded', 'false');
    matching.setAttribute('aria-controls', `ca-row-states-${alert.id}`);
    states.id = `ca-row-states-${alert.id}`;
    const matchingLabel = el('span', 'ca-stat-label');
    matchingLabel.append(explained(i18n.t('ca.card.matching'), LEARN_OPTIONS.matching), text.icon('chevron', 'ca-icon ca-stat-chevron'));
    matching.append(el('strong', 'ca-stat-value', String(alert.matching)), matchingLabel);
    if (alert.halfway) matching.append(explained(i18n.t('ca.card.halfwayCount', alert.halfway), LEARN_OPTIONS.HALFWAY, 'ca-stat-extra'));
    matching.addEventListener('click', () => toggleStates(alert, matching, states));
    const triggers = link('', alertUrl(alert), 'ca-stat ca-stat-link');
    triggers.append(el('strong', 'ca-stat-value', String(alert.triggers)),
      explained(i18n.plural('ca.card.triggers', alert.triggers), LEARN_OPTIONS.triggers, 'ca-stat-label'));
    triggers.setAttribute('aria-label', i18n.plural('ca.card.triggersAria', alert.triggers, alert.triggers, alert.name));
    const last = el('div', 'ca-stat ca-stat-last');
    const when = el('strong', 'ca-stat-value', alert.lastTriggeredAt ? text.relativeDay(alert.lastTriggeredAt) : '—');
    if (alert.lastTriggeredAt) when.title = text.dateTime(alert.lastTriggeredAt);
    last.append(when, el('span', 'ca-stat-label', i18n.t('ca.card.lastTrigger')));
    stats.append(matching, triggers, last);

    const actions = el('div', 'ca-row-actions');
    const edit = link(i18n.t('ca.card.edit'), `/custom-alerts/${alert.id}/edit`, 'btn-secondary ca-btn-sm');
    edit.setAttribute('aria-label', i18n.t('ca.card.editAria', alert.name));
    edit.title = i18n.t('ca.card.editShortcut');
    actions.append(edit, switchButton(alert), menu(alert));
    item.append(main, stats, actions, states);
    return item;
  }

  /** Turns an alert on or off at once, then saves it; the message offers to undo, and a failure puts it back. */
  async function setActive(alert, active, offerUndo = true) {
    const before = alerts.find(item => item.id === alert.id);
    if (!before || before.active === active) return;
    alerts = alerts.map(item => item.id === alert.id ? { ...item, active } : item);
    render();
    document.querySelector(`#ca-row-name-${alert.id}`)?.closest('.ca-row')?.querySelector('.ca-switch')?.focus();
    try {
      replace(await api(`/api/custom-alerts/${alert.id}/active?value=${active}`, { method: 'POST' }));
      if (!offerUndo) { notify(''); return; }
      const undo = el('button', 'ca-text-button ca-undo');
      undo.type = 'button';
      undo.append(text.icon('undo'), el('span', null, i18n.t('ca.builder.undo')));
      undo.addEventListener('click', () => setActive(alert, !active, false));
      const status = document.querySelector('[data-ca-read-status]');
      status.replaceChildren(i18n.t(active ? 'ca.list.resumed' : 'ca.list.paused', alert.name), ' ', undo);
    } catch (error) {
      alerts = alerts.map(item => item.id === alert.id ? before : item);
      render();
      notify(error.message);
    }
  }

  function switchButton(alert) {
    const control = text.toggleSwitch(alert.active, i18n.t('ca.switch.aria', alert.name), () => setActive(alert, !alert.active));
    control.classList.add('learn-control');
    control.dataset.learn = LEARN_OPTIONS.active;
    control.dataset.learnTitle = i18n.t('ca.switch.title');
    control.title = `${control.title} (X)`;
    return control;
  }

  function menu(alert) {
    const others = ['DAILY', 'WEEKLY', 'MONTHLY'].filter(interval => alert.intervals.length > 1 || interval !== alert.intervals[0]);
    return text.menu(i18n.t('ca.menu.aria', alert.name), [
      { label: i18n.t('ca.menu.duplicate'), icon: 'copy', run: () => post(`/api/custom-alerts/${alert.id}/duplicate`, created => alerts.unshift(created)) },
      { label: i18n.t('ca.menu.duplicateAsLabel'), choices: others.map(interval => ({ label: text.intervalLabel(interval),
        run: () => post(`/api/custom-alerts/${alert.id}/duplicate?interval=${interval}`, created => alerts.unshift(created)) })) },
      { label: i18n.t(alert.pinned ? 'ca.menu.unpin' : 'ca.menu.pin'), icon: 'pin',
        run: () => post(`/api/custom-alerts/${alert.id}/pinned?value=${!alert.pinned}`, replace) },
      { label: i18n.t(alert.emailEnabled ? 'ca.menu.emailOff' : 'ca.menu.emailOn'), icon: 'mail',
        run: () => post(`/api/custom-alerts/${alert.id}/email?value=${!alert.emailEnabled}`, replace) },
      'divider',
      { label: i18n.t('ca.menu.deleteEllipsis'), icon: 'trash', danger: true, run: () => confirmDelete(alert) }
    ]);
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

  /** Deleting asks first, names what goes with the alert, and offers pausing instead. */
  function confirmDelete(alert) {
    const dialog = el('dialog', 'ca-dialog ca-confirm');
    const heading = el('h2', null, i18n.t('ca.delete.title'));
    heading.id = 'ca-delete-title';
    dialog.setAttribute('aria-labelledby', heading.id);
    const detail = el('p', 'ca-dialog-text');
    const strong = el('strong', null, alert.name);
    const rest = alert.triggers ? i18n.plural('ca.delete.withTriggers', alert.triggers, alert.triggers) : i18n.t('ca.delete.text');
    detail.append(strong, ' ', rest);
    const hint = el('p', 'ca-muted', i18n.t('ca.delete.pauseHint'));
    const status = el('p', 'ca-dialog-status');
    status.setAttribute('role', 'status');
    const actions = el('div', 'ca-dialog-actions');
    const cancel = button(i18n.t('common.cancel'), () => dialog.close(), 'btn-secondary ca-btn-ghost');
    const pause = button(i18n.t('ca.delete.pauseInstead'), async () => {
      dialog.close();
      await post(`/api/custom-alerts/${alert.id}/active?value=false`, replace);
    });
    const remove = button(i18n.t('ca.delete.confirm'), async () => {
      remove.disabled = true;
      try {
        await fetchDelete(`/api/custom-alerts/${alert.id}`);
        alerts = alerts.filter(item => item.id !== alert.id);
        dialog.close();
        render();
        notify(i18n.t('ca.delete.done', alert.name));
      } catch (error) { status.textContent = error.message; remove.disabled = false; }
    }, 'ca-btn-danger');
    actions.append(cancel);
    if (alert.active) actions.append(pause);
    actions.append(remove);
    dialog.append(heading, detail);
    if (alert.active) dialog.append(hint);
    dialog.append(status, actions);
    dialog.addEventListener('close', () => dialog.remove());
    document.body.append(dialog);
    dialog.showModal();
    cancel.focus();
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

  /** The stocks an alert watches, opened under its row from the "matching" count. */
  async function toggleStates(alert, trigger, box) {
    const open = trigger.getAttribute('aria-expanded') !== 'true';
    trigger.setAttribute('aria-expanded', String(open));
    trigger.closest('.ca-row').classList.toggle('is-expanded', open);
    box.hidden = !open;
    if (!open) return;
    const heading = el('p', 'ca-row-states-title', i18n.plural('ca.states.title', alert.stocks, alert.stocks));
    const list = el('div');
    box.replaceChildren(heading, list);
    await window.StockWatchCustomAlertStates.render(list, alert, {
      onChange: updated => { alerts = alerts.map(item => item.id === updated.id ? updated : item); },
      onError: notify
    });
  }

  // ------------------------------------------------------------------ examples

  /** Starter rules as cards, after a "Blank rule" card; each opens the builder filled in with it. */
  function exampleCards(limit = Infinity, withBlank = true) {
    const grid = el('div', 'ca-templates');
    if (withBlank) {
      const blank = link('', '/custom-alerts/new', 'ca-template ca-template-blank');
      const plus = el('span', 'ca-template-plus');
      plus.append(text.icon('plus'));
      blank.append(plus, el('strong', 'ca-template-name', i18n.t('ca.templates.blank')), el('span', 'ca-template-text', i18n.t('ca.templates.blankText')));
      grid.append(blank);
    }
    text.templates.slice(0, limit).forEach(template => {
      const item = el('a', 'ca-template');
      item.href = `/custom-alerts/new?template=${encodeURIComponent(template.id)}`;
      item.append(el('strong', 'ca-template-name', i18n.t(`ca.template.${template.id}.name`)),
        el('span', 'ca-template-text', i18n.t(`ca.template.${template.id}.text`)),
        text.chips(template.definition, definitions, 3));
      const use = el('span', 'ca-template-use', i18n.t('ca.templates.use'));
      use.append(text.icon('arrow'));
      item.append(use);
      grid.append(item);
    });
    return grid;
  }

  function emptyState() {
    const box = el('section', 'ca-empty');
    box.setAttribute('aria-labelledby', 'ca-empty-title');
    const intro = el('div', 'ca-empty-intro');
    const heading = el('h2', null, i18n.t('ca.empty.title'));
    heading.id = 'ca-empty-title';
    const blankRule = link(i18n.t('ca.templates.blank'), '/custom-alerts/new', 'btn-accent');
    const actions = el('div', 'ca-empty-actions');
    actions.append(blankRule);
    const ask = text.askButton();
    if (ask) { ask.classList.add('is-labelled'); ask.querySelector('.ca-ask-label').textContent = i18n.t('assistant.open'); actions.append(ask); }
    intro.append(heading, el('p', null, i18n.t('ca.empty.text')), actions);
    const examples = el('div', 'ca-empty-examples');
    const shown = compact() ? 3 : Infinity;
    const grid = exampleCards(shown, false);
    examples.append(el('h3', null, i18n.t('ca.templates.title')), el('p', 'ca-muted', i18n.t('ca.templates.note')), grid);
    if (shown < text.templates.length) {
      const more = button(i18n.t('ca.templates.showAll', text.templates.length), () => { grid.replaceWith(exampleCards(Infinity, false)); more.remove(); },
        'btn-secondary ca-show-all');
      examples.append(more);
    }
    box.append(intro, examples);
    return box;
  }

  /** "New alert ▾": the examples in a dialog, for people who already have alerts. */
  function openExamples() {
    const dialog = el('dialog', 'ca-dialog ca-examples-dialog');
    const head = el('div', 'ca-dialog-head');
    const heading = el('h2', null, i18n.t('ca.templates.title'));
    heading.id = 'ca-examples-title';
    dialog.setAttribute('aria-labelledby', heading.id);
    const close = el('button', 'ca-icon-button');
    close.type = 'button';
    close.setAttribute('aria-label', i18n.t('common.close'));
    close.title = i18n.t('common.close');
    close.append(text.icon('close'));
    close.addEventListener('click', () => dialog.close());
    head.append(heading, close);
    const body = el('div', 'ca-dialog-body');
    body.append(el('p', 'ca-muted', i18n.t('ca.templates.note')), exampleCards());
    dialog.append(head, body);
    dialog.addEventListener('close', () => dialog.remove());
    document.body.append(dialog);
    dialog.showModal();
    close.focus();
  }
  document.querySelector('[data-ca-examples]')?.addEventListener('click', openExamples);

  // ------------------------------------------------------------------ chart alerts

  const chartSection = document.querySelector('[data-ca-chart-alerts]');
  let chartAlerts = [];

  async function loadChartAlerts() {
    try { chartAlerts = (await api('/api/chart-alerts/all')).alerts || []; }
    catch (error) { chartAlerts = []; }
    renderChartAlerts();
  }

  function renderChartAlerts() {
    if (!chartSection) return;
    chartSection.hidden = false;
    chartSection.querySelector('[data-ca-chart-count]').textContent = String(chartAlerts.length);
    const box = chartSection.querySelector('[data-ca-chart-list]');
    if (!chartAlerts.length) { box.replaceChildren(el('p', 'ca-muted', i18n.t('ca.chart.none'))); return; }
    const list = el('div', 'ca-rows ca-chart-rows');
    list.setAttribute('role', 'list');
    chartAlerts.forEach(alert => {
      const item = el('article', `ca-chart-row${alert.active ? '' : ' is-paused'}`);
      item.setAttribute('role', 'listitem');
      const main = el('div', 'ca-chart-main');
      const title = el('div', 'ca-row-title');
      title.append(el('strong', 'ca-chart-name', alert.label));
      if (!alert.active) title.append(el('span', 'ca-badge', i18n.t('ca.status.paused')));
      main.append(title, el('p', 'ca-row-meta', [alert.symbol,
        i18n.t(`interval.${({ '1d': 'DAILY', '1wk': 'WEEKLY', '1mo': 'MONTHLY' })[alert.interval] || 'DAILY'}`),
        i18n.t(alert.sourceKind === 'DRAWING' ? 'ca.chart.drawing' : 'ca.chart.indicator'),
        i18n.has(`chartAlert.condition.${alert.condition}`) ? i18n.t(`chartAlert.condition.${alert.condition}`) : alert.condition,
        i18n.plural('ca.chart.triggers', alert.triggerCount, alert.triggerCount)].join(' · ')));
      const actions = el('div', 'ca-row-actions');
      actions.append(link(i18n.t('ca.chart.open'), `/stock/${encodeURIComponent(alert.symbol)}`, 'btn-secondary ca-btn-sm'));
      if (convertible(alert)) {
        const add = link(i18n.t('ca.chart.convert'), `/custom-alerts/new?chartAlert=${alert.id}`, 'btn-secondary ca-btn-sm learn-control');
        add.dataset.learn = LEARN_OPTIONS.convert;
        add.dataset.learnTitle = i18n.t('ca.chart.convert');
        actions.append(add);
      }
      actions.append(text.menu(i18n.t('ca.menu.aria', alert.label), [
        { label: i18n.t(alert.active ? 'ca.chart.pause' : 'ca.chart.resume'), run: async () => {
          try {
            const updated = await api(`/api/chart-alerts/${alert.id}`, { method: 'PATCH', body: JSON.stringify({ active: !alert.active }) });
            chartAlerts = chartAlerts.map(other => other.id === updated.id ? updated : other);
            renderChartAlerts();
          } catch (error) { notify(error.message); }
        } },
        'divider',
        { label: i18n.t('ca.menu.delete'), icon: 'trash', danger: true, run: async () => {
          try {
            await fetchDelete(`/api/chart-alerts/${alert.id}`);
            chartAlerts = chartAlerts.filter(other => other.id !== alert.id);
            renderChartAlerts();
          } catch (error) { notify(error.message); }
        } }
      ]));
      item.append(main, actions);
      list.append(item);
    });
    box.replaceChildren(list);
  }

  /** Indicator alerts and horizontal lines become ordinary conditions; sloped drawings stay on their chart. */
  const convertible = alert => alert.sourceKind === 'INDICATOR' || alert.definition?.geometry === 'HORIZONTAL';

  // ------------------------------------------------------------------ controls

  const saved = new URLSearchParams(window.location.search).get('saved');
  if (saved) {
    notify(i18n.t('ca.list.saved'));
    window.history.replaceState(null, '', window.location.pathname);
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => { filter = tab.dataset.caTab; render(); });
    // Arrow keys move between the filters, as in any tab list.
    tab.addEventListener('keydown', event => {
      const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
      if (!step) return;
      event.preventDefault();
      const next = tabs[(index + step + tabs.length) % tabs.length];
      filter = next.dataset.caTab;
      render();
      next.focus();
    });
  });
  let timer;
  search?.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(render, 150); });
  text.escapeClears(search);
  text.shortcuts([
    [['/'], i18n.t('ca.shortcuts.search')], [['n'], i18n.t('ca.shortcuts.new')], [['j', 'k'], i18n.t('ca.shortcuts.move')],
    [['Enter'], i18n.t('ca.shortcuts.open')], [['e'], i18n.t('ca.shortcuts.edit')], [['x'], i18n.t('ca.shortcuts.toggle')],
  ]);
  // "/" jumps to the search and "n" starts a new alert, unless the user is typing.
  document.addEventListener('keydown', event => {
    if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.target instanceof Element && event.target.closest('input, textarea, select, [contenteditable="true"], dialog')) return;
    if (event.key === '/' && loaded && alerts.length) { event.preventDefault(); search.focus(); }
    else if (event.key === 'n') window.location.assign('/custom-alerts/new');
    else if (event.key === 'j' || event.key === 'k') {
      const names = [...root.querySelectorAll('.ca-row-name')];
      if (!names.length) return;
      const at = names.indexOf(document.activeElement);
      const next = names[Math.max(0, Math.min(names.length - 1, at < 0 ? 0 : at + (event.key === 'j' ? 1 : -1)))];
      event.preventDefault();
      next.focus();
      next.closest('.ca-row').scrollIntoView({ block: 'nearest' });
    } else if (event.key === 'x') {
      const row = document.activeElement?.closest?.('.ca-row');
      if (row) { event.preventDefault(); row.querySelector('.ca-switch')?.click(); }
    } else if (event.key === 'e') {
      const row = document.activeElement?.closest?.('.ca-row');
      const edit = row?.querySelector('.ca-row-actions a');
      if (edit) window.location.assign(edit.href);
    }
  });
  readAll?.addEventListener('click', async () => {
    readAll.disabled = true;
    try {
      const result = await api('/api/custom-alerts/read-all', { method: 'POST' });
      notify(i18n.plural('ca.readAll.done', result.updated, result.updated));
      alerts = alerts.map(alert => ({ ...alert, unread: 0 }));
      render();
    } catch (error) { notify(error.message); }
    readAll.disabled = false;
  });
  // Rows switch between their wide and narrow layouts; the number of chips shown follows.
  let wasCompact = compact();
  window.addEventListener('resize', () => {
    if (compact() === wasCompact || !loaded) return;
    wasCompact = compact();
    render();
  });
  // Deferred scripts run in document order, so the navigation's request helper may not exist yet.
  if (window.StockWatchLists) load();
  else document.addEventListener('DOMContentLoaded', load, { once: true });
})();
