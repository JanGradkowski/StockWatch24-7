# Stock chart: indicator and drawing-tool parity with TradingView

Date: 2026-10-11

Status: implemented (phases 0–6). What was built, and what was left out, is in
`tradingview-chart-parity-implementation.md`.

## Scope

Compare the indicator and drawing capabilities of the stock workspace chart
(`templates/stock.html`, `static/js/stock-workspace.js`,
`static/js/fibonacci-drawing-tool.js`, `ChartTechnicalIndicatorService`) against
TradingView's built-in catalog, then plan how to close the gaps that make sense
for this product.

Constraints that shape the plan:

- The app stores Daily, Weekly and Monthly candles only. Intraday-only TradingView
  features (session VWAP, volume delta, session volume profile) cannot be
  supported without a new data source.
- Charts use TradingView Lightweight Charts **4.0.1** (webjar). This version has
  no panes, no series primitives (plugins, added in 4.1) and no custom series.
- Indicators are computed server-side with **ta4j 0.26.0** through
  `POST /api/stocks/{symbol}/chart-indicators`. ta4j 0.26 already contains roughly
  60 of TradingView's built-in indicators that we do not yet expose.
- The drawing tool is shared. `StockWatchFibonacciDrawingTool` is mounted on the
  stock page and on the signal-detail, activity-signal-detail, historical
  Elliott/harmonic/candlestick detail, technical-outlook and virtual-trade pages.
- Every visible string needs `messages.properties` and `messages_pl.properties`
  entries. Scripts must stay CSP-compatible, with no inline handlers.

## What we have today

### Indicators

| Area | Current capability |
| --- | --- |
| Price overlays | EMA, SMA, Bollinger Bands, rolling VWAP, support/resistance, Donchian, Keltner, rolling volume profile (POC/VAH/VAL), KDE volume-profile mode |
| Panels | RSI (bespoke path with zone colouring), MACD, CCI, ATR, relative volume, ADX/DMI, Stochastic, Stochastic RSI, OBV, MFI, ta4j trend state |
| Interactive | Anchored volume profile: one instance, click a candle to anchor it, live refresh |
| Auto-detected overlays | Candlestick signals with outcomes, Elliott structures, fractal subwaves, harmonic formations |
| Configuration | Numeric inputs only, set once in a modal. Source is always close. Colours come from a hash of the indicator id |
| Management | Chips with remove (×). No edit, hide, reorder or style control |
| Panels | One separate chart instance per indicator. Panel time ranges follow the price chart one way. Crosshairs are not shared, and the panels themselves cannot be scrolled |
| Persistence | None. Indicators survive interval switches but are lost on reload |
| Legend | Names only. Values are not shown under the crosshair |
| Limits | 32 indicators per request, period 2–500, 8 numeric parameters |

### Drawing tools

| Area | Current capability |
| --- | --- |
| Tools | Fibonacci retracement (13 fixed levels from −0.618 to 2.618), Long position, Short position (entry/target/stop, R:R, %) |
| Editing | Move a drawing, drag its anchors and handles, delete the selected drawing (button or Delete key), clear all (two-click confirm), Esc cancels, right-click leaves drawing mode |
| Selection | Only possible while a drawing mode is active. In Navigate mode, drawings cannot be clicked |
| Persistence | `localStorage`, key `stockwatch:fibonacci:v1:stock:{ticker}:{interval}`, max 25 drawings. Drawings are per interval and per device |
| Rendering | One overlay `<canvas>` above the chart. No axis labels, no z-order relative to series, price pane only |

### Where we are ahead of TradingView

These should be kept and highlighted, not rebuilt:

- Automatic Elliott wave structures with fractal subwaves. TradingView only
  provides manual Elliott drawing tools.
- Automatic harmonic formations with historical outcomes. TradingView provides
  only the manual XABCD tool.
- Historical candlestick signals with outcome statistics and alerts.
- KDE volume-profile mode, and an anchored profile with live refresh.
- Learning-mode explanations attached to indicator controls.

## Gap inventory

Status key: **Have**, **Partial** (exists but is clearly weaker), **Missing**,
**N/A** (not feasible with Daily/Weekly/Monthly data, or out of product scope).

### Indicator system features

