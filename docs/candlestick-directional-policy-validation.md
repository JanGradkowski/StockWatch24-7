# Post-2018 direction-specific candlestick validation

Run date: 2026-08-12. The pre-outcome universe contains **2,227 usable stocks** and **4,923,993 adjusted daily candles** from 2017-01-03 through 2025-12-17. Signals are evaluated only from 2019 onward; 2017-2018 is warm-up.

Universe selection was frozen from the local pre-2019 US-equity metadata snapshot at a $300M market-cap floor, supplemented by the final S&P 500 membership on or before 2018-12-31. Price rows come from the public `HexQuant/Stocks-Daily-Price` Parquet snapshot. OHLC is adjusted by `adjusted close / raw close`. Missing old identifiers remain in the failure audit and are not replaced using future outcomes.

- Manifest SHA-256: `4228946F6535DBFAE60FCB901E95446868D6888CE63C13BEC48F16CB0D429A8D`
- Candle file SHA-256: `291F3A8C514D840E3EB3756A4905415AA0FC300DE335A077ACDD25354E84688F`
- Early out-of-sample period: 2019-2021.
- Late out-of-sample period: 2022-2025.
- Precision is success / (success + failure); inconclusive signals remain in N and average return.

## Pre-registered policy definitions

| Interval | Policy | BUY prior-trend model | SELL prior-trend model | Deployment candidate |
|---|---|---|---|:---:|
| Daily | Current production control | Current 1.5% - 3/5 | Current 1.5% - 3/5 | yes |
| Daily | Direction-optimized candidate | Veto L2-R2-B30-P0.00 + W25-S0.15-R0.20 | Regression W25-S0.00-R0.00 | yes |
| Daily | Symmetric sideways filter D0.25 | Veto L2-R2-B30-P0.00-D0.25 + W25-S0.15-R0.20 | Veto L2-R2-B30-P0.00-D0.25 + W25-S0.15-R0.20 | yes |
| Daily | Symmetric sideways filter D0.50 | Veto L2-R2-B30-P0.00-D0.50 + W25-S0.15-R0.20 | Veto L2-R2-B30-P0.00-D0.50 + W25-S0.15-R0.20 | yes |
| Weekly | Current production control | Current 16.0% - 4/6 | Current 16.0% - 4/6 | yes |
| Weekly | Pre-registered weekly hybrid | Structure L2-R2-B15-P0.00 | Current 16.0% - 4/6 | yes |
| Weekly | Count-preserving optimized candidate | Regression W25-S0.00-R0.00 | Current 16.0% - 4/6 | yes |
| Weekly | Fit-only sideways-filtered candidate | Regression W25-S0.00-R0.10 | Current 16.0% - 4/6 | yes |
| Weekly | Gentle sideways-filtered candidate | Regression W25-S0.05-R0.10 | Current 16.0% - 4/6 | yes |
| Weekly | Light sideways-filtered candidate | Regression W25-S0.10-R0.10 | Current 16.0% - 4/6 | yes |
| Weekly | Sideways-filtered candidate | Regression W25-S0.15-R0.20 | Current 16.0% - 4/6 | yes |
| Weekly | Symmetric sideways filter B15 D0.25 | Veto L2-R2-B15-P0.00-D0.25 + W25-S0.15-R0.20 | Veto L2-R2-B15-P0.00-D0.25 + W25-S0.15-R0.20 | yes |
| Weekly | Symmetric sideways filter B30 D0.25 | Veto L2-R2-B30-P0.00-D0.25 + W25-S0.15-R0.20 | Veto L2-R2-B30-P0.00-D0.25 + W25-S0.15-R0.20 | yes |
| Monthly | Current production control | Current 1.5% - 3/5 | Current 1.5% - 3/5 | yes |
| Monthly | Direction-optimized candidate | Regression W25-S0.00-R0.00 | Current 1.5% - 3/5 | yes |
| Monthly | Fit-only sideways-filtered candidate | Regression W25-S0.00-R0.10 | Current 1.5% - 3/5 | yes |
| Monthly | Gentle sideways-filtered candidate | Regression W25-S0.05-R0.10 | Current 1.5% - 3/5 | yes |
| Monthly | Light sideways-filtered candidate | Regression W25-S0.10-R0.10 | Current 1.5% - 3/5 | yes |
| Monthly | Sideways-filtered candidate | Regression W25-S0.15-R0.20 | Current 1.5% - 3/5 | yes |
| Monthly | Symmetric sideways filter B15 D0.25 | Veto L2-R2-B15-P0.00-D0.25 + W25-S0.15-R0.20 | Veto L2-R2-B15-P0.00-D0.25 + W25-S0.15-R0.20 | yes |
| Monthly | Symmetric sideways filter B30 D0.25 | Veto L2-R2-B30-P0.00-D0.25 + W25-S0.15-R0.20 | Veto L2-R2-B30-P0.00-D0.25 + W25-S0.15-R0.20 | yes |

The direction-optimized models were frozen from 2003-2010 training and 2011-2014 validation before this 2019-2025 dataset was evaluated. They are therefore eligible deployment candidates here; the final choice explicitly balances precision with the requested signal-volume priority.

## Daily results

Coverage: 2,227 symbols and 4,923,993 aggregated candles. Outcome: 10 candles / 3.0%.

### Policy totals

| Period | Policy | N | Actionable | S | F | I | Precision | Wilson LCB | Avg | Ticker precision | Tickers | BUY N | SELL N | Email eligible | Website-only |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 2019-2021 | Current production control | 55,893 | 35,566 | 17,576 | 17,990 | 20,327 | 49.42% | 48.90% | -0.29% | 49.34% | 2,188 | 23,794 | 32,099 | 23,794 | 32,099 |
| 2019-2021 | Direction-optimized candidate | 97,697 | 59,830 | 29,408 | 30,422 | 37,867 | 49.15% | 48.75% | -0.24% | 49.03% | 2,189 | 29,338 | 68,359 | 29,338 | 68,359 |
| 2019-2021 | Symmetric sideways filter D0.25 | 50,846 | 31,454 | 16,225 | 15,229 | 19,392 | 51.58% | 51.03% | +0.17% | 51.56% | 2,189 | 21,289 | 29,557 | 21,289 | 29,557 |
| 2019-2021 | Symmetric sideways filter D0.50 | 37,699 | 23,487 | 12,203 | 11,284 | 14,212 | 51.96% | 51.32% | +0.18% | 52.08% | 2,189 | 15,559 | 22,140 | 15,559 | 22,140 |
| 2022-2025 | Current production control | 78,971 | 50,725 | 25,120 | 25,605 | 28,246 | 49.52% | 49.09% | -0.06% | 49.62% | 2,225 | 39,473 | 39,498 | 39,473 | 39,498 |
| 2022-2025 | Direction-optimized candidate | 123,766 | 77,249 | 38,690 | 38,559 | 46,517 | 50.08% | 49.73% | -0.09% | 50.02% | 2,226 | 43,418 | 80,348 | 43,418 | 80,348 |
| 2022-2025 | Symmetric sideways filter D0.25 | 68,217 | 42,829 | 21,524 | 21,305 | 25,388 | 50.26% | 49.78% | -0.03% | 50.21% | 2,222 | 32,111 | 36,106 | 32,111 | 36,106 |
| 2022-2025 | Symmetric sideways filter D0.50 | 50,702 | 31,832 | 16,030 | 15,802 | 18,870 | 50.36% | 49.81% | +0.02% | 50.32% | 2,222 | 23,621 | 27,081 | 23,621 | 27,081 |
| 2019-2025 combined | Current production control | 134,864 | 86,291 | 42,696 | 43,595 | 48,573 | 49.48% | 49.15% | -0.16% | 49.52% | 2,226 | 63,267 | 71,597 | 63,267 | 71,597 |
| 2019-2025 combined | Direction-optimized candidate | 221,463 | 137,079 | 68,098 | 68,981 | 84,384 | 49.68% | 49.41% | -0.16% | 49.57% | 2,226 | 72,756 | 148,707 | 72,756 | 148,707 |
| 2019-2025 combined | Symmetric sideways filter D0.25 | 119,063 | 74,283 | 37,749 | 36,534 | 44,780 | 50.82% | 50.46% | +0.06% | 50.74% | 2,223 | 53,400 | 65,663 | 53,400 | 65,663 |
| 2019-2025 combined | Symmetric sideways filter D0.50 | 88,401 | 55,319 | 28,233 | 27,086 | 33,082 | 51.04% | 50.62% | +0.08% | 51.03% | 2,223 | 39,180 | 49,221 | 39,180 | 49,221 |

### Direction breakdown

| Period | Policy | Direction | N | S | F | I | Precision | Wilson LCB | Avg |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 2019-2021 | Current production control | BUY | 23,794 | 8,722 | 6,838 | 8,234 | 56.05% | 55.27% | +0.81% |
| 2019-2021 | Current production control | SELL | 32,099 | 8,854 | 11,152 | 12,093 | 44.26% | 43.57% | -1.11% |
| 2019-2021 | Direction-optimized candidate | BUY | 29,338 | 11,238 | 7,505 | 10,595 | 59.96% | 59.25% | +1.47% |
| 2019-2021 | Direction-optimized candidate | SELL | 68,359 | 18,170 | 22,917 | 27,272 | 44.22% | 43.74% | -0.97% |
| 2019-2021 | Symmetric sideways filter D0.25 | BUY | 21,289 | 8,322 | 5,457 | 7,510 | 60.40% | 59.58% | +1.55% |
| 2019-2021 | Symmetric sideways filter D0.25 | SELL | 29,557 | 7,903 | 9,772 | 11,882 | 44.71% | 43.98% | -0.83% |
| 2019-2021 | Symmetric sideways filter D0.50 | BUY | 15,559 | 6,233 | 3,977 | 5,349 | 61.05% | 60.10% | +1.62% |
| 2019-2021 | Symmetric sideways filter D0.50 | SELL | 22,140 | 5,970 | 7,307 | 8,863 | 44.96% | 44.12% | -0.84% |
| 2022-2025 | Current production control | BUY | 39,473 | 13,054 | 12,618 | 13,801 | 50.85% | 50.24% | +0.29% |
| 2022-2025 | Current production control | SELL | 39,498 | 12,066 | 12,987 | 14,445 | 48.16% | 47.54% | -0.41% |
| 2022-2025 | Direction-optimized candidate | BUY | 43,418 | 14,297 | 13,504 | 15,617 | 51.43% | 50.84% | +0.30% |
| 2022-2025 | Direction-optimized candidate | SELL | 80,348 | 24,393 | 25,055 | 30,900 | 49.33% | 48.89% | -0.30% |
| 2022-2025 | Symmetric sideways filter D0.25 | BUY | 32,111 | 10,622 | 9,961 | 11,528 | 51.61% | 50.92% | +0.33% |
| 2022-2025 | Symmetric sideways filter D0.25 | SELL | 36,106 | 10,902 | 11,344 | 13,860 | 49.01% | 48.35% | -0.34% |
| 2022-2025 | Symmetric sideways filter D0.50 | BUY | 23,621 | 7,843 | 7,345 | 8,433 | 51.64% | 50.84% | +0.38% |
| 2022-2025 | Symmetric sideways filter D0.50 | SELL | 27,081 | 8,187 | 8,457 | 10,437 | 49.19% | 48.43% | -0.31% |
| 2019-2025 combined | Current production control | BUY | 63,267 | 21,776 | 19,456 | 22,035 | 52.81% | 52.33% | +0.48% |
| 2019-2025 combined | Current production control | SELL | 71,597 | 20,920 | 24,139 | 26,538 | 46.43% | 45.97% | -0.73% |
| 2019-2025 combined | Direction-optimized candidate | BUY | 72,756 | 25,535 | 21,009 | 26,212 | 54.86% | 54.41% | +0.77% |
| 2019-2025 combined | Direction-optimized candidate | SELL | 148,707 | 42,563 | 47,972 | 58,172 | 47.01% | 46.69% | -0.61% |
| 2019-2025 combined | Symmetric sideways filter D0.25 | BUY | 53,400 | 18,944 | 15,418 | 19,038 | 55.13% | 54.60% | +0.82% |
| 2019-2025 combined | Symmetric sideways filter D0.25 | SELL | 65,663 | 18,805 | 21,116 | 25,742 | 47.11% | 46.62% | -0.56% |
| 2019-2025 combined | Symmetric sideways filter D0.50 | BUY | 39,180 | 14,076 | 11,322 | 13,782 | 55.42% | 54.81% | +0.88% |
| 2019-2025 combined | Symmetric sideways filter D0.50 | SELL | 49,221 | 14,157 | 15,764 | 19,300 | 47.31% | 46.75% | -0.54% |

