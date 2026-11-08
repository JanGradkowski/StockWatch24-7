# Recipes: goals turned into alerts

Worked examples. Each shows the goal in the user's words, the steps, and the `alertDraft` JSON for that
rule. Adapt them; do not copy a recipe when the user asked for something different. Unless a step says
otherwise: **{{msg:customAlerts.builder.checkEvery}}** stays **{{msg:customAlerts.builder.interval.DAILY}}**,
trigger mode stays **{{msg:customAlerts.mode.BECOMES_TRUE}}**, and the stocks are chosen in step 4.

## RSI oversold — "tell me when RSI drops below 30"

1. **{{msg:customAlerts.builder.addCondition}}** → **{{msg:js.ca.picker.rsi.title}}** (already RSI (14)
   **{{msg:js.ca.opLabel.BELOW}}** 30, **{{msg:js.ca.builder.when}}** **{{msg:js.ca.window.label.LATEST}}**).
2. With **{{msg:customAlerts.mode.BECOMES_TRUE}}** it fires on the first candle below 30, not on every one.

```json
{"name": "RSI below 30", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "RSI", "parameters": {"period": 14}, "output": "value"},
   "operator": "BELOW", "compare": {"kind": "VALUE", "value": 30}}]}]}}
```

## Oversold for a while, recovery, then a bullish candle — a sequence

"RSI below 30 for some time, then back above 30, and after that a bullish candlestick." If the user does not
say whether the candle must come after the recovery or around the same time, ask (see the reference).

1. **{{msg:customAlerts.builder.addCondition}}** → **{{msg:js.ca.picker.sequence.title}}** → step 1
   **{{msg:js.ca.picker.rsi.title}}**; set **{{msg:js.ca.builder.stepInARow}}** to 5.
2. **{{msg:js.ca.builder.addStep}}** → **{{msg:js.ca.picker.rsi.title}}**; change **{{msg:js.ca.builder.condition}}**
   to **{{msg:js.ca.opLabel.ABOVE}}**; "{{msg:js.ca.builder.thenWithin}}" 3.
3. **{{msg:js.ca.builder.addStep}}** → **{{msg:js.ca.picker.candlestick.title}}** (bullish,
   **{{msg:js.ca.patternStatus.CONFIRMED}}**); "{{msg:js.ca.builder.thenWithin}}" 5.
4. **{{msg:js.ca.builder.span}}** 25; **{{msg:js.ca.builder.completed}}** **{{msg:js.ca.builder.completedLatest}}**.
   The example **{{msg:js.ca.template.oversold-recovery-then-candle.name}}** is this rule.

```json
{"name": "Oversold recovery, then a candle", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "THEN", "span": 25, "conditions": [
  {"id": "c1", "kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "RSI", "parameters": {"period": 14}, "output": "value"},
   "operator": "BELOW", "compare": {"kind": "VALUE", "value": 30}, "step": {"candles": 5}},
  {"id": "c2", "kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "RSI", "parameters": {"period": 14}, "output": "value"},
   "operator": "ABOVE", "compare": {"kind": "VALUE", "value": 30}, "step": {"candles": 1, "within": 3}},
  {"id": "c3", "kind": "PATTERN", "family": "CANDLESTICK", "status": "CONFIRMED", "direction": "BULLISH", "patterns": [],
   "window": {"mode": "ANY", "candles": 1}, "step": {"candles": 1, "within": 5}}]}]}}
```

"Around the same time" instead: steps 1–2 as a sequence with **{{msg:js.ca.builder.completed}}**
**{{msg:js.ca.builder.completedRecent}}** 3, and the candlestick in a second group (window 3 candles), groups
joined with **{{msg:js.ca.builder.all}}** — the example **{{msg:js.ca.template.oversold-recovery.name}}**.

## Moving-average cross — "the 50-day average crosses above the 200-day"

