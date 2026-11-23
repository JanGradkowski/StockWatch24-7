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
})();
