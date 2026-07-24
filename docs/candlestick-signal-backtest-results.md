# Candlestick Signal Backtest Results

Date run: 2026-07-08

This document records the historical backtests performed for the StockWatch24-7 candlestick signal detector after adding TA4J-based indicator enrichment and confidence scoring.

> **Terminology and validity note (2026-07-21):** The 2026-07-08 results below use the detector's former “confidence” terminology and predate mandatory prior-trend validation for every reversal pattern. The production UI now calls this a heuristic **setup score**, not a probability estimate. See the strict-context rerun dated 2026-07-21 for current results.

## Current Pipeline

The tested signal flow is:

1. Fetch historical candles from the database/API.
2. Build a TA4J `BarSeries`.
3. Calculate technical indicators:
   - RSI 14
   - EMA 20
   - SMA 200
   - ATR 14
   - Bollinger Bands 20, 2
   - Average volume 20
4. Package the most recent enriched candles as `EnrichedCandle`.
5. Run `CandlePatternDetectionService`.
6. Record detected BUY/SELL signals.
7. Look forward a fixed number of candles and classify the signal result.

Relevant code:

- `TechnicalIndicatorEnrichmentService`
- `CandlePatternDetectionService`
- `HistoricalSignalBacktestService`
- `HistoricalSignalRealDataBacktestTest`

## Backtest Methodology

The real-data backtest uses daily candles and walks forward chronologically through history.

For every candle after the warmup period:

1. Take all candles up to the current candle.
2. Enrich them with TA4J indicators.
3. Pass the latest 5 enriched candles into the detector.
4. If a BUY or SELL signal is emitted on the current candle, evaluate it after 5 future candles.

Settings used:

| Setting | Value |
|---|---:|
| Timeframe | Daily |
| Minimum warmup history | 250 candles |
| Enriched candles passed to detector | 5 |
| Forward evaluation window | 5 candles |
| Required move | 2.00% |

Outcome definition:

- BUY success: close after 5 candles is at least 2.00% above signal close.
- SELL success: close after 5 candles is at least 2.00% below signal close.
- Failure: price moved at least 2.00% against the signal.
- Inconclusive: price did not move at least 2.00% either way.

Two percentages are reported:

- Success rate: successful signals divided by all signals, including inconclusive signals.
- Precision: successful signals divided by successful + failed signals, excluding inconclusive signals.

Command used for the real-data test:

```powershell
.\mvnw "-Dtest=HistoricalSignalRealDataBacktestTest" "-Dbacktest.real.enabled=true" test
```

## 10-Stock Baseline Run

Symbols:

`AAPL, MSFT, NVDA, AMZN, GOOGL, META, TSLA, JPM, XOM, JNJ`

Aggregate result:

| Metric | Result |
|---|---:|
| Total candles | 10,502 |
| Analyzed candles | 10,452 |
| Total signals | 730 |
| BUY signals | 265 |
| SELL signals | 465 |
| Successful signals | 221 |
| Failed signals | 192 |
| Inconclusive signals | 317 |
| Success rate including inconclusive | 30.27% |
| Precision excluding inconclusive | 53.51% |
| Average directional return | 0.20% |

Per-symbol precision:

| Symbol | Signals | Success | Failed | Inconclusive | Precision |
|---|---:|---:|---:|---:|---:|
| AAPL | 97 | 21 | 17 | 59 | 55.26% |
| MSFT | 83 | 23 | 18 | 42 | 56.10% |
| NVDA | 76 | 34 | 22 | 20 | 60.71% |
| AMZN | 56 | 22 | 14 | 20 | 61.11% |
| GOOGL | 65 | 18 | 17 | 30 | 51.43% |
| META | 61 | 22 | 18 | 21 | 55.00% |
| TSLA | 78 | 30 | 30 | 18 | 50.00% |
| JPM | 63 | 16 | 16 | 31 | 50.00% |
| XOM | 77 | 20 | 21 | 36 | 48.78% |
| JNJ | 74 | 15 | 19 | 40 | 44.12% |

High-confidence signals, defined as confidence score >= 80:

| Metric | Result |
|---|---:|
| High-confidence signals | 333 |
| Successful | 96 |
| Failed | 96 |
| Inconclusive | 141 |
| Success rate including inconclusive | 28.83% |
| Precision excluding inconclusive | 50.00% |

## 30-Stock Expanded Run

Symbols:

`AAPL, MSFT, NVDA, AMZN, GOOGL, META, TSLA, AVGO, AMD, ORCL, CRM, JPM, BAC, GS, V, MA, XOM, CVX, COP, JNJ, UNH, PFE, LLY, PG, KO, COST, WMT, HD, CAT, BA`

Aggregate result:

| Metric | Result |
|---|---:|
| Total candles | 30,502 |
| Analyzed candles | 30,352 |
| Total signals | 2,126 |
| BUY signals | 812 |
| SELL signals | 1,314 |
| Successful signals | 579 |
| Failed signals | 575 |
| Inconclusive signals | 972 |
| Success rate including inconclusive | 27.23% |
| Precision excluding inconclusive | 50.17% |
| Average directional return | -0.01% |

High-confidence aggregate, confidence score >= 80:

| Metric | Result |
|---|---:|
| High-confidence signals | 960 |
| Successful | 254 |
| Failed | 270 |
| Inconclusive | 436 |
| Success rate including inconclusive | 26.46% |
| Precision excluding inconclusive | 48.47% |

Confidence bucket breakdown:

| Confidence Score | Signals | Success | Failed | Inconclusive | Success Rate | Precision |
|---|---:|---:|---:|---:|---:|---:|
| 65-69 | 448 | 120 | 119 | 209 | 26.79% | 50.21% |
| 70-74 | 336 | 91 | 83 | 162 | 27.08% | 52.30% |
| 75-79 | 382 | 114 | 103 | 165 | 29.84% | 52.53% |
| 80-84 | 294 | 78 | 78 | 138 | 26.53% | 50.00% |
| 85-89 | 196 | 51 | 61 | 84 | 26.02% | 45.54% |
| 90-94 | 244 | 70 | 60 | 114 | 28.69% | 53.85% |
| 95-100 | 226 | 55 | 71 | 100 | 24.34% | 43.65% |

## Interpretation

The current detector is not reliably predictive under the tested definition.

The expanded sample is close to coin-flip precision:

- All signals: 50.17% precision.
- High-confidence signals: 48.47% precision.
- Average directional return: -0.01%.

The confidence score is also not calibrated correctly. A higher score does not consistently produce better outcomes. The `95-100` bucket performed worse than several lower-confidence buckets, which means the current scoring function should not be presented as a true probability or as a reliable ranking of signal quality.

## Should We Reconsider The Algorithm?

Yes. The detection geometry can remain useful, but the classification and scoring model should be reconsidered.

The current algorithm is reasonable for identifying candle formations with technical confluence, but the backtest shows that this does not automatically translate into profitable or directionally accurate 5-day signals. The biggest issue is the confidence layer, not necessarily the candle-pattern recognizers.

Recommended changes:

1. Stop treating the current confidence score as a precision estimate.
2. Separate pattern detection from signal classification:
   - Pattern detection answers: "Did this candle formation occur?"
   - Classification answers: "Is this setup likely to produce a useful forward move?"
3. Recalibrate or replace the scoring model using historical results.
4. Add per-pattern statistics because some patterns may be useful while others may add noise.
5. Add per-signal-direction statistics because BUY and SELL behavior can differ substantially.
6. Test multiple horizons and thresholds:
   - 3 candles / 1%
   - 5 candles / 2%
   - 10 candles / 3%
   - ATR-based target instead of fixed percent
7. Consider regime filters:
   - broad market trend
   - stock-specific volatility
   - volume/liquidity
   - earnings/news exclusion windows
8. Consider training a small calibrated classifier using the current detector output as features rather than relying on manually weighted scores.

## Practical Conclusion

The algorithm should currently be treated as a pattern scanner, not as a high-confidence trading signal engine.

The next engineering step is to turn the backtest results into calibration data:

- compute precision per pattern
- compute precision per confidence reason
- compute precision per BUY vs SELL
- remove or downweight reasons that do not improve results
- promote only statistically supported combinations to alert-worthy signals

## Threshold Sweep Results

After the initial confidence-bucket test, a larger threshold sweep was run over the same 30-stock cached dataset.

Sweep dimensions:

- Forward horizons: 3, 5, and 10 candles
- Required moves:
  - 3-candle horizon: 1.0%, 1.5%, 2.0%
  - 5-candle horizon: 1.0%, 1.5%, 2.0%
  - 10-candle horizon: 1.0%, 2.0%, 3.0%
- Confidence filters:
  - 65-100
  - 70-100
  - 75-100
  - 80-100
  - 85-100
  - 90-100
  - 95-100
  - 65-94
  - 75-94
  - 80-94
  - 85-94
  - 90-94

Command used:

```powershell
.\mvnw "-Dtest=HistoricalSignalThresholdSweepTest" "-Dbacktest.sweep.enabled=true" test
```

Best configurations with at least 500 signals:

| Horizon | Required Move | Confidence | Signals | Success | Failed | Inconclusive | Success Rate | Precision | Avg Return |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 3.0% | 75-94 | 1,108 | 323 | 262 | 523 | 29.15% | 55.21% | 0.18% |
| 10 | 3.0% | 80-94 | 727 | 213 | 173 | 341 | 29.30% | 55.18% | 0.17% |
| 10 | 2.0% | 75-94 | 1,108 | 399 | 335 | 374 | 36.01% | 54.36% | 0.18% |
| 10 | 3.0% | 75-100 | 1,334 | 386 | 325 | 623 | 28.94% | 54.29% | 0.16% |
| 10 | 2.0% | 80-94 | 727 | 260 | 219 | 248 | 35.76% | 54.28% | 0.17% |
| 10 | 3.0% | 70-100 | 1,668 | 476 | 405 | 787 | 28.54% | 54.03% | 0.15% |
| 10 | 3.0% | 80-100 | 953 | 276 | 236 | 441 | 28.96% | 53.91% | 0.14% |
| 10 | 2.0% | 75-100 | 1,334 | 471 | 412 | 451 | 35.31% | 53.34% | 0.16% |
| 10 | 3.0% | 85-100 | 662 | 188 | 166 | 308 | 28.40% | 53.11% | 0.17% |
| 10 | 2.0% | 70-100 | 1,668 | 584 | 517 | 567 | 35.01% | 53.04% | 0.15% |

Notable with/without 95-100 results:

| Horizon | Required Move | Include 95-100 | Exclude 95-100 | Change |
|---:|---:|---:|---:|---:|
| 10 | 3.0% | 75-100: 54.29% | 75-94: 55.21% | +0.92 pp |
| 10 | 3.0% | 80-100: 53.91% | 80-94: 55.18% | +1.27 pp |
| 10 | 2.0% | 75-100: 53.34% | 75-94: 54.36% | +1.02 pp |
| 10 | 2.0% | 80-100: 52.87% | 80-94: 54.28% | +1.41 pp |
| 5 | 2.0% | 75-100: 49.66% | 75-94: 50.89% | +1.23 pp |
| 5 | 2.0% | 80-100: 48.47% | 80-94: 50.00% | +1.53 pp |

The 95-100 bucket alone was not reliable:

| Horizon | Required Move | Confidence | Signals | Precision |
|---:|---:|---:|---:|---:|
| 3 | 2.0% | 95-100 | 226 | 47.12% |
| 5 | 2.0% | 95-100 | 226 | 43.65% |
| 10 | 3.0% | 95-100 | 226 | 50.00% |

Threshold sweep conclusion:

The best tested setup was confidence 75-94, 10-candle horizon, and 3.0% required move. It reached 55.21% precision on 1,108 signals.

This is an improvement over the original 5-candle/2.0% setup, but it is still not strong enough to confidently call the system a buy/sell signal engine. Before per-pattern calibration, excluding the 95-100 bucket consistently helped slightly, which reinforced that the original confidence scoring was miscalibrated.

## Implemented Pattern-First Calibration Change

Based on the threshold sweep and the client requirement, the production detector now treats candle-pattern detection as the primary event. A structurally detected pattern is emitted even when the directional confidence is low.

Implemented rule:

- score < 75: emit as `LOW_CONFIDENCE`
- score 75-84: emit as `MEDIUM_CONFIDENCE`
- score >= 85: emit as `HIGH_CONFIDENCE`

This means the algorithm notifies users that the candle pattern itself occurred, while still labeling the directional classification separately.

Before per-pattern calibration, the best-tested confidence band was 75-94:

| Horizon | Required Move | Signals | Success | Failed | Inconclusive | Precision |
|---:|---:|---:|---:|---:|---:|---:|
| 10 | 3.0% | 1,108 | 323 | 262 | 523 | 55.21% |
| 10 | 2.0% | 1,108 | 399 | 335 | 374 | 54.36% |
| 5 | 2.0% | 1,116 | 313 | 302 | 501 | 50.89% |

This improved interpretation compared with treating every score as equally reliable, but it did not solve the deeper classification problem. The detector should be presented as a pattern scanner first. BUY/SELL direction and confidence are secondary classifications.

## Pattern-First Threshold Sweep Rerun

After changing production behavior to emit structurally detected patterns regardless of directional confidence, the threshold sweep was rerun on 2026-07-08 with additional low-confidence buckets.

Command used:

```powershell
.\mvnw "-Dtest=HistoricalSignalThresholdSweepTest" "-Dbacktest.sweep.enabled=true" test
```

Sample:

- 30/30 representative symbols had sufficient cached history.
- Daily candles were used.
- The cached dataset contains roughly 30,500 daily candles.
- Precision still means successes divided by successes plus failures; inconclusive outcomes are excluded.

Additional confidence filters added:

- 0-100
- 0-64
- 0-74
- 30-64
- 50-64

Best configurations with at least 500 signals:

| Horizon | Required Move | Confidence | Signals | Success | Failed | Inconclusive | Success Rate | Precision | Avg Return |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 3.0% | 75-94 | 1,108 | 323 | 262 | 523 | 29.15% | 55.21% | 0.18% |
| 10 | 3.0% | 80-94 | 727 | 213 | 173 | 341 | 29.30% | 55.18% | 0.17% |
| 10 | 2.0% | 75-94 | 1,108 | 399 | 335 | 374 | 36.01% | 54.36% | 0.18% |
| 10 | 3.0% | 75-100 | 1,334 | 386 | 325 | 623 | 28.94% | 54.29% | 0.16% |
| 10 | 2.0% | 80-94 | 727 | 260 | 219 | 248 | 35.76% | 54.28% | 0.17% |

All-confidence and low-confidence directional performance:

| Horizon | Required Move | Confidence | Signals | Success | Failed | Inconclusive | Precision | Avg Return |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 3.0% | 0-100 | 4,106 | 1,131 | 1,134 | 1,841 | 49.93% | -0.08% |
| 10 | 3.0% | 0-64 | 1,992 | 528 | 588 | 876 | 47.31% | -0.28% |
| 10 | 3.0% | 0-74 | 2,772 | 745 | 809 | 1,218 | 47.94% | -0.20% |
| 5 | 2.0% | 0-100 | 4,125 | 1,132 | 1,152 | 1,841 | 49.56% | -0.07% |
| 5 | 2.0% | 0-64 | 1,999 | 553 | 577 | 869 | 48.94% | -0.14% |

Rerun conclusion:

The pattern-first change is appropriate for the email product because it increases pattern coverage, but low-confidence patterns should not be presented as directionally precise. The best directional interpretation still comes from the 75-94 range over a 10-candle horizon. Low-confidence detections are useful as candlestick-pattern notifications, not as strong BUY/SELL classifications.

