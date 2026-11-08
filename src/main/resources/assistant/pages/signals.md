---
id: signals
title: sidebar.signals
routes: [/signals, /alerts/{id}]
templates: [all-signals]
suggestions: [assistant.suggest.signals.1, assistant.suggest.signals.2]
---
## What this page is for

{{msg:signals.subtitle.all}} The same page shows one watchlist's signals (`?watchlistId=`), one ticker's
(`?ticker=`), or one custom alert's triggers (`?customAlertId=`).

## What's on it

- Filters: **{{msg:signals.types}}**, **{{msg:signals.showSignals}}** (**{{msg:signals.state.all}}**,
  **{{msg:signals.state.unread}}**, **{{msg:signals.state.active}}**, **{{msg:signals.state.completed}}**),
  **{{msg:signals.column.ticker}}** ({{msg:signals.tickerPlaceholder}}), **{{msg:common.sortBy}}**,
  **{{msg:common.order}}**, then **{{msg:common.applyFilters}}** or **{{msg:technicalWatchlist.clearFilters}}**.
- **{{msg:signals.historyHeading}}** table: **{{msg:signals.column.signal}}**, **{{msg:signals.column.ticker}}**,
  **{{msg:signals.column.interval}}**, **{{msg:signals.column.received}}**, **{{msg:signals.column.status}}**,
  **{{msg:signals.column.setupScore}}**, **{{msg:signals.column.tradeOutcome}}**. Custom alert triggers are
  marked **{{msg:signals.type.custom}}** with "{{msg:signals.conditionsMet}}"; chart alert triggers
  **{{msg:signals.type.chart}}**.
- **{{msg:signals.markAllRead}}**, **{{msg:signals.selectPage}}**, **{{msg:signals.deleteSelected}}**.
- **{{msg:signals.howItWorks}}**: {{msg:signals.how.unread}} **{{msg:signals.column.setupScore}}**
  {{msg:signals.how.scoreText}} **{{msg:signals.column.tradeOutcome}}** {{msg:signals.how.outcomeText}}

## One custom alert's triggers (`?customAlertId=`)

The header shows **{{msg:customAlerts.archive.rule}}**, **{{msg:customAlerts.archive.appliedTo}}**,
**{{msg:customAlerts.archive.matchingNow}}** ({{learn:customAlerts.matching}}), triggers in total, how often
the stock "{{msg:customAlerts.archive.higherAfter}}" and the **{{msg:customAlerts.archive.median}}**, plus
**{{msg:customAlerts.archive.edit}}** and **{{msg:customAlerts.archive.pause}}** / **{{msg:customAlerts.archive.resume}}**.
{{msg:customAlerts.archive.disclaimer}}

## Things people get wrong

- A setup score is an evidence rating, not a probability of profit.
- Deleting removes a signal from the archive for good.
