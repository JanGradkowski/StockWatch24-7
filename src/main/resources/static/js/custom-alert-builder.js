(() => {
  'use strict';
  // The custom alert builder. The rule is edited as plain data (the JSON the server stores); every change
  // re-describes the condition in words and refreshes the preview a moment later, so the user always sees
  // what the alert will do and how it reads on a real stock.
  const form = document.querySelector('[data-ca-builder]');
  if (!form) return;
  const page = document.querySelector('.ca-builder-page');
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  // The shared helpers (watchlist-picker.js) load with the navigation, after this script.
  const api = (...args) => window.StockWatchLists.api(...args);
  const choose = (...args) => window.StockWatchLists.choose(...args);
  const el = text.element;
  // Learning-mode explanations (js.learn.<key>) used on this page.
  const LEARN_OPTIONS = { capacity: 'customAlerts.capacity', detected: 'customAlerts.detected', direction: 'customAlerts.direction', disclosureDate: 'customAlerts.disclosureDate', filingDate: 'customAlerts.filingDate', history: 'customAlerts.history', minPeople: 'customAlerts.minPeople', minScore: 'customAlerts.minScore', negate: 'customAlerts.negate', operator: 'customAlerts.operator', outcome: 'customAlerts.outcome', outlook: 'customAlerts.outlook', timeframe: 'customAlerts.timeframe', window: 'customAlerts.window' };
  const PRICE_FIELDS = ['CLOSE', 'OPEN', 'HIGH', 'LOW', 'VOLUME'];
  const SOURCES = ['close', 'open', 'high', 'low', 'hl2', 'hlc3', 'ohlc4', 'hlcc4'];
  const COMPARISONS = ['ABOVE', 'BELOW', 'CROSSES_ABOVE', 'CROSSES_BELOW'];
  const OPERATORS = ['ABOVE', 'BELOW', 'CROSSES_ABOVE', 'CROSSES_BELOW', 'BETWEEN', 'OUTSIDE', 'RISING', 'FALLING',
    'CHANGE_ABOVE', 'CHANGE_BELOW', 'NEW_HIGH', 'NEW_LOW'];

  const nodes = {
    name: form.querySelector('[data-ca-name]'), description: form.querySelector('[data-ca-description]'),
    interval: form.querySelector('[data-ca-interval]'), groups: form.querySelector('[data-ca-groups]'),
    quiet: form.querySelector('[data-ca-quiet]'), outcome: form.querySelector('[data-ca-outcome]'),
    email: form.querySelector('[data-ca-email]'), notifyExisting: form.querySelector('[data-ca-notify-existing]'),
    targets: form.querySelector('[data-ca-targets]'), start: form.querySelector('[data-ca-start]'),
    summary: form.querySelector('[data-ca-summary]'), result: form.querySelector('[data-ca-result]'),
    coverage: form.querySelector('[data-ca-coverage]'), testSymbol: form.querySelector('[data-ca-test-symbol]'),
    status: form.querySelector('[data-ca-status]'), save: form.querySelector('[data-ca-save]')
  };

  const state = {
    id: page.dataset.alertId ? Number(page.dataset.alertId) : null,
    interval: 'DAILY', triggerMode: 'BECOMES_TRUE', quietCandles: 0, outcomeCandles: 10, emailEnabled: true,
    definition: { version: 1, match: 'ALL', groups: [{ id: 'g1', match: 'ALL', conditions: [] }] },
    targets: new Map(), notifyExisting: false, sourceChartAlertId: null, pendingSymbol: null, pendingChoice: null
  };
  let catalog = { indicators: [], byType: new Map(), patterns: {}, maxConditions: 12, maxGroups: 4 };
  let lists = [];
  let dirty = false;
  let preview = null;
  let previewTimer = null;
  let previewSequence = 0;
  let counter = 0;
  const newId = prefix => `${prefix}${Date.now().toString(36)}${(counter++).toString(36)}`;
  const conditions = () => state.definition.groups.flatMap(group => group.conditions);

  // ------------------------------------------------------------------ small controls

  /**
   * A captioned control. A single input or select sits inside its label; a group of controls (checkboxes,
   * an input with suggestions) is a labelled group instead, since a label may hold only one control.
   */
  function field(label, control, learnKey) {
    const single = control instanceof HTMLInputElement || control instanceof HTMLSelectElement;
    const wrapper = el(single ? 'label' : 'div', 'ca-field');
    const caption = el('span', 'ca-field-label', label);
    if (!single) {
      caption.id = newId('ca-caption-');
      wrapper.setAttribute('role', 'group');
      wrapper.setAttribute('aria-labelledby', caption.id);
    }
    if (learnKey) {
      const help = el('span', 'learn-help', '?');
      help.dataset.learn = learnKey;
      help.dataset.learnTitle = label;
      caption.append(' ', help);
    }
    wrapper.append(caption, control);
    return wrapper;
  }

  function select(options, value, onChange, ariaLabel) {
    const control = el('select');
    options.forEach(([optionValue, label]) => {
      const option = el('option', null, label);
      option.value = optionValue;
      control.append(option);
    });
    control.value = value;
    if (ariaLabel) control.setAttribute('aria-label', ariaLabel);
    control.addEventListener('change', () => onChange(control.value));
    return control;
  }

  function numberInput(value, { min, max, step = 'any', placeholder } = {}, onChange) {
    const input = el('input');
    input.type = 'number';
    if (min != null) input.min = String(min);
    if (max != null) input.max = String(max);
    input.step = String(step);
    if (placeholder) input.placeholder = placeholder;
    input.value = value == null ? '' : String(value);
    input.inputMode = 'decimal';
    input.addEventListener('input', () => onChange(input.value === '' ? null : Number(input.value)));
    return input;
  }

  function check(label, checked, onChange, term) {
    const wrapper = el('label', 'wl-check ca-check');
    const input = el('input');
    input.type = 'checkbox';
    input.checked = Boolean(checked);
    input.addEventListener('change', () => onChange(input.checked));
    const caption = el('span', term ? 'learn-term' : null, label);
    if (term) caption.dataset.term = term;
    wrapper.append(input, caption);
    return wrapper;
  }

  function segmented(value, options, onChange, ariaLabel) {
    const group = el('span', 'ca-segmented');
    group.setAttribute('role', 'group');
    group.setAttribute('aria-label', ariaLabel);
    options.forEach(([optionValue, label]) => {
      const button = el('button', null, label);
      button.type = 'button';
      button.setAttribute('aria-pressed', String(optionValue === value));
      button.addEventListener('click', () => onChange(optionValue));
      group.append(button);
    });
    return group;
  }

  function iconButton(label, symbol, onClick, disabled) {
    const button = el('button', 'ca-icon-button', symbol);
    button.type = 'button';
    button.title = label;
    button.setAttribute('aria-label', label);
    button.disabled = Boolean(disabled);
    button.addEventListener('click', onClick);
    return button;
  }

  function changed() {
    dirty = true;
    nodes.summary.textContent = text.summary(snapshot(), catalog.byType);
    schedulePreview();
  }

  // ------------------------------------------------------------------ rule

  function renderGroups() {
    const groups = state.definition.groups;
    const parts = [];
    if (groups.length > 1) {
      const top = el('div', 'ca-match-row');
      top.append(el('span', null, i18n.t('ca.builder.triggerWhen')),
        segmented(state.definition.match, [['ALL', i18n.t('ca.builder.all')], ['ANY', i18n.t('ca.builder.any')]], value => {
          state.definition.match = value; renderGroups(); changed();
        }, i18n.t('ca.builder.groupsMatchAria')),
        el('span', null, i18n.t('ca.builder.ofTheseGroups')));
      parts.push(top);
    }
    groups.forEach((group, groupIndex) => {
      if (groupIndex > 0) parts.push(el('div', 'ca-group-joiner', i18n.t(state.definition.match === 'ANY' ? 'ca.badge.or' : 'ca.badge.and')));
      parts.push(groupBox(group, groupIndex));
    });
    if (!conditions().length) {
      const empty = el('div', 'ca-rule-empty');
      empty.append(el('strong', null, i18n.t('ca.builder.emptyTitle')), el('p', 'wl-muted', i18n.t('ca.builder.emptyText')));
      parts.push(empty);
    }
    nodes.groups.replaceChildren(...parts);
    form.querySelector('[data-ca-add-group]').disabled = groups.length >= catalog.maxGroups || !conditions().length;
    form.querySelector('[data-ca-add-condition]').disabled = conditions().length >= catalog.maxConditions;
  }

  function groupBox(group, groupIndex) {
    const groups = state.definition.groups;
    const box = el('section', `ca-group${groups.length > 1 ? ' is-grouped' : ''}`);
    box.setAttribute('aria-label', i18n.t('ca.builder.groupAria', groupIndex + 1));
    if (groups.length > 1 || group.conditions.length > 1) {
      const header = el('div', 'ca-group-header');
      if (groups.length > 1) header.append(el('strong', null, i18n.t('ca.builder.groupTitle', groupIndex + 1)));
      if (group.conditions.length > 1) {
        header.append(segmented(group.match, [['ALL', i18n.t('ca.builder.allConditions')], ['ANY', i18n.t('ca.builder.anyCondition')]], value => {
          group.match = value; renderGroups(); changed();
        }, i18n.t('ca.builder.groupMatchAria', groupIndex + 1)));
        header.append(el('span', 'wl-muted', i18n.t(group.match === 'ANY' ? 'ca.builder.anyHint' : 'ca.builder.allHint')));
      }
      if (groups.length > 1) {
        header.append(iconButton(i18n.t('ca.builder.removeGroup', groupIndex + 1), '×', () => {
          groups.splice(groupIndex, 1);
          if (!groups.length) groups.push({ id: newId('g'), match: 'ALL', conditions: [] });
          renderGroups(); changed();
        }));
      }
      box.append(header);
    }
    group.conditions.forEach((condition, index) => {
      if (index > 0) box.append(el('div', 'ca-condition-joiner', i18n.t(group.match === 'ANY' ? 'ca.badge.or' : 'ca.badge.and')));
      box.append(conditionCard(condition, group, index));
    });
    if (groups.length > 1) {
      const add = el('button', 'btn-secondary ca-add-here', i18n.t('ca.builder.addToGroup'));
      add.type = 'button';
      add.disabled = conditions().length >= catalog.maxConditions;
      add.addEventListener('click', () => addCondition(group));
      box.append(add);
    }
    return box;
  }

  const kindLabel = condition => i18n.t(`ca.kind.${condition.kind === 'VALUE' && condition.subject?.kind === 'PRICE' ? 'PRICE'
    : condition.kind === 'VALUE' ? 'INDICATOR' : condition.kind === 'PATTERN' ? condition.family : condition.kind}`);
  const tone = condition => condition.kind === 'VALUE' ? 'value' : condition.kind === 'PATTERN' ? 'pattern'
    : condition.kind === 'OUTLOOK' ? 'outlook' : 'activity';

  function conditionCard(condition, group, index) {
    const card = el('article', `ca-condition ca-tone-${tone(condition)}${condition.negate ? ' is-negated' : ''}`);
    card.dataset.conditionId = condition.id;
    const header = el('div', 'ca-condition-header');
    const label = el('span', 'ca-kind', kindLabel(condition));
    const sentence = el('p', 'ca-condition-sentence', text.describe(condition, catalog.byType));
    const readout = el('span', 'ca-readout');
    readout.dataset.readout = condition.id;
    const tools = el('div', 'ca-condition-tools');
    const negate = el('button', 'ca-negate', i18n.t(condition.negate ? 'ca.builder.negated' : 'ca.builder.negate'));
    negate.type = 'button';
    negate.setAttribute('aria-pressed', String(Boolean(condition.negate)));
    negate.classList.add('learn-control');
    negate.dataset.learn = LEARN_OPTIONS.negate;
    negate.dataset.learnTitle = i18n.t('ca.builder.negate');
    negate.addEventListener('click', () => { condition.negate = !condition.negate; renderGroups(); changed(); });
    const all = conditions();
    const position = all.indexOf(condition);
    tools.append(negate,
      iconButton(i18n.t('ca.builder.moveUp'), '↑', () => move(condition, -1), position === 0),
      iconButton(i18n.t('ca.builder.moveDown'), '↓', () => move(condition, 1), position === all.length - 1),
      iconButton(i18n.t('ca.builder.duplicate'), '⧉', () => {
        const copy = JSON.parse(JSON.stringify(condition)); copy.id = newId('c');
        group.conditions.splice(index + 1, 0, copy); renderGroups(); changed();
      }, all.length >= catalog.maxConditions),
      iconButton(i18n.t('ca.builder.remove'), '×', () => {
        group.conditions.splice(index, 1);
        if (!group.conditions.length && state.definition.groups.length > 1) {
          state.definition.groups.splice(state.definition.groups.indexOf(group), 1);
        }
        renderGroups(); changed();
      }));
    header.append(label, sentence, readout, tools);
    const body = el('div', 'ca-condition-body');
    const refresh = () => {
      sentence.textContent = text.describe(condition, catalog.byType);
      label.textContent = kindLabel(condition);
      changed();
    };
    const rebuild = () => { body.replaceChildren(...editor(condition, refresh, rebuild)); refresh(); };
    body.append(...editor(condition, refresh, rebuild));
    card.append(header, body);
    applyReadout(readout, condition);
    return card;
  }

  /** Moves a condition one place up or down, across groups when it reaches a group's edge. */
  function move(condition, step) {
    const all = conditions();
    const target = all.indexOf(condition) + step;
    if (target < 0 || target >= all.length) return;
    const groups = state.definition.groups;
    const from = groups.find(group => group.conditions.includes(condition));
    const neighbour = all[target];
    const to = groups.find(group => group.conditions.includes(neighbour));
    from.conditions.splice(from.conditions.indexOf(condition), 1);
    to.conditions.splice(to.conditions.indexOf(neighbour) + (step > 0 ? 1 : 0), 0, condition);
    if (!from.conditions.length && groups.length > 1) groups.splice(groups.indexOf(from), 1);
    renderGroups(); changed();
  }

  function editor(condition, refresh, rebuild) {
    switch (condition.kind) {
      case 'VALUE': return valueEditor(condition, refresh, rebuild);
      case 'PATTERN': return patternEditor(condition, refresh, rebuild);
      case 'OUTLOOK': return outlookEditor(condition, refresh);
      default: return activityEditor(condition, refresh);
    }
  }

  // ------------------------------------------------------------------ value conditions

  function valueEditor(condition, refresh, rebuild) {
    const fields = [];
    const subjectKind = condition.subject.kind === 'PRICE' ? condition.subject.field : 'INDICATOR';
    const subject = select([...PRICE_FIELDS.map(fieldName => [fieldName, i18n.t(`ca.price.${fieldName}`)]), ['INDICATOR', i18n.t('ca.builder.anIndicator')]],
      subjectKind, value => {
        if (value === 'INDICATOR') {
          chooseIndicator(null).then(operand => {
            if (operand) { condition.subject = operand; suggestCompare(condition); }
            rebuild();
          });
          return;
        }
        condition.subject = { kind: 'PRICE', field: value };
        if (condition.compare?.kind === 'PRICE' && value === 'CLOSE') condition.compare = { kind: 'VALUE', value: 0 };
        rebuild();
      });
    fields.push(field(i18n.t('ca.builder.watch'), subject));
    if (condition.subject.kind === 'INDICATOR') {
      fields.push(field(i18n.t('ca.builder.indicator'), indicatorButton(condition.subject, operand => {
        condition.subject = operand; rebuild();
      })));
    }
    fields.push(field(i18n.t('ca.builder.condition'), select(OPERATORS.map(op => [op, i18n.t(`ca.opLabel.${op}`)]), condition.operator, value => {
      condition.operator = value;
      if (COMPARISONS.includes(value) && !condition.compare) suggestCompare(condition);
      if (['RISING', 'FALLING'].includes(value)) condition.candles = condition.candles && condition.candles <= 20 ? condition.candles : 2;
      if (['NEW_HIGH', 'NEW_LOW'].includes(value)) condition.candles = Math.max(2, condition.candles || 20);
      if (['CHANGE_ABOVE', 'CHANGE_BELOW'].includes(value)) { condition.candles = condition.candles || 5; condition.percent = condition.percent ?? 5; }
      if (['BETWEEN', 'OUTSIDE'].includes(value) && condition.lower == null) { condition.lower = 30; condition.upper = 70; }
      if (['CROSSES_ABOVE', 'CROSSES_BELOW'].includes(value) && condition.window?.mode === 'EVERY') condition.window = { mode: 'LATEST' };
      rebuild();
    }), LEARN_OPTIONS.operator));
    const op = condition.operator;
    if (COMPARISONS.includes(op)) {
      const compare = condition.compare || { kind: 'VALUE', value: 0 };
      const options = [['VALUE', i18n.t('ca.builder.aNumber')]];
      if (!(condition.subject.kind === 'PRICE' && condition.subject.field === 'CLOSE')) options.push(['PRICE', i18n.t('ca.builder.theClose')]);
      options.push(['INDICATOR', i18n.t('ca.builder.anotherIndicator')]);
      fields.push(field(i18n.t('ca.builder.comparedWith'), select(options, compare.kind, value => {
        if (value === 'INDICATOR') {
          chooseIndicator(null).then(operand => { if (operand) condition.compare = operand; rebuild(); });
          return;
        }
        condition.compare = value === 'PRICE' ? { kind: 'PRICE', field: 'CLOSE' } : { kind: 'VALUE', value: 0 };
        suggestCompare(condition, true);
        rebuild();
      })));
      if (compare.kind === 'VALUE') {
        const wrapper = el('div', 'ca-value-with-hints');
        const input = numberInput(compare.value, { placeholder: '30' }, value => { condition.compare = { kind: 'VALUE', value: value ?? 0 }; refresh(); });
        input.setAttribute('aria-label', i18n.t('ca.builder.value'));
        wrapper.append(input);
        hints(condition.subject).forEach(hint => {
          const chip = el('button', 'ca-hint', text.number(hint));
          chip.type = 'button';
          chip.title = i18n.t('ca.builder.useValue', text.number(hint));
          chip.addEventListener('click', () => { input.value = String(hint); condition.compare = { kind: 'VALUE', value: hint }; refresh(); });
          wrapper.append(chip);
        });
        fields.push(field(i18n.t('ca.builder.value'), wrapper));
      } else if (compare.kind === 'INDICATOR') {
        fields.push(field(i18n.t('ca.builder.otherIndicator'), indicatorButton(compare, operand => { condition.compare = operand; rebuild(); })));
      }
    } else if (['BETWEEN', 'OUTSIDE'].includes(op)) {
      fields.push(field(i18n.t('ca.builder.lower'), numberInput(condition.lower, {}, value => { condition.lower = value; refresh(); })));
      fields.push(field(i18n.t('ca.builder.upper'), numberInput(condition.upper, {}, value => { condition.upper = value; refresh(); })));
    } else if (['CHANGE_ABOVE', 'CHANGE_BELOW'].includes(op)) {
      fields.push(field(i18n.t('ca.builder.percent'), numberInput(condition.percent, { min: 0, max: 1000 }, value => { condition.percent = value; refresh(); })));
      fields.push(field(i18n.t('ca.builder.overCandles'), numberInput(condition.candles, { min: 1, max: 250, step: 1 }, value => { condition.candles = value; refresh(); })));
    } else {
      const limits = ['RISING', 'FALLING'].includes(op) ? { min: 1, max: 20, step: 1 } : { min: 2, max: 250, step: 1 };
      fields.push(field(i18n.t(['RISING', 'FALLING'].includes(op) ? 'ca.builder.inARow' : 'ca.builder.lookback'),
        numberInput(condition.candles, limits, value => { condition.candles = value; refresh(); })));
    }
    fields.push(...windowFields(condition, refresh, rebuild));
    return fields;
  }

  /** Levels an indicator is usually read against (RSI 30 and 70, Stochastic 20 and 80) as one-click values. */
  function hints(operand) {
    if (operand?.kind !== 'INDICATOR') return [];
    const definition = catalog.byType.get(operand.type);
    if (!definition?.zones) return [];
    const levels = [definition.zones.lowerInput, definition.zones.upperInput]
      .map(key => operand.parameters?.[key] ?? definition.inputs.find(input => input.key === key)?.defaultValue)
      .filter(value => typeof value === 'number');
    return [...new Set(levels)];
  }

  function suggestCompare(condition, keepKind) {
    if (keepKind && condition.compare?.kind !== 'VALUE') return;
    const levels = hints(condition.subject);
    if (!condition.compare || condition.compare.kind === 'VALUE') {
      const below = ['BELOW', 'CROSSES_BELOW'].includes(condition.operator);
      const level = levels.length ? (below ? levels[0] : levels.at(-1)) : (preview?.latestClose && condition.subject.kind === 'PRICE'
        ? Math.round(preview.latestClose) : condition.compare?.value ?? 0);
      condition.compare = { kind: 'VALUE', value: level };
    }
  }

  function windowFields(condition, refresh, rebuild) {
    const span = condition.window || { mode: 'LATEST' };
    const crossing = ['CROSSES_ABOVE', 'CROSSES_BELOW', 'NEW_HIGH', 'NEW_LOW'].includes(condition.operator);
    const options = [['LATEST', i18n.t('ca.window.label.LATEST')], ['ANY', i18n.t('ca.window.label.ANY')]];
    if (!crossing) options.push(['EVERY', i18n.t('ca.window.label.EVERY')]);
    const fields = [field(i18n.t('ca.builder.when'), select(options, span.mode, value => {
      condition.window = value === 'LATEST' ? { mode: 'LATEST' } : { mode: value, candles: span.candles && span.candles > 1 ? span.candles : 3 };
      rebuild();
    }), LEARN_OPTIONS.window)];
    if (span.mode !== 'LATEST') {
      fields.push(field(i18n.t('ca.builder.candles'), numberInput(span.candles, { min: 1, max: catalog.maxWindow || 60, step: 1 }, value => {
        condition.window = { mode: span.mode, candles: value }; refresh();
      })));
    }
    return fields;
  }

  function indicatorButton(operand, onChange) {
    const button = el('button', 'ca-indicator-button', `${text.operandLabel(operand, catalog.byType)} ✎`);
    button.type = 'button';
    button.setAttribute('aria-label', i18n.t('ca.builder.changeIndicator', text.operandLabel(operand, catalog.byType)));
    button.addEventListener('click', () => chooseIndicator(operand).then(chosen => { if (chosen) onChange(chosen); }));
    return button;
  }

  /** Indicator chooser: search the catalog, then set its numbers and the line to watch. */
  function chooseIndicator(current) {
    return new Promise(resolve => {
      const dialog = el('dialog', 'wl-dialog ca-indicator-dialog');
      const heading = el('h2', null, i18n.t('ca.indicatorDialog.title'));
      heading.id = 'ca-indicator-title';
      dialog.setAttribute('aria-labelledby', heading.id);
      const content = el('div');
      let result = null;
      dialog.append(heading, content);
      dialog.addEventListener('close', () => { dialog.remove(); resolve(result); });
      document.body.append(dialog);
      dialog.showModal();

      const showList = () => {
        const search = el('input');
        search.type = 'search';
        search.placeholder = i18n.t('ca.indicatorDialog.search');
        search.setAttribute('aria-label', i18n.t('ca.indicatorDialog.search'));
        const categories = ['ALL', ...new Set(catalog.indicators.map(item => item.category))];
        const category = select(categories.map(value => [value, value === 'ALL' ? i18n.t('ca.indicatorDialog.allCategories')
          : i18n.t(`ind.category.${value}`)]), 'ALL', () => draw(), i18n.t('ca.indicatorDialog.category'));
        const list = el('div', 'ca-indicator-list');
        list.setAttribute('role', 'list');
        const popular = ['RSI', 'MACD', 'SMA', 'EMA', 'BOLLINGER', 'STOCHASTIC', 'RELATIVE_VOLUME', 'ADX_DMI', 'ATR', 'OBV'];
        const draw = () => {
          const query = search.value.trim().toLowerCase();
          let items = catalog.indicators.filter(item => category.value === 'ALL' || item.category === category.value)
            .filter(item => !query || [text.indicatorName(item.type), item.shortName, item.type].some(value => String(value).toLowerCase().includes(query)));
          items = items.sort((a, b) => (popular.includes(b.type) - popular.includes(a.type))
            || text.indicatorName(a.type).localeCompare(text.indicatorName(b.type), i18n.tag));
          list.replaceChildren(...(items.length ? items.map(item => {
            const row = el('button', 'ca-indicator-item');
            row.type = 'button';
            row.setAttribute('role', 'listitem');
            row.append(el('strong', null, text.indicatorName(item.type)),
              el('span', 'wl-muted', `${item.shortName} · ${i18n.t(`ind.category.${item.category}`)}`));
            if (i18n.has(`ind.${item.type}.description`)) row.append(el('span', 'ca-indicator-description', i18n.t(`ind.${item.type}.description`)));
            row.addEventListener('click', () => showSettings({ kind: 'INDICATOR', type: item.type, parameters: {}, output: item.outputs[0] }));
            return row;
          }) : [el('p', 'wl-muted', i18n.t('ca.indicatorDialog.none'))]));
        };
        search.addEventListener('input', draw);
        const toolbar = el('div', 'ca-indicator-toolbar');
        toolbar.append(search, category);
        const actions = el('div', 'wl-actions');
        const cancel = el('button', 'btn-secondary', i18n.t('common.cancel'));
        cancel.type = 'button';
        cancel.addEventListener('click', () => dialog.close());
        actions.append(cancel);
        content.replaceChildren(el('p', 'wl-muted', i18n.t('ca.indicatorDialog.intro')), toolbar, list, actions);
        draw();
        search.focus();
      };

      const showSettings = operand => {
        const definition = catalog.byType.get(operand.type);
        if (!definition) { showList(); return; }
        const values = { ...operand.parameters };
        const settings = el('div', 'ca-indicator-settings');
        settings.append(el('h3', null, text.indicatorName(operand.type)));
        if (i18n.has(`ind.${operand.type}.description`)) settings.append(el('p', 'wl-muted', i18n.t(`ind.${operand.type}.description`)));
        const grid = el('div', 'ca-settings-grid');
        definition.inputs.forEach(input => {
          const current = values[input.key] ?? input.defaultValue;
          const label = text.inputLabel(operand.type, input.key);
          let control = null;
          switch (input.kind) {
            case 'INTEGER': case 'DECIMAL':
              control = numberInput(current, { min: input.min, max: input.max, step: input.kind === 'INTEGER' ? 1 : (input.step || 'any') },
                value => { if (value == null) delete values[input.key]; else values[input.key] = value; });
              break;
            case 'BOOLEAN':
              control = el('input');
              control.type = 'checkbox';
              control.checked = Boolean(current);
              control.addEventListener('change', () => { values[input.key] = control.checked; });
              break;
            case 'CHOICE':
              control = select((input.options || []).map(option => [option, text.choiceLabel(input.choiceSet, option)]), current,
                value => { values[input.key] = value; });
              break;
            case 'SOURCE':
              control = select(SOURCES.map(option => [option, text.choiceLabel('source', option)]), current, value => { values[input.key] = value; });
              break;
            case 'TIMEFRAME': {
              const allowed = state.interval === 'MONTHLY' ? ['chart'] : state.interval === 'WEEKLY' ? ['chart', '1mo'] : ['chart', '1wk', '1mo'];
              control = select(allowed.map(option => [option, i18n.t(`ca.timeframe.option.${option}`)]), allowed.includes(current) ? current : 'chart',
                value => { values[input.key] = value; });
              break;
            }
            case 'SYMBOL':
              control = el('input');
              control.type = 'text';
              control.maxLength = 20;
              control.value = current || '';
              control.addEventListener('input', () => { values[input.key] = control.value.trim().toUpperCase(); });
              break;
            default:
          }
          if (control) grid.append(field(label, control, input.kind === 'TIMEFRAME' ? LEARN_OPTIONS.timeframe : null));
        });
        settings.append(grid);
        let output = operand.output;
        if (definition.outputs.length > 1) {
          settings.append(field(i18n.t('ca.indicatorDialog.line'), select(definition.outputs.map(key => [key, text.outputLabel(operand.type, key)]),
            output, value => { output = value; })));
        }
        const actions = el('div', 'wl-actions');
        const back = el('button', 'btn-secondary', i18n.t('ca.indicatorDialog.back'));
        back.type = 'button';
        back.addEventListener('click', showList);
        const use = el('button', 'btn-accent', i18n.t('ca.indicatorDialog.use'));
        use.type = 'button';
        use.addEventListener('click', () => {
          // Only settings that differ from the defaults are kept, so the rule stays short and readable.
          const parameters = {};
          definition.inputs.forEach(input => {
            if (values[input.key] !== undefined && values[input.key] !== input.defaultValue && values[input.key] !== '') {
              parameters[input.key] = values[input.key];
            }
          });
          result = { kind: 'INDICATOR', type: operand.type, parameters, output };
          dialog.close();
        });
        actions.append(back, use);
        content.replaceChildren(settings, actions);
        use.focus();
      };

      if (current?.kind === 'INDICATOR') showSettings(current); else showList();
    });
  }

  // ------------------------------------------------------------------ pattern conditions

  function patternEditor(condition, refresh, rebuild) {
    const fields = [];
    fields.push(field(i18n.t('ca.builder.patternFamily'), select(['CANDLESTICK', 'ELLIOTT_WAVE', 'HARMONIC_FORMATION']
      .map(family => [family, i18n.t(`ca.kind.${family}`)]), condition.family, value => {
      condition.family = value; condition.patterns = []; condition.confirmedOnly = false; condition.stillValid = false;
      rebuild();
    })));
    fields.push(field(i18n.t('ca.builder.direction'), select(['BULLISH', 'BEARISH', 'ANY'].map(value => [value, i18n.t(`ca.direction.${value}`)]),
      condition.direction || 'ANY', value => { condition.direction = value; refresh(); }), LEARN_OPTIONS.direction));
    fields.push(field(i18n.t('ca.builder.withinCandles'), numberInput(condition.window?.candles ?? 5, { min: 1, max: catalog.maxWindow || 60, step: 1 },
      value => { condition.window = { mode: 'ANY', candles: value }; refresh(); }), LEARN_OPTIONS.detected));
    fields.push(field(i18n.t('ca.builder.minScore'), numberInput(condition.minScore, { min: 0, max: 100, step: 1, placeholder: i18n.t('ca.builder.anyScore') },
      value => { if (value == null) delete condition.minScore; else condition.minScore = value; refresh(); }), LEARN_OPTIONS.minScore));

    const names = catalog.patterns[condition.family] || [];
    const chooser = el('details', 'ca-pattern-chooser');
    const summary = el('summary');
    const updateSummary = () => {
      summary.textContent = condition.patterns?.length
        ? i18n.t('ca.builder.patternsChosen', condition.patterns.map(name => text.patternName(condition.family, name)).join(', '))
        : i18n.t('ca.builder.anyPattern');
    };
    updateSummary();
    const list = el('div', 'ca-pattern-list');
    names.forEach(name => list.append(check(text.patternName(condition.family, name), condition.patterns?.includes(name), checked => {
      const chosen = new Set(condition.patterns || []);
      if (checked) chosen.add(name); else chosen.delete(name);
      condition.patterns = names.filter(item => chosen.has(item));
      updateSummary(); refresh();
    }, text.patternTerm(condition.family, name))));
    chooser.append(summary, list);
    fields.push(field(i18n.t('ca.builder.patterns'), chooser));
    if (condition.family === 'CANDLESTICK') {
      fields.push(check(i18n.t('ca.builder.confirmedOnly'), condition.confirmedOnly, value => { condition.confirmedOnly = value; refresh(); }, 'statusConfirmed'));
    }
    if (condition.family === 'HARMONIC_FORMATION') {
      fields.push(check(i18n.t('ca.builder.stillValid'), condition.stillValid, value => { condition.stillValid = value; refresh(); }));
    }
    return fields;
  }

  function outlookEditor(condition, refresh) {
    return [
      field(i18n.t('ca.builder.direction'), select([['BUY', i18n.t('ca.outlookDirection.BUY')], ['SELL', i18n.t('ca.outlookDirection.SELL')]],
        condition.direction, value => { condition.direction = value; refresh(); })),
      field(i18n.t('ca.builder.atLeast'), select(['SLIGHT', 'MODERATE', 'STRONG'].map(value => [value, i18n.t(`ca.strength.${value}`)]),
        condition.strength, value => { condition.strength = value; refresh(); }), LEARN_OPTIONS.outlook),
      el('p', 'wl-muted ca-field-note', i18n.t('ca.builder.outlookNote'))
    ];
  }

  function activityEditor(condition, refresh) {
    const insider = condition.kind === 'INSIDER';
    const fields = [
      field(i18n.t('ca.builder.transaction'), select([['PURCHASE', i18n.t('ca.transaction.PURCHASE')], ['SALE', i18n.t('ca.transaction.SALE')]],
        condition.transaction, value => { condition.transaction = value; refresh(); })),
      field(i18n.t('ca.builder.inLastDays'), numberInput(condition.days, { min: 1, max: insider ? 730 : 365, step: 1 },
        value => { condition.days = value; refresh(); }), insider ? LEARN_OPTIONS.filingDate : LEARN_OPTIONS.disclosureDate),
      field(i18n.t('ca.builder.minPeople'), numberInput(condition.minCount ?? 1, { min: 1, max: 20, step: 1 },
        value => { condition.minCount = value ?? 1; refresh(); }), LEARN_OPTIONS.minPeople),
      field(i18n.t(insider ? 'ca.builder.minTotalInsider' : 'ca.builder.minTotalCongress'), numberInput(condition.minTotal, { min: 0, step: 1000, placeholder: i18n.t('ca.builder.anyAmount') },
        value => { if (value == null) delete condition.minTotal; else condition.minTotal = value; refresh(); }))
    ];
    const key = insider ? 'roles' : 'chambers';
    const options = insider ? ['OFFICER', 'DIRECTOR', 'OWNER'] : ['HOUSE', 'SENATE'];
    const box = el('div', 'ca-checks');
    options.forEach(option => box.append(check(i18n.t(`ca.activity.${insider ? 'role' : 'chamber'}.${option}`), (condition[key] || []).includes(option), checked => {
      const chosen = new Set(condition[key] || []);
      if (checked) chosen.add(option); else chosen.delete(option);
      condition[key] = options.filter(item => chosen.has(item));
      refresh();
    })));
    fields.push(field(i18n.t(insider ? 'ca.builder.roles' : 'ca.builder.chambers'), box));
    fields.push(el('p', 'wl-muted ca-field-note', i18n.t(insider ? 'ca.builder.insiderNote' : 'ca.builder.congressNote')));
    return fields;
  }

  // ------------------------------------------------------------------ adding conditions

  const choices = () => [
    { key: 'rsi', make: () => ({ kind: 'VALUE', subject: { kind: 'INDICATOR', type: 'RSI', parameters: {}, output: 'value' }, operator: 'BELOW',
      compare: { kind: 'VALUE', value: 30 }, window: { mode: 'LATEST' } }) },
    { key: 'maCross', make: () => ({ kind: 'VALUE', subject: { kind: 'INDICATOR', type: 'EMA', parameters: { period: 50 }, output: 'value' },
      operator: 'CROSSES_ABOVE', compare: { kind: 'INDICATOR', type: 'EMA', parameters: { period: 200 }, output: 'value' }, window: { mode: 'LATEST' } }) },
    { key: 'volume', make: () => ({ kind: 'VALUE', subject: { kind: 'INDICATOR', type: 'RELATIVE_VOLUME', parameters: {}, output: 'value' },
      operator: 'ABOVE', compare: { kind: 'VALUE', value: 2 }, window: { mode: 'LATEST' } }) },
    { key: 'breakout', make: () => ({ kind: 'VALUE', subject: { kind: 'PRICE', field: 'CLOSE' }, operator: 'NEW_HIGH', candles: 20, window: { mode: 'LATEST' } }) },
    { key: 'priceLevel', make: () => ({ kind: 'VALUE', subject: { kind: 'PRICE', field: 'CLOSE' }, operator: 'CROSSES_ABOVE',
      compare: { kind: 'VALUE', value: preview?.latestClose ? Math.round(preview.latestClose) : 100 }, window: { mode: 'LATEST' } }) },
    { key: 'priceChange', make: () => ({ kind: 'VALUE', subject: { kind: 'PRICE', field: 'CLOSE' }, operator: 'CHANGE_ABOVE', candles: 5,
      percent: 5, window: { mode: 'LATEST' } }) },
    { key: 'candlestick', make: () => ({ kind: 'PATTERN', family: 'CANDLESTICK', direction: 'BULLISH', patterns: [], window: { mode: 'ANY', candles: 3 } }) },
    { key: 'elliott', make: () => ({ kind: 'PATTERN', family: 'ELLIOTT_WAVE', direction: 'BULLISH', patterns: ['WAVE_II_END'], window: { mode: 'ANY', candles: 5 } }) },
    { key: 'harmonic', make: () => ({ kind: 'PATTERN', family: 'HARMONIC_FORMATION', direction: 'BULLISH', patterns: [], window: { mode: 'ANY', candles: 3 } }) },
    { key: 'outlook', make: () => ({ kind: 'OUTLOOK', direction: 'BUY', strength: 'MODERATE' }) },
    { key: 'insider', make: () => ({ kind: 'INSIDER', transaction: 'PURCHASE', days: 30, minCount: 1, roles: [] }) },
    { key: 'congress', make: () => ({ kind: 'CONGRESS', transaction: 'PURCHASE', days: 45, minCount: 1, chambers: [] }) },
    { key: 'anyIndicator', make: null }
  ];

  function addCondition(group) {
    const target = group || state.definition.groups.at(-1);
    const dialog = el('dialog', 'wl-dialog ca-picker');
    const heading = el('h2', null, i18n.t('ca.picker.title'));
    heading.id = 'ca-picker-title';
    dialog.setAttribute('aria-labelledby', heading.id);
    const grid = el('div', 'ca-picker-grid');
    choices().forEach(choice => {
      const option = el('button', `ca-picker-option ca-picker-${choice.key}`);
      option.type = 'button';
      option.append(el('strong', null, i18n.t(`ca.picker.${choice.key}.title`)), el('span', null, i18n.t(`ca.picker.${choice.key}.text`)));
      option.addEventListener('click', async () => {
        dialog.close();
        let condition;
        if (choice.make) condition = choice.make();
        else {
          const operand = await chooseIndicator(null);
          if (!operand) return;
          condition = { kind: 'VALUE', subject: operand, operator: 'ABOVE', window: { mode: 'LATEST' } };
          suggestCompare(condition);
        }
        target.conditions.push({ id: newId('c'), negate: false, ...condition });
        renderGroups(); changed();
        const card = nodes.groups.querySelector(`[data-condition-id="${CSS.escape(target.conditions.at(-1).id)}"]`);
        card?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        card?.querySelector('select, input, button')?.focus();
      });
      grid.append(option);
    });
    const cancel = el('button', 'btn-secondary', i18n.t('common.cancel'));
    cancel.type = 'button';
    cancel.addEventListener('click', () => dialog.close());
    const actions = el('div', 'wl-actions');
    actions.append(cancel);
    dialog.append(heading, el('p', 'wl-muted', i18n.t('ca.picker.intro')), grid, actions);
    dialog.addEventListener('close', () => dialog.remove());
    document.body.append(dialog);
    dialog.showModal();
    grid.querySelector('button')?.focus();
  }

  // ------------------------------------------------------------------ where to watch

  function renderTargets() {
    const parts = lists.length ? lists.map(list => targetRow(list)) : [el('p', 'wl-muted ca-targets-empty', i18n.t('ca.targets.noLists'))];
    if (state.pendingSymbol) {
      const pending = el('div', 'ca-pending-symbol');
      pending.append(el('p', null, state.pendingChoice
        ? i18n.t('ca.targets.pendingChosen', state.pendingSymbol, chosenLists(state.pendingChoice))
        : i18n.t('ca.targets.pending', state.pendingSymbol)));
      const actions = el('div', 'ca-pending-actions');
      const pick = el('button', 'btn-secondary', i18n.t(state.pendingChoice ? 'ca.targets.changeList' : 'ca.targets.pickList', state.pendingSymbol));
      pick.type = 'button';
      pick.addEventListener('click', async () => {
        const chosen = await chooseList(state.pendingSymbol);
        if (chosen) { state.pendingChoice = chosen; renderTargets(); changed(); }
      });
      const drop = el('button', 'btn-secondary', i18n.t('ca.targets.dropStock'));
      drop.type = 'button';
      drop.addEventListener('click', () => { state.pendingSymbol = null; state.pendingChoice = null; renderTargets(); changed(); });
      actions.append(pick, drop);
      pending.append(actions);
      parts.unshift(pending);
    }
    parts.push(singleStock());
    nodes.targets.replaceChildren(...parts);
  }

  const chooseList = symbol => choose(symbol, { mode: 'signals', signalTypes: ['CUSTOM'], texts: {
    title: i18n.t('ca.picker.title', symbol), intro: i18n.t('ca.picker.intro', symbol), submit: i18n.t('ca.picker.submit') } });

  /** The lists a pending stock goes into, by name: "Growth, a new list “Ideas”". */
  function chosenLists(choice) {
    const names = (choice.watchlistIds || []).map(id => lists.find(list => list.id === id)?.name).filter(Boolean);
    if (choice.newWatchlistName) names.push(i18n.t('ca.targets.newList', choice.newWatchlistName));
    return names.join(', ');
  }

  /** One stock without going to the watchlists first: it is added to a list (existing or new) when the alert is saved. */
  function singleStock() {
    const box = el('div', 'ca-single-stock');
    const id = 'ca-single-stock';
    const label = el('label', null, i18n.t(lists.length ? 'ca.targets.singleLabel' : 'ca.targets.singleLabelFirst'));
    label.htmlFor = id;
    const row = el('div', 'ca-test-row');
    const input = el('input');
    input.id = id;
    input.type = 'text';
    input.maxLength = 20;
    input.autocomplete = 'off';
    input.placeholder = 'MSFT';
    const add = el('button', 'btn-secondary', i18n.t('ca.targets.singleAdd'));
    add.type = 'button';
    add.addEventListener('click', async () => {
      const symbol = input.value.trim().toUpperCase();
      if (!/^[A-Z0-9.^=-]{1,20}$/.test(symbol)) { input.focus(); return; }
      const chosen = await chooseList(symbol);
      if (!chosen) return;
      state.pendingSymbol = symbol;
      state.pendingChoice = chosen;
      if (!nodes.testSymbol.value.trim()) nodes.testSymbol.value = symbol;
      renderTargets(); changed();
    });
    input.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); add.click(); } });
    row.append(input, add);
    box.append(label, row, el('p', 'wl-muted', i18n.t('ca.targets.singleHelp')));
    return box;
  }

  function targetRow(list) {
    const target = state.targets.get(list.id);
    const allowed = !list.allowedTypes || list.allowedTypes.includes('CUSTOM');
    const row = el('div', `ca-target${target ? ' is-selected' : ''}${allowed ? '' : ' is-blocked'}`);
    const toggle = el('label', 'wl-check');
    const input = el('input');
    input.type = 'checkbox';
    input.checked = Boolean(target);
    input.disabled = !allowed;
    input.addEventListener('change', () => {
      if (input.checked) state.targets.set(list.id, { watchlistId: list.id, scope: 'ALL_MEMBERS', symbols: [] });
      else state.targets.delete(list.id);
      renderTargets(); changed();
    });
    toggle.append(input, el('span', 'ca-target-name', list.name), el('small', 'wl-muted', i18n.plural('watchlists.instruments', list.instruments, list.instruments)));
    row.append(toggle);
    if (!allowed) row.append(el('p', 'wl-muted', i18n.t('ca.targets.notAllowed')));
    if (target) {
      const scope = segmented(target.scope, [['ALL_MEMBERS', i18n.t('ca.targets.allStocks')], ['SELECTED', i18n.t('ca.targets.chosenStocks')]], value => {
        target.scope = value; renderTargets(); changed();
      }, i18n.t('ca.targets.scopeAria', list.name));
      row.append(scope);
      if (target.scope === 'ALL_MEMBERS') row.append(el('p', 'wl-muted ca-target-note', i18n.t('ca.targets.allNote')));
      else row.append(memberChooser(list, target));
    }
    return row;
  }

  function memberChooser(list, target) {
    const box = el('div', 'ca-members');
    const search = el('input');
    search.type = 'search';
    search.placeholder = i18n.t('watchlists.searchPlaceholder');
    search.setAttribute('aria-label', i18n.t('watchlists.searchAria', list.name));
    const status = el('p', 'wl-muted ca-members-count', i18n.plural('ca.targets.chosenCount', target.symbols.length, target.symbols.length));
    const results = el('div', 'ca-member-list');
    let timer;
    let sequence = 0;
    const load = async () => {
      const request = ++sequence;
      try {
        const page = await api(`/api/watchlists/${list.id}/members?${new URLSearchParams({ page: 0, group: 'all', q: search.value.trim() })}`);
        if (request !== sequence) return;
        results.replaceChildren(...page.items.map(item => check(`${item.symbol} · ${item.companyName || ''}`, target.symbols.includes(item.symbol), checked => {
          const chosen = new Set(target.symbols);
          if (checked) chosen.add(item.symbol); else chosen.delete(item.symbol);
          target.symbols = [...chosen].sort();
          status.textContent = i18n.plural('ca.targets.chosenCount', target.symbols.length, target.symbols.length);
          changed();
        })));
        if (page.total > page.items.length) results.append(el('p', 'wl-muted', i18n.t('ca.targets.moreMembers', page.total - page.items.length)));
      } catch (error) { results.replaceChildren(el('p', 'wl-status', error.message)); }
    };
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(load, 250); });
    box.append(search, status, results);
    load();
    return box;
  }

  // ------------------------------------------------------------------ preview

  function snapshot() {
    return {
      name: nodes.name.value.trim(), description: nodes.description.value.trim(), interval: state.interval,
      definition: state.definition, triggerMode: state.triggerMode, quietCandles: state.quietCandles,
      outcomeCandles: state.outcomeCandles, emailEnabled: state.emailEnabled,
      targets: [...state.targets.values()].filter(target => target.scope === 'ALL_MEMBERS' || target.symbols.length),
      notifyExisting: state.notifyExisting, active: true, sourceChartAlertId: state.sourceChartAlertId
    };
  }

  function schedulePreview() {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(runPreview, 600);
  }

  async function runPreview() {
    const sequence = ++previewSequence;
    const draft = snapshot();
    if (!conditions().length) {
      preview = null;
      nodes.result.replaceChildren(el('p', 'wl-muted', i18n.t('ca.preview.addFirst')));
      renderReadouts();
      return;
    }
    nodes.result.setAttribute('aria-busy', 'true');
    try {
      const result = await api('/api/custom-alerts/preview', { method: 'POST', body: JSON.stringify({
        interval: draft.interval, definition: draft.definition, symbol: nodes.testSymbol.value.trim(), triggerMode: draft.triggerMode,
        quietCandles: draft.quietCandles, outcomeCandles: draft.outcomeCandles, targets: draft.targets, alertId: state.id }) });
      if (sequence !== previewSequence) return;
      preview = result;
      renderPreview(result);
    } catch (error) {
      if (sequence !== previewSequence) return;
      preview = null;
      const problem = el('div', 'ca-preview-problem');
      problem.append(el('strong', null, i18n.t('ca.preview.problem')), el('p', null, error.message));
      nodes.result.replaceChildren(problem);
      nodes.coverage.replaceChildren();
    } finally {
      if (sequence === previewSequence) nodes.result.removeAttribute('aria-busy');
      renderReadouts();
    }
  }

  function renderPreview(result) {
    const parts = [];
    if (!result.available) {
      parts.push(el('p', 'wl-muted', result.unavailableReason));
    } else {
      const verdict = el('div', `ca-verdict ${!result.applicable ? 'is-skipped' : result.matched ? 'is-match' : 'is-miss'}`);
      verdict.append(el('strong', null, !result.applicable ? i18n.t('ca.preview.notApplicable', result.symbol)
          : i18n.t(result.matched ? 'ca.preview.matches' : 'ca.preview.noMatch', result.symbol)),
        el('span', null, i18n.t('ca.preview.conditionsMet', result.matchedConditions, result.totalConditions)),
        el('small', 'wl-muted', i18n.t('ca.preview.asOf', i18n.date(result.latestCandle * 1000, { day: 'numeric', month: 'short', year: 'numeric' }),
          text.number(result.latestClose))));
      parts.push(verdict, historyStrip(result));
    }
    nodes.result.replaceChildren(...parts);
    renderCoverage(result.coverage);
  }

  function historyStrip(result) {
    const box = el('div', 'ca-history');
    const heading = el('p', 'ca-history-title');
    const label = el('span', 'learn-term', i18n.plural('ca.preview.history', result.history.length, result.history.length, result.replayedCandles));
    label.dataset.learn = LEARN_OPTIONS.history;
    label.dataset.learnTitle = i18n.t('ca.preview.historyTitle');
    heading.append(label);
    box.append(heading);
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 300 34');
    svg.setAttribute('class', 'ca-history-strip');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', i18n.plural('ca.preview.history', result.history.length, result.history.length, result.replayedCandles));
    const line = document.createElementNS(svg.namespaceURI, 'line');
    Object.entries({ x1: 2, x2: 298, y1: 24, y2: 24, class: 'ca-history-axis' }).forEach(([key, value]) => line.setAttribute(key, value));
    svg.append(line);
    const span = Math.max(1, result.latestCandle - result.firstReplayed);
    result.history.forEach(point => {
      const tick = document.createElementNS(svg.namespaceURI, 'line');
      const x = 2 + ((point.timestamp - result.firstReplayed) / span) * 296;
      const toneClass = point.outcomeReturn == null ? 'pending' : point.outcomeReturn >= 0 ? 'up' : 'down';
      Object.entries({ x1: x, x2: x, y1: 8, y2: 24, class: `ca-history-tick ${toneClass}` }).forEach(([key, value]) => tick.setAttribute(key, value));
      const title = document.createElementNS(svg.namespaceURI, 'title');
      title.textContent = `${i18n.date(point.timestamp * 1000, { day: 'numeric', month: 'short', year: 'numeric' })}${point.outcomeReturn == null ? ''
        : ` · ${point.outcomeReturn >= 0 ? '+' : ''}${text.number(point.outcomeReturn)}%`}`;
      tick.append(title);
      svg.append(tick);
    });
    box.append(svg);
    const range = el('div', 'ca-history-range');
    range.append(el('span', null, i18n.date(result.firstReplayed * 1000, { month: 'short', year: 'numeric' })),
      el('span', null, i18n.date(result.latestCandle * 1000, { month: 'short', year: 'numeric' })));
    box.append(range);
    if (result.medianReturn != null) {
      const statsLine = el('p', 'ca-history-stats', i18n.t('ca.preview.after', state.outcomeCandles,
        `${result.medianReturn >= 0 ? '+' : ''}${text.number(result.medianReturn)}%`, text.number(result.positiveShare)));
      statsLine.classList.add('learn-term');
      statsLine.dataset.learn = LEARN_OPTIONS.outcome;
      statsLine.dataset.learnTitle = i18n.t('ca.preview.afterTitle');
      box.append(statsLine);
    } else if (!result.history.length) {
      box.append(el('p', 'wl-muted', i18n.t('ca.preview.noHistory')));
    }
    box.append(el('p', 'ca-disclaimer', i18n.t('ca.preview.disclaimer')));
    return box;
  }

  function renderCoverage(coverage) {
    if (!coverage) { nodes.coverage.replaceChildren(); return; }
    const box = el('div', 'ca-coverage');
    if (!coverage.stocks) {
      box.append(el('p', 'ca-coverage-warning', i18n.t(state.pendingSymbol && state.pendingChoice ? 'ca.coverage.pendingOnly' : 'ca.coverage.none')));
    } else {
      box.append(el('p', null, i18n.plural('ca.coverage.stocks', coverage.stocks, coverage.stocks)));
      if (coverage.maximum) {
        const used = coverage.tracked + coverage.newToMonitoring;
        const meter = el('div', `ca-meter${used > coverage.maximum ? ' is-over' : ''}`);
        const fill = el('span');
        fill.style.width = `${Math.min(100, Math.round(used * 100 / coverage.maximum))}%`;
        meter.append(fill);
        const label = el('p', 'ca-meter-label learn-term', i18n.t('ca.coverage.capacity', coverage.newToMonitoring, used, coverage.maximum));
        label.dataset.learn = LEARN_OPTIONS.capacity;
        label.dataset.learnTitle = i18n.t('ca.coverage.capacityTitle');
        box.append(meter, label);
        if (used > coverage.maximum) box.append(el('p', 'ca-coverage-warning', i18n.t('ca.coverage.over', coverage.maximum)));
      }
      if (coverage.nonStocks) box.append(el('p', 'ca-coverage-note', i18n.plural('ca.coverage.nonStocks', coverage.nonStocks, coverage.nonStocks)));
    }
    nodes.coverage.replaceChildren(box);
  }

  function applyReadout(node, condition) {
    const found = preview?.conditions?.find(item => item.id === condition.id);
    if (!found || !preview.available) { node.replaceChildren(); node.className = 'ca-readout'; return; }
    node.className = `ca-readout ${!found.applicable ? 'is-skipped' : found.passed ? 'is-met' : 'is-unmet'}`;
    const facts = found.facts || {};
    let detail = '';
    if (!found.applicable) detail = i18n.t('ca.readout.notApplicable');
    else if (condition.kind === 'VALUE' && facts.value != null) detail = i18n.t('ca.readout.value', text.number(facts.value));
    else if (condition.kind === 'PATTERN' && facts.pattern) detail = i18n.plural('ca.readout.candlesAgo', facts.candlesAgo, facts.candlesAgo);
    else if ((condition.kind === 'INSIDER' || condition.kind === 'CONGRESS') && facts.filings != null) {
      detail = i18n.plural('ca.readout.filings', facts.filings, facts.filings);
    } else if (condition.kind === 'OUTLOOK' && facts.classification) detail = facts.classification;
    const status = i18n.t(!found.applicable ? 'ca.readout.skipped' : found.passed ? 'ca.readout.met' : 'ca.readout.unmet');
    node.replaceChildren(el('strong', null, status), detail ? el('span', null, detail) : '');
    node.setAttribute('aria-label', `${preview.symbol}: ${status}${detail ? `, ${detail}` : ''}`);
  }

  function renderReadouts() {
    nodes.groups.querySelectorAll('[data-readout]').forEach(node => {
      const condition = conditions().find(item => item.id === node.dataset.readout);
      if (condition) applyReadout(node, condition);
    });
  }

  // ------------------------------------------------------------------ starting points

  function renderStart() {
    if (state.id || conditions().length) { nodes.start.replaceChildren(); return; }
    const box = el('div', 'ca-start-box');
    box.append(el('p', null, i18n.t('ca.builder.startWith')));
    const row = el('div', 'ca-start-templates');
    text.templates.forEach(template => {
      const button = el('button', 'ca-start-template', i18n.t(`ca.template.${template.id}.name`));
      button.type = 'button';
      button.title = i18n.t(`ca.template.${template.id}.text`);
      button.addEventListener('click', () => { useTemplate(template.id); });
      row.append(button);
    });
    box.append(row, el('p', 'wl-muted', i18n.t('ca.builder.orScratch')));
    nodes.start.replaceChildren(box);
  }

  function useTemplate(id) {
    const template = text.fromTemplate(id);
    if (!template) return;
    if (!nodes.name.value.trim()) nodes.name.value = template.name;
    if (!nodes.description.value.trim()) nodes.description.value = template.description;
    state.interval = template.interval;
    state.triggerMode = template.triggerMode;
    state.definition = template.definition;
    syncBasics();
    renderStart(); renderGroups(); changed();
  }

  /** A chart alert on an indicator or a horizontal line becomes the first condition. */
  async function fromChartAlert(id) {
    const alert = await api(`/api/chart-alerts/${id}`);
    state.sourceChartAlertId = alert.id;
    state.interval = ({ '1d': 'DAILY', '1wk': 'WEEKLY', '1mo': 'MONTHLY' })[alert.interval] || 'DAILY';
    nodes.name.value = alert.label;
    const operator = ({ CROSSES_UP: 'CROSSES_ABOVE', CROSSES_DOWN: 'CROSSES_BELOW', CROSSES: 'CROSSES_ABOVE', ABOVE: 'ABOVE', BELOW: 'BELOW',
      INSIDE: 'BETWEEN', ENTERS: 'BETWEEN', OUTSIDE: 'OUTSIDE', EXITS: 'OUTSIDE' })[alert.condition] || 'ABOVE';
    let condition;
    if (alert.sourceKind === 'INDICATOR') {
      const definition = alert.definition;
      const target = (definition.indicators || []).find(item => item.id === definition.target) || definition.indicators?.at(-1);
      const subject = { kind: 'INDICATOR', type: target.type, parameters: target.parameters || {}, output: definition.output };
      condition = { kind: 'VALUE', subject, operator, window: { mode: 'LATEST' } };
      if (definition.compare === 'VALUE') condition.compare = { kind: 'VALUE', value: definition.value };
      else if (definition.compare === 'PRICE') condition.compare = { kind: 'PRICE', field: 'CLOSE' };
      else if (definition.compare === 'OUTPUT') condition.compare = { ...subject, output: definition.otherOutput };
      else { condition.lower = definition.lower; condition.upper = definition.upper; }
    } else {
      condition = { kind: 'VALUE', subject: { kind: 'PRICE', field: 'CLOSE' }, operator, compare: { kind: 'VALUE', value: alert.definition.price },
        window: { mode: 'LATEST' } };
    }
    state.definition.groups[0].conditions.push({ id: newId('c'), negate: false, ...condition });
    return alert.symbol;
  }

  // ------------------------------------------------------------------ setup and saving

  function syncBasics() {
    nodes.interval.value = state.interval;
    form.querySelectorAll('input[name="triggerMode"]').forEach(input => { input.checked = input.value === state.triggerMode; });
    nodes.quiet.value = String(state.quietCandles);
    nodes.outcome.value = String(state.outcomeCandles);
    nodes.email.checked = state.emailEnabled;
    nodes.notifyExisting.checked = state.notifyExisting;
  }

  function bindBasics() {
    nodes.name.addEventListener('input', changed);
    nodes.description.addEventListener('input', () => { dirty = true; });
    nodes.interval.addEventListener('change', () => { state.interval = nodes.interval.value; changed(); });
    form.querySelectorAll('input[name="triggerMode"]').forEach(input => input.addEventListener('change', () => {
      if (input.checked) { state.triggerMode = input.value; changed(); }
    }));
    nodes.quiet.addEventListener('input', () => { state.quietCandles = Number(nodes.quiet.value) || 0; changed(); });
    nodes.outcome.addEventListener('input', () => { state.outcomeCandles = Number(nodes.outcome.value) || 10; changed(); });
    nodes.email.addEventListener('change', () => { state.emailEnabled = nodes.email.checked; dirty = true; });
    nodes.notifyExisting.addEventListener('change', () => { state.notifyExisting = nodes.notifyExisting.checked; dirty = true; });
    form.querySelector('[data-ca-add-condition]').addEventListener('click', () => addCondition(null));
    form.querySelector('[data-ca-add-group]').addEventListener('click', () => {
      state.definition.groups.push({ id: newId('g'), match: 'ALL', conditions: [] });
      addCondition(state.definition.groups.at(-1));
      renderGroups();
    });
    form.querySelector('[data-ca-test]').addEventListener('click', runPreview);
    nodes.testSymbol.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); runPreview(); } });
    window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
    form.addEventListener('submit', save);
  }

  async function save(event) {
    event.preventDefault();
    const draft = snapshot();
    if (!conditions().length) { nodes.status.textContent = i18n.t('ca.save.needCondition'); return; }
    if (!draft.name) {
      nodes.name.value = text.describe(conditions()[0], catalog.byType).slice(0, 80);
      draft.name = nodes.name.value;
    }
    nodes.save.disabled = true;
    nodes.status.textContent = i18n.t('ca.save.saving');
    try {
      const saved = await api(state.id ? `/api/custom-alerts/${state.id}` : '/api/custom-alerts',
        { method: state.id ? 'PUT' : 'POST', body: JSON.stringify(draft) });
      // From here on the alert exists: a retry after a failed stock step updates it instead of creating a copy.
      state.id = saved.id;
      if (state.pendingSymbol && state.pendingChoice) {
        await api(`/api/custom-alerts/for-stock/${encodeURIComponent(state.pendingSymbol)}`, { method: 'PUT', body: JSON.stringify({
          apply: [saved.id], remove: [], watchlistIds: state.pendingChoice.watchlistIds, newWatchlistName: state.pendingChoice.newWatchlistName }) });
      }
      dirty = false;
      window.location.assign(`/custom-alerts?saved=${saved.id}`);
    } catch (error) {
      nodes.status.textContent = error.message;
      nodes.save.disabled = false;
      nodes.status.focus?.();
    }
  }

  async function init() {
    bindBasics();
    try {
      [catalog, lists] = await Promise.all([text.catalog(), api('/api/watchlists')]);
    } catch (error) {
      nodes.status.textContent = error.message;
      return;
    }
    const startSymbol = page.dataset.startSymbol || null;
    try {
      if (state.id) {
        const alert = await api(`/api/custom-alerts/${state.id}`);
        nodes.name.value = alert.name;
        nodes.description.value = alert.description || '';
        Object.assign(state, { interval: alert.interval, triggerMode: alert.triggerMode, quietCandles: alert.quietCandles,
          outcomeCandles: alert.outcomeCandles, emailEnabled: alert.emailEnabled, definition: alert.definition });
        alert.targets.forEach(target => state.targets.set(target.watchlistId, { watchlistId: target.watchlistId, scope: target.scope,
          symbols: [...target.symbols] }));
        const first = alert.targets.find(target => target.symbols.length);
        nodes.testSymbol.value = first?.symbols[0] || '';
      } else if (page.dataset.startTemplate) {
        useTemplate(page.dataset.startTemplate);
      } else if (page.dataset.startChartAlert) {
        nodes.testSymbol.value = await fromChartAlert(Number(page.dataset.startChartAlert));
      }
    } catch (error) {
      nodes.status.textContent = error.message;
    }
    const symbol = startSymbol || (state.sourceChartAlertId ? nodes.testSymbol.value : null);
    if (symbol) {
      nodes.testSymbol.value = symbol;
      const memberships = await api(`/api/watchlists/memberships/${encodeURIComponent(symbol)}`).catch(() => []);
      memberships.forEach(id => {
        const list = lists.find(item => item.id === id);
        if (list && (!list.allowedTypes || list.allowedTypes.includes('CUSTOM'))) state.targets.set(id, { watchlistId: id, scope: 'SELECTED', symbols: [symbol] });
      });
      if (!state.targets.size) state.pendingSymbol = symbol;
    }
    const startList = Number(page.dataset.startWatchlist);
    if (startList && lists.some(list => list.id === startList)) state.targets.set(startList, { watchlistId: startList, scope: 'ALL_MEMBERS', symbols: [] });
    if (!nodes.testSymbol.value) {
      // A stock from the first chosen list, so the preview has something to show.
      const first = [...state.targets.values()][0];
      if (first) {
        const members = await api(`/api/watchlists/${first.watchlistId}/members?page=0&group=all&q=`).catch(() => null);
        nodes.testSymbol.value = first.symbols[0] || members?.items?.[0]?.symbol || '';
      }
    }
    syncBasics();
    renderStart();
    renderGroups();
    renderTargets();
    nodes.summary.textContent = text.summary(snapshot(), catalog.byType);
    dirty = Boolean(page.dataset.startTemplate || page.dataset.startChartAlert);
    runPreview();
  }

  // Deferred scripts run in document order, so the navigation's request helper may not exist yet.
  if (window.StockWatchLists) init();
  else document.addEventListener('DOMContentLoaded', init, { once: true });
})();