### Complete pattern breakdown (combined 2019-2025)

| Policy | Pattern | Direction | N | Actionable | Precision | Wilson LCB | Avg | Meets sample floor |
|---|---|---|---:|---:|---:|---:|---:|:---:|
| Current production control | BULLISH_ENGULFING | BUY | 19,941 | 13,069 | 52.01% | 51.15% | +0.38% | yes |
| Current production control | BEARISH_ENGULFING | SELL | 24,864 | 15,595 | 46.53% | 45.75% | -0.78% | yes |
| Current production control | HAMMER | BUY | 8,574 | 5,588 | 52.65% | 51.34% | +0.39% | yes |
| Current production control | INVERTED_HAMMER | BUY | 9,476 | 6,070 | 52.26% | 51.00% | +0.49% | yes |
| Current production control | HANGING_MAN | SELL | 10,725 | 6,772 | 46.97% | 45.79% | -0.60% | yes |
| Current production control | SHOOTING_STAR | SELL | 9,558 | 6,144 | 44.78% | 43.54% | -1.02% | yes |
| Current production control | MORNING_STAR | BUY | 6,718 | 4,381 | 52.02% | 50.54% | +0.34% | yes |
| Current production control | EVENING_STAR | SELL | 6,775 | 4,325 | 47.49% | 46.01% | -0.51% | yes |
| Current production control | PIERCING_LINE | BUY | 5,192 | 3,383 | 54.51% | 52.83% | +0.85% | yes |
| Current production control | DARK_CLOUD_COVER | SELL | 6,686 | 4,067 | 46.69% | 45.16% | -0.80% | yes |
| Current production control | BULLISH_HARAMI | BUY | 12,416 | 8,195 | 54.78% | 53.70% | +0.72% | yes |
| Current production control | BEARISH_HARAMI | SELL | 12,195 | 7,636 | 46.22% | 45.10% | -0.57% | yes |
| Current production control | THREE_WHITE_SOLDIERS | BUY | 950 | 546 | 46.34% | 42.19% | -0.67% | yes |
| Current production control | THREE_BLACK_CROWS | SELL | 794 | 520 | 48.08% | 43.81% | -0.69% | yes |
| Direction-optimized candidate | BULLISH_ENGULFING | BUY | 22,049 | 14,143 | 54.08% | 53.26% | +0.67% | yes |
| Direction-optimized candidate | BEARISH_ENGULFING | SELL | 51,587 | 31,398 | 46.90% | 46.35% | -0.63% | yes |
| Direction-optimized candidate | HAMMER | BUY | 10,231 | 6,490 | 54.99% | 53.78% | +0.81% | yes |
| Direction-optimized candidate | INVERTED_HAMMER | BUY | 11,018 | 7,059 | 54.00% | 52.84% | +0.58% | yes |
| Direction-optimized candidate | HANGING_MAN | SELL | 22,255 | 13,378 | 46.91% | 46.07% | -0.55% | yes |
| Direction-optimized candidate | SHOOTING_STAR | SELL | 19,103 | 11,951 | 45.75% | 44.85% | -0.97% | yes |
| Direction-optimized candidate | MORNING_STAR | BUY | 7,593 | 4,802 | 53.94% | 52.52% | +0.62% | yes |
| Direction-optimized candidate | EVENING_STAR | SELL | 14,674 | 9,009 | 46.84% | 45.81% | -0.58% | yes |
| Direction-optimized candidate | PIERCING_LINE | BUY | 5,858 | 3,761 | 56.45% | 54.86% | +1.06% | yes |
| Direction-optimized candidate | DARK_CLOUD_COVER | SELL | 14,288 | 8,559 | 48.21% | 47.15% | -0.51% | yes |
| Direction-optimized candidate | BULLISH_HARAMI | BUY | 15,339 | 9,866 | 56.77% | 55.79% | +1.05% | yes |
| Direction-optimized candidate | BEARISH_HARAMI | SELL | 25,116 | 15,196 | 47.93% | 47.13% | -0.39% | yes |
| Direction-optimized candidate | THREE_WHITE_SOLDIERS | BUY | 668 | 423 | 45.15% | 40.48% | -0.91% | no |
| Direction-optimized candidate | THREE_BLACK_CROWS | SELL | 1,684 | 1,044 | 44.44% | 41.46% | -1.14% | yes |
| Symmetric sideways filter D0.25 | BULLISH_ENGULFING | BUY | 16,148 | 10,453 | 54.54% | 53.58% | +0.79% | yes |
| Symmetric sideways filter D0.25 | BEARISH_ENGULFING | SELL | 22,573 | 13,654 | 46.57% | 45.73% | -0.60% | yes |
| Symmetric sideways filter D0.25 | HAMMER | BUY | 7,468 | 4,762 | 55.02% | 53.60% | +0.82% | yes |
| Symmetric sideways filter D0.25 | INVERTED_HAMMER | BUY | 8,212 | 5,300 | 54.19% | 52.84% | +0.60% | yes |
| Symmetric sideways filter D0.25 | HANGING_MAN | SELL | 9,693 | 5,806 | 47.43% | 46.15% | -0.42% | yes |
| Symmetric sideways filter D0.25 | SHOOTING_STAR | SELL | 8,434 | 5,351 | 45.69% | 44.36% | -1.00% | yes |
| Symmetric sideways filter D0.25 | MORNING_STAR | BUY | 5,600 | 3,558 | 54.47% | 52.83% | +0.69% | yes |
| Symmetric sideways filter D0.25 | EVENING_STAR | SELL | 6,676 | 4,063 | 47.08% | 45.55% | -0.45% | yes |
| Symmetric sideways filter D0.25 | PIERCING_LINE | BUY | 4,260 | 2,754 | 56.25% | 54.39% | +1.00% | yes |
| Symmetric sideways filter D0.25 | DARK_CLOUD_COVER | SELL | 6,265 | 3,771 | 48.40% | 46.80% | -0.46% | yes |
| Symmetric sideways filter D0.25 | BULLISH_HARAMI | BUY | 11,213 | 7,226 | 57.04% | 55.90% | +1.07% | yes |
| Symmetric sideways filter D0.25 | BEARISH_HARAMI | SELL | 11,250 | 6,782 | 48.69% | 47.50% | -0.31% | yes |
| Symmetric sideways filter D0.25 | THREE_WHITE_SOLDIERS | BUY | 499 | 309 | 45.95% | 40.48% | -0.76% | no |
| Symmetric sideways filter D0.25 | THREE_BLACK_CROWS | SELL | 772 | 494 | 42.11% | 37.83% | -1.82% | no |
| Symmetric sideways filter D0.50 | BULLISH_ENGULFING | BUY | 11,854 | 7,727 | 55.30% | 54.19% | +0.92% | yes |
| Symmetric sideways filter D0.50 | BEARISH_ENGULFING | SELL | 16,929 | 10,245 | 46.64% | 45.67% | -0.61% | yes |
| Symmetric sideways filter D0.50 | HAMMER | BUY | 5,495 | 3,507 | 54.95% | 53.30% | +0.88% | yes |
| Symmetric sideways filter D0.50 | INVERTED_HAMMER | BUY | 6,013 | 3,916 | 53.88% | 52.32% | +0.61% | yes |
| Symmetric sideways filter D0.50 | HANGING_MAN | SELL | 7,307 | 4,388 | 47.24% | 45.77% | -0.42% | yes |
| Symmetric sideways filter D0.50 | SHOOTING_STAR | SELL | 6,354 | 4,003 | 45.94% | 44.40% | -1.02% | yes |
| Symmetric sideways filter D0.50 | MORNING_STAR | BUY | 4,086 | 2,615 | 54.34% | 52.43% | +0.69% | yes |
| Symmetric sideways filter D0.50 | EVENING_STAR | SELL | 4,972 | 3,071 | 48.06% | 46.30% | -0.30% | yes |
| Symmetric sideways filter D0.50 | PIERCING_LINE | BUY | 3,061 | 1,994 | 57.42% | 55.24% | +1.10% | yes |
| Symmetric sideways filter D0.50 | DARK_CLOUD_COVER | SELL | 4,689 | 2,803 | 49.05% | 47.21% | -0.40% | yes |
| Symmetric sideways filter D0.50 | BULLISH_HARAMI | BUY | 8,310 | 5,412 | 57.24% | 55.92% | +1.09% | yes |
| Symmetric sideways filter D0.50 | BEARISH_HARAMI | SELL | 8,408 | 5,052 | 48.73% | 47.36% | -0.30% | yes |
| Symmetric sideways filter D0.50 | THREE_WHITE_SOLDIERS | BUY | 361 | 227 | 44.93% | 38.60% | -1.02% | no |
| Symmetric sideways filter D0.50 | THREE_BLACK_CROWS | SELL | 562 | 359 | 42.90% | 37.88% | -1.87% | no |

## Weekly results

Coverage: 2,217 symbols and 1,021,667 aggregated candles. Outcome: 8 candles / 8.0%.

### Policy totals

