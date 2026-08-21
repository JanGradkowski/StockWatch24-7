# Candlestick SELL refinement validation

Run date: 2026-08-16. The now-rejected SELL-refinement experiment was replayed over **2,227 usable stocks** and **4,923,993 adjusted daily candles**, with signals evaluated from 2019-01-01 through 2025-12-31. The experiment was fully rolled back after this result.

## What this test answers

This report documents why the attempted V5 SELL-context score, ATR-buffered confirmation, relative-weakness context, and post-breakdown stages were rejected. The frozen experiment remains reproducible only in the opt-in research harness; none of those rules are part of production detection, scoring, history, or notifications.

The comparison is causal. A stage is entered at the close that proves that stage, never at the earlier pattern close. In the rejected experiment, one-candle patterns first had to pass the mandatory next-candle red-body/lower-close gate. Multi-candle setups then had 3 completed candles, and gated one-candle setups 10 completed candles, to close below `pattern low - 0.25 ATR`; a close above the pattern high invalidated first. Confirmed breakdowns used a 3-candle retest window, 0.10 ATR retest tolerance, and 3-candle continuation window.

- Manifest SHA-256: `4228946F6535DBFAE60FCB901E95446868D6888CE63C13BEC48F16CB0D429A8D`
- Candle file SHA-256: `291F3A8C514D840E3EB3756A4905415AA0FC300DE335A077ACDD25354E84688F`
- Daily outcome: 10 bars, success at a 3% fall and failure at a 3% rise.
- Weekly outcome: 8 bars, success at an 8% fall and failure at an 8% rise.
- Monthly outcome: 6 bars, success at a 12% fall and failure at a 12% rise.
- Positive average return means price fell after that stage's causal entry close.
- Precision is success / (success + failure); moves inside the symmetric threshold remain inconclusive but stay in N and average return.
- The 2019-2021 and 2022-2025 cohorts are shown separately as a stability check. They reuse previously studied outcomes and are not a fresh untouched holdout.

## Daily

### 2019-2025 combined

| Causal entry stage | N | Share of detected | Actionable | S | F | I | Precision | Delta vs detected | Wilson LCB | Avg return | Delta vs detected | Tickers |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Detected setup | 72,941 | 100.00% | 45,212 | 21,283 | 23,929 | 27,729 | 47.07% | +0.00 pp | 46.61% | -0.61% | +0.00% | 2,222 |
| Buffered breakdown confirmed | 32,700 | 44.83% | 20,843 | 9,492 | 11,351 | 11,857 | 45.54% | -1.53 pp | 44.87% | -0.91% | -0.29% | 2,217 |
| Retest reached | 13,155 | 18.04% | 8,344 | 3,799 | 4,545 | 4,811 | 45.53% | -1.54 pp | 44.46% | -0.82% | -0.21% | 2,202 |
| Failed-retest continuation confirmed | 4,784 | 6.56% | 3,052 | 1,433 | 1,619 | 1,732 | 46.95% | -0.12 pp | 45.19% | -0.66% | -0.04% | 1,905 |
| Breakdown held without retest | 12,733 | 17.46% | 8,224 | 3,848 | 4,376 | 4,509 | 46.79% | -0.28 pp | 45.71% | -0.53% | +0.09% | 2,201 |
| Broken level reclaimed | 12,914 | 17.70% | 8,014 | 3,623 | 4,391 | 4,900 | 45.21% | -1.87 pp | 44.12% | -0.93% | -0.32% | 2,197 |
| Retest unresolved | 2,242 | 3.07% | 1,367 | 658 | 709 | 875 | 48.13% | +1.06 pp | 45.49% | -0.33% | +0.29% | 1,364 |

Primary setup resolution: confirmed 32,736 (44.88%); invalidated 20,381 (27.94%); expired 19,824 (27.18%); censored at data boundary 0 (0.00%).

### 2019-2021

| Causal entry stage | N | Share of detected | Actionable | S | F | I | Precision | Delta vs detected | Wilson LCB | Avg return | Delta vs detected | Tickers |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Detected setup | 33,248 | 100.00% | 20,350 | 9,113 | 11,237 | 12,898 | 44.78% | +0.00 pp | 44.10% | -0.97% | +0.00% | 2,183 |
| Buffered breakdown confirmed | 14,459 | 43.49% | 9,273 | 3,869 | 5,404 | 5,186 | 41.72% | -3.06 pp | 40.72% | -1.44% | -0.46% | 2,174 |
| Retest reached | 6,142 | 18.47% | 3,863 | 1,623 | 2,240 | 2,279 | 42.01% | -2.77 pp | 40.47% | -1.28% | -0.31% | 2,037 |
| Failed-retest continuation confirmed | 2,111 | 6.35% | 1,356 | 588 | 768 | 755 | 43.36% | -1.42 pp | 40.75% | -1.01% | -0.03% | 1,302 |
| Breakdown held without retest | 5,191 | 15.61% | 3,383 | 1,584 | 1,799 | 1,808 | 46.82% | +2.04 pp | 45.15% | -0.32% | +0.66% | 1,951 |
| Broken level reclaimed | 6,106 | 18.37% | 3,722 | 1,511 | 2,211 | 2,384 | 40.60% | -4.18 pp | 39.03% | -1.50% | -0.53% | 2,014 |
| Retest unresolved | 1,051 | 3.16% | 620 | 285 | 335 | 431 | 45.97% | +1.19 pp | 42.08% | -0.54% | +0.44% | 819 |

