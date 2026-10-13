const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// A minimal Lightweight Charts 5 surface: addSeries(definition), createSeriesMarkers(series).
function app(theme = 'dark') {
    const events = new Map(), charts = [], markerPlugins = [];
    const document = {documentElement: {dataset: {theme}}, addEventListener() {}, querySelectorAll: () => []};
    const definition = type => ({type, isBuiltIn: true, defaultOptions: {}});
    const context = {document, window: {addEventListener: (key, fn) => events.set(key, fn)}, LightweightCharts: {
        LineSeries: definition('Line'), HistogramSeries: definition('Histogram'), CandlestickSeries: definition('Candlestick'),
        AreaSeries: definition('Area'), BaselineSeries: definition('Baseline'), BarSeries: definition('Bar'),
        createSeriesMarkers(series, markers) {
            const plugin = {series, current: markers, setMarkers(next) {this.current = next;}};
            markerPlugins.push(plugin); return plugin;
        },
        createChart(host, options) {
            const chart = {options, series: [], removed: false, applyOptions(next) {Object.assign(this.options, next);}, remove() {this.removed = true;}, removeSeries(series) {this.series.splice(this.series.indexOf(series), 1);}};
            const add = (seriesDefinition, options, paneIndex) => {
                const series = {type: seriesDefinition.type ?? 'Custom', paneIndex, options, writes: 0, applyOptions(next) {Object.assign(this.options, next);},
                    setData(data) {this.data = data; this.writes++;},
                    createPriceLine(options) {return {options, applyOptions(next) {Object.assign(this.options, next);}};}, removePriceLine() {}};
                chart.series.push(series); return series;
            };
            chart.addSeries = add;
            chart.addCustomSeries = (view, options, paneIndex) => add({}, options, paneIndex);
            charts.push(chart);return chart;
        }
    }};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/chart-theme.js', 'utf8'), context);
    const markersOf = series => markerPlugins.find(plugin => plugin.series === series)?.current;
    return {api:context.window.StockWatchCharts, charts, markersOf, switchTheme(theme) {document.documentElement.dataset.theme = theme;events.get('stockwatch:themechange')();}};
}
test('theme updates existing charts and series without rebuilding or changing visibility and data', () => {
    const a = app(), chart = a.api.createChart({}, {height:540});
    const series = chart.addLineSeries({color:'#64e8bd', visible:true, lineStyle:2});
    series.setData([{time:1,value:0},{time:2,value:8}]);
    series.applyOptions({visible:false});
    const guide = series.createPriceLine({price:12,color:'#ffbd59'});
    guide.applyOptions({price:15});
    a.switchTheme('light');
    assert.equal(a.charts.length,1);assert.equal(chart.options.height,540);
    assert.equal(series.options.color,'#08795d');assert.equal(series.options.visible,false);assert.equal(series.options.lineStyle,2);
    assert.equal(series.writes,1);assert.equal(series.data[0].value,0);assert.equal(guide.options.price,15);assert.equal(guide.options.color,'#945b09');
    a.switchTheme('dark');assert.equal(series.options.color,'#64e8bd');
});
test('coloured histogram points and marker visibility survive repeated theme changes', () => {
    const a = app(), chart = a.api.createChart({}, {}), series = chart.addHistogramSeries({color:'#64e8bd'});
    const data=[{time:1,value:2,color:'#64e8bd'},{time:2,value:-1,color:'#ff647c'}];
    series.setData(data);series.setMarkers([{time:1,color:'#ffbd59',text:'Entry'}]);
    a.switchTheme('light');assert.equal(series.data[0].color,'#08795d');assert.equal(data[0].color,'#64e8bd');
    assert.equal(a.markersOf(series)[0].text,'Entry');assert.equal(a.markersOf(series)[0].color,'#945b09');
    assert.equal(series.markers()[0].text,'Entry');series.setMarkers([]);
    a.switchTheme('dark');assert.equal(a.markersOf(series).length,0);assert.equal(series.data[1].value,-1);
});
test('legacy series methods map onto Lightweight Charts 5 definitions and pane indexes', () => {
    const a = app(), chart = a.api.createChart({}, {});
    assert.equal(chart.addCandlestickSeries({}).type, 'Candlestick');
    assert.equal(chart.addLineSeries({}, 2).paneIndex, 2);
    assert.equal(chart.addSeries({type: 'Area'}, {}, 1).type, 'Area');
});
test('markers are written in time order whatever order the page supplies', () => {
    const a = app(), chart = a.api.createChart({}, {}), series = chart.addLineSeries({});
    series.setMarkers([{time:'2024-03-02',text:'b'},{time:'2024-06-01',text:'c'},{time:'2024-01-05',text:'a'}]);
    assert.deepEqual([...a.markersOf(series).map(marker => marker.text)], ['a','b','c']);
    const business = chart.addLineSeries({});
    business.setMarkers([{time:{year:2024,month:6,day:1},text:'late'},{time:{year:2023,month:12,day:31},text:'early'}]);
    assert.deepEqual([...a.markersOf(business).map(marker => marker.text)], ['early','late']);
});
test('explicit account colours are preserved and removed charts stop receiving updates', () => {
    const a=app('light');a.api.preserveColors(['#64e8bd']);
    const chart=a.api.createChart({},{}), series=chart.addLineSeries({color:'#64e8bd'});
    assert.equal(series.options.color,'#64e8bd');chart.remove();
    const color=chart.options.layout.textColor;a.switchTheme('dark');assert.equal(chart.options.layout.textColor,color);
});
