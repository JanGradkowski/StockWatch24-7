(() => {
    'use strict';
    const charts = new Set();
    const preserved = new Set();
    const groups = [
        ['#b7f34a', '#477a12', '#5c861b', '#d7ff83'],
        ['#64e8bd', '#08795d', '#078568', '#00785d', '#3ddc97'],
        ['#ff647c', '#c93452', '#d23f59', '#c54059'],
        ['#ffbd59', '#945b09', '#ffc261', '#ffb55f', '#d68b24', '#fb923c'],
        ['#a78bfa', '#7050bf', '#9a8cff', '#8d7dff', '#b696ff', '#7b5cc7', '#6557d6', '#5b50c9'],
        ['#60a5fa', '#2869bc', '#55b8ff', '#7598ff', '#5473d3'],
        ['#47d4df', '#007d89', '#008e9b', '#46d3c4'],
        ['#f472b6', '#b52b76', '#d993ff'],
        ['#f6df69', '#826810', '#c2e970'],
        ['#ff8f70', '#b84c30'], ['#7ea6ff', '#4469bb'], ['#e2aa65', '#8e6330', '#9b6e35']
    ];
    const light = () => document.documentElement.dataset.theme === 'light';
    function color(value) {
        if (typeof value !== 'string' || preserved.has(value.toLowerCase())) return value;
        const normalized = value.toLowerCase().replace(/\s/g, '');
        const group = groups.find(values => values.includes(normalized));
        if (group) return group[light() ? 1 : 0];
        const rgba = normalized.match(/^rgba?\((\d+),(\d+),(\d+)(?:,([\d.]+))?\)$/);
        if (rgba) {
            const [, r, g, b, alpha = '1'] = rgba;
            if (r === '255' && g === '255' && b === '255') return `rgba(${light() ? '24,34,48' : '232,237,243'},${alpha})`;
            const hex = '#' + [r, g, b].map(v => Number(v).toString(16).padStart(2, '0')).join('');
            const translated = color(hex);
            if (translated !== hex) return `rgba(${[1, 3, 5].map(i => parseInt(translated.slice(i, i + 2), 16)).join(',')},${light() ? Math.max(.8, Number(alpha)) : alpha})`;
        }
        return value;
    }
    function paint(options = {}) {
        return Object.fromEntries(Object.entries(options).map(([key, value]) => [key,
            /color$/i.test(key) ? color(value) : value && typeof value === 'object' && !Array.isArray(value) ? paint(value) : value]));
    }
    function base(options = {}) {
        const palette = light()
            ? {text: '#526174', grid: 'rgba(24,34,48,.08)', border: 'rgba(24,34,48,.18)'}
            : {text: '#94a3b8', grid: 'rgba(232,237,243,.06)', border: 'rgba(232,237,243,.16)'};
        return {...paint(options),
            layout: {...options.layout, textColor: palette.text, fontSize: 12,
                fontFamily: 'Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif'},
            grid: {vertLines: {...options.grid?.vertLines, color: palette.grid}, horzLines: {...options.grid?.horzLines, color: palette.grid}},
            rightPriceScale: {...options.rightPriceScale, borderColor: palette.border},
            timeScale: {...options.timeScale, borderColor: palette.border}};
    }
    function createChart(container, options) {
        const chart = LightweightCharts.createChart(container, base(options));
        const apply = chart.applyOptions.bind(chart);
        const seriesStates = new Map();
        chart.applyOptions = update => apply(base(update));
        for (const method of ['addLineSeries', 'addAreaSeries', 'addBaselineSeries', 'addHistogramSeries', 'addCandlestickSeries', 'addBarSeries']) {
            if (!chart[method]) continue;
            const add = chart[method].bind(chart);
            chart[method] = (initial = {}) => {
                const series = add(paint(initial));
                const state = {options: initial, data: null, markers: null, lines: new Map()};
                const setOptions = series.applyOptions.bind(series);
                const setData = series.setData.bind(series);
                const setMarkers = series.setMarkers?.bind(series);
                state.refresh = () => {
                    setOptions(paint(state.options));
                    if (state.data) setData(state.data.map(paint));
                    if (state.markers) setMarkers(state.markers.map(paint));
                    state.lines.forEach(line => line.refresh());
                };
                series.applyOptions = update => { state.options = {...state.options, ...update}; setOptions(paint(update)); };
                series.setData = data => {
                    state.data = data.some(point => Object.keys(point).some(key => /color$/i.test(key))) ? data : null;
                    setData(state.data ? data.map(paint) : data);
                };
                if (setMarkers) series.setMarkers = markers => { state.markers = markers; setMarkers(markers.map(paint)); };
                if (series.createPriceLine) {
                    const create = series.createPriceLine.bind(series);
                    const remove = series.removePriceLine?.bind(series);
                    series.createPriceLine = settings => {
                        const line = create(paint(settings));
                        let current = settings;
                        const update = line.applyOptions.bind(line);
                        line.applyOptions = next => { current = {...current, ...next}; update(paint(next)); };
                        state.lines.set(line, {refresh: () => update(paint(current))});
                        return line;
                    };
                    if (remove) series.removePriceLine = line => { state.lines.delete(line); remove(line); };
                }
                seriesStates.set(series, state);
                return series;
            };
        }
        const refresh = () => { apply(base()); seriesStates.forEach(state => state.refresh()); };
        charts.add(refresh);
        const removeSeries = chart.removeSeries?.bind(chart);
        if (removeSeries) chart.removeSeries = series => { seriesStates.delete(series); removeSeries(series); };
        const remove = chart.remove.bind(chart);
        chart.remove = () => { charts.delete(refresh); seriesStates.clear(); remove(); };
        return chart;
    }
    function refreshSwatches() {
        document.querySelectorAll('.chart-legend-option svg path').forEach(path => {
            path.dataset.originalColor ||= path.getAttribute('stroke');
            path.setAttribute('stroke', color(path.dataset.originalColor));
        });
        document.querySelectorAll('.signal-chart-key, .signal-results-key').forEach(swatch => {
            swatch.dataset.originalColor ||= getComputedStyle(swatch).backgroundColor;
            swatch.style.backgroundColor = color(swatch.dataset.originalColor);
        });
        document.querySelectorAll('.technical-chart-legend > span').forEach(label => {
            label.dataset.originalColor ||= getComputedStyle(label).color;
            label.style.color = color(label.dataset.originalColor);
        });
    }
    window.StockWatchCharts = {createChart, color, preserveColors: values => values.forEach(value => {
        if (typeof value === 'string') preserved.add(value.toLowerCase());
    })};
    window.addEventListener('stockwatch:themechange', () => { charts.forEach(refresh => refresh()); refreshSwatches(); });
    document.addEventListener('DOMContentLoaded', refreshSwatches);
})();