| Period | Policy | N | Actionable | S | F | I | Precision | Wilson LCB | Avg | Ticker precision | Tickers | BUY N | SELL N | Email eligible | Website-only |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 2019-2021 | Current production control | 3,511 | 2,229 | 942 | 1,287 | 1,282 | 42.26% | 40.23% | -2.15% | 40.21% | 1,520 | 1,083 | 2,428 | 3,511 | 0 |
| 2019-2021 | Pre-registered weekly hybrid | 4,845 | 2,802 | 1,278 | 1,524 | 2,043 | 45.61% | 43.77% | -1.98% | 45.95% | 1,831 | 2,417 | 2,428 | 4,845 | 0 |
| 2019-2021 | Count-preserving optimized candidate | 12,303 | 6,985 | 3,689 | 3,296 | 5,318 | 52.81% | 51.64% | +1.17% | 55.61% | 2,152 | 9,875 | 2,428 | 12,303 | 0 |
| 2019-2021 | Fit-only sideways-filtered candidate | 9,536 | 5,469 | 2,850 | 2,619 | 4,067 | 52.11% | 50.79% | +0.90% | 54.35% | 2,097 | 7,108 | 2,428 | 9,536 | 0 |
| 2019-2021 | Gentle sideways-filtered candidate | 8,836 | 5,090 | 2,619 | 2,471 | 3,746 | 51.45% | 50.08% | +0.42% | 53.17% | 2,077 | 6,408 | 2,428 | 8,836 | 0 |
| 2019-2021 | Light sideways-filtered candidate | 6,603 | 3,886 | 1,916 | 1,970 | 2,717 | 49.31% | 47.73% | -0.46% | 49.92% | 1,951 | 4,175 | 2,428 | 6,603 | 0 |
| 2019-2021 | Sideways-filtered candidate | 4,824 | 2,845 | 1,316 | 1,529 | 1,979 | 46.26% | 44.43% | -1.77% | 45.38% | 1,773 | 2,396 | 2,428 | 4,824 | 0 |
| 2019-2021 | Symmetric sideways filter B15 D0.25 | 4,268 | 2,257 | 1,047 | 1,210 | 2,011 | 46.39% | 44.34% | -1.23% | 45.54% | 1,790 | 1,488 | 2,780 | 4,268 | 0 |
| 2019-2021 | Symmetric sideways filter B30 D0.25 | 11,547 | 6,078 | 2,885 | 3,193 | 5,469 | 47.47% | 46.21% | -0.96% | 46.91% | 2,171 | 4,039 | 7,508 | 11,547 | 0 |
| 2022-2025 | Current production control | 3,970 | 2,590 | 1,345 | 1,245 | 1,380 | 51.93% | 50.00% | +1.52% | 51.55% | 1,552 | 1,676 | 2,294 | 3,970 | 0 |
| 2022-2025 | Pre-registered weekly hybrid | 6,820 | 3,872 | 1,982 | 1,890 | 2,948 | 51.19% | 49.61% | +0.49% | 52.22% | 2,038 | 4,526 | 2,294 | 6,820 | 0 |
| 2022-2025 | Count-preserving optimized candidate | 18,209 | 10,419 | 5,619 | 4,800 | 7,790 | 53.93% | 52.97% | +1.60% | 57.27% | 2,205 | 15,915 | 2,294 | 18,209 | 0 |
| 2022-2025 | Fit-only sideways-filtered candidate | 13,992 | 8,157 | 4,373 | 3,784 | 5,835 | 53.61% | 52.53% | +1.50% | 56.99% | 2,175 | 11,698 | 2,294 | 13,992 | 0 |
| 2022-2025 | Gentle sideways-filtered candidate | 12,813 | 7,485 | 4,027 | 3,458 | 5,328 | 53.80% | 52.67% | +1.42% | 56.91% | 2,162 | 10,519 | 2,294 | 12,813 | 0 |
| 2022-2025 | Light sideways-filtered candidate | 8,877 | 5,271 | 2,789 | 2,482 | 3,606 | 52.91% | 51.56% | +1.03% | 55.10% | 2,038 | 6,583 | 2,294 | 8,877 | 0 |
| 2022-2025 | Sideways-filtered candidate | 5,755 | 3,460 | 1,791 | 1,669 | 2,295 | 51.76% | 50.10% | +0.38% | 53.13% | 1,837 | 3,461 | 2,294 | 5,755 | 0 |
| 2022-2025 | Symmetric sideways filter B15 D0.25 | 6,088 | 3,197 | 1,655 | 1,542 | 2,891 | 51.77% | 50.03% | +0.39% | 52.43% | 2,008 | 3,025 | 3,063 | 6,088 | 0 |
| 2022-2025 | Symmetric sideways filter B30 D0.25 | 15,697 | 8,265 | 4,271 | 3,994 | 7,432 | 51.68% | 50.60% | +0.23% | 51.90% | 2,208 | 7,206 | 8,491 | 15,697 | 0 |
| 2019-2025 combined | Current production control | 7,481 | 4,819 | 2,287 | 2,532 | 2,662 | 47.46% | 46.05% | -0.21% | 46.54% | 1,895 | 2,759 | 4,722 | 7,481 | 0 |
| 2019-2025 combined | Pre-registered weekly hybrid | 11,665 | 6,674 | 3,260 | 3,414 | 4,991 | 48.85% | 47.65% | -0.54% | 50.44% | 2,171 | 6,943 | 4,722 | 11,665 | 0 |
| 2019-2025 combined | Count-preserving optimized candidate | 30,512 | 17,404 | 9,308 | 8,096 | 13,108 | 53.48% | 52.74% | +1.42% | 56.66% | 2,213 | 25,790 | 4,722 | 30,512 | 0 |
| 2019-2025 combined | Fit-only sideways-filtered candidate | 23,528 | 13,626 | 7,223 | 6,403 | 9,902 | 53.01% | 52.17% | +1.26% | 56.19% | 2,210 | 18,806 | 4,722 | 23,528 | 0 |
| 2019-2025 combined | Gentle sideways-filtered candidate | 21,649 | 12,575 | 6,646 | 5,929 | 9,074 | 52.85% | 51.98% | +1.01% | 55.62% | 2,209 | 16,927 | 4,722 | 21,649 | 0 |
| 2019-2025 combined | Light sideways-filtered candidate | 15,480 | 9,157 | 4,705 | 4,452 | 6,323 | 51.38% | 50.36% | +0.39% | 53.44% | 2,169 | 10,758 | 4,722 | 15,480 | 0 |
| 2019-2025 combined | Sideways-filtered candidate | 10,579 | 6,305 | 3,107 | 3,198 | 4,274 | 49.28% | 48.05% | -0.60% | 50.19% | 2,098 | 5,857 | 4,722 | 10,579 | 0 |
| 2019-2025 combined | Symmetric sideways filter B15 D0.25 | 10,356 | 5,454 | 2,702 | 2,752 | 4,902 | 49.54% | 48.22% | -0.28% | 49.72% | 2,170 | 4,513 | 5,843 | 10,356 | 0 |
| 2019-2025 combined | Symmetric sideways filter B30 D0.25 | 27,244 | 14,343 | 7,156 | 7,187 | 12,901 | 49.89% | 49.07% | -0.28% | 49.70% | 2,213 | 11,245 | 15,999 | 27,244 | 0 |

### Direction breakdown

| Period | Policy | Direction | N | S | F | I | Precision | Wilson LCB | Avg |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 2019-2021 | Current production control | BUY | 1,083 | 454 | 275 | 354 | 62.28% | 58.70% | +8.40% |
| 2019-2021 | Current production control | SELL | 2,428 | 488 | 1,012 | 928 | 32.53% | 30.21% | -6.86% |
| 2019-2021 | Pre-registered weekly hybrid | BUY | 2,417 | 790 | 512 | 1,115 | 60.68% | 57.99% | +2.92% |
| 2019-2021 | Pre-registered weekly hybrid | SELL | 2,428 | 488 | 1,012 | 928 | 32.53% | 30.21% | -6.86% |
| 2019-2021 | Count-preserving optimized candidate | BUY | 9,875 | 3,201 | 2,284 | 4,390 | 58.36% | 57.05% | +3.14% |
| 2019-2021 | Count-preserving optimized candidate | SELL | 2,428 | 488 | 1,012 | 928 | 32.53% | 30.21% | -6.86% |
| 2019-2021 | Fit-only sideways-filtered candidate | BUY | 7,108 | 2,362 | 1,607 | 3,139 | 59.51% | 57.98% | +3.56% |
| 2019-2021 | Fit-only sideways-filtered candidate | SELL | 2,428 | 488 | 1,012 | 928 | 32.53% | 30.21% | -6.86% |
| 2019-2021 | Gentle sideways-filtered candidate | BUY | 6,408 | 2,131 | 1,459 | 2,818 | 59.36% | 57.74% | +3.18% |
| 2019-2021 | Gentle sideways-filtered candidate | SELL | 2,428 | 488 | 1,012 | 928 | 32.53% | 30.21% | -6.86% |
| 2019-2021 | Light sideways-filtered candidate | BUY | 4,175 | 1,428 | 958 | 1,789 | 59.85% | 57.87% | +3.27% |
| 2019-2021 | Light sideways-filtered candidate | SELL | 2,428 | 488 | 1,012 | 928 | 32.53% | 30.21% | -6.86% |
| 2019-2021 | Sideways-filtered candidate | BUY | 2,396 | 828 | 517 | 1,051 | 61.56% | 58.93% | +3.38% |
| 2019-2021 | Sideways-filtered candidate | SELL | 2,428 | 488 | 1,012 | 928 | 32.53% | 30.21% | -6.86% |
| 2019-2021 | Symmetric sideways filter B15 D0.25 | BUY | 1,488 | 508 | 302 | 678 | 62.72% | 59.33% | +3.38% |
| 2019-2021 | Symmetric sideways filter B15 D0.25 | SELL | 2,780 | 539 | 908 | 1,333 | 37.25% | 34.80% | -3.70% |
| 2019-2021 | Symmetric sideways filter B30 D0.25 | BUY | 4,039 | 1,339 | 854 | 1,846 | 61.06% | 59.00% | +3.40% |
| 2019-2021 | Symmetric sideways filter B30 D0.25 | SELL | 7,508 | 1,546 | 2,339 | 3,623 | 39.79% | 38.27% | -3.31% |
| 2022-2025 | Current production control | BUY | 1,676 | 654 | 522 | 500 | 55.61% | 52.76% | +5.32% |
| 2022-2025 | Current production control | SELL | 2,294 | 691 | 723 | 880 | 48.87% | 46.27% | -1.26% |
| 2022-2025 | Pre-registered weekly hybrid | BUY | 4,526 | 1,291 | 1,167 | 2,068 | 52.52% | 50.55% | +1.38% |
| 2022-2025 | Pre-registered weekly hybrid | SELL | 2,294 | 691 | 723 | 880 | 48.87% | 46.27% | -1.26% |
| 2022-2025 | Count-preserving optimized candidate | BUY | 15,915 | 4,928 | 4,077 | 6,910 | 54.73% | 53.70% | +2.01% |
| 2022-2025 | Count-preserving optimized candidate | SELL | 2,294 | 691 | 723 | 880 | 48.87% | 46.27% | -1.26% |
| 2022-2025 | Fit-only sideways-filtered candidate | BUY | 11,698 | 3,682 | 3,061 | 4,955 | 54.60% | 53.41% | +2.04% |
| 2022-2025 | Fit-only sideways-filtered candidate | SELL | 2,294 | 691 | 723 | 880 | 48.87% | 46.27% | -1.26% |
| 2022-2025 | Gentle sideways-filtered candidate | BUY | 10,519 | 3,336 | 2,735 | 4,448 | 54.95% | 53.70% | +2.00% |
| 2022-2025 | Gentle sideways-filtered candidate | SELL | 2,294 | 691 | 723 | 880 | 48.87% | 46.27% | -1.26% |
| 2022-2025 | Light sideways-filtered candidate | BUY | 6,583 | 2,098 | 1,759 | 2,726 | 54.39% | 52.82% | +1.82% |
| 2022-2025 | Light sideways-filtered candidate | SELL | 2,294 | 691 | 723 | 880 | 48.87% | 46.27% | -1.26% |
| 2022-2025 | Sideways-filtered candidate | BUY | 3,461 | 1,100 | 946 | 1,415 | 53.76% | 51.60% | +1.47% |
| 2022-2025 | Sideways-filtered candidate | SELL | 2,294 | 691 | 723 | 880 | 48.87% | 46.27% | -1.26% |
| 2022-2025 | Symmetric sideways filter B15 D0.25 | BUY | 3,025 | 894 | 775 | 1,356 | 53.57% | 51.17% | +1.48% |
| 2022-2025 | Symmetric sideways filter B15 D0.25 | SELL | 3,063 | 761 | 767 | 1,535 | 49.80% | 47.30% | -0.69% |
| 2022-2025 | Symmetric sideways filter B30 D0.25 | BUY | 7,206 | 2,211 | 1,798 | 3,197 | 55.15% | 53.61% | +1.66% |
| 2022-2025 | Symmetric sideways filter B30 D0.25 | SELL | 8,491 | 2,060 | 2,196 | 4,235 | 48.40% | 46.90% | -0.98% |
| 2019-2025 combined | Current production control | BUY | 2,759 | 1,108 | 797 | 854 | 58.16% | 55.93% | +6.53% |
| 2019-2025 combined | Current production control | SELL | 4,722 | 1,179 | 1,735 | 1,808 | 40.46% | 38.69% | -4.14% |
| 2019-2025 combined | Pre-registered weekly hybrid | BUY | 6,943 | 2,081 | 1,679 | 3,183 | 55.35% | 53.75% | +1.91% |
| 2019-2025 combined | Pre-registered weekly hybrid | SELL | 4,722 | 1,179 | 1,735 | 1,808 | 40.46% | 38.69% | -4.14% |
| 2019-2025 combined | Count-preserving optimized candidate | BUY | 25,790 | 8,129 | 6,361 | 11,300 | 56.10% | 55.29% | +2.44% |
| 2019-2025 combined | Count-preserving optimized candidate | SELL | 4,722 | 1,179 | 1,735 | 1,808 | 40.46% | 38.69% | -4.14% |
| 2019-2025 combined | Fit-only sideways-filtered candidate | BUY | 18,806 | 6,044 | 4,668 | 8,094 | 56.42% | 55.48% | +2.61% |
| 2019-2025 combined | Fit-only sideways-filtered candidate | SELL | 4,722 | 1,179 | 1,735 | 1,808 | 40.46% | 38.69% | -4.14% |
| 2019-2025 combined | Gentle sideways-filtered candidate | BUY | 16,927 | 5,467 | 4,194 | 7,266 | 56.59% | 55.60% | +2.45% |
| 2019-2025 combined | Gentle sideways-filtered candidate | SELL | 4,722 | 1,179 | 1,735 | 1,808 | 40.46% | 38.69% | -4.14% |
| 2019-2025 combined | Light sideways-filtered candidate | BUY | 10,758 | 3,526 | 2,717 | 4,515 | 56.48% | 55.25% | +2.38% |
| 2019-2025 combined | Light sideways-filtered candidate | SELL | 4,722 | 1,179 | 1,735 | 1,808 | 40.46% | 38.69% | -4.14% |
| 2019-2025 combined | Sideways-filtered candidate | BUY | 5,857 | 1,928 | 1,463 | 2,466 | 56.86% | 55.18% | +2.25% |
| 2019-2025 combined | Sideways-filtered candidate | SELL | 4,722 | 1,179 | 1,735 | 1,808 | 40.46% | 38.69% | -4.14% |
| 2019-2025 combined | Symmetric sideways filter B15 D0.25 | BUY | 4,513 | 1,402 | 1,077 | 2,034 | 56.56% | 54.60% | +2.11% |
| 2019-2025 combined | Symmetric sideways filter B15 D0.25 | SELL | 5,843 | 1,300 | 1,675 | 2,868 | 43.70% | 41.92% | -2.12% |
| 2019-2025 combined | Symmetric sideways filter B30 D0.25 | BUY | 11,245 | 3,550 | 2,652 | 5,043 | 57.24% | 56.00% | +2.29% |
| 2019-2025 combined | Symmetric sideways filter B30 D0.25 | SELL | 15,999 | 3,606 | 4,535 | 7,858 | 44.29% | 43.22% | -2.08% |

