# Pattern detection remediation

This report follows the [audit of revision 247c443](pattern-detection-audit.md).
The fixes change recognition, historical scoring, live availability, preferences
and rendering. They do not establish that every real-market Elliott count or
harmonic formation will be detected, or that detected patterns predict profit.

## October 4 Elliott correction-search implementation

**Implemented:** equivalent correction suffixes are now searched once per scan,
not once per five-wave parent path. This applies to primary detection and compact
cross-pattern confluence; it does not truncate history or discard alternatives.

### Exact reuse, not a beam-search limit

- A validated Wave V leaves a correction problem determined by the origin,
  Wave-V pivot and common scale support. The scan-local state key includes all
  three. Rules, parent candles and child candles are fixed for that scan.
- The state retains the exact admissible-path count and the winning A/B/C suffix.
  Parent persistence contributes the same additive coherence score to every
  suffix. Confidence is the rounded average of eight integer leg scores, so
  **eight confidence-remainder buckets** preserve rounding ties for every possible
  five-leg prefix sum. All buckets are updated during one suffix traversal.
  Triangle preference, coherence, confidence, confirmation time and original
  encounter-order ties remain unchanged.
- Each suffix validates A/B/C without fetching and validating I-V again. Only the
  selected suffix is attached to the actual parent prefix and materialized. Exact
  alternative counts still contribute to the original hypothesis evidence.
- Compact confluence also keys on previous-prefix scale support and seed
  eligibility. It preserves the first geometry for each pattern/direction and
  causal availability. Full alternative enumeration and non-adjacent geometry
  differences retain the exhaustive path.
- Both suffix memo tables are bounded to 8,192 entries. Eviction causes
  recomputation, never lost hypotheses. Tables are rebuilt for each input scan,
  so candle revisions, child revisions, rewinds and rolling windows cannot reuse
  stale suffixes. Existing unchanged-leg reuse remains in place.
- Checkpoints use a new search-key version. A suffix computation is an atomic
  checkpoint unit; cancellation is checked during its traversal. Interrupted
  suffixes are recomputed rather than partially counted. Serialized continuation
  tests cover primary counts and compact confluence.

### Measurements and validation

Java 25.0.1, 768 MB heap, local read-only candle snapshots, separate fresh JVMs,
factory rules. No provider calls, notifications or production checkpoint writes.
An immediately paired CDR daily replay at the logged scheduled cutoff
`2026-10-01T22:00:00Z` measured:

| Operation | Exhaustive reference | Optimized |
|---|---:|---:|
| Cold current primary scan | 12.978 s | 4.273 s |
| Previous-prefix scan | 13.568 s | 2.001 s |
| Compact Elliott confluence | 7.335 s | 3.151 s |

Additional paired cold daily primary scans at the same scheduled cutoff:

| Symbol | Exhaustive reference | Optimized | Primary results |
|---|---:|---:|---:|
| AXP | 15.562 s | 7.014 s | 41 |
| IBM | 26.647 s | 7.281 s | 39 |
| BA | 9.454 s | 6.518 s | 41 |

Each pair has byte-identical serialized results. These are detector replays,
not total scheduled-job durations, and are not directly comparable to the
application's earlier 70–90-second stage timings.

The original profiled cold reproduction was 44.509 s; wall times vary considerably
between runs, so the paired numbers are the more conservative comparison. The
primary traversal falls from **18,925,851 prefixes to 1,113,391** (431,514 parent
prefixes plus 681,877 correction prefixes), with 1,134 suffix states and 35,611
reuses. All 42 primary results, including geometry, evidence and alternative
counts, serialize **byte-identically** to both the saved original and the fresh
exhaustive reference. The later October 4 CDR snapshot also produces byte-identical
results; its optimized cold replay took 7.063 s versus the earlier 86.202 s run.

AAPL weekly with 1,000 parent and 4,821 child candles also matches the saved
exhaustive output byte-for-byte (139 results). Its profiled cold scan still takes
12.848 s: child-degree grammar/containment remains a cost on that workload. This
change does **not** claim universal sub-five-second cold scans or sub-one-second
new-bar calculations. Current and previous inputs still perform separate searches,
although both now use suffix reuse; persistent cross-candle search-state reuse is
not implemented here.

Validation: **133 tests passed, zero failures/errors/skips**, including the
opt-in synthetic benchmark with **1,000/1,000 exact recoveries**. The test log is
`target/elliott-final-regression.log`.

Regression coverage includes primary ranking/counts, all confidence-remainder
rounding ties, changed rules, zigzag/flat/triangular
corrections, generated price paths, child coverage, append/rewind/window changes,
compact confluence equivalence and serialized restart. Debug logging on
`ElliottWaveDetectionService` reports parent/correction prefixes, retained states,
reuse counts and search time. Replay artifacts are under `target/elliott-suffix-*`
and `target/recovery-replay`; `tools/AnalysisRecoveryReplay.java` accepts a final
`reference` argument after `continuous` to disable suffix reuse for comparison.

The correction portion changes from repeated suffix-tree traversal for every
valid parent prefix to one traversal per distinct boundary/scale state, plus
constant-size ranking/count aggregation on reuse. This reduces the repeated
factor; it is **not** an O(candles) claim. Parent-path and child-grammar enumeration
remain output/state dependent, and cache eviction can require recomputation.

## October 4 late-run Elliott investigation: original proposal

The following records the diagnosis before the implementation above and the
broader optimization proposal; not every proposed phase is implemented.
The new runtime log confirms that the outlook fix works, but does not establish
that Elliott detection is fast. SHW completes its entire check in 2.910 seconds;
its summary/detail outlooks take 190/154 ms. The remaining long stages are:

| Symbol / daily stage | Approximate wall time between stage messages |
|---|---:|
| AAPL developing Elliott | 24.19 s |
| AMGN developing Elliott | 31.18 s |
| AXP developing Elliott | 70.01 s |
| BA candlestick cross-pattern confluence | 27.47 s |
| BA developing Elliott | 38.58 s |
| CDR developing Elliott | 88.87 s |
| IBM developing Elliott | 69.91 s |

These are stage durations, not isolated detector CPU measurements: the stage can
include validation, persistence and signal preparation. Provider fallbacks and
SMTP acceptance are separate from these long computations. The final database
warning occurs during application shutdown and is not evidence for the earlier
Elliott bottleneck.

### Reproduction and root cause

A fresh Java 25.0.1 / 768 MB read-only CDR replay used the logged scheduled cutoff
`2026-10-01T22:00:00Z`, 1,199 stored daily input bars, and the 1,000-bar enriched
parent window. Available hourly history does not cover the beginning of that
window, so the existing production coverage rule selects parent-only subdivision
validation. The replay uses factory Elliott rules; it is not a full reconstruction
of every subscriber's settings, database queries or notification processing.

The cold primary scan took **44.509 seconds** to return **42 primary counts**.
Its per-seed checkpoint counters record **548 seed traversals**,
**18,925,851 visited prefixes**, **485,977 accepted paths**, and **5,239,166
visited prefixes for the largest single seed**. Artifacts:
`target/elliott-cdr-scheduled.log`, `target/elliott-cdr-scheduled.jfr`, and
`target/recovery-replay/CDR-v2-1d-1790892000/checkpoints-scheduled-diagnosis`.
All checkpoint writes are local files, not production database writes.