Primary setup resolution: confirmed 14,459 (43.49%); invalidated 9,451 (28.43%); expired 9,338 (28.09%); censored at data boundary 0 (0.00%).

### 2022-2025

| Causal entry stage | N | Share of detected | Actionable | S | F | I | Precision | Delta vs detected | Wilson LCB | Avg return | Delta vs detected | Tickers |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Detected setup | 39,693 | 100.00% | 24,862 | 12,170 | 12,692 | 14,831 | 48.95% | +0.00 pp | 48.33% | -0.31% | +0.00% | 2,222 |
| Buffered breakdown confirmed | 18,241 | 45.96% | 11,570 | 5,623 | 5,947 | 6,671 | 48.60% | -0.35 pp | 47.69% | -0.48% | -0.17% | 2,215 |
| Retest reached | 7,013 | 17.67% | 4,481 | 2,176 | 2,305 | 2,532 | 48.56% | -0.39 pp | 47.10% | -0.42% | -0.10% | 2,108 |
| Failed-retest continuation confirmed | 2,673 | 6.73% | 1,696 | 845 | 851 | 977 | 49.82% | +0.87 pp | 47.45% | -0.38% | -0.07% | 1,525 |
| Breakdown held without retest | 7,542 | 19.00% | 4,841 | 2,264 | 2,577 | 2,701 | 46.77% | -2.18 pp | 45.36% | -0.67% | -0.36% | 2,125 |
| Broken level reclaimed | 6,808 | 17.15% | 4,292 | 2,112 | 2,180 | 2,516 | 49.21% | +0.26 pp | 47.71% | -0.41% | -0.10% | 2,097 |
| Retest unresolved | 1,191 | 3.00% | 747 | 373 | 374 | 444 | 49.93% | +0.98 pp | 46.36% | -0.14% | +0.17% | 882 |

Primary setup resolution: confirmed 18,277 (46.05%); invalidated 10,930 (27.54%); expired 10,486 (26.42%); censored at data boundary 0 (0.00%).

## Weekly

### 2019-2025 combined

| Causal entry stage | N | Share of detected | Actionable | S | F | I | Precision | Delta vs detected | Wilson LCB | Avg return | Delta vs detected | Tickers |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Detected setup | 20,967 | 100.00% | 10,576 | 4,550 | 6,026 | 10,391 | 43.02% | +0.00 pp | 42.08% | -2.01% | +0.00% | 2,209 |
| Buffered breakdown confirmed | 8,538 | 40.72% | 4,480 | 1,857 | 2,623 | 4,058 | 41.45% | -1.57 pp | 40.02% | -2.32% | -0.31% | 2,149 |
| Retest reached | 3,512 | 16.75% | 1,889 | 796 | 1,093 | 1,623 | 42.14% | -0.88 pp | 39.93% | -2.35% | -0.34% | 1,731 |
| Failed-retest continuation confirmed | 1,287 | 6.14% | 700 | 284 | 416 | 587 | 40.57% | -2.45 pp | 36.99% | -2.43% | -0.42% | 948 |
| Breakdown held without retest | 3,209 | 15.31% | 1,795 | 699 | 1,096 | 1,414 | 38.94% | -4.08 pp | 36.71% | -3.72% | -1.71% | 1,668 |
| Broken level reclaimed | 3,395 | 16.19% | 1,802 | 823 | 979 | 1,593 | 45.67% | +2.65 pp | 43.38% | -1.19% | +0.82% | 1,666 |
| Retest unresolved | 503 | 2.40% | 262 | 126 | 136 | 241 | 48.09% | +5.07 pp | 42.11% | -1.77% | +0.24% | 428 |

Primary setup resolution: confirmed 8,573 (40.89%); invalidated 6,214 (29.64%); expired 6,179 (29.47%); censored at data boundary 1 (0.00%).

### 2019-2021