| TradingView feature | Status | Notes |
| --- | --- | --- |
| Edit inputs after adding | Missing | Must remove and re-add |
| Style tab (colour, width, line style, per-plot visibility) | Missing | |
| Show/hide (eye) without removing | Missing | |
| Source input (open/high/low/close/hl2/hlc3/ohlc4/hlcc4) | Missing | Always close |
| Offset input | Missing | |
| Indicator on indicator (e.g. SMA of RSI) | Missing | |
| Multi-timeframe input (e.g. weekly SMA on daily chart) | Missing | Feasible: weekly and monthly candles are stored |
| Values in legend on crosshair | Missing | |
| Data window | Missing | |
| Panes share crosshair and time axis, and can be scrolled | Partial | Separate charts, one-way sync |
| Move pane up/down, merge into price pane, maximize/collapse | Missing | |
| Band/cloud fills | Missing | Needs primitives or custom series |
| Markers/dots output (PSAR, fractals) | Missing | |
| Colour-changing line (SuperTrend) | Partial | Exists only for RSI |
| Forward-shifted plots (Ichimoku spans) | Missing | Needs future time points |
| Indicator templates and favourites | Missing | |
| Save inputs as default | Missing | |
| Persistence across reloads/devices | Missing | |
| Categorised indicator browser | Partial | Flat list with search |
| Alerts on indicator conditions | Missing | App already has alert and email delivery |
| Pine Script / community scripts | N/A | Possible future: a restricted formula indicator (Phase 6) |

### Indicator catalog

ta4j column: the ta4j 0.26 class that implements it, or *compose* when it is a
short formula over existing ta4j helpers.

**Tier A: widely used; implement first**

| Indicator | Placement / output | ta4j |
| --- | --- | --- |
| Ichimoku Cloud | Price; 5 lines, cloud fill, +26 forward shift | `ichimoku/*` |
| Parabolic SAR | Price; dots | `ParabolicSarIndicator` |
| SuperTrend | Price; up/down coloured step line | `supertrend/SuperTrendIndicator` |
| Moving Average (type: WMA, HMA, VWMA, DEMA, TEMA, SMMA/RMA, LSMA, KAMA, ZLEMA, McGinley, VIDYA, ALMA) | Price; line | `averages/*`; ALMA is *compose* (not in ta4j) |
| MA Ribbon, MA Cross (with cross markers) | Price | *compose* |
| Envelopes | Price; bands | *compose* |
| Pivot Points Standard (Traditional, Fibonacci, Woodie, Classic, DeMark, Camarilla) | Price; per-period step levels | `pivotpoints/*`; Woodie/Classic/Camarilla are *compose* |
| Pivot Points High/Low | Price; labels | `RecentSwing*` / *compose* |
| Anchored VWAP with σ bands | Price; lines + fill | `volume/AnchoredVWAPIndicator`, `VWAPBandIndicator` |
| Zig Zag | Price; polyline + labels | `zigzag/*` |
| Williams Fractals | Price; markers | `FractalHighIndicator`, `FractalLowIndicator` |
| Williams %R | Panel | `WilliamsRIndicator` |
| Aroon (Up/Down, Oscillator) | Panel | `aroon/*` |
| Momentum, Rate of Change | Panel | *compose*, `ROCIndicator` |
| Bollinger %B, Bollinger BandWidth | Panel | `bollinger/PercentBIndicator`, `BollingerBandWidthIndicator` |
| Accumulation/Distribution | Panel | `volume/AccumulationDistributionIndicator` |
| Chaikin Money Flow, Chaikin Oscillator | Panel | `volume/ChaikinMoneyFlowIndicator`, `ChaikinOscillatorIndicator` |
| Historical Volatility | Panel | `LogReturnIndicator` + `StandardDeviationIndicator` |
| Volume with MA (on the existing volume pane) | Volume pane | `SMAIndicator` on volume |

**Tier B: complete the catalog**

