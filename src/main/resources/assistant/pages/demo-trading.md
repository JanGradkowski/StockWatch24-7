---
id: demo-trading
title: sidebar.demo
routes: [/virtual-trades, /virtual-trades/{id}]
templates: [virtual-trades, virtual-trade]
suggestions: [assistant.suggest.demo.1, assistant.suggest.demo.2]
---
## What this page is for

Practice trades with play money: {{msg:sidebar.demo.title}}. Nothing here places a real order.

## What's on it

- `/virtual-trades`: **{{msg:virtualTrades.portfolioHeading}}** (portfolio value of sized buy trades),
  **{{msg:virtualTrades.methodTitle}}**, and **{{msg:virtualTrades.historyHeading}}** with sorting and
  delete.
- `/virtual-trades/{id}`: one trade with tabs **{{msg:virtualTrade.tab.general}}**,
  **{{msg:virtualTrade.tab.technical}}** and **{{msg:virtualTrade.tab.results}}**; **{{msg:virtualTrade.chartTitle}}**,
  **{{msg:js.virtualTrade.close}}**.

## How to create one

On a stock page (header) or an outlook report press **{{msg:js.virtualTrade.marker.buy}}** or
**{{msg:js.virtualTrade.marker.sell}}**; from a signal's plan use **{{msg:signalDetail.practiceTitle}}**. The
dialog **{{msg:js.outlook.createDemoTrade}}** asks for the size: **{{msg:virtualTrades.percentageOnly}}**,
**{{msg:outlook.sizeQuantity}}** or **{{msg:outlook.sizeAmount}}** ({{learn:demo.size}}), and an optional
**{{msg:virtualTrade.planLegend}}** with **{{msg:virtualTrade.stopPrice}}** and **{{msg:virtualTrade.targetPrice}}**
({{msg:virtualTrade.planHelp}}).
