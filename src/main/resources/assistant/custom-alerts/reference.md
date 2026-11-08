# Custom alert reference

A **custom alert** is the user's own rule built from conditions. It is saved once and watches stocks
through the user's watchlists. After every completed candle of the alert's interval, StockWatch checks
each watched stock; when the rule is true, it records a **trigger**, shows it on the dashboard
(`/home`), in the alert's own list and in the watchlist, and can send an email. Each trigger has a page
explaining which conditions were met. Alerts are created at `/custom-alerts/new` (button
**{{msg:customAlerts.new}}** on `/custom-alerts`, or from a stock page). Candles are daily, weekly or
monthly only: there are no intraday alerts.

## The builder, top to bottom

**1 · {{msg:customAlerts.builder.step.name}}** — **{{msg:customAlerts.builder.name}}** (up to 80
characters; if left empty it is named after the first condition when saved) and
**{{msg:customAlerts.builder.description}}**. While the rule is empty, a row of examples appears here:
"{{msg:js.ca.builder.startWith}}" with one button per example (see "Examples in the builder").

**2 · {{msg:customAlerts.builder.step.rule}}**
- **{{msg:customAlerts.builder.checkEvery}}**: **{{msg:customAlerts.builder.interval.DAILY}}**,
  **{{msg:customAlerts.builder.interval.WEEKLY}}** or **{{msg:customAlerts.builder.interval.MONTHLY}}**.
  {{learn:customAlerts.interval}}
- **{{msg:customAlerts.builder.addCondition}}** opens the window **{{msg:js.ca.conditionPicker.title}}**
  with these choices (each creates a condition you can then change):
  - **{{msg:js.ca.picker.rsi.title}}** — RSI (14) is below 30.
  - **{{msg:js.ca.picker.maCross.title}}** — EMA (50) crosses above EMA (200).
  - **{{msg:js.ca.picker.volume.title}}** — Relative volume is above 2.
  - **{{msg:js.ca.picker.breakout.title}}** — the close makes a new high compared with the last 20 candles.
  - **{{msg:js.ca.picker.priceLevel.title}}** — the close crosses above a level (pre-filled near the latest close).
  - **{{msg:js.ca.picker.priceChange.title}}** — the close rises by at least 5% over 5 candles.
  - **{{msg:js.ca.picker.candlestick.title}}** — a bullish candlestick pattern, confirmed within the last 3 candles.
  - **{{msg:js.ca.picker.elliott.title}}** — a bullish Elliott Wave II end within the last 5 candles.
  - **{{msg:js.ca.picker.harmonic.title}}** — a bullish harmonic pattern within the last 3 candles.
  - **{{msg:js.ca.picker.outlook.title}}** — the automated outlook is at least a moderate buy.
  - **{{msg:js.ca.picker.insider.title}}** — insider purchases in the last 30 days.
  - **{{msg:js.ca.picker.congress.title}}** — congressional purchases in the last 45 days.
  - **{{msg:js.ca.picker.anyIndicator.title}}** — opens **{{msg:js.ca.indicatorDialog.title}}** to pick any
    indicator from the list below.
  - **{{msg:js.ca.picker.sequence.title}}** — starts an ordered sequence (see "Sequences").
- **{{msg:customAlerts.builder.addGroup}}** adds another group (see "Groups").

**3 · {{msg:customAlerts.builder.step.when}}** — one of:
- **{{msg:customAlerts.mode.BECOMES_TRUE}}** (default, recommended): {{msg:customAlerts.mode.BECOMES_TRUE.help}}
- **{{msg:customAlerts.mode.ONCE}}**: {{msg:customAlerts.mode.ONCE.help}}
- **{{msg:customAlerts.mode.EVERY_CANDLE}}**: {{msg:customAlerts.mode.EVERY_CANDLE.help}}

Then **{{msg:customAlerts.builder.quiet}}** (0–60 {{msg:customAlerts.builder.candlesUnit}}, default 0):
{{learn:customAlerts.quiet}} And **{{msg:customAlerts.builder.outcome}}** (1–60, default 10):
{{learn:customAlerts.outcome}}