1. **{{msg:customAlerts.builder.addCondition}}** → **{{msg:js.ca.picker.maCross.title}}** (EMA 50
   **{{msg:js.ca.opLabel.CROSSES_ABOVE}}** EMA 200). For simple averages, click the indicator button, choose
   SMA with **{{msg:js.ca.indicatorDialog.use}}**, and do the same for **{{msg:js.ca.builder.otherIndicator}}**.
2. A cross happens on one candle; **{{msg:js.ca.window.label.ANY}}** 3 also accepts a cross 1–2 candles ago.

```json
{"name": "SMA 50 crosses above SMA 200", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 20,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "SMA", "parameters": {"period": 50}, "output": "value"},
   "operator": "CROSSES_ABOVE", "compare": {"kind": "INDICATOR", "type": "SMA", "parameters": {"period": 200}, "output": "value"},
   "window": {"mode": "LATEST"}}]}]}}
```

## Price level — "tell me when AAPL closes above 200"

1. **{{msg:customAlerts.builder.addCondition}}** → **{{msg:js.ca.picker.priceLevel.title}}**; type 200 in
   **{{msg:js.ca.builder.value}}**. **{{msg:js.ca.opLabel.CROSSES_ABOVE}}** fires once when it gets there;
   **{{msg:js.ca.opLabel.ABOVE}}** would also match a stock already above.
2. Step 4: tick the watchlist with AAPL and choose **{{msg:js.ca.targets.chosenStocks}}** → AAPL, or type
   AAPL and press **{{msg:js.ca.targets.singleAdd}}**. A price level suits one stock; for many stocks use a
   relative condition instead.

```json
{"name": "AAPL closes above 200", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "VALUE", "subject": {"kind": "PRICE", "field": "CLOSE"}, "operator": "CROSSES_ABOVE",
   "compare": {"kind": "VALUE", "value": 200}}]}]}}
```

## Breakout on volume — "a new one-year high on heavy volume"

1. **{{msg:js.ca.picker.breakout.title}}**: set **{{msg:js.ca.builder.lookback}}** to 250 (about a year of
   trading days, the maximum; on a weekly alert use 52).
2. **{{msg:customAlerts.builder.addCondition}}** → **{{msg:js.ca.picker.volume.title}}** (relative volume
   **{{msg:js.ca.opLabel.ABOVE}}** 2 = twice the usual volume).

```json
{"name": "One-year high on volume", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 5, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "VALUE", "subject": {"kind": "PRICE", "field": "CLOSE"}, "operator": "NEW_HIGH", "candles": 250,
   "window": {"mode": "LATEST"}},
  {"id": "c2", "kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "RELATIVE_VOLUME", "parameters": {"period": 20}, "output": "value"},
   "operator": "ABOVE", "compare": {"kind": "VALUE", "value": 2}}]}]}}
```

## Sharp drop — "a stock falls 10% within a week"

**{{msg:js.ca.picker.priceChange.title}}** → **{{msg:js.ca.builder.condition}}**
**{{msg:js.ca.opLabel.CHANGE_BELOW}}**, **{{msg:js.ca.builder.percent}}** 10,
**{{msg:js.ca.builder.overCandles}}** 5 (five trading days on a daily alert).

```json
{"name": "Down 10% in a week", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 5, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "VALUE", "subject": {"kind": "PRICE", "field": "CLOSE"}, "operator": "CHANGE_BELOW", "percent": 10,
   "candles": 5}]}]}}
```

## Indicator line against the price — "close below the lower Bollinger Band"

**{{msg:customAlerts.builder.addCondition}}** → **{{msg:js.ca.picker.priceLevel.title}}**; change
**{{msg:js.ca.builder.condition}}** to **{{msg:js.ca.opLabel.BELOW}}**, **{{msg:js.ca.builder.comparedWith}}**
**{{msg:js.ca.builder.anotherIndicator}}** → Bollinger Bands, **{{msg:js.ca.indicatorDialog.line}}** lower.

