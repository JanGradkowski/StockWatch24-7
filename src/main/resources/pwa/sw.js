/* StockWatch 24/7 service worker. It caches nothing about the account: pages hold financial data and a device may be
   shared. It keeps only the offline page and the icons, and shows the offline page when a page cannot load at all.
   Every other request goes to the network untouched. */
'use strict';

const CACHE = 'stockwatch-shell-v1';
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