**4 · {{msg:customAlerts.builder.step.where}}** — {{msg:customAlerts.builder.whereHelp}} Tick a watchlist,
then choose **{{msg:js.ca.targets.allStocks}}** ({{msg:js.ca.targets.allNote}}) or
**{{msg:js.ca.targets.chosenStocks}}** (tick members). A list whose allowed signal types exclude custom
alerts cannot be ticked. To watch a single stock that is in no list, type its ticker under the lists and
press **{{msg:js.ca.targets.singleAdd}}**; StockWatch asks which watchlist it goes in (or a new one) and
adds it when the alert is saved. **{{msg:customAlerts.builder.notifyExisting}}**: {{learn:customAlerts.notifyExisting}}
An account can monitor a limited number of instruments in total; the step shows how many the alert adds.

**5 · {{msg:customAlerts.builder.step.notify}}** — **{{msg:customAlerts.builder.email}}** (ticked by
default). {{msg:customAlerts.builder.emailNote}} {{msg:customAlerts.builder.emailSettings}} (`/settings/analysis-alerts`).

**{{msg:customAlerts.preview.title}}** (right side; below the form on phones) — {{learn:customAlerts.preview}}
Type a ticker in **{{msg:customAlerts.preview.testOn}}** and press **{{msg:customAlerts.preview.test}}**.
It shows whether the stock matches now, "{{msg:js.ca.preview.conditionsMet}}", a strip of past triggers
and what the price did after them. Each condition card also shows **{{msg:js.ca.readout.met}}** /
**{{msg:js.ca.readout.unmet}}** for the test stock.

**{{msg:customAlerts.builder.save}}** in the footer saves; **{{msg:common.cancel}}** leaves without saving.

## Conditions

Every condition card has: **{{msg:js.ca.builder.negate}}** ({{learn:customAlerts.negate}} It then shows
**{{msg:js.ca.builder.negated}}**), **{{msg:js.ca.builder.then}}** (makes it the first step of a
sequence), ↑ **{{msg:js.ca.builder.moveUp}}**, ↓ **{{msg:js.ca.builder.moveDown}}**, ⧉
**{{msg:js.ca.builder.duplicate}}** and × **{{msg:js.ca.builder.remove}}**.

### Indicator or price (kind `VALUE`)

- **{{msg:js.ca.builder.watch}}**: **{{msg:js.ca.price.CLOSE}}**, **{{msg:js.ca.price.OPEN}}**,
  **{{msg:js.ca.price.HIGH}}**, **{{msg:js.ca.price.LOW}}**, **{{msg:js.ca.price.VOLUME}}** or
  **{{msg:js.ca.builder.anIndicator}}**. For an indicator, the **{{msg:js.ca.builder.indicator}}** button opens
  **{{msg:js.ca.indicatorDialog.title}}**: search or pick, set its numbers, choose
  **{{msg:js.ca.indicatorDialog.line}}** when it has several lines, then **{{msg:js.ca.indicatorDialog.use}}**.
  An indicator's timeframe setting can be **{{msg:js.ca.timeframe.option.chart}}**,
  **{{msg:js.ca.timeframe.option.1wk}}** or **{{msg:js.ca.timeframe.option.1mo}}** (only longer than the
  alert: a daily alert can read weekly or monthly, a weekly alert monthly). {{learn:customAlerts.timeframe}}
- **{{msg:js.ca.builder.condition}}** (operator):
  - **{{msg:js.ca.opLabel.ABOVE}}** / **{{msg:js.ca.opLabel.BELOW}}** — on every candle on that side.
  - **{{msg:js.ca.opLabel.CROSSES_ABOVE}}** / **{{msg:js.ca.opLabel.CROSSES_BELOW}}** — only on the candle
    that moves across. {{learn:customAlerts.operator}}
  - For these four, **{{msg:js.ca.builder.comparedWith}}**: **{{msg:js.ca.builder.aNumber}}** (then
    **{{msg:js.ca.builder.value}}**; for RSI and similar, one-click buttons offer the usual levels such as 30
    and 70), **{{msg:js.ca.builder.theClose}}**, or **{{msg:js.ca.builder.anotherIndicator}}** (then
    **{{msg:js.ca.builder.otherIndicator}}**). It may be another line of the same indicator (MACD line vs
    signal line) but not the very same line.
  - **{{msg:js.ca.opLabel.BETWEEN}}** / **{{msg:js.ca.opLabel.OUTSIDE}}** — **{{msg:js.ca.builder.lower}}**
    and **{{msg:js.ca.builder.upper}}** (lower must be below upper).
  - **{{msg:js.ca.opLabel.RISING}}** / **{{msg:js.ca.opLabel.FALLING}}** — **{{msg:js.ca.builder.inARow}}** (1–20).
  - **{{msg:js.ca.opLabel.CHANGE_ABOVE}}** / **{{msg:js.ca.opLabel.CHANGE_BELOW}}** —
    **{{msg:js.ca.builder.percent}}** (0–1000) and **{{msg:js.ca.builder.overCandles}}** (1–250).
  - **{{msg:js.ca.opLabel.NEW_HIGH}}** / **{{msg:js.ca.opLabel.NEW_LOW}}** —
    **{{msg:js.ca.builder.lookback}}** (2–250).
