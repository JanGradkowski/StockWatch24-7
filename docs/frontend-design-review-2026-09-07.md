# Frontend design review — 7 September 2026

Proposal only. No application changes were made for this review.

Subsequently approved and implemented: see the [implementation and validation record](frontend-design-implementation-2026-09-07.md). The proposal below records the original review.

Reviewed the current 24 page templates, 10 shared fragments, shared stylesheet, theme switching, chart rendering and primary navigation/form flows. This includes the preceding copy cleanup. Browser discovery returned no connected browser; observations below come from source inspection, not current screenshots. Colour calculations use specified solid colours and are not a substitute for testing rendered gradients, transparency, hover states and chart backgrounds.

**Recommended direction**

Keep StockWatch's existing font stack, lime brand accent, sidebar and chart-focused identity. Improve the hierarchy: readable data, quieter surrounding surfaces, consistent controls and fewer competing elements. Light mode should feel like a clean research application; dark mode should feel like a comfortable chart workspace. Neither needs a new brand or a new frontend framework.

The strongest existing foundations are the persistent sidebar, stock search, sortable archives, report tabs, chart legends, expandable methodology and portfolio period summaries. Refine these components instead of adding more navigation or explanatory panels.

**Source-confirmed findings, in priority order**

| Priority | Finding | Evidence | Proposed change |
| --- | --- | --- | --- |
| P0 | Theme changes do not consistently reach charts. | `theme.js:83` emits on `window`; `technical-outlook.html:1296` and `virtual-trade.html:238` subscribe on `document`. | Use one shared chart-theme event contract. Update existing charts in place, preserving zoom, drawings, indicators, selected tabs and sliders. Avoid the currently intended full reload on the demo-trade report. |
| P0 | Several charts retain dark-oriented colours in light mode. | `technical-outlook-change.html:154–183` has fixed axis, grid, candle and overlay colours. `stock-workspace.js:125`, `1190`, `3270–3326` has fixed indicator, histogram and historical highlight colours. Historical Elliott and harmonic charts read the theme when created but do not subscribe to subsequent changes in those templates. | Share semantic chart palettes across every chart. Update all series, guides, annotations and legend swatches when the theme changes, including already-created historical overlays. Preserve user-selected overlay colours, with readable labels and outlines where needed. |
| P1 | Secondary financial data is too small. | `style.css:3322` sets tags to `.55rem`; `3375` company/date details to `.58rem`; `4516` sort labels to `.56rem`; `4865` result explanations to `.48rem`. These are approximately 8–9px at a 16px root. | Keep the font family. Use 14–16px for normal interface text and 12–13px for secondary labels. Reflow columns and reduce low-priority content rather than merely increasing every size inside the same grid. |
| P1 | Some text and chart colours have weak contrast. | Dashboard headings use `#656c64`; this is about 3.60:1 against the dark surface token `#0b0d11`. Outlook-change axis text `#98a2b3` is about 2.55:1 against light surface `#fbfffa`. Its mint overlay `#64e8bd` is about 1.50:1 against that surface. These are indicative solid-pair checks. | Assign explicit text, border, focus and chart colours for each theme and validate the actual rendered combinations. The general muted-text tokens already give roughly 5.3–5.6:1 on the sampled surfaces; fix exceptions instead of indiscriminately darkening or brightening everything. |
| P1 | The interface spends too much visual emphasis on containers. | The approximately 400KB stylesheet contains 213 gradient expressions, repeated card shadows, global background grids, animated ambient backgrounds and 16 backdrop-filter declarations. These counts describe source complexity, not simultaneous effects or measured performance. | Use mostly solid surfaces in authenticated pages. Reserve pronounced gradients and ambient decoration for public landing content. Give the chart, price and selected action more prominence than the surrounding cards. |
| P1 | Light mode has overlapping definitions. | Broad light token sets begin around `style.css:13519` and `13980`, followed by component overrides. The later palette applies a green tint to most surfaces. | Establish one authoritative palette per theme. Use neutral light surfaces with restrained green accents. Consolidate only components being redesigned, preserving cascade order and checking visual differences. |
| P1 | Lists compete with themselves for attention. | Home's latest-signal grid contains pattern, family, direction, company, interval, date, research horizon, status and score. Several values have badges or supporting text. | Lead each row with ticker and signal, followed by interval, status and outcome/score. Keep research horizon and extra timestamps in expanded details. Establish one consistent reading order across archives and dashboard lists. |
| P2 | Sorting has duplicate visible controls. | Technical and ticker-alert archives have clickable column headers plus Sort by, Order and Apply controls. | Make headers the primary desktop sorting interface. Retain a compact sort control for mobile, where headers disappear. Keep filtering separate and preserve URL state and server-side ordering. |
| P2 | Stock workflows require several different control patterns. | `stock.html` combines three major tabs, a chart toolbar, indicator menus, following stars, mixed save behaviour and ten dialogs, including a multi-step historical scan. | Group chart actions predictably; simplify the historical scan to one form containing interval, candle count and display choice. Make save state visible where alert rules are edited. |
| P2 | Layouts repeat navigation and instructions at several levels. | Global sidebar, header, floating back control, breadcrumbs, page tabs and large section headers coexist. Settings also has eight subsection links; sidebar-era CSS already converts these to wrapping tabs. | Give each navigation layer a clear purpose. Keep global destinations in the sidebar, local report views in tabs and a single contextual back/breadcrumb area. Group settings links by Account, Alerts and Analysis without adding a second permanent sidebar. |

