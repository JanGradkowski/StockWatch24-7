# Adaptive Elliott projections

The detector retains authority over confirmed wave counts and stage transitions.
`ElliottProjectionAdaptationPolicy` only revises the conditional remainder of an
existing, eligible scenario. It does not change the trade plan or cross-pattern
confluence scores.

## Decision rules

- Only completed daily, weekly and monthly candles count. Duplicate timestamps
  contribute one observation. Batches are evaluated chronologically, using only
  the prefix available at each decision candle.
- A completed candle crossing the stored hard boundary invalidates that scenario
  immediately. Scenario endpoint guidelines instead require three consecutive
  closes beyond the boundary plus half an ATR.
- A price mismatch must exceed the greater of 1.5 ATR and 18% of the original
  source-to-target distance for three consecutive closes in the same direction.
  An overdue window or incompatible corrective swings also requires persistence.
- Redraws are at least five completed candles apart. Timing is always measured
  from the original wave endpoint; the maximum lifetime is twice the initial
  maximum window, capped at 240 candles (without shortening an initially longer
  window). Insufficient support results in `UNRESOLVED`, with no displayed path.
- Corrective evidence uses alternating local pivots with two candles on both
  sides. Their displacement threshold is fixed at confirmation. Retracement and
  contraction evidence can favor existing zigzag, flat, expanded-flat, triangle
  or combination candidates; the policy never introduces a correction family
  that the stage generator did not permit.
- A challenger needs an eight-point advantage for three consecutive evaluations
  to replace a still-drawable preferred scenario. A supported drawable alternative
  can replace a retired or target-touched path immediately. Ties between
  unresolved paths do not change their order.
- Target contact records the first contact timestamp and normally hides that
  path while awaiting a validated endpoint or an extension. A subsequently
  confirmed corrective rebound can support a new remaining leg back to that
  target. Contact history is retained; it never automatically completes a wave.

These are conservative, configurable-in-code engineering thresholds, not fitted
probabilities or guarantees of a particular Elliott interpretation. Confirmed
local swings provide evidence about a candidate; they do not become confirmed
parent-wave labels. Revised paths start at the completed close as an explicitly
provisional anchor and retain conditional turns. Existing scenario targets and
hard boundaries remain fixed; a price extension uses the eligible extension
scenario's own target.

## History and integration

Flyway migration V69 adds ordered path snapshots, evidence counters, target
contact, count-availability timestamps and optimistic locking on projection sets.
It retains existing paths as original snapshots. Each redraw appends the new
geometry, decision candle, target zone, total candle window and reason. Wave
endpoint time and the time its count became available are stored separately.
On their first new evaluation, existing scenarios also recover earlier target
contact from available completed history without backdating a path revision.

The signal detail view shows only supported remaining paths. Its expandable
history includes original/revised turning points and archived scenarios. Preferred
labels derive from current rank. The scheduler and historical backfill use the
same evaluation service; existing trade outcomes remain independent.

Tests cover bullish and bearish acceleration, noise, target contact and returns,
extensions, correction alternatives, bounded duration, promotion persistence,
cooldowns, incomplete/duplicate candles, batch/incremental/rolling-history replay,
locale-independent serialization, template rendering and PostgreSQL persistence.
