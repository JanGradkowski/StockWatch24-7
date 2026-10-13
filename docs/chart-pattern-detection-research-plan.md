# Automatic chart patterns: research plan

Date: 2026-10-11

## Why a separate plan

TradingView marks classic chart patterns automatically: head and shoulders, double
tops and bottoms, triangles, wedges, flags, pennants and rectangles. The chart-parity
plan (`tradingview-chart-parity-plan.md`, Phase 6) deliberately left them out of the
chart work. A pattern that the app draws looks like a recommendation, so it needs the
same standard as the candlestick, harmonic and Elliott families: a pre-registered
definition, a backtest on a frozen universe, documented outcomes, and an explicit
decision before any user sees it.

This document authorizes research only. It authorizes no detection code in
production, no overlay, no alert family, no score, no email and no persistence
change. The manual drawing tools that already exist (head and shoulders, triangle,
XABCD, Elliott and so on in the drawing rail) are unaffected: they draw what the user
places and claim nothing.

## Scope

Patterns, each in its bullish and bearish form where one exists:

| Group | Patterns |
| --- | --- |
| Reversal | Head and shoulders, inverse head and shoulders, double top, double bottom, triple top, triple bottom |
| Triangles | Ascending, descending and symmetrical triangle |
| Wedges | Rising wedge, falling wedge |
| Continuation | Bull and bear flag, bull and bear pennant, rectangle (range) |

Intervals: Daily, Weekly and Monthly, as for the other families. Intraday is out of
scope.

## Phase 1: definitions fixed before any outcome is read

Every pattern is defined on completed candles only, from pivots that are themselves
confirmed (a pivot is only known `k` bars after it forms; `k` is part of the
definition and enters the occurrence time).

1. **Pivots.** One pivot detector for all patterns: the existing Zig Zag
   (percentage deviation and depth) and an ATR-scaled alternative. The choice and
   its parameters are fixed per interval in this phase and not tuned on outcomes.
2. **Geometry in ATR units.** Equal highs and lows (double tops, triangle flat
   sides, head-and-shoulders necklines) are "equal" within a tolerance measured in
   ATR at the pivot, not in percent. Slopes are measured in ATR per bar.
3. **Minimum size and duration.** Each pattern has a minimum height (ATR multiple)
   and a minimum and maximum number of bars, per interval.
4. **Completion event.** A pattern is a signal only when price closes beyond its
   breakout line (neckline, triangle side, wedge line, flag channel). The occurrence
   time is that close. Patterns that never break out are recorded as non-events,
   not as failures, so the drawn-but-unconfirmed shapes can be studied separately.
5. **Invalidation.** Each definition states when a forming pattern stops existing
   (for example, a head and shoulders whose right shoulder exceeds the head).
6. **Measured move.** The textbook target (height projected from the breakout) is
   recorded for evaluation only; it is not assumed to be meaningful.

The definitions, thresholds and the grid of any alternatives are committed to this
repository before Phase 2 reads a single outcome.

## Phase 2: backtest on the frozen universe

Data: the frozen 2,227-stock universe and the 2019–2025 history used for the
candlestick studies, split into 2019–2021 and 2022–2025 so that every result is
reported for both periods.

For every occurrence, measured from the breakout close:

- direction-adjusted return after 1, 3, 5, 10 and 20 Daily bars; 1, 2, 4, 8 and 12
  Weekly bars; 1, 3, 6 and 9 Monthly bars;
- maximum favorable and adverse excursion, and time to each;
- whether the measured-move target or the pattern's invalidation level is reached
  first;
- breakout failure: a close back inside the pattern within 3 bars.

Baselines, so that "the pattern works" means more than "the market moved":

- a breakout of an ordinary `n`-bar high or low of the same size, at the same
  interval and volatility, without the pattern geometry;
- random occurrence times matched on interval, symbol and ATR;
- the same pattern with the breakout direction reversed.

Overlap: an occurrence that coincides with a candlestick, harmonic or Elliott signal
in the same direction is reported both inside and outside the overlap, so a chart
pattern does not take credit for another family's edge.

## Phase 3: acceptance criteria (decided now)

A pattern can be proposed for production only if, at its main horizon:

- its direction-adjusted mean and median returns beat both baselines in **both**
  periods, with bootstrap confidence intervals that exclude zero difference;
- the hit rate of the direction is above the matched baseline in both periods;
- results do not depend on a single sector or year (no sector above 30 % of
  occurrences without a per-sector check);
- the effect survives the alternative pivot detector from Phase 1.

A pattern that fails stays a manual drawing tool. Results are published in
`docs/` either way, as for the candlestick validation reports.

## Phase 4: product integration (only for accepted patterns)

If a pattern is accepted, it becomes an alert family alongside candlestick,
harmonic and Elliott signals:

- detection runs in the scheduled checks on completed candles, like the other
  families, with one row per occurrence and the pivots stored for the overlay;
- the stock chart shows confirmed occurrences as an overlay that can be switched on
  in the Buy/Short signals menu, drawn from the stored pivots, never recalculated in
  the browser;
- forming (not yet broken out) patterns, if shown at all, are labelled as
  unconfirmed and never alert;
- watchlist settings, Latest signals, email and learning-mode explanations follow
  the existing families; the signal detail page shows the measured outcome and the
  study's figures for that pattern and interval;
- the score model is not extended until a separate study shows that a chart pattern
  adds information to it.

## Open questions for the study

- Whether volume confirmation (breakout volume relative to the pattern's average)
  belongs in the definition or is studied as a separate filter.
- Whether flags and pennants need a preceding "pole" definition shared with the
  trend-state indicator.
- How to treat gaps through a breakout line on Daily data.
