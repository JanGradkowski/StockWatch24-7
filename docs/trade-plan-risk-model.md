# Qualified trade plans

New candlestick, Elliott and harmonic plans preserve structural levels and explicitly distinguish a usable trade plan from a pattern whose entry risk is unsuitable. The pattern remains visible when its trade plan is projection only. Qualification is a rule check, not an estimated probability of success.

## Versioning and stored history

| Family | New version | Previous records |
|---|---|---|
| Candlesticks | `CANDLE_RR_V5` | V4 and earlier keep their signal-close entry and original resolution |
| Elliott | `ELLIOTT_FIB_RR_V4` | V3 and earlier keep their signal-close entry and original resolution |
| Harmonics | `HARMONIC_TRADE_V3` | V2 keeps its single-target model; `HARMONIC_STOP_V1` retains its stop-only, eight-bar model |
| Elliott scenarios | `ELLIOTT_SCENARIOS_V2` | New scenarios enforce the same standard-impulse boundaries as plans |

Migration V81 adds the fill status (`AWAITING_FILL`, `FILLED`, `NOT_FILLED`) and entry timestamp used by the current versions. Migration V68 adds qualification, risk, horizon, secondary-target and modeled-fill fields. It does not rewrite historical prices or results. New stage revisions receive new snapshots. Retired Elliott revisions remain in stage history. Historical candlestick scans are recomputed under the new policy with a separate cache key; they are not persisted legacy alert outcomes.

## Entry at the next open

A signal is known only once its candle has closed, so no one could have bought or sold at that close. Current plans therefore enter at the **open of the next candle**. The plan is calculated at detection and waits as `AWAITING_FILL` until that candle exists:

- If the next open is still on the right side of the stop and short of the target, the plan is `FILLED` at that open, and the entry candle itself is checked for a stop or target touch.
- If the market gaps through the stop or beyond the target, the plan is `NOT_FILLED`: the trade never happened and has no result. Candlestick alerts send a short "not entered" email.
- Results, the archive and the virtual-trade helpers measure from the modeled fill, not from the signal close.

Historical scans and the historical Elliott and harmonic pages apply the same rule.

## Stops, targets and eligibility

Candlestick stops are exactly the configured stop: the structural price moved by the configured buffer percentage, or the entry moved by the configured fixed percentage. No further ATR buffer is added, so the factory "textbook structural price (0% buffer)" setting places the stop at the pattern's own high or low. The previous ATR circuit breaker no longer tightens new stops to manufacture a better reward/risk ratio. The ATR risk ceiling below still applies.

The configured candlestick reward/risk target is capped by nearer confirmed support/resistance: a two-bar pivot on either side, confirmed by the entry timestamp, within 60 bars. A nearer obstacle can therefore make the setup projection only. This is intentionally conservative; the pivot is not assumed to guarantee a reversal.

Elliott targets enforce standard impulse constraints: Wave III must pass Wave I; projected Wave IV cannot overlap Wave I; projected Wave V cannot make Wave III the shortest motive wave. Detector origins labeled either `0` or an empty string are accepted. The nearest eligible objective is primary. A more distant objective is a separate scenario and cannot rescue an inadequate primary reward/risk ratio. Diagonal counts need a separate explicit policy and do not silently bypass these checks. Analytical count invalidation stays separate from the new protective-order trade outcome.

Weekly and monthly Elliott objectives are projected on a logarithmic price scale, as is conventional for long-horizon wave counts; daily objectives stay arithmetic.

Harmonic plans retain their pattern-specific structural boundaries. Gartley, Bat and Cypher use X; Butterfly uses the 1.414 XA extension; Crab uses 2.0 XA; Shark uses the 1.27 XA extension. Conventional XABCD plans show 38.2%/61.8% AD objectives; Cypher uses CD; Shark uses a 50% CD reaction objective and a shorter horizon. These are modeled objectives, not promises of a price move.

Harmonic trades scale out in the traditional way: half the position closes at the first objective, the stop on the remainder moves to the entry, and the rest closes at the second objective, the breakeven stop or the horizon. When the first objective and a return to the entry fall in the same bar, the order is unknown and the remainder is treated as stopped at breakeven. The plan qualifies on the reward/risk of the blended objective (the midpoint of the two), with a minimum of 1.5 daily and 2.0 weekly/monthly.

All families use causal Wilder ATR, normally 14 bars (candlestick ATR period remains configurable). Missing/invalid ATR makes the plan projection only. Stops, targets and entry must be finite and positive, with a stop on the adverse side. Stop rounding is adverse to the entry, using a decimal precision floor of 0.01 above one currency unit and 0.0001 below it. This is not an exchange-specific tick-size database; these informational plans do not submit broker orders.