An additional replay with a later October 4 cutoff (different input, not the
logged job) took 86.202 seconds for the current primary scan and 57.326 seconds
for the previous prefix. It demonstrates that adding a small number of candles
can materially change the search cost. It must not be presented as an exact
before/after reproduction of the October 1 job.

The code explains why the prior optimizations do not resolve this:

- `extendDevelopingImpulse` still traverses the parent path tree separately for
  every origin/Wave-I seed. Even `primaryOnly` evaluates alternate paths, counts
  them, and chooses a winner afterwards. Forty-two returned objects therefore
  do not mean forty-two calculations.
- `evaluateDevelopingPrefix` repeatedly invokes `developingImpulse`. Completing
  an A-B-C extension rechecks the earlier five-wave impulse and retrieves its
  leg subdivisions again, although that prefix was already validated on the way
  to Wave V. Millions of cache hits still require millions of keys, hashes,
  lookups, list allocations and parent-rule checks.
- JFR samples are concentrated in `selectedSubdivision`/`HashMap.computeIfAbsent`,
  selection-key hashing/equality, prefix list creation, common-scale calculations
  and correction validation. This is repeated search work, not another numerical
  exponential or a provider delay. Cache-expiration sweeps are a smaller,
  secondary cost.
- `ScheduledAlertService.newlyAvailableDeveloping` can ask for the entire previous
  primary scan to decide whether a current result is newly available. Job-local
  memoization avoids doing that separately for every rule, but not the first
  expensive previous-prefix traversal.
- Cross-pattern confluence already has an adjacent-prefix delta path, but it can
  still walk old parent prefixes to reach a changed endpoint. That is why a
  candlestick's confluence stage can also be slow while raw recognition is fast.
- Completed-result caches and checkpoints are keyed by complete inputs. They help
  identical retries, not a genuinely new candle or a changed child series.

### Proposed general implementation

1. **Replace repeated paths with reusable search states.** Represent validated
   prefixes as states containing their stage, future-rule boundary pivots,
   common scale support, necessary correction grammar, ranking aggregates and
   deterministic encounter-order tie information. Carry validated subdivisions
   forward rather than revalidating I-V at every A-B-C endpoint. Start with the
   expensive correction suffix: after a validated Wave V, many earlier paths
   have the same remaining correction problem. Cache those suffix transitions
   using all inputs that can affect their validity and ranking.
2. **Merge only provably equivalent states.** Keep the best representative needed
   for primary selection plus path multiplicity for alternative-count evidence.
   Do not merge merely because two paths share their last pivot: origin-relative
   retracements, scale support, Wave-II/IV grammar, confidence and tie order can
   distinguish them. Keep explicit alternative geometry enumeration as a separate
   on-demand query. If multiplicity cannot yet be proven correct for a state,
   retain the exhaustive reference path rather than silently changing evidence.
3. **Compute current/previous availability together.** Share immutable leg/state
   results between the two adjacent prefixes and expose a new-primary delta API
   to the scheduler. Respect the existing distinction between confirmation time
   and first availability; do not simply filter by the latest confirmation date.
   Confluence should query the same causal state index instead of walking an
   equivalent historical tree independently.
4. **Persist reusable state across candles, not only exact-input results.** Rebuild
   or compare the inexpensive pivot/scale input, identify changed dependencies,
   and invalidate only affected states and descendants. Use stable timestamps,
   not rolling array indices. Corrections, child revisions, window eviction,
   sensitivity changes, newly eligible seed pairs and retrospective pivot/scale
   changes all need explicit invalidation. Do not assume appending one candle
   only changes the final pivot. Version the state format and retain restart/
   checkpoint safety.
5. **Reduce overhead after reducing the search.** Index valid next legs, represent
   common scale sets compactly, avoid allocating a fresh list for every extension,
   and avoid a full TTL-cache sweep on every insertion. These are secondary
   improvements, not substitutes for eliminating repeated states.
6. **Prove equivalence and measure cold as well as warm work.** Preserve the current
   exhaustive detector as an oracle for manageable fixtures. Compare primary
   geometry, stage, ranking ties, confidence, hypothesis multiplicity, grammar
   evidence, confirmation/availability, lifecycle and notification identities.
   Cover append, rewind, correction, rolling-window eviction, partial/full child
   coverage, settings changes and restart. Benchmark CDR, AXP, IBM, AAPL, BA
   confluence, weekly/monthly inputs and adverse synthetic pivot arrangements;
   report visited states, transitions, allocations and cache misses, not only
   elapsed time. Existing synthetic recall alone does not prove these semantics.

### Complexity and acceptance criteria

Current work is at least proportional to the number of traversed path prefixes
`H`, which can grow combinatorially with pivot count even at the fixed nine-pivot
maximum depth. Leg memoization lowers the cost per prefix but does not lower `H`.
The proposed memoized state graph processes distinct states and transitions,
approximately `O(V + E)` after input/index preparation, rather than replaying
equivalent suffixes for every full path. `V` and `E` must include every field needed
for exact future behavior; their size is not claimed to be linear in candles.
Incremental cost should track changed dependencies plus any required pivot scan,
not a repeat of the whole historical path tree.

Targets for the measured workload: **under five seconds for cold 1,000-parent
scans**, and **under one second for ordinary one-bar updates**, with separate
budgets for provider I/O. These are proposed acceptance targets, not achieved
measurements or universal bounds. Returning every admissible alternative always
has an output-size lower bound. Arbitrary lookback cuts, beam-search/top-N caps,
timeouts that drop hypotheses and more worker threads do not solve the exact
recognition problem and are not the proposed fix.

## October 4 automated-outlook fix: implementation and validation

The KDE numerical fix is now implemented in `StableVolumeProfileKde`. It retains
the existing sampled candidates, typical prices, absolute volume weights,
lookback, bandwidth and warm-up rules, using fixed-precision Gaussian evaluation
instead of TA4J's unbounded decimal Taylor expansion. Normalized weights avoid
overflow; compensated density summation reduces rounding loss. Zero bandwidth,
zero-volume samples and lower-price ties are handled explicitly. The same fix
is used by the optional KDE chart indicator, which had the identical failure path.
No pattern enumeration limits, indicator periods or headline scoring rules change.

Summary and detailed analysis now share the same immutable core enrichment result.
Exact OHLCV/timestamp content, effective profile and output count identify that
work; detailed research is cached separately. Bounded caches use striped
single-flight computation, so simultaneous identical requests share a result and
failed calculations can be retried. Corrected candles cannot reuse the old result.

Tracking no longer invalidates each user's presentation cache unconditionally.
Instead, its dedicated fresh-view path re-reads preferences, candles, recent
signals, activity and benchmark inputs, while reusing only content-keyed numerical
calculations. This deliberately avoids caching a whole tracking decision on
timestamp alone or introducing a fragile dependency fingerprint for notification
state. Related-data queries and cheap view construction still run on each check;
user-specific deliveries are never shared across users. Notification baseline,
classification-change and duplicate-retry rules remain intact.

Tracking logs indicator input sizes, summary/detail mode, related-data stage,
whole-outlook substep timings, and report/notification-save duration at INFO.
Core/research/KDE timings and numerical cache hits are available at DEBUG for
`TechnicalIndicatorEnrichmentService`.

### SHW replay results