```json
{"name": "Close below lower Bollinger Band", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "VALUE", "subject": {"kind": "PRICE", "field": "CLOSE"}, "operator": "BELOW",
   "compare": {"kind": "INDICATOR", "type": "BOLLINGER", "parameters": {"period": 20, "deviation": 2}, "output": "lower"},
   "window": {"mode": "LATEST"}}]}]}}
```

## Two lines of one indicator — "MACD crosses its signal line below zero"

1. **{{msg:js.ca.picker.anyIndicator.title}}** → MACD, **{{msg:js.ca.indicatorDialog.line}}** MACD;
   **{{msg:js.ca.opLabel.CROSSES_ABOVE}}** **{{msg:js.ca.builder.anotherIndicator}}** → MACD, line Signal.
2. Second condition: MACD line **{{msg:js.ca.opLabel.BELOW}}** 0.

```json
{"name": "MACD bullish cross below zero", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "MACD", "parameters": {}, "output": "macd"},
   "operator": "CROSSES_ABOVE", "compare": {"kind": "INDICATOR", "type": "MACD", "parameters": {}, "output": "signal"}},
  {"id": "c2", "kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "MACD", "parameters": {}, "output": "macd"},
   "operator": "BELOW", "compare": {"kind": "VALUE", "value": 0}}]}]}}
```

## Candlestick pattern in an uptrend — "bullish engulfing above the 200-day average"

1. **{{msg:js.ca.picker.candlestick.title}}**: **{{msg:js.ca.builder.status}}** — ask or choose:
   **{{msg:js.ca.patternStatus.DETECTED}}** to hear early, **{{msg:js.ca.patternStatus.CONFIRMED}}** only once it
   reached its target. **{{msg:js.ca.builder.patterns}}**: tick the engulfing pattern. Window 3 candles.
2. **{{msg:js.ca.picker.priceLevel.title}}** → **{{msg:js.ca.opLabel.ABOVE}}**, **{{msg:js.ca.builder.comparedWith}}**
   **{{msg:js.ca.builder.anotherIndicator}}** → SMA, length 200.

```json
{"name": "Bullish engulfing in an uptrend", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "PATTERN", "family": "CANDLESTICK", "status": "DETECTED", "direction": "BULLISH",
   "patterns": ["BULLISH_ENGULFING"], "window": {"mode": "ANY", "candles": 3}},
  {"id": "c2", "kind": "VALUE", "subject": {"kind": "PRICE", "field": "CLOSE"}, "operator": "ABOVE",
   "compare": {"kind": "INDICATOR", "type": "SMA", "parameters": {"period": 200}, "output": "value"}}]}]}}
```

## Elliott Wave — "the start of a wave 3"

**{{msg:js.ca.picker.elliott.title}}** (bullish Wave II end, window 5: the end of wave II is the start of
wave III and is recognised a few candles late). Optionally MACD histogram
**{{msg:js.ca.opLabel.RISING}}** 2 — the example **{{msg:js.ca.template.elliott-wave-three.name}}**.

```json
{"name": "Elliott Wave 3 setup", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "PATTERN", "family": "ELLIOTT_WAVE", "direction": "BULLISH", "patterns": ["WAVE_II_END"],
   "window": {"mode": "ANY", "candles": 5}},
  {"id": "c2", "kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "MACD", "parameters": {}, "output": "histogram"},
   "operator": "RISING", "candles": 2}]}]}}
```

## Insider cluster buying with no selling

1. **{{msg:js.ca.picker.insider.title}}**: **{{msg:js.ca.transaction.PURCHASE}}**, 30 days,
   **{{msg:js.ca.builder.minPeople}}** 2, **{{msg:js.ca.builder.minTotalInsider}}** 250000.
2. Another **{{msg:js.ca.picker.insider.title}}**: **{{msg:js.ca.transaction.SALE}}**, 30 days, then
   **{{msg:js.ca.builder.negate}}** (no sales). Only stocks have filings.

