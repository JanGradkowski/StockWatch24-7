/* Browser check of the drawing toolset on the candlestick charts outside the stock page (signal detail with
 * its other-interval chart, technical outlook, demo trade): the rendered pages with production JS/CSS and
 * deterministic API fixtures (no server or market data needed). Capture the pages first:
 *
 *   ./mvnw test -Ddesign.preview.capture=true -Dtest='StockWatch247ApplicationTests'
 *   npm run test:chart-pages:browser
 *
 * Screenshots are written to target/chart-pages-browser for review. */
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { messages, messagesScript } = require('./i18n-fixture.cjs');

const repo = path.resolve(__dirname, '..');
const previews = path.join(repo, 'target/design-preview');
const output = path.join(repo, 'target/chart-pages-browser');
fs.mkdirSync(output, { recursive: true });
const HOST = 'http://chart.test';
const DAY = 86400;

/** The newest capture whose name starts with {@code prefix}. */
function capture(prefix) {
  const files = fs.existsSync(previews) ? fs.readdirSync(previews).filter(file => file.startsWith(prefix) && file.endsWith('.html')) : [];
  files.sort((a, b) => fs.statSync(path.join(previews, b)).mtimeMs - fs.statSync(path.join(previews, a)).mtimeMs);
  assert.ok(files.length, `Missing ${prefix}*.html; run the capture command at the top of this script first.`);
  return fs.readFileSync(path.join(previews, files[0]), 'utf8');
}

/** Candles ending on Friday 9 October 2026, as /api/stocks/{symbol}/candles serves them. */
function candles(interval, count = 600) {
  const result = [];
  const step = interval === '1wk' ? 7 * DAY : interval === '1mo' ? 30 * DAY : DAY;
  let time = Date.UTC(2026, 9, 9) / 1000;
  while (result.length < count) {
    const weekday = new Date(time * 1000).getUTCDay();
    if (interval !== '1d' || (weekday !== 0 && weekday !== 6)) result.push(time);
    time -= step;
  }
  return result.reverse().map((timestamp, index) => {
    const close = 120 + index * 0.08 + Math.sin(index / 9) * 4 + Math.sin(index / 41) * 9;
    const open = close - Math.cos(index / 3) * 1.2;
    return { timestamp, openPrice: open, highPrice: Math.max(open, close) + 1 + (index % 5) * 0.2,
      lowPrice: Math.min(open, close) - 0.8 - (index % 7) * 0.15, closePrice: close, volume: 2_000_000 + (index % 13) * 150_000 };
  });
}

function outlook(interval) {
  const bars = candles(interval, 400).map(bar => ({
    timestamp: bar.timestamp, open: bar.openPrice, high: bar.highPrice, low: bar.lowPrice, close: bar.closePrice, volume: bar.volume,
    fastEma: bar.closePrice - 1, slowEma: bar.closePrice - 3, longSma: bar.closePrice - 6, bollingerLower: bar.closePrice - 8,
    bollingerMiddle: bar.closePrice, bollingerUpper: bar.closePrice + 8, vwap: bar.closePrice - 2,
    valueAreaLow: null, pointOfControl: null, valueAreaHigh: null
  }));
  const score = { buy: 6, neutral: 3, sell: 2, net: 4, denominator: 11, normalizedScore: 0.36, classification: 'BUY' };
  return {
    symbol: 'AAPL', companyName: 'Apple Inc.', interval, intervalLabel: 'Daily', profileLabel: 'Factory profile', available: true,
    candleTimestamp: bars[bars.length - 1].timestamp, freshness: 'Up to date', headlineScore: score, rawScore: score,
    categories: [], indicators: [], candles: bars, recentSignals: [], activityMarkers: [],
    marketComparison: { available: false, benchmarkSymbol: '^GSPC', benchmarkName: 'S&P 500', relativeTrend: 'UNAVAILABLE',
      ratioChange: 0, vote: 0, classification: 'UNAVAILABLE', horizons: [], ratioSeries: [] },
    indicatorSettings: { rsiPeriod: 14, atrPeriod: 14, fastEmaPeriod: 20, slowEmaPeriod: 50, longSmaPeriod: 200, macdFastPeriod: 12,
      macdSlowPeriod: 26, macdSignalPeriod: 9, cciPeriod: 20, bollingerPeriod: 20, bollingerDeviation: 2, volumePeriod: 20,
      vwapPeriod: 20, volumeProfilePeriod: 20, supportResistancePeriod: 20 },
    methodology: { rawVoteRule: '-', categoryRule: '-', thresholds: '-', disclaimer: '-' }
  };
}

