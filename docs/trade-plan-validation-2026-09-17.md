# Trade plan validation

Universe: [A, AA, AAL, AAOI, AAON, AAP, AAPL, AAT]. Sampled scans every 10 daily / 3 weekly / 1 monthly bars after 60 bars of history; rolling 180-bar detection.

Coverage: multi-candle candlestick patterns without the separate next-bar confirmation gate, completed Elliott Wave V/corrections, and harmonic formations. Developing Elliott stages and candidate-gated candles are covered by deterministic tests, not this replay. The baseline Elliott implementation rejects detector origins labeled with an empty string; those rejections are retained as the actual previous behavior. Both Elliott variants use the new fixed horizon for comparison. Every scan uses only completed prefix data. Entry is the next bar open; eligibility is recalculated at that price. Outcomes use OHLC, opening gaps and conservative stop-first ambiguity for both versions, with 10 basis points cost per side. Daily sub-bars are not used in this offline comparison. Baselines freeze the pre-change formulas; harmonic baseline has no target. Duplicate formations are excluded. Pending tails are excluded.

Before 2019 is the development slice; 2019 onward is temporal evaluation. Cached data was used in earlier research, so this is not a pristine holdout. Defaults were not optimized on either slice. Alphabetical cached sample, overlapping signals and survivor selection limit inference. Drawdown is a sequential equal-risk diagnostic, not a portfolio backtest. Zero counts indicate insufficient evidence.

| Group | Proposed | Rejected | Trades | Target | Stop | Expired | Incomplete | Mean net R | Sequential drawdown R |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| CANDLE / DAILY / development / baseline | 14 | 0 | 14 | 1 | 9 | 4 | 0 | -0.897 | 13.775 |
| CANDLE / DAILY / development / refined | 14 | 6 | 8 | 1 | 7 | 0 | 0 | -0.876 | 7.823 |
| CANDLE / DAILY / evaluation / baseline | 53 | 5 | 47 | 6 | 32 | 9 | 1 | -0.533 | 25.216 |
| CANDLE / DAILY / evaluation / refined | 53 | 37 | 16 | 4 | 10 | 2 | 0 | -0.361 | 6.858 |
| CANDLE / MONTHLY / evaluation / baseline | 21 | 0 | 17 | 7 | 10 | 0 | 4 | 0.217 | 7.116 |
| CANDLE / MONTHLY / evaluation / refined | 21 | 16 | 3 | 0 | 2 | 1 | 2 | -0.401 | 1.204 |
| CANDLE / WEEKLY / development / baseline | 3 | 0 | 3 | 1 | 2 | 0 | 0 | -0.035 | 2.052 |
| CANDLE / WEEKLY / development / refined | 3 | 2 | 1 | 0 | 1 | 0 | 0 | -1.009 | 1.009 |
| CANDLE / WEEKLY / evaluation / baseline | 47 | 0 | 46 | 17 | 20 | 9 | 1 | 0.319 | 5.251 |
| CANDLE / WEEKLY / evaluation / refined | 47 | 29 | 18 | 7 | 8 | 3 | 0 | 0.343 | 3.411 |
| ELLIOTT / DAILY / development / baseline | 40 | 40 | 0 | 0 | 0 | 0 | 0 | 0.000 | 0.000 |
| ELLIOTT / DAILY / development / refined | 40 | 28 | 12 | 1 | 10 | 1 | 0 | -0.700 | 11.428 |
| ELLIOTT / DAILY / evaluation / baseline | 160 | 160 | 0 | 0 | 0 | 0 | 0 | 0.000 | 0.000 |
| ELLIOTT / DAILY / evaluation / refined | 160 | 119 | 41 | 4 | 34 | 3 | 0 | -0.472 | 20.283 |
| ELLIOTT / MONTHLY / evaluation / baseline | 4 | 4 | 0 | 0 | 0 | 0 | 0 | 0.000 | 0.000 |
| ELLIOTT / MONTHLY / evaluation / refined | 4 | 4 | 0 | 0 | 0 | 0 | 0 | 0.000 | 0.000 |
| ELLIOTT / WEEKLY / development / baseline | 8 | 8 | 0 | 0 | 0 | 0 | 0 | 0.000 | 0.000 |
| ELLIOTT / WEEKLY / development / refined | 8 | 7 | 1 | 0 | 1 | 0 | 0 | -1.024 | 1.024 |
| ELLIOTT / WEEKLY / evaluation / baseline | 34 | 34 | 0 | 0 | 0 | 0 | 0 | 0.000 | 0.000 |
| ELLIOTT / WEEKLY / evaluation / refined | 34 | 25 | 9 | 0 | 6 | 3 | 0 | 0.312 | 4.136 |
| HARMONIC / DAILY / development / baseline | 3 | 0 | 3 | 0 | 3 | 0 | 0 | -1.063 | 3.190 |
| HARMONIC / DAILY / development / refined | 3 | 2 | 1 | 0 | 1 | 0 | 0 | -1.143 | 1.143 |
| HARMONIC / DAILY / evaluation / baseline | 33 | 3 | 30 | 0 | 2 | 28 | 0 | -0.031 | 4.782 |
| HARMONIC / DAILY / evaluation / refined | 33 | 32 | 1 | 0 | 0 | 1 | 0 | -0.107 | 0.107 |
| HARMONIC / MONTHLY / evaluation / baseline | 1 | 0 | 1 | 0 | 0 | 1 | 0 | -0.262 | 0.262 |
| HARMONIC / MONTHLY / evaluation / refined | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0.000 | 0.000 |
| HARMONIC / WEEKLY / evaluation / baseline | 8 | 0 | 8 | 0 | 3 | 5 | 0 | 0.004 | 2.986 |
| HARMONIC / WEEKLY / evaluation / refined | 8 | 7 | 1 | 0 | 1 | 0 | 0 | -1.083 | 1.083 |

Do not promote tuned parameters or claim improved accuracy from this diagnostic alone. Expand the point-in-time universe and reserve a genuinely untouched period before selecting defaults.
