# Pattern detection remediation

This report follows the [audit of revision 247c443](pattern-detection-audit.md).
The fixes change recognition, historical scoring, live availability, preferences
and rendering. They do not establish that every real-market Elliott count or
harmonic formation will be detected, or that detected patterns predict profit.

## Audit findings and changes

| Finding | Implemented change | Evidence / remaining boundary |
|---|---|---|
| 1. Wrong parent boundaries accepted | Child boundaries must be actual local extrema within the explicit 0.75% price tolerance; triangle counts also use observed endpoints. | Missing 1/1000 boundaries against a 100–160 child series are rejected. Intrabar order remains limited by the available candles. |
| 2. Hindsight-dependent confluence | Lazy chronological prefix replay replaces final-history Elliott/harmonic observations. A geometry is observed when it becomes available. | Published SPY replay and changing-output regressions compare full-history assessments with prefix-only assessments. |
| 3. Bat exclusions | B below 0.618; preferred 0.382/0.50 ratios used for ranking; AB=CD is a minimum with extensions supported. | Fixed 0.55 B and 1.618 CD/AB examples, mirrored in both directions. |
| 4. Butterfly projection ceiling | BC projection range extends through 2.618; equivalent/extended CD relationships supported. | Published-ratio fixed examples in both directions. |
| 5. Shark completion holes | Continuous 0.886–1.13 completion zone replaces two isolated targets. | Origin-retest example accepted in both directions. |
| 6. Inconsistent Elliott validation claims | Shared bounded grammar assessment for emitted cycles, historical structures and subdivisions. Reports observed depth and unresolved child legs. | Recursion is bounded to three observed levels; unresolved finer degrees remain explicit hypotheses, not proof of complete textbook grammar. |
| 7. Diagonal geometry and position | Contracting/expanding lengths and converging/diverging boundaries; leading/ending labels; III cannot be diagonal; ending children are corrective. | Non-wedge negatives, a corrected contracting-wedge positive and position tests. Leading child grammar supports the documented alternatives. |
| 8. Unsigned correction | Signed retracement rejects C ending on the wrong side of V; zigzag C must pass A. | Wrong-side running-flat and failed-zigzag negatives. |
| 9. Missing pattern families | Five additional harmonic families; standalone Elliott corrections, diagonals and triangle candidates; contracting/barrier/expanding triangle support. | New harmonic families pass OHLC detection and trade-plan checks in both directions/all three intervals. Standalone Elliott shapes have public API and chart candidate rendering. Parent context is still required before assigning a trading stage. |
| 10. Timing exclusions and early pruning | Factory minimum leg span is one bar; confirmation-lag policy is shared; a missed early trigger no longer stops later developing stages. | Slow-II/later-V regression. Structural enumeration is separated from timely-alert policy. |
| 11. Pivot search and display thinning | More harmonic scales and contained hierarchical grouping; uncapped detection separate from display limits. Removed fixed Elliott combination/hypothesis truncation. | Finite configured pivots and history still limit search; this is not exhaustive recognition. |
| 12. Live context and late entry | Elliott parent context increased to 1,000 bars plus enrichment warm-up; new cycles can start at later stages. Recognition time is separate from old pivot confirmation. | Scheduler regression verifies newly recognized III is persisted at current availability and an already-known old count is not redelivered. Harmonic endpoint deduplication prevents later reappearance from sending another event. |
| 13. Confluence stages/directions | Uses actual detected stage, pattern and expected move; removes hard-coded reconstructed V/C labels and separate fixed lag. | Bullish V correctly contributes SELL; same-family observations are excluded and only prior candles contribute. |
| 14. Bad input | Shared OHLC/duplicate/identity validation; independent valid segments for historical detection and indicator enrichment. Detail paths preserve boundaries and limit outcome data to their segment. | Regression preserves earlier history after later malformed bars. Calendar coverage is only verified when expected exchange periods are supplied; adjusted-price consistency cannot be inferred without corporate-action metadata. |
| 15. Narrow validation | Added independent fixed ratio examples, invalid geometry, causal replay, malformed-data and operational regressions; retained deterministic synthetic benchmarks. | Synthetic recall is not market recall or precision. No comprehensive independently labelled market corpus is available in this repository. |
| 16. Confidence/settings/docs | Explicit heuristic-score/validation-scope wording, updated documentation, versioned caches and legacy preference migration. | Existing users may still deliberately select stricter/custom rules and alert score thresholds. |

## Operational distinctions

`findAllWaveStructures` exposes structural counts without historical display
thinning or alert-score filtering. `findPatternCandidates` also exposes standalone
shapes through `/{symbol}/elliott-waves/candidates`; the chart marks them as
candidates with unresolved parent/finer-wave context. `findHistoricalWaveStructures`
continues to provide a curated chart view. Live alerts still require confidence,
confirmation and watch-rule eligibility. These are different outputs by design.

An Elliott subdivision's `validated` flag means its observed endpoint geometry
passed the implemented rules. Read `validationScope`, `observedDepth` and
`unresolvedLegs` for what was actually established. Finite OHLC bars cannot prove
an infinite recursive wave decomposition. No exchange-session completeness or
split-adjustment claim is made without the corresponding metadata.

Version changes are `HARMONIC_V4`, `CONFLUENCE_V3_CAUSAL`,
`ELLIOTT_RULES_V2`, `USER_HARMONIC_RULES_V4` and
`USER_ELLIOTT_DETECTION_V3`. Elliott score arithmetic retains its separate
`ELLIOTT_V1` score version.

## Validation

