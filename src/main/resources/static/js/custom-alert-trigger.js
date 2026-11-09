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

  /**
   * A stock that already matched when the alert was saved is reported at once, not at the next close; a sequence that
   * had completed a little earlier still counts while it is inside its window. Says which happened.
   */
  function atSaveNote(definition, groupResults) {
    if (page.dataset.kind !== 'MATCHED_ON_APPLY') return null;
    const saved = text.date(Number(page.dataset.created));
    const candle = Number(page.dataset.candle);
    const sequence = definition.groups.find(group => group.match === 'THEN' && groupResults.get(group.id)?.facts?.completedAt);
    const completed = sequence ? groupResults.get(sequence.id).facts.completedAt : null;
    const note = completed && completed < candle
      ? i18n.t('ca.evidence.atSaveSequence', day(completed), sequence.span ?? text.defaultSpan(sequence), saved)
      : i18n.t('ca.evidence.atSave', saved);
    return el('p', 'ca-evidence-note', note);
  }

  const capitalize = words => words.charAt(0).toLocaleUpperCase(i18n.tag || 'en') + words.slice(1);

  /** A condition as a heading: its words, a reversed one led by a NOT badge. */
  function conditionTitle(condition, label) {
    const title = el('strong', 'ca-evidence-title');
    if (condition.negate) title.append(el('span', 'ca-chip-not', i18n.t('ca.badge.not')), ' ');
    title.append(label);
    return title;
  }

  async function renderEvidence() {
    const box = document.querySelector('[data-ca-evidence]');
    const definition = evidence.definition;
    if (!definition) { box.replaceChildren(el('p', 'ca-muted', i18n.t('ca.evidence.missing'))); return; }
    const catalog = await text.catalog().catch(() => ({ byType: new Map() }));
    const results = new Map((evidence.conditions || []).map(item => [item.id, item]));
    const groupResults = new Map((evidence.groups || []).map(item => [item.id, item]));
    const palette = window.StockWatchTriggerChart?.PALETTE.length || 8;
    let position = 0;
    const parts = [];
    const note = atSaveNote(definition, groupResults);
    if (note) parts.push(note);
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
        section.append(el('p', 'ca-evidence-group-title', capitalize([definition.groups.length > 1 ? i18n.t('ca.builder.groupTitle', groupIndex + 1) : null,
          i18n.t(ordered ? 'ca.evidence.inOrder' : group.match === 'ANY' ? 'ca.evidence.anyOf' : 'ca.evidence.allOf'),
          ordered && groupPassed && completed?.facts?.completedAt ? i18n.t('ca.evidence.completedOn', day(completed.facts.completedAt))
            : i18n.t(groupPassed ? 'ca.evidence.groupMet' : 'ca.evidence.groupUnmet')].filter(Boolean).join(' · '))));
      }
      const list = el(ordered ? 'ol' : 'ul', 'ca-evidence-list');
      group.conditions.forEach((condition, index) => {
        const result = results.get(condition.id);
        const ok = result?.passed;
        const item = el('li', `ca-evidence-item ${ok ? 'is-met' : 'is-unmet'} ca-color-${position % palette}`);
        item.dataset.position = String(position++);
        // Focusable, so the card can point at its marks on the chart from the keyboard too.
        item.tabIndex = 0;
        const mark = el('span', 'ca-evidence-mark', ordered ? String(index + 1) : '');
        if (!ordered) mark.append(text.icon(ok ? 'check' : 'close'));
        item.append(mark);
        const body = el('div');
        const label = ordered ? `${i18n.t('ca.builder.stepNumber', index + 1)} · ${text.describeStep({ ...condition, negate: false }, catalog.byType)}`
          : text.describe({ ...condition, negate: false }, catalog.byType);
        body.append(conditionTitle(condition, label));
        const info = ordered ? stepDetail(condition, result?.facts, index + 1) : detail(condition, result?.facts);
        if (info) body.append(el('span', 'ca-evidence-detail', info));
        item.append(body);
        const spoken = ordered ? `${i18n.t('ca.builder.stepNumber', index + 1)} · ${text.describeStep(condition, catalog.byType)}` : text.describe(condition, catalog.byType);
        item.setAttribute('aria-label', `${i18n.t(ok ? 'ca.readout.met' : 'ca.readout.unmet')}: ${spoken}${info ? `. ${info}` : ''}`);
        list.append(item);
      });
      section.append(list);
      parts.push(section);
    });
    box.replaceChildren(...parts);
  }

  /**
   * How the price moved after the trigger, close to close: while the follow-up window runs, how far it has got and the
   * change so far; then the change at each checkpoint inside the window (1, 5 and 10 candles) and at its end.
   */
  let candlesSince = null;
  let nextClose = null;
  let closeRevised = false;
  function renderNext() {
    const box = document.querySelector('[data-ca-next]');
    const returns = JSON.parse(box.dataset.returns || '{}');
    const follow = Number(page.dataset.outcomeCandles) || 10;
    const outcome = box.dataset.outcome === undefined || box.dataset.outcome === '' ? null : Number(box.dataset.outcome);
    const complete = box.dataset.outcomeStatus === 'COMPLETE';
    const parts = [];
    if (!complete) {
      const done = candlesSince == null ? null : Math.min(follow, candlesSince);
      const meter = el('div', 'ca-next-progress');
      // One segment per candle of the window (a plain bar past 20), filled as candles close.
      const bar = el('div', `ca-next-track${follow > 20 ? ' is-continuous' : ''}`);
      bar.setAttribute('role', 'progressbar');
      bar.setAttribute('aria-valuemin', '0');
      bar.setAttribute('aria-valuemax', String(follow));
      bar.setAttribute('aria-valuenow', String(done ?? 0));
      bar.setAttribute('aria-label', i18n.t('ca.next.progressAria', follow));
      if (follow > 20) {
        const fill = el('span', 'ca-next-fill');
        fill.style.width = `${Math.round(((done ?? 0) / follow) * 100)}%`;
        bar.append(fill);
      } else {
        for (let at = 0; at < follow; at++) bar.append(el('span', at < (done ?? 0) ? 'is-done' : null));
      }
      const line = el('p', 'ca-next-line');
      line.append(el('span', null, done == null ? i18n.plural('ca.next.measuring', follow, follow) : i18n.plural('ca.next.measuringDone', follow, done, follow)));
      if (outcome != null) {
        line.append(' · ');
        line.append(el('strong', `ca-change ${outcome >= 0 ? 'is-up' : 'is-down'}`, text.signed(outcome)), ` ${i18n.t('ca.next.soFar')}`);
      }
      meter.append(bar, line);
      if (nextClose) line.append(' · ', i18n.t(done ? 'ca.next.nextUpdate' : 'ca.next.firstUpdate', text.date(nextClose, true)));
      parts.push(meter);
    }
    const checkpoints = [1, 5, 10, 20].filter(candles => candles < follow);
    const rows = checkpoints.map(candles => [candles, returns[String(candles)]]);
    if (complete && outcome != null) rows.push([follow, outcome]);
    const measured = rows.filter(([, value]) => value != null);
    if (measured.length) {
      const table = el('table', 'ca-next-table');
      const head = el('tr');
      [i18n.t('ca.next.colCandles'), i18n.t('ca.next.colChange')].forEach(label => { const th = el('th', null, label); th.scope = 'col'; head.append(th); });
      const thead = el('thead');
      thead.append(head);
      const body = el('tbody');
      measured.forEach(([candles, value]) => {
        const tr = el('tr', candles === follow ? 'is-window' : null);
        tr.append(el('td', null, i18n.plural('ca.next.after', candles, candles)));
        const cell = el('td');
        cell.append(el('strong', `ca-change ${value >= 0 ? 'is-up' : 'is-down'}`, text.signed(value)));
        tr.append(cell);
        body.append(tr);
      });
      table.append(thead, body);
      parts.push(table);
    }
    // What this alert's triggers usually did, so this one reads against them.
    const median = box.dataset.alertMedian === undefined || box.dataset.alertMedian === '' ? null : Number(box.dataset.alertMedian);
    const measuredTriggers = Number(box.dataset.alertMeasured) || 0;
    if (median != null && measuredTriggers >= 2) {
      const context = el('p', 'ca-next-context');
      context.append(`${i18n.t('ca.next.alertMedian', follow)} `, el('strong', `ca-change ${median >= 0 ? 'is-up' : 'is-down'}`, text.signed(median)),
        ` (${i18n.plural('ca.next.alertMeasured', measuredTriggers, measuredTriggers)})`);
      parts.push(context);
    }
    // Which close the changes start from matters only when the candle's data changed after the check.
    if (closeRevised && Number.isFinite(Number(page.dataset.close))) parts.push(el('p', 'ca-next-base', i18n.t('ca.next.base', text.number(Number(page.dataset.close)))));
    else if (!nextClose && candlesSince == null) parts.push(el('p', 'ca-next-summary', i18n.t('ca.next.pending', follow)));
    box.replaceChildren(...parts);
  }
  // The chart knows how many candles have closed since the trigger.
  document.addEventListener('stockwatch:trigger-chart', event => {
    candlesSince = event.detail?.candlesSince ?? null;
    nextClose = closeAfter(event.detail?.lastCandle, page.dataset.interval);
    closeRevised = Boolean(event.detail?.revised);
    renderNext();
  });

  /** The day the candle after {@code seconds} closes: the next weekday, the end of next week, or the end of next month. */
  function closeAfter(seconds, interval) {
    if (!Number.isFinite(Number(seconds)) || !seconds) return null;
    const day = new Date(Number(seconds) * 1000);
    if (interval === 'WEEKLY') { day.setUTCDate(day.getUTCDate() + 7 + ((5 - day.getUTCDay() + 7) % 7)); return day.getTime(); }
    if (interval === 'MONTHLY') { return Date.UTC(day.getUTCFullYear(), day.getUTCMonth() + 2, 0); }
    do { day.setUTCDate(day.getUTCDate() + 1); } while (day.getUTCDay() === 0 || day.getUTCDay() === 6);
    return day.getTime();
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

  // What the assistant is told about this page: the trigger as shown, so "why did it fire?" is answered from it.
  const assistantHooks = window.StockWatchAssistantHooks || (window.StockWatchAssistantHooks = {});
  assistantHooks.context = () => ({
    alert: document.querySelector('.ca-trigger-header h1')?.textContent.replace(/\s+/g, ' ').trim(),
    candle: document.querySelector('.ca-trigger-lead')?.textContent.replace(/\s+/g, ' ').trim(),
    tags: document.querySelector('.ca-trigger-tags')?.textContent.replace(/\s+/g, ' ').trim(),
    interval: evidence.interval || null,
    rule: evidence.definition || null,
    checklist: [...document.querySelectorAll('[data-ca-evidence] .ca-evidence-item')].map(item => item.getAttribute('aria-label')),
    afterwards: document.querySelector('[data-ca-next]')?.textContent.replace(/\s+/g, ' ').trim() || null
  });

  renderEvidence();
  renderNext();
})();