### Complete pattern breakdown (combined 2019-2025)

| Policy | Pattern | Direction | N | Actionable | Precision | Wilson LCB | Avg | Meets sample floor |
|---|---|---|---:|---:|---:|---:|---:|:---:|
| Current production control | BULLISH_ENGULFING | BUY | 1,042 | 732 | 58.06% | 54.45% | +6.60% | yes |
| Current production control | BEARISH_ENGULFING | SELL | 1,785 | 1,154 | 39.08% | 36.31% | -5.10% | yes |
| Current production control | HAMMER | BUY | 324 | 203 | 59.11% | 52.24% | +5.98% | yes |
| Current production control | INVERTED_HAMMER | BUY | 429 | 291 | 53.61% | 47.87% | +4.93% | yes |
| Current production control | HANGING_MAN | SELL | 619 | 353 | 36.83% | 31.96% | -4.12% | yes |
| Current production control | SHOOTING_STAR | SELL | 530 | 308 | 47.08% | 41.57% | -2.44% | yes |
| Current production control | MORNING_STAR | BUY | 252 | 180 | 61.67% | 54.39% | +7.59% | no |
| Current production control | EVENING_STAR | SELL | 419 | 265 | 38.87% | 33.20% | -3.99% | yes |
| Current production control | PIERCING_LINE | BUY | 185 | 134 | 64.18% | 55.77% | +9.64% | no |
| Current production control | DARK_CLOUD_COVER | SELL | 563 | 356 | 39.04% | 34.12% | -5.08% | yes |
| Current production control | BULLISH_HARAMI | BUY | 488 | 343 | 55.98% | 50.69% | +6.18% | yes |
| Current production control | BEARISH_HARAMI | SELL | 768 | 453 | 43.93% | 39.43% | -2.45% | yes |
| Current production control | THREE_WHITE_SOLDIERS | BUY | 39 | 22 | 81.82% | 61.48% | +9.64% | no |
| Current production control | THREE_BLACK_CROWS | SELL | 38 | 25 | 48.00% | 30.03% | -5.25% | no |
| Pre-registered weekly hybrid | BULLISH_ENGULFING | BUY | 2,467 | 1,365 | 55.75% | 53.10% | +1.94% | yes |
| Pre-registered weekly hybrid | BEARISH_ENGULFING | SELL | 1,785 | 1,154 | 39.08% | 36.31% | -5.10% | yes |
| Pre-registered weekly hybrid | HAMMER | BUY | 913 | 469 | 58.85% | 54.34% | +2.34% | yes |
| Pre-registered weekly hybrid | INVERTED_HAMMER | BUY | 867 | 486 | 53.50% | 49.05% | +1.13% | yes |
| Pre-registered weekly hybrid | HANGING_MAN | SELL | 619 | 353 | 36.83% | 31.96% | -4.12% | yes |
| Pre-registered weekly hybrid | SHOOTING_STAR | SELL | 530 | 308 | 47.08% | 41.57% | -2.44% | yes |
| Pre-registered weekly hybrid | MORNING_STAR | BUY | 642 | 358 | 47.77% | 42.64% | +0.28% | yes |
| Pre-registered weekly hybrid | EVENING_STAR | SELL | 419 | 265 | 38.87% | 33.20% | -3.99% | yes |
| Pre-registered weekly hybrid | PIERCING_LINE | BUY | 575 | 300 | 56.00% | 50.34% | +2.18% | yes |
| Pre-registered weekly hybrid | DARK_CLOUD_COVER | SELL | 563 | 356 | 39.04% | 34.12% | -5.08% | yes |
| Pre-registered weekly hybrid | BULLISH_HARAMI | BUY | 1,344 | 715 | 55.94% | 52.28% | +2.56% | yes |
| Pre-registered weekly hybrid | BEARISH_HARAMI | SELL | 768 | 453 | 43.93% | 39.43% | -2.45% | yes |
| Pre-registered weekly hybrid | THREE_WHITE_SOLDIERS | BUY | 135 | 67 | 67.16% | 55.26% | +3.64% | no |
| Pre-registered weekly hybrid | THREE_BLACK_CROWS | SELL | 38 | 25 | 48.00% | 30.03% | -5.25% | no |
| Count-preserving optimized candidate | BULLISH_ENGULFING | BUY | 9,181 | 5,252 | 53.66% | 52.30% | +1.91% | yes |
| Count-preserving optimized candidate | BEARISH_ENGULFING | SELL | 1,785 | 1,154 | 39.08% | 36.31% | -5.10% | yes |
| Count-preserving optimized candidate | HAMMER | BUY | 3,409 | 1,814 | 58.05% | 55.76% | +2.38% | yes |
| Count-preserving optimized candidate | INVERTED_HAMMER | BUY | 3,520 | 2,024 | 51.33% | 49.16% | +1.51% | yes |
| Count-preserving optimized candidate | HANGING_MAN | SELL | 619 | 353 | 36.83% | 31.96% | -4.12% | yes |
| Count-preserving optimized candidate | SHOOTING_STAR | SELL | 530 | 308 | 47.08% | 41.57% | -2.44% | yes |
| Count-preserving optimized candidate | MORNING_STAR | BUY | 2,254 | 1,325 | 53.51% | 50.82% | +2.08% | yes |
| Count-preserving optimized candidate | EVENING_STAR | SELL | 419 | 265 | 38.87% | 33.20% | -3.99% | yes |
| Count-preserving optimized candidate | PIERCING_LINE | BUY | 1,962 | 1,050 | 59.33% | 56.33% | +2.89% | yes |
| Count-preserving optimized candidate | DARK_CLOUD_COVER | SELL | 563 | 356 | 39.04% | 34.12% | -5.08% | yes |
| Count-preserving optimized candidate | BULLISH_HARAMI | BUY | 4,978 | 2,801 | 62.19% | 60.38% | +4.11% | yes |
| Count-preserving optimized candidate | BEARISH_HARAMI | SELL | 768 | 453 | 43.93% | 39.43% | -2.45% | yes |
| Count-preserving optimized candidate | THREE_WHITE_SOLDIERS | BUY | 486 | 224 | 64.73% | 58.27% | +2.59% | yes |
| Count-preserving optimized candidate | THREE_BLACK_CROWS | SELL | 38 | 25 | 48.00% | 30.03% | -5.25% | no |
| Fit-only sideways-filtered candidate | BULLISH_ENGULFING | BUY | 6,683 | 3,869 | 53.37% | 51.80% | +1.94% | yes |
| Fit-only sideways-filtered candidate | BEARISH_ENGULFING | SELL | 1,785 | 1,154 | 39.08% | 36.31% | -5.10% | yes |
| Fit-only sideways-filtered candidate | HAMMER | BUY | 2,461 | 1,353 | 59.13% | 56.49% | +2.70% | yes |
| Fit-only sideways-filtered candidate | INVERTED_HAMMER | BUY | 2,664 | 1,558 | 51.73% | 49.25% | +1.67% | yes |
| Fit-only sideways-filtered candidate | HANGING_MAN | SELL | 619 | 353 | 36.83% | 31.96% | -4.12% | yes |
| Fit-only sideways-filtered candidate | SHOOTING_STAR | SELL | 530 | 308 | 47.08% | 41.57% | -2.44% | yes |
| Fit-only sideways-filtered candidate | MORNING_STAR | BUY | 1,643 | 970 | 54.02% | 50.87% | +2.34% | yes |
| Fit-only sideways-filtered candidate | EVENING_STAR | SELL | 419 | 265 | 38.87% | 33.20% | -3.99% | yes |
| Fit-only sideways-filtered candidate | PIERCING_LINE | BUY | 1,396 | 754 | 60.74% | 57.21% | +3.46% | yes |
| Fit-only sideways-filtered candidate | DARK_CLOUD_COVER | SELL | 563 | 356 | 39.04% | 34.12% | -5.08% | yes |
| Fit-only sideways-filtered candidate | BULLISH_HARAMI | BUY | 3,574 | 2,033 | 63.06% | 60.94% | +4.33% | yes |
| Fit-only sideways-filtered candidate | BEARISH_HARAMI | SELL | 768 | 453 | 43.93% | 39.43% | -2.45% | yes |
| Fit-only sideways-filtered candidate | THREE_WHITE_SOLDIERS | BUY | 385 | 175 | 62.29% | 54.91% | +2.33% | no |
| Fit-only sideways-filtered candidate | THREE_BLACK_CROWS | SELL | 38 | 25 | 48.00% | 30.03% | -5.25% | no |
| Gentle sideways-filtered candidate | BULLISH_ENGULFING | BUY | 6,007 | 3,495 | 53.71% | 52.05% | +1.82% | yes |
| Gentle sideways-filtered candidate | BEARISH_ENGULFING | SELL | 1,785 | 1,154 | 39.08% | 36.31% | -5.10% | yes |
| Gentle sideways-filtered candidate | HAMMER | BUY | 2,236 | 1,243 | 59.94% | 57.18% | +2.93% | yes |
| Gentle sideways-filtered candidate | INVERTED_HAMMER | BUY | 2,418 | 1,413 | 51.52% | 48.92% | +1.38% | yes |
| Gentle sideways-filtered candidate | HANGING_MAN | SELL | 619 | 353 | 36.83% | 31.96% | -4.12% | yes |
| Gentle sideways-filtered candidate | SHOOTING_STAR | SELL | 530 | 308 | 47.08% | 41.57% | -2.44% | yes |
| Gentle sideways-filtered candidate | MORNING_STAR | BUY | 1,482 | 873 | 53.15% | 49.83% | +1.59% | yes |
| Gentle sideways-filtered candidate | EVENING_STAR | SELL | 419 | 265 | 38.87% | 33.20% | -3.99% | yes |
| Gentle sideways-filtered candidate | PIERCING_LINE | BUY | 1,243 | 669 | 61.58% | 57.84% | +3.64% | yes |
| Gentle sideways-filtered candidate | DARK_CLOUD_COVER | SELL | 563 | 356 | 39.04% | 34.12% | -5.08% | yes |
| Gentle sideways-filtered candidate | BULLISH_HARAMI | BUY | 3,180 | 1,803 | 63.12% | 60.86% | +4.05% | yes |
| Gentle sideways-filtered candidate | BEARISH_HARAMI | SELL | 768 | 453 | 43.93% | 39.43% | -2.45% | yes |
| Gentle sideways-filtered candidate | THREE_WHITE_SOLDIERS | BUY | 361 | 165 | 62.42% | 54.83% | +2.33% | no |
| Gentle sideways-filtered candidate | THREE_BLACK_CROWS | SELL | 38 | 25 | 48.00% | 30.03% | -5.25% | no |
| Light sideways-filtered candidate | BULLISH_ENGULFING | BUY | 3,802 | 2,283 | 53.13% | 51.08% | +1.74% | yes |
| Light sideways-filtered candidate | BEARISH_ENGULFING | SELL | 1,785 | 1,154 | 39.08% | 36.31% | -5.10% | yes |
| Light sideways-filtered candidate | HAMMER | BUY | 1,433 | 805 | 59.50% | 56.07% | +3.06% | yes |
| Light sideways-filtered candidate | INVERTED_HAMMER | BUY | 1,641 | 976 | 51.23% | 48.09% | +0.98% | yes |
| Light sideways-filtered candidate | HANGING_MAN | SELL | 619 | 353 | 36.83% | 31.96% | -4.12% | yes |
| Light sideways-filtered candidate | SHOOTING_STAR | SELL | 530 | 308 | 47.08% | 41.57% | -2.44% | yes |
| Light sideways-filtered candidate | MORNING_STAR | BUY | 916 | 545 | 52.84% | 48.65% | +1.18% | yes |
| Light sideways-filtered candidate | EVENING_STAR | SELL | 419 | 265 | 38.87% | 33.20% | -3.99% | yes |
| Light sideways-filtered candidate | PIERCING_LINE | BUY | 755 | 400 | 62.25% | 57.40% | +3.46% | yes |
| Light sideways-filtered candidate | DARK_CLOUD_COVER | SELL | 563 | 356 | 39.04% | 34.12% | -5.08% | yes |
| Light sideways-filtered candidate | BULLISH_HARAMI | BUY | 1,947 | 1,119 | 65.24% | 62.40% | +4.57% | yes |
| Light sideways-filtered candidate | BEARISH_HARAMI | SELL | 768 | 453 | 43.93% | 39.43% | -2.45% | yes |
| Light sideways-filtered candidate | THREE_WHITE_SOLDIERS | BUY | 264 | 115 | 58.26% | 49.12% | +1.73% | no |
| Light sideways-filtered candidate | THREE_BLACK_CROWS | SELL | 38 | 25 | 48.00% | 30.03% | -5.25% | no |
| Sideways-filtered candidate | BULLISH_ENGULFING | BUY | 2,039 | 1,232 | 51.87% | 49.08% | +1.23% | yes |
| Sideways-filtered candidate | BEARISH_ENGULFING | SELL | 1,785 | 1,154 | 39.08% | 36.31% | -5.10% | yes |
| Sideways-filtered candidate | HAMMER | BUY | 791 | 436 | 64.45% | 59.85% | +3.53% | yes |
| Sideways-filtered candidate | INVERTED_HAMMER | BUY | 995 | 583 | 50.09% | 46.04% | +0.85% | yes |
| Sideways-filtered candidate | HANGING_MAN | SELL | 619 | 353 | 36.83% | 31.96% | -4.12% | yes |
| Sideways-filtered candidate | SHOOTING_STAR | SELL | 530 | 308 | 47.08% | 41.57% | -2.44% | yes |
| Sideways-filtered candidate | MORNING_STAR | BUY | 474 | 283 | 54.42% | 48.59% | +1.53% | yes |
| Sideways-filtered candidate | EVENING_STAR | SELL | 419 | 265 | 38.87% | 33.20% | -3.99% | yes |
| Sideways-filtered candidate | PIERCING_LINE | BUY | 389 | 205 | 63.41% | 56.63% | +3.13% | yes |
| Sideways-filtered candidate | DARK_CLOUD_COVER | SELL | 563 | 356 | 39.04% | 34.12% | -5.08% | yes |
| Sideways-filtered candidate | BULLISH_HARAMI | BUY | 1,019 | 592 | 67.06% | 63.18% | +4.70% | yes |
| Sideways-filtered candidate | BEARISH_HARAMI | SELL | 768 | 453 | 43.93% | 39.43% | -2.45% | yes |
| Sideways-filtered candidate | THREE_WHITE_SOLDIERS | BUY | 150 | 60 | 58.33% | 45.73% | +2.13% | no |
| Sideways-filtered candidate | THREE_BLACK_CROWS | SELL | 38 | 25 | 48.00% | 30.03% | -5.25% | no |
| Symmetric sideways filter B15 D0.25 | BULLISH_ENGULFING | BUY | 1,639 | 915 | 56.28% | 53.05% | +2.26% | yes |
| Symmetric sideways filter B15 D0.25 | BEARISH_ENGULFING | SELL | 2,157 | 1,113 | 41.06% | 38.21% | -2.88% | yes |
| Symmetric sideways filter B15 D0.25 | HAMMER | BUY | 616 | 324 | 60.19% | 54.77% | +2.30% | yes |
| Symmetric sideways filter B15 D0.25 | INVERTED_HAMMER | BUY | 586 | 322 | 54.66% | 49.20% | +0.94% | yes |
| Symmetric sideways filter B15 D0.25 | HANGING_MAN | SELL | 826 | 400 | 39.00% | 34.35% | -2.66% | yes |
| Symmetric sideways filter B15 D0.25 | SHOOTING_STAR | SELL | 653 | 361 | 45.43% | 40.37% | -1.86% | yes |
| Symmetric sideways filter B15 D0.25 | MORNING_STAR | BUY | 401 | 232 | 51.29% | 44.89% | +0.88% | yes |
| Symmetric sideways filter B15 D0.25 | EVENING_STAR | SELL | 585 | 315 | 46.98% | 41.54% | -0.52% | yes |
| Symmetric sideways filter B15 D0.25 | PIERCING_LINE | BUY | 373 | 201 | 54.23% | 47.33% | +2.26% | yes |
| Symmetric sideways filter B15 D0.25 | DARK_CLOUD_COVER | SELL | 603 | 304 | 44.08% | 38.61% | -2.77% | yes |
| Symmetric sideways filter B15 D0.25 | BULLISH_HARAMI | BUY | 799 | 440 | 58.64% | 53.98% | +2.90% | yes |
| Symmetric sideways filter B15 D0.25 | BEARISH_HARAMI | SELL | 980 | 456 | 50.22% | 45.65% | -0.38% | yes |
| Symmetric sideways filter B15 D0.25 | THREE_WHITE_SOLDIERS | BUY | 99 | 45 | 66.67% | 52.07% | +3.35% | no |
| Symmetric sideways filter B15 D0.25 | THREE_BLACK_CROWS | SELL | 39 | 26 | 46.15% | 28.76% | -10.76% | no |
| Symmetric sideways filter B30 D0.25 | BULLISH_ENGULFING | BUY | 4,099 | 2,335 | 55.93% | 53.91% | +2.01% | yes |
| Symmetric sideways filter B30 D0.25 | BEARISH_ENGULFING | SELL | 6,037 | 3,072 | 43.55% | 41.81% | -2.64% | yes |
| Symmetric sideways filter B30 D0.25 | HAMMER | BUY | 1,502 | 790 | 60.25% | 56.80% | +3.13% | yes |
| Symmetric sideways filter B30 D0.25 | INVERTED_HAMMER | BUY | 1,524 | 864 | 52.78% | 49.44% | +0.97% | yes |
| Symmetric sideways filter B30 D0.25 | HANGING_MAN | SELL | 2,205 | 1,051 | 40.34% | 37.42% | -2.55% | yes |
| Symmetric sideways filter B30 D0.25 | SHOOTING_STAR | SELL | 1,614 | 927 | 45.31% | 42.13% | -1.95% | yes |
| Symmetric sideways filter B30 D0.25 | MORNING_STAR | BUY | 977 | 546 | 51.28% | 47.10% | +0.78% | yes |
| Symmetric sideways filter B30 D0.25 | EVENING_STAR | SELL | 1,721 | 861 | 46.81% | 43.49% | -0.62% | yes |
| Symmetric sideways filter B30 D0.25 | PIERCING_LINE | BUY | 857 | 454 | 60.13% | 55.56% | +3.11% | yes |
| Symmetric sideways filter B30 D0.25 | DARK_CLOUD_COVER | SELL | 1,584 | 820 | 41.22% | 37.90% | -3.32% | yes |
| Symmetric sideways filter B30 D0.25 | BULLISH_HARAMI | BUY | 2,014 | 1,102 | 62.52% | 59.63% | +3.58% | yes |
| Symmetric sideways filter B30 D0.25 | BEARISH_HARAMI | SELL | 2,700 | 1,336 | 48.28% | 45.61% | -0.69% | yes |
| Symmetric sideways filter B30 D0.25 | THREE_WHITE_SOLDIERS | BUY | 272 | 111 | 63.06% | 53.79% | +2.49% | no |
| Symmetric sideways filter B30 D0.25 | THREE_BLACK_CROWS | SELL | 138 | 74 | 51.35% | 40.18% | -2.23% | no |