| Causal entry stage | N | Share of detected | Actionable | S | F | I | Precision | Delta vs detected | Wilson LCB | Avg return | Delta vs detected | Tickers |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Detected setup | 10,157 | 100.00% | 5,146 | 1,921 | 3,225 | 5,011 | 37.33% | +0.00 pp | 36.02% | -3.26% | +0.00% | 2,146 |
| Buffered breakdown confirmed | 3,642 | 35.86% | 1,969 | 801 | 1,168 | 1,673 | 40.68% | +3.35 pp | 38.53% | -2.62% | +0.64% | 1,752 |
| Retest reached | 1,560 | 15.36% | 833 | 334 | 499 | 727 | 40.10% | +2.77 pp | 36.82% | -2.90% | +0.37% | 1,121 |
| Failed-retest continuation confirmed | 548 | 5.40% | 310 | 137 | 173 | 238 | 44.19% | +6.86 pp | 38.77% | -0.87% | +2.39% | 479 |
| Breakdown held without retest | 1,339 | 13.18% | 806 | 277 | 529 | 533 | 34.37% | -2.96 pp | 31.17% | -5.63% | -2.36% | 964 |
| Broken level reclaimed | 1,502 | 14.79% | 842 | 358 | 484 | 660 | 42.52% | +5.19 pp | 39.22% | -2.17% | +1.10% | 1,059 |
| Retest unresolved | 253 | 2.49% | 131 | 52 | 79 | 122 | 39.69% | +2.36 pp | 31.72% | -3.90% | -0.64% | 230 |

Primary setup resolution: confirmed 3,642 (35.86%); invalidated 3,246 (31.96%); expired 3,269 (32.18%); censored at data boundary 0 (0.00%).

### 2022-2025

| Causal entry stage | N | Share of detected | Actionable | S | F | I | Precision | Delta vs detected | Wilson LCB | Avg return | Delta vs detected | Tickers |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Detected setup | 10,810 | 100.00% | 5,430 | 2,629 | 2,801 | 5,380 | 48.42% | +0.00 pp | 47.09% | -0.84% | +0.00% | 2,164 |
| Buffered breakdown confirmed | 4,896 | 45.29% | 2,511 | 1,056 | 1,455 | 2,385 | 42.05% | -6.36 pp | 40.14% | -2.09% | -1.26% | 1,934 |
| Retest reached | 1,952 | 18.06% | 1,056 | 462 | 594 | 896 | 43.75% | -4.67 pp | 40.79% | -1.91% | -1.08% | 1,250 |
| Failed-retest continuation confirmed | 739 | 6.84% | 390 | 147 | 243 | 349 | 37.69% | -10.72 pp | 33.02% | -3.59% | -2.75% | 606 |
| Breakdown held without retest | 1,870 | 17.30% | 989 | 422 | 567 | 881 | 42.67% | -5.75 pp | 39.62% | -2.35% | -1.52% | 1,248 |
| Broken level reclaimed | 1,893 | 17.51% | 960 | 465 | 495 | 933 | 48.44% | +0.02 pp | 45.29% | -0.42% | +0.42% | 1,206 |
| Retest unresolved | 250 | 2.31% | 131 | 74 | 57 | 119 | 56.49% | +8.07 pp | 47.93% | +0.39% | +1.22% | 223 |

Primary setup resolution: confirmed 4,931 (45.62%); invalidated 2,968 (27.46%); expired 2,910 (26.92%); censored at data boundary 1 (0.01%).

## Monthly

### 2019-2025 combined

| Causal entry stage | N | Share of detected | Actionable | S | F | I | Precision | Delta vs detected | Wilson LCB | Avg return | Delta vs detected | Tickers |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Detected setup | 4,948 | 100.00% | 2,852 | 1,136 | 1,716 | 2,096 | 39.83% | +0.00 pp | 38.05% | -6.23% | +0.00% | 1,883 |
| Buffered breakdown confirmed | 1,981 | 40.04% | 1,249 | 470 | 779 | 732 | 37.63% | -2.20 pp | 34.99% | -8.07% | -1.84% | 1,298 |
| Retest reached | 890 | 17.99% | 530 | 194 | 336 | 360 | 36.60% | -3.23 pp | 32.61% | -7.83% | -1.60% | 739 |
| Failed-retest continuation confirmed | 311 | 6.29% | 183 | 77 | 106 | 128 | 42.08% | +2.24 pp | 35.16% | -5.40% | +0.83% | 290 |
| Breakdown held without retest | 642 | 12.97% | 428 | 170 | 258 | 214 | 39.72% | -0.11 pp | 35.20% | -6.88% | -0.65% | 544 |
| Broken level reclaimed | 836 | 16.90% | 466 | 176 | 290 | 370 | 37.77% | -2.06 pp | 33.48% | -7.43% | -1.20% | 662 |
| Retest unresolved | 133 | 2.69% | 90 | 27 | 63 | 43 | 30.00% | -9.83 pp | 21.51% | -14.79% | -8.56% | 129 |

