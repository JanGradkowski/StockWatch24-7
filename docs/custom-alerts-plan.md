# Custom alerts: design plan

Date: 2026-10-16

Status: implemented on the `custom-alerts` branch (October 2026). The design below is kept as written;
[Implementation notes](#implementation-notes) at the end list where the build differs from it.

## The idea in one paragraph

A **custom alert** is a rule the user builds from conditions — "RSI(14) is below 30",
"a bullish Elliott Wave II end was detected in the last 5 candles", "two or more insiders
bought at least $250k in the last 30 days" — joined with **all / any** logic. The user saves
it once and **applies** it to individual stocks or to whole watchlists. Every completed candle
the app checks each applied stock; when the rule becomes true, it records a **trigger**,
shows it on the dashboard, in the watchlist and in All signals, and can email it. Clicking a
custom alert anywhere opens All signals filtered to that alert. Every trigger explains itself
with a checklist of which conditions matched and by how much.

This plan covers the product design, the screens, the condition model, how evaluation fits
the existing scheduler, the data model, limits, and a phased delivery. It also lists problems
in what exists today that this feature touches and should fix first.

## Constraints that shape the design

- Candles are Daily, Weekly and Monthly only. Checks run on completed candles at the
  existing schedule (`alerts.schedule.*-cron`: daily 00:00 Tue–Sat, weekly Saturday,
  monthly on the 1st, Europe/Brussels), with durable jobs and missed-run recovery
  (`AlertCheckJobStore`, `ScheduledAlertService`). Custom alerts must use the same jobs, not
  a parallel scheduler.
- Indicators already exist as a typed catalog of ~100 definitions with validated inputs,
  named outputs and a `timeframe` input (`ChartIndicatorCatalog`,
  `ChartTechnicalIndicatorService`). Chart alerts (`ChartAlertService`, V87) already compare
  an indicator output with a value, the price, another output or a channel. Custom alerts
  reuse both instead of inventing a second indicator system.
- Pattern detection results depend on **per-user preferences** (candlestick, Elliott,
  harmonic and scoring preferences). Two users' "bullish engulfing" can differ.
- Insider filings are polled per followed ticker (daily, API Ninjas, latest 10 filings per
  check); congressional disclosures come from a shared six-hourly poll with a 90-request
  daily budget. Both have per-user follow limits (50 each by default). A custom alert must
  not call providers during evaluation, and must not quietly exceed these budgets.
- Monitoring capacity is limited: `ALERTS_MAX_TRACKED_STOCKS_PER_USER` (300) and
  `ALERTS_MAX_GLOBAL_TRACKED_STOCKS` (500).
- Everything is bilingual (`messages.properties`, `messages_pl.properties`), supports
  learning mode, light/dark themes, CSP without inline handlers, and keyboard use.
- Rendering must stay cheap on weak GPUs: no charts on list pages or the dashboard, fixed
  chart heights where charts appear, bounded repaint areas.
- Insider and congressional conditions apply to stocks only; indexes and ETFs are skipped,
  as with today's activity follows.

## What we have today, and what to fix first

These are verified in the current code. Phase 0 fixes them because custom alerts build on
the same paths.

| # | Area | Problem | Where | Fix |
| --- | --- | --- | --- | --- |
| 1 | Chart alerts | A chart alert on a symbol/interval with **no pattern follows** is queued for checking but never evaluated: the check returns early when there are no `alert_rules`, before `evaluateChartAlerts` runs. Such alerts silently never fire. | `ScheduledAlertService.java:465-468`, `:577-582` | Run chart-alert (and later custom-alert) evaluation before the early return. Add a test with a chart alert and no rules. |
| 2 | Dashboard counts | "Unread signals" and the Latest signals count show the size of a list already cut to 8 items, so they never exceed 8. | `AuthController.java:266-274`, `home.html:42`, `home.html:589` | Count unread technical, outlook, chart and custom triggers in SQL; keep the list at 8. |
| 3 | Chart alert history | A chart-alert trigger appears only in the dashboard's unread Latest signals. Once read it is gone from every page, although the empty state promises "Signals you have opened stay in All signals". | `AuthController.java:263-265`, `SignalArchiveQuery` has no chart-alert source | Add chart-alert triggers to the All signals archive as their own type. |
| 4 | Chart alert email | Chart-alert emails follow only the shared "Enable signal emails" switch (through the misleadingly named `allowsTechnicalOutlookEmail`), so they cannot be switched off without stopping every signal email. | `AlertNotificationService.java:1605` | Give chart alerts (and custom alerts) their own choice under *New activity* in the email settings. |
| 5 | Archive filtering | The archive filter has state, ticker and watchlist only. With eight signal types it needs a **type** filter. | `SignalArchiveFilter.java:6` | Add a multi-select type filter (Candlestick, Elliott, Harmonic, Outlook, Congress, Insider, Chart alert, Custom alert). |
| 6 | Navigation | Sidebar links have no unread counts; users must open each page to learn whether anything is new. | `fragments/app-sidebar.html` | Add small unread badges to Technical signals, Ticker alerts and Custom alerts. |
| 7 | Vocabulary | "Alert" currently means four things: Alert settings, Ticker alerts, chart alerts and signal follows. | Sidebar, dashboard, settings | Settle the words below and manage chart alerts from the Custom alerts page. |

Proposed vocabulary, used in every string of this feature:

- **Signal** — something the app detected (a pattern, an outlook change, a filing).
- **Follow** — subscribing a watchlist member to built-in signals (what exists today).
- **Custom alert** — the user's own rule. It is reusable: the user's "template".
- **Apply** — attach a custom alert to stocks or watchlists.
- **Trigger** — one time a custom alert's rule became true for one stock on one candle.
- **Chart alert** — a custom alert created from a chart drawing or indicator; it stays
  bound to that one stock and appears in the Custom alerts page under its own tab.

## Where the feature lives

### Sidebar

Add **Custom alerts** to the *Monitor* group, directly under Watchlists, with an unread
badge (see fix 6):

```
Home
MONITOR
  Watchlists
  Custom alerts        3     <- new, unread trigger count
  Followed outlooks
SIGNALS
  Technical signals   12
  Ticker alerts        2
PRACTICE
  Demo trading
```

It belongs under Monitor because, like a watchlist, it is something the user sets up and
the app then watches. Icon: a bell with a small spark/sliders, distinct from Ticker alerts'
plain bell. When the user is on a custom-alerts page, up to five pinned or most recently
triggered alerts appear as shortcuts below the link, like watchlist shortcuts today.

### Dashboard (`/home`)

A new **Custom alerts** panel sits between *Latest signals* and *Your watchlists*:

- Heading: kicker "Your rules", title "Custom alerts" with the unread count, actions
  **New custom alert** (primary) and **All custom alerts**.
- **Latest triggers** — up to five unread triggers, using the existing
  `latest-signal-row` grid so the dashboard stays visually consistent: alert name with a
  *Custom alert* family tag and a "3/3 conditions" tag; ticker and company; watchlist
  origin; interval; candle date with "Recorded HH:mm"; status pill *Triggered*; and, in the
  score column, the alert's strongest evidence value (e.g. "RSI 27.4"). Clicking opens the
  trigger detail. **Mark all as read** applies to custom triggers only.
- **Your alerts** — a compact strip of up to six alert cards: name, Active/Paused dot,
  "Daily · 2 watchlists · 57 stocks", **Matching now: 4**, unread badge. Clicking a card
  opens All signals filtered to that alert. No charts, no sparklines.
- Empty state (no alerts yet): three starter-template cards ("Oversold bounce", "Insider
  cluster buy", "Elliott Wave 3 setup") with **Use this template** buttons, plus "Build
  from scratch".

Custom triggers do **not** also appear in Latest signals, to avoid showing the same event
twice. The metric cards gain **Unread custom alerts** (linking to the Custom alerts page).

### Custom alerts page (`/custom-alerts`)

The management hub.

- Hero: eyebrow "Monitor", title "Custom alerts", one-line explanation, buttons
  **New custom alert** and **Templates**.
- Tabs: **All · Active · Paused · Chart alerts**, a search box, and sort (Last triggered,
  Name, Most matches, Unread).
- One row per alert (a card on phones):
  - Name and optional description.
  - **Rule summary**: the conditions as readable chips joined by `AND` / `OR` badges, e.g.
    `RSI(14) < 30` **AND** `Elliott Wave II end ↑ · last 5` **AND** (`Insider buy ≥ $250k ·
    30d` **OR** `Congress buy · 45d`). Long rules collapse to the first three chips and
    "+2 more".
  - Interval chip (Daily / Weekly / Monthly) or "On new filings" for activity-only alerts.
  - **Applied to**: watchlist chips ("Tech Giants · all 42", "Swing ideas · 3 stocks").
  - **Matching now**: count of applied stocks whose rule is currently true.
  - Triggers: total and unread; last triggered date.
  - Controls: Active switch, email icon toggle, and a row menu (Edit, Duplicate,
    Duplicate as weekly/monthly, Apply to…, Show in watchlists, Delete).
- **Clicking the row opens All signals for that alert** (`/signals?customAlertId={id}`),
  as requested. Edit is an explicit button so a click never lands in an editor by accident.
- Chart alerts tab: today's chart alerts, finally manageable in one place (fix 3/7): symbol,
  drawing or indicator, condition, frequency, Active switch, **Open on chart**, Delete, and
  **Add conditions** which converts it into a full custom alert (see *Chart alerts* below).
- Empty state: the starter-template gallery.

### Watchlists

Opening a watchlist (`/watchlists/{id}`) shows a **Custom alerts** strip above the member
table:

- One card per custom alert applied to this list: name, "All members" or "3 of 42
  members", **Matching now**, unread count, Active switch.
- Clicking a card opens `/signals?customAlertId={id}&watchlistId={listId}` — that alert's
  triggers within this list.
- **+ Apply custom alert**: a picker of the user's alerts (with "Matching now in this list"
  shown for each) and **Create new for this list**, which opens the builder with this list
  pre-selected.

Member table:

- The *Following* column gains a **Custom** chip group per member, in a new chip tone
  (`wl-type-custom`), listing the alerts that cover it: `★ Oversold bounce · D`.
- *Manage follows* for selected members gains a **Custom alerts** section with a checkbox
  per alert, so the user can apply or remove an alert for a subset of members.

Watchlist editor:

- *Allowed signal types* gains **Custom alerts** (requires extending the
  `watchlist_disallowed_signals` check constraint). Disallowing it removes this list's
  custom-alert targets after the usual preview.
- The list's **Enable email notifications** switch does not gate custom alerts; each custom
  alert has its own email switch. Reason: a custom alert is an explicit, user-built
  subscription, and two switches in two places for the same email would be confusing.

### Stock page (`/stock/{symbol}`)

In the follow panel, below the built-in follows, a **Custom alerts** section:

- Each of the user's alerts with its live state for this stock ("Matches now" / "Not
  matching" / "Not enough history") and a checkbox **Apply to {symbol}**.
- Checking boxes joins the existing **Apply changes** draft; saving uses the existing
  watchlist picker ("which watchlists should receive this?"), with existing memberships
  preselected and inline list creation. This keeps one rule across the app: monitoring
  lives in watchlists.
- **Create custom alert for {symbol}** opens the builder with the stock as the test ticker
  and target.

### All signals (`/signals`)

- New filter parameter `customAlertId`. With it, the archive header becomes the alert's
  header: name, rule summary chips, interval, "Applied to" chips, Active switch, **Edit**,
  and a small performance summary (see *Trigger outcomes*). Works together with
  `watchlistId`, `ticker` and `state`.
- Custom triggers are rows like other signals: *Signal* = alert name with tags
  `Custom alert` and `3/3 conditions`; *Ticker*; *Interval*; *Received*; *Status* =
  `Triggered` or `Matched when applied`; *Setup score* = "N/A · custom"; *Trade outcome* =
  the forward return after the alert's outcome horizon ("+3.2% after 10 candles", or
  "Pending").