- **{{msg:js.ca.builder.when}}** (not shown inside a sequence): **{{msg:js.ca.window.label.LATEST}}**,
  **{{msg:js.ca.window.label.ANY}}** or **{{msg:js.ca.window.label.EVERY}}** (the last one is not offered for
  crosses, new highs and new lows), with **{{msg:js.ca.builder.candles}}** (1–60). {{learn:customAlerts.window}}

### Chart pattern (kind `PATTERN`)

- **{{msg:js.ca.builder.patternFamily}}**: **{{msg:js.ca.kind.CANDLESTICK}}**, **{{msg:js.ca.kind.ELLIOTT_WAVE}}**
  or **{{msg:js.ca.kind.HARMONIC_FORMATION}}**. Detection follows the user's own pattern settings
  (Settings → Candlestick patterns / Elliott waves / Harmonic patterns).
- Candlesticks only, **{{msg:js.ca.builder.status}}**: **{{msg:js.ca.patternStatus.CONFIRMED}}** (default) or
  **{{msg:js.ca.patternStatus.DETECTED}}**. {{learn:customAlerts.status}}
- **{{msg:js.ca.builder.direction}}**: **{{msg:js.ca.direction.BULLISH}}**, **{{msg:js.ca.direction.BEARISH}}**
  or **{{msg:js.ca.direction.ANY}}**. {{learn:customAlerts.direction}}
- **{{msg:js.ca.builder.withinCandles}}** (or **{{msg:js.ca.builder.confirmedWithin}}** for confirmed
  candlesticks), 1–60. {{learn:customAlerts.detected}}
- **{{msg:js.ca.builder.minScore}}**, 0–100. {{learn:customAlerts.minScore}}
- **{{msg:js.ca.builder.patterns}}**: "{{msg:js.ca.builder.anyPattern}}" or tick specific ones (names below).
- Harmonic only: **{{msg:js.ca.builder.stillValid}}**.

### Automated outlook (kind `OUTLOOK`)

**{{msg:js.ca.builder.direction}}** **{{msg:js.ca.outlookDirection.BUY}}** or **{{msg:js.ca.outlookDirection.SELL}}**,
**{{msg:js.ca.builder.atLeast}}** **{{msg:js.ca.strength.SLIGHT}}**, **{{msg:js.ca.strength.MODERATE}}** or
**{{msg:js.ca.strength.STRONG}}**. {{learn:customAlerts.outlook}}

### Insider or Congress trades (kinds `INSIDER`, `CONGRESS`)

**{{msg:js.ca.builder.transaction}}** **{{msg:js.ca.transaction.PURCHASE}}** or **{{msg:js.ca.transaction.SALE}}**;
**{{msg:js.ca.builder.inLastDays}}** (insiders 1–730, Congress 1–365); **{{msg:js.ca.builder.minPeople}}**
(1–20; {{learn:customAlerts.minPeople}}); **{{msg:js.ca.builder.minTotalInsider}}** /
**{{msg:js.ca.builder.minTotalCongress}}**; **{{msg:js.ca.builder.roles}}** ({{msg:js.ca.activity.role.OFFICER}},
{{msg:js.ca.activity.role.DIRECTOR}}, {{msg:js.ca.activity.role.OWNER}}) or **{{msg:js.ca.builder.chambers}}**
({{msg:js.ca.activity.chamber.HOUSE}}, {{msg:js.ca.activity.chamber.SENATE}}).
Insiders: {{learn:customAlerts.filingDate}} Congress: {{msg:js.ca.builder.congressNote}}
Filings exist for stocks only: for indexes and ETFs such a condition is skipped. An alert made only of
filing conditions is checked whenever new filings arrive instead of after each candle.

