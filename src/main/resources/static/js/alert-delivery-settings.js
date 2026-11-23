/* Alert delivery settings: while a Telegram link waits for Start, ask every 4 seconds whether the bot has seen it,
   then open the channel so the owner can confirm it. Stops when the tab is hidden and after 15 minutes. Without
   JavaScript the "I pressed Start" link does the same by reloading the page. */
(function () {
  'use strict';
  var box = document.querySelector('[data-telegram-link]');
  if (!box) return;
  var url = box.getAttribute('data-status-url');
  var status = box.querySelector('[data-telegram-status]');
  var deadline = Date.now() + 15 * 60 * 1000;
  var timer = null;
  var t = function (key, fallback) {
    try { return window.StockWatchI18n ? window.StockWatchI18n.t(key) : fallback; } catch (ignored) { return fallback; }
  };

  function check() {
    timer = null;
    if (Date.now() > deadline) {
      if (status) status.textContent = t('delivery.telegram.expired', 'This link has expired. Create a new one.');
      return;
    }
    if (document.hidden) return;
    fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (body) {
        if (!body) return schedule();
        if (body.state === 'READY' && body.channel) {
          window.location.assign('/settings/alert-delivery#channel-' + encodeURIComponent(body.channel));
          window.location.reload();
          return;
        }
        if (body.state === 'EXPIRED') {
          if (status) status.textContent = t('delivery.telegram.expired', 'This link has expired. Create a new one.');
          return;
        }
        schedule();
      })
      .catch(schedule);
  }

  function schedule() {
    if (timer === null) timer = window.setTimeout(check, 4000);
  }

  document.addEventListener('visibilitychange', function () { if (!document.hidden) schedule(); });
  schedule();
})();