Java **25.0.1**, 768 MB heap, 520 stored monthly bars, the same effective subscriber
profile as the diagnosis, 519 input bars and 320 emitted bars. Three fresh JVM
snapshot-only detailed-outlook runs measured **468-540 ms**, including indicator
analysis and view construction; corresponding repeated calls took **19-28 ms**.
A separate tracking-style summary-then-detail replay took **464 ms + 151 ms**,
with the same headline classification in both views. Before the fix, even the
detailed indicator substep alone remained unfinished at the diagnostic's
60-second cutoff, consistent with the supplied multi-minute production log.

These are local calculation measurements, not live queue throughput or email
latency. The replay uses saved market candles/settings and empty signal/activity
repositories; database/provider I/O, real related-data contents and notification
delivery are excluded. `tools/OutlookPerformanceReplay.java` makes that boundary
explicit. No live worker was started, database changed or real notification sent.

### Numerical and behavioral validation

Tests compare ordinary KDE cases against the original decimal implementation,
and widely separated prices against an independent 70-digit, range-reduced
Gaussian reference. They cover extreme price/bandwidth ratios, ties/near ties,
duplicate prices, zero/negative volume, zero bandwidth and causal prefixes.
Core TA4J parity, summary/detail scores, corrected candles/benchmarks/signals,
concurrent reuse, failed-cache retry and the real outlook-to-tracking pipeline
are also exercised.

The final targeted Java 25 run passed **75 tests, zero failures/errors/skips**,
including scheduled alerts and historical enrichment consumers. Results are in
`target/outlook-final-tests.log`; `git diff --check` also passes. No application
restart or database migration was performed.

Research-only KDE values are **not** promised to be bit-identical to the old
unstable decimal exponential. The existing 520-bar research comparison now gives
a KDE mean ATR error of 1.1323689213945263 versus the unchanged binned result of
1.1177169908068278: a 1.31% disadvantage, below its 2% materiality threshold.
Its regression expectation therefore changes from `ESTABLISHED_BINNED_PROFILE`
to `NO_MATERIAL_DIFFERENCE`; the recommendation remains
`KEEP_ESTABLISHED_PRODUCTION_INPUT`. This numerical correction does not promote
KDE into production scoring.

The separate cold developing-Elliott enumeration limitation documented below
remains unresolved by this outlook-specific fix.

## October 4 automated-outlook stall: diagnosis and proposed fixes

**Historical investigation and approved plan; implementation results are above.**

The supplied SHW monthly log isolates a different bottleneck from developing
Elliott detection. The job starts at 12:05:24.540, reaches automated outlook at
12:05:26.253, and remains there through the 120-second progress report. Its
115,062 ms CPU counter measures the job's worker thread, not whole-process CPU.
This is CPU-bound calculation, not evidence of a market-provider or database wait.

### Reproduced cause

A standalone, read-only diagnostic used the installed Java **25.0.1**, the 520
stored SHW monthly candles and the active subscriber's effective indicator
profile. Like `buildOutlook`, it selected the latest 519 raw bars and requested
320 output bars. It did not start Spring, execute a scheduled job, send email,
request provider data, or write to the database.

- Summary indicator analysis (`includeResearch=false`) completed in **142 ms**.
- Detailed analysis (`includeResearch=true`) remained unfinished at **60 seconds**,
  when the diagnostic watchdog terminated its own process.
- All six ten-second stack samples placed the main thread in
  `DecimalNum.exp -> VolumeProfileKDEIndicator.evaluateDensity -> getModePrice ->
  TechnicalIndicatorEnrichmentService.researchKdeMode`.

The installed TA4J 0.23.0 source confirms that KDE mode selection evaluates each
sample against every sample, and its default decimal exponential uses a direct
Taylor-series loop. The application's KDE bandwidth is derived from the first
bar in the entire input, rather than the recent KDE window. For this input the
60-bar KDE window spans approximately 168.86 in typical price, but bandwidth is
only 0.025. Gaussian exponent magnitudes therefore reach approximately
**22.81 million**. Evaluating these with that decimal Taylor loop is pathological;
the problem is not simply the number of candles.

An isolated diagnostic evaluating the same weighted Gaussian formula and sampled
candidate prices with `Math.exp` took **0.86-2.02 ms** for the 60-bar kernel and
selected mode price 326.72333. This is a feasibility measurement, **not** a shipped
fix, full-outlook timing, or proof of decimal-result equivalence. The original
decimal calculation did not finish, and numerical regression tests are required.

`TechnicalOutlookTrackingService.evaluateSubscription` first builds a summary,
then synchronously calls the detailed outlook when classification changes. That
detailed path includes this research-only KDE, although KDE is not a scored
headline input. Summary builds below 500 ms log only at DEBUG, explaining why the
supplied INFO log can show no completed outlook before the stall. This call path
plus the isolated reproduction identifies the likely production hotspot; no
stack dump from the original, already-stopped application is available.

The previous AAPL weekly replay was not adequate coverage: it measured indicator
analysis on another symbol with an interval factory profile, not SHW with the
subscriber's effective profile, and did not execute the full tracking path.

### Proposed implementation order

1. **Replace the pathological KDE numerical path, narrowly.** Use a dedicated,
   stable floating-point Gaussian evaluator for this research-only field, or an
   isolated double-backed TA4J KDE. Preserve the lookback, bandwidth, volume
   weighting, candidate prices, warm-up rules and lower-price tie preference.
   Do not switch every production indicator to double arithmetic, change
   bandwidth merely to hide the delay, drop historical context, or cap detected
   patterns. Validate finite inputs, underflow behavior and near-tied modes.
2. **Separate scored analysis from optional research/report work.** Reuse the
   core result between summary and detailed requests instead of recomputing it
   under separate `includeResearch` cache keys. Share identical numerical work
   by candle content and effective profile, while keeping user-specific alerts
   and deliveries isolated. Add single-flight handling for matching concurrent
   calculations. Keep report completeness explicit if optional research fails;
   never silently omit scored inputs or skip classification notifications.
3. **Avoid unnecessary tracking recomputation.** Replace unconditional per-user
   outlook invalidation with dependency-aware reuse. A valid key must include
   candle revisions, effective preferences, benchmark data and relevant signal
   changes; candle timestamp alone is insufficient. Preserve baseline and
   notification idempotency across corrections and retries.
4. **Add actionable stage timing.** Report summary/core indicators, research
   indicators, KDE, related-data reads, report construction and notification
   persistence separately, including cache hits and input/window sizes. A
   timeout by itself is not a numerical fix, and an executor timeout must not
   leave the expensive CPU task running behind it.
5. **Validate the actual failing route.** Add the SHW monthly/effective-profile
   regression, extreme price-to-bandwidth and near-tie numerical tests, and
   full tracking tests for both unchanged and changed classifications. Compare
   all scored indicators, headline classifications and notification behavior;
   verify summary/detail parity and corrected-input cache invalidation. Repeat
   cold runs under Java 25.0.1 before measuring warm-cache benefits. Target
   sub-second numerical analysis and a local full-outlook calculation below
   two seconds for this fixture, excluding external delivery latency; these
   are acceptance targets, not achieved production measurements.

### Complexity and scope

For `W` KDE samples and `T` iterations per decimal exponential, the current mode
scan costs approximately `O(W^2 * T)` arithmetic operations; big-decimal costs
and `T` depend on the values, not just candle count. A stable fixed-precision
exponential removes this pathological iteration factor, giving an `O(W^2)`
sampled-mode scan. At the observed `W=60`, only 3,600 pair evaluations are needed.
Grouping duplicate prices or exploiting symmetric distances can reduce work
further, but an exact unrestricted Gaussian scan is not claimed to become linear.

