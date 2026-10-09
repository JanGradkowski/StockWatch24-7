(() => {
  'use strict';
  const { node: n } = window.StockWatchLists;
  const i18n = window.StockWatchI18n;
  const intervals = ['DAILY', 'WEEKLY', 'MONTHLY'];
  const rows = [
    ['CANDLESTICK', 'BUY'], ['CANDLESTICK', 'SELL'], ['ELLIOTT_WAVE', 'BUY'], ['ELLIOTT_WAVE', 'SELL'],
    ['HARMONIC_FORMATION', 'BUY'], ['HARMONIC_FORMATION', 'SELL'], ['OUTLOOK', 'ANY'],
  ].map(([type, direction]) => [type, direction, i18n.t(`watchlistSettings.row.${type}.${direction}`)]);
  function create(onChange, { members = false } = {}) {
    const root = n('fieldset', null, 'wl-signal-settings');
    root.append(n('legend', i18n.t('watchlistSettings.legend')));
    root.append(n('p', i18n.t(members ? 'watchlistSettings.membersIntro' : 'watchlistSettings.intro'), 'wl-muted'));
    const controls = [], initial = new Map();
    const typeNames = Object.fromEntries(['CANDLESTICK', 'ELLIOTT_WAVE', 'HARMONIC_FORMATION', 'OUTLOOK', 'CONGRESS', 'INSIDER']
      .map(type => [type, i18n.t(`followType.${type}`)]));
    const permissions = new Map(), initialPermissions = new Map();
    let followCounts = {};
    const allowed = n('fieldset', null, 'wl-allowed-types');
    allowed.append(n('legend', i18n.t('watchlistSettings.allowedLegend')), n('p', i18n.t('watchlistSettings.allowedHelp'), 'wl-muted'));
    for (const [type, title] of Object.entries(typeNames)) {
      const label = n('label', null, 'wl-check'), input = n('input'); input.type = 'checkbox'; input.checked = true;
      input.setAttribute('aria-label', i18n.t('watchlistSettings.allowAria', title));
      input.addEventListener('change', () => { updateAllowedControls(); changed(); });
      permissions.set(type, input); label.append(input, n('span', title)); allowed.append(label);
    }
    if (!members) root.append(allowed);
    function updateAllowedControls() {
      controls.forEach(value => { value.watch.disabled = !permissions.get(value.type).checked; });
    }
    let initialEmail = false;
    const email = n('input'); email.type = 'checkbox';
    email.setAttribute('aria-label', i18n.t('watchlistSettings.emailEnable'));
    const emailLabel = n('label', null, 'wl-check');
    emailLabel.append(email, n('span', i18n.t('watchlistSettings.emailEnable')));
    if (!members) root.append(emailLabel);
    const key = value => `${value.type}:${value.interval}:${value.direction}`;
    const changed = () => { onChange(); };
    email.addEventListener('change', changed);
    function button(text, handler) {
      const b = n('button', text, 'btn-secondary'); b.type = 'button'; b.addEventListener('click', handler); return b;
    }
    function followControl(type, interval, direction, title) {
      const cell = n('div', null, 'wl-setting-pair');
      const label = n('label', null, 'wl-check'), watch = n('input'); watch.type = 'checkbox';
      watch.setAttribute('aria-label', i18n.t('watchlistSettings.watchAria', title));
      watch.addEventListener('change', changed);
      label.append(watch, n('span', i18n.t('watchlistSettings.follow'))); cell.append(label);
      controls.push({ type, interval, direction, watch }); return cell;
    }
    function watchGroup(predicate) {
      const targets = controls.filter(value => !value.watch.disabled && predicate(value)), enabled = !targets.every(value => value.watch.checked);
      targets.forEach(value => { value.watch.checked = enabled; value.watch.indeterminate = false; }); changed();
    }
    const technical = value => !value.watch.disabled && !['CONGRESS', 'INSIDER'].includes(value.type);
    const actions = n('div', null, 'wl-actions');
    actions.append(button(i18n.t('watchlistSettings.watchAllTechnical'), () => { controls.filter(technical).forEach(value => { value.watch.checked = true; value.watch.indeterminate = false; }); changed(); }),
      button(i18n.t('watchlistSettings.clearTechnical'), () => { controls.filter(technical).forEach(value => { value.watch.checked = false; value.watch.indeterminate = false; }); changed(); }));
    root.append(actions, n('h3', i18n.t('watchlistSettings.technicalHeading')));
    const wrap = n('div', null, 'wl-settings-table-wrap'), table = n('table', null, 'wl-settings-table');
    wrap.tabIndex = 0; wrap.setAttribute('role', 'region'); wrap.setAttribute('aria-label', i18n.t('watchlistSettings.tableAria'));
    const head = n('thead'), header = n('tr'); header.append(n('th', i18n.t('watchlistSettings.signalType')));
    intervals.forEach(interval => {
      const th = n('th'); th.scope = 'col';
      th.append(button(i18n.t(`interval.${interval}`), () => watchGroup(value => technical(value) && value.interval === interval)));
      th.firstChild.setAttribute('aria-label', i18n.t(`watchlistSettings.toggleInterval.${interval}`)); header.append(th);
    });
    head.append(header); table.append(head); const body = n('tbody'); table.append(body);
    for (const [type, direction, title] of rows) {
      const row = n('tr'), th = n('th'); th.scope = 'row';
      th.append(n('span', title), button(i18n.t('watchlistSettings.allIntervals'), () => watchGroup(value => value.type === type && value.direction === direction)));
      th.lastChild.setAttribute('aria-label', i18n.t('watchlistSettings.toggleRowAria', title)); row.append(th);
      for (const interval of intervals) { const td = n('td'); td.append(followControl(type, interval, direction, i18n.t(`watchlistSettings.rowInterval.${interval}`, title))); row.append(td); }
      body.append(row);
    }
    root.append(n('p', i18n.t('watchlistSettings.scrollHint'), 'wl-muted wl-settings-scroll-hint'));
    wrap.append(table); root.append(wrap, n('h3', i18n.t('watchlistSettings.tickerHeading')));
    root.append(n('p', i18n.t('watchlistSettings.tickerHelp'), 'wl-muted'));
    const activity = n('div', null, 'wl-ticker-settings');
    for (const [type, title] of ['CONGRESS', 'INSIDER'].map(type => [type, i18n.t(`watchlistSettings.ticker.${type}`)])) {
      const item = n('div'); item.append(n('strong', title), followControl(type, 'DAILY', 'ANY', title)); activity.append(item);
    }
    root.append(activity, n('p', i18n.t(members ? 'watchlistSettings.membersEmail' : 'watchlistSettings.emailHelp'), 'wl-muted'));
    const mixed = n('p', '', 'wl-status'); mixed.hidden = true; root.append(mixed);
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