## Groups

With one group, its conditions are joined by a switch shown once it has two conditions:
**{{msg:js.ca.builder.allConditions}}** (AND), **{{msg:js.ca.builder.anyCondition}}** (OR) or
**{{msg:js.ca.builder.inOrder}}** (a sequence). {{learn:customAlerts.groups}} With two or more groups, a line
"{{msg:js.ca.builder.triggerWhen}} [{{msg:js.ca.builder.all}} / {{msg:js.ca.builder.any}}]
{{msg:js.ca.builder.ofTheseGroups}}" joins the groups, and each group has
**{{msg:js.ca.builder.addToGroup}}**. Example: (RSI below 30 AND bullish candlestick) OR (insider buying) is
two groups joined with **{{msg:js.ca.builder.any}}**: group 1 **{{msg:js.ca.builder.allConditions}}** with
two conditions, group 2 with one.

## Sequences ("{{msg:js.ca.builder.inOrder}}")

{{learn:customAlerts.inOrder}} Build one with **{{msg:js.ca.builder.then}}** on a condition (it becomes step 1
and the step picker opens), with **{{msg:js.ca.picker.sequence.title}}** in the picker, or by switching a
group to **{{msg:js.ca.builder.inOrder}}**. Next steps are added with **{{msg:js.ca.builder.addStep}}**.
- Steps can be indicator/price conditions and chart patterns only (not outlook, insider or Congress), 2–4
  steps. A pattern step cannot be reversed.
- **{{msg:js.ca.builder.stepInARow}}** (on indicator and price steps, except crosses and rises/falls):
  {{learn:customAlerts.inARow}}
- Between steps: "↓ {{msg:js.ca.builder.thenWithin}} [N] {{msg:js.ca.builder.thenUnit}}" (1–60, default 5).
  {{learn:customAlerts.within}}
- **{{msg:js.ca.builder.span}}** (2–120): {{learn:customAlerts.span}}
- **{{msg:js.ca.builder.completed}}**: **{{msg:js.ca.builder.completedLatest}}** or
  **{{msg:js.ca.builder.completedRecent}}** with a number. {{learn:customAlerts.completed}}
- To combine a sequence with other conditions, put those in another group joined with
  **{{msg:js.ca.builder.all}}**. Where a condition sits decides the timing: in its own group it may happen
  around the same time as the sequence; as the last step it must happen after the previous step. ↑ on a
  condition just below a sequence makes it the last step; ↓ on the last step takes it out again.

## Limits

{{limits}}. Conditions on another stock (for example "when the S&P 500 falls") are not possible: every
condition reads the stock being checked.

## Choices that change the meaning

- **Crosses** marks one candle; **is above/below** holds for as long as the value stays there. For
  "tell me when it happens" use a cross or the default trigger mode **{{msg:customAlerts.mode.BECOMES_TRUE}}**.
- **{{msg:js.ca.window.label.LATEST}}** vs **{{msg:js.ca.window.label.ANY}}**: conditions joined with AND must
  all be true; a window of a few candles lets them happen close together instead of on the same candle.
- Candlestick **{{msg:js.ca.patternStatus.CONFIRMED}}** comes late and rarely (most patterns never reach
  their target); **{{msg:js.ca.patternStatus.DETECTED}}** comes early, outcome open. Ask when unclear.
- Elliott wave ends are recognised only a few candles after the turn, so give them a window of about 5
  candles or more.
- Daily alert + weekly timeframe on an indicator = "weekly trend, daily timing".

## The alertDraft format

`alertDraft` is JSON text of this object (field names and values exactly as written; labels in
parentheses):

```
{"name": "...", "description": "...", "interval": "DAILY" | "WEEKLY" | "MONTHLY",
 "triggerMode": "BECOMES_TRUE" | "ONCE" | "EVERY_CANDLE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL" | "ANY", "groups": [GROUP, ...]}}
```