Viewport batching cannot fix this scheduled-report calculation. The earlier
cold developing-Elliott enumeration limit remains a separate unresolved issue;
this diagnosis does not attribute the SHW outlook stall to that algorithm.

## October 4 viewport and detector performance follow-up

Historical chart overlays now request `from`/`to` batches through
`candlestick-patterns/history/range`, `elliott-waves/history/range`,
`elliott-waves/candidates/range` and `harmonic-formations/history/range`.
The browser derives the viewport from candle dates, adds a 20-candle margin,
debounces changes by 180 ms, reuses overlapping batches and merges duplicate
geometries. Requests for obsolete batches are aborted; late responses cannot
restore disabled overlays. Existing overlays remain visible while replacements
load. Browser batches have a bounded 96-entry, 60-second cache; changing the
chart interval resets it. Full-history report APIs remain available.

Candlestick overlays calculate recognition and lifecycle outcomes for the
requested candles only. They return a dedicated DTO without confidence scores
or scoring evidence: those fields are not used by the chart. This avoids running
Elliott/harmonic confluence just to draw a candlestick marker or its outcome card.
The detail page still calculates the complete causal score, now for the selected
signal timestamp instead of rescoring the archive. Scored historical reports
and live alert eligibility retain their confluence calculations.

Long structures beginning before the viewport are retained. Shared completed
candle snapshots preserve the earlier pivot/indicator context and later outcome
candles, respect malformed-data boundaries, and invalidate on candle revisions
or a changed completion cutoff. Range caches include effective settings and
detector versions. Range requests use stored candles rather than synchronously
refreshing the market provider again.

**Input-loading boundary:** the first cold snapshot still reads the completed
archive. Subsequent batches share that context. Elliott's curated structure
selection is also computed once for the snapshot/settings because its overlap
ranking is global; standalone candidate searches are restricted before expensive
subdivision work. Arbitrarily truncating the left context would change long
formations. This is not a claim of bounded cold database I/O or a fully persisted
incremental pivot index. A new candle revision currently invalidates historical
range caches as a whole; selective dependency invalidation remains future work.

### Shared detector changes

- Harmonic contained-swing hierarchy construction uses linked neighbors and a
  priority queue. After removing a swing, only affected pivot neighborhoods are
  revisited, preserving removal ties and all admissible geometries. Partial legs
  are checked before expanding the remaining combinations.
- For `P` pivots and structural window `W`, hierarchy construction changes from
  repeated `O(P²)` searches/copies to `O(P log P)`. Geometry enumeration visits
  `O(P W)` neighborhood entries instead of `O(P²)` hierarchy entries. With the
  existing bounded `W`, this removes the quadratic hierarchy replay factor; it
  does not make every detector operation constant-time.
- Elliott confirmation indexes are prepared once per validated candle window.
  Repeated confirmation queries change from `O(N)` scans to `O(1)` lookups, and
  prefix views cannot see confirmations beyond their end. Subdivision enumeration
  reuses contained-leg connections; cached connection storage is bounded without
  excluding hypotheses. Historical scans share subdivision memoization.
- Confluence builds reusable validated prefix views across malformed/duplicate
  boundaries and uses binary timestamp lookup. Compact Elliott availability
  observations, exact harmonic scans, indicator enrichment and selected Elliott
  subdivisions/results can be reused across calls. Keys include exact candle
  values and effective settings, so corrections and child-data changes cannot
  reuse mismatched results. Completed harmonic scans and compact confluence
  observations also use the existing durable checkpoint store during scheduled
  work. Explicit sliced-recovery runs still exercise their continuation path.
- Candlestick lifecycle timestamp lookup is binary rather than a full scan.
  Pattern recognition itself was already inexpensive; unused confluence was
  the principal historical overlay cost in the measured case.

### Measurements and remaining limits

Read-only saved AAPL weekly snapshot: 1,000 parent bars, 4,821 daily children for
the developing-wave replay, Java **26.0.1** normal JIT, 768 MB heap. These are local
component measurements, excluding HTTP, database/provider I/O and rendering.

| Operation | Before | After |
|---|---:|---:|
| 96-candle candlestick range, cold, 9 signals | 19,679 ms including unused confluence | 429 ms for the overlay DTO |
| Same candlestick range repeated | 2,013 ms including scoring | 45 ms for the overlay DTO |
| Harmonic history, third pass, result-cache misses | 65.707 ms | 43.882 ms |
| Newly available harmonics, third pass, result-cache misses | 124.811 ms | 90.816 ms |
| Harmonic history, reused exact input | No cross-call result cache | 0.422 ms |
| Newly available harmonics, reused exact inputs | No cross-call result cache | 2.271 ms |
| Cold developing Elliott primary scan, 139 results | 15.998 s | 17.167 s |
| Cold weekly component replay | 25.649 s | 26.837 s |

The score-free overlay's **entire displayed payload** matches the corresponding
scored reference for all nine candlestick signals. Harmonic/candlestick detector
JSON is semantically identical to the saved pre-change output. Developing
Elliott primary JSON remains byte-identical, including alternatives and evidence.
Cache-miss harmonic measurements vary only the display limit, which does not
affect `detectAll` or new-signal recognition, to isolate computation from reuse.

**Cold developing Elliott analysis has not become faster in this comparison.**
It still enumerates admissible parent counts and is not generally linear in the
number of candles. A sub-second guarantee for every new Elliott bar, and the
earlier sub-10-second cold weekly target, remain unmet. An experimental additional
lookup index was removed after it failed to improve the measured replay. The
viewport and shared-work improvements must not be presented as resolving that
remaining cold-primary bottleneck.

Validation includes independent randomized hierarchy enumeration, viewport/full
candidate equivalence, prefix confirmation/causality, corrected-input caches,
snapshot revision races, checkpoint recovery, scheduler/lifecycle regressions,
and the opt-in **1,000/1,000** synthetic Elliott count benchmark. **180 Java tests
and 61 JavaScript tests passed**, with no failures or skips. The combined
acceptance run and JavaScript suite are recorded in
`target/viewport-final-acceptance.log` and `target/viewport-final-js.log`.
Measurements and output comparisons use `target/viewport-*.log/json` and
`target/recovery-replay/AAPL-v2-1wk-1790373600/primary-viewport-*.json`.
`tools/README.md` contains reproduction commands. No application restart,
database migration, queue mutation or real-user notification was performed.

## September 30 performance and queue follow-up

This section supersedes the earlier operational observations about rotating
30-second slices and an outstanding backlog. The application was stopped before
maintenance. Migration V75 and `tools/ClearTechnicalBacklog.java --apply` were
applied to the local database on **2026-09-30 at 20:24:31.403293 Europe/Brussels**:

- Cancelled all **15 pending/processing technical checks**, preserving their audit
  rows as `CANCELLED`; removed **21 unfinished analysis checkpoints**.
- Retained candles, saved signals, completed checkpoints, completed/failed job
  history and delivery records. Cancellation is not recorded as successful analysis.
- Saved a cancellation cutoff so startup recovery cannot recreate the discarded
  schedules. Recorded the latest skipped cron instants in the schedule ledger for
  compatibility with the previous binary.
