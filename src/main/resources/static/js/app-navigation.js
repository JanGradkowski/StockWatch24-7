(() => {
    'use strict';
    const sidebar = document.getElementById('appSidebar');
    const menu = document.getElementById('appMenuToggle');
    if (!sidebar || !menu || sidebar.dataset.initialized) return;
    sidebar.dataset.initialized = 'true';
    const root = document.documentElement;
    const mobile = window.matchMedia('(max-width: 1024px)');
    // Between the drawer and full desktop widths the sidebar starts as an icon rail,
    // so page content keeps its width, unless the user has chosen a state.
    const compact = window.matchMedia('(max-width: 1279px)');
    const collapse = sidebar.querySelector('.app-sidebar-collapse');
    const close = sidebar.querySelector('.app-sidebar-close');
    const links = [...sidebar.querySelectorAll('[data-nav-section]')];
    const storageKey = 'stockwatch-sidebar-collapsed';
    let storedPreference = null;
    try { storedPreference = localStorage.getItem(storageKey); } catch (_) { /* Storage is optional. */ }
    const hasStoredPreference = () => storedPreference === 'true' || storedPreference === 'false';
    let collapsed = hasStoredPreference() ? storedPreference === 'true' : compact.matches;

    function updateCollapse() {
        root.dataset.sidebarCollapsed = String(collapsed);
        collapse.setAttribute('aria-expanded', String(!collapsed));
        const collapseLabel = window.StockWatchI18n.t(collapsed ? 'sidebar.expand' : 'sidebar.collapse');
        collapse.setAttribute('aria-label', collapseLabel);
        collapse.title = collapseLabel;
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
        storedPreference = String(collapsed);
        try { localStorage.setItem(storageKey, storedPreference); } catch (_) { /* Storage is optional. */ }
        updateCollapse();
    });
    compact.addEventListener('change', () => {
        if (hasStoredPreference() || compact.matches === collapsed) return;
        collapsed = compact.matches;
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
    // On wide screens the navigation is one row. Longer translations may not fit it: the tabs then move closer
    // together; if that is not enough, settings, help and learning mode show only their icons, and as a last resort
    // the tabs take a second row rather than running off the screen.
    function fitRow() {
        sidebar.classList.remove('nav-fit-icons', 'nav-fit-tight', 'nav-fit-compact', 'nav-fit-wrap');
        if (mobile.matches) return;
        const overflows = () => sidebar.scrollWidth > sidebar.clientWidth + 1;
        if (!overflows()) return;
        sidebar.classList.add('nav-fit-tight');
        if (!overflows()) return;
        sidebar.classList.add('nav-fit-icons');
        if (!overflows()) return;
        sidebar.classList.add('nav-fit-compact');
        if (overflows()) sidebar.classList.add('nav-fit-wrap');
    }
    let fitQueued = false;
    window.addEventListener('resize', () => {
        if (fitQueued) return;
        fitQueued = true;
        requestAnimationFrame(() => { fitQueued = false; fitRow(); });
    });
    // The skip link's target: the page's own main element, which takes focus so the next Tab continues from there.
    const skip = document.querySelector('.skip-link');
    const main = document.querySelector('main');
    if (skip && main) {
        if (!main.id) main.id = 'main-content';
        skip.setAttribute('href', `#${main.id}`);
        skip.addEventListener('click', () => {
            if (!main.hasAttribute('tabindex')) main.setAttribute('tabindex', '-1');
            main.focus();
        });
    }
    mobile.addEventListener('change', updateViewport);
    mobile.addEventListener('change', fitRow);
    root.classList.add('app-navigation-ready');
    updateCollapse();
    updateActiveSection();
    updateViewport();
    fitRow();
    document.fonts?.ready.then(fitRow);
})();
