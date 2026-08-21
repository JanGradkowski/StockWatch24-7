# Candlestick BUY/SELL performance by market regime

Run date: 2026-08-16. This study reruns the current factory candlestick model on the frozen post-2018 universe of **2,227 usable stocks** and **4,923,993 adjusted daily candles**. The evaluation period is 2019-01-01 through 2025-12-31; 2017-2018 supplies warm-up history.

## Question and method

The question is whether the previously weak SELL results were caused by evaluating mostly bullish market conditions. BUY and SELL signals are therefore measured separately inside bearish, bullish, and transitional broad-market regimes. The signal detector is the current factory conflict-aware OR model, not the older fixed-window control used in the first directional report.

The broad-market proxy is a daily index built from this same frozen universe. Every stock's daily adjusted-close return is weighted by its pre-2019 market capitalization; available weights are renormalized each day. This prevents future constituent or future market-cap information from entering the regime label. The proxy is aggregated to each tested interval.

A bar is **bearish** only when its close is below the long moving average and its short moving average is also below the long average. It is **bullish** under the symmetric opposite rule; all other bars are transitional. Daily uses 50/200 sessions, Weekly 10/40 weeks, and Monthly 3/10 months. Both averages include the completed signal/entry bar, so the label was knowable then. No future peak, trough, return, or signal outcome is used.

- Manifest SHA-256: `4228946F6535DBFAE60FCB901E95446868D6888CE63C13BEC48F16CB0D429A8D`
- Candle file SHA-256: `291F3A8C514D840E3EB3756A4905415AA0FC300DE335A077ACDD25354E84688F`
- Daily outcome: 10 bars, success at +3%, failure at -3%.
- Weekly outcome: 8 bars, success at +8%, failure at -8%.
- Monthly outcome: 6 bars, success at +12%, failure at -12%.
- SELL returns are direction-adjusted: positive means price fell after the signal.
- Precision is success / (success + failure); inconclusive signals remain in N and average return.

## Regime coverage

| Interval | Bearish bars | Bullish bars | Transitional bars | Bearish share |
|---|---:|---:|---:|---:|
| Daily | 215 | 1,343 | 193 | 12.28% |
| Weekly | 45 | 282 | 37 | 12.36% |
| Monthly | 10 | 64 | 10 | 11.90% |

## Daily results

Coverage: 2,227 symbols and 4,923,993 aggregated candles.

### Combined 2019-2025

| Regime | Direction | N | Actionable | S | F | I | Precision | Wilson LCB | Avg return | Ticker precision | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| All regimes | BUY | 59,386 | 39,034 | 21,039 | 17,995 | 20,352 | 53.90% | 53.40% | +0.60% | 55.57% | 2,219 |
| All regimes | SELL | 72,941 | 45,212 | 21,283 | 23,929 | 27,729 | 47.07% | 46.61% | -0.61% | 46.80% | 2,222 |
| Bearish | BUY | 8,327 | 5,869 | 3,428 | 2,441 | 2,458 | 58.41% | 57.14% | +1.77% | 59.63% | 2,130 |
| Bearish | SELL | 8,379 | 6,045 | 2,276 | 3,769 | 2,334 | 37.65% | 36.44% | -2.34% | 38.37% | 2,120 |
| Bullish | BUY | 43,115 | 27,702 | 14,434 | 13,268 | 15,413 | 52.10% | 51.52% | +0.31% | 53.85% | 2,219 |
| Bullish | SELL | 57,869 | 35,102 | 16,898 | 18,204 | 22,767 | 48.14% | 47.62% | -0.43% | 47.88% | 2,222 |
| Transitional | BUY | 7,944 | 5,463 | 3,177 | 2,286 | 2,481 | 58.15% | 56.84% | +0.99% | 59.24% | 2,137 |
| Transitional | SELL | 6,693 | 4,065 | 2,109 | 1,956 | 2,628 | 51.88% | 50.34% | -0.06% | 53.24% | 2,065 |

### Independent time splits

#### 2019-2021

