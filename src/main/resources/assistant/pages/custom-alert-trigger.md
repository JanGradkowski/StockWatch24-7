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

- Header: alert name, ticker, interval, "{{msg:customAlerts.trigger.candle}}", close, status
  **{{msg:home.chartAlertTriggered}}** or **{{msg:signals.matchedWhenApplied}}** (it already matched when the
  alert was saved with "{{msg:customAlerts.builder.notifyExistingShort}}"), "{{msg:customAlerts.trigger.conditionsMet}}",
  the watchlists it came from. Buttons **{{msg:customAlerts.trigger.openStock}}**,
  **{{msg:customAlerts.trigger.allTriggers}}**, **{{msg:customAlerts.trigger.edit}}**, and
  **{{msg:virtualTrades.moreActions}}** → **{{msg:customAlerts.trigger.markUnread}}**,
  "{{msg:customAlerts.trigger.stop}}".
- **{{msg:customAlerts.trigger.chartTitle}}**: {{learn:customAlerts.chart}} {{msg:customAlerts.trigger.chartHelp}}
- **{{msg:customAlerts.trigger.why}}**: {{learn:customAlerts.why}}
- **{{msg:customAlerts.trigger.next}}**: {{learn:customAlerts.outcome}}
- "{{msg:customAlerts.trigger.ruleChanged}}" appears when the alert was edited later; the checklist keeps
  the old rule.

## What the page sends you

"What the page shows now" may include the alert name, ticker, interval and the checklist lines as
shown. Explain them; do not guess values that are not there.

## Things people get wrong

- A trigger reports that the rule matched on a completed candle; it is not a recommendation.
- "Stop this alert for …" stops it for this stock only; the alert keeps watching others.