- States: custom triggers count as **Completed** once the outcome horizon has passed and
  **Active** before that; **Unread** as usual.
- The new type filter (fix 5) includes *Custom alert* and *Chart alert*.
- Deletion, mark-all-read and watchlist scoping work as for other types; watchlist origins
  are captured at insert time like the other four kinds.

### Trigger detail (`/custom-alerts/triggers/{id}`)

The page that makes the feature trustworthy:

1. **Header**: alert name, ticker and company, interval, candle date, *Triggered* pill,
   watchlist origins, Mark unread, **Open stock**, **Practice trade** (opens the demo
   trading form prefilled), **Edit alert**, **Stop for this stock**.
2. **Why it triggered**: the rule as a checklist, each line with a pass mark, the observed
   value against the threshold, and when it happened:
   - `RSI(14), Daily — 27.4, below 30 — on 14 Oct 2026` ✓
   - `Bullish Elliott Wave II end — wave ended 7 Oct, detected 10 Oct — score 74` ✓
     **View pattern**
   - `Insider purchases — 2 insiders, $1.42M, last 30 days` ✓ **View filings**
   - In an *any* group, the matching branch is marked and the others are shown greyed with
     their own values, so users learn how close the alternatives were.
3. **Chart**: the existing chart workbench at a fixed height with markers for the trigger
   candle, each pattern's detection candle and each filing date, and one indicator pane per
   indicator condition with its threshold drawn as a level line.
