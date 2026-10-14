# TradingView chart parity: implementation notes

Date: 2026-10-11 (updated 2026-10-12)

What was built for phases 0–6 of `tradingview-chart-parity-plan.md`, where it lives, the
decisions taken on the way, and what was left out.

## Phase 0: foundations

- **Lightweight Charts 5.2.1** is vendored under `static/js/vendor/lightweight-charts/5.2.1/`
  with its LICENSE and NOTICE (Maven Central has no v5 webjar). `.gitattributes` marks the
  folder `-text` so the minified file is stored byte for byte.
- **v5 adapter.** `chart-theme.js` keeps the older `add*Series` and `setMarkers` names on top of
  v5's `addSeries(definition, options, pane)` and `createSeriesMarkers`, so all chart pages share
  one migration point, and re-themes series on a theme switch.
- **Attribution.** v5's built-in logo injects a `<style>` element that the page CSP blocks, so
  `attributionLogo` is off and the licence's attribution and tradingview.com link are in the
  About page footer.
- **Indicator catalog.** `service/chartindicator/` defines 121 indicators (inputs, outputs, styles,
  fills, zones, references, shapes) and computes them; `GET /api/chart-indicators/catalog` serves
  the definitions and `POST /api/stocks/{symbol}/chart-indicators` calculates a batch.
- **Server-side chart state.** `V86__chart_workspace_state.sql`, `ChartStateService` and
  `/api/chart-state/*` keep drawings (per account and symbol, versioned), indicator layouts and
  templates. Limits: 32 KB per drawing, 1,000 drawings per symbol, 20,000 per account.

## Phases 1–2: indicators

`static/js/chart-indicators.js` (`StockWatchIndicatorManager`) builds everything from the catalog:

- **Indicator list**: search, categories, favourites, recently used, descriptions; `/` opens it.
  Indicators that have a learning-mode explanation are marked in the list.
- **Settings dialog** with Inputs, Style and Visibility tabs: every input kind (numbers, choices,
  sources, timeframes, symbols, dates, formula text), output colours, widths and line styles,
  fills, reference lines, RSI-style zones, and which intervals show the indicator. Defaults can
  be saved per indicator type. The formula indicator shows its allowed series and functions.
- **Panes**: panel indicators get their own pane of the one chart (shared time axis and
  crosshair), the volume pane sits under the price, and panes can be moved, maximised, resized by
  their separators or merged into the price pane. As on TradingView the chart keeps one height and
  its panes share it by weight (price 480, volume 110, others 150 by default; a separator drag
  moves weight between the two panes beside it and is remembered). A new pane makes the others
  smaller instead of making the chart taller, and the price pane keeps at least 40% of the height
  however many panes there are. Legends are cut off at the bottom of their pane; the price
  legend's collapse button sits under the symbol row so it stays in view.
- **Chart height**: without a chosen height the chart fills the window below the panel heading
  (window height − 180 px, 420–900 px; at least 360 px on phones) and follows window resizes of
  more than 150 px, so a phone's address bar does not make it jump. The handle under the chart
  sets a height of its own (260–1,200 px, saved with the layout); double-clicking it goes back to
  fitting the window. A price-pane height saved by the earlier version is kept only when it is
  taller than the fitted height.
- **Legends** per pane with live values under the crosshair, hide/settings/more/remove controls,
  errors per indicator, a symbol row with OHLC and change, and a collapsible price legend.
- **Rendering**: lines, steps, areas, histograms, columns, dots, markers, sign/momentum/tone
  colouring, gaps where an output has none, band and cloud fills (a custom series), and a shape
  primitive for levels, segments, polylines, labels, boxes, volume profiles and tables.
