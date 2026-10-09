(() => {
  'use strict';
  // Opening a page is a GET and must not change data, so the page marks itself read with a
  // CSRF-protected POST once it has been displayed. Failures are silent: the item stays unread.
  const page = document.querySelector('[data-mark-read-endpoint]');
  const endpoint = page?.dataset.markReadEndpoint;
  if (!endpoint || !endpoint.startsWith('/api/')) return;
  const token = document.querySelector('meta[name="_csrf"]')?.content
    || document.getElementById('accountThemeSync')?.dataset.csrfToken;
  if (!token) return;
  const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
  fetch(endpoint, { method: 'POST', credentials: 'same-origin', headers: { [header]: token } })
    .catch(() => {});
})();
