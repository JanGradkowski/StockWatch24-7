(() => {
  'use strict';
  // The stock page overview beside the chart: the technical outlook's verdict for one interval and this stock's
  // newest signals. The full report (/stock/{symbol}/technical-outlook) keeps the chart, indicators and market
  // comparison. The "You follow" chips belong to the alert form, so stock-workspace.js renders those.
  const root = document.querySelector('[data-stock-overview]');
  if (!root) return;
  const i18n = window.StockWatchI18n;
  const encodedSymbol = encodeURIComponent(root.dataset.symbol);
  const status = root.querySelector('[data-overview-status]');
  const result = root.querySelector('[data-overview-result]');
  const report = root.querySelector('[data-overview-report]');
  const reportUrl = report.getAttribute('href');
  const signalsSection = root.querySelector('[data-overview-signals-section]');
  const signalList = root.querySelector('[data-overview-signals]');
  const intervalButtons = Array.from(root.querySelectorAll('[data-overview-interval]'));
  // A cold outlook is built in the background; the full report waits the same way.
  const POLL_MILLIS = 1500;
  const POLL_ATTEMPTS = 40;
  const summaries = new Map();
  let requestController = null;
  let started = false;

  const el = (tag, className, content) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content != null) node.textContent = content;
    return node;
  };

  function wait(milliseconds, signal) {
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(resolve, milliseconds);
      signal.addEventListener('abort', () => {
        window.clearTimeout(timer);
        reject(new DOMException('Aborted', 'AbortError'));
      }, { once: true });
    });
  }

  // Classifications arrive as English constants ("Slight buy outlook"); the full report shows them the same way.
  function classificationLabel(value) {
    const text = String(value ?? '');
    const key = `outlookClassFull.${text.replace(/ outlook$/i, '').toLowerCase().replace(/\s+(\w)/g, (match, letter) => letter.toUpperCase())}`;
    return i18n.has(key) ? i18n.t(key) : text;
  }

  function tone(value) {
    const text = String(value ?? '').toLowerCase();
    if (text.includes('buy')) return 'buy';
    if (text.includes('sell')) return 'sell';
    return 'neutral';
  }

  function directionLabel(value) {
    const key = `stockOverview.direction.${String(value ?? '').toUpperCase()}`;
    return i18n.has(key) ? i18n.t(key) : String(value ?? '');
  }

  function signedPoints(score) {
    const points = Math.round(Number(score) * 100);
    if (!Number.isFinite(points) || points === 0) return '0';
    return `${points > 0 ? '+' : '−'}${Math.abs(points)}`;
  }

  function showStatus(message) {
    status.textContent = message;
    status.hidden = false;
    result.hidden = true;
  }

  function render(summary) {
    const score = summary.headlineScore;
    const verdict = root.querySelector('[data-overview-verdict]');
    verdict.textContent = classificationLabel(score.classification);
    verdict.className = `stock-outlook-tone-${tone(score.classification)}`;
    root.querySelector('[data-overview-score]').textContent = i18n.t('stockOverview.score', signedPoints(score.normalizedScore));
    const position = Math.max(0, Math.min(100, (Number(score.normalizedScore) + 1) * 50));
    const marker = root.querySelector('[data-overview-marker]');
    marker.style.left = `${position}%`;
    marker.className = `stock-outlook-gauge-marker stock-outlook-tone-${tone(score.classification)}`;
    root.querySelector('[data-overview-votes]').textContent =
            i18n.t('stockOverview.votes', score.buy, score.neutral, score.sell, summary.categories.length);
    root.querySelector('[data-overview-categories]').replaceChildren(...summary.categories.map(category => {
      const item = el('li', 'stock-outlook-category');
      item.title = (category.inputs || []).join(' · ');
      item.append(el('span', 'stock-outlook-category-name', category.label),
              el('span', `stock-outlook-pill stock-outlook-tone-${tone(category.classification)}`, directionLabel(category.classification)));
      return item;
    }));
    root.querySelector('[data-overview-freshness]').textContent = summary.freshness || '';
    status.hidden = true;
    result.hidden = false;
    renderSignals(summary.recentSignals || []);
  }

  function renderSignals(signals) {
    signalsSection.hidden = signals.length === 0;
    signalList.replaceChildren(...signals.map(signal => {
      const link = el('a', 'stock-recent-signal');
      link.href = signal.detailUrl;
      const head = el('span', 'stock-recent-signal-head');
      head.append(el('strong', '', signal.label),
              el('span', `stock-outlook-pill stock-outlook-tone-${tone(signal.direction)}`, directionLabel(signal.direction)));
      const familyKey = `stockOverview.family.${signal.family}`;
      const meta = [i18n.has(familyKey) ? i18n.t(familyKey) : signal.family, signal.intervalLabel,
        i18n.date(Number(signal.timestamp) * 1000, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })];
      if (signal.score != null) meta.push(i18n.t('stockOverview.signalScore', signal.score));
      if (signal.lifecycle) meta.push(signal.lifecycle);
      link.append(head, el('span', 'stock-recent-signal-meta', meta.join(' · ')));
      const item = el('li');
      item.append(link);
      return item;
    }));
  }

  async function fetchSummary(interval, signal) {
    const response = await fetch(`/api/stocks/${encodedSymbol}/technical-outlook/summary?interval=${encodeURIComponent(interval)}`,
            { signal, headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`Outlook summary request failed: ${response.status}`);
    return response.json();
  }

  async function load(interval) {
    requestController?.abort();
    requestController = new AbortController();
    const { signal } = requestController;
    intervalButtons.forEach(button => button.setAttribute('aria-pressed', String(button.dataset.overviewInterval === interval)));
    report.href = interval === '1d' ? reportUrl : `${reportUrl}?interval=${encodeURIComponent(interval)}`;
    if (summaries.has(interval)) {
      render(summaries.get(interval));
      return;
    }
    showStatus(i18n.t('stockOverview.calculating'));
    try {
      let summary = await fetchSummary(interval, signal);
      for (let attempt = 0; !summary.available && attempt < POLL_ATTEMPTS; attempt++) {
        await wait(POLL_MILLIS, signal);
        const refresh = await fetch(`/api/stocks/${encodedSymbol}/technical-outlook/refresh-status?interval=${encodeURIComponent(interval)}`,
                { signal, headers: { Accept: 'application/json' } });
        if (!refresh.ok) continue;
        const refreshStatus = await refresh.json();
        if (refreshStatus.state === 'FAILED') break;
        if (refreshStatus.ready) summary = await fetchSummary(interval, signal);
      }
      if (!summary.available) {
        showStatus(i18n.t('stockOverview.unavailable'));
        signalsSection.hidden = true;
        return;
      }
      summaries.set(interval, summary);
      render(summary);
    } catch (error) {
      if (error.name === 'AbortError') return;
      showStatus(i18n.t('stockOverview.failed'));
      signalsSection.hidden = true;
      console.warn('Outlook summary failed', error);
    }
  }

  intervalButtons.forEach(button => button.addEventListener('click', () => load(button.dataset.overviewInterval)));

  // Edit goes to the alert form below without changing the address (the workspace tabs follow the hash).
  root.querySelector('[data-follow-summary-edit]')?.addEventListener('click', () => {
    const form = document.querySelector('.alert-star-panel');
    if (!form) return;
    form.setAttribute('tabindex', '-1');
    form.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' });
    form.focus({ preventScroll: true });
  });

  // Load once the overview is on screen: beside the chart that is at once, on narrow screens when its tab opens.
  function start() {
    if (started) return;
    started = true;
    load('1d');
  }
  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => {
      if (!entries.some(entry => entry.isIntersecting)) return;
      observer.disconnect();
      start();
    });
    observer.observe(root);
  } else {
    start();
  }
})();