## Per-Pattern Calibration

The next calibration pass grouped historical results by candle pattern. This showed that the original confidence score underweighted several bullish reversal patterns and overweighted several bearish reversal patterns.

Baseline per-pattern results using the 10-candle / 3.0% outcome:

| Pattern | Signals | Success | Failed | Inconclusive | Precision | Avg Confidence | Avg Return |
|---|---:|---:|---:|---:|---:|---:|---:|
| INVERTED_HAMMER | 168 | 56 | 34 | 78 | 62.22% | 65.68 | 0.90% |
| MORNING_STAR | 248 | 82 | 50 | 116 | 62.12% | 59.67 | 1.18% |
| BULLISH_HARAMI | 513 | 165 | 120 | 228 | 57.89% | 57.02 | 0.69% |
| HAMMER | 202 | 63 | 46 | 93 | 57.80% | 71.26 | 0.83% |
| BULLISH_ENGULFING | 621 | 195 | 149 | 277 | 56.69% | 60.54 | 0.57% |
| BEARISH_ENGULFING | 798 | 177 | 266 | 355 | 39.95% | 67.26 | -1.04% |
| DARK_CLOUD_COVER | 172 | 34 | 61 | 77 | 35.79% | 64.53 | -1.75% |

Implemented pattern score adjustments:

| Pattern | Adjustment |
|---|---:|
| INVERTED_HAMMER | +10 |
| MORNING_STAR | +10 |
| HAMMER | +5 |
| BULLISH_HARAMI | +5 |
| BULLISH_ENGULFING | +5 |
| HANGING_MAN | -5 |
| BEARISH_HARAMI | -5 |
| SHOOTING_STAR | -10 |
| BEARISH_ENGULFING | -10 |
| DARK_CLOUD_COVER | -15 |

Tiny-sample patterns such as `THREE_WHITE_SOLDIERS` and `THREE_BLACK_CROWS` were left neutral despite volatile precision numbers.

Post-calibration threshold sweep, best configurations with at least 500 signals:

| Horizon | Required Move | Confidence | Signals | Success | Failed | Inconclusive | Success Rate | Precision | Avg Return |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 3.0% | 85-100 | 613 | 195 | 135 | 283 | 31.81% | 59.09% | 0.71% |
| 10 | 3.0% | 80-100 | 883 | 272 | 194 | 417 | 30.80% | 58.37% | 0.64% |
| 10 | 3.0% | 75-100 | 1,244 | 380 | 280 | 584 | 30.55% | 57.58% | 0.53% |
| 10 | 2.0% | 85-100 | 613 | 233 | 173 | 207 | 38.01% | 57.39% | 0.71% |
| 10 | 3.0% | 80-94 | 671 | 201 | 150 | 320 | 29.96% | 57.26% | 0.39% |

Per-pattern calibration improved the best 500+ signal directional precision from 55.21% to 59.09%. This is a meaningful improvement in ranking/classification, but the product should still present alerts as pattern notifications first and directional confidence second.

## Extended Horizon Sweep

The threshold sweep was extended to test longer forward outcome windows while keeping the same 30 representative symbols and cached daily history.

Additional settings tested:

- 20-candle horizon with 2.0%, 3.0%, and 5.0% required moves
- 30-candle horizon with 3.0%, 5.0%, and 8.0% required moves

Best configurations with at least 500 signals:

| Horizon | Required Move | Confidence | Signals | Success | Failed | Inconclusive | Success Rate | Precision | Avg Return |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 30 | 8.0% | 85-100 | 602 | 137 | 84 | 381 | 22.76% | 61.99% | 1.36% |
| 30 | 8.0% | 80-100 | 863 | 191 | 127 | 545 | 22.13% | 60.06% | 1.21% |
| 10 | 3.0% | 85-100 | 613 | 195 | 135 | 283 | 31.81% | 59.09% | 0.71% |
| 10 | 3.0% | 80-100 | 883 | 272 | 194 | 417 | 30.80% | 58.37% | 0.64% |
| 30 | 8.0% | 80-94 | 654 | 140 | 102 | 412 | 21.41% | 57.85% | 0.56% |
| 10 | 3.0% | 75-100 | 1,244 | 380 | 280 | 584 | 30.55% | 57.58% | 0.53% |
| 10 | 2.0% | 85-100 | 613 | 233 | 173 | 207 | 38.01% | 57.39% | 0.71% |
| 30 | 5.0% | 85-100 | 602 | 195 | 145 | 262 | 32.39% | 57.35% | 1.36% |

Extended horizon conclusion:

The 30-candle / 8.0% setup produced the highest precision so far, but the success rate including inconclusive outcomes was lower because many signals did not move far enough in either direction. For a client-facing confidence label, the best practical range is now `85-100`; for more frequent alerts, `80-100` gives more coverage with slightly lower precision.

## Strict Trend-Validated Candlestick Rerun

Date run: 2026-07-21

This rerun measures the current candlestick detector after making candle geometry and the required prior trend mandatory. Elliott Wave signals were disabled. `DOJI` was excluded from directional testing because it emits `HOLD`; `ANY` is a subscription selector rather than a detected pattern.

### Methodology and data

The outcome methodology is unchanged from the earlier candlestick report:

- chronological walk-forward evaluation with no future candles passed to detection;
- daily candles and a 250-candle warmup;
- fixed-horizon close-to-close directional returns;
- success when the directional return reaches the positive move threshold, failure when it reaches the equal negative threshold, and otherwise inconclusive;
- precision = success / (success + failure), excluding inconclusive outcomes;
- average return = mean fixed-horizon directional return across every signal, including inconclusive outcomes.

The same 30-symbol universe was used. All 30 symbols had sufficient cached history: 30,078 daily candles spanning 2022-07-11 through 2026-07-20.

One detector-input correction was required. The old harness passed only 5 enriched candles. The current production detector uses a 20-candle relative-body baseline and up to 5 completed pre-pattern candles for trend validation, so the harness now passes 25 candles. Five input candles would leave only two pre-pattern candles for a three-candle formation and would therefore make every three-candle reversal impossible to validate. This does not change the warmup, outcome horizons, move thresholds, or walk-forward design.

Commands used:

```powershell
.\mvnw.cmd "-Dtest=HistoricalSignalRealDataBacktestTest" "-Dbacktest.real.enabled=true" test
.\mvnw.cmd "-Dtest=HistoricalSignalThresholdSweepTest" "-Dbacktest.sweep.enabled=true" test
.\mvnw.cmd "-Dtest=HistoricalSignalPatternCalibrationTest" "-Dbacktest.pattern.enabled=true" test
```

### Original 5-candle / 2.0% outcome baseline

| Setup score | Signals | Success | Failed | Inconclusive | Success rate | Precision | Avg directional return |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 0-100 | 1,009 | 280 | 296 | 433 | 27.75% | 48.61% | -0.19% |
| 70-100 | 540 | 144 | 144 | 252 | 26.67% | 50.00% | +0.10% |
| 75-100 | 451 | 116 | 126 | 209 | 25.72% | 47.93% | +0.02% |
| 80-100 | 325 | 84 | 100 | 141 | 25.85% | 45.65% | -0.03% |

The strict detector does not show a useful aggregate edge at the original 5-candle / 2.0% outcome. A higher setup score is also not monotonically associated with higher precision at this horizon.

### Horizon and setup-score sweep

The sweep retained the report's 3-, 5-, 10-, 20-, and 30-candle horizons and their associated move thresholds. Selected rows are shown below; score bands were selected on this same sample and are therefore calibration results, not out-of-sample validation.

| Horizon | Required move | Setup score | Signals | Success | Failed | Inconclusive | Success rate | Precision | Avg directional return |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 3.0% | 0-100 | 1,003 | 282 | 274 | 447 | 28.12% | 50.72% | +0.05% |
| 10 | 3.0% | 70-100 | 536 | 154 | 122 | 260 | 28.73% | 55.80% | +0.61% |
| 10 | 3.0% | 75-100 | 448 | 124 | 98 | 226 | 27.68% | 55.86% | +0.63% |
| 10 | 3.0% | 80-100 | 322 | 96 | 76 | 150 | 29.81% | 55.81% | +0.81% |
| 20 | 5.0% | 70-100 | 531 | 139 | 123 | 269 | 26.18% | 53.05% | +0.68% |
| 30 | 8.0% | 0-100 | 969 | 175 | 194 | 600 | 18.06% | 47.43% | -0.46% |
| 30 | 8.0% | 80-100 | 314 | 67 | 52 | 195 | 21.34% | 56.30% | +0.69% |
| 30 | 8.0% | 90-100 | 94 | 24 | 14 | 56 | 25.53% | 63.16% | +0.71% |

Best tested bands by minimum sample size:

- At least 500 signals: 10 candles / 3.0% / setup score 70-100, with 55.80% precision and +0.61% average directional return across 536 signals.
- At least 300 signals: 30 candles / 8.0% / setup score 80-100, with 56.30% precision and +0.69% average directional return across 314 signals.
- The 90-100 result is included for visibility but is too small and too heavily selected to support a production claim.

### Per-pattern accuracy and average return

The main per-pattern comparison uses the report's 10-candle / 3.0% outcome and includes every setup score.

| Pattern | Signals | Success | Failed | Inconclusive | Precision | Avg setup score | Avg directional return |
|---|---:|---:|---:|---:|---:|---:|---:|
| BULLISH_ENGULFING | 96 | 41 | 18 | 37 | 69.49% | 70.73 | +1.81% |
| INVERTED_HAMMER | 92 | 37 | 19 | 36 | 66.07% | 61.33 | +1.15% |
| BULLISH_HARAMI | 81 | 28 | 20 | 33 | 58.33% | 74.72 | +1.13% |
| HAMMER | 78 | 27 | 20 | 31 | 57.45% | 71.38 | +1.43% |
| EVENING_STAR | 46 | 14 | 11 | 21 | 56.00% | 73.02 | +0.20% |
| DARK_CLOUD_COVER | 39 | 10 | 8 | 21 | 55.56% | 74.44 | +0.88% |
| BEARISH_HARAMI | 84 | 22 | 24 | 38 | 47.83% | 71.62 | -1.10% |
| BEARISH_ENGULFING | 146 | 36 | 43 | 67 | 45.57% | 74.08 | -0.64% |
| MORNING_STAR | 43 | 9 | 11 | 23 | 45.00% | 74.40 | +0.19% |
| HANGING_MAN | 140 | 32 | 44 | 64 | 42.11% | 63.10 | -0.63% |
| SHOOTING_STAR | 122 | 23 | 44 | 55 | 34.33% | 71.59 | -1.29% |
| PIERCING_LINE | 23 | 2 | 7 | 14 | 22.22% | 76.17 | -1.55% |
| THREE_WHITE_SOLDIERS | 11 | 1 | 4 | 6 | 20.00% | 50.64 | -1.20% |
| THREE_BLACK_CROWS | 2 | 0 | 1 | 1 | 0.00% | 62.00 | -0.94% |

At the original 5-candle / 2.0% outcome, `BEARISH_HARAMI` produced 86 signals, 45.24% precision, and -0.94% average directional return. The prior-uptrend requirement makes the label structurally correct, but this sample does not support using bearish harami as a standalone SELL edge.

### Rerun conclusion

Mandatory trend context sharply reduces false structural labels, but it does not make the complete pattern set profitable as one undifferentiated signal engine. Aggregate performance was 48.61% / -0.19% at 5 candles and 50.72% / +0.05% at 10 candles. The promising evidence is concentrated in a few bullish reversal patterns and in selected longer-horizon setup-score bands.

These are gross signal returns, not a portfolio backtest. They exclude fees, spread, slippage, position sizing, stop losses, and overlapping-position constraints. The universe is a fixed set of large current US stocks, outcomes overlap, and all score/horizon choices were inspected on the same sample. The next reliable step is a frozen-rule out-of-sample or time-split validation, particularly for bullish engulfing and inverted hammer. Bearish harami, shooting star, hanging man, bearish engulfing, and piercing line should remain informational alerts rather than be described as historically validated trade signals.

### Temporal holdout and matched-control follow-up

Date run: 2026-07-21

The current definitions were frozen and the two strongest research candidates, `BULLISH_ENGULFING` and `INVERTED_HAMMER`, were tested with the already selected 10-candle / 3.0% outcome. No setup-score filter was added. The split was:

- Development: analyzable signals through 2024-12-31 whose 10-candle exits also occurred before 2025.
- Validation: signals from 2025-01-01 through the last date with a complete 10-candle outcome.

This is a retrospective temporal-stability diagnostic, not a pristine out-of-sample test, because the full 2022-2026 dataset had already been inspected when these two candidates were selected.

Each signal was matched to a control date that was not reused within the same pattern, symbol, and segment and that:

- belonged to the same symbol and temporal segment;
- was within 63 trading candles of the signal;
- had the same mandatory prior downtrend according to the detector's exact trend routine;
- minimized the prior-trend setup-point difference before minimizing date distance; and
- did not emit any bullish candlestick-reversal signal.

Future returns were not used to choose controls. All 181 candidate signals received a control. Mean trend-score gaps were 0.16 points in development and 0.05 points in validation. Exploratory 95% intervals use 10,000 deterministic symbol-cluster bootstrap resamples.

Command used:

```powershell
.\mvnw.cmd "-Dtest=HistoricalCandlestickHoldoutValidationTest" "-Dbacktest.candlestick.holdout.enabled=true" test
```

| Segment | Pattern | Matched signals | Signal precision | Signal avg return | Control precision | Control avg return | Precision uplift (95% CI) | Return uplift (95% CI) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Development | BULLISH_ENGULFING | 33 | 78.26% | +2.45% | 68.42% | +2.72% | +9.84 pp (-9.62, +38.57) | -0.27 pp (-1.77, +1.80) |
| Development | INVERTED_HAMMER | 44 | 65.38% | +1.03% | 50.00% | -0.64% | +15.38 pp (-7.27, +34.71) | +1.67 pp (+0.04, +3.48) |
| Development | Combined | 77 | 71.43% | +1.64% | 58.54% | +0.80% | +12.89 pp (+0.38, +25.46) | +0.84 pp (-0.27, +2.04) |
| Validation | BULLISH_ENGULFING | 58 | 65.63% | +1.65% | 72.50% | +2.39% | -6.88 pp (-21.82, +7.23) | -0.74 pp (-2.11, +0.70) |
| Validation | INVERTED_HAMMER | 46 | 65.52% | +1.30% | 78.13% | +2.97% | -12.61 pp (-30.56, +4.43) | -1.67 pp (-4.62, +0.39) |
| Validation | Combined | 104 | 65.57% | +1.49% | 75.00% | +2.65% | -9.43 pp (-21.03, +2.47) | -1.15 pp (-2.85, +0.13) |

The raw validation performance remained attractive: the two patterns combined reached 65.57% precision and +1.49% average 10-candle return. However, matched downtrend controls were stronger at 75.00% precision and +2.65%. Neither validation uplift interval excludes zero, and both point estimates are negative.

The defensible conclusion is therefore that the earlier result primarily captured a large-cap rebound effect after the detector's qualifying downtrends, not demonstrated incremental forecasting value from the bullish-engulfing or inverted-hammer shape. These patterns remain useful descriptions and contextual alerts, but this test does not validate them as standalone BUY signals. A genuinely untouched test must use post-freeze data after 2026-07-21 or a separately frozen external universe, followed by a transaction-cost-aware portfolio simulation.

