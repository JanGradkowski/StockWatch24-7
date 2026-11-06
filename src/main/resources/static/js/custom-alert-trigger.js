(() => {
  'use strict';
  // A custom alert trigger: the rule as it was then, each condition with the value that decided it (in the colour
  // custom-alert-trigger-chart.js draws it with), and how the price moved afterwards.
  const page = document.querySelector('.ca-trigger-page');
  if (!page) return;
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  const el = text.element;
  // The shared request helper (watchlist-picker.js) loads with the navigation, after this script.
  const api = (...args) => window.StockWatchLists.api(...args);
  const evidence = JSON.parse(page.dataset.evidence || '{}');
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
          facts.confirmedAt ? i18n.t('ca.evidence.confirmed', day(facts.confirmedAt)) : null,
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
    const palette = window.StockWatchTriggerChart?.PALETTE.length || 8;
    let position = 0;
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
        const item = el('li', `ca-evidence-item ${ok ? 'is-met' : 'is-unmet'} ca-color-${position++ % palette}`);
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
  renderNext();
})();