## Provisional interval defaults

These safeguards are engineering defaults, not parameters proven optimal by backtesting. Both the ATR and percentage risk ceilings must pass.

| Interval | Maximum stop risk, ATR | Maximum stop risk, % of entry | ATR buffer | Candlestick horizon | Harmonic horizon | Shark horizon | Elliott horizon |
|---|---:|---:|---:|---:|---:|---:|---:|
| Daily | 3 | 12.5% | 0.20 | 8 | 12 | 5 | 20 |
| Weekly | 4 | 20% | 0.20 | 8 | 10 | 4 | 16 |
| Monthly | 5 | 30% | 0.25 | 6 | 8 | 3 | 12 |

Horizons count completed bars from the entry bar. Elliott minimum primary reward/risk is 2 daily and 3 weekly/monthly; harmonic plans use the blended minimum above. A user's own reward/risk preference replaces these minimums only when it differs from the factory value. Candlesticks use the user's interval preference. Elliott zone half-width is the lesser of 1.5% of target and the greater of 0.25 ATR or the price precision floor. Missing ATR permits an explanatory projection, not a qualified trade.

## Modeled outcomes

New plans use completed-bar OHLC to detect protective stop and target touches. An opening gap through a stop exits at the opening price. An opening gap beyond a target exits conservatively at the target price. When both levels are touched after an opening price between them and intrabar order is unknown, the stop wins. At the saved horizon, an unresolved trade exits at the close.

For weekly/monthly bars, cached daily bars can resolve ordering only when their aggregate open, high, low, close and positive volume reconcile to the parent. Otherwise the conservative parent-bar rule applies. No additional provider request is made. This does not reconstruct tick-level execution or guarantee liquidity at a stop. Stored modeled fills are separate from observed closing prices and drive archive returns and sorting. Live displayed results are before costs; the offline diagnostic below applies explicit costs.

## Validation and reproduction

Deterministic tests cover mirrored buy/sell and daily/weekly/monthly calculations, invalid Elliott objectives, wide structural stops, missing volatility, non-positive prices, causal ATR/pivot inputs, gap fills, ambiguous bars, harmonic targets, horizons and legacy compatibility. Integration tests apply V68 and render new signal details in the dedicated `watchlist_permissions_test` schema.

`TradePlanHistoricalValidationTest` is opt-in. It replays saved daily data, aggregates weekly/monthly bars without including the final potentially partial aggregate, recalculates eligibility at the next bar's open, and applies 10 basis points cost per side. It compares frozen pre-change formulas with new formulas under the same protective fill assumptions. The baseline harmonic model has no target. Both Elliott formula variants use the new horizon for a bounded comparison, so this is not an exact reproduction of all former live behavior.

Coverage is sampled multi-candle candlesticks without the separate confirmation gate, completed Elliott impulses/corrections, and harmonic formations. Developing Elliott stages and candidate-gated candles have deterministic regression coverage, but are not included in this statistical replay. The frozen Elliott baseline rejects detector origins labeled with an empty string; its zero accepted trades on affected samples is a compatibility defect, not evidence of better market prediction.

Run from PowerShell:

```powershell
.\mvnw.cmd '-Dtest=TradePlanHistoricalValidationTest' '-Dtrade.validation=true' test
```

The default dataset is `target/expanded-backtest-data/post-2018-candles.csv.gz`; override with `-Dtrade.validation.data=...` and increase `-Dtrade.validation.symbols=...` for broader coverage. The report is written to `target/trade-validation/report.md`. Data files are local research inputs, not committed dependencies. With the opt-in flag absent this test is skipped.

The diagnostic separates dates before 2019 from 2019 onward. Cached samples were used in prior research, so the later slice is not a pristine holdout. Alphabetical selection, overlapping trades, incomplete histories and survivor bias limit inference. Sequential equal-risk drawdown is not portfolio drawdown. No parameter tuning or claim of improved predictive accuracy is justified by this small run. A point-in-time universe, untreated holdout and sensitivity analysis remain necessary before optimizing these defaults.


The checked-in [17 September 2026 diagnostic](trade-plan-validation-2026-09-17.md) covers eight cached symbols. Later-period mean net R for candlesticks changed from -0.533 to -0.361 daily, +0.319 to +0.343 weekly, and +0.217 to -0.401 monthly (only three completed refined monthly trades). Almost all harmonic proposals were filtered out, leaving one daily and one weekly completed trade. This is mixed, sparse evidence; improved predictive accuracy and an optimal acceptance rate have not been established.
