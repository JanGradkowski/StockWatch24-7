(() => {
  'use strict';
  // Custom alerts in plain words: every page that shows a rule (the list, the builder, a trigger, a
  // watchlist, the stock page) describes it with these functions, in the reader's language. The rule is
  // the JSON the server stores (CustomAlertDefinition).
  if (window.StockWatchCustomAlerts) return;
  const i18n = window.StockWatchI18n;
  let catalogRequest = null;

  const has = key => i18n.has(key);
  /** A number as typed: no trailing zeros, two decimals at most (four below 1), local separators. */
  const number = value => {
    const n = Number(value);
    if (!Number.isFinite(n)) return '';
    if (Number.isInteger(n)) return i18n.number(n, 0);
    const digits = Math.abs(n) >= 1 ? 2 : 4;
    return i18n.number(n, digits).replace(/([.,]\d*?)0+$/, '$1').replace(/[.,]$/, '');
  };
  // Dollar amounts get thousands separators in both languages ($250,000 / 250 000 $ is not used: the data is in USD).
  const money = value => '$' + new Intl.NumberFormat(i18n.tag || 'en', { maximumFractionDigits: 0 }).format(Number(value) || 0);

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

  /** "RSI (14)", "MACD › Histogram (12, 26, 9)", "SMA (30, weekly)". */
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
    const line = main ? '' : ` › ${outputLabel(operand.type, operand.output)}`;
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

  function patternSentence(condition) {
    const direction = condition.direction || 'ANY';
    let text = i18n.t('ca.pattern.detected', i18n.t(`ca.pattern.family.${condition.family}.${direction}`),
      condition.window?.candles || 1);
    const details = [];
    if (condition.patterns?.length) details.push(condition.patterns.map(name => patternName(condition.family, name)).join(', '));
    if (condition.minScore != null) details.push(i18n.t('ca.pattern.minScore', condition.minScore));
    if (condition.confirmedOnly) details.push(i18n.t('ca.pattern.confirmed'));
    if (condition.stillValid) details.push(i18n.t('ca.pattern.stillValid'));
    if (details.length) text += ` (${details.join('; ')})`;
    return text;
  }

  function activitySentence(condition) {
    const insider = condition.kind === 'INSIDER';
    let text = i18n.t(`ca.activity.${condition.kind}.${condition.transaction}`, condition.days);
    const details = [];
    if (condition.minCount > 1) details.push(i18n.t('ca.activity.minCount', condition.minCount));
    if (condition.minTotal) details.push(i18n.t('ca.activity.minTotal', money(condition.minTotal)));
    const list = insider ? condition.roles : condition.chambers;
    if (list?.length && (insider || list.length < 2)) details.push(list.map(item => i18n.t(`ca.activity.${insider ? 'role' : 'chamber'}.${item}`)).join(', '));
    if (details.length) text += ` (${details.join('; ')})`;
    return text;
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

  /**
   * A condition inside the summary sentence. Sentences written by us (patterns, filings, outlook, prices) start with an
   * ordinary word and are lowercased mid-sentence; indicator names (RSI, Bollinger Bands) keep their capitals.
   */
  function phrase(condition, definitions) {
    let text = describe({ ...condition, negate: false }, definitions);
    if ((condition.kind !== 'VALUE' || condition.subject?.kind === 'PRICE') && /^\p{Lu}[\p{Ll}\s]/u.test(text)) {
      text = text.charAt(0).toLocaleLowerCase(i18n.tag || 'en') + text.slice(1);
    }
    return condition.negate ? i18n.t('ca.summary.not', text) : text;
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
      const texts = (group.conditions || []).map(condition => phrase(condition, definitions));
      const joined = join(texts, group.match);
      return groups.length > 1 && texts.length > 1 ? `(${joined})` : joined;
    });
    const rule = join(parts, definition.match);
    const activityOnly = groups.every(group => (group.conditions || []).every(c => c.kind === 'INSIDER' || c.kind === 'CONGRESS'));
    const clock = activityOnly ? i18n.t('ca.summary.clockActivity', intervalLabel(alert.interval || 'DAILY').toLowerCase())
      : i18n.t('ca.summary.clock', intervalLabel(alert.interval || 'DAILY').toLowerCase());
    const mode = i18n.t(`ca.summary.mode.${alert.triggerMode || 'BECOMES_TRUE'}`);
    return `${clock} ${i18n.t('ca.summary.rule', rule)} ${mode}`;
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  const toneOf = condition => condition.kind === 'VALUE' ? 'value' : condition.kind === 'PATTERN' ? 'pattern'
    : condition.kind === 'OUTLOOK' ? 'outlook' : 'activity';

  /** The rule as chips joined by AND / OR badges; at most {@code limit} chips, then "+N more". */
  function chips(definition, definitions, limit = 4) {
    const box = element('div', 'ca-rule-chips');
    const groups = definition?.groups || [];
    let shown = 0;
    let total = 0;
    groups.forEach((group, groupIndex) => {
      if (groupIndex > 0) box.append(element('span', 'ca-joiner', i18n.t(definition.match === 'ANY' ? 'ca.badge.or' : 'ca.badge.and')));
      const several = groups.length > 1 && (group.conditions || []).length > 1;
      if (several) box.append(element('span', 'ca-paren', '('));
      (group.conditions || []).forEach((condition, index) => {
        total++;
        if (shown >= limit) return;
        if (index > 0) box.append(element('span', 'ca-joiner', i18n.t(group.match === 'ANY' ? 'ca.badge.or' : 'ca.badge.and')));
        const chip = element('span', `ca-chip ca-chip-${toneOf(condition)}`, describe(condition, definitions));
        box.append(chip);
        shown++;
      });
      if (several) box.append(element('span', 'ca-paren', ')'));
    });
    if (total > shown) box.append(element('span', 'ca-more', i18n.t('ca.moreConditions', total - shown)));
    return box;
  }

  const condition = (id, body) => Object.assign({ id, negate: false }, body);
  const indicator = (type, parameters, output) => ({ kind: 'INDICATOR', type, parameters, output });
  const value = v => ({ kind: 'VALUE', value: v });
  const price = field => ({ kind: 'PRICE', field });
  const rule = (...conditions) => ({ version: 1, match: 'ALL', groups: [{ id: 'g1', match: 'ALL', conditions }] });

  /** Starter rules. Educational examples, never recommendations. */
  const templates = [
    { id: 'oversold-bounce', interval: 'DAILY', triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'VALUE', subject: indicator('RSI', { period: 14 }, 'value'), operator: 'BELOW', compare: value(30),
        window: { mode: 'ANY', candles: 5 } }),
      condition('c2', { kind: 'PATTERN', family: 'CANDLESTICK', direction: 'BULLISH', patterns: [], confirmedOnly: false,
        window: { mode: 'ANY', candles: 5 } })) },
    { id: 'golden-cross', interval: 'DAILY', triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'VALUE', subject: indicator('EMA', { period: 50 }, 'value'), operator: 'CROSSES_ABOVE',
        compare: indicator('EMA', { period: 200 }, 'value'), window: { mode: 'ANY', candles: 3 } }),
      condition('c2', { kind: 'VALUE', subject: indicator('RELATIVE_VOLUME', { period: 20 }, 'value'), operator: 'ABOVE', compare: value(1.5),
        window: { mode: 'LATEST' } })) },
    { id: 'insider-cluster', interval: 'DAILY', triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'INSIDER', transaction: 'PURCHASE', days: 30, minCount: 2, minTotal: 250000, roles: [] }),
      condition('c2', { kind: 'INSIDER', negate: true, transaction: 'SALE', days: 30, minCount: 1, roles: [] })) },
    { id: 'congress-trend', interval: 'DAILY', triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'CONGRESS', transaction: 'PURCHASE', days: 45, minCount: 1, chambers: [] }),
      condition('c2', { kind: 'VALUE', subject: price('CLOSE'), operator: 'ABOVE', compare: indicator('SMA', { period: 200 }, 'value'),
        window: { mode: 'LATEST' } })) },
    { id: 'elliott-wave-three', interval: 'DAILY', triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'PATTERN', family: 'ELLIOTT_WAVE', direction: 'BULLISH', patterns: ['WAVE_II_END'],
        window: { mode: 'ANY', candles: 5 } }),
      condition('c2', { kind: 'VALUE', subject: indicator('MACD', {}, 'histogram'), operator: 'RISING', candles: 2,
        window: { mode: 'LATEST' } })) },
    { id: 'breakout-volume', interval: 'DAILY', triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'VALUE', subject: price('CLOSE'), operator: 'NEW_HIGH', candles: 20, window: { mode: 'LATEST' } }),
      condition('c2', { kind: 'VALUE', subject: indicator('RELATIVE_VOLUME', { period: 20 }, 'value'), operator: 'ABOVE', compare: value(2),
        window: { mode: 'LATEST' } })) },
    { id: 'harmonic-reversal', interval: 'DAILY', triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'PATTERN', family: 'HARMONIC_FORMATION', direction: 'BULLISH', patterns: [], stillValid: true,
        window: { mode: 'ANY', candles: 3 } }),
      condition('c2', { kind: 'VALUE', subject: indicator('STOCHASTIC', {}, 'k'), operator: 'BELOW', compare: value(20),
        window: { mode: 'ANY', candles: 3 } })) },
    { id: 'uptrend-pullback', interval: 'DAILY', triggerMode: 'BECOMES_TRUE', definition: rule(
      condition('c1', { kind: 'VALUE', subject: price('CLOSE'), operator: 'ABOVE',
        compare: indicator('SMA', { period: 30, timeframe: '1wk' }, 'value'), window: { mode: 'LATEST' } }),
      condition('c2', { kind: 'VALUE', subject: indicator('RSI', { period: 14 }, 'value'), operator: 'CROSSES_ABOVE', compare: value(40),
        window: { mode: 'ANY', candles: 2 } })) }
  ];

  function fromTemplate(id) {
    const template = templates.find(item => item.id === id);
    if (!template) return null;
    return {
      name: i18n.t(`ca.template.${id}.name`), description: i18n.t(`ca.template.${id}.text`), interval: template.interval,
      triggerMode: template.triggerMode, definition: JSON.parse(JSON.stringify(template.definition))
    };
  }

  window.StockWatchCustomAlerts = Object.freeze({
    catalog, describe, summary, chips, operandLabel, patternName, patternTerm, indicatorName, indicatorShort, inputLabel,
    outputLabel, choiceLabel, intervalLabel, number, money, templates, fromTemplate, element
  });
})();
