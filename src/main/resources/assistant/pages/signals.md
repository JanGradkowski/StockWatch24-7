---
id: signals
title: sidebar.signals
routes: [/signals, /alerts/{id}]
templates: [all-signals]
suggestions: [assistant.suggest.signals.1, assistant.suggest.signals.2]
---
## What this page is for

{{msg:signals.subtitle.all}} The same page shows one watchlist's signals (`?watchlistId=`) or one ticker's
(`?ticker=`). One custom alert's triggers have their own page, `/custom-alerts/{id}`.

## What's on it

- Two figures for the list as filtered (whatever the tab): signals per day, and **{{msg:signals.kpi.outcome}}**:
  how many finished higher, the median, one dot per finished signal, and **{{msg:signals.results.bestLink}}**
  (the finished signals, best result first).
- **{{msg:signals.historyHeading}}**: tabs **{{msg:signals.state.all}}**, **{{msg:signals.state.unread}}**,
  **{{msg:signals.state.active}}** and **{{msg:signals.state.completed}}** with counts; a
  **{{msg:signals.column.ticker}}** field ("/" jumps there; {{msg:signals.tickerPlaceholder}});
  **{{msg:signals.typesButton}}** for pattern families, outlook changes and chart or custom alert triggers;
  **{{msg:signals.select}}** to tick rows, then **{{msg:signals.deleteSelected}}**. A filter shown above the list
  comes off with its ×. On phones the order is a list (**{{msg:signals.sortOption.newest}}**,
  **{{msg:signals.sortOption.best}}** and so on).
- The table: **{{msg:signals.column.ticker}}** (company underneath), **{{msg:signals.column.signal}}** (its kind,
  direction and interval underneath), **{{msg:signals.column.received}}**, **{{msg:signals.column.status}}** (with the
  setup score, e.g. 78/100) and **{{msg:signals.column.tradeOutcome}}**. The status dot: amber
  **{{msg:signals.legend.waitingName}}** (a pattern waiting for confirmation, worth a look), solid grey
  **{{msg:signals.legend.openName}}** (still measured), hollow **{{msg:signals.legend.doneName}}**; gains and losses are
  coloured on the outcome. "{{msg:signals.projectionOpen}}" under a cancelled pattern means its projection is still
  followed. "{{msg:signals.atSave}}": {{msg:signals.atSaveTitle}}.
  Click a column title to sort; signals are grouped by day. Custom alert triggers are marked
  **{{msg:signals.type.custom}}** with "{{msg:signals.conditionsMet}}"; chart alert triggers
  **{{msg:signals.type.chart}}**.
- **{{msg:signals.markAllRead}}**, and **{{msg:signals.howLink}}**: {{msg:signals.how.unread}}
  **{{msg:signals.column.setupScore}}** {{msg:signals.how.scoreText}} **{{msg:signals.column.tradeOutcome}}**
  {{msg:signals.how.outcomeText}}

## Things people get wrong

- A setup score is an evidence rating, not a probability of profit.
- Deleting removes a signal from the archive for good.
