(() => {
  'use strict';
  // Custom alerts in plain words: every page that shows a rule (the list, the builder, a trigger, a
  // watchlist, the stock page) describes it with these functions, in the reader's language. The rule is
  // the JSON the server stores (CustomAlertDefinition).
  if (window.StockWatchCustomAlerts) return;
  const i18n = window.StockWatchI18n;
  let catalogRequest = null;

  const has = key => i18n.has(key);
  const formatters = new Map();
  const formatter = options => {
    const key = JSON.stringify(options);
    if (!formatters.has(key)) formatters.set(key, new Intl.NumberFormat(i18n.tag || 'en', options));
    return formatters.get(key);
  };
  /**
   * A number as typed: no trailing zeros, two decimals at most (four below 1), local separators. Large figures
   * (volumes, share counts) read as 1.5M or 25,000 instead of a run of digits.
   */
  const number = value => {
    const n = Number(value);
    if (!Number.isFinite(n)) return '';
    if (Math.abs(n) >= 1e6) return formatter({ notation: 'compact', maximumFractionDigits: 1 }).format(n);
    if (Math.abs(n) >= 10000) return formatter({ maximumFractionDigits: 2 }).format(n);
    if (Number.isInteger(n)) return i18n.number(n, 0);
    const digits = Math.abs(n) >= 1 ? 2 : 4;
    return i18n.number(n, digits).replace(/([.,]\d*?)0+$/, '$1').replace(/[.,]$/, '');
  };
  // Dollar amounts get thousands separators in both languages ($250,000 / 250 000 $ is not used: the data is in USD).
  const money = value => '$' + formatter({ maximumFractionDigits: 0 }).format(Number(value) || 0);
  /** A change in percent with its sign: "+2.1%", "−0.9%". */
  const signed = value => `${Number(value) >= 0 ? '+' : '−'}${number(Math.abs(Number(value)))}%`;

  /** One date format on every custom alert page: "Nov 7, 2026" (the year left out for this year when {@code short}). */
  const date = (millis, short = false) => {
    const value = new Date(millis);
    const thisYear = value.getFullYear() === new Date().getFullYear();
    return i18n.date(value, short && thisYear ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' });
  };
  /** A date with its time: "Nov 7, 2026 · 15:11". */
  const dateTime = millis => `${date(millis)} · ${i18n.date(new Date(millis), { hour: '2-digit', minute: '2-digit' })}`;
  /** A recent day in words ("Today", "Yesterday", "3 days ago"), older ones as a date. */
  const relativeDay = millis => {
    const startOf = value => { const day = new Date(value); day.setHours(0, 0, 0, 0); return day.getTime(); };
    const days = Math.round((startOf(Date.now()) - startOf(millis)) / 86400000);
    if (days < 0 || days > 6) return date(millis, true);
    const words = new Intl.RelativeTimeFormat(i18n.tag || 'en', { numeric: 'auto' }).format(-days, 'day');
    return words.charAt(0).toLocaleUpperCase(i18n.tag || 'en') + words.slice(1);
  };

  // Line icons (24-unit grid, drawn with the text colour) shared by the custom alert pages.
  const ICONS = {
    indicator: 'M3 17l5-5 4 3 9-9M15 6h6v6',
    price: 'M3 17l5-5 4 3 9-9M15 6h6v6',
    pattern: 'M7 3v3M7 17v4M5 6h4v11H5zM17 3v5M17 15v6M15 8h4v7h-4z',
    outlook: 'M4 16a8 8 0 0 1 16 0M12 16l4-5M3 20h18',
    insider: 'M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0',
    congress: 'M3 9h18L12 3zM5 9v9M9.5 9v9M14.5 9v9M19 9v9M3 21h18',
    more: 'M5 12h.01M12 12h.01M19 12h.01',
    close: 'M6 6l12 12M18 6L6 18',
    up: 'M12 19V5M6 11l6-6 6 6',
    down: 'M12 5v14M6 13l6 6 6-6',
    copy: 'M9 9h11v11H9zM5 15H4V4h11v1',
    trash: 'M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3',
    mail: 'M3 6h18v12H3zM3 7l9 7 9-7',
    pin: 'M9 3h6l-1 6 4 4H6l4-4zM12 13v8',
    chevron: 'M6 9l6 6 6-6',
    search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4',
    check: 'M5 12l5 5 9-11',
    reverse: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM5.6 5.6l12.8 12.8',
    sequence: 'M5 6h6M5 12h10M5 18h14',
    arrow: 'M5 12h14M13 6l6 6-6 6',
    plus: 'M12 5v14M5 12h14',
    play: 'M7 5l12 7-12 7z',
    grip: 'M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01',
    edit: 'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
    undo: 'M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3',
    info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5M12 8h.01'
  };
  /** An inline SVG icon; decorative unless given a label. */
  function icon(name, className = 'ca-icon') {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', className);
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    const path = document.createElementNS(svg.namespaceURI, 'path');
    path.setAttribute('d', ICONS[name] || ICONS.indicator);
    svg.append(path);
    return svg;
  }
  /** The icon naming a condition's kind: an indicator or price line, a pattern, the outlook, insiders or Congress. */
  const conditionIcon = condition => condition.kind === 'VALUE' ? (condition.subject?.kind === 'PRICE' ? 'price' : 'indicator')
    : condition.kind === 'PATTERN' ? 'pattern' : condition.kind === 'OUTLOOK' ? 'outlook' : condition.kind === 'CONGRESS' ? 'congress' : 'insider';

  function catalog() {
    if (!catalogRequest) {
      catalogRequest = fetch('/api/custom-alerts/catalog', { credentials: 'same-origin' })
        .then(response => {
          if (!response.ok) throw new Error(i18n.t('ca.catalogFailed'));
          return response.json();
        })
        .then(body => {
          body.byType = new Map(body.indicators.map(item => [item.type, item]));
          return body;
        })
        .catch(error => { catalogRequest = null; throw error; });
    }
    return catalogRequest;
  }

  const indicatorName = type => has(`ind.${type}.name`) ? i18n.t(`ind.${type}.name`) : String(type).replace(/_/g, ' ');
  const indicatorShort = (type, definition) => has(`ind.${type}.short`) ? i18n.t(`ind.${type}.short`) : (definition?.shortName || indicatorName(type));
  const inputLabel = (type, key) => has(`ind.${type}.input.${key}`) ? i18n.t(`ind.${type}.input.${key}`)
    : has(`ind.input.${key}`) ? i18n.t(`ind.input.${key}`) : key;
  const outputLabel = (type, key) => has(`ind.${type}.output.${key}`) ? i18n.t(`ind.${type}.output.${key}`)
    : has(`ind.output.${key}`) ? i18n.t(`ind.output.${key}`) : key;
  const choiceLabel = (set, value) => has(`ind.choice.${set}.${value}`) ? i18n.t(`ind.choice.${set}.${value}`) : value;
  const intervalLabel = interval => i18n.t(`interval.${interval}`);
  /** An alert's intervals in one phrase: "Daily", "Daily and weekly", "Daily, weekly and monthly". */
  const intervalsLabel = intervals => {
    const labels = (intervals?.length ? intervals : ['DAILY']).map((interval, index) =>
      index ? intervalLabel(interval).toLowerCase() : intervalLabel(interval));
    return labels.length < 2 ? labels.join('') : `${labels.slice(0, -1).join(', ')} ${i18n.t('ca.summary.and')} ${labels.at(-1)}`;
  };

  /** "RSI (14)", "MACD histogram (12, 26, 9)", "SMA (30, weekly)". */
  function operandLabel(operand, definitions) {
    if (!operand) return '';
    if (operand.kind === 'VALUE') return number(operand.value);
    if (operand.kind === 'PRICE') return i18n.t(`ca.price.${operand.field || 'CLOSE'}`);
    const definition = definitions?.get?.(operand.type);
    const parameters = operand.parameters || {};
    // The main length is always shown (RSI (14)); other numbers only when changed. Zone levels (RSI 30/70)
    // only shade the chart and never change the line, so they are left out.
    const zones = new Set([definition?.zones?.upperInput, definition?.zones?.lowerInput, 'upper', 'lower', 'offset']);
    const length = definition?.inputs?.find(input => input.kind === 'INTEGER');
    const shown = [];
    if (length) shown.push(parameters[length.key] ?? length.defaultValue);
    if (definition) {
      // Saved rules carry every setting, so only the ones changed from the default are worth naming.
      const defaults = new Map(definition.inputs.map(input => [input.key, input.defaultValue]));
      Object.entries(parameters)
        .filter(([key, value]) => typeof value === 'number' && !zones.has(key) && key !== length?.key && value !== defaults.get(key))
        .forEach(([, value]) => shown.push(value));
    } else {
      // Without the catalog (compact lists) only the first setting, usually the length, is named.
      const first = Object.entries(parameters).find(([key, value]) => typeof value === 'number' && !zones.has(key));
      if (first) shown.push(first[1]);
    }
    const numbers = shown.filter(value => typeof value === 'number').slice(0, 4).map(value => number(value));
    const timeframe = parameters.timeframe;
    if (timeframe && timeframe !== 'chart') numbers.push(i18n.t(timeframe === '1wk' ? 'ca.timeframe.weekly' : 'ca.timeframe.monthly'));
    const base = indicatorShort(operand.type, definition);
    // The main line ("value") needs no name; other lines do (MACD › Signal, RSI › Moving average).
    const main = !operand.output || operand.output === 'value' || operand.output === String(operand.type).toLowerCase()
      || (definition?.outputs?.length === 1);
    // Another line is named after the indicator in words: "MACD histogram", "RSI moving average".
    const lineName = main ? '' : outputLabel(operand.type, operand.output);
    const line = main ? '' : ` ${/^\p{Lu}\p{Ll}/u.test(lineName) ? lineName.charAt(0).toLocaleLowerCase(i18n.tag || 'en') + lineName.slice(1) : lineName}`;
    return `${base}${line}${numbers.length ? ` (${numbers.join(', ')})` : ''}`;
  }

  function patternName(family, name) {
    if (family === 'ELLIOTT_WAVE') {
      if (name === 'CORRECTION_END') return i18n.t('ca.elliott.CORRECTION_END');
      return has(`glossary.elliott.${name}.term`) ? i18n.t(`glossary.elliott.${name}.term`) : name;
    }
    const key = family === 'HARMONIC_FORMATION' ? `glossary.pattern.HARMONIC_${name}.term` : `glossary.pattern.${name}.term`;
    return has(key) ? i18n.t(key) : String(name).replace(/_/g, ' ').toLowerCase();
  }

  /** The glossary entry explaining a pattern name, for learning mode. */
  function patternTerm(family, name) {
    if (family === 'ELLIOTT_WAVE') return name === 'CORRECTION_END' ? 'elliott.CORRECTION' : `elliott.${name}`;
    return family === 'HARMONIC_FORMATION' ? `pattern.HARMONIC_${name}` : `pattern.${name}`;
  }

  function valueSentence(condition, definitions) {
    const subject = operandLabel(condition.subject, definitions);
    const op = condition.operator;
    let text;
    switch (op) {
      case 'ABOVE': case 'BELOW': case 'CROSSES_ABOVE': case 'CROSSES_BELOW':
        text = i18n.t(`ca.op.${op}`, subject, operandLabel(condition.compare, definitions)); break;
      case 'BETWEEN': case 'OUTSIDE':
        text = i18n.t(`ca.op.${op}`, subject, number(condition.lower), number(condition.upper)); break;
      case 'RISING': case 'FALLING': case 'NEW_HIGH': case 'NEW_LOW':
        text = i18n.t(`ca.op.${op}`, subject, condition.candles); break;
      case 'CHANGE_ABOVE': case 'CHANGE_BELOW':
        text = i18n.t(`ca.op.${op}`, subject, number(condition.percent), condition.candles); break;
      default: text = subject;
    }
    const window = condition.window || { mode: 'LATEST' };
    if (window.mode === 'ANY' && window.candles > 1) return i18n.t('ca.window.ANY', text, window.candles);
    if (window.mode === 'EVERY') return i18n.t('ca.window.EVERY', text, window.candles);
    return text;
  }

  function patternSentence(condition, step = false) {
    const direction = condition.direction || 'ANY';
    const confirmed = condition.family === 'CANDLESTICK' && condition.status === 'CONFIRMED';
    const family = i18n.t(`ca.pattern.family.${condition.family}.${direction}`);
    // A step of a sequence counts on one candle, so it has no window of its own.
    const text = step ? i18n.t(confirmed ? 'ca.pattern.stepConfirmed' : 'ca.pattern.stepDetected', family)
      : i18n.t(confirmed ? 'ca.pattern.confirmedIn' : 'ca.pattern.detected', family, condition.window?.candles || 1);
    return text + patternDetails(condition);
  }

  /** Which patterns, how good and still valid, in brackets after the pattern ("" when none). */
  function patternDetails(condition) {
    const details = [];
    if (condition.patterns?.length) details.push(condition.patterns.map(name => patternName(condition.family, name)).join(', '));
    if (condition.minScore != null) details.push(i18n.t('ca.pattern.minScore', condition.minScore));
    // Triggers keep the rule as it was; before the status existed, confirmation was this extra test.
    if (condition.confirmedOnly) details.push(i18n.t('ca.pattern.confirmed'));
    if (condition.stillValid) details.push(i18n.t('ca.pattern.stillValid'));
    return details.length ? ` (${details.join('; ')})` : '';
  }

  function activitySentence(condition) {
    return i18n.t(`ca.activity.${condition.kind}.${condition.transaction}`, condition.days) + activityDetails(condition);
  }

  /** How many people, how much and who, in brackets after the trades ("" when none). */
  function activityDetails(condition) {
    const insider = condition.kind === 'INSIDER';
    const details = [];
    if (condition.minCount > 1) details.push(i18n.t('ca.activity.minCount', condition.minCount));
    if (condition.minTotal) details.push(i18n.t('ca.activity.minTotal', money(condition.minTotal)));
    const list = insider ? condition.roles : condition.chambers;
    if (list?.length && (insider || list.length < 2)) details.push(list.map(item => i18n.t(`ca.activity.${insider ? 'role' : 'chamber'}.${item}`)).join(', '));
    return details.length ? ` (${details.join('; ')})` : '';
  }

  /**
   * A condition in two parts for a chip: what is tested ("RSI (14) is above 30"), and over which candles or days
   * ("within the last 10 candles", null for the latest candle), which the chip shows quieter.
   */
  function chipParts(condition, definitions) {
    const window = condition.window || { mode: 'LATEST' };
    const candles = window.candles || 1;
    switch (condition.kind) {
      case 'VALUE': {
        const main = valueSentence({ ...condition, window: { mode: 'LATEST' } }, definitions);
        if (window.mode === 'ANY' && candles > 1) return [main, i18n.plural('ca.when.within', candles, candles)];
        if (window.mode === 'EVERY' && candles > 1) return [main, i18n.t('ca.when.every', candles)];
        return [main, null];
      }
      case 'PATTERN': {
        const confirmed = condition.family === 'CANDLESTICK' && condition.status === 'CONFIRMED';
        const family = i18n.t(`ca.pattern.family.${condition.family}.${condition.direction || 'ANY'}`);
        return [family + patternDetails(condition), i18n.plural(confirmed ? 'ca.when.confirmed' : 'ca.when.within', candles, candles)];
      }
      case 'INSIDER': case 'CONGRESS':
        return [i18n.t(`ca.activity.short.${condition.kind}.${condition.transaction}`) + activityDetails(condition), i18n.plural('ca.when.days', condition.days, condition.days)];
      default: return [describe(condition, definitions), null];
    }
  }

  /** One condition as a sentence; "Not true: …" when negated. */
  function describe(condition, definitions) {
    let text;
    switch (condition.kind) {
      case 'VALUE': text = valueSentence(condition, definitions); break;
      case 'PATTERN': text = patternSentence(condition); break;
      case 'OUTLOOK': text = i18n.t(`ca.outlook.${condition.direction}.${condition.strength}`); break;
      case 'INSIDER': case 'CONGRESS': text = activitySentence(condition); break;
      default: text = '';
    }
    return condition.negate ? i18n.t('ca.not', text) : text;
  }

  /** The condition of a sequence step, read on one candle (the step's own timing is said elsewhere). */
  function stepCondition(condition, definitions) {
    return condition.kind === 'PATTERN' ? patternSentence(condition, true) : describe({ ...condition, window: { mode: 'LATEST' } }, definitions);
  }

  /** How many candles in a row a step must hold: only indicator and price tests that do not count candles themselves. */
  const stepCandles = condition => condition.kind === 'VALUE' && !['CROSSES_ABOVE', 'CROSSES_BELOW', 'RISING', 'FALLING'].includes(condition.operator)
    ? condition.step?.candles || 1 : 1;

  /**
   * One step of a sequence ("in order" group): "RSI (14) is below 30 for at least 5 candles in a row". A reversed step
   * says "on each of", so the reversal reads as holding candle by candle. In a sentence the step starts in lower case.
   */
  function describeStep(condition, definitions, inSentence = false) {
    let text = stepCondition({ ...condition, negate: false }, definitions);
    if (inSentence) text = lowerFirst(condition, text);
    if (condition.negate) text = i18n.t(inSentence ? 'ca.summary.not' : 'ca.not', text);
    const candles = stepCandles(condition);
    return candles > 1 ? i18n.t(condition.negate ? 'ca.step.inARowEach' : 'ca.step.inARow', text, candles) : text;
  }

  /** The span a sequence gets when none is saved: every step's full allowance, at least 30 and at most 120 candles. */
  function defaultSpan(group) {
    const steps = (group.conditions || []).map((condition, index) => ({ candles: stepCandles(condition), within: index ? condition.step?.within ?? 5 : 0 }));
    return Math.min(120, Math.max(30, steps.reduce((sum, step) => sum + step.candles + step.within, 0)));
  }

  /**
   * Mid-sentence, sentences written by us (patterns, filings, outlook, prices) start with an ordinary word and are
   * lowercased; indicator names (RSI, Bollinger Bands) keep their capitals.
   */
  function lowerFirst(condition, text) {
    return (condition.kind !== 'VALUE' || condition.subject?.kind === 'PRICE') && /^\p{Lu}[\p{Ll}\s]/u.test(text)
      ? text.charAt(0).toLocaleLowerCase(i18n.tag || 'en') + text.slice(1) : text;
  }

  /** A condition inside the summary sentence. */
  function phrase(condition, definitions) {
    const text = lowerFirst(condition, describe({ ...condition, negate: false }, definitions));
    return condition.negate ? i18n.t('ca.summary.not', text) : text;
  }

  /** A sequence in the summary: "A for at least 5 candles in a row, then within 3 candles B, all within 20 candles". */
  function sequencePhrase(group, definitions) {
    const steps = (group.conditions || []).map((condition, index) => {
      const text = describeStep(condition, definitions, true);
      if (!index) return text;
      const within = condition.step?.within ?? 5;
      return `${within === 1 ? i18n.t('ca.summary.thenNext') : i18n.t('ca.summary.thenWithin', within)} ${text}`;
    });
    const span = group.span ?? defaultSpan(group);
    const recent = group.window?.mode === 'ANY' && group.window.candles > 1 ? group.window.candles : null;
    return `${steps.join(', ')}, ${recent ? i18n.t('ca.summary.spanWindow', span, recent) : i18n.t('ca.summary.span', span)}`;
  }

  /** The whole rule in one paragraph, ending with how it triggers. */
  function summary(alert, definitions) {
    const definition = alert.definition || {};
    const groups = definition.groups || [];
    const conditionCount = groups.reduce((sum, group) => sum + (group.conditions?.length || 0), 0);
    if (!conditionCount) return i18n.t('ca.summary.empty');
    const join = (texts, match) => texts.length < 2 ? texts.join('') : `${texts.slice(0, -1).join(', ')} ${
      i18n.t(match === 'ANY' ? 'ca.summary.or' : 'ca.summary.and')} ${texts.at(-1)}`;
    const parts = groups.map(group => {
      if (group.match === 'THEN') {
        const sequence = sequencePhrase(group, definitions);
        return groups.length > 1 ? `(${sequence})` : sequence;
      }
      const texts = (group.conditions || []).map(condition => phrase(condition, definitions));
      const joined = join(texts, group.match);
      return groups.length > 1 && texts.length > 1 ? `(${joined})` : joined;
    });
    const rule = join(parts, definition.match);
    const activityOnly = groups.every(group => (group.conditions || []).every(c => c.kind === 'INSIDER' || c.kind === 'CONGRESS'));
    const clock = i18n.t(activityOnly ? 'ca.summary.clockActivity' : 'ca.summary.clock', intervalsLabel(alert.intervals).toLowerCase());
    const mode = i18n.t(`ca.summary.mode.${alert.triggerMode || 'BECOMES_TRUE'}`);
    return `${clock} ${i18n.t('ca.summary.rule', rule)} ${mode}`;
  }

  /**
   * A stock's state for an alert: "Matches now", or "Matches now · Weekly" when the alert watches several intervals and
   * the state holds on only some of them.
   */
  function stateLabel(state, stateIntervals, alertIntervals) {
    const label = i18n.t(`ca.state.${state}`);
    return alertIntervals?.length > 1 && stateIntervals?.length && stateIntervals.length < alertIntervals.length
      ? `${label} · ${intervalsLabel(stateIntervals)}` : label;
  }

  /** A stock part-way through a sequence: "Step 1 of 2 done · step 2 due within 3 candles". */
  function progressText(progress) {
    if (!progress?.done) return '';
    const parts = [i18n.t('ca.state.progress', progress.done, progress.steps)];
    if (progress.candlesLeft) parts.push(i18n.plural('ca.state.progressLeft', progress.candlesLeft, progress.done + 1, progress.candlesLeft));
    return parts.join(' · ');
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  // ------------------------------------------------------------------ menus

  let menusWired = false;
  /** Open menus close on a click elsewhere or Escape; one opening near the bottom of the screen opens upwards. */
  function wireMenus() {
    if (menusWired) return;
    menusWired = true;
    document.addEventListener('click', event => {
      const inside = event.target instanceof Element ? event.target.closest('details.ca-menu') : null;
      document.querySelectorAll('details.ca-menu[open]').forEach(open => { if (open !== inside) open.open = false; });
    });
    document.addEventListener('keydown', event => {
      if (event.key !== 'Escape') return;
      const open = document.querySelector('details.ca-menu[open]');
      if (!open) return;
      open.open = false;
      open.querySelector('summary')?.focus();
    });
    // A menu that opens stays on screen: upwards near the bottom, rightwards when its button is near the left edge.
    document.addEventListener('toggle', event => {
      const details = event.target;
      if (!(details instanceof Element) || !details.matches('details.ca-menu') || !details.open) return;
      document.querySelectorAll('details.ca-menu[open]').forEach(other => { if (other !== details) other.open = false; });
      const panel = details.querySelector('.ca-menu-panel');
      details.classList.remove('opens-up', 'opens-right');
      if (!panel) return;
      if (panel.getBoundingClientRect().bottom > window.innerHeight - 8) details.classList.add('opens-up');
      if (panel.getBoundingClientRect().left < 8) details.classList.add('opens-right');
      panel.querySelector('button:not(:disabled), a')?.focus();
    }, true);
  }
  if (typeof document !== 'undefined') wireMenus();

  /**
   * On the custom alert pages the assistant opens from a button in the page header instead of the floating corner
   * button, which kept landing on rows, charts and the Save bar.
   */
  function askButtons() {
    if (!document.querySelector('.ca-page') || !document.querySelector('[data-assistant]')) return;
    document.querySelectorAll('[data-ca-ask-slot]').forEach(slot => slot.replaceChildren(askButton()));
  }

  /** The button that opens the assistant ("Ask StockWatch"); null when the assistant is off. */
  function askButton() {
    if (!document.querySelector('[data-assistant]')) return null;
    const button = element('button', 'ca-ask');
    button.type = 'button';
    button.setAttribute('aria-label', i18n.t('assistant.open'));
    button.title = i18n.t('assistant.open');
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', 'ca-icon');
    svg.setAttribute('aria-hidden', 'true');
    ['M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v8a2.5 2.5 0 0 1-2.5 2.5H10l-4.5 4v-4h0A1.5 1.5 0 0 1 4 14.5z', 'M8.5 8.5h7M8.5 11.5h4.5']
      .forEach(d => { const path = document.createElementNS(svg.namespaceURI, 'path'); path.setAttribute('d', d); svg.append(path); });
    const word = element('span', 'ca-ask-label', i18n.t('ca.ask'));
    word.setAttribute('aria-hidden', 'true');
    button.append(svg, word);
    button.addEventListener('click', () => {
      if (window.StockWatchAssistant) window.StockWatchAssistant.open();
      else document.querySelector('[data-assistant-toggle]')?.click();
    });
    return button;
  }
  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', askButtons, { once: true });
    else askButtons();
  }

  /**
   * A "more actions" menu: an icon button that opens a list of actions. {@code items} are
   * {label, icon, run, danger, disabled} or the string 'divider'; a group of choices is {label, choices: [{label, run}]}.
   */
  function menu(ariaLabel, items, className = '') {
    wireMenus();
    const details = element('details', `row-menu ca-menu${className ? ` ${className}` : ''}`);
    const summary = element('summary');
    summary.setAttribute('aria-label', ariaLabel);
    summary.title = ariaLabel;
    summary.append(icon('more', 'ca-icon ca-icon-more'));
    const panel = element('div', 'row-menu-panel ca-menu-panel');
    panel.setAttribute('role', 'menu');
    const close = () => { details.open = false; };
    items.filter(Boolean).forEach(item => {
      if (item === 'divider') { const line = element('div', 'ca-menu-divider'); line.setAttribute('role', 'separator'); panel.append(line); return; }
      if (item.choices) {
        const row = element('div', 'ca-menu-choices');
        row.setAttribute('role', 'group');
        row.setAttribute('aria-label', item.label);
        row.append(element('span', 'ca-menu-choices-label', item.label));
        item.choices.forEach(choice => {
          const button = element('button', 'ca-menu-choice', choice.label);
          button.type = 'button';
          button.setAttribute('role', 'menuitem');
          button.addEventListener('click', async () => { close(); await choice.run(); });
          row.append(button);
        });
        panel.append(row);
        return;
      }
      const button = element('button', `ca-menu-item${item.danger ? ' danger-text' : ''}`);
      button.type = 'button';
      button.setAttribute('role', 'menuitem');
      if (item.icon) button.append(icon(item.icon, 'ca-icon'));
      button.append(element('span', null, item.label));
      button.disabled = Boolean(item.disabled);
      button.addEventListener('click', async () => { close(); await item.run(); });
      panel.append(button);
    });
    details.append(summary, panel);
    return details;
  }

  /** A switch (role="switch") that says its state to screen readers and shows it as a sliding knob. */
  /**
   * The page's keyboard shortcuts, as [[keys], what they do]: "?" lists them in a dialog, unless the user is typing. With
   * {@code hint}, a "Keyboard shortcuts" button at the end of the page opens it too (not on touch screens, by CSS).
   */
  function shortcuts(items, hint = true) {
    const open = () => {
      if (document.querySelector('dialog.ca-shortcuts')) return;
      const dialog = element('dialog', 'ca-dialog ca-shortcuts');
      const head = element('div', 'ca-dialog-head');
      const heading = element('h2', null, i18n.t('ca.shortcuts.title'));
      heading.id = 'ca-shortcuts-title';
      dialog.setAttribute('aria-labelledby', heading.id);
      const close = element('button', 'ca-icon-button');
      close.type = 'button';
      close.setAttribute('aria-label', i18n.t('common.close'));
      close.title = i18n.t('common.close');
      close.append(icon('close'));
      close.addEventListener('click', () => dialog.close());
      head.append(heading, close);
      const body = element('div', 'ca-dialog-body');
      const list = element('dl', 'ca-shortcuts-list');
      [...items, [['?'], i18n.t('ca.shortcuts.help')]].forEach(([keys, label]) => {
        const term = element('dt');
        keys.forEach(key => term.append(element('kbd', 'ca-kbd', key)));
        list.append(term, element('dd', null, label));
      });
      const actions = element('div', 'ca-dialog-actions');
      const done = element('button', 'btn-secondary', i18n.t('common.close'));
      done.type = 'button';
      done.addEventListener('click', () => dialog.close());
      actions.append(done);
      body.append(list, actions);
      dialog.append(head, body);
      dialog.addEventListener('close', () => dialog.remove());
      document.body.append(dialog);
      dialog.showModal();
      close.focus();
    };
    document.addEventListener('keydown', event => {
      if (event.key !== '?' || event.ctrlKey || event.metaKey || event.altKey) return;
      if (event.target instanceof Element && event.target.closest('input, textarea, select, [contenteditable="true"], dialog')) return;
      event.preventDefault();
      open();
    });
    const main = document.querySelector('main.ca-page');
    if (hint && main) {
      const row = element('p', 'ca-shortcuts-hint');
      const button = element('button', 'ca-link-button');
      button.type = 'button';
      button.append(element('kbd', 'ca-kbd', '?'), ` ${i18n.t('ca.shortcuts.title')}`);
      button.addEventListener('click', open);
      row.append(button);
      main.append(row);
    }
    return open;
  }

  /** Whether a modal dialog is open: the site's sidebar is a dialog too, but never a modal one. */
  function modalOpen() {
    return [...document.querySelectorAll('dialog[open]')].some(dialog => {
      try { return dialog.matches(':modal'); } catch { return !dialog.classList.contains('app-sidebar'); }
    });
  }

  /** Escape in a filter field clears it, or, when it is empty, leaves it. */
  function escapeClears(input) {
    input?.addEventListener('keydown', event => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      if (input.value) {
        input.value = '';
        input.dispatchEvent(new Event('input', { bubbles: true }));
      } else input.blur();
    });
  }

  function toggleSwitch(on, ariaLabel, onChange) {
    const control = element('button', 'ca-switch');
    control.type = 'button';
    control.setAttribute('role', 'switch');
    control.setAttribute('aria-checked', String(on));
    control.setAttribute('aria-label', ariaLabel);
    control.title = i18n.t(on ? 'ca.switch.titleOn' : 'ca.switch.titleOff');
    control.append(element('span', 'ca-switch-track'));
    control.addEventListener('click', () => onChange(control));
    return control;
  }

  const toneOf = condition =>condition.kind === 'VALUE' ? 'value' : condition.kind === 'PATTERN' ? 'pattern'
    : condition.kind === 'OUTLOOK' ? 'outlook' : 'activity';

  /** A sequence step as a chip: "RSI (14) is below 30 · 5 in a row", "RSI (14) is above 30 · within 3". */
  function stepChip(condition, index, definitions) {
    const parts = [stepCondition(condition, definitions)];
    const candles = stepCandles(condition);
    if (candles > 1) parts.push(i18n.t('ca.chip.inARow', candles));
    if (index > 0) parts.push(i18n.t('ca.chip.within', condition.step?.within ?? 5));
    return parts.join(' · ');
  }

  /**
   * One condition as a neutral chip: an icon for its kind (indicator, pattern, insiders…) and the condition in words,
   * a reversed one led by a NOT badge. Colour is kept for meaning (met, price up or down), not for kinds.
   */
  function chip(condition, label, when = null) {
    const node = element('span', `ca-chip ca-chip-${toneOf(condition)}${condition.negate ? ' is-negated' : ''}`);
    node.append(icon(conditionIcon(condition), 'ca-chip-icon'));
    if (condition.negate) node.append(element('span', 'ca-chip-not', i18n.t('ca.badge.not')));
    // The timing runs on in the same text, so a narrow chip wraps like a sentence.
    const words = element('span', 'ca-chip-text', label);
    // A chip cut short on a narrow row still says it all in its tooltip.
    words.title = when ? `${label} · ${when}` : label;
    if (when) words.append(' ', element('span', 'ca-chip-when', when));
    node.append(words);
    return node;
  }

  /**
   * The rule as chips joined by AND / OR / THEN badges; at most {@code limit} chips, then "+N more". A joiner (and an
   * opening bracket) travels with the chip after it, so a line never ends on "AND".
   */
  function chips(definition, definitions, limit = 4) {
    const box = element('div', 'ca-rule-chips');
    const groups = definition?.groups || [];
    let shown = 0;
    let total = 0;
    let leading = [];
    const hidden = [];
    const joiner = (key, then = false) => {
      const node = element('span', `ca-joiner${then ? ' is-then' : ''}`);
      if (then) node.append(icon('arrow'));
      node.append(i18n.t(key));
      return node;
    };
    const place = node => {
      if (!leading.length) { box.append(node); return; }
      const pair = element('span', 'ca-chip-pair');
      pair.append(...leading, node);
      leading = [];
      box.append(pair);
    };
    groups.forEach((group, groupIndex) => {
      if (shown >= limit) {
        total += (group.conditions || []).length;
        (group.conditions || []).forEach(condition => hidden.push(describe(condition, definitions)));
        return;
      }
      if (groupIndex > 0) leading.push(joiner(definition.match === 'ANY' ? 'ca.badge.or' : 'ca.badge.and'));
      const ordered = group.match === 'THEN';
      const several = groups.length > 1 && (group.conditions || []).length > 1;
      const target = several ? element('span', 'ca-chip-group') : box;
      if (several && ordered) target.append(element('span', 'ca-chip-group-label', i18n.t('ca.builder.inOrder')));
      const put = node => { if (several) target.append(node); else place(node); };
      (group.conditions || []).forEach((condition, index) => {
        total++;
        if (shown >= limit) { hidden.push(describe(condition, definitions)); return; }
        const node = ordered ? chip(condition, stepChip({ ...condition, negate: false }, index, definitions))
          : chip(condition, ...chipParts({ ...condition, negate: false }, definitions));
        if (index > 0) {
          const pair = element('span', 'ca-chip-pair');
          pair.append(joiner(ordered ? 'ca.badge.then' : group.match === 'ANY' ? 'ca.badge.or' : 'ca.badge.and', ordered), node);
          put(pair);
        } else put(node);
        shown++;
      });
      if (several && target.childElementCount) place(target);
    });
    leading = [];
    if (total > shown) {
      // The conditions left out, in the tooltip.
      const more = element('span', 'ca-more', i18n.t('ca.moreConditions', total - shown));
      more.title = hidden.join('\n');
      box.append(more);
    }
    return box;
  }

  const condition = (id, body) => Object.assign({ id, negate: false }, body);
  const indicator = (type, parameters, output) => ({ kind: 'INDICATOR', type, parameters, output });
  const value = v => ({ kind: 'VALUE', value: v });
  const price = field => ({ kind: 'PRICE', field });
  const rule = (...conditions) => ({ version: 1, match: 'ALL', groups: [{ id: 'g1', match: 'ALL', conditions }] });
  const step = (condition, candles, within) => ({ ...condition, step: within ? { candles, within } : { candles } });
  const oversold = id => condition(id, { kind: 'VALUE', subject: indicator('RSI', { period: 14 }, 'value'), operator: 'BELOW', compare: value(30),
    window: { mode: 'LATEST' } });
  const recovered = id => condition(id, { kind: 'VALUE', subject: indicator('RSI', { period: 14 }, 'value'), operator: 'ABOVE', compare: value(30),
    window: { mode: 'LATEST' } });
  const bullishCandle = (id, candles) => condition(id, { kind: 'PATTERN', family: 'CANDLESTICK', status: 'CONFIRMED', direction: 'BULLISH',
    patterns: [], window: { mode: 'ANY', candles } });

  /** Starter rules. Educational examples, never recommendations. */
  const templates = [
    { id: 'oversold-bounce', intervals: ['DAILY'], triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'VALUE', subject: indicator('RSI', { period: 14 }, 'value'), operator: 'BELOW', compare: value(30),
        window: { mode: 'ANY', candles: 5 } }),
      condition('c2', { kind: 'PATTERN', family: 'CANDLESTICK', status: 'CONFIRMED', direction: 'BULLISH', patterns: [],
        window: { mode: 'ANY', candles: 5 } })) },
    { id: 'golden-cross', intervals: ['DAILY'], triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'VALUE', subject: indicator('EMA', { period: 50 }, 'value'), operator: 'CROSSES_ABOVE',
        compare: indicator('EMA', { period: 200 }, 'value'), window: { mode: 'ANY', candles: 3 } }),
      condition('c2', { kind: 'VALUE', subject: indicator('RELATIVE_VOLUME', { period: 20 }, 'value'), operator: 'ABOVE', compare: value(1.5),
        window: { mode: 'LATEST' } })) },
    { id: 'insider-cluster', intervals: ['DAILY'], triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'INSIDER', transaction: 'PURCHASE', days: 30, minCount: 2, minTotal: 250000, roles: [] }),
      condition('c2', { kind: 'INSIDER', negate: true, transaction: 'SALE', days: 30, minCount: 1, roles: [] })) },
    { id: 'congress-trend', intervals: ['DAILY'], triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'CONGRESS', transaction: 'PURCHASE', days: 45, minCount: 1, chambers: [] }),
      condition('c2', { kind: 'VALUE', subject: price('CLOSE'), operator: 'ABOVE', compare: indicator('SMA', { period: 200 }, 'value'),
        window: { mode: 'LATEST' } })) },
    { id: 'elliott-wave-three', intervals: ['DAILY'], triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'PATTERN', family: 'ELLIOTT_WAVE', direction: 'BULLISH', patterns: ['WAVE_II_END'],
        window: { mode: 'ANY', candles: 5 } }),
      condition('c2', { kind: 'VALUE', subject: indicator('MACD', {}, 'histogram'), operator: 'RISING', candles: 2,
        window: { mode: 'LATEST' } })) },
    { id: 'breakout-volume', intervals: ['DAILY'], triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'VALUE', subject: price('CLOSE'), operator: 'NEW_HIGH', candles: 20, window: { mode: 'LATEST' } }),
      condition('c2', { kind: 'VALUE', subject: indicator('RELATIVE_VOLUME', { period: 20 }, 'value'), operator: 'ABOVE', compare: value(2),
        window: { mode: 'LATEST' } })) },
    { id: 'harmonic-reversal', intervals: ['DAILY'], triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'PATTERN', family: 'HARMONIC_FORMATION', direction: 'BULLISH', patterns: [], stillValid: true,
        window: { mode: 'ANY', candles: 3 } }),
      condition('c2', { kind: 'VALUE', subject: indicator('STOCHASTIC', {}, 'k'), operator: 'BELOW', compare: value(20),
        window: { mode: 'ANY', candles: 3 } })) },
    { id: 'uptrend-pullback', intervals: ['DAILY'], triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'VALUE', subject: price('CLOSE'), operator: 'ABOVE',
        compare: indicator('SMA', { period: 30, timeframe: '1wk' }, 'value'), window: { mode: 'LATEST' } }),
      condition('c2', { kind: 'VALUE', subject: indicator('RSI', { period: 14 }, 'value'), operator: 'CROSSES_ABOVE', compare: value(40),
        window: { mode: 'ANY', candles: 2 } })) },
    // The same idea with the candle around the recovery (its own group) or after it (the last step).
    { id: 'oversold-recovery', intervals: ['DAILY'], triggerMode: 'BECOMES_TRUE', definition: { version: 1, match: 'ALL', groups: [
      { id: 'g1', match: 'THEN', span: 20, window: { mode: 'ANY', candles: 3 }, conditions: [step(oversold('c1'), 5), step(recovered('c2'), 1, 3)] },
      { id: 'g2', match: 'ALL', conditions: [bullishCandle('c3', 3)] }] } },
    { id: 'oversold-recovery-then-candle', intervals: ['DAILY'], triggerMode: 'BECOMES_TRUE', definition: { version: 1, match: 'ALL', groups: [
      { id: 'g1', match: 'THEN', span: 25, window: { mode: 'LATEST' }, conditions: [step(oversold('c1'), 5), step(recovered('c2'), 1, 3),
        step(bullishCandle('c3', 1), 1, 5)] }] } }
  ];

  function fromTemplate(id) {
    const template = templates.find(item => item.id === id);
    if (!template) return null;
    return {
      name: i18n.t(`ca.template.${id}.name`), description: i18n.t(`ca.template.${id}.text`), intervals: [...template.intervals],
      triggerMode: template.triggerMode, definition: JSON.parse(JSON.stringify(template.definition))
    };
  }

  window.StockWatchCustomAlerts = Object.freeze({
    catalog, describe, describeStep, stepCandles, defaultSpan, progressText, stateLabel, summary, chips, chip, operandLabel, patternName, patternTerm, indicatorName, indicatorShort,
    inputLabel, outputLabel, choiceLabel, intervalLabel, intervalsLabel, number, money, signed, date, dateTime, relativeDay, icon, conditionIcon,
    menu, toggleSwitch, askButton, templates, fromTemplate, element, shortcuts, escapeClears, modalOpen
  });
})();
