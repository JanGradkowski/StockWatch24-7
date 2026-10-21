(() => {
  'use strict';
  const { api, node: n } = window.StockWatchLists;
  const i18n = window.StockWatchI18n;
  const geo = name => {
    const key = 'geo.' + String(name).replace(/[^A-Za-z]/g, ''), text = i18n.t(key);
    return text === key ? name : text;
  };
  const byLocalName = (a, b) => geo(a).localeCompare(geo(b), i18n.tag);
  const listSummary = list => [i18n.plural('watchlists.instruments', list.instruments, list.instruments),
    i18n.plural('watchlists.followed', list.monitored, list.monitored), i18n.plural('watchlists.unread', list.unread, list.unread)].join(' · ');
  const stockCount = count => i18n.plural('watchlists.stocks', count, count);
  const root = document.querySelector('[data-named-watchlists]');
  // Learning-mode explanations (js.learn.<key>) used on this page.
  const LEARN_OPTIONS = { customStrip: 'customAlerts.watchlistStrip', customChip: 'customAlerts.watchlistChip' };
  let lists = [];
  const selections = new Map();
  const button = (text, handler, style = 'btn-secondary') => {
    const b = n('button', text, style); b.type = 'button'; b.addEventListener('click', handler); return b;
  };
  const link = (text, href) => { const a = n('a', text, 'text-link'); a.href = href; return a; };
  function actionIcon(kind) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true'); svg.setAttribute('focusable', 'false');
    const path = document.createElementNS(svg.namespaceURI, 'path');
    path.setAttribute('d', {
      signals: 'M4 19h16M5 14l4-5 4 3 6-8',
      manage: 'M4 7h6m4 0h6M4 17h10m4 0h2M10 4v6m4 4v6',
      remove: 'M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13M10 10v7m4-7v7'
    }[kind]);
    svg.append(path); return svg;
  }
  function field(text, type = 'text', value = '') {
    const label = n('label', text, 'wl-field'); const input = n(type === 'textarea' ? 'textarea' : 'input');
    if (type !== 'textarea') input.type = type; input.value = value; label.append(input); return { label, input };
  }
  function select(labelText, options) {
    const label = n('label', labelText, 'wl-field'), input = n('select');
    input.setAttribute('aria-label', labelText);
    for (const [value, text] of options) { const o = n('option', text); o.value = value; input.append(o); }
    label.append(input); return { label, input };
  }
  function modal(title) {
    const dialog = n('dialog', null, 'wl-dialog'); const heading = n('h2', title);
    heading.id = `wl-dialog-${Date.now()}`; dialog.setAttribute('aria-labelledby', heading.id); dialog.append(heading);
    const focused = document.activeElement;
    dialog.addEventListener('close', () => { dialog.remove(); focused?.focus(); });
    document.body.append(dialog); dialog.showModal(); return dialog;
  }
  async function refresh() {
    try {
      lists = await api('/api/watchlists'); render();
      const shortcuts = document.querySelector('[data-watchlist-shortcuts]');
      if (shortcuts) {
        shortcuts.replaceChildren();
        lists.slice(0, 5).forEach(list => shortcuts.append(link(list.name, `/watchlists/${list.id}`)));
      }
    } catch (error) { if (root) { root.replaceChildren(n('p', error.message, 'wl-status'), button(i18n.t('common.retry'), refresh)); } }
  }
  function render() {
    if (!root) return;
    const openIds = new Set(Array.from(root.querySelectorAll('details[open]'), e => e.dataset.id));
    const search = document.querySelector('[data-watchlist-search]')?.value.trim().toLowerCase() || '';
    root.replaceChildren();
    if (!lists.length) {
      const empty = n('div', null, 'wl-empty');
      empty.append(n('h3', i18n.t('watchlists.emptyTitle')), n('p', i18n.t('watchlists.emptyText'), 'wl-muted'), button(i18n.t('watchlists.create'), () => edit(), 'btn-accent'));
      root.append(empty); return;
    }
    const visible = lists.filter(list => (!root.dataset.selectedId || String(list.id) === root.dataset.selectedId)
      && `${list.name} ${list.description}`.toLowerCase().includes(search));
    if (!visible.length) root.append(n('p', i18n.t('watchlists.noMatches'), 'wl-muted'));
    for (const list of visible) {
      if (root.dataset.selectedId) {
        const title = document.querySelector('.app-hero h1');
        if (title) title.textContent = list.name;
        const overview = n('div', null, 'wl-overview');
        overview.append(n('p', listSummary(list), 'wl-muted'));
        if (list.description) overview.append(n('p', list.description, 'wl-muted'));
        root.append(overview); memberTable(list, overview); continue;
      }
      const details = n('details', null, 'wl-accordion'); details.dataset.id = String(list.id);
      const summary = n('summary'); const heading = n('div', null, 'wl-heading');
      heading.append(n('strong', `${list.pinned ? '★ ' : ''}${list.name}`), n('small', listSummary(list)));
      if (list.description) heading.append(n('small', list.description));
      summary.append(heading); details.append(summary);
      const body = n('div', null, 'wl-body'); details.append(body);
      let loaded = false;
      details.addEventListener('toggle', () => { if (details.open && !loaded) { loaded = true; memberTable(list, body); } });
      details.open = openIds.has(String(list.id)) || String(list.id) === root.dataset.selectedId;
      root.append(details);
    }
  }
  function memberTable(list, body) {
    if (!selections.has(list.id)) selections.set(list.id, new Set());
    const selected = selections.get(list.id);
    const actions = n('div', null, 'wl-actions');
    if (!root.dataset.selectedId) actions.append(link(i18n.t('watchlists.open'), `/watchlists/${list.id}`));
    const markRead = n('button', i18n.t('watchlists.markAllRead'), 'btn-secondary'); markRead.type = 'button';
    markRead.dataset.notificationsReadAll = ''; markRead.dataset.readWatchlist = String(list.id);
    markRead.title = i18n.t('watchlists.markAllReadTitle');
    const readStatus = n('span', '', 'wl-status'); readStatus.dataset.notificationsReadStatus = ''; readStatus.setAttribute('role', 'status');
    actions.append(link(i18n.t('watchlists.viewAllSignals'), `/signals?watchlistId=${list.id}`), markRead, button(i18n.t('watchlists.addOrEdit'), () => edit(list)), button(i18n.t(list.pinned ? 'watchlists.unpin' : 'watchlists.pin'), async () => {
      try { await api(`/api/watchlists/${list.id}`, { method: 'PUT', body: JSON.stringify({ ...list, pinned: !list.pinned }) }); await refresh(); }
      catch (error) { status.textContent = error.message; }
    }), button(i18n.t('watchlists.delete'), () => confirmDelete(list), 'danger-outline-button'));
    actions.append(readStatus);
    const toolbar = n('div', null, 'wl-toolbar');
    const searchField = field(i18n.t('watchlists.search'), 'search'), search = searchField.input;
    search.placeholder = i18n.t('watchlists.searchPlaceholder'); search.setAttribute('aria-label', i18n.t('watchlists.searchAria', list.name));
    const group = select(i18n.t('watchlists.instrumentType'), ['all', 'stocks', 'funds'].map(value => [value, i18n.t(`watchlists.group.${value}`)]));
    toolbar.append(searchField.label, group.label);
    const status = n('p', '', 'wl-status'); status.setAttribute('role', 'status');
    const tableArea = n('div', null, 'wl-table-wrap'), pages = n('div', null, 'wl-actions');
    tableArea.tabIndex = 0; tableArea.setAttribute('role', 'region'); tableArea.setAttribute('aria-label', i18n.t('watchlists.tableAria', list.name));
    const bulk = n('div', null, 'wl-actions wl-bulk-actions'); bulk.setAttribute('aria-label', i18n.t('watchlists.bulkAria'));
    const selectedCount = n('span', '', 'wl-muted'); selectedCount.setAttribute('role', 'status');
    const viewSelected = link(i18n.t('watchlists.viewSignals'), '#'); viewSelected.className = 'btn-accent';
    viewSelected.addEventListener('click', event => { if (!selected.size) event.preventDefault(); });
    const manageSelected = button(i18n.t('watchlists.manageFollows'), () => manageFollows(list, [...selected]));
    const removeSelected = button(i18n.t('watchlists.remove'), () => removeMembers(list, [...selected]), 'danger-outline-button');
    const clear = button(i18n.t('watchlists.clearSelection'), () => { selected.clear(); updateSelection(); });
    const selectPage = n('input'); selectPage.type = 'checkbox'; selectPage.setAttribute('aria-label', i18n.t('watchlists.selectPageAria'));
    const pageLabel = n('label', null, 'wl-check'); pageLabel.append(selectPage, n('span', i18n.t('watchlists.selectPage')));
    let visibleSymbols = [];
    selectPage.addEventListener('change', () => {
      visibleSymbols.forEach(symbol => selectPage.checked ? selected.add(symbol) : selected.delete(symbol)); updateSelection();
    });
    function updateSelection() {
      selectedCount.textContent = i18n.plural('watchlists.selected', selected.size, selected.size);
      viewSelected.href = `/signals?${new URLSearchParams({ watchlistId: list.id, ticker: [...selected].join(',') })}`;
      viewSelected.setAttribute('aria-disabled', String(!selected.size)); viewSelected.tabIndex = selected.size ? 0 : -1;
      manageSelected.disabled = removeSelected.disabled = clear.disabled = !selected.size;
      const checked = visibleSymbols.filter(symbol => selected.has(symbol)).length;
      selectPage.checked = visibleSymbols.length > 0 && checked === visibleSymbols.length;
      selectPage.indeterminate = checked > 0 && checked < visibleSymbols.length;
      selectPage.disabled = !visibleSymbols.length;
      tableArea.querySelectorAll('[data-select-symbol]').forEach(input => {
        input.checked = selected.has(input.dataset.selectSymbol);
        const row = input.closest('tr'); row.classList.toggle('wl-selected', input.checked);
        const view = row.querySelector('.wl-view-action');
        view.href = selected.size ? viewSelected.href : `/signals?${new URLSearchParams({ watchlistId: list.id, ticker: input.dataset.selectSymbol })}`;
        row.querySelector('.wl-row-actions').setAttribute('aria-label', selected.size
          ? i18n.plural('watchlists.selectedActionsAria', selected.size, selected.size) : i18n.t('watchlists.rowActionsAria', input.dataset.selectSymbol));
      });
    }
    bulk.append(pageLabel, selectedCount, viewSelected, manageSelected, removeSelected, clear);
    body.append(actions, customAlerts(list), toolbar, bulk, status, tableArea, pages); updateSelection();
    let page = 0, sequence = 0, timer;
    async function load() {
      const request = ++sequence; status.textContent = i18n.t('watchlists.loadingInstruments');
      try {
        const data = await api(`/api/watchlists/${list.id}/members?${new URLSearchParams({ page, group: group.input.value, q: search.value })}`);
        if (request !== sequence || !body.isConnected) return;
        tableArea.replaceChildren(); pages.replaceChildren();
        status.textContent = data.total
          ? `${i18n.plural('watchlists.instruments', data.total, data.total)} · ${i18n.t('watchlists.pageOf', page + 1, Math.ceil(data.total / data.pageSize))}`
          : i18n.t('watchlists.noInstruments');
        visibleSymbols = data.items.map(item => item.symbol);
        const table = n('table', null, 'wl-table'), head = n('thead'), row = n('tr'), tbody = n('tbody');
        for (const column of ['instrument', 'following', 'unread', 'actions']) { const th = n('th', i18n.t(`watchlists.column.${column}`)); th.scope = 'col'; row.append(th); }
        head.append(row); table.append(head, tbody);
        for (const item of data.items) {
          const tr = n('tr'), instrument = n('td', null, 'wl-instrument-cell');
          const check = n('input'); check.type = 'checkbox'; check.dataset.selectSymbol = item.symbol;
          check.setAttribute('aria-label', i18n.t('watchlists.selectSymbolAria', item.symbol));
          check.addEventListener('change', () => { check.checked ? selected.add(item.symbol) : selected.delete(item.symbol); updateSelection(); });
          const selectionLabel = n('label', null, 'wl-member-select'); selectionLabel.append(check, n('span', i18n.t('watchlists.select')));
          instrument.append(selectionLabel);
          const signalsUrl = `/signals?${new URLSearchParams({ watchlistId: list.id, ticker: item.symbol })}`;
          instrument.append(link(item.symbol, signalsUrl), n('small', item.companyName), n('small', `${item.exchange} · ${item.currency}`)); tr.append(instrument);
          const follows = item.signals || [];
          const types = ['CANDLESTICK', 'ELLIOTT_WAVE', 'HARMONIC_FORMATION', 'OUTLOOK', 'CONGRESS', 'INSIDER'];
          const following = n('td', null, 'wl-following-cell'); following.dataset.label = i18n.t('watchlists.column.following');
          const groups = new Map();
          follows.forEach(value => {
            const key = `${value.type}:${value.direction}`;
            if (!groups.has(key)) groups.set(key, { type: value.type, direction: value.direction, intervals: new Set() });
            groups.get(key).intervals.add(value.interval);
          });
          groups.forEach(({ type, direction, intervals }) => {
            const group = n('div', null, 'wl-follow-group'); group.setAttribute('role', 'group');
            const label = types.includes(type) ? i18n.t(`watchlists.type.${type}`) : type;
            group.setAttribute('aria-label', i18n.t(direction === 'BUY' || direction === 'SELL' ? `watchlists.followsAria.${direction}` : 'watchlists.followsAria', label));
            const tone = { CANDLESTICK: 'candle', ELLIOTT_WAVE: 'elliott', HARMONIC_FORMATION: 'harmonic', OUTLOOK: 'outlook', CONGRESS: 'congress', INSIDER: 'insider' }[type] || 'other';
            group.append(n('span', label, `dashboard-chip wl-type-chip wl-type-${tone}`));
            if (direction !== 'ANY') group.append(n('span', direction === 'BUY' || direction === 'SELL' ? i18n.t(`watchlists.direction.${direction}`) : direction, `dashboard-chip wl-direction-chip ${direction === 'BUY' ? 'buy' : 'sell'}`));
            const periods = n('div', null, 'wl-intervals');
            ['DAILY', 'WEEKLY', 'MONTHLY'].filter(interval => intervals.has(interval)).forEach(interval => {
              periods.append(n('span', i18n.t(`interval.${interval}`), 'dashboard-chip wl-interval-chip'));
            });
            group.append(periods); following.append(group);
          });
          if (!groups.size) following.append(n('span', i18n.t('watchlists.noFollows'), 'wl-no-follows'));
          following.dataset.followingSymbol = item.symbol;
          const unread = n('td', null, 'wl-unread-cell'), count = item.unreadSignalCount || 0;
          const badge = n('span', null, `wl-unread-badge${count > 0 ? ' has-unread' : ''}`);
          badge.append(n('strong', String(count)), n('span', i18n.plural('watchlists.unreadBadge', count))); unread.append(badge);
          tr.append(following, unread);
          const action = n('td', null, 'wl-actions-cell'), actionGroup = n('div', null, 'wl-row-actions');
          actionGroup.setAttribute('role', 'group'); actionGroup.setAttribute('aria-label', i18n.t('watchlists.rowActionsAria', item.symbol));
          const view = link(i18n.t('watchlists.viewSignals'), signalsUrl); view.className = 'wl-row-action wl-view-action'; view.prepend(actionIcon('signals'));
          const manage = button(i18n.t('watchlists.manageFollows'), () => manageFollows(list, selected.size ? [...selected] : [item.symbol]), 'wl-row-action'); manage.prepend(actionIcon('manage'));
          const remove = button(i18n.t('watchlists.remove'), async () => {
            if (selected.size) { removeMembers(list, [...selected]); return; }
            const d = modal(i18n.t('watchlists.removeSymbolTitle', item.symbol));
            d.append(n('p', i18n.t('watchlists.removeSymbolText'), 'wl-muted'));
            const errorText = n('p', '', 'wl-status'), buttons = n('div', null, 'wl-actions');
            const confirm = button(i18n.t('watchlists.removeFromList'), async () => {
              confirm.disabled = true;
              try { await api(`/api/watchlists/${list.id}/members/${encodeURIComponent(item.symbol)}`, { method: 'DELETE' }); d.close(); await refresh(); }
              catch (error) { errorText.textContent = error.message; confirm.disabled = false; }
            }, 'danger-outline-button');
            buttons.append(button(i18n.t('common.cancel'), () => d.close()), confirm); d.append(errorText, buttons);
          }, 'wl-row-action wl-remove-action'); remove.prepend(actionIcon('remove'));
          actionGroup.append(view, manage, remove); action.append(actionGroup); tr.append(action); tbody.append(tr);
        }
        if (data.items.length) tableArea.append(table);
        updateSelection();
        memberCustomAlerts(list, data.items, tbody, request);
        const previous = button(i18n.t('watchlists.previous'), () => { page--; load(); }), next = button(i18n.t('watchlists.next'), () => { page++; load(); });
        previous.disabled = page === 0; next.disabled = (page + 1) * data.pageSize >= data.total; pages.append(previous, next);
      } catch (error) { if (request === sequence) { status.textContent = error.message; pages.replaceChildren(button(i18n.t('common.retry'), load)); } }
    }
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { page = 0; load(); }, 250); });
    group.input.addEventListener('change', () => { page = 0; load(); }); load();

    /** Custom alerts covering the shown stocks, as chips beside their follows (one request per page). */
    async function memberCustomAlerts(current, items, tbody, request) {
      if (!items.length) return;
      try {
        const bySymbol = await api(`/api/custom-alerts/for-watchlist/${current.id}/members?${new URLSearchParams({ symbols: items.map(item => item.symbol).join(',') })}`);
        if (request !== sequence) return;
        tbody.querySelectorAll('[data-following-symbol]').forEach(cell => {
          const found = bySymbol[cell.dataset.followingSymbol] || [];
          if (!found.length) return;
          cell.querySelector('.wl-no-follows')?.remove();
          const group = n('div', null, 'wl-follow-group wl-custom-follow'); group.setAttribute('role', 'group');
          group.setAttribute('aria-label', i18n.t('watchlists.custom.chipsAria', cell.dataset.followingSymbol));
          const type = n('span', i18n.t('followType.CUSTOM'), 'dashboard-chip wl-type-chip wl-type-custom learn-term');
          type.dataset.learn = LEARN_OPTIONS.customChip; type.dataset.learnTitle = i18n.t('followType.CUSTOM');
          group.append(type);
          found.forEach(alert => {
            const chip = link(alert.name, `/signals?${new URLSearchParams({ customAlertId: alert.id, ticker: cell.dataset.followingSymbol })}`);
            chip.className = `dashboard-chip wl-custom-chip${alert.active ? '' : ' is-paused'}`;
            if (!alert.active) chip.title = i18n.t('watchlists.custom.paused');
            group.append(chip);
          });
          cell.append(group);
        });
      } catch (error) { /* The follows above still show; custom chips are an extra. */ }
    }
  }

  /** The custom alerts that watch this list: open one, stop it here, or apply another alert to the whole list. */
  function customAlerts(list) {
    const box = n('section', null, 'wl-custom-alerts');
    const headingId = `wl-custom-${list.id}`;
    box.setAttribute('aria-labelledby', headingId);
    const head = n('div', null, 'wl-custom-head');
    const heading = n('h3'); heading.id = headingId;
    const title = n('span', i18n.t('watchlists.custom.title'), 'learn-term');
    title.dataset.learn = LEARN_OPTIONS.customStrip; title.dataset.learnTitle = i18n.t('watchlists.custom.title');
    heading.append(title);
    const create = link(i18n.t('watchlists.custom.create'), `/custom-alerts/new?watchlistId=${list.id}`);
    head.append(heading, create);
    const items = n('div', null, 'wl-custom-items');
    const add = n('div', null, 'wl-custom-add');
    const status = n('p', '', 'wl-status'); status.setAttribute('role', 'status');
    items.append(n('p', i18n.t('watchlists.custom.loading'), 'wl-muted'));
    box.append(head, items, add, status);
    const allowed = !list.allowedTypes || list.allowedTypes.includes('CUSTOM');

    async function setApplied(alert, applied) {
      await api(`/api/custom-alerts/${alert.id}/watchlists/${list.id}?applied=${applied}`, { method: 'POST' });
      await load();
    }
    function item(alert) {
      const row = n('div', null, `wl-custom-item${alert.active ? '' : ' is-paused'}`);
      const name = link(alert.name, `/signals?${new URLSearchParams({ customAlertId: alert.id })}`);
      const facts = [i18n.t(`interval.${alert.interval}`),
        alert.scope === 'SELECTED' ? i18n.plural('watchlists.custom.chosen', alert.stocks, alert.stocks) : i18n.plural('watchlists.custom.all', alert.stocks, alert.stocks),
        i18n.plural('watchlists.custom.matching', alert.matching, alert.matching)];
      if (!alert.active) facts.push(i18n.t('watchlists.custom.paused'));
      const text = n('div', null, 'wl-custom-text');
      text.append(name, n('small', facts.join(' · ')));
      row.append(text);
      if (alert.unread) row.append(n('span', i18n.plural('watchlists.custom.unread', alert.unread, alert.unread), 'wl-custom-unread'));
      const remove = button(i18n.t('watchlists.custom.remove'), async () => {
        remove.disabled = true;
        try {
          await setApplied(alert, false);
          status.replaceChildren(n('span', i18n.t('watchlists.custom.removed', alert.name) + ' '));
          const undo = button(i18n.t('watchlists.custom.undo'), async () => {
            undo.disabled = true;
            try { await setApplied(alert, true); status.textContent = ''; } catch (error) { status.textContent = error.message; }
          });
          status.append(undo);
        } catch (error) { status.textContent = error.message; remove.disabled = false; }
      }, 'btn-secondary wl-custom-remove');
      remove.setAttribute('aria-label', i18n.t('watchlists.custom.removeAria', alert.name));
      row.append(remove);
      return row;
    }
    function render(applied, all) {
      items.replaceChildren(...(applied.length ? applied.map(item) : [n('p', i18n.t('watchlists.custom.empty'), 'wl-muted')]));
      add.replaceChildren();
      if (!allowed) { add.append(n('p', i18n.t('watchlists.custom.notAllowed'), 'wl-muted')); return; }
      const appliedIds = new Set(applied.map(alert => alert.id));
      const others = all.filter(alert => !appliedIds.has(alert.id));
      if (!others.length) return;
      const choice = select(i18n.t('watchlists.custom.applyLabel'), [['', i18n.t('watchlists.custom.applyChoose')], ...others.map(alert => [String(alert.id), alert.name])]);
      const apply = button(i18n.t('watchlists.custom.apply'), async () => {
        const alert = others.find(value => String(value.id) === choice.input.value);
        if (!alert) { choice.input.focus(); return; }
        apply.disabled = true;
        try { await setApplied(alert, true); status.textContent = i18n.t('watchlists.custom.applied', alert.name); }
        catch (error) { status.textContent = error.message; apply.disabled = false; }
      });
      add.append(choice.label, apply);
    }
    async function load() {
      try {
        const [applied, all] = await Promise.all([api(`/api/custom-alerts/for-watchlist/${list.id}`), api('/api/custom-alerts')]);
        render(applied, all);
      } catch (error) { items.replaceChildren(n('p', error.message, 'wl-status')); }
    }
    load();
    return box;
  }
  async function manageFollows(list, symbols) {
    const d = modal(i18n.t('watchlists.manageFollowsTitle', i18n.plural('watchlists.selected', symbols.length, symbols.length)));
    d.append(n('p', symbols.join(', '), 'wl-selected-symbols'));
    const status = n('p', i18n.t('watchlists.loadingFollows'), 'wl-status'); status.setAttribute('role', 'status');
    const settings = window.StockWatchListSettings.create(() => { save.disabled = !settings.readChanges().length; }, { members: true });
    settings.root.disabled = true;
    const actions = n('div', null, 'wl-actions');
    const save = button(i18n.t('watchlists.applyToSelected'), async () => {
      save.disabled = true; settings.root.disabled = true;
      try {
        await api(`/api/watchlists/${list.id}/members/follows`, { method: 'POST', body: JSON.stringify({ symbols, changes: settings.readChanges() }) });
        d.close(); await refresh();
      } catch (error) { status.textContent = error.message; save.disabled = false; settings.root.disabled = false; }
    }, 'btn-accent'); save.disabled = true;
    actions.append(button(i18n.t('common.cancel'), () => d.close()), save); d.append(settings.root, status, actions);
    try {
      const view = await api(`/api/watchlists/${list.id}/members/follows/preview`, { method: 'POST', body: JSON.stringify({ symbols }) });
      if (!d.isConnected) return;
      settings.set(view); settings.root.disabled = false;
      status.textContent = view.nonStocks ? i18n.plural('watchlists.nonStocksSkipped', view.nonStocks, view.nonStocks) : '';
    } catch (error) { status.textContent = error.message; }
  }
  function removeMembers(list, symbols) {
    const d = modal(i18n.plural('watchlists.removeSelectedTitle', symbols.length, symbols.length));
    d.append(n('p', symbols.join(', '), 'wl-selected-symbols'), n('p', i18n.t('watchlists.removeSelectedText'), 'wl-muted'));
    const status = n('p', '', 'wl-status'), actions = n('div', null, 'wl-actions'); status.setAttribute('role', 'status');
    const confirm = button(i18n.t('watchlists.removeSelected'), async () => {
      confirm.disabled = true;
      try {
        await api(`/api/watchlists/${list.id}/members/remove`, { method: 'POST', body: JSON.stringify({ symbols }) });
        symbols.forEach(symbol => selections.get(list.id)?.delete(symbol)); d.close(); await refresh();
      } catch (error) { status.textContent = error.message; confirm.disabled = false; }
    }, 'danger-outline-button');
    actions.append(button(i18n.t('common.cancel'), () => d.close()), confirm); d.append(status, actions);
  }
  function confirmDelete(list) {
    const d = modal(i18n.t('watchlists.deleteTitle', list.name));
    d.append(n('p', i18n.t('watchlists.deleteText'), 'wl-muted'));
    const status = n('p', '', 'wl-status'), actions = n('div', null, 'wl-actions');
    const remove = button(i18n.t('watchlists.delete'), async () => {
      remove.disabled = true;
      try {
        await api(`/api/watchlists/${list.id}`, { method: 'DELETE' }); d.close();
        if (root?.dataset.selectedId === String(list.id)) { window.location.assign('/watchlists'); return; }
        await refresh();
      }
      catch (error) { status.textContent = error.message; remove.disabled = false; }
    }, 'danger-outline-button'); actions.append(button(i18n.t('common.cancel'), () => d.close()), remove); d.append(status, actions);
  }
  async function edit(list) {
    const d = modal(list ? i18n.t('watchlists.manageTitle', list.name) : i18n.t('watchlists.create')), form = n('form', null, 'wl-editor-form');
    d.classList.add('wl-editor');
    const heading = d.querySelector('h2'), header = n('header', null, 'wl-editor-header');
    const close = button('×', () => d.close(), 'wl-editor-close'); close.setAttribute('aria-label', i18n.t('watchlists.closeEditor'));
    header.append(heading, close); d.append(header, form);
    const name = field(i18n.t('watchlists.name'), 'text', list?.name || ''), description = field(i18n.t('watchlists.description'), 'text', list?.description || '');
    name.input.required = true; name.input.maxLength = 100; description.input.maxLength = 500;
    name.input.placeholder = i18n.t('watchlists.namePlaceholder'); description.input.placeholder = i18n.t('watchlists.descriptionPlaceholder');
    const identity = n('div', null, 'wl-editor-identity'); identity.append(name.label, description.label);
    const columns = n('div', null, 'wl-editor-columns'), instruments = n('section', null, 'wl-editor-instruments');
    instruments.setAttribute('aria-label', i18n.t('watchlists.chooseInstruments'));
    instruments.append(n('h3', i18n.t('watchlists.chooseInstruments')));
    const search = field(i18n.t('watchlists.searchTickers'), 'search'), results = n('div', null, 'wl-results'), resultsNote = n('p', '', 'wl-muted wl-results-note');
    search.input.placeholder = i18n.t('watchlists.searchTickersPlaceholder'); instruments.append(search.label, results, resultsNote);
    // The search is a combobox like the global one: arrows move through the results and Enter picks one.
    results.id = `wl-results-${Date.now()}`; results.setAttribute('role', 'listbox'); results.setAttribute('aria-label', i18n.t('watchlists.searchTickers'));
    resultsNote.setAttribute('role', 'status');
    Object.entries({ role: 'combobox', 'aria-autocomplete': 'list', 'aria-controls': results.id, 'aria-expanded': 'false' })
      .forEach(([name, value]) => search.input.setAttribute(name, value));
    const market = n('section', null, 'wl-market-browser'); market.setAttribute('aria-label', i18n.t('watchlists.browseIndexes'));
    market.append(n('h3', i18n.t('watchlists.browseIndexes')), n('p', i18n.t('watchlists.browseIndexesHelp'), 'wl-muted'));
    const regions = n('div', null, 'wl-region-options'); regions.setAttribute('role', 'group'); regions.setAttribute('aria-label', i18n.t('watchlists.continents'));
    const countries = n('div', null, 'wl-country-options'); countries.setAttribute('role', 'group'); countries.setAttribute('aria-label', i18n.t('watchlists.countries'));
    const countrySection = n('div', null, 'wl-country-section'); countrySection.hidden = true;
    countrySection.append(n('h4', i18n.t('watchlists.country')), countries);
    const indexSection = n('div', null, 'wl-index-section'); indexSection.hidden = true;
    const indexTitle = n('h4', i18n.t('watchlists.indexes')), indexOptions = n('div', null, 'wl-index-options'); indexSection.append(indexTitle, indexOptions);
    const catalogStatus = n('p', i18n.t('watchlists.loadingContinents'), 'wl-muted'); catalogStatus.setAttribute('role', 'status');
    market.append(regions, countrySection, indexSection, catalogStatus); instruments.append(market);
    const selections = n('div', null, 'wl-selection-list'), selectionSection = n('section', null, 'wl-selection-summary');
    selectionSection.setAttribute('aria-label', i18n.t('watchlists.selectedAdditions'));
    selectionSection.append(n('h3', i18n.t('watchlists.selectedAdditions')), selections); instruments.append(selectionSection);
    const monitoring = window.StockWatchListSettings.create(changed);
    monitoring.root.disabled = !!list;
    const settingsPane = n('section', null, 'wl-editor-settings'); settingsPane.setAttribute('aria-label', i18n.t('watchlists.settingsAria'));
    settingsPane.append(monitoring.root); columns.append(instruments, settingsPane);
    const status = n('p', '', 'wl-status'); status.setAttribute('role', 'status');
    const previewArea = n('div', null, 'wl-editor-preview'), count = n('p', '', 'wl-muted'); count.setAttribute('role', 'status');
    const save = n('button', i18n.t(list ? 'watchlists.save' : 'watchlists.create'), 'btn-accent'); save.type = 'submit';
    const actions = n('div', null, 'wl-actions'); actions.append(button(i18n.t('common.cancel'), () => d.close()), save);
    const footer = n('footer', null, 'wl-editor-footer'), footerRow = n('div', null, 'wl-editor-footer-row'); footerRow.append(count, actions);
    footer.append(status, previewArea, footerRow); form.append(identity, columns, footer);
    const selected = new Map(), indexChoices = new Map(), indexInputs = new Map();
    let catalog = [], activeRegion = '', activeCountry = '', previewed = false, busy = false, settingsLoaded = !list;
    const modes = Object.fromEntries(['index', 'stocks', 'both'].map(mode => [mode, i18n.t(`watchlists.mode.${mode}`)]));
    function additions() {
      const symbols = new Set(selected.keys()), indexIds = [];
      indexChoices.forEach(({ entry, mode }) => {
        if (mode !== 'stocks') symbols.add(entry.symbol);
        if (mode !== 'index') indexIds.push(entry.id);
      });
      return { symbols: [...symbols], indexIds };
    }
    function changed() {
      previewed = false; previewArea.replaceChildren(); selections.replaceChildren();
      const itemRow = (title, subtitle, edit, remove) => {
        const row = n('div', null, 'wl-selection-row'), text = n('div'); text.append(n('strong', title), n('small', subtitle));
        const actions = n('div', null, 'wl-selection-actions');
        if (edit) { const b = button(i18n.t('watchlists.change'), edit); b.setAttribute('aria-label', i18n.t('watchlists.changeAria', title)); actions.append(b); }
        const b = button('×', remove); b.setAttribute('aria-label', i18n.t('watchlists.removeAria', title)); actions.append(b); row.append(text, actions); selections.append(row);
      };
      selected.forEach((item, symbol) => itemRow(symbol, item.name || i18n.t('watchlists.individualInstrument'), null, () => { selected.delete(symbol); changed(); }));
      indexChoices.forEach(({ entry, mode }) => itemRow(entry.name, `${modes[mode]} · ${mode === 'index' ? entry.symbol
        : mode === 'both' ? i18n.t('watchlists.stocksAndIndex', stockCount(entry.count)) : stockCount(entry.count)}`,
        () => chooseIndex(entry), () => { indexChoices.delete(entry.id); changed(); }));
      if (!selected.size && !indexChoices.size) selections.append(n('p', i18n.t('watchlists.selectionsEmpty'), 'wl-muted'));
      indexInputs.forEach((input, id) => { input.checked = indexChoices.has(id); });
      count.textContent = `${i18n.plural('watchlists.individualTickers', selected.size, selected.size)} · ${i18n.plural('watchlists.indexSelections', indexChoices.size, indexChoices.size)}`;
      save.textContent = selected.size || indexChoices.size || monitoring.restrictionsChanged() || list?.instruments > 0 && monitoring.monitoringChanged() ? i18n.t('watchlists.reviewChanges') : i18n.t(list ? 'watchlists.save' : 'watchlists.create');
    }
    function chooseIndex(entry) {
      const choice = modal(i18n.t('watchlists.addIndexTitle', entry.name)); choice.classList.add('wl-index-dialog');
      choice.append(n('p', i18n.t('watchlists.addIndexQuestion'), 'wl-muted'));
      const options = n('fieldset', null, 'wl-index-modes'); options.append(n('legend', i18n.t('watchlists.chooseWhatToAdd')));
      let mode = indexChoices.get(entry.id)?.mode || 'index';
      const descriptions = { index: i18n.t('watchlists.modeHelp.index', entry.symbol), stocks: i18n.plural('watchlists.modeHelp.stocks', entry.count, entry.count),
        both: i18n.plural('watchlists.modeHelp.both', entry.count, entry.count) };
      Object.entries(modes).forEach(([value, title]) => {
        const label = n('label', null, 'wl-index-mode'), input = n('input'); input.type = 'radio'; input.name = 'indexMode'; input.value = value;
        input.checked = mode === value; input.disabled = value !== 'index' && !entry.count;
        const text = n('span'); text.append(n('strong', title), n('small', descriptions[value])); label.append(input, text); options.append(label);
        input.addEventListener('change', () => { mode = value; });
      });
      const note = n('p', null, 'wl-muted'); note.append(n('span', `${i18n.t('watchlists.constituentsAsOf', entry.asOf)} `));
      const source = link(i18n.t('watchlists.viewSource'), entry.source); source.target = '_blank'; source.rel = 'noopener noreferrer'; note.append(source);
      const actions = n('div', null, 'wl-actions');
      actions.append(button(i18n.t('common.cancel'), () => choice.close()), button(i18n.t('watchlists.confirmSelection'), () => { indexChoices.set(entry.id, { entry, mode }); changed(); choice.close(); }, 'btn-accent'));
      choice.append(options, note, actions);
      choice.addEventListener('close', () => {
        indexInputs.forEach((input, id) => { input.checked = indexChoices.has(id); });
        if (d.open && !d.contains(document.activeElement)) indexInputs.get(entry.id)?.focus();
      });
    }
    function renderIndexes() {
      indexOptions.replaceChildren(); indexInputs.clear();
      indexTitle.textContent = i18n.t('watchlists.indexesIn', geo(activeCountry)); indexSection.hidden = false;
      catalog.filter(entry => entry.region === activeRegion && entry.country === activeCountry).forEach(entry => {
        const row = n('label', null, 'wl-index-option'), input = n('input'); input.type = 'checkbox';
        input.setAttribute('aria-label', entry.name); input.checked = indexChoices.has(entry.id); indexInputs.set(entry.id, input);
        const text = n('span'); text.append(n('strong', entry.name), n('small', `${entry.symbol} · ${stockCount(entry.count)}`)); row.append(input, text); indexOptions.append(row);
        input.addEventListener('change', () => {
          if (input.checked) chooseIndex(entry);
          else { indexChoices.delete(entry.id); changed(); }
        });
      });
    }
    function renderCountries() {
      countries.replaceChildren(); countrySection.hidden = false; indexSection.hidden = true; indexInputs.clear();
      [...new Set(catalog.filter(entry => entry.region === activeRegion).map(entry => entry.country))].sort(byLocalName).forEach(country => {
        const b = button(geo(country), () => {
          activeCountry = country; countries.querySelectorAll('button').forEach(item => item.setAttribute('aria-pressed', String(item === b))); renderIndexes();
        }, 'wl-geography-button'); b.setAttribute('aria-pressed', 'false'); countries.append(b);
      });
    }
    async function loadCatalog() {
      catalogStatus.replaceChildren(n('span', i18n.t('watchlists.loadingContinents')));
      try {
        catalog = await api('/api/watchlists/indexes'); if (!d.isConnected) return;
        catalogStatus.replaceChildren(); regions.replaceChildren();
        [...new Set(catalog.map(entry => entry.region))].sort(byLocalName).forEach(region => {
          const b = button(geo(region), () => {
            activeRegion = region; activeCountry = ''; regions.querySelectorAll('button').forEach(item => item.setAttribute('aria-pressed', String(item === b))); renderCountries();
          }, 'wl-geography-button'); b.setAttribute('aria-pressed', 'false'); regions.append(b);
        });
        if (!catalog.length) catalogStatus.textContent = i18n.t('watchlists.noIndexCollections');
      } catch (error) {
        catalogStatus.replaceChildren(n('span', `${i18n.t('watchlists.indexesFailed')} `), button(i18n.t('common.retry'), loadCatalog));
      }
    }
    let searchSequence = 0, timer, activeOption = -1;
    const options = () => Array.from(results.querySelectorAll('[role="option"]'));
    function showResults(rows, note = '') {
      results.replaceChildren(...rows); resultsNote.textContent = note; activeOption = -1;
      search.input.removeAttribute('aria-activedescendant'); search.input.setAttribute('aria-expanded', String(rows.length > 0));
    }
    function setActiveOption(index) {
      const rows = options(); activeOption = rows.length ? (index + rows.length) % rows.length : -1;
      rows.forEach((row, position) => {
        const active = position === activeOption;
        row.classList.toggle('is-active', active); row.setAttribute('aria-selected', String(active));
        if (active) row.scrollIntoView({ block: 'nearest' });
      });
      if (activeOption >= 0) search.input.setAttribute('aria-activedescendant', rows[activeOption].id);
    }
    function resultOption(item, index) {
      const option = button(`${item.symbol} · ${item.name} · ${item.region || item.country || ''}`, () => {
        selected.set(item.symbol, item); search.input.value = ''; showResults([]); changed(); search.input.focus();
      }, 'wl-result');
      option.id = `${results.id}-${index}`; option.tabIndex = -1;
      option.setAttribute('role', 'option'); option.setAttribute('aria-selected', 'false');
      return option;
    }
    search.input.addEventListener('keydown', event => {
      const rows = options();
      if (event.key === 'Enter') {
        // Enter picks a result; it must never submit, and so save, the whole editor.
        event.preventDefault();
        if (rows.length) rows[Math.max(0, activeOption)].click();
      } else if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && rows.length) {
        event.preventDefault(); setActiveOption(activeOption + (event.key === 'ArrowDown' ? 1 : -1));
      } else if (event.key === 'Escape' && rows.length) {
        // The first Escape closes the results; the next one closes the dialog.
        event.preventDefault(); showResults([]);
      }
    });
    search.input.addEventListener('input', () => {
      clearTimeout(timer); const seq = ++searchSequence;
      timer = setTimeout(async () => {
        const q = search.input.value.trim(); if (q.length < 1) { showResults([]); return; }
        try {
          const found = await api(`/api/stocks/search?q=${encodeURIComponent(q)}`);
          if (seq !== searchSequence || !d.isConnected) return;
          showResults(found.map(resultOption), found.length ? '' : i18n.t('watchlists.noInstrumentsFound'));
        } catch (error) { if (seq === searchSequence) showResults([], error.message); }
      }, 300);
    });
    form.addEventListener('submit', async event => {
      event.preventDefault(); if (busy || !settingsLoaded) return; busy = true; save.disabled = true; status.textContent = '';
      const applyExisting = !!list && list.instruments > 0 && monitoring.monitoringChanged();
      const payload = { ...additions(), monitor: false,
        families: [], intervals: [], selections: monitoring.read(), includeExisting: applyExisting, applySettings: !list || monitoring.monitoringChanged() };
      try {
        if (monitoring.restrictionsChanged() && !previewed && !selected.size && !indexChoices.size && !applyExisting) {
          previewArea.replaceChildren(n('p', monitoring.restrictionSummary(), 'wl-status'));
          previewed = true; save.textContent = i18n.t('watchlists.saveChanges'); return;
        }
        if ((selected.size || indexChoices.size || applyExisting) && !previewed) {
          const preview = await api(`/api/watchlists/imports/preview${list ? `?watchlistId=${list.id}` : ''}`, { method: 'POST', body: JSON.stringify(payload) });
          previewArea.replaceChildren(n('p', [i18n.plural('watchlists.uniqueInstruments', preview.uniqueCount, preview.uniqueCount),
            i18n.plural('watchlists.duplicatesRemoved', preview.duplicates, preview.duplicates),
            i18n.plural('watchlists.alreadyPresent', preview.alreadyPresent, preview.alreadyPresent)].join(' · '), 'wl-muted'));
          if (monitoring.restrictionsChanged()) previewArea.append(n('p', monitoring.restrictionSummary(), 'wl-status'));
          if (preview.warning) previewArea.append(n('p', preview.warning, 'wl-status'));
          if (preview.skippedTickerInstruments) previewArea.append(n('p', i18n.plural('watchlists.tickerAlertsSkipped', preview.skippedTickerInstruments, preview.skippedTickerInstruments), 'wl-muted'));
          if (payload.selections.some(value => value.watch && ['CONGRESS', 'INSIDER'].includes(value.type))) previewArea.append(n('p', i18n.t('watchlists.followSlots', preview.congressRemaining, preview.insiderRemaining), 'wl-muted'));
          previewArea.append(n('p', `${i18n.plural('watchlists.newlyFollowed', preview.newlyMonitored, preview.newlyMonitored)} · ${i18n.t('watchlists.roomFor', preview.capacityRemaining)}`, 'wl-muted'));
          previewed = preview.allowed; save.textContent = i18n.t(preview.allowed ? 'watchlists.saveChanges' : 'watchlists.reviewChanges');
          return;
        }
        const saved = await api('/api/watchlists/save', { method: 'POST', body: JSON.stringify({
          id: list?.id || null, name: name.input.value, description: description.input.value, pinned: list?.pinned || false,
          symbols: payload.symbols, indexIds: payload.indexIds, selections: payload.selections, allowedTypes: monitoring.allowedTypes(),
          applyMonitoring: !list || monitoring.monitoringChanged(), settingsChanged: !list || monitoring.changed()
        }) });
        d.close(); await refresh();
        if (saved.importId) showImport(saved.importId);
      } catch (error) { status.textContent = error.message; }
      finally { busy = false; save.disabled = false; }
    });
    const loadSettings = list ? api(`/api/watchlists/${list.id}/settings`).then(view => {
      monitoring.set(view); monitoring.root.disabled = false; settingsLoaded = true;
    }).catch(error => { status.textContent = i18n.t('watchlists.settingsFailed', error.message); save.disabled = true; }) : Promise.resolve();
    loadCatalog();
    await loadSettings;
    changed();
  }
  async function showImport(id) {
    const d = modal(i18n.t('watchlists.importTitle')), status = n('p', '', 'wl-status'), errors = n('div');
    const actions = n('div', null, 'wl-actions'), close = button(i18n.t('watchlists.closeInBackground'), () => d.close());
    actions.append(close); d.append(status, errors, actions);
    async function poll() {
      try {
        const job = await api(`/api/watchlists/imports/${id}`);
        status.textContent = i18n.t('watchlists.importProgress', job.completed, job.total, job.failed, job.pending);
        close.textContent = i18n.t(job.pending ? 'watchlists.closeInBackground' : 'watchlists.importDone');
        errors.replaceChildren(); job.errors.forEach(error => errors.append(n('p', `${error.symbol}: ${error.error}`, 'wl-muted')));
        if (job.pending) setTimeout(poll, d.isConnected ? 2000 : 10000);
        else {
          await refresh();
          if (job.failed) actions.append(button(i18n.t('watchlists.retryFailed'), async event => {
            const retry = event.currentTarget; retry.disabled = true;
            try { await api(`/api/watchlists/imports/${id}/retry`, { method: 'POST' }); retry.remove(); poll(); }
            catch (error) { status.textContent = error.message; retry.disabled = false; }
          }));
        }
      } catch (error) { status.textContent = error.message; actions.append(button(i18n.t('watchlists.retryStatus'), poll)); }
    }
    poll();
  }
  async function recentImports() {
    const d = modal(i18n.t('watchlists.recentImports')), content = n('div');
    d.append(content, button(i18n.t('common.close'), () => d.close()));
    content.append(n('p', i18n.t('watchlists.loadingImports'), 'wl-status'));
    try {
      const jobs = await api('/api/watchlists/imports'); content.replaceChildren();
      if (!jobs.length) content.append(n('p', i18n.t('watchlists.noImports'), 'wl-muted'));
      jobs.forEach(job => content.append(button(
        i18n.t('watchlists.importSummary', job.id, job.completed, job.total, job.pending, job.failed),
        () => { d.close(); showImport(job.id); })));
    } catch (error) { content.replaceChildren(n('p', error.message, 'wl-status')); }
  }
  document.querySelectorAll('[data-create-watchlist]').forEach(b => b.addEventListener('click', () => edit()));
  document.querySelectorAll('[data-watchlist-imports]').forEach(b => b.addEventListener('click', recentImports));
  document.querySelector('[data-watchlist-search]')?.addEventListener('input', render);
  document.querySelectorAll('[data-stock-watchlists]').forEach(b => b.addEventListener('click', async () => {
    b.disabled = true;
    try {
      const selection = await window.StockWatchLists.choose(b.dataset.symbol, { mode: 'membership' });
      if (selection) { await api(`/api/watchlists/memberships/${encodeURIComponent(b.dataset.symbol)}`, { method: 'POST', body: JSON.stringify(selection) }); b.textContent = i18n.t('watchlists.addedToWatchlists'); await refresh(); }
    } catch (error) { b.textContent = error.message; }
    finally { b.disabled = false; }
  }));
  refresh();
})();