- **Indicator on indicator** (a source can be another indicator's output), higher timeframes,
  and second symbols (compare, ratio, spread, correlation).
- **Layout and templates**: the layout (indicators, pane heights, chart type, scale, volume,
  data window) is saved to the account; indicator sets can be saved and loaded by name.

A setting the server rejects (a formula typo, fast ≥ slow) now marks only that indicator in its
legend instead of failing the whole batch. Those messages are `UserInputException`s and are
translated through `i18n/server-messages.txt`.

## Phases 3–4: drawing tools and workflow

`chart-drawing-core.js`, `-kit.js`, `-tools-*.js`, `-icons.js` and `-ui.js` provide 86 tools
(lines, channels, pitchforks, Fibonacci and Gann, harmonic and Elliott patterns, cycles,
positions, measures, forecasting, volume tools, shapes, annotations) and the workflow: tool rail
with favourites, selection toolbar, properties dialog, object tree (drawings and indicators),
lock/hide, z-order, clone, copy/paste, undo/redo, templates, keyboard shortcuts, magnet modes,
per-interval visibility, drawings on indicator panes, touch input. Every candlestick chart loads the
toolset through `fragments/chart-workbench.html`; the old `fibonacci-drawing-tool.js` is gone.

The other candlestick charts (signal, candlestick, Elliott, harmonic and activity details with their
other-interval charts, the technical outlook and its change reports, demo trades) get the stock page's
toolset through `StockWatchChartTools.attach` (`chart-page-tools.js`, loaded by the `pageAssets`
fragment):

- **Rail column.** The rail goes into the `[data-drawing-rail-host]` element beside the chart (a
  `.chart-tools-frame`), never over the candles, and is exactly as tall as the chart, styled like the
  page's chart box. When the chart is shorter than the rail (detail charts are 430 px, 350 px on
  phones), the least-used buttons fold into "More tools" at its bottom, as TradingView does in short
  windows: favourites, stay in drawing mode, lock, hide, object tree, remove all, then zoom, measure,
  magnet, redo and undo. The stock page's rail is unchanged and scrolls instead.
- **Account storage per symbol**, the same set the stock page shows. A page's charts of one symbol share
  one `ServerDrawingStore.shared` store, so a page loads drawings once and a drawing saved on one chart
  (the signal interval) appears on the other (another interval). Drawings a page kept in this browser
  before are imported once, limited to that chart's interval.
- **Alerts on drawings** (`chart-alerts.js`) and **demo trades from long and short positions**, through the
  `demoTradeDialog` fragment; the technical outlook opens its own demo trade dialog.
- **Volume** for the volume tools and the ranges: the outlook, its change reports and demo trades pass
  their candles' volume; the detail pages, whose candles carry none, load it from the candles endpoint
  the first time a tool needs it.

A tool group (the arrow beside a rail button) opens as a panel fixed to the window beside the
rail, with "Back to all tools" at the top; on phones it is a sheet at the bottom. It lives outside
the rail because the stock page's rail scrolls when the chart is shorter than it, which would cut
the panel off. The rail never scrolls sideways.

On the stock page drawings are stored in the account (`ServerDrawingStore`); drawings an older
version kept per interval in the browser are imported once. When the chart type changes, the
drawing layer moves to the new price series (`DrawingManager.setSeries`).

## Phase 5: alerts on drawings and indicators

- **Data**: `V87__chart_alerts.sql` (`chart_alerts`, `chart_alert_events`).
- **Service**: `ChartAlertService` validates alerts, stores the shape or the indicator batch
  they watch, and evaluates them in the scheduled checks (`ScheduledAlertService` calls it after
  each symbol's candle refresh; `AlertCheckJobStore` schedules symbols that only have chart
  alerts). Only completed candles are used, and only candles that complete after the alert was
  set can trigger it. Each trigger is stored once per candle.
- **Conditions**: for lines (trend lines, rays, extended and horizontal lines, price labels):
  crosses, crosses up, crosses down, closes above, closes below. For channels and rectangles:
  enters, exits, inside, outside. For indicators: an output against a value, another output or
  the closing price, or a channel between two values. "Only once" alerts switch off after
  triggering; "every candle" alerts stay on.
- **Geometry** follows the chart: lines are straight in bar-by-bar space and extend beyond the
  data by trading days (or weeks, months), exactly like the drawing engine's time mapping.
- **Delivery**: triggers appear in Latest signals on the home page (a "Chart alert" row) and are
  emailed through the outbox when the user's email alerts for that interval are on
  (`AlertNotificationService.sendChartAlertEmail`). "Mark all read" includes them.
- **UI** (`static/js/chart-alerts.js`): an alert dialog from a drawing's toolbar or menu and from
  an indicator's legend menu, and a list of the symbol's alerts under Chart tools. A drawing's
  alerts follow it when it is moved and are deleted with it.
