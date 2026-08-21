# Elliott Wave I shortest-rule validation

Run date: 2026-08-15.

## Study design

- Frozen pre-outcome universe: **2,193 equities** and **7,802,979 adjusted daily candles**.
- Both variants use V1 production scoring, completed weekly/monthly candles, rolling 100-candle windows, identical pivot rules, and the production 75-point alert gate.
- Current model: Wave I may be the longest actionary wave, while Wave III still cannot be the shortest.
- Candidate: Wave I must be no longer than both Waves III and V. This is the only changed rule.
- Precision is success / (success + failure); inconclusive outcomes are excluded. Average return includes every retained signal and is direction-adjusted, so positive is favorable for BUY and SELL.

## Signal identity impact

| Model / transition | Unique signals | Tickers |
|---|---:|---:|
| Current model | 29,470 | 2,156 |
| Wave I must be shortest | 14,604 | 2,004 |
| Retained in both | 14,602 | 2,004 |
| Removed by candidate | 14,868 | 2,045 |
| Candidate-only after count reselection | 2 | 1 |

## Precision and return comparison

| Interval | Horizon / move | Stage | Current N | Candidate N | N change | Current precision | Candidate precision | Precision change | Current avg return | Candidate avg return | Return change |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Weekly | 4 candles / 4% | All | 27,022 | 13,260 | -13,762 (-50.93%) | 48.01% | 47.47% | -0.53 pp | +0.15% | -0.24% | -0.39 pp |
| Weekly | 4 candles / 4% | Wave V end | 22,792 | 11,063 | -11,729 (-51.46%) | 46.80% | 46.03% | -0.77 pp | +0.04% | -0.46% | -0.50 pp |
| Weekly | 4 candles / 4% | ABC end | 4,230 | 2,197 | -2,033 (-48.06%) | 54.62% | 54.97% | +0.35 pp | +0.73% | +0.85% | +0.11 pp |
| Weekly | 8 candles / 8% | All | 27,022 | 13,260 | -13,762 (-50.93%) | 46.33% | 45.52% | -0.81 pp | -0.04% | -0.75% | -0.71 pp |
| Weekly | 8 candles / 8% | Wave V end | 22,792 | 11,063 | -11,729 (-51.46%) | 44.56% | 43.47% | -1.09 pp | -0.27% | -1.14% | -0.87 pp |
| Weekly | 8 candles / 8% | ABC end | 4,230 | 2,197 | -2,033 (-48.06%) | 56.31% | 56.27% | -0.04 pp | +1.23% | +1.25% | +0.02 pp |
| Weekly | 12 candles / 12% | All | 27,022 | 13,260 | -13,762 (-50.93%) | 44.65% | 43.35% | -1.30 pp | -0.10% | -1.47% | -1.37 pp |
| Weekly | 12 candles / 12% | Wave V end | 22,792 | 11,063 | -11,729 (-51.46%) | 42.59% | 41.32% | -1.27 pp | -0.42% | -2.01% | -1.59 pp |
| Weekly | 12 candles / 12% | ABC end | 4,230 | 2,197 | -2,033 (-48.06%) | 56.39% | 54.23% | -2.16 pp | +1.63% | +1.25% | -0.38 pp |
| Monthly | 3 candles / 6% | All | 2,448 | 1,344 | -1,104 (-45.10%) | 40.66% | 40.72% | +0.07 pp | -0.97% | -1.82% | -0.85 pp |
| Monthly | 3 candles / 6% | Wave V end | 2,105 | 1,132 | -973 (-46.22%) | 37.45% | 37.45% | -0.01 pp | -1.53% | -2.44% | -0.91 pp |
| Monthly | 3 candles / 6% | ABC end | 343 | 212 | -131 (-38.19%) | 59.32% | 55.92% | -3.40 pp | +2.48% | +1.50% | -0.97 pp |
| Monthly | 6 candles / 12% | All | 2,448 | 1,344 | -1,104 (-45.10%) | 40.93% | 38.50% | -2.42 pp | -1.39% | -2.73% | -1.33 pp |
| Monthly | 6 candles / 12% | Wave V end | 2,105 | 1,132 | -973 (-46.22%) | 37.36% | 33.56% | -3.81 pp | -2.46% | -4.37% | -1.90 pp |
| Monthly | 6 candles / 12% | ABC end | 343 | 212 | -131 (-38.19%) | 63.64% | 64.60% | +0.97 pp | +5.17% | +6.04% | +0.87 pp |
| Monthly | 9 candles / 18% | All | 2,448 | 1,344 | -1,104 (-45.10%) | 43.04% | 40.69% | -2.35 pp | -0.07% | -3.56% | -3.49 pp |
| Monthly | 9 candles / 18% | Wave V end | 2,105 | 1,132 | -973 (-46.22%) | 38.87% | 33.85% | -5.02 pp | -1.51% | -6.27% | -4.75 pp |
| Monthly | 9 candles / 18% | ABC end | 343 | 212 | -131 (-38.19%) | 67.46% | 70.94% | +3.48 pp | +8.79% | +10.91% | +2.13 pp |

## Primary-horizon direction split

| Interval | Direction | Current N | Candidate N | N change | Current precision | Candidate precision | Precision change | Current avg return | Candidate avg return | Return change |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Weekly | BUY | 9,489 | 3,855 | -5,634 | 56.40% | 56.88% | +0.48 pp | +2.36% | +1.96% | -0.40 pp |
| Weekly | SELL | 17,533 | 9,405 | -8,128 | 42.59% | 43.06% | +0.47 pp | -1.05% | -1.15% | -0.09 pp |
| Monthly | BUY | 662 | 256 | -406 | 58.52% | 57.29% | -1.23 pp | +5.84% | +3.05% | -2.78 pp |
| Monthly | SELL | 1,786 | 1,088 | -698 | 32.64% | 35.94% | +3.30 pp | -3.49% | -2.97% | +0.53 pp |

## Interpretation

At the primary production horizons, requiring Wave I as the shortest changed weekly signal volume by -50.93%, precision by -0.53 percentage points, and average return by -0.39 percentage points. Monthly signal volume changed by -45.10%, precision by +0.07 percentage points, and average return by -0.85 percentage points. These are descriptive full-universe results on the frozen dataset. Recommendation: retain the current factory rule; the small precision changes do not compensate for lower returns and fewer signals.