| Indicator | ta4j |
| --- | --- |
| Awesome Oscillator, Accelerator Oscillator, Williams Alligator, Gator | `AwesomeOscillatorIndicator`, `AccelerationDecelerationIndicator`, `AlligatorIndicator`, `GatorOscillatorIndicator` |
| Ultimate Oscillator, True Strength Index, Chande Momentum, Fisher Transform, Connors RSI | `UltimateOscillatorIndicator`, `TrueStrengthIndexIndicator`, `CMOIndicator`, `FisherIndicator`, `ConnorsRSIIndicator` |
| KST, Coppock Curve, DPO, Price Oscillator (PPO), Volume Oscillator (PVO), TRIX | `KSTIndicator`, `CoppockCurveIndicator`, `DPOIndicator`, `PPOIndicator`, `PVOIndicator`; TRIX *compose* |
| Mass Index, Vortex, Choppiness Index, Chop Zone, Ulcer Index | `MassIndexIndicator`, `VortexIndicator`, `ChopIndicator`; Chop Zone *compose*; `UlcerIndexIndicator` |
| Elder Force Index, Ease of Movement, Klinger, NVI/PVI, Price Volume Trend | `volume/ForceIndexIndicator`, `EaseOfMovementIndicator`, `KlingerVolumeOscillatorIndicator`, `NVIIndicator`, `PVIIndicator`; PVT *compose* |
| Balance of Power, Bull Bear Power, Relative Vigor Index, SMI Ergodic, Woodies CCI | *compose* |
| Relative Volatility Index, Standard Deviation, Standard Error Bands, Volatility Stop, Chande Kroll Stop | *compose* / `statistics/*` |
| Linear Regression Curve, Slope, Channel | `statistics/SimpleLinearRegressionIndicator` |
| Correlation Coefficient, Ratio, Spread, Compare symbol (% overlay) | `statistics/PearsonCorrelationIndicator` + a second symbol's stored candles |
| 52-week High/Low, Median, Seasonality, Performance | *compose* |
| Auto Fib Retracement/Extension, Auto Pitchfork, Auto Trendlines | Reuse existing swing detection (`ContainedSwingHierarchy`, ZigZag) |
| Visible-range, fixed-range and periodic (weekly/monthly blocks) volume profiles | Reuse the existing volume-profile and anchored-profile code |
| Popular community scripts: Squeeze Momentum, Chandelier Exit, Schaff Trend Cycle | `SqueezeProIndicator`, `ChandelierExit*Indicator`, `SchaffTrendCycleIndicator` |

**Existing indicators that need TradingView-equivalent inputs**

| Indicator | Missing inputs |
| --- | --- |
| Stochastic | %K smoothing, %D period |
| Stochastic RSI | RSI length, stochastic length, K and D smoothing |
| MACD | Oscillator and signal MA type (EMA/SMA), source |
| Bollinger | MA type, source, offset, band fill |
| RSI | Smoothing MA with its own length. Should move into the generic pipeline (the backend already supports `RSI`) |
| VWAP | Anchored variant and σ bands; the rolling version can stay |
| Volume | MA overlay, colour by previous close vs open |

**N/A for this data set:** session VWAP, Volume Delta / Cumulative Volume Delta,
Net Volume and Up/Down Volume (need intraday ticks), Session Volume Profile,
Relative Volume at Time, Opening Range, Sessions, Technical Ratings (our Automated
Technical Outlook already covers this).

### Drawing tools

| TradingView group | Tools | Status |
| --- | --- | --- |
| Lines | Trend line, Ray, Extended line, Info line, Trend angle, Horizontal line, Horizontal ray, Vertical line, Cross line, Arrow | Missing |
| Channels | Parallel channel, Regression trend, Flat top/bottom, Disjoint channel | Missing |
| Pitchforks | Pitchfork, Schiff, Modified Schiff, Inside pitchfork, Pitchfan | Missing |
| Fibonacci | Retracement | **Partial**: fixed levels, no extend/reverse/labels options, no log-scale handling |
| | Trend-based extension, Fib channel, Time zone, Trend-based time, Speed resistance fan, Speed resistance arcs, Circles, Spiral, Wedge | Missing |
| Gann | Gann box, Gann square (fixed), Gann square, Gann fan | Missing |
| Patterns | XABCD, Cypher, ABCD, Three drives, Head and shoulders, Triangle pattern | Missing (automatic harmonic overlay exists) |
| Elliott | Impulse (12345), Correction (ABC), Triangle (ABCDE), Double combo (WXY), Triple combo (WXYXZ) | Missing (automatic overlay exists) |
| Cycles | Cyclic lines, Time cycles, Sine line | Missing |
| Forecasting/measurement | Long position, Short position | **Partial**: no account size, risk %, quantity, or outcome tracking |
| | Price range, Date range, Date and price range, Forecast, Bars pattern, Ghost feed, Projection | Missing |
| Volume-based | Anchored volume profile | **Partial**: single instance, a mode rather than a drawing |
| | Fixed range volume profile, Anchored VWAP | Missing |
| Shapes | Rectangle, Rotated rectangle, Circle, Ellipse, Triangle, Arc, Curve, Double curve, Path, Polyline, Brush, Highlighter, Arrow marker, Arrow mark up/down | Missing |
| Annotation | Text, Anchored text, Note, Anchored note, Callout, Comment, Price label, Price note, Signpost, Flag mark, Table | Missing |
| Decorative | Emojis, stickers, icons, image | Missing (lowest value) |