4. **What happened next**: forward returns after 1, 5, 10 and 20 candles, filled in as
   candles complete; informational, not a trade result.
5. **Rule as of this trigger**: if the alert was edited later, a note "This alert has
   changed since this trigger" with the old rule text, because history must not be rewritten.

### Builder (`/custom-alerts/new`, `/custom-alerts/{id}/edit`)

A full page, not a modal: rules can be long and the preview needs room. Layout follows the
watchlist editor: wide two-column on desktop, stacked on tablet and phone, with **Save** and
**Cancel** pinned in a footer that stays visible while content scrolls.

**Left column — four sections in one scrolling form**

1. **Name** — name (required, 1–80 chars) and optional description. Name suggestions are
   generated from the first conditions ("RSI < 30 + Elliott II") until the user types one.
2. **Conditions**
   - "Check after every **[Daily ▾]** close" — the alert interval.
   - "Trigger when **[all ▾]** of these are true".
   - A list of condition rows. Each row reads as a sentence with inline controls, e.g.
     `[RSI] (14) on [Daily] [is below] [30] [on the latest candle ▾]`.
     Row controls: *is / is not* toggle, duplicate, delete, move up/down (no drag-only
     reordering). Invalid fields are outlined with a message under the row.
   - **+ Add condition** opens a searchable picker grouped by category (below), with a
     "Popular" row at the top: RSI level, Moving-average cross, Volume spike, Candlestick
     pattern, Elliott wave, Harmonic pattern, Insider buying, Congress buying.
   - **+ Add group** turns the flat list into groups, each with its own all/any switch:
     "Trigger when **all** of these groups are true: (A and B) and (C or D)". Two levels
     are enough for real rules and stay readable; deeper nesting is not offered.
   - With a test ticker chosen (right column), every row shows a small live readout at its
     end — `AAPL now: 41.2 ✗` — so users can calibrate thresholds while typing.