| Regime | Direction | N | Actionable | S | F | I | Precision | Wilson LCB | Avg return | Ticker precision | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| All regimes | BUY | 22,041 | 14,658 | 8,476 | 6,182 | 7,383 | 57.83% | 57.02% | +1.05% | 60.46% | 2,183 |
| All regimes | SELL | 33,248 | 20,350 | 9,113 | 11,237 | 12,898 | 44.78% | 44.10% | -0.97% | 44.63% | 2,183 |
| Bearish | BUY | 1,324 | 1,028 | 830 | 198 | 296 | 80.74% | 78.22% | +6.94% | 82.11% | 918 |
| Bearish | SELL | 2,766 | 2,046 | 492 | 1,554 | 720 | 24.05% | 22.24% | -5.75% | 25.53% | 1,482 |
| Bullish | BUY | 17,491 | 11,288 | 6,400 | 4,888 | 6,203 | 56.70% | 55.78% | +0.83% | 59.70% | 2,177 |
| Bullish | SELL | 27,264 | 16,343 | 7,566 | 8,777 | 10,921 | 46.30% | 45.53% | -0.65% | 45.91% | 2,183 |
| Transitional | BUY | 3,226 | 2,342 | 1,246 | 1,096 | 884 | 53.20% | 51.18% | -0.17% | 54.61% | 1,641 |
| Transitional | SELL | 3,218 | 1,961 | 1,055 | 906 | 1,257 | 53.80% | 51.59% | +0.36% | 54.77% | 1,626 |

#### 2022-2025

| Regime | Direction | N | Actionable | S | F | I | Precision | Wilson LCB | Avg return | Ticker precision | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| All regimes | BUY | 37,345 | 24,376 | 12,563 | 11,813 | 12,969 | 51.54% | 50.91% | +0.34% | 53.71% | 2,218 |
| All regimes | SELL | 39,693 | 24,862 | 12,170 | 12,692 | 14,831 | 48.95% | 48.33% | -0.31% | 48.99% | 2,222 |
| Bearish | BUY | 7,003 | 4,841 | 2,598 | 2,243 | 2,162 | 53.67% | 52.26% | +0.79% | 54.60% | 2,070 |
| Bearish | SELL | 5,613 | 3,999 | 1,784 | 2,215 | 1,614 | 44.61% | 43.08% | -0.66% | 45.47% | 1,968 |
| Bullish | BUY | 25,624 | 16,414 | 8,034 | 8,380 | 9,210 | 48.95% | 48.18% | -0.04% | 51.18% | 2,218 |
| Bullish | SELL | 30,605 | 18,759 | 9,332 | 9,427 | 11,846 | 49.75% | 49.03% | -0.23% | 49.97% | 2,222 |
| Transitional | BUY | 4,718 | 3,121 | 1,931 | 1,190 | 1,597 | 61.87% | 60.15% | +1.78% | 63.98% | 1,902 |
| Transitional | SELL | 3,475 | 2,104 | 1,054 | 1,050 | 1,371 | 50.10% | 47.96% | -0.45% | 50.97% | 1,666 |

### Bearish-regime pattern breakdown (2019-2025)

| Pattern | Direction | N | Actionable | Precision | Wilson LCB | Avg return | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|
| BULLISH_ENGULFING | BUY | 2,623 | 1,890 | 58.62% | 56.39% | +2.05% | 1,545 |
| BEARISH_ENGULFING | SELL | 2,472 | 1,776 | 32.66% | 30.52% | -3.49% | 1,462 |
| HAMMER | BUY | 1,149 | 811 | 63.38% | 60.01% | +2.63% | 900 |
| INVERTED_HAMMER | BUY | 1,356 | 936 | 61.32% | 58.16% | +1.64% | 1,027 |
| HANGING_MAN | SELL | 1,363 | 997 | 36.21% | 33.28% | -2.55% | 1,002 |
| SHOOTING_STAR | SELL | 923 | 661 | 35.70% | 32.14% | -3.16% | 748 |
| MORNING_STAR | BUY | 896 | 640 | 45.78% | 41.96% | +0.31% | 753 |
| EVENING_STAR | SELL | 959 | 698 | 37.68% | 34.16% | -1.97% | 760 |
| PIERCING_LINE | BUY | 582 | 419 | 60.14% | 55.38% | +1.66% | 512 |
| DARK_CLOUD_COVER | SELL | 676 | 475 | 36.21% | 32.02% | -2.59% | 573 |
| BULLISH_HARAMI | BUY | 1,634 | 1,111 | 58.60% | 55.67% | +1.77% | 1,145 |
| BEARISH_HARAMI | SELL | 1,939 | 1,400 | 46.43% | 43.83% | -0.37% | 1,291 |
| THREE_WHITE_SOLDIERS | BUY | 87 | 62 | 58.06% | 45.67% | -0.28% | 86 |
| THREE_BLACK_CROWS | SELL | 47 | 38 | 36.84% | 23.38% | -4.86% | 47 |