## Monthly results

Coverage: 2,204 symbols and 235,793 aggregated candles. Outcome: 6 candles / 12.0%.

### Policy totals

| Period | Policy | N | Actionable | S | F | I | Precision | Wilson LCB | Avg | Ticker precision | Tickers | BUY N | SELL N | Email eligible | Website-only |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 2019-2021 | Current production control | 2,535 | 1,657 | 712 | 945 | 878 | 42.97% | 40.60% | -5.41% | 43.33% | 1,540 | 569 | 1,966 | 569 | 1,966 |
| 2019-2021 | Direction-optimized candidate | 3,400 | 2,275 | 1,224 | 1,051 | 1,125 | 53.80% | 51.75% | +4.09% | 52.77% | 1,716 | 1,434 | 1,966 | 1,434 | 1,966 |
| 2019-2021 | Fit-only sideways-filtered candidate | 2,979 | 1,995 | 1,033 | 962 | 984 | 51.78% | 49.59% | +2.53% | 50.98% | 1,623 | 1,013 | 1,966 | 1,013 | 1,966 |
| 2019-2021 | Gentle sideways-filtered candidate | 2,872 | 1,918 | 977 | 941 | 954 | 50.94% | 48.70% | +1.91% | 50.20% | 1,592 | 906 | 1,966 | 906 | 1,966 |
| 2019-2021 | Light sideways-filtered candidate | 2,526 | 1,679 | 812 | 867 | 847 | 48.36% | 45.98% | -0.42% | 47.78% | 1,487 | 560 | 1,966 | 560 | 1,966 |
| 2019-2021 | Sideways-filtered candidate | 2,242 | 1,474 | 654 | 820 | 768 | 44.37% | 41.85% | -3.84% | 44.78% | 1,412 | 276 | 1,966 | 276 | 1,966 |
| 2019-2021 | Symmetric sideways filter B15 D0.25 | 725 | 510 | 335 | 175 | 215 | 65.69% | 61.46% | +11.88% | 66.67% | 543 | 311 | 414 | 311 | 414 |
| 2019-2021 | Symmetric sideways filter B30 D0.25 | 1,949 | 1,302 | 767 | 535 | 647 | 58.91% | 56.21% | +7.55% | 58.92% | 1,226 | 692 | 1,257 | 692 | 1,257 |
| 2022-2025 | Current production control | 4,166 | 2,430 | 1,035 | 1,395 | 1,736 | 42.59% | 40.64% | -2.33% | 43.34% | 1,888 | 1,859 | 2,307 | 1,859 | 2,307 |
| 2022-2025 | Direction-optimized candidate | 5,157 | 3,066 | 1,429 | 1,637 | 2,091 | 46.61% | 44.85% | -0.36% | 46.45% | 1,992 | 2,850 | 2,307 | 2,850 | 2,307 |
| 2022-2025 | Fit-only sideways-filtered candidate | 4,292 | 2,590 | 1,141 | 1,449 | 1,702 | 44.05% | 42.15% | -1.63% | 44.45% | 1,890 | 1,985 | 2,307 | 1,985 | 2,307 |
| 2022-2025 | Gentle sideways-filtered candidate | 4,006 | 2,408 | 1,047 | 1,361 | 1,598 | 43.48% | 41.51% | -1.94% | 43.93% | 1,842 | 1,699 | 2,307 | 1,699 | 2,307 |
| 2022-2025 | Light sideways-filtered candidate | 3,241 | 1,915 | 762 | 1,153 | 1,326 | 39.79% | 37.62% | -3.80% | 40.37% | 1,701 | 934 | 2,307 | 934 | 2,307 |
| 2022-2025 | Sideways-filtered candidate | 2,673 | 1,551 | 548 | 1,003 | 1,122 | 35.33% | 32.99% | -6.11% | 36.62% | 1,541 | 366 | 2,307 | 366 | 2,307 |
| 2022-2025 | Symmetric sideways filter B15 D0.25 | 1,491 | 818 | 364 | 454 | 673 | 44.50% | 41.13% | -2.56% | 45.26% | 954 | 547 | 944 | 547 | 944 |
| 2022-2025 | Symmetric sideways filter B30 D0.25 | 3,784 | 2,154 | 971 | 1,183 | 1,630 | 45.08% | 42.99% | -2.23% | 45.35% | 1,720 | 1,178 | 2,606 | 1,178 | 2,606 |
| 2019-2025 combined | Current production control | 6,701 | 4,087 | 1,747 | 2,340 | 2,614 | 42.75% | 41.24% | -3.49% | 42.52% | 2,097 | 2,428 | 4,273 | 2,428 | 4,273 |
| 2019-2025 combined | Direction-optimized candidate | 8,557 | 5,341 | 2,653 | 2,688 | 3,216 | 49.67% | 48.33% | +1.41% | 49.04% | 2,144 | 4,284 | 4,273 | 4,284 | 4,273 |
| 2019-2025 combined | Fit-only sideways-filtered candidate | 7,271 | 4,585 | 2,174 | 2,411 | 2,686 | 47.42% | 45.97% | +0.07% | 46.93% | 2,113 | 2,998 | 4,273 | 2,998 | 4,273 |
| 2019-2025 combined | Gentle sideways-filtered candidate | 6,878 | 4,326 | 2,024 | 2,302 | 2,552 | 46.79% | 45.30% | -0.34% | 46.17% | 2,095 | 2,605 | 4,273 | 2,605 | 4,273 |
| 2019-2025 combined | Light sideways-filtered candidate | 5,767 | 3,594 | 1,574 | 2,020 | 2,173 | 43.80% | 42.18% | -2.32% | 43.37% | 2,039 | 1,494 | 4,273 | 1,494 | 4,273 |
| 2019-2025 combined | Sideways-filtered candidate | 4,915 | 3,025 | 1,202 | 1,823 | 1,890 | 39.74% | 38.01% | -5.08% | 40.18% | 1,962 | 642 | 4,273 | 642 | 4,273 |
| 2019-2025 combined | Symmetric sideways filter B15 D0.25 | 2,216 | 1,328 | 699 | 629 | 888 | 52.64% | 49.95% | +2.16% | 53.76% | 1,257 | 858 | 1,358 | 858 | 1,358 |
| 2019-2025 combined | Symmetric sideways filter B30 D0.25 | 5,733 | 3,456 | 1,738 | 1,718 | 2,277 | 50.29% | 48.62% | +1.09% | 51.12% | 1,985 | 1,870 | 3,863 | 1,870 | 3,863 |

