(() => {
  'use strict';
  const intervals = { '1d': 'Daily', '1wk': 'Weekly', '1mo': 'Monthly' };
  const direction = value => /buy/i.test(value || '') ? 'buy' : /sell/i.test(value || '') ? 'sell' : 'neutral';
  const label = value => (value || 'Unavailable').replace(/ outlook$/i, '');
  const intervalFor = (ticker, interval) => ticker.intervals.find(item => item.apiInterval === interval);
  const signed = value => value == null || !Number.isFinite(value) ? '—' : `${value > 0 ? '+' : ''}${value.toFixed(1)}`;

  function ranked(tickers, interval, side) {
    return tickers.map(ticker => ({ ticker, outlook: intervalFor(ticker, interval) }))
      .filter(item => item.outlook?.available && !item.outlook.stale && Number.isFinite(item.outlook.score)
        && direction(item.outlook.classification) === side)
      .sort((a, b) => (side === 'buy' ? b.outlook.score - a.outlook.score : a.outlook.score - b.outlook.score)
        || a.ticker.symbol.localeCompare(b.ticker.symbol))
      .slice(0, 5);
  }

  function filtered(tickers, { search = '', interval = 'all', outlook = 'all', sort = 'ticker', rankingInterval = '1d' }) {
    const needle = search.trim().toLowerCase();
    const selected = tickers.filter(ticker => {
      if (!`${ticker.symbol} ${ticker.companyName || ''}`.toLowerCase().includes(needle)) return false;
      return ticker.intervals.some(item => (interval === 'all' || item.apiInterval === interval)
        && (outlook === 'all' || (outlook === 'unavailable' ? !item.available
          : outlook === 'stale' ? item.stale : item.available && direction(item.classification) === outlook)));
    });
    const sortInterval = interval === 'all' ? rankingInterval : interval;
    const value = ticker => {
      if (sort === 'latest') {
        const dates = ticker.intervals.filter(item => interval === 'all' || item.apiInterval === interval)
          .map(item => item.changedAt).filter(Number.isFinite);
        return dates.length ? Math.max(...dates) : null;
      }
      const item = intervalFor(ticker, sortInterval);
      return !item?.available ? null : sort.startsWith('score') ? item.score : item.priceChangePercent;
    };
    return selected.sort((a, b) => {
      if (sort === 'ticker') return a.symbol.localeCompare(b.symbol);
      const av = value(a), bv = value(b);
      if (av == null && bv != null) return 1;
      if (bv == null && av != null) return -1;
      return (av == null ? 0 : sort.endsWith('low') ? av - bv : bv - av) || a.symbol.localeCompare(b.symbol);
    });
  }

  // Pure selection logic is also exercised by the Node regression tests.
  if (typeof module !== 'undefined' && module.exports) module.exports = { ranked, filtered, direction, signed };
  if (typeof document === 'undefined') return;
  const page = document.getElementById('technicalWatchlist');
  if (!page) return;
  const get = id => document.getElementById(id);
  const endpoint = page.dataset.endpoint;
  const dialog = get('unfollowAllDialog');
  let tickers = [], rankingInterval = '1d', busy = false, loading = false, loaded = false, changesSequence = 0;
  const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
  const dates = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' });
  const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

  function node(tag, className, text) {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (text != null) item.textContent = text;
    return item;
  }
  function dateText(timestamp) { return timestamp == null ? 'Unknown' : `${dates.format(new Date(timestamp * 1000))} UTC`; }
  function time(timestamp) {
    const element = node('time', '', '');
    const seconds = Math.min(0, timestamp - Date.now() / 1000);
    element.textContent = Math.abs(seconds) < 60 ? 'Just now' : Math.abs(seconds) < 3600
      ? relative.format(Math.round(seconds / 60), 'minute') : Math.abs(seconds) < 86400
        ? relative.format(Math.round(seconds / 3600), 'hour') : relative.format(Math.round(seconds / 86400), 'day');
    element.dateTime = new Date(timestamp * 1000).toISOString();
    element.title = dateText(timestamp);
    return element;
  }
  function outlookUrl(symbol, interval) {
    return `${page.dataset.stockBase}${encodeURIComponent(symbol)}/technical-outlook${interval ? `?interval=${interval}` : ''}`;
  }
  function tone(value) { return value > 0 ? 'watchlist-positive' : value < 0 ? 'watchlist-negative' : ''; }
  function badge(value) { return node('span', `watchlist-badge ${direction(value)}`, label(value)); }
  function showError(message) { get('watchlistError').textContent = message || ''; get('watchlistError').hidden = !message; }
  function announce(message) { get('watchlistAnnouncement').textContent = message; }
  function message(container, text) { container.replaceChildren(node('li', 'watchlist-panel-message', text)); }
  async function request(url, options = {}) {
    const response = await fetch(url, { credentials: 'same-origin', signal: AbortSignal.timeout(20_000), ...options });
    if (response.redirected || response.status === 401) throw new Error('Your session has expired. Sign in again to continue.');
    if (!response.ok) throw new Error(response.status === 403 ? 'This action could not be authorized. Refresh the page and try again.' : 'Could not update the watchlist. Please try again.');
    return response.json();
  }
  function renderRankings() {
    get('rankingCaption').textContent = `Strongest followed outlooks · ${intervals[rankingInterval]}`;
    document.querySelectorAll('[data-ranking-interval]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.rankingInterval === rankingInterval)));
    for (const side of ['buy', 'sell']) {
      const container = get(side === 'buy' ? 'watchlistBuys' : 'watchlistSells');
      const leaders = ranked(tickers, rankingInterval, side);
      if (!leaders.length) { message(container, `No current ${side} outlooks among your followed ${intervals[rankingInterval].toLowerCase()} intervals.`); continue; }
      container.replaceChildren(...leaders.map(({ ticker, outlook }, index) => {
        const row = node('li');
        const link = node('a'); link.href = outlookUrl(ticker.symbol, rankingInterval);
        const company = node('span', 'watchlist-company');
        company.append(node('strong', '', ticker.symbol), node('small', '', ticker.companyName));
        const score = node('span', 'watchlist-ranking-value');
        score.append(node('strong', side === 'buy' ? 'watchlist-positive' : 'watchlist-negative', signed(outlook.score)), node('small', '', label(outlook.classification)));
        score.title = `Overall outlook score: ${signed(outlook.score)} of ±100. Candle: ${dateText(outlook.candleTimestamp)}`;
        link.append(node('span', 'watchlist-rank', String(index + 1).padStart(2, '0')), company, score);
        row.append(link); return row;
      }));
    }
  }
  function intervalCell(ticker, interval) {
    const cell = node('td');
    const outlook = intervalFor(ticker, interval);
    const content = node(outlook ? 'a' : 'div', outlook ? 'watchlist-interval-link' : 'watchlist-not-followed');
    content.append(node('span', 'watchlist-mobile-interval', intervals[interval]));
    if (!outlook) { content.append(node('span', '', 'Not followed')); cell.append(content); return cell; }
    content.href = outlookUrl(ticker.symbol, interval);
    content.setAttribute('aria-label', `${ticker.symbol} ${intervals[interval]} outlook. ${outlook.available ? label(outlook.classification) : 'Awaiting analysis'}`);
    if (!outlook.available) {
      content.append(node('span', 'watchlist-badge', 'Awaiting analysis'), node('span', 'watchlist-cell-date', outlook.settingsChanged ? 'Settings changed · awaiting next evaluation' : 'Waiting for the first evaluated candle'));
      cell.append(content); return cell;
    }
    const top = node('div', 'watchlist-interval-top');
    const score = node('span', 'watchlist-cell-score');
    score.append(node('small', '', 'Score'), node('strong', '', signed(outlook.score)));
    top.append(badge(outlook.classification), score);
    content.append(top);
    if (outlook.changedAt != null) {
      const changed = node('span', 'watchlist-cell-date', 'Changed '); changed.append(time(outlook.changedAt));
      const history = node('div', 'watchlist-cell-history');
      history.append(changed, node('span', 'watchlist-cell-transition', `${label(outlook.previousClassification)} \u2192 ${label(outlook.classification)}`));
      content.append(history);
      const progress = node('span', 'watchlist-price-progress');
      progress.append(node('strong', tone(outlook.priceChangePercent), outlook.priceChangePercent == null ? '—' : `${signed(outlook.priceChangePercent)}%`), node('span', '', 'price since change'));
      progress.title = `Change close: ${outlook.changePrice == null ? 'unavailable' : number.format(outlook.changePrice)} ${ticker.currency || ''} on ${dateText(outlook.changedAt)}. Latest evaluated close: ${outlook.price == null ? 'unavailable' : number.format(outlook.price)} ${ticker.currency || ''} on ${dateText(outlook.candleTimestamp)}.`;
      const metrics = node('div', 'watchlist-cell-metrics');
      metrics.append(progress, node('span', 'watchlist-score-progress', `${signed(outlook.scoreChangePoints)} pts since change`));
      content.append(metrics);
    } else {
      const pending = node('div', 'watchlist-cell-history watchlist-history-pending');
      pending.append(node('span', 'watchlist-cell-date', outlook.historyUnknown ? 'Earlier baseline unavailable' : 'No outlook change yet'), node('span', 'watchlist-score-progress', 'Tracking the next change'));
      pending.title = 'Price and score progress will be measured from the next recorded outlook change.';
      content.append(pending);
    }
    const asOf = node('span', outlook.stale ? 'watchlist-stale' : 'watchlist-asof', outlook.stale ? 'Potentially stale · ' : 'Evaluated candle · ');
    asOf.append(time(outlook.candleTimestamp)); content.append(asOf);
    cell.append(content); return cell;
  }
  function renderTable() {
    const interval = get('watchlistInterval').value;
    const selected = filtered(tickers, { search: get('watchlistSearch').value, interval,
      outlook: get('watchlistOutlook').value, sort: get('watchlistSort').value, rankingInterval });
    get('watchlistRows').replaceChildren(...selected.map(ticker => {
      const row = node('tr'), cell = node('td'), company = node('div', 'watchlist-ticker');
      const link = node('a', '', ticker.symbol); link.href = outlookUrl(ticker.symbol);
      const identity = node('div', 'watchlist-ticker-identity');
      const emblem = node('span', 'watchlist-ticker-emblem', ticker.symbol.replace(/[^a-z0-9]/gi, '').slice(0, 2).toUpperCase());
      emblem.setAttribute('aria-hidden', 'true');
      const copy = node('div', 'watchlist-ticker-copy');
      copy.append(link, node('p', '', ticker.companyName));
      identity.append(emblem, copy);
      const followed = node('div', 'watchlist-followed-intervals');
      followed.setAttribute('aria-label', `${ticker.intervals.length} intervals followed`);
      Object.keys(intervals).filter(key => intervalFor(ticker, key)).forEach(key => {
        const tag = node('span', '', intervals[key]);
        tag.title = `Following ${intervals[key].toLowerCase()} automated analysis`;
        followed.append(tag);
      });
      company.append(identity, followed);
      cell.append(company); row.append(cell);
      Object.keys(intervals).forEach(key => row.append(intervalCell(ticker, key)));
      const action = node('td', 'watchlist-row-action'), button = node('button', 'watchlist-unfollow', 'Unfollow');
      button.type = 'button'; button.disabled = busy || loading; button.title = `Unfollow all automated outlook intervals for ${ticker.symbol}`;
      button.setAttribute('aria-label', `Unfollow ${ticker.symbol}`);
      button.addEventListener('click', () => unfollow(ticker.symbol));
      action.append(button); row.append(action); return row;
    }));
    get('watchlistCount').textContent = `${tickers.length} ticker${tickers.length === 1 ? '' : 's'} followed`;
    get('watchlistResults').textContent = `${selected.length} of ${tickers.length} tickers`;
    get('watchlistSortNote').textContent = `Score and price sorting use ${intervals[interval === 'all' ? rankingInterval : interval]}.`;
    get('watchlistTable').hidden = !selected.length;
    get('watchlistEmpty').hidden = !!tickers.length;
    get('watchlistNoMatches').hidden = !tickers.length || !!selected.length;
    get('unfollowAll').disabled = busy || loading || !tickers.length;
  }
  async function loadChanges() {
    const sequence = ++changesSequence;
    const container = get('watchlistChanges');
    message(container, 'Loading changes…');
    try {
      const changes = await request(`${endpoint}/changes?interval=${encodeURIComponent(get('changeInterval').value)}&watchlistId=${encodeURIComponent(get('namedWatchlistFilter')?.value || '')}`);
      if (sequence !== changesSequence) return;
      if (!changes.length) { message(container, 'No recent outlook changes for your followed intervals. New changes will appear here.'); return; }
      container.replaceChildren(...changes.map(change => {
        const row = node('li'), link = node('a'); link.href = `${page.dataset.changeBase}${encodeURIComponent(change.id)}`;
        const copy = node('span', 'watchlist-change-copy'), title = node('span', 'watchlist-change-title');
        title.append(node('strong', '', change.symbol), node('small', '', change.intervalLabel));
        const transition = node('span', 'watchlist-change-transition', `${label(change.previousClassification)} → `);
        transition.append(node('strong', direction(change.currentClassification) === 'buy' ? 'watchlist-positive' : direction(change.currentClassification) === 'sell' ? 'watchlist-negative' : '', label(change.currentClassification)));
        copy.append(title, transition); link.append(copy, time(change.candleTimestamp)); row.append(link); return row;
      }));
    } catch (error) { if (sequence === changesSequence) message(container, `${error.message} Use Refresh to retry.`); }
  }
  async function load() {
    if (busy || loading) return;
    loading = true; get('watchlistRefresh').disabled = true;
    if (get('namedWatchlistFilter')) get('namedWatchlistFilter').disabled = true;
    get('unfollowAll').disabled = true;
    document.querySelectorAll('.watchlist-unfollow').forEach(button => { button.disabled = true; });
    const changes = loadChanges();
    try {
      const data = await request(`${endpoint}?watchlistId=${encodeURIComponent(get('namedWatchlistFilter')?.value || '')}`);
      tickers = data.tickers; loaded = true; showError('');
      renderRankings(); renderTable();
      const staleCount = tickers.flatMap(ticker => ticker.intervals).filter(item => item.stale).length;
      get('watchlistFreshness').textContent = `Checked ${dateText(data.fetchedAt)}${staleCount ? ` · ${staleCount} potentially stale interval${staleCount === 1 ? '' : 's'}` : ' · Completed candles'}`;
    } catch (error) {
      showError(`${error.message}${loaded ? ' Previously loaded values remain visible.' : ' Use Refresh to retry.'}`);
      if (!loaded) {
        get('watchlistCount').textContent = 'Watchlist unavailable';
        get('watchlistResults').textContent = 'Could not load tickers';
        get('watchlistFreshness').textContent = 'Data could not be checked';
        message(get('watchlistBuys'), 'Outlooks could not be loaded. Use Refresh to retry.');
        message(get('watchlistSells'), 'Outlooks could not be loaded. Use Refresh to retry.');
      }
    } finally {
      get('watchlistLoading').hidden = true;
      await changes;
      loading = false; get('watchlistRefresh').disabled = false;
      if (get('namedWatchlistFilter')) get('namedWatchlistFilter').disabled = false;
      get('unfollowAll').disabled = !tickers.length;
      document.querySelectorAll('.watchlist-unfollow').forEach(button => { button.disabled = false; });
    }
  }
  async function unfollow(symbol) {
    if (busy || loading) return;
    busy = true;
    ++changesSequence;
    const error = get('unfollowAllError'); error.hidden = true;
    const confirm = get('confirmUnfollowAll'), cancel = get('cancelUnfollowAll');
    confirm.disabled = true; cancel.disabled = true; get('watchlistRefresh').disabled = true;
    get('unfollowAll').disabled = true;
    document.querySelectorAll('.watchlist-unfollow').forEach(button => { button.disabled = true; });
    try {
      const header = document.querySelector('meta[name="_csrf_header"]').content;
      const token = document.querySelector('meta[name="_csrf"]').content;
      await request(symbol == null ? endpoint : `${endpoint}/${encodeURIComponent(symbol)}`, { method: 'DELETE', headers: { [header]: token } });
      tickers = symbol == null ? [] : tickers.filter(ticker => ticker.symbol !== symbol);
      if (dialog.open) dialog.close();
      showError(''); renderRankings(); renderTable();
      announce(symbol == null ? 'All automated outlooks unfollowed.' : `${symbol} unfollowed on all intervals.`);
      get('watchlistSearch').focus();
      await loadChanges();
    } catch (failure) {
      if (dialog.open) { error.textContent = failure.message; error.hidden = false; }
      else showError(failure.message);
    } finally {
      busy = false; confirm.disabled = false; cancel.disabled = false; get('watchlistRefresh').disabled = false;
      get('unfollowAll').disabled = !tickers.length;
      document.querySelectorAll('.watchlist-unfollow').forEach(button => { button.disabled = false; });
    }
  }

  document.querySelectorAll('[data-ranking-interval]').forEach(button => button.addEventListener('click', () => {
    rankingInterval = button.dataset.rankingInterval; if (loaded) { renderRankings(); renderTable(); }
  }));
  ['watchlistInterval', 'watchlistOutlook', 'watchlistSort'].forEach(id => get(id).addEventListener('change', () => { if (loaded) renderTable(); }));
  get('watchlistSearch').addEventListener('input', () => { if (loaded) renderTable(); });
  get('changeInterval').addEventListener('change', loadChanges);
  const namedFilter = get('namedWatchlistFilter');
  if (namedFilter) {
    namedFilter.addEventListener('change', load);
    request('/api/watchlists').then(lists => lists.forEach(list => {
      const option = document.createElement('option'); option.value = list.id; option.textContent = list.name; namedFilter.append(option);
    })).catch(error => showError(error.message));
  }
  get('watchlistClear').addEventListener('click', () => {
    get('watchlistSearch').value = ''; get('watchlistInterval').value = 'all'; get('watchlistOutlook').value = 'all'; renderTable(); get('watchlistSearch').focus();
  });
  get('watchlistRefresh').addEventListener('click', load);
  get('unfollowAll').addEventListener('click', () => {
    get('unfollowAllDescription').textContent = `Stop following automated technical analysis for every ticker in your account, across every watchlist and followed interval. This includes tickers hidden by your filters. You can follow them again from their stock pages.`;
    get('unfollowAllError').hidden = true; dialog.showModal();
  });
  get('cancelUnfollowAll').addEventListener('click', () => dialog.close());
  get('confirmUnfollowAll').addEventListener('click', () => unfollow(null));
  dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
  setInterval(() => { if (!document.hidden && !dialog.open) load(); }, 60_000);
  load();
})();
