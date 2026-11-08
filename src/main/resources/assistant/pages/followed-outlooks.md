---
id: followed-outlooks
title: sidebar.outlooks
routes: [/technical-watchlist]
templates: [technical-watchlist]
suggestions: [assistant.suggest.outlooks.1, assistant.suggest.outlooks.2]
---
## What this page is for

{{msg:technicalWatchlist.subtitle}} The automated technical outlook combines many indicators into one
buy, neutral or sell reading with a score from −100 to +100, per interval.

## What's on it

- **{{msg:technicalWatchlist.atAGlance}}**: **{{msg:technicalWatchlist.strongestBuys}}** and
  **{{msg:technicalWatchlist.strongestSells}}** (top 5) for the chosen interval, and
  **{{msg:technicalWatchlist.latestChanges}}**. {{msg:technicalWatchlist.rankingFootnote}}
- **{{msg:technicalWatchlist.followedTickers}}** with filters **{{msg:technicalWatchlist.watchlist}}**,
  **{{msg:technicalWatchlist.searchTickers}}**, **{{msg:technicalWatchlist.followedInterval}}**,
  **{{msg:technicalWatchlist.outlook}}** and **{{msg:technicalWatchlist.sortBy}}**; per ticker the daily,
  weekly and monthly outlook, the price since the last change, and **{{msg:js.technicalWatchlist.unfollow}}**.
  **{{msg:technicalWatchlist.unfollowAll}}** stops every outlook follow.
- {{msg:technicalWatchlist.footer}}

## How to follow an outlook

{{msg:technicalWatchlist.emptyText}} On the stock page this is **{{msg:stock.followOutlook}}** in tab
**{{msg:activityDetail.tab.technical}}**, then **{{msg:js.watchlistPicker.apply}}**. A watchlist can also
follow outlooks for all its members. To be told only when the outlook reaches a strength (for example
strong buy) or combine it with other conditions, use a custom alert with an
**{{msg:js.ca.picker.outlook.title}}** condition.
