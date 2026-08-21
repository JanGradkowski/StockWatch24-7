# Harmonic formations phase one

The application detects completed bullish and bearish Gartley, Bat, Butterfly,
Crab, Shark, and Cypher formations on daily, weekly, and monthly candles. The
implementation owns the classification and does not delegate harmonic geometry
to TA4J.

## Detection policy

- Percentage relationships use a configurable 4% Fibonacci tolerance by
  default. AB/CD equality uses 8%; a required visual difference uses 10%.
- Direction, alternating pivot order, inside/outside completion, the Crab and
  Butterfly C-point boundary, and Cypher's 1.414 maximum are hard constraints.
- Each terminal pivot requires right-side confirmation candles. The detector
  exposes a separate confirmation timestamp so historical results do not use a
  pivot before it was knowable.
- If multiple names fit the same five pivots, the classifier keeps the result
  closest to its primary B and completion ratios, including an explicit penalty
  for stretched Gartley alternatives.
- Shark points are displayed as `0-X-A-B-C`; the other patterns use `X-A-B-C-D`.
  The bullish Shark terminal C completes below A (mirrored above A for bearish),
  consistent with the official 0.886 retracement to 1.13 extension completion
  zone: <https://harmonictrader.com/harmonic-patterns/shark-pattern/>.

## Signal delivery

Users can persist bullish and bearish harmonic watch rules per ticker for all
three chart intervals and show confirmed formations with the **Harmonic
formations overlay** on `stock.html`. A watched formation creates a technical
signal only when the terminal D/C pivot is confirmed. The signal stores the
original geometry and ratios, records the confirmation-candle close, supports
email delivery and the standard technical signal-detail page, and exposes a
separate historical-detail page from the overlay hover card.
