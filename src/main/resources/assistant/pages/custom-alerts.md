---
id: custom-alerts
title: sidebar.customAlerts
routes: [/custom-alerts]
templates: [custom-alerts]
suggestions: [assistant.suggest.customAlerts.1, assistant.suggest.customAlerts.2, assistant.suggest.customAlerts.3]
---
## What this page is for

All of the user's custom alerts in one place: create, switch on or pause, edit, duplicate, delete, and
see what each one watches and finds.

## What's on it

- Header: **{{msg:customAlerts.new}}** (opens the builder) and **{{msg:signals.markAllRead}}**
  (marks every custom alert trigger read).
- **{{msg:customAlerts.guide.title}}** — a collapsible four-step guide.
- **{{msg:customAlerts.yours}}** with tabs **{{msg:customAlerts.tab.all}}**, **{{msg:customAlerts.tab.active}}**,
  **{{msg:customAlerts.tab.paused}}**, **{{msg:customAlerts.tab.chart}}**, and **{{msg:customAlerts.search}}**
  (by name, list or ticker).
- Each alert card: name, the rule as chips joined by AND / OR / THEN, interval, trigger mode, email
  ("{{msg:js.ca.card.noEmail}}" when off), "{{msg:js.ca.card.appliedTo}}" with its watchlists, how many stocks
  "{{msg:js.ca.card.matchingNow}}" (click it for every stock with its state), triggers and
  "{{msg:js.ca.card.lastTriggered}}". Controls: the on/paused switch ({{learn:customAlerts.active}}),
  **{{msg:js.ca.card.edit}}**, and **{{msg:js.ca.menu.title}}** → **{{msg:js.ca.menu.duplicate}}**,
  "{{msg:js.ca.menu.duplicateAs}}" (as weekly/monthly), **{{msg:js.ca.menu.delete}}** (it offers
  **{{msg:js.ca.delete.pauseInstead}}**). Clicking the card opens all its triggers (`/signals?customAlertId=…`).
- Stock states: **{{msg:js.ca.state.MATCHING}}**, **{{msg:js.ca.state.NOT_MATCHING}}**,
  **{{msg:js.ca.state.CHECKING}}**, **{{msg:js.ca.state.WAITING}}**, and a stock can be stopped for one alert.
- **{{msg:customAlerts.tab.chart}}**: {{msg:js.ca.chart.intro}} Each has **{{msg:js.ca.chart.open}}** and,
  for indicator and horizontal-line alerts, **{{msg:js.ca.chart.convert}}** ({{learn:customAlerts.convert}})
- With no alerts: **{{msg:js.ca.empty.title}}**, the examples (**{{msg:js.ca.templates.title}}**) and
  **{{msg:js.ca.empty.scratch}}**.

## Things people get wrong

- "{{msg:js.ca.card.notApplied}}" — edit it and tick a watchlist in step 4.
- After saving, "{{msg:js.ca.list.saved}}"
- Alerts read completed candles only, so nothing triggers during the trading day.
- Pausing keeps the triggers; deleting removes the alert.

## Related pages

`/custom-alerts/new` (builder), `/signals?customAlertId=…` (one alert's triggers), `/watchlists`.
