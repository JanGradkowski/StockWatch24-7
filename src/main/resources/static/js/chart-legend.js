(() => {
    'use strict';

    // One legend/control renderer for chart series, marker groups and indicator panels.
    window.StockWatchChartLegend = {
        mount(host, items, title) {
            host.replaceChildren();
            const fieldset = document.createElement('fieldset');
            fieldset.className = 'chart-visibility-legend';
            const legend = document.createElement('legend');
            legend.textContent = title;
            const actions = document.createElement('div');
            actions.className = 'chart-legend-actions';
            const options = document.createElement('div');
            options.className = 'chart-legend-options';
            const controls = [];

            items.forEach(item => {
                const label = document.createElement('label');
                label.className = 'chart-legend-option';
                const input = document.createElement('input');
                input.type = 'checkbox';
                input.checked = item.available !== false;
                input.disabled = item.available === false;
                input.dataset.legendKey = item.key;
                if (item.controls) input.setAttribute('aria-controls', item.controls);
                const swatch = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                swatch.setAttribute('viewBox', '0 0 24 12');
                swatch.setAttribute('aria-hidden', 'true');
                const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                path.setAttribute('d', item.marker ? 'M3 6h18m-5-4 5 4-5 4' : 'M2 6h20');
                path.setAttribute('stroke', window.StockWatchCharts?.color(item.color) || item.color);
                path.dataset.originalColor = item.color;
                if (item.dash) path.setAttribute('stroke-dasharray', item.dash);
                swatch.append(path);
                const text = document.createElement('span');
                text.textContent = item.label + (input.disabled ? ' · No data' : '');
                label.title = item.description || item.label;
                label.append(input, swatch, text);
                options.append(label);
                input.addEventListener('change', () => item.setVisible(input.checked));
                controls.push({input, item});
            });

            for (const [text, visible] of [['Show all', true], ['Hide all', false]]) {
                const button = document.createElement('button');
                button.type = 'button';
                button.textContent = text;
                button.disabled = !controls.some(({input}) => !input.disabled);
                button.addEventListener('click', () => controls.forEach(({input, item}) => {
                    if (input.disabled || input.checked === visible) return;
                    input.checked = visible;
                    item.setVisible(visible);
                }));
                actions.append(button);
            }
            fieldset.append(legend, actions, options);
            host.append(fieldset);
        }
    };
})();