## Weekly results

Coverage: 2,217 symbols and 1,021,667 aggregated candles.

### Combined 2019-2025

| Regime | Direction | N | Actionable | S | F | I | Precision | Wilson LCB | Avg return | Ticker precision | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| All regimes | BUY | 13,329 | 7,662 | 4,353 | 3,309 | 5,667 | 56.81% | 55.70% | +2.85% | 60.29% | 2,198 |
| All regimes | SELL | 20,967 | 10,576 | 4,550 | 6,026 | 10,391 | 43.02% | 42.08% | -2.01% | 44.07% | 2,209 |
| Bearish | BUY | 2,943 | 1,779 | 1,275 | 504 | 1,164 | 71.67% | 69.53% | +7.62% | 72.44% | 1,588 |
| Bearish | SELL | 1,361 | 768 | 380 | 388 | 593 | 49.48% | 45.95% | -2.15% | 50.19% | 960 |
| Bullish | BUY | 9,232 | 5,195 | 2,637 | 2,558 | 4,037 | 50.76% | 49.40% | +0.87% | 53.98% | 2,139 |
| Bullish | SELL | 17,065 | 8,437 | 3,768 | 4,669 | 8,628 | 44.66% | 43.60% | -1.54% | 45.78% | 2,207 |
| Transitional | BUY | 1,154 | 688 | 441 | 247 | 466 | 64.10% | 60.45% | +6.50% | 64.09% | 875 |
| Transitional | SELL | 2,541 | 1,371 | 402 | 969 | 1,170 | 29.32% | 26.97% | -5.11% | 28.92% | 1,496 |

### Independent time splits

#### 2019-2021

| Regime | Direction | N | Actionable | S | F | I | Precision | Wilson LCB | Avg return | Ticker precision | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| All regimes | BUY | 4,857 | 2,798 | 1,662 | 1,136 | 2,059 | 59.40% | 57.57% | +3.70% | 62.28% | 1,837 |
| All regimes | SELL | 10,157 | 5,146 | 1,921 | 3,225 | 5,011 | 37.33% | 36.02% | -3.26% | 37.80% | 2,146 |
| Bearish | BUY | 1,022 | 620 | 553 | 67 | 402 | 89.19% | 86.50% | +14.25% | 88.59% | 782 |
| Bearish | SELL | 345 | 216 | 20 | 196 | 129 | 9.26% | 6.07% | -15.66% | 8.97% | 309 |
| Bullish | BUY | 3,524 | 1,992 | 1,023 | 969 | 1,532 | 51.36% | 49.16% | +0.91% | 53.83% | 1,619 |
| Bullish | SELL | 8,323 | 4,115 | 1,703 | 2,412 | 4,208 | 41.39% | 39.89% | -2.19% | 42.71% | 2,108 |
| Transitional | BUY | 311 | 186 | 86 | 100 | 125 | 46.24% | 39.22% | +0.56% | 45.24% | 271 |
| Transitional | SELL | 1,489 | 815 | 198 | 617 | 674 | 24.29% | 21.48% | -6.36% | 22.82% | 1,073 |

#### 2022-2025

