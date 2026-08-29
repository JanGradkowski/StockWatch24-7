# Elliott V1 versus V2 expanded benchmark

Run date: 2026-08-06.

## Production decision

V2 was rolled back after this comparison. `ELLIOTT_V1` remains the production score for manual checks, scheduled alerts, emails, persisted events, and historical reconstruction. V2 is retained only as an explicitly selected offline research model.

## Frozen study design

- 2,193 equities from the pre-outcome power-universe manifest.
- 7,802,979 adjusted daily source candles, aggregated into completed weekly and monthly candles.
- 1,377,167 weekly and 137,418 monthly rolling 100-candle detection windows.
- Production Elliott alert eligibility, actionable Wave V/ABC endings only.
- Weekly outcomes: 4/8/12 candles at 4%/8%/12%; monthly outcomes: 3/6/9 candles at 6%/12%/18%.
- Precision is success / (success + failure); inconclusive outcomes are excluded.

The V1 raw run was completed and archived before V2 was implemented. The V2 run asserted exact identity parity against V1 by interval, ticker, pattern, direction, and signal timestamp.

The harness can reproduce either model with `-Dbacktest.elliott.score-model=V1` or `V2`; run V1 first because the V2 parity assertion reads the archived V1 CSV.

**Parity result: 29,470 / 29,470 signal identities preserved (100.00%).** V2 therefore changed scoring only; it did not change detection, pivot selection, confirmation timing, or alert qualification.

## Production-horizon comparison

These rows compare the same numeric score cutoffs. Because V1 and V2 have different score distributions, signal counts differ and the rows are not equal-coverage samples.

| Interval / stage | Cutoff | V1 signals | V1 precision | V1 avg close return | V2 signals | V2 precision | V2 avg close return |
|---|---:|---:|---:|---:|---:|---:|---:|
| Weekly 4 / 4%, all | 75+ | 27,022 | 48.01% | +0.15% | 21,127 | 47.45% | +0.09% |
| Weekly 4 / 4%, V end | 75+ | 22,792 | 46.80% | +0.04% | 19,584 | 46.92% | +0.03% |
| Weekly 4 / 4%, ABC end | 75+ | 4,230 | 54.62% | +0.73% | 1,543 | 54.39% | +0.82% |
| Monthly 3 / 6%, all | 75+ | 2,448 | 40.66% | -0.97% | 1,831 | 38.50% | -1.79% |
| Monthly 3 / 6%, V end | 75+ | 2,105 | 37.45% | -1.53% | 1,696 | 37.00% | -1.99% |
| Monthly 3 / 6%, ABC end | 75+ | 343 | 59.32% | +2.48% | 135 | 56.52% | +0.76% |

At 85+, weekly V endings improved from 46.13% to 46.79% precision and from -0.12% to +0.20% average close return. Monthly V endings were effectively unchanged in precision (36.88% versus 36.86%) and worse in average return (-1.65% versus -3.07%). Monthly ABC samples become small at high V2 thresholds, so those percentages should not be generalized.

## Equal-coverage top quartile

| Interval / stage | V1 precision | V1 avg return | V2 precision | V2 avg return |
|---|---:|---:|---:|---:|
| Weekly, all | 46.83% | +0.03% | 46.67% | +0.15% |
| Weekly, V end | 46.05% | -0.15% | 46.70% | +0.15% |
| Weekly, ABC end | 57.03% | +1.06% | 53.33% | +0.78% |
| Monthly, all | 40.44% | -1.13% | 38.17% | -2.85% |
| Monthly, V end | 39.87% | -1.16% | 38.05% | -3.03% |
| Monthly, ABC end | 71.19% | +4.41% | 60.00% | +1.47% |

## Interpretation

V2 is materially better as an explanation model: it reports seven bounded, stage-aware evidence families. It modestly improves weekly Wave V ranking in this sample, but it does **not** establish generally better return prediction and it underperforms V1 ranking for ABC endings and monthly short-horizon outcomes. For that reason it is not active in production.

The final V2 refinement used feedback from this same frozen benchmark. Its performance is therefore descriptive/in-sample for that refinement, not independent out-of-sample validation. V1 remains both the displayed production score and the alert eligibility gate.

Raw generated artifacts:

- `target/expanded-backtest-data/expanded-elliott-signals-elliott-v1.csv`
- `target/expanded-backtest-data/expanded-elliott-summary-elliott-v1.md`
- `target/expanded-backtest-data/expanded-elliott-signals-elliott-v2.csv`
- `target/expanded-backtest-data/expanded-elliott-summary-elliott-v2.md`

