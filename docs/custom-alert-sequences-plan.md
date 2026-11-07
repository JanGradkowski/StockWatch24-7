# Custom alert sequences ("then"): design plan

Date: 2026-11-07

Status: built on the `custom-alert-sequences` branch (November 2026). [Implementation notes](#implementation-notes) list where the build differs.

## The idea in one paragraph

Today a group joins its conditions with **and** or **or**. This adds a third way, **then**: the
conditions of a group become **steps** that must happen **in this order**. A step can have to
last ("RSI below 30 for at least 5 candles in a row"), and the next step must follow soon enough
("then, within 3 candles, RSI above 30"). The whole chain must fit in a limited number of
candles. A group joined with *then* is a **sequence**. It combines with other groups through
the existing all/any switch, so "the sequence completed **and** a bullish candlestick was
confirmed" is two groups joined with *and*. Rules still have only two levels (groups, then
conditions), so nothing that exists today changes shape.

This was the "Later" row of [custom-alerts-plan.md](custom-alerts-plan.md#delivery-phases):
"Sequences (A, then B within N candles)".

## Your example, built with the feature

> RSI has been below 30 for some time, then it becomes above 30, combined with a candlestick
> pattern.

"Combined with" can mean two timings, and users can build either one. Where the candlestick
condition sits decides which:

- **In its own group**, next to the sequence → A: the pattern must happen *around the same time*.
- **Inside the sequence**, as the last step → B: the pattern must happen *after* the recovery.

Moving the condition with ↑ ↓ switches between A and B in one click: ↑ on the candlestick under the
sequence makes it the last step (B), and ↓ on that last step takes it out into a group of its own
again (A). Each timing also gets its own template (see [Templates](#everywhere-else-a-rule-is-shown)).

**A. The candlestick pattern happens around the time RSI recovers**

```
Trigger when [all] of these groups are true

┌ Group 1 · these happen [in order] ─────────────────────────────────────────┐
│ ① RSI (14) is below 30 · for at least 5 candles in a row                   │
│   ↓ then, within 3 candles                                                 │
│ ② RSI (14) is above 30                                                     │
│ All steps within 20 candles · counts if it completed in the last 3 candles │
└────────────────────────────────────────────────────────────────────────────┘
                                   AND
┌ Group 2 ───────────────────────────────────────────────────────────────────┐
│ Bullish candlestick pattern confirmed in the last 3 candles                │
└────────────────────────────────────────────────────────────────────────────┘
```

It triggers on the first candle where RSI recovered in the last 3 candles **and** a bullish
pattern was confirmed in the last 3 candles. The pattern may come shortly before or shortly
after the recovery.

**B. The candlestick pattern must come after RSI recovers.** The pattern becomes step 3:

```
┌ Group 1 · these happen [in order] ─────────────────────────────────────────┐
│ ① RSI (14) is below 30 · for at least 5 candles in a row                   │
│   ↓ then, within 3 candles                                                 │
│ ② RSI (14) is above 30                                                     │
│   ↓ then, within 5 candles                                                 │
│ ③ Bullish candlestick pattern confirmed                                    │
│ All steps within 25 candles · counts on the latest candle                  │
└────────────────────────────────────────────────────────────────────────────┘
```

It triggers on the candle where the pattern is confirmed. RSI must first have been below 30 for
at least 5 candles, then got back above 30 within 3 candles, and still been above 30 at some
point in the 5 candles before the pattern. All of it must fit in 25 candles.

## How a sequence works, in plain words

A sequence has 2 to 4 steps. Each step is one ordinary condition with two extra settings, and
the sequence as a whole has two:

| Setting | Where | Meaning | Default |
| --- | --- | --- | --- |
| **For at least N candles in a row** | each indicator or price step | The step only counts once its condition has held on N completed candles in a row. 1 means a single candle. | 1 |
| **Then, within N candles** | each step after the first | The step must start within N candles after the previous step was **last true**. 1 means "on the very next candle". | 5 |
| **All steps within N candles** | the sequence | From the first candle of step 1 to the candle the last step counts, at most N candles. Stops one step from being matched against something months earlier. | 30, raised automatically when the steps need more |
| **Counts if it completed** | the sequence | *On the latest candle* (the last step happened on the checked candle) or *in the last N candles*. Same idea as a pattern condition's window. | On the latest candle |

Four rules decide when a step counts:

1. **A step counts on the candle its condition has held for its N candles in a row.**
2. **Each later step counts the first time it happens after the previous step.** If RSI recovers
   on Monday and stays above 30, step 2 counts on Monday only. Tuesday is not a second
   completion.
3. **"Within" is measured from the last candle the previous step was true.** RSI below 30 for
   12 candles, then above 30 on the 13th: step 2 is 1 candle after step 1, not 8 candles after
   the 5th oversold candle.
4. **A later step does not wait for the earlier one to end.** Step 1 "RSI below 30 for 5
   candles", step 2 "bullish engulfing": an engulfing on the 6th oversold candle counts. If you
   mean "after RSI is back above 30", say so with a step ("RSI is above 30" or "crosses above
   30").

Two steps never share one candle. A sequence that is half done when you save the alert still
triggers when its last step happens after the save. The baseline only stops triggers on candles
that completed before saving, and step 1 from before the save still counts.

### Worked example (daily candles)

Rule: ① RSI below 30 for at least 5 in a row → ② within 3 candles: RSI above 30. All within 20.

```
candle      1    2    3    4    5    6    7    8    9   10   11   12
RSI        35   29   27   25   24   26   28   31   33   29   32   34
below 30    ·    ■    ■    ■    ■    ■    ■    ·    ·    ■    ·    ·
                      step 1 reached ▲    ▲ last true
above 30    ■    ·    ·    ·    ·    ·    ·    ■    ■    ·    ■    ■
                                               ▲ step 2: 1 candle after step 1 → completed
```

- Candle 6: step 1 is reached (5 oversold candles: 2–6). It stays true on candle 7.
- Candle 8: RSI is above 30, 1 candle after step 1 was last true. **The sequence completes.**
- Candle 9: RSI is still above 30, but step 2 already counted on candle 8. Not a new completion.
- Candles 10–11: one oversold candle is not 5 in a row, so step 1 is not reached again. Candle 11
  is 4 candles after candle 7, outside "within 3". Nothing completes.

With *counts if it completed in the last 3 candles*, the group is true on candles 8, 9 and 10.

## Precise rules (for the implementation and the tests)

Steps 1..n (2 ≤ n ≤ 4). Step k has a condition Cₖ and a length hₖ ≥ 1. Every step after the
first also has an allowance gₖ ≥ 1. The sequence has a span limit W and a window (the checked
candle, or the last N candles).

- **Cₖ(t)**: the step's condition evaluated on candle t alone. An indicator or price step is
  read as "on the latest candle". A pattern step is true on the candle its pattern counts on:
  detected, or confirmed for candlesticks with status *Confirmed*. This is the same candle
  today's pattern windows use.
- **Step k is reached on t** when Cₖ holds on every candle t−hₖ+1 … t.
- **The sequence completes on candle c** when there are candles r₁ < r₂ < … < rₙ = c, and for
  each k ≥ 2 a candle eₖ₋₁, such that:
  1. step 1 is reached on r₁;
  2. for k ≥ 2: Cₖ₋₁ holds on every candle rₖ₋₁ … eₖ₋₁. Step k's stretch starts after that,
     sₖ = rₖ − hₖ + 1 > eₖ₋₁, and in time, sₖ − eₖ₋₁ ≤ gₖ. rₖ is the first candle after eₖ₋₁ on
     which step k is reached with a stretch starting after eₖ₋₁;
  3. c − (r₁ − h₁ + 1) + 1 ≤ W.
- **The group is true on candle i** when the sequence completed on i (window: the checked
  candle) or on any of i−N+1 … i (window: last N candles).

The answer for candle i reads only candles i−W−N+1 … i (plus whatever each step's own
indicator needs for warm-up). It never changes when later candles arrive, which keeps the
evaluator's no-look-ahead promise. There is no stored progress, so restarts, missed checks,
catch-ups and edits behave exactly as they do today.

## What can be a step

| Condition | As a step | Why |
| --- | --- | --- |
| Indicator or price (every operator) | Yes, with "for at least N candles in a row" and *is / is not* | Has a value on every candle |
| Candlestick, Elliott, harmonic pattern | Yes, counted on one candle (no "in a row"); not negated | A pattern happens on one candle. "No pattern on this candle" is true almost always and makes a poor step |
| Automated outlook | No | Known only for the latest candle, so it cannot be placed in the past |
| Insider, Congress | No | They are counted over calendar days, not candles. Put them in another group: "insiders bought in the last 30 days **and** the sequence completed" |

A step's own window (latest / at least once / each of the last N) is not used inside a
sequence: "in a row" and "within" replace it. When a group is switched from *all* to *in order*,
"on each of the last N candles" becomes "for at least N candles in a row", and back again.
Other windows are dropped, and a note says so.

## Builder

### Turning a group into a sequence

The group header's switch gains a third choice: **[ all | any | in order ]**. In a rule with one
group, that reads "Trigger when these happen **in order**". Choosing it:

- numbers the cards ①②③ and replaces the AND/OR joiners with **connectors**. Each connector
  reads "↓ then, within [3] candles", with the number editable right on the connector;
- replaces the window field of indicator and price cards with "For at least [1] candles in a
  row"; pattern cards say "Counts on the candle it is confirmed" (or detected);
- adds a footer to the group: "All steps within [30] candles · Counts if it completed
  [on the latest candle ▾ | in the last N candles]";
- shows a one-line hint: "To combine the sequence with other conditions, put them in another
  group."

While the group holds an outlook, insider or Congress condition, choosing *in order* leaves the
group as it is and says why under its header (a note rather than a disabled button, so keyboard and
screen-reader users learn the reason too). ↑ ↓ reorder steps as they reorder conditions today.

The **+ Add condition** picker gets one more card, **"In order: one thing, then another"**. It
adds a new group already set to *in order* and opens the usual picker for its first step. A
sequence with one step shows "+ Add the next step", and saving asks for at least two.

Moving a condition into a sequence with ↑ ↓ makes it a step. At a group's edge a condition crosses
into the neighbouring group and stays at that edge: moving up it becomes the last of the group
above, moving down the first of the group below. A sequence's first or last step at the very top
or bottom of the rule can still move out, into a new plain group. A step that enters gets "then,
within 5 candles" (or none, as step 1). Moving it out gives it a window again: the one it had
before it was moved in, or "in the last 3 candles". This is how a user switches between the two
timings of the example above.

Each condition card also gets a **"Then…"** action. It turns a single condition into step 1 of a
new sequence group and opens the picker for step 2. This is the quickest path for "RSI below
30… then". When the condition shares its group with others, "Then…" moves it into a new group,
which keeps the rule's meaning only when its group and the groups are joined with *all*. Anywhere
else it changes nothing and says so.

### Preview readouts

Every step shows where the test stock stands now:

- `① done · 2–7 Oct (6 candles)` ✓, or `① 3 of 5 candles so far`, or `① not true now`
- `② waiting · 2 candles left`, or `② on 8 Oct` ✓, or `② too late (step 1 ended 9 candles ago)`

The history strip and its trigger count work as today, because they read the rule's result
per candle.

### Mockup

```
2  Describe what to look for
   Check after every [daily close ▾]

   Trigger when [ all | any ] of these groups are true
   ┌ Group 1   [ all | any | in order ]                                     × ┐
   │ ① INDICATOR  RSI (14) is below 30          AAPL: done 2–7 Oct ✓  ↑ ↓ ⧉ × │
   │    [RSI (14) ✎] [is below ▾] [a number ▾] [30]                           │
   │    For at least [5] candles in a row                                     │
   │    │                                                                     │
   │    ↓ then, within [3] candles                                            │
   │ ② INDICATOR  RSI (14) is above 30          AAPL: on 8 Oct ✓      ↑ ↓ ⧉ × │
   │    [RSI (14) ✎] [is above ▾] [a number ▾] [30]                           │
   │    For at least [1] candles in a row                                     │
   │ ──────────────────────────────────────────────────────────────────────── │
   │ All steps within [20] candles · Counts if it completed                   │
   │ [ on the latest candle | in the last [3] candles ]                       │
   │ + Add the next step                                                      │
   └──────────────────────────────────────────────────────────────────────────┘
                                     AND
   ┌ Group 2   …                                                            × ┐
   │ CANDLESTICK  Bullish candlestick pattern confirmed in the last 3 candles │
   └──────────────────────────────────────────────────────────────────────────┘
   [+ Add condition]  [+ Add group]
```

On phones the connector and the footer wrap under the card. Nothing is placed side by side.

## Everywhere else a rule is shown

| Place | Change |
| --- | --- |
| **Summary sentence** (builder, emails) | "…triggers when RSI (14) is below 30 for at least 5 candles in a row, **then within 3 candles** RSI (14) is above 30 (all within 20 candles), **and** a bullish candlestick pattern is confirmed in the last 3 candles." |
| **Rule chips** (alerts list, watchlist strip, stock page, All signals header) | `RSI (14) < 30 · 5 in a row` **THEN** `RSI (14) > 30 · within 3` **AND** `Bullish candlestick confirmed · last 3`. **THEN** gets its own badge style with an arrow, so it is not read as AND. |
| **Server rule text** (`CustomAlertText.rule`, kept with each trigger) | "In this order, all within 20 candles:" then "1. RSI (14) is below 30 for at least 5 candles in a row" and "2. Then within 3 candles: RSI (14) is above 30". |
| **Trigger page, "Why it triggered"** | The group line reads "Group 1 · In this order · completed on 8 Oct ✓". Each step gets its own line with when it happened: "① … — 6 candles, 2–7 Oct, lowest 24.1 ✓" and "② … — 8 Oct, 1 candle after step 1, 31.8 ✓". |
| **Trigger chart** | Each step's stretch is shaded in its condition's colour and labelled ① ②. The chart widens to show step 1's start, as it already widens for pattern points and filings. Indicator panes and level lines stay as they are. |
| **Email** | The condition list uses the same step lines as the trigger page. |
| **Templates** | Two new templates, labelled educational like the others: **"Oversold recovery with a candle"** (example A, the pattern around the recovery) and **"Oversold recovery, then a candle"** (example B, the pattern after it). Each template's description says how it differs from the other. |
| **Learning mode** | "?" help for: in order, in a row, within, all steps within, and counts if it completed. |

Every new string gets English and Polish versions (`messages*.properties`,
`i18n/server-labels.txt`). Polish wording follows `docs/i18n-glossary-pl.md`.

## What stays the same

- Trigger modes (*becomes true*, *once per stock*, *every candle while true*), quiet period and
  outcome tracking. They read the rule's yes/no per candle, and a sequence is just one more way
  to compute it.
- Baselines, catch-up (up to 60 candles), missed-run replay and *Matched at save*.
- Limits: 4 groups, 12 conditions (each step counts as one), 8 indicators.
- No database migration. The rule JSON stays at version 1 because the new fields are additive:
  every saved rule reads as before, and old triggers keep showing the rule as it was.

## Definition format

```json
{ "version": 1, "match": "ALL", "groups": [
  { "id": "g1", "match": "THEN", "span": 20, "window": { "mode": "LATEST" }, "conditions": [
    { "id": "c1", "kind": "VALUE", "negate": false,
      "subject": { "kind": "INDICATOR", "type": "RSI", "parameters": { "period": 14 }, "output": "value" },
      "operator": "BELOW", "compare": { "kind": "VALUE", "value": 30 },
      "window": { "mode": "LATEST" }, "step": { "candles": 5 } },
    { "id": "c2", "kind": "VALUE", "negate": false,
      "subject": { "kind": "INDICATOR", "type": "RSI", "parameters": { "period": 14 }, "output": "value" },
      "operator": "ABOVE", "compare": { "kind": "VALUE", "value": 30 },
      "window": { "mode": "LATEST" }, "step": { "candles": 1, "within": 3 } } ] },
  { "id": "g2", "match": "ALL", "conditions": [
    { "id": "c3", "kind": "PATTERN", "negate": false, "family": "CANDLESTICK", "status": "CONFIRMED",
      "direction": "BULLISH", "patterns": [], "window": { "mode": "ANY", "candles": 3 } } ] } ] }
```

Validation in `CustomAlertDefinition.parse`, with messages in the existing style:

| Field | Rule | Message |
| --- | --- | --- |
| group `match` | ALL, ANY or THEN | "Choose whether all, any or these in order must be true." |
| THEN group | 2–4 conditions | "A sequence needs 2 to 4 steps." |
| step kind | VALUE or PATTERN; PATTERN not negated | "Outlook, insider and Congress conditions can't be steps of a sequence." |
| `step.candles` | 1–60, VALUE steps only (PATTERN is always 1) | "Choose 1 to 60 candles in a row." |
| `step.within` | 1–60, every step after the first, none on the first | "Choose 1 to 60 candles." |
| `span` | 2–120, at least the sum of the steps' `candles` | "The whole sequence needs at least {n} candles." |
| group `window` | LATEST or ANY 1–60 | as today |
| top-level `match` | ALL or ANY (sequences of groups are not offered) | as today |

The step's own `window` is normalized: LATEST for values, ANY 1 for patterns. `toJson` writes the
normalized form, as it does today.

## Engine changes

| File | Change |
| --- | --- |
| `CustomAlertDefinition` | `Group` gains `span` and `window` (both null unless THEN) and the step settings of each condition. Also parsing, writing, and `lookbackCandles()` extended by `span` + window + the steps' own warm-up. |
| `CustomAlertEvaluator` | A THEN group is evaluated by a small search back from the checked candle over at most `span` candles, following the precise rules above. It returns the group's result and one `ConditionResult` per step: passed, and facts `from`, `to`, `at`, `value`, `candlesAfter`, or progress (`soFar`, `waiting`, `candlesLeft`). `Result` gains per-group facts (`completedAt`, `span`) for the evidence. |
| `CustomAlertEvaluator.Facts` / `CustomAlertFactsLoader` | Each step's yes/no per candle is computed once per check or replay and reused, so 60 catch-up candles or a 500-candle preview evaluate each step on each candle once. Pattern hits are indexed by candle once. |
| `CustomAlertEngine` | Records group facts in the evidence. Nothing else changes. |
| `CustomAlertPreview` | Nothing beyond the larger lookback. Readouts come from the step facts. |
| `CustomAlertText` | Step lines and the sequence header, through `server-labels.txt`. |
| `CustomAlertTriggerChart` | `ConditionView` gains step stretches. The chart widens to step 1's start. |
| `custom-alert-text.js` | `describe` / `summary` / `chips` learn THEN; the new template. |
| `custom-alert-builder.js`, `custom-alerts.css` | The third switch choice, connectors, step and group fields, the picker card, the "Then…" action, readouts. |
| `custom-alert-trigger.js`, `custom-alert-trigger-chart.js` | Step evidence lines and shaded stretches. |

## Performance

- The search is at most `span` (≤ 120) × steps (≤ 4) checks of cached booleans per evaluated
  candle: about 240,000 cheap steps for a 500-candle preview. That is small next to the
  indicator and pattern work the preview already does.
- A longer lookback only matters for scheduled checks when `span` exceeds today's longest window
  plus catch-up. Candles are loaded from the database as now; no provider requests are added.

## Edge cases

| Case | Behaviour |
| --- | --- |
| Indicator still warming up (no value) | The condition is false on that candle, so it breaks a run |
| Step 2 is true at the same time as step 1 (e.g. "RSI below 30", then "close above SMA 20") | Completes on the first candle after step 1's minimum stretch where step 2 is true. It stays complete while both stay true, so *becomes true* fires once. To require a break, add the break as a step |
| The same condition twice ("RSI below 30", then "RSI below 30") | Step 2 counts straight after step 1's minimum stretch. A real double dip needs a middle step ("RSI above 35") |
| Confirmed candlestick as a step | Ordered by its confirmation candle, which can be several candles after the pattern formed. Detected patterns use the detection candle. The trigger page shows both dates, as today |
| Elliott wave as a step | Ordered by the candle the wave became known, as in today's windows |
| Higher-timeframe indicator (weekly RSI on a daily alert) | "In a row" and "within" count the alert's own candles (days here) |
| Alert edited | Re-baselined as today. A sequence half done at that moment still completes afterwards |
| Not enough history for the span | The sequence is simply not complete; the stock is not flagged as broken |
| Group switched back from *in order* to *all* | "In a row" becomes "on each of the last N candles"; the "within" and span settings are dropped |

## Testing

- **Unit** (`CustomAlertEvaluatorTest`), over synthetic series: the worked example above;
  a short dip does not re-arm; "within" exceeded; span exceeded; overlapping steps; the same
  condition twice; two steps never on one candle; 3- and 4-step chains; pattern steps (detected
  and confirmed); negated value steps; warm-up gaps; window *in the last N*; and no-look-ahead
  (the answer for candle i does not change when candles after i are added).
- **Definition** (`CustomAlertDefinition` tests): every validation row above, and that
  `toJson` → `parse` gives the same rule back.
- **Text**: `CustomAlertMessagesTest` (every server label has a Polish version) and
  `custom-alert-text.test.cjs` (sentences, chips, summary in both languages).
- **Engine integration** (`CustomAlertEngineIntegrationTest`): step 1 before the last check and
  step 2 now triggers; a sequence half done at save triggers after it; a catch-up over a whole
  sequence triggers once; preview replay equals the engine's triggers on the same candles.
- **Browser**: build example A by switching a group to *in order*, by the picker card and by
  "Then…"; turn A into B and back by moving the candlestick condition; open both templates;
  edit numbers on connectors; keyboard only; phone width; light and dark themes;
  trigger page evidence and shaded stretches. Then screenshots and a design critique before
  handover.

## Delivery phases

| Phase | Scope | Done when |
| --- | --- | --- |
| 1. Engine | Format, validation, evaluator search with step facts, lookback, server text, unit and engine tests | A sequence rule saved through the API triggers on the right candle with step evidence, and the preview's replay agrees |
| 2. Builder | Third switch choice, connectors, step and group fields, picker card, "Then…", moving conditions in and out of a sequence, readouts, summary and chips, both templates, EN/PL strings, learning help | Examples A and B can be built, tested and saved on desktop and phone |
| 3. Trigger page | Step evidence lines, shaded stretches on the chart, email lines | A trigger explains each step with its dates |
| 4. Optional: "halfway" | Store each stock's progress (`custom_alert_states.progress`, migration V99) and show "Halfway: 3 stocks" on the alerts page and watchlist strip, with "AAPL — step 1 done, waiting for step 2 (2 candles left)" | You can see which stocks are one step away without opening the builder |

## Decisions

- **Your example** is possible both ways, A (the pattern around the recovery) and B (the pattern
  after it), each with its own template.
- **One condition per step** for now; several conditions in one step would need a third level.
- **"Halfway" status** is part of this feature (phase 4).

## Implementation notes

What the build does differently from the design above, or adds to it:

- **No step cache.** Each evaluation reads every step on every candle a chain could use (at most
  the window plus the span, 180 candles, times 4 steps) and searches once. That is cheap enough
  that previews and catch-ups need no cache shared between evaluations.
- **Pattern steps are read as known on the evaluated candle**, as pattern windows are: a wave
  that only a later count showed counts for candles evaluated from then on.
- **Emails** carry the rule text with numbered step lines ("Step 2, within 3 candles: …"). They
  have never listed per-condition evidence, so step dates appear on the trigger page only.
- **The completion switch** reads "on the latest candle | in the last few candles", matching the
  wording of condition windows.
- **"+ Add condition"** after a sequence starts a new plain group, so the new condition combines
  with the sequence instead of becoming its next step. "+ Add the next step" is inside the sequence.
- **The condition picker** had shown the watchlist chooser's title ("Which watchlist should {0} go
  in?") since custom alerts were built; it now says "Add a condition" ("Add a step" in a sequence).
- **Halfway**: `custom_alert_states.progress` (V99) holds `{"done", "steps", "candlesLeft"}` for the
  furthest sequence under way when the rule does not match. The alerts list counts "halfway"
  stocks, the stock table and stock page show "Halfway · Step 1 of 2 done · step 2 due within 3
  candles", and the watchlist strip counts them.
- **Verified** on stored data: an AAPL replay of "RSI below 40 for 3 candles, then above 40 within
  3" gave the same 8 trigger dates in 500 candles as an independent RSI and brute-force check;
  saving that rule on a 25-stock list with "tell me about stocks already matching" recorded exactly
  the stocks the preview matched, and marked the one the preview showed half done as halfway.

### After the independent review

An independent review tested the build against this plan. On about 7 million random candle checks,
a literal reading of the precise rules agreed with the evaluator, with no look-ahead. It also found
problems, fixed as follows:

- **Halfway on a finished chain.** When a later step missed its allowance, halfway fell back to an
  earlier step and showed the next one as still due, although it had already had its one chance
  (a step counts only the first time after the previous one). A step whose next step was already
  reached after it is now over, not waiting.
  With that in place, halfway no longer ignores chains that started before the latest completion,
  so a stock part-way into a second completion (identical or overlapping steps) shows as halfway.
- **Warm-up.** A candle whose values are not known yet is false for a step, reversed or not. That
  includes the candle before a cross, every candle of a rise, fall or new high, the two ends of a
  change, and the indicator a step compares with. Plain reversed conditions outside sequences keep
  their earlier behaviour, so existing alerts trigger as before.
- **Crosses, rises and falls take no "in a row".** A cross cannot happen on two candles in a row,
  and a rise or fall counts its own candles; such steps always count one candle, and the builder
  does not offer the field.
- **Reversed steps read candle by candle**: "Not: RSI (14) is above 30 on each of at least 5
  candles in a row", so "not" is not read as "the run did not happen".
- **Paused alerts show no halfway stocks**, since their "candles left" would go stale.
- **Builder**: ↑ ↓ work at group edges as described above; "Then…" keeps the rule's meaning; the
  span stops at 120 and the sequence says which steps need shortening; a cleared number keeps its
  value; switching a sequence to *all* and back keeps its timing; a full sequence (4 steps) says
  so; the "windows were removed" note also follows "Then…" and ↑ ↓ (and a rise's or fall's "on each
  of the last N candles"), and stays when later steps are added; focus stays on the switch or
  arrow used; the step number is read out as "Step 2"; the gap's unit follows its number.
- **Wording**: the summary has no parentheses inside parentheses ("…, all within 20 candles"), the
  stock page joins steps with →, and a Polish case was corrected.
- **Kept by design**: a harmonic step with "stop not breached" is checked on the evaluated candle,
  as the plain condition is; an unknown `step` or a `within` on the first step is ignored rather
  than rejected.
- **Tests** now cover each of these, and the span cases three deliberately broken evaluators had
  slipped past: the span together with "in the last N candles", the span counted from step 1's
  first candle, and the span's share of the halfway deadline. A builder test drives the real
  builder through the moves, switches and limits. The engine test also checks that the preview's
  replay finds the same triggers as the scheduled checks.
