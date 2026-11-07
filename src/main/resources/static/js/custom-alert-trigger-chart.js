(() => {
  'use strict';
  // A custom alert trigger on the candlestick chart of the signal pages, with every condition of the rule drawn
  // where it was decided: indicator lines in panes of their own, the side of a fixed threshold that passes
  // shaded, comparisons and runs traced in bold, a pattern's candles boxed or its turning points joined, filings
  // on the candles they were published in, and each window the rule looked back over. The candles and lines come
  // from /api/custom-alerts/triggers/{id}/chart, the rule and its deciding values from the trigger's evidence.

  /** One colour per condition, in rule order; the chart theme and custom-alerts.css give each its light partner. */
  const PALETTE = ['#a78bfa', '#ffbd59', '#60a5fa', '#f472b6', '#47d4df', '#ff8f70', '#e2aa65', '#7ea6ff'];
  const colorAt = index => PALETTE[index % PALETTE.length];
  /** Candles after the trigger whose close "What happened next" reports. */
  const FORWARD = [1, 5, 10, 20];
  /** Indicator panes besides price and volume: more would leave each too short to read on a laptop. */
  const MAX_PANELS = 3;
  // Dark-theme colours; StockWatchCharts swaps them for their light partners.
  const UP = '#64e8bd';
  const DOWN = '#ff647c';
  const TRIGGER = '#b7f34a';
  const MUTED = '#8a94a6';

  /** The rule's conditions in reading order, the order of their colours. */
  function conditionsOf(definition) {
    return (definition?.groups || []).flatMap(group => group.conditions || []);
  }

  /** For each condition that is a step of a sequence ("in order" group), its step number. */
  function stepsOf(definition) {
    const steps = new Map();
    (definition?.groups || []).filter(group => group.match === 'THEN')
      .forEach(group => (group.conditions || []).forEach((condition, index) => steps.set(condition.id, index + 1)));
    return steps;
  }

  /** A [time, value] list on the chart's candles: NaN where the line has no value. */
  function align(points, indexOf, length) {
    const values = new Array(length).fill(NaN);
    (points || []).forEach(([time, value]) => {
      const at = indexOf.get(Number(time));
      if (at !== undefined && value !== null && Number.isFinite(Number(value))) values[at] = Number(value);
    });
    return values;
  }

  const finite = (...values) => values.every(Number.isFinite);
  const pick = (values, at) => values && at >= 0 && at < values.length ? values[at] : NaN;

  function changeOf(values, from, to) {
    const start = pick(values, from);
    const end = pick(values, to);
    return finite(start, end) && start !== 0 ? (end - start) / Math.abs(start) * 100 : NaN;
  }

  /** Whether a value condition's test is true on candle {@code at}, decided as CustomAlertEvaluator.holds does. */
  function holds(condition, subject, compare, at) {
    const now = pick(subject, at);
    if (!Number.isFinite(now)) return false;
    const other = pick(compare, at);
    const count = Number(condition.candles) || 1;
    switch (condition.operator) {
      case 'ABOVE': return Number.isFinite(other) && now > other;
      case 'BELOW': return Number.isFinite(other) && now < other;
      case 'CROSSES_ABOVE': case 'CROSSES_BELOW': {
        const before = pick(subject, at - 1);
        const otherBefore = pick(compare, at - 1);
        if (!finite(before, otherBefore, other)) return false;
        return condition.operator === 'CROSSES_ABOVE' ? before <= otherBefore && now > other : before >= otherBefore && now < other;
      }
      case 'BETWEEN': return now >= condition.lower && now <= condition.upper;
      case 'OUTSIDE': return now < condition.lower || now > condition.upper;
      case 'RISING': case 'FALLING': {
        if (at - count < 0) return false;
        for (let step = 0; step < count; step++) {
          const later = pick(subject, at - step);
          const earlier = pick(subject, at - step - 1);
          if (!finite(later, earlier)) return false;
          if (condition.operator === 'RISING' ? later <= earlier : later >= earlier) return false;
        }
        return true;
      }
      case 'CHANGE_ABOVE': case 'CHANGE_BELOW': {
        const change = changeOf(subject, at - count, at);
        if (!Number.isFinite(change)) return false;
        return condition.operator === 'CHANGE_ABOVE' ? change >= condition.percent : change <= -condition.percent;
      }
      case 'NEW_HIGH': case 'NEW_LOW': {
        if (at - count < 0) return false;
        for (let previous = at - count; previous < at; previous++) {
          const earlier = pick(subject, previous);
          if (!Number.isFinite(earlier)) return false;
          if (condition.operator === 'NEW_HIGH' ? now <= earlier : now >= earlier) return false;
        }
        return true;
      }
      default: return false;
    }
  }

  /** Tests decided on a single candle: marked there rather than traced along the line. */
  const momentary = operator => ['CROSSES_ABOVE', 'CROSSES_BELOW', 'CHANGE_ABOVE', 'CHANGE_BELOW', 'NEW_HIGH', 'NEW_LOW'].includes(operator);

  /**
   * The candles a value condition is drawn as passing on: every candle where its test holds, and for a rising or
   * falling run the whole run that ends there.
   */
  function passing(condition, subject, compare) {
    const length = subject.length;
    const passed = new Array(length).fill(false);
    if (momentary(condition.operator)) return passed;
    const run = ['RISING', 'FALLING'].includes(condition.operator) ? Number(condition.candles) || 1 : 0;
    for (let at = 0; at < length; at++) {
      if (!holds(condition, subject, compare, at)) continue;
      for (let index = Math.max(0, at - run); index <= at; index++) passed[index] = true;
    }
    return passed;
  }

  /** The candles a condition's window covers on the trigger candle: [first, last]. */
  function windowOf(condition, trigger) {
    const window = condition.window || { mode: 'LATEST' };
    const span = !window.mode || window.mode === 'LATEST' || window.mode === 'DAYS' ? 1 : Math.max(1, Number(window.candles) || 1);
    return [Math.max(0, trigger - span + 1), trigger];
  }

  /** The last candle starting at or before {@code seconds}; -1 when every candle starts later. */
  function atOrBefore(times, seconds) {
    let low = 0;
    let high = times.length;
    while (low < high) {
      const middle = (low + high) >>> 1;
      if (times[middle] <= seconds) low = middle + 1; else high = middle;
    }
    return low - 1;
  }

  /** The operand a value condition reads as {@code role} ("subject" or "compare"), and the server's line for it. */
  function operandOf(condition, role) {
    return role === 'subject' ? condition.subject : condition.compare;
  }

  /**
   * Where each indicator line goes: over the candles (PRICE), over volume (VOLUME), or into a pane of its own that
   * the lines of one indicator with the same settings share (RSI and its moving average). Returns the pane order
   * and each line's pane index; -1 for a line past MAX_PANELS.
   */
  function panePlan(conditions, views, series) {
    const usesVolume = conditions.some(condition => condition.kind === 'VALUE'
      && [condition.subject, condition.compare].some(operand => operand?.kind === 'PRICE' && operand.field === 'VOLUME'));
    const owners = new Map();
    conditions.forEach(condition => ['subject', 'compare'].forEach(role => {
      const id = views[condition.id]?.[role];
      if (id && !owners.has(id)) owners.set(id, operandOf(condition, role));
    }));
    const volume = usesVolume || [...owners.keys()].some(id => series.get(id)?.placement === 'VOLUME');
    const panes = volume ? ['price', 'volume'] : ['price'];
    const paneOf = new Map();
    const hidden = [];
    owners.forEach((operand, id) => {
      const placement = series.get(id)?.placement;
      if (placement === 'PRICE') { paneOf.set(id, 0); return; }
      if (placement === 'VOLUME') { paneOf.set(id, 1); return; }
      const parameters = operand?.parameters || {};
      const key = `${operand?.type}:${JSON.stringify(Object.keys(parameters).sort().map(name => [name, parameters[name]]))}`;
      if (!panes.includes(key)) {
        if (panes.length - (volume ? 2 : 1) >= MAX_PANELS) { paneOf.set(id, -1); hidden.push(id); return; }
        panes.push(key);
      }
      paneOf.set(id, panes.indexOf(key));
    });
    return { panes, paneOf, volume, hidden };
  }

  window.StockWatchTriggerChart = Object.freeze({
    PALETTE, colorAt, conditionsOf, stepsOf, align, holds, passing, windowOf, atOrBefore, panePlan
  });

  const page = document.querySelector('.ca-trigger-page');
  const host = document.querySelector('[data-ca-chart]');
  if (!page || !host || typeof LightweightCharts === 'undefined' || !window.StockWatchCharts) return;
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  const el = text.element;
  const charts = window.StockWatchCharts;
  const LWC = LightweightCharts;
  const status = document.querySelector('[data-ca-chart-status]');
  const legend = document.querySelector('[data-ca-chart-legend]');
  const evidence = JSON.parse(page.dataset.evidence || '{}');
  const results = new Map((evidence.conditions || []).map(item => [item.id, item]));
  const conditions = conditionsOf(evidence.definition);
  const steps = stepsOf(evidence.definition);
  const describeLayer = (condition, definitions) => steps.has(condition.id)
    ? `${i18n.t('ca.chart.step', steps.get(condition.id))} · ${text.describeStep(condition, definitions)}` : text.describe(condition, definitions);
  const signed = value => `${value >= 0 ? '+' : ''}${text.number(value)}%`;
  const narrow = () => window.matchMedia('(max-width: 640px)').matches;
  /** A value short enough to sit beside a candle near the chart's edge: volumes as 42.3M. */
  const short = value => Math.abs(value) >= 100000
    ? new Intl.NumberFormat(i18n.tag || 'en', { notation: 'compact', maximumFractionDigits: 1 }).format(value) : text.number(value);
  /** The page's own accent, which the trigger's line, label and legend key use too. */
  const accent = () => getComputedStyle(page).getPropertyValue('--primary-color').trim() || TRIGGER;

  /** A themed colour with transparency, kept out of the chart theme's own translation (which would raise it). */
  function fill(color, alpha) {
    const hex = charts.color(color);
    const rgba = `rgba(${[1, 3, 5].map(at => parseInt(hex.slice(at, at + 2), 16)).join(',')},${alpha})`;
    charts.preserveColors([rgba]);
    return rgba;
  }

  async function load() {
    status.textContent = i18n.t('ca.chart.loading');
    try {
      const [response, catalog] = await Promise.all([
        fetch(`/api/custom-alerts/triggers/${encodeURIComponent(page.dataset.triggerId)}/chart`,
          { credentials: 'same-origin', headers: { Accept: 'application/json' } }),
        text.catalog().catch(() => ({ byType: new Map() }))
      ]);
      if (!response.ok) throw new Error(String(response.status));
      const data = await response.json();
      if (!Array.isArray(data.candles) || data.candles.length < 2) {
        host.closest('.chart-tools-frame').hidden = true;
        status.textContent = i18n.t('ca.price.none');
        return;
      }
      status.textContent = '';
      draw(data, catalog.byType);
    } catch (error) {
      host.closest('.chart-tools-frame').hidden = true;
      status.textContent = i18n.t('ca.chart.failed');
    }
  }

  function draw(data, definitions) {
    const bars = data.candles.map(([time, open, high, low, close, volume]) => ({
      time: Number(time), open: Number(open), high: Number(high), low: Number(low), close: Number(close),
      volume: volume === null || volume === undefined ? NaN : Number(volume)
    }));
    const times = bars.map(bar => bar.time);
    const indexOf = new Map(times.map((time, index) => [time, index]));
    const trigger = Math.max(0, atOrBefore(times, Number(data.trigger)));
    const series = new Map((data.series || []).map(item => [item.id, item]));
    const views = data.conditions || {};
    const plan = panePlan(conditions, views, series);
    const index = seconds => {
      const at = atOrBefore(times, Number(seconds));
      return Number.isFinite(Number(seconds)) && Number(seconds) > 0 ? at : -1;
    };

    // Taller with each pane, so the price pane keeps its height.
    const small = narrow();
    const priceHeight = small ? 300 : 380;
    const paneHeight = pane => pane === 'volume' ? (small ? 80 : 96) : (small ? 112 : 136);
    host.style.height = `${priceHeight + plan.panes.slice(1).reduce((sum, pane) => sum + paneHeight(pane), 0)}px`;

    const chart = charts.createChart(host, {
      autoSize: true,
      layout: { background: { type: 'solid', color: 'transparent' } },
      crosshair: { mode: 0 },
      // Candles are narrow on a phone: more space after the last one keeps a centred marker label inside the chart.
      timeScale: { rightOffset: small ? 12 : 6, timeVisible: false },
      handleScroll: true,
      handleScale: true
    });
    const candles = chart.addCandlestickSeries({
      upColor: UP, downColor: DOWN, wickUpColor: UP, wickDownColor: DOWN, borderVisible: false
    });
    candles.setData(bars.map(({ time, open, high, low, close }) => ({ time, open, high, low, close })));

    let volumeSeries = null;
    if (plan.volume) {
      volumeSeries = chart.addHistogramSeries({
        color: 'rgba(138, 148, 166, 0.45)', priceFormat: { type: 'volume' }, lastValueVisible: false, priceLineVisible: false
      }, 1);
      volumeSeries.setData(bars.map(bar => Number.isFinite(bar.volume) ? { time: bar.time, value: bar.volume } : { time: bar.time }));
    }

    // ---------------------------------------------------------------- what each condition draws on

    const layers = conditions.map((condition, position) => ({
      condition, position, color: colorAt(position), result: results.get(condition.id),
      visible: true, series: [], lines: [], overlays: [], fills: [], uses: new Set(), note: null
    }));
    const markers = new Map();
    const addMarker = (target, marker, layer = null) => {
      if (!target) return;
      if (!markers.has(target)) markers.set(target, []);
      markers.get(target).push({ marker, layer });
    };

    // Indicator lines: drawn once, in the colour of the first condition that tests them.
    const lines = new Map();
    layers.forEach(layer => {
      if (layer.condition.kind !== 'VALUE') return;
      ['subject', 'compare'].forEach(role => {
        const id = views[layer.condition.id]?.[role];
        if (!id) return;
        layer.uses.add(id);
        const view = series.get(id);
        const pane = plan.paneOf.get(id);
        if (lines.has(id) || !view || view.error || pane === undefined || pane < 0) return;
        const options = {
          color: layer.color, lineWidth: 2, priceLineVisible: false, lastValueVisible: true, crosshairMarkerVisible: false,
          lineStyle: role === 'subject' ? LWC.LineStyle.Solid : LWC.LineStyle.Dashed
        };
        const line = view.style === 'HISTOGRAM'
          ? chart.addHistogramSeries({ color: layer.color, priceLineVisible: false, lastValueVisible: false }, pane)
          : chart.addLineSeries(options, pane);
        line.setData((view.points || []).filter(([time]) => indexOf.has(Number(time)))
          .map(([time, value]) => ({ time: Number(time), value: Number(value) })));
        // The indicator's own guide levels (RSI 30 and 70) stay faint, behind the rule's thresholds.
        if (pane > 0) (view.references || []).forEach(price => line.createPriceLine({
          price, color: MUTED, lineWidth: 1, lineStyle: LWC.LineStyle.Dotted, axisLabelVisible: false
        }));
        lines.set(id, { line, pane, values: align(view.points, indexOf, bars.length) });
      });
    });

    const field = name => bars.map(bar => name === 'VOLUME' ? bar.volume : bar[String(name || 'CLOSE').toLowerCase()]);
    const valuesOf = (operand, id) => {
      if (!operand) return null;
      if (operand.kind === 'VALUE') return bars.map(() => Number(operand.value));
      if (operand.kind === 'PRICE') return field(operand.field);
      return lines.get(id)?.values || align(series.get(id)?.points, indexOf, bars.length);
    };
    /** The chart series a value condition's subject is drawn on, and its pane. */
    const targetOf = (operand, id) => {
      if (operand?.kind === 'PRICE') return operand.field === 'VOLUME' ? { series: volumeSeries, pane: 1, candles: false }
        : { series: candles, pane: 0, candles: true };
      const line = lines.get(id);
      return line ? { series: line.line, pane: line.pane, candles: line.pane === 0, histogram: series.get(id)?.style === 'HISTOGRAM' } : null;
    };

    const overlay = document.createElement('div');
    overlay.className = 'signal-chart-annotation-layer ca-chart-overlay';
    overlay.setAttribute('aria-hidden', 'true');
    host.append(overlay);
    const placed = [];
    let bandRows = 0;
    let stripRows = 0;
    let firstPoint = Infinity;

    /** A bracket along the top of the chart over the candles a condition looked back over, labelled. */
    function band(layer, from, to, label) {
      if (from > to || to < 0) return;
      const node = el('div', `ca-chart-band ca-color-${layer.position % PALETTE.length}`);
      const tag = el('span', null, label);
      node.append(tag);
      overlay.append(node);
      const item = { node, tag, from: Math.max(0, from), to, kind: 'band', row: bandRows++ };
      placed.push(item);
      layer.overlays.push(item);
    }

    /** Runs of candles where a price condition held, as a slim strip under the candles (a line over them would read as price). */
    function strip(layer, passed) {
      const row = stripRows++;
      let start = -1;
      passed.concat(false).forEach((pass, at) => {
        if (pass && start < 0) start = at;
        if (pass || start < 0) return;
        const node = el('div', `ca-chart-strip ca-color-${layer.position % PALETTE.length}`);
        overlay.append(node);
        const item = { node, from: start, to: at - 1, kind: 'strip', row };
        placed.push(item);
        layer.overlays.push(item);
        start = -1;
      });
    }

    /** A frame around a pattern's candles, from their highest high to their lowest low. */
    function box(layer, from, to, label) {
      from = Math.max(0, from);
      if (to < from) return;
      const node = el('div', `ca-chart-box ca-color-${layer.position % PALETTE.length}`);
      node.append(el('span', null, label));
      overlay.append(node);
      const range = bars.slice(from, to + 1);
      const item = { node, from, to, kind: 'box', high: Math.max(...range.map(bar => bar.high)), low: Math.min(...range.map(bar => bar.low)) };
      placed.push(item);
      layer.overlays.push(item);
    }

    // The axis label already gives the level; a title beside it would repeat it. An indicator's pane always shows
    // the rule's levels (RSI 30 and 70); a price pane does not, so a distant level cannot flatten the candles.
    const levels = new Map();
    function threshold(layer, target, price) {
      if (!target?.series || !Number.isFinite(price)) return;
      layer.lines.push(target.series.createPriceLine({
        price, color: layer.color, lineWidth: 1, lineStyle: LWC.LineStyle.Dashed, axisLabelVisible: true, title: ''
      }));
      if (target.pane === 0 || target.series === volumeSeries) return;
      if (!levels.has(target.series)) {
        const wanted = [];
        levels.set(target.series, wanted);
        target.series.applyOptions({ autoscaleInfoProvider: original => {
          const range = original();
          if (!range?.priceRange) return range;
          return { ...range, priceRange: { minValue: Math.min(range.priceRange.minValue, ...wanted),
            maxValue: Math.max(range.priceRange.maxValue, ...wanted) } };
        } });
      }
      levels.get(target.series).push(price);
    }

    function drawValue(layer) {
      const { condition, result } = layer;
      const facts = result?.facts || {};
      const view = views[condition.id] || {};
      const subject = valuesOf(condition.subject, view.subject);
      const compare = condition.compare ? valuesOf(condition.compare, view.compare) : null;
      const target = targetOf(condition.subject, view.subject);
      if (!target?.series) {
        layer.note = i18n.t(plan.hidden.includes(view.subject) ? 'ca.chart.notDrawn' : 'ca.chart.lineUnavailable');
        return;
      }
      const operator = condition.operator;
      const fixed = condition.compare?.kind === 'VALUE' ? Number(condition.compare.value) : NaN;
      const matched = Math.max(0, index(facts.at) >= 0 ? index(facts.at) : trigger);
      threshold(layer, target, fixed);
      if (['BETWEEN', 'OUTSIDE'].includes(operator)) {
        threshold(layer, target, Number(condition.lower));
        threshold(layer, target, Number(condition.upper));
      }
      const data = subject.map((value, at) => Number.isFinite(value) ? { time: times[at], value } : { time: times[at] });
      if (Number.isFinite(fixed) && ['ABOVE', 'BELOW', 'CROSSES_ABOVE', 'CROSSES_BELOW'].includes(operator)) {
        // The side of the threshold that passes, shaded between the line and the threshold.
        const above = operator === 'ABOVE' || operator === 'CROSSES_ABOVE';
        const strength = target.candles ? 0.16 : 0.3;
        const shade = chart.addSeries(LWC.BaselineSeries, {
          baseValue: { type: 'price', price: fixed }, lineVisible: false, lineWidth: 1, priceLineVisible: false,
          lastValueVisible: false, crosshairMarkerVisible: false, autoscaleInfoProvider: () => null,
          topLineColor: 'rgba(0,0,0,0)', bottomLineColor: 'rgba(0,0,0,0)'
        }, target.pane);
        const paint = () => shade.applyOptions(above
          ? { topFillColor1: fill(layer.color, strength), topFillColor2: fill(layer.color, strength / 3),
            bottomFillColor1: 'rgba(0,0,0,0)', bottomFillColor2: 'rgba(0,0,0,0)' }
          : { bottomFillColor1: fill(layer.color, strength / 3), bottomFillColor2: fill(layer.color, strength),
            topFillColor1: 'rgba(0,0,0,0)', topFillColor2: 'rgba(0,0,0,0)' });
        paint();
        shade.setData(data);
        layer.series.push(shade);
        layer.fills.push(paint);
      } else {
        // Where the test passes, an indicator's line is traced in bold; prices and volume get a strip.
        const passed = passing(condition, subject, compare);
        if (passed.some(Boolean) && (target.series === candles || target.series === volumeSeries)) {
          strip(layer, passed);
        } else if (passed.some(Boolean) && target.histogram) {
          // A histogram's bars that passed take the condition's colour; the others fade.
          target.series.setData(data.map((point, at) => 'value' in point
            ? { ...point, color: passed[at] ? layer.color : 'rgba(138, 148, 166, 0.35)' } : point));
        } else if (passed.some(Boolean)) {
          const trace = chart.addLineSeries({
            color: layer.color, lineWidth: 3, priceLineVisible: false, lastValueVisible: false,
            crosshairMarkerVisible: false, autoscaleInfoProvider: () => null
          }, target.pane);
          trace.setData(data.map((point, at) => passed[at] && 'value' in point ? point : { time: point.time }));
          layer.series.push(trace);
        }
      }
      const inLine = position => target.candles ? position : 'inBar';
      if (operator.startsWith('CROSSES')) {
        subject.forEach((value, at) => {
          if (at !== matched && holds(condition, subject, compare, at)) {
            addMarker(target.series, { time: times[at], position: inLine(operator === 'CROSSES_ABOVE' ? 'belowBar' : 'aboveBar'),
              color: layer.color, shape: 'circle', size: 0.6 }, layer);
          }
        });
      }
      const count = Number(condition.candles) || 1;
      if (operator.startsWith('CHANGE') && matched - count >= 0) {
        const bracket = chart.addLineSeries({
          color: layer.color, lineWidth: 2, lineStyle: LWC.LineStyle.Dashed, priceLineVisible: false, lastValueVisible: false,
          crosshairMarkerVisible: false, pointMarkersVisible: true
        }, target.pane);
        bracket.setData([matched - count, matched].filter(at => Number.isFinite(subject[at]))
          .map(at => ({ time: times[at], value: subject[at] })));
        layer.series.push(bracket);
      }
      if (operator.startsWith('NEW_') && matched - count >= 0) {
        const earlier = subject.slice(matched - count, matched).filter(Number.isFinite);
        if (earlier.length) {
          const level = operator === 'NEW_HIGH' ? Math.max(...earlier) : Math.min(...earlier);
          const ceiling = chart.addLineSeries({
            color: layer.color, lineWidth: 1, lineStyle: LWC.LineStyle.Dashed, priceLineVisible: false, lastValueVisible: false,
            crosshairMarkerVisible: false
          }, target.pane);
          ceiling.setData(times.slice(matched - count, matched + 1).map(time => ({ time, value: level })));
          layer.series.push(ceiling);
        }
      }
      // The candle that decided it, with the value the evidence recorded.
      let caption = '';
      if (operator.startsWith('CHANGE') && facts.change != null) caption = i18n.plural('ca.chart.change', count, signed(facts.change), count);
      else if (operator.startsWith('NEW_')) caption = i18n.plural(`ca.chart.${operator === 'NEW_HIGH' ? 'newHigh' : 'newLow'}`, count, count);
      else if (facts.value != null && (!target.candles || matched !== trigger)) caption = short(Number(facts.value));
      if (caption || !target.candles) {
        addMarker(target.series, { time: times[matched], position: 'aboveBar', color: layer.color, shape: 'circle',
          size: target.candles ? 0.8 : 1, text: caption }, layer);
      }
      if (stepBand(layer)) return;
      const [first, last] = windowOf(condition, trigger);
      if (last > first) band(layer, first, last, i18n.plural('ca.chart.window', last - first + 1, last - first + 1));
    }

    /** A step of a sequence is bracketed over the candles it held on, labelled with its number; false for other conditions. */
    function stepBand(layer) {
      const number = steps.get(layer.condition.id);
      if (!number) return false;
      const facts = layer.result?.facts || {};
      if (facts.from && facts.to) band(layer, index(facts.from), index(facts.to), i18n.t('ca.chart.step', number));
      return true;
    }

    function drawPattern(layer) {
      const { condition, result } = layer;
      const facts = result?.facts || {};
      const view = views[condition.id] || {};
      const [first, last] = windowOf(condition, trigger);
      if (!facts.pattern) {
        if (!steps.has(condition.id)) band(layer, first, last, i18n.t('ca.chart.noneFound'));
        return;
      }
      if (!stepBand(layer) && last > first) band(layer, first, last, i18n.plural('ca.chart.window', last - first + 1, last - first + 1));
      const name = text.patternName(condition.family, facts.type);
      const bullish = facts.bullish !== false;
      const anchor = index(facts.anchorAt);
      const detected = index(facts.detectedAt);
      if (condition.family === 'CANDLESTICK') {
        if (anchor >= 0) box(layer, anchor - (view.formationCandles || 1) + 1, anchor, name);
        if (detected >= 0 && detected !== anchor) {
          addMarker(candles, { time: times[detected], position: bullish ? 'belowBar' : 'aboveBar', color: layer.color,
            shape: bullish ? 'arrowUp' : 'arrowDown', text: i18n.t('ca.chart.recognised') }, layer);
        }
      } else {
        const points = (view.points || []).filter(point => indexOf.has(Number(point.time)))
          .sort((a, b) => a.time - b.time).filter((point, at, all) => at === 0 || point.time !== all[at - 1].time);
        if (points.length >= 2) {
          firstPoint = Math.min(firstPoint, indexOf.get(Number(points[0].time)));
          const path = chart.addLineSeries({
            color: layer.color, lineWidth: 2, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
            lineStyle: condition.family === 'HARMONIC_FORMATION' ? LWC.LineStyle.Dashed : LWC.LineStyle.Solid
          }, 0);
          path.setData(points.map(point => ({ time: Number(point.time), value: Number(point.price) })));
          path.setMarkers(points.filter(point => point.label).map(point => ({
            time: Number(point.time), position: point.pivot === 'HIGH' ? 'aboveBar' : 'belowBar', color: layer.color,
            shape: 'circle', size: 0.6, text: point.label
          })));
          layer.series.push(path);
        }
        if (detected >= 0) {
          addMarker(candles, { time: times[detected], position: bullish ? 'belowBar' : 'aboveBar', color: layer.color,
            shape: bullish ? 'arrowUp' : 'arrowDown', text: name }, layer);
        } else if (anchor >= 0) {
          addMarker(candles, { time: times[anchor], position: bullish ? 'belowBar' : 'aboveBar', color: layer.color,
            shape: 'circle', text: name }, layer);
        }
      }
      const confirmed = index(facts.confirmedAt);
      if (confirmed >= 0) {
        addMarker(candles, { time: times[confirmed], position: bullish ? 'aboveBar' : 'belowBar', color: layer.color,
          shape: 'square', size: 0.8, text: i18n.t('ca.chart.targetReached') }, layer);
      }
    }

    function drawOutlook(layer) {
      const facts = layer.result?.facts || {};
      if (facts.unavailable || !facts.classification) {
        layer.note = i18n.t('ca.evidence.outlookUnavailable');
        return;
      }
      addMarker(candles, { time: times[trigger], position: 'aboveBar', color: layer.color, shape: 'square', size: 0.8,
        text: i18n.t('ca.chart.outlook', `${facts.classification} · ${text.number(facts.score)}%`) }, layer);
    }

    function drawFilings(layer) {
      const { condition } = layer;
      const facts = layer.result?.facts || {};
      if (facts.notApplicable) {
        layer.note = i18n.t('ca.evidence.notApplicable');
        return;
      }
      if (facts.from && facts.to) {
        const from = Date.parse(`${facts.from}T00:00:00Z`) / 1000;
        const to = Date.parse(`${facts.to}T23:59:59Z`) / 1000;
        band(layer, Math.max(0, atOrBefore(times, from)), Math.min(trigger, atOrBefore(times, to)),
          i18n.plural('ca.chart.days', condition.days, condition.days));
      }
      const byCandle = new Map();
      (views[condition.id]?.filings || []).forEach(filing => {
        const at = atOrBefore(times, Date.parse(`${filing.date}T23:59:59Z`) / 1000);
        if (at < 0) return;
        if (!byCandle.has(at)) byCandle.set(at, []);
        byCandle.get(at).push(filing);
      });
      const purchase = condition.transaction === 'PURCHASE';
      const name = i18n.t(`ca.chart.filing.${condition.kind}.${purchase ? 'PURCHASE' : 'SALE'}`);
      byCandle.forEach((filings, at) => addMarker(candles, {
        time: times[at], position: purchase ? 'belowBar' : 'aboveBar', color: layer.color, shape: purchase ? 'arrowUp' : 'arrowDown',
        text: filings.length > 1 ? `${name} ×${filings.length}` : `${name}${filings[0].amount ? ` ${filings[0].amount}` : ''}`
      }, layer));
    }

    layers.forEach(layer => {
      switch (layer.condition.kind) {
        case 'VALUE': drawValue(layer); break;
        case 'PATTERN': drawPattern(layer); break;
        case 'OUTLOOK': drawOutlook(layer); break;
        default: drawFilings(layer);
      }
    });

    // ---------------------------------------------------------------- the trigger, what followed, other triggers

    // The trigger's label sits on the time axis, where it cannot cover a pattern's or a filing's label.
    const triggerMarker = { time: times[trigger], position: 'belowBar', color: accent(), shape: 'arrowUp' };
    charts.preserveColors([triggerMarker.color]);
    addMarker(candles, triggerMarker);
    const returns = JSON.parse(document.querySelector('[data-ca-next]')?.dataset.returns || '{}');
    let forward = 0;
    FORWARD.forEach(count => {
      const value = returns[String(count)];
      if (trigger + count >= bars.length || value === undefined || value === null) return;
      forward++;
      addMarker(candles, { time: times[trigger + count], position: 'belowBar', color: value >= 0 ? UP : DOWN, shape: 'circle',
        size: 0.5, text: i18n.t('ca.chart.forward', count, signed(Number(value))) });
    });
    const others = new Map((data.triggers || []).map(other => [`trigger-${other.id}`, other]));
    others.forEach((other, id) => {
      if (indexOf.has(Number(other.candle))) {
        addMarker(candles, { id, time: Number(other.candle), position: 'aboveBar', color: MUTED, shape: 'circle', size: 0.7 });
      }
    });
    // A dot is a small target: a click just above that candle's high opens the trigger too.
    chart.subscribeClick(param => {
      let other = others.get(param?.hoveredObjectId);
      if (!other && param?.point && param.time !== undefined && (param.paneIndex ?? 0) === 0) {
        const candidate = [...others.values()].find(item => Number(item.candle) === Number(param.time));
        const top = candidate ? candles.priceToCoordinate(bars[indexOf.get(Number(candidate.candle))].high) : null;
        if (top !== null && param.point.y <= top && param.point.y >= top - 32) other = candidate;
      }
      if (other) window.location.assign(`/custom-alerts/triggers/${other.id}${window.location.search}`);
    });
    if (trigger < bars.length - 1) {
      const after = el('div', 'ca-chart-after');
      overlay.append(after);
      placed.push({ node: after, from: trigger + 1, to: bars.length - 1, kind: 'after' });
    }
    const line = el('div', 'ca-chart-trigger-line');
    const tag = el('div', 'ca-chart-trigger-tag', i18n.t('ca.chart.triggered'));
    overlay.append(line, tag);
    placed.push({ node: line, from: trigger, to: trigger, kind: 'line' }, { node: tag, from: trigger, to: trigger, kind: 'tag' });

    function applyMarkers() {
      markers.forEach((list, target) => target.setMarkers(list.filter(item => !item.layer || item.layer.visible).map(item => item.marker)));
    }
    applyMarkers();
    // Every pane exists once the lines are added: each gets its share of the height.
    chart.panes().forEach((pane, at) => pane.setStretchFactor(at === 0 ? priceHeight : paneHeight(plan.panes[at])));
    // Room above the candles for the window brackets and below them for the strips, so marker labels stay clear.
    candles.priceScale().applyOptions({ scaleMargins: {
      top: Math.min(0.4, 0.12 + (Math.min(bandRows, 4) * 24 + 8) / priceHeight),
      bottom: Math.min(0.25, 0.08 + (stripRows * 7 + 6) / priceHeight)
    } });

    // ---------------------------------------------------------------- overlays follow the chart

    let frame = 0;
    function layout() {
      frame = 0;
      const scale = chart.timeScale();
      const width = scale.width();
      const height = host.clientHeight - scale.height();
      const priceBottom = chart.panes()[0]?.getHeight?.() ?? height;
      const step = Math.abs((scale.logicalToCoordinate(1) ?? 0) - (scale.logicalToCoordinate(0) ?? 0)) || 6;
      placed.forEach(item => {
        const left = scale.logicalToCoordinate(item.from);
        const right = scale.logicalToCoordinate(item.to);
        const layerHidden = item.node.dataset.off === 'true';
        if (left === null || right === null || layerHidden) { item.node.hidden = true; return; }
        const start = Math.max(0, left - step / 2);
        const end = Math.min(width, right + step / 2);
        const point = item.kind === 'line' || item.kind === 'tag';
        if (end <= 0 || start >= width || (point && (left < 0 || left > width)) || (!point && end - start < 1)) {
          item.node.hidden = true;
          return;
        }
        item.node.hidden = false;
        if (item.kind === 'line') {
          item.node.style.left = `${left}px`;
          item.node.style.height = `${height}px`;
          return;
        }
        if (item.kind === 'tag') {
          const size = item.node.offsetWidth;
          item.node.style.left = `${Math.max(0, Math.min(width - size, left - size / 2))}px`;
          item.node.style.top = `${height + 4}px`;
          return;
        }
        item.node.style.left = `${start}px`;
        item.node.style.width = `${end - start}px`;
        if (item.kind === 'box') {
          const top = candles.priceToCoordinate(item.high);
          const bottom = candles.priceToCoordinate(item.low);
          if (top === null || bottom === null) { item.node.hidden = true; return; }
          item.node.style.top = `${top - 5}px`;
          item.node.style.height = `${Math.max(10, bottom - top + 10)}px`;
        } else if (item.kind === 'band') {
          item.node.style.top = `${6 + item.row * 24}px`;
          // A label wider than its bracket runs to the left when it would leave the chart.
          item.tag.classList.toggle('to-left', start + item.tag.offsetWidth + 8 > width);
        } else if (item.kind === 'strip') {
          item.node.style.top = `${priceBottom - 9 - item.row * 7}px`;
        } else {
          item.node.style.top = '0px';
          item.node.style.height = `${height}px`;
        }
      });
    }
    const schedule = () => { if (!frame) frame = window.requestAnimationFrame(layout); };
    chart.timeScale().subscribeVisibleLogicalRangeChange(schedule);
    chart.timeScale().subscribeSizeChange(schedule);
    // A price pane rescaled by dragging its axis moves the pattern frames too.
    chart.subscribeCrosshairMove(schedule);

    // Open on the trigger with what led to it: the earliest window, pattern or wave drawn, and some candles after.
    // On a phone longer windows run off the left edge instead of squeezing the candles.
    const wanted = Math.min(trigger - (small ? 35 : 60), firstPoint - 4,
      ...placed.filter(item => item.kind === 'band' || item.kind === 'box').map(item => item.from - 4));
    const earliest = small ? Math.max(wanted, trigger - 40) : wanted;
    chart.timeScale().setVisibleLogicalRange({
      from: Math.max(-0.5, earliest), to: Math.min(bars.length - 1, trigger + (small ? 12 : 25)) + (small ? 9 : 3)
    });

    // ---------------------------------------------------------------- legend

    function setVisible(layer, visible) {
      layer.visible = visible;
      layer.series.forEach(item => item.applyOptions({ visible }));
      layer.lines.forEach(item => item.applyOptions({ lineVisible: visible, axisLabelVisible: visible }));
      layer.overlays.forEach(item => { item.node.dataset.off = String(!visible); });
      layer.uses.forEach(id => lines.get(id)?.line.applyOptions({
        visible: layers.some(other => other.visible && other.uses.has(id))
      }));
      applyMarkers();
      schedule();
    }

    const chips = layers.map(layer => {
      const met = Boolean(layer.result?.passed);
      const chip = el('button', `ca-chart-chip ca-color-${layer.position % PALETTE.length}${met ? ' is-met' : ''}`);
      chip.type = 'button';
      chip.setAttribute('aria-pressed', 'true');
      chip.append(el('i', 'ca-swatch'), el('span', 'ca-chart-state', met ? '✓' : '–'),
        el('span', null, describeLayer(layer.condition, definitions)));
      if (layer.note) chip.append(el('small', null, layer.note));
      chip.title = i18n.t('ca.chart.toggle');
      chip.setAttribute('aria-label', `${i18n.t(met ? 'ca.readout.met' : 'ca.readout.unmet')}: ${describeLayer(layer.condition, definitions)}`);
      chip.addEventListener('click', () => {
        const visible = chip.getAttribute('aria-pressed') !== 'true';
        chip.setAttribute('aria-pressed', String(visible));
        setVisible(layer, visible);
      });
      // A condition that did not decide the trigger (in an "any" group) is listed but starts hidden.
      if (!met) {
        chip.setAttribute('aria-pressed', 'false');
        setVisible(layer, false);
      }
      return chip;
    });
    const keys = [el('span', 'ca-chart-key trigger', i18n.t('ca.chart.triggered'))];
    if (trigger < bars.length - 1) keys.push(el('span', 'ca-chart-key after', i18n.t('ca.chart.after')));
    if (forward) keys.push(el('span', 'ca-chart-key forward', i18n.t('ca.chart.forwardKey')));
    if ([...others.values()].some(other => indexOf.has(Number(other.candle)))) keys.push(el('span', 'ca-chart-key other', i18n.t('ca.chart.others')));
    legend.replaceChildren(...chips, ...keys);

    window.addEventListener('stockwatch:themechange', () => {
      layers.forEach(layer => layer.fills.forEach(paint => paint()));
      // The page's accent follows the theme through CSS; the chart reads it again.
      window.requestAnimationFrame(() => {
        triggerMarker.color = accent();
        charts.preserveColors([triggerMarker.color]);
        applyMarkers();
      });
      schedule();
    });

    window.StockWatchChartTools?.attach({ chart, series: candles, container: host, symbol: page.dataset.symbol, interval: data.interval });
    layout();
  }

  load();
})();
