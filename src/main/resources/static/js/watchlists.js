(() => {
  'use strict';
  const { api, node: n } = window.StockWatchLists;
  const root = document.querySelector('[data-named-watchlists]');
  let lists = [];
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
    } catch (error) { if (root) { root.replaceChildren(n('p', error.message, 'wl-status'), button('Retry', refresh)); } }
  }
  function render() {
    if (!root) return;
    const openIds = new Set(Array.from(root.querySelectorAll('details[open]'), e => e.dataset.id));
    const search = document.querySelector('[data-watchlist-search]')?.value.trim().toLowerCase() || '';
    root.replaceChildren();
    if (!lists.length) {
      const empty = n('div', null, 'wl-empty');
      empty.append(n('h3', 'Create your first watchlist'), n('p', 'Add individual instruments or start with the companies in a market index.', 'wl-muted'), button('Create watchlist', () => edit(), 'btn-accent'));
      root.append(empty); return;
    }
    const visible = lists.filter(list => (!root.dataset.selectedId || String(list.id) === root.dataset.selectedId)
      && `${list.name} ${list.description}`.toLowerCase().includes(search));
    if (!visible.length) root.append(n('p', 'No matching watchlists.', 'wl-muted'));
    for (const list of visible) {
      if (root.dataset.selectedId) {
        const title = document.querySelector('.app-hero h1');
        if (title) title.textContent = list.name;
        const overview = n('div', null, 'wl-overview');
        overview.append(n('p', `${list.instruments} instruments · ${list.monitored} monitored · ${list.unread} unread`, 'wl-muted'));
        if (list.description) overview.append(n('p', list.description, 'wl-muted'));
        root.append(overview); memberTable(list, overview); continue;
      }
      const details = n('details', null, 'wl-accordion'); details.dataset.id = String(list.id);
      const summary = n('summary'); const heading = n('div', null, 'wl-heading');
      heading.append(n('strong', `${list.pinned ? '★ ' : ''}${list.name}`), n('small', `${list.instruments} instruments · ${list.monitored} monitored · ${list.unread} unread`));
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
    const actions = n('div', null, 'wl-actions');
    if (!root.dataset.selectedId) actions.append(link('Open watchlist', `/watchlists/${list.id}`));
    const markRead = n('button', 'Mark all as read', 'btn-secondary'); markRead.type = 'button';
    markRead.dataset.notificationsReadAll = ''; markRead.dataset.readWatchlist = String(list.id);
    markRead.title = 'Mark every notification in this watchlist as read, across all pages and filters';
    const readStatus = n('span', '', 'wl-status'); readStatus.dataset.notificationsReadStatus = ''; readStatus.setAttribute('role', 'status');
    actions.append(link('View all signals', `/signals?watchlistId=${list.id}`), markRead, button('Add instruments / edit', () => edit(list)), button(list.pinned ? 'Unpin' : 'Pin', async () => {
      try { await api(`/api/watchlists/${list.id}`, { method: 'PUT', body: JSON.stringify({ ...list, pinned: !list.pinned }) }); await refresh(); }
      catch (error) { status.textContent = error.message; }
    }), button('Delete watchlist', () => confirmDelete(list), 'danger-outline-button'));
    actions.append(readStatus);
    const toolbar = n('div', null, 'wl-toolbar');
    const searchField = field('Search', 'search'), search = searchField.input;
    search.placeholder = 'Ticker or company'; search.setAttribute('aria-label', `Search ${list.name}`);
    const group = select('Instrument type', [['all', 'All instruments'], ['stocks', 'Stocks'], ['funds', 'Indexes / ETFs']]);
    toolbar.append(searchField.label, group.label);
    const status = n('p', '', 'wl-status'); status.setAttribute('role', 'status');
    const tableArea = n('div', null, 'wl-table-wrap'), pages = n('div', null, 'wl-actions');
    tableArea.tabIndex = 0; tableArea.setAttribute('role', 'region'); tableArea.setAttribute('aria-label', `Instruments in ${list.name}`);
    body.append(actions, toolbar, status, tableArea, pages);
    let page = 0, sequence = 0, timer;
    async function load() {
      const request = ++sequence; status.textContent = 'Loading instruments…';
      try {
        const data = await api(`/api/watchlists/${list.id}/members?${new URLSearchParams({ page, group: group.input.value, q: search.value })}`);
        if (request !== sequence || !body.isConnected) return;
        tableArea.replaceChildren(); pages.replaceChildren();
        status.textContent = data.total ? `${data.total} instruments · Page ${page + 1} of ${Math.ceil(data.total / data.pageSize)}` : 'No instruments in this view.';
        const table = n('table', null, 'wl-table'), head = n('thead'), row = n('tr'), tbody = n('tbody');
        for (const text of ['Instrument', 'Following', 'Unread signals', 'Actions']) { const th = n('th', text); th.scope = 'col'; row.append(th); }
        head.append(row); table.append(head, tbody);
        for (const item of data.items) {
          const tr = n('tr'), instrument = n('td', null, 'wl-instrument-cell');
          const signalsUrl = `/signals?${new URLSearchParams({ watchlistId: list.id, ticker: item.symbol })}`;
          instrument.append(link(item.symbol, signalsUrl), n('small', item.companyName), n('small', `${item.exchange} · ${item.currency}`)); tr.append(instrument);
          const follows = item.signals || [];
          const types = { CANDLESTICK: 'Candlestick', ELLIOTT_WAVE: 'Elliott Wave', HARMONIC_FORMATION: 'Harmonic', OUTLOOK: 'Automated outlook', CONGRESS: 'Congressional trades', INSIDER: 'Insider trades' };
          const following = n('td', null, 'wl-following-cell'); following.dataset.label = 'Following';
          const groups = new Map();
          follows.forEach(value => {
            const key = `${value.type}:${value.direction}`;
            if (!groups.has(key)) groups.set(key, { type: value.type, direction: value.direction, intervals: new Set() });
            groups.get(key).intervals.add(value.interval);
          });
          groups.forEach(({ type, direction, intervals }) => {
            const group = n('div', null, 'wl-follow-group'); group.setAttribute('role', 'group');
            const label = types[type] || type;
            group.setAttribute('aria-label', `${label}${direction === 'ANY' ? '' : ` ${direction.toLowerCase()}`} follows`);
            const tone = { CANDLESTICK: 'candle', ELLIOTT_WAVE: 'elliott', HARMONIC_FORMATION: 'harmonic', OUTLOOK: 'outlook', CONGRESS: 'congress', INSIDER: 'insider' }[type] || 'other';
            group.append(n('span', label, `dashboard-chip wl-type-chip wl-type-${tone}`));
            if (direction !== 'ANY') group.append(n('span', direction, `dashboard-chip wl-direction-chip ${direction === 'BUY' ? 'buy' : 'sell'}`));
            const periods = n('div', null, 'wl-intervals');
            ['DAILY', 'WEEKLY', 'MONTHLY'].filter(interval => intervals.has(interval)).forEach(interval => {
              periods.append(n('span', interval[0] + interval.slice(1).toLowerCase(), 'dashboard-chip wl-interval-chip'));
            });
            group.append(periods); following.append(group);
          });
          if (!groups.size) following.append(n('span', 'No follows in this list', 'wl-no-follows'));
          const unread = n('td', null, 'wl-unread-cell'), count = item.unreadSignalCount || 0;
          const badge = n('span', null, `wl-unread-badge${count > 0 ? ' has-unread' : ''}`);
          badge.append(n('strong', String(count)), n('span', 'unread')); unread.append(badge);
          tr.append(following, unread);
          const action = n('td', null, 'wl-actions-cell'), actionGroup = n('div', null, 'wl-row-actions');
          actionGroup.setAttribute('role', 'group'); actionGroup.setAttribute('aria-label', `Actions for ${item.symbol}`);
          const view = link('View signals', signalsUrl); view.className = 'wl-row-action wl-view-action'; view.prepend(actionIcon('signals'));
          const manage = link('Manage follows', `/stock/${encodeURIComponent(item.symbol)}`); manage.className = 'wl-row-action'; manage.prepend(actionIcon('manage'));
          const remove = button('Remove', async () => {
            const d = modal(`Remove ${item.symbol}?`);
            d.append(n('p', 'Other watchlists keep this instrument. If this is its last list, new monitoring stops; signal history remains.', 'wl-muted'));
            const errorText = n('p', '', 'wl-status'), buttons = n('div', null, 'wl-actions');
            const confirm = button('Remove from this list', async () => {
              confirm.disabled = true;
              try { await api(`/api/watchlists/${list.id}/members/${encodeURIComponent(item.symbol)}`, { method: 'DELETE' }); d.close(); await refresh(); }
              catch (error) { errorText.textContent = error.message; confirm.disabled = false; }
            }, 'danger-outline-button');
            buttons.append(button('Cancel', () => d.close()), confirm); d.append(errorText, buttons);
          }, 'wl-row-action wl-remove-action'); remove.prepend(actionIcon('remove'));
          actionGroup.append(view, manage, remove); action.append(actionGroup); tr.append(action); tbody.append(tr);
        }
        if (data.items.length) tableArea.append(table);
        const previous = button('Previous', () => { page--; load(); }), next = button('Next', () => { page++; load(); });
        previous.disabled = page === 0; next.disabled = (page + 1) * data.pageSize >= data.total; pages.append(previous, next);
      } catch (error) { if (request === sequence) { status.textContent = error.message; pages.replaceChildren(button('Retry', load)); } }
    }
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { page = 0; load(); }, 250); });
    group.input.addEventListener('change', () => { page = 0; load(); }); load();
  }
  function confirmDelete(list) {
    const d = modal(`Delete “${list.name}”?`);
    d.append(n('p', 'Instruments in other watchlists stay monitored. Instruments only in this list stop new monitoring. Notification history is preserved.', 'wl-muted'));
    const status = n('p', '', 'wl-status'), actions = n('div', null, 'wl-actions');
    const remove = button('Delete watchlist', async () => {
      remove.disabled = true;
      try {
        await api(`/api/watchlists/${list.id}`, { method: 'DELETE' }); d.close();
        if (root?.dataset.selectedId === String(list.id)) { window.location.assign('/watchlists'); return; }
        await refresh();
      }
      catch (error) { status.textContent = error.message; remove.disabled = false; }
    }, 'danger-outline-button'); actions.append(button('Cancel', () => d.close()), remove); d.append(status, actions);
  }
  async function edit(list) {
    const d = modal(list ? `Manage ${list.name}` : 'Create watchlist'), form = n('form', null, 'wl-editor-form');
    d.classList.add('wl-editor');
    const heading = d.querySelector('h2'), header = n('header', null, 'wl-editor-header');
    const close = button('×', () => d.close(), 'wl-editor-close'); close.setAttribute('aria-label', 'Close watchlist editor');
    header.append(heading, close); d.append(header, form);
    const name = field('Name', 'text', list?.name || ''), description = field('Description (optional)', 'text', list?.description || '');
    name.input.required = true; name.input.maxLength = 100; description.input.maxLength = 500;
    name.input.placeholder = 'e.g. European opportunities'; description.input.placeholder = 'What is this watchlist for?';
    const identity = n('div', null, 'wl-editor-identity'); identity.append(name.label, description.label);
    const columns = n('div', null, 'wl-editor-columns'), instruments = n('section', null, 'wl-editor-instruments');
    instruments.setAttribute('aria-label', 'Choose instruments');
    instruments.append(n('h3', 'Choose instruments'));
    const search = field('Search individual tickers', 'search'), results = n('div', null, 'wl-results');
    search.input.placeholder = 'Ticker or company name'; instruments.append(search.label, results);
    const market = n('section', null, 'wl-market-browser'); market.setAttribute('aria-label', 'Browse indexes');
    market.append(n('h3', 'Browse indexes'), n('p', 'Choose a continent, then a country. Check an index to choose what to add.', 'wl-muted'));
    const regions = n('div', null, 'wl-region-options'); regions.setAttribute('role', 'group'); regions.setAttribute('aria-label', 'Continents');
    const countries = n('div', null, 'wl-country-options'); countries.setAttribute('role', 'group'); countries.setAttribute('aria-label', 'Countries');
    const countrySection = n('div', null, 'wl-country-section'); countrySection.hidden = true;
    countrySection.append(n('h4', 'Country'), countries);
    const indexSection = n('div', null, 'wl-index-section'); indexSection.hidden = true;
    const indexTitle = n('h4', 'Indexes'), indexOptions = n('div', null, 'wl-index-options'); indexSection.append(indexTitle, indexOptions);
    const catalogStatus = n('p', 'Loading continents…', 'wl-muted'); catalogStatus.setAttribute('role', 'status');
    market.append(regions, countrySection, indexSection, catalogStatus); instruments.append(market);
    const selections = n('div', null, 'wl-selection-list'), selectionSection = n('section', null, 'wl-selection-summary');
    selectionSection.setAttribute('aria-label', 'Selected additions');
    selectionSection.append(n('h3', 'Selected additions'), selections); instruments.append(selectionSection);
    const monitoring = window.StockWatchListSettings.create(changed);
    monitoring.root.disabled = !!list;
    const settingsPane = n('section', null, 'wl-editor-settings'); settingsPane.setAttribute('aria-label', 'Follow and email settings');
    settingsPane.append(monitoring.root); columns.append(instruments, settingsPane);
    const status = n('p', '', 'wl-status'); status.setAttribute('role', 'status');
    const previewArea = n('div', null, 'wl-editor-preview'), count = n('p', '', 'wl-muted'); count.setAttribute('role', 'status');
    const save = n('button', list ? 'Save watchlist' : 'Create watchlist', 'btn-accent'); save.type = 'submit';
    const actions = n('div', null, 'wl-actions'); actions.append(button('Cancel', () => d.close()), save);
    const footer = n('footer', null, 'wl-editor-footer'), footerRow = n('div', null, 'wl-editor-footer-row'); footerRow.append(count, actions);
    footer.append(status, previewArea, footerRow); form.append(identity, columns, footer);
    const selected = new Map(), indexChoices = new Map(), indexInputs = new Map();
    let catalog = [], activeRegion = '', activeCountry = '', previewed = false, busy = false, settingsLoaded = !list;
    const modes = { index: 'Index only', stocks: 'All stocks in the index', both: 'Both' };
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
        if (edit) { const b = button('Change', edit); b.setAttribute('aria-label', `Change ${title} selection`); actions.append(b); }
        const b = button('×', remove); b.setAttribute('aria-label', `Remove ${title}`); actions.append(b); row.append(text, actions); selections.append(row);
      };
      selected.forEach((item, symbol) => itemRow(symbol, item.name || 'Individual instrument', null, () => { selected.delete(symbol); changed(); }));
      indexChoices.forEach(({ entry, mode }) => itemRow(entry.name, `${modes[mode]} · ${mode === 'index' ? entry.symbol : entry.count + ' stocks' + (mode === 'both' ? ' + index' : '')}`,
        () => chooseIndex(entry), () => { indexChoices.delete(entry.id); changed(); }));
      if (!selected.size && !indexChoices.size) selections.append(n('p', 'Your selected tickers and indexes will appear here.', 'wl-muted'));
      indexInputs.forEach((input, id) => { input.checked = indexChoices.has(id); });
      count.textContent = `${selected.size} individual tickers · ${indexChoices.size} index selections`;
      save.textContent = selected.size || indexChoices.size || monitoring.restrictionsChanged() || list?.instruments > 0 && monitoring.monitoringChanged() ? 'Review changes' : (list ? 'Save watchlist' : 'Create watchlist');
    }
    function chooseIndex(entry) {
      const choice = modal(`Add ${entry.name}`); choice.classList.add('wl-index-dialog');
      choice.append(n('p', 'What would you like to add to this watchlist?', 'wl-muted'));
      const options = n('fieldset', null, 'wl-index-modes'); options.append(n('legend', 'Choose what to add'));
      let mode = indexChoices.get(entry.id)?.mode || 'index';
      const descriptions = { index: `The index itself (${entry.symbol}).`, stocks: `All ${entry.count} constituent stocks, without the index.`, both: `The index and all ${entry.count} constituent stocks.` };
      Object.entries(modes).forEach(([value, title]) => {
        const label = n('label', null, 'wl-index-mode'), input = n('input'); input.type = 'radio'; input.name = 'indexMode'; input.value = value;
        input.checked = mode === value; input.disabled = value !== 'index' && !entry.count;
        const text = n('span'); text.append(n('strong', title), n('small', descriptions[value])); label.append(input, text); options.append(label);
        input.addEventListener('change', () => { mode = value; });
      });
      const note = n('p', null, 'wl-muted'); note.append(n('span', `Constituents captured ${entry.asOf}. `));
      const source = link('View source', entry.source); source.target = '_blank'; source.rel = 'noopener noreferrer'; note.append(source);
      const actions = n('div', null, 'wl-actions');
      actions.append(button('Cancel', () => choice.close()), button('Confirm selection', () => { indexChoices.set(entry.id, { entry, mode }); changed(); choice.close(); }, 'btn-accent'));
      choice.append(options, note, actions);
      choice.addEventListener('close', () => {
        indexInputs.forEach((input, id) => { input.checked = indexChoices.has(id); });
        if (d.open && !d.contains(document.activeElement)) indexInputs.get(entry.id)?.focus();
      });
    }
    function renderIndexes() {
      indexOptions.replaceChildren(); indexInputs.clear();
      indexTitle.textContent = `Indexes in ${activeCountry}`; indexSection.hidden = false;
      catalog.filter(entry => entry.region === activeRegion && entry.country === activeCountry).forEach(entry => {
        const row = n('label', null, 'wl-index-option'), input = n('input'); input.type = 'checkbox';
        input.setAttribute('aria-label', entry.name); input.checked = indexChoices.has(entry.id); indexInputs.set(entry.id, input);
        const text = n('span'); text.append(n('strong', entry.name), n('small', `${entry.symbol} · ${entry.count} stocks`)); row.append(input, text); indexOptions.append(row);
        input.addEventListener('change', () => {
          if (input.checked) chooseIndex(entry);
          else { indexChoices.delete(entry.id); changed(); }
        });
      });
    }
    function renderCountries() {
      countries.replaceChildren(); countrySection.hidden = false; indexSection.hidden = true; indexInputs.clear();
      [...new Set(catalog.filter(entry => entry.region === activeRegion).map(entry => entry.country))].sort().forEach(country => {
        const b = button(country, () => {
          activeCountry = country; countries.querySelectorAll('button').forEach(item => item.setAttribute('aria-pressed', String(item === b))); renderIndexes();
        }, 'wl-geography-button'); b.setAttribute('aria-pressed', 'false'); countries.append(b);
      });
    }
    async function loadCatalog() {
      catalogStatus.replaceChildren(n('span', 'Loading continents…'));
      try {
        catalog = await api('/api/watchlists/indexes'); if (!d.isConnected) return;
        catalogStatus.replaceChildren(); regions.replaceChildren();
        [...new Set(catalog.map(entry => entry.region))].sort().forEach(region => {
          const b = button(region, () => {
            activeRegion = region; activeCountry = ''; regions.querySelectorAll('button').forEach(item => item.setAttribute('aria-pressed', String(item === b))); renderCountries();
          }, 'wl-geography-button'); b.setAttribute('aria-pressed', 'false'); regions.append(b);
        });
        if (!catalog.length) catalogStatus.textContent = 'No index collections are available. You can still search individual tickers.';
      } catch (error) {
        catalogStatus.replaceChildren(n('span', 'Indexes could not be loaded. You can still search individual tickers. '), button('Retry', loadCatalog));
      }
    }
    let searchSequence = 0, timer;
    search.input.addEventListener('input', () => {
      clearTimeout(timer); const seq = ++searchSequence;
      timer = setTimeout(async () => {
        const q = search.input.value.trim(); if (q.length < 1) { results.replaceChildren(); return; }
        try {
          const found = await api(`/api/stocks/search?q=${encodeURIComponent(q)}`);
          if (seq !== searchSequence || !d.isConnected) return; results.replaceChildren();
          for (const item of found) results.append(button(`${item.symbol} · ${item.name} · ${item.region || item.country || ''}`, () => { selected.set(item.symbol, item); search.input.value = ''; results.replaceChildren(); changed(); }));
          if (!found.length) results.append(n('p', 'No instruments found.', 'wl-muted'));
        } catch (error) { if (seq === searchSequence) results.replaceChildren(n('p', error.message, 'wl-status')); }
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
          previewed = true; save.textContent = 'Save watchlist changes'; return;
        }
        if ((selected.size || indexChoices.size || applyExisting) && !previewed) {
          const preview = await api(`/api/watchlists/imports/preview${list ? `?watchlistId=${list.id}` : ''}`, { method: 'POST', body: JSON.stringify(payload) });
          previewArea.replaceChildren(n('p', `${preview.uniqueCount} unique instruments · ${preview.duplicates} duplicates removed · ${preview.alreadyPresent} already in this list`, 'wl-muted'));
          if (monitoring.restrictionsChanged()) previewArea.append(n('p', monitoring.restrictionSummary(), 'wl-status'));
          if (preview.warning) previewArea.append(n('p', preview.warning, 'wl-status'));
          if (preview.skippedTickerInstruments) previewArea.append(n('p', `${preview.skippedTickerInstruments} indexes or ETFs skipped for ticker alerts. Technical selections still apply.`, 'wl-muted'));
          if (payload.selections.some(value => value.watch && ['CONGRESS', 'INSIDER'].includes(value.type))) previewArea.append(n('p', `${preview.congressRemaining} congressional and ${preview.insiderRemaining} insider follow slots remaining`, 'wl-muted'));
          previewArea.append(n('p', `${preview.newlyMonitored} new monitored instruments · ${preview.capacityRemaining} remaining monitoring capacity`, 'wl-muted'));
          previewed = preview.allowed; save.textContent = preview.allowed ? 'Save watchlist changes' : 'Review changes';
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
    }).catch(error => { status.textContent = `Settings could not be loaded: ${error.message} Close and retry to avoid overwriting them.`; save.disabled = true; }) : Promise.resolve();
    loadCatalog();
    await loadSettings;
    changed();
  }
  async function showImport(id) {
    const d = modal('Updating watchlist instruments'), status = n('p', '', 'wl-status'), errors = n('div');
    const actions = n('div', null, 'wl-actions'); actions.append(button('Close — continue in background', () => d.close())); d.append(status, errors, actions);
    async function poll() {
      try {
        const job = await api(`/api/watchlists/imports/${id}`);
        status.textContent = `${job.completed} of ${job.total} updated · ${job.failed} failed · ${job.pending} remaining`;
        errors.replaceChildren(); job.errors.forEach(error => errors.append(n('p', `${error.symbol}: ${error.error}`, 'wl-muted')));
        if (job.pending) setTimeout(poll, d.isConnected ? 2000 : 10000);
        else {
          await refresh();
          if (job.failed) actions.append(button('Retry failed instruments', async event => {
            const retry = event.currentTarget; retry.disabled = true;
            try { await api(`/api/watchlists/imports/${id}/retry`, { method: 'POST' }); retry.remove(); poll(); }
            catch (error) { status.textContent = error.message; retry.disabled = false; }
          }));
        }
      } catch (error) { status.textContent = error.message; actions.append(button('Retry status', poll)); }
    }
    poll();
  }
  async function recentImports() {
    const d = modal('Recent imports'), content = n('div');
    d.append(content, button('Close', () => d.close()));
    content.append(n('p', 'Loading imports…', 'wl-status'));
    try {
      const jobs = await api('/api/watchlists/imports'); content.replaceChildren();
      if (!jobs.length) content.append(n('p', 'No imports yet.', 'wl-muted'));
      jobs.forEach(job => content.append(button(
        `Import ${job.id}: ${job.completed} of ${job.total} updated · ${job.pending} remaining · ${job.failed} failed`,
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
      if (selection) { await api(`/api/watchlists/memberships/${encodeURIComponent(b.dataset.symbol)}`, { method: 'POST', body: JSON.stringify(selection) }); b.textContent = 'Added to watchlists'; await refresh(); }
    } catch (error) { b.textContent = error.message; }
    finally { b.disabled = false; }
  }));
  refresh();
})();