3. **When to trigger**
   - **When the rule becomes true** (default): fires on the candle where the rule turns
     from false to true; it re-arms after a candle where the rule is false.
   - **Once per stock**: fires once for each stock, then stops for that stock until the
     alert is edited or re-armed by the user.
   - **Every candle while true**: fires on each completed candle where the rule holds,
     with a warning that it can be noisy.
   - **Quiet period** (optional): "Don't trigger again for the same stock within
     **[10]** candles".
   - **Outcome horizon**: "Track the price for **[10]** candles after each trigger" (used
     for *Trade outcome* and performance stats).
4. **Apply to**
   - Watchlists: checkbox list of the user's lists, each with **All members** (default,
     follows future membership changes) or **Choose members…**.
   - Stocks: ticker search; each chosen stock asks which watchlist(s) it belongs in, using
     the existing picker (existing memberships preselected, inline list creation).
   - "Also trigger for stocks that already match when I save" — off by default. When on,
     current matches create triggers labelled *Matched when applied* (no back-filled history).
   - Capacity and data notes appear here (see right column).
5. **Notify**: **Email me** switch (on by default; the account-level email pause and the
   server email switch still apply), and **Digest**: "When several stocks trigger in the
   same check, send one email" (on by default).

**Right column — sticky Preview**

- **Summary**: the rule written as one plain-language sentence, regenerated live. This is
  also what emails and the trigger page use.
  > Checked after every daily close. Triggers when RSI(14) is below 30 at least once in the
  > last 3 candles **and** a bullish Elliott Wave II end was detected in the last 5 candles
  > **and** either insiders bought at least $250,000 in the last 30 days **or** a member of
  > Congress bought in the last 45 days.
- **Test on**: a ticker search (defaults to the first applied stock). Shows each
  condition's current pass/fail, and a thin two-year **history strip** (SVG ticks on a
  line, no chart library) of when the rule would have triggered, with "6 triggers in 2
  years · median +3.8% after 10 candles". Clicking a tick shows that date's checklist. A
  "Show on chart" link opens the stock chart with markers; the builder itself draws no chart.
- **Matching now**: "3 of 42 applied stocks match right now — NVDA, AMD, AVGO", from
  cached candles only. Stocks without cached history are listed as "not checked yet"
  instead of triggering provider requests.
- **Capacity**: "Applies to 42 stocks · 9 new to monitoring · 257 of 300 used". If the rule
  uses insider or congressional data: "Needs insider data for 42 stocks · 38 of 50 insider
  slots free" with a blocking error when over the limit and the remedies (fewer stocks,
  remove the condition, or raise the configured limit).
- **Warnings**: ETFs/indexes skipped for activity conditions; "SMA(200) needs 200 candles —
  4 stocks don't have enough history yet"; repainting indicators not available (below).

The preview is one debounced request (600 ms after the last edit), bounded server-side to
one test ticker's history and at most 50 targets for *Matching now*.

**Templates**: **Templates** opens a gallery of built-in starters. Choosing one fills the
builder; nothing is saved until **Save**. Built-ins are educational examples, labelled as
such, never recommendations:

| Template | Rule |
| --- | --- |
| Oversold bounce | RSI(14) below 30 in the last 3 candles **and** a confirmed bullish candlestick pattern in the last 3 candles |
| Golden cross with volume | EMA(50) crosses above EMA(200) **and** relative volume ≥ 1.5 |
| Insider cluster buy | ≥ 2 distinct insiders purchased ≥ $250k total in 30 days **and** no insider sales in 30 days |
| Congress + trend | Congressional purchase in the last 45 days **and** close above SMA(200) |
| Elliott Wave 3 setup | Bullish Wave II end detected in the last 5 candles **and** MACD histogram rising for 2 candles |
| Breakout on volume | Close crosses above Donchian(20) upper **and** volume ≥ 2× its 20-candle average |
| Harmonic reversal | Bullish harmonic pattern in the last 3 candles (stop not breached) **and** Stochastic %K below 20 |
| Pullback in an uptrend | Weekly close above SMA(30) **and** Daily RSI(14) crosses above 40 |

## Condition catalog

Every condition has: a **kind**, its parameters, an optional **timeframe** (default: the
alert interval; may be higher, never lower — the same rule as the indicator `timeframe`
input), a **window**, and a **negate** flag.

### Windows

| Window | Meaning | Default for |
| --- | --- | --- |
| On the latest candle | True on the evaluated completed candle | Indicator and price levels |
| At least once in the last N candles | True on any of the last N completed candles | Crosses, patterns |
| On each of the last N candles | True on every one of the last N candles ("for 3 candles in a row") | Optional for levels |
| In the last N days | Calendar days, for filings | Insider, Congress |

N is 1–60 candles or 1–365 days (730 for insider, matching the insider history window).

### Kinds

