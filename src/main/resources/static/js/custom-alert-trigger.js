(() => {
  'use strict';
  // A custom alert trigger: the rule as it was then, each condition with the value that decided it, a light
  // sketch of the price around the trigger, and how the price moved afterwards.
  const page = document.querySelector('.ca-trigger-page');
  if (!page) return;
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  const el = text.element;
  // The shared request helper (watchlist-picker.js) loads with the navigation, after this script.
  const api = (...args) => window.StockWatchLists.api(...args);
  const evidence = JSON.parse(page.dataset.evidence || '{}');
  const prices = JSON.parse(page.dataset.prices || '[]');
  const status = document.querySelector('[data-ca-trigger-status]');
  const day = seconds => i18n.date(seconds * 1000, { day: 'numeric', month: 'short', year: 'numeric' });
  const signed = value => `${value >= 0 ? '+' : ''}${text.number(value)}%`;

  function detail(condition, facts) {
    if (!facts) return '';
    if (facts.notApplicable) return i18n.t('ca.evidence.notApplicable');
    switch (condition.kind) {
      case 'VALUE': {
        const parts = [];
        if (facts.value != null) parts.push(i18n.t('ca.evidence.value', text.number(facts.value)));
        if (facts.compare != null && condition.compare && condition.compare.kind !== 'VALUE') parts.push(i18n.t('ca.evidence.compare', text.number(facts.compare)));
        if (facts.change != null) parts.push(i18n.t('ca.evidence.change', signed(facts.change)));
        if (facts.at && facts.at !== evidence.candle) parts.push(i18n.t('ca.evidence.on', day(facts.at)));
        return parts.join(' · ');
      }
      case 'PATTERN':
        if (!facts.pattern) return i18n.t('ca.evidence.noPattern');
        return [text.patternName(condition.family, facts.type), i18n.t('ca.evidence.detected', day(facts.detectedAt)),
          facts.anchorAt && facts.anchorAt !== facts.detectedAt ? i18n.t('ca.evidence.anchor', day(facts.anchorAt)) : null,
          facts.score != null ? i18n.t('ca.evidence.score', facts.score) : null].filter(Boolean).join(' · ');
      case 'OUTLOOK':
        return facts.unavailable ? i18n.t('ca.evidence.outlookUnavailable') : `${facts.classification} · ${text.number(facts.score)}%`;
      default:
        return facts.filings
          ? [i18n.plural('ca.readout.filings', facts.filings, facts.filings), i18n.plural('ca.evidence.people', facts.people, facts.people),
            facts.total ? text.money(facts.total) : null].filter(Boolean).join(' · ')
          : i18n.t('ca.evidence.noFilings');
    }
  }

  async function renderEvidence() {
    const box = document.querySelector('[data-ca-evidence]');
    const definition = evidence.definition;
    if (!definition) { box.replaceChildren(el('p', 'wl-muted', i18n.t('ca.evidence.missing'))); return; }
    const catalog = await text.catalog().catch(() => ({ byType: new Map() }));
    const results = new Map((evidence.conditions || []).map(item => [item.id, item]));
    const parts = [];
    if (definition.groups.length > 1) {
      parts.push(el('p', 'ca-evidence-lead', i18n.t(definition.match === 'ANY' ? 'ca.evidence.anyGroup' : 'ca.evidence.everyGroup')));
    }
    definition.groups.forEach((group, groupIndex) => {
      const section = el('div', 'ca-evidence-group');
      const passed = group.conditions.map(condition => results.get(condition.id)?.passed);
      const groupPassed = group.match === 'ANY' ? passed.includes(true) : !passed.includes(false);
      if (definition.groups.length > 1 || group.conditions.length > 1) {
        section.append(el('p', 'ca-evidence-group-title', [definition.groups.length > 1 ? i18n.t('ca.builder.groupTitle', groupIndex + 1) : null,
          i18n.t(group.match === 'ANY' ? 'ca.evidence.anyOf' : 'ca.evidence.allOf'),
          i18n.t(groupPassed ? 'ca.evidence.groupMet' : 'ca.evidence.groupUnmet')].filter(Boolean).join(' · ')));
      }
      const list = el('ul', 'ca-evidence-list');
      group.conditions.forEach(condition => {
        const result = results.get(condition.id);
        const ok = result?.passed;
        const item = el('li', `ca-evidence-item ${ok ? 'is-met' : 'is-unmet'}`);
        item.append(el('span', 'ca-evidence-mark', ok ? '✓' : '–'));
        const body = el('div');
        body.append(el('strong', null, text.describe(condition, catalog.byType)));
        const info = detail(condition, result?.facts);
        if (info) body.append(el('span', 'wl-muted', info));
        item.append(body);
        item.setAttribute('aria-label', `${i18n.t(ok ? 'ca.readout.met' : 'ca.readout.unmet')}: ${text.describe(condition, catalog.byType)}${info ? `. ${info}` : ''}`);
        list.append(item);
      });
      section.append(list);
      parts.push(section);
    });
    box.replaceChildren(...parts);
  }

  /** A plain SVG line: cheap to draw, readable on any screen; the full chart is one click away. */
  function renderPrices() {
    const box = document.querySelector('[data-ca-price-chart]');
    if (prices.length < 2) { box.replaceChildren(el('p', 'wl-muted', i18n.t('ca.price.none'))); return; }
    const width = 600, height = 180, pad = 8;
    const closes = prices.map(point => point[1]);
    const min = Math.min(...closes), max = Math.max(...closes);
    const x = index => pad + index * (width - 2 * pad) / (prices.length - 1);
    const y = value => height - pad - (max === min ? 0.5 : (value - min) / (max - min)) * (height - 2 * pad);
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.setAttribute('class', 'ca-price-svg');
    svg.setAttribute('role', 'img');
    const candle = Number(page.dataset.candle);
    const at = prices.findIndex(point => point[0] === candle);
    svg.setAttribute('aria-label', i18n.t('ca.price.aria', page.dataset.symbol, prices.length));
    if (at >= 0 && at < prices.length - 1) {
      const after = document.createElementNS(ns, 'rect');
      Object.entries({ x: x(at), y: 0, width: x(prices.length - 1) - x(at), height, class: 'ca-price-after' }).forEach(([k, v]) => after.setAttribute(k, v));
      svg.append(after);
    }
    const line = document.createElementNS(ns, 'polyline');
    line.setAttribute('points', prices.map((point, index) => `${x(index).toFixed(1)},${y(point[1]).toFixed(1)}`).join(' '));
    line.setAttribute('class', 'ca-price-line');
    svg.append(line);
    // Patterns that the rule used, at the candle they became known.
    let patterns = 0;
    (evidence.conditions || []).forEach(result => {
      const when = result.facts?.detectedAt;
      const index = when ? prices.findIndex(point => point[0] === when) : -1;
      if (index < 0) return;
      patterns++;
      const dot = document.createElementNS(ns, 'circle');
      Object.entries({ cx: x(index), cy: y(prices[index][1]), r: 4, class: 'ca-price-pattern' }).forEach(([k, v]) => dot.setAttribute(k, v));
      svg.append(dot);
    });
    if (at >= 0) {
      const marker = document.createElementNS(ns, 'line');
      Object.entries({ x1: x(at), x2: x(at), y1: 0, y2: height, class: 'ca-price-marker' }).forEach(([k, v]) => marker.setAttribute(k, v));
      const dot = document.createElementNS(ns, 'circle');
      Object.entries({ cx: x(at), cy: y(prices[at][1]), r: 5, class: 'ca-price-trigger' }).forEach(([k, v]) => dot.setAttribute(k, v));
      svg.append(marker, dot);
    }
    const legend = el('p', 'ca-price-legend');
    // Each key only when the sketch shows it.
    legend.append(el('span', 'ca-legend-trigger', i18n.t('ca.price.trigger')));
    if (at >= 0 && at < prices.length - 1) legend.append(el('span', 'ca-legend-after', i18n.t('ca.price.after')));
    if (patterns) legend.append(el('span', 'ca-legend-pattern', i18n.t('ca.price.pattern')));
    const range = el('div', 'ca-history-range');
    range.append(el('span', null, day(prices[0][0])), el('span', null, `${text.number(min)} – ${text.number(max)}`), el('span', null, day(prices.at(-1)[0])));
    box.replaceChildren(svg, range, legend);
  }

  function renderNext() {
    const box = document.querySelector('[data-ca-next]');
    const returns = JSON.parse(box.dataset.returns || '{}');
    const horizon = Number(page.dataset.outcomeCandles);
    const rows = [1, 5, 10, 20].map(candles => {
      const item = el('div', 'ca-next-item');
      const value = returns[String(candles)];
      item.append(el('span', null, i18n.plural('ca.next.after', candles, candles)),
        el('strong', value == null ? 'unavailable' : value >= 0 ? 'positive' : 'negative', value == null ? i18n.t('ca.next.waiting') : signed(value)));
      return item;
    });
    const outcome = box.dataset.outcome;
    const summary = el('p', 'ca-next-summary', box.dataset.outcomeStatus === 'COMPLETE' && outcome
      ? i18n.t('ca.next.complete', horizon, signed(Number(outcome)))
      : i18n.t('ca.next.pending', horizon));
    box.replaceChildren(...rows, summary);
  }

  document.querySelector('[data-ca-unread]')?.addEventListener('click', async () => {
    try {
      await api(`/api/custom-alerts/triggers/${page.dataset.triggerId}/read?read=false`, { method: 'POST' });
      status.textContent = i18n.t('ca.trigger.markedUnread');
    } catch (error) { status.textContent = error.message; }
  });
  document.querySelector('[data-ca-stop]')?.addEventListener('click', async () => {
    if (!window.confirm(i18n.t('ca.trigger.stopConfirm', page.dataset.symbol))) return;
    try {
      await api(`/api/custom-alerts/${page.dataset.alertId}/stocks/${encodeURIComponent(page.dataset.symbol)}?stopped=true`, { method: 'POST' });
      status.textContent = i18n.t('ca.trigger.stopped', page.dataset.symbol);
    } catch (error) { status.textContent = error.message; }
  });

  renderEvidence();
  renderPrices();
  renderNext();
})();