- A subsequent read-only audit verified **zero pending/processing checks and zero
  unfinished checkpoints**. The application has not been restarted by this work.

### Execution and duplicate handling

Only one technical check is dispatched at a time, in oldest `scheduled_for, id`
order across symbols and intervals. It retains its lease and computation caches
until completion. Checkpoints are still written periodically for recovery, but
the normal 30-second checkpoint no longer causes a yield or starts another job.
An earlier deferred or processing check also blocks later checks at the database
claim layer, including claims from another application instance. Infrastructure
outages retain that ordering; genuine calculation failures keep the existing
bounded retry policy and terminal `FAILED` state. Email/insider workers remain
independent.

Publication now serializes on the rule and checks the exact database identity
`(alert_rule_id, pattern, signal_candle_timestamp)`, including archived events,
inside the delivery transaction. An existing event skips repeated creation of
events, plans and outbox messages. A racing violation of that specific unique
constraint is handled after rollback; unrelated integrity errors still fail.
The ordinary Elliott path also checks that identity before expensive confluence.
Failures now retain the failing stage and root cause in the job error, instead
of only the previous generic "Some technical stages failed" message.

### Detector work reduced

- **Elliott:** incremental pivot trajectories reuse earlier prefix scans while
  preserving each historical prefix, including replaced final pivots. Boundary
  lookup, range extrema and unchanged leg selections are reused. Necessary
  geometry checks run before child searches. Ranking avoids recursive explanation
  construction for losing candidates; returned winners still receive their full
  evidence, grammar and trade-plan data. Leading-diagonal compatibility is still
  evaluated before selection. No hypothesis cap or shorter history was introduced.
- **Harmonics:** shared range extrema replace repeated full candle scans, and
  containment scans visit only the pivots inside the candidate leg.
- **Candlesticks:** BUY/SELL evaluations share identical causal trend inputs;
  different user-configured direction models still run independently.
- **Confluence:** candlestick observations are calculated only for required
  timestamps. Scoring searches backwards to the latest observation from each
  other family, preserving simultaneous opposite-direction ties. Elliott
  observation replay omits explanation/preview construction that scoring never
  consumes. Full historical timelines remain available on demand.

These changes are in the shared detector paths used for history and new signals.
Corrected candle data or changed rules invalidate reused work; adjacent unchanged
parent and child prefixes can share valid legs in either traversal direction.

### Measured results

Saved local-market snapshots, Java 25 full JIT, 768 MB replay heap, 1,000 enriched
parent bars. Cold runs used fresh checkpoint namespaces. Weekly inputs include
4,821 AAPL and 4,897 CDR daily child bars. Component times exclude database
snapshot loading, initial enrichment, provider I/O, publication and mail.

| Calculation | Earlier recorded cold baseline | New cold measurement |
|---|---:|---:|
| AAPL weekly primary Elliott scan, 139 results | 926.268 s | 13.251 s |
| AAPL weekly component replay, including preceding prefix, harmonics and outlook | 2,076.438 s | 21.518 s |
| CDR weekly primary Elliott scan, 142 results | 2,191.634 s | 40.049 s |
| CDR weekly component replay, including preceding prefix, harmonics and outlook | No complete cold baseline | 75.989 s |
| NKE daily component replay, including two new harmonics and their confluence | Not measured under identical conditions | 12.283 s |

Both weekly primary outputs are **byte-identical** to their saved baseline JSON,
including evidence and alternatives. Earlier baselines predate the preceding
endpoint-filter changes as well as this follow-up, so these are cumulative local
improvements, not an isolated attribution to one optimization. The weekly cutoffs
produced no new harmonics. The separate NKE replay did exercise harmonic-triggered
confluence: **2.546 seconds** for the first assessment, with the second reusing the
timeline. NKE had no sufficiently early hourly history for a separate child input;
its replay used the existing parent-derived subdivision behavior.

An isolated same-snapshot comparison against the previously unchanged harmonic
and candlestick implementations produced semantically identical result JSON:
163 candlestick detections and 37 harmonic formations. Warm third-pass timings:

| Detector operation | Reference | Updated |
|---|---:|---:|
| Candlestick history over 1,000 prefixes | 97.032 ms | 82.370 ms |
| Latest candlestick check | 0.078 ms | 0.064 ms |
| Harmonic history | 236.958 ms | 67.796 ms |
| Newly available harmonics | 465.916 ms | 126.025 ms |

Cold harmonic history fell from 461.955 to 258.133 ms and its new-signal comparison
from 698.962 to 267.315 ms. These are local observations, not universal latency
guarantees. **The proposed sub-10-second cold target is not met for the large
weekly cases, and sub-second analysis of every newly appended bar is not proven.**
Continuous execution eliminates queue rotation but does not make a large cold
Elliott search instantaneous.

Validation: **154 distinct tests passed**, including isolated PostgreSQL tests
for oldest-first claims, simultaneous workers, cancellation recovery, leases and
delivery transactions; archived-event duplicate suppression; independent pivot
prefix equivalence; child-cache correction/rewind checks; sliced restart
equivalence; causal confluence; harmonic and historical candlestick tests. The
opt-in synthetic Elliott test recovered **1,000/1,000** intended counts, endpoints
and nested leg sets. Synthetic recall does not establish real-market accuracy.
No real-user messages were sent; SMTP integration used a local test sink.

Evidence: `target/speed-final-regression.log` (152 tests),
`target/speed-queue-final.log` (15 tests, including two additional queue tests),
`target/speed-{aapl,nke}-full.log`, `target/speed-cdr-final.log`,
`target/speed-{reference,current}-signals.log`, and
`target/speed-final-queue-audit.log`. Diagnostic artifacts under `target` are local
and are not committed. Reproduction commands are in `tools/README.md`.

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

## Runtime and delivery remediation (26 September 2026)

The supplied log shows a successful application startup followed by independent
problems. Twelve Data rejected CDR for plan coverage and rejected the submitted
index/test symbols. Yahoo's fallback failed at the transport/TLS layer; those
failures do not establish that the instruments have no candles. MARA and NFLX
loaded candles successfully and then spent their time in Elliott enumeration.
The generated `WLxxxxxxxx` symbols came from integration fixtures in the live
local schema. A blanket four-tickers-per-minute limit would not fix any of these
causes. Provider request quotas remain enforced by the existing market-data
governor; no additional ticker throughput cap was added.

Implemented changes:

- Elliott scans use immutable validated candle ranges with constant-time range
  hashes, binary timestamp slicing, bounded pivot caches and range extrema.
  Enumeration reuses its path buffer, counts alternatives without retaining
  every primary-path key, and materializes detailed results only for winners.
  Identical primary scans are reused within a symbol job. Lifecycle viability
  first checks the primary count, then searches only the requested cycle if
  necessary. The 1,000-bar history and admissible hypothesis set are retained.
- Confluence replays only other signal families and uses lightweight Elliott
  observations instead of constructing a trade preview for every alternative.
  Prefix-based availability and the eight-candle causal window are retained.
- Yahoo transport errors, rate limits and provider-wide HTTP failures stop alias
  searches immediately and trigger a shared cooldown (60 seconds by default;
  `YAHOO_FINANCE_FAILURE_COOLDOWN_SECONDS`). Missing-symbol responses still allow
  ordinary alias resolution. Transport errors are no longer described as proof
  of absent candle data.