### Drawing workflow features

| TradingView feature | Status |
| --- | --- |
| Select/move drawings from the normal cursor | Missing (only works inside a drawing mode) |
| Properties dialog (style, text, coordinates, visibility per interval) | Missing |
| Price/time axis labels for the selected drawing | Missing |
| Magnet mode (snap to OHLC) | Missing |
| Stay in drawing mode toggle | **Partial** (every tool always stays armed; no one-shot default as in TradingView) |
| Measure (Shift+drag ruler) | Missing |
| Zoom-to-selection | Missing |
| Undo/redo (Ctrl+Z / Ctrl+Y) | Missing |
| Clone, copy/paste (Ctrl+drag, Ctrl+C/V) | Missing |
| Lock one/all, hide all, remove all | **Partial** (remove all only) |
| Z-order (bring to front / send to back) | Missing |
| Object tree (list, rename, hide, lock, reorder drawings and indicators) | Missing |
| Drawing templates and favourites toolbar | Missing |
| Keyboard shortcuts (Alt+T trend line, Alt+H horizontal, Alt+F Fib, …) | Missing |
| Drawings visible on all intervals | Missing (stored per interval) |
| Drawings on indicator panes (e.g. trend line on RSI) | Missing |
| Cloud sync across devices | Missing (`localStorage`) |
| Alerts on drawings (line/channel/rectangle crossing) | Missing |
| Touch support | Unverified; needs testing |

### Adjacent chart features (not requested; listed for completeness)

Chart types (Bars, Hollow candles, Heikin Ashi, Area, Baseline, Step line,
Columns, High-low; Renko, Kagi, Point & Figure and Line break later),
log/percent/indexed scale, compare symbols, date-range presets and go-to-date,
screenshot (`chart.takeScreenshot()`), fullscreen, and corporate-action or earnings
markers. **N/A:** intraday intervals, bar replay, multi-chart layouts, published
ideas.

## Architecture decisions

### 1. Upgrade Lightweight Charts to v5

Recommendation: move from 4.0.1 to the current v5 release. Do not stop at 4.2.

- **Native panes.** Indicator panels become panes of the price chart. This gives
  a shared crosshair, a single time axis, scrolling inside any pane, and
  move-up/down/merge for free. It removes the per-panel chart instances and the
  one-way `setVisibleLogicalRange` sync in `initCharts()`.
- **Series primitives.** These give drawings and fills proper rendering:
  z-order behind or in front of candles, price/time axis labels, autoscale
  participation, `hitTest` for hover cursors and selection without overlay
  pointer tricks, and redraws without custom `requestAnimationFrame` hooks.
- **Custom series.** Needed for band and cloud fills (Bollinger, Keltner,
  Ichimoku, anchored VWAP bands) and later for Heikin Ashi and other chart types.

v5 is a breaking API change: `addLineSeries()` and similar become
`addSeries(LineSeries, …)`, and `setMarkers` moves to `createSeriesMarkers`.
`StockWatchCharts` in `chart-theme.js` already wraps every `add*Series` call on
every chart page, so the migration can mostly happen in one place. Keep the
legacy method names on the adapter as a shim, including `series.setMarkers`
(about 30 call sites). Then migrate pages one at a time and remove the shim at the end.
Update the webjar version in `pom.xml` and every `th:src` that hard-codes
`4.0.1`.

