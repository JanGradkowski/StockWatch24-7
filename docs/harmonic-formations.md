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
- Bat B is 0.382-0.50 of XA by default (Carney), keeping it apart from the Gartley's 0.618 and the Alternate Bat's 0.382-or-less; CD may exceed AB. The looser 0-0.618 range can be chosen in the harmonic settings. Profiles still on the earlier 0-0.618 factory range were moved to the new default; ranges users set themselves were kept.
  Butterfly supports BC projections through 2.618.
- Every five-point pattern, the Shark included, is labelled `X-A-B-C-D`; the
  four-point AB=CD families use `A-B-C-D`. Carney writes the Shark as `0-X-A-B-C`;
  here each Shark point moves up one letter (0→X, X→A, A→B, B→C, C→D), so its
  rules read: B retraces 32–61.8% of XA, C extends 113–161.8% of AB, D extends
  161.8–224% of BC and completes at 0.886–1.13 of XA. Signals saved under the old
  letters were relabelled by migration V83.
  The bullish Shark terminal D completes below B (mirrored above B for bearish),
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
