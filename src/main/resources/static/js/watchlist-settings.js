(() => {
  'use strict';
  const { node: n } = window.StockWatchLists;
  const intervals = ['DAILY', 'WEEKLY', 'MONTHLY'];
  const rows = [
    ['CANDLESTICK', 'BUY', 'Candlestick buy'], ['CANDLESTICK', 'SELL', 'Candlestick sell'],
    ['ELLIOTT_WAVE', 'BUY', 'Elliott Wave buy'], ['ELLIOTT_WAVE', 'SELL', 'Elliott Wave sell'],
    ['HARMONIC_FORMATION', 'BUY', 'Harmonic buy'], ['HARMONIC_FORMATION', 'SELL', 'Harmonic sell'],
    ['OUTLOOK', 'ANY', 'Automated technical outlook'],
  ];
  function create(onChange, { members = false } = {}) {
    const root = n('fieldset', null, 'wl-signal-settings');
    root.append(n('legend', 'Signals to follow'));
    root.append(n('p', members ? 'Change follows for the selected instruments in this watchlist. A dash means only some follow that signal. Untouched choices keep their current follows.' : 'Choose signal types and intervals for this list. Followed signals always appear on the website, whether email is enabled or disabled.', 'wl-muted'));
    const controls = [], initial = new Map();
    const typeNames = { CANDLESTICK: 'Candlestick', ELLIOTT_WAVE: 'Elliott Wave', HARMONIC_FORMATION: 'Harmonic', OUTLOOK: 'Automated technical outlook', CONGRESS: 'Congressional activity', INSIDER: 'Insider activity' };
    const permissions = new Map(), initialPermissions = new Map();
    let followCounts = {};
    const allowed = n('fieldset', null, 'wl-allowed-types');
    allowed.append(n('legend', 'Allowed signal types'), n('p', 'Choose which types this watchlist can follow. Allowing a type does not automatically follow it.', 'wl-muted'));
    for (const [type, title] of Object.entries(typeNames)) {
      const label = n('label', null, 'wl-check'), input = n('input'); input.type = 'checkbox'; input.checked = true;
      input.setAttribute('aria-label', `Allow ${title}`);
      input.addEventListener('change', () => { updateAllowedControls(); changed(); });
      permissions.set(type, input); label.append(input, n('span', title)); allowed.append(label);
    }
    if (!members) root.append(allowed);
    function updateAllowedControls() {
      controls.forEach(value => { value.watch.disabled = !permissions.get(value.type).checked; });
    }
    let initialEmail = false;
    const email = n('input'); email.type = 'checkbox';
    email.setAttribute('aria-label', 'Enable email notifications');
    const emailLabel = n('label', null, 'wl-check');
    emailLabel.append(email, n('span', 'Enable email notifications'));
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
      watch.setAttribute('aria-label', `Watch ${title}`);
      watch.addEventListener('change', changed);
      label.append(watch, n('span', 'Follow')); cell.append(label);
      controls.push({ type, interval, direction, watch }); return cell;
    }
    function watchGroup(predicate) {
      const targets = controls.filter(value => !value.watch.disabled && predicate(value)), enabled = !targets.every(value => value.watch.checked);
      targets.forEach(value => { value.watch.checked = enabled; value.watch.indeterminate = false; }); changed();
    }
    const technical = value => !value.watch.disabled && !['CONGRESS', 'INSIDER'].includes(value.type);
    const actions = n('div', null, 'wl-actions');
    actions.append(button('Watch all technical signals', () => { controls.filter(technical).forEach(value => { value.watch.checked = true; value.watch.indeterminate = false; }); changed(); }),
      button('Clear technical signals', () => { controls.filter(technical).forEach(value => { value.watch.checked = false; value.watch.indeterminate = false; }); changed(); }));
    root.append(actions, n('h3', 'Technical signals'));
    const wrap = n('div', null, 'wl-settings-table-wrap'), table = n('table', null, 'wl-settings-table');
    wrap.tabIndex = 0; wrap.setAttribute('role', 'region'); wrap.setAttribute('aria-label', 'Technical signal settings by type and interval');
    const head = n('thead'), header = n('tr'); header.append(n('th', 'Signal type'));
    intervals.forEach(interval => {
      const th = n('th'); th.scope = 'col';
      th.append(button(interval[0] + interval.slice(1).toLowerCase(), () => watchGroup(value => technical(value) && value.interval === interval)));
      th.firstChild.setAttribute('aria-label', `Toggle all ${interval.toLowerCase()} technical follows`); header.append(th);
    });
    head.append(header); table.append(head); const body = n('tbody'); table.append(body);
    for (const [type, direction, title] of rows) {
      const row = n('tr'), th = n('th'); th.scope = 'row';
      th.append(n('span', title), button('All intervals', () => watchGroup(value => value.type === type && value.direction === direction)));
      th.lastChild.setAttribute('aria-label', `Toggle all intervals for ${title}`); row.append(th);
      for (const interval of intervals) { const td = n('td'); td.append(followControl(type, interval, direction, `${title} ${interval.toLowerCase()}`)); row.append(td); }
      body.append(row);
    }
    root.append(n('p', 'Scroll the table sideways to see all intervals.', 'wl-muted wl-settings-scroll-hint'));
    wrap.append(table); root.append(wrap, n('h3', 'Ticker alerts'));
    root.append(n('p', 'Ticker alerts apply to stocks only. Indexes and ETFs are skipped for these two options.', 'wl-muted'));
    const activity = n('div', null, 'wl-ticker-settings');
    for (const [type, title] of [['CONGRESS', 'Congressional trades'], ['INSIDER', 'Corporate insider trades']]) {
      const item = n('div'); item.append(n('strong', title), followControl(type, 'DAILY', 'ANY', title)); activity.append(item);
    }
    root.append(activity, n('p', members ? 'Email settings are managed in the watchlist editor.' : 'Email notifications cover followed signals and their follow-up outcomes. Overlapping watchlists send one email if any matching list enables email. Your account email preferences still apply.', 'wl-muted'));
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
      mixed.textContent = 'Some instruments have individual follows. Changing signal selections applies them to every instrument in this list. Changing email notifications preserves those individual follows.';
      updateAllowedControls();
    }
    set(null);
    const monitoringChanged = () => controls.some(value => permissions.get(value.type).checked && value.watch.checked !== initial.get(key(value)).watch);
    const restrictionsChanged = () => [...permissions].some(([type, input]) => input.checked !== initialPermissions.get(type));
    const allowedTypes = () => [...permissions].filter(([, input]) => input.checked).map(([type]) => type);
    const restrictionSummary = () => {
      const removed = [...permissions].filter(([type, input]) => !input.checked && initialPermissions.get(type));
      const total = removed.reduce((sum, [type]) => sum + (followCounts[type] || 0), 0);
      return removed.length ? `Disallow ${removed.map(([type]) => typeNames[type]).join(', ')}. Remove ${total} existing follows from this watchlist. Saved history and other watchlists are preserved.` : 'Allow the selected signal types. Existing follows stay unchanged.';
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