## Developing-cycle synthetic hierarchy recall

Run date: 2026-08-27.

This is a separate structural-recall test of the developing Wave II-to-V detector. It is not a return, precision, or profitability backtest. The deterministic generator created 1,000 complete parent impulses with 500 bullish and 500 bearish cases. Every parent contained motive five-subwave Waves I, III, and V; Waves II and IV rotated through A-B-C, W-X-Y, W-X-Y-X-Z, and (for Wave IV) contracting triangles. Prices, legal retracements, extensions, and leg durations varied under seed `14749815`.

The pass criterion for an exact recovery was deliberately strict: direction, Wave V completion, non-null completed structure, all five subdivision evidence gates, and exact generated timestamps for 0/I/II/III/IV/V all had to match. Merely finding an unrelated Wave V did not count.

The original single-count implementation produced 100% nested-leg recognition but only 627/1,000 exact parent counts. The 373 misses split into 334 competing endpoints inside paired complex corrections and 39 prematurely completed bearish triangle/Wave V counts.

The rules-first implementation made these changes:

- Each parent pivot retains the sensitivity scales on which it exists. Parent counts are ranked by common-degree coherence rather than freely mixing fine and coarse pivots.
- Every subdivision consumes all pivots between its boundaries at the selected degree. A count can no longer skip same-degree pivots to manufacture a shorter A-B-C or five-wave sequence.
- Up to 24 admissible counts remain attached to one stable 0/I development key. The app stores and updates one primary signal; replacing its primary count does not create another signal or email.
- A developing cycle is invalidated only when every admissible count has failed, except for immediate hard-rule violations such as Wave II crossing the origin or standard-impulse Wave IV overlap.
- Motive diagonals are legal for Waves I and V but not Wave III. Standard impulses retain the three hard rules.
- Corrections distinguish zigzags, flats, double/triple zigzags, double/triple-three combinations, and contracting triangles. Standalone triangles are not admitted as Wave II, while a triangle may terminate a combination.
- Wave II/Wave IV alternation is used as a ranking guideline, never as an invalidation rule.

Post-refactor results:

| Measurement | Original seed `14749815` | Held-out seed `20260827` |
|---|---:|---:|
| All five intended nested leg shapes recognized | 1,000/1,000 | 1,000/1,000 |
| Parent 0/I/V endpoints recovered by the primary count | 1,000/1,000 | 1,000/1,000 |
| Exact intended count retained among admissible hypotheses | 1,000/1,000 | 1,000/1,000 |
| Exact 0/I/II/III/IV/V primary count recovered | 1,000/1,000 | 1,000/1,000 |

All twelve direction/Wave II/Wave IV shape buckets scored 100% in both runs. The second seed was not used while refining the implementation.

This closes the deterministic 373-case structural-recall gap. It does not establish market profitability or prove that a unique Elliott count exists on real price data; the retained-hypothesis design explicitly preserves legitimate ambiguity while a correction unfolds.

Reproduce the strict benchmark in PowerShell with:

```powershell
& .\mvnw.cmd '-Dtest=DevelopingElliottSyntheticRecallBenchmarkTest' '-Delliott.synthetic.benchmark=true' '-Delliott.synthetic.count=1000' test
```

Reproduce the held-out run by adding `'-Delliott.synthetic.seed=20260827'`. The benchmark remains opt-in so ordinary Maven test runs do not absorb a 1,000-case research workload. Its 1,000/1,000 admissible-hypothesis assertion now passes.

## Scope correction after the independent-model experiment

On 2026-08-27 an experimental independent detector reused the developing-cycle subdivision
validator as a replacement gate for completed V1 structures. The frozen study returned only
two weekly signals and no monthly signals. That was the wrong scope: validating a tracked
Wave II-to-V development is not the same operation as replacing the established independent
V1 endpoint detector.

The experimental detector, benchmark switch, and hierarchy dependency have been removed.
`ELLIOTT_V1` remains the unchanged detector for completed Wave V/correction alerts, charts,
historical reconstruction, confluence, and hierarchy baselines. Strict motive/corrective
subdivision validation is confined to the additive developing-cycle tracker:

- Wave I must have a validated five-wave subdivision before a Wave II development begins.
- Wave II and IV must have a valid corrective subdivision, including the supported ABC,
  complex-correction, and legal triangle forms.
- Wave III and V must have validated five-wave subdivisions before their stage advances.
- A tracked development is invalidated when its hard parent rule breaks or when no admissible
  count with the required subdivision remains.

