/* Alert delivery settings: while a Telegram link waits for Start, ask every 4 seconds whether the bot has seen it,
   then open the channel so the owner can confirm it. Stops when the tab is hidden and after 15 minutes. Without
   JavaScript the "I pressed Start" link does the same by reloading the page. */
(function () {
  'use strict';
  // Copy buttons for a secret shown once; they appear only where the clipboard can be written.
  document.querySelectorAll('[data-copy]').forEach(function (button) {
    var source = button.parentElement && button.parentElement.querySelector('[data-copy-source]');
    if (!source || !navigator.clipboard) return;
    button.hidden = false;
    button.addEventListener('click', function () {
      navigator.clipboard.writeText(source.textContent.trim()).then(function () {
        button.textContent = window.StockWatchI18n ? window.StockWatchI18n.t('delivery.copied') : button.textContent;
      });
    });
  });
})();

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

/* This device's push notifications. The permission prompt only ever follows the button click. */
(function () {
  'use strict';
  var card = document.querySelector('[data-push-card]');
  if (!card) return;
  var key = card.getAttribute('data-vapid-key');
  var message = card.querySelector('[data-push-message]');
  var hide = card.querySelector('[data-push-hide]');
  var i18n = window.StockWatchI18n;
  var t = function (name) { return i18n ? i18n.t(name) : ''; };
  var ios = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  var standalone = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
  var subscription = null;

  function state(name) {
    card.querySelectorAll('[data-push-state]').forEach(function (part) { part.hidden = part.getAttribute('data-push-state') !== name; });
  }
  function say(text) { if (message) message.textContent = text || ''; }
  function post(url, body) { return window.StockWatchPush.post(url, body).then(function (r) { return r.ok ? r.json() : Promise.reject(r); }); }
  function keyBytes() {
    var padded = (key + '===='.slice(key.length % 4 || 4)).replace(/-/g, '+').replace(/_/g, '/');
    var raw = window.atob(padded); var out = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  }
  function register(enable) {
    var json = subscription.toJSON();
    return post('/api/push/subscriptions', { endpoint: json.endpoint, keys: json.keys, enable: enable }).then(function (body) {
      if (hide) hide.checked = !!body.hideDetails;
      state('on');
    });
  }

  if (!('serviceWorker' in navigator) || !('PushManager' in window) || !window.Notification || !key || !window.StockWatchPush) {
    state(ios && !standalone ? 'ios' : 'unsupported');
    return;
  }
  if (Notification.permission === 'denied') { state('denied'); return; }
  state('off');
  // A worker that never becomes ready (blocked by policy or an extension) must not leave the card waiting.
  function ready() {
    return Promise.race([navigator.serviceWorker.ready, new Promise(function (resolve, reject) {
      window.setTimeout(function () { reject(new Error('timeout')); }, 5000);
    })]);
  }
  ready().then(function (registration) { return registration.pushManager.getSubscription(); })
    .then(function (current) {
      subscription = current;
      if (current && Notification.permission === 'granted') return register(false);
      state('off');
    }).catch(function () { state('unsupported'); });

  card.querySelector('[data-push-enable]').addEventListener('click', function () {
    say('');
    Notification.requestPermission().then(function (permission) {
      if (permission !== 'granted') { state(permission === 'denied' ? 'denied' : 'off'); return null; }
      return ready().then(function (registration) {
        return registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes() });
      }).then(function (created) { subscription = created; return register(true); })
        .then(function () { say(t('delivery.push.enabled')); });
    }).catch(function () { say(t('delivery.push.failed')); });
  });
  card.querySelector('[data-push-test]').addEventListener('click', function () {
    if (!subscription) return;
    post('/api/push/test', { endpoint: subscription.endpoint }).then(function (body) {
      say(t(body.outcome === 'SENT' ? 'delivery.push.testSent' : 'delivery.push.testFailed'));
    }).catch(function () { say(t('delivery.push.testFailed')); });
  });
  card.querySelector('[data-push-disable]').addEventListener('click', function () {
    if (!subscription) return;
    var endpoint = subscription.endpoint;
    post('/api/push/subscriptions/remove', { endpoint: endpoint }).catch(function () { return null; })
      .then(function () { return subscription.unsubscribe(); })
      .then(function () { subscription = null; state('off'); say(t('delivery.push.disabled')); });
  });
  if (hide) hide.addEventListener('change', function () {
    if (!subscription) return;
    post('/api/push/subscriptions/details', { endpoint: subscription.endpoint, hideDetails: hide.checked })
      .catch(function () { hide.checked = !hide.checked; });
  });
})();
