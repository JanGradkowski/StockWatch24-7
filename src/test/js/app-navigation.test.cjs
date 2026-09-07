const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// Exercise our state and event wiring; modal focus trapping is provided by HTMLDialogElement.
function setup({mobile = false, stored = null, blockedStorage = false, section = 'home', path = '/home', hash = ''} = {}) {
    class Element extends EventTarget {
        constructor(dataset = {}) { super(); this.dataset = dataset; this.attributes = new Map(); }
        setAttribute(key, value) { this.attributes.set(key, String(value)); if (key === 'open') this.open = true; }
        removeAttribute(key) { this.attributes.delete(key); }
        hasAttribute(key) { return this.attributes.has(key); }
        getAttribute(key) { return this.attributes.get(key); }
        focus() { document.activeElement = this; }
        closest() { return this.href ? this : null; }
    }
    const document = new EventTarget();
    const root = new Element();
    const classes = new Set();
    root.classList = {add: key => classes.add(key), remove: key => classes.delete(key)};
    document.documentElement = root;
    document.activeElement = new Element();
    const menu = new Element(), collapse = new Element(), close = new Element();
    const sidebar = new Element({activeSection: section});
    sidebar.open = true;
    const links = ['home', 'signals', 'alerts', 'demo', 'settings', 'help'].map(name => {
        const link = new Element({navSection: name});
        link.href = 'https://test.local' + (name === 'home' ? '/home' : '/' + name);
        return link;
    });
    sidebar.querySelector = selector => selector.includes('collapse') ? collapse : close;
    sidebar.querySelectorAll = () => links;
    sidebar.contains = element => [sidebar, collapse, close, ...links].includes(element);
    sidebar.close = () => { sidebar.open = false; sidebar.modal = false; };
    sidebar.showModal = () => { sidebar.open = true; sidebar.modal = true; };
    sidebar.getBoundingClientRect = () => ({left: 0, right: 280, top: 0, bottom: 800});
    document.getElementById = id => id === 'appSidebar' ? sidebar : menu;
    const window = new EventTarget();
    window.location = {pathname: path, hash};
    const query = new EventTarget(); query.matches = mobile;
    window.matchMedia = () => query;
    let resizeCount = 0;
    window.addEventListener('resize', () => resizeCount++);
    const storage = new Map([['stockwatch-sidebar-collapsed', stored]]);
    const localStorage = {
        getItem: key => { if (blockedStorage) throw Error('blocked'); return storage.get(key); },
        setItem: (key, value) => { if (blockedStorage) throw Error('blocked'); storage.set(key, value); }
    };
    vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/app-navigation.js', 'utf8'),
        {document, window, localStorage, Element, URL, Event, requestAnimationFrame: fn => fn()});
    return {document, root, classes, sidebar, menu, collapse, close, links, window, query, storage,
        resizeCount: () => resizeCount,
        click: element => element.dispatchEvent(new Event('click'))};
}

test('desktop restores collapse preference without stealing focus and resizes charts on toggle', () => {
    const app = setup({stored: 'true'});
    assert.equal(app.root.dataset.sidebarCollapsed, 'true');
    assert.equal(app.sidebar.open, true);
    assert.equal(app.sidebar.contains(app.document.activeElement), false);
    const count = app.resizeCount();
    app.click(app.collapse);
    assert.equal(app.root.dataset.sidebarCollapsed, 'false');
    assert.equal(app.storage.get('stockwatch-sidebar-collapsed'), 'false');
    assert.equal(app.collapse.getAttribute('aria-expanded'), 'true');
    assert.equal(app.resizeCount(), count + 1);
});

test('mobile ignores desktop collapse, opens a modal and unlocks scrolling when closed or cancelled', () => {
    const app = setup({mobile: true, stored: 'true'});
    assert.equal(app.sidebar.open, false);
    app.click(app.menu);
    assert.equal(app.sidebar.modal, true);
    assert.equal(app.menu.getAttribute('aria-expanded'), 'true');
    assert.equal(app.document.activeElement, app.links[0]);
    assert.equal(app.classes.has('app-drawer-open'), true);
    app.click(app.close);
    assert.equal(app.sidebar.open, false);
    assert.equal(app.classes.has('app-drawer-open'), false);
    app.click(app.menu);
    app.sidebar.dispatchEvent(new Event('cancel'));
    assert.equal(app.classes.has('app-drawer-open'), false);
    assert.equal(app.menu.getAttribute('aria-expanded'), 'false');
});