async function open(browser, language, pages) {
  const text = messages(language === 'pl' ? 'messages_pl.properties' : 'messages.properties');
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.setDefaultTimeout(15000);
  const errors = [];
  const mutations = [];
  page.on('pageerror', error => { errors.push(error.message); console.error(`[${language}] page error:`, error.message); });
  await page.route('**/*', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.origin !== HOST) return route.abort();
    const pathname = url.pathname;
    if (pages[pathname]) return route.fulfill({ contentType: 'text/html', body: pages[pathname] });
    if (pathname === '/i18n/messages.js') return route.fulfill({ contentType: 'application/javascript', body: messagesScript(text, language) });
    if (pathname.startsWith('/api/')) {
      if (request.method() !== 'GET') mutations.push({ method: request.method(), url: url.pathname + url.search, body: request.postData() });
      if (/^\/api\/stocks\/[^/]+\/candles$/.test(pathname)) {
        const before = Number(url.searchParams.get('before') || Infinity);
        const limit = Number(url.searchParams.get('limit') || 1000);
        const older = candles(url.searchParams.get('interval') || '1d').filter(bar => bar.timestamp < before).slice(-limit);
        return json({ candles: older, nextCursor: older[0]?.timestamp ?? null, hasMore: false, source: 'CACHE', failureMessage: null });
      }
      if (pathname === '/api/stocks/AAPL/technical-outlook') return json(outlook(url.searchParams.get('interval') || '1d'));
      if (pathname === '/api/chart-state/drawings') return json({ drawings: [] });
      if (pathname.startsWith('/api/chart-state/drawings/')) {
        return json({ id: decodeURIComponent(pathname.split('/').pop()), version: 1, payload: JSON.parse(request.postData() || '{}') });
      }
      if (pathname.startsWith('/api/chart-state/templates')) return json({ templates: [] });
      if (pathname === '/api/chart-alerts') return json({ alerts: [] });
      if (pathname === '/api/virtual-trades') return json([]);
      return json({});
    }
    const relative = pathname.replace(/-[a-f0-9]{32}(?=\.(js|css)$)/, '');
    const file = path.join(repo, 'src/main/resources/static', relative);
    if (file.startsWith(path.join(repo, 'src/main/resources/static')) && fs.existsSync(file) && fs.statSync(file).isFile()) {
      const contentType = file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css'
        : file.endsWith('.svg') ? 'image/svg+xml' : file.endsWith('.woff2') ? 'font/woff2' : 'application/octet-stream';
      return route.fulfill({ contentType, body: fs.readFileSync(file) });
    }
    return route.fulfill({ status: 404, body: '' });
  });
  return { page, errors, mutations, text };
}

/** The rail sits in its own column left of the chart, exactly as tall, with nothing spilling out of it. */
async function railBeside(page, chartSelector) {
  const geometry = await page.evaluate(selector => {
    const chart = document.querySelector(selector);
    const rail = chart.parentElement.querySelector(':scope > [data-drawing-rail-host] .draw-rail');
    const box = node => { const rect = node.getBoundingClientRect(); return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom }; };
    const canvas = chart.querySelector('.tv-lightweight-charts');
    return {
      chart: box(chart), rail: rail && box(rail), overChart: Boolean(chart.querySelector('.draw-rail')),
      drawnTo: canvas ? canvas.getBoundingClientRect().right : null,
      fits: rail && rail.scrollHeight <= rail.clientHeight + 1,
      folded: rail ? [...rail.querySelectorAll(':scope > .draw-rail-button[hidden]:not(.draw-rail-overflow)')].length : 0,
      overflow: Boolean(rail && !rail.querySelector('.draw-rail-overflow').hidden)
    };
  }, chartSelector);
  assert.ok(geometry.rail, `${chartSelector}: no rail`);
  assert.equal(geometry.overChart, false, `${chartSelector}: the rail is over the chart`);
  assert.ok(geometry.rail.right <= geometry.chart.left, `${chartSelector}: the rail overlaps the chart`);
  assert.ok(geometry.drawnTo !== null && geometry.drawnTo <= geometry.chart.right + 1,
    `${chartSelector}: the chart is wider than its box ${JSON.stringify(geometry)}`);
  assert.ok(Math.abs(geometry.rail.top - geometry.chart.top) <= 1 && Math.abs(geometry.rail.bottom - geometry.chart.bottom) <= 1,
    `${chartSelector}: the rail is not as tall as the chart ${JSON.stringify(geometry)}`);
  assert.ok(geometry.fits, `${chartSelector}: the rail overflows`);
  assert.equal(geometry.overflow, geometry.folded > 0, `${chartSelector}: More tools without folded buttons`);
  return geometry;
}

