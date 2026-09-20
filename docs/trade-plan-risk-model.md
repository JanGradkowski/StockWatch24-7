# Qualified trade plans

New candlestick, Elliott and harmonic plans preserve structural levels and explicitly distinguish a usable trade plan from a pattern whose entry risk is unsuitable. The pattern remains visible when its trade plan is projection only. Qualification is a rule check, not an estimated probability of success.

## Versioning and stored history

| Family | New version | Previous records |
|---|---|---|
| Candlesticks | `CANDLE_RR_V4` | V1–V3 retain their existing close-based resolution |
| Elliott | `ELLIOTT_FIB_RR_V3` | Previous stage plans retain their original resolution logic |
| Harmonics | `HARMONIC_TRADE_V2` | `HARMONIC_STOP_V1` retains its stop-only, eight-bar model |
| Elliott scenarios | `ELLIOTT_SCENARIOS_V2` | New scenarios enforce the same standard-impulse boundaries as plans |

Migration V68 adds qualification, risk, horizon, secondary-target and modeled-fill fields. It does not rewrite historical prices or results. New stage revisions receive new snapshots. Retired Elliott revisions remain in stage history. Historical candlestick scans are recomputed under the new policy with a separate cache key; they are not persisted legacy alert outcomes.

## Stops, targets and eligibility

Candlestick stops use the more adverse of the configured stop and the formation's structural stop, plus a volatility buffer. A fixed percentage can widen the stop but cannot move it inside the structure. The previous ATR circuit breaker no longer tightens new stops to manufacture a better reward/risk ratio. Existing preference fields remain readable for historical compatibility; the settings panel exposes the ATR lookback instead.

The configured candlestick reward/risk target is capped by nearer confirmed support/resistance: a two-bar pivot on either side, confirmed by the entry timestamp, within 60 bars. A nearer obstacle can therefore make the setup projection only. This is intentionally conservative; the pivot is not assumed to guarantee a reversal.

Elliott targets enforce standard impulse constraints: Wave III must pass Wave I; projected Wave IV cannot overlap Wave I; projected Wave V cannot make Wave III the shortest motive wave. Detector origins labeled either `0` or an empty string are accepted. The nearest eligible objective is primary. A more distant objective is a separate scenario and cannot rescue an inadequate primary reward/risk ratio. Diagonal counts need a separate explicit policy and do not silently bypass these checks. Analytical count invalidation stays separate from the new protective-order trade outcome.

Harmonic plans retain their pattern-specific structural boundaries. Gartley, Bat and Cypher use X; Butterfly uses the 1.414 XA extension; Crab uses 2.0 XA; Shark uses the 1.27 OX extension. Conventional XABCD plans show 38.2%/61.8% AD objectives; Cypher uses CD; Shark uses a 50% BC reaction objective and a shorter horizon. These are modeled objectives, not promises of a price move.

All families use causal Wilder ATR, normally 14 bars (candlestick ATR period remains configurable). Missing/invalid ATR makes the plan projection only. Stops, targets and entry must be finite and positive, with a stop on the adverse side. Stop rounding is adverse to the entry, using a decimal precision floor of 0.01 above one currency unit and 0.0001 below it. This is not an exchange-specific tick-size database; these informational plans do not submit broker orders.

## Provisional interval defaults

These safeguards are engineering defaults, not parameters proven optimal by backtesting. Both the ATR and percentage risk ceilings must pass.

| Interval | Maximum stop risk, ATR | Maximum stop risk, % of entry | ATR buffer | Candlestick horizon | Harmonic horizon | Shark horizon | Elliott horizon |
|---|---:|---:|---:|---:|---:|---:|---:|
| Daily | 3 | 12.5% | 0.20 | 8 | 12 | 5 | 20 |
| Weekly | 4 | 20% | 0.20 | 8 | 10 | 4 | 16 |
| Monthly | 5 | 30% | 0.25 | 6 | 8 | 3 | 12 |

Horizons count completed bars after the reference entry bar. Elliott/harmonic minimum primary reward/risk is 2 daily and 3 weekly/monthly; candlesticks retain the user's interval preference. Elliott zone half-width is the lesser of 1.5% of target and the greater of 0.25 ATR or the price precision floor. Missing ATR permits an explanatory projection, not a qualified trade.

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
