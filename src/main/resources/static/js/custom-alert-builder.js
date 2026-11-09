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

  function iconButton(label, iconName, onClick, disabled) {
    const button = el('button', 'ca-icon-button');
    button.append(text.icon(iconName));
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
    renderSteps();
    scheduleDraft();
    schedulePreview();
  }

  // ------------------------------------------------------------------ steps and the save bar

  const STEPS = ['name', 'rule', 'when', 'where'];
  const stepLinks = [...document.querySelectorAll('[data-ca-step-link]')];

  /** Each step: done, needing attention, or not started yet. */
  function stepStates() {
    const draft = snapshot();
    const halfSequence = state.definition.groups.some(group => group.match === 'THEN' && group.conditions.length < 2);
    const watching = draft.targets.length > 0 || Boolean(state.pendingSymbol && state.pendingChoice);
    return {
      name: nodes.name.value.trim() ? 'done' : 'todo',
      rule: !conditions().length ? 'todo' : halfSequence ? 'problem' : 'done',
      when: 'done',
      where: watching ? 'done' : conditions().length ? 'problem' : 'todo'
    };
  }

  /** What the rule watches, in a line: "2 conditions · Growth (25 stocks) · Daily". */
  function footerSummary() {
    const count = conditions().length;
    const parts = [i18n.plural('ca.footer.conditions', count, count)];
    snapshot().targets.forEach(target => {
      const list = lists.find(item => item.id === target.watchlistId);
      if (!list) return;
      parts.push(target.scope === 'ALL_MEMBERS' ? i18n.plural('ca.target.allStocks', list.instruments, list.name, list.instruments)
        : i18n.plural('ca.footer.chosen', target.symbols.length, list.name, target.symbols.length));
    });
    if (state.pendingSymbol && state.pendingChoice) parts.push(state.pendingSymbol);
    parts.push(text.intervalsLabel(state.intervals));
    return parts.join(' · ');
  }

  function renderSteps() {
    const states = stepStates();
    STEPS.forEach((key, index) => {
      const node = document.querySelector(`[data-ca-step-state="${key}"]`);
      if (!node) return;
      node.className = `ca-step-state is-${states[key]}`;
      node.replaceChildren(states[key] === 'done' ? text.icon('check') : String(index + 1),
        el('span', 'visually-hidden', i18n.t(`ca.builder.stepState.${states[key]}`)));
    });
    const whereProblem = document.querySelector('[data-ca-where-problem]');
    if (whereProblem) whereProblem.hidden = states.where !== 'problem';
    const summary = document.querySelector('[data-ca-footer-summary]');
    if (!summary) return;
    // The first thing still missing, with the step that fixes it; otherwise what the alert will watch.
    const problem = !conditions().length ? 'ca.footer.needCondition' : states.rule === 'problem' ? 'ca.footer.needSteps'
      : states.where === 'problem' ? 'ca.footer.needStocks' : null;
    summary.classList.toggle('is-problem', Boolean(problem && conditions().length));
    if (problem) {
      const go = el('button', 'ca-footer-problem', i18n.t(problem));
      go.type = 'button';
      go.addEventListener('click', () => problem === 'ca.footer.needStocks' ? goToStep('where', '[data-ca-targets] input:not(:disabled)')
        : problem === 'ca.footer.needSteps' ? goToStep('rule', '.ca-sequence-adders button') : goToStep('rule', '[data-ca-add-condition]'));
      summary.replaceChildren(go);
    } else summary.textContent = footerSummary();
    nodes.save.setAttribute('aria-disabled', String(Boolean(problem)));
    if (problem) nodes.save.setAttribute('aria-describedby', 'ca-footer-summary'); else nodes.save.removeAttribute('aria-describedby');
    renderStepSummaries();
  }

  /** Opens a step (unfolding it), scrolls to it and puts the keyboard on {@code selector} inside it. */
  function goToStep(key, selector) {
    const section = document.querySelector(`[data-ca-step="${key}"]`);
    if (!section) return;
    setFolded(section, false);
    section.scrollIntoView({ block: 'start', behavior: 'smooth' });
    section.querySelector(selector)?.focus({ preventScroll: true });
  }

  // ------------------------------------------------------------------ steps that fold to one line

  /** A folded step's line: what it is set to, in words. */
  function stepSummary(key) {
    if (key === 'name') {
      const name = nodes.name.value.trim();
      return name ? [name, nodes.description.value.trim()].filter(Boolean).join(' · ') : i18n.t('ca.builder.summary.unnamed');
    }
    if (key === 'when') {
      return [text.intervalsLabel(state.intervals), i18n.t(`ca.mode.short.${state.triggerMode}`),
        state.quietCandles ? i18n.plural('ca.builder.summary.quiet', state.quietCandles, state.quietCandles) : i18n.t('ca.builder.summary.noQuiet')].join(' · ');
    }
    const parts = [];
    snapshot().targets.forEach(target => {
      const list = lists.find(item => item.id === target.watchlistId);
      if (list) parts.push(target.scope === 'ALL_MEMBERS' ? i18n.plural('ca.target.allStocks', list.instruments, list.name, list.instruments)
        : i18n.plural('ca.footer.chosen', target.symbols.length, list.name, target.symbols.length));
    });
    if (state.pendingSymbol && state.pendingChoice) parts.push(state.pendingSymbol);
    parts.push(i18n.t(state.emailEnabled ? 'ca.card.emailOn' : 'ca.card.noEmail'));
    parts.push(i18n.plural('ca.builder.summary.measure', state.outcomeCandles, state.outcomeCandles));
    return parts.join(' · ');
  }

  function setFolded(section, folded) {
    const key = section.dataset.caStep;
    const summary = section.querySelector('[data-ca-step-summary]');
    const body = section.querySelector('[data-ca-step-body]');
    if (!summary || !body) return;
    section.classList.toggle('is-folded', folded);
    summary.hidden = !folded;
    body.hidden = folded;
    if (folded) summary.querySelector('[data-ca-step-summary-text]').textContent = stepSummary(key);
  }

  function renderStepSummaries() {
    document.querySelectorAll('[data-ca-collapsible].is-folded').forEach(section => {
      section.querySelector('[data-ca-step-summary-text]').textContent = stepSummary(section.dataset.caStep);
    });
  }

  /**
   * Steps with nothing to decide start folded: the schedule always (its defaults suit most alerts), and on an
   * existing alert the name and the stocks too. A step that needs attention never folds.
   */
  function foldFinishedSteps() {
    const states = stepStates();
    document.querySelectorAll('[data-ca-collapsible]').forEach(section => {
      const key = section.dataset.caStep;
      const fold = states[key] === 'done' && (key === 'when' || Boolean(state.id));
      setFolded(section, fold);
      section.querySelector('[data-ca-step-edit]')?.addEventListener('click', () => {
        setFolded(section, false);
        section.querySelector('[data-ca-step-body] input, [data-ca-step-body] textarea, [data-ca-step-body] select')?.focus();
      });
    });
  }
  // A step chosen in the step list opens.
  stepLinks.forEach(link => link.addEventListener('click', () => {
    const section = document.querySelector(`[data-ca-step="${link.dataset.caStepLink}"]`);
    if (section?.classList.contains('is-folded')) setFolded(section, false);
  }));

  // The step being read is marked in the step list.
  if ('IntersectionObserver' in window) {
    const seen = new Map();
    const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => seen.set(entry.target.dataset.caStep, entry.intersectionRatio));
      const current = STEPS.find(key => (seen.get(key) || 0) > 0.25) || STEPS.find(key => (seen.get(key) || 0) > 0);
      stepLinks.forEach(link => {
        if (link.dataset.caStepLink === current) link.setAttribute('aria-current', 'step');
        else link.removeAttribute('aria-current');
      });
    }, { threshold: [0, 0.25, 0.5], rootMargin: '-80px 0px -40% 0px' });
    document.querySelectorAll('[data-ca-step]').forEach(section => observer.observe(section));
  }

  // ------------------------------------------------------------------ the preview on phones

  const previewPanel = document.querySelector('[data-ca-preview]');
  function setSheet(open) {
    previewPanel?.classList.toggle('is-open', open);
    document.body.classList.toggle('ca-sheet-open', open);
    if (open) previewPanel.querySelector('[data-ca-close-preview]')?.focus();
  }
  document.querySelectorAll('[data-ca-open-preview]').forEach(button => button.addEventListener('click', () => setSheet(true)));
  document.querySelector('[data-ca-close-preview]')?.addEventListener('click', () => {
    setSheet(false);
    form.querySelector('.ca-footer-preview')?.focus();
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && previewPanel?.classList.contains('is-open') && !text.modalOpen()) setSheet(false);
  });
  // Ctrl+Enter (⌘+Enter) tests the rule from any field: caught before the field's own Enter handling.
  document.addEventListener('keydown', event => {
    if (event.key !== 'Enter' || !(event.ctrlKey || event.metaKey) || text.modalOpen()) return;
    event.preventDefault();
    event.stopPropagation();
    runPreview();
  }, true);
  if (/Mac|iPhone|iPad/.test(navigator.platform || '')) document.querySelectorAll('[data-ca-mod-key]').forEach(key => { key.textContent = '⌘'; });
  text.shortcuts([[['Ctrl', 'Enter'], i18n.t('ca.shortcuts.test')]], false);

  /** On phones the latest reading also sits in step 2, next to the conditions it is about. */
  function renderMobileReading(result) {
    const strip = document.querySelector('[data-ca-mobile-reading]');
    if (!strip) return;
    if (!result?.available) { strip.hidden = true; return; }
    strip.hidden = false;
    strip.querySelector('[data-ca-mobile-reading-text]').textContent = !result.applicable ? i18n.t('ca.preview.notApplicable', result.symbol)
      : `${i18n.t(result.matched ? 'ca.preview.matches' : 'ca.preview.noMatch', result.symbol)} · ${i18n.t('ca.preview.conditionsMet', result.matchedConditions, result.totalConditions)}`;
    strip.classList.toggle('is-match', Boolean(result.applicable && result.matched));
  }

  // ------------------------------------------------------------------ an unsaved new alert survives a closed tab

  const DRAFT_KEY = 'stockwatch.customAlertDraft';
  let draftTimer = null;
  let draftReady = false;
  function scheduleDraft() {
    if (state.id || !draftReady) return;
    clearTimeout(draftTimer);
    draftTimer = setTimeout(() => {
      try {
        if (!conditions().length) { localStorage.removeItem(DRAFT_KEY); return; }
        localStorage.setItem(DRAFT_KEY, JSON.stringify({ savedAt: Date.now(), alert: snapshot(), testSymbol: nodes.testSymbol.value.trim() }));
      } catch (ignored) { /* private windows may refuse storage */ }
    }, 800);
  }
  function forgetDraft() {
    clearTimeout(draftTimer);
    try { localStorage.removeItem(DRAFT_KEY); } catch (ignored) { /* nothing stored */ }
  }
  /** A new, empty builder offers the draft left by a tab that closed without saving (up to a week old). */
  function offerDraft() {
    let draft = null;
    try { draft = JSON.parse(localStorage.getItem(DRAFT_KEY) || 'null'); } catch (ignored) { draft = null; }
    const banner = document.querySelector('[data-ca-draft]');
    if (!banner || !draft?.alert?.definition?.groups?.some(group => group.conditions?.length) || Date.now() - draft.savedAt > 7 * 86400000) return;
    banner.querySelector('[data-ca-draft-text]').textContent = i18n.t('ca.builder.draftFound', draft.alert.name || i18n.t('ca.builder.draftUnnamed'),
      text.relativeDay(draft.savedAt).toLowerCase());
    banner.hidden = false;
    banner.querySelector('[data-ca-draft-restore]').addEventListener('click', () => {
      applyDraft(draft.alert, { ask: false });
      state.emailEnabled = draft.alert.emailEnabled ?? true;
      state.notifyExisting = Boolean(draft.alert.notifyExisting);
      (draft.alert.targets || []).forEach(target => { if (lists.some(list => list.id === target.watchlistId)) state.targets.set(target.watchlistId, { ...target }); });
      if (draft.testSymbol) nodes.testSymbol.value = draft.testSymbol;
      syncBasics(); renderTargets(); changed();
      banner.hidden = true;
      nodes.name.focus();
    }, { once: true });
    banner.querySelector('[data-ca-draft-discard]').addEventListener('click', () => { forgetDraft(); banner.hidden = true; }, { once: true });
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
    if (!conditions().length) parts.push(emptyRule());
    nodes.groups.replaceChildren(...parts);
    form.querySelector('[data-ca-add-group]').disabled = groups.length >= catalog.maxGroups || !conditions().length;
    // A rule that is only a sequence grows from inside it ("Add the next step", "Combine with other conditions").
    const addRow = form.querySelector('[data-ca-add-row]');
    if (addRow) addRow.hidden = groups.every(group => group.match === 'THEN') && conditions().length > 0;
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
        header.append(iconButton(i18n.t('ca.builder.removeGroup', groupIndex + 1), 'close', () => {
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
      const add = el('button', 'btn-secondary ca-add-here');
      add.append(text.icon('plus'), el('span', null, i18n.t('ca.builder.addToGroup')));
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
      completed.append(candles, el('span', 'ca-phrase-unit', i18n.t('ca.builder.candlesUnit')));
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
    const adders = el('div', 'ca-add-row ca-sequence-adders');
    if (group.conditions.length < (catalog.maxSteps || 4)) {
      const add = el('button', 'btn-secondary ca-add-here');
      add.append(text.icon('plus'), el('span', null, i18n.t('ca.builder.addStep')));
      add.type = 'button';
      add.disabled = conditions().length >= catalog.maxConditions;
      add.addEventListener('click', () => addCondition(group));
      adders.append(add);
    }
    // While the sequence is the whole rule, it is also where the rule grows into more conditions.
    if (state.definition.groups.every(other => other.match === 'THEN') && state.definition.groups.length < catalog.maxGroups) {
      const combine = el('button', 'ca-text-button', i18n.t('ca.builder.combine'));
      combine.type = 'button';
      combine.title = i18n.t('ca.builder.sequenceHint');
      combine.disabled = conditions().length >= catalog.maxConditions;
      combine.addEventListener('click', () => addCondition(null));
      adders.append(combine);
    }
    if (adders.childElementCount) parts.push(adders);
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

  /** The condition in words for its card's heading; a reversed one is led by a NOT badge. */
  function writeSentence(node, condition, ordered) {
    const words = ordered ? text.describeStep({ ...condition, negate: false }, catalog.byType) : text.describe({ ...condition, negate: false }, catalog.byType);
    node.replaceChildren();
    if (condition.negate) node.append(el('span', 'ca-chip-not', i18n.t('ca.badge.not')), ' ');
    node.append(words);
  }

  /** The kind of condition with its icon: "Indicator", "Candlestick pattern", "Insider trades". */
  function writeKind(node, condition) {
    node.replaceChildren(text.icon(text.conditionIcon(condition)), el('span', null, kindLabel(condition)));
  }

  /** Removing a condition can be undone for a few seconds from the save bar. */
  let undoTimer = null;
  function offerUndo(restore) {
    clearTimeout(undoTimer);
    const undo = el('button', 'ca-text-button ca-undo');
    undo.type = 'button';
    undo.append(text.icon('undo'), el('span', null, i18n.t('ca.builder.undo')));
    undo.addEventListener('click', () => { clearTimeout(undoTimer); nodes.status.replaceChildren(); restore(); });
    nodes.status.replaceChildren(el('span', null, i18n.t('ca.builder.removed')), ' ', undo);
    undoTimer = setTimeout(() => { if (nodes.status.contains(undo)) nodes.status.replaceChildren(); }, 8000);
  }

  function conditionCard(condition, group, index) {
    const ordered = group.match === 'THEN';
    const card = el('article', `ca-condition ca-tone-${tone(condition)}${condition.negate ? ' is-negated' : ''}`);
    card.dataset.conditionId = condition.id;
    const header = el('div', 'ca-condition-header');
    const label = el('span', 'ca-kind');
    writeKind(label, condition);
    const sentence = el('p', 'ca-condition-sentence');
    writeSentence(sentence, condition, ordered);
    const readout = el('span', 'ca-readout');
    readout.dataset.readout = condition.id;
    const tools = el('div', 'ca-condition-tools');
    const groups = state.definition.groups;
    const all = conditions();
    const position = all.indexOf(condition);
    // A pattern step cannot be reversed: "no pattern on this candle" holds almost always.
    const reversible = !(ordered && condition.kind === 'PATTERN');
    const sequenceable = !ordered && canBeStep(condition);
    const remove = () => {
      const snapshotGroups = JSON.parse(JSON.stringify(state.definition));
      group.conditions.splice(index, 1);
      if (!group.conditions.length && groups.length > 1) groups.splice(groups.indexOf(group), 1);
      else normalizeSteps(group);
      renderGroups(); changed();
      offerUndo(() => { state.definition = snapshotGroups; adoptSequences(); renderGroups(); changed(); });
      form.querySelector('[data-ca-add-condition]')?.focus();
    };
    tools.append(text.menu(i18n.t('ca.builder.conditionMenu', kindLabel(condition)), [
      reversible ? { label: i18n.t(condition.negate ? 'ca.builder.unreverse' : 'ca.builder.negate'), icon: 'reverse',
        run: () => { condition.negate = !condition.negate; renderGroups(); changed(); } } : null,
      sequenceable ? { label: i18n.t('ca.builder.then'), icon: 'sequence',
        disabled: conditions().length >= catalog.maxConditions || (group.conditions.length > 1 && groups.length >= catalog.maxGroups),
        run: () => startSequence(condition, group) } : null,
      { label: i18n.t('ca.builder.duplicate'), icon: 'copy',
        disabled: all.length >= catalog.maxConditions || (ordered && group.conditions.length >= (catalog.maxSteps || 4)),
        run: () => {
          const copy = JSON.parse(JSON.stringify(condition)); copy.id = newId('c');
          group.conditions.splice(index + 1, 0, copy);
          normalizeSteps(group);
          renderGroups(); changed();
        } },
      'divider',
      { label: i18n.t('ca.builder.moveUp'), icon: 'up', disabled: position === 0 && !canLeaveAtEdge(group), run: () => move(condition, -1) },
      { label: i18n.t('ca.builder.moveDown'), icon: 'down', disabled: position === all.length - 1 && !canLeaveAtEdge(group), run: () => move(condition, 1) }
    ], 'ca-condition-menu'));
    const removeButton = iconButton(i18n.t('ca.builder.remove'), 'close', remove);
    tools.append(removeButton);
    if (ordered) {
      // The number is shown; "Step 2" is what a screen reader says.
      const badge = el('span', 'ca-step-badge');
      const number = el('span', null, String(index + 1));
      number.setAttribute('aria-hidden', 'true');
      badge.append(number, el('span', 'visually-hidden', i18n.t('ca.builder.stepNumber', index + 1)));
      badge.title = i18n.t('ca.builder.stepNumber', index + 1);
      header.append(badge);
    }
    const heading = el('div', 'ca-condition-heading');
    heading.append(label, sentence);
    header.append(heading, readout, tools);
    const body = el('div', 'ca-condition-body');
    const refresh = () => {
      writeSentence(sentence, condition, ordered);
      writeKind(label, condition);
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
    // The keyboard stays on the moved condition's menu, on its new card.
    nodes.groups.querySelector(`[data-condition-id="${CSS.escape(condition.id)}"] .ca-condition-menu > summary`)?.focus();
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
        // The levels the indicator is usually read against, one click away: "Common: 30 · 70".
        const levels = hints(condition.subject);
        if (levels.length) {
          const common = el('div', 'ca-hints');
          common.append(el('span', 'ca-hints-label', i18n.t('ca.builder.common')));
          levels.forEach(hint => {
            const chip = el('button', 'ca-hint', text.number(hint));
            chip.type = 'button';
            chip.setAttribute('aria-label', i18n.t('ca.builder.useValue', text.number(hint)));
            chip.addEventListener('click', () => { input.value = String(hint); condition.compare = { kind: 'VALUE', value: hint }; refresh(); });
            common.append(chip);
          });
          wrapper.append(common);
        }
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
    const input = restoreWhenBlank(numberInput(condition.step?.candles || 1, { min: 1, max: catalog.maxWindow || 60, step: 1 }, value => {
      if (value == null) return;
      condition.step = { ...condition.step, candles: value };
      syncSpan(group); refresh();
    }), () => condition.step?.candles || 1);
    input.setAttribute('aria-label', i18n.t('ca.builder.stepInARowAria'));
    const phrase = el('div', 'ca-phrase');
    phrase.append(input, el('span', 'ca-phrase-unit', i18n.t('ca.builder.inARowUnit')));
    const wrapper = field(i18n.t('ca.builder.stepInARow'), phrase, LEARN_OPTIONS.inARow);
    wrapper.classList.add('ca-field-wide');
    return wrapper;
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

  /**
   * When the test must hold, as one phrase: "[On the latest candle]", or "[At least once in the last] [5] candles".
   * The number sits inside the phrase it belongs to, so no label has to say "…".
   */
  function windowFields(condition, refresh, rebuild) {
    const span = condition.window || { mode: 'LATEST' };
    const crossing = ['CROSSES_ABOVE', 'CROSSES_BELOW', 'NEW_HIGH', 'NEW_LOW'].includes(condition.operator);
    const options = [['LATEST', i18n.t('ca.window.label.LATEST')], ['ANY', i18n.t('ca.window.label.ANY')]];
    if (!crossing) options.push(['EVERY', i18n.t('ca.window.label.EVERY')]);
    const phrase = el('div', 'ca-phrase');
    const mode = select(options, span.mode, value => {
      condition.window = value === 'LATEST' ? { mode: 'LATEST' } : { mode: value, candles: span.candles && span.candles > 1 ? span.candles : 3 };
      rebuild();
    }, i18n.t('ca.builder.when'));
    phrase.append(mode);
    if (span.mode !== 'LATEST') {
      const candles = numberInput(span.candles, { min: 1, max: catalog.maxWindow || 60, step: 1 }, value => {
        condition.window = { mode: span.mode, candles: value }; refresh();
      });
      candles.setAttribute('aria-label', i18n.t('ca.builder.candles'));
      phrase.append(candles, el('span', 'ca-phrase-unit', i18n.t('ca.builder.candlesUnit')));
    }
    const wrapper = field(i18n.t('ca.builder.when'), phrase, LEARN_OPTIONS.window);
    wrapper.classList.add('ca-field-wide');
    return [wrapper];
  }

  function indicatorButton(operand, onChange) {
    const button = el('button', 'ca-indicator-button');
    button.append(el('span', null, text.operandLabel(operand, catalog.byType)), text.icon('edit'));
    button.type = 'button';
    button.setAttribute('aria-label', i18n.t('ca.builder.changeIndicator', text.operandLabel(operand, catalog.byType)));
    button.addEventListener('click', () => chooseIndicator(operand).then(chosen => { if (chosen) onChange(chosen); }));
    return button;
  }

  /** Indicator chooser: search the catalog, then set its numbers and the line to watch. */
  function chooseIndicator(current) {
    return new Promise(resolve => {
      const dialog = el('dialog', 'ca-dialog ca-indicator-dialog');
      const head = el('div', 'ca-dialog-head');
      const heading = el('h2', null, i18n.t('ca.indicatorDialog.title'));
      heading.id = 'ca-indicator-title';
      dialog.setAttribute('aria-labelledby', heading.id);
      head.append(heading, iconButton(i18n.t('common.close'), 'close', () => dialog.close()));
      const content = el('div', 'ca-dialog-body');
      let result = null;
      dialog.append(head, content);
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
    // "Detected | within the last [5] candles": the number inside its phrase.
    let windowField = null;
    if (!step) {
      const phrase = el('div', 'ca-phrase');
      const candles = numberInput(condition.window?.candles ?? 5, { min: 1, max: catalog.maxWindow || 60, step: 1 },
        value => { condition.window = { mode: 'ANY', candles: value }; refresh(); });
      candles.setAttribute('aria-label', i18n.t('ca.builder.candles'));
      phrase.append(el('span', 'ca-phrase-unit', i18n.t('ca.builder.withinLast')), candles, el('span', 'ca-phrase-unit', i18n.t('ca.builder.candlesUnit')));
      windowField = field(windowLabel(confirmed), phrase, windowLearn(confirmed));
      windowField.classList.add('ca-field-wide');
    }
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
    const patternsField = field(i18n.t('ca.builder.patterns'), chooser);
    patternsField.classList.add('ca-field-wide');
    fields.push(patternsField);
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
    const dialog = el('dialog', 'ca-dialog ca-picker');
    const head = el('div', 'ca-dialog-head');
    const heading = el('h2', null, i18n.t(ordered ? 'ca.conditionPicker.stepTitle' : 'ca.conditionPicker.title'));
    heading.id = 'ca-picker-title';
    dialog.setAttribute('aria-labelledby', heading.id);
    const closeButton = iconButton(i18n.t('common.close'), 'close', () => dialog.close());
    head.append(heading, closeButton);
    const searchBox = el('label', 'ca-search ca-picker-search');
    const search = el('input');
    search.type = 'search';
    search.placeholder = i18n.t('ca.conditionPicker.search');
    search.setAttribute('aria-label', i18n.t('ca.conditionPicker.search'));
    search.autocomplete = 'off';
    searchBox.append(text.icon('search'), search);
    const sectionsBox = el('div', 'ca-picker-sections');
    let picked = false;
    const offered = choices().filter(choice => !ordered || !['outlook', 'insider', 'congress'].includes(choice.key));
    // A new sequence: the target itself while it is empty, otherwise a new group after it.
    if (!ordered && (!target.conditions.length || groups.length < catalog.maxGroups)) offered.push({ key: 'sequence', sequence: true });
    const optionFor = choice => {
      const option = el('button', `ca-picker-option ca-picker-${choice.key}`);
      option.type = 'button';
      const icon = el('span', 'ca-picker-icon');
      icon.append(text.icon(PICKER_ICONS[choice.key] || 'indicator'));
      const words = el('span', 'ca-picker-words');
      words.append(el('strong', null, i18n.t(`ca.picker.${choice.key}.title`)), el('span', null, i18n.t(`ca.picker.${choice.key}.text`)));
      option.append(icon, words);
      option.addEventListener('click', () => pick(choice));
      return option;
    };
    /** An indicator found by name: a condition on it starts straight away, with the usual comparison. */
    const indicatorOption = item => {
      const option = el('button', 'ca-picker-option ca-picker-indicator');
      option.type = 'button';
      const icon = el('span', 'ca-picker-icon');
      icon.append(text.icon('indicator'));
      const words = el('span', 'ca-picker-words');
      words.append(el('strong', null, text.indicatorName(item.type)), el('span', null, `${item.shortName} · ${i18n.t(`ind.category.${item.category}`)}`));
      option.append(icon, words);
      option.addEventListener('click', () => pick({ key: 'indicator', make: () => {
        const made = { kind: 'VALUE', subject: { kind: 'INDICATOR', type: item.type, parameters: {}, output: item.outputs[0] }, operator: 'ABOVE', window: { mode: 'LATEST' } };
        suggestCompare(made);
        return made;
      } }));
      return option;
    };
    const drawSections = () => {
      const query = search.value.trim().toLowerCase();
      const found = choice => !query || [i18n.t(`ca.picker.${choice.key}.title`), i18n.t(`ca.picker.${choice.key}.text`)]
        .some(value => value.toLowerCase().includes(query));
      const parts = PICKER_SECTIONS.map(section => {
        const items = offered.filter(choice => section.keys.includes(choice.key) && found(choice));
        if (!items.length) return null;
        const box = el('section', 'ca-picker-section');
        const title = el('h3', null, i18n.t(`ca.conditionPicker.section.${section.id}`));
        title.id = `ca-picker-section-${section.id}`;
        box.setAttribute('aria-labelledby', title.id);
        const grid = el('div', 'ca-picker-grid');
        items.forEach(choice => grid.append(optionFor(choice)));
        box.append(title, grid);
        return box;
      }).filter(Boolean);
      if (query) {
        const indicators = catalog.indicators.filter(item => [text.indicatorName(item.type), item.shortName, item.type]
          .some(value => String(value).toLowerCase().includes(query))).slice(0, 8);
        if (indicators.length) {
          const box = el('section', 'ca-picker-section');
          const title = el('h3', null, i18n.t('ca.conditionPicker.section.indicators'));
          title.id = 'ca-picker-section-indicators';
          box.setAttribute('aria-labelledby', title.id);
          const grid = el('div', 'ca-picker-grid');
          indicators.forEach(item => grid.append(indicatorOption(item)));
          box.append(title, grid);
          parts.push(box);
        }
      }
      sectionsBox.replaceChildren(...(parts.length ? parts : [el('p', 'ca-muted ca-picker-none', i18n.t('ca.conditionPicker.none', search.value.trim()))]));
    };
    search.addEventListener('input', drawSections);
    search.addEventListener('keydown', event => {
      if (event.key === 'Enter') { event.preventDefault(); sectionsBox.querySelector('button')?.click(); }
    });
    async function pick(choice) {
      {
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
        card?.querySelector('.ca-condition-body select, .ca-condition-body input, .ca-condition-body button')?.focus();
      }
    }
    const body = el('div', 'ca-dialog-body');
    body.append(el('p', 'ca-muted', i18n.t('ca.conditionPicker.intro')), searchBox, sectionsBox);
    dialog.append(head, body);
    dialog.addEventListener('close', () => {
      dialog.remove();
      if (!picked) { tidyGroups(); renderGroups(); }
    });
    drawSections();
    document.body.append(dialog);
    dialog.showModal();
    search.focus();
  }

  /** The picker's choices by family, and the icon of each. */
  const PICKER_SECTIONS = [
    { id: 'price', keys: ['rsi', 'maCross', 'volume', 'breakout', 'priceLevel', 'priceChange', 'anyIndicator'] },
    { id: 'patterns', keys: ['candlestick', 'elliott', 'harmonic'] },
    { id: 'filings', keys: ['insider', 'congress', 'outlook'] },
    { id: 'advanced', keys: ['sequence'] }
  ];
  const PICKER_ICONS = { rsi: 'indicator', maCross: 'indicator', volume: 'indicator', breakout: 'price', priceLevel: 'price', priceChange: 'price',
    anyIndicator: 'search', candlestick: 'pattern', elliott: 'pattern', harmonic: 'pattern', insider: 'insider', congress: 'congress',
    outlook: 'outlook', sequence: 'sequence' };

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
        el('small', null, i18n.t('ca.preview.asOf', text.date(result.latestCandle * 1000), text.number(result.latestClose))));
      parts.push(verdict, historyStrip(result));
    }
    nodes.result.replaceChildren(...parts);
    renderCoverage(result.coverage);
    renderMobileReading(result);
  }

  function historyStrip(result) {
    const box = el('div', 'ca-history');
    const heading = el('p', 'ca-history-title');
    const label = el('span', 'learn-term', i18n.plural('ca.preview.history', result.history.length, result.history.length, result.replayedCandles));
    label.dataset.learn = LEARN_OPTIONS.history;
    label.dataset.learnTitle = i18n.t('ca.preview.historyTitle');
    heading.append(label);
    box.append(heading);
    // No triggers: the sentence says so; an empty axis would only look broken.
    if (result.history.length) box.append(...historyChart(result));
    if (result.medianReturn != null) {
      const statsLine = el('p', 'ca-history-stats', i18n.t('ca.preview.after', state.outcomeCandles,
        text.signed(result.medianReturn), text.number(result.positiveShare)));
      statsLine.classList.add('learn-term');
      statsLine.dataset.learn = LEARN_OPTIONS.outcome;
      statsLine.dataset.learnTitle = i18n.t('ca.preview.afterTitle');
      box.append(statsLine);
    } else if (!result.history.length && result.elliottHistory !== 'PREPARING') {
      // While the Elliott history is prepared the replay is short: "loosen a condition" would mislead.
      box.append(el('p', 'ca-muted', i18n.t('ca.preview.noHistory')));
    }
    // Elliott counts are replayed as they stood on each candle. A background job prepares that history once per
    // stock; until it is ready the replay covers the latest candles only.
    if (result.elliottHistory === 'PREPARING') {
      box.append(el('p', 'ca-muted ca-preview-preparing', i18n.t('ca.preview.elliottPreparing', result.symbol, result.replayedCandles)));
    } else if (result.shortenedBy === 'ELLIOTT_WAVE') {
      box.append(el('p', 'ca-muted', i18n.t('ca.preview.elliottShort', result.replayedCandles)));
    }
    box.append(el('p', 'ca-disclaimer', i18n.t('ca.preview.disclaimer')));
    return box;
  }

  /** The replay's triggers on a time line, green where the price was higher {@code outcomeCandles} later, red where lower. */
  function historyChart(result) {
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
    const range = el('div', 'ca-history-range');
    range.append(el('span', null, i18n.date(result.firstReplayed * 1000, { month: 'short', year: 'numeric' })),
      el('span', null, i18n.date(result.latestCandle * 1000, { month: 'short', year: 'numeric' })));
    const legend = el('p', 'ca-history-legend');
    legend.append(el('span', 'ca-history-key up', i18n.t('ca.preview.keyHigher', state.outcomeCandles)),
      el('span', 'ca-history-key down', i18n.t('ca.preview.keyLower')));
    if (result.history.some(point => point.outcomeReturn == null)) legend.append(el('span', 'ca-history-key pending', i18n.t('ca.preview.keyPending')));
    return [svg, range, legend];
  }

  function renderCoverage(coverage) {
    if (!coverage) { nodes.coverage.replaceChildren(); return; }
    const box = el('div', 'ca-coverage');
    if (!coverage.stocks) {
      if (!(state.pendingSymbol && state.pendingChoice)) { nodes.coverage.replaceChildren(); return; }
      box.append(el('p', 'ca-coverage-note', i18n.t('ca.coverage.pendingOnly')));
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

  /** With no conditions yet: what to do, and three examples to start from (all of them a click further). */
  function emptyRule() {
    const empty = el('div', 'ca-rule-empty');
    empty.append(el('strong', null, i18n.t('ca.builder.emptyTitle')), el('p', 'ca-muted', i18n.t('ca.builder.emptyText')));
    if (!state.id) {
      const examples = el('div', 'ca-start-templates');
      examples.setAttribute('role', 'group');
      examples.setAttribute('aria-label', i18n.t('ca.builder.startWith'));
      examples.append(el('span', 'ca-hints-label', i18n.t('ca.builder.startWith')));
      text.templates.slice(0, 3).forEach(template => {
        const button = el('button', 'ca-start-template', i18n.t(`ca.template.${template.id}.name`));
        button.type = 'button';
        button.title = i18n.t(`ca.template.${template.id}.text`);
        button.addEventListener('click', () => useTemplate(template.id));
        examples.append(button);
      });
      const all = el('button', 'ca-text-button', i18n.t('ca.builder.allExamples', text.templates.length));
      all.type = 'button';
      all.addEventListener('click', openExamples);
      examples.append(all);
      empty.append(examples);
    }
    return empty;
  }

  /** Every example as a card; choosing one fills in the rule. */
  function openExamples() {
    const dialog = el('dialog', 'ca-dialog ca-examples-dialog');
    const head = el('div', 'ca-dialog-head');
    const heading = el('h2', null, i18n.t('ca.templates.title'));
    heading.id = 'ca-examples-title';
    dialog.setAttribute('aria-labelledby', heading.id);
    head.append(heading, iconButton(i18n.t('common.close'), 'close', () => dialog.close()));
    const grid = el('div', 'ca-templates');
    text.templates.forEach(template => {
      const card = el('button', 'ca-template');
      card.type = 'button';
      card.append(el('strong', 'ca-template-name', i18n.t(`ca.template.${template.id}.name`)),
        el('span', 'ca-template-text', i18n.t(`ca.template.${template.id}.text`)), text.chips(template.definition, catalog.byType, 3));
      const use = el('span', 'ca-template-use', i18n.t('ca.templates.use'));
      use.append(text.icon('arrow'));
      card.append(use);
      card.addEventListener('click', () => { dialog.close(); useTemplate(template.id); });
      grid.append(card);
    });
    const body = el('div', 'ca-dialog-body');
    body.append(el('p', 'ca-muted', i18n.t('ca.templates.note')), grid);
    dialog.append(head, body);
    dialog.addEventListener('close', () => dialog.remove());
    document.body.append(dialog);
    dialog.showModal();
    grid.querySelector('button')?.focus();
  }

  function renderStart() {
    nodes.start?.replaceChildren();
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

  /** The note grows with its text, up to three lines. */
  function growNote() {
    if (!nodes.description.scrollHeight) return;
    nodes.description.style.height = 'auto';
    nodes.description.style.height = `${Math.min(nodes.description.scrollHeight + 2, 92)}px`;
  }

  function syncBasics() {
    growNote();
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
    nodes.description.addEventListener('input', scheduleDraft);
    nodes.description.addEventListener('input', growNote);
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
    nodes.email.addEventListener('change', () => { state.emailEnabled = nodes.email.checked; dirty = true; renderStepSummaries(); scheduleDraft(); });
    nodes.notifyExisting.addEventListener('change', () => { state.notifyExisting = nodes.notifyExisting.checked; dirty = true; scheduleDraft(); });
    nodes.description.addEventListener('input', renderStepSummaries);
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
    if (!conditions().length) {
      nodes.status.textContent = i18n.t('ca.save.needCondition');
      goToStep('rule', '[data-ca-add-condition]');
      return;
    }
    if (state.definition.groups.some(group => group.match === 'THEN' && group.conditions.length < 2)) {
      nodes.status.textContent = i18n.t('ca.save.needSteps');
      goToStep('rule', '.ca-sequence-adders button');
      return;
    }
    if (!draft.targets.length && !(state.pendingSymbol && state.pendingChoice)) {
      nodes.status.textContent = i18n.t('ca.save.needStocks');
      goToStep('where', '[data-ca-targets] input:not(:disabled)');
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
      forgetDraft();
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
    renderSteps();
    foldFinishedSteps();
    dirty = Boolean(page.dataset.startTemplate || page.dataset.startChartAlert || assistantDraft);
    draftReady = true;
    const blank = !state.id && !page.dataset.startTemplate && !page.dataset.startChartAlert && !page.dataset.startSymbol
      && !page.dataset.startWatchlist && !assistantDraft;
    if (blank) offerDraft();
    runPreview();
  }

  // Deferred scripts run in document order, so the navigation's request helper may not exist yet.
  if (window.StockWatchLists) init();
  else document.addEventListener('DOMContentLoaded', init, { once: true });
})();