- Opening a trigger from Latest signals opens the stock chart with `?chartAlert=<id>`; the page
  switches to the alert's interval, scrolls to the candle and then marks it read with a POST
  (opening a page never changes data).

## Phase 6

- **Chart types**: candles, hollow candles, Heikin Ashi, bars, high-low, line, line with
  markers, step line, area, baseline, columns (`chart-types.js`).
- **Price-only types**: Renko, Line break, Kagi and Point & Figure, built from closes in
  `chart-types.js`. They are not time-based, so they open in their own view: one element per
  brick, line or column, with the date of the candle that completed it on the axis. The box size
  is automatic (ATR 14 rounded to two significant digits) or set in the view's legend, as is the
  Line break count. Indicators, drawings and panes stay on the time-based chart and come back
  when another type is chosen.
- **Price scale**: auto, logarithmic, percentage, indexed to 100, inverted. In percentage and
  indexed scales Lightweight Charts measures every series from its own first visible value, so a
  plain line series (an EMA, a Bollinger band, an Elliott wave from its first pivot) drifts away
  from the candles. Lines on the main price scale are therefore `AnchoredLineView` custom series
  (`chart-types.js`): they have an item for every bar carrying the main series' close as the
  value that sets the base, and the line's own value last only on the final item, for the price
  axis label. Indicator lines, steps and dots, band fills, Elliott waves and sub-waves, harmonic
  patterns and candlestick-pattern highlights use them; indicator markers on the price pane sit
  on the main series. The page's HTML and canvas overlays (Elliott and harmonic points,
  candlestick hit areas, the anchored volume profile) are repositioned on every repaint of the
  price pane through a primitive on the main series, so they follow invert, log and percentage
  as well as panning.
- **Compare**: adds the COMPARE indicator for a searched symbol, rebased to the first candle.
- **Date ranges**: 1M, 3M, 6M, YTD, 1Y, 5Y, All, and go to date; older history is loaded as needed.
- **Screenshot**: a PNG of the chart with the symbol, interval, date and the legends written on it.
- **Full screen** of the chart panel, and a **data window** with OHLC, change, volume and every
  indicator value under the crosshair.
- **Split markers** for splits found in the stored history (`GET /api/stocks/{symbol}/splits`,
  from `corporate_actions`).
- **Formula indicator** (own pane or on price): a restricted grammar over whitelisted functions,
  parsed and checked on the server, never evaluated as code.
- **Automatic chart patterns**: not built; `chart-pattern-detection-research-plan.md` describes
  the validation they need first.

## Left out or different from the plan

- **Price-only types** do not carry indicators or drawings (see Phase 6), unlike TradingView,
  which draws some indicators on Renko bricks.
- **Dividend markers**: the app stores no dividend data, so only detected splits are marked.
- **Full screen** uses the standard Fullscreen API. The app's built-in browser refuses the
  fullscreen permission, so it was checked in headless Edge by the browser check below. Only
  the full-screen element is shown then, so menus, popovers and tool groups open inside it
  (dialogs are modal and shown above it anyway).
- **Alert evaluation uses closes**, not intrabar highs and lows, in line with the other signal
  families that only use completed candles.

## Tests

- Java: catalog consistency and translations for every indicator, input, output and choice
  (`ChartIndicatorCatalogTest`), indicator batches and per-indicator errors
  (`ChartTechnicalIndicatorServiceTest`), chart state (`ChartStateServiceIntegrationTest`), chart
  alerts on stored candles (`ChartAlertServiceIntegrationTest`), the rendered stock page and the
  Latest signals row (`StockWatch247ApplicationTests`), page and script checks
  (`FrontendSecurityTest`), and the message catalogs.
- JavaScript: drawing engine and tools (`chart-drawing.test.cjs`), the indicator manager, band
  fills, chart types and alert geometry (`chart-indicators.test.cjs`), the candlestick overlay
  row, the v5 adapter, and the i18n and learning-mode guards (the guard no longer scans vendored
  libraries).
- Checked in the browser on `/stock/AAPL`: indicators and panes, all chart types, scales, date
  ranges, compare, data window, screenshot, saved layout after a restart, alert dialogs and list,
  a Latest signals trigger opening its chart, dark and light themes, and phone width.
