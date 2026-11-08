---
id: signal-detail
title: signals.column.signal
routes: [/alerts/signals/{id}, /stock/{symbol}/candlestick-patterns/**, /stock/{symbol}/elliott-waves/**, /stock/{symbol}/harmonic-formations/**]
templates: [signal-detail, historical-candlestick-detail, historical-elliott-detail, historical-harmonic-detail]
suggestions: [assistant.suggest.signalDetail.1, assistant.suggest.signalDetail.2]
---
## What this page is for

The report of one detected pattern (candlestick, Elliott Wave or harmonic), either a signal the user
received (`/alerts/signals/{id}`) or a historical detection opened from a stock's chart or
**{{msg:stock.findHistorical}}**.

## What's on it

- The chart around the signal with its annotations, and **{{msg:signalDetail.marketSnapshot}}** /
  **{{msg:detail.snapshot}}**.
- **{{msg:detail.planTitle}}** (entry, stop, target, risk-to-reward) — for Elliott
  **{{msg:signalDetail.elliottPlanTitle}}**, for harmonics **{{msg:signalDetail.harmonicPlanTitle}}**;
  **{{msg:signalDetail.practiceTitle}}** creates a demo trade from the plan.
- Elliott signals: **{{msg:signalDetail.projectionTitle}}** and **{{msg:signalDetail.historySummary}}**.
- **{{msg:detail.whyThisScore}}** and **{{msg:signalDetail.howScoreWorks}}**: the setup score out of 100
  from the user's scoring settings (`/settings/scoring`).
- Outcome / **{{msg:results.title}}** once enough candles have closed.

## Things people get wrong

- Pattern rules, stop and target settings come from Settings (Candlestick Patterns, Elliott Waves,
  Harmonic Formations); changing them affects future signals, not ones already recorded.
- Scores and results describe evidence and past prices, not future returns.