### Direction breakdown

| Period | Policy | Direction | N | S | F | I | Precision | Wilson LCB | Avg |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 2019-2021 | Current production control | BUY | 569 | 248 | 161 | 160 | 60.64% | 55.82% | +14.11% |
| 2019-2021 | Current production control | SELL | 1,966 | 464 | 784 | 718 | 37.18% | 34.54% | -11.06% |
| 2019-2021 | Direction-optimized candidate | BUY | 1,434 | 760 | 267 | 407 | 74.00% | 71.23% | +24.86% |
| 2019-2021 | Direction-optimized candidate | SELL | 1,966 | 464 | 784 | 718 | 37.18% | 34.54% | -11.06% |
| 2019-2021 | Fit-only sideways-filtered candidate | BUY | 1,013 | 569 | 178 | 266 | 76.17% | 72.99% | +28.90% |
| 2019-2021 | Fit-only sideways-filtered candidate | SELL | 1,966 | 464 | 784 | 718 | 37.18% | 34.54% | -11.06% |
| 2019-2021 | Gentle sideways-filtered candidate | BUY | 906 | 513 | 157 | 236 | 76.57% | 73.21% | +30.04% |
| 2019-2021 | Gentle sideways-filtered candidate | SELL | 1,966 | 464 | 784 | 718 | 37.18% | 34.54% | -11.06% |
| 2019-2021 | Light sideways-filtered candidate | BUY | 560 | 348 | 83 | 129 | 80.74% | 76.75% | +36.93% |
| 2019-2021 | Light sideways-filtered candidate | SELL | 1,966 | 464 | 784 | 718 | 37.18% | 34.54% | -11.06% |
| 2019-2021 | Sideways-filtered candidate | BUY | 276 | 190 | 36 | 50 | 84.07% | 78.74% | +47.55% |
| 2019-2021 | Sideways-filtered candidate | SELL | 1,966 | 464 | 784 | 718 | 37.18% | 34.54% | -11.06% |
| 2019-2021 | Symmetric sideways filter B15 D0.25 | BUY | 311 | 201 | 44 | 66 | 82.04% | 76.75% | +33.35% |
| 2019-2021 | Symmetric sideways filter B15 D0.25 | SELL | 414 | 134 | 131 | 149 | 50.57% | 44.58% | -4.25% |
| 2019-2021 | Symmetric sideways filter B30 D0.25 | BUY | 692 | 390 | 121 | 181 | 76.32% | 72.45% | +28.98% |
| 2019-2021 | Symmetric sideways filter B30 D0.25 | SELL | 1,257 | 377 | 414 | 466 | 47.66% | 44.20% | -4.25% |
| 2022-2025 | Current production control | BUY | 1,859 | 611 | 535 | 713 | 53.32% | 50.42% | +4.27% |
| 2022-2025 | Current production control | SELL | 2,307 | 424 | 860 | 1,023 | 33.02% | 30.50% | -7.64% |
| 2022-2025 | Direction-optimized candidate | BUY | 2,850 | 1,005 | 777 | 1,068 | 56.40% | 54.08% | +5.54% |
| 2022-2025 | Direction-optimized candidate | SELL | 2,307 | 424 | 860 | 1,023 | 33.02% | 30.50% | -7.64% |
| 2022-2025 | Fit-only sideways-filtered candidate | BUY | 1,985 | 717 | 589 | 679 | 54.90% | 52.19% | +5.36% |
| 2022-2025 | Fit-only sideways-filtered candidate | SELL | 2,307 | 424 | 860 | 1,023 | 33.02% | 30.50% | -7.64% |
| 2022-2025 | Gentle sideways-filtered candidate | BUY | 1,699 | 623 | 501 | 575 | 55.43% | 52.51% | +5.80% |
| 2022-2025 | Gentle sideways-filtered candidate | SELL | 2,307 | 424 | 860 | 1,023 | 33.02% | 30.50% | -7.64% |
| 2022-2025 | Light sideways-filtered candidate | BUY | 934 | 338 | 293 | 303 | 53.57% | 49.66% | +5.68% |
| 2022-2025 | Light sideways-filtered candidate | SELL | 2,307 | 424 | 860 | 1,023 | 33.02% | 30.50% | -7.64% |
| 2022-2025 | Sideways-filtered candidate | BUY | 366 | 124 | 143 | 99 | 46.44% | 40.55% | +3.54% |
| 2022-2025 | Sideways-filtered candidate | SELL | 2,307 | 424 | 860 | 1,023 | 33.02% | 30.50% | -7.64% |
| 2022-2025 | Symmetric sideways filter B15 D0.25 | BUY | 547 | 194 | 123 | 230 | 61.20% | 55.73% | +4.95% |
| 2022-2025 | Symmetric sideways filter B15 D0.25 | SELL | 944 | 170 | 331 | 443 | 33.93% | 29.92% | -6.92% |
| 2022-2025 | Symmetric sideways filter B30 D0.25 | BUY | 1,178 | 403 | 326 | 449 | 55.28% | 51.65% | +4.51% |
| 2022-2025 | Symmetric sideways filter B30 D0.25 | SELL | 2,606 | 568 | 857 | 1,181 | 39.86% | 37.35% | -5.28% |
| 2019-2025 combined | Current production control | BUY | 2,428 | 859 | 696 | 873 | 55.24% | 52.76% | +6.58% |
| 2019-2025 combined | Current production control | SELL | 4,273 | 888 | 1,644 | 1,741 | 35.07% | 33.24% | -9.21% |
| 2019-2025 combined | Direction-optimized candidate | BUY | 4,284 | 1,765 | 1,044 | 1,475 | 62.83% | 61.03% | +12.01% |
| 2019-2025 combined | Direction-optimized candidate | SELL | 4,273 | 888 | 1,644 | 1,741 | 35.07% | 33.24% | -9.21% |
| 2019-2025 combined | Fit-only sideways-filtered candidate | BUY | 2,998 | 1,286 | 767 | 945 | 62.64% | 60.53% | +13.31% |
| 2019-2025 combined | Fit-only sideways-filtered candidate | SELL | 4,273 | 888 | 1,644 | 1,741 | 35.07% | 33.24% | -9.21% |
| 2019-2025 combined | Gentle sideways-filtered candidate | BUY | 2,605 | 1,136 | 658 | 811 | 63.32% | 61.07% | +14.23% |
| 2019-2025 combined | Gentle sideways-filtered candidate | SELL | 4,273 | 888 | 1,644 | 1,741 | 35.07% | 33.24% | -9.21% |
| 2019-2025 combined | Light sideways-filtered candidate | BUY | 1,494 | 686 | 376 | 432 | 64.60% | 61.67% | +17.39% |
| 2019-2025 combined | Light sideways-filtered candidate | SELL | 4,273 | 888 | 1,644 | 1,741 | 35.07% | 33.24% | -9.21% |
| 2019-2025 combined | Sideways-filtered candidate | BUY | 642 | 314 | 179 | 149 | 63.69% | 59.36% | +22.46% |
| 2019-2025 combined | Sideways-filtered candidate | SELL | 4,273 | 888 | 1,644 | 1,741 | 35.07% | 33.24% | -9.21% |
| 2019-2025 combined | Symmetric sideways filter B15 D0.25 | BUY | 858 | 395 | 167 | 296 | 70.28% | 66.38% | +15.24% |
| 2019-2025 combined | Symmetric sideways filter B15 D0.25 | SELL | 1,358 | 304 | 462 | 592 | 39.69% | 36.28% | -6.10% |
| 2019-2025 combined | Symmetric sideways filter B30 D0.25 | BUY | 1,870 | 793 | 447 | 630 | 63.95% | 61.24% | +13.57% |
| 2019-2025 combined | Symmetric sideways filter B30 D0.25 | SELL | 3,863 | 945 | 1,271 | 1,647 | 42.64% | 40.60% | -4.95% |

### Complete pattern breakdown (combined 2019-2025)