```json
{"name": "Insider cluster buy", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 20,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "INSIDER", "transaction": "PURCHASE", "days": 30, "minCount": 2, "minTotal": 250000, "roles": []},
  {"id": "c2", "kind": "INSIDER", "negate": true, "transaction": "SALE", "days": 30, "minCount": 1, "roles": []}]}]}}
```

## Congress — "senators buying a stock"

**{{msg:js.ca.picker.congress.title}}**: **{{msg:js.ca.transaction.PURCHASE}}**, 45 days,
**{{msg:js.ca.builder.chambers}}** {{msg:js.ca.activity.chamber.SENATE}}. Disclosures can come up to 45
days after the trade.

```json
{"name": "Senators buying", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 20,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "CONGRESS", "transaction": "PURCHASE", "days": 45, "minCount": 1, "chambers": ["SENATE"]}]}]}}
```

## Automated outlook — "when the outlook turns to strong buy"

**{{msg:js.ca.picker.outlook.title}}**: **{{msg:js.ca.outlookDirection.BUY}}**, **{{msg:js.ca.builder.atLeast}}**
**{{msg:js.ca.strength.STRONG}}**.

```json
{"name": "Outlook strong buy", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "OUTLOOK", "direction": "BUY", "strength": "STRONG"}]}]}}
```

## Either of two setups — groups joined with "any"

"(RSI below 30 and a bullish candlestick) or insiders buying": group 1 has the two technical conditions
(**{{msg:js.ca.builder.allConditions}}**); **{{msg:customAlerts.builder.addGroup}}** → insider purchases;
then "{{msg:js.ca.builder.triggerWhen}} **{{msg:js.ca.builder.any}}** {{msg:js.ca.builder.ofTheseGroups}}".

```json
{"name": "Oversold candle or insider buying", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ANY", "groups": [
  {"id": "g1", "match": "ALL", "conditions": [
   {"id": "c1", "kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "RSI", "parameters": {"period": 14}, "output": "value"},
    "operator": "BELOW", "compare": {"kind": "VALUE", "value": 30}, "window": {"mode": "ANY", "candles": 3}},
   {"id": "c2", "kind": "PATTERN", "family": "CANDLESTICK", "status": "DETECTED", "direction": "BULLISH", "patterns": [],
    "window": {"mode": "ANY", "candles": 3}}]},
  {"id": "g2", "match": "ALL", "conditions": [
   {"id": "c3", "kind": "INSIDER", "transaction": "PURCHASE", "days": 30, "minCount": 1, "roles": []}]}]}}
```

## Weekly trend, daily timing — and "has held for a while"

"Price above its weekly 30-week average for the last 10 days, and daily RSI turns back up through 40":
the average uses timeframe **{{msg:js.ca.timeframe.option.1wk}}** in the indicator window;
**{{msg:js.ca.builder.when}}** **{{msg:js.ca.window.label.EVERY}}** 10 means it held on every one of the last 10
candles.

```json
{"name": "Pullback in a weekly uptrend", "interval": "DAILY", "triggerMode": "BECOMES_TRUE", "quietCandles": 0, "outcomeCandles": 10,
 "definition": {"version": 1, "match": "ALL", "groups": [{"id": "g1", "match": "ALL", "conditions": [
  {"id": "c1", "kind": "VALUE", "subject": {"kind": "PRICE", "field": "CLOSE"}, "operator": "ABOVE",
   "compare": {"kind": "INDICATOR", "type": "SMA", "parameters": {"period": 30, "timeframe": "1wk"}, "output": "value"},
   "window": {"mode": "EVERY", "candles": 10}},
  {"id": "c2", "kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "RSI", "parameters": {"period": 14}, "output": "value"},
   "operator": "CROSSES_ABOVE", "compare": {"kind": "VALUE", "value": 40}, "window": {"mode": "ANY", "candles": 2}}]}]}}
```
