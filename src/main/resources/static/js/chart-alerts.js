(() => {
    'use strict';

    // Alerts on chart drawings and indicators. The server checks them on completed candles and reports
    // each trigger in Latest signals (and by email when the user's email alerts allow it). This module
    // holds the dialogs, the list of a symbol's alerts, and keeps drawing alerts on the drawn shape.

    const i18n = window.StockWatchI18n;
    const LEVEL_CONDITIONS = ['CROSSES', 'CROSSES_UP', 'CROSSES_DOWN', 'ABOVE', 'BELOW'];
    const BAND_CONDITIONS = ['ENTERS', 'EXITS', 'INSIDE', 'OUTSIDE'];
    const BAND_TOOLS = new Set(['parallelChannel', 'rectangle']);
    const INTERVALS = ['1d', '1wk', '1mo'];
    const finite = value => typeof value === 'number' && Number.isFinite(value);

    let context = null;
    let alerts = [];
    let syncTimer = null;

    function element(tag, options = {}) {
        const node = document.createElement(tag);
        if (options.className) node.className = options.className;
        if (options.text !== undefined) node.textContent = options.text;
        Object.entries(options.attrs || {}).forEach(([name, value]) => {
            if (value !== undefined && value !== null && value !== false) node.setAttribute(name, value === true ? '' : String(value));
        });
        Object.entries(options.on || {}).forEach(([name, handler]) => node.addEventListener(name, handler));
        (options.children || []).forEach(child => child && node.append(child));
        return node;
    }

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        return token && header ? {[header]: token} : {};
    }

    async function request(url, options = {}) {
        const response = await fetch(url, {credentials: 'same-origin', ...options,
            headers: {...(options.body ? {'Content-Type': 'application/json'} : {}), ...csrfHeaders(), ...(options.headers || {})}});
        if (response.status === 204) return null;
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.error || body.message || i18n.t('alert.status.failed'));
        return body;
    }

    function status(message) {
        context?.onStatus?.(message);
    }

    // ---- geometry: what the server compares the close with, in time and price

    function point(value) {
        return {time: Math.round(value.time), price: value.price};
    }

    /** The drawn shape as the server needs it, or null for drawings alerts cannot follow. */
    function geometry(drawing, mapper) {
        const [a, b, c] = drawing.points || [];
        const style = drawing.style || {};
        const valid = item => item && finite(item.time) && finite(item.price);
        switch (drawing.type) {
            case 'horizontalLine':
            case 'priceLabel':
                return valid(a) ? {geometry: 'HORIZONTAL', price: a.price} : null;
            case 'horizontalRay':
                return valid(a) ? {geometry: 'HORIZONTAL', price: a.price, from: Math.round(a.time)} : null;
            case 'trendLine':
            case 'infoLine':
            case 'arrowLine':
            case 'ray':
            case 'extendedLine': {
                if (!valid(a) || !valid(b) || Math.round(a.time) === Math.round(b.time)) return null;
                const extendLeft = drawing.type === 'extendedLine' || (drawing.type !== 'ray' && Boolean(style.extendLeft));
                const extendRight = drawing.type === 'ray' || drawing.type === 'extendedLine' || Boolean(style.extendRight);
                return {geometry: 'LINE', a: point(a), b: point(b), extendLeft, extendRight};
            }
            case 'parallelChannel': {
                if (!valid(a) || !valid(b) || !valid(c) || !mapper) return null;
                const ia = mapper.logical(a.time);
                const ib = mapper.logical(b.time);
                const ic = mapper.logical(c.time);
                if (![ia, ib, ic].every(finite) || ia === ib) return null;
                // The second line passes through the third point, parallel to the first (as the chart draws it).
                const offset = c.price - (a.price + (b.price - a.price) * (ic - ia) / (ib - ia));
                const first = {a: point(a), b: point(b)};
                const second = {a: {time: Math.round(a.time), price: a.price + offset}, b: {time: Math.round(b.time), price: b.price + offset}};
                return {geometry: 'CHANNEL', upper: offset > 0 ? second : first, lower: offset > 0 ? first : second,
                    extendLeft: Boolean(style.extendLeft), extendRight: Boolean(style.extendRight)};
            }
            case 'rectangle': {
                if (!valid(a) || !valid(b) || Math.round(a.time) === Math.round(b.time)) return null;
                return {geometry: 'BOX', from: Math.round(Math.min(a.time, b.time)), to: Math.round(Math.max(a.time, b.time)),
                    top: Math.max(a.price, b.price), bottom: Math.min(a.price, b.price),
                    extendLeft: Boolean(style.extendLeft), extendRight: Boolean(style.extendRight)};
            }
            default:
                return null;
        }
    }

    /** The indicator and every indicator it reads, so the server can calculate it on its own. */
    function indicatorConfigurations(manager, config) {
        const ordered = [];
        const seen = new Set();
        const visit = item => {
            if (!item || seen.has(item.id)) return;
            seen.add(item.id);
            Object.values(item.inputs || {}).forEach(value => {
                const match = String(value).match(/^ind:([^:]+):/);
                if (match) visit(manager.find(match[1]));
            });
            ordered.push({id: item.id, type: item.type, parameters: manager.inputValues(item)});
        };
        visit(config);
        return ordered;
    }

    // ---- dialogs

    function field(label, control, hint) {
        return element('label', {className: 'draw-field', children: [element('span', {className: 'draw-field-label', text: label}), control,
            hint ? element('span', {className: 'draw-field-hint', text: hint}) : null]});
    }

    function select(options, value) {
        const node = element('select');
        options.forEach(([key, label]) => node.append(element('option', {text: label, attrs: {value: key}})));
        if (value !== undefined) node.value = value;
        return node;
    }

    function intervalSelect() {
        return select(INTERVALS.map(interval => [interval, i18n.t(`draw.interval.${interval}`)]), context.interval());
    }

    function frequencySelect() {
        return select([['ONCE', i18n.t('alert.frequency.ONCE')], ['EVERY_BAR', i18n.t('alert.frequency.EVERY_BAR')]], 'ONCE');
    }

    function conditionLabel(condition, kind) {
        return i18n.t(`alert.condition.${kind}.${condition}`);
    }

    function dialog(title, fields, onSubmit) {
        const node = element('dialog', {className: 'draw-dialog chart-alert-dialog', attrs: {'aria-label': title}});
        const form = element('form', {className: 'draw-dialog-content', attrs: {method: 'dialog', novalidate: true}});
        const error = element('p', {className: 'ind-settings-error', attrs: {role: 'alert'}});
        const submit = element('button', {className: 'btn-primary', text: i18n.t('alert.action.create'), attrs: {type: 'submit'}});
        form.append(
            element('div', {className: 'draw-dialog-heading', children: [element('h2', {className: 'draw-dialog-title', text: title})]}),
            ...fields,
            element('p', {className: 'draw-field-hint', text: i18n.t('alert.dialog.delivery')}),
            error,
            element('div', {className: 'draw-dialog-actions', children: [
                element('span', {className: 'draw-dialog-spacer'}),
                element('button', {className: 'btn-secondary', text: i18n.t('common.cancel'), attrs: {type: 'button'}, on: {click: () => node.close()}}),
                submit]})
        );
        form.addEventListener('submit', async event => {
            event.preventDefault();
            error.textContent = '';
            submit.disabled = true;
            try {
                await onSubmit();
                node.close();
            } catch (failure) {
                error.textContent = failure.message || i18n.t('alert.status.failed');
            } finally {
                submit.disabled = false;
            }
        });
        node.addEventListener('close', () => node.remove());
        node.append(form);
        document.body.append(node);
        node.showModal();
        form.querySelector('input, select')?.focus();
        return node;
    }

    async function create(payload) {
        const created = await request('/api/chart-alerts', {method: 'POST', body: JSON.stringify(payload)});
        alerts = [created, ...alerts.filter(item => item.id !== created.id)];
        status(i18n.t('alert.status.created', created.label));
        return created;
    }

    function drawingName(drawing) {
        return drawing.name || i18n.t(`draw.tool.${drawing.type}`);
    }

    function openForDrawing(drawing) {
        if (!context || !drawing) return;
        const shape = geometry(drawing, context.drawings?.mapper);
        if (!shape) {
            status(i18n.t('alert.status.unsupported'));
            return;
        }
        const band = BAND_TOOLS.has(drawing.type);
        const name = element('input', {attrs: {type: 'text', maxlength: 160, required: true}});
        name.value = drawingName(drawing);
        const condition = select((band ? BAND_CONDITIONS : LEVEL_CONDITIONS).map(item => [item, conditionLabel(item, 'DRAWING')]),
            band ? 'ENTERS' : 'CROSSES');
        const interval = intervalSelect();
        const frequency = frequencySelect();
        dialog(i18n.t('alert.dialog.drawingTitle', drawingName(drawing)), [
            field(i18n.t('alert.field.name'), name),
            field(i18n.t('alert.field.condition'), condition, i18n.t('alert.dialog.closeHint')),
            field(i18n.t('alert.field.interval'), interval),
            field(i18n.t('alert.field.frequency'), frequency)
        ], () => create({
            symbol: context.symbol, interval: interval.value, sourceKind: 'DRAWING', sourceId: drawing.id,
            label: name.value.trim(), condition: condition.value, frequency: frequency.value,
            definition: geometry(drawing, context.drawings?.mapper)
        }));
    }

    function openForIndicator(config) {
        if (!context?.indicators || !config) return;
        const manager = context.indicators;
        const definition = manager.definition(config.type);
        const outputs = (definition?.outputs || []).filter(output => output.style !== 'MARKERS');
        if (!outputs.length) {
            status(i18n.t('alert.status.noLines'));
            return;
        }
        const name = element('input', {attrs: {type: 'text', maxlength: 160, required: true}});
        name.value = manager.label(config);
        const output = select(outputs.map(item => [item.key, manager.outputLabel(config.type, item.key)]), outputs[0].key);
        const condition = select([...LEVEL_CONDITIONS, ...BAND_CONDITIONS].map(item => [item, conditionLabel(item, 'INDICATOR')]), 'CROSSES');
        const compare = select([['VALUE', i18n.t('alert.compare.VALUE')], ['OUTPUT', i18n.t('alert.compare.OUTPUT')],
            ['PRICE', i18n.t('alert.compare.PRICE')]], 'VALUE');
        const value = element('input', {attrs: {type: 'number', step: 'any', inputmode: 'decimal'}});
        const references = definition?.zones ? manager.inputValues(config)[definition.zones.upperInput] : definition?.references?.[0];
        if (finite(Number(references))) value.value = String(references);
        const other = select([], undefined);
        const lower = element('input', {attrs: {type: 'number', step: 'any', inputmode: 'decimal'}});
        const upper = element('input', {attrs: {type: 'number', step: 'any', inputmode: 'decimal'}});
        if (definition?.zones) {
            const values = manager.inputValues(config);
            lower.value = String(values[definition.zones.lowerInput]);
            upper.value = String(values[definition.zones.upperInput]);
        }
        const compareField = field(i18n.t('alert.field.compare'), compare);
        const valueField = field(i18n.t('alert.field.value'), value);
        const otherField = field(i18n.t('alert.field.otherLine'), other);
        const lowerField = field(i18n.t('alert.field.lower'), lower);
        const upperField = field(i18n.t('alert.field.upper'), upper);
        const interval = intervalSelect();
        const frequency = frequencySelect();
        const refresh = () => {
            other.replaceChildren(...outputs.filter(item => item.key !== output.value)
                .map(item => element('option', {text: manager.outputLabel(config.type, item.key), attrs: {value: item.key}})));
            compare.querySelector('option[value="OUTPUT"]').disabled = !other.options.length;
            if (compare.value === 'OUTPUT' && !other.options.length) compare.value = 'VALUE';
            const band = BAND_CONDITIONS.includes(condition.value);
            compareField.hidden = band;
            valueField.hidden = band || compare.value !== 'VALUE';
            otherField.hidden = band || compare.value !== 'OUTPUT';
            lowerField.hidden = !band;
            upperField.hidden = !band;
        };
        [output, condition, compare].forEach(control => control.addEventListener('change', refresh));
        refresh();
        dialog(i18n.t('alert.dialog.indicatorTitle', manager.label(config)), [
            field(i18n.t('alert.field.name'), name), field(i18n.t('alert.field.line'), output),
            field(i18n.t('alert.field.condition'), condition, i18n.t('alert.dialog.closeHint')),
            compareField, valueField, otherField, lowerField, upperField,
            field(i18n.t('alert.field.interval'), interval), field(i18n.t('alert.field.frequency'), frequency)
        ], () => {
            const band = BAND_CONDITIONS.includes(condition.value);
            const payload = {indicators: indicatorConfigurations(manager, config), target: config.id, output: output.value};
            if (band) {
                Object.assign(payload, {compare: 'CHANNEL', lower: Number(lower.value), upper: Number(upper.value)});
            } else {
                payload.compare = compare.value;
                if (compare.value === 'VALUE') payload.value = Number(value.value);
                if (compare.value === 'OUTPUT') payload.otherOutput = other.value;
            }
            return create({symbol: context.symbol, interval: interval.value, sourceKind: 'INDICATOR', sourceId: config.id,
                label: name.value.trim(), condition: condition.value, frequency: frequency.value, definition: payload});
        });
    }

    // ---- the symbol's alerts

    async function load() {
        if (!context) return [];
        try {
            alerts = (await request(`/api/chart-alerts?symbol=${encodeURIComponent(context.symbol)}`))?.alerts || [];
        } catch (error) {
            alerts = [];
        }
        return alerts;
    }

    async function openManager() {
        if (!context) return;
        await load();
        const node = element('dialog', {className: 'draw-dialog chart-alert-manager', attrs: {'aria-labelledby': 'chartAlertManagerTitle'}});
        const content = element('div', {className: 'draw-dialog-content'});
        const list = element('div', {className: 'chart-alert-list', attrs: {role: 'list'}});
        const render = () => {
            if (!alerts.length) {
                list.replaceChildren(element('p', {className: 'draw-field-hint', text: i18n.t('alert.manager.empty')}));
                return;
            }
            list.replaceChildren(...alerts.map(alert => {
                const toggle = element('input', {attrs: {type: 'checkbox', 'aria-label': i18n.t('alert.manager.active', alert.label)}});
                toggle.checked = alert.active;
                toggle.addEventListener('change', async () => {
                    try {
                        const updated = await request(`/api/chart-alerts/${alert.id}`, {method: 'PATCH', body: JSON.stringify({active: toggle.checked})});
                        Object.assign(alert, updated);
                    } catch (error) {
                        toggle.checked = alert.active;
                        status(error.message);
                    }
                    render();
                });
                const remove = element('button', {className: 'draw-tool-button danger', attrs: {type: 'button', 'aria-label': i18n.t('alert.manager.delete', alert.label),
                    title: i18n.t('alert.manager.delete', alert.label)}, on: {click: async () => {
                    await request(`/api/chart-alerts/${alert.id}`, {method: 'DELETE'}).catch(error => status(error.message));
                    alerts = alerts.filter(item => item.id !== alert.id);
                    render();
                }}});
                if (window.StockWatchDrawingIcons) remove.append(window.StockWatchDrawingIcons.icon('trash', 15));
                const details = [conditionLabel(alert.condition, alert.sourceKind), i18n.t(`draw.interval.${alert.interval}`),
                    i18n.t(`alert.frequency.${alert.frequency}`)];
                if (alert.triggerCount) details.push(i18n.plural('alert.manager.triggered', alert.triggerCount, alert.triggerCount));
                return element('div', {className: `chart-alert-row${alert.active ? '' : ' inactive'}`, attrs: {role: 'listitem'}, children: [
                    element('label', {className: 'chart-alert-switch', children: [toggle]}),
                    element('div', {className: 'chart-alert-text', children: [
                        element('strong', {text: alert.label}),
                        element('span', {text: details.join(' · ')})]}),
                    remove]});
            }));
        };
        content.append(
            element('div', {className: 'draw-dialog-heading', children: [
                element('h2', {className: 'draw-dialog-title', text: i18n.t('alert.manager.title', context.symbol), attrs: {id: 'chartAlertManagerTitle'}}),
                element('button', {className: 'btn-secondary', text: i18n.t('common.close'), attrs: {type: 'button'}, on: {click: () => node.close()}})]}),
            element('p', {className: 'draw-field-hint', text: i18n.t('alert.manager.help')}),
            list);
        node.append(content);
        node.addEventListener('close', () => node.remove());
        document.body.append(node);
        render();
        node.showModal();
    }

    /** Moving or deleting a drawing updates or removes its alerts. */
    function scheduleSync() {
        window.clearTimeout(syncTimer);
        syncTimer = window.setTimeout(sync, 1200);
    }

    async function sync() {
        const manager = context?.drawings;
        if (!manager || !alerts.some(alert => alert.sourceKind === 'DRAWING')) return;
        const removed = new Set();
        for (const alert of alerts.filter(item => item.sourceKind === 'DRAWING')) {
            const drawing = manager.drawings.find(item => item.id === alert.sourceId);
            if (!drawing) {
                if (removed.has(alert.sourceId)) continue;
                removed.add(alert.sourceId);
                await request(`/api/chart-alerts?symbol=${encodeURIComponent(context.symbol)}&source=${encodeURIComponent(alert.sourceId)}`,
                    {method: 'DELETE'}).catch(() => null);
                continue;
            }
            const shape = geometry(drawing, manager.mapper);
            if (!shape || JSON.stringify(shape) === JSON.stringify(alert.definition)) continue;
            try {
                Object.assign(alert, await request(`/api/chart-alerts/${alert.id}`, {method: 'PATCH', body: JSON.stringify({definition: shape})}));
            } catch (error) {
                status(error.message);
            }
        }
        if (removed.size) {
            alerts = alerts.filter(alert => !removed.has(alert.sourceId));
            status(i18n.t('alert.status.removedWithDrawing'));
        }
    }

    function attach(options) {
        context = options;
        options.drawings?.on?.('change', scheduleSync);
        return load();
    }

    window.StockWatchChartAlerts = Object.freeze({attach, openForDrawing, openForIndicator, openManager, geometry, indicatorConfigurations,
        alerts: () => alerts});
})();