## Weekly/Monthly Elliott Wave Experiment

Date run: 2026-07-09

The client requested Elliott Wave support for higher intervals, especially weekly and monthly candles. A separate `ElliottWaveDetectionService` was added so daily candlestick detection remains unchanged.

Implemented Elliott patterns:

- `ELLIOTT_BULLISH_IMPULSE`
- `ELLIOTT_BEARISH_IMPULSE`
- `ELLIOTT_BULLISH_CORRECTION`
- `ELLIOTT_BEARISH_CORRECTION`

Implementation notes:

- Elliott detection uses the last higher-interval enriched candle window, up to 80 candles.
- It identifies alternating swing pivots from highs/lows.
- It requires five-wave impulse structure with higher highs/lows or lower lows/highs.
- It checks Fibonacci-like retracement bounds for waves 2 and 4.
- It only emits impulse signals on the actual wave-5 breakout/breakdown candle, not repeatedly after price is already beyond the pivot.
- Correction signals require a completed five-wave structure and a confirmed rebound/breakdown after the corrective pivot.
- Production daily alerts do not use Elliott Wave logic.

Production setting after the test:

| Setting | Default |
|---|---:|
| `alerts.elliott.weekly-enabled` | `false` |
| `alerts.elliott.monthly-enabled` | `true` |

Weekly Elliott is implemented but disabled by default because the cached backtest degraded most weekly precision buckets. Monthly Elliott is enabled by default because it improved every tested monthly bucket in this cached sample.

### Higher-Interval Methodology

The higher-interval backtest used the same 30 representative symbols as the daily expanded run:

`AAPL, MSFT, NVDA, AMZN, GOOGL, META, TSLA, AVGO, AMD, ORCL, CRM, JPM, BAC, GS, V, MA, XOM, CVX, COP, JNJ, UNH, PFE, LLY, PG, KO, COST, WMT, HD, CAT, BA`

Important sample limitation:

- Only 2 of the 30 symbols had enough cached weekly/monthly history in the local database.
- The numbers below are useful for engineering direction, but they are not enough to claim statistical confidence.
- A stronger final validation needs a refreshed weekly/monthly cache for all 30 symbols.

Command used:

```powershell
.\mvnw "-Dtest=HigherIntervalSignalBacktestTest" "-Dbacktest.higher.enabled=true" test
```

Outcome settings:

| Interval | Horizon | Required Move |
|---|---:|---:|
| Weekly | 4 candles | 4.0% |
| Weekly | 8 candles | 8.0% |
| Weekly | 12 candles | 12.0% |
| Monthly | 3 candles | 6.0% |
| Monthly | 6 candles | 12.0% |
| Monthly | 9 candles | 18.0% |

Precision still means `success / (success + failure)`. Expected return is represented by average directional return.

### Candlestick-Only Baseline

Best baseline higher-interval results with at least 20 signals:

| Interval | Horizon | Move | Confidence | Signals | Success | Failed | Inconclusive | Precision | Avg Return |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Weekly | 12 | 12.0% | 90-100 | 21 | 7 | 3 | 11 | 70.00% | 3.25% |
| Weekly | 12 | 12.0% | 85-100 | 53 | 15 | 9 | 29 | 62.50% | 3.23% |
| Weekly | 4 | 4.0% | 80-100 | 71 | 23 | 14 | 34 | 62.16% | 0.47% |
| Weekly | 4 | 4.0% | 90-100 | 21 | 8 | 5 | 8 | 61.54% | 0.40% |
| Weekly | 8 | 8.0% | 85-100 | 53 | 14 | 9 | 30 | 60.87% | 2.05% |

Monthly baseline was materially weaker:

| Interval | Horizon | Move | Confidence | Signals | Precision | Avg Return |
|---|---:|---:|---:|---:|---:|---:|
| Monthly | 3 | 6.0% | 0-100 | 171 | 48.82% | -0.27% |
| Monthly | 6 | 12.0% | 0-100 | 170 | 44.14% | -4.35% |
| Monthly | 9 | 18.0% | 0-100 | 170 | 36.84% | -10.67% |

### Candlestick Plus Elliott Results

After tightening Elliott impulse signals to only fire on the breakout candle, the combined detector produced these best results with at least 20 signals:

| Interval | Horizon | Move | Confidence | Signals | Success | Failed | Inconclusive | Precision | Avg Return |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Weekly | 4 | 4.0% | 75-100 | 143 | 46 | 32 | 65 | 58.97% | 0.57% |
| Weekly | 4 | 4.0% | 80-100 | 104 | 31 | 22 | 51 | 58.49% | 0.18% |
| Weekly | 4 | 4.0% | 90-100 | 42 | 14 | 10 | 18 | 58.33% | 0.11% |
| Monthly | 9 | 18.0% | 90-100 | 30 | 9 | 7 | 14 | 56.25% | 6.05% |
| Weekly | 12 | 12.0% | 85-100 | 77 | 15 | 12 | 50 | 55.56% | 1.69% |

Monthly before/after comparison:

| Horizon | Move | Confidence | Before Signals | Before Precision | Before Avg Return | After Signals | After Precision | After Avg Return | Precision Change | Return Change |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 3 | 6.0% | 0-100 | 171 | 48.82% | -0.27% | 200 | 50.34% | -0.05% | +1.52 pp | +0.23 pp |
| 3 | 6.0% | 90-100 | 20 | 44.44% | 2.45% | 30 | 52.00% | 4.66% | +7.56 pp | +2.20 pp |
| 6 | 12.0% | 0-100 | 170 | 44.14% | -4.35% | 199 | 46.09% | -2.89% | +1.95 pp | +1.46 pp |
| 6 | 12.0% | 85-100 | 30 | 35.29% | -7.41% | 49 | 46.67% | 0.37% | +11.37 pp | +7.78 pp |
| 9 | 18.0% | 0-100 | 170 | 36.84% | -10.67% | 199 | 40.54% | -8.52% | +3.70 pp | +2.15 pp |
| 9 | 18.0% | 90-100 | 20 | 44.44% | 1.25% | 30 | 56.25% | 6.05% | +11.81 pp | +4.80 pp |

Weekly before/after comparison:

| Horizon | Move | Confidence | Before Signals | Before Precision | Before Avg Return | After Signals | After Precision | After Avg Return | Precision Change | Return Change |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 4 | 4.0% | 0-100 | 385 | 50.85% | -0.31% | 422 | 51.00% | -0.33% | +0.14 pp | -0.01 pp |
| 4 | 4.0% | 80-100 | 71 | 62.16% | 0.47% | 104 | 58.49% | 0.18% | -3.67 pp | -0.28 pp |
| 8 | 8.0% | 85-100 | 53 | 60.87% | 2.05% | 77 | 54.84% | 1.12% | -6.03 pp | -0.93 pp |
| 12 | 12.0% | 85-100 | 53 | 62.50% | 3.23% | 77 | 55.56% | 1.69% | -6.94 pp | -1.54 pp |
| 12 | 12.0% | 90-100 | 21 | 70.00% | 3.25% | 42 | 53.85% | 0.57% | -16.15 pp | -2.69 pp |

### Elliott Wave Conclusion

Elliott Wave should not be added blindly to every higher interval.

The monthly results improved across all tested confidence thresholds and horizons in the cached sample, so monthly Elliott alerts are enabled by default. The strongest monthly result was the 9-month / 18.0% / 90-100 bucket at 56.25% precision and 6.05% average directional return, but this is only 30 signals from 2 symbols.

The weekly results mostly degraded in the higher-confidence buckets, so weekly Elliott alerts are disabled by default. Weekly can be enabled later with:

```properties
alerts.elliott.weekly-enabled=true
```

The best next validation step is to sync enough weekly and monthly candles for the full 30-symbol sample, rerun `HigherIntervalSignalBacktestTest`, and only then decide whether weekly Elliott should be enabled for the client.

## Full 30-Symbol Weekly/Monthly Rerun

Date run: 2026-07-09

The preliminary Elliott Wave experiment above was based on only 2 of 30 symbols because the local database did not yet contain enough weekly/monthly candles. The missing higher-interval candles were then synced from Twelve Data and the same test was rerun.

Sync command:

```powershell
.\mvnw "-Dtest=HigherIntervalSignalBacktestTest" "-Dbacktest.higher.enabled=true" "-Dbacktest.higher.sync-missing=true" test
```

Sync result:

| Metric | Result |
|---|---:|
| Missing symbol/interval datasets synced | 56 |
| Already sufficient datasets skipped | 4 |
| Weekly symbols with sufficient data | 30/30 |
| Monthly symbols with sufficient data | 30/30 |
| Weekly total candles | 29,681 |
| Monthly total candles | 14,538 |

The test includes a provider-call delay of 8.5 seconds by default to avoid hammering the Twelve Data API.

### Full Sample Baseline

Candlestick-only baseline, best higher-interval results with at least 20 signals:

| Interval | Horizon | Move | Confidence | Signals | Success | Failed | Inconclusive | Precision | Avg Return |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Weekly | 12 | 12.0% | 90-100 | 429 | 92 | 62 | 275 | 59.74% | 1.68% |
| Weekly | 8 | 8.0% | 85-100 | 759 | 189 | 129 | 441 | 59.43% | 1.72% |
| Weekly | 8 | 8.0% | 80-100 | 1,089 | 263 | 189 | 637 | 58.19% | 1.43% |
| Weekly | 8 | 8.0% | 90-100 | 430 | 107 | 77 | 246 | 58.15% | 2.28% |
| Weekly | 4 | 4.0% | 90-100 | 432 | 141 | 102 | 189 | 58.02% | 1.11% |

Monthly candlestick-only baseline:

| Interval | Horizon | Move | Confidence | Signals | Precision | Avg Return |
|---|---:|---:|---:|---:|---:|---:|
| Monthly | 3 | 6.0% | 0-100 | 2,713 | 50.63% | -0.12% |
| Monthly | 6 | 12.0% | 0-100 | 2,697 | 47.16% | -1.47% |
| Monthly | 9 | 18.0% | 0-100 | 2,674 | 44.60% | -2.14% |
| Monthly | 9 | 18.0% | 90-100 | 251 | 45.61% | -4.15% |

### Full Sample With Elliott

Candlestick plus Elliott, best higher-interval results with at least 20 signals:

| Interval | Horizon | Move | Confidence | Signals | Success | Failed | Inconclusive | Precision | Avg Return |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Weekly | 4 | 4.0% | 90-100 | 697 | 229 | 170 | 298 | 57.39% | 0.69% |
| Weekly | 4 | 4.0% | 85-100 | 1,064 | 325 | 248 | 491 | 56.72% | 0.69% |
| Weekly | 12 | 12.0% | 90-100 | 691 | 137 | 105 | 449 | 56.61% | 0.99% |
| Weekly | 8 | 8.0% | 85-100 | 1,060 | 245 | 194 | 621 | 55.81% | 1.31% |
| Monthly | 9 | 18.0% | 90-100 | 432 | 104 | 86 | 242 | 54.74% | 0.50% |

Monthly before/after on the full sample:

| Horizon | Move | Confidence | Before Signals | Before Precision | Before Avg Return | After Signals | After Precision | After Avg Return | Precision Change | Return Change |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 3 | 6.0% | 0-100 | 2,713 | 50.63% | -0.12% | 3,115 | 51.68% | 0.36% | +1.04 pp | +0.49 pp |
| 3 | 6.0% | 90-100 | 252 | 49.69% | 0.06% | 436 | 52.59% | 1.42% | +2.90 pp | +1.36 pp |
| 6 | 12.0% | 0-100 | 2,697 | 47.16% | -1.47% | 3,096 | 49.50% | -0.50% | +2.34 pp | +0.97 pp |
| 6 | 12.0% | 90-100 | 252 | 42.74% | -2.30% | 436 | 50.00% | 0.45% | +7.26 pp | +2.75 pp |
| 9 | 18.0% | 0-100 | 2,674 | 44.60% | -2.14% | 3,068 | 48.61% | -0.56% | +4.01 pp | +1.59 pp |
| 9 | 18.0% | 75-100 | 777 | 40.93% | -4.92% | 1,171 | 52.21% | 0.17% | +11.27 pp | +5.09 pp |
| 9 | 18.0% | 90-100 | 251 | 45.61% | -4.15% | 432 | 54.74% | 0.50% | +9.12 pp | +4.66 pp |

Weekly before/after on the full sample:

| Horizon | Move | Confidence | Before Signals | Before Precision | Before Avg Return | After Signals | After Precision | After Avg Return | Precision Change | Return Change |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 4 | 4.0% | 0-100 | 5,630 | 49.11% | -0.23% | 6,165 | 49.36% | -0.25% | +0.25 pp | -0.02 pp |
| 4 | 4.0% | 85-100 | 761 | 56.64% | 0.88% | 1,064 | 56.72% | 0.69% | +0.08 pp | -0.19 pp |
| 4 | 4.0% | 90-100 | 432 | 58.02% | 1.11% | 697 | 57.39% | 0.69% | -0.63 pp | -0.41 pp |
| 8 | 8.0% | 85-100 | 759 | 59.43% | 1.72% | 1,060 | 55.81% | 1.31% | -3.63 pp | -0.41 pp |
| 8 | 8.0% | 90-100 | 430 | 58.15% | 2.28% | 693 | 53.20% | 1.41% | -4.95 pp | -0.86 pp |
| 12 | 12.0% | 90-100 | 429 | 59.74% | 1.68% | 691 | 56.61% | 0.99% | -3.13 pp | -0.70 pp |

### Updated Recommendation

The 30-symbol rerun confirms the preliminary direction:

- Monthly Elliott Wave support is useful as an additional pattern source. It improved precision and expected directional return across the tested monthly horizons.
- Weekly Elliott Wave support should remain disabled by default. It increases signal count, but it generally reduces precision and expected return in the strongest weekly confidence buckets.

Production defaults remain appropriate:

```properties
alerts.elliott.weekly-enabled=false
alerts.elliott.monthly-enabled=true
```

Client-facing interpretation:

The system can now send Elliott Wave pattern notifications for monthly intervals with better evidence than the original 2-symbol test. Weekly Elliott detection exists in the codebase, but based on the 30-symbol result it should not be enabled for the client unless they explicitly prefer more pattern coverage over directional quality.

## Elliott Follow-Through Rerun

Date run: 2026-07-10

After the first production test, monthly Elliott Wave checks were still rarely appearing in the UI. The detector was changed so a present signal can be either:

- the breakout/rebound candle itself, or
- one fresh follow-through candle immediately after that breakout/rebound.

The detector still does not emit old historical Elliott waves as current alerts. The signal timestamp remains the latest candle being evaluated, and the allowed follow-through window is one higher-interval candle.

### Follow-Through Methodology

The same 30 representative symbols and cached higher-interval history were used:

`AAPL, MSFT, NVDA, AMZN, GOOGL, META, TSLA, AVGO, AMD, ORCL, CRM, JPM, BAC, GS, V, MA, XOM, CVX, COP, JNJ, UNH, PFE, LLY, PG, KO, COST, WMT, HD, CAT, BA`

The dataset was sufficient for all symbols:

| Metric | Result |
|---|---:|
| Weekly symbols with sufficient data | 30/30 |
| Monthly symbols with sufficient data | 30/30 |
| Weekly total candles | 29,681 |
| Monthly total candles | 14,538 |

Commands used:

```powershell
.\mvnw "-Dtest=HigherIntervalSignalBacktestTest" "-Dbacktest.higher.enabled=true" test
.\mvnw "-Dtest=ElliottWaveImplementationComparisonBacktestTest" "-Dbacktest.elliott.compare.enabled=true" test
```

