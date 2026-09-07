(() => {
    'use strict';
    const sidebar = document.getElementById('appSidebar');
    const menu = document.getElementById('appMenuToggle');
    if (!sidebar || !menu || sidebar.dataset.initialized) return;
    sidebar.dataset.initialized = 'true';
    const root = document.documentElement;
    const mobile = window.matchMedia('(max-width: 1100px)');
    const collapse = sidebar.querySelector('.app-sidebar-collapse');
    const close = sidebar.querySelector('.app-sidebar-close');
    const links = [...sidebar.querySelectorAll('[data-nav-section]')];
    const storageKey = 'stockwatch-sidebar-collapsed';
    let collapsed = false;
    try { collapsed = localStorage.getItem(storageKey) === 'true'; } catch (_) { /* Storage is optional. */ }

    function updateCollapse() {
        root.dataset.sidebarCollapsed = String(collapsed);
        collapse.setAttribute('aria-expanded', String(!collapsed));
        collapse.setAttribute('aria-label', collapsed ? 'Expand sidebar' : 'Collapse sidebar');
        collapse.title = collapsed ? 'Expand sidebar' : 'Collapse sidebar';
        // Existing charts listen for resize; dispatch after the new content width is laid out.
        requestAnimationFrame(() => window.dispatchEvent(new Event('resize')));
    }

    function closeDrawer() {
        if (mobile.matches && sidebar.open) sidebar.close();
        menu.setAttribute('aria-expanded', 'false');
        root.classList.remove('app-drawer-open');
    }

    function updateViewport() {
        const focusedInside = sidebar.contains(document.activeElement);
        const focusedMenu = document.activeElement === menu;
        sidebar.close();
        root.classList.remove('app-drawer-open');
        menu.setAttribute('aria-expanded', 'false');
        if (mobile.matches) {
            sidebar.removeAttribute('role');
            if (focusedInside) menu.focus();
        } else {
            sidebar.setAttribute('role', 'complementary');
            sidebar.setAttribute('open', '');
            if (focusedInside || focusedMenu) (links.find(link => link.hasAttribute('aria-current')) || links[0]).focus();
        }
    }

    function updateActiveSection() {
        const home = new URL(links.find(link => link.dataset.navSection === 'home').href);
        const section = window.location.pathname === home.pathname
            ? (window.location.hash === '#ticker-alerts' ? 'alerts' : 'home')
            : sidebar.dataset.activeSection;
        links.forEach(link => {
            if (link.dataset.navSection === section) link.setAttribute('aria-current', 'page');
            else link.removeAttribute('aria-current');
        });
    }

    collapse.addEventListener('click', () => {
        collapsed = !collapsed;
        try { localStorage.setItem(storageKey, String(collapsed)); } catch (_) { /* Storage is optional. */ }
        updateCollapse();
    });
    menu.addEventListener('click', () => {
        if (!mobile.matches) return;
        sidebar.showModal();
        root.classList.add('app-drawer-open');
        menu.setAttribute('aria-expanded', 'true');
        (links.find(link => link.hasAttribute('aria-current')) || links[0]).focus();
    });
    close.addEventListener('click', closeDrawer);
    sidebar.addEventListener('cancel', () => {
        root.classList.remove('app-drawer-open');
        menu.setAttribute('aria-expanded', 'false');
    });
    sidebar.addEventListener('click', event => {
        if (event.target !== sidebar || !mobile.matches) return;
        const bounds = sidebar.getBoundingClientRect();
        if (event.clientX < bounds.left || event.clientX > bounds.right
                || event.clientY < bounds.top || event.clientY > bounds.bottom) closeDrawer();
    });
    // Close before the stock page's capture-phase unsaved-changes guard runs.
    // Navigation remains a normal link, preserving that guard and modifier clicks.
    document.addEventListener('click', event => {
        if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        if (event.target instanceof Element && sidebar.contains(event.target)
                && event.target.closest('a[href]')) closeDrawer();
    }, true);
    window.addEventListener('hashchange', updateActiveSection);
    window.addEventListener('stockwatch:dashboard-view', updateActiveSection);
    window.addEventListener('pageshow', () => { closeDrawer(); updateActiveSection(); });
    mobile.addEventListener('change', updateViewport);
    root.classList.add('app-navigation-ready');
    updateCollapse();
    updateActiveSection();
    updateViewport();
})();
