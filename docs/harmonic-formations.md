# Harmonic formations

The application detects completed bullish and bearish Gartley, Bat, Butterfly,
Crab, Shark, Cypher, Alternate Bat, Deep Crab, 5-0, AB=CD and Alternate AB=CD
formations on daily, weekly, and monthly candles. The
implementation owns the classification and does not delegate harmonic geometry
to TA4J.

## Detection policy

- Percentage relationships use a configurable 3% Fibonacci tolerance by
  default. AB/CD equality uses 3%; a required visual difference uses 10%.
- Direction, alternating pivot order, inside/outside completion, the Crab and
  Butterfly C-point boundary, and Cypher's 1.414 maximum are hard constraints.
- Each terminal pivot requires right-side confirmation candles. The detector
  exposes a separate confirmation timestamp so historical results do not use a
  pivot before it was knowable.
- `classifyAll` retains compatible interpretations of five pivots; the older
  `classify` wrapper returns the best fit. `detectAll` retains detected geometries,
  while `detectHistorical` applies the display count cap.
- Bat B is below 0.618 and CD may exceed AB; preferred ratios affect ranking.
  Butterfly supports BC projections through 2.618.
- Shark points are displayed as `0-X-A-B-C`; AB=CD families use `A-B-C-D`;
  the other patterns use `X-A-B-C-D`.
  The bullish Shark terminal C completes below A (mirrored above A for bearish),
  consistent with the official 0.886 retracement to 1.13 extension completion
  zone: <https://harmonictrader.com/harmonic-patterns/shark-pattern/>.
- Finite pivot windows, volatility scales and hierarchical grouping search for
  swings. Factory settings permit four skipped pivots; omitted OHLC extrema
  must remain inside the selected legs. This is not exhaustive human counting.
- Malformed OHLC and duplicate timestamps split history into independent
  segments. Calendar completeness and adjusted-price consistency require
  exchange-session and corporate-action metadata.
- Scores measure configured geometry/evidence, not calibrated success probability.

## Signal delivery

Users can persist bullish and bearish harmonic watch rules per ticker for all
three chart intervals and show confirmed formations with the **Harmonic
formations overlay** on `stock.html`. A watched formation creates a technical
signal when a completed formation first becomes recognizable. Prefix comparison
separates recognition time from an earlier pivot confirmation; evidence retains
the original structural confirmation time. Endpoint deduplication prevents
redelivery when an old geometry reappears. The signal stores the
original geometry and ratios, records the confirmation-candle close, supports
email delivery and the standard technical signal-detail page, and exposes a
separate historical-detail page from the overlay hover card.

Historical confluence replays completed prefixes instead of reading earlier
evidence from a final chart. Historical detail and outcomes stay within the
formation's valid data segment. Stop/target calculations are application trading
heuristics, not proof of a reversal.

The geometry version is `HARMONIC_V4`. Legacy preferences migrate to
`USER_HARMONIC_RULES_V4`; current explicit custom settings are retained and can
make acceptance differ from factory rules. See the
[remediation report](pattern-detection-remediation.md) for validation and limits.