| Category | Condition | Parameters | Operators |
| --- | --- | --- | --- |
| Indicator | Any *alertable* catalog indicator and one of its outputs (RSI value, MACD histogram, Bollinger upper…) | the indicator's own inputs, validated by `ChartIndicatorCatalog.resolve` | is above / below, crosses above / below / either way, is between / outside, rises / falls for N candles, changes by ≥ x% over N candles |
| | compared with | a number, the close, another output of the same indicator, or **another indicator** (EMA 50 vs EMA 200) | |
| Price & volume | Close / high / low, % change over N candles, new N-candle high or low, gap up/down ≥ x%, distance from 52-week high/low, volume vs its N-candle average | thresholds | same as indicator |
| Candlestick pattern | Any bullish / any bearish / chosen patterns | minimum setup score; *Detected* or *Confirmed only* | detected in window |
| Elliott wave | Stage: Wave II, III, IV, V end, truncated V, correction end, impulse complete; direction | minimum score; "projection not invalidated" | detected in window |
| Harmonic pattern | Chosen types (Gartley, Bat, Butterfly, Crab, Shark, Cypher, Alt Bat, Deep Crab, 5-0, AB=CD, Alt AB=CD); direction | minimum score; "stop not breached" | detected in window |
| Automated outlook | Classification at least Slight / Moderate / Strong buy (or sell), or changed to it | interval | is / changed to, in window |
| Insider activity | Purchases or sales by filing date | min total value, min distinct insiders, role (officer, director, 10% owner) | had / had no, in last N days |
| Congress activity | Purchases or sales by disclosure date | min amount (lower bound of the reported range), min distinct members, chamber | had / had no, in last N days |
| Market context (phase 4) | An indicator condition evaluated on **another symbol** (e.g. S&P 500 above its SMA 200) | symbol | as indicator |

Rules that matter for correctness:

- **Detected, not happened.** Elliott and harmonic structures are only known some candles
  after their turning point. Pattern windows use the candle on which the pattern **became
  detectable** (`confirmationTimestamp` / the signal's availability candle), not its anchor
  candle. The UI says "detected in the last 5 candles" and the trigger page shows both
  dates ("wave ended 7 Oct, detected 10 Oct").
- **No look-ahead, no repainting.** Each candle is evaluated with data available at its
  close, using `CausalCandlePrefixes` where needed. Indicators whose past values can change
  with new data — for example Zig Zag, Williams Fractals, the auto trendline/Fib/pitchfork
  tools, support/resistance and range-anchored volume profiles — are marked
  `alertable = false` in the catalog and are not offered; Phase 1 audits every catalog entry
  for this. Otherwise previews would look better than real alerts.
- **Higher timeframes** use the latest **completed** higher-timeframe candle, which
  `ChartIndicatorEngine` already does for the indicator `timeframe` input.
- **Confirmed only** for candlestick patterns applies the existing lifecycle policy to the
  candles after the pattern, so a pattern counts only after its confirming close.
- **User preferences apply.** Pattern conditions use the alert owner's detection and
  scoring preferences, exactly like their follows.
- **Not applicable vs false.** A condition that cannot apply (insider data on an ETF) makes
  the stock *skipped* for that alert — shown in the preview and on the alert's page — rather
  than silently false. Insufficient history is reported per stock as "not enough history".
- **Negation** is per condition: "had **no** insider sales in the last 30 days".

### Definition format

Stored as versioned JSON, validated on save and on every evaluation:

```json
{
  "version": 1,
  "match": "ALL",
  "groups": [
    { "id": "g1", "match": "ALL", "conditions": [
      { "id": "c1", "kind": "VALUE", "negate": false,
        "subject": { "kind": "INDICATOR", "type": "RSI", "parameters": { "period": 14 }, "output": "value" },
        "operator": "BELOW", "compare": { "kind": "VALUE", "value": 30 },
        "window": { "mode": "ANY", "candles": 3 } },
      { "id": "c2", "kind": "PATTERN", "negate": false, "family": "ELLIOTT_WAVE",
        "direction": "BULLISH", "patterns": ["WAVE_II_END"], "minScore": 60,
        "window": { "mode": "ANY", "candles": 5 } }
    ]},
    { "id": "g2", "match": "ANY", "conditions": [
      { "id": "c3", "kind": "INSIDER", "negate": false, "transaction": "PURCHASE",
        "days": 30, "minTotal": 250000, "minCount": 2, "roles": [] },
      { "id": "c4", "kind": "CONGRESS", "negate": false, "transaction": "PURCHASE",
        "days": 45, "minTotal": 15001, "chambers": ["HOUSE", "SENATE"] }
    ]}
  ]
}
```

As built, the interval lives on the alert row, not in the JSON. Condition kinds are `VALUE`
(a price or indicator `subject` compared with a number, a price or another indicator through
the operators ABOVE, BELOW, CROSSES_ABOVE/BELOW, BETWEEN, OUTSIDE, RISING, FALLING,
CHANGE_ABOVE/BELOW, NEW_HIGH, NEW_LOW), `PATTERN` (family CANDLESTICK, ELLIOTT_WAVE or
HARMONIC_FORMATION, with `confirmedOnly` and `stillValid`), `OUTLOOK`, `INSIDER` and
`CONGRESS`. Windows are `LATEST`, `ANY` and `EVERY` candles; filings use `days`. The server
validates and normalizes every definition (`CustomAlertDefinition.parse`), filling indicator
inputs with their defaults, so stored rules always carry every setting.

A single-group rule is rendered without group chrome, so simple alerts look simple.

## How evaluation works

### When an alert is checked

- **Candle alerts** (any market-data condition): in the existing scheduled check for each
  `(symbol, interval)`. `AlertCheckJobStore.enqueueScheduledRun` gains one more `union`
  branch: every active custom alert's effective targets at that interval. Paused alerts are
  not queued. Missed-run recovery works unchanged because it replays the same jobs.
- **Activity-only alerts** (only insider/congress conditions): *as built*, evaluated on the
  alert's candles like every other alert, counting filings dated up to each candle (an
  event-driven check would have to evaluate between candles and break the no-look-ahead
  rule). Filings for every stock such an alert watches are collected even when nobody follows
  the stock for filings: the daily insider check and the congressional feed include them, and
  a stock's congressional history is loaded once, within the existing request budget. The UI
  says "Checked after every daily candle, counting filings reported up to that candle".
