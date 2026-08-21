# Candlestick SELL improvement: second research plan

Date: 2026-08-16

## Starting point

The first refinement experiment was rejected and fully rolled back. On the frozen
2,227-stock universe, waiting for an ATR-buffered breakdown reduced signal volume
and worsened both precision and average direction-adjusted return on Daily,
Weekly, and Monthly intervals. Post-breakdown retest/continuation states were not
stable between 2019-2021 and 2022-2025. Broad bearish-market regimes had already
failed as a stable SELL filter.

This changes the design premise: a bearish reversal must be evaluated at the
pattern's completed close. A later breakdown can consume any short-lived edge and
must not be assumed to improve the original occurrence.

Production remains on `CANDLE_V4_EXPERIMENTAL`. This document authorizes no
detection, scoring, notification, persistence, or UI change.

## Phase 1: diagnose the target before designing another rule

For every existing SELL occurrence, measure the causal path after the occurrence
close rather than only one fixed endpoint:

- maximum favorable excursion (largest fall) and maximum adverse excursion;
- return after 1, 3, 5, 10, and 20 Daily bars;
- return after 1, 2, 4, 8, and 12 Weekly bars;
- return after 1, 3, 6, and 9 Monthly bars;
- time to the best favorable excursion;
- whether a useful fall happened temporarily and was later reversed.

This determines whether the current model has no bearish information at all or
whether the existing fixed horizons are measuring after a short-lived reversal
has already ended. No entry filter should be optimized until this path diagnostic
is complete.

## Phase 2: pre-registered occurrence-time candidates

Each candidate must use only information available at the pattern close and must
first be tested alone. Threshold grids must be declared before outcomes are read.

### 1. Rejection quality

- close-location value within the candle's range;
- upper-wick/body ratio;
- bearish body and body/range ratio where compatible with the named pattern;
- volume relative to its completed-bar average;
- bearish volume relative to the preceding bullish candle.

The purpose is to distinguish a geometric bearish shape from a candle that
actually rejected higher prices. These are candidate features, not assumed rules.

### 2. Overextension followed by an inflection

- prior advance measured in ATR, not raw percentage;
- distance above the fast EMA, slow EMA, and long moving average in ATR;
- previous RSI/CCI elevated and current RSI/CCI falling;
- five-bar momentum decelerating relative to the preceding five bars;
- MACD histogram weakening while price remains extended.

An elevated oscillator by itself is insufficient. The candidate requires evidence
of a turn that is already visible at the occurrence close.

### 3. Resistance and failed breakout location

- pattern high within a declared ATR distance of a causal prior swing high;
- test of the upper Bollinger band followed by a close back inside;
- close below an anchored or rolling VWAP after trading above it;
- failed new price high accompanied by a lower momentum high.

### 4. Failed leadership, not broad bearish regime

The rejected experiment displayed unscored broad-benchmark context. The new test
would instead ask a narrower question: did the stock previously outperform its
benchmark or sector and then begin underperforming at the pattern close? This must
use cached, timestamp-aligned benchmark candles and must report missing benchmark
coverage explicitly. It cannot silently substitute future or fetched-on-demand
data.

### 5. Pattern/interval calibration

Results must be reported separately for every bearish pattern and interval. A
candidate model may use a pattern/interval combination only if it passes the same
walk-forward acceptance rules; a strong aggregate must not hide a consistently
bad subgroup. Named occurrences can still remain visible even if their directional
forecast is not promoted.

## Phase 3: validation design

The existing 2019-2025 outcomes have already been examined and are not an untouched
holdout. They can be used for walk-forward development, but the report must say so.

1. Use expanding walk-forward folds and never fit thresholds on a fold's future.
2. Group uncertainty by ticker and calendar month so clustered market episodes do
   not masquerade as thousands of independent observations.
3. Report raw N, retained share, successes, failures, inconclusive outcomes,
   precision, Wilson lower bound, average return, median return, favorable/adverse
   excursion, and ticker-balanced precision.
4. Run ablations. A combined model must beat each simpler component rather than
   receiving credit for an unnecessary rule pile.
5. Keep Daily, Weekly, and Monthly decisions independent.
6. Reserve genuinely new post-2025 data as the final untouched confirmation once
   adequate coverage is available.

## Acceptance gates before production

A new SELL policy is deployable only if all of the following are true:

- precision improves by at least 3 percentage points over the V4 occurrence
  baseline in every validation fold used for that interval;
- average direction-adjusted return improves by at least 0.50 percentage points
  and is not negative in the combined validation result;
- the direction of both improvements is consistent in early and late periods;
- at least 25% of existing SELL occurrences remain, unless a smaller subgroup was
  explicitly pre-registered as a separate high-conviction tier;
- ticker-balanced results agree with signal-weighted results;
- improvement is not produced by one pattern, ticker, or market episode;
- the final untouched period confirms both precision and average-return gains.

If a model improves ranking but not an actionable cutoff, it may replace the score
only after score-band monotonicity is demonstrated. It must not suppress pattern
occurrences or alter notification eligibility.

## Recommended next executable study

Build one opt-in research harness that first emits the full forward-path diagnostic
and then compares five single-feature families: rejection quality, ATR-normalized
overextension, oscillator inflection, resistance/failed breakout, and failed
relative leadership. Do not implement another production model until that report
identifies a stable candidate and the acceptance gates above are met.
