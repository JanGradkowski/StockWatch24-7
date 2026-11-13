// The signal archive pages (all signals, a watchlist's or a stock's): the figures from the whole filtered archive,
// times in words, rows that open their signal, choosing rows to delete, the guide, and keyboard shortcuts. Filters,
// sorting and pages are links, so the server keeps paging large archives.
(() => {
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  const figures = window.StockWatchListFigures;
  const page = document.querySelector('.sa-page');
  if (!page) return;


  // ------------------------------------------------------------------ the figures

  let data = {};
  try { data = JSON.parse(page.dataset.figures || '{}'); } catch { /* the figures stay empty */ }
  const dayStart = iso => { const [year, month, day] = iso.split('-').map(Number); return new Date(year, month - 1, day).getTime(); };
  const returnTo = encodeURIComponent(window.location.pathname + window.location.search);
  const activity = page.dataset.kind === 'activity';
  figures.history(document.querySelector('[data-sa-history]'), (data.days || []).map(([day, count]) => ({ time: dayStart(day), count })), {
    // Ticker alerts' strip runs the full width, so its bars are slim.
    slim: activity,
    keys: activity ? { caption: 'sa.activityCaption', aria: 'sa.activityAria', day: 'sa.activityDay' }
      : { caption: 'sa.historyCaption', aria: 'sa.historyAria', day: 'sa.historyDay' } });
  // The dots only show the spread; the link under them lists the finished signals, best first.
  figures.spread(document.querySelector('[data-sa-spread]'), (data.outcomes || []).map(([, , symbol, time, value]) => ({ value, symbol, time })));

  // Phones fold the figures into one line, so the list starts on the first screen; the line opens them (remembered).
  const band = document.querySelector('.sa-figures');
  const bandToggle = band?.querySelector('[data-sa-figures-toggle]');
  // Without a results headline (ticker alerts, or too few finished signals) the folded line says how many came in the
  // last 7 days, or when the latest one came.
  const toggleText = bandToggle?.querySelector('.sa-figures-toggle-text');
  if (toggleText && (activity || toggleText.hasAttribute('data-sa-recent'))) {
    const days = (data.days || []).filter(([, count]) => count > 0).map(([day, count]) => [dayStart(day), count]);
    const since = new Date().setHours(0, 0, 0, 0) - 6 * 86400000;
    const recent = days.reduce((sum, [time, count]) => sum + (time >= since ? count : 0), 0);
    const kind = activity ? 'activity' : 'signals';
    if (recent) toggleText.textContent = i18n.plural(`sa.${kind}Week`, recent, recent);
    else if (days.length) toggleText.textContent = i18n.t(`sa.${kind}Latest`, text.date(Math.max(...days.map(([time]) => time)), true));
  }
  if (band && bandToggle) {
    const setOpen = open => {
      band.classList.toggle('is-open', open);
      bandToggle.setAttribute('aria-expanded', String(open));
    };
    let remembered = false;
    try { remembered = window.localStorage.getItem('sa.figuresOpen') === '1'; } catch { /* stays folded */ }
    band.classList.add('is-collapsible');
    setOpen(remembered);
    bandToggle.addEventListener('click', () => {
      const open = !band.classList.contains('is-open');
      setOpen(open);
      try { window.localStorage.setItem('sa.figuresOpen', open ? '1' : '0'); } catch { /* not remembered */ }
    });
  }

  // ------------------------------------------------------------------ the guide

  const guide = document.getElementById('sa-guide');
  document.querySelectorAll('[data-sa-guide-toggle]').forEach(button => button.addEventListener('click', () => {
    guide.hidden = !guide.hidden;
    button.setAttribute('aria-expanded', String(!guide.hidden));
    if (!guide.hidden) guide.scrollIntoView({ block: 'nearest' });
  }));

  // ------------------------------------------------------------------ rows

  // Under a day's title a row needs only its time.
  const table = document.querySelector('.sa-table:not(.sa-activity-table)');
  const byDay = Boolean(table?.classList.contains('sa-by-day'));

  // Times read as "Today · 15:11" or "Nov 6 · 09:30" (just "15:11" under a day's title), the full date in the tooltip.
  document.querySelectorAll('.sa-row [data-ca-time]').forEach(time => {
    const at = new Date(time.getAttribute('datetime')).getTime();
    if (!Number.isFinite(at)) return;
    const clock = i18n.date(new Date(at), { hour: 'numeric', minute: '2-digit' });
    time.textContent = byDay ? clock : `${text.relativeDay(at)} · ${clock}`;
    time.title = text.dateTime(at);
  });
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  document.querySelectorAll('[data-sa-day]').forEach(label => {
    const days = Math.round((today.getTime() - dayStart(label.dataset.saDay)) / 86400000);
    if (days === 0 || days === 1) label.textContent = text.relativeDay(dayStart(label.dataset.saDay));
  });

  // A line may break after a separator but not before it, nor inside a wave's name ("Wave V", "Fala V").
  document.querySelectorAll('.sa-row :is(.sa-kind, .ca-cell-note, .sa-status-note)').forEach(box => {
    const walker = document.createTreeWalker(box, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const kept = node.nodeValue.replace(/\s+\u00b7/g, '\u00a0\u00b7').replace(/\b(Wave|Fala|Fali|Fale) (?=(?:I{1,3}|IV|V|[A-E])\b)/g, '$1\u00a0');
      if (kept !== node.nodeValue) node.nodeValue = kept;
    }
  });

  const rows = [...document.querySelectorAll('.sa-row')];
  const selecting = () => page.classList.contains('is-selecting');
  // A click anywhere on a row opens the signal, as its ticker does; while choosing rows, it ticks the row.
  rows.forEach(row => row.addEventListener('click', event => {
    if (event.target.closest('a, button, input, label')) return;
    if (selecting()) {
      const box = row.querySelector('[data-signal-checkbox]');
      box.checked = !box.checked;
      box.dispatchEvent(new Event('change', { bubbles: true }));
      return;
    }
    if (window.getSelection()?.toString()) return;
    const link = row.querySelector('.ca-trigger-link');
    if (event.ctrlKey || event.metaKey) window.open(link.href, '_blank', 'noopener');
    else window.location.assign(link.href);
  }));
  document.addEventListener('change', event => {
    if (event.target.matches?.('[data-signal-checkbox], [data-select-all]')) {
      rows.forEach(row => row.classList.toggle('is-selected', Boolean(row.querySelector('[data-signal-checkbox]')?.checked)));
    }
  });

  // ------------------------------------------------------------------ tabs and notes

  // When a tab's word can't fit its quarter of a narrow row (long Polish words), the tabs go two by two.
  const tabs = document.querySelector('.sa-tabs');
  const fitTabs = () => {
    if (!tabs) return;
    tabs.classList.remove('is-stacked');
    tabs.classList.toggle('is-stacked', [...tabs.querySelectorAll('a')].some(tab => tab.scrollWidth > tab.clientWidth + 1));
  };
  fitTabs();
  document.fonts?.ready.then(fitTabs);
  window.addEventListener('resize', fitTabs);

  // An (i) opens its note just under the button shown (the column title's, or the line above the list on phones),
  // inside the window; scrolling closes it.
  document.querySelectorAll('[data-sa-help]').forEach(note => {
    const buttons = [...document.querySelectorAll(`[popovertarget="${note.id}"]`)];
    // Browsers without popovers keep the guide's explanation only.
    if (!('popover' in HTMLElement.prototype)) { note.hidden = true; buttons.forEach(button => { button.hidden = true; }); return; }
    note.addEventListener('toggle', event => {
      const button = buttons.find(candidate => candidate.offsetParent !== null);
      if (event.newState !== 'open' || !button) return;
      const at = button.getBoundingClientRect();
      note.style.left = `${Math.max(8, Math.min(at.right - note.offsetWidth, window.innerWidth - note.offsetWidth - 8))}px`;
      note.style.top = `${Math.min(at.bottom + 6, window.innerHeight - note.offsetHeight - 8)}px`;
    });
    window.addEventListener('scroll', () => { if (note.matches(':popover-open')) note.hidePopover(); }, { passive: true });
  });

  // ------------------------------------------------------------------ choosing rows to delete

  const bulk = document.querySelector('[data-sa-bulk]');
  const selectButtons = [...document.querySelectorAll('[data-sa-select]')];

  function setSelecting(on) {
    page.classList.toggle('is-selecting', on);
    selectButtons.forEach(button => { button.hidden = on; });
    if (bulk) bulk.hidden = !on;
    selectButtons.forEach(button => button.setAttribute('aria-pressed', String(on)));
    if (!on) {
      document.querySelectorAll('[data-signal-checkbox], [data-select-all]').forEach(box => {
        if (!box.checked && !box.indeterminate) return;
        box.checked = false;
        box.indeterminate = false;
      });
      document.querySelector('[data-select-all]')?.dispatchEvent(new Event('change', { bubbles: true }));
      rows.forEach(row => row.classList.remove('is-selected'));
    } else {
      bulk?.querySelector('[data-select-all]')?.focus();
    }
  }
  selectButtons.forEach(button => button.addEventListener('click', () => setSelecting(!selecting())));
  // Phones choose the order from a list; it is a link like the column titles.
  document.querySelectorAll('[data-sa-sort]').forEach(select => select.addEventListener('change', () => {
    if (select.value) window.location.assign(select.value);
  }));
  document.querySelector('[data-sa-select-done]')?.addEventListener('click', () => {
    setSelecting(false);
    selectButtons[0]?.focus();
  });

  // ------------------------------------------------------------------ keyboard

  const ticker = document.querySelector('[data-sa-ticker]');
  if (ticker) {
    // Escape clears a filter that is in the address (and shows everything again), or leaves the field.
    ticker.addEventListener('keydown', event => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      if (ticker.value) {
        const applied = new URLSearchParams(window.location.search).get('ticker');
        ticker.value = '';
        if (applied) ticker.form.requestSubmit();
      } else ticker.blur();
    });
    document.addEventListener('keydown', event => {
      if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey) return;
      if (event.target instanceof Element && event.target.closest('input, textarea, select, [contenteditable="true"], dialog')) return;
      event.preventDefault();
      ticker.focus();
      ticker.select();
    });
  }
  // j and k move between the rows, Enter opens the one in focus.
  document.addEventListener('keydown', event => {
    if ((event.key !== 'j' && event.key !== 'k') || event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.target instanceof Element && event.target.closest('input, textarea, select, [contenteditable="true"], dialog')) return;
    const links = rows.map(row => row.querySelector('.ca-trigger-link')).filter(Boolean);
    if (!links.length) return;
    const at = links.indexOf(document.activeElement);
    const next = links[Math.max(0, Math.min(links.length - 1, at < 0 ? 0 : at + (event.key === 'j' ? 1 : -1)))];
    event.preventDefault();
    next.focus();
    next.closest('tr').scrollIntoView({ block: 'nearest' });
  });
  text.shortcuts([
    [['/'], i18n.t('sa.shortcuts.filter')], [['Esc'], i18n.t('ca.shortcuts.clear')], [['j', 'k'], i18n.t('sa.shortcuts.move')],
    [['Enter'], i18n.t('sa.shortcuts.open')],
  ]);
})();