- **Browser check** (`scripts/check_stock_chart_ui.cjs`, Playwright with Edge on Windows): the
  rendered stock page with the production scripts and fixed API answers, in English and Polish.
  It covers a tool group opening whole beside the rail, the indicator list and settings, panes
  and legends, every chart type, log scale, an EMA on the candles' base in percentage and indexed
  scales,
  date ranges, drawing a trend line and setting an alert on it, the screenshot file name, the
  data window clear of the price scale, full screen, compare, light-theme fills, the chart
  keeping its height with 30 indicators, the performance budget and phone width.
  Screenshots go to `target/stock-chart-browser/`. Capture the page and catalog first:

  ```bash
  ./mvnw test -Ddesign.preview.capture=true -Dtest='StockWatch247ApplicationTests#indexAliasIsSearchableAndNavigatesWithTheCanonicalSymbol,ChartIndicatorCatalogTest#capturesTheCatalogForTheBrowserCheckWhenAsked'
  npm run test:chart:browser
  ```

- **Browser check of the other chart pages** (`scripts/check_chart_pages_ui.cjs`): a signal detail with
  its other-interval chart, the technical outlook and a demo trade, in English and Polish. It checks the
  rail beside each chart and as tall as it, the chart inside its box, "More tools" on the short signal
  chart, a trend line saved under the symbol with an alert dialog, light theme and phone width.
  Screenshots go to `target/chart-pages-browser/`. Capture the pages with
  `./mvnw test -Ddesign.preview.capture=true -Dtest=StockWatch247ApplicationTests`, then run
  `npm run test:chart-pages:browser`.

## Performance

The plan's budget is 30 indicators and 200 drawings panning without dropped frames. The browser
check adds them to 1,600 daily candles (16 panes) and pans back and forth 90 times, timing each
step's main-thread work: the range change, the frame's animation callbacks (the chart paints in
one) and the browser's style, layout and paint. It does that three times, because other programs
can only add time, and the best round's median must stay under one 60 Hz frame (16.7 ms).

Profiling found four costs, now fixed:

- `/i18n/messages.js` built a new `Intl.DateTimeFormat` or `Intl.NumberFormat` on every call, and
  the drawing layer formats axis dates on every frame. Formatters are now cached per options.
- The drawing layer asked the chart to update its primitives on every visible-range change. In
  Lightweight Charts that is a full update, which re-lays out every pane on each frame. The chart
  repaints primitives itself when the range changes, so range changes now only notify listeners
  (the selection toolbar).
- Band and cloud fills filled each bar's quad separately. Each run of one colour is now a single
  polygon: half the price conversions, one fill per run, and no seams between bars.
- The drawing palette read computed styles on every paint of every pane; it is cached until the
  theme changes.

The chart used to grow by 150 px for every lower pane, so 16 panes made it about 2,700 px tall
and two thirds of what the GPU repainted on every frame was off screen. It now keeps one height
that fits the window (see Panes), as TradingView does; this matters most on weaker graphics
cards, whose frame rate depends on how much canvas is repainted. The browser check asserts that
30 indicators leave the chart as tall as before, inside the window, with 40% for the price pane.

Measured on a laptop with Intel Iris Xe graphics (headless Edge, GPU canvas, 1440 × 1000 window,
820 px chart):

| Pan with                                        | Main-thread work (median) | Frame interval (median) |
|-------------------------------------------------|---------------------------|-------------------------|
| 3 indicators, no extra drawings                 | 6 ms                      | 16.5 ms (60 fps)        |
| 3 indicators, 200 drawings                      | 8 ms                      | 16.5 ms (60 fps)        |
| 30 overlay indicators, 3 panes                  | 10 ms                     | 28 ms                   |
| the same without band and cloud fills           | 6 ms                      | 22 ms                   |
| 30 indicators in 16 panes, chart growing        | 12–15 ms                  | 31–34 ms (about 30 fps) |
| 30 indicators in 16 panes, one chart height     | 9–12 ms                   | 22–26 ms (about 40 fps) |

The first four rows were measured before the height change, with a chart of about 750 px.
Drawings are cheap; 30 indicators are not, mostly because of the GPU work for that many line
series and translucent fills. The check judges main-thread work only and prints the frame
interval; single rounds vary by a few milliseconds.
