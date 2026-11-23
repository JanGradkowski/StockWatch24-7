/* StockWatch 24/7 service worker. It caches nothing about the account: pages hold financial data and a device may be
   shared. It keeps only the offline page and the icons, and shows the offline page when a page cannot load at all.
   Every other request goes to the network untouched. */
'use strict';

const CACHE = 'stockwatch-shell-v3';
const SHELL = ['/offline', '/icons/icon-192.png', '/icons/icon.svg'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
    .then(() => self.clients.claim()));
});

// Navigations go to the network; only a network failure falls back to the offline page. Nothing else is touched.
self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.mode !== 'navigate' || request.method !== 'GET') return;
  event.respondWith(fetch(request).catch(() => caches.match('/offline').then((page) => page || Response.error())));
});

// A push carries an encrypted title, line, link and tag; the tag makes a repeated delivery replace itself.
self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch (ignored) { data = {}; }
  event.waitUntil(self.registration.showNotification(typeof data.title === 'string' && data.title ? data.title : 'StockWatch 24/7', {
    body: typeof data.body === 'string' ? data.body : '',
    tag: typeof data.tag === 'string' ? data.tag : undefined,
    lang: typeof data.lang === 'string' ? data.lang : undefined,
    silent: data.silent === true,
    icon: '/icons/icon-192.png',
    data: { url: sameOrigin(data.url) },
  }));
});

// Only addresses on this site are opened; anything else opens the home page.
function sameOrigin(url) {
  try {
    const target = new URL(url, self.location.origin);
    return target.origin === self.location.origin ? target.href : self.location.origin + '/home';
  } catch (ignored) {
    return self.location.origin + '/home';
  }
}

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = sameOrigin(event.notification.data && event.notification.data.url);
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
    for (const client of windows) {
      if (client.url === target && 'focus' in client) return client.focus();
    }
    return self.clients.openWindow(target);
  }));
});

// The browser replaced the subscription: subscribe again with the same key. The next page load sends the new one to
// the server, because only a page has the session's CSRF token.
self.addEventListener('pushsubscriptionchange', (event) => {
  const options = event.oldSubscription ? event.oldSubscription.options : null;
  if (!options) return;
  event.waitUntil(self.registration.pushManager.subscribe(options).catch(() => {}));
});
