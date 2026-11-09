(() => {
  'use strict';
  const { api, node: n } = window.StockWatchLists;
  const i18n = window.StockWatchI18n;
  const SVG = 'http://www.w3.org/2000/svg';
  const INTERVALS = ['DAILY', 'WEEKLY', 'MONTHLY'];
  const TYPE_ORDER = ['CANDLESTICK', 'ELLIOTT_WAVE', 'HARMONIC_FORMATION', 'OUTLOOK', 'CONGRESS', 'INSIDER'];
  const DIRECTION_ORDER = ['BUY', 'SELL', 'ANY'];
  const geo = name => {
    const key = 'geo.' + String(name).replace(/[^A-Za-z]/g, ''), text = i18n.t(key);
    return text === key ? name : text;
  };
  const byLocalName = (a, b) => geo(a).localeCompare(geo(b), i18n.tag);
  const listSummary = list => [i18n.plural('watchlists.instruments', list.instruments, list.instruments),
    i18n.plural('watchlists.followed', list.monitored, list.monitored), i18n.plural('watchlists.unread', list.unread, list.unread)].join(' · ');
  const stockCount = count => i18n.plural('watchlists.stocks', count, count);
  const signalsUrl = (list, params = {}) => `/signals?${new URLSearchParams({ watchlistId: list.id, ...params })}`;
  const root = document.querySelector('[data-named-watchlists]');
  // The Watchlists page shows lists as cards and opens each on its own page; Home keeps expandable rows.
  const management = root?.dataset.management === 'true';
  // Learning-mode explanations (js.learn.<key>) used on this page.
  const LEARN_OPTIONS = { customStrip: 'customAlerts.watchlistStrip', customChip: 'customAlerts.watchlistChip' };
  let lists = [];
  const selections = new Map();
  let idSequence = 0;
  const nextId = prefix => `${prefix}-${++idSequence}`;

  const ICONS = {
    signals: 'M4 19h16M5 14l4-5 4 3 6-8',
    manage: 'M4 7h6m4 0h6M4 17h10m4 0h2M10 4v6m4 4v6',
    remove: 'M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13M10 10v7m4-7v7',
    plus: 'M12 5v14M5 12h14',
    edit: 'M4 20h4L19 9l-4-4L4 16v4ZM13.5 6.5l4 4',
    more: 'M5 12h.01M12 12h.01M19 12h.01',
    star: 'm12 3.5 2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9Z',
    chart: 'M4 4v16h16M8 15l3-4 3 2 5-6',
    read: 'M2.5 12.5 6.5 16.5 14 8.5M11 16l.5.5L21 7',
    imports: 'M12 4v11m0 0 4-4m-4 4-4-4M5 20h14',
    close: 'm6 6 12 12M18 6 6 18',
    search: 'M11 18a7 7 0 1 1 0-14 7 7 0 0 1 0 14Zm5-2 4 4',
    next: 'm9.5 5 7 7-7 7',
    previous: 'm14.5 5-7 7 7 7',
    arrow: 'M5 12h14m-5-5 5 5-5 5',
    list: 'M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01'
  };
  function icon(kind) {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true'); svg.setAttribute('focusable', 'false');
    svg.setAttribute('class', `wl-icon wl-icon-${kind}`);
    const path = document.createElementNS(SVG, 'path'); path.setAttribute('d', ICONS[kind]);
    svg.append(path); return svg;
  }
  const button = (text, handler, style = 'btn-secondary', iconKind = null) => {
    const b = n('button', text, style); b.type = 'button';
    if (iconKind) b.prepend(icon(iconKind));
    b.addEventListener('click', handler); return b;
  };
  /** A button that shows only an icon; its label is the accessible name and the tooltip. */
  function iconButton(label, handler, kind, style = '') {
    const b = n('button', null, `wl-icon-button ${style}`.trim()); b.type = 'button';
    b.setAttribute('aria-label', label); b.title = label; b.append(icon(kind));
    b.addEventListener('click', handler); return b;
  }
  const link = (text, href, style = 'text-link', iconKind = null) => {
    const a = n('a', text, style); a.href = href;
    if (iconKind) a.prepend(icon(iconKind));
    return a;
  };
  function field(text, type = 'text', value = '') {
    const label = n('label', null, 'wl-field'), input = n(type === 'textarea' ? 'textarea' : 'input');
    label.append(n('span', text, 'wl-field-label'));
    if (type !== 'textarea') input.type = type; input.value = value; label.append(input); return { label, input };
  }
  /** A compact select: the label is read out, and shown only when `visible` (for "Sort by"). */
  function select(labelText, options, { visible = false } = {}) {
    const label = n('label', null, 'wl-select'), input = n('select');
    input.setAttribute('aria-label', labelText);
    label.append(n('span', labelText, visible ? 'wl-select-label' : 'visually-hidden'));
    for (const [value, text] of options) { const o = n('option', text); o.value = value; input.append(o); }
    label.append(input); return { label, input };
  }
  /** Status messages that have no place of their own (a failed pin, for example). */
  let toast = null, toastTimer = 0;
  function announce(message) {
    const created = !toast;
    if (created) { toast = n('div', '', 'wl-toast'); toast.setAttribute('role', 'status'); document.body.append(toast); }
    // A live region announces changes, so the first message waits until the region is in the page.
    setTimeout(() => {
      toast.textContent = message; toast.classList.add('is-visible');
      clearTimeout(toastTimer); toastTimer = setTimeout(() => toast.classList.remove('is-visible'), 6000);
    }, created ? 60 : 0);
  }

  /** An overflow menu: a "⋯" button and a list of actions, operated with the mouse or the arrow keys. */
  let activeMenu = null;
  function menu(label, items, style = '') {
    const wrap = n('div', null, `wl-menu ${style}`.trim());
    const list = n('div', null, 'wl-menu-list'); list.id = nextId('wl-menu'); list.hidden = true;
    list.setAttribute('role', 'menu'); list.setAttribute('aria-label', label);
    const trigger = iconButton(label, () => (list.hidden ? open(false) : close(false)), 'more', 'wl-menu-trigger');
    trigger.setAttribute('aria-haspopup', 'menu'); trigger.setAttribute('aria-expanded', 'false'); trigger.setAttribute('aria-controls', list.id);
    for (const item of items) {
      if (!item) continue;
      if (item === 'separator') { const line = n('div', null, 'wl-menu-separator'); line.setAttribute('role', 'separator'); list.append(line); continue; }
      const entry = n(item.href ? 'a' : 'button', null, `wl-menu-item${item.danger ? ' is-danger' : ''}`);
      if (item.href) entry.href = item.href; else entry.type = 'button';
      entry.setAttribute('role', 'menuitem'); entry.tabIndex = -1;
      if (item.icon) entry.append(icon(item.icon));
      entry.append(n('span', item.label));
      // An action returns focus to the menu button first, so a dialog it opens hands focus back there.
      entry.addEventListener('click', event => { close(!item.href); item.action?.(event); });
      list.append(entry);
    }
    const entries = () => Array.from(list.querySelectorAll('[role="menuitem"]'));
    // The list is fixed to the viewport, so table and panel overflow never clips it.
    function place() {
      const rect = trigger.getBoundingClientRect(), width = list.offsetWidth, height = list.offsetHeight;
      const left = Math.max(8, Math.min(rect.right - width, window.innerWidth - width - 8));
      const below = rect.bottom + 6;
      const top = below + height > window.innerHeight - 8 && rect.top - height - 6 > 8 ? rect.top - height - 6 : below;
      list.style.left = `${left}px`; list.style.top = `${top}px`;
    }
    function open(fromEnd) {
      if (activeMenu && activeMenu !== control) activeMenu.close(false);
      list.hidden = false; trigger.setAttribute('aria-expanded', 'true'); activeMenu = control; place();
      const all = entries(); (fromEnd ? all.at(-1) : all[0])?.focus({ preventScroll: true });
    }
    function close(returnFocus) {
      if (list.hidden) return;
      list.hidden = true; trigger.setAttribute('aria-expanded', 'false');
      if (activeMenu === control) activeMenu = null;
      if (returnFocus) trigger.focus();
    }
    const control = { close, wrap };
    trigger.addEventListener('keydown', event => {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); open(event.key === 'ArrowUp'); }
    });
    list.addEventListener('keydown', event => {
      const all = entries(), index = all.indexOf(document.activeElement);
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault(); all[(index + (event.key === 'ArrowDown' ? 1 : -1) + all.length) % all.length]?.focus({ preventScroll: true });
      } else if (event.key === 'Home' || event.key === 'End') {
        event.preventDefault(); (event.key === 'Home' ? all[0] : all.at(-1))?.focus({ preventScroll: true });
      } else if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(true); }
      else if (event.key === 'Tab') close(false);
    });
    wrap.append(trigger, list);
    return wrap;
  }
  document.addEventListener('click', event => { if (activeMenu && !activeMenu.wrap.contains(event.target)) activeMenu.close(false); });
  window.addEventListener('resize', () => activeMenu?.close(false));
  document.addEventListener('scroll', () => activeMenu?.close(false), true);

  /**
   * Tickers from pasted text: commas, semicolons, tabs, spaces and new lines separate them; quotes and a
   * "Symbol"/"Ticker" header are dropped. Values the server would reject come back as invalid.
   */
  function parseTickers(text) {
    const valid = [], invalid = [];
    String(text || '').split(/[\s,;|]+/).map(value => value.replace(/^["']+|["']+$/g, '').trim().toUpperCase())
      .filter(value => value && !['SYMBOL', 'TICKER', 'SYMBOLS', 'TICKERS'].includes(value))
      .forEach(value => {
        const target = /^[A-Z0-9^][A-Z0-9.^=&_-]{0,19}$/.test(value) ? valid : invalid;
        if (!target.includes(value)) target.push(value);
      });
    return { valid, invalid };
  }
  /** Latest completed daily close, its change and a 30-close sparkline. */
  function priceCell(item) {
    const cell = n('td', null, 'wl-price-cell'); cell.dataset.label = i18n.t('watchlists.column.price');
    const quote = item.quote;
    if (!quote) { cell.append(n('span', i18n.t('watchlists.noPrice'), 'wl-no-data')); return cell; }
    const rising = quote.changePercent >= 0;
    const layout = n('div', null, 'wl-price-layout'), text = n('div', null, 'wl-price-text');
    const price = n('div', null, 'wl-price');
    price.append(n('strong', i18n.number(quote.close, quote.close < 1 ? 4 : 2)), n('small', item.currency || ''));
    const meta = n('div', null, 'wl-price-meta');
    meta.append(n('span', `${rising ? '+' : '−'}${i18n.number(Math.abs(quote.changePercent), 2)}%`, `wl-price-change ${rising ? 'is-up' : 'is-down'}`),
      n('span', i18n.t('watchlists.closeOn', i18n.date(quote.closeTimestamp * 1000, { day: 'numeric', month: 'short', timeZone: 'UTC' })), 'wl-price-date'));
    text.append(price, meta); layout.append(text);
    if (quote.sparkline) {
      const svg = document.createElementNS(SVG, 'svg');
      svg.setAttribute('viewBox', '0 0 100 32'); svg.setAttribute('preserveAspectRatio', 'none');
      svg.setAttribute('aria-hidden', 'true'); svg.setAttribute('focusable', 'false');
      svg.setAttribute('class', `wl-spark ${rising ? 'is-up' : 'is-down'}`);
      const path = document.createElementNS(SVG, 'path'); path.setAttribute('d', quote.sparkline);
      svg.append(path); layout.append(svg);
    }
    cell.append(layout);
    return cell;
  }
  /** The followed daily outlook, in its own column. */
  function outlookCell(item) {
    const cell = n('td', null, 'wl-outlook-cell'); cell.dataset.label = i18n.t('watchlists.column.outlook');
    const quote = item.quote;
    if (quote?.outlookLabel) cell.append(n('span', quote.outlookLabel, `wl-price-outlook is-${quote.outlookTone}`));
    else { cell.classList.add('is-empty'); cell.append(n('span', '—', 'wl-no-data')); }
    return cell;
  }
  /**
   * One chip per followed signal type: its name, the direction when only buy or only sell is followed, and the
   * intervals as letters (D W M). The full wording is the chip's accessible name and tooltip.
   */
  function followChips(signals) {
    const byType = new Map();
    for (const value of signals || []) {
      if (!byType.has(value.type)) byType.set(value.type, new Map());
      const directions = byType.get(value.type);
      if (!directions.has(value.direction)) directions.set(value.direction, new Set());
      directions.get(value.direction).add(value.interval);
    }
    const rank = (order, value) => { const index = order.indexOf(value); return index < 0 ? order.length : index; };
    return [...byType].sort(([a], [b]) => rank(TYPE_ORDER, a) - rank(TYPE_ORDER, b)).map(([type, directions]) => {
      const label = TYPE_ORDER.includes(type) ? i18n.t(`watchlists.type.${type}`) : type;
      const chip = n('div', null, 'wl-follow-group'); chip.setAttribute('role', 'group');
      chip.append(n('span', label, 'wl-follow-name'));
      const ordered = set => INTERVALS.filter(interval => set.has(interval));
      const buy = directions.get('BUY'), sell = directions.get('SELL');
      let parts;
      if (type === 'CONGRESS' || type === 'INSIDER') parts = [];
      else if (directions.size === 2 && buy && sell && buy.size === sell.size && [...buy].every(value => sell.has(value))) {
        parts = [{ direction: 'BOTH', intervals: ordered(buy) }];
      } else {
        parts = [...directions].sort(([a], [b]) => rank(DIRECTION_ORDER, a) - rank(DIRECTION_ORDER, b))
          .map(([direction, set]) => ({ direction, intervals: ordered(set) }));
      }
      const spoken = [];
      for (const { direction, intervals } of parts) {
        const part = n('span', null, 'wl-follow-part');
        if (direction === 'BUY' || direction === 'SELL') part.append(n('span', i18n.t(`watchlists.dir.${direction}`), `wl-dir is-${direction.toLowerCase()}`));
        const letters = n('span', null, 'wl-intervals');
        intervals.forEach(interval => letters.append(n('span', i18n.t(`watchlists.intervalShort.${interval}`), 'wl-interval')));
        part.append(letters); chip.append(part);
        const names = intervals.map(interval => i18n.t(`interval.${interval}`)).join(', ');
        spoken.push(['BUY', 'SELL', 'BOTH'].includes(direction) ? `${i18n.t(`watchlists.dir.${direction}`)} · ${names}` : names);
      }
      const description = spoken.length ? i18n.t('watchlists.followChipAria', label, spoken.join('; ')) : label;
      chip.setAttribute('aria-label', description); chip.title = description;
      return chip;
    });
  }
  function modal(title, style = '', closeLabel = i18n.t('common.close')) {
    const dialog = n('dialog', null, `wl-dialog wl-modal ${style}`.trim());
    const header = n('header', null, 'wl-dialog-header'), heading = n('h2', title);
    heading.id = nextId('wl-dialog-title'); dialog.setAttribute('aria-labelledby', heading.id);
    header.append(heading, iconButton(closeLabel, () => dialog.close(), 'close', 'wl-dialog-close'));
    dialog.append(header);
    const focused = document.activeElement;
    dialog.addEventListener('close', () => { dialog.remove(); focused?.focus?.(); });
    document.body.append(dialog); dialog.showModal(); return dialog;
  }
  const dialogActions = (...buttons) => { const actions = n('div', null, 'wl-actions wl-dialog-actions'); actions.append(...buttons); return actions; };

  async function refresh() {
    try {
      lists = await api('/api/watchlists'); render();
      const shortcuts = document.querySelector('[data-watchlist-shortcuts]');
      if (shortcuts) {
        shortcuts.replaceChildren();
        lists.slice(0, 5).forEach(list => shortcuts.append(link(list.name, `/watchlists/${list.id}`)));
      }
    } catch (error) {
      if (!root) return;
      const failed = n('div', null, 'wl-empty');
      failed.append(n('p', error.message, 'wl-status'), button(i18n.t('common.retry'), refresh));
      root.replaceChildren(failed);
    }
  }
  function emptyState() {
    const empty = n('div', null, 'wl-empty'), badge = n('span', null, 'wl-empty-icon');
    badge.append(icon('list'));
    empty.append(badge, n('h3', i18n.t('watchlists.emptyTitle')), n('p', i18n.t('watchlists.emptyText'), 'wl-muted'),
      button(i18n.t('watchlists.create'), () => edit(), 'btn-accent', 'plus'));
    return empty;
  }
  function render() {
    if (!root) return;
    const openIds = new Set(Array.from(root.querySelectorAll('details[open]'), e => e.dataset.id));
    const search = document.querySelector('[data-watchlist-search]')?.value.trim().toLowerCase() || '';
    const toolbar = document.querySelector('[data-watchlist-toolbar]');
    if (toolbar) toolbar.hidden = !lists.length;
    activeMenu?.close(false);
    root.replaceChildren();
    if (!lists.length) { root.append(emptyState()); return; }
    if (root.dataset.selectedId) {
      const list = lists.find(item => String(item.id) === root.dataset.selectedId);
      if (list) renderList(list); else root.append(n('p', i18n.t('watchlists.noMatches'), 'wl-muted'));
      return;
    }
    const visible = lists.filter(list => `${list.name} ${list.description || ''}`.toLowerCase().includes(search));
    const count = document.querySelector('[data-watchlist-count]');
    if (count) count.textContent = i18n.plural('watchlists.count', visible.length, visible.length);
    if (!visible.length) { root.append(n('p', i18n.t('watchlists.noMatches'), 'wl-muted wl-no-matches')); return; }
    if (management) renderCards(visible); else renderAccordions(visible, openIds);
  }
  async function togglePin(list) {
    try { await api(`/api/watchlists/${list.id}`, { method: 'PUT', body: JSON.stringify({ ...list, pinned: !list.pinned }) }); await refresh(); }
    catch (error) { announce(error.message); }
  }
  function pinButton(list) {
    const b = iconButton(i18n.t(list.pinned ? 'watchlists.unpin' : 'watchlists.pin'), () => togglePin(list), 'star', `wl-pin-button${list.pinned ? ' is-pinned' : ''}`);
    return b;
  }
  /** Mark every notification of the list as read (notifications-read.js does the work and reloads). */
  function markReadControl(list) {
    const wrap = n('div', null, 'wl-mark-read');
    const markRead = button(i18n.t('watchlists.markAllRead'), () => {}, 'btn-secondary', 'read');
    markRead.dataset.notificationsReadAll = ''; markRead.dataset.readWatchlist = String(list.id);
    markRead.title = i18n.t('watchlists.markAllReadTitle');
    const readStatus = n('span', '', 'wl-status'); readStatus.dataset.notificationsReadStatus = ''; readStatus.setAttribute('role', 'status');
    wrap.append(markRead, readStatus);
    return wrap;
  }

  /** The Watchlists page: one card per list, each opening the list's own page. */
  function renderCards(visible) {
    const grid = n('div', null, 'wl-card-grid'); grid.setAttribute('role', 'list');
    for (const list of visible) {
      const card = n('article', null, `wl-card${list.pinned ? ' is-pinned' : ''}`); card.setAttribute('role', 'listitem');
      const head = n('div', null, 'wl-card-head'), title = n('h2', null, 'wl-card-title');
      title.append(link(list.name, `/watchlists/${list.id}`, 'wl-card-link'));
      const tools = n('div', null, 'wl-card-tools');
      tools.append(pinButton(list), menu(i18n.t('watchlists.rowActionsAria', list.name), [
        { label: i18n.t('watchlists.open'), icon: 'arrow', href: `/watchlists/${list.id}` },
        { label: i18n.t('watchlists.viewAllSignals'), icon: 'signals', href: signalsUrl(list) },
        { label: i18n.t('watchlists.addInstruments'), icon: 'plus', action: () => edit(list, { focus: 'instruments' }) },
        { label: i18n.t('watchlists.editList'), icon: 'edit', action: () => edit(list) },
        'separator',
        { label: i18n.t('watchlists.delete'), icon: 'remove', danger: true, action: () => confirmDelete(list) }
      ]));
      head.append(title, tools);
      const description = n('p', list.description || i18n.t('watchlists.noDescription'), `wl-card-description${list.description ? '' : ' is-empty'}`);
      const stats = n('dl', null, 'wl-card-stats');
      [['instruments', list.instruments], ['followed', list.monitored], ['unread', list.unread]].forEach(([key, value]) => {
        const stat = n('div', null, `wl-card-stat${key === 'unread' && value > 0 ? ' has-unread' : ''}`);
        stat.append(n('dt', i18n.t(`watchlists.stat.${key}`)), n('dd', String(value ?? 0)));
        stats.append(stat);
      });
      card.append(head, description, stats); grid.append(card);
    }
    const slot = n('div', null, 'wl-card-slot'); slot.setAttribute('role', 'listitem');
    slot.append(button(i18n.t('watchlists.create'), () => edit(), 'wl-card-new', 'plus'));
    grid.append(slot);
    root.append(grid);
  }

  /** Home: expandable rows that load a list's instruments when opened. */
  function renderAccordions(visible, openIds) {
    for (const list of visible) {
      const details = n('details', null, 'wl-accordion'); details.dataset.id = String(list.id);
      const summary = n('summary'), heading = n('div', null, 'wl-heading'), name = n('strong', null, 'wl-heading-name');
      if (list.pinned) { const pinned = n('span', null, 'wl-pinned-mark'); pinned.append(icon('star'), n('span', i18n.t('watchlists.pinned'), 'visually-hidden')); name.append(pinned); }
      name.append(document.createTextNode(list.name));
      heading.append(name, n('small', listSummary(list)));
      if (list.description) heading.append(n('small', list.description, 'wl-heading-description'));
      summary.append(heading);
      if (list.unread > 0) summary.append(n('span', i18n.plural('watchlists.unread', list.unread, list.unread), 'wl-count-pill has-unread'));
      details.append(summary);
      const body = n('div', null, 'wl-body'); details.append(body);
      let loaded = false;
      details.addEventListener('toggle', () => {
        if (!details.open || loaded) return;
        loaded = true;
        const actions = n('div', null, 'wl-list-actions');
        actions.append(link(i18n.t('watchlists.open'), `/watchlists/${list.id}`, 'btn-secondary', 'arrow'),
          link(i18n.t('watchlists.viewAllSignals'), signalsUrl(list), 'btn-secondary', 'signals'), markReadControl(list),
          button(i18n.t('watchlists.editList'), () => edit(list), 'btn-secondary', 'edit'),
          menu(i18n.t('watchlists.moreActions'), [
            { label: i18n.t(list.pinned ? 'watchlists.unpin' : 'watchlists.pin'), icon: 'star', action: () => togglePin(list) },
            { label: i18n.t('watchlists.addInstruments'), icon: 'plus', action: () => edit(list, { focus: 'instruments' }) },
            'separator',
            { label: i18n.t('watchlists.delete'), icon: 'remove', danger: true, action: () => confirmDelete(list) }
          ]));
        body.append(actions);
        memberTable(list, body);
        body.append(customAlerts(list, 'h3'));
      });
      details.open = openIds.has(String(list.id));
      root.append(details);
    }
  }

  /** A list's own page: its header actions, a summary strip, the instruments and the custom alerts that watch it. */
  function renderList(list) {
    const title = document.querySelector('.app-hero h1');
    if (title) title.textContent = list.name;
    const subtitle = document.querySelector('[data-watchlist-subtitle]');
    if (subtitle) {
      if (subtitle.dataset.fallback === undefined) subtitle.dataset.fallback = subtitle.textContent;
      subtitle.textContent = list.description || subtitle.dataset.fallback;
    }
    const actions = [
      button(i18n.t('watchlists.editList'), () => edit(list), 'btn-secondary', 'edit'),
      button(i18n.t('watchlists.addInstruments'), () => edit(list, { focus: 'instruments' }), 'btn-accent', 'plus'),
      menu(i18n.t('watchlists.moreActions'), [
        { label: i18n.t('watchlists.create'), icon: 'plus', action: () => edit() },
        { label: i18n.t('watchlists.recentImports'), icon: 'imports', action: recentImports },
        'separator',
        { label: i18n.t('watchlists.delete'), icon: 'remove', danger: true, action: () => confirmDelete(list) }
      ])
    ];
    const overview = n('div', null, 'wl-overview');
    const titleActions = document.querySelector('[data-watchlist-title-actions]');
    if (titleActions) titleActions.replaceChildren(pinButton(list)); else actions.unshift(pinButton(list));
    const header = document.querySelector('[data-watchlist-header-actions]');
    if (header) header.replaceChildren(...actions);
    else { const bar = n('div', null, 'wl-list-actions'); bar.append(...actions); overview.append(bar); }
    overview.append(statsStrip(list));
    const members = n('section', null, 'wl-panel wl-members');
    root.append(overview); overview.append(members);
    memberTable(list, members, { titled: true });
    overview.append(customAlerts(list, 'h2'));
  }
  function statsStrip(list) {
    const strip = n('div', null, 'wl-stats'); strip.setAttribute('role', 'group'); strip.setAttribute('aria-label', i18n.t('watchlists.statsAria'));
    const stat = (value, label, style = '') => {
      const box = n('div', null, `wl-stat ${style}`.trim()), figures = n('div', null, 'wl-stat-figures');
      figures.append(n('strong', value, 'wl-stat-value'), n('span', label, 'wl-stat-label'));
      box.append(figures); strip.append(box); return box;
    };
    stat(String(list.instruments ?? 0), i18n.t('watchlists.stat.instruments'));
    stat(String(list.monitored ?? 0), i18n.t('watchlists.stat.followed'));
    const unread = stat(String(list.unread ?? 0), i18n.t('watchlists.stat.unread'), `wl-stat-unread${list.unread > 0 ? ' has-unread' : ''}`);
    const unreadActions = n('div', null, 'wl-stat-actions');
    unreadActions.append(link(i18n.t('watchlists.viewAllSignals'), signalsUrl(list), 'btn-secondary', 'signals'), markReadControl(list));
    unread.append(unreadActions);
    const email = stat('…', i18n.t('watchlists.stat.email'), 'wl-stat-email');
    const emailValue = email.querySelector('.wl-stat-value');
    // The list's email switch lives in its settings; a failed load leaves the figure out rather than guessing.
    api(`/api/watchlists/${list.id}/settings`).then(view => {
      const on = Array.isArray(view?.selections) && view.selections.some(value => value.email);
      emailValue.textContent = i18n.t(on ? 'watchlists.stat.emailOn' : 'watchlists.stat.emailOff');
      email.classList.toggle('is-on', on);
    }).catch(() => { email.hidden = true; });
    return strip;
  }

  function memberTable(list, body, { titled = false } = {}) {
    if (!selections.has(list.id)) selections.set(list.id, new Set());
    const selected = selections.get(list.id);
    if (titled) {
      const head = n('div', null, 'wl-panel-head'), heading = n('h2', null, 'wl-panel-title');
      heading.append(n('span', i18n.t('watchlists.membersTitle')), n('span', String(list.instruments ?? 0), 'panel-count'));
      head.append(heading); body.append(head);
    }
    const toolbar = n('div', null, 'wl-toolbar');
    const searchBox = n('label', null, 'wl-search'), search = n('input');
    search.type = 'search'; search.placeholder = i18n.t('watchlists.searchPlaceholder'); search.setAttribute('aria-label', i18n.t('watchlists.searchAria', list.name));
    searchBox.append(icon('search'), n('span', i18n.t('watchlists.search'), 'visually-hidden'), search);
    const group = select(i18n.t('watchlists.instrumentType'), ['all', 'stocks', 'funds'].map(value => [value, i18n.t(`watchlists.group.${value}`)]));
    const sort = select(i18n.t('watchlists.sortBy'), ['symbol', 'gain', 'loss'].map(value => [value, i18n.t(`watchlists.sort.${value}`)]), { visible: true });
    toolbar.append(searchBox, group.label, sort.label);
    const status = n('p', '', 'wl-status wl-table-status'); status.setAttribute('role', 'status');
    const tableArea = n('div', null, 'wl-table-wrap');
    tableArea.tabIndex = 0; tableArea.setAttribute('role', 'region'); tableArea.setAttribute('aria-label', i18n.t('watchlists.tableAria', list.name));
    const pages = n('nav', null, 'wl-pagination'); pages.setAttribute('aria-label', i18n.t('watchlists.paginationAria'));
    // Selection actions appear only while something is selected; the selection survives paging and filters.
    const bulk = n('div', null, 'wl-bulk-actions'); bulk.setAttribute('role', 'group'); bulk.setAttribute('aria-label', i18n.t('watchlists.bulkAria'));
    const selectedCount = n('span', '', 'wl-bulk-count'); selectedCount.setAttribute('role', 'status');
    const viewSelected = link(i18n.t('watchlists.viewSignals'), '#', 'btn-secondary', 'signals');
    viewSelected.addEventListener('click', event => { if (!selected.size) event.preventDefault(); });
    const manageSelected = button(i18n.t('watchlists.manageFollows'), () => manageFollows(list, [...selected]), 'btn-secondary', 'manage');
    const removeSelected = button(i18n.t('watchlists.remove'), () => removeMembers(list, [...selected]), 'btn-secondary wl-danger-outline', 'remove');
    const clear = button(i18n.t('watchlists.clearSelection'), () => { selected.clear(); updateSelection(); }, 'wl-text-button');
    const bulkButtons = n('div', null, 'wl-bulk-buttons'); bulkButtons.append(viewSelected, manageSelected, removeSelected, clear);
    bulk.append(selectedCount, bulkButtons);
    const selectPage = n('input'); selectPage.type = 'checkbox'; selectPage.setAttribute('aria-label', i18n.t('watchlists.selectPageAria'));
    selectPage.title = i18n.t('watchlists.selectPage');
    let visibleSymbols = [];
    selectPage.addEventListener('change', () => {
      visibleSymbols.forEach(symbol => selectPage.checked ? selected.add(symbol) : selected.delete(symbol)); updateSelection();
    });
    function updateSelection() {
      bulk.hidden = !selected.size;
      selectedCount.textContent = i18n.plural('watchlists.selected', selected.size, selected.size);
      viewSelected.href = signalsUrl(list, { ticker: [...selected].join(',') });
      viewSelected.setAttribute('aria-disabled', String(!selected.size)); viewSelected.tabIndex = selected.size ? 0 : -1;
      manageSelected.disabled = removeSelected.disabled = clear.disabled = !selected.size;
      const checked = visibleSymbols.filter(symbol => selected.has(symbol)).length;
      selectPage.checked = visibleSymbols.length > 0 && checked === visibleSymbols.length;
      selectPage.indeterminate = checked > 0 && checked < visibleSymbols.length;
      selectPage.disabled = !visibleSymbols.length;
      tableArea.querySelectorAll('[data-select-symbol]').forEach(input => {
        input.checked = selected.has(input.dataset.selectSymbol);
        input.closest('tr').classList.toggle('wl-selected', input.checked);
      });
    }
    body.append(toolbar, bulk, status, tableArea, pages); updateSelection();
    let page = 0, sequence = 0, timer;
    async function load() {
      const request = ++sequence; status.textContent = i18n.t('watchlists.loadingInstruments'); tableArea.setAttribute('aria-busy', 'true');
      try {
        const data = await api(`/api/watchlists/${list.id}/members?${new URLSearchParams({ page, group: group.input.value, q: search.value, sort: sort.input.value })}`);
        if (request !== sequence || !body.isConnected) return;
        tableArea.replaceChildren(); pages.replaceChildren(); status.textContent = ''; tableArea.removeAttribute('aria-busy');
        visibleSymbols = data.items.map(item => item.symbol);
        if (!data.items.length) {
          const filtered = search.value.trim() || group.input.value !== 'all';
          const empty = n('div', null, 'wl-table-empty');
          if (filtered) empty.append(n('p', i18n.t('watchlists.noInstruments'), 'wl-muted'));
          else empty.append(n('strong', i18n.t('watchlists.emptyListTitle')), n('p', i18n.t('watchlists.emptyListText'), 'wl-muted'),
            button(i18n.t('watchlists.addInstruments'), () => edit(list, { focus: 'instruments' }), 'btn-accent', 'plus'));
          tableArea.append(empty);
        } else tableArea.append(table(data.items));
        updateSelection();
        memberCustomAlerts(list, data.items, tableArea, request);
        if (data.total) {
          const first = page * data.pageSize + 1, last = Math.min(data.total, (page + 1) * data.pageSize);
          pages.append(n('span', i18n.t('watchlists.range', first, last, data.total), 'wl-range'));
        }
        if (data.total > data.pageSize) {
          const previous = button(i18n.t('watchlists.previous'), () => { page--; load(); }, 'btn-secondary', 'previous');
          const next = button(i18n.t('watchlists.next'), () => { page++; load(); }, 'btn-secondary wl-next', 'next');
          previous.disabled = page === 0; next.disabled = (page + 1) * data.pageSize >= data.total;
          const nav = n('div', null, 'wl-page-buttons'); nav.append(previous, next); pages.append(nav);
        }
      } catch (error) {
        if (request !== sequence) return;
        tableArea.removeAttribute('aria-busy'); status.textContent = error.message;
        pages.replaceChildren(button(i18n.t('common.retry'), load));
      }
    }
    function table(items) {
      const table = n('table', null, 'wl-table'), head = n('thead'), row = n('tr'), tbody = n('tbody');
      const selectHeader = n('th', null, 'wl-select-cell'), pageTarget = n('label', null, 'wl-check-target');
      pageTarget.append(selectPage); selectHeader.scope = 'col'; selectHeader.append(pageTarget); row.append(selectHeader);
      for (const column of ['instrument', 'price', 'outlook', 'following', 'unread']) {
        const th = n('th', i18n.t(`watchlists.column.${column}`), `wl-col-${column}`); th.scope = 'col'; row.append(th);
      }
      const actionsHeader = n('th', null, 'wl-col-actions'); actionsHeader.scope = 'col';
      actionsHeader.append(n('span', i18n.t('watchlists.column.actions'), 'visually-hidden')); row.append(actionsHeader);
      head.append(row); table.append(head, tbody);
      for (const item of items) tbody.append(memberRow(item));
      return table;
    }
    function memberRow(item) {
      const tr = n('tr'), symbolSignals = `/signals?${new URLSearchParams({ watchlistId: list.id, ticker: item.symbol })}`;
      const selectCell = n('td', null, 'wl-select-cell'), check = n('input'); check.type = 'checkbox'; check.dataset.selectSymbol = item.symbol;
      check.setAttribute('aria-label', i18n.t('watchlists.selectSymbolAria', item.symbol));
      check.addEventListener('change', () => { check.checked ? selected.add(item.symbol) : selected.delete(item.symbol); updateSelection(); });
      const target = n('label', null, 'wl-check-target'); target.append(check); selectCell.append(target);
      const instrument = n('td', null, 'wl-instrument-cell');
      instrument.append(link(item.symbol, symbolSignals, 'wl-ticker'));
      if (item.companyName) instrument.append(n('span', item.companyName, 'wl-company'));
      const meta = [item.exchange, item.currency].filter(Boolean).join(' · ');
      if (meta) instrument.append(n('span', meta, 'wl-instrument-meta'));
      const following = n('td', null, 'wl-following-cell'); following.dataset.label = i18n.t('watchlists.column.following');
      following.dataset.followingSymbol = item.symbol;
      const chips = n('div', null, 'wl-follows'); chips.append(...followChips(item.signals));
      if (!chips.children.length) {
        const none = n('div', null, 'wl-no-follows');
        none.append(n('span', i18n.t('watchlists.noFollows')), button(i18n.t('watchlists.setUpFollows'), () => manageFollows(list, [item.symbol]), 'wl-text-button'));
        chips.append(none);
      }
      following.append(chips);
      const unread = n('td', null, 'wl-unread-cell'), count = item.unreadSignalCount || 0;
      unread.dataset.label = i18n.t('watchlists.column.unread');
      const badge = count > 0 ? link('', signalsUrl(list, { ticker: item.symbol, state: 'unread' }), 'wl-unread-badge has-unread') : n('span', null, 'wl-unread-badge');
      badge.append(n('strong', String(count)), n('span', i18n.plural('watchlists.unreadBadge', count), 'wl-unread-word'));
      badge.setAttribute('aria-label', i18n.plural('watchlists.unreadAria', count, count, item.symbol));
      badge.title = badge.getAttribute('aria-label');
      unread.append(badge);
      const action = n('td', null, 'wl-actions-cell');
      action.append(menu(i18n.t('watchlists.rowActionsAria', item.symbol), [
        { label: i18n.t('watchlists.viewSignals'), icon: 'signals', href: symbolSignals },
        { label: i18n.t('watchlists.openChart'), icon: 'chart', href: `/stock/${encodeURIComponent(item.symbol)}` },
        { label: i18n.t('watchlists.manageFollows'), icon: 'manage', action: () => manageFollows(list, [item.symbol]) },
        'separator',
        { label: i18n.t('watchlists.removeFromList'), icon: 'remove', danger: true, action: () => removeMember(list, item.symbol) }
      ]));
      tr.append(selectCell, instrument, priceCell(item), outlookCell(item), following, unread, action);
      return tr;
    }
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { page = 0; load(); }, 250); });
    group.input.addEventListener('change', () => { page = 0; load(); });
    sort.input.addEventListener('change', () => { page = 0; load(); }); load();

    /** Custom alerts covering the shown stocks, as chips beside their follows (one request per page). */
    async function memberCustomAlerts(current, items, area, request) {
      if (!items.length) return;
      try {
        const bySymbol = await api(`/api/custom-alerts/for-watchlist/${current.id}/members?${new URLSearchParams({ symbols: items.map(item => item.symbol).join(',') })}`);
        if (request !== sequence) return;
        area.querySelectorAll('[data-following-symbol]').forEach(cell => {
          const found = bySymbol[cell.dataset.followingSymbol] || [];
          if (!found.length) return;
          cell.querySelector('.wl-no-follows')?.remove();
          const group = n('div', null, 'wl-follow-group wl-custom-follow'); group.setAttribute('role', 'group');
          group.setAttribute('aria-label', i18n.t('watchlists.custom.chipsAria', cell.dataset.followingSymbol));
          const type = n('span', i18n.t('followType.CUSTOM'), 'wl-follow-name learn-term');
          type.dataset.learn = LEARN_OPTIONS.customChip; type.dataset.learnTitle = i18n.t('followType.CUSTOM');
          group.append(type);
          found.forEach(alert => {
            const chip = link(alert.name, `/custom-alerts/${alert.id}?${new URLSearchParams({ ticker: cell.dataset.followingSymbol })}`, `wl-custom-chip${alert.active ? '' : ' is-paused'}`);
            if (!alert.active) chip.title = i18n.t('watchlists.custom.paused');
            group.append(chip);
          });
          cell.querySelector('.wl-follows').append(group);
        });
      } catch (error) { /* The follows above still show; custom chips are an extra. */ }
    }
  }

  /** The custom alerts that watch this list: open one, stop it here, or apply another alert to the whole list. */
  function customAlerts(list, headingLevel = 'h3') {
    const box = n('section', null, `wl-custom-alerts${headingLevel === 'h2' ? ' wl-panel' : ''}`);
    const headingId = `wl-custom-${list.id}`;
    box.setAttribute('aria-labelledby', headingId);
    const head = n('div', null, 'wl-custom-head');
    const heading = n(headingLevel, null, headingLevel === 'h2' ? 'wl-panel-title' : 'wl-subheading'); heading.id = headingId;
    const title = n('span', i18n.t('watchlists.custom.title'), 'learn-term');
    title.dataset.learn = LEARN_OPTIONS.customStrip; title.dataset.learnTitle = i18n.t('watchlists.custom.title');
    heading.append(title);
    const create = link(i18n.t('watchlists.custom.create'), `/custom-alerts/new?watchlistId=${list.id}`, 'btn-secondary wl-small-button');
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
      const name = link(alert.name, `/custom-alerts/${alert.id}`);
      const facts = [alert.intervals.map(interval => i18n.t(`interval.${interval}`)).join(', '),
        alert.scope === 'SELECTED' ? i18n.plural('watchlists.custom.chosen', alert.stocks, alert.stocks) : i18n.plural('watchlists.custom.all', alert.stocks, alert.stocks),
        i18n.plural('watchlists.custom.matching', alert.matching, alert.matching)];
      if (alert.halfway) facts.push(i18n.plural('watchlists.custom.halfway', alert.halfway, alert.halfway));
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
          }, 'wl-text-button');
          status.append(undo);
        } catch (error) { status.textContent = error.message; remove.disabled = false; }
      }, 'btn-secondary wl-small-button wl-custom-remove');
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
      const choice = select(i18n.t('watchlists.custom.applyLabel'), [['', i18n.t('watchlists.custom.applyChoose')], ...others.map(alert => [String(alert.id), alert.name])], { visible: true });
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
  function symbolChips(symbols) {
    const chips = n('div', null, 'wl-selected-symbols');
    symbols.forEach(symbol => chips.append(n('span', symbol, 'wl-symbol-chip')));
    return chips;
  }
  async function manageFollows(list, symbols) {
    const d = modal(i18n.t('watchlists.manageFollowsTitle', i18n.plural('watchlists.selected', symbols.length, symbols.length)), 'wl-follows-dialog');
    d.append(symbolChips(symbols));
    const status = n('p', i18n.t('watchlists.loadingFollows'), 'wl-status'); status.setAttribute('role', 'status');
    const settings = window.StockWatchListSettings.create(() => { save.disabled = !settings.readChanges().length; }, { members: true });
    settings.root.disabled = true;
    const save = button(i18n.t('watchlists.applyToSelected'), async () => {
      save.disabled = true; settings.root.disabled = true;
      try {
        await api(`/api/watchlists/${list.id}/members/follows`, { method: 'POST', body: JSON.stringify({ symbols, changes: settings.readChanges() }) });
        d.close(); await refresh();
      } catch (error) { status.textContent = error.message; save.disabled = false; settings.root.disabled = false; }
    }, 'btn-accent'); save.disabled = true;
    const body = n('div', null, 'wl-dialog-body'); body.append(settings.root);
    d.append(body, status, dialogActions(button(i18n.t('common.cancel'), () => d.close()), save));
    try {
      const view = await api(`/api/watchlists/${list.id}/members/follows/preview`, { method: 'POST', body: JSON.stringify({ symbols }) });
      if (!d.isConnected) return;
      settings.set(view); settings.root.disabled = false;
      status.textContent = view.nonStocks ? i18n.plural('watchlists.nonStocksSkipped', view.nonStocks, view.nonStocks) : '';
    } catch (error) { status.textContent = error.message; }
  }
  function confirmation(title, text, confirmText, perform, details = null) {
    const d = modal(title, 'wl-confirm');
    if (details) d.append(details);
    d.append(n('p', text, 'wl-muted'));
    const status = n('p', '', 'wl-status'); status.setAttribute('role', 'status');
    const cancel = button(i18n.t('common.cancel'), () => d.close());
    const confirm = button(confirmText, async () => {
      confirm.disabled = true;
      try { await perform(d); } catch (error) { status.textContent = error.message; confirm.disabled = false; }
    }, 'wl-danger-button');
    d.append(status, dialogActions(cancel, confirm)); cancel.focus();
    return d;
  }
  function removeMember(list, symbol) {
    confirmation(i18n.t('watchlists.removeSymbolTitle', symbol), i18n.t('watchlists.removeSymbolText'), i18n.t('watchlists.removeFromList'), async d => {
      await api(`/api/watchlists/${list.id}/members/${encodeURIComponent(symbol)}`, { method: 'DELETE' });
      selections.get(list.id)?.delete(symbol); d.close(); await refresh();
    });
  }
  function removeMembers(list, symbols) {
    confirmation(i18n.plural('watchlists.removeSelectedTitle', symbols.length, symbols.length), i18n.t('watchlists.removeSelectedText'),
      i18n.t('watchlists.removeSelected'), async d => {
        await api(`/api/watchlists/${list.id}/members/remove`, { method: 'POST', body: JSON.stringify({ symbols }) });
        symbols.forEach(symbol => selections.get(list.id)?.delete(symbol)); d.close(); await refresh();
      }, symbolChips(symbols));
  }
  function confirmDelete(list) {
    confirmation(i18n.t('watchlists.deleteTitle', list.name), i18n.t('watchlists.deleteText'), i18n.t('watchlists.delete'), async d => {
      await api(`/api/watchlists/${list.id}`, { method: 'DELETE' }); d.close();
      if (root?.dataset.selectedId === String(list.id)) { window.location.assign('/watchlists'); return; }
      await refresh();
    });
  }
  /** The editor: name and description, instruments to add (search, paste or indexes) and the list's alert settings. */
  async function edit(list, { focus = 'name' } = {}) {
    const d = modal(list ? i18n.t('watchlists.editTitle', list.name) : i18n.t('watchlists.newTitle'), 'wl-editor', i18n.t('watchlists.closeEditor'));
    const form = n('form', null, 'wl-editor-form'); d.append(form);
    const name = field(i18n.t('watchlists.name'), 'text', list?.name || ''), description = field(i18n.t('watchlists.description'), 'text', list?.description || '');
    name.input.required = true; name.input.maxLength = 100; description.input.maxLength = 500;
    name.input.placeholder = i18n.t('watchlists.namePlaceholder'); description.input.placeholder = i18n.t('watchlists.descriptionPlaceholder');
    const identity = n('div', null, 'wl-editor-identity'); identity.append(name.label, description.label);
    const columns = n('div', null, 'wl-editor-columns'), instruments = n('section', null, 'wl-editor-instruments');
    const instrumentsHeading = n('h3', i18n.t('watchlists.chooseInstruments'), 'wl-editor-heading'); instrumentsHeading.id = nextId('wl-instruments');
    instruments.setAttribute('aria-labelledby', instrumentsHeading.id); instruments.append(instrumentsHeading);
    // Three ways to add instruments, one visible at a time.
    const tabList = n('div', null, 'wl-tabs'); tabList.setAttribute('role', 'tablist'); tabList.setAttribute('aria-labelledby', instrumentsHeading.id);
    const tabs = new Map(), panels = new Map();
    for (const key of ['search', 'paste', 'indexes']) {
      const tab = n('button', i18n.t(`watchlists.tab.${key}`), 'wl-tab'); tab.type = 'button'; tab.id = nextId(`wl-tab-${key}`);
      const panel = n('div', null, `wl-tab-panel wl-tab-${key}`); panel.id = nextId(`wl-panel-${key}`);
      tab.setAttribute('role', 'tab'); tab.setAttribute('aria-controls', panel.id);
      panel.setAttribute('role', 'tabpanel'); panel.setAttribute('aria-labelledby', tab.id);
      tab.addEventListener('click', () => selectTab(key));
      tabs.set(key, tab); panels.set(key, panel); tabList.append(tab);
    }
    function selectTab(key, focusTab = false) {
      tabs.forEach((tab, value) => {
        const active = value === key;
        tab.setAttribute('aria-selected', String(active)); tab.tabIndex = active ? 0 : -1; panels.get(value).hidden = !active;
      });
      if (focusTab) tabs.get(key).focus();
    }
    tabList.addEventListener('keydown', event => {
      const keys = [...tabs.keys()], index = keys.findIndex(key => tabs.get(key) === document.activeElement);
      if (index < 0) return;
      const target = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: keys.length - 1 }[event.key];
      if (target === undefined) return;
      event.preventDefault(); selectTab(keys[(target + keys.length) % keys.length], true);
    });
    instruments.append(tabList, ...panels.values());
    const search = field(i18n.t('watchlists.searchTickers'), 'search'), results = n('div', null, 'wl-results'), resultsNote = n('p', '', 'wl-muted wl-results-note');
    search.input.placeholder = i18n.t('watchlists.searchTickersPlaceholder'); panels.get('search').append(search.label, results, resultsNote);
    // The search is a combobox like the global one: arrows move through the results and Enter picks one.
    results.id = nextId('wl-results'); results.setAttribute('role', 'listbox'); results.setAttribute('aria-label', i18n.t('watchlists.searchResults'));
    resultsNote.setAttribute('role', 'status');
    Object.entries({ role: 'combobox', 'aria-autocomplete': 'list', 'aria-controls': results.id, 'aria-expanded': 'false' })
      .forEach(([attribute, value]) => search.input.setAttribute(attribute, value));
    // A pasted list (a broker export column, a spreadsheet range) adds many tickers at once.
    const paste = panels.get('paste'); paste.classList.add('wl-paste');
    const pasteField = field(i18n.t('watchlists.pasteLabel'), 'textarea'), pasteNote = n('p', '', 'wl-muted');
    pasteField.input.rows = 5; pasteField.input.placeholder = i18n.t('watchlists.pastePlaceholder'); pasteNote.setAttribute('role', 'status');
    const pasteButton = button(i18n.t('watchlists.pasteAdd'), () => {
      const { valid, invalid } = parseTickers(pasteField.input.value);
      const added = valid.filter(symbol => !selected.has(symbol) && !pasted.has(symbol));
      added.forEach(symbol => pasted.add(symbol));
      pasteNote.textContent = [i18n.plural('watchlists.pasteAdded', added.length, added.length),
        invalid.length ? i18n.t('watchlists.pasteInvalid', invalid.slice(0, 10).join(', ') + (invalid.length > 10 ? ' …' : '')) : ''].filter(Boolean).join(' ');
      if (added.length) { pasteField.input.value = ''; changed(); }
    }, 'btn-secondary', 'plus');
    paste.append(n('p', i18n.t('watchlists.pasteHelp'), 'wl-muted'), pasteField.label, pasteButton, pasteNote);
    const market = panels.get('indexes'); market.classList.add('wl-market-browser');
    market.append(n('p', i18n.t('watchlists.browseIndexesHelp'), 'wl-muted'));
    const regionSection = n('div', null, 'wl-geo-step');
    const regions = n('div', null, 'wl-region-options'); regions.setAttribute('role', 'group'); regions.setAttribute('aria-label', i18n.t('watchlists.continents'));
    regionSection.append(n('h4', i18n.t('watchlists.continents')), regions);
    const countries = n('div', null, 'wl-country-options'); countries.setAttribute('role', 'group'); countries.setAttribute('aria-label', i18n.t('watchlists.countries'));
    const countrySection = n('div', null, 'wl-geo-step wl-country-section'); countrySection.hidden = true;
    countrySection.append(n('h4', i18n.t('watchlists.country')), countries);
    const indexSection = n('div', null, 'wl-geo-step wl-index-section'); indexSection.hidden = true;
    const indexTitle = n('h4', i18n.t('watchlists.indexes')), indexOptions = n('div', null, 'wl-index-options'); indexSection.append(indexTitle, indexOptions);
    const catalogStatus = n('p', i18n.t('watchlists.loadingContinents'), 'wl-muted'); catalogStatus.setAttribute('role', 'status');
    market.append(regionSection, countrySection, indexSection, catalogStatus);
    const selections = n('div', null, 'wl-selection-list'), selectionSection = n('section', null, 'wl-selection-summary');
    const selectionHeading = n('h3', null, 'wl-editor-heading'), selectionCount = n('span', '0', 'panel-count');
    selectionHeading.append(n('span', i18n.t('watchlists.selectedAdditions')), selectionCount);
    selectionSection.setAttribute('aria-label', i18n.t('watchlists.selectedAdditions'));
    selectionSection.append(selectionHeading, selections); instruments.append(selectionSection);
    const monitoring = window.StockWatchListSettings.create(changed);
    monitoring.root.disabled = !!list;
    const settingsPane = n('section', null, 'wl-editor-settings'); settingsPane.setAttribute('aria-label', i18n.t('watchlists.settingsAria'));
    settingsPane.append(monitoring.root); columns.append(instruments, settingsPane);
    const status = n('p', '', 'wl-status'); status.setAttribute('role', 'status');
    const previewArea = n('div', null, 'wl-editor-preview'), count = n('p', '', 'wl-muted wl-editor-count'); count.setAttribute('role', 'status');
    const save = n('button', i18n.t(list ? 'watchlists.save' : 'watchlists.create'), 'btn-accent'); save.type = 'submit';
    const actions = n('div', null, 'wl-actions'); actions.append(button(i18n.t('common.cancel'), () => d.close()), save);
    const footer = n('footer', null, 'wl-editor-footer'), footerRow = n('div', null, 'wl-editor-footer-row'); footerRow.append(count, actions);
    footer.append(status, previewArea, footerRow); form.append(identity, columns, footer);
    const selected = new Map(), indexChoices = new Map(), indexInputs = new Map(), pasted = new Set();
    let catalog = [], activeRegion = '', activeCountry = '', previewed = false, busy = false, settingsLoaded = !list;
    const modes = Object.fromEntries(['index', 'stocks', 'both'].map(mode => [mode, i18n.t(`watchlists.mode.${mode}`)]));
    selectTab('search');
    (focus === 'instruments' ? search.input : name.input).focus();
    function additions() {
      const symbols = new Set([...selected.keys(), ...pasted]), indexIds = [];
      indexChoices.forEach(({ entry, mode }) => {
        if (mode !== 'stocks') symbols.add(entry.symbol);
        if (mode !== 'index') indexIds.push(entry.id);
      });
      return { symbols: [...symbols], indexIds };
    }
    function changed() {
      previewed = false; previewArea.replaceChildren(); selections.replaceChildren();
      const itemRow = (title, subtitle, edit, remove) => {
        const row = n('div', null, 'wl-selection-row'), text = n('div', null, 'wl-selection-text'); text.append(n('strong', title), n('small', subtitle));
        const actions = n('div', null, 'wl-selection-actions');
        if (edit) { const b = button(i18n.t('watchlists.change'), edit, 'wl-text-button'); b.setAttribute('aria-label', i18n.t('watchlists.changeAria', title)); actions.append(b); }
        actions.append(iconButton(i18n.t('watchlists.removeAria', title), remove, 'close', 'wl-selection-remove')); row.append(text, actions); selections.append(row);
      };
      selected.forEach((item, symbol) => itemRow(symbol, item.name || i18n.t('watchlists.individualInstrument'), null, () => { selected.delete(symbol); changed(); }));
      if (pasted.size) itemRow(i18n.plural('watchlists.pastedTickers', pasted.size, pasted.size),
        [...pasted].slice(0, 12).join(', ') + (pasted.size > 12 ? ' …' : ''), null, () => { pasted.clear(); changed(); });
      indexChoices.forEach(({ entry, mode }) => itemRow(entry.name, `${modes[mode]} · ${mode === 'index' ? entry.symbol
        : mode === 'both' ? i18n.t('watchlists.stocksAndIndex', stockCount(entry.count)) : stockCount(entry.count)}`,
        () => chooseIndex(entry), () => { indexChoices.delete(entry.id); changed(); }));
      if (!selected.size && !pasted.size && !indexChoices.size) selections.append(n('p', i18n.t('watchlists.selectionsEmpty'), 'wl-muted wl-selection-empty'));
      indexInputs.forEach((input, id) => { input.checked = indexChoices.has(id); });
      const tickers = selected.size + pasted.size;
      selectionCount.textContent = String(tickers + indexChoices.size);
      count.textContent = `${i18n.plural('watchlists.individualTickers', tickers, tickers)} · ${i18n.plural('watchlists.indexSelections', indexChoices.size, indexChoices.size)}`;
      save.textContent = tickers || indexChoices.size || monitoring.restrictionsChanged() || list?.instruments > 0 && monitoring.monitoringChanged() ? i18n.t('watchlists.reviewChanges') : i18n.t(list ? 'watchlists.save' : 'watchlists.create');
    }
    function chooseIndex(entry) {
      const choice = modal(i18n.t('watchlists.addIndexTitle', entry.name), 'wl-index-dialog');
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
      const note = n('p', null, 'wl-muted wl-index-source'); note.append(n('span', `${i18n.t('watchlists.constituentsAsOf', entry.asOf)} `));
      const source = link(i18n.t('watchlists.viewSource'), entry.source); source.target = '_blank'; source.rel = 'noopener noreferrer'; note.append(source);
      choice.append(options, note, dialogActions(button(i18n.t('common.cancel'), () => choice.close()),
        button(i18n.t('watchlists.confirmSelection'), () => { indexChoices.set(entry.id, { entry, mode }); changed(); choice.close(); }, 'btn-accent')));
      options.querySelector('input:checked')?.focus();
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
      const option = button('', () => {
        selected.set(item.symbol, item); search.input.value = ''; showResults([]); changed(); search.input.focus();
      }, 'wl-result');
      option.append(n('strong', item.symbol), n('span', item.name || '', 'wl-result-name'), n('small', item.region || item.country || ''));
      option.setAttribute('aria-label', `${item.symbol} · ${item.name} · ${item.region || item.country || ''}`);
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
        if (monitoring.restrictionsChanged() && !previewed && !selected.size && !pasted.size && !indexChoices.size && !applyExisting) {
          previewArea.replaceChildren(n('p', monitoring.restrictionSummary(), 'wl-status'));
          previewed = true; save.textContent = i18n.t('watchlists.saveChanges'); return;
        }
        if ((selected.size || pasted.size || indexChoices.size || applyExisting) && !previewed) {
          const preview = await api(`/api/watchlists/imports/preview${list ? `?watchlistId=${list.id}` : ''}`, { method: 'POST', body: JSON.stringify(payload) });
          previewArea.replaceChildren(n('p', [i18n.plural('watchlists.uniqueInstruments', preview.uniqueCount, preview.uniqueCount),
            i18n.plural('watchlists.duplicatesRemoved', preview.duplicates, preview.duplicates),
            i18n.plural('watchlists.alreadyPresent', preview.alreadyPresent, preview.alreadyPresent)].join(' · '), 'wl-preview-lead'));
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
  function progressBar(job, label) {
    // Spans, so the bar can sit inside a button.
    const bar = n('span', null, `wl-progress${job.pending ? '' : ' is-done'}${job.failed ? ' has-failures' : ''}`), fill = n('span');
    const done = job.total ? Math.round((job.completed + job.failed) / job.total * 100) : 100;
    fill.style.width = `${done}%`; bar.append(fill);
    if (label) { bar.setAttribute('role', 'progressbar'); bar.setAttribute('aria-label', label); bar.setAttribute('aria-valuemin', '0'); bar.setAttribute('aria-valuemax', '100'); bar.setAttribute('aria-valuenow', String(done)); }
    else bar.setAttribute('aria-hidden', 'true');
    return bar;
  }
  async function showImport(id) {
    const d = modal(i18n.t('watchlists.importTitle'), 'wl-import-dialog'), meter = n('div', null, 'wl-import-meter');
    const status = n('p', '', 'wl-status'), errors = n('div', null, 'wl-import-errors'); status.setAttribute('role', 'status');
    const close = button(i18n.t('watchlists.closeInBackground'), () => d.close());
    const actions = dialogActions(close); d.append(meter, status, errors, actions);
    async function poll() {
      try {
        const job = await api(`/api/watchlists/imports/${id}`);
        meter.replaceChildren(progressBar(job, i18n.t('watchlists.importTitle')));
        status.textContent = i18n.t('watchlists.importProgress', job.completed, job.total, job.failed, job.pending);
        close.textContent = i18n.t(job.pending ? 'watchlists.closeInBackground' : 'watchlists.importDone');
        close.className = job.pending ? 'btn-secondary' : 'btn-accent';
        errors.replaceChildren(); job.errors.forEach(error => errors.append(n('p', `${error.symbol}: ${error.error}`, 'wl-muted')));
        if (job.pending) setTimeout(poll, d.isConnected ? 2000 : 10000);
        else {
          await refresh();
          if (job.failed) actions.prepend(button(i18n.t('watchlists.retryFailed'), async event => {
            const retry = event.currentTarget; retry.disabled = true;
            try { await api(`/api/watchlists/imports/${id}/retry`, { method: 'POST' }); retry.remove(); poll(); }
            catch (error) { status.textContent = error.message; retry.disabled = false; }
          }));
        }
      } catch (error) { status.textContent = error.message; actions.prepend(button(i18n.t('watchlists.retryStatus'), poll)); }
    }
    poll();
  }
  async function recentImports() {
    const d = modal(i18n.t('watchlists.recentImports'), 'wl-imports-dialog'), content = n('div', null, 'wl-import-list');
    d.append(content, dialogActions(button(i18n.t('common.close'), () => d.close())));
    content.append(n('p', i18n.t('watchlists.loadingImports'), 'wl-status'));
    try {
      const jobs = await api('/api/watchlists/imports'); content.replaceChildren();
      if (!jobs.length) content.append(n('p', i18n.t('watchlists.noImports'), 'wl-muted'));
      jobs.forEach(job => {
        const row = button(i18n.t('watchlists.importSummary', job.id, job.completed, job.total, job.pending, job.failed),
          // Removed at once (the close event comes later), so only the progress dialog shows this import.
          () => { d.close(); d.remove(); showImport(job.id); }, 'wl-import-row');
        row.append(progressBar(job)); content.append(row);
      });
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