- Parent candle refresh failures defer their jobs with exponential backoff,
  capped at 30 minutes. V71 separates calculation failures from the monotonically
  increasing claim counter, so provider outages cannot consume calculation
  retries or weaken lease ownership. Deferred jobs remain ordered per symbol
  and interval. A permanently unsupported instrument still needs its provider
  coverage/symbol corrected; deferral cannot supply missing market data.
- Non-Elliott rules run before the developing Elliott stage. Rule failures are
  collected while remaining rules and outlook evaluation continue. Successful
  event/outbox transactions remain committed when another stage needs a retry.
  Developing invalidation updates and their outbox insertion are also atomic.
- Delivery logs distinguish an email queued for delivery, a retry, and SMTP
  acceptance. Durable initial/follow-up receipts are written only by the outbox
  after successful SMTP submission. SMTP acceptance is not proof of inbox arrival.
- Spring Boot integration tests use a disposable PostgreSQL schema and disable
  automatic scheduled work. V72 disables follows/settings only for accounts
  matching all named-watchlist fixture markers, and cancels unleased queued jobs
  for the exact generated assets. Accounts, assets and signal history are retained.
- The shared IntelliJ **StockWatch full JIT** application run configuration omits
  the startup-oriented `-XX:TieredStopAtLevel=1` shown in the original command.
  Use the project's Java 25 SDK and this configuration for sustained analysis.

Validation on Java 25:

- The combined detector/provider/scheduler/delivery run passed 153 tests, with
  zero failures or skips. It includes the 1,000-case synthetic recall benchmark,
  primary/exhaustive winner and alternative-count comparisons, lightweight/full
  observation equivalence, lifecycle viability equivalence and causal confluence
  regressions. See `target/final-signal-remediation-tests.log`.
- After adding fixture quarantine and the invalidation transaction boundary,
  the final scheduler/durability run passed all 30 tests. It checks provider
  deferrals followed by all three calculation retries, stale-owner fencing,
  outbox rollback, technical-email deduplication, failed-SMTP retry and receipt
  timing, and preservation of a similar non-fixture account. The SMTP sender is
  mocked; these tests cannot email users. See
  `target/final-delivery-acceptance-tests.log`.
- Fresh read-only replays of cached daily data (1,000 enriched candles, 768 MB
  heap, no JFR) completed in **21.8 seconds for MARA (47 primary results)** and
  **20.0 seconds for CDR (42 primary results)**. Earlier profiled MARA runs fell
  from 186.3 to 83.6 seconds during optimization. These are single local runs,
  not a controlled benchmark or an end-to-end job latency guarantee. Child-series
  analysis, previous-candle comparisons and confluence can add further work.
- Flyway migrations V71 and V72 were applied to the local application's `public`
  schema and verified at version 72. All five active insider follows on generated
  test assets are now inactive; 84 pending technical fixture jobs were quarantined.
  Stored signal/receipt counts were unchanged. The remaining queue had 182 pending
  and two processing records; no application JVM was running, so those processing
  records were not evidence of active work. The pre-existing 19 failed non-fixture
  jobs were not blindly reopened or relabeled as successful.

Remaining operational limits: independent Java HTTP clients still timed out
against both Yahoo query hosts in the final connectivity check. The configured
SMTP connection/authentication probe still failed with `MailConnectException`;
it sent no message. The exact network/provider cause has not been established,
and TLS validation has not been weakened. Twelve Data plan coverage must also be
sufficient for the requested instruments. The code and local schema changes are
ready, but the live queue has not been drained and real-user email delivery has
not been demonstrated. Start the application with **StockWatch full JIT** after
restoring provider/SMTP connectivity, and verify the queued/accepted logs and
persisted receipts. This remains targeted acceptance, not a claim that the
previously documented unrelated full-suite failures are fixed.

## Live-run follow-up: confluence memory and outlook delivery (26 September, evening)

The subsequent log disproved end-to-end performance acceptance: NFLX remained
in the apparent harmonic stage for over 35 minutes and AAPL in the apparent
candlestick stage for over 18 minutes. The earlier 20-second measurements tested
one primary Elliott scan, not publication-time confluence. They were insufficient
to establish live job throughput. The submitted launch command also still used
`-XX:TieredStopAtLevel=1`.

A read-only replay used NFLX's 1,199 cached daily input bars preceding the exact
scheduled time `2026-09-22T19:45:36.075990Z`. Harmonic detection took 596 ms and
returned one Alternate AB=CD formation. Its confluence scoring then reproduced
`OutOfMemoryError: Java heap space` with a 768 MB heap. The stack led through
`findDevelopingObservations` into exhaustive Elliott enumeration. The prior
implementation retained full alternative results and geometry sets for multiple
prefixes. An intermediate compact-set implementation still retained millions of
geometry keys; increasing heap or changing the ticker rate is not the remedy.

The final correction streams observations and computes the difference between
adjacent prefixes. For unchanged parent history, a previously admissible path
remains observable when its seed pair, shared pivot-scale support and timely
confirmation were already present. Only changed support/seeds or newly available
confirmation can introduce evidence. Unchanged historical endpoints are skipped
in that difference calculation; the public primary/exhaustive APIs retain their
existing hypothesis semantics. Parent-only subdivision caches can be reused on
exact appends and invalidate on rewinds, corrected history, rules or child-data
changes. Harmonic replay retains only adjacent compact geometry sets. Progress
logs now explicitly identify `scoring cross-pattern confluence`.

The corrected NFLX replay completed harmonic detection in **0.7 seconds** and
its Elliott confluence calculation in **51.8 seconds**, with one confluence
evidence entry, using Java 25's normal JIT and a 768 MB heap. It made no provider
requests, database writes or email sends. This replay isolates the failing
component; it does not establish a 52-second whole-job SLA or live queue drain.
Evidence: `target/confluence-nflx-before.log` and
`target/confluence-nflx-incremental.log`.

The log also exposed a separate direct-SMTP path for automated outlook changes.
Those new notifications now enter the encrypted durable outbox with an outlook
notification ID, a deduplication key, current watchlist email-policy checks and
an SMTP-acknowledged receipt. V73 adds the outlook foreign key, including cascade
cleanup when its notification is deleted. It was applied successfully to the
local `public` schema (now version 73). Previously failed direct-send outlook
emails have not been automatically reconstructed or resent.

Validation: **104 detector/confluence/scheduler tests passed**, including the
1,000-case synthetic recall benchmark and exhaustive-versus-incremental geometry
differences at every prefix of nested zigzag, flat and triangular-B fixtures.
**28 notification/outlook/durability tests passed**, including deduplication,
failed-SMTP retry, receipt timing and outlook cascade cleanup with a mock sender.
Logs: `target/confluence-final-tests.log`, `target/outlook-delivery-fix-tests.log`.