### 2. Indicator registry and catalog endpoint

Replace the `switch` in `ChartTechnicalIndicatorService.calculateOne` with one
`IndicatorDefinition` per indicator, registered by type. Each definition declares:

- `type`, `category` (Trend, Momentum, Volatility, Volume, Moving averages,
  Levels, Statistics), default `placement` (PRICE / PANE / VOLUME);
- typed inputs: `int`, `double`, `bool`, `enum` (MA type, pivot type),
  `source` (OHLC variants or `{indicatorId}.{output}`), and `timeframe`. Each has
  a default, min, max, step and an i18n key;
- outputs: key, i18n key, and style (`LINE`, `STEP`, `HISTOGRAM`, `MARKERS`,
  `AREA`, `BAND` pair, `CLOUD` pair), with default colour, width and
  forward/backward shift;
- reference levels and optional zone colouring (generalising the RSI behaviour).

Add `GET /api/chart-indicators/catalog`. The frontend should build the menu,
search, category tabs and settings dialog from it. This replaces the hard-coded
`CHART_INDICATOR_SPECS`, the per-indicator `<button>` elements in `stock.html`,
and the MACD-specific validation. Server-side validation stays authoritative.

Request contract change: `parameters` becomes `Map<String, JsonNode>` (or
strings plus typed parsing) so enums and sources can be sent. Raise the
8-parameter cap to the per-definition input count. Keep the 32-indicator and
10k-point cache limits.

Inside a batch, resolve indicator-on-indicator sources in topological order and
reject cycles. For multi-timeframe inputs, compute on the stored weekly/monthly
candles and map each value onto the chart's bars as a step series. Only completed
higher-timeframe bars should be used, matching the existing completed-candle policy.

Forward-shifted outputs (Ichimoku) need future timestamps. Generate them from
the interval's trading calendar (business days for Daily; known exchange
holidays when available). Return them as whitespace points so the time axis
extends.

### 3. Drawing engine

Generalise `fibonacci-drawing-tool.js` into a small `chart-drawings` module,
shared by every page that mounts the tool today:

- **Model.** Each drawing is `{id, type, anchors: [{time, logical, price}],
  style, text, locked, hidden, intervals, z, paneId, version}`. This is a
  superset of the current anchor format, so the existing `v1` entries can be
  migrated on load.
- **Tool registry.** Each tool definition declares its category, icon, how it is
  placed (n clicks, click-drag, or freehand), default style, `hitTest`,
  `render` (via a primitive pane view), axis label views, handle drag rules, and
  properties-dialog fields. Fibonacci, Long and Short become the first three
  registered tools.
- **Interaction controller.** Keep the existing pointer state machine (draft →
  anchors → complete, handle drag, move). Add selection from Navigate mode via
  primitive `hitTest`, temporarily disable chart scroll while dragging,
  multi-click placement, Shift to constrain angles, magnet snapping to the
  nearest O/H/L/C, and stay-in-mode.
- **Command stack.** Model every mutation as a command so undo, redo, clone and
  paste come for free.
- **Cross-interval display.** Store anchors as timestamps. On Weekly and
  Monthly, map each anchor to the bar that contains it. Default visibility is all
  intervals, with an option to restrict it.
- **UI.** Replace the inline toolbar with a vertical, TradingView-style tool rail
  on the left of the price chart, with flyout groups and a favourites row. Add a
  floating properties bar for the selected drawing (colour, width, style, lock,
  delete, more) and a full properties dialog. Keep the current `aria-live`
  status line and keyboard behaviour (Esc, Delete).

### 4. Persistence

Add a Flyway migration (next free number, currently V86) with:

- `user_chart_drawing` (`user_id`, `symbol`, `drawing_id`, `payload jsonb`,
  `schema_version`, `updated_at`), with per-user and per-symbol row caps and a
  payload size cap;
- `user_chart_layout` (`user_id`, `scope` = global or symbol, `payload jsonb`)
  for active indicators, pane order and heights, chart type, and scale mode;
- `user_chart_template` (`user_id`, `kind` = indicator/drawing, `name`,
  `payload jsonb`).

