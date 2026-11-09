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

- Header: **{{msg:customAlerts.new}}** (opens the builder; its arrow opens **{{msg:customAlerts.fromExample}}**),
  **{{msg:signals.markAllRead}}** when there are unread triggers, and **{{msg:js.ca.ask}}**, which opens this assistant.
  The link **{{msg:customAlerts.guide.title}}** shows a four-step guide.
- Filters **{{msg:customAlerts.tab.all}}**, **{{msg:customAlerts.tab.active}}** and **{{msg:customAlerts.tab.paused}}**
  with counts, how many active slots are used, and **{{msg:customAlerts.search}}** (by name, list or ticker; "/" jumps
  there, "n" starts a new alert, j and k move between alerts, e edits the one focused).
- Each alert is a row: its name (opens the alert's own page), unread triggers, the timeframe and watchlists, the rule as
  chips joined by AND / OR / THEN, and three counts: "{{msg:js.ca.card.matching}}" (click it for every stock with its
  state), "{{msg:js.ca.card.triggers.other}}" (opens the alert's page) and "{{msg:js.ca.card.lastTrigger}}". Controls:
  **{{msg:js.ca.card.edit}}**, the on/paused switch ({{learn:customAlerts.active}}) and a menu with
  **{{msg:js.ca.menu.duplicate}}**, "{{msg:js.ca.menu.duplicateAsLabel}}" a daily, weekly or monthly copy,
  pin, email on/off, and **{{msg:js.ca.menu.deleteEllipsis}}** (it offers **{{msg:js.ca.delete.pauseInstead}}**).
- Stock states: **{{msg:js.ca.state.MATCHING}}**, **{{msg:js.ca.state.HALFWAY}}**, **{{msg:js.ca.state.NOT_MATCHING}}**,
  **{{msg:js.ca.state.CHECKING}}**, **{{msg:js.ca.state.WAITING}}**; **{{msg:js.ca.states.stop}}** stops the alert for
  one stock.
- **{{msg:customAlerts.chartAlerts.title}}** under the list: {{msg:customAlerts.chartAlerts.lead}} Each has
  **{{msg:js.ca.chart.open}}** and, for indicator and horizontal-line alerts, **{{msg:js.ca.chart.convert}}**
  ({{learn:customAlerts.convert}})
- With no alerts: **{{msg:js.ca.empty.title}}** with **{{msg:js.ca.templates.blank}}** and a button for this
  assistant, then the examples (**{{msg:js.ca.templates.title}}**), each with **{{msg:js.ca.templates.use}}**.

## Things people get wrong

- "{{msg:js.ca.card.notApplied}}" — edit it and tick a watchlist in step 4.
- After saving, "{{msg:js.ca.list.saved}}"
- Alerts read completed candles only, so nothing triggers during the trading day.
- Pausing keeps the triggers; deleting removes the alert.

## Related pages

`/custom-alerts/new` (builder), `/custom-alerts/{id}` (one alert and its triggers), `/watchlists`.
