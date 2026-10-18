(() => {
  'use strict';
  // The rule above a custom alert's triggers in All signals, and its pause switch.
  const box = document.querySelector('[data-ca-archive]');
  if (!box) return;
  const text = window.StockWatchCustomAlerts;
  const alert = {
    definition: JSON.parse(box.dataset.definition || '{}'), interval: box.dataset.interval, triggerMode: box.dataset.triggerMode
  };
  text.catalog().catch(() => ({ byType: new Map() })).then(catalog => {
    box.querySelector('[data-ca-archive-summary]').textContent = text.summary(alert, catalog.byType);
    box.querySelector('[data-ca-archive-chips]').replaceChildren(text.chips(alert.definition, catalog.byType, 12));
  });
  const toggle = box.querySelector('[data-ca-archive-toggle]');
  toggle?.addEventListener('click', async () => {
    toggle.disabled = true;
    try {
      await window.StockWatchLists.api(`/api/custom-alerts/${box.dataset.caArchive}/active?value=${box.dataset.active !== 'true'}`, { method: 'POST' });
      window.location.reload();
    } catch (error) {
      box.querySelector('[data-ca-archive-status]').textContent = error.message;
      toggle.disabled = false;
    }
  });
})();