The original frozen V1 output was rerun after this correction and remains 27,022 weekly plus
2,448 monthly endpoint signals. Developing Wave II, III, and IV notifications require their
own lifecycle/outcome study because the endpoint benchmark intentionally does not count them.

## Developing-cycle multi-timeframe market validation

Run date: 2026-08-27.

The additive developing-cycle detector was evaluated separately from frozen V1 on the same
2,193-symbol, 7,802,979-daily-candle source. Completed V1 endpoint detection was not replaced.
The developing study used 1,157,867 weekly as-of windows of 200 parent candles and all 137,418
monthly as-of windows of 100 parent candles. Weekly parent legs were validated on daily child
candles; monthly parent legs were validated on weekly child candles. Child data was cut off at
each historical as-of boundary, so the detector could not inspect future lower-timeframe bars.

The real-market fixes tested here were:

- parent and child timeframes are supplied separately instead of searching for both degrees in
  the same candle series;
- extra smaller pivots may be grouped inside an enclosing child leg only when they remain inside
  that leg's price envelope and the selected sequence still passes every motive/corrective hard
  rule;
- a corrective A-B-C cannot hide a completed five-wave move detected at that same degree;
- leg validation is cached by immutable timestamps during a historical scan; this changes runtime,
  not detection;
- weekly developing counts use a 200-parent-candle context, while monthly retains the viable
  100-candle context;
- the lifecycle creates one app row only from a confirmed Wave II. Later III/IV/V observations
  update that row and send a stage notification; a cycle first discovered after Wave II is not
  back-created by this benchmark.

The 1,000-case nested synthetic benchmark remained 1,000/1,000 after these changes, and all 43
focused Elliott detector regressions passed.

### Signal volume

| Interval | App signal rows created at Wave II | Wave II notifications | Wave III updates | Wave IV updates | Wave V updates | Total notifications |
|---|---:|---:|---:|---:|---:|---:|
| Weekly | 103,939 | 103,939 | 57,248 | 44,678 | 33,591 | 239,456 |
| Monthly | 11,875 | 11,875 | 4,674 | 2,904 | 1,312 | 20,765 |
| Total | 115,814 | 115,814 | 61,922 | 47,582 | 34,903 | 260,221 |

This removes the earlier wrong-scope two-signal outcome. It does not mean 260,221 separate cards:
there are 115,814 rows, and 144,407 later-stage notifications update those rows. For scale only,
frozen V1 contains 29,470 completed endpoint signals; the developing study measures different,
earlier events and is not an identity-parity comparison with V1.

### Production horizons at 75+ confidence

Precision remains success / (success + failure), excluding inconclusive outcomes. Return is the
direction-adjusted close return for the move forecast immediately after that stage. These are
notification outcomes, not a portfolio backtest with stop execution, costs, slippage, or sizing.

| Interval / forecast after stage | Notifications | Precision | Average close return |
|---|---:|---:|---:|
| Weekly 4 candles / 4%, after II (forecast III) | 60,409 | 51.06% | +0.15% |
| Weekly 4 candles / 4%, after III (forecast IV) | 35,697 | 45.37% | -0.63% |
| Weekly 4 candles / 4%, after IV (forecast V) | 31,991 | 53.93% | +0.29% |
| Weekly 4 candles / 4%, after V (forecast correction) | 22,634 | 46.19% | +0.03% |
| Monthly 3 candles / 6%, after II (forecast III) | 7,548 | 56.78% | +1.06% |
| Monthly 3 candles / 6%, after III (forecast IV) | 3,514 | 32.00% | -3.76% |
| Monthly 3 candles / 6%, after IV (forecast V) | 2,452 | 61.62% | +1.87% |
| Monthly 3 candles / 6%, after V (forecast correction) | 1,099 | 29.70% | -3.60% |

At the longer 75+ horizons, weekly Wave II reaches 52.70% precision and +0.60% average return at
8 weeks, while monthly Wave II reaches 60.12% and +3.67% at 9 months. Monthly Wave IV reaches
72.77% and +7.83% at 9 months. Conversely, the countertrend forecasts after Waves III and V are
consistently weak, especially monthly. Raising the confidence cutoff does not reliably repair
those stages, so subdivision confidence should be treated as structural confidence, not a
calibrated probability of return.

### Decision

The detector now finds abundant real developing structures, so scarcity was primarily a degree
and exact-pivot parsing problem. The market results support Wave II and Wave IV notifications as
the more useful directional forecasts. Wave III and Wave V notifications should remain useful
structural progress/completion notices, but their projected countertrend move must not be described
as high-precision. Frozen V1 remains the independent completed-endpoint detector.

