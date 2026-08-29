# Harmonic structural stop plans

## Scope

`HARMONIC_STOP_V1` is a downstream trade-plan layer for already confirmed harmonic signals. It does not participate in pivot discovery, pattern classification, Fibonacci validation, setup scoring, confluence, deduplication, or signal eligibility. The existing harmonic detector remains the sole owner of whether a formation exists.

The plan is attached to the same saved alert event that appears in Latest signals and All signals. A later stop breach updates that event and sends an outcome email; it does not create a second signal.

## Entry and buffer

- Entry is the harmonic confirmation candle's close, because the saved PRZ endpoint is a pivot that becomes known only after later confirmation candles.
- The exact structural invalidation is calculated from the immutable points saved by the detector.
- The executable stop adds a 0.5% equity liquidity buffer to the structural price.
- For a BUY, the buffer is below the structural price. For a SELL, it is above it.
- A plan is not created if the calculated structural level is not on the loss side of the confirmation entry.

## Supported formations

| Detector formation | Exact structural invalidation | Buffered executable stop |
| --- | --- | --- |
| Gartley | Point X / 1.0 XA | BUY: X − 0.5% of X; SELL: X + 0.5% of X |
| Bat | Point X / 1.0 XA | BUY: X − 0.5% of X; SELL: X + 0.5% of X |
| Cypher | Point X / 1.0 XC boundary | BUY: X − 0.5% of X; SELL: X + 0.5% of X |
| Butterfly | 1.414 XA: `A + (X − A) × 1.414` | Buffer outside that level |
| Crab | 2.0 XA: `A + (X − A) × 2.0` | Buffer outside that level |
| Shark | 1.27 OX: `X + (0 − X) × 1.27` | Buffer outside that level |

The detector currently has no Alternate Bat, Deep Crab, 5-0, or AB=CD formation type. No stop policy was added for those names because manufacturing those signals would change detection scope.

## Stop monitoring

- Only candles after the confirmation candle are eligible; the confirmation candle is never evaluated retrospectively.
- A BUY plan stops when a later completed candle's low is at or below the buffered stop.
- A SELL plan stops when a later completed candle's high is at or above the buffered stop.
- The first qualifying candle resolves the plan as `STOPPED`, persists its timestamp and breached high/low, updates the original signal, and may send one outcome email.
- There is no profit target, risk-to-reward ratio, or time stop in this version because only harmonic stop rules were supplied.

## Presentation

The initial harmonic email and signal-detail page show the confirmation entry, PRZ endpoint, exact structural invalidation, formula, buffer amount and percentage, executable stop, and distance from entry. The chart draws the entry, structural boundary, and buffered stop. Latest signals, All signals, and alert history show `Active stop` or `Stop breached` on the same event.

## Verification

- The pure policy tests cover all six supported formations in bullish and bearish orientation.
- Scheduler and service tests cover plan attachment, ignoring the confirmation candle, BUY low breaches, SELL high breaches, no-breach behavior, persistence, and email dispatch.
- Email and frontend tests cover the initial plan, stop outcome, no invented target/R:R, card, and chart lines.
- Detector-regression tests still evaluate 1,200 independently generated valid structures and 120 hard-rule violations without involving this stop policy.

Existing harmonic signals saved before migration V52 are not backfilled. New confirmed harmonic signals receive `HARMONIC_STOP_V1` plans.
