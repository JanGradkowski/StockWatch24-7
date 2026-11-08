---
id: watchlists
title: sidebar.watchlists
routes: [/watchlists, /watchlists/{id}]
templates: [watchlists]
suggestions: [assistant.suggest.watchlists.1, assistant.suggest.watchlists.2, assistant.suggest.watchlists.3]
---
## What this page is for

Watchlists are the user's lists of stocks, ETFs and indexes. Monitoring lives in watchlists: signal
follows and custom alerts watch the instruments through them. {{msg:watchlists.subtitle.all}}

## All watchlists (`/watchlists`)

**{{msg:watchlists.create}}**, **{{msg:watchlists.recentImports}}**, **{{msg:watchlists.find}}**. Each list
card shows **{{msg:js.watchlists.stat.instruments}}**, **{{msg:js.watchlists.stat.followed}}**,
**{{msg:js.watchlists.stat.unread}}** and has **{{msg:js.watchlists.open}}**, **{{msg:js.watchlists.viewAllSignals}}**,
**{{msg:js.watchlists.addInstruments}}**, **{{msg:js.watchlists.editList}}**, **{{msg:js.watchlists.delete}}**,
**{{msg:js.watchlists.markAllRead}}** (some under **{{msg:js.watchlists.moreActions}}**).

## Creating or editing a list

**{{msg:js.watchlists.newTitle}}** / **{{msg:js.watchlists.editList}}**: **{{msg:js.watchlists.name}}**,
**{{msg:js.watchlists.description}}**, then **{{msg:js.watchlists.chooseInstruments}}** with tabs
**{{msg:js.watchlists.tab.search}}** (ticker or company), **{{msg:js.watchlists.tab.paste}}** ({{msg:js.watchlists.pasteHelp}})
and **{{msg:js.watchlists.tab.indexes}}** ({{msg:js.watchlists.browseIndexesHelp}} Then **{{msg:js.watchlists.mode.index}}**,
**{{msg:js.watchlists.mode.stocks}}** or **{{msg:js.watchlists.mode.both}}**). Also in the editor:
**{{msg:js.watchlistSettings.legend}}** (per signal type, direction and interval; **{{msg:js.watchlistSettings.watchAllTechnical}}**;
**{{msg:js.watchlistSettings.tickerHeading}}**: {{msg:js.watchlistSettings.tickerHelp}}),
**{{msg:js.watchlistSettings.allowedLegend}}** ({{msg:js.watchlistSettings.allowedHelp}} {{msg:js.watchlistSettings.customNote}})
and **{{msg:js.watchlistSettings.emailEnable}}**. **{{msg:js.watchlists.reviewChanges}}**, then
**{{msg:js.watchlists.saveChanges}}**. Large imports continue in the background.

## One list (`/watchlists/{id}`)

- **{{msg:js.watchlists.custom.title}}**: a card per custom alert applied here (click it for its
  triggers in this list; **{{msg:js.watchlists.custom.remove}}**), **{{msg:js.watchlists.custom.create}}**,
  and "{{msg:js.watchlists.custom.applyLabel}}" → **{{msg:js.watchlists.custom.apply}}**.
- **{{msg:js.watchlists.membersTitle}}** table with search, **{{msg:js.watchlists.instrumentType}}**,
  **{{msg:js.watchlists.sortBy}}**; columns include **{{msg:js.watchlists.column.price}}**,
  **{{msg:js.watchlists.column.outlook}}**, **{{msg:js.watchlists.column.following}}**,
  **{{msg:js.watchlists.column.unread}}**. Select rows to **{{msg:js.watchlists.viewSignals}}**,
  **{{msg:js.watchlists.manageFollows}}** (choose signals for just those, then **{{msg:js.watchlists.applyToSelected}}**)
  or **{{msg:js.watchlists.remove}}**.

## Things people get wrong

- If custom alerts are not allowed on a list, alerts cannot watch it: {{msg:js.watchlists.custom.notAllowed}}
- Allowing a signal type does not follow it; follows are chosen separately.
- Removing an instrument from its last list stops its alerts; history stays.