**Proposed visual foundations**

These colours are starting values for a rendered comparison, not final replacements for every existing colour.

| Role | Dark mode | Light mode |
| --- | --- | --- |
| Page background | `#0B1017`, a slightly softer near-black | `#F4F6F8`, a neutral pale grey |
| Main surface | `#151C26` | `#FFFFFF` |
| Raised/selected neutral surface | `#1C2633` | `#EDF1F5` |
| Main text | `#E8EDF3` | `#182230` |
| Secondary text | `#94A3B8` | `#526174` |
| Brand accent | Retain lime `#B7F34A` | Retain a darker green such as `#477A12` |

Use the brand accent for primary actions and navigation selection. Use separate positive, negative, warning and neutral tokens for financial state. A green navigation item must not be confused with a positive return. Pair financial colours with signs, direction labels and status text. Keep blue/violet/amber chart colours distinct from candle direction colours, with line styles where series overlap.

Keep the existing Inter/system font stack. Do not introduce a new font download. Use a small type scale: approximately 28–32px page headings, 18–20px section headings, 14–16px controls and body text, and 12–13px secondary information. Apply tabular numbers to aligned prices, percentages and timestamps. Use sentence case for functional labels and fewer widely spaced uppercase captions. Limit long explanations to a comfortable reading width.

Use a consistent spacing scale of 4, 8, 12, 16, 24 and 32px. Aim for 12–16px card corners and 8–10px controls. Ordinary panels need a subtle border and little or no shadow; menus and dialogs need stronger separation. Avoid putting a decorated card inside another decorated card unless they represent separate tasks.

Standardize primary, secondary, text and destructive actions, including their heights, icons, loading states and focus outlines. A page may have multiple actions, but only the action most relevant to its current task should dominate. Keep the current SVG icon style and provide labels for unfamiliar controls.

**Changes by page family**

| Pages | Suggested treatment |
| --- | --- |
| Home | Shrink the greeting area so recent signals and the watchlist start higher. Move low-priority monitoring explanations into expandable help. Use a quiet unread marker rather than multiple competing highlights. Keep broad subscription actions such as following 200 stocks secondary to searching for an individual stock. |
| Technical signals and company signal archive | Use the same readable row structure, clear active sort arrow, aligned numeric columns and predictable row expansion. Keep ticker search and state filters together. Use compact mobile cards with explicit field labels; secondary details expand within the row. |
| Ticker alerts dashboard and all ticker alerts | Keep Insider and Congress visibly distinct with labelled source chips. Separate unread notifications from historical activity. Make the difference between transaction date and filing date clear within the relevant row or detail. Reuse archive sorting and row styling. Ticker/source filters would be useful, but require a separate backend filtering increment if not already supported. |
| Company alert history | Present active rules as a readable interval-by-direction overview with clear followed states. Put company identity and an Open chart action first. Use the same rule indicators as the stock page. |
| Stock page | Put company, ticker, price and quote timestamp together. Reduce the large demo-trade promotion to a compact action area. Use explicit local names such as Chart, Technical analysis and Ticker alerts. Group toolbar controls into interval, chart type, indicators and drawing tools. Show active indicators near the chart with the existing visibility controls. Keep the chart dominant and lower panels collapsible. |
| Stock history dialogs | Combine interval, lookback and chart/table choice in one dialog. Show the estimated date range before running the scan. Keep the existing historical-results limitations available below the controls. This reduces repeated opening and closing of dialogs. |
| Technical Outlook | Keep the existing report tabs. Lead with classification, score, interval and data timestamp, then the chart and strongest supporting/conflicting evidence. Put all indicator readings and formulas in the appropriate report section. Make demo-trade actions compact. |
| Saved outlook change | Put the previous and current outlook beside each other, with the change clearly labelled. Lead the report with what changed. Retain the new indicator legend, improve its light-theme palette, and reuse the same chart controls as other reports. |
| Signal detail and historical candlestick, Elliott and harmonic reports | Keep Chart, Report and Results as consistent views. Use the same location for ticker, direction, status, score and interval. Present entry, stop and target in one compact group where applicable. Keep family-specific geometry and wave evidence available without displaying every technical section as equally prominent. Separate setup score from measured return visually and in labels. |
| Activity signal detail | Lead with person, company, transaction and date. Treat technical indicators as secondary context. Reuse the report/chart/result structure but preserve the distinction between a filing and a technical signal. |
| Demo trading | Keep portfolio value and all four return periods visible, as previously requested. Make period selection unmistakable; show the selected period once above the top/bottom rankings. Use aligned, compact rankings and quieter accounting notes. Distinguish holdings from sell decisions. Keep currency totals separate. |
| Demo-trade detail | Put open/closed state, entry, current or exit price, amount and result in a compact summary. Keep the price chart prominent. Use consistent before/after columns in indicator comparisons and reveal unchanged indicators on demand. Closing and deleting must remain visibly different actions. |
| General and Appearance settings | Group account security and appearance clearly. Keep the step-based password and MFA flows. Use a small real chart preview for colour choices, including what selected overlays look like in both themes. System theme could be added later as an explicit preference with defined account-versus-device behaviour. |
| Analysis, detection, scoring and pattern settings | Use grouped sections, readable labels, visible units and consistent numeric inputs. Reuse the existing pattern search where present. Show a saved/unsaved state and a compact Apply/Discard area on long forms. Mark changed values, keep reset actions secondary and retain all validation and defaults. Clarify the existing immediate-save versus Apply behaviour before considering any change to that behaviour. |
| Landing and About | Keep more brand expression here: restrained lime accents and a small number of purposeful visuals. Prefer screenshots of real features in both themes to conceptual illustrations when explaining functionality. Keep one main sign-up action. About already has expandable rules; improve spacing and navigation around them. |
| Sign in, signup, two-factor, recovery, verification and deletion-cancellation pages | Give the form priority, keep widths and field spacing consistent, and reduce the supporting marketing panel on narrow screens. Keep security requirements and recovery steps visible. Place errors beside the relevant field as well as in any summary. |