| Regime | Direction | N | Actionable | S | F | I | Precision | Wilson LCB | Avg return | Ticker precision | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| All regimes | BUY | 8,472 | 4,864 | 2,691 | 2,173 | 3,608 | 55.32% | 53.92% | +2.36% | 58.57% | 2,127 |
| All regimes | SELL | 10,810 | 5,430 | 2,629 | 2,801 | 5,380 | 48.42% | 47.09% | -0.84% | 51.05% | 2,164 |
| Bearish | BUY | 1,921 | 1,159 | 722 | 437 | 762 | 62.30% | 59.47% | +4.09% | 64.01% | 1,215 |
| Bearish | SELL | 1,016 | 552 | 360 | 192 | 464 | 65.22% | 61.15% | +2.44% | 66.30% | 751 |
| Bullish | BUY | 5,708 | 3,203 | 1,614 | 1,589 | 2,505 | 50.39% | 48.66% | +0.85% | 53.26% | 1,980 |
| Bullish | SELL | 8,742 | 4,322 | 2,065 | 2,257 | 4,420 | 47.78% | 46.29% | -0.92% | 49.81% | 2,131 |
| Transitional | BUY | 843 | 502 | 355 | 147 | 341 | 70.72% | 66.59% | +8.70% | 70.92% | 697 |
| Transitional | SELL | 1,052 | 556 | 204 | 352 | 496 | 36.69% | 32.79% | -3.33% | 37.59% | 790 |

### Bearish-regime pattern breakdown (2019-2025)

| Pattern | Direction | N | Actionable | Precision | Wilson LCB | Avg return | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|
| BULLISH_ENGULFING | BUY | 964 | 639 | 71.36% | 67.74% | +8.77% | 798 |
| BEARISH_ENGULFING | SELL | 362 | 209 | 36.36% | 30.14% | -5.94% | 328 |
| HAMMER | BUY | 442 | 253 | 67.19% | 61.19% | +4.97% | 398 |
| INVERTED_HAMMER | BUY | 544 | 305 | 71.48% | 66.17% | +6.22% | 479 |
| HANGING_MAN | SELL | 128 | 74 | 56.76% | 45.41% | +1.55% | 124 |
| SHOOTING_STAR | SELL | 294 | 150 | 42.67% | 35.03% | -3.03% | 279 |
| MORNING_STAR | BUY | 256 | 152 | 75.00% | 67.56% | +7.27% | 242 |
| EVENING_STAR | SELL | 149 | 70 | 55.71% | 44.08% | -0.21% | 145 |
| PIERCING_LINE | BUY | 183 | 103 | 69.90% | 60.46% | +8.83% | 172 |
| DARK_CLOUD_COVER | SELL | 90 | 58 | 36.21% | 25.05% | -8.68% | 89 |
| BULLISH_HARAMI | BUY | 434 | 275 | 77.09% | 71.77% | +10.48% | 380 |
| BEARISH_HARAMI | SELL | 331 | 202 | 67.82% | 61.10% | +2.38% | 317 |
| THREE_WHITE_SOLDIERS | BUY | 120 | 52 | 63.46% | 49.87% | +3.03% | 115 |
| THREE_BLACK_CROWS | SELL | 7 | 5 | 20.00% | 3.62% | -7.64% | 7 |

## Monthly results

Coverage: 2,204 symbols and 235,793 aggregated candles.

### Combined 2019-2025

| Regime | Direction | N | Actionable | S | F | I | Precision | Wilson LCB | Avg return | Ticker precision | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| All regimes | BUY | 2,367 | 1,599 | 930 | 669 | 768 | 58.16% | 55.73% | +9.76% | 61.39% | 1,331 |
| All regimes | SELL | 4,948 | 2,852 | 1,136 | 1,716 | 2,096 | 39.83% | 38.05% | -6.23% | 43.64% | 1,883 |
| Bearish | BUY | 610 | 367 | 214 | 153 | 243 | 58.31% | 53.21% | +7.97% | 59.05% | 531 |
| Bearish | SELL | 568 | 339 | 115 | 224 | 229 | 33.92% | 29.09% | -12.14% | 34.27% | 480 |
| Bullish | BUY | 1,537 | 1,086 | 621 | 465 | 451 | 57.18% | 54.22% | +9.39% | 59.72% | 985 |
| Bullish | SELL | 3,928 | 2,255 | 914 | 1,341 | 1,673 | 40.53% | 38.52% | -5.47% | 43.03% | 1,749 |
| Transitional | BUY | 220 | 146 | 95 | 51 | 74 | 65.07% | 57.04% | +17.38% | 64.49% | 204 |
| Transitional | SELL | 452 | 258 | 107 | 151 | 194 | 41.47% | 35.63% | -5.45% | 41.99% | 394 |

