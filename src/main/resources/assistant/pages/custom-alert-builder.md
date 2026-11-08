---
id: custom-alert-builder
title: customAlerts.builder.new
routes: [/custom-alerts/new, /custom-alerts/{id}/edit]
templates: [custom-alert-builder]
suggestions: [assistant.suggest.builder.1, assistant.suggest.builder.2, assistant.suggest.builder.3]
---
## What this page is for

Building or editing one custom alert. The custom alert reference above describes every section and
field of this page in order; use it for the steps. A new alert can start empty, from an example
(`/custom-alerts/new?template=…`), for one stock (`?symbol=AAPL`: the stock is preselected and used for
the preview), for one watchlist, or from a chart alert ("{{msg:js.ca.chart.convert}}").

## What the page sends you

"What the page shows now" holds the rule as currently edited: `rule` (the alertDraft format),
`saved` (false for a new alert), `testStock` and `preview` (whether the test stock matches now, how many
conditions are met, each condition's reading), and `status` (a message under the Save button, such as a
validation problem). Use them to answer "why does it not match?" or "change it to…".

## Things people get wrong

- Nothing is watched until a watchlist is ticked in **{{msg:customAlerts.builder.step.where}}**: the
  preview's **{{msg:customAlerts.preview.testOn}}** stock is only for testing.
- Several conditions joined with **{{msg:js.ca.builder.allConditions}}** must be true on the same check;
  with **{{msg:js.ca.window.label.LATEST}}** on each, they must happen on the same candle. Widen a window
  ("{{msg:js.ca.window.label.ANY}}") when events can be a few candles apart.
- "{{msg:js.ca.preview.noHistory}}" means the rule was never true on the test stock in about two years:
  loosen a threshold or widen a window.
- **{{msg:js.ca.builder.inOrderBlocked}}** — outlook, insider and Congress conditions cannot be steps.
- Leaving the page with unsaved changes asks for confirmation; nothing is saved until
  **{{msg:customAlerts.builder.save}}**.

## Related pages

`/custom-alerts` (all alerts), `/watchlists` (lists the alert can watch), `/settings/analysis-alerts`
(email switches; the **{{msg:analysis.email.customAlerts}}** switch there must be on for alert emails).