The first command compares candlestick-only against the current Elliott implementation. The second command compares the legacy breakout-candle-only Elliott implementation against the current follow-through implementation.

Outcome settings remained unchanged:

| Interval | Horizon | Required Move |
|---|---:|---:|
| Weekly | 4 candles | 4.0% |
| Weekly | 8 candles | 8.0% |
| Weekly | 12 candles | 12.0% |
| Monthly | 3 candles | 6.0% |
| Monthly | 6 candles | 12.0% |
| Monthly | 9 candles | 18.0% |

Precision still means `success / (success + failure)`. Average return is average directional return per signal.

### Current Best Results

Candlestick plus current Elliott, best higher-interval results with at least 20 signals:

| Interval | Horizon | Move | Confidence | Signals | Success | Failed | Inconclusive | Precision | Avg Return |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Weekly | 12 | 12.0% | 90-100 | 616 | 127 | 85 | 404 | 59.91% | 1.52% |
| Weekly | 8 | 8.0% | 85-100 | 964 | 237 | 167 | 560 | 58.66% | 1.73% |
| Weekly | 8 | 8.0% | 80-100 | 1,331 | 318 | 233 | 780 | 57.71% | 1.47% |
| Weekly | 4 | 4.0% | 90-100 | 622 | 203 | 150 | 269 | 57.51% | 0.94% |
| Weekly | 8 | 8.0% | 90-100 | 618 | 153 | 114 | 351 | 57.30% | 2.15% |
| Monthly | 9 | 18.0% | 90-100 | 370 | 91 | 75 | 204 | 54.82% | -0.05% |

### Current Versus Candlestick-Only

Monthly results versus candlestick-only baseline:

| Horizon | Move | Confidence | Candlestick Signals | Candlestick Precision | Candlestick Avg Return | Current Signals | Current Precision | Current Avg Return | Precision Change | Return Change |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 3 | 6.0% | 0-100 | 2,713 | 50.63% | -0.12% | 2,873 | 51.06% | 0.03% | +0.43 pp | +0.15 pp |
| 3 | 6.0% | 90-100 | 252 | 49.69% | 0.06% | 373 | 54.17% | 1.37% | +4.48 pp | +1.31 pp |
| 6 | 12.0% | 0-100 | 2,697 | 47.16% | -1.47% | 2,857 | 47.97% | -1.10% | +0.81 pp | +0.38 pp |
| 6 | 12.0% | 90-100 | 252 | 42.74% | -2.30% | 373 | 50.85% | 0.57% | +8.11 pp | +2.87 pp |
| 9 | 18.0% | 0-100 | 2,674 | 44.60% | -2.14% | 2,832 | 45.96% | -1.59% | +1.36 pp | +0.55 pp |
| 9 | 18.0% | 75-100 | 777 | 40.93% | -4.92% | 935 | 45.45% | -2.79% | +4.52 pp | +2.14 pp |
| 9 | 18.0% | 90-100 | 251 | 45.61% | -4.15% | 370 | 54.82% | -0.05% | +9.21 pp | +4.10 pp |

Weekly results versus candlestick-only baseline:

| Horizon | Move | Confidence | Candlestick Signals | Candlestick Precision | Candlestick Avg Return | Current Signals | Current Precision | Current Avg Return | Precision Change | Return Change |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 4 | 4.0% | 0-100 | 5,630 | 49.11% | -0.23% | 5,884 | 49.34% | -0.19% | +0.23 pp | +0.03 pp |
| 4 | 4.0% | 85-100 | 761 | 56.64% | 0.88% | 968 | 56.78% | 0.82% | +0.14 pp | -0.06 pp |
| 4 | 4.0% | 90-100 | 432 | 58.02% | 1.11% | 622 | 57.51% | 0.94% | -0.52 pp | -0.17 pp |
| 8 | 8.0% | 85-100 | 759 | 59.43% | 1.72% | 964 | 58.66% | 1.73% | -0.77 pp | +0.01 pp |
| 8 | 8.0% | 90-100 | 430 | 58.15% | 2.28% | 618 | 57.30% | 2.15% | -0.85 pp | -0.13 pp |
| 12 | 12.0% | 85-100 | 756 | 55.64% | 0.95% | 960 | 56.57% | 1.00% | +0.94 pp | +0.05 pp |
| 12 | 12.0% | 90-100 | 429 | 59.74% | 1.68% | 616 | 59.91% | 1.52% | +0.17 pp | -0.17 pp |

### Current Versus Legacy Elliott

Legacy means the previous breakout/rebound-candle-only Elliott implementation. Current means the follow-through implementation.

Raw signal coverage:

| Interval | Horizon | Legacy Signals | Current Signals | Change |
|---|---:|---:|---:|---:|
| Weekly | 4 | 5,760 | 5,884 | +124 |
| Weekly | 8 | 5,727 | 5,850 | +123 |
| Weekly | 12 | 5,705 | 5,828 | +123 |
| Monthly | 3 | 2,794 | 2,873 | +79 |
| Monthly | 6 | 2,778 | 2,857 | +79 |
| Monthly | 9 | 2,754 | 2,832 | +78 |

Monthly current-versus-legacy comparison:

| Horizon | Move | Confidence | Legacy Signals | Legacy Precision | Legacy Avg Return | Current Signals | Current Precision | Current Avg Return | Precision Change | Return Change |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 3 | 6.0% | 0-100 | 2,794 | 50.78% | -0.06% | 2,873 | 51.06% | 0.03% | +0.28 pp | +0.09 pp |
| 3 | 6.0% | 75-100 | 870 | 44.12% | -1.46% | 949 | 45.56% | -1.08% | +1.44 pp | +0.38 pp |
| 3 | 6.0% | 80-100 | 650 | 44.53% | -1.52% | 724 | 46.42% | -0.99% | +1.90 pp | +0.53 pp |
| 3 | 6.0% | 85-100 | 497 | 48.10% | -0.57% | 558 | 49.72% | -0.14% | +1.62 pp | +0.43 pp |
| 3 | 6.0% | 90-100 | 318 | 52.22% | 0.83% | 373 | 54.17% | 1.37% | +1.95 pp | +0.54 pp |
| 6 | 12.0% | 0-100 | 2,778 | 47.47% | -1.27% | 2,857 | 47.97% | -1.10% | +0.50 pp | +0.17 pp |
| 6 | 12.0% | 75-100 | 865 | 42.38% | -2.77% | 944 | 44.26% | -2.12% | +1.88 pp | +0.65 pp |
| 6 | 12.0% | 80-100 | 647 | 42.81% | -2.78% | 721 | 45.25% | -1.90% | +2.44 pp | +0.88 pp |
| 6 | 12.0% | 85-100 | 495 | 45.87% | -1.92% | 556 | 48.52% | -1.21% | +2.65 pp | +0.71 pp |
| 6 | 12.0% | 90-100 | 318 | 47.06% | -0.35% | 373 | 50.85% | 0.57% | +3.79 pp | +0.92 pp |
| 9 | 18.0% | 0-100 | 2,754 | 45.15% | -1.88% | 2,832 | 45.96% | -1.59% | +0.82 pp | +0.29 pp |
| 9 | 18.0% | 75-100 | 857 | 42.89% | -3.81% | 935 | 45.45% | -2.79% | +2.56 pp | +1.03 pp |
| 9 | 18.0% | 80-100 | 640 | 42.57% | -4.42% | 713 | 45.76% | -3.05% | +3.19 pp | +1.36 pp |
| 9 | 18.0% | 85-100 | 490 | 44.89% | -4.11% | 550 | 48.03% | -2.86% | +3.14 pp | +1.24 pp |
| 9 | 18.0% | 90-100 | 316 | 50.35% | -1.50% | 370 | 54.82% | -0.05% | +4.46 pp | +1.45 pp |

Weekly current-versus-legacy comparison:

| Horizon | Move | Confidence | Legacy Signals | Legacy Precision | Legacy Avg Return | Current Signals | Current Precision | Current Avg Return | Precision Change | Return Change |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 4 | 4.0% | 0-100 | 5,760 | 49.23% | -0.21% | 5,884 | 49.34% | -0.19% | +0.11 pp | +0.02 pp |
| 4 | 4.0% | 75-100 | 1,732 | 52.98% | 0.52% | 1,856 | 53.07% | 0.53% | +0.09 pp | +0.01 pp |
| 4 | 4.0% | 80-100 | 1,218 | 56.02% | 0.77% | 1,336 | 55.88% | 0.77% | -0.14 pp | -0.00 pp |
| 4 | 4.0% | 85-100 | 871 | 56.74% | 0.83% | 968 | 56.78% | 0.82% | +0.04 pp | -0.01 pp |
| 4 | 4.0% | 90-100 | 535 | 58.28% | 1.02% | 622 | 57.51% | 0.94% | -0.77 pp | -0.08 pp |
| 8 | 8.0% | 0-100 | 5,727 | 47.42% | -0.34% | 5,850 | 47.73% | -0.28% | +0.32 pp | +0.06 pp |
| 8 | 8.0% | 75-100 | 1,724 | 53.69% | 0.89% | 1,847 | 54.31% | 1.00% | +0.62 pp | +0.11 pp |
| 8 | 8.0% | 80-100 | 1,214 | 57.09% | 1.36% | 1,331 | 57.71% | 1.47% | +0.63 pp | +0.12 pp |
| 8 | 8.0% | 85-100 | 868 | 58.20% | 1.62% | 964 | 58.66% | 1.73% | +0.47 pp | +0.11 pp |
| 8 | 8.0% | 90-100 | 532 | 56.71% | 2.08% | 618 | 57.30% | 2.15% | +0.59 pp | +0.07 pp |
| 12 | 12.0% | 0-100 | 5,705 | 46.91% | -0.55% | 5,828 | 47.09% | -0.52% | +0.18 pp | +0.04 pp |
| 12 | 12.0% | 75-100 | 1,720 | 52.83% | 0.53% | 1,843 | 53.08% | 0.57% | +0.25 pp | +0.04 pp |
| 12 | 12.0% | 80-100 | 1,210 | 55.50% | 0.84% | 1,327 | 55.91% | 0.87% | +0.41 pp | +0.03 pp |
| 12 | 12.0% | 85-100 | 864 | 56.33% | 0.99% | 960 | 56.57% | 1.00% | +0.24 pp | +0.00 pp |
| 12 | 12.0% | 90-100 | 530 | 60.43% | 1.66% | 616 | 59.91% | 1.52% | -0.52 pp | -0.15 pp |

### Follow-Through Recommendation

The follow-through change improved monthly Elliott behavior clearly. Monthly precision and average directional return improved versus the legacy Elliott implementation in every tested horizon and confidence bucket.

Weekly also became slightly better in most broad and mid-confidence buckets, especially the 8-week and 12-week horizons. However, the strictest weekly `90-100` buckets are mixed: coverage increased, but precision/return dipped for 4-week and 12-week horizons. Weekly Elliott should therefore remain disabled by default unless the client explicitly wants more weekly pattern coverage.

Production defaults remain:

```properties
alerts.elliott.weekly-enabled=false
alerts.elliott.monthly-enabled=true
```

Client-facing interpretation remains unchanged: monthly Elliott alerts are present-relevant pattern notifications, not stale historical wave reports. The detector is now less brittle, but the report still does not justify presenting Elliott confidence as a probability of profit.

## Elliott Wave Structural-Detection Rerun

Date run: 2026-07-12

The Elliott detector was rerun after expanding the structural recognition logic for complete I-V impulses, wave-V reversal confirmation and completed A-B-C corrections. The purpose was to measure whether the greater structural coverage also improved directional accuracy and average return.

### Reused Methodology

The rerun deliberately retained the methodology and parameters from the previous higher-interval experiments:

- The same 30 symbols were used:
  `AAPL, MSFT, NVDA, AMZN, GOOGL, META, TSLA, AVGO, AMD, ORCL, CRM, JPM, BAC, GS, V, MA, XOM, CVX, COP, JNJ, UNH, PFE, LLY, PG, KO, COST, WMT, HD, CAT, BA`.
- Each symbol was processed chronologically in a walk-forward backtest.
- The detector saw at most the trailing 80 enriched candles at each decision point and did not see future candles.
- Weekly runs required 80 historical candles; monthly runs required 36.
- Confidence filters remained `0-100`, `75-100`, `80-100`, `85-100` and `90-100`.
- A success required the fixed-horizon closing return to reach the required move in the signal direction. An equal adverse move was a failure; anything between those thresholds was inconclusive.
- Precision, referred to as accuracy in the discussion below, remained `success / (success + failure)`. Inconclusive signals were excluded from precision but included in signal counts and average directional return.
- Average return remained the mean fixed-horizon directional return per signal. BUY returns use the normal percentage change and SELL returns use its inverse.

Outcome parameters were unchanged:

| Interval | Horizon | Required Move |
|---|---:|---:|
| Weekly | 4 candles | 4.0% |
| Weekly | 8 candles | 8.0% |
| Weekly | 12 candles | 12.0% |
| Monthly | 3 candles | 6.0% |
| Monthly | 6 candles | 12.0% |
| Monthly | 9 candles | 18.0% |

Commands used:

```powershell
.\mvnw "-Dtest=HigherIntervalSignalBacktestTest" "-Dbacktest.higher.enabled=true" test
.\mvnw "-Dtest=ElliottWaveImplementationComparisonBacktestTest" "-Dbacktest.elliott.compare.enabled=true" test
```

Both commands completed with `BUILD SUCCESS`, one test executed in each suite and zero failures, errors or skipped tests. The provider-sync option was intentionally omitted so the test would use the existing cache rather than fetch a new sample during the run.

### Dataset and Comparability

All 30 symbols still had sufficient weekly and monthly data:

| Metric | Previous Run | Current Run | Change |
|---|---:|---:|---:|
| Weekly symbols with sufficient data | 30/30 | 30/30 | 0 |
| Monthly symbols with sufficient data | 30/30 | 30/30 | 0 |
| Weekly total candles | 29,681 | 29,837 | +156 |
| Monthly total candles | 14,538 | 14,538 | 0 |

The parameters and symbol universe are identical, but the July 10 comparison is not a perfectly frozen code-and-data A/B test because the weekly cache now contains 156 additional candles and the earlier detector implementation was not retained as a separate snapshot. Monthly row counts are unchanged, although an equal count alone cannot prove that every stored row is byte-for-byte identical. The same-run candlestick-only and follow-through-control comparisons below are the stronger causal comparisons because both sides use exactly the same current cache.

Signals from different horizons and confidence filters overlap. They must not be added together as if they were independent trades.

### Current Signal Coverage

The structural detector increased the number of signals evaluated by the combined candlestick-plus-Elliott run:

| Interval | Horizon | Candlestick-Only Signals | With Current Elliott | Elliott Additions |
|---|---:|---:|---:|---:|
| Weekly | 4 | 5,629 | 6,073 | +444 |
| Weekly | 8 | 5,598 | 6,037 | +439 |
| Weekly | 12 | 5,576 | 6,015 | +439 |
| Monthly | 3 | 2,713 | 2,933 | +220 |
| Monthly | 6 | 2,697 | 2,915 | +218 |
| Monthly | 9 | 2,674 | 2,890 | +216 |

This confirms improved pattern coverage. It does not, by itself, establish improved directional quality.

### Complete Current Results

The following is the complete candlestick-plus-current-Elliott result matrix. `Success`, `Failed` and `Inconclusive` sum to `Signals` in every row.

