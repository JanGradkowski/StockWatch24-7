---
id: settings
title: sidebar.settings
routes: [/settings, /settings/**]
templates: [settings]
suggestions: [assistant.suggest.settings.1, assistant.suggest.settings.2, assistant.suggest.settings.3]
---
## What this page is for

Account and analysis settings. Its own menu has *{{msg:settings.group.account}}*
(**{{msg:settings.nav.general}}** `/settings`, **{{msg:settings.nav.appearance}}** `/settings/appearance`,
**{{msg:settings.nav.language}}** `/settings/language`) and *{{msg:settings.group.analysis}}*
(**{{msg:settings.nav.analysis}}** `/settings/analysis-alerts`, **{{msg:settings.nav.scoring}}** `/settings/scoring`,
**{{msg:settings.nav.candlestick}}** `/settings/candlestick-patterns`, **{{msg:settings.nav.elliott}}**
`/settings/elliott-waves`, **{{msg:settings.nav.harmonic}}** `/settings/harmonic-formations`,
**{{msg:settings.nav.detection}}** `/settings/detection`).

## Pages

- **{{msg:settings.nav.general}}**: {{msg:settings.description.general}} — **{{msg:settings.password.heading}}**
  (an emailed eight-digit code first), **{{msg:settings.mfa.heading}}** (**{{msg:settings.mfa.begin}}**),
  **{{msg:settings.sessions.heading}}** (**{{msg:settings.sessions.revoke}}**), **{{msg:settings.danger.heading}}**
  (**{{msg:settings.danger.export}}**, **{{msg:settings.danger.delete}}**: {{msg:settings.danger.deleteHelp}}).
- **{{msg:settings.nav.appearance}}**: **{{msg:settings.appearance.colorMode}}** (dark/light) and colours of the
  Elliott Wave and harmonic chart overlays; **{{msg:settings.apply}}**.
- **{{msg:settings.nav.language}}**: {{msg:settings.language.pageDescription}} (English, Polish or
  **{{msg:settings.language.auto}}**).
- **{{msg:settings.nav.analysis}}**: **{{msg:settings.alertEmails.heading}}** (**{{msg:settings.alertEmails.pause}}**;
  delivery **{{msg:settings.alertEmails.immediate}}** or **{{msg:settings.alertEmails.digest}}**),
  **{{msg:analysis.email.heading}}** with switches under **{{msg:analysis.email.newActivity}}**
  (**{{msg:analysis.email.candlestick}}**, **{{msg:analysis.email.elliott}}**, **{{msg:analysis.email.harmonic}}**,
  **{{msg:analysis.email.insider}}**, **{{msg:analysis.email.congressional}}**, **{{msg:analysis.email.chartAlerts}}**,
  **{{msg:analysis.email.customAlerts}}**), **{{msg:analysis.email.resolution}}**, **{{msg:analysis.email.delivery}}**
  (**{{msg:analysis.email.signals}}**, directions); and **{{msg:analysis.profile.heading}}** for the outlook's
  indicator periods and Elliott lifecycle tracking.
- **{{msg:settings.nav.scoring}}**: {{msg:settings.description.scoring}}
- **{{msg:settings.nav.candlestick}}**: {{msg:settings.description.candlestick}}
- **{{msg:settings.nav.elliott}}**: {{msg:settings.description.elliott}}
- **{{msg:settings.nav.harmonic}}**: {{msg:settings.description.harmonic}}
- **{{msg:settings.nav.detection}}**: {{msg:settings.description.detection}}

## Things people get wrong

- No emails at all: check **{{msg:settings.alertEmails.heading}}** is not paused, then the switch for the
  signal type, then (for watchlist follows) the list's **{{msg:js.watchlistSettings.emailEnable}}** or (for
  custom alerts) the alert's own email box. Everything still appears on the website.
- Pattern, scoring and detection settings change how future signals and custom alert pattern conditions
  are detected for this account only.
