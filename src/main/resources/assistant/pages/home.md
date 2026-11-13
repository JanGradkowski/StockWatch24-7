---
id: home
title: sidebar.home
routes: [/home]
templates: [home]
suggestions: [assistant.suggest.home.1, assistant.suggest.home.2, assistant.suggest.home.3]
---
## What this page is for

The dashboard: what is new since the user last looked, and shortcuts to everything else.

## What's on it

- Greeting and a status line ("{{msg:home.caughtUp}}" when nothing is unread). Buttons
  **{{msg:signals.all}}** (`/signals`) and **{{msg:home.alertSettings}}** (`/settings/analysis-alerts`).
- **{{msg:home.stocks.title}}**: {{msg:home.stocks.note}}.
- **{{msg:home.latestSignals}}** ({{msg:home.newSignalsKicker}}): the five newest unread pattern signals, outlook
  changes and chart alert triggers, each opening its report; the status dot works as on **{{msg:signals.all}}**. {{msg:home.aboutScoresText}}
- **{{msg:watchlists.yours}}** with **{{msg:watchlists.create}}**.
- **{{msg:activitySignals.eyebrow}}** ({{msg:home.tickerIntro}}): **{{msg:home.latestNotifications}}**,
  congressional and insider filings with **{{msg:home.markAsRead}}**, **{{msg:home.openStock}}**,
  **{{msg:signals.markAllRead}}**, and **{{msg:activitySignals.heading}}**.
- **{{msg:sidebar.customAlerts}}** panel ({{msg:home.custom.kicker}}): {{learn:customAlerts.dashboard}}
  **{{msg:home.custom.create}}** in its header and **{{msg:home.custom.manage}}** under the list; without alerts it offers three examples
  and **{{msg:home.custom.moreExamples}}**. Each alert shows "{{msg:home.custom.matching}}".
- **{{msg:sidebar.demo}}** summary with **{{msg:home.demo.open}}**.
- The status line under the greeting links each unread count to its list. **{{msg:watchlists.yours}}** shows
  how many instruments and signal alerts are followed. {{msg:home.aboutMonitoringText}}
- **{{msg:js.watchlists.manageFollows}}** on a signal opens **{{msg:home.manageRules}}**: stop following
  some rules of that company ({{msg:home.manageRulesNote}}).

## Things people get wrong

- Read signals leave the dashboard but stay in **{{msg:signals.all}}** and **{{msg:activitySignals.heading}}**.
- Signals appear only for what the user follows: patterns and outlooks are followed per stock (stock
  page) or per watchlist; custom alerts apply through watchlists.

## Related pages

`/signals`, `/ticker-alerts`, `/custom-alerts`, `/watchlists`, `/virtual-trades`.
