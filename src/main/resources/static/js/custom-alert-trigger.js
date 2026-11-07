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

  /** Lower and upper tests point at their extreme ("lowest 24.1"); the others at the value on the step's candle. */
  const LOWER = ['BELOW', 'CROSSES_BELOW', 'FALLING', 'CHANGE_BELOW', 'NEW_LOW'];
  const UPPER = ['ABOVE', 'CROSSES_ABOVE', 'RISING', 'CHANGE_ABOVE', 'NEW_HIGH'];

  /** A step of a sequence: when it held, its deciding value or pattern, and how soon after the step before it came. */
  function stepDetail(condition, facts, number) {
    if (!facts?.from) {
      if (facts?.waiting) return [i18n.t('ca.readout.waiting'), facts.candlesLeft ? i18n.plural('ca.readout.candlesLeft', facts.candlesLeft, facts.candlesLeft) : null]
        .filter(Boolean).join(' · ');
      return facts?.soFar ? i18n.t('ca.readout.soFar', facts.soFar, facts.needed) : '';
    }
    const parts = [];
    const several = facts.candles > 1;
    if (condition.kind === 'VALUE') {
      if (several && LOWER.includes(condition.operator) && facts.low != null) parts.push(i18n.t('ca.evidence.lowest', text.number(facts.low)));
      else if (several && UPPER.includes(condition.operator) && facts.high != null) parts.push(i18n.t('ca.evidence.highest', text.number(facts.high)));
      else if (facts.value != null) parts.push(i18n.t('ca.evidence.value', text.number(facts.value)));
    } else if (facts.pattern) {
      parts.push(detail(condition, facts));
    }
    // "27 Oct–3 Nov 2026": the year once, unless the stretch crosses a new year.
    const sameYear = new Date(facts.from * 1000).getUTCFullYear() === new Date(facts.to * 1000).getUTCFullYear();
    const start = sameYear ? i18n.date(facts.from * 1000, { day: 'numeric', month: 'short' }) : day(facts.from);
    parts.push(several ? `${start}–${day(facts.to)} (${i18n.plural('ca.evidence.stepCandles', facts.candles, facts.candles)})`
      : i18n.t('ca.evidence.on', day(facts.at)));
    if (facts.after != null) parts.push(i18n.plural('ca.evidence.after', facts.after, facts.after, number - 1));
    return parts.filter(Boolean).join(' · ');
  }

  async function renderEvidence() {
    const box = document.querySelector('[data-ca-evidence]');
    const definition = evidence.definition;
    if (!definition) { box.replaceChildren(el('p', 'wl-muted', i18n.t('ca.evidence.missing'))); return; }
    const catalog = await text.catalog().catch(() => ({ byType: new Map() }));
    const results = new Map((evidence.conditions || []).map(item => [item.id, item]));
    const groupResults = new Map((evidence.groups || []).map(item => [item.id, item]));
    const palette = window.StockWatchTriggerChart?.PALETTE.length || 8;
    let position = 0;
    const parts = [];
    if (definition.groups.length > 1) {
      parts.push(el('p', 'ca-evidence-lead', i18n.t(definition.match === 'ANY' ? 'ca.evidence.anyGroup' : 'ca.evidence.everyGroup')));
    }
    definition.groups.forEach((group, groupIndex) => {
      const section = el('div', 'ca-evidence-group');
      const ordered = group.match === 'THEN';
      const passed = group.conditions.map(condition => results.get(condition.id)?.passed);
      const completed = groupResults.get(group.id);
      const groupPassed = ordered ? Boolean(completed?.passed) : group.match === 'ANY' ? passed.includes(true) : !passed.includes(false);
      if (definition.groups.length > 1 || group.conditions.length > 1) {
        section.append(el('p', 'ca-evidence-group-title', [definition.groups.length > 1 ? i18n.t('ca.builder.groupTitle', groupIndex + 1) : null,
          i18n.t(ordered ? 'ca.evidence.inOrder' : group.match === 'ANY' ? 'ca.evidence.anyOf' : 'ca.evidence.allOf'),
          ordered && groupPassed && completed?.facts?.completedAt ? i18n.t('ca.evidence.completedOn', day(completed.facts.completedAt))
            : i18n.t(groupPassed ? 'ca.evidence.groupMet' : 'ca.evidence.groupUnmet')].filter(Boolean).join(' · ')));
      }
      const list = el(ordered ? 'ol' : 'ul', 'ca-evidence-list');
      group.conditions.forEach((condition, index) => {
        const result = results.get(condition.id);
        const ok = result?.passed;
        const item = el('li', `ca-evidence-item ${ok ? 'is-met' : 'is-unmet'} ca-color-${position++ % palette}`);
        item.append(el('span', 'ca-evidence-mark', ordered ? String(index + 1) : ok ? '✓' : '–'));
        const body = el('div');
        body.append(el('strong', null, ordered
          ? `${i18n.t('ca.builder.stepNumber', index + 1)} · ${text.describeStep(condition, catalog.byType)}` : text.describe(condition, catalog.byType)));
        const info = ordered ? stepDetail(condition, result?.facts, index + 1) : detail(condition, result?.facts);
        if (info) body.append(el('span', 'wl-muted', info));
        item.append(body);
        item.setAttribute('aria-label', `${i18n.t(ok ? 'ca.readout.met' : 'ca.readout.unmet')}: ${body.firstChild.textContent}${info ? `. ${info}` : ''}`);
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
