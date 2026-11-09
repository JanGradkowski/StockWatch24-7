---
id: custom-alert
title: customAlerts.alert.triggers
routes: [/custom-alerts/{id}]
templates: [custom-alert]
suggestions: [assistant.suggest.alert.1, assistant.suggest.alert.2]
---
## What this page is for

One custom alert: its rule in words, how its triggers did afterwards, the stocks it watches, and every
trigger, newest first. Older links to one alert's triggers (`/signals?customAlertId=…`) open this page.

## What's on it

- Header: the alert's name (with **{{msg:customAlerts.status.paused}}** when paused), its timeframe, watchlists,
  how it fires and whether it emails. The on/paused switch ({{learn:customAlerts.active}}),
  **{{msg:customAlerts.archive.edit}}**, and a menu with **{{msg:js.ca.menu.duplicate}}**, email on/off and
  **{{msg:js.ca.menu.deleteEllipsis}}**.
- The rule as one sentence.
- Three figures: **{{msg:customAlerts.alert.kpi.triggers}}** (and how many are unread),
  **{{msg:customAlerts.alert.kpi.matching}}** ({{learn:customAlerts.matching}} Its link opens the stocks the alert
  watches, each with its state and **{{msg:js.ca.states.stop}}**), and the median price change a set number of
  candles after the triggers with how often the close was higher ({{learn:customAlerts.outcome}} Until the first
  trigger is that old it says **{{msg:customAlerts.alert.kpi.measuring}}** and roughly when the first result comes).
- **{{msg:customAlerts.alert.triggers}}**: filters **{{msg:customAlerts.alert.filter.all}}**,
  **{{msg:customAlerts.alert.filter.unread}}**, **{{msg:customAlerts.alert.filter.higher}}** and
  **{{msg:customAlerts.alert.filter.lower}}** (measured triggers, by how they ended), **{{msg:customAlerts.alert.searchTicker}}** ("/" jumps there),
  **{{msg:signals.markAllRead}}** and **{{msg:customAlerts.alert.select}}** (tick triggers, then
  **{{msg:customAlerts.alert.bulkDelete}}**). The table: ticker (a dot when unread), company, when it fired,
  and the change 1, 5 and 10 candles after it (those inside the follow-up window; while a trigger is still measured,
  its change so far stands in the next column to come, marked "{{msg:customAlerts.alert.changeSoFar}}"
  ("{{msg:customAlerts.alert.pending}}" until a candle has closed), and on phones one figure says
  "{{msg:customAlerts.alert.changeSoFar}}" or after how many candles it was taken). Click a column title to sort, a row to open the trigger.
- Stocks that already matched when the alert was saved (with
  "{{msg:customAlerts.builder.notifyExistingShort}}") are reported together; that batch is one row,
  "{{msg:js.ca.alert.batchTitle}}", which opens to show them.

## Things people get wrong

- {{msg:customAlerts.archive.disclaimer}}
- "{{msg:customAlerts.archive.notApplied}}" — edit the alert and tick a watchlist in step 4.
- Pausing keeps the triggers; deleting removes the alert and its triggers.

## Related pages

`/custom-alerts` (all alerts), `/custom-alerts/{id}/edit` (the builder), `/custom-alerts/triggers/{id}` (one trigger).