**Interaction and responsive improvements**

Keep the page horizontally contained at every breakpoint. Charts may pan internally as they do now; this must not move the document sideways. Reflow archives into labelled cards on narrow screens and reduce decorative spacing before shrinking text. Test compact desktops with the sidebar both expanded and collapsed, rather than treating screen width alone as available content width.

Use component-sized loading placeholders that reserve chart and list space. The current stock page starts with an elaborate full-page loader; an ordinary shell with a loading chart would make search and navigation available sooner. Show separate initial-loading, refreshing, no-data and failed states. Keep usable existing data visible during refresh, with its timestamp and a retry action if refresh fails.

Give icon buttons comfortable targets, tooltips that also work on focus, and obvious selected states. Preserve the right-click Fibonacci cancellation; make its active drawing state apparent and keep a visible way to leave the tool for touch users. Keep keyboard focus visible and return it correctly after dialogs close. Existing reduced-motion rules are a useful foundation; preserve them and reduce decorative movement in ordinary use.

Accessibility acceptance targets: 4.5:1 for ordinary text, 3:1 for large text and essential graphical/control distinctions, with more contrast for small chart labels where practical. WCAG 2.2's minimum target-size criterion is 24 by 24 CSS pixels with exceptions; prefer roughly 40–44px for frequently used icon controls and mobile actions. Colour should not carry meaning alone. Validate zoom, reflow, keyboard use and focus visibility against [WCAG 2.2](https://www.w3.org/TR/WCAG22/) and its [contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum/).

**Implementation order after approval**

1. Establish current screenshots and interaction baselines for populated, empty, loading and error states. Use isolated demo data, not changes to the user's account. Include both themes, desktop, tablet and mobile.
2. Fix chart theme propagation and centralize chart palettes. Verify that changing themes preserves chart state. These are concrete defects independent of the broader visual direction.
3. Apply the proposed typography, surfaces and shared controls to three representative screens: Home, All signals and Stock. Compare light and dark variants before extending the same rules sitewide.
4. Extend the shared components to reports, portfolio, settings and public/authentication pages. Simplify dialogs and control grouping as separately reviewable changes.
5. Validate every page family at 360, 390, 768, 1024, 1440 and 1920px widths, including 200% zoom and 320 CSS-pixel reflow. Check document overflow, readable labels, long company names, missing values, keyboard use, dialogs and light/dark chart contrast. Run relevant template and interaction tests and build checks.

Use the current Thymeleaf/CSS/JavaScript stack. Centralize shared visual decisions and small reusable components; avoid a framework migration or a blanket stylesheet rewrite. Keep stylesheet order and existing responsive rules under review as each component changes. The preceding font/overflow regression makes screenshot comparison a necessary part of execution. Template tests alone cannot demonstrate visual consistency.

Optional later additions: compact/comfortable list density, a system-following theme preference and a portfolio value-history chart. The latter needs suitable historical portfolio valuations; it must not be drawn from the four existing period summaries. These are not prerequisites for the proposed visual improvements.