| Policy | Pattern | Direction | N | Actionable | Precision | Wilson LCB | Avg | Meets sample floor |
|---|---|---|---:|---:|---:|---:|---:|:---:|
| Current production control | BULLISH_ENGULFING | BUY | 767 | 461 | 50.33% | 45.78% | +3.48% | yes |
| Current production control | BEARISH_ENGULFING | SELL | 1,981 | 1,173 | 36.49% | 33.78% | -8.07% | yes |
| Current production control | HAMMER | BUY | 336 | 208 | 59.62% | 52.83% | +8.64% | yes |
| Current production control | INVERTED_HAMMER | BUY | 447 | 306 | 59.80% | 54.22% | +8.70% | yes |
| Current production control | HANGING_MAN | SELL | 492 | 269 | 36.43% | 30.91% | -6.30% | yes |
| Current production control | SHOOTING_STAR | SELL | 455 | 303 | 32.67% | 27.64% | -17.70% | yes |
| Current production control | MORNING_STAR | BUY | 163 | 111 | 46.85% | 37.83% | +3.96% | yes |
| Current production control | EVENING_STAR | SELL | 335 | 206 | 30.10% | 24.24% | -10.00% | yes |
| Current production control | PIERCING_LINE | BUY | 240 | 145 | 68.28% | 60.31% | +7.75% | yes |
| Current production control | DARK_CLOUD_COVER | SELL | 514 | 287 | 38.68% | 33.23% | -6.45% | yes |
| Current production control | BULLISH_HARAMI | BUY | 447 | 306 | 50.65% | 45.08% | +8.01% | yes |
| Current production control | BEARISH_HARAMI | SELL | 446 | 253 | 28.46% | 23.25% | -10.97% | yes |
| Current production control | THREE_WHITE_SOLDIERS | BUY | 28 | 18 | 77.78% | 54.79% | +15.01% | no |
| Current production control | THREE_BLACK_CROWS | SELL | 50 | 41 | 43.90% | 29.89% | -13.35% | no |
| Direction-optimized candidate | BULLISH_ENGULFING | BUY | 1,373 | 902 | 59.76% | 56.52% | +8.95% | yes |
| Direction-optimized candidate | BEARISH_ENGULFING | SELL | 1,981 | 1,173 | 36.49% | 33.78% | -8.07% | yes |
| Direction-optimized candidate | HAMMER | BUY | 526 | 326 | 60.74% | 55.34% | +8.44% | yes |
| Direction-optimized candidate | INVERTED_HAMMER | BUY | 796 | 519 | 70.13% | 66.06% | +18.19% | yes |
| Direction-optimized candidate | HANGING_MAN | SELL | 492 | 269 | 36.43% | 30.91% | -6.30% | yes |
| Direction-optimized candidate | SHOOTING_STAR | SELL | 455 | 303 | 32.67% | 27.64% | -17.70% | yes |
| Direction-optimized candidate | MORNING_STAR | BUY | 420 | 269 | 65.80% | 59.94% | +11.89% | yes |
| Direction-optimized candidate | EVENING_STAR | SELL | 335 | 206 | 30.10% | 24.24% | -10.00% | yes |
| Direction-optimized candidate | PIERCING_LINE | BUY | 273 | 176 | 68.18% | 60.98% | +13.62% | yes |
| Direction-optimized candidate | DARK_CLOUD_COVER | SELL | 514 | 287 | 38.68% | 33.23% | -6.45% | yes |
| Direction-optimized candidate | BULLISH_HARAMI | BUY | 865 | 596 | 59.56% | 55.57% | +13.25% | yes |
| Direction-optimized candidate | BEARISH_HARAMI | SELL | 446 | 253 | 28.46% | 23.25% | -10.97% | yes |
| Direction-optimized candidate | THREE_WHITE_SOLDIERS | BUY | 31 | 21 | 57.14% | 36.55% | +1.76% | no |
| Direction-optimized candidate | THREE_BLACK_CROWS | SELL | 50 | 41 | 43.90% | 29.89% | -13.35% | no |
| Fit-only sideways-filtered candidate | BULLISH_ENGULFING | BUY | 1,013 | 696 | 59.20% | 55.50% | +9.18% | yes |
| Fit-only sideways-filtered candidate | BEARISH_ENGULFING | SELL | 1,981 | 1,173 | 36.49% | 33.78% | -8.07% | yes |
| Fit-only sideways-filtered candidate | HAMMER | BUY | 355 | 229 | 62.01% | 55.57% | +9.89% | yes |
| Fit-only sideways-filtered candidate | INVERTED_HAMMER | BUY | 561 | 382 | 70.68% | 65.93% | +21.13% | yes |
| Fit-only sideways-filtered candidate | HANGING_MAN | SELL | 492 | 269 | 36.43% | 30.91% | -6.30% | yes |
| Fit-only sideways-filtered candidate | SHOOTING_STAR | SELL | 455 | 303 | 32.67% | 27.64% | -17.70% | yes |
| Fit-only sideways-filtered candidate | MORNING_STAR | BUY | 275 | 187 | 64.71% | 57.62% | +11.79% | yes |
| Fit-only sideways-filtered candidate | EVENING_STAR | SELL | 335 | 206 | 30.10% | 24.24% | -10.00% | yes |
| Fit-only sideways-filtered candidate | PIERCING_LINE | BUY | 201 | 132 | 68.94% | 60.60% | +14.64% | yes |
| Fit-only sideways-filtered candidate | DARK_CLOUD_COVER | SELL | 514 | 287 | 38.68% | 33.23% | -6.45% | yes |
| Fit-only sideways-filtered candidate | BULLISH_HARAMI | BUY | 571 | 412 | 58.98% | 54.17% | +15.88% | yes |
| Fit-only sideways-filtered candidate | BEARISH_HARAMI | SELL | 446 | 253 | 28.46% | 23.25% | -10.97% | yes |
| Fit-only sideways-filtered candidate | THREE_WHITE_SOLDIERS | BUY | 22 | 15 | 46.67% | 24.81% | -0.35% | no |
| Fit-only sideways-filtered candidate | THREE_BLACK_CROWS | SELL | 50 | 41 | 43.90% | 29.89% | -13.35% | no |
| Gentle sideways-filtered candidate | BULLISH_ENGULFING | BUY | 891 | 613 | 60.52% | 56.60% | +9.89% | yes |
| Gentle sideways-filtered candidate | BEARISH_ENGULFING | SELL | 1,981 | 1,173 | 36.49% | 33.78% | -8.07% | yes |
| Gentle sideways-filtered candidate | HAMMER | BUY | 308 | 201 | 61.69% | 54.81% | +8.89% | yes |
| Gentle sideways-filtered candidate | INVERTED_HAMMER | BUY | 489 | 332 | 71.08% | 65.99% | +22.81% | yes |
| Gentle sideways-filtered candidate | HANGING_MAN | SELL | 492 | 269 | 36.43% | 30.91% | -6.30% | yes |
| Gentle sideways-filtered candidate | SHOOTING_STAR | SELL | 455 | 303 | 32.67% | 27.64% | -17.70% | yes |
| Gentle sideways-filtered candidate | MORNING_STAR | BUY | 240 | 166 | 65.06% | 57.54% | +12.76% | yes |
| Gentle sideways-filtered candidate | EVENING_STAR | SELL | 335 | 206 | 30.10% | 24.24% | -10.00% | yes |
| Gentle sideways-filtered candidate | PIERCING_LINE | BUY | 167 | 110 | 67.27% | 58.05% | +14.94% | yes |
| Gentle sideways-filtered candidate | DARK_CLOUD_COVER | SELL | 514 | 287 | 38.68% | 33.23% | -6.45% | yes |
| Gentle sideways-filtered candidate | BULLISH_HARAMI | BUY | 491 | 358 | 60.61% | 55.47% | +18.03% | yes |
| Gentle sideways-filtered candidate | BEARISH_HARAMI | SELL | 446 | 253 | 28.46% | 23.25% | -10.97% | yes |
| Gentle sideways-filtered candidate | THREE_WHITE_SOLDIERS | BUY | 19 | 14 | 42.86% | 21.38% | -2.70% | no |
| Gentle sideways-filtered candidate | THREE_BLACK_CROWS | SELL | 50 | 41 | 43.90% | 29.89% | -13.35% | no |
| Light sideways-filtered candidate | BULLISH_ENGULFING | BUY | 510 | 362 | 58.29% | 53.15% | +10.90% | yes |
| Light sideways-filtered candidate | BEARISH_ENGULFING | SELL | 1,981 | 1,173 | 36.49% | 33.78% | -8.07% | yes |
| Light sideways-filtered candidate | HAMMER | BUY | 163 | 115 | 62.61% | 53.49% | +8.93% | yes |
| Light sideways-filtered candidate | INVERTED_HAMMER | BUY | 319 | 219 | 74.89% | 68.75% | +26.68% | yes |
| Light sideways-filtered candidate | HANGING_MAN | SELL | 492 | 269 | 36.43% | 30.91% | -6.30% | yes |
| Light sideways-filtered candidate | SHOOTING_STAR | SELL | 455 | 303 | 32.67% | 27.64% | -17.70% | yes |
| Light sideways-filtered candidate | MORNING_STAR | BUY | 135 | 93 | 68.82% | 58.81% | +16.98% | no |
| Light sideways-filtered candidate | EVENING_STAR | SELL | 335 | 206 | 30.10% | 24.24% | -10.00% | yes |
| Light sideways-filtered candidate | PIERCING_LINE | BUY | 90 | 67 | 67.16% | 55.26% | +20.85% | no |
| Light sideways-filtered candidate | DARK_CLOUD_COVER | SELL | 514 | 287 | 38.68% | 33.23% | -6.45% | yes |
| Light sideways-filtered candidate | BULLISH_HARAMI | BUY | 269 | 200 | 63.50% | 56.63% | +23.18% | yes |
| Light sideways-filtered candidate | BEARISH_HARAMI | SELL | 446 | 253 | 28.46% | 23.25% | -10.97% | yes |
| Light sideways-filtered candidate | THREE_WHITE_SOLDIERS | BUY | 8 | 6 | 50.00% | 18.76% | +6.30% | no |
| Light sideways-filtered candidate | THREE_BLACK_CROWS | SELL | 50 | 41 | 43.90% | 29.89% | -13.35% | no |
| Sideways-filtered candidate | BULLISH_ENGULFING | BUY | 211 | 163 | 57.67% | 49.99% | +11.74% | yes |
| Sideways-filtered candidate | BEARISH_ENGULFING | SELL | 1,981 | 1,173 | 36.49% | 33.78% | -8.07% | yes |
| Sideways-filtered candidate | HAMMER | BUY | 57 | 43 | 46.51% | 32.51% | +5.95% | no |
| Sideways-filtered candidate | INVERTED_HAMMER | BUY | 165 | 122 | 81.15% | 73.30% | +35.84% | yes |
| Sideways-filtered candidate | HANGING_MAN | SELL | 492 | 269 | 36.43% | 30.91% | -6.30% | yes |
| Sideways-filtered candidate | SHOOTING_STAR | SELL | 455 | 303 | 32.67% | 27.64% | -17.70% | yes |
| Sideways-filtered candidate | MORNING_STAR | BUY | 68 | 56 | 73.21% | 60.41% | +26.72% | no |
| Sideways-filtered candidate | EVENING_STAR | SELL | 335 | 206 | 30.10% | 24.24% | -10.00% | yes |
| Sideways-filtered candidate | PIERCING_LINE | BUY | 31 | 27 | 51.85% | 33.99% | +24.53% | no |
| Sideways-filtered candidate | DARK_CLOUD_COVER | SELL | 514 | 287 | 38.68% | 33.23% | -6.45% | yes |
| Sideways-filtered candidate | BULLISH_HARAMI | BUY | 107 | 80 | 55.00% | 44.12% | +28.14% | no |
| Sideways-filtered candidate | BEARISH_HARAMI | SELL | 446 | 253 | 28.46% | 23.25% | -10.97% | yes |
| Sideways-filtered candidate | THREE_WHITE_SOLDIERS | BUY | 3 | 2 | 100.00% | 34.24% | +33.32% | no |
| Sideways-filtered candidate | THREE_BLACK_CROWS | SELL | 50 | 41 | 43.90% | 29.89% | -13.35% | no |
| Symmetric sideways filter B15 D0.25 | BULLISH_ENGULFING | BUY | 287 | 186 | 67.74% | 60.72% | +13.26% | yes |
| Symmetric sideways filter B15 D0.25 | BEARISH_ENGULFING | SELL | 548 | 332 | 40.66% | 35.51% | -6.73% | yes |
| Symmetric sideways filter B15 D0.25 | HAMMER | BUY | 89 | 62 | 64.52% | 52.08% | +10.23% | no |
| Symmetric sideways filter B15 D0.25 | INVERTED_HAMMER | BUY | 157 | 112 | 85.71% | 78.05% | +29.88% | yes |
| Symmetric sideways filter B15 D0.25 | HANGING_MAN | SELL | 183 | 96 | 43.75% | 34.26% | -4.46% | no |
| Symmetric sideways filter B15 D0.25 | SHOOTING_STAR | SELL | 108 | 64 | 45.31% | 33.73% | -7.92% | no |
| Symmetric sideways filter B15 D0.25 | MORNING_STAR | BUY | 100 | 69 | 72.46% | 60.95% | +16.40% | no |
| Symmetric sideways filter B15 D0.25 | EVENING_STAR | SELL | 123 | 72 | 30.56% | 21.13% | -9.06% | no |
| Symmetric sideways filter B15 D0.25 | PIERCING_LINE | BUY | 54 | 29 | 75.86% | 57.89% | +12.44% | no |
| Symmetric sideways filter B15 D0.25 | DARK_CLOUD_COVER | SELL | 160 | 80 | 41.25% | 31.11% | -3.02% | no |
| Symmetric sideways filter B15 D0.25 | BULLISH_HARAMI | BUY | 168 | 103 | 59.22% | 49.57% | +8.14% | yes |
| Symmetric sideways filter B15 D0.25 | BEARISH_HARAMI | SELL | 219 | 109 | 31.19% | 23.26% | -6.44% | yes |
| Symmetric sideways filter B15 D0.25 | THREE_WHITE_SOLDIERS | BUY | 3 | 1 | 0.00% | 0.00% | -2.51% | no |
| Symmetric sideways filter B15 D0.25 | THREE_BLACK_CROWS | SELL | 17 | 13 | 69.23% | 42.37% | +4.76% | no |
| Symmetric sideways filter B30 D0.25 | BULLISH_ENGULFING | BUY | 583 | 395 | 61.52% | 56.63% | +10.61% | yes |
| Symmetric sideways filter B30 D0.25 | BEARISH_ENGULFING | SELL | 1,684 | 996 | 43.98% | 40.92% | -4.64% | yes |
| Symmetric sideways filter B30 D0.25 | HAMMER | BUY | 206 | 130 | 56.92% | 48.33% | +5.64% | yes |
| Symmetric sideways filter B30 D0.25 | INVERTED_HAMMER | BUY | 367 | 257 | 75.10% | 69.47% | +23.37% | yes |
| Symmetric sideways filter B30 D0.25 | HANGING_MAN | SELL | 474 | 269 | 46.10% | 40.24% | -3.15% | yes |
| Symmetric sideways filter B30 D0.25 | SHOOTING_STAR | SELL | 347 | 223 | 43.95% | 37.59% | -7.81% | yes |
| Symmetric sideways filter B30 D0.25 | MORNING_STAR | BUY | 192 | 130 | 67.69% | 59.25% | +13.05% | yes |
| Symmetric sideways filter B30 D0.25 | EVENING_STAR | SELL | 328 | 183 | 33.88% | 27.42% | -7.69% | yes |
| Symmetric sideways filter B30 D0.25 | PIERCING_LINE | BUY | 116 | 68 | 64.71% | 52.84% | +14.99% | no |
| Symmetric sideways filter B30 D0.25 | DARK_CLOUD_COVER | SELL | 458 | 232 | 46.55% | 40.24% | -2.70% | yes |
| Symmetric sideways filter B30 D0.25 | BULLISH_HARAMI | BUY | 395 | 252 | 57.94% | 51.77% | +12.95% | yes |
| Symmetric sideways filter B30 D0.25 | BEARISH_HARAMI | SELL | 532 | 280 | 32.86% | 27.62% | -6.57% | yes |
| Symmetric sideways filter B30 D0.25 | THREE_WHITE_SOLDIERS | BUY | 11 | 8 | 62.50% | 30.57% | +7.21% | no |
| Symmetric sideways filter B30 D0.25 | THREE_BLACK_CROWS | SELL | 40 | 33 | 69.70% | 52.66% | +4.31% | no |