Add CSRF-protected REST endpoints following the existing `/api/...` patterns,
with optimistic version checks. Keep `localStorage` as a write-through cache and
offline fallback. On first load, import existing
`stockwatch:fibonacci:v1:*` keys once.

## Phased plan

Sizes are relative (S < M < L < XL). Each phase ends releasable, with its own
docs entry like `frontend-design-implementation-2026-09-07.md`.

### Phase 0: Foundations (XL)

1. Upgrade Lightweight Charts to v5 behind the `StockWatchCharts` shim, then
   migrate every chart page. No visible change is intended. Regression-test
   markers, theme switching, history paging and every overlay. (L)
2. Indicator registry, catalog endpoint, typed parameters. Port the 19 existing
   indicators and RSI unchanged. Frontend menu and dialog generated from the
   catalog. (L)
3. Drawing engine refactor with Fibonacci, Long and Short ported, `v1` storage
   migration, and selection from Navigate mode. (L)
4. Server persistence for drawings and layouts, with `localStorage` fallback and
   import. (M)

### Phase 1: Indicator UX parity (L)

- Indicator panels become v5 panes: shared crosshair, scrolling, move up/down,
  merge into the price pane, maximise/collapse, resizable height (reuse the
  existing resize-handle behaviour).
- Legend rows per indicator with live values under the crosshair, plus eye
  (hide), settings (gear) and remove buttons.
- Settings dialog with Inputs, Style and Visibility tabs. Edit in place.
  Per-output colour, width, line style and visibility. "Save as default".
- Source and offset inputs on every indicator where TradingView offers them.
- Move RSI into the generic pipeline. Its zone colouring becomes a style option
  for any bounded oscillator. Add band fills.
- Add the missing inputs on Stochastic, Stochastic RSI, MACD, Bollinger and
  Volume (table above).
- Persist the active indicator layout. Indicator templates and favourites.
  Categorised browser with recently used.

### Phase 2: Indicator catalog (L, mostly backend)

- **2a** Tier A list. Needs the new `MARKERS`, `STEP`, coloured-line, `CLOUD` and
  forward-shift output styles.
- **2b** Tier B list.
- **2c** Indicator on indicator; multi-timeframe input.
- **2d** Second-symbol indicators (compare, ratio, spread, correlation), using
  stored candles of another symbol. Fetch the other symbol through the existing
  candle service so history is filled the same way.

### Phase 3: Drawing tool catalog (XL, frontend)

In order of expected use:

- **3a Lines.** Trend line, Ray, Extended line, Horizontal line and ray, Vertical
  line, Cross line, Arrow, Info line, Trend angle. Includes axis labels and
  extend-left/right options. (M)
- **3b Measurement.** Price range, Date range, Date and price range (bars,
  Δprice, %, volume in range), Shift+drag quick measure. Upgrade Long/Short with
  account size, risk %, quantity, and outcome shading from later candles. Add a
  "Create demo trade" action that pre-fills the existing demo-trade dialog. (M)
- **3c Channels and Fibonacci.** Parallel, Regression trend, Flat top/bottom,
  Disjoint channel. Fib upgrades: editable levels and colours, extend, reverse,
  price/percent labels, log-scale aware. Trend-based extension, Fib channel,
  Time zone, Trend-based time, Speed fan and arcs, Circles, Spiral, Wedge. (L)
- **3d Shapes and annotations.** Rectangle (with extend-right for zones), Rotated
  rectangle, Circle, Ellipse, Triangle, Arc, Curve, Path/Polyline, Brush,
  Highlighter, Arrow markers. Text, Anchored text, Note, Callout, Price label,
  Price note, Flag mark, Signpost. Text input must render through canvas
  `fillText`, never as HTML. (L)
- **3e Volume-based drawings.** Turn the anchored volume profile into a drawing
  that can have several instances. Add a fixed-range volume profile (two anchors;
  extend the existing endpoint with an end timestamp). Add an anchored VWAP
  drawing. (M)
- **3f Pitchforks and Gann.** Pitchfork family, Pitchfan, Gann box, Gann square
  (fixed), Gann fan. (M)
- **3g Pattern tools.** XABCD, Cypher, ABCD, Three drives, Head and shoulders,
  Triangle. Elliott impulse, correction, triangle, WXY and WXYXZ. Cyclic lines,
  time cycles, sine line. Show ratio labels and, as a differentiator, check a
  hand-drawn harmonic or Elliott count against the user's own detection rules
  from the harmonic/Elliott preference services. (L)
