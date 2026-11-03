(() => {
  'use strict';
  const { node: n } = window.StockWatchLists;
  const i18n = window.StockWatchI18n;
  const intervals = ['DAILY', 'WEEKLY', 'MONTHLY'];
  const rows = [
    ['CANDLESTICK', 'BUY'], ['CANDLESTICK', 'SELL'], ['ELLIOTT_WAVE', 'BUY'], ['ELLIOTT_WAVE', 'SELL'],
    ['HARMONIC_FORMATION', 'BUY'], ['HARMONIC_FORMATION', 'SELL'], ['OUTLOOK', 'ANY'],
  ].map(([type, direction]) => [type, direction, i18n.t(`watchlistSettings.row.${type}.${direction}`)]);
  /**
   * The "Signals to follow" settings of a watchlist (or, with `members`, of selected instruments): email, a
   * type-by-interval grid of technical signals, ticker alerts and, tucked away, the signal types the list allows.
   */
  function create(onChange, { members = false } = {}) {
    const root = n('fieldset', null, `wl-signal-settings${members ? ' is-members' : ''}`);
    root.append(n('legend', i18n.t('watchlistSettings.legend')));
    root.append(n('p', i18n.t(members ? 'watchlistSettings.membersIntro' : 'watchlistSettings.intro'), 'wl-muted wl-settings-intro'));
    const controls = [], initial = new Map();
    // Every type the server knows: a type missing here would be switched off whenever the list is saved.
    const typeNames = Object.fromEntries(['CANDLESTICK', 'ELLIOTT_WAVE', 'HARMONIC_FORMATION', 'OUTLOOK', 'CONGRESS', 'INSIDER', 'CUSTOM']
      .map(type => [type, i18n.t(`followType.${type}`)]));
    const permissions = new Map(), initialPermissions = new Map();
    let followCounts = {};
    const changed = () => { onChange(); };
    function button(text, handler, style = 'wl-text-button') {
      const b = n('button', text, style); b.type = 'button'; b.addEventListener('click', handler); return b;
    }

    // Email: one switch for the whole list.
    let initialEmail = false;
    const email = n('input'); email.type = 'checkbox'; email.setAttribute('role', 'switch');
    email.setAttribute('aria-label', i18n.t('watchlistSettings.emailEnable'));
    email.addEventListener('change', changed);
    const emailLabel = n('label', null, 'wl-switch-row'), emailText = n('span', null, 'wl-switch-text');
    emailText.append(n('strong', i18n.t('watchlistSettings.emailTitle')), n('small', i18n.t('watchlistSettings.emailHelp')));
    emailLabel.append(emailText, email);
    if (!members) root.append(emailLabel);

    function followControl(type, interval, direction, title, container) {
      const watch = n('input'); watch.type = 'checkbox';
      watch.setAttribute('aria-label', i18n.t('watchlistSettings.watchAria', title));
      watch.addEventListener('change', changed);
      controls.push({ type, interval, direction, watch, container }); return watch;
    }
    function watchGroup(predicate) {
      const targets = controls.filter(value => !value.watch.disabled && predicate(value)), enabled = !targets.every(value => value.watch.checked);
      targets.forEach(value => { value.watch.checked = enabled; value.watch.indeterminate = false; }); changed();
    }
    const technical = value => !value.watch.disabled && !['CONGRESS', 'INSIDER'].includes(value.type);
    const setTechnical = checked => { controls.filter(technical).forEach(value => { value.watch.checked = checked; value.watch.indeterminate = false; }); changed(); };

    // Technical signals: rows are signal types and directions, columns are intervals.
    const technicalSection = n('section', null, 'wl-settings-section'), technicalHead = n('div', null, 'wl-settings-head');
    const quick = n('div', null, 'wl-settings-quick');
    const selectAll = button(i18n.t('watchlistSettings.selectAll'), () => setTechnical(true));
    selectAll.setAttribute('aria-label', i18n.t('watchlistSettings.watchAllTechnical'));
    const clearAll = button(i18n.t('watchlistSettings.clearAll'), () => setTechnical(false));
    clearAll.setAttribute('aria-label', i18n.t('watchlistSettings.clearTechnical'));
    quick.append(selectAll, clearAll);
    technicalHead.append(n('h3', i18n.t('watchlistSettings.technicalHeading')), quick);
    const table = n('table', null, 'wl-settings-table'); table.setAttribute('aria-label', i18n.t('watchlistSettings.tableAria'));
    const head = n('thead'), header = n('tr'), corner = n('th', i18n.t('watchlistSettings.signalType')); corner.scope = 'col';
    header.append(corner);
    intervals.forEach(interval => {
      const th = n('th'); th.scope = 'col';
      const toggle = button(i18n.t(`interval.${interval}`), () => watchGroup(value => technical(value) && value.interval === interval), 'wl-col-toggle');
      toggle.setAttribute('aria-label', i18n.t(`watchlistSettings.toggleInterval.${interval}`)); toggle.title = toggle.getAttribute('aria-label');
      th.append(toggle); header.append(th);
    });
    head.append(header); table.append(head); const body = n('tbody'); table.append(body);
    let previousType = null;
    for (const [type, direction, title] of rows) {
      const row = n('tr', null, type !== previousType ? 'wl-row-start' : ''); previousType = type;
      const th = n('th'); th.scope = 'row';
      const label = n('div', null, 'wl-signal-label'), name = n('span', null, 'wl-signal-name');
      name.append(n('span', typeNames[type]));
      if (direction === 'BUY' || direction === 'SELL') name.append(n('span', i18n.t(`watchlists.dir.${direction}`), `wl-dir is-${direction.toLowerCase()}`));
      const rowToggle = button(i18n.t('watchlistSettings.allIntervals'), () => watchGroup(value => value.type === type && value.direction === direction), 'wl-row-toggle');
      rowToggle.setAttribute('aria-label', i18n.t('watchlistSettings.toggleRowAria', title));
      label.append(name, n('small', i18n.t('watchlistSettings.notAllowed'), 'wl-disallowed-note'));
      const rowHead = n('div', null, 'wl-signal-row-head'); rowHead.append(label, rowToggle);
      th.append(rowHead); row.append(th);
      for (const interval of intervals) {
        const td = n('td'), cell = n('label', null, 'wl-matrix-cell');
        cell.append(followControl(type, interval, direction, i18n.t(`watchlistSettings.rowInterval.${interval}`, title), row));
        td.append(cell); row.append(td);
      }
      body.append(row);
    }
    const tableWrap = n('div', null, 'wl-settings-table-wrap'); tableWrap.append(table);
    technicalSection.append(technicalHead, tableWrap);
    root.append(technicalSection);

    // Ticker alerts: congressional and insider trades, for stocks only.
    const tickerSection = n('section', null, 'wl-settings-section'), tickerHead = n('div', null, 'wl-settings-head');
    tickerHead.append(n('h3', i18n.t('watchlistSettings.tickerHeading')), n('span', i18n.t('watchlistSettings.stocksOnly'), 'wl-tag'));
    const activity = n('div', null, 'wl-ticker-settings');
    for (const [type, title] of ['CONGRESS', 'INSIDER'].map(type => [type, i18n.t(`watchlistSettings.ticker.${type}`)])) {
      const option = n('label', null, 'wl-option');
      option.append(followControl(type, 'DAILY', 'ANY', title, option), n('span', title));
      activity.append(option);
    }
    tickerSection.append(tickerHead, n('p', i18n.t('watchlistSettings.tickerHelp'), 'wl-muted'), activity);
    root.append(tickerSection);
    if (members) root.append(n('p', i18n.t('watchlistSettings.membersEmail'), 'wl-muted'));

    // Allowed signal types: rarely changed, so it sits in a closed section that opens when a type is restricted.
    const advanced = n('details', null, 'wl-advanced'), advancedSummary = n('summary'), allowedHint = n('span', '', 'wl-advanced-hint');
    advancedSummary.append(n('span', i18n.t('watchlistSettings.allowedLegend'), 'wl-advanced-title'), allowedHint);
    const allowed = n('fieldset', null, 'wl-allowed-types');
    allowed.append(n('legend', i18n.t('watchlistSettings.allowedLegend'), 'visually-hidden'), n('p', i18n.t('watchlistSettings.allowedHelp'), 'wl-muted'));
    const allowedGrid = n('div', null, 'wl-allowed-grid');
    for (const [type, title] of Object.entries(typeNames)) {
      const label = n('label', null, 'wl-check'), input = n('input'); input.type = 'checkbox'; input.checked = true;
      input.setAttribute('aria-label', i18n.t('watchlistSettings.allowAria', title));
      input.addEventListener('change', () => { updateAllowedControls(); changed(); });
      permissions.set(type, input); label.append(input, n('span', title)); allowedGrid.append(label);
    }
    allowed.append(allowedGrid, n('p', i18n.t('watchlistSettings.customNote'), 'wl-muted'));
    advanced.append(advancedSummary, allowed);
    if (!members) root.append(advanced);
    function updateAllowedControls() {
      controls.forEach(value => { value.watch.disabled = !permissions.get(value.type).checked; });
      controls.forEach(value => value.container.classList.toggle('is-disallowed',
        controls.filter(other => other.container === value.container).every(other => other.watch.disabled)));
      const count = [...permissions.values()].filter(input => input.checked).length;
      allowedHint.textContent = i18n.t('watchlistSettings.allowedSummary', count, permissions.size);
    }

    const key = value => `${value.type}:${value.interval}:${value.direction}`;
    const mixed = n('p', '', 'wl-status wl-settings-mixed'); mixed.hidden = true; root.append(mixed);
    function read() { return controls.map(value => ({ type: value.type, interval: value.interval, direction: value.direction, watch: permissions.get(value.type).checked && value.watch.checked, email: email.checked })); }
    function set(view) {
      const byKey = new Map((view?.selections || []).map(value => [key(value), value])); initial.clear();
      email.checked = (view?.selections || []).some(value => value.email);
      initialEmail = email.checked;
      followCounts = view?.followCounts || {};
      permissions.forEach((input, type) => { input.checked = !view?.allowedTypes || view.allowedTypes.includes(type); initialPermissions.set(type, input.checked); });
      controls.forEach(value => {
        const saved = byKey.get(key(value)); value.watch.checked = !!saved?.watch;
        value.watch.indeterminate = !!view?.mixedKeys?.includes(key(value));
        initial.set(key(value), { watch: value.watch.checked, mixed: value.watch.indeterminate });
      });
      mixed.hidden = !view?.mixed;
      mixed.textContent = i18n.t('watchlistSettings.mixed');
      advanced.open = [...permissions.values()].some(input => !input.checked);
      updateAllowedControls();
    }
    set(null);
    const monitoringChanged = () => controls.some(value => permissions.get(value.type).checked && value.watch.checked !== initial.get(key(value)).watch);
    const restrictionsChanged = () => [...permissions].some(([type, input]) => input.checked !== initialPermissions.get(type));
    const allowedTypes = () => [...permissions].filter(([, input]) => input.checked).map(([type]) => type);
    const restrictionSummary = () => {
      const removed = [...permissions].filter(([type, input]) => !input.checked && initialPermissions.get(type));
      const total = removed.reduce((sum, [type]) => sum + (followCounts[type] || 0), 0);
      return removed.length
        ? i18n.plural('watchlistSettings.disallow', total, total, removed.map(([type]) => typeNames[type]).join(', '))
        : i18n.t('watchlistSettings.allowSelected');
    };
    const readChanges = () => read().filter(value => {
      const control = controls.find(control => key(control) === key(value));
      return !control.watch.disabled && !control.watch.indeterminate
        && (initial.get(key(value)).mixed || value.watch !== initial.get(key(value)).watch);
    });
    return { root, read, readChanges, set, monitoringChanged, restrictionsChanged, allowedTypes, restrictionSummary,
      changed: () => email.checked !== initialEmail || monitoringChanged() || restrictionsChanged() };
  }
  window.StockWatchListSettings = { create };
})();