Primary setup resolution: confirmed 2,007 (40.56%); invalidated 1,545 (31.22%); expired 1,390 (28.09%); censored at data boundary 6 (0.12%).

### 2019-2021

| Causal entry stage | N | Share of detected | Actionable | S | F | I | Precision | Delta vs detected | Wilson LCB | Avg return | Delta vs detected | Tickers |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Detected setup | 2,100 | 100.00% | 1,293 | 569 | 724 | 807 | 44.01% | +0.00 pp | 41.32% | -5.82% | +0.00% | 1,333 |
| Buffered breakdown confirmed | 951 | 45.29% | 624 | 258 | 366 | 327 | 41.35% | -2.66 pp | 37.55% | -6.87% | -1.05% | 785 |
| Retest reached | 450 | 21.43% | 284 | 120 | 164 | 166 | 42.25% | -1.75 pp | 36.65% | -4.54% | +1.27% | 410 |
| Failed-retest continuation confirmed | 142 | 6.76% | 81 | 44 | 37 | 61 | 54.32% | +10.31 pp | 43.52% | -0.24% | +5.58% | 134 |
| Breakdown held without retest | 344 | 16.38% | 258 | 97 | 161 | 86 | 37.60% | -6.41 pp | 31.91% | -8.81% | -2.99% | 312 |
| Broken level reclaimed | 362 | 17.24% | 207 | 82 | 125 | 155 | 39.61% | -4.39 pp | 33.20% | -5.79% | +0.03% | 325 |
| Retest unresolved | 103 | 4.90% | 76 | 24 | 52 | 27 | 31.58% | -12.43 pp | 22.23% | -15.14% | -9.32% | 100 |

Primary setup resolution: confirmed 951 (45.29%); invalidated 608 (28.95%); expired 541 (25.76%); censored at data boundary 0 (0.00%).

### 2022-2025

| Causal entry stage | N | Share of detected | Actionable | S | F | I | Precision | Delta vs detected | Wilson LCB | Avg return | Delta vs detected | Tickers |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Detected setup | 2,848 | 100.00% | 1,559 | 567 | 992 | 1,289 | 36.37% | +0.00 pp | 34.02% | -6.53% | +0.00% | 1,451 |
| Buffered breakdown confirmed | 1,030 | 36.17% | 625 | 212 | 413 | 405 | 33.92% | -2.45 pp | 30.32% | -9.18% | -2.65% | 795 |
| Retest reached | 440 | 15.45% | 246 | 74 | 172 | 194 | 30.08% | -6.29 pp | 24.69% | -11.18% | -4.65% | 401 |
| Failed-retest continuation confirmed | 169 | 5.93% | 102 | 33 | 69 | 67 | 32.35% | -4.02 pp | 24.06% | -9.73% | -3.20% | 161 |
| Breakdown held without retest | 298 | 10.46% | 170 | 73 | 97 | 128 | 42.94% | +6.57 pp | 35.74% | -4.66% | +1.87% | 263 |
| Broken level reclaimed | 474 | 16.64% | 259 | 94 | 165 | 215 | 36.29% | -0.08 pp | 30.68% | -8.68% | -2.15% | 404 |
| Retest unresolved | 30 | 1.05% | 14 | 3 | 11 | 16 | 21.43% | -14.94 pp | 7.57% | -13.56% | -7.03% | 30 |

Primary setup resolution: confirmed 1,056 (37.08%); invalidated 937 (32.90%); expired 849 (29.81%); censored at data boundary 6 (0.21%).

## Interpretation and deployment decision

A stage is described as stable only when both precision and average return exceed the detected-setup baseline in **both** time splits. A higher combined number by itself is not enough.

- **Daily:** detected setups produced 72,941 signals at 47.07% precision and -0.61% average return. Buffered breakdown entries produced 32,700 (44.83% of detected) at 45.54% precision and -0.91% average return. Stages improving both measures in both splits: **none**.
- **Weekly:** detected setups produced 20,967 signals at 43.02% precision and -2.01% average return. Buffered breakdown entries produced 8,538 (40.72% of detected) at 41.45% precision and -2.32% average return. Stages improving both measures in both splits: **none**.
- **Monthly:** detected setups produced 4,948 signals at 39.83% precision and -6.23% average return. Buffered breakdown entries produced 1,981 (40.04% of detected) at 37.63% precision and -8.07% average return. Stages improving both measures in both splits: **none**.

No lifecycle stage improves both precision and average return in both time splits. The refinement was therefore removed rather than retained as a production status or trigger layer.

The original product objective remains unchanged: valid candlestick occurrences are detected and notification behavior is not filtered by the rejected experiment. This observational replay ignores fees, slippage, overlapping positions, position sizing, and cross-signal dependence.
