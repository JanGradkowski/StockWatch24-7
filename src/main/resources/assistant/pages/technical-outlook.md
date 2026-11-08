---
id: technical-outlook
title: home.automatedOutlook
routes: [/stock/{symbol}/technical-outlook, /technical-outlook/changes/{id}]
templates: [technical-outlook, technical-outlook-change]
suggestions: [assistant.suggest.outlook.1, assistant.suggest.outlook.2]
---
## What this page is for

`/stock/{symbol}/technical-outlook` is the full **{{msg:home.automatedOutlook}}** report of one stock for
Daily, Weekly or Monthly candles. `/technical-outlook/changes/{id}` reports one change of that outlook
(the previous completed candle versus the current one).

## Outlook report

- The overall reading (buy / neutral / sell and its strength) with a score on a ±100 scale; interval
  tabs; **{{msg:outlook.editPeriods}}** ({{msg:outlook.periodsTitle}}, **{{msg:js.outlook.saveAndRecalculate}}**;
  **{{msg:outlook.openSettings}}** goes to `/settings/analysis-alerts`).
- **{{msg:outlook.priceChartTitle}}**, **{{msg:outlook.indicatorVotes}}**, **{{msg:outlook.indicatorAnalysis}}**,
  **{{msg:outlook.recentSignals}}**, **{{msg:outlook.relativeStrength}}**, **{{msg:outlook.methodology}}**.
- **{{msg:outlook.recordDemoTrade}}** (**{{msg:js.virtualTrade.marker.buy}}** / **{{msg:js.virtualTrade.marker.sell}}**).

## Change report

Tabs **{{msg:outlookChange.tab.chart}}** and **{{msg:outlookChange.tab.report}}**: **{{msg:outlookChange.categoryTitle}}**,
**{{msg:outlookChange.indicatorTitle}}** (with **{{msg:outlookChange.showUnchangedStatic}}**),
**{{msg:outlookChange.signalsTitle}}**, and **{{msg:outlookChange.openLive}}**.

## Things people get wrong

- The outlook summarises indicators; it is not advice and not a probability. Indicator periods are the
  user's own analysis settings.