| Interval | Horizon | Move | Confidence | Signals | Success | Failed | Inconclusive | Precision | Avg Return |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Monthly | 3 | 6.0% | 0-100 | 2,933 | 946 | 924 | 1,063 | 50.59% | -0.06% |
| Monthly | 3 | 6.0% | 75-100 | 1,009 | 280 | 350 | 379 | 44.44% | -1.28% |
| Monthly | 3 | 6.0% | 80-100 | 788 | 221 | 275 | 292 | 44.56% | -1.43% |
| Monthly | 3 | 6.0% | 85-100 | 640 | 190 | 212 | 238 | 47.26% | -0.68% |
| Monthly | 3 | 6.0% | 90-100 | 466 | 144 | 147 | 175 | 49.48% | 0.10% |
| Monthly | 6 | 12.0% | 0-100 | 2,915 | 713 | 801 | 1,401 | 47.09% | -1.44% |
| Monthly | 6 | 12.0% | 75-100 | 1,002 | 217 | 300 | 485 | 41.97% | -3.05% |
| Monthly | 6 | 12.0% | 80-100 | 783 | 167 | 231 | 385 | 41.96% | -3.23% |
| Monthly | 6 | 12.0% | 85-100 | 636 | 139 | 175 | 322 | 44.27% | -2.59% |
| Monthly | 6 | 12.0% | 90-100 | 464 | 100 | 127 | 237 | 44.05% | -1.86% |
| Monthly | 9 | 18.0% | 0-100 | 2,890 | 590 | 706 | 1,594 | 45.52% | -1.92% |
| Monthly | 9 | 18.0% | 75-100 | 993 | 210 | 264 | 519 | 44.30% | -3.66% |
| Monthly | 9 | 18.0% | 80-100 | 775 | 167 | 209 | 399 | 44.41% | -4.11% |
| Monthly | 9 | 18.0% | 85-100 | 630 | 141 | 164 | 325 | 46.23% | -3.78% |
| Monthly | 9 | 18.0% | 90-100 | 461 | 111 | 110 | 240 | 50.23% | -1.98% |
| Weekly | 4 | 4.0% | 0-100 | 6,073 | 1,621 | 1,656 | 2,796 | 49.47% | -0.19% |
| Weekly | 4 | 4.0% | 75-100 | 2,038 | 570 | 514 | 954 | 52.58% | 0.39% |
| Weekly | 4 | 4.0% | 80-100 | 1,526 | 445 | 366 | 715 | 54.87% | 0.58% |
| Weekly | 4 | 4.0% | 85-100 | 1,174 | 336 | 283 | 555 | 54.28% | 0.50% |
| Weekly | 4 | 4.0% | 90-100 | 838 | 252 | 210 | 376 | 54.55% | 0.51% |
| Weekly | 8 | 8.0% | 0-100 | 6,037 | 1,197 | 1,329 | 3,511 | 47.39% | -0.36% |
| Weekly | 8 | 8.0% | 75-100 | 2,026 | 440 | 404 | 1,182 | 52.13% | 0.54% |
| Weekly | 8 | 8.0% | 80-100 | 1,518 | 345 | 291 | 882 | 54.25% | 0.84% |
| Weekly | 8 | 8.0% | 85-100 | 1,167 | 262 | 229 | 676 | 53.36% | 0.78% |
| Weekly | 8 | 8.0% | 90-100 | 832 | 181 | 176 | 475 | 50.70% | 0.74% |
| Weekly | 12 | 12.0% | 0-100 | 6,015 | 953 | 1,064 | 3,998 | 47.25% | -0.53% |
| Weekly | 12 | 12.0% | 75-100 | 2,023 | 350 | 314 | 1,359 | 52.71% | 0.35% |
| Weekly | 12 | 12.0% | 80-100 | 1,515 | 269 | 221 | 1,025 | 54.90% | 0.56% |
| Weekly | 12 | 12.0% | 85-100 | 1,164 | 208 | 172 | 784 | 54.74% | 0.52% |
| Weekly | 12 | 12.0% | 90-100 | 831 | 153 | 116 | 562 | 56.88% | 0.77% |

The highest current precision is 56.88% in the weekly 12-candle / 12.0% / 90-100 bucket. The highest average directional return is 0.84% in the weekly 8-candle / 8.0% / 80-100 bucket. The only monthly bucket with a positive average return is the 3-candle / 6.0% / 90-100 bucket at 0.10%.

### Exact Same-Run Comparison With Candlestick-Only

This table contains all confidence buckets from the same run. Positive changes mean that adding current Elliott signals improved the metric; negative changes mean it diluted it.

| Interval | Horizon | Move | Confidence | Baseline Signals | Baseline Precision | Baseline Avg Return | Current Signals | Current Precision | Current Avg Return | Precision Change | Return Change |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Weekly | 4 | 4.0% | 0-100 | 5,629 | 49.18% | -0.22% | 6,073 | 49.47% | -0.19% | +0.29 pp | +0.03 pp |
| Weekly | 4 | 4.0% | 75-100 | 1,594 | 52.41% | 0.46% | 2,038 | 52.58% | 0.39% | +0.17 pp | -0.07 pp |
| Weekly | 4 | 4.0% | 80-100 | 1,082 | 55.54% | 0.76% | 1,526 | 54.87% | 0.58% | -0.67 pp | -0.18 pp |
| Weekly | 4 | 4.0% | 85-100 | 754 | 56.14% | 0.82% | 1,174 | 54.28% | 0.50% | -1.86 pp | -0.32 pp |
| Weekly | 4 | 4.0% | 90-100 | 434 | 57.89% | 1.05% | 838 | 54.55% | 0.51% | -3.35 pp | -0.54 pp |
| Weekly | 8 | 8.0% | 0-100 | 5,598 | 47.51% | -0.35% | 6,037 | 47.39% | -0.36% | -0.12 pp | -0.01 pp |
| Weekly | 8 | 8.0% | 75-100 | 1,587 | 53.85% | 0.82% | 2,026 | 52.13% | 0.54% | -1.71 pp | -0.28 pp |
| Weekly | 8 | 8.0% | 80-100 | 1,079 | 57.58% | 1.36% | 1,518 | 54.25% | 0.84% | -3.34 pp | -0.53 pp |
| Weekly | 8 | 8.0% | 85-100 | 752 | 58.62% | 1.62% | 1,167 | 53.36% | 0.78% | -5.26 pp | -0.84 pp |
| Weekly | 8 | 8.0% | 90-100 | 432 | 57.22% | 2.13% | 832 | 50.70% | 0.74% | -6.52 pp | -1.39 pp |
| Weekly | 12 | 12.0% | 0-100 | 5,576 | 46.84% | -0.56% | 6,015 | 47.25% | -0.53% | +0.41 pp | +0.03 pp |
| Weekly | 12 | 12.0% | 75-100 | 1,584 | 52.49% | 0.48% | 2,023 | 52.71% | 0.35% | +0.22 pp | -0.12 pp |
| Weekly | 12 | 12.0% | 80-100 | 1,076 | 55.28% | 0.82% | 1,515 | 54.90% | 0.56% | -0.39 pp | -0.27 pp |
| Weekly | 12 | 12.0% | 85-100 | 749 | 55.85% | 0.94% | 1,164 | 54.74% | 0.52% | -1.11 pp | -0.43 pp |
| Weekly | 12 | 12.0% | 90-100 | 431 | 60.00% | 1.73% | 831 | 56.88% | 0.77% | -3.12 pp | -0.96 pp |
| Monthly | 3 | 6.0% | 0-100 | 2,713 | 50.63% | -0.12% | 2,933 | 50.59% | -0.06% | -0.05 pp | +0.06 pp |
| Monthly | 3 | 6.0% | 75-100 | 789 | 42.94% | -1.82% | 1,009 | 44.44% | -1.28% | +1.50 pp | +0.54 pp |
| Monthly | 3 | 6.0% | 80-100 | 570 | 42.86% | -2.05% | 788 | 44.56% | -1.43% | +1.70 pp | +0.62 pp |
| Monthly | 3 | 6.0% | 85-100 | 425 | 46.49% | -1.09% | 640 | 47.26% | -0.68% | +0.77 pp | +0.41 pp |
| Monthly | 3 | 6.0% | 90-100 | 252 | 49.69% | 0.06% | 466 | 49.48% | 0.10% | -0.20 pp | +0.05 pp |
| Monthly | 6 | 12.0% | 0-100 | 2,697 | 47.16% | -1.47% | 2,915 | 47.09% | -1.44% | -0.07 pp | +0.04 pp |
| Monthly | 6 | 12.0% | 75-100 | 784 | 40.88% | -3.62% | 1,002 | 41.97% | -3.05% | +1.10 pp | +0.57 pp |
| Monthly | 6 | 12.0% | 80-100 | 567 | 40.61% | -3.99% | 783 | 41.96% | -3.23% | +1.35 pp | +0.77 pp |
| Monthly | 6 | 12.0% | 85-100 | 423 | 43.81% | -3.18% | 636 | 44.27% | -2.59% | +0.46 pp | +0.59 pp |
| Monthly | 6 | 12.0% | 90-100 | 252 | 42.74% | -2.30% | 464 | 44.05% | -1.86% | +1.31 pp | +0.43 pp |
| Monthly | 9 | 18.0% | 0-100 | 2,674 | 44.60% | -2.14% | 2,890 | 45.52% | -1.92% | +0.92 pp | +0.22 pp |
| Monthly | 9 | 18.0% | 75-100 | 777 | 40.93% | -4.91% | 993 | 44.30% | -3.66% | +3.37 pp | +1.25 pp |
| Monthly | 9 | 18.0% | 80-100 | 561 | 39.85% | -6.00% | 775 | 44.41% | -4.11% | +4.57 pp | +1.90 pp |
| Monthly | 9 | 18.0% | 85-100 | 419 | 41.62% | -5.94% | 630 | 46.23% | -3.78% | +4.61 pp | +2.16 pp |
| Monthly | 9 | 18.0% | 90-100 | 251 | 45.61% | -4.15% | 461 | 50.23% | -1.98% | +4.61 pp | +2.17 pp |

The exact same-run comparison is interval-dependent:

- Monthly Elliott additions improve average return in every bucket and improve precision in 12 of 15 buckets. However, most monthly average returns remain negative in absolute terms.
- Weekly Elliott additions improve broad-bucket precision slightly at 4 and 12 weeks, but dilute nearly all medium/high-confidence buckets. The largest reduction is the weekly 8-candle / 90-100 bucket: -6.52 percentage points of precision and -1.39 percentage points of average return.

### Comparison With the July 10 Findings

The following rows use the same decision-focused buckets documented in the preceding follow-through report. Changes compare the newly measured combined detector with the July 10 combined detector:

| Interval | Horizon | Move | Confidence | Previous Signals | Previous Precision | Previous Avg Return | Current Signals | Current Precision | Current Avg Return | Signal Change | Precision Change | Return Change |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Weekly | 4 | 4.0% | 0-100 | 5,884 | 49.34% | -0.19% | 6,073 | 49.47% | -0.19% | +189 | +0.13 pp | +0.00 pp |
| Weekly | 4 | 4.0% | 85-100 | 968 | 56.78% | 0.82% | 1,174 | 54.28% | 0.50% | +206 | -2.50 pp | -0.32 pp |
| Weekly | 4 | 4.0% | 90-100 | 622 | 57.51% | 0.94% | 838 | 54.55% | 0.51% | +216 | -2.96 pp | -0.43 pp |
| Weekly | 8 | 8.0% | 85-100 | 964 | 58.66% | 1.73% | 1,167 | 53.36% | 0.78% | +203 | -5.30 pp | -0.95 pp |
| Weekly | 8 | 8.0% | 90-100 | 618 | 57.30% | 2.15% | 832 | 50.70% | 0.74% | +214 | -6.60 pp | -1.41 pp |
| Weekly | 12 | 12.0% | 85-100 | 960 | 56.57% | 1.00% | 1,164 | 54.74% | 0.52% | +204 | -1.83 pp | -0.48 pp |
| Weekly | 12 | 12.0% | 90-100 | 616 | 59.91% | 1.52% | 831 | 56.88% | 0.77% | +215 | -3.03 pp | -0.75 pp |
| Monthly | 3 | 6.0% | 0-100 | 2,873 | 51.06% | 0.03% | 2,933 | 50.59% | -0.06% | +60 | -0.47 pp | -0.09 pp |
| Monthly | 3 | 6.0% | 90-100 | 373 | 54.17% | 1.37% | 466 | 49.48% | 0.10% | +93 | -4.69 pp | -1.27 pp |
| Monthly | 6 | 12.0% | 0-100 | 2,857 | 47.97% | -1.10% | 2,915 | 47.09% | -1.44% | +58 | -0.88 pp | -0.34 pp |
| Monthly | 6 | 12.0% | 90-100 | 373 | 50.85% | 0.57% | 464 | 44.05% | -1.86% | +91 | -6.80 pp | -2.43 pp |
| Monthly | 9 | 18.0% | 0-100 | 2,832 | 45.96% | -1.59% | 2,890 | 45.52% | -1.92% | +58 | -0.44 pp | -0.33 pp |
| Monthly | 9 | 18.0% | 75-100 | 935 | 45.45% | -2.79% | 993 | 44.30% | -3.66% | +58 | -1.15 pp | -0.87 pp |
| Monthly | 9 | 18.0% | 90-100 | 370 | 54.82% | -0.05% | 461 | 50.23% | -1.98% | +91 | -4.59 pp | -1.93 pp |

The new implementation generates more signals in every compared bucket, but the July 10 result is not reproduced at the medium and strict confidence levels. Except for the broad weekly 4-candle bucket, every selected precision comparison declined, and every selected monthly average return declined. The evidence therefore supports an improvement in structural coverage, not an improvement in trading accuracy or average return.

### Same-Dataset Follow-Through Control

The second test constructs the current detector twice on the same candles: once with no present-signal lookback and once with the production one-candle follow-through. This isolates follow-through behavior, not the broader structural changes. The broad and strict endpoints are:

| Interval | Horizon | Move | Confidence | No-Follow Signals | No-Follow Precision | No-Follow Avg Return | Current Signals | Current Precision | Current Avg Return | Precision Change | Return Change |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Weekly | 4 | 4.0% | 0-100 | 5,909 | 49.36% | -0.21% | 6,073 | 49.47% | -0.19% | +0.11 pp | +0.02 pp |
| Weekly | 4 | 4.0% | 90-100 | 688 | 55.56% | 0.58% | 838 | 54.55% | 0.51% | -1.01 pp | -0.07 pp |
| Weekly | 8 | 8.0% | 0-100 | 5,875 | 47.44% | -0.37% | 6,037 | 47.39% | -0.36% | -0.05 pp | +0.01 pp |
| Weekly | 8 | 8.0% | 90-100 | 684 | 52.20% | 0.97% | 832 | 50.70% | 0.74% | -1.50 pp | -0.23 pp |
| Weekly | 12 | 12.0% | 0-100 | 5,853 | 47.01% | -0.55% | 6,015 | 47.25% | -0.53% | +0.24 pp | +0.03 pp |
| Weekly | 12 | 12.0% | 90-100 | 683 | 57.08% | 0.88% | 831 | 56.88% | 0.77% | -0.20 pp | -0.12 pp |
| Monthly | 3 | 6.0% | 0-100 | 2,856 | 50.52% | -0.12% | 2,933 | 50.59% | -0.06% | +0.07 pp | +0.05 pp |
| Monthly | 3 | 6.0% | 90-100 | 391 | 48.77% | -0.15% | 466 | 49.48% | 0.10% | +0.71 pp | +0.25 pp |
| Monthly | 6 | 12.0% | 0-100 | 2,839 | 46.99% | -1.47% | 2,915 | 47.09% | -1.44% | +0.11 pp | +0.04 pp |
| Monthly | 6 | 12.0% | 90-100 | 390 | 42.41% | -2.23% | 464 | 44.05% | -1.86% | +1.64 pp | +0.37 pp |
| Monthly | 9 | 18.0% | 0-100 | 2,815 | 45.09% | -2.02% | 2,890 | 45.52% | -1.92% | +0.43 pp | +0.10 pp |
| Monthly | 9 | 18.0% | 90-100 | 388 | 48.04% | -2.77% | 461 | 50.23% | -1.98% | +2.18 pp | +0.79 pp |