The scheduled application path now supplies the next-lower cached/synchronized timeframe to the
developing detector (daily to weekly, weekly to monthly, and hourly to daily when sufficient hourly
coverage exists). Weekly development may use up to 200 already-fetched parent bars; the V1 endpoint
path still receives its original latest 100. If the provider cannot supply enough child coverage,
the detector retains its same-series fallback rather than using partial child history as though it
covered the complete parent boundary.

Hourly candles are valid Elliott-enrichment inputs only for Daily child-wave validation; hourly
top-level alert rules remain disabled. Lower-degree series are persisted in the shared candle cache.
The first use bootstraps up to 1,000 child bars, subsequent runs request only a small latest-bar delta
subject to the normal provider cooldown, and cached child-enrichment failures fall back without
aborting the parent symbol's other alert families.

Generated artifacts:

- `target/expanded-backtest-data/expanded-elliott-developing-signals-full.csv`
- `target/expanded-backtest-data/expanded-elliott-developing-summary-full.md`

Reproduce the opt-in full run in PowerShell with:

```powershell
& .\mvnw.cmd '-Dbacktest.elliott.expanded.enabled=true' `
  '-Dbacktest.elliott.developing.enabled=true' `
  '-Dbacktest.elliott.developing.window=200' `
  '-Dtest=ExpandedElliottScoringValidationTest#runsDevelopingMultiTimeframeValidation' test
```

## Connected post-impulse correction lifecycle

Implementation update: 2026-08-28.

The additive developing-cycle tracker now continues beyond a validated Wave V. The original
Wave-II card remains the single persisted record and progresses through III, IV, V, and a
validated post-impulse correction ending. Wave V no longer closes that record. The correction
must share the same I-V cycle key and can complete only as:

- zigzag 5-3-5: motive A, corrective B, motive C;
- flat 3-3-5: corrective A, corrective B, motive C, with B recovering at least 90% of A;
- either form may contain a legal contracting 3-3-3-3-3 triangle in Wave B, after which
  motive Wave C remains mandatory. Complex corrective B structures remain supported.

A standalone triangle cannot become Wave II and cannot replace the complete correction after
Wave V. At correction completion the same Latest Signals / All Signals card receives the new
pattern, direction, structure evidence, Wave-C stop, Wave-V target, transition history, and
notification. Completed endpoint scanning deduplicates by user/symbol/interval/cycle/stage across
opposite Buy/Sell rules, preventing a second card for the same Wave V or correction endpoint.

Focused regression tests cover connected zigzag, flat, triangular-B-plus-motive-C, same-card
Wave-V-to-correction progression, lifecycle initialization, notification wording, and cross-rule
deduplication. This implementation update has not rerun the historical precision/return study;
the performance figures above remain the prior frozen results and must not be read as new ABC
performance measurements.

## Additive Fibonacci possible-trade layer

Implementation and verification update: 2026-08-28.

`ELLIOTT_FIB_RR_V2` now enriches already-detected Wave II, III, IV, V, and connected
correction-ending stages with a possible-trade entry, ATR-buffered structural stop,
Fibonacci target zone, conservative completed-close trigger, and actual/required R:R.
Daily requires 1:2; weekly, monthly, and every other supported interval require 1:3.
Targets use a ±1.5% zone. A valid projection below the interval requirement is persisted
and displayed as `PROJECTION_ONLY`, never as an actionable trade.

This is intentionally downstream of detection. No pivot rule, subdivision grammar,
candidate count, confidence calculation, V1 eligibility rule, or signal-emission threshold
was changed for this feature. Each stage revision is stored separately while the original
AlertEvent continues to be the single Latest Signals / All Signals record. The signal-detail
page and emails show the current plan, target basis, zone, R:R qualification, and historical
stage revisions. An ordinary buffered stop closes only the possible trade; an existing hard
Elliott invalidation closes the count and continues through the pre-existing invalidation path.

Verification completed after implementation:

- full Maven test suite: passed;
- Flyway V51 applied and the Spring application context loaded with 25 repositories;
- deterministic 1,000-case nested-wave benchmark: 1,000/1,000 intended subwave sets,
  parent endpoints, retained hypotheses, and exact recoveries.

These checks establish implementation and detector parity. They do not establish that the
new trade plans are profitable; a separate frozen walk-forward execution study with costs,
slippage, and position sizing is still required for that claim.
