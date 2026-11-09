---
id: custom-alert-trigger
title: customAlerts.trigger.eyebrow
routes: [/custom-alerts/triggers/{id}]
templates: [custom-alert-trigger]
suggestions: [assistant.suggest.trigger.1, assistant.suggest.trigger.2]
---
## What this page is for

One trigger of a custom alert: which stock, which completed candle, why the rule matched, and what the
price did afterwards. Opening it marks it read.

## What's on it

- Header: the ticker and company, then "{{msg:customAlerts.trigger.lead}}" with the alert's name (or
  "{{msg:customAlerts.trigger.leadAtSave}}" when the stock already matched as the alert was saved with
  "{{msg:customAlerts.builder.notifyExistingShort}}"), the status **{{msg:customAlerts.alert.how.TRIGGERED}}** or
  **{{msg:customAlerts.alert.how.MATCHED_ON_APPLY}}**, "{{msg:customAlerts.trigger.conditionsMet}}", the interval
  and the watchlists it came from. **{{msg:customAlerts.trigger.openStock}}** and a menu with
  **{{msg:customAlerts.trigger.allTriggers}}**, **{{msg:customAlerts.trigger.edit}}**,
  **{{msg:customAlerts.trigger.markUnread}}** and "{{msg:customAlerts.trigger.stop}}".
- **{{msg:customAlerts.trigger.chartTitle}}**: {{learn:customAlerts.chart}} {{msg:customAlerts.trigger.chartHelp}}
  The shaded stretch after the trigger is the follow-up window. When the candle's price data changed after the
  check, the row under the header shows both closes and the chart marks the one the alert read.
- Beside the breadcrumb, links open the alert's newer and older triggers.
- Beside the chart, **{{msg:customAlerts.trigger.why}}**: {{learn:customAlerts.why}}
- **{{msg:customAlerts.trigger.next}}**: {{learn:customAlerts.outcome}} While the window runs it shows how many of its
  candles have closed and the change so far.
- "{{msg:customAlerts.trigger.ruleChanged}}" appears when the alert was edited later; the checklist keeps
  the old rule.

## What the page sends you

"What the page shows now" may include the alert name, ticker, interval and the checklist lines as
shown. Explain them; do not guess values that are not there.

## Things people get wrong

- A trigger reports that the rule matched on a completed candle; it is not a recommendation.
- "Stop this alert for …" stops it for this stock only; the alert keeps watching others.