Across the complete output, follow-through improves monthly precision and average return in all 15 monthly horizon/confidence combinations. Weekly effects remain mixed: broad 4- and 12-week buckets improve slightly, while strict buckets weaken.

### Rerun Conclusion

The rerun does not support describing the expanded detector as more accurate or more profitable:

- It is structurally more capable and detects substantially more I-V, wave-V-end and completed A-B-C opportunities.
- Greater detection coverage produces more evaluated Elliott signals, but the additional high-confidence signals dilute the strongest weekly buckets and do not reproduce the earlier monthly performance.
- Monthly Elliott still improves average return relative to candlestick-only in every same-run bucket, and longer monthly horizons show meaningful relative precision gains. Most absolute monthly returns nevertheless remain negative.
- The one-candle follow-through remains beneficial on monthly intervals when compared with the identical no-follow-through detector.
- Confidence is a classification score, not a calibrated probability of profit. The current results show that higher scores do not consistently select better returns.

The production recommendation is therefore:

- Keep weekly Elliott alerts disabled.
- Treat monthly Elliott as an informational structural alert rather than a stand-alone trading recommendation. The present rerun gives weaker support for enabling it than the July 10 report did.
- Do not claim that the structural rewrite improved accuracy or expected return. Its demonstrated improvement is coverage and chart interpretation.
- Before changing production defaults, separately backtest impulse, wave-V-end and A-B-C correction signals, recalibrate their confidence scores, and preserve a frozen dataset plus old/new detector snapshots for a strict code-only A/B test.

No production alert setting was changed as part of this reporting run.

## Candlestick Stock-Only Setup Score V3

Date changed: 2026-07-23

This change applies only to candlestick setup scoring. The Elliott Wave detector, its score, its enabled intervals, and its 100-candle weekly/monthly input were not changed.

### Production invariant

A directional alert still requires a recognized candlestick formation with all mandatory candle geometry and classical prior-trend rules. The prior trend is established from raw OHLC structure across three to five completed pre-pattern candles, including meaningful net progress and either a coherent close sequence or a higher-high/higher-low sequence.

The following inputs are evaluated only after the pattern is valid and can only change its setup score and explanation:

- EMA;
- RSI;
- volume;
- Bollinger Bands;
- ATR and volatility context;
- completed higher-timeframe alignment;
- support/resistance proximity.

All scoring inputs now come from the instrument being checked. Weak, contradictory, missing, or unavailable scoring context does not suppress a valid candlestick alert. It also cannot create one. `DOJI` remains neutral (`HOLD`) and is not a directional alert.

### Daily five-component / higher-interval six-component score

| Component | Maximum | Evidence summarized |
|---|---:|---|
| Pattern quality | 35.7 daily; 21.4 weekly/monthly | Mandatory geometry quality plus raw-price prior-trend quality |
| Historical pattern calibration | 0 daily; 14.3 weekly/monthly | Frozen, sample-size-shrunk pattern reliability from the pre-2020 development sample |
| Higher-timeframe alignment | 21.4 | Completed weekly/monthly periods for daily candles; completed monthly/quarterly periods for higher intervals |
| Price location | 14.3 | Recent swing support/resistance and Bollinger Band contact |
| Volatility and momentum | 21.4 | Candle range versus ATR, ATR percentile, RSI level, and RSI turn |
| Volume | 7.2 | Current volume versus its 20-period average |
| **Total** | **100** | Heuristic confluence, not probability of profit |

The previous stock-only components totaled 70 points (25/15/10/15/5). Each component was multiplied by `100/70`, then represented to one decimal place with equal-weight components kept equal. The higher-interval pattern allocation preserves its former 15:10 structural-to-calibration split. Only completed higher-timeframe periods are used, and no other instrument is loaded or scored.

### Fresh V3 daily temporal rerun

Date run: 2026-07-24

The required V3 rerun is now complete. It used:

- the current production candlestick detector and stock-only V3 setup score;
- the same 30 large US stocks used in the earlier daily research;
- 30,124 cached daily candles spanning 2022-07-11 through 2026-07-23;
- a chronological walk-forward with a 250-candle indicator warmup and 100 candles passed to detection;
- the existing 10-candle / 3.0% fixed-horizon classification, with entry measured at the signal candle's close, so the results remain comparable with the archived V2 score test;
- no Elliott Wave signals, score gate, fees, spread, slippage, stop, sizing, capital, or overlapping-position constraints;
- the same split at 2025-01-01.

The later segment is a temporal stability check, not pristine out-of-sample evidence. The application, detector features, and broader dataset had already been inspected before this rerun. This score test is also not an executable trade simulation because it measures from the already-completed signal candle's close.

Command used:

```powershell
.\mvnw.cmd "-Dtest=HistoricalCandlestickScoringValidationTest" "-Dbacktest.candlestick.scoring.enabled=true" test
```

| Segment | Score/direction | Signals | Success | Failed | Inconclusive | Precision | Avg directional return |
|---|---|---:|---:|---:|---:|---:|---:|
| Development through 2024 | All | 525 | 148 | 139 | 238 | 51.57% | -0.08% |
| Development through 2024 | 0-59 | 113 | 27 | 38 | 48 | 41.54% | -1.10% |
| Development through 2024 | 60-69 | 199 | 54 | 49 | 96 | 52.43% | -0.27% |
| Development through 2024 | 70-100 | 213 | 67 | 52 | 94 | 56.30% | +0.64% |
| Development through 2024 | 75-100 | 87 | 32 | 13 | 42 | 71.11% | +1.62% |
| Development through 2024 | 70-100 BUY | 109 | 42 | 21 | 46 | 66.67% | +1.26% |
| Development through 2024 | 70-100 SELL | 104 | 25 | 31 | 48 | 44.64% | -0.02% |
| Development through 2024 | 75-100 BUY | 53 | 19 | 8 | 26 | 70.37% | +1.67% |
| Development through 2024 | 75-100 SELL | 34 | 13 | 5 | 16 | 72.22% | +1.55% |
| Validation from 2025 | All | 475 | 131 | 142 | 202 | 47.99% | -0.02% |
| Validation from 2025 | 0-59 | 109 | 30 | 44 | 35 | 40.54% | -0.65% |
| Validation from 2025 | 60-69 | 188 | 62 | 45 | 81 | 57.94% | +0.81% |
| Validation from 2025 | 70-100 | 178 | 39 | 53 | 86 | 42.39% | -0.50% |
| Validation from 2025 | 75-100 | 82 | 17 | 25 | 40 | 40.48% | -0.37% |
| Validation from 2025 | 70-100 BUY | 99 | 25 | 30 | 44 | 45.45% | -0.09% |
| Validation from 2025 | 70-100 SELL | 79 | 14 | 23 | 42 | 37.84% | -1.01% |
| Validation from 2025 | 75-100 BUY | 45 | 12 | 14 | 19 | 46.15% | +0.36% |
| Validation from 2025 | 75-100 SELL | 37 | 5 | 11 | 21 | 31.25% | -1.26% |

The V3 score did not preserve a monotonic relationship with outcomes. The 70+ and 75+ development results looked strong, but both deteriorated below 50% precision in the later segment. The later 60-69 bucket performed better than the higher buckets. Higher-timeframe alignment also fell from 51.26% precision / -0.08% average return in development to 44.50% / -0.62% later; price-location evidence moved from 57.89% / +0.34% to 50.63% / +0.24%. These results reject a 70, 75, or other V3 alert gate. The score remains an explanation and ranking heuristic, not a calibrated probability or permission to trade.

### Expanded 2,343-symbol cross-sectional validation

Date run: 2026-07-24

#### Blunt result

The much larger test overturns the earlier suggestion that `85+` is a reliably better bracket:

- in the untouched 2,193-symbol power-validation cohort, daily `85+` precision was **47.80%**, with a two-way symbol/year cluster-bootstrap 95% interval of **44.44%-51.42%** and an average directional return of **-0.37%**;
- daily `85+` BUY was **50.79%** with a clustered interval of **45.11%-56.91%** and a **+0.04%** average return, so it did not demonstrate an edge;
- daily `85+` SELL was **43.74%** with a clustered interval of **38.70%-48.78%** and a **-0.94%** average return;
- weekly `85+` was **45.24%**, **-1.41%** on average, and contained only BUY signals; its clustered interval was **31.42%-66.26%**;
- monthly `85+` produced only **17 signals and eight actionable outcomes** in 374,262 monthly candles, which is nowhere near enough to estimate precision.

This is strong evidence that `85+` is not a calibrated high-probability label. It should not be presented as having better historical precision than lower scores, and an `85+` alert should not be treated as permission to trade.

#### Frozen design and data

The production research dependencies were frozen before the holdout and power-validation outcomes were opened:

- `CandlePatternDetectionService`;
- `TechnicalIndicatorEnrichmentService`;
- `HistoricalSignalBacktestService`;
- `CandlestickPatternCalibration`;
- the `85` high-confidence boundary;
- daily 10-candle / 3.0%, weekly 8-candle / 8.0%, and monthly 6-candle / 12.0% outcomes.

