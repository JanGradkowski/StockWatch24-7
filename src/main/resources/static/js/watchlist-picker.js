(() => {
  'use strict';
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
    if (response.redirected && response.url.includes('/login')) throw new Error('Your session expired. Please sign in again.');
    const value = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(value.error || value.detail || 'The change could not be saved. Please try again.');
    return value;
  }
  let pending = null;
  function choose(symbol, { mode = 'signals', signalTypes = [] } = {}) {
    if (pending) return pending;
    pending = new Promise(resolve => {
      const previousFocus = document.activeElement;
      const dialog = node('dialog', null, 'wl-dialog wl-picker');
      const form = node('form');
      const applyingSignals = mode === 'signals';
      const title = node('h2', applyingSignals ? `Apply ${symbol}’s signal changes to watchlists` : `Add ${symbol} to watchlists`); title.id = 'wl-picker-title';
      dialog.setAttribute('aria-labelledby', title.id);
      const intro = node('p', applyingSignals
        ? 'Choose which watchlists receive the new follows. You can select a list even if this ticker is already in it.'
        : 'Choose one or more watchlists for this ticker. Existing memberships are preselected.', 'wl-muted');
      const options = node('fieldset', null, 'wl-picker-options'); options.append(node('legend', 'Your watchlists'));
      const status = node('p', 'Loading your watchlists…', 'wl-status'); status.setAttribute('role', 'status');
      const label = node('label', 'Or create a watchlist', 'wl-field');
      const name = node('input'); name.type = 'text'; name.maxLength = 100; name.placeholder = 'Watchlist name';
      label.append(name);
      const actions = node('div', null, 'wl-actions');
      const cancel = node('button', 'Cancel', 'btn-secondary'); cancel.type = 'button';
      const save = node('button', applyingSignals ? 'Apply changes' : 'Add to watchlists', 'btn-accent'); save.type = 'submit'; save.disabled = true;
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
        if (!ids.length && !name.value.trim()) { status.textContent = 'Select a watchlist or enter a name to create one.'; name.focus(); return; }
        finish({ watchlistIds: ids, newWatchlistName: name.value.trim() || null });
      });
      document.body.append(dialog); dialog.showModal();
      Promise.all([api('/api/watchlists'), api(`/api/watchlists/memberships/${encodeURIComponent(symbol)}`)])
        .then(([lists, selected]) => {
          if (finished) return;
          for (const list of lists) {
            const row = node('label', null, 'wl-check'); const input = node('input');
            input.type = 'checkbox'; input.value = list.id; input.checked = selected.includes(list.id);
            const text = node('span', `${list.name} · ${list.instruments} instruments`);
            if (input.checked) text.append(node('small', 'Already in this watchlist', 'wl-picker-membership'));
            const blocked = applyingSignals && list.allowedTypes ? signalTypes.filter(type => !list.allowedTypes.includes(type)) : [];
            if (blocked.length) {
              input.checked = false; input.disabled = true;
              const names = { CANDLESTICK: 'Candlestick', ELLIOTT_WAVE: 'Elliott Wave', HARMONIC_FORMATION: 'Harmonic', OUTLOOK: 'Automated outlook', CONGRESS: 'Congressional activity', INSIDER: 'Insider activity' };
              text.append(node('small', `Not allowed here: ${blocked.map(type => names[type] || type).join(', ')}. Edit this watchlist or choose another.`, 'wl-picker-membership'));
            }
            row.append(input, text); options.append(row);
          }
          status.textContent = lists.length
            ? applyingSignals
              ? 'Unchecking a list skips these new follows. It does not remove the ticker or its existing follows from that list.'
              : 'Unchecking a list does not remove an existing membership. Remove memberships from Manage watchlists.'
            : 'You have no watchlists yet. Name your first one below.';
          save.disabled = false; if (!lists.length) name.focus();
        }).catch(error => { if (!finished) status.textContent = error.message; });
    }).finally(() => { pending = null; });
    return pending;
  }
  window.StockWatchLists = { choose, api, node };
})();
