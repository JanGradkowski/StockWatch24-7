---
id: stock
title: stock.instrumentStock
routes: [/stock/{symbol}]
templates: [stock]
suggestions: [assistant.suggest.stock.1, assistant.suggest.stock.2, assistant.suggest.stock.3]
---
## What this page is for

One instrument's workspace (the path holds its ticker, e.g. `/stock/AAPL`): chart, signal follows,
custom alerts and filings for it.

## Header

Price and **{{msg:stock.lastClose}}**, **{{msg:stock.addToWatchlist}}**, **{{msg:stock.newAlert}}** (opens the
custom alert builder for this stock), and **{{msg:js.virtualTrade.marker.buy}}** / **{{msg:js.virtualTrade.marker.sell}}**
({{msg:stock.demoTradeHeading}}).

## Tab {{msg:outlookChange.tab.chart}}

- Toolbar: **{{msg:stock.view}}** (chart type: candles, Heikin Ashi, bars, line, area, Renko and more),
  **{{msg:stock.indicators}}**, **{{msg:stock.compare}}** ({{msg:stock.compareHelp}}), **{{msg:stock.volumeOn}}**,
  **{{msg:stock.buyShortSignals}}** (overlays: candlestick signals, Elliott structures, harmonic formations),
  **{{msg:stock.chartTools}}** (**{{msg:stock.anchoredProfile}}**, **{{msg:stock.splitMarkers}}**,
  **{{msg:stock.chartAlerts}}**), and **{{msg:signals.column.interval}}** Daily/Weekly/Monthly.
- Side tools: **{{msg:stock.drawingTools}}**, **{{msg:stock.dataWindow}}**, **{{msg:stock.screenshot}}**,
  **{{msg:stock.fullscreen}}**; below the chart, ranges, **{{msg:stock.goToDate}}** and scale buttons.
- **Chart alerts** — a simple alert on one chart line: right-click a drawn line (trend line, ray,
  horizontal line, channel, rectangle, price label) or use an indicator's menu. The dialog has
  **{{msg:js.alert.field.name}}**, **{{msg:js.alert.field.condition}}** (e.g. "{{msg:js.alert.condition.DRAWING.CROSSES_UP}}"),
  **{{msg:js.alert.field.interval}}**, **{{msg:js.alert.field.frequency}}** ({{msg:js.alert.frequency.ONCE}} /
  {{msg:js.alert.frequency.EVERY_BAR}}), for indicators **{{msg:js.alert.field.compare}}**, then
  **{{msg:js.alert.action.create}}**. {{msg:js.alert.dialog.closeHint}} **{{msg:stock.chartAlerts}}** lists
  them. They are also on `/custom-alerts` → **{{msg:customAlerts.tab.chart}}**, where
  **{{msg:js.ca.chart.convert}}** turns one into a full custom alert. For several conditions or many
  stocks, use a custom alert instead.
- **{{msg:stock.findHistorical}}**: past candlestick patterns of this stock, as **{{msg:stock.resultsTable}}**
  or **{{msg:stock.onChart}}**.

## Tab {{msg:activityDetail.tab.technical}}

- **{{msg:stock.technicalOutlook}}** summary with **{{msg:stock.overview.fullReport}}** (the outlook page).
- **{{msg:stock.alertsHeading}}**: {{msg:stock.alertsHelp}} Sections **{{msg:stock.candleHeading}}**,
  **{{msg:stock.elliottHeading}}**, **{{msg:stock.harmonicHeading}}**, each with buy/sell (bullish/bearish)
  switches per interval, and **{{msg:home.automatedOutlook}}** with **{{msg:stock.followOutlook}}**
  ({{msg:stock.outlookAlertHelp}}). Changes wait for **{{msg:js.watchlistPicker.apply}}**, which asks which
  watchlists they belong to.
- **{{msg:stock.customAlerts.heading}}**: {{learn:customAlerts.stockSection}} Then
  **{{msg:stock.customAlerts.save}}**; **{{msg:stock.customAlerts.create}}** opens the builder for this stock.

## Tab {{msg:activitySignals.eyebrow}}

Stocks only. **{{msg:stock.congressTitle}}** with **{{msg:stock.followCongress}}** and
**{{msg:stock.showCongressHistory}}**; **{{msg:home.insiderTitle}}** with **{{msg:stock.followInsider}}** and
**{{msg:stock.showInsiderHistory}}**. {{msg:stock.tickerUnavailable}} for indexes and ETFs.

## Things people get wrong

- Following a signal type on this page versus a custom alert: follows send the app's built-in pattern
  signals; a custom alert is the user's own rule. Both need the stock in a watchlist.
- Leaving with unapplied follow changes asks: **{{msg:stock.saveAndLeave}}** / **{{msg:stock.discardChanges}}**.