The study uses the archived [Quandl WIKI Prices US Equities table](https://www.kaggle.com/datasets/marketneutral/quandl-wiki-prices-us-equites), ending on 2018-03-27. Adjusted open, high, low, close, and volume were used so stock splits do not create false candle shapes or false returns. The archive contains 15,389,314 rows and 3,199 tickers. The application does not redistribute the archive. Kaggle labels its license `Unknown`, even though the uploader describes the original table as public-domain data, so the file is suitable here as a local research input but requires a separate licensing review before commercial redistribution or bundling.

The frozen 150-symbol core was selected without inspecting algorithm outcomes:

- 40 large-cap, 40 mid-cap, and 40 small-cap endpoint companies;
- all 11 available major sector groups represented;
- 30 former S&P 500 tickers whose WIKI series ended before the dataset endpoint;
- 120 development symbols and 30 untouched holdout symbols;
- the holdout contains eight names from each active cap tier and six former constituents, with every active sector represented at least twice.

The frozen cap bands were $10 billion or more for large, $2-$10 billion for mid, and $300 million-$2 billion for small. Static sector metadata was used only to diversify the core; it can be imperfect for renamed companies and reused tickers, so no sector-level precision claim is made.

The 120-symbol development pilot revealed that the original 150-symbol design would not reach the planned high-score sample sizes. The study was therefore enlarged before any new-universe outcomes were inspected. The power cohort deterministically includes every remaining WIKI ticker that had:

- at least 2,400 daily rows in the fixed 2003-03-28 through 2018-03-27 window;
- at least 3,650 calendar days between its first and last included row;
- no invalid adjusted-OHLC row;
- no overlap with any of the 150 core symbols.

That outcome-blind rule produced 2,193 additional symbols, 7,802,979 daily candles, and 400 ended tickers. This cohort was run once after the detector, harness, eligibility rule, manifest, and source checksums were locked. The pilot-driven increase is a sample-size adaptation, so the untouched 2,193-symbol result is the primary estimate; the combined 2,343-symbol table is secondary.

| Disjoint cohort | Symbols | Daily candles | Weekly candles | Monthly candles | Outcome use |
|---|---:|---:|---:|---:|---|
| Development pilot | 120 | 439,905 | 91,353 | 21,099 | Exposed first; used only to size the extension |
| Frozen core holdout | 30 | 110,015 | 22,846 | 5,277 | Opened once after harness freeze |
| Power validation | 2,193 | 7,802,979 | 1,620,590 | 374,262 | Outcome-blind eligibility; opened once |
| **Disjoint total** | **2,343** | **8,352,899** | **1,734,789** | **400,638** | Intervals remain separate |

Weekly and monthly candles were aggregated from each symbol's adjusted daily series: first open, maximum high, minimum low, last close, and summed volume in each calendar week or month. Daily, weekly, and monthly results are not added together because they reuse the same underlying price path and are not independent observations.

The full prepared universe includes 430 ended/former tickers. That materially reduces survivor-only selection, but it is not a complete point-in-time reconstruction of every investable US stock. Former tickers are mostly acquisitions, mergers, or discontinued share classes; they are not all bankruptcies.

#### Outcome and uncertainty definitions

The outcome definition remains exactly comparable with the existing fixed-horizon score research:

- entry price is the detected candle's close;
- exit is the close after 10 daily, eight weekly, or six monthly candles;
- BUY uses normal percentage return and SELL uses the directional inverse;
- success requires at least +3%, +8%, or +12%, respectively;
- failure requires at most -3%, -8%, or -12%;
- everything between the two boundaries is inconclusive;
- precision is `success / (success + failure)`, excluding inconclusive outcomes;
- average directional return includes every signal.

The detected close is not an executable fill for a scanner that acts after the candle has completed. The test has no spread, slippage, fees, borrow cost, dividend payment on shorts, tax, stop, sizing, capital, overlap, or portfolio constraint. It is a score-validation test, not a profitability backtest.

Two uncertainty estimates are reported:

- the Wilson 95% interval treats actionable signals as independent and is included for conventional sample-size comparison;
- the primary cluster interval uses 10,000 deterministic bootstrap resamples across both symbols and calendar years, preserving signal dependence within a company and common market-period concentration.

The clustered interval is deliberately wider. Thousands of tickers do not create thousands of independent market regimes when they all trade through the same 15 years.

#### Untouched power-validation result

| Interval | Direction | Signals | Success | Failed | Inconclusive | Actionable | Precision | Wilson 95% CI | Cluster 95% CI | Cluster MOE | Avg return | 400 target |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Daily | Combined | 7,117 | 2,142 | 2,339 | 2,636 | 4,481 | 47.80% | 46.34%-49.27% | 44.44%-51.42% | 3.61 pp | -0.37% | Met |
| Daily | BUY | 4,124 | 1,311 | 1,270 | 1,543 | 2,581 | 50.79% | 48.87%-52.72% | 45.11%-56.91% | 6.11 pp | +0.04% | Met |
| Daily | SELL | 2,993 | 831 | 1,069 | 1,093 | 1,900 | 43.74% | 41.52%-45.98% | 38.70%-48.78% | 5.04 pp | -0.94% | Met |
| Weekly | Combined | 706 | 176 | 213 | 317 | 389 | 45.24% | 40.37%-50.21% | 31.42%-66.26% | 21.02 pp | -1.41% | Not met |
| Weekly | BUY | 706 | 176 | 213 | 317 | 389 | 45.24% | 40.37%-50.21% | 31.22%-67.02% | 21.77 pp | -1.41% | Not met |
| Weekly | SELL | 0 | 0 | 0 | 0 | 0 | n/a | n/a | n/a | n/a | n/a | Not met |
| Monthly | Combined | 17 | 4 | 4 | 9 | 8 | 50.00% | 21.52%-78.48% | 0.00%-100.00% | 50.00 pp | +2.28% | Not met |
| Monthly | BUY | 16 | 4 | 4 | 8 | 8 | 50.00% | 21.52%-78.48% | 0.00%-100.00% | 50.00 pp | +1.76% | Not met |
| Monthly | SELL | 1 | 0 | 0 | 1 | 0 | n/a | n/a | n/a | n/a | +10.60% | Not met |

The daily SELL cluster interval is wholly below 50%, which is a negative finding for the current score. The combined daily and BUY cluster intervals include 50%, so they do not demonstrate a directional advantage. A 50% classification reference is not itself an economic break-even benchmark, and no multiple-comparison-adjusted profitability claim is made.

The weekly raw actionable count nearly reached 400 in the untouched cohort, but it did not produce low uncertainty. Outcomes were heavily market-period-dependent: for example, 106 of the 152 weekly `85+` signals during 2008 were actionable failures or successes, and failures outnumbered successes 82 to 24. All 706 weekly high-score signals were BUY because the frozen weekly calibration awards its strongest prior points to bullish formations; no bearish weekly setup reached 85 in 1,620,590 weekly candles.

Monthly `85+` occurred once per approximately 22,000 monthly candles and produced an actionable result only eight times. At that observed rate, obtaining 400 actionable monthly outcomes would require roughly 18.7 million monthly candles, or more than 100,000 complete 15-year symbol histories. Adding a few hundred more tickers cannot make the current monthly `85+` bracket estimable.

#### Score ordering in the untouched cohort

| Interval | Score group | Signals | Actionable | Precision | Wilson 95% CI | Avg return |
|---|---|---:|---:|---:|---:|---:|
| Daily | All detected | 352,854 | 216,778 | 50.31% | 50.10%-50.52% | +0.04% |
| Daily | Below 75 | 293,927 | 180,158 | 50.50% | 50.27%-50.73% | +0.11% |
| Daily | 75-84 | 51,810 | 32,139 | 49.55% | 49.00%-50.09% | -0.32% |
| Daily | 85+ | 7,117 | 4,481 | 47.80% | 46.34%-49.27% | -0.37% |
| Weekly | All detected | 79,513 | 41,076 | 49.23% | 48.74%-49.71% | +0.08% |
| Weekly | Below 75 | 75,785 | 39,046 | 49.11% | 48.61%-49.60% | +0.09% |
| Weekly | 75-84 | 3,022 | 1,641 | 53.08% | 50.66%-55.48% | +0.27% |
| Weekly | 85+ | 706 | 389 | 45.24% | 40.37%-50.21% | -1.41% |
| Monthly | All detected | 17,898 | 10,800 | 46.93% | 45.99%-47.87% | +0.98% |
| Monthly | Below 75 | 17,753 | 10,709 | 46.84% | 45.90%-47.79% | +0.96% |
| Monthly | 75-84 | 128 | 83 | 57.83% | 47.09%-67.88% | +3.90% |
| Monthly | 85+ | 17 | 8 | 50.00% | 21.52%-78.48% | +2.28% |

The score is not monotonic. In daily data the 85+ bracket was worse than both all detections and below-75 detections. In weekly data 75-84 looked better than 85+, but this split was discovered in the final validation and cannot be promoted into a new production threshold without a new untouched sample. Monthly 75-84 remains far too small.

#### Secondary combined long-history result

Combining the three disjoint cohorts gives the planned raw weekly actionable count, but does not fix the dependence or monthly scarcity:

| Interval | Direction | Signals | Success | Failed | Inconclusive | Actionable | Precision | Wilson 95% CI | Avg return |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Daily | Combined | 7,612 | 2,263 | 2,500 | 2,849 | 4,763 | 47.51% | 46.10%-48.93% | -0.41% |
| Daily | BUY | 4,421 | 1,387 | 1,357 | 1,677 | 2,744 | 50.55% | 48.68%-52.42% | -0.01% |
| Daily | SELL | 3,191 | 876 | 1,143 | 1,172 | 2,019 | 43.39% | 41.24%-45.56% | -0.96% |
| Weekly | Combined / BUY | 767 | 187 | 230 | 350 | 417 | 44.84% | 40.14%-49.64% | -1.43% |
| Weekly | SELL | 0 | 0 | 0 | 0 | 0 | n/a | n/a | n/a |
| Monthly | Combined | 17 | 4 | 4 | 9 | 8 | 50.00% | 21.52%-78.48% | +2.28% |

The combined Wilson interval is not the primary inference because it treats correlated signals as independent. The untouched power cohort's cluster interval remains the honest uncertainty estimate.

#### Independence and generalization limits

`Untouched` here means the new ticker manifest and its outcomes were not inspected before the final run. It is a cross-sectional holdout, not a later-time holdout. The 2003-2018 market years overlap the pre-2020 period used to create the frozen weekly/monthly pattern-calibration table, although the 2,193 power tickers do not overlap the 150-symbol core and were not selected by outcomes. Common market regimes can therefore still leak broad conditions across tickers.

This makes the negative high-score result especially important, but it means a positive subset from this report would still require a new temporal test. A production trading claim needs a separately licensed, point-in-time dataset covering post-development years, delisted securities, actual next-open execution, transaction and borrow costs, dividends, and portfolio overlap.

#### Reproduction and checksums

The reproducible harness is `ExpandedCandlestickStatisticalValidationTest`; the source preparation command verifies both the downloaded zip and its uncompressed CSV before producing adjusted subsets.

```powershell
python scripts\prepare_expanded_candlestick_backtest.py `
  --archive target\expanded-backtest-data\quandl-wiki-prices.zip

python scripts\prepare_expanded_candlestick_backtest.py `
  --archive target\expanded-backtest-data\quandl-wiki-prices.zip `
  --build-power-universe `
  --manifest target\expanded-backtest-data\expanded-candlestick-power-universe.tsv `
  --output target\expanded-backtest-data\expanded-power-candles.csv.gz

.\mvnw.cmd "-Dtest=ExpandedCandlestickStatisticalValidationTest" `
  "-Dbacktest.expanded.enabled=true" `
  "-Dbacktest.expanded.stage=holdout" `
  "-Dbacktest.expanded.bootstrap-replicates=10000" test

.\mvnw.cmd "-Dtest=ExpandedCandlestickStatisticalValidationTest" `
  "-Dbacktest.expanded.enabled=true" `
  "-Dbacktest.expanded.stage=validation" `
  "-Dbacktest.expanded.bootstrap-replicates=10000" `
  "-Dbacktest.expanded.manifest-file=target\expanded-backtest-data\expanded-candlestick-power-universe.tsv" `
  "-Dbacktest.expanded.data-file=target\expanded-backtest-data\expanded-power-candles.csv.gz" test
```

| Frozen artifact | SHA-256 |
|---|---|
| Source zip | `ADFD226694C6F3EC2C56B585D973764180A39A3EC516601721E90096DC1DE94F` |
| Uncompressed `WIKI_PRICES.csv` | `CA7FB174C7948DB85638917D25FF65D438E27D5CB23675DA784C54DB01E3D003` |
| Core manifest | `20C0B44D9A05635B46AC8378CC90DEC9405918A847B2451DFF2953C2E6C36D98` |
| Prepared core candles | `EE7EFBB991B271057BE8B2677BF5848DC5B172462B3F14DC05DFBBA4FDD3C31F` |
| Power manifest | `8364BE9B75B3D91A3377FB128195849CAD080189FE928243AC92F6CE573F160F` |
| Prepared power candles | `3E99F1BD3CC0DB80DE45CA02BB7D9B89DBCB307F48DA17FC1EC75542A85245BD` |

#### Production decision

- Do not market `85+` as statistically higher precision or a probability of success.
- Do not gate trades or increase position size because a score reached 85.
- Do not change the detector to weekly 75-84 or monthly 75-84 based on this final sample; those are post-hoc research observations.
- Preserve immediate `DETECTED` and factual lifecycle follow-ups because those communicate observed state rather than a guaranteed return.
- Treat daily SELL and weekly high-score behavior as failed validation targets.
- Keep monthly `85+` out of any quoted precision table until its scoring design is changed on development data and tested on a new untouched dataset.
- Any future score redesign must use a new version, a new development sample, and a separately frozen post-development validation set. Reusing this 2003-2018 power cohort for tuning would convert it into development data.

## Three-Candle Candlestick Signal Lifecycle Backtest

Date run: 2026-07-24

### Rules and data tested

The historical harness uses the same pure lifecycle policy as the live service, rather than reimplementing a similar rule inside the test:

- freeze the complete one-, two-, or three-candle pattern high and low when the signal is detected;
- for BUY, confirm on the first subsequent close strictly above the high and invalidate on the first close strictly below the low;
- for SELL, confirm on the first subsequent close strictly below the low and invalidate on the first close strictly above the high;
- equality with a boundary and intraday high/low crossings do not resolve the signal;
- the first terminal close wins;
- expire at the third subsequent close if neither boundary has closed beyond it.

Detection still uses the current production detector, a 250-candle warmup, 100 detection candles, and no Elliott Wave signals. All 30 symbols had sufficient data. The cache contained 30,124 daily candles from 2022-07-11 through 2026-07-23. The lifecycle population contains 1,008 detections with all three following candles available; signals too near the right edge are excluded rather than assigned an incomplete outcome.

Commands used:

```powershell
.\mvnw.cmd "-Dtest=CandlestickSignalLifecycleServiceTest" test
.\mvnw.cmd "-Dtest=HistoricalCandlestickLifecycleBacktestTest" "-Dbacktest.candlestick.lifecycle.enabled=true" test
```

### Executable-timing methodology

Two timings are reported and must not be confused:

- **Detection entry:** the next daily candle's open after the pattern has completed. This is the earliest hypothetical entry in this daily-close system.
- **Post-confirmation entry:** the next daily candle's open after the confirming candle has closed. Entering at the confirming close would use a price before the system can safely act on that completed close.

The exit is the close of the fifth, tenth, or twentieth holding-session candle, including the entry session. BUY returns use normal percentage change; SELL returns use its directional inverse. The primary outcome is the report's existing 10-session / +/-3.0% classification. `Precision` is success / (success + failure), excluding inconclusive final returns. `Gross+` is the percentage finishing above 0%. `Net+` is the percentage finishing above an illustrative 0.20% round-trip cost, equivalent to subtracting 0.20 percentage points from every gross directional return. MFE and MAE are average maximum favorable and adverse intraperiod moves from the next-open entry; they do not implement a target or stop.

This remains a signal-level research simulation. It does not model bid/ask spread separately, variable slippage, taxes, short borrow availability or fees, dividends, position sizing, portfolio capital, simultaneous/overlapping positions, compounding, stop execution, gaps through a stop, or market impact. The 0.20% cost is an explicit sensitivity assumption, not an estimate for every broker or instrument.

### Lifecycle resolution

| Terminal status | Signals | Share | BUY | SELL |
|---|---:|---:|---:|---:|
| Confirmed | 444 | 44.05% | 214 | 230 |
| Invalidated | 355 | 35.22% | 115 | 240 |
| Expired | 209 | 20.73% | 88 | 121 |
| **Total detected** | **1,008** | **100.00%** | **417** | **591** |

| Resolution candle after detection | Confirmed | Invalidated | Expired | Total |
|---:|---:|---:|---:|---:|
| 1 | 273 | 208 | 0 | 481 |
| 2 | 102 | 102 | 0 | 204 |
| 3 | 69 | 45 | 209 | 323 |

The mean terminal delay was 1.84 candles. If confirmation were used as an entry gate, only 44.05% of detections would become eligible, so it would indeed produce fewer entries. The implemented notification workflow does not suppress those detections: it sends `DETECTED` first and later sends one terminal follow-up.

### Primary 10-session result

Rows have slightly different counts because a complete lifecycle needs three future candles while a 10-session return after a delayed entry can need additional candles near the dataset boundary.

| Cohort and hypothetical entry | N | Success | Failed | Inconclusive | Precision | Gross+ | Net+ | Avg gross | Median | Avg net | Avg MFE | Avg MAE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| All detected / next open after detection | 1,000 | 284 | 276 | 440 | 50.71% | 52.20% | 50.40% | -0.01% | +0.26% | -0.21% | +4.45% | -4.61% |
| Eventually confirmed / next open after detection | 440 | 178 | 82 | 180 | 68.46% | 65.68% | 63.64% | +1.76% | +1.76% | +1.56% | +6.03% | -3.15% |
| Confirmed / next open after confirmation | 440 | 129 | 112 | 199 | 53.53% | 51.36% | 50.00% | +0.22% | +0.17% | +0.02% | +4.61% | -4.38% |
| Invalidated / next open after detection | 351 | 51 | 146 | 154 | 25.89% | 35.33% | 33.90% | -2.46% | -1.81% | -2.66% | +2.85% | -6.81% |
| Expired / next open after detection | 208 | 55 | 48 | 105 | 53.40% | 52.40% | 50.48% | +0.38% | +0.25% | +0.18% | +3.82% | -3.97% |
| Confirmed BUY / next open after confirmation | 213 | 73 | 40 | 100 | 64.60% | 59.15% | 57.28% | +1.15% | +0.91% | +0.95% | +4.90% | -3.88% |
| Confirmed SELL / next open after confirmation | 227 | 56 | 72 | 99 | 43.75% | 44.05% | 43.17% | -0.66% | -0.70% | -0.86% | +4.33% | -4.84% |

The `eventually confirmed / next open after detection` row is hindsight-only: at that entry time nobody knows which detections will later confirm. It demonstrates that the terminal label separates a historically stronger subset, but it is not an executable selection rule. Once entry waits until the next open after confirmation, the combined result falls to +0.22% gross and +0.02% after the illustrative cost. Most of the apparent confirmation edge occurred in the move that caused confirmation.

Invalidation was informative: detections that later invalidated averaged -2.46% from the initial next-open entry. That does not make invalidation a validated stop. The notification arrives only after a completed close outside the range, and the test did not exit at that close or model the next executable fill.

### Horizon and temporal stability

| Cohort | Horizon / move | N | Precision | Avg gross | Avg net |
|---|---|---:|---:|---:|---:|
| All detected / detection entry | 5 sessions / 2% | 1,006 | 47.46% | -0.18% | -0.38% |
| Confirmed / post-confirmation entry | 5 sessions / 2% | 441 | 50.62% | -0.09% | -0.29% |
| All detected / detection entry | 10 sessions / 3% | 1,000 | 50.71% | -0.01% | -0.21% |
| Confirmed / post-confirmation entry | 10 sessions / 3% | 440 | 53.53% | +0.22% | +0.02% |
| All detected / detection entry | 20 sessions / 5% | 987 | 47.71% | -0.31% | -0.51% |
| Confirmed / post-confirmation entry | 20 sessions / 5% | 433 | 50.00% | +0.17% | -0.03% |

| Signal-date segment, 10-session post-confirmation test | N | Precision | Avg gross | Avg net |
|---|---:|---:|---:|---:|
| Development through 2024, all detected / detection entry | 525 | 50.70% | -0.08% | -0.28% |
| Development through 2024, confirmed combined | 224 | 51.35% | +0.11% | -0.09% |
| Development through 2024, confirmed BUY | 105 | 68.09% | +1.26% | +1.06% |
| Development through 2024, confirmed SELL | 119 | 39.06% | -0.91% | -1.11% |
| Validation from 2025, all detected / detection entry | 475 | 50.72% | +0.07% | -0.13% |
| Validation from 2025, confirmed combined | 216 | 55.38% | +0.33% | +0.13% |
| Validation from 2025, confirmed BUY | 108 | 62.12% | +1.04% | +0.84% |
| Validation from 2025, confirmed SELL | 108 | 48.44% | -0.38% | -0.58% |

The BUY/SELL split was directionally stable in this reused sample: confirmed BUY was positive in both periods and confirmed SELL was negative in both. That makes confirmed BUY a research candidate, not a production rule. The split was examined retrospectively, trades overlap, the symbols share market regimes, and no untouched external or post-freeze period has tested it.

### V3 score inside the confirmed cohort

| V3 score, 10-session post-confirmation entry | N | Precision | Avg gross | Avg net |
|---|---:|---:|---:|---:|
| 0-59 | 78 | 48.08% | -0.05% | -0.25% |
| 60-69 | 176 | 61.86% | +0.68% | +0.48% |
| 70-100 | 186 | 47.83% | -0.11% | -0.31% |

Confirmation did not repair the V3 score ordering: 60-69 outperformed 70+ here as well. There is no basis for interpreting a higher V3 number as a higher probability of profit.

### Blunt conclusion and production decision

The lifecycle is useful product information, but this run does not validate a profitable combined trading system:

- immediate detections remained approximately flat gross and negative after the illustrative cost at every tested horizon;
- waiting for confirmation improved the combined descriptive result only to approximately break-even after cost;
- confirmed BUY was the one promising directional subset, while confirmed SELL remained negative;
- the V3 score was not monotonic and its high-score development performance did not survive the later segment;
- no stop, target, sizing, portfolio, overlap, borrow, dividend, or full execution model was tested;
- this is reused historical data, not an untouched prospective test, and no multiple-testing or correlated-signal significance claim is made.

Production should therefore keep sending `DETECTED` plus the factual `CONFIRMED`, `INVALIDATED`, or `EXPIRED` follow-up, but must not describe `CONFIRMED` as a buy/sell instruction or profitability guarantee. No detector or score gate should be changed from this retrospective run. A trading product would still need pre-registered entry, exit, stop/invalidation execution, sizing, cost and portfolio rules, followed by a frozen external or post-2026-07-24 walk-forward test.

## Archived Candlestick Contextual Setup Score V2 Results

### Backtest methodology

The rerun uses the same fixed-horizon methodology as the earlier report:

- 30 large US stocks and `^GSPC` as the same-interval market benchmark;
- 30,078 cached daily candles, spanning 2022-07-11 through 2026-07-20;
- chronological walk-forward detection with a 250-candle indicator warmup;
- 100 asset candles passed to candlestick detection so 60-period relative strength and completed weekly/monthly context are available;
- no Elliott Wave signals;
- precision excludes inconclusive outcomes, while average directional return includes every signal;
- no fees, spread, slippage, position sizing, stop loss, or overlapping-trade constraints.

Commands used:

```powershell
.\mvnw.cmd "-Dtest=HistoricalSignalRealDataBacktestTest" "-Dbacktest.real.enabled=true" test
.\mvnw.cmd "-Dtest=HistoricalSignalThresholdSweepTest" "-Dbacktest.sweep.enabled=true" test
.\mvnw.cmd "-Dtest=HistoricalSignalPatternCalibrationTest" "-Dbacktest.pattern.enabled=true" test
.\mvnw.cmd "-Dtest=HistoricalCandlestickScoringValidationTest" "-Dbacktest.candlestick.scoring.enabled=true" test
```

### Full-sample score sweep

| Horizon | Required move | Setup score | Signals | Success | Failed | Inconclusive | Precision | Avg directional return |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 2.0% | 0-100 | 1,003 | 276 | 296 | 431 | 48.25% | -0.22% |
| 5 | 2.0% | 60-100 | 392 | 125 | 103 | 164 | 54.82% | +0.03% |
| 5 | 2.0% | 70-100 | 110 | 42 | 31 | 37 | 57.53% | +0.34% |
| 5 | 2.0% | 75-100 | 35 | 11 | 12 | 12 | 47.83% | -0.01% |
| 10 | 3.0% | 0-100 | 997 | 279 | 280 | 438 | 49.91% | -0.04% |
| 10 | 3.0% | 60-100 | 389 | 116 | 107 | 166 | 52.02% | +0.15% |
| 10 | 3.0% | 70-100 | 110 | 38 | 28 | 44 | 57.58% | +0.29% |
| 10 | 3.0% | 75-100 | 35 | 13 | 10 | 12 | 56.52% | +0.30% |
| 20 | 5.0% | 0-100 | 984 | 234 | 250 | 500 | 48.35% | -0.33% |
| 20 | 5.0% | 60-100 | 386 | 98 | 86 | 202 | 53.26% | +0.33% |
| 20 | 5.0% | 70-100 | 109 | 33 | 25 | 51 | 56.90% | +0.62% |
| 30 | 8.0% | 0-100 | 963 | 169 | 192 | 602 | 46.81% | -0.73% |
| 30 | 8.0% | 60-100 | 377 | 70 | 55 | 252 | 56.00% | +0.42% |
| 30 | 8.0% | 65-100 | 214 | 46 | 32 | 136 | 58.97% | +1.46% |

The score separates some useful full-sample cohorts: 60+ improves precision and average return at each selected horizon, and 70+ usually improves precision further. It is not monotonic, however. At five candles the small 75+ bucket falls back to 47.83% precision, and no signal reached 80 in this sample. These observations rule out interpreting the numeric score as a calibrated probability.

### Chronological stability check

The 10-candle / 3.0% sample was split at 2025-01-01. This remains a retrospective stability check rather than pristine out-of-sample evidence because the broader dataset and candidate features have already been inspected.

| Segment | Score/direction | Signals | Success | Failed | Inconclusive | Precision | Avg directional return |
|---|---|---:|---:|---:|---:|---:|---:|
| Development through 2024 | All | 525 | 148 | 139 | 238 | 51.57% | -0.08% |
| Development through 2024 | 60-69 | 135 | 40 | 34 | 61 | 54.05% | +0.17% |
| Development through 2024 | 70-100 | 57 | 19 | 14 | 24 | 57.58% | +0.77% |
| Development through 2024 | 75-100 | 16 | 8 | 3 | 5 | 72.73% | +3.38% |
| Validation from 2025 | All | 472 | 131 | 141 | 200 | 48.16% | -0.01% |
| Validation from 2025 | 60-69 | 144 | 38 | 45 | 61 | 45.78% | +0.02% |
| Validation from 2025 | 70-100 | 53 | 19 | 14 | 20 | 57.58% | -0.24% |
| Validation from 2025 | 75-100 | 19 | 5 | 7 | 7 | 41.67% | -2.30% |
| Validation from 2025 | 70-100 BUY | 39 | 16 | 8 | 15 | 66.67% | +0.73% |
| Validation from 2025 | 70-100 SELL | 14 | 3 | 6 | 5 | 33.33% | -2.93% |

The 70+ band preserved higher precision in the later segment, especially for BUY signals, but its overall average return weakened and the 75+ development result did not generalize. Individual context components also did not produce a stable standalone edge. The score should therefore be used to explain and rank confluence, not to suppress alerts, claim a probability, or authorize a trade automatically.

### Per-pattern reminder

At 10 candles / 3.0%, the strongest full-sample raw patterns remained `BULLISH_ENGULFING` (93 signals, 70.69% precision, +1.92% average return), `INVERTED_HAMMER` (91, 63.16%, +0.88%), and `BULLISH_HARAMI` (81, 58.33%, +1.14%). Several bearish formations remained below 50% precision with negative average returns. These are descriptive sample results, and the earlier matched-control test still prevents claiming that the candle shape itself has demonstrated incremental predictive value.

### Decision

- Ship the richer candlestick-only explanation and setup score.
- Continue sending every structurally valid directional candlestick alert regardless of score.
- Keep Elliott Wave scoring and detection unchanged.
- Do not add a 70, 75, or any other production alert threshold from this retrospective sample.
- Treat 70+ BUY behavior as a research candidate for a future frozen, transaction-cost-aware validation, not as a production filter.

## Frozen Weekly and Monthly Pattern Calibration

Date run: 2026-07-21

### Why published rankings were not copied directly

The published evidence is mixed and is overwhelmingly based on daily candles rather than weekly or monthly candles:

- Caginalp and Laurent reported predictive reversal behavior for combined candlestick rules in an older S&P 500 sample, but their method aggregates patterns and uses a different exit rule ([Applied Mathematical Finance](https://www.tandfonline.com/doi/abs/10.1080/135048698334637)).
- Marshall, Young, and Rose found that candlestick strategies did not consistently outperform the Dow Jones Industrial Average under a more robust bootstrap design ([Journal of Banking & Finance](https://www.sciencedirect.com/science/article/abs/pii/S0378426605002116)).
- Horton found little forecasting value across nine named patterns in 349 US stocks ([Quarterly Review of Economics and Finance](https://www.sciencedirect.com/science/article/pii/S106297690700097X)).
- Lu, Shiu, and Liu reported strong out-of-sample daily results for bullish piercing, engulfing, and harami rules in Taiwan, while the tested bearish rules had weak or negative average returns after their assumed costs ([Research in International Business and Finance paper](https://ah.lib.nccu.edu.tw/bitstream/140.119/61660/1/6368.pdf)).

This supports using external research as a conservative prior, not as a transferable win-rate table. The production weights therefore come from this application's own strict detector, symbols, intervals, and outcome definitions.

### Calibration method

- Weekly cache coverage was expanded from 4/30 to 30/30 representative US stocks before fitting.
- The frozen development segment contains signals before 2020-01-01. Candles from 2020 onward are kept as a chronological stability segment.
- Weekly calibration uses the 8-candle / 8.0% outcome. Monthly calibration uses the 6-candle / 12.0% outcome.
- Pattern precision is shrunk toward the same-interval, same-direction baseline with 30 prior actionable outcomes. This prevents a tiny pattern sample from receiving an extreme score and avoids comparing a bullish pattern only with a bearish baseline.
- The shrunk rate maps to 0-10 ranking points. Patterns with fewer than 10 actionable development outcomes receive the neutral 5/10 score.
- The calibration model still emits its frozen raw 0-10 assessment. In V3 that assessment receives 14.3 setup-score points, while weekly/monthly structural pattern quality receives 21.4. Daily structural pattern quality receives 35.7. The proportional stock-only total remains 100.
- The table is frozen in code. Production does not learn from live outcomes, inspect future candles, or make network calls while scoring.

Commands used:

```powershell
.\mvnw.cmd "-Dtest=HigherIntervalSignalBacktestTest" "-Dbacktest.higher.enabled=true" "-Dbacktest.higher.sync-missing=true" test
.\mvnw.cmd "-Dtest=HistoricalHigherIntervalCandlestickCalibrationTest" "-Dbacktest.candlestick.higher.calibration.enabled=true" test
```

### Chronological stability results

Precision remains `success / (success + failure)` and excludes inconclusive outcomes. Average return includes every signal. These are retrospective stability results, not a calibrated probability or a transaction-cost-aware trading simulation.

| Interval outcome | Cohort in 2020+ segment | Signals | Actionable | Precision | Avg directional return |
|---|---|---:|---:|---:|---:|
| Weekly 4 candles / 4% | All patterns | 594 | 333 | 52.55% | +0.48% |
| Weekly 4 candles / 4% | Score 60+ | 116 | 77 | 58.44% | +1.45% |
| Weekly 4 candles / 4% | Score 65+ | 66 | 49 | 55.10% | +1.27% |
| Weekly 8 candles / 8% | All patterns | 592 | 264 | 50.76% | +0.40% |
| Weekly 8 candles / 8% | Score 60+ | 116 | 64 | 68.75% | +5.14% |
| Weekly 8 candles / 8% | Score 65+ | 66 | 39 | 61.54% | +3.17% |
| Weekly 12 candles / 12% | All patterns | 587 | 218 | 46.79% | -0.66% |
| Weekly 12 candles / 12% | Score 60+ | 115 | 50 | 70.00% | +5.80% |
| Weekly 12 candles / 12% | Score 65+ | 65 | 28 | 64.29% | +3.05% |
| Monthly 3 candles / 6% | All patterns | 157 | 100 | 39.00% | -3.21% |
| Monthly 3 candles / 6% | Score 60+ | 21 | 14 | 35.71% | -1.93% |
| Monthly 3 candles / 6% | Score 65+ | 14 | 9 | 44.44% | +0.50% |
| Monthly 6 candles / 12% | All patterns | 153 | 87 | 31.03% | -5.58% |
| Monthly 6 candles / 12% | Score 60+ | 21 | 10 | 60.00% | +8.57% |
| Monthly 6 candles / 12% | Score 65+ | 14 | 7 | 71.43% | +13.11% |
| Monthly 9 candles / 18% | All patterns | 145 | 79 | 34.18% | -10.54% |
| Monthly 9 candles / 18% | Score 60+ | 18 | 6 | 83.33% | +16.53% |
| Monthly 9 candles / 18% | Score 65+ | 12 | 3 | 100.00% | +16.70% |

The monthly 9-candle percentages are based on only six and three actionable outcomes, so they must not be treated as reliable precision estimates. The monthly 3-candle result also shows that the calibration is not uniformly beneficial at every horizon.

### Most stable pattern observation

The strongest repeatable split was direction rather than a universal named-pattern hierarchy. In the 2020+ weekly segment, bullish hammers reached 75.0%, 88.2%, and 84.2% precision over the 4-, 8-, and 12-candle outcomes. Bullish engulfing reached 72.2%, 68.8%, and 70.4%. Most bearish formations remained below 50% and had negative average directional returns. Monthly pattern-level validation counts were much smaller, so their individual rankings remain tentative.

At the 75+ full-sample weekly score band, calibration reduced coverage from 55 to 38 signals but improved the descriptive results:

| Weekly outcome | Before calibration precision / return | After calibration precision / return |
|---|---:|---:|
| 4 candles / 4% | 48.57% / +0.62% | 60.87% / +2.94% |
| 8 candles / 8% | 58.62% / +2.21% | 68.42% / +4.94% |
| 12 candles / 12% | 61.90% / +2.64% | 86.67% / +7.03% |

The analogous monthly 75+ cohort contained only seven signals and did not improve consistently. Monthly conclusions should therefore focus on the broader 60+ cohort and on accumulating more forward observations.

### Production decision

- Add frozen empirical-Bayes calibration to candlestick scoring on weekly and monthly intervals only.
- Keep daily candlestick scoring and all Elliott Wave logic unchanged.
- Continue emitting every structurally valid directional candlestick pattern. Calibration changes only score and explanation; it is not an alert gate.
- Do not describe the setup score as a probability, and do not advertise the tiny 70+/75+ cohorts as expected precision.
- Refit only through an explicit offline, chronological calibration process after enough new outcomes accumulate.

### User-facing research horizons

Alert emails and website signal views show the evaluation window in which the historical tests were most useful. This is explanatory metadata only; it does not change detection, scoring, or alert emission.

| Signal interval | Displayed research horizon | Interpretation |
|---|---|---|
| Daily | 10&ndash;30 trading sessions | Ten sessions is the primary directional calibration window; 20&ndash;30 sessions capture slower follow-through. |
| Weekly | 8&ndash;12 weeks | Score separation was clearest at 8 and 12 weeks; the 4-week result was weaker. |
| Monthly | About 6 months | Six months is the primary window. Three months was weak, and nine months remains preliminary because the actionable sample is too small. |

These are historical evaluation windows, not recommended holding periods, price targets, precision guarantees, or financial advice. Elliott Wave alerts do not receive this candlestick-specific guidance.
