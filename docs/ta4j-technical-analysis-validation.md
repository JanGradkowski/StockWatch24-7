# TA4J technical-analysis validation

## Boundary

TA4J is used only for deterministic technical calculations and offline
research. Elliott Wave detection, candlestick-pattern detection, StockWatch
scoring, explanations, alerts, and business decisions remain application-owned.

New TA4J trend, momentum, channel, and KDE values are stored in
`TechnicalResearchSnapshot`; production detectors consume `EnrichedCandle` and
cannot access those research values accidentally.

## Version and calculation parity

The project was upgraded from TA4J 0.22.8 to 0.23.0. A deterministic oscillating
OHLCV fixture compares TA4J with independent reference implementations of SMA,
EMA, RSI/Wilder smoothing, MACD, CCI, population Bollinger Bands, ATR, rolling
VWAP, and average volume. Values agree within `0.00001`. A second linear golden
fixture locks exact, human-verifiable values and formula semantics.

This is a validation of TA4J rather than a contest with an older StockWatch
engine: these production indicators were already calculated by TA4J before this
change.

## Trend comparison

The established causal Theil-Sen/ATR regression classifier and TA4J's generic
`UpTrendIndicator`/`DownTrendIndicator` were tested against 200 labeled samples
from deterministic uptrend, downtrend, and sideways regimes. Only bars well
inside each regime were evaluated, and both calculations used data available at
that bar.

| Method | Correct labels | Accuracy |
| --- | ---: | ---: |
| Established StockWatch regression | 200/200 | 100.0% |
| TA4J generic trend indicators | 164/200 | 82.0% |

Conclusion: TA4J trend state is useful additional context, but this evidence
does not support replacing StockWatch's existing trend classifier. The TA4J
state remains research-only.

## Volume-profile comparison

The established rolling binned OHLCV approximation and TA4J's causal KDE mode
were compared over 460 one-candle-forward samples. The metric is the absolute
distance between the current estimated price mode and the next completed close,
normalized by current ATR. This tests price-location usefulness; it is not a
profitability claim.

| Method | Mean forward error |
| --- | ---: |
| Established binned approximation | 1.1177 ATR |
| TA4J KDE mode | 1.3121 ATR |

On this fixture TA4J KDE error was 17.39% higher. The established volume profile
therefore remains the production input. The repeatable comparison service also
requires at least 100 samples and a material improvement before it can even
recommend reviewing the TA4J candidate; it never switches production behavior.

## Walk-forward research

`TechnicalWalkForwardResearchService` uses TA4J 0.23.0 walk-forward execution
for DMI/ADX, Stochastic, Stochastic RSI, MFI, Donchian, Keltner, and generic
trend candidates. It reports out-of-sample folds, net return, maximum drawdown,
position count, and a deterministic configuration hash. It is not registered in
the request, detector, scoring, or alert paths and cannot promote a candidate.

## Candlestick and Elliott performance research

`Ta4jPatternPerformanceResearchService` evaluates signals produced by the
existing StockWatch candlestick and Elliott detectors. It does not contain or
call a TA4J pattern rule. Historical detection is run one completed-candle
prefix at a time, and only a signal stamped with that prefix's final candle is
accepted. Execution then starts at the following candle's open, preventing a
confirmation candle from also being treated as an already-available fill.

The default research run evaluates daily, weekly, monthly, or intraday series
independently at 1, 3, 5, and 10 native-bar holding periods. Reports include
overall results and slices by StockWatch family, exact pattern, BUY/SELL
direction, and setup-score band. Gross directional return is retained for
diagnosis; TA4J calculates net return and maximum drawdown after configurable
transaction costs and adverse entry/exit slippage. Every detected event is
evaluated independently, including overlapping events, so these are signal
quality statistics rather than a capital-constrained portfolio simulation.

Intervals must be supplied as separate native candle series. A weekly holding
period is therefore measured in weekly candles, never resampled daily candles.
Insufficient-tail signals are excluded from longer horizons rather than being
given an artificially shortened holding period.

The repeatable real-data suite uses only candles already cached in the local
database and can be run with:

```powershell
.\mvnw.cmd -Dbacktest.ta4j.patterns.enabled=true -Dtest=Ta4jPatternMultiIntervalRealDataBacktestTest test
```

It reports daily (1/3/5/10 bars), weekly (1/2/4/8 bars), and monthly
(1/2/3/6 bars) results. No market-data sync is performed by the test.

### Initial cached-data run (2026-08-21)

The first run covered AAPL, MSFT, NVDA, AMZN, GOOGL, META, TSLA, JPM, XOM,
and JNJ, with all ten symbols sufficiently populated at each interval. Costs
were 5 bps per transaction side plus 2 bps adverse slippage per side.

| Native interval / horizon | Signals | Win rate | Mean net return |
| --- | ---: | ---: | ---: |
| Daily / 1 bar | 2,267 | 44.42% | -0.198% |
| Daily / 5 bars | 2,264 | 48.54% | -0.230% |
| Weekly / 1 bar | 1,333 | 45.54% | -0.174% |
| Weekly / 4 bars | 1,329 | 47.33% | -0.608% |
| Monthly / 1 bar | 468 | 48.50% | -0.465% |
| Monthly / 3 bars | 466 | 46.14% | -0.471% |

The broad, unfiltered signal pool was negative after friction at every tested
horizon. There were narrower positive observations: setup score >=70 averaged
+0.156% and +0.119% at weekly 1- and 2-bar horizons, and +0.802%, +0.594%, and
+0.203% at monthly 2-, 3-, and 6-bar horizons. Monthly Elliott signals averaged
+0.416% and +0.721% at 2 and 3 bars. These are exploratory slices, not promotion
evidence: they mix symbols and market regimes, have not had a held-out period,
and score >=85 did not improve consistently. No detector, score threshold, or
alert behavior was changed from these results.

## Interpretation

Current evidence supports TA4J as the shared implementation for standard
technical indicators and as a research framework. It does not show that TA4J's
generic trend or KDE volume-profile implementations are better than the current
StockWatch alternatives, so those production implementations are deliberately
unchanged.
