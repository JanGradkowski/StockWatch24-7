/* Kill switch (pwa.enabled=false): this worker replaces any installed one, deletes every cache it made and
   unregisters itself, so a faulty worker can be withdrawn from every device on its next visit. */
'use strict';

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.map((key) => caches.delete(key))))
    .then(() => self.registration.unregister()));
});