### Independent time splits

#### 2019-2021

| Regime | Direction | N | Actionable | S | F | I | Precision | Wilson LCB | Avg return | Ticker precision | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| All regimes | BUY | 623 | 489 | 346 | 143 | 134 | 70.76% | 66.58% | +26.68% | 71.23% | 480 |
| All regimes | SELL | 2,100 | 1,293 | 569 | 724 | 807 | 44.01% | 41.32% | -5.82% | 45.69% | 1,333 |
| Bearish | BUY | 108 | 78 | 48 | 30 | 30 | 61.54% | 50.44% | +19.86% | 60.81% | 103 |
| Bearish | SELL | 62 | 52 | 3 | 49 | 10 | 5.77% | 1.98% | -67.03% | 6.00% | 59 |
| Bullish | BUY | 482 | 385 | 275 | 110 | 97 | 71.43% | 66.72% | +27.30% | 71.73% | 389 |
| Bullish | SELL | 1,804 | 1,092 | 506 | 586 | 712 | 46.34% | 43.40% | -3.70% | 47.61% | 1,224 |
| Transitional | BUY | 33 | 26 | 23 | 3 | 7 | 88.46% | 71.02% | +39.86% | 88.00% | 32 |
| Transitional | SELL | 234 | 149 | 60 | 89 | 85 | 40.27% | 32.73% | -5.95% | 41.43% | 222 |

#### 2022-2025

| Regime | Direction | N | Actionable | S | F | I | Precision | Wilson LCB | Avg return | Ticker precision | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| All regimes | BUY | 1,744 | 1,110 | 584 | 526 | 634 | 52.61% | 49.67% | +3.72% | 55.64% | 1,085 |
| All regimes | SELL | 2,848 | 1,559 | 567 | 992 | 1,289 | 36.37% | 34.02% | -6.53% | 39.84% | 1,451 |
| Bearish | BUY | 502 | 289 | 166 | 123 | 213 | 57.44% | 51.68% | +5.41% | 58.68% | 439 |
| Bearish | SELL | 506 | 287 | 112 | 175 | 219 | 39.02% | 33.56% | -5.41% | 39.39% | 428 |
| Bullish | BUY | 1,055 | 701 | 346 | 355 | 354 | 49.36% | 45.67% | +1.20% | 51.33% | 735 |
| Bullish | SELL | 2,124 | 1,163 | 408 | 755 | 961 | 35.08% | 32.39% | -6.97% | 38.15% | 1,235 |
| Transitional | BUY | 187 | 120 | 72 | 48 | 67 | 60.00% | 51.06% | +13.42% | 59.21% | 175 |
| Transitional | SELL | 218 | 109 | 47 | 62 | 109 | 43.12% | 34.21% | -4.90% | 43.00% | 195 |

### Bearish-regime pattern breakdown (2019-2025)

| Pattern | Direction | N | Actionable | Precision | Wilson LCB | Avg return | Tickers |
|---|---|---:|---:|---:|---:|---:|---:|
| BULLISH_ENGULFING | BUY | 256 | 144 | 58.33% | 50.17% | +5.95% | 249 |
| BEARISH_ENGULFING | SELL | 306 | 187 | 33.69% | 27.30% | -12.58% | 289 |
| HAMMER | BUY | 41 | 24 | 41.67% | 24.47% | +0.88% | 41 |
| INVERTED_HAMMER | BUY | 43 | 34 | 50.00% | 34.07% | +18.23% | 42 |
| HANGING_MAN | SELL | 56 | 31 | 41.94% | 26.42% | -3.59% | 56 |
| SHOOTING_STAR | SELL | 75 | 51 | 21.57% | 12.49% | -25.41% | 73 |
| MORNING_STAR | BUY | 19 | 15 | 40.00% | 19.82% | -2.76% | 19 |
| EVENING_STAR | SELL | 41 | 23 | 39.13% | 22.16% | -8.78% | 41 |
| PIERCING_LINE | BUY | 133 | 75 | 64.00% | 52.70% | +6.96% | 128 |
| DARK_CLOUD_COVER | SELL | 43 | 25 | 44.00% | 26.67% | -2.86% | 43 |
| BULLISH_HARAMI | BUY | 117 | 75 | 65.33% | 54.05% | +14.06% | 114 |
| BEARISH_HARAMI | SELL | 37 | 14 | 50.00% | 26.80% | -2.69% | 37 |
| THREE_WHITE_SOLDIERS | BUY | 1 | 0 | n/a | n/a | -0.52% | 1 |
| THREE_BLACK_CROWS | SELL | 10 | 8 | 12.50% | 2.24% | -35.58% | 10 |