The application was already stopped when this follow-up began and was not
restarted by the diagnostic. Restart with **StockWatch full JIT**, or enable
**Run > Edit Configurations > Modify options > Disable launch optimization**
on the existing Spring Boot configuration. Verify that the command no longer
contains `-XX:TieredStopAtLevel=1`. JetBrains documents that this startup option
reduces long-term performance in its
[Spring Boot run configuration reference](https://www.jetbrains.com/help/idea/run-debug-configuration-spring-boot.html).
Yahoo transport failures, Twelve Data coverage rejections and SMTP connectivity
remain separate operational blockers. Queue insertion is not email delivery.

## Email, long-running analysis and recovery follow-up (27 September 2026)

The supplied log contains several independent failures. The 18-hour job timers
include long host pauses: Hikari reports gaps of 11h15m, 4h16m and 1h29m. Those
figures are not CPU execution times. However, AAPL WEEKLY had already spent over
55 minutes in developing-wave detection before the first overnight gap. There
was a real analysis bottleneck as well as a host-availability problem.

| Issue in the log | Change and evidence | Remaining boundary |
|---|---|---|
| Gmail connection timeout on port 587 | An authenticated, no-send probe succeeded on port 465 with implicit TLS and hostname verification. Local `.env` now selects 465, SSL on, STARTTLS off. `application.properties` supports that mode. Credentials were not changed or printed. | Inbox delivery still requires one explicitly designated test recipient. SMTP acceptance does not prove inbox placement. |
| Lifecycle SMTP failure aborts BA's calculation | Candidate-gate, lifecycle and trade-plan outcome notifications now use the transactional durable outbox. Sent timestamps are recorded after SMTP acknowledgement. Transition-specific keys preserve deduplication. | SMTP plus a database acknowledgement cannot be one atomic transaction; delivery remains at-least-once. A stable Message-ID is not an exactly-once guarantee. |
| Repeated outbox failures, poor diagnostics and bursts after resume | Shared provider cooldown, bounded batches, exponential row retry with jitter, nested failure classification and owner-fenced lease checks. Logs include failure category, attempt and duration without recipient/message contents. | Permanent credential/configuration failures still require operational correction. The queue does not repair SMTP infrastructure. |
| Elliott and confluence occupy technical slots for minutes/hours | Explicit continuation stacks and versioned durable checkpoints yield completed work back to the queue. Completed results are reused across later stages; corrected data/rules invalidate keys. Exact containment/range pruning and best-per-structure grammar selection reduce repeated work. | The 30-second slice is cooperative, checked between parent nodes; an expensive individual subdivision can exceed it. Whole-job latency must be measured separately from fairness. |
| Repeated old jobs redo expensive analysis | Checkpoints retain completed seeds and winning results, keyed by complete parent/child input and settings. Scheduling continuations by availability lets another symbol run while preserving each symbol's historical order. | Historical runs are retained; this does not silently discard old checks or shorten the 1,000-bar recognition window. |
| Weekly outlook analysis is also expensive | Bounded indicator-analysis cache keyed by complete immutable input/profile/version. | Initial calculations still cost time; changing a candle invalidates reuse. |
| Late/lost workers can publish after resume | Cancellation checks in detector loops, renewable leases, and a fresh ownership check in publishing transactions. | Checkpoints may be recomputed after eviction; stale owners cannot acknowledge another owner's job. |
| Closed PostgreSQL connections cause worker exceptions | Explicit driver/pool timeout and lifetime settings; shared database outage cooldown and recovery probe; dispatcher releases slots on every exit. Scheduled technical/insider/import work uses recovery handling. | No timeout can run while Windows/JVM execution is suspended. Local PostgreSQL and the app must remain available for continuous service. |
| Provider failures consume retries or block all-provider refresh | Infrastructure/provider deferral is separate from calculation-failure limits, including insider jobs. Retry recovery preserves monotonic claim generations. | Invalid inputs and actual calculation defects still have finite retry limits. |
| Twelve Data repeats plan/invalid-symbol rejections | Bounded six-hour negative coverage cache, checked before reserving credits. Yahoo remains the fallback; both Yahoo Java HTTPS transports passed a read-only probe. | CDR/TPG0 entitlements and the missing Twelve Data index mapping are not invented or bypassed. A cached rejection can be retried after expiry/configuration change. |
| HD is incorrectly reported as requiring a paid plan | Canonical Home Depot is XNYS, but its stored Twelve Data alias was XLIM. Removed that exact conflicting alias and added request/response MIC checks. A direct HD/XNYS probe returned HTTP 200 with XNYS. | Other provider identity mismatches are rejected; they must be mapped using verified exchange metadata. |
| Partial child history triggers repeated 1,000-candle bootstraps | Successful short bootstrap state survives restarts; subsequent refreshes use the normal delta/cooldown. Empty responses and increased history requirements still retry bootstrap. | Partial history is not labelled complete. Provider historical coverage remains finite. |
| Existing Elliott event moves onto another event's unique identity | Publication checks the destination rule/pattern/candle identity before changing an existing event. Regression reproduces the conflict. | This preserves existing events; it does not merge unrelated historical notifications. |
| IntelliJ uses startup-only C1 compilation | Added/selected `StockWatch full JIT` run configuration and a startup warning for `TieredStopAtLevel=1`. | Launch with that configuration after rebuilding. |
| Progress logs conceal whether analysis is working or paused | Heartbeats now include worker CPU time; runtime monitor identifies long scheduling gaps. Checkpoint and sanitized infrastructure logs distinguish continuations from failed calculations. | CPU profiles and end-to-end replay remain necessary to establish throughput. |

Google documents Gmail's SSL port 465 and TLS port 587 in its
[IMAP/SMTP reference](https://developers.google.com/workspace/gmail/imap/imap-smtp).
The successful local probe establishes connectivity/authentication for this
machine at the time of the check; it is not a permanent network guarantee.

### Local schema and queue recovery

V74 was applied and validated on the local database. It adds analysis checkpoints,
partial-history refresh state and a separate insider calculation-failure counter.
The idempotent `tools/RecoveryMaintenance.java` defaults to a read-only audit;
`--apply` performs only the explicitly classified recovery operations.

Recovery reopened 3 direct-mail calculation failures, 9 historical provider
failures and 7 notification-identity conflicts, and released 2 expired analysis
leases. The final audit reports **1,394 completed, 54 pending, 84 failed** technical
jobs. All 84 remaining failures are quarantined integration fixtures; they were
not reopened. The outbox contains **47 pending and 4 previously SMTP-accepted**
messages. No live backlog was sent by this remediation.

### Verification and operating procedure

Focused verification covers checkpoint serialization/restart equivalence,
input invalidation, exhaustive-versus-pruned subdivision enumeration, grammar
selection equivalence, the 1,000-case synthetic benchmark, provider identity and
budget boundaries, notification identity conflicts, transactional outbox
rollback/deduplication, expired-owner retry, provider-wide cooldown and actual
SMTP acceptance against a loopback-only mail sink. Integration tests use an
isolated database schema with automatic workers disabled.

The final detector/outbox run passed **80 tests, zero failures/errors/skips**,
including **1,000/1,000 synthetic Elliott cases** and the loopback SMTP test
(`target/recovery-corrected-validation.log`). The first combined run exposed a
local-hostname lookup during MIME Message-ID generation/SMTP greeting: the mail
sink timed out while otherwise mocked delivery paths could spend 15 seconds
before sending. Outbox MIME messages now generate the stable ID directly, and
`SMTP_EHLO_HOST` supplies the greeting hostname. The corrected real-socket sink
verifies the exact outbox Message-ID, SMTP acknowledgement and payload erasure.

Read-only AAPL/CDR weekly replays use **1,000 parent candles plus the full available
daily child window** at the logged cutoff. Files under `target/recovery-replay`
hold their snapshots and resumable checkpoints; the helper sends no messages,
does not refresh providers and does not mutate the live database. These component
replays do not substitute for a complete scheduled-job/queue-drain measurement.
The large AAPL/CDR weekly replays had **not yet completed** in the 27 September
verification. At that point their continuations demonstrated saved progress and
worker fairness, not an acceptable full-job latency. The 30 September follow-up
below records the subsequent verification. A 30-second continuation alone does
not establish that the original weekly-runtime issue is fully resolved.
`tools/README.md` contains the restart commands.

1. Build and run using **StockWatch full JIT** on an awake host with PostgreSQL
   available. A sleeping development laptop cannot provide 24/7 execution.
2. Verify one designated test recipient, then monitor the existing outbox by
   SMTP acknowledgements and receipt timestamps. Do not reset delivered rows.
3. Measure completed checks, oldest pending schedule, continuation progress,
   CPU time and outbox age. A continuation is expected progress, not a failed
   calculation or a completed job.
4. If a provider/DB outage returns, retain checkpoints and leased-owner fencing;
   correct connectivity and allow backoff/recovery to resume work. Avoid blanket
   retry-counter resets and increases in worker/pool size.

## Weekly replay completion follow-up (30 September 2026)

The remaining weekly acceptance work was resumed from the saved AAPL/CDR candle
snapshots. Profiling found substantial time in eager suffix reachability checks
inside subdivision selection. Selection now checks suffixes lazily after a valid
prefix reaches them. It also stops scanning later endpoints once intervening
prices straddle the leg's start beyond the largest possible endpoint tolerance:
no later endpoint can enclose that leg. Fixed parent geometry is checked before
searching child subdivisions. The final refinement also rejects impossible parent
endpoints before allocating continuations and checks that a child motive prefix
still has room for a legal IV/V endpoint. These changes preserve enumeration
order, the 1,000-parent-candle window, child history and admissible hypotheses.

The exhaustive enumeration regression now exercises bullish and bearish inputs,
both small and larger pivot sets, and both diagonal policies. The combined
detector/scheduler/outbox acceptance run passed **110 tests**, including the
loopback SMTP sink and 1,000-case synthetic benchmark
(`target/resume-acceptance-tests.log`). After the final containment optimization,
the detector/checkpoint/confluence run passed **67 tests**, with **1,000/1,000 exact
synthetic counts** retained (`target/resume-pruning-tests.log`). The final endpoint
filters passed the same 67 checks using separately compiled classes, including
the numerical tolerance edge case (`target/resume-final-detector-tests.log`).
These runs had zero failures, errors or skips.

The final standard Maven build then passed **43 scheduler, durability, checkpoint
and enumeration tests**, also with zero failures/errors/skips
(`target/resume-final-build-acceptance.log`). The two final pruning-reference tests
also passed, including 4,000 randomized bullish/bearish parent paths checked
against the existing complete-geometry validators
(`target/resume-final-pruning-reference-tests.log`).

The replay helper now accepts a separate checkpoint-run name and reports process
CPU time as well as wall time. This permits cold measurements without deleting
prior checkpoints or candle snapshots; `tools/README.md` documents the command.

Before the final endpoint filters, the cold AAPL primary scan completed with
**139 results in 926.268 seconds**,
using 1,000 weekly parent candles and 4,821 daily child candles at
`2026-09-25T22:00:00Z`, Java 25 full JIT and a 768 MB heap. All **344 completed
seed checkpoints** available from the earlier implementation matched exactly,
including winning geometry, alternative counts and visited-node counts. This
is an equivalence comparison against saved work, not independently labelled
market ground truth. Other diagnostics ran during parts of the replay, so the
wall time is a local observation, not a controlled performance benchmark.

AAPL's complete component replay finished in **2,076.438 seconds wall time**
(2,584.547 seconds process CPU), with 139 primary and 139 previous-prefix results.
It found zero newly available harmonics at that cutoff, so no harmonic-triggered
confluence assessment was exercised by this particular market replay; the causal
confluence tests above cover that path. Outlook indicator analysis completed in
2.156 seconds. A fresh JVM then reused the completed checkpoints and finished the
same component replay in **3.472 seconds**, with byte-identical primary-result
JSON. Logs: `target/resume-aapl-v4.log`, `target/resume-aapl-warm.log`.

The CDR baseline primary scan also completed: **142 results in 2,191.634 seconds**,
using 1,000 weekly parents and 4,897 daily children with the same cutoff and heap.
All 69 completed seed checkpoints available from the earlier CDR implementation
matched. Its baseline preceding-candle scan was stopped to finish verification
on the final code. The final code's newly completed checkpoints (31 AAPL and 43
CDR) matched the corresponding baseline winners, evidence and alternative counts.
Visited-node counters were excluded from this last comparison because the early
parent filters intentionally avoid visiting impossible nodes. Matching completed
work was reused for the final continuation rather than recomputed; the manifest
is `target/resume-final-checkpoint-reuse.json`. These final resumed runs must not
be presented as fresh cold benchmarks of the final endpoint filters.

**Both final component replays completed on the standard Maven build.**

| Symbol | Primary results | Previous-prefix results | Newly available harmonics | Final verification |
|---|---:|---:|---:|---|
| AAPL | 139 | 139 | 0 | Resumed complete replay: 13.652 seconds; primary JSON byte-identical to the baseline. |
| CDR | 142 | 142 | 0 | Remaining analysis completed; a fresh JVM then replayed all stages in 2.641 seconds using completed checkpoints. Primary JSON byte-identical to the baseline. |

Logs are `target/resume-aapl-completion.log`,
`target/resume-cdr-completion.log` and `target/resume-cdr-warm.log`. Neither cutoff
produced a newly available harmonic, so the market replays did not execute a
harmonic-triggered confluence assessment; that path has the focused causal tests.

The final CDR continuation used **978.765 seconds of process CPU**, but reported
**26,055.905 seconds of wall time** because Windows slept during verification.
Power-Troubleshooter resume events were recorded at 19:33:53 and 19:39:00 local
time. These wall-clock gaps are not detector CPU time. A temporary system sleep
request was held during the remaining diagnostic and released when it exited;
it did not prevent every sleep/resume event. The helper now prints CPU time for
each slice as well as for the full run.

This closes the previously unfinished component replay and restart-verification
items. It does **not** establish fast cold weekly analysis or a completed live
queue-drain acceptance result. The measured cold baselines still took minutes,
and a fresh cold benchmark of the final endpoint filters has not been completed.

A fresh no-send SMTP probe authenticated successfully on port 465 with implicit
TLS and hostname verification. A read-only local audit found **91 SMTP-accepted
outbox messages**, zero pending outbox messages, zero retained accepted-message
payloads and zero missing associated event/outlook receipts. These persisted
acceptances occurred before this follow-up; this work sent no real-user emails.
SMTP acceptance still does not establish inbox placement.

The same audit found **1,470 completed, 15 pending, one processing and 85 failed**
technical jobs. Of those failures, 84 remain quarantined fixtures; the other row
(27605, CVX WEEKLY at `2026-09-25T22:00:00Z`) contains only the aggregate message
"Some technical stages failed;
completed signals were retained for delivery". That row was not reopened without
its underlying stage error. No application JVM was running, so the processing row
does not demonstrate active work. No live queue mutations or application restart
were performed. The audit is saved in `target/resume-recovery-audit.log`;
`tools/RecoveryMaintenance.java` now includes the receipt-integrity counts.

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
