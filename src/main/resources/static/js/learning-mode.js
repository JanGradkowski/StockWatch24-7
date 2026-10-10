(() => {
    'use strict';
    // Learning mode: explanations for options and technical terms, on hover, keyboard focus or tap.
    // Each marker names its explanation with data-term="id" (js.glossary.<id>.term and .text) or
    // data-learn="key" (js.learn.<key>, titled by data-learn-title). Markers are plain, inert markup
    // until learning mode is switched on:
    //   .learn-help     a "?" beside an option or heading
    //   .learn-term     a word or label that gets a dotted underline
    //   .learn-control  a button, tab or menu item that explains itself; its own click is untouched
    const root = document.documentElement;
    if (root.dataset.learningModeReady) return;
    root.dataset.learningModeReady = 'true';
    const i18n = window.StockWatchI18n;
    const TRIGGER = '.learn-help, .learn-term, .learn-control';
    const INTERACTIVE = 'a[href], button, summary, select, input, textarea, [role="button"], [role="tab"], [role="link"]';

    const popover = document.createElement('div');
    popover.id = 'learnPopover';
    popover.className = 'learn-popover';
    popover.setAttribute('role', 'tooltip');
    const heading = document.createElement('strong');
    const body = document.createElement('p');
    popover.append(heading, body);
    // The top layer keeps explanations above open dialogs; older browsers fall back to a fixed element.
    const topLayer = typeof popover.showPopover === 'function';
    if (topLayer) popover.setAttribute('popover', 'manual'); else popover.hidden = true;

    let anchor = null;      // the marker the popover points at
    let described = null;   // the element the popover describes (the marker, or a labelled field)
    let pinned = false;     // opened by a click or tap; stays until dismissed

    const enabled = () => root.dataset.learningMode === 'on';

    function explanation(trigger) {
        const term = trigger.dataset.term;
        if (term) {
            if (!i18n.has(`glossary.${term}.text`)) return null;
            return {title: i18n.t(`glossary.${term}.term`), text: i18n.t(`glossary.${term}.text`)};
        }
        const key = trigger.dataset.learn;
        if (!key || !i18n.has(`learn.${key}`)) return null;
        return {title: trigger.dataset.learnTitle || '', text: i18n.t(`learn.${key}`)};
    }

    // Passive markers keep their control's click: a control that explains itself, or a marker inside a
    // link or button. They explain themselves on hover and focus only.
    const passive = trigger => trigger.classList.contains('learn-control')
        || Boolean(trigger.parentElement?.closest(INTERACTIVE));
    // A "?" inside a <label> stays out of the keyboard order (as a button it would join the field's name);
    // the labelled field explains itself instead when it receives keyboard focus. Markers in hidden
    // decorative rows are for the pointer only.
    const pointerOnly = trigger => passive(trigger) || Boolean(trigger.closest('[aria-hidden="true"]'))
        || (trigger.classList.contains('learn-help') && Boolean(trigger.closest('label')));

    function prepare(trigger) {
        if (trigger.classList.contains('learn-control')) return;
        const info = explanation(trigger);
        if (trigger.classList.contains('learn-help')) {
            if (trigger.dataset.learnReady) return;
            trigger.dataset.learnReady = 'true';
            if (pointerOnly(trigger)) {
                if (trigger.closest('label')) trigger.setAttribute('aria-hidden', 'true');
                return;
            }
            // Hidden while learning mode is off, so it can keep its button semantics throughout.
            trigger.setAttribute('role', 'button');
            trigger.tabIndex = 0;
            trigger.setAttribute('aria-expanded', 'false');
            if (info?.title) trigger.setAttribute('aria-label', i18n.t('learning.explain', info.title));
            return;
        }
        // Underlined terms are ordinary text, and join the keyboard order only while learning mode is on.
        if (enabled() && info && !pointerOnly(trigger)) {
            trigger.tabIndex = 0;
            trigger.setAttribute('role', 'button');
            trigger.setAttribute('aria-expanded', String(trigger === anchor && pinned));
        } else {
            trigger.removeAttribute('tabindex');
            trigger.removeAttribute('role');
            trigger.removeAttribute('aria-expanded');
        }
    }

    function prepareWithin(node) {
        if (!(node instanceof Element)) return;
        if (node.matches(TRIGGER)) prepare(node);
        node.querySelectorAll(TRIGGER).forEach(prepare);
    }

    function place() {
        if (!anchor) return;
        if (!anchor.isConnected) { hide(); return; }
        const margin = 8, gap = 8;
        const target = anchor.getBoundingClientRect();
        const width = popover.offsetWidth, height = popover.offsetHeight;
        const left = Math.min(Math.max(margin, target.left + target.width / 2 - width / 2),
            Math.max(margin, window.innerWidth - width - margin));
        const above = target.top - gap - height;
        const top = above >= margin ? above : Math.min(target.bottom + gap, window.innerHeight - height - margin);
        popover.style.left = `${Math.round(left)}px`;
        popover.style.top = `${Math.round(Math.max(margin, top))}px`;
    }

    function describe(element) {
        const ids = (element.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean);
        if (!ids.includes(popover.id)) element.setAttribute('aria-describedby', [...ids, popover.id].join(' '));
        described = element;
    }

    function undescribe() {
        if (!described) return;
        const ids = (described.getAttribute('aria-describedby') || '').split(/\s+/).filter(id => id && id !== popover.id);
        if (ids.length) described.setAttribute('aria-describedby', ids.join(' '));
        else described.removeAttribute('aria-describedby');
        described = null;
    }

    function show(trigger, {pin = false, field = null} = {}) {
        const info = explanation(trigger);
        if (!info) return;
        if (anchor && anchor !== trigger) hide();
        heading.textContent = info.title;
        heading.hidden = !info.title;
        body.textContent = info.text;
        if (!popover.isConnected) document.body.append(popover);
        if (topLayer) {
            if (!popover.matches(':popover-open')) popover.showPopover();
        } else {
            popover.hidden = false;
        }
        anchor = trigger;
        pinned = pin;
        if (trigger.hasAttribute('aria-expanded') && !trigger.classList.contains('learn-control')) {
            trigger.setAttribute('aria-expanded', String(pin));
        }
        describe(field || trigger);
        place();
    }

    function hide() {
        if (!anchor) return;
        if (anchor.hasAttribute('aria-expanded') && !anchor.classList.contains('learn-control')) {
            anchor.setAttribute('aria-expanded', 'false');
        }
        undescribe();
        anchor = null;
        pinned = false;
        if (topLayer) {
            if (popover.matches(':popover-open')) popover.hidePopover();
        } else {
            popover.hidden = true;
        }
    }

    const triggerFrom = target => (target instanceof Element ? target.closest(TRIGGER) : null);

    // The "?" of a labelled field, for fields whose label holds one.
    function fieldHelp(control) {
        if (!(control instanceof HTMLElement) || !control.labels) return null;
        for (const label of control.labels) {
            const help = label.querySelector('.learn-help');
            if (help) return help;
        }
        return null;
    }

    function keyboardFocused(element) {
        try { return element.matches(':focus-visible'); } catch (_) { return true; }
    }

    function togglePinned(trigger) {
        if (anchor === trigger && pinned) hide();
        else show(trigger, {pin: true});
    }

    document.addEventListener('mouseover', event => {
        if (!enabled() || pinned) return;
        const trigger = triggerFrom(event.target);
        if (trigger && trigger !== anchor) show(trigger);
    });
    document.addEventListener('mouseout', event => {
        if (!anchor || pinned) return;
        if (triggerFrom(event.target) === anchor && !anchor.contains(event.relatedTarget)) hide();
    });
    document.addEventListener('focusin', event => {
        if (!enabled()) return;
        const trigger = triggerFrom(event.target);
        // A control group that explains itself does so when focus enters any of its controls.
        if (trigger && (trigger === event.target || trigger.classList.contains('learn-control'))) {
            if (trigger !== anchor) show(trigger, {field: event.target});
            return;
        }
        const help = fieldHelp(event.target);
        if (help && keyboardFocused(event.target)) show(help, {field: event.target});
    });
    document.addEventListener('focusout', event => {
        if (!anchor || (event.target !== anchor && event.target !== described)) return;
        // A pinned explanation closes when focus moves to another element, not when the window loses focus.
        if (!pinned || event.relatedTarget) hide();
    });
    document.addEventListener('click', event => {
        const trigger = enabled() ? triggerFrom(event.target) : null;
        if (trigger && !passive(trigger)) {
            // Inside a <label> the click would otherwise focus or toggle the field.
            event.preventDefault();
            togglePinned(trigger);
            return;
        }
        if (pinned) hide();
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && anchor) { hide(); return; }
        if (!enabled() || (event.key !== 'Enter' && event.key !== ' ')) return;
        const trigger = triggerFrom(event.target);
        if (!trigger || trigger !== event.target || passive(trigger)) return;
        event.preventDefault();
        togglePinned(trigger);
    });
    let frame = 0;
    const reposition = () => {
        if (!anchor || frame) return;
        frame = requestAnimationFrame(() => { frame = 0; place(); });
    };
    window.addEventListener('scroll', reposition, {capture: true, passive: true});
    window.addEventListener('resize', reposition, {passive: true});

    // Scripts render signals, charts and lists after load; their markers need the same treatment.
    new MutationObserver(records => {
        records.forEach(record => record.addedNodes.forEach(prepareWithin));
    }).observe(document.documentElement, {childList: true, subtree: true});

    function apply(on) {
        if (on) root.dataset.learningMode = 'on';
        else delete root.dataset.learningMode;
        if (!on) hide();
        prepareWithin(document.body);
        document.getElementById('learningModeToggle')?.setAttribute('aria-pressed', String(on));
        window.dispatchEvent(new CustomEvent('stockwatch:learningmodechange', {detail: {enabled: on}}));
    }

    function persist(toggle, on) {
        fetch(toggle.dataset.endpoint, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                'X-CSRF-TOKEN': toggle.dataset.csrfToken
            },
            body: new URLSearchParams({enabled: String(on)}).toString(),
            credentials: 'same-origin'
        }).catch(() => {
            // The page keeps the new state; the account preference saves on the next change.
        });
    }

    function mount() {
        prepareWithin(document.body);
        const toggle = document.getElementById('learningModeToggle');
        if (!toggle) return;
        toggle.setAttribute('aria-pressed', String(enabled()));
        toggle.addEventListener('click', () => {
            const on = !enabled();
            apply(on);
            persist(toggle, on);
        });
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount, {once: true});
    else mount();
})();