async function signalDetail(browser, language) {
  const { page, errors, mutations, text } = await open(browser, language, { '/alerts/signals/1': capture('_alerts_signals_1-') });
  const shot = name => page.screenshot({ path: path.join(output, `${language}-signal-${name}.png`) });
  await page.goto(`${HOST}/alerts/signals/1`);
  await page.locator('[data-drawing-rail-host] .draw-rail').first().waitFor();
  await page.locator('#signalChart').scrollIntoViewIfNeeded();
  const symbol = await page.locator('#signalChart').getAttribute('data-symbol');

  // The 430 px chart is shorter than the full rail: the least-used buttons fold into More tools.
  const geometry = await railBeside(page, '#signalChart');
  assert.ok(geometry.folded > 0 && geometry.overflow, 'a short chart folds buttons into More tools');
  await shot('rail');

  await page.locator('#signalChart').evaluate(node => node.parentElement.querySelector('.draw-rail-overflow').click());
  const flyout = page.locator('.draw-flyout');
  await flyout.waitFor();
  const box = await flyout.boundingBox();
  assert.ok(box.y >= 0 && box.y + box.height <= 1000 && box.x + box.width <= 1440, 'More tools opens inside the window');
  assert.equal(await flyout.locator('.draw-flyout-item').count(), geometry.folded);
  await shot('more-tools');
  await page.keyboard.press('Escape');

  // Drawing a trend line stores it with the account under the symbol, and alerts can be set on it.
  const frame = page.locator('.chart-tools-frame').first();
  await frame.locator(`.draw-rail-button[data-section="lines"]`).click();
  const chartBox = await page.locator('#signalChart').boundingBox();
  await page.mouse.click(chartBox.x + chartBox.width * 0.3, chartBox.y + chartBox.height * 0.6);
  await page.mouse.click(chartBox.x + chartBox.width * 0.6, chartBox.y + chartBox.height * 0.35);
  await page.waitForTimeout(600);
  assert.ok(mutations.some(item => item.method === 'PUT' && item.url.startsWith('/api/chart-state/drawings/')
    && item.url.includes(`symbol=${encodeURIComponent(symbol)}`)), 'the drawing is saved to the account');
  await page.locator('#signalChart .draw-selection-toolbar').waitFor();
  await page.locator(`#signalChart .draw-selection-toolbar [aria-label="${text['draw.action.alert']}"]`).click();
  await page.locator('.chart-alert-dialog').waitFor();
  await shot('alert');
  await page.locator('.chart-alert-dialog .btn-secondary').click();

  // The other-interval chart gets its own rail; the signal chart's frame goes away with its chart.
  await page.locator('[data-detail-interval="1d"]').click();
  await page.locator('#signalAlternateChart .tv-lightweight-charts, #signalAlternateChart canvas').first().waitFor();
  assert.equal(await page.locator('#signalChart').evaluate(node => node.parentElement.getBoundingClientRect().height), 0);
  await railBeside(page, '#signalAlternateChart');
  assert.ok(await page.evaluate(() => window.StockWatchDrawing.managers().length) >= 2);

  await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
  await page.locator('[data-detail-interval="1wk"]').click();
  await page.locator('#signalChart').scrollIntoViewIfNeeded();
  await shot('light');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(300);
  await page.locator('#signalChart').scrollIntoViewIfNeeded();
  await railBeside(page, '#signalChart');
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), 'no sideways scroll on phones');
  await shot('phone');

  assert.deepEqual(errors, []);
  await page.close();
}

async function technicalOutlook(browser, language) {
  const { page, errors } = await open(browser, language, { '/stock/AAPL/technical-outlook': capture('_stock_AAPL_technical-outlook-') });
  await page.goto(`${HOST}/stock/AAPL/technical-outlook`);
  await page.locator('#technicalOutlookPriceChart canvas').first().waitFor();
  await page.locator('[data-drawing-rail-host] .draw-rail').first().waitFor();
  await page.locator('#technicalOutlookPriceChart').scrollIntoViewIfNeeded();
  await railBeside(page, '#technicalOutlookPriceChart');
  const features = await page.evaluate(() => ({ ...window.StockWatchDrawing.managers()[0].features }));
  assert.deepEqual(features, { alerts: true, demoTrade: true });
  // The outlook's candles carry volume, so the volume tools need no extra request.
  assert.ok(await page.evaluate(() => window.StockWatchDrawing.managers()[0].bars.every(bar => Number.isFinite(bar.volume))));
  await page.screenshot({ path: path.join(output, `${language}-outlook.png`) });
  assert.deepEqual(errors, []);
  await page.close();
}

async function demoTrade(browser, language) {
  const { page, errors } = await open(browser, language, { '/virtual-trades/1': capture('_virtual-trades_1-') });
  await page.goto(`${HOST}/virtual-trades/1`);
  await page.locator('#virtualTradePriceChart canvas').first().waitFor();
  await page.locator('[data-drawing-rail-host] .draw-rail').first().waitFor();
  await page.locator('#virtualTradePriceChart').scrollIntoViewIfNeeded();
  await railBeside(page, '#virtualTradePriceChart');
  await page.screenshot({ path: path.join(output, `${language}-demo-trade.png`) });
  assert.deepEqual(errors, []);
  await page.close();
}

(async () => {
  const browser = await chromium.launch({ channel: process.env.PW_BROWSER || (process.platform === 'win32' ? 'msedge' : 'chromium'), headless: true });
  try {
    for (const language of ['en', 'pl']) {
      await signalDetail(browser, language);
      await technicalOutlook(browser, language);
      await demoTrade(browser, language);
      console.log(`[${language}] chart page checks passed`);
    }
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
