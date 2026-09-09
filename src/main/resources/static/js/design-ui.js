(() => {
    'use strict';
    function labelRows() {
        const families = [
            ['.alert-list-head.company-alert-grid', '.alert-row.company-alert-grid'],
            ['.latest-signal-head', '.latest-signal-row'],
            ['.signal-archive-head', '.signal-archive-row'],
            ['.activity-signal-archive-head', '.activity-signal-archive-row'],
            ['.virtual-trade-archive-head', '.virtual-trade-archive-row']
        ];
        for (const [header, rows] of families) {
            const labels = Array.from(document.querySelector(header)?.children || [], cell => cell.textContent.trim());
            document.querySelectorAll(rows).forEach(row => Array.from(row.children).forEach((cell, index) => {
                if (labels[index] && cell.tagName !== 'BUTTON') cell.dataset.mobileLabel = labels[index];
            }));
        }
        if (document.querySelector('.archive-column-sort')) {
            document.querySelectorAll('.signal-archive-sort-form select[name="sort"], .signal-archive-sort-form select[name="direction"]')
                .forEach(select => select.closest('label')?.classList.add('archive-desktop-sort'));
            document.querySelectorAll('.signal-archive-sort-form').forEach(form => {
                if (form.querySelector('.archive-desktop-sort') && !form.querySelector('select:not([name="sort"]):not([name="direction"]), input:not([type="hidden"])')) form.classList.add('archive-sort-only');
            });
        }
    }
    function settingsChanges() {
        document.querySelectorAll('form').forEach(form => {
            const path = new URL(form.action, location.href).pathname;
            if (!/^\/settings\/(appearance|analysis-alerts|detection|scoring|candlestick-patterns|elliott-waves|harmonic-formations)$/.test(path)) return;
            const fields = Array.from(form.elements).filter(field => field.name && !['hidden', 'submit', 'button'].includes(field.type));
            const original = fields.map(field => ({field, value: field.value, checked: field.checked}));
            const submitter = Array.from(form.elements).find(field => field.type === 'submit' && field.form === form);
            if (!submitter || !fields.length) return;
            const bar = document.createElement('div'); bar.className = 'design-save-state'; bar.hidden = true;
            const message = document.createElement('span'); message.textContent = 'Unsaved changes'; message.setAttribute('role', 'status');
            const actions = document.createElement('div'); actions.className = 'design-save-actions';
            const discard = document.createElement('button'); discard.type = 'button'; discard.className = 'btn-secondary'; discard.textContent = 'Discard';
            const save = document.createElement('button'); save.type = 'button'; save.className = 'btn-accent'; save.textContent = 'Apply changes';
            actions.append(discard, save); bar.append(message, actions); form.append(bar);
            const changed = entry => ['checkbox', 'radio'].includes(entry.field.type)
                ? entry.field.checked !== entry.checked : entry.field.value !== entry.value;
            function update() {
                original.forEach(entry => entry.field.classList.toggle('design-field-changed', changed(entry)));
                bar.hidden = !original.some(changed);
                form.classList.toggle('design-has-changes', !bar.hidden);
                save.disabled = submitter.disabled;
            }
            form.addEventListener('input', update); form.addEventListener('change', update);
            new MutationObserver(update).observe(submitter, {attributes: true, attributeFilter: ['disabled']});
            discard.addEventListener('click', () => {
                original.forEach(({field, value, checked}) => { field.value = value; if (checked !== undefined) field.checked = checked; });
                original.forEach(({field}) => field.dispatchEvent(new Event('input', {bubbles: true})));
                form.dispatchEvent(new Event('change', {bubbles: true}));
                update(); submitter.focus({preventScroll: true});
            });
            save.addEventListener('click', () => form.requestSubmit(submitter));
        });
    }
    function appearancePreview() {
        const form = document.querySelector('.appearance-settings-form');
        if (!form) return;
        const colors = Array.from(form.querySelectorAll('input[type="color"]'));
        if (!colors.length) return;
        const host = document.createElement('div'); host.className = 'appearance-chart-previews';
        host.setAttribute('aria-label', 'Chart colour previews');
        const namespace = 'http://www.w3.org/2000/svg';
        const element = (tag, attrs) => {
            const item = document.createElementNS(namespace, tag);
            Object.entries(attrs).forEach(([key, value]) => item.setAttribute(key, String(value))); return item;
        };
        for (const theme of ['dark', 'light']) {
            const card = document.createElement('div'); card.className = 'appearance-chart-preview'; card.dataset.previewTheme = theme;
            const label = document.createElement('strong'); label.textContent = `${theme === 'dark' ? 'Dark' : 'Light'} chart preview`;
            const svg = element('svg', {viewBox: '0 0 360 170', role: 'img', 'aria-label': 'Sample candles with your chosen overlay colours'});
            for (let i = 0; i < 14; i++) {
                const x = 18 + i * 25, y = 120 - i * 5 + Math.sin(i * 1.3) * 17;
                const color = i % 3 === 0 ? (theme === 'dark' ? '#ff7890' : '#bb2947') : (theme === 'dark' ? '#64e8bd' : '#08795d');
                svg.append(element('line', {x1: x, x2: x, y1: y - 17, y2: y + 17, stroke: color}), element('rect', {x: x - 4, y: y - 7, width: 8, height: 14, fill: color, rx: 1}));
            }
            colors.forEach((input, index) => {
                const paths = ['10,142 65,104 112,127 175,54 225,80 280,24', '175,54 225,90 270,70 340,107', '30,144 55,119 80,138 105,110', '10,135 100,50 165,102 225,30 340,105'];
                const line = element('polyline', {points: paths[index % paths.length], stroke: input.value, fill: 'none', 'stroke-width': 2.5, 'stroke-linejoin': 'round'});
                const title = element('title', {}); title.textContent = input.closest('label')?.querySelector('strong')?.textContent || 'Overlay';
                line.append(title); svg.append(line);
                input.addEventListener('input', () => line.setAttribute('stroke', input.value));
            });
            card.append(label, svg); host.append(card);
        }
        form.querySelector('button[type="submit"]')?.before(host);
    }
    function fieldErrors() {
        const errors = new Map();
        let sequence = 0;
        document.addEventListener('invalid', event => {
            const field = event.target;
            if (!field.matches('input, select, textarea') || field.type === 'hidden') return;
            field.setAttribute('aria-invalid', 'true');
            const existing = (field.getAttribute('aria-describedby') || '').split(/\s+/)
                .map(id => document.getElementById(id)).find(node => node?.getAttribute('role') === 'alert' && node.textContent.trim());
            if (existing) return;
            let message = errors.get(field);
            if (!message) {
                message = document.createElement('small'); message.className = 'design-field-error';
                message.id = `design-field-error-${++sequence}`; message.setAttribute('role', 'alert');
                field.after(message); errors.set(field, message);
                field.setAttribute('aria-describedby', `${field.getAttribute('aria-describedby') || ''} ${message.id}`.trim());
            }
            message.textContent = field.validationMessage;
            field.setAttribute('aria-invalid', 'true');
        }, true);
        document.addEventListener('input', event => {
            const field = event.target, message = errors.get(field);
            if (field.validity?.valid) field.removeAttribute('aria-invalid');
            if (!message || !field.validity.valid) return;
            field.removeAttribute('aria-invalid');
            const descriptions = (field.getAttribute('aria-describedby') || '').split(/\s+/).filter(id => id !== message.id);
            if (descriptions.length) field.setAttribute('aria-describedby', descriptions.join(' ')); else field.removeAttribute('aria-describedby');
            message.remove(); errors.delete(field);
        });
    }
    labelRows(); settingsChanges(); appearancePreview(); fieldErrors();
})();