## Production selection after validation

The selection below balances the two priorities specified for this study: signal count and precision. The trade-off tolerances were applied after reviewing the complete frozen-candidate table, so this section is a production choice rather than a claim of another untouched holdout test. A direction-specific model must satisfy its stated volume multiplier without exceeding its allowed precision trade-off in either independent period.

| Decision | Passed? | Consequence |
|---|:---:|---|
| Weekly structure hybrid | no | Reject this candidate; it lost BUY precision in both periods. |
| Daily BUY pivot/regression veto | yes | Establishes the direction-specific baseline superseded by the symmetric filter below. |
| Daily SELL 25-candle regression | yes | Establishes the direction-specific baseline superseded by the symmetric filter below. |
| Daily symmetric 0.25 ATR swing-displacement filter | yes | Use for factory daily BUY and SELL detection; tiny pivot drift is sideways. |
| Weekly BUY 25-candle regression | yes | Use for factory weekly BUY detection; retain 16.0% - 4/6 for SELL. |
| Monthly BUY 25-candle regression | yes | Use for factory monthly BUY detection; retain 1.5% - 3/5 for SELL. |

Machine-readable deployment flags: `dailySymmetricSwingFilter=true`, `dailyMinimumSwingDisplacementAtr=0.25`, `weeklyBuyRegression=true`, `monthlyBuyRegression=true`.

Implementation: factory daily BUY and SELL now share the symmetric 0.25 ATR swing-displacement gate with the regression veto. Weekly and monthly retain their previously validated direction-specific policies because the tested sideways regression filters reduced volume without consistent precision gains. Explicit numerical trend settings remain fixed-window overrides.

### Final all-interval symmetric trend scope

The production scope applies the same trend definition to **Daily, Weekly, and Monthly**, for both BUY and SELL patterns. Every interval uses a 30-candle confirmed-pivot context, requires at least **0.25 ATR** displacement between both decisive highs and both decisive lows, and retains the 25-candle regression veto. Small or conflicting pivot movement is sideways and cannot satisfy a candlestick pattern's required prior trend.

| Interval | Detections | Precision | Average directional return |
|---|---:|---:|---:|
| Daily | 119,063 | 50.82% | +0.06% |
| Weekly | 27,244 | 49.89% | -0.28% |
| Monthly | 5,733 | 50.29% | +1.09% |

Weekly previously achieved better aggregate precision under the asymmetric BUY-regression/SELL-fixed policy. That policy did not give BUY and SELL—or all intervals—the same definition of a meaningful trend. The symmetric model is therefore a deliberate consistency and sideways-rejection choice; the full comparison remains in the tables above.

Machine-readable final scope: `dailySymmetricSwingFilter=true`, `weeklySymmetricSwingFilter=true`, `monthlySymmetricSwingFilter=true`, `minimumSwingDisplacementAtr=0.25`, `swingLookbackCandles=30`.

### Active-structure continuity correction

The production classifier now also requires the detected swing structure to remain active through the final completed candle before the pattern. A historical sequence of lower highs and lower lows is rejected once a completed pre-pattern candle closes above the latest lower high. The inverse rule rejects a historical sequence of higher highs and higher lows once a completed pre-pattern candle closes below the latest higher low.

This symmetric, causal market-structure rule prevents a completed V-shaped recovery from being mislabeled as an active downtrend (and an inverted V-shaped decline from being mislabeled as an active uptrend). It applies to BUY and SELL detection on Daily, Weekly, and Monthly. It introduces no Keltner Channel or moving-average dependency and uses no future pattern candle.

### Terminal median-position validation

The active-structure correction is now followed by a second causal check over the detector's exact identified trend leg, not the entire 30-candle pivot-search context. The leg begins at the earlier of the two prior decisive pivots used to establish the latest higher-high/higher-low or lower-high/lower-low pair and ends at the final completed candle before the pattern.

For an uptrend, the terminal close must be at or above `median leg close + configured margin * median leg ATR`. For a downtrend, it must be at or below `median leg close - configured margin * median leg ATR`. The comparison is symmetric, uses only completed pre-pattern candles, and does not use Keltner Channels or moving averages. The same exact leg boundaries are now carried into newly detected historical signals and reconstructed from the stored analysis profile for saved signal-detail charts, so the colored region represents the trend that was actually tested.

The margin was tested on the same 2,227-stock, 4,923,993-candle, post-2018 validation set. These are the complete combined-period threshold totals; actionable means success plus failure, while inconclusive results remain in total detections and average return.

| Interval | Margin (median ATR) | Detections | Actionable | Precision | Average directional return |
|---|---:|---:|---:|---:|---:|
| Daily | 0.00 | 88,608 | 55,152 | 50.99% | +0.06% |
| Daily | 0.15 | 80,527 | 50,101 | 51.07% | +0.07% |
| Daily | 0.25 | 76,858 | 47,847 | 51.10% | +0.08% |
| Daily | 0.50 | 67,497 | 42,029 | 51.02% | +0.07% |
| Daily | 0.75 | 58,076 | 36,096 | 51.16% | +0.08% |
| Weekly | 0.00 | 19,883 | 10,463 | 50.11% | +0.01% |
| Weekly | 0.15 | 18,017 | 9,462 | 50.04% | -0.02% |
| Weekly | 0.25 | 17,213 | 9,027 | 50.15% | -0.02% |
| Weekly | 0.50 | 15,002 | 7,830 | 50.05% | -0.03% |
| Weekly | 0.75 | 12,898 | 6,722 | 50.43% | +0.08% |
| Monthly | 0.00 | 4,348 | 2,640 | 50.68% | +1.60% |
| Monthly | 0.15 | 3,996 | 2,416 | 50.41% | +1.39% |
| Monthly | 0.25 | 3,812 | 2,295 | 50.24% | +1.36% |
| Monthly | 0.50 | 3,366 | 2,014 | 49.06% | +0.62% |
| Monthly | 0.75 | 2,900 | 1,738 | 48.39% | +0.44% |

The production defaults therefore remain interval-specific while the rule itself applies everywhere: **0.25 ATR for Daily, 0.00 ATR for Weekly, and 0.00 ATR for Monthly**. A 0.00 margin does not disable the check; it still requires the terminal close to be on the correct side of the exact leg median. Daily 0.25 was selected as the count/precision compromise. Weekly 0.00 preserves 2,670 more detections than 0.25 with slightly better average return and only 0.04 percentage point lower precision. Monthly 0.00 dominates 0.25 on count, precision, and average return.

The April 2026 daily `^GSPC` hammer case around the 6,500-6,700 level is included as a regression test. Its older lower-high/lower-low structure no longer qualifies: the final completed pre-pattern close fails the downtrend terminal-position comparison, so the prior trend is classified as sideways and the bullish reversal pattern is not emitted.

Users can change this ATR-normalized margin separately for Daily, Weekly, and Monthly under Settings > Detection, and can reset each interval or all intervals to these validated factory values. Changing only this margin retains the adaptive pivot/regression model; changing the legacy move/window controls intentionally selects the fixed-window custom model.

Machine-readable terminal defaults: `dailyTerminalMedianDistanceAtr=0.25`, `weeklyTerminalMedianDistanceAtr=0.00`, `monthlyTerminalMedianDistanceAtr=0.00`.

### Adaptive structure plus recent directional participation

The original percentage and directional-candle concept was retested as an **additional gate** on the adaptive model rather than as a replacement fixed-window model. A signal must first pass confirmed swing structure, the regression veto, structural continuity, and terminal median position. When participation is enabled, the latest configured number of candles inside the detector's exact trend leg must then satisfy both:

- at least the configured number of directional close transitions or directional high/low transitions; and
- at least the configured net close move in the required direction.

For example, Daily `0.5% 3/6` means that the latest six candles inside the exact detected leg must contain at least three rising transitions for an uptrend or three falling transitions for a downtrend, and the sixth close must be at least 0.5% beyond the first close in that direction. This gate is symmetric for BUY and SELL requirements and uses no pattern or future candle.

The full 2,227-stock test evaluated 25 participation combinations per interval in addition to the terminal-margin controls. The complete generated result is reproducible through `Post2018DirectionalCandlestickValidationTest`; the most decision-relevant combined-period candidates are below.

| Interval | Candidate | Detections | Precision | Average directional return | Result versus interval terminal control |
|---|---|---:|---:|---:|---|
| Daily | Terminal control: 0.25 ATR | 76,858 | 51.10% | +0.08% | Control |
| Daily | 0.0% 3/6 | 54,243 | 51.51% | +0.16% | Better precision/return; 29.4% fewer signals |
| Daily | **0.5% 3/6** | **51,618** | **51.66%** | **+0.17%** | Selected count/precision compromise |
| Daily | 1.0% 3/6 | 48,148 | 51.74% | +0.19% | Slightly stronger, but loses another 3,470 signals |
| Daily | 2.0% 3/6 | 39,800 | 51.73% | +0.20% | No precision gain over 1.0%; excessive volume loss |
| Daily | 0.0% 4/8 | 59,293 | 51.06% | +0.09% | More signals but no meaningful precision gain |
| Weekly | Terminal control: 0.00 ATR | 19,883 | 50.11% | +0.01% | Control retained |
| Weekly | 0.0% 3/5 | 9,227 | 50.15% | +0.01% | Negligible precision gain; loses 53.6% of signals |
| Weekly | 12.0% 3/6 | 3,888 | 50.62% | +0.01% | Excessive volume loss |
| Monthly | Terminal control: 0.00 ATR | 4,348 | 50.68% | +1.60% | Control retained |
| Monthly | 0.0% 2/4 | 2,581 | 48.21% | +0.37% | Worse on precision and return |
| Monthly | 2.0% 4/8 | 3,013 | 49.48% | +0.98% | Worse on precision and return |

Daily `0.5% 3/6` improved both independent periods: precision changed from 51.59% to 51.91% in 2019-2021 and from 50.75% to 51.47% in 2022-2025. Its combined 51,618 detections retain approximately 67.2% of the terminal-control volume. That is a material but not trivial volume cost, so the participation gate and all three values are explicitly configurable.

Factory deployment is therefore interval-specific:

- **Daily:** participation enabled at `0.5% 3/6`, with the existing 0.25 ATR terminal margin.
- **Weekly:** participation disabled; the adaptive structure and 0.00 ATR terminal-side requirement remain.
- **Monthly:** participation disabled; the adaptive structure and 0.00 ATR terminal-side requirement remain.

Settings no longer switch to the old fixed-window trend detector when these values change. They now configure the additional participation gate on top of the adaptive pivot/regression model. Users can enable or disable it separately per interval and reset all values to the validated defaults.

### Superseding conflict-aware OR policy

This section supersedes the earlier agreement-required experiment. The production objective is now to balance visual trend quality with signal volume.

The production policy was subsequently made deliberately more conservative after repeated visual review found that adaptive-only classifications could still look insufficiently directional. For Daily, Weekly, and Monthly—and symmetrically for BUY and SELL contexts—the detector now runs two independent tests:

1. The adaptive swing-structure, regression-veto, continuity, and terminal-median model.
2. The original fixed-window percentage-move and directional-transition model over the completed candles immediately before the pattern.

A trend is accepted when either test identifies it. A non-directional or unavailable result from one model does not veto a directional result from the other. If both models are directional but identify opposite directions, the result is rejected as a conflict. Thus a BUY pattern can use a downtrend from either model and a SELL pattern can use an uptrend from either model. The factory original-rule values are `3%` and `4/6` for all three intervals. Users may change the percentage, required directional confirmations, window size, and adaptive terminal ATR margin independently per interval in Settings > Detection.

This final policy was adopted as a pragmatic signal-volume and quality compromise and was **not** subjected to another 2,000+ ticker benchmark at the user's request. It should therefore not be described as statistically optimal. It is less restrictive than agreement-required `AND`, while explicit conflict rejection prevents contradictory directional classifications from producing a signal. Existing stored signal snapshots retain the rules under which those signals were created; future and on-demand historical scans use the conflict-aware OR policy.

Machine-readable original-rule defaults: `dailyOriginalRuleEnabled=true`, `weeklyOriginalRuleEnabled=true`, `monthlyOriginalRuleEnabled=true`, `minimumMovePercent=3.0`, `directionalConfirmations=4`, `windowCandles=6`, `combiner=CONFLICT_AWARE_OR`.