- GROUP: `{"id": "g1", "match": "ALL" | "ANY" | "THEN", "conditions": [CONDITION, ...]}`. `match` of the
  definition joins groups (only matters with 2+ groups). A `THEN` group (sequence) also has
  `"span": 2–120` and `"window": {"mode": "LATEST"}` or `{"mode": "ANY", "candles": N}`
  (**{{msg:js.ca.builder.completed}}**), and every condition in it has `"step": {"candles": N}` (in a row;
  first step) or `"step": {"candles": N, "within": M}` (later steps).
- Every condition has `"id"` (c1, c2, … unique) and `"kind"`; `"negate": true` reverses it (default false).
- `VALUE`: `"subject": OPERAND, "operator": OP`, optional `"window": WINDOW` (default latest candle), plus, by operator:
  `ABOVE`, `BELOW`, `CROSSES_ABOVE`, `CROSSES_BELOW` → `"compare": OPERAND`;
  `BETWEEN`, `OUTSIDE` → `"lower": n, "upper": n`; `RISING`, `FALLING` → `"candles": 1–20`;
  `CHANGE_ABOVE`, `CHANGE_BELOW` → `"percent": 0–1000, "candles": 1–250`; `NEW_HIGH`, `NEW_LOW` →
  `"candles": 2–250`.
  - OPERAND: `{"kind": "VALUE", "value": 30}` · `{"kind": "PRICE", "field": "CLOSE" | "OPEN" | "HIGH" | "LOW" | "VOLUME"}`
    · `{"kind": "INDICATOR", "type": "RSI", "parameters": {"period": 14}, "output": "value"}`. `type`,
    parameter names and `output` come from the indicator list below; parameters left out take their
    defaults. A `timeframe` parameter is `"chart"`, `"1wk"` or `"1mo"`. A `source` parameter is a price
    (`close`, `open`, `high`, `low`, `hl2`, `hlc3`, `ohlc4`, `hlcc4`).
  - WINDOW: `{"mode": "LATEST"}` · `{"mode": "ANY", "candles": 1–60}` · `{"mode": "EVERY", "candles": 1–60}`
    (`EVERY` not with crosses, `NEW_HIGH`, `NEW_LOW`).
- `PATTERN`: `"family": "CANDLESTICK" | "ELLIOTT_WAVE" | "HARMONIC_FORMATION", "direction": "BULLISH" |
  "BEARISH" | "ANY", "patterns": [names, or empty for any], "window": {"mode": "ANY", "candles": 1–60}`;
  candlesticks also `"status": "CONFIRMED" | "DETECTED"`; optional `"minScore": 0–100`; harmonics optional
  `"stillValid": true`.
- `OUTLOOK`: `"direction": "BUY" | "SELL", "strength": "SLIGHT" | "MODERATE" | "STRONG"`.
- `INSIDER`: `"transaction": "PURCHASE" | "SALE", "days": 1–730, "minCount": 1–20`, optional
  `"minTotal": dollars`, `"roles": [] or some of "OFFICER", "DIRECTOR", "OWNER"`.
- `CONGRESS`: the same with `"days": 1–365` and `"chambers": [] or "HOUSE" / "SENATE"` instead of roles.

## Indicators a custom alert can use

Each line: `TYPE` name: settings (`key` label = default [range] {choices}) → lines (`output` label).
`period` is the field labelled **{{msg:js.ind.input.period}}**. Lengths take 1 (or 2) to 500 unless a range is shown. `source` takes a price: close, open, high, low, hl2, hlc3,
ohlc4, hlcc4. `timeframe` takes chart (default), 1wk or 1mo. Settings left out keep their default.

{{catalog:indicators}}

## Pattern names

{{catalog:patterns}}

## Examples in the builder

The empty builder offers these starting points (names as shown): {{msg:js.ca.template.oversold-bounce.name}},
{{msg:js.ca.template.golden-cross.name}}, {{msg:js.ca.template.insider-cluster.name}},
{{msg:js.ca.template.congress-trend.name}}, {{msg:js.ca.template.elliott-wave-three.name}},
{{msg:js.ca.template.breakout-volume.name}}, {{msg:js.ca.template.harmonic-reversal.name}},
{{msg:js.ca.template.uptrend-pullback.name}}, {{msg:js.ca.template.oversold-recovery.name}},
{{msg:js.ca.template.oversold-recovery-then-candle.name}}. When a goal matches one closely, you may say the
user can click it and then adjust it.