- **3h Low priority.** Forecast, Bars pattern, Ghost feed, Projection, Table,
  emojis and stickers. (M)

### Phase 4: Drawing workflow parity (L)

Object tree for drawings and indicators. Lock or hide one or all. Z-order.
Clone, copy and paste. Undo and redo. Drawing templates and favourites. Keyboard
shortcuts. Magnet weak/strong. Per-interval visibility. Drawings on indicator
panes. Touch interactions (long-press to place, larger handles). Keyboard access
to drawings through the object tree.

### Phase 5: Alerts on drawings and indicators (L)

Horizontal line, trend line, channel and rectangle crossing alerts. Indicator
condition alerts (crosses, crosses up/down, enters/exits a channel, above/below
a value). Evaluate server-side on completed candles, consistent with the current
alert policy. Deliver through Latest signals and email per the user's
notification settings. Depends on Phase 0.4 server-side drawings.

### Phase 6: Optional

- Adjacent chart features: chart types, log/percent scale, compare symbol,
  date-range presets, screenshot, fullscreen, data window, split/dividend
  markers.
- A restricted formula indicator as a safe Pine Script substitute. Parse to an
  AST over a whitelist of ta4j functions. Never evaluate user code.
- Automatic chart patterns (head and shoulders, triangles, wedges, flags,
  double tops). These need their own validated detector and backtest
  documentation, like the existing candlestick, harmonic and Elliott work, so
  they should get a separate research plan.

## Suggested milestones

| Milestone | Contents | Result for users |
| --- | --- | --- |
| M1 | Phase 0, Phase 1, Phase 2a, Phase 3a–3b | TradingView-like panes and legends, editable and persistent indicators, the most-used missing indicators, lines and measuring tools |
| M2 | Phase 2b, Phase 3c–3e, Phase 4 | Full Fibonacci/channel/shape toolset, object tree, undo, templates |
| M3 | Phase 2c–2d, Phase 3f–3h, Phase 5 | Pattern tools, MTF and compare indicators, alerts |

## Testing and verification

- **Indicator math.** One JUnit test per definition on a fixed OHLCV fixture, with
  reference values. Cover warm-up NaN suppression and parameter validation. Where
  TradingView's formula differs from ta4j (smoothing choice, population vs sample
  σ, first-value seeding), document the difference and either match TradingView
  or label ours clearly.
- **Catalog contract.** Every definition has i18n keys in both locales. Defaults
  are within bounds. Outputs have a supported style.
- **Drawing geometry.** `node --test` with jsdom for anchor math, hit-testing,
  snapping, cross-interval anchor mapping, serialisation and `v1` migration, and
  the undo/redo command stack.
- **Browser flows.** Playwright, as in `scripts/check_watchlist_ui.cjs`: place,
  select, drag, edit, delete, undo, persist and reload for each tool group, on
  the stock page and the detail pages. Both themes, 390px and 1440px widths.
- **Existing suites.** `node --test src/test/js/*.test.cjs` (including the i18n
  guard), `FrontendSecurityTest` for CSP, and the template tests listed in the
  2026-09-07 implementation doc.
- **Performance budgets.** 30 indicators and 200 drawings at 1,000+ candles must
  pan and zoom without dropped frames on a mid-range laptop. Indicator batch
  latency stays within the current cache behaviour.

## Risks

- **v5 migration** touches every chart page. Mitigate with the adapter shim and
  one PR per page group.
- **Forward-shifted plots** rely on a trading calendar. Unknown holidays can
  misplace future points by a bar. Label projected areas as approximate.
- **Cross-interval drawings** on Weekly/Monthly can look slightly different from
  Daily because anchors snap to containing bars. This matches TradingView's
  behaviour; document it.
- **Scope.** TradingView's catalog is large. The Tier A indicators and the 3a–3d
  tools cover most real use. Phases 3h and 6 should only start if usage data
  asks for them.
- **Storage growth** from server-side drawings. Enforce row and payload caps per
  user and per symbol, and soft-delete with cleanup in line with existing
  retention jobs.
