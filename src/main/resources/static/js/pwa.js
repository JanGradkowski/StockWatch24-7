/* Registers the service worker that makes StockWatch installable, and offers Install where the browser allows it:
   the browser's own beforeinstallprompt event reveals any [data-pwa-install] button, and [data-pwa-installed]
   notes show once the site runs as an installed app. */
(function () {
  'use strict';
  if (!('serviceWorker' in navigator)) return;
  window.addEventListener('load', function () {
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(function () { /* the site works without it */ });
  });

  var deferred = null;
  var standalone = window.matchMedia && window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

  function show() {
    document.querySelectorAll('[data-pwa-installed]').forEach(function (note) { note.hidden = !standalone; });
    document.querySelectorAll('[data-pwa-install]').forEach(function (button) { button.hidden = standalone || !deferred; });
    document.querySelectorAll('[data-pwa-manual]').forEach(function (note) { note.hidden = standalone || !!deferred; });
  }

  window.addEventListener('beforeinstallprompt', function (event) {
    event.preventDefault();
    deferred = event;
    show();
  });
  window.addEventListener('appinstalled', function () {
    deferred = null;
    standalone = true;
    show();
  });
  document.addEventListener('click', function (event) {
    var button = event.target.closest && event.target.closest('[data-pwa-install]');
    if (!button || !deferred) return;
    deferred.prompt();
    deferred.userChoice.finally(function () { deferred = null; show(); });
  });
  document.addEventListener('DOMContentLoaded', show);

  // Signed in with notifications on: send this browser's subscription again once a day, or at once when the
  // account's security version changed (the server then removed its push devices) or the subscription changed.
  function account() {
    var sync = document.getElementById('accountThemeSync');
    return sync && sync.getAttribute('data-push-revision') ? sync : null;
  }
  function post(url, body, keepalive) {
    var sync = account();
    return fetch(url, { method: 'POST', credentials: 'same-origin', keepalive: !!keepalive,
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': sync.getAttribute('data-csrf-token') },
      body: JSON.stringify(body) });
  }
  window.StockWatchPush = { post: post };
  document.addEventListener('DOMContentLoaded', function () {
    var sync = account();
    if (!sync || !('PushManager' in window) || !window.Notification || Notification.permission !== 'granted') return;
    navigator.serviceWorker.ready.then(function (registration) {
      return registration.pushManager.getSubscription();
    }).then(function (subscription) {
      if (!subscription) return;
      var json = subscription.toJSON();
      var mark = sync.getAttribute('data-push-revision') + '|' + json.endpoint;
      var last = {};
      try { last = JSON.parse(localStorage.getItem('stockwatch.push.sync') || '{}'); } catch (ignored) { last = {}; }
      if (last.mark === mark && Date.now() - (last.at || 0) < 86400000) return;
      return post('/api/push/subscriptions', { endpoint: json.endpoint, keys: json.keys }).then(function (response) {
        if (!response.ok) return;
        try { localStorage.setItem('stockwatch.push.sync', JSON.stringify({ mark: mark, at: Date.now() })); } catch (ignored) { }
      });
    }).catch(function () { /* push stays as it is */ });
  });

  // Signing out removes this device from the account and ends its subscription, so the next account to sign in
  // here has to turn notifications on itself.
  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form.classList || !form.classList.contains('logout-form') || form.dataset.pushChecked || !account()
        || !('PushManager' in window)) return;
    event.preventDefault();
    form.dataset.pushChecked = 'true';
    var submitted = false;
    var done = function () { if (submitted) return; submitted = true; form.submit(); };
    var timer = window.setTimeout(done, 1500);
    navigator.serviceWorker.ready.then(function (registration) {
      return registration.pushManager.getSubscription();
    }).then(function (subscription) {
      if (!subscription) return null;
      return post('/api/push/subscriptions/remove', { endpoint: subscription.endpoint }, true)
        .catch(function () { return null; })
        .then(function () { return subscription.unsubscribe(); });
    }).catch(function () { return null; }).then(function () { window.clearTimeout(timer); done(); });
  }, true);
})();
