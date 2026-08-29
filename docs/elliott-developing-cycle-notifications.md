# Developing Elliott cycle notifications

## Scope

Developing impulse tracking starts only after a validated lower-degree five-wave Wave I
and a completed, validated Wave II correction. One persisted alert event represents the
cycle. Wave III, IV, V, and the completed post-impulse A-B-C confirmation update that event
rather than creating duplicate rows; every notified transition is retained in its
transition-history snapshot. Wave V therefore remains an open developing cycle until the
following correction validates or the count is invalidated.

The feature does not replace `ELLIOTT_V1`. V1 remains the production score and eligibility
model for the completed Wave V and correction-ending signals measured by the frozen
benchmark. Developing-cycle confidence is structural subdivision confidence and must not
be compared with the V1 benchmark score as if both estimated the same outcome.

The subdivision grammar is confined to developing-cycle validation. It does not replace or
filter the independent `ELLIOTT_V1` completed-signal, chart, history, confluence, or hierarchy
baseline paths.

## Supported parent structure

- Waves I, III, and V require validated lower-degree motive 1-2-3-4-5 structures.
  Diagonals are admitted only in the legal Wave I/Wave V positions; Wave III must be a
  standard impulse.
- Waves II and IV accept validated zigzags and flats. Their A-B-C classifications retain
  the textbook 5-3-5 and 3-3-5 child-wave modes at degrees above the terminal observable
  pivot degree.
- Double/triple zigzags and double/triple-three combinations are accepted. X waves must
  oppose the larger correction, and a combination cannot contain more than one zigzag.
- Contracting A-B-C-D-E triangles are accepted in Wave IV and B positions. A standalone
  triangle is rejected in Wave II, although the final component of a Wave II combination
  may be a triangle.
- The completed Wave V stage is emitted only when all five parent subdivisions and the
  parent impulse hard rules validate together.
- A post-Wave-V correction can complete the tracked cycle only after the same stored I-V
  impulse. A zigzag requires motive A, corrective B, and motive C (5-3-5). A flat requires
  corrective A, corrective B, and motive C (3-3-5). The B retracement must recover at least
  90% of A for the outer correction to be labeled a flat.
- A contracting 3-3-3-3-3 triangle is legal inside Wave B, and complex corrective B forms
  remain legal. A triangle never substitutes for the complete post-impulse A-B-C and is
  never accepted as standalone Wave II; after a triangular B, motive Wave C is still required.

At the finest pivot resolution available in the candle series, a leg is necessarily
terminal: there is no still-lower observed degree to inspect. This is the finite-data
boundary of the validator, not permission to skip intervening pivots at a selected degree.

## Possible-trade plan

The detector first validates the count exactly as before. Only then does the separate
`ELLIOTT_FIB_RR_V2` policy calculate an entry/reference close, structural stop, buffered
trade stop, Fibonacci target zone, conservative target trigger, and risk/reward. The policy
does not select pivots, validate subdivisions, change confidence, or make a signal eligible.

- Wave II: Wave III target at 1.618 times Wave I projected from Wave II; stop beyond the
  Wave I origin.
- Wave III: Wave IV target at a 38.2% retracement of Wave III, with 50% as the secondary
  projection; stop beyond Wave III.
- Wave IV: Wave V target at Wave I equality projected from Wave IV, with 61.8% of the
  Wave 0-to-III movement as the secondary projection; the buffered trade stop is beyond
  Wave IV while the detector-owned Wave-I overlap boundary remains the hard count rule.
- Wave V: correction target toward prior Wave IV territory, with a 38.2% whole-impulse
  retracement as the secondary projection; stop beyond Wave V.
- A-B-C completion: primary-trend target back toward Wave V; stop beyond the Wave C extreme.

The stop is placed 0.10 ATR outside the structural level. If ATR is unavailable, a 0.10%
entry-price fallback buffer is used. Targets are ±1.5% probability zones and the near edge
is the conservative completed-close trigger used for R:R. Daily plans require at least 1:2;
every other supported interval requires 1:3. A structurally valid projection below that
threshold remains visible but is explicitly `PROJECTION_ONLY` and is not tracked as a trade.

Each stage/revision is stored in `elliott_stage_trade_plans`. Advancing a stage completes the
prior plan; revising the same count retains the old revision and replaces its current plan.
Target and ordinary buffered-stop outcomes use completed-candle closes. Detector-owned hard
Elliott boundaries continue to invalidate the count itself. Thus, a buffered stop can close
the possible trade while the Elliott count remains valid; the two outcomes are not conflated.

## Invalidation and delivery

The detector retains several admissible counts under one stable Wave 0/I development key.
A revised primary count updates the stored signal without creating another row or email.
Completed-candle structural checks invalidate that cycle when Wave II reaches the Wave I
origin, Wave IV enters Wave I territory before the impulse completes, or all admissible counts fail their required
subdivision. An expected Wave III/V resolving as a corrective structure is one such failure.
The existing event is marked invalidated, returned to the unread Latest Signals list, and
emailed with the stored reason. Detection then resumes from new candidate origins; the
invalidated record remains as an audit trail.

## Validation status

Unit and integration coverage verifies strict triangle and complex-correction recognition,
legal diagonal positions, connected post-V zigzag and flat validation, triangular Wave B
followed by motive C, actionary ABC mismatch invalidation, a corrective wave extending
into an invalid five-wave motive, nested Wave II discovery, the complete five-parent-wave
gate, same-event count revision and Wave II-to-III-to-V-to-ABC advancement, cross-rule
cycle/stage deduplication, email content, repository startup, Flyway V49, and the additive
V51 trade-plan persistence. Trade-plan tests cover bullish/bearish symmetry, every currently
detected stage, interval R:R thresholds, projection-only behavior, conservative target-zone
triggers, target outcomes, buffered stops, and hard-rule outcome precedence.
A separate frozen, walk-forward performance study is still required before early-stage
survival or return claims are made.
