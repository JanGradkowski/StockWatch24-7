(() => {
  'use strict';
  const i18n = window.StockWatchI18n;
  const node = (tag, text, className) => {
    const e = document.createElement(tag); if (text != null) e.textContent = text;
    if (className) e.className = className; return e;
  };
  const headers = () => {
    const h = { 'Content-Type': 'application/json' };
    const token = document.querySelector('meta[name="_csrf"]')?.content || document.getElementById('accountThemeSync')?.dataset.csrfToken;
    const name = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    if (token) h[name] = token; return h;
  };
  async function api(url, options = {}) {
    const response = await fetch(url, { ...options, headers: { ...headers(), ...options.headers } });
    if (response.redirected && response.url.includes('/login')) throw new Error(i18n.t('common.sessionExpired'));
    const value = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(value.error || value.detail || i18n.t('common.saveFailed'));
    return value;
  }
  let pending = null;
  // texts: optional { title, intro, submit } for callers whose wording differs from following signals.
  function choose(symbol, { mode = 'signals', signalTypes = [], texts = {} } = {}) {
    if (pending) return pending;
    pending = new Promise(resolve => {
      const previousFocus = document.activeElement;
      const dialog = node('dialog', null, 'wl-dialog wl-picker');
      const form = node('form');
      const applyingSignals = mode === 'signals';
      const title = node('h2', texts.title || i18n.t(applyingSignals ? 'watchlistPicker.applyTitle' : 'watchlistPicker.addTitle', symbol)); title.id = 'wl-picker-title';
      dialog.setAttribute('aria-labelledby', title.id);
      const intro = node('p', texts.intro || i18n.t(applyingSignals ? 'watchlistPicker.applyIntro' : 'watchlistPicker.addIntro'), 'wl-muted');
      const options = node('fieldset', null, 'wl-picker-options'); options.append(node('legend', i18n.t('watchlistPicker.yourWatchlists')));
      const status = node('p', i18n.t('watchlistPicker.loading'), 'wl-status'); status.setAttribute('role', 'status');
      const label = node('label', i18n.t('watchlistPicker.orCreate'), 'wl-field');
      const name = node('input'); name.type = 'text'; name.maxLength = 100; name.placeholder = i18n.t('watchlistPicker.namePlaceholder');
      label.append(name);
      const actions = node('div', null, 'wl-actions');
      const cancel = node('button', i18n.t('common.cancel'), 'btn-secondary'); cancel.type = 'button';
      const save = node('button', texts.submit || i18n.t(applyingSignals ? 'watchlistPicker.apply' : 'watchlistPicker.add'), 'btn-accent'); save.type = 'submit'; save.disabled = true;
      actions.append(cancel, save); form.append(title, intro, options, label, status, actions); dialog.append(form);
      let finished = false;
      function finish(value) {
        if (finished) return; finished = true;
        dialog.close(); dialog.remove(); previousFocus?.focus(); resolve(value);
      }
      cancel.addEventListener('click', () => finish(null));
      dialog.addEventListener('cancel', event => { event.preventDefault(); finish(null); });
      dialog.addEventListener('close', () => { if (!finished) finish(null); });
      form.addEventListener('submit', event => {
        event.preventDefault();
        if (save.disabled) return;
        const ids = Array.from(options.querySelectorAll('input:checked'), input => Number(input.value));
        if (!ids.length && !name.value.trim()) { status.textContent = i18n.t('watchlistPicker.chooseOrName'); name.focus(); return; }
        finish({ watchlistIds: ids, newWatchlistName: name.value.trim() || null });
      });
      document.body.append(dialog); dialog.showModal();
      Promise.all([api('/api/watchlists'), api(`/api/watchlists/memberships/${encodeURIComponent(symbol)}`)])
        .then(([lists, selected]) => {
          if (finished) return;
          for (const list of lists) {
            const row = node('label', null, 'wl-check'); const input = node('input');
            input.type = 'checkbox'; input.value = list.id; input.checked = selected.includes(list.id);
            const text = node('span', `${list.name} · ${i18n.plural('watchlists.instruments', list.instruments, list.instruments)}`);
            if (input.checked) text.append(node('small', i18n.t('watchlistPicker.alreadyIn'), 'wl-picker-membership'));
            const blocked = applyingSignals && list.allowedTypes ? signalTypes.filter(type => !list.allowedTypes.includes(type)) : [];
            if (blocked.length) {
              input.checked = false; input.disabled = true;
              const known = ['CANDLESTICK', 'ELLIOTT_WAVE', 'HARMONIC_FORMATION', 'OUTLOOK', 'CONGRESS', 'INSIDER', 'CUSTOM'];
              const names = blocked.map(type => known.includes(type) ? i18n.t(`followType.${type}`) : type).join(', ');
              text.append(node('small', i18n.t('watchlistPicker.notAllowed', names), 'wl-picker-membership'));
            }
            row.append(input, text); options.append(row);
          }
          status.textContent = lists.length
            ? applyingSignals
              ? i18n.t('watchlistPicker.applyStatus')
              : i18n.t('watchlistPicker.addStatus')
            : i18n.t('watchlistPicker.none');
          save.disabled = false; if (!lists.length) name.focus();
        }).catch(error => { if (!finished) status.textContent = error.message; });
    }).finally(() => { pending = null; });
    return pending;
  }
  window.StockWatchLists = { choose, api, node };
})();
