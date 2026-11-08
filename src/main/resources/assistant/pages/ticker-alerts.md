---
id: ticker-alerts
title: sidebar.tickerAlerts
routes: [/ticker-alerts, /activity-signals, /activity-signals/**]
templates: [all-activity-signals, activity-signal-detail]
suggestions: [assistant.suggest.tickerAlerts.1, assistant.suggest.tickerAlerts.2]
---
## What this page is for

Insider and congressional trades in stocks the user follows for them. `/ticker-alerts` shows the unread
ones (as on the dashboard); **{{msg:activitySignals.heading}}** (`/activity-signals`) keeps the whole
history; each alert opens a detail page.

## What's on it

- Unread lists: **{{msg:home.congressKicker}}** and **{{msg:home.insiderTitle}}**, each with
  **{{msg:home.markAsRead}}**, **{{msg:home.openStock}}**, **{{msg:activityDetail.officialFiling}}**, and the
  stocks followed. **{{msg:activitySignals.directionalReturn}}** measures how the price moved in the trade's
  direction since the filing.
- **{{msg:activitySignals.heading}}**: **{{msg:activitySignals.historyHeading}}** sorted by
  **{{msg:activitySignals.sort.date}}**, ticker, company, buy/sale, type or name; delete selected.
- Detail page: **{{msg:signals.transactionDetails}}**, **{{msg:activityDetail.openFiling}}**,
  **{{msg:activityDetail.snapshotTitle}}**, **{{msg:activityDetail.relatedTitle}}**, **{{msg:activityDetail.resultsTitle}}**.

## How to follow a stock's filings

On the stock page, tab **{{msg:activitySignals.eyebrow}}**: **{{msg:stock.followCongress}}** and/or
**{{msg:stock.followInsider}}** (stocks only; follows are limited per account), or in a watchlist's
**{{msg:js.watchlistSettings.tickerHeading}}**. To combine filings with other conditions (for example
"insiders buying while RSI is low"), build a custom alert with an insider or Congress condition instead.

## Things people get wrong

- {{msg:stock.congressSource}}
- Insider data covers the latest filings per check, so busy periods may have gaps.
