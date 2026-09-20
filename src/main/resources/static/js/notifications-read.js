(() => {
  'use strict';
  document.addEventListener('click', async event => {
    const button = event.target.closest?.('[data-notifications-read-all]');
    if (!button || button.disabled) return;
    const status = button.parentElement.querySelector('[data-notifications-read-status]');
    const label = button.textContent;
    button.disabled = true;
    button.setAttribute('aria-busy', 'true');
    button.textContent = 'Marking as read...';
    if (status) status.textContent = '';
    try {
      const token = document.querySelector('meta[name="_csrf"]')?.content || document.getElementById('accountThemeSync')?.dataset.csrfToken;
      if (!token) throw new Error('Please refresh the page and try again.');
      const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
      const query = new URLSearchParams({ scope: button.dataset.readScope || 'ALL' });
      if (button.dataset.readWatchlist) query.set('watchlistId', button.dataset.readWatchlist);
      const response = await fetch(`/api/notifications/read-all?${query}`, {
        method: 'POST', credentials: 'same-origin', headers: { [header]: token }
      });
      if (response.redirected) throw new Error('Your session expired. Please sign in again.');
      if (!response.ok) throw new Error('Notifications could not be marked as read. Please try again.');
      // Reload the same URL so counts, shared notifications and active archive filters all agree.
      window.location.reload();
    } catch (error) {
      if (status) status.textContent = error.message;
      button.disabled = false;
      button.removeAttribute('aria-busy');
      button.textContent = label;
    }
  });
})();
