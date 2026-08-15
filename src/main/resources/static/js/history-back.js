(() => {
  'use strict';

  if (window.stockWatchHistoryBackInitialized) return;
  window.stockWatchHistoryBackInitialized = true;

  const ENTRY_ID_KEY = 'stockWatchHistoryEntryId';
  const SNAPSHOT_PREFIX = 'stockwatch:page-state:';
  const originalReplaceState = window.history.replaceState.bind(window.history);
  const originalPushState = window.history.pushState.bind(window.history);

  function newEntryId() {
    return window.crypto?.randomUUID?.()
      || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  }

  function stateWithEntryId(state, entryId) {
    const copy = state && typeof state === 'object' && !Array.isArray(state)
      ? { ...state }
      : {};
    copy[ENTRY_ID_KEY] = entryId;
    return copy;
  }

  function currentEntryId() {
    return window.history.state?.[ENTRY_ID_KEY] || null;
  }

  let entryId = currentEntryId() || newEntryId();
  if (!currentEntryId()) {
    originalReplaceState(stateWithEntryId(window.history.state, entryId), '', window.location.href);
  }

  // Page scripts use replaceState for tabs and filters. Keep this entry's identity
  // when they do so, and give genuinely new history entries their own snapshot.
  window.history.replaceState = (state, title, url) => {
    originalReplaceState(stateWithEntryId(state, entryId), title, url);
  };
  window.history.pushState = (state, title, url) => {
    entryId = newEntryId();
    originalPushState(stateWithEntryId(state, entryId), title, url);
  };

  function snapshotKey() {
    return `${SNAPSHOT_PREFIX}${entryId}`;
  }

  function controlValue(control) {
    if (control instanceof HTMLInputElement) {
      const type = control.type.toLowerCase();
      if (['password', 'file', 'hidden'].includes(type)) return null;
      if (/password|token|secret|csrf/i.test(control.name || control.id)) return null;
      if (type === 'checkbox' || type === 'radio') {
        return { id: control.id, checked: control.checked };
      }
      return { id: control.id, value: control.value };
    }
    return { id: control.id, value: control.value };
  }

  function capturePageState() {
    const controls = [...document.querySelectorAll('input[id], select[id], textarea[id]')]
      .map(controlValue)
      .filter(Boolean);
    const activeControls = [...document.querySelectorAll(
      '[role="tab"][aria-selected="true"][id], [data-outlook-interval].active[id], '
      + '[data-virtual-chart-interval].active[id], .interval-btn.active[id]'
    )].map(control => control.id);
    const openDetails = [...document.querySelectorAll('details')]
      .map((details, index) => details.open ? index : null)
      .filter(index => index !== null);
    const elementScroll = [...document.querySelectorAll('[data-history-preserve-scroll][id]')]
      .map(element => ({ id: element.id, left: element.scrollLeft, top: element.scrollTop }));
    const customState = {};
    window.dispatchEvent(new CustomEvent('stockwatch:capture-page-state', {
      detail: customState
    }));

    try {
      window.sessionStorage.setItem(snapshotKey(), JSON.stringify({
        url: `${window.location.pathname}${window.location.search}${window.location.hash}`,
        scrollX: window.scrollX,
        scrollY: window.scrollY,
        controls,
        activeControls,
        openDetails,
        elementScroll,
        customState
      }));
    } catch (error) {
      // History navigation must continue even when storage is unavailable or full.
    }
  }

  function restoreControl(saved) {
    if (!saved?.id) return;
    const control = document.getElementById(saved.id);
    if (!control) return;
    if ('checked' in saved && control instanceof HTMLInputElement) {
      control.checked = Boolean(saved.checked);
    } else if ('value' in saved && 'value' in control) {
      control.value = saved.value;
    }
  }

  function restorePageState(event) {
    // A back-forward-cache restoration already contains the live DOM, chart ranges,
    // dialogs and fetched results exactly as the user left them.
    if (event.persisted) return;
    let snapshot;
    try {
      snapshot = JSON.parse(window.sessionStorage.getItem(snapshotKey()) || 'null');
    } catch (error) {
      return;
    }
    if (!snapshot) return;

    snapshot.controls?.forEach(restoreControl);
    document.querySelectorAll('details').forEach((details, index) => {
      details.open = snapshot.openDetails?.includes(index) || false;
    });
    snapshot.elementScroll?.forEach(saved => {
      const element = document.getElementById(saved.id);
      if (element) element.scrollTo(saved.left, saved.top);
    });

    window.stockWatchRestoredPageState = snapshot.customState || {};
    window.dispatchEvent(new CustomEvent('stockwatch:restore-page-state', {
      detail: snapshot.customState || {}
    }));

    // Run after page-owned load handlers have attached their listeners.
    window.setTimeout(() => {
      snapshot.activeControls?.forEach(id => {
        const control = document.getElementById(id);
        if (control && !control.matches('[aria-selected="true"], .active')) control.click();
      });
      window.scrollTo(snapshot.scrollX || 0, snapshot.scrollY || 0);
    }, 0);
  }

  function navigateBack(fallbackUrl) {
    capturePageState();
    let previousPageIsStockWatch = false;
    try {
      previousPageIsStockWatch = Boolean(document.referrer)
        && new URL(document.referrer).origin === window.location.origin;
    } catch (error) {
      previousPageIsStockWatch = false;
    }
    if (previousPageIsStockWatch && window.history.length > 1) {
      window.history.back();
      return;
    }
    window.location.assign(fallbackUrl);
  }

  window.requestStockWatchHistoryBack = navigateBack;

  document.addEventListener('click', event => {
    const button = event.target instanceof Element
      ? event.target.closest('[data-history-back]')
      : null;
    if (!button || event.defaultPrevented || event.button !== 0
        || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }

    event.preventDefault();
    navigateBack(button.href);
  });

  // pagehide runs for both normal navigation and bfcache navigation without
  // disqualifying the page from the bfcache as an unload handler would.
  window.addEventListener('pagehide', capturePageState);
  window.addEventListener('pageshow', restorePageState);
})();
