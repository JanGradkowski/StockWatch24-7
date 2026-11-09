(() => {
  'use strict';
  // One custom alert's page: the rule in words, its switch and menu, the stocks it watches, and its triggers as a
  // table that filters, sorts and (after "Select") deletes in place.
  const page = document.querySelector('.ca-alert-page');
  if (!page) return;
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  const el = text.element;
  // The shared request helper (watchlist-picker.js) loads with the navigation, after this script.
  const api = (...args) => window.StockWatchLists.api(...args);
  let alert = JSON.parse(page.dataset.alert || '{}');
  const status = document.querySelector('[data-ca-alert-status]');
  const notify = message => { status.textContent = message; };

  // ------------------------------------------------------------------ header and rule

  function meta() {
    const parts = [alert.activityOnly ? i18n.t('ca.card.onFilings', text.intervalsLabel(alert.intervals)) : text.intervalsLabel(alert.intervals)];
    alert.targets.forEach(target => parts.push(target.scope === 'ALL_MEMBERS'
      ? i18n.plural('ca.target.allStocks', target.stocks, target.watchlistName, target.stocks)
      : i18n.t('ca.target.selected', target.watchlistName, target.symbols.join(', '))));
    parts.push(i18n.t(alert.emailEnabled ? 'ca.card.emailOn' : 'ca.card.noEmail'));
    document.querySelector('[data-ca-alert-meta]').textContent = parts.join(' · ');
    document.querySelector('[data-ca-alert-paused]').hidden = alert.active;
  }

  function header() {
    meta();
    const field = document.querySelector('[data-ca-alert-switch]');
    const label = el('span', 'ca-switch-text', i18n.t(alert.active ? 'ca.alert.statusOn' : 'ca.alert.statusOff'));
    const control = text.toggleSwitch(alert.active, i18n.t('ca.switch.aria', alert.name), async () => {
      control.disabled = true;
      try {
        alert = await api(`/api/custom-alerts/${alert.id}/active?value=${!alert.active}`, { method: 'POST' });
        header();
        notify(i18n.t(alert.active ? 'ca.alert.resumed' : 'ca.alert.paused'));
        field.querySelector('.ca-switch')?.focus();
      } catch (error) { notify(error.message); control.disabled = false; }
    });
    label.setAttribute('aria-hidden', 'true');
    field.replaceChildren(control, label);
    document.querySelector('[data-ca-alert-menu]').replaceChildren(text.menu(i18n.t('ca.menu.aria', alert.name), [
      { label: i18n.t('ca.menu.duplicate'), icon: 'copy', run: async () => {
        try {
          const created = await api(`/api/custom-alerts/${alert.id}/duplicate`, { method: 'POST' });
          window.location.assign(`/custom-alerts/${created.id}`);
        } catch (error) { notify(error.message); }
      } },
      { label: i18n.t(alert.emailEnabled ? 'ca.menu.emailOff' : 'ca.menu.emailOn'), icon: 'mail', run: async () => {
        try { alert = await api(`/api/custom-alerts/${alert.id}/email?value=${!alert.emailEnabled}`, { method: 'POST' }); header(); }
        catch (error) { notify(error.message); }
      } },
      'divider',
      { label: i18n.t('ca.menu.deleteEllipsis'), icon: 'trash', danger: true, run: confirmDeleteAlert }
    ]));
  }

  async function rule() {
    const catalog = await text.catalog().catch(() => ({ byType: new Map() }));
    document.querySelector('[data-ca-alert-summary]').textContent = text.summary(alert, catalog.byType);
  }

  // ------------------------------------------------------------------ dialogs

  function dialog(title, body, confirmLabel, onConfirm) {
    const box = el('dialog', 'ca-dialog ca-confirm');
    const heading = el('h2', null, title);
    heading.id = `ca-dialog-${Date.now()}`;
    box.setAttribute('aria-labelledby', heading.id);
    const message = el('p', 'ca-dialog-text');
    message.append(...body);
    const problem = el('p', 'ca-dialog-status');
    problem.setAttribute('role', 'status');
    const actions = el('div', 'ca-dialog-actions');
    const cancel = el('button', 'btn-secondary ca-btn-ghost', i18n.t('common.cancel'));
    cancel.type = 'button';
    cancel.addEventListener('click', () => box.close());
    const confirm = el('button', 'ca-btn-danger', confirmLabel);
    confirm.type = 'button';
    confirm.addEventListener('click', async () => {
      confirm.disabled = true;
      try { await onConfirm(); box.close(); }
      catch (error) { problem.textContent = error.message; confirm.disabled = false; }
    });
    actions.append(cancel, confirm);
    box.append(heading, message, problem, actions);
    box.addEventListener('close', () => box.remove());
    document.body.append(box);
    box.showModal();
    cancel.focus();
  }

  async function fetchDelete(url) {
    const token = document.querySelector('meta[name="_csrf"]')?.content || document.getElementById('accountThemeSync')?.dataset.csrfToken;
    const headerName = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    const response = await fetch(url, { method: 'DELETE', credentials: 'same-origin', headers: token ? { [headerName]: token } : {} });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.error || i18n.t('common.saveFailed'));
    }
  }

  function confirmDeleteAlert() {
    const rest = alert.triggers ? i18n.plural('ca.delete.withTriggers', alert.triggers, alert.triggers) : i18n.t('ca.delete.text');
    dialog(i18n.t('ca.delete.title'), [el('strong', null, alert.name), ` ${rest}`], i18n.t('ca.delete.confirm'), async () => {
      await fetchDelete(`/api/custom-alerts/${alert.id}`);
      window.location.assign('/custom-alerts');
    });
  }

  // ------------------------------------------------------------------ the stocks it watches

  const stocksToggle = document.querySelector('[data-ca-stocks-toggle]');
  const stocks = document.querySelector('#ca-alert-stocks');
  stocksToggle?.addEventListener('click', () => {
    const open = stocks.hidden;
    stocks.hidden = !open;
    stocksToggle.setAttribute('aria-expanded', String(open));
    if (open) {
      window.StockWatchCustomAlertStates.render(stocks.querySelector('[data-ca-alert-states]'), alert, {
        onChange: updated => { alert = updated; }, onError: notify
      });
      stocks.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
  });

  // ------------------------------------------------------------------ triggers

  const table = document.querySelector('[data-ca-triggers]');
  const rows = table ? [...table.querySelectorAll('tbody tr')] : [];
  const search = document.querySelector('[data-ca-trigger-search]');
  const filters = [...document.querySelectorAll('[data-ca-trigger-filter]')];
  const none = document.querySelector('[data-ca-triggers-none]');
  // Wide screens show these as buttons; phones keep them in a menu beside the filter.
  const readAlls = [...document.querySelectorAll('[data-ca-trigger-read-all]')];
  const selectButtons = [...document.querySelectorAll('[data-ca-trigger-select]')];
  const selectButton = selectButtons[0];
  const bulk = document.querySelector('[data-ca-bulk]');
  const bulkCount = document.querySelector('[data-ca-bulk-count]');
  const bulkDelete = document.querySelector('[data-ca-bulk-delete]');
  const selectAll = document.querySelector('[data-ca-select-all]');
  let filter = 'all';
  let sort = { key: 'fired', direction: -1 };
  const SORT_KEY = `stockwatch.customAlertSort.${alert.id}`;

  // Times read as "Today · 15:11" or "Nov 6 · 09:30", with the full date in the tooltip.
  rows.forEach(row => {
    const time = row.querySelector('[data-ca-time]');
    const fired = Number(row.dataset.fired);
    time.textContent = `${text.relativeDay(fired)} · ${i18n.date(new Date(fired), { hour: 'numeric', minute: '2-digit' })}`;
    time.title = text.dateTime(fired);
    // A click anywhere on the row opens the trigger, as its ticker link does; while selecting, it ticks the row.
    row.addEventListener('click', event => {
      if (event.target.closest('a, button, input, label')) return;
      if (page.classList.contains('is-selecting')) {
        const box = row.querySelector('[data-ca-row-select]');
        box.checked = !box.checked;
        updateBulk();
        return;
      }
      if (window.getSelection()?.toString()) return;
      window.location.assign(row.querySelector('.ca-trigger-link').href);
    });
  });

  function visibleRows() { return rows.filter(row => !row.hidden); }

  // ------------------------------------------------------------------ the "already matching when saved" snapshot

  /**
   * Saving an alert with "tell me about stocks that already match" records a trigger for every matching stock at
   * once. In the default view (newest first, unfiltered) such a batch folds into one row that opens to show them.
   */
  const batches = [];
  {
    let current = null;
    rows.forEach(row => {
      if (row.dataset.kind !== 'MATCHED_ON_APPLY') { current = null; return; }
      if (current?.day === row.dataset.day) { current.rows.push(row); return; }
      current = { day: row.dataset.day, rows: [row], open: false };
      batches.push(current);
    });
  }
  const median = values => {
    const sorted = values.filter(Number.isFinite).sort((a, b) => a - b);
    if (!sorted.length) return null;
    const middle = sorted.length >> 1;
    return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  };
  batches.filter(batch => batch.rows.length >= 3).forEach(batch => {
    const header = el('tr', 'ca-batch-row');
    const cell = el('td');
    cell.colSpan = table?.querySelectorAll('thead th').length || 7;
    const toggle = el('button', 'ca-batch-toggle');
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', 'false');
    const changes = batch.rows.map(row => row.dataset.change === undefined ? NaN : Number(row.dataset.change));
    const middle = median(changes);
    const unread = batch.rows.some(row => row.dataset.unread === 'true');
    const words = el('span', 'ca-batch-words');
    const firedAt = Number(batch.rows[0].dataset.fired);
    words.append(el('strong', null, i18n.t('ca.alert.batchTitle', `${text.date(firedAt, true)}, ${i18n.date(new Date(firedAt), { hour: 'numeric', minute: '2-digit' })}`)),
      el('span', null, i18n.plural('ca.alert.batchText', batch.rows.length, batch.rows.length)));
    toggle.append(text.icon('chevron', 'ca-icon ca-batch-chevron'));
    if (unread) toggle.append(el('i', 'ca-dot'));
    toggle.append(words);
    // The batch's numbers, and once for all its rows when the next checkpoint closes.
    const { nextMark, due } = batch.rows[0].dataset;
    if (middle != null || (nextMark && due)) {
      const summary = el('span', 'ca-batch-median');
      if (middle != null) summary.append(el('strong', `ca-change ${middle >= 0 ? 'is-up' : 'is-down'}`, text.signed(middle)), ` ${i18n.t('ca.alert.batchMedian')}`);
      if (nextMark && due) summary.append(middle != null ? ' · ' : '', i18n.t('ca.alert.batchDue', nextMark, due));
      toggle.append(summary);
    }
    toggle.addEventListener('click', () => {
      batch.open = !batch.open;
      toggle.setAttribute('aria-expanded', String(batch.open));
      apply();
    });
    cell.append(toggle);
    header.append(cell);
    batch.header = header;
  });
  const folding = () => filter === 'all' && !(search?.value || '').trim() && sort.key === 'fired' && sort.direction < 0;

  function apply() {
    const query = (search?.value || '').trim().toUpperCase().split(/[\s,]+/).filter(Boolean);
    const folded = folding();
    rows.forEach(row => {
      const measured = row.dataset.complete === 'true' && row.dataset.change !== undefined && row.dataset.change !== '';
      const unreadOk = filter === 'unread' ? row.dataset.unread === 'true'
        : filter === 'higher' ? measured && Number(row.dataset.change) > 0
          : filter === 'lower' ? measured && Number(row.dataset.change) <= 0 : true;
      const tickerOk = !query.length || query.some(part => row.dataset.symbol.includes(part));
      row.hidden = !(unreadOk && tickerOk);
      row.classList.remove('is-in-batch');
    });
    batches.filter(batch => batch.header).forEach(batch => {
      if (!folded) { batch.header.remove(); return; }
      batch.rows[0].before(batch.header);
      batch.rows.forEach(row => { row.classList.add('is-in-batch'); if (!batch.open) row.hidden = true; });
    });
    if (none) none.hidden = visibleRows().length > 0;
    filters.forEach(button => {
      const selected = button.dataset.caTriggerFilter === filter;
      button.setAttribute('aria-selected', String(selected));
      button.tabIndex = selected ? 0 : -1;
    });
    updateBulk();
  }

  function sortBy(key, column = 0) {
    const id = key === 'after' ? `after${column}` : key;
    sort = { key: id, direction: sort.key === id ? -sort.direction : key === 'symbol' ? 1 : -1 };
    const number = text => text === undefined || text === '' ? -Infinity : Number(text);
    const value = row => key === 'symbol' ? row.dataset.symbol : key === 'fired' ? Number(row.dataset.fired)
      : key === 'after' ? number(row.querySelectorAll('.ca-col-after')[column]?.dataset.value) : number(row.dataset.change);
    rows.sort((a, b) => {
      const left = value(a);
      const right = value(b);
      return (typeof left === 'string' ? left.localeCompare(right) : left - right) * sort.direction || Number(b.dataset.fired) - Number(a.dataset.fired);
    });
    const body = table.querySelector('tbody');
    rows.forEach(row => body.append(row));
    table.querySelectorAll('th[aria-sort]').forEach(th => {
      const button = th.querySelector('[data-ca-sort]');
      const own = button?.dataset.caSort === key && (key !== 'after' || Number(button.dataset.caAfter) === column);
      th.setAttribute('aria-sort', own ? (sort.direction > 0 ? 'ascending' : 'descending') : 'none');
    });
    try { localStorage.setItem(SORT_KEY, JSON.stringify(sort)); } catch { /* storage off: the order is not remembered */ }
  }

  /** The order chosen on an earlier visit to this alert, unless it is the default (newest first). */
  function restoreSort() {
    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(SORT_KEY) || 'null'); } catch { /* storage off */ }
    if (!saved || typeof saved.key !== 'string' || (saved.key === 'fired' && saved.direction < 0)) return;
    const after = /^after(\d+)$/.exec(saved.key);
    const [key, column] = after ? ['after', Number(after[1])] : [saved.key, 0];
    if (!table.querySelector(after ? `[data-ca-sort="after"][data-ca-after="${column}"]` : `[data-ca-sort="${key}"]`)) return;
    // sortBy turns a column already sorted around, so start from the other direction.
    sort = { key: saved.key, direction: saved.direction > 0 ? -1 : 1 };
    sortBy(key, column);
  }

  function counts() {
    const unread = rows.filter(row => row.dataset.unread === 'true').length;
    document.querySelector('[data-ca-count-all]').textContent = String(rows.length);
    document.querySelector('[data-ca-count-unread]').textContent = String(unread);
    const measured = rows.filter(row => row.dataset.complete === 'true' && row.dataset.change !== undefined && row.dataset.change !== '');
    const higher = measured.filter(row => Number(row.dataset.change) > 0).length;
    const higherCount = document.querySelector('[data-ca-count-higher]');
    if (higherCount) higherCount.textContent = String(higher);
    const lowerCount = document.querySelector('[data-ca-count-lower]');
    if (lowerCount) lowerCount.textContent = String(measured.length - higher);
    document.querySelector('[data-ca-kpi-triggers]').textContent = String(alert.triggers);
    document.querySelector('[data-ca-kpi-unread]').textContent = unread ? i18n.t('ca.alert.unreadCount', unread) : i18n.t('ca.alert.allRead');
    readAlls.forEach(button => { button.hidden = !unread; });
  }

  function updateBulk() {
    if (!bulk) return;
    const chosen = rows.filter(row => !row.hidden && row.querySelector('[data-ca-row-select]').checked);
    bulkCount.textContent = i18n.plural('ca.alert.selected', chosen.length, chosen.length);
    bulkDelete.disabled = !chosen.length;
    const shown = visibleRows();
    selectAll.checked = shown.length > 0 && chosen.length === shown.length;
    selectAll.indeterminate = chosen.length > 0 && chosen.length < shown.length;
    rows.forEach(row => row.classList.toggle('is-selected', row.querySelector('[data-ca-row-select]').checked));
  }

  function setSelecting(on) {
    page.classList.toggle('is-selecting', on);
    bulk.hidden = !on;
    selectButton.setAttribute('aria-pressed', String(on));
    // Selecting shows every trigger, so a folded snapshot cannot hide what gets ticked.
    batches.forEach(batch => { batch.open = on || batch.open; });
    apply();
    if (!on) rows.forEach(row => { row.querySelector('[data-ca-row-select]').checked = false; });
    updateBulk();
    if (on) selectAll.focus(); else selectButton.focus();
  }

  if (table) {
    filters.forEach((button, index) => {
      button.addEventListener('click', () => { filter = button.dataset.caTriggerFilter; apply(); });
      button.addEventListener('keydown', event => {
        const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
        if (!step) return;
        event.preventDefault();
        const next = filters[(index + step + filters.length) % filters.length];
        filter = next.dataset.caTriggerFilter;
        apply();
        next.focus();
      });
    });
    search?.addEventListener('input', apply);
    text.escapeClears(search);
    text.shortcuts([[['/'], i18n.t('ca.shortcuts.filter')], [['Esc'], i18n.t('ca.shortcuts.clear')]]);
    table.querySelectorAll('[data-ca-sort]').forEach(button => button.addEventListener('click', () => {
      sortBy(button.dataset.caSort, Number(button.dataset.caAfter || 0));
      apply();
    }));
    selectButtons.forEach(button => button.addEventListener('click', () => setSelecting(!page.classList.contains('is-selecting'))));
    document.querySelector('[data-ca-bulk-done]')?.addEventListener('click', () => setSelecting(false));
    table.addEventListener('change', event => { if (event.target.matches('[data-ca-row-select]')) updateBulk(); });
    selectAll?.addEventListener('change', () => {
      visibleRows().forEach(row => { row.querySelector('[data-ca-row-select]').checked = selectAll.checked; });
      updateBulk();
    });
    bulkDelete?.addEventListener('click', () => {
      const chosen = rows.filter(row => !row.hidden && row.querySelector('[data-ca-row-select]').checked);
      dialog(i18n.plural('ca.alert.deleteTitle', chosen.length, chosen.length), [i18n.t('ca.alert.deleteText')], i18n.t('ca.alert.deleteConfirm'), async () => {
        const result = await api(`/api/custom-alerts/${alert.id}/triggers/delete`, { method: 'POST', body: JSON.stringify({ ids: chosen.map(row => Number(row.dataset.id)) }) });
        chosen.forEach(row => { row.remove(); rows.splice(rows.indexOf(row), 1); });
        alert.triggers = Math.max(0, alert.triggers - result.deleted);
        notify(i18n.plural('ca.alert.deleted', result.deleted, result.deleted));
        counts();
        apply();
        if (!rows.length) window.location.reload();
      });
    });
    readAlls.forEach(readAll => readAll.addEventListener('click', async () => {
      readAll.disabled = true;
      try {
        const result = await api(`/api/custom-alerts/read-all?alertId=${alert.id}`, { method: 'POST' });
        rows.forEach(row => {
          row.dataset.unread = 'false';
          row.classList.remove('is-unread');
          row.querySelector('.ca-dot')?.remove();
          row.querySelector('.ca-col-ticker .visually-hidden')?.remove();
        });
        batches.forEach(batch => batch.header?.querySelector('.ca-dot')?.remove());
        notify(i18n.plural('ca.readAll.done', result.updated, result.updated));
        counts();
        apply();
      } catch (error) { notify(error.message); }
      readAll.disabled = false;
    }));
    // "/" jumps to the ticker filter, unless the user is typing.
    document.addEventListener('keydown', event => {
      if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey) return;
      if (event.target instanceof Element && event.target.closest('input, textarea, select, [contenteditable="true"], dialog')) return;
      event.preventDefault();
      search.focus();
    });
    restoreSort();
    apply();
  }

  /**
   * The Triggers tile: triggers per day over the alert's life (at most 180 days), on a baseline with its first day and
   * today named. A square-root scale keeps a busy day from flattening the rest; stocks already matching when the alert
   * was saved are no trigger of their own, so they are a marker on their day, not a bar.
   */
  function history() {
    const box = document.querySelector('[data-ca-history]');
    if (!box) return;
    const DAY = 86400000;
    const startOf = value => { const day = new Date(value); day.setHours(0, 0, 0, 0); return day.getTime(); };
    const today = startOf(Date.now());
    const firsts = [alert.createdAt ? new Date(alert.createdAt).getTime() : Date.now(), ...rows.map(row => Number(row.dataset.fired))];
    const days = Math.max(14, Math.min(180, Math.round((today - startOf(Math.min(...firsts))) / DAY) + 1));
    const fired = new Array(days).fill(0);
    const atSave = new Array(days).fill(0);
    rows.forEach(row => {
      const at = days - 1 - Math.round((today - startOf(Number(row.dataset.fired))) / DAY);
      if (at < 0 || at >= days) return;
      if (row.dataset.kind === 'MATCHED_ON_APPLY') atSave[at]++; else fired[at]++;
    });
    const width = days * 4;
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', `0 0 ${width} 28`);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('class', 'ca-history-bars');
    svg.setAttribute('role', 'img');
    const total = fired.reduce((sum, value) => sum + value, 0);
    svg.setAttribute('aria-label', i18n.plural('ca.alert.historyAria', total, total, days));
    const rect = (attributes, className) => {
      const node = document.createElementNS(svg.namespaceURI, 'rect');
      Object.entries(attributes).forEach(([key, value]) => node.setAttribute(key, value));
      if (className) node.setAttribute('class', className);
      return node;
    };
    svg.append(rect({ x: 0, y: 27, width, height: 1 }, 'ca-history-base'));
    const top = Math.sqrt(Math.max(1, ...fired));
    fired.forEach((value, at) => {
      if (!value) return;
      const height = Math.max(4, Math.round((Math.sqrt(value) / top) * 24));
      const bar = rect({ x: at * 4 + 0.5, y: 27 - height, width: 3, height });
      const title = document.createElementNS(svg.namespaceURI, 'title');
      title.textContent = `${text.date(today - (days - 1 - at) * DAY, true)}: ${i18n.plural('ca.alert.historyDay', value, value)}`;
      bar.append(title);
      svg.append(bar);
    });
    atSave.forEach((value, at) => {
      if (!value) return;
      const mark = rect({ x: at * 4, y: 21, width: 4, height: 4 }, 'ca-history-save');
      const title = document.createElementNS(svg.namespaceURI, 'title');
      title.textContent = `${text.date(today - (days - 1 - at) * DAY, true)}: ${i18n.plural('ca.alert.historyAtSave', value, value)}`;
      mark.append(title);
      svg.append(mark);
    });
    const caption = el('p', 'ca-history-caption');
    caption.append(el('span', null, i18n.t('ca.alert.historyCaption')));
    const saved = atSave.reduce((sum, value) => sum + value, 0);
    if (saved) {
      const key = el('span', 'ca-history-save-key');
      key.append(el('i'), i18n.plural('ca.alert.historyAtSave', saved, saved));
      caption.append(key);
    }
    const axis = el('div', 'ca-history-axis-labels');
    axis.append(el('span', null, text.date(today - (days - 1) * DAY, true)), el('span', null, i18n.t('ca.alert.historyToday')));
    box.replaceChildren(caption, svg, axis);
  }

  /**
   * The outcome tile: every measured trigger's change as a dot on one line around zero, the median marked, and how many
   * closed higher and lower. With a dozen triggers the spread says as much as the median.
   */
  function spread() {
    const box = document.querySelector('[data-ca-spread]');
    if (!box) return;
    const measured = rows.filter(row => row.dataset.complete === 'true' && row.dataset.change !== undefined && row.dataset.change !== '')
      .map(row => ({ row, value: Number(row.dataset.change) })).filter(item => Number.isFinite(item.value)).sort((a, b) => a.value - b.value);
    const values = measured.map(item => item.value);
    if (!values.length) { box.remove(); return; }
    // The line runs from the lowest change to the highest, zero always on it; its ends are named underneath.
    const percent = new Intl.NumberFormat(i18n.tag || 'en', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    const signed = value => `${value > 0 ? '+' : value < 0 ? '−' : ''}${percent.format(Math.abs(value))}%`;
    const low = Math.min(0, values[0]);
    const high = Math.max(0, values.at(-1));
    const higher = values.filter(value => value > 0).length;
    const middle = values.length % 2 ? values[values.length >> 1] : (values[values.length / 2 - 1] + values[values.length / 2]) / 2;
    const labels = el('div', 'ca-spread-labels');
    labels.append(el('span', null, signed(low)), el('span', 'ca-spread-count', i18n.t('ca.alert.spreadCount', higher, values.length - higher)),
      el('span', null, signed(high)));
    const draw = () => {
      // Drawn to the tile's width, so the dots stay round.
      const width = Math.max(120, Math.round(box.clientWidth));
      const x = value => 6 + ((value - low) / ((high - low) || 1)) * (width - 12);
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('viewBox', `0 -12 ${width} 46`);
      svg.setAttribute('class', 'ca-spread');
      svg.setAttribute('role', 'img');
      svg.setAttribute('aria-label', i18n.t('ca.alert.spreadAria', higher, values.length - higher, signed(values[0]), signed(values.at(-1))));
      const line = (x1, y1, x2, y2, className) => {
        const node = document.createElementNS(svg.namespaceURI, 'line');
        Object.entries({ x1, y1, x2, y2, class: className }).forEach(([key, value]) => node.setAttribute(key, value));
        return node;
      };
      svg.append(line(6, 17, width - 6, 17, 'ca-spread-axis'), line(x(0), 3, x(0), 31, 'ca-spread-zero'));
      svg.append(line(x(middle), 5, x(middle), 29, 'ca-spread-median'));
      // Each dot opens its trigger. Dots that would touch step above and below the line, so every trigger stays visible.
      const placedDots = [];
      measured.forEach(({ row, value }) => {
        const cx = x(value);
        const cy = 17 + ([0, -8, 8, -15, 15].find(offset => placedDots.every(dot => Math.hypot(dot.cx - cx, dot.cy - 17 - offset) >= 8.5)) ?? 0);
        placedDots.push({ cx, cy });
        const link = document.createElementNS(svg.namespaceURI, 'a');
        link.setAttribute('href', `/custom-alerts/triggers/${row.dataset.id}?returnTo=${encodeURIComponent(window.location.pathname)}`);
        const label = `${row.dataset.symbol} ${signed(value)} · ${text.date(Number(row.dataset.fired), true)}`;
        link.setAttribute('aria-label', label);
        const dot = document.createElementNS(svg.namespaceURI, 'circle');
        Object.entries({ cx, cy, r: 4, class: value > 0 ? 'is-up' : 'is-down' }).forEach(([key, number]) => dot.setAttribute(key, number));
        const title = document.createElementNS(svg.namespaceURI, 'title');
        title.textContent = label;
        link.append(title, dot);
        svg.append(link);
      });
      const zero = document.createElementNS(svg.namespaceURI, 'text');
      Object.entries({ x: x(0), y: 2, class: 'ca-spread-zero-label', 'text-anchor': x(0) < 20 ? 'start' : x(0) > width - 20 ? 'end' : 'middle', 'dominant-baseline': 'hanging' })
        .forEach(([key, value]) => zero.setAttribute(key, value));
      zero.textContent = '0%';
      zero.setAttribute('y', '-11');
      svg.prepend(zero);
      box.replaceChildren(svg, labels);
    };
    draw();
    if (window.ResizeObserver) {
      let drawnAt = box.clientWidth;
      new ResizeObserver(() => {
        if (Math.abs(box.clientWidth - drawnAt) < 4) return;
        drawnAt = box.clientWidth;
        draw();
      }).observe(box);
    }
  }

  header();
  rule();
  history();
  spread();
})();
