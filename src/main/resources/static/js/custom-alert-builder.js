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
  const LEARN_OPTIONS = { capacity: 'customAlerts.capacity', confirmed: 'customAlerts.confirmed', detected: 'customAlerts.detected', direction: 'customAlerts.direction', disclosureDate: 'customAlerts.disclosureDate', filingDate: 'customAlerts.filingDate', history: 'customAlerts.history', minPeople: 'customAlerts.minPeople', minScore: 'customAlerts.minScore', negate: 'customAlerts.negate', operator: 'customAlerts.operator', outcome: 'customAlerts.outcome', outlook: 'customAlerts.outlook', status: 'customAlerts.status', timeframe: 'customAlerts.timeframe', window: 'customAlerts.window', inOrder: 'customAlerts.inOrder', inARow: 'customAlerts.inARow', within: 'customAlerts.within', span: 'customAlerts.span', completed: 'customAlerts.completed' };
  const PRICE_FIELDS = ['CLOSE', 'OPEN', 'HIGH', 'LOW', 'VOLUME'];
  const SOURCES = ['close', 'open', 'high', 'low', 'hl2', 'hlc3', 'ohlc4', 'hlcc4'];
  const COMPARISONS = ['ABOVE', 'BELOW', 'CROSSES_ABOVE', 'CROSSES_BELOW'];
  const OPERATORS = ['ABOVE', 'BELOW', 'CROSSES_ABOVE', 'CROSSES_BELOW', 'BETWEEN', 'OUTSIDE', 'RISING', 'FALLING',
    'CHANGE_ABOVE', 'CHANGE_BELOW', 'NEW_HIGH', 'NEW_LOW'];

  const nodes = {
    name: form.querySelector('[data-ca-name]'), description: form.querySelector('[data-ca-description]'),
    intervals: form.querySelector('[data-ca-intervals]'), testInterval: form.querySelector('[data-ca-test-interval]'),
    groups: form.querySelector('[data-ca-groups]'),
    quiet: form.querySelector('[data-ca-quiet]'), outcome: form.querySelector('[data-ca-outcome]'),
    email: form.querySelector('[data-ca-email]'), notifyExisting: form.querySelector('[data-ca-notify-existing]'),
    targets: form.querySelector('[data-ca-targets]'), start: form.querySelector('[data-ca-start]'),
    summary: form.querySelector('[data-ca-summary]'), result: form.querySelector('[data-ca-result]'),
    coverage: form.querySelector('[data-ca-coverage]'), testSymbol: form.querySelector('[data-ca-test-symbol]'),
    status: form.querySelector('[data-ca-status]'), save: form.querySelector('[data-ca-save]')
  };

  const state = {
    id: page.dataset.alertId ? Number(page.dataset.alertId) : null,
    // The closes the alert is checked after, shortest first; the preview tests one of them at a time.
    intervals: ['DAILY'], testInterval: 'DAILY', triggerMode: 'BECOMES_TRUE', quietCandles: 0, outcomeCandles: 10, emailEnabled: true,
    definition: { version: 1, match: 'ALL', groups: [{ id: 'g1', match: 'ALL', conditions: [] }] },
    targets: new Map(), notifyExisting: false, sourceChartAlertId: null, pendingSymbol: null, pendingChoice: null
  };
  let catalog = { indicators: [], byType: new Map(), patterns: {}, maxConditions: 12, maxGroups: 4 };
  let lists = [];
  let dirty = false;
  let preview = null;
  let previewTimer = null;
  let previewSequence = 0;
  // While a stock's Elliott history is prepared in the background, the preview re-runs itself (up to ten minutes).
  let elliottRetry = null;
  let elliottRetries = 0;
  let counter = 0;
  const newId = prefix => `${prefix}${Date.now().toString(36)}${(counter++).toString(36)}`;
  const conditions = () => state.definition.groups.flatMap(group => group.conditions);
  const groupOf = condition => state.definition.groups.find(group => group.conditions.includes(condition));
  // Steps of a sequence ("in order" group) are read candle by candle: the outlook (latest candle only) and filings
  // (calendar days) cannot be, and "no pattern on this candle" is true almost always.
  const canBeStep = condition => (condition.kind === 'VALUE' || condition.kind === 'PATTERN') && !(condition.kind === 'PATTERN' && condition.negate);
  // Per sequence: a note to show in it, its span input, and whether the user set the span (otherwise it follows the steps).
  const groupNotes = new WeakMap();
  const spanInputs = new WeakMap();
  const spanWarnings = new WeakMap();
  const spanTouched = new WeakSet();
  // A sequence switched to all/any keeps its settings here, so switching back to "in order" restores them.
  const sequenceSettings = new WeakMap();

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
      const box = groupBox(group, groupIndex);
      box.dataset.groupId = group.id;
      parts.push(box);
    });
    if (!conditions().length) {
      const empty = el('div', 'ca-rule-empty');
      empty.append(el('strong', null, i18n.t('ca.builder.emptyTitle')), el('p', 'wl-muted', i18n.t('ca.builder.emptyText')));
      parts.push(empty);
    }
    nodes.groups.replaceChildren(...parts);
    form.querySelector('[data-ca-add-group]').disabled = groups.length >= catalog.maxGroups || !conditions().length;
    // A plain condition goes into the last plain group, or a new one after a sequence.
    form.querySelector('[data-ca-add-condition]').disabled = conditions().length >= catalog.maxConditions
      || (groups.at(-1).match === 'THEN' && groups.length >= catalog.maxGroups && !groups.some(group => group.match !== 'THEN'));
  }

  function groupBox(group, groupIndex) {
    const groups = state.definition.groups;
    const ordered = group.match === 'THEN';
    const box = el('section', `ca-group${groups.length > 1 ? ' is-grouped' : ''}${ordered ? ' is-sequence' : ''}`);
    box.setAttribute('aria-label', i18n.t('ca.builder.groupAria', groupIndex + 1));
    if (groups.length > 1 || group.conditions.length > 1 || ordered) {
      const header = el('div', 'ca-group-header');
      if (groups.length > 1) header.append(el('strong', null, i18n.t('ca.builder.groupTitle', groupIndex + 1)));
      if (group.conditions.length > 1 || ordered) {
        header.append(segmented(group.match, [['ALL', i18n.t('ca.builder.allConditions')], ['ANY', i18n.t('ca.builder.anyCondition')],
          ['THEN', i18n.t('ca.builder.inOrder')]], value => setMatch(group, value), i18n.t('ca.builder.groupMatchAria', groupIndex + 1)));
        const hint = el('span', 'wl-muted', i18n.t(ordered ? 'ca.builder.inOrderHint' : group.match === 'ANY' ? 'ca.builder.anyHint' : 'ca.builder.allHint'));
        if (ordered) hint.append(' ', learnHelp(LEARN_OPTIONS.inOrder, i18n.t('ca.builder.inOrder')));
        header.append(hint);
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
    const note = groupNotes.get(group);
    if (note) {
      const message = el('p', 'wl-muted ca-sequence-note', note);
      message.setAttribute('role', 'status');
      box.append(message);
    }
    group.conditions.forEach((condition, index) => {
      if (index > 0) box.append(ordered ? connector(group, condition, index) : el('div', 'ca-condition-joiner', i18n.t(group.match === 'ANY' ? 'ca.badge.or' : 'ca.badge.and')));
      box.append(conditionCard(condition, group, index));
    });
    if (ordered) {
      box.append(...sequenceFooter(group));
    } else if (groups.length > 1) {
      const add = el('button', 'btn-secondary ca-add-here', i18n.t('ca.builder.addToGroup'));
      add.type = 'button';
      add.disabled = conditions().length >= catalog.maxConditions;
      add.addEventListener('click', () => addCondition(group));
      box.append(add);
    }
    return box;
  }

  function learnHelp(key, title) {
    const help = el('span', 'learn-help', '?');
    help.dataset.learn = key;
    help.dataset.learnTitle = title;
    return help;
  }

  /** A switch redraws the rule: keyboard focus goes back to the choice now pressed, instead of dropping to the page. */
  function refocus(group, area) {
    nodes.groups.querySelector(`[data-group-id="${CSS.escape(group.id)}"] ${area} .ca-segmented button[aria-pressed="true"]`)?.focus();
  }

  /**
   * A number field cleared while typing keeps the last value it had (its handler ignores the empty field); when the
   * user leaves it empty, it shows that value again rather than looking blank.
   */
  function restoreWhenBlank(input, current) {
    input.addEventListener('change', () => { if (input.value === '') input.value = String(current()); });
    return input;
  }

  // ------------------------------------------------------------------ sequences

  /**
   * A condition becomes a step. "On each of the last N candles" becomes N candles in a row; any other window is kept
   * aside (the server ignores it in a step) so the condition gets it back if it leaves. True when a window stops counting.
   */
  function enterSequence(condition, first) {
    const every = condition.kind === 'VALUE' && condition.window?.mode === 'EVERY' ? condition.window.candles : 1;
    const candles = text.stepCandles({ ...condition, step: { candles: condition.step?.candles || every } });
    // A rise or fall counts its own candles, so its "on each of the last N" does not carry over either.
    const dropped = condition.kind === 'VALUE' ? condition.window?.mode === 'ANY' && condition.window.candles > 1 || every > candles
      : (condition.window?.candles || 1) > 1;
    condition.step = first ? { candles } : { candles, within: condition.step?.within || 5 };
    return dropped;
  }

  /** A note that refused something goes once the user does something else; the note about removed windows stays. */
  function clearRefusal(group) {
    if (groupNotes.get(group) !== i18n.t('ca.builder.windowsDropped')) groupNotes.delete(group);
  }

  /** A step leaves its sequence: candles in a row become "on each of the last N candles"; a pattern gets a window again. */
  function leaveSequence(condition) {
    const candles = condition.step?.candles || 1;
    delete condition.step;
    if (condition.kind === 'VALUE' && candles > 1 && !['CROSSES_ABOVE', 'CROSSES_BELOW', 'NEW_HIGH', 'NEW_LOW'].includes(condition.operator)) {
      condition.window = { mode: 'EVERY', candles };
    } else if (condition.kind === 'PATTERN' && (condition.window?.candles || 1) <= 1) {
      condition.window = { mode: 'ANY', candles: 3 };
    }
  }

  /** The first step has no allowance, every later one has; patterns, crosses, rises and falls count one candle. */
  function normalizeSteps(group) {
    if (group.match !== 'THEN') return;
    group.conditions.forEach((condition, index) => {
      const candles = text.stepCandles(condition);
      condition.step = index ? { candles, within: condition.step?.within || 5 } : { candles };
    });
    syncSpan(group);
  }

  /**
   * Unless the user set it, the span follows the steps; it never drops below what the steps need, nor goes past the
   * longest span allowed. Steps that need more than that are told so under the sequence.
   */
  function syncSpan(group) {
    if (group.match !== 'THEN') return;
    const longest = catalog.maxSpan || 120;
    const needed = group.conditions.reduce((sum, condition) => sum + text.stepCandles(condition), 0);
    group.span = Math.min(longest, spanTouched.has(group) ? Math.max(group.span || 0, needed) : text.defaultSpan(group));
    const input = spanInputs.get(group);
    if (input && document.activeElement !== input) input.value = String(group.span);
    const warning = spanWarnings.get(group);
    if (warning) warning.textContent = needed > longest ? i18n.t('ca.builder.spanTooLong', needed, longest) : '';
  }

  /** Sequences loaded from a saved alert or a template keep a span that differs from the default as the user's own. */
  function adoptSequences() {
    state.definition.groups.forEach(group => {
      if (group.match === 'THEN' && group.span != null && group.span !== text.defaultSpan(group)) spanTouched.add(group);
    });
  }

  function setMatch(group, match) {
    if (match === group.match) return;
    groupNotes.delete(group);
    if (match === 'THEN') {
      if (!group.conditions.every(canBeStep)) {
        groupNotes.set(group, i18n.t('ca.builder.inOrderBlocked'));
        renderGroups();
        refocus(group, '.ca-group-header');
        return;
      }
      // Back to "in order" after trying all/any: the sequence's own settings come back as they were.
      const kept = sequenceSettings.get(group);
      let dropped = false;
      group.conditions.forEach((condition, index) => {
        if (kept?.steps.has(condition)) condition.step = { ...kept.steps.get(condition) };
        dropped = enterSequence(condition, index === 0) || dropped;
      });
      group.window = kept ? { ...kept.window } : { mode: 'LATEST' };
      if (kept) {
        group.span = kept.span;
        if (kept.touched) spanTouched.add(group);
      }
      if (dropped) groupNotes.set(group, i18n.t('ca.builder.windowsDropped'));
    } else if (group.match === 'THEN') {
      sequenceSettings.set(group, { span: group.span, window: { ...group.window }, touched: spanTouched.has(group),
        steps: new Map(group.conditions.map(condition => [condition, { ...condition.step }])) });
      group.conditions.forEach(leaveSequence);
      delete group.span;
      delete group.window;
      spanTouched.delete(group);
    }
    group.match = match;
    if (match === 'THEN') normalizeSteps(group);
    renderGroups(); changed();
    refocus(group, '.ca-group-header');
  }

  /** Between two steps: "↓ then, within [5] candles", editable in place. */
  function connector(group, condition, index) {
    const row = el('div', 'ca-step-connector');
    const within = () => condition.step?.within ?? 5;
    const unit = el('span', null, i18n.plural('ca.builder.withinUnit', within()));
    const input = restoreWhenBlank(numberInput(within(), { min: 1, max: catalog.maxWindow || 60, step: 1 }, value => {
      if (value == null) return;
      condition.step = { ...condition.step, within: value };
      unit.textContent = i18n.plural('ca.builder.withinUnit', within());
      syncSpan(group); changed();
    }), within);
    input.setAttribute('aria-label', i18n.t('ca.builder.thenAria', index + 1, index));
    const arrow = el('span', 'ca-step-arrow', '↓');
    arrow.setAttribute('aria-hidden', 'true');
    row.append(arrow, el('span', null, i18n.t('ca.builder.thenWithin')), input, unit, learnHelp(LEARN_OPTIONS.within, i18n.t('ca.builder.thenWithin')));
    return row;
  }

  /** Under the steps: the span, when the sequence must have completed, the live reading, and the next step. */
  function sequenceFooter(group) {
    const parts = [];
    const footer = el('div', 'ca-sequence-footer');
    const span = restoreWhenBlank(numberInput(group.span ?? text.defaultSpan(group), { min: 2, max: catalog.maxSpan || 120, step: 1 }, value => {
      if (value == null) return;
      spanTouched.add(group);
      group.span = value;
      changed();
    }), () => group.span);
    span.setAttribute('aria-label', i18n.t('ca.builder.spanAria'));
    spanInputs.set(group, span);
    const spanUnit = el('span', 'ca-unit');
    spanUnit.append(span, el('span', null, i18n.t('ca.builder.thenUnit')));
    footer.append(field(i18n.t('ca.builder.span'), spanUnit, LEARN_OPTIONS.span));
    const recent = group.window?.mode === 'ANY';
    const completed = el('div', 'ca-sequence-completed');
    completed.append(segmented(recent ? 'ANY' : 'LATEST', [['LATEST', i18n.t('ca.builder.completedLatest')], ['ANY', i18n.t('ca.builder.completedRecent')]], value => {
      group.window = value === 'ANY' ? { mode: 'ANY', candles: group.window?.candles > 1 ? group.window.candles : 3 } : { mode: 'LATEST' };
      renderGroups(); changed();
      refocus(group, '.ca-sequence-completed');
    }, i18n.t('ca.builder.completedAria')));
    if (recent) {
      const candles = restoreWhenBlank(numberInput(group.window.candles, { min: 1, max: catalog.maxWindow || 60, step: 1 }, value => {
        if (value == null) return;
        group.window = { mode: 'ANY', candles: value }; changed();
      }), () => group.window.candles);
      candles.setAttribute('aria-label', i18n.t('ca.builder.completedCandles'));
      completed.append(candles);
    }
    footer.append(field(i18n.t('ca.builder.completed'), completed, LEARN_OPTIONS.completed));
    const readout = el('p', 'ca-sequence-readout');
    readout.dataset.groupReadout = group.id;
    readout.setAttribute('aria-live', 'polite');
    const warning = el('p', 'ca-sequence-note ca-sequence-warning ca-span-warning');
    warning.setAttribute('role', 'status');
    spanWarnings.set(group, warning);
    footer.append(readout, warning);
    parts.push(footer);
    syncSpan(group);
    if (group.conditions.length < 2) parts.push(el('p', 'ca-sequence-note ca-sequence-warning', i18n.t('ca.builder.needSteps')));
    if (state.definition.groups.length < 2) parts.push(el('p', 'wl-muted ca-sequence-note', i18n.t('ca.builder.sequenceHint')));
    if (group.conditions.length < (catalog.maxSteps || 4)) {
      const add = el('button', 'btn-secondary ca-add-here', i18n.t('ca.builder.addStep'));
      add.type = 'button';
      add.disabled = conditions().length >= catalog.maxConditions;
      add.addEventListener('click', () => addCondition(group));
      parts.push(add);
    }
    applyGroupReadout(readout, group);
    return parts;
  }

  /**
   * Whether "Then…" keeps the rule's meaning: a lone condition becomes a sequence in place; one of several moves into a
   * new group, which only means the same when its group and the groups are both joined with "all".
   */
  function canStartSequence(group) {
    return group.conditions.length === 1 || group.match === 'ALL' && (state.definition.groups.length === 1 || state.definition.match === 'ALL');
  }

  /** "Then…": the condition becomes step 1 of a sequence (its group, or a new group after it), and step 2 is chosen next. */
  function startSequence(condition, group) {
    const groups = state.definition.groups;
    if (!canStartSequence(group)) {
      groupNotes.set(group, i18n.t('ca.builder.thenBlocked'));
      renderGroups();
      return;
    }
    groupNotes.delete(group);
    let sequence = group;
    if (group.conditions.length > 1) {
      // Until now the only group, so its join with the new group was never shown: it is "all", as the rule meant.
      if (groups.length === 1) state.definition.match = 'ALL';
      group.conditions.splice(group.conditions.indexOf(condition), 1);
      sequence = { id: newId('g'), match: 'THEN', conditions: [condition] };
      groups.splice(groups.indexOf(group) + 1, 0, sequence);
    } else {
      group.match = 'THEN';
    }
    if (enterSequence(condition, true)) groupNotes.set(sequence, i18n.t('ca.builder.windowsDropped'));
    sequence.window = { mode: 'LATEST' };
    syncSpan(sequence);
    renderGroups(); changed();
    addCondition(sequence);
  }

  /** Groups a cancelled picker left empty go away; a lone empty sequence turns back into a plain group. */
  function tidyGroups() {
    const groups = state.definition.groups;
    for (let index = groups.length - 1; index >= 0 && groups.length > 1; index--) if (!groups[index].conditions.length) groups.splice(index, 1);
    const only = groups[0];
    if (groups.length === 1 && !only.conditions.length && only.match === 'THEN') {
      only.match = 'ALL';
      delete only.span;
      delete only.window;
    }
  }

  const kindLabel = condition => i18n.t(`ca.kind.${condition.kind === 'VALUE' && condition.subject?.kind === 'PRICE' ? 'PRICE'
    : condition.kind === 'VALUE' ? 'INDICATOR' : condition.kind === 'PATTERN' ? condition.family : condition.kind}`);
  const tone = condition => condition.kind === 'VALUE' ? 'value' : condition.kind === 'PATTERN' ? 'pattern'
    : condition.kind === 'OUTLOOK' ? 'outlook' : 'activity';

  function conditionCard(condition, group, index) {
    const ordered = group.match === 'THEN';
    const describeCard = () => ordered ? text.describeStep(condition, catalog.byType) : text.describe(condition, catalog.byType);
    const card = el('article', `ca-condition ca-tone-${tone(condition)}${condition.negate ? ' is-negated' : ''}`);
    card.dataset.conditionId = condition.id;
    const header = el('div', 'ca-condition-header');
    const label = el('span', 'ca-kind', kindLabel(condition));
    const sentence = el('p', 'ca-condition-sentence', describeCard());
    const readout = el('span', 'ca-readout');
    readout.dataset.readout = condition.id;
    const tools = el('div', 'ca-condition-tools');
    const groups = state.definition.groups;
    // A pattern step cannot be reversed: "no pattern on this candle" holds almost always.
    if (!(ordered && condition.kind === 'PATTERN')) {
      const negate = el('button', 'ca-negate', i18n.t(condition.negate ? 'ca.builder.negated' : 'ca.builder.negate'));
      negate.type = 'button';
      negate.setAttribute('aria-pressed', String(Boolean(condition.negate)));
      negate.classList.add('learn-control');
      negate.dataset.learn = LEARN_OPTIONS.negate;
      negate.dataset.learnTitle = i18n.t('ca.builder.negate');
      negate.addEventListener('click', () => { condition.negate = !condition.negate; renderGroups(); changed(); });
      tools.append(negate);
    }
    if (!ordered && canBeStep(condition)) {
      const then = el('button', 'ca-negate ca-then', i18n.t('ca.builder.then'));
      then.type = 'button';
      then.title = i18n.t('ca.builder.thenTitle');
      then.disabled = conditions().length >= catalog.maxConditions || (group.conditions.length > 1 && groups.length >= catalog.maxGroups);
      then.addEventListener('click', () => startSequence(condition, group));
      tools.append(then);
    }
    const all = conditions();
    const position = all.indexOf(condition);
    tools.append(
      iconButton(i18n.t('ca.builder.moveUp'), '↑', () => move(condition, -1), position === 0 && !canLeaveAtEdge(group)),
      iconButton(i18n.t('ca.builder.moveDown'), '↓', () => move(condition, 1), position === all.length - 1 && !canLeaveAtEdge(group)),
      iconButton(i18n.t('ca.builder.duplicate'), '⧉', () => {
        const copy = JSON.parse(JSON.stringify(condition)); copy.id = newId('c');
        group.conditions.splice(index + 1, 0, copy);
        normalizeSteps(group);
        renderGroups(); changed();
      }, all.length >= catalog.maxConditions || (ordered && group.conditions.length >= (catalog.maxSteps || 4))),
      iconButton(i18n.t('ca.builder.remove'), '×', () => {
        group.conditions.splice(index, 1);
        if (!group.conditions.length && groups.length > 1) groups.splice(groups.indexOf(group), 1);
        else normalizeSteps(group);
        renderGroups(); changed();
      }));
    if (ordered) {
      // The number is shown; "Step 2" is what a screen reader says.
      const badge = el('span', 'ca-step-badge');
      const number = el('span', null, String(index + 1));
      number.setAttribute('aria-hidden', 'true');
      badge.append(number, el('span', 'visually-hidden', i18n.t('ca.builder.stepNumber', index + 1)));
      badge.title = i18n.t('ca.builder.stepNumber', index + 1);
      header.append(badge);
    }
    header.append(label, sentence, readout, tools);
    const body = el('div', 'ca-condition-body');
    const refresh = () => {
      sentence.textContent = describeCard();
      label.textContent = kindLabel(condition);
      changed();
    };
    const rebuild = () => { body.replaceChildren(...editor(condition, refresh, rebuild)); refresh(); };
    body.append(...editor(condition, refresh, rebuild));
    card.append(header, body);
    applyReadout(readout, condition);
    return card;
  }

  /** A step can leave a sequence past the rule's first or last condition, into a plain group of its own. */
  function canLeaveAtEdge(group) {
    return group.match === 'THEN' && group.conditions.length > 1 && state.definition.groups.length < catalog.maxGroups;
  }

  /**
   * Moves a condition one place up or down. At a group's edge it crosses into the neighbouring group and stays at that
   * edge: moving up it becomes the last of the group above, moving down the first of the group below. So ↑ on a
   * condition under a sequence makes it the sequence's last step, and ↓ on that last step takes it out again (into a
   * new group when the sequence is the last one). Entering a sequence makes it a step; leaving gives it a window again.
   */
  function move(condition, step) {
    const all = conditions();
    const groups = state.definition.groups;
    const from = groupOf(condition);
    const target = all.indexOf(condition) + step;
    let to;
    if (target < 0 || target >= all.length) {
      if (!canLeaveAtEdge(from)) return;
      to = { id: newId('g'), match: 'ALL', conditions: [] };
      groups.splice(step < 0 ? 0 : groups.length, 0, to);
    } else {
      to = groupOf(all[target]);
    }
    if (to !== from && to.match === 'THEN' && !canBeStep(condition)) {
      groupNotes.set(to, i18n.t('ca.builder.inOrderBlocked'));
      renderGroups();
      return;
    }
    if (to !== from && to.match === 'THEN' && to.conditions.length >= (catalog.maxSteps || 4)) {
      groupNotes.set(to, i18n.t('ca.builder.sequenceFull', catalog.maxSteps || 4));
      renderGroups();
      return;
    }
    clearRefusal(from);
    clearRefusal(to);
    from.conditions.splice(from.conditions.indexOf(condition), 1);
    if (to === from) to.conditions.splice(to.conditions.indexOf(all[target]) + (step > 0 ? 1 : 0), 0, condition);
    else to.conditions.splice(step < 0 ? to.conditions.length : 0, 0, condition);
    if (to !== from) {
      if (from.match === 'THEN') leaveSequence(condition);
      if (to.match === 'THEN' && enterSequence(condition, to.conditions.indexOf(condition) === 0)) {
        groupNotes.set(to, i18n.t('ca.builder.windowsDropped'));
      }
    }
    if (!from.conditions.length && groups.length > 1) groups.splice(groups.indexOf(from), 1);
    else normalizeSteps(from);
    normalizeSteps(to);
    renderGroups(); changed();
    // The keyboard stays on the arrow that was pressed, on the condition's new card.
    nodes.groups.querySelector(`[data-condition-id="${CSS.escape(condition.id)}"] [aria-label="${i18n.t(step < 0 ? 'ca.builder.moveUp' : 'ca.builder.moveDown')}"]`)?.focus();
  }

  function editor(condition, refresh, rebuild) {
    switch (condition.kind) {
      case 'VALUE': return valueEditor(condition, refresh, rebuild);
      case 'PATTERN': return patternEditor(condition, refresh, rebuild, groupOf(condition)?.match === 'THEN');
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
    const group = groupOf(condition);
    if (group?.match === 'THEN') {
      // A cross happens on one candle, and a rise or fall counts its own candles: neither takes "in a row".
      if (text.stepCandles({ ...condition, step: { candles: 2 } }) > 1) fields.push(stepField(condition, group, refresh));
      syncSpan(group);
    } else {
      fields.push(...windowFields(condition, refresh, rebuild));
    }
    return fields;
  }

  /** In a sequence the step's own timing replaces the window: how many candles in a row it must hold. */
  function stepField(condition, group, refresh) {
    return field(i18n.t('ca.builder.stepInARow'), restoreWhenBlank(numberInput(condition.step?.candles || 1, { min: 1, max: catalog.maxWindow || 60, step: 1 }, value => {
      if (value == null) return;
      condition.step = { ...condition.step, candles: value };
      syncSpan(group); refresh();
    }), () => condition.step?.candles || 1), LEARN_OPTIONS.inARow);
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
              // Not shorter than any of the alert's intervals: the longest one decides.
              const longest = state.intervals.at(-1);
              const allowed = longest === 'MONTHLY' ? ['chart'] : longest === 'WEEKLY' ? ['chart', '1mo'] : ['chart', '1wk', '1mo'];
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

  function patternEditor(condition, refresh, rebuild, step) {
    const fields = [];
    const candlestick = condition.family === 'CANDLESTICK';
    fields.push(field(i18n.t('ca.builder.patternFamily'), select(['CANDLESTICK', 'ELLIOTT_WAVE', 'HARMONIC_FORMATION']
      .map(family => [family, i18n.t(`ca.kind.${family}`)]), condition.family, value => {
      condition.family = value; condition.patterns = []; condition.stillValid = false;
      delete condition.confirmedOnly;
      if (value === 'CANDLESTICK') condition.status = 'CONFIRMED'; else delete condition.status;
      rebuild();
    })));
    // Rules saved before the status existed waited for detection unless "confirmedOnly" was ticked.
    const status = condition.status || (condition.confirmedOnly ? 'CONFIRMED' : 'DETECTED');
    const windowLabel = confirmed => i18n.t(confirmed ? 'ca.builder.confirmedWithin' : 'ca.builder.withinCandles');
    const windowLearn = confirmed => confirmed ? LEARN_OPTIONS.confirmed : LEARN_OPTIONS.detected;
    const confirmed = candlestick && status === 'CONFIRMED';
    // A step counts on the one candle its pattern counts on, so it has no window of its own.
    const stepNote = el('p', 'wl-muted ca-field-note', i18n.t(`ca.builder.patternStep.${confirmed ? 'CONFIRMED' : 'DETECTED'}`));
    const windowField = step ? null : field(windowLabel(confirmed), numberInput(condition.window?.candles ?? 5, { min: 1, max: catalog.maxWindow || 60, step: 1 },
      value => { condition.window = { mode: 'ANY', candles: value }; refresh(); }), windowLearn(confirmed));
    if (candlestick) {
      // The status changes what the window counts from; only its label is redrawn, so the focus stays here.
      fields.push(field(i18n.t('ca.builder.status'), select(['CONFIRMED', 'DETECTED'].map(value => [value, i18n.t(`ca.patternStatus.${value}`)]),
        status, value => {
          condition.status = value; delete condition.confirmedOnly;
          stepNote.textContent = i18n.t(`ca.builder.patternStep.${value}`);
          if (windowField) {
            const caption = windowField.querySelector('.ca-field-label');
            const help = caption.querySelector('.learn-help');
            caption.firstChild.textContent = windowLabel(value === 'CONFIRMED');
            help.dataset.learn = windowLearn(value === 'CONFIRMED');
            help.dataset.learnTitle = windowLabel(value === 'CONFIRMED');
          }
          refresh();
        }), LEARN_OPTIONS.status));
    }
    fields.push(field(i18n.t('ca.builder.direction'), select(['BULLISH', 'BEARISH', 'ANY'].map(value => [value, i18n.t(`ca.direction.${value}`)]),
      condition.direction || 'ANY', value => { condition.direction = value; refresh(); }), LEARN_OPTIONS.direction));
    if (windowField) fields.push(windowField);
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
    if (step) fields.push(stepNote);
    if (candlestick) fields.push(el('p', 'wl-muted ca-field-note', i18n.t('ca.builder.statusNote')));
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
    { key: 'candlestick', make: () => ({ kind: 'PATTERN', family: 'CANDLESTICK', status: 'CONFIRMED', direction: 'BULLISH', patterns: [], window: { mode: 'ANY', candles: 3 } }) },
    { key: 'elliott', make: () => ({ kind: 'PATTERN', family: 'ELLIOTT_WAVE', direction: 'BULLISH', patterns: ['WAVE_II_END'], window: { mode: 'ANY', candles: 5 } }) },
    { key: 'harmonic', make: () => ({ kind: 'PATTERN', family: 'HARMONIC_FORMATION', direction: 'BULLISH', patterns: [], window: { mode: 'ANY', candles: 3 } }) },
    { key: 'outlook', make: () => ({ kind: 'OUTLOOK', direction: 'BUY', strength: 'MODERATE' }) },
    { key: 'insider', make: () => ({ kind: 'INSIDER', transaction: 'PURCHASE', days: 30, minCount: 1, roles: [] }) },
    { key: 'congress', make: () => ({ kind: 'CONGRESS', transaction: 'PURCHASE', days: 45, minCount: 1, chambers: [] }) },
    { key: 'anyIndicator', make: null }
  ];

  /** Where "+ Add condition" puts a plain condition: the last group, or a new group after a sequence. */
  function plainGroup() {
    const groups = state.definition.groups;
    if (groups.at(-1).match !== 'THEN') return groups.at(-1);
    if (groups.length < catalog.maxGroups) {
      groups.push({ id: newId('g'), match: 'ALL', conditions: [] });
      return groups.at(-1);
    }
    return groups.findLast(group => group.match !== 'THEN') || null;
  }

  function addCondition(group) {
    const target = group || plainGroup();
    if (!target) return;
    const ordered = target.match === 'THEN';
    const groups = state.definition.groups;
    const dialog = el('dialog', 'wl-dialog ca-picker');
    const heading = el('h2', null, i18n.t(ordered ? 'ca.conditionPicker.stepTitle' : 'ca.conditionPicker.title'));
    heading.id = 'ca-picker-title';
    dialog.setAttribute('aria-labelledby', heading.id);
    const grid = el('div', 'ca-picker-grid');
    let picked = false;
    const offered = choices().filter(choice => !ordered || !['outlook', 'insider', 'congress'].includes(choice.key));
    // A new sequence: the target itself while it is empty, otherwise a new group after it.
    if (!ordered && (!target.conditions.length || groups.length < catalog.maxGroups)) offered.push({ key: 'sequence', sequence: true });
    offered.forEach(choice => {
      const option = el('button', `ca-picker-option ca-picker-${choice.key}`);
      option.type = 'button';
      option.append(el('strong', null, i18n.t(`ca.picker.${choice.key}.title`)), el('span', null, i18n.t(`ca.picker.${choice.key}.text`)));
      option.addEventListener('click', async () => {
        picked = true;
        dialog.close();
        if (choice.sequence) {
          let sequence = target;
          if (target.conditions.length) {
            sequence = { id: newId('g'), match: 'THEN', conditions: [] };
            groups.splice(groups.indexOf(target) + 1, 0, sequence);
          }
          sequence.match = 'THEN';
          sequence.window = { mode: 'LATEST' };
          renderGroups();
          addCondition(sequence);
          return;
        }
        let condition;
        if (choice.make) condition = choice.make();
        else {
          const operand = await chooseIndicator(null);
          if (!operand) { tidyGroups(); renderGroups(); return; }
          condition = { kind: 'VALUE', subject: operand, operator: 'ABOVE', window: { mode: 'LATEST' } };
          suggestCompare(condition);
        }
        const added = { id: newId('c'), negate: false, ...condition };
        clearRefusal(target);
        if (ordered) enterSequence(added, !target.conditions.length);
        target.conditions.push(added);
        normalizeSteps(target);
        renderGroups(); changed();
        const card = nodes.groups.querySelector(`[data-condition-id="${CSS.escape(added.id)}"]`);
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
    dialog.append(heading, el('p', 'wl-muted', i18n.t('ca.conditionPicker.intro')), grid, actions);
    dialog.addEventListener('close', () => {
      dialog.remove();
      if (!picked) { tidyGroups(); renderGroups(); }
    });
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
      name: nodes.name.value.trim(), description: nodes.description.value.trim(), intervals: [...state.intervals],
      definition: state.definition, triggerMode: state.triggerMode, quietCandles: state.quietCandles,
      outcomeCandles: state.outcomeCandles, emailEnabled: state.emailEnabled,
      targets: [...state.targets.values()].filter(target => target.scope === 'ALL_MEMBERS' || target.symbols.length),
      notifyExisting: state.notifyExisting, active: true, sourceChartAlertId: state.sourceChartAlertId
    };
  }

  function schedulePreview() {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(() => runPreview(), 600);
  }

  async function runPreview(retrying = false) {
    const sequence = ++previewSequence;
    clearTimeout(elliottRetry);
    if (!retrying) elliottRetries = 0;
    const draft = snapshot();
    if (!conditions().length) {
      preview = null;
      nodes.result.replaceChildren(el('p', 'wl-muted', i18n.t('ca.preview.addFirst')));
      renderReadouts();
      return;
    }
    nodes.result.setAttribute('aria-busy', 'true');
    // A first test on a stock can take a few seconds (Elliott counts most of all): say so instead of looking stuck.
    const elliott = conditions().some(condition => condition.kind === 'PATTERN' && condition.family === 'ELLIOTT_WAVE');
    const slow = setTimeout(() => {
      if (sequence !== previewSequence) return;
      nodes.result.querySelector('.ca-preview-wait')?.remove();
      nodes.result.prepend(el('p', 'wl-status ca-preview-wait', i18n.t(elliott ? 'ca.preview.waitElliott' : 'ca.preview.wait')));
    }, 1200);
    try {
      const result = await api('/api/custom-alerts/preview', { method: 'POST', body: JSON.stringify({
        interval: state.testInterval, intervals: draft.intervals, definition: draft.definition, symbol: nodes.testSymbol.value.trim(),
        triggerMode: draft.triggerMode,
        quietCandles: draft.quietCandles, outcomeCandles: draft.outcomeCandles, targets: draft.targets, alertId: state.id }) });
      if (sequence !== previewSequence) return;
      preview = result;
      renderPreview(result);
      if (result.elliottHistory === 'PREPARING' && elliottRetries < 40) {
        elliottRetries++;
        elliottRetry = setTimeout(() => { if (sequence === previewSequence) runPreview(true); }, 15000);
      }
    } catch (error) {
      if (sequence !== previewSequence) return;
      preview = null;
      const problem = el('div', 'ca-preview-problem');
      problem.append(el('strong', null, i18n.t('ca.preview.problem')), el('p', null, error.message));
      nodes.result.replaceChildren(problem);
      nodes.coverage.replaceChildren();
    } finally {
      clearTimeout(slow);
      if (sequence === previewSequence) {
        nodes.result.removeAttribute('aria-busy');
        nodes.result.querySelector('.ca-preview-wait')?.remove();
      }
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
    } else if (!result.history.length && result.elliottHistory !== 'PREPARING') {
      // While the Elliott history is prepared the replay is short: "loosen a condition" would mislead.
      box.append(el('p', 'wl-muted', i18n.t('ca.preview.noHistory')));
    }
    // Elliott counts are replayed as they stood on each candle. A background job prepares that history once per
    // stock; until it is ready the replay covers the latest candles only.
    if (result.elliottHistory === 'PREPARING') {
      box.append(el('p', 'wl-status ca-preview-preparing', i18n.t('ca.preview.elliottPreparing', result.symbol, result.replayedCandles)));
    } else if (result.shortenedBy === 'ELLIOTT_WAVE') {
      box.append(el('p', 'wl-muted', i18n.t('ca.preview.elliottShort', result.replayedCandles)));
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

  const shortDay = seconds => i18n.date(seconds * 1000, { day: 'numeric', month: 'short' });

  /** A step's reading: when it happened, how far it has got, or how long the next step has left. */
  function applyStepReadout(node, found) {
    const facts = found.facts || {};
    let status;
    let detail = '';
    let tone;
    if (found.passed) {
      status = i18n.t('ca.readout.met');
      detail = facts.candles > 1 ? i18n.t('ca.readout.stepRange', shortDay(facts.from), shortDay(facts.to)) : i18n.t('ca.readout.stepOn', shortDay(facts.at));
      tone = 'is-met';
    } else if (facts.waiting) {
      status = i18n.t('ca.readout.waiting');
      detail = [facts.soFar != null ? i18n.t('ca.readout.soFar', facts.soFar, facts.needed) : null,
        facts.candlesLeft ? i18n.plural('ca.readout.candlesLeft', facts.candlesLeft, facts.candlesLeft) : null].filter(Boolean).join(' · ');
      tone = 'is-waiting';
    } else {
      status = i18n.t('ca.readout.unmet');
      if (facts.needed != null) detail = facts.soFar ? i18n.t('ca.readout.soFar', facts.soFar, facts.needed) : i18n.t('ca.readout.notYet');
      tone = 'is-unmet';
    }
    node.className = `ca-readout ${tone}`;
    node.replaceChildren(el('strong', null, status), detail ? el('span', null, detail) : '');
    node.setAttribute('aria-label', `${preview.symbol}: ${status}${detail ? `, ${detail}` : ''}`);
  }

  /** Under a sequence: when it completed (also when that was before its window). */
  function applyGroupReadout(node, group) {
    const found = preview?.available ? preview.groups?.find(item => item.id === group.id) : null;
    const facts = found?.facts || {};
    let detail = '';
    if (found?.passed && facts.completedAt) detail = i18n.t('ca.evidence.completedOn', i18n.date(facts.completedAt * 1000, { day: 'numeric', month: 'short', year: 'numeric' }));
    else if (facts.outsideWindow) detail = i18n.plural('ca.readout.completedAgo', facts.candlesAgo, facts.candlesAgo);
    node.textContent = detail ? `${preview.symbol}: ${detail}` : '';
    node.classList.toggle('is-met', Boolean(found?.passed));
  }

  function applyReadout(node, condition) {
    const found = preview?.conditions?.find(item => item.id === condition.id);
    if (!found || !preview.available) { node.replaceChildren(); node.className = 'ca-readout'; return; }
    if (groupOf(condition)?.match === 'THEN') { applyStepReadout(node, found); return; }
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
    nodes.groups.querySelectorAll('[data-group-readout]').forEach(node => {
      const group = state.definition.groups.find(item => item.id === node.dataset.groupReadout);
      if (group) applyGroupReadout(node, group);
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
    setIntervals(template.intervals);
    state.triggerMode = template.triggerMode;
    state.definition = template.definition;
    adoptSequences();
    syncBasics();
    renderStart(); renderGroups(); changed();
  }

  // ------------------------------------------------------------------ the assistant

  /**
   * A rule the assistant proposed (already checked by the server's validator), filled in like a template. The stocks
   * to watch stay as they are: the user always chooses them and saves. Replacing conditions asks first.
   */
  function applyDraft(draft, { ask = true } = {}) {
    if (!draft?.definition?.groups) return false;
    if (ask && conditions().length && !window.confirm(i18n.t('assistant.draft.replace'))) return false;
    if (!nodes.name.value.trim() && draft.name) nodes.name.value = draft.name;
    if (!nodes.description.value.trim() && draft.description) nodes.description.value = draft.description;
    setIntervals(draft.intervals || (draft.interval ? [draft.interval] : null));
    state.triggerMode = draft.triggerMode || 'BECOMES_TRUE';
    state.quietCandles = draft.quietCandles ?? 0;
    state.outcomeCandles = draft.outcomeCandles ?? 10;
    state.definition = JSON.parse(JSON.stringify(draft.definition));
    adoptSequences();
    syncBasics();
    renderStart(); renderGroups(); changed();
    document.dispatchEvent(new CustomEvent('stockwatch:assistant-draft-applied'));
    return true;
  }

  /** "Open in builder" from the assistant on another page leaves the rule in this tab's session storage. */
  function draftFromAssistant() {
    if (new URLSearchParams(window.location.search).get('from') !== 'assistant') return null;
    try {
      const draft = JSON.parse(sessionStorage.getItem('stockwatch.assistant.draft') || 'null');
      sessionStorage.removeItem('stockwatch.assistant.draft');
      return draft;
    } catch (_) {
      return null;
    }
  }

  /** What the assistant is told about this page: the rule as edited now and how it reads on the test stock. */
  function assistantContext() {
    const draft = snapshot();
    const reading = preview?.available ? {
      symbol: preview.symbol, matchesNow: preview.matched, applicable: preview.applicable,
      conditionsMet: `${preview.matchedConditions} of ${preview.totalConditions}`,
      conditions: (preview.conditions || []).map(item => ({ id: item.id, met: item.passed, applicable: item.applicable, facts: item.facts })),
      triggersInReplay: preview.history?.length ?? 0, replayedCandles: preview.replayedCandles
    } : preview && !preview.available ? { unavailable: preview.unavailableReason } : null;
    return {
      rule: { name: draft.name, intervals: draft.intervals, triggerMode: draft.triggerMode, quietCandles: draft.quietCandles,
        outcomeCandles: draft.outcomeCandles, definition: draft.definition },
      saved: Boolean(state.id), watchlistsChosen: draft.targets.length, singleStockToAdd: state.pendingSymbol,
      testStock: nodes.testSymbol.value.trim() || null, preview: reading, status: nodes.status.textContent || null
    };
  }

  const assistantHooks = window.StockWatchAssistantHooks || (window.StockWatchAssistantHooks = {});
  assistantHooks.context = assistantContext;
  assistantHooks.applyDraft = draft => applyDraft(draft);

  /** A chart alert on an indicator or a horizontal line becomes the first condition. */
  async function fromChartAlert(id) {
    const alert = await api(`/api/chart-alerts/${id}`);
    state.sourceChartAlertId = alert.id;
    setIntervals([({ '1d': 'DAILY', '1wk': 'WEEKLY', '1mo': 'MONTHLY' })[alert.interval] || 'DAILY']);
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

  const INTERVALS = ['DAILY', 'WEEKLY', 'MONTHLY'];

  /** The alert's intervals in order (daily when none is given); the tested one stays if it is still among them. */
  function setIntervals(intervals) {
    const chosen = INTERVALS.filter(interval => (intervals?.length ? intervals : ['DAILY']).includes(interval));
    state.intervals = chosen.length ? chosen : ['DAILY'];
    if (!state.intervals.includes(state.testInterval)) state.testInterval = state.intervals[0];
  }

  /** With several intervals, the preview offers to test each of them. */
  function renderTestInterval() {
    nodes.testInterval.hidden = state.intervals.length < 2;
    nodes.testInterval.replaceChildren(...state.intervals.map(interval => {
      const button = el('button', null, text.intervalLabel(interval));
      button.type = 'button';
      button.setAttribute('aria-pressed', String(interval === state.testInterval));
      button.addEventListener('click', () => {
        if (state.testInterval === interval) return;
        state.testInterval = interval;
        renderTestInterval();
        runPreview();
      });
      return button;
    }));
  }

  function syncBasics() {
    nodes.intervals.querySelectorAll('input[type="checkbox"]').forEach(input => { input.checked = state.intervals.includes(input.value); });
    renderTestInterval();
    form.querySelectorAll('input[name="triggerMode"]').forEach(input => { input.checked = input.value === state.triggerMode; });
    nodes.quiet.value = String(state.quietCandles);
    nodes.outcome.value = String(state.outcomeCandles);
    nodes.email.checked = state.emailEnabled;
    nodes.notifyExisting.checked = state.notifyExisting;
  }

  function bindBasics() {
    nodes.name.addEventListener('input', changed);
    nodes.description.addEventListener('input', () => { dirty = true; });
    nodes.intervals.querySelectorAll('input[type="checkbox"]').forEach(input => input.addEventListener('change', () => {
      const ticked = [...nodes.intervals.querySelectorAll('input[type="checkbox"]:checked')].map(box => box.value);
      // At least one stays ticked: an alert is always checked after some close.
      if (!ticked.length) { input.checked = true; return; }
      setIntervals(ticked);
      renderTestInterval();
      // A timeframe offered by a condition depends on the longest interval.
      renderGroups();
      changed();
    }));
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
    form.querySelector('[data-ca-test]').addEventListener('click', () => runPreview());
    nodes.testSymbol.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); runPreview(); } });
    window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
    form.addEventListener('submit', save);
  }

  async function save(event) {
    event.preventDefault();
    const draft = snapshot();
    if (!conditions().length) { nodes.status.textContent = i18n.t('ca.save.needCondition'); return; }
    if (state.definition.groups.some(group => group.match === 'THEN' && group.conditions.length < 2)) {
      nodes.status.textContent = i18n.t('ca.save.needSteps');
      return;
    }
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
    // Saving waits until the alert, template or chart alert is loaded and drawn.
    nodes.save.disabled = true;
    try {
      [catalog, lists] = await Promise.all([text.catalog(), api('/api/watchlists')]);
    } catch (error) {
      nodes.status.textContent = error.message;
      return;
    }
    const startSymbol = page.dataset.startSymbol || null;
    const assistantDraft = state.id ? null : draftFromAssistant();
    try {
      if (assistantDraft) {
        applyDraft(assistantDraft, { ask: false });
      } else if (state.id) {
        const alert = await api(`/api/custom-alerts/${state.id}`);
        nodes.name.value = alert.name;
        nodes.description.value = alert.description || '';
        setIntervals(alert.intervals);
        Object.assign(state, { triggerMode: alert.triggerMode, quietCandles: alert.quietCandles,
          outcomeCandles: alert.outcomeCandles, emailEnabled: alert.emailEnabled, definition: alert.definition });
        adoptSequences();
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
    nodes.save.disabled = false;
    nodes.summary.textContent = text.summary(snapshot(), catalog.byType);
    dirty = Boolean(page.dataset.startTemplate || page.dataset.startChartAlert || assistantDraft);
    runPreview();
  }

  // Deferred scripts run in document order, so the navigation's request helper may not exist yet.
  if (window.StockWatchLists) init();
  else document.addEventListener('DOMContentLoaded', init, { once: true });
})();
