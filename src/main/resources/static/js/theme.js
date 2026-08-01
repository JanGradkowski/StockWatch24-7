(function () {
  'use strict';

  const STORAGE_KEY = 'stockwatch-theme';
  const root = document.documentElement;

  function storedTheme() {
    const accountTheme = root.dataset.accountTheme;
    if (accountTheme === 'light' || accountTheme === 'dark') return accountTheme;
    try {
      return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark';
    } catch (ignored) {
      return 'dark';
    }
  }

  function createSvgIcon(name) {
    const namespace = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(namespace, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.classList.add('theme-toggle-icon', `theme-toggle-${name}`);

    if (name === 'sun') {
      const circle = document.createElementNS(namespace, 'circle');
      circle.setAttribute('cx', '12');
      circle.setAttribute('cy', '12');
      circle.setAttribute('r', '4');
      svg.append(circle);
      [
        ['12', '2', '12', '4'],
        ['12', '20', '12', '22'],
        ['4.93', '4.93', '6.34', '6.34'],
        ['17.66', '17.66', '19.07', '19.07'],
        ['2', '12', '4', '12'],
        ['20', '12', '22', '12'],
        ['4.93', '19.07', '6.34', '17.66'],
        ['17.66', '6.34', '19.07', '4.93']
      ].forEach(coordinates => {
        const line = document.createElementNS(namespace, 'line');
        ['x1', 'y1', 'x2', 'y2'].forEach((attribute, index) =>
          line.setAttribute(attribute, coordinates[index]));
        svg.append(line);
      });
      return svg;
    }

    const path = document.createElementNS(namespace, 'path');
    path.setAttribute(
      'd',
      'M20.6 15.2A8.7 8.7 0 0 1 8.8 3.4 8.8 8.8 0 1 0 20.6 15.2Z');
    svg.append(path);
    return svg;
  }

  function updateButton(theme) {
    const button = document.getElementById('themeToggle');
    if (!button) return;
    const switchToLight = theme === 'dark';
    button.setAttribute(
      'aria-label',
      switchToLight ? 'Switch to light mode' : 'Switch to dark mode');
    button.setAttribute(
      'title',
      switchToLight ? 'Switch to light mode' : 'Switch to dark mode');
    button.setAttribute('aria-pressed', String(theme === 'light'));
    button.querySelector('.theme-toggle-sun').toggleAttribute('hidden', !switchToLight);
    button.querySelector('.theme-toggle-moon').toggleAttribute('hidden', switchToLight);
  }

  function applyTheme(theme, persist, notify) {
    const normalized = theme === 'light' ? 'light' : 'dark';
    root.dataset.theme = normalized;
    if (persist) {
      try {
        localStorage.setItem(STORAGE_KEY, normalized);
      } catch (ignored) {
        // A private browsing policy may disable storage; the current page still updates.
      }
    }
    updateButton(normalized);
    if (notify) {
      window.dispatchEvent(new CustomEvent('stockwatch:themechange', {
        detail: { theme: normalized }
      }));
    }
  }

  function mountToggle() {
    if (document.getElementById('themeToggle')) return;
    const button = document.createElement('button');
    button.id = 'themeToggle';
    button.type = 'button';
    button.className = 'theme-toggle';
    button.append(createSvgIcon('sun'), createSvgIcon('moon'));
    button.addEventListener('click', () => {
      const nextTheme = root.dataset.theme === 'light' ? 'dark' : 'light';
      applyTheme(nextTheme, true, true);
      persistAccountTheme(nextTheme);
    });
    const navbar = document.querySelector('.navbar');
    if (navbar) {
      button.classList.add('theme-toggle-in-navbar');
      navbar.append(button);
    } else {
      document.body.append(button);
    }
    updateButton(root.dataset.theme);
  }

  function persistAccountTheme(theme) {
    const sync = document.getElementById('accountThemeSync');
    if (!sync) return;
    const token = sync.dataset.csrfToken;
    const body = new URLSearchParams({ theme });
    fetch(sync.dataset.themeEndpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
        'X-CSRF-TOKEN': token
      },
      body: body.toString(),
      credentials: 'same-origin'
    }).catch(() => {
      // The local choice remains active; the account preference can sync on the next change.
    });
  }

  applyTheme(storedTheme(), false, false);
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mountToggle, { once: true });
  } else {
    mountToggle();
  }
  window.addEventListener('storage', event => {
    if (event.key === STORAGE_KEY) {
      applyTheme(event.newValue, false, true);
    }
  });
}());