test('switching viewport closes a modal and leaves desktop navigation available', () => {
    const app = setup({mobile: true});
    app.click(app.menu);
    app.query.matches = false;
    app.query.dispatchEvent(new Event('change'));
    assert.equal(app.sidebar.open, true);
    assert.equal(app.sidebar.modal, false);
    assert.equal(app.sidebar.getAttribute('role'), 'complementary');
    assert.equal(app.classes.has('app-drawer-open'), false);
});

test('dashboard view changes and browser hash navigation keep exactly one active link', () => {
    const app = setup({hash: '#ticker-alerts'});
    const current = () => app.links.filter(link => link.hasAttribute('aria-current'));
    assert.deepEqual(current(), [app.links[2]]);
    app.window.location.hash = '';
    app.window.dispatchEvent(new Event('stockwatch:dashboard-view'));
    assert.deepEqual(current(), [app.links[0]]);
    app.window.location.hash = '#ticker-alerts';
    app.window.dispatchEvent(new Event('hashchange'));
    assert.deepEqual(current(), [app.links[2]]);
});

test('private browsing and detail pages still work without storage', () => {
    const app = setup({blockedStorage: true, section: 'demo', path: '/virtual-trades/42'});
    app.click(app.collapse);
    assert.equal(app.root.dataset.sidebarCollapsed, 'true');
    assert.equal(app.links[3].getAttribute('aria-current'), 'page');
});

test('link capture closes the drawer without preventing the existing navigation guard', () => {
    const app = setup({mobile: true});
    app.click(app.menu);
    const event = new Event('click', {cancelable: true});
    Object.defineProperties(event, {target: {value: app.links[1]}, button: {value: 0}});
    app.document.dispatchEvent(event);
    assert.equal(app.sidebar.open, false);
    assert.equal(event.defaultPrevented, false);
});

test('dashboard switches from sidebar links and restored history without dashboard tab buttons', () => {
    const app = setup();
    const makeControl = dataset => Object.assign(new EventTarget(), {
        dataset, classList: {toggle() {}}, setAttribute() {}, focus() {}
    });
    const sections = ['technical', 'alerts'].map(view => makeControl({dashboardView: view}));
    app.document.querySelectorAll = selector => selector === '[data-dashboard-view]' ? sections : [];
    app.document.querySelector = () => null;
    app.document.getElementById = () => null;
    app.document.readyState = 'complete';
    app.window.location = new URL('https://test.local/home#latestTechnicalSignals');
    app.window.history = {state: {saved: true}, replaceState(state, title, url) {
        this.state = state;
        app.window.location = new URL(url);
    }};
    vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/dashboard.js', 'utf8'),
        {document: app.document, window: app.window, URL, Event});
    assert.equal(app.window.location.hash, '#latestTechnicalSignals');
    assert.equal(sections[0].hidden, false);
    app.window.location.hash = '#ticker-alerts';
    app.window.dispatchEvent(new Event('hashchange'));
    assert.equal(sections[1].hidden, false);
    assert.equal(sections[0].hidden, true);
    app.window.location.hash = '';
    app.window.dispatchEvent(new Event('hashchange'));
    assert.equal(app.window.location.hash, '');
    assert.equal(sections[0].hidden, false);
    assert.equal(app.links[0].getAttribute('aria-current'), 'page');
    assert.deepEqual(app.window.history.state, {saved: true});
    app.window.location.hash = '#ticker-alerts';
    app.window.dispatchEvent(new Event('pageshow'));
    assert.equal(sections[1].hidden, false);
    assert.equal(sections[0].hidden, true);
    assert.equal(app.links[2].getAttribute('aria-current'), 'page');
});