- Custom alerts are evaluated in a new stage, "evaluating custom alerts", placed **before**
  the "no rules" early return (fix 1).

### The facts layer

Per job, a `CustomAlertFacts` object computes each needed fact once and shares it across
every user's alerts for that symbol (memoized with `AnalysisComputationScope.memo`):

- Indicator series, deduplicated by a canonical hash of `(type, resolved inputs, timeframe)`;
  ten users with RSI(14) cost one RSI.
- Pattern detections over the last *max window* candles, keyed by the user preference
  fingerprint (two users with identical preferences share one detection run).
- Higher-timeframe candles, loaded in the same job (as Elliott already loads lower-degree
  candles).
- Insider and congressional records from the database only.

### Per-stock state and trigger rules

For each `(alert, stock)` the engine stores the last evaluated candle and the last result.
On each check it evaluates the new completed candles oldest-first (at most 60, like chart
alerts' catch-up limit), applies the trigger mode and quiet period, and inserts at most one
trigger per `(alert, stock, candle)` (unique constraint, so retries are idempotent).

- **Baseline**: applying an alert to a stock, saving a changed rule, or re-activating a
  paused alert sets the state to the latest completed candle **without triggering**. Only
  candles completing afterwards can trigger (the same promise chart alerts and activity
  follows make today), except for the opt-in *Matched when applied* triggers.
- **Edits**: changing conditions, interval or trigger mode increments the alert's
  `revision` and re-baselines every stock. Name, email and target edits do not.
- **Evidence** stored with each trigger: per condition, pass/fail, observed value,
  threshold, the candle or date it refers to, and links (pattern detail, filing ids), plus
  the rule text and revision at that moment.

### Notifications

- Email subject: "Custom alert: Oversold bounce — AAPL (Daily)". Body: the evidence
  checklist, candle date and close, link to the trigger page. Localized through the
  existing `localize(message)` and queued through `EmailOutboxService` with key
  `custom-alert:{eventId}`.
- **Digest**: when one check run triggers an alert for several stocks (common for a
  500-stock watchlist), one email lists them (first 25 with key values, then "and 14 more"
  with a link), keyed `custom-alert:{alertId}:{scheduledFor}`.
- Gated by the alert's own email switch, the account email pause and the server email
  switch — not by outlook or pattern preferences (fix 4).

### Trigger outcomes

Each trigger tracks the close-to-close forward return at 1, 5, 10, 20 candles and at the
alert's outcome horizon, filled in by later checks. The alert header in All signals shows a
small summary: number of triggers, share positive after the horizon, median return — with
the standard "informational, not financial advice" note. This lets users learn whether
their rule is any good, which is the main reason to keep using the feature.

## Data model (Flyway V90+)

```sql
create table custom_alerts (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    name varchar(80) not null,
    description varchar(500) not null default '',
    interval varchar(16) not null check (interval in ('DAILY','WEEKLY','MONTHLY')),
    clock varchar(16) not null check (clock in ('CANDLE','ACTIVITY')),
    definition text not null,               -- versioned JSON above
    revision integer not null default 1,
    trigger_mode varchar(16) not null check (trigger_mode in ('BECOMES_TRUE','ONCE','EVERY_CANDLE')),
    quiet_candles integer not null default 0,
    outcome_candles integer not null default 10,
    email_enabled boolean not null default true,
    email_digest boolean not null default true,
    active boolean not null default true,
    pinned boolean not null default false,
    source_chart_alert_id bigint references chart_alerts(id) on delete set null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp
);

-- Monitoring lives in watchlists: an alert is applied to a list (all members, or chosen members).
create table custom_alert_targets (
    id bigserial primary key,
    alert_id bigint not null references custom_alerts(id) on delete cascade,
    watchlist_id bigint not null references watchlists(id) on delete cascade,
    scope varchar(16) not null check (scope in ('ALL_MEMBERS','SELECTED')),
    unique (alert_id, watchlist_id)
);
create table custom_alert_target_members (
    target_id bigint not null references custom_alert_targets(id) on delete cascade,
    watchlist_id bigint not null,
    stock_asset_id bigint not null,
    primary key (target_id, stock_asset_id),
    foreign key (watchlist_id, stock_asset_id)
        references watchlist_members(watchlist_id, stock_asset_id) on delete cascade
);
-- "Stop for this stock" on an all-members target.
create table custom_alert_exclusions (
    alert_id bigint not null references custom_alerts(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    primary key (alert_id, stock_asset_id)
);

create table custom_alert_states (
    alert_id bigint not null references custom_alerts(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    revision integer not null,
    last_evaluated_timestamp bigint,
    last_result boolean,
    status varchar(16) not null default 'OK',   -- OK, SKIPPED, INSUFFICIENT_HISTORY
    last_triggered_timestamp bigint,
    primary key (alert_id, stock_asset_id)
);

create table custom_alert_events (
    id bigserial primary key,
    alert_id bigint not null references custom_alerts(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    candle_timestamp bigint not null,
    kind varchar(20) not null check (kind in ('TRIGGERED','MATCHED_ON_APPLY')),
    close_price double precision not null,
    revision integer not null,
    rule_text text not null,                -- the plain-language rule at trigger time
    evidence text not null,                 -- JSON checklist
    forward_returns text,                   -- JSON {1:..,5:..,10:..,20:..}
    outcome_return double precision,        -- at outcome_candles, for sorting
    created_at timestamptz not null default current_timestamp,
    read_at timestamptz,
    email_queued_at timestamptz,
    deleted_at timestamptz,
    unique (alert_id, stock_asset_id, candle_timestamp)
);
create index ix_custom_alert_events_user_feed on custom_alert_events (alert_id, created_at desc);
```

Also:

- A view `custom_alert_effective_targets(alert_id, stock_asset_id, watchlist_ids)`:
  all-members lists plus selected members, minus exclusions.
- Extend `capture_watchlist_origins()` with kind `CUSTOM`, attributing a trigger to the
  lists whose targets cover that stock (not mere membership), and honouring
  `watchlist_disallowed_signals`.
- Extend `watchlist_monitoring` so custom-alert targets count toward monitoring capacity,
  and add `CUSTOM` to the `watchlist_disallowed_signals` check constraint.
- Insider/congressional data requests for custom-alert targets are recorded as
  *data-only* subscriptions (new `notify` flag on the existing subscription tables), so
  the pollers fetch the data and the per-user limits apply, without sending ticker-alert
  notifications the user never asked for.
- `AccountDataExportService` and account deletion include the new tables.

## API and routes

| Method and path | Purpose |
| --- | --- |
| `GET /custom-alerts` | Management page |
| `GET /custom-alerts/new?template=&symbol=&watchlistId=` | Builder, optionally prefilled |
| `GET /custom-alerts/{id}/edit` | Builder for an existing alert |
| `GET /custom-alerts/triggers/{id}` | Trigger detail |
| `GET /signals?customAlertId=` | Archive filtered to one alert (with `watchlistId`, `ticker`, `state`, `type`) |
| `GET /api/custom-alerts/catalog` | Condition catalog: alertable indicators with inputs and outputs, pattern lists, operators, localized |
| `GET/POST /api/custom-alerts`, `GET/PUT/DELETE /api/custom-alerts/{id}` | CRUD |
| `POST /api/custom-alerts/{id}/active`, `/duplicate` | Pause/resume (resume re-baselines), duplicate |
| `POST /api/custom-alerts/preview` | Definition + test symbol + targets → per-condition state, history hits, matching now, capacity, warnings |
| `PUT /api/custom-alerts/{id}/targets` | Apply to lists/members in one transaction, with capacity check |
| `POST /api/custom-alerts/{id}/exclusions/{symbol}` | Stop for this stock |
| `POST /api/custom-alerts/triggers/{id}/read`, `/read-all` | Read state |
| `POST /api/chart-alerts/{id}/convert` | Turn a chart alert into a custom alert |

All mutations are CSRF-protected, ownership-checked and rate-limited like the watchlist API.

## Chart alerts, unified

Chart alerts keep working as they do, plus:

- They are listed and managed on the Custom alerts page (*Chart alerts* tab) and their
  triggers enter the archive (fix 3).
- **Add conditions** converts a chart alert into a custom alert: an *indicator* chart alert
  becomes a normal indicator condition (applicable to any stock); a *drawing* chart alert
  becomes a **pinned** condition ("close crosses the trend line drawn on AAPL") that only
  applies to that stock, so the builder limits that alert's targets to it. The original
  chart alert is switched off and linked (`source_chart_alert_id`).

## Limits

| Limit | Default | Setting |
| --- | --- | --- |
| Active custom alerts per user | 25 | `CUSTOM_ALERTS_MAX_ACTIVE_PER_USER` |
| Conditions per alert | 12 | — |
| Groups per alert | 4 | — |
| Indicators per alert | 8 (as chart alerts) | — |
| Window | 60 candles / 365 days (730 insider) | — |
| Definition size | 16 KB (as chart alerts) | — |
| Applied stocks | within existing monitoring and activity capacity | existing settings |
| Preview | 1 test ticker's history, 50 targets for "Matching now", cached data only | — |

## Accessibility, language and learning mode

- Each condition row is a `fieldset` with a legend containing its plain-language sentence;
  all/any switches are radio groups; the preview's counts are announced through a polite
  live region; reordering uses Move up/Move down buttons.
- Every label, operator, template, email and warning has English and Polish strings;
  operator words follow `docs/i18n-glossary-pl.md`.
- Learning mode adds "?" help to: interval, window, "becomes true", quiet period, outcome
  horizon, detected-vs-happened, repainting, and each category.

## Performance

- No charts on the dashboard, Custom alerts page or watchlist strip. *Matching now* is read
  from `custom_alert_states`, not computed on page load.
- The builder draws one small SVG history strip; the chart is opened on demand on another
  page. The trigger page uses the existing chart at a fixed height.
- Server: facts computed once per job and shared across users; indicator configurations
  deduplicated; activity facts come from indexed queries.

## Edge cases

| Case | Behaviour |
| --- | --- |
| Stock removed from a list | Stops being checked for that list's targets; history stays |
| List deleted | Its targets go; the alert stays and shows "Not applied anywhere" |
| Alert deleted | Confirmation shows trigger count; triggers are deleted with it (as with chart alerts). Offer **Pause** as the alternative |
| Alert edited | Re-baselined from the next candle; old triggers keep their rule text |
| ETF/index with activity conditions | Skipped and listed as skipped |
| New listing, short history | "Not enough history" per stock until it has it |
| Corporate actions / candle revisions | Evaluation uses adjusted candles; stored evidence keeps the values seen at the time; triggers are never retracted |
| Missed runs | Replayed in order through existing recovery, at most 60 candles per stock |
| Same stock in two lists with the same alert | One evaluation, one trigger, both lists as origins |
| Provider outage | No evaluation for that job; retried by the job store like other checks |

## Testing

- Unit: definition validation (every kind, operator, window, negation, group logic);
  trigger modes and quiet periods over synthetic series; no-look-ahead (the result at
  candle *i* never changes when candles after *i* are added); pattern windows use the
  detection candle.
- Integration (dedicated schema, as in `named-watchlists.md`): scheduling union, baselines,
  idempotent retries, missed-run replay, watchlist origins and disallowed types, capacity
  and data-only subscriptions, digest email, archive filters, ownership and CSRF.
- Browser checks (Playwright, like `npm run test:watchlists:browser`): builder on desktop and
  phone, adding/removing groups and conditions, keyboard use, preview updates, applying to
  a list, watchlist strip and archive navigation, light and dark themes.

## Delivery phases

| Phase | Scope | Done when |
| --- | --- | --- |
| 0. Fix what exists | Fixes 1–6 above | Chart-only symbols trigger; counts are true; chart triggers in the archive with their own email switch; archive type filter; sidebar badges |
| 1. Engine | Tables, definition schema and validator, `alertable` catalog flag, facts layer, indicator + price + pattern + outlook conditions, scheduling, states, events, evidence, emails with digest | A rule saved through the API triggers on the right candle, once, with evidence, and survives restarts and retries |
| 2. Core UI | Custom alerts page, builder with preview and templates, trigger detail, `customAlertId` archive filter, sidebar link | A user can build, test, apply, pause, edit and delete alerts and read every trigger |
| 3. Everywhere | Watchlist strip, member chips, *Manage follows* section, allowed types; stock-page section; dashboard panel; chart-alert tab and conversion | Custom alerts are visible and manageable from every place the user meets them |
| 4. Activity and insight | Insider and congressional conditions with data-only subscriptions and capacity checks, activity clock, forward returns and performance summary, other-symbol (market context) conditions | "Insider cluster buy" and "Congress + trend" templates work end to end |
| Later | Sequences ("A, then B within N candles"), sharing alerts between users, import/export | — |

Phase 4 could be pulled ahead of Phase 3 if insider/congressional conditions matter more
than the watchlist and dashboard surfaces; the engine in Phase 1 is designed for them from
the start.

## Implementation notes

What the build does differently from the design above, and why:

- **Definition format and windows**: see [Definition format](#definition-format) — one `VALUE`
  kind covers indicators and prices, one `PATTERN` kind covers the three pattern families.
- **Single stocks** are applied through a watchlist rather than a pinned symbol: the builder's
  "Watch one stock" field and the stock page ask which list the stock belongs in (or name a new
  one). Alerts therefore always show up in a list, and capacity is counted the same way.
- **Activity timing**: candle-based, not event-driven (see
  [When an alert is checked](#when-an-alert-is-checked)).
- **Watchlists**: a "Custom alerts on this list" strip (open, remove with undo, apply an
  existing alert, create one for the list) and chips beside each stock's follows. The
  *Manage follows* dialog was not extended; the strip and the stock page cover the same
  actions. "Custom alerts" is one of the list's allowed signal types; turning it off pauses
  the alerts for that list.
- **Dashboard**: a separate *Custom alerts* panel (newest unread triggers, or the alerts with
  how many stocks match now) and an "Unread custom alerts" metric, answering open question 1.
- **Deleting an alert** deletes its triggers; the delete dialog offers pausing instead.
- **Other-symbol conditions** are limited to catalog indicators that already take a symbol
  input; a general "evaluate this condition on another symbol" is not built.
- **"Matched when applied"** is labelled "Matched at save".
- **Previews** replay up to 500 candles; candlestick detections cover the whole replay and are
  kept briefly (48 scans) so edits do not repeat the slowest step.
- **Configuration**: `custom-alerts.max-active-per-user` (default 25) and
  `custom-alerts.background-enabled` (baselines and digest emails; follows
  `alerts.schedule.enabled`). Migrations: `V90__chart_alert_archive.sql`,
  `V91__custom_alerts.sql`.

## Open questions

1. Should the dashboard's *Latest signals* also show custom triggers (one feed), with the
   new panel showing only the alert cards? This plan keeps them separate to avoid
   duplicates.
2. Is 25 active custom alerts per user the right default for the expected deployment size?
3. Should deleting an alert keep its triggers in the archive (as "deleted alert"), like
   deleted watchlists keep their notification names?
