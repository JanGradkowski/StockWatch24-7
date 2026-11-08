# Site map

The main navigation (left sidebar; a menu button on phones and tablets) has **{{msg:sidebar.home}}**,
group *{{msg:sidebar.group.monitor}}* (**{{msg:sidebar.watchlists}}**, **{{msg:sidebar.customAlerts}}**,
**{{msg:sidebar.outlooks}}**), group *{{msg:sidebar.group.signals}}* (**{{msg:sidebar.signals}}**,
**{{msg:sidebar.tickerAlerts}}**), group *{{msg:sidebar.group.practice}}* (**{{msg:sidebar.demo}}**), then
**{{msg:sidebar.settings}}**, **{{msg:sidebar.help}}** and the **{{msg:sidebar.learning}}** switch
({{msg:sidebar.learning.hint}}). The search box in the top bar finds stocks, ETFs and indexes and opens
their stock page. Every page id below can be used in `needPage`.

- `home` — `/home`, **{{msg:sidebar.home}}**: the dashboard. Latest unread signals, custom alert
  triggers, ticker alerts, watchlists, demo-trading summary and counts.
- `watchlists` — `/watchlists` and `/watchlists/{id}`: create and edit watchlists (lists of stocks, ETFs,
  indexes), add instruments (search, paste a list, or a market index), choose which signals each list
  follows, its allowed signal types and email switch; custom alerts applied to a list.
- `custom-alerts` — `/custom-alerts`: the user's custom alerts and chart alerts; switch on/pause,
  edit, duplicate, delete, see which stocks match now; starting examples.
- `custom-alert-builder` — `/custom-alerts/new`, `/custom-alerts/{id}/edit`: the builder (rule,
  when it fires, which stocks, email, preview).
- `custom-alert-trigger` — `/custom-alerts/triggers/{id}`: one trigger: why it fired, chart, what the
  price did next.
- `followed-outlooks` — `/technical-watchlist`, **{{msg:sidebar.outlooks}}**: automated technical
  outlooks (buy/neutral/sell readings from many indicators) the user follows, with rankings and changes.
- `technical-outlook` — `/stock/{symbol}/technical-outlook` and `/technical-outlook/changes/{id}`: one
  stock's full outlook report, and the report of one outlook change.
- `signals` — `/signals`, **{{msg:sidebar.signals}}**: the archive of technical signals (candlestick,
  Elliott Wave, harmonic, outlook changes, chart alert and custom alert triggers) with filters. With
  `?customAlertId=` it shows one custom alert's triggers.
- `signal-detail` — `/alerts/signals/{id}` and historical pattern pages under `/stock/{symbol}/…`: one
  signal's report: chart, score, trade plan, outcome.
- `ticker-alerts` — `/ticker-alerts`, `/activity-signals`: insider and congressional trades in stocks the
  user follows, and their history.
- `stock` — `/stock/{symbol}` (e.g. `/stock/AAPL`): the stock workspace: chart with indicators,
  drawings and chart alerts; tab **{{msg:activityDetail.tab.technical}}** with signal follows, outlook
  follows and custom alerts for this stock; tab **{{msg:activitySignals.eyebrow}}** for insider and
  Congress follows; demo trades.
- `demo-trading` — `/virtual-trades`, **{{msg:sidebar.demo}}**: practice trades with play money, no
  real orders.
- `settings` — `/settings/…`: account (general, appearance, language) and analysis settings (alert
  emails at `/settings/analysis-alerts`, scoring, candlestick patterns, Elliott waves, harmonic
  formations, detection).
- `about` — `/about`, **{{msg:sidebar.help}}**: how StockWatch works, detector rules, glossary, limits.

## Questions asked from any page

- **No alert emails?** In this order: **{{msg:sidebar.settings}}** → **{{msg:settings.nav.analysis}}**
  (`/settings/analysis-alerts`): is **{{msg:settings.alertEmails.heading}}** paused (then
  **{{msg:settings.alertEmails.resume}}**)? Is the switch for that kind of alert on under
  **{{msg:analysis.email.newActivity}}** (for custom alerts **{{msg:analysis.email.customAlerts}}**)? For a custom
  alert, is **{{msg:customAlerts.builder.email}}** ticked in the alert? For followed signals, does the watchlist have
  **{{msg:js.watchlistSettings.emailEnable}}**? Everything also appears on the website.
- **Where do triggers and signals appear?** Unread ones on **{{msg:sidebar.home}}**; all of them in
  **{{msg:sidebar.signals}}** (`/signals`), insider and Congress trades in **{{msg:sidebar.tickerAlerts}}**; one custom
  alert's triggers by clicking it on `/custom-alerts`.