## Comparison with the previous directional report

The previous report's `Current production control` used the older fixed-window model. The table below keeps it as historical context and places it beside the current factory model's all-regime and bearish-regime results. Model changes mean the N values are not expected to match; the clean causal comparison for the market-regime hypothesis is Current all regimes versus Current bearish.

| Interval | Direction | Previous N | Previous precision | Previous avg | Current all N | Current all precision | Current all avg | Current bearish N | Current bearish precision | Current bearish avg | Bearish precision delta vs current all |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Daily | BUY | 63,267 | 52.81% | +0.48% | 59,386 | 53.90% | +0.60% | 8,327 | 58.41% | +1.77% | +4.51 pp |
| Daily | SELL | 71,597 | 46.43% | -0.73% | 72,941 | 47.07% | -0.61% | 8,379 | 37.65% | -2.34% | -9.42 pp |
| Weekly | BUY | 2,759 | 58.16% | +6.53% | 13,329 | 56.81% | +2.85% | 2,943 | 71.67% | +7.62% | +14.86 pp |
| Weekly | SELL | 4,722 | 40.46% | -4.14% | 20,967 | 43.02% | -2.01% | 1,361 | 49.48% | -2.15% | +6.46 pp |
| Monthly | BUY | 2,428 | 55.24% | +6.58% | 2,367 | 58.16% | +9.76% | 610 | 58.31% | +7.97% | +0.15 pp |
| Monthly | SELL | 4,273 | 35.07% | -9.21% | 4,948 | 39.83% | -6.23% | 568 | 33.92% | -12.14% | -5.91 pp |

## Interpretation

- **Daily:** SELL precision changes from 47.07% across all regimes to 37.65% in bearish regimes (-9.42 pp), while average direction-adjusted return changes from -0.61% to -2.34%. BUY precision changes from 53.90% to 58.41% (+4.51 pp), with average return changing from +0.60% to +1.77%.
- **Weekly:** SELL precision changes from 43.02% across all regimes to 49.48% in bearish regimes (+6.46 pp), while average direction-adjusted return changes from -2.01% to -2.15%. BUY precision changes from 56.81% to 71.67% (+14.86 pp), with average return changing from +2.85% to +7.62%.
- **Monthly:** SELL precision changes from 39.83% across all regimes to 33.92% in bearish regimes (-5.91 pp), while average direction-adjusted return changes from -6.23% to -12.14%. BUY precision changes from 58.16% to 58.31% (+0.15 pp), with average return changing from +9.76% to +7.97%.

### Decision

**The hypothesis that SELL weakness was mainly caused by bullish test conditions is rejected.** Daily and Monthly SELL signals were less precise and had worse average direction-adjusted returns in bearish regimes. Weekly SELL improved in the combined table, but the effect reversed completely between the two time splits: bearish precision was 9.26% in 2019-2021 and 65.22% in 2022-2025. Its combined bearish average return also remained negative. That instability is not a defensible regime filter. No SELL notification or scoring policy should change from this result.

The exploratory result worth retaining is on the other side: Daily and Weekly BUY reversal signals improved in bearish regimes in both time splits. That is economically plausible—bullish candlestick reversals have more room to rebound during broad drawdowns—but it was discovered on reused outcomes. It should be treated as a candidate for a future untouched or walk-forward confirmation, not deployed now. Monthly BUY did not show the same stable split-period improvement.

This is a descriptive regime-stratified rerun, not a newly untouched holdout: the current detector was developed before this analysis, but the 2019-2025 outcomes have already appeared in earlier studies. Signals also cluster within common market episodes, so individual-signal Wilson bounds overstate independence. A regime-dependent production policy should be considered only if the direction of the effect is consistent in both 2019-2021 and 2022-2025, has adequate bearish samples, and improves average return as well as precision.
