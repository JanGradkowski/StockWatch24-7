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
