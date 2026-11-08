(() => {
  'use strict';
  // The "Ask StockWatch" assistant panel, on every signed-in page. The conversation lives in this tab's session
  // storage, so it survives moving between pages (every link reloads the page) and is gone when the tab closes or the
  // user signs out. Questions go to /api/assistant/chat with the page's path and, where a page offers it, what the
  // page shows now (window.StockWatchAssistantHooks.context). Answers are drawn as DOM nodes, never as HTML.
  const root = document.querySelector('[data-assistant]');
  if (!root || root.dataset.ready) return;
  root.dataset.ready = 'true';
  const i18n = window.StockWatchI18n;
  const hooks = window.StockWatchAssistantHooks || (window.StockWatchAssistantHooks = {});
  const STORE = 'stockwatch.assistant.v1';
  const DRAFT = 'stockwatch.assistant.draft';
  const MAX_QUESTION = 1000;
  const MAX_KEPT = 30;

  const nodes = {
    fab: root.querySelector('[data-assistant-toggle]'), panel: root.querySelector('.sw-assistant-panel'),
    close: root.querySelector('[data-assistant-close]'), fresh: root.querySelector('[data-assistant-new]'),
    notice: root.querySelector('[data-assistant-notice]'), noticeOk: root.querySelector('[data-assistant-notice-ok]'),
    log: root.querySelector('[data-assistant-log]'), empty: root.querySelector('[data-assistant-empty]'),
    suggestions: root.querySelector('[data-assistant-suggestions]'), chips: root.querySelector('[data-assistant-chips]'),
    form: root.querySelector('[data-assistant-form]'), input: root.querySelector('[data-assistant-input]'),
    counter: root.querySelector('[data-assistant-counter]'), send: root.querySelector('[data-assistant-send]')
  };

  // ------------------------------------------------------------------ storage

  function load() {
    try {
      const saved = JSON.parse(sessionStorage.getItem(STORE) || 'null');
      if (saved && saved.account === root.dataset.account && Array.isArray(saved.messages)) return saved;
    } catch (_) { /* unreadable or blocked storage starts a new conversation */ }
    return { account: root.dataset.account, open: false, noticeSeen: false, messages: [] };
  }

  const state = load();
  let busy = false;

  function save() {
    state.messages = state.messages.slice(-MAX_KEPT);
    try { sessionStorage.setItem(STORE, JSON.stringify(state)); } catch (_) { /* the panel works without storage */ }
  }

  function forget() {
    try { sessionStorage.removeItem(STORE); sessionStorage.removeItem(DRAFT); } catch (_) { /* nothing stored */ }
  }

  // Signing out ends the conversation, so the next person in this tab does not see it.
  document.querySelectorAll('.logout-form').forEach(form => form.addEventListener('submit', forget));

  // ------------------------------------------------------------------ small helpers

  const el = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };

  function headers() {
    const result = { 'Content-Type': 'application/json' };
    const token = document.querySelector('meta[name="_csrf"]')?.content || document.getElementById('accountThemeSync')?.dataset.csrfToken;
    const name = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    if (token) result[name] = token;
    return result;
  }

  /** Same-origin app paths only: "/settings/scoring", never "//host" or "https://…". */
  const safePath = href => typeof href === 'string' && /^\/(?!\/)[A-Za-z0-9/_.?=&%-]*$/.test(href);

  // ------------------------------------------------------------------ Markdown, as text nodes

  /** **bold**, `code` and [text](/path) inside one line. */
  function inline(text) {
    const parts = [];
    const pattern = /\*\*([^*]+)\*\*|`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)/g;
    let last = 0;
    let match;
    while ((match = pattern.exec(text))) {
      if (match.index > last) parts.push(document.createTextNode(text.slice(last, match.index)));
      if (match[1] != null) parts.push(el('strong', null, match[1]));
      else if (match[2] != null) parts.push(el('code', null, match[2]));
      else if (safePath(match[4])) {
        const link = el('a', null, match[3]);
        link.href = match[4];
        parts.push(link);
      } else parts.push(document.createTextNode(match[3]));
      last = pattern.lastIndex;
    }
    if (last < text.length) parts.push(document.createTextNode(text.slice(last)));
    return parts;
  }

  /** Paragraphs and bulleted or numbered lists; headings are shown as bold lines. */
  function markdown(text) {
    const box = el('div', 'sw-assistant-text');
    let list = null;
    let paragraph = null;
    const close = () => { list = null; paragraph = null; };
    String(text || '').replace(/\r/g, '').split('\n').forEach(raw => {
      const line = raw.trim();
      if (!line) { close(); return; }
      const bullet = line.match(/^[-*•]\s+(.*)$/);
      const numbered = line.match(/^(\d+)[.)]\s+(.*)$/);
      if (bullet || numbered) {
        const tag = numbered ? 'ol' : 'ul';
        if (!list || list.tagName.toLowerCase() !== tag) {
          list = el(tag);
          if (numbered && Number(numbered[1]) > 1) list.start = Number(numbered[1]);
          box.append(list);
        }
        paragraph = null;
        const item = el('li');
        item.append(...inline(numbered ? numbered[2] : bullet[1]));
        list.append(item);
        return;
      }
      const heading = line.match(/^#{1,6}\s+(.*)$/);
      if (heading) { close(); const strong = el('p'); strong.append(el('strong', null, heading[1])); box.append(strong); return; }
      if (list && raw.startsWith('  ')) { list.lastElementChild.append(' ', ...inline(line)); return; }
      list = null;
      if (!paragraph) { paragraph = el('p'); box.append(paragraph); } else paragraph.append(el('br'));
      paragraph.append(...inline(line));
    });
    return box;
  }

  // ------------------------------------------------------------------ drawing the conversation

  function render() {
    nodes.log.querySelectorAll('.sw-assistant-message').forEach(node => node.remove());
    nodes.empty.hidden = state.messages.length > 0;
    state.messages.forEach((message, index) => nodes.log.append(messageNode(message, index)));
    nodes.suggestions.hidden = state.messages.length > 0 && !nodes.chips.querySelector('[data-learn-chip]');
    nodes.notice.hidden = state.noticeSeen;
    scrollToEnd();
  }

  function scrollToEnd() {
    nodes.log.scrollTop = nodes.log.scrollHeight;
  }

  function messageNode(message, index) {
    const user = message.role === 'user';
    const item = el('div', `sw-assistant-message ${user ? 'is-user' : 'is-bot'}${message.error ? ' is-error' : ''}`);
    item.append(el('span', 'visually-hidden', i18n.t(user ? 'assistant.you' : 'assistant.bot') + ': '));
    if (user) {
      item.append(el('p', 'sw-assistant-text', message.text));
      return item;
    }
    if (message.error) {
      item.append(el('p', 'sw-assistant-text', message.text));
      if (message.retry) {
        const retry = el('button', 'btn-secondary sw-assistant-small', i18n.t('assistant.retry'));
        retry.type = 'button';
        retry.addEventListener('click', () => {
          state.messages.splice(index, 1);
          save();
          render();
          ask();
        });
        item.append(retry);
      }
      return item;
    }
    item.append(markdown(message.text));
    if (message.draft) item.append(draftCard(message.draft));
    if (message.link && safePath(message.link.path)) {
      const link = el('a', 'btn-secondary sw-assistant-small sw-assistant-link', i18n.t('assistant.link.open', message.link.label));
      link.href = message.link.path;
      item.append(link);
    }
    item.append(feedback(message, index));
    return item;
  }

  /** The alert the answer describes, in the app's own words, with a button that fills the builder in. */
  function draftCard(draft) {
    const card = el('div', 'sw-assistant-draft');
    card.append(el('strong', null, draft.name || i18n.t('assistant.draft.title')));
    const words = el('p', 'sw-assistant-draft-rule');
    const text = window.StockWatchCustomAlerts;
    if (text) {
      words.textContent = text.summary(draft);
      text.catalog().then(catalog => { words.textContent = text.summary(draft, catalog.byType); }).catch(() => { /* plain labels stay */ });
    }
    card.append(words);
    const actions = el('div', 'sw-assistant-draft-actions');
    const inBuilder = typeof hooks.applyDraft === 'function';
    const use = el('button', 'btn-accent sw-assistant-small', i18n.t(inBuilder ? 'assistant.draft.use' : 'assistant.draft.open'));
    use.type = 'button';
    use.addEventListener('click', () => {
      // The builder announces the rule it filled in (stockwatch:assistant-draft-applied), which adds the note.
      if (inBuilder) { hooks.applyDraft(draft); return; }
      try { sessionStorage.setItem(DRAFT, JSON.stringify(draft)); } catch (_) { note(i18n.t('assistant.draft.unavailable')); return; }
      window.location.assign('/custom-alerts/new?from=assistant');
    });
    actions.append(use);
    card.append(actions, el('p', 'sw-assistant-draft-note', i18n.t('assistant.draft.note')));
    return card;
  }

  /** "Not helpful" asks once, then sends the question and the answer so the knowledge files can be improved. */
  function feedback(message, index) {
    const box = el('div', 'sw-assistant-feedback');
    if (message.feedback) {
      box.append(el('span', 'sw-assistant-feedback-done', i18n.t(message.feedback === 'sent' ? 'assistant.feedback.thanks' : 'assistant.feedback.failed')));
      return box;
    }
    const start = el('button', 'sw-assistant-feedback-button', i18n.t('assistant.feedback.notHelpful'));
    start.type = 'button';
    start.addEventListener('click', () => {
      const question = el('p', null, i18n.t('assistant.feedback.confirm'));
      const send = el('button', 'btn-secondary sw-assistant-small', i18n.t('assistant.feedback.send'));
      send.type = 'button';
      const cancel = el('button', 'btn-secondary sw-assistant-small', i18n.t('assistant.feedback.cancel'));
      cancel.type = 'button';
      cancel.addEventListener('click', () => { box.replaceWith(feedback(message, index)); });
      send.addEventListener('click', async () => {
        send.disabled = true;
        const asked = state.messages.slice(0, index).reverse().find(item => item.role === 'user');
        try {
          const response = await fetch('/api/assistant/feedback', { method: 'POST', headers: headers(), credentials: 'same-origin',
            body: JSON.stringify({ page: location.pathname, question: asked?.text || '', reply: message.text }) });
          message.feedback = response.ok ? 'sent' : 'failed';
        } catch (_) {
          message.feedback = 'failed';
        }
        save();
        box.replaceWith(feedback(message, index));
      });
      const row = el('div', 'sw-assistant-feedback-actions');
      row.append(send, cancel);
      box.replaceChildren(question, row);
      send.focus();
    });
    box.append(start);
    return box;
  }

  /** A short line from the panel itself (not kept in the conversation). */
  function note(text) {
    const item = el('div', 'sw-assistant-message is-bot is-note');
    item.append(el('p', 'sw-assistant-text', text));
    nodes.log.append(item);
    scrollToEnd();
  }

  // ------------------------------------------------------------------ asking

  function pageContext() {
    try {
      const context = typeof hooks.context === 'function' ? hooks.context() : null;
      return context && typeof context === 'object' ? context : null;
    } catch (_) {
      return null;
    }
  }

  async function ask() {
    if (busy) return;
    busy = true;
    nodes.send.disabled = true;
    const thinking = el('div', 'sw-assistant-message is-bot is-thinking');
    thinking.append(el('p', 'sw-assistant-text', i18n.t('assistant.thinking')));
    nodes.log.append(thinking);
    nodes.log.setAttribute('aria-busy', 'true');
    scrollToEnd();
    // A busy model can take half a minute; say so rather than look stuck.
    const slow = setTimeout(() => { thinking.firstElementChild.textContent = i18n.t('assistant.slow'); }, 10000);
    const history = state.messages.filter(message => !message.error)
      .map(message => ({ role: message.role, text: message.text, draft: message.draft ? JSON.stringify(message.draft) : null }));
    let reply;
    try {
      const response = await fetch('/api/assistant/chat', { method: 'POST', headers: headers(), credentials: 'same-origin',
        body: JSON.stringify({ page: location.pathname, messages: history, context: pageContext() }) });
      if (response.redirected && response.url.includes('/login')) {
        reply = { role: 'model', error: true, text: i18n.t('common.sessionExpired') };
      } else {
        const body = await response.json().catch(() => ({}));
        if (response.ok && typeof body.reply === 'string') {
          reply = { role: 'model', text: body.reply, draft: body.draft || null, link: body.link || null };
        } else {
          const key = body.code ? `assistant.error.${body.code}` : 'assistant.error.generic';
          reply = { role: 'model', error: true, retry: !['ACCOUNT_DAY', 'SITE_DAY', 'DISABLED'].includes(body.code),
            text: i18n.has(key) ? i18n.t(key) : i18n.t('assistant.error.generic') };
        }
      }
    } catch (_) {
      reply = { role: 'model', error: true, retry: true, text: i18n.t('assistant.error.network') };
    }
    clearTimeout(slow);
    thinking.remove();
    nodes.log.removeAttribute('aria-busy');
    state.messages.push(reply);
    save();
    render();
    busy = false;
    updateCounter();
  }

  function submit(text) {
    const question = String(text || '').trim();
    if (!question || busy) return;
    if (question.length > MAX_QUESTION) { nodes.counter.textContent = i18n.t('assistant.tooLong', MAX_QUESTION); return; }
    // A failed answer is dropped once a new question is asked: the model expects the turns to alternate.
    while (state.messages.length && state.messages.at(-1).error) state.messages.pop();
    state.messages.push({ role: 'user', text: question });
    nodes.input.value = '';
    nodes.chips.querySelector('[data-learn-chip]')?.remove();
    save();
    render();
    ask();
  }

  function updateCounter() {
    const length = nodes.input.value.length;
    nodes.counter.textContent = length > MAX_QUESTION * 0.8 ? i18n.t('assistant.counter', length, MAX_QUESTION) : '';
    nodes.send.disabled = busy || !nodes.input.value.trim();
  }

  // ------------------------------------------------------------------ opening and closing

  function setOpen(open, { focus = true } = {}) {
    state.open = open;
    save();
    nodes.panel.hidden = !open;
    root.classList.toggle('is-open', open);
    nodes.fab.setAttribute('aria-expanded', String(open));
    if (open) {
      render();
      if (focus) (state.noticeSeen ? nodes.input : nodes.noticeOk).focus();
    } else if (focus) {
      nodes.fab.focus();
    }
  }

  nodes.fab.addEventListener('click', () => setOpen(nodes.panel.hidden));
  nodes.close.addEventListener('click', () => setOpen(false));
  nodes.fresh.addEventListener('click', () => {
    state.messages = [];
    save();
    render();
    nodes.input.focus();
  });
  nodes.noticeOk.addEventListener('click', () => {
    state.noticeSeen = true;
    save();
    nodes.notice.hidden = true;
    nodes.input.focus();
  });
  nodes.panel.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !event.defaultPrevented) { event.preventDefault(); setOpen(false); }
  });
  nodes.form.addEventListener('submit', event => { event.preventDefault(); submit(nodes.input.value); });
  nodes.input.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); submit(nodes.input.value); }
  });
  nodes.input.addEventListener('input', updateCounter);
  nodes.chips.addEventListener('click', event => {
    const chip = event.target.closest('.sw-assistant-chip');
    if (chip) submit(chip.dataset.question || chip.textContent);
  });

  // A pinned learning-mode explanation offers a question about that term in the panel.
  window.addEventListener('stockwatch:learn-pinned', event => {
    const title = event.detail?.title;
    if (!title) return;
    nodes.chips.querySelector('[data-learn-chip]')?.remove();
    const chip = el('button', 'sw-assistant-chip is-learn', `${i18n.t('assistant.askAbout')}: ${title}`);
    chip.type = 'button';
    chip.dataset.learnChip = 'true';
    chip.dataset.question = i18n.t('assistant.askAboutPrompt', title);
    nodes.chips.prepend(chip);
    nodes.suggestions.hidden = false;
  });

  // On pages with a fixed footer (the alert builder's Save bar) the button sits above it.
  const footer = document.querySelector('.ca-footer');
  if (footer && 'ResizeObserver' in window) {
    new ResizeObserver(() => root.style.setProperty('--sw-assistant-lift', `${footer.offsetHeight}px`)).observe(footer);
  }

  // The builder reports a rule filled in from the panel, so the panel can say what to do next.
  document.addEventListener('stockwatch:assistant-draft-applied', () => {
    if (!nodes.panel.hidden) note(i18n.t('assistant.draft.applied'));
  });

  window.StockWatchAssistant = Object.freeze({ open: () => setOpen(true), ask: text => { setOpen(true, { focus: false }); submit(text); } });

  updateCounter();
  if (state.open) setOpen(true, { focus: false });
  else render();
})();