The broad unit run discovered 621 tests: **597 passed, 24 skipped, zero failures
or errors** (`target/pattern-fixes-final-unit-suite.log`). Both opt-in benchmarks
were enabled: **1,000/1,000 harmonic completions** and **1,000/1,000 exact nested
Elliott counts** were recovered. The harmonic duration benchmark previously
recovered 990/1,000. This run excludes integration tests, the application context
test and the two independently reproduced failing test classes listed below.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress `
  '-Dtest=*,!*IntegrationTest,!StockWatch247ApplicationTests,!FrontendSecurityTest,!CandlestickTextbookRulesTest' `
  '-Delliott.synthetic.benchmark=true' `
  '-Dbacktest.harmonic.long-duration.enabled=true' test
```

After the last hierarchy, drill-down and short AB=CD changes, the final focused
run passed **76 tests, zero skipped/failures/errors**, recorded in
`target/pattern-fixes-final-entry-points.log`. It includes malformed-data entry
points, causal confluence replay and newly available later-stage delivery.
JavaScript syntax (`node --check
src/main/resources/static/js/stock-workspace.js`) and `git diff --check` pass.

The Elliott synthetic generator previously emitted negative prices for some
bearish cases. It now shifts the bearish origin sufficiently upward while
preserving the generated wave lengths, ratios and timing. The harmonic duration
benchmark uses the public factory rules; its original six-family cohort remains
fixed and the five added families have separate positive/negative tests. Family
iteration is now deterministic; the earlier 990-case result should not be read
as an identical-case comparison against the formerly unspecified iteration order.

An earlier complete application-suite attempt did not pass. A detached checkout
of the original revision reproduced three `FrontendSecurityTest` failures and
two `CandlestickTextbookRulesTest` failures. Integration testing also encountered
database connection exhaustion and schema/cleanup failures; it is not a clean
integration acceptance result. Those unrelated features/database issues were
not changed as part of this detector remediation.

## Technical queue follow-up (24 September 2026)

The startup log's 57 queued symbol checks did not mean they had finished.
A read-only inspection found CDR and ^GSPC in PROCESSING, occupying both
technical worker slots, with 114 PENDING checks behind them. Insider checks use
a separate worker slot. A cached 1,000-candle CDR replay reproduced expensive
Elliott subdivision enumeration after candle loading.

Technical jobs now log their start, active rule families, processing stage,
elapsed time, success or failure, and remaining queue size. A 30-second progress
message identifies running jobs. Failure no longer produces a misleading
"check completed" message from the finally block.

The Elliott search now rejects an invalid finished leg before enumerating its
remaining legs, checks correction A/B subdivisions before expanding C, and
reuses recursive grammar and ATR calculations within a scan. It retains distinct
parent paths in a map and sorts once. These changes do not impose a hypothesis
cap or reduce the 1,000-bar context. Scheduler comparisons against the preceding
candle are reused across candidates instead of replayed for every candidate.
The primary-result API keeps only the best materialized result per cycle while
counting every distinct alternative path; the exhaustive API still returns all
alternatives. Detailed structures and trade previews are built after ranking
for the primary API. A regression compares both APIs' winners, complete result fields
and alternative counts on nested zigzag, flat and triangular-B examples.

The WL-prefixed fake symbols in the supplied log match the named-watchlist
integration fixture format. Bulk-action integration checks use a separate
database schema. Berkshire share classes now try Yahoo's BRK-A/BRK-B aliases.

Watchlists also support selected-member follow changes and removal, with
ownership and membership validation before mutation. Follow edits reconcile the
union across lists. Multi-ticker archives filter in SQL before sorting and
pagination, and retain the selection in their navigation URLs.

Validation: the combined focused run passed 120 tests, including the isolated
bulk-action integration checks. After the final deferred-materialization change,
89 detector/scheduler tests passed, including the 1,000-case synthetic benchmark
and primary-versus-exhaustive equivalence checks. The 1,000 intended counts were
retained. Ten JavaScript tests and desktop/mobile browser checks also passed.
These are targeted acceptance results, not a clean full-application suite.

A read-only replay of the cached 1,000 daily CDR candles completed with 42 primary
results in 92.6 seconds using Java 25 and a 768 MB heap. Before deferring detailed
materialization, the same diagnostic took 193.0 seconds and returned 42 results.
These are single local runs after the other optimizations, not a controlled
benchmark against the original revision. Full-history enumeration remains
expensive. The live application was not restarted and its queued jobs were not
modified or verified as drained.

## Primary specification references

- Scott Carney: [Bat](https://harmonictrader.com/harmonic-patterns/bat-pattern/),
  [Butterfly](https://harmonictrader.com/harmonic-patterns/butterfly-pattern/),
  [Shark](https://harmonictrader.com/harmonic-patterns/shark-pattern/),
  [Alternate Bat](https://harmonictrader.com/harmonic-patterns/alternate-bat-pattern/),
  [Deep Crab](https://harmonictrader.com/harmonic-patterns/deep-crab-pattern/),
  [5-0](https://harmonictrader.com/harmonic-patterns/5-0/),
  [Alternate AB=CD](https://harmonictrader.com/harmonic-patterns/alternate-abcd-pattern/).
- Elliott Wave International: [impulses](https://www.elliottwave.com/waveopedia/impulse/),
  [zigzags](https://www.elliottwave.com/waveopedia/zigzags/),
  [flats](https://www.elliottwave.com/waveopedia/flats/),
  [triangles](https://www.elliottwave.com/waveopedia/triangles/),
  [diagonals](https://www.elliottwave.com/waveopedia/elliott-wave-pattern-diagonals/).
