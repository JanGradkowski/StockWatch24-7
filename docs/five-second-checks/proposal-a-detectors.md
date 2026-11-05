# Plan A: make each detection run cheaper

## Summary
I'd measure first, then make six changes that leave results exactly as they are. (1) Take database checkpoint I/O and multi-megabyte key serialisation out of the Elliott and harmonic inner loops. (2) Compute each per-event Elliott check once per job instead of once per event. (3) Give the 60-minute subdivision search a best-only branch-and-bound path. (4) Make the parent search tree cheaper to walk. (5) Build harmonic formations lazily. (6) Clean up small costs. Output and tie-breaking stay the same, and an extended record/compare harness proves it.

Estimated end state on the dev laptop (none of these numbers are measured: the container has JDK 21 and no Maven cache, and I'm read-only):
- scheduled daily check per stock: p95 about 1.5–2.5 s
- one alert-grade Elliott scan: about 0.3–0.45 s (now about 1 s, measured)
- harmonic alert scan pair: about 0.1–0.2 s
- custom alert preview: 4 s or less

Measured numbers come only from `docs/shared-analysis-store-plan.md`: 2.05 s mean / 3.8 s p95 per check, about 1 s per scan, 0.4 s for the previous-candle scan, as-of history 20 s / 1 min / 8 min.

## 1. What "the checks" are
I assume the budget is wall time for one stock and one interval, p95 on the dev laptop, and excludes waiting on Twelve Data.

| Flow | Entry point | Under 5 s? |
|---|---|---|
| **A. Scheduled check** | `ScheduledAlertService.processNextQueuedCheck` (`:297`): built-in rules, the developing Elliott scan with trade plan and projection (`:679-783`), invalidation (`:943-1000`), lifecycles, custom alerts (`:642`) | Yes, primary target |
| **B. Manual check** | `POST /api/alerts/{symbol}/check` → `AlertRuleService.checkLatestSignal:2115`. Elliott uses the old `detect()` path (`:2371`); harmonics run `detectNewlyAvailable`, which is two scans | Yes |
| **C. Custom alerts** | evaluate and baseline per stock; preview `POST /api/custom-alerts/preview`, whose Elliott budget is **6,000 ms** (`CustomAlertPreview:48`) | Yes; the preview budget must come down |
| **D. Chart overlays, cold** | `ChartController:178, 256, 350, 370, 385, 420` | Yes |
| **E. Background work** | as-of history (`ElliottHistoryBuilder`), baselines across many stocks | No; faster as a side effect |

**The projection is not the cost.** `ElliottProjectionPolicy.generate` (`:24-96`) and `ElliottProjectionService.evaluateOpenProjections` (`:182-238`) are O(n) arithmetic and take milliseconds. "Including the projection" therefore means the developing scan that feeds it (`ScheduledAlertService:679-682, 812-814`).

## 2. Diagnosis
Costs below are estimates unless marked measured.

**Likely regressions.**
- `a0e421d` (Oct 9) added 1,298 lines to the Elliott detector, including `AnalysisWork` and per-seed checkpoints.
- `4f237ea` (Oct 20) moved custom-alert scans into the same jobs, including the candlestick confluence that runs Elliott and harmonic prefix scans.
- `CustomAlertLoadTest` calls `engine.evaluate` without `AnalysisWork`, so its 2.05 s leaves out D1. Production jobs pay more.

**D1. Checkpoint persistence inside the search (scheduled jobs only).**
- `extendDevelopingImpulse` (`ElliottWaveDetectionService:2447-2498`) reads a checkpoint per seed (`:2456`) and writes one per seed (`:2491-2492`) whenever a job is running (`:2454`).
- Each write is an upsert in its own `REQUIRES_NEW` transaction with Jackson serialisation (`AnalysisCheckpointStore:19-27`).
- Seeds are adjacent pivot pairs over 7 scales (`:2842-2858`): about 300–450 on 1,000 candles, so roughly 800 database round trips per scan.
- Completed-scan results are written even when they came from the in-memory cache (`:2158, 2419`).
- Durable keys are SHA-256 of `toString()` over every candle (`AnalysisWork:40-44`, used at `:2155, 2344-2347`, `CrossPatternConfluenceService:97`). That is about 750 characters per candle, so megabytes per key.
- Harmonics do the same per scan (`HarmonicPatternDetectionService:141-149`).
- Estimated 0.5–1.5 s per scan, multiplied by the latest scan, the previous-candle scan, the viability scan and every confluence prefix.

**D2. Per-event work repeated without a shared memo.**
- `invalidateBrokenDevelopingElliott` (`ScheduledAlertService:943-979`) loops over every user's developing events for the symbol.
- For each it calls `hasViableDevelopingHypothesis` (`:3535-3551`). That runs a parent-only full scan (`:3542`). With no 60-minute candles, the whole 1,000-candle window stays searchable.
- It may then run a fully materialising enumeration (`:3546-3547`), including `developingPreviewAtr`, which copies candles (`:3260-3265`).
- It also calls `findActionaryStructureMismatch` (`:3433-3485`): two full-grammar `bestStrictSubdivision` calls per endpoint pivot at 4 scales (`:3473-3474`).
- Nothing is shared between events. Detectors are new instances per rule (`ScheduledAlertService:1529-1535`, `configured()` at `:170`), and the memos are per-instance `ThreadLocal`s (`:64-73`).
- The lifecycle service does the same per event (`CandlestickSignalLifecycleService:260-267, 441-445`).

**D3. Exhaustive subdivision search.**
- `computeStrictSubdivisions` (`:1862-1920`) runs for every new leg (`:3331-3338`) on 60-minute windows (`:3404-3418`).
- It enumerates every selection at 4 scales with no limit (`Integer.MAX_VALUE` at `:3749, 4073`).
- It builds evidence strings for each candidate (`:3761-3777, 4107-4118`) and a string dedupe key (`:1901, 4480-4484`), then sorts.
- In best modes it then keeps only the first validated candidate (`:1916`), and ranking mode validates trivially (`:1930`).
- Sharing leg context saved 0.6 s of a roughly 1 s scan, so leg validation is about 60% of a scan.

**D4. Parent search overhead.**
- Each prefix in `evaluateDevelopingPrefix` (`:2507-2597`) allocates TreeSets (`:2829-2840`), `List.copyOf` keys (`:2811`), path copies (`:2486, 2733, 2744`) and record keys for every leg (`:3335`).
- MSFT's history is about 1 s per candle even with leg reuse, which suggests the tree walk itself dominates on busy stocks.

**D5. Harmonics.**
- `detectNewlyAvailable` runs two full scans (`:193-202`).
- Every candle is boxed into a `ScanKey` (`:136-137`).
- `scanGeometries` builds String keys (`:351-386`, `:426-429`).
- Each matching pattern gets four `String.format` calls (`:663-686`) before the merge (`:379-380`) throws most away.

**D6. Small costs.**
- `grammarLabel` compiles a regex on every call (`:2106`), once per `findStrictSubdivisions` entry (`:1833`).
- `Set.of` is allocated per call (`:1866-1869, 1963`).
- `BoundedTtlCache.put` scans every entry under one global lock (`BoundedTtlCache:35-43`), which contends with the baseline threads.

## 3. Steps

| # | What to change, and files | Expected gain | Risk |
|---|---|---|---|
| **0** | Measure (1 d). Add counters to the existing debug line (`:2383`): seeds, prefixes, leg cache misses, candidates enumerated, checkpoint calls and time. Add a new opt-in `ScheduledCheckBenchmarkTest`: copy real stocks plus CSCO, AAPL and MSFT, create built-in rules for 3 users, 25 custom alerts and open II/IV events, then run `processNextQueuedCheck` with the real store. Report time per stage. Profile with JFR. If D1 is under 30% of job time, swap steps 1 and 3. | — | — |
| **1** | Move persistence out of the loop. Files: `ElliottWaveDetectionService` (`:2149-2175, 2343-2364, 2410-2441, 2447-2498`), `HarmonicPatternDetectionService:134-152`, `AnalysisWork`, `AnalysisCheckpointStore` (add a batched read). Changes: write a marker row per scan and read seed checkpoints in one query, only if a marker exists; write only when `checkpointDue()` fires (once per 30 s slice) and never on cache hits; compute keys lazily as SHA-256 over binary doubles, once per window. | est. 0.5–1.5 s per scan in jobs; results unchanged | Low. Keep `AnalysisCheckpointTest` resume tests green; add a test that a scan finishing within one slice makes 2 or fewer store calls |
| **2** | Compute once per job. Cache `configured(rules)` instances. Open one memo scope around the invalidation and lifecycle loops, and wrap the remaining public entry points. Memoise per job: structure lists per rule set, mismatch per (rules, stage, points). In `hasViable`, scan only the needed seeds (the `requiredDevelopmentKey` filter exists at `:2375`; seeds are independent, see `:2493`), materialise points only, and stop at the first viable count. In mismatch, process endpoints by ascending timestamp with a stable order and stop once timestamp ≥ best (keeps the strict `<` at `:3481`). | est. 0.5–3 s on stocks with open events | Low–medium: memo keys must be complete |
| **3** | Best-only subdivision search in `computeStrictSubdivisions` and the collect methods (`:3723-3782, 4058-4275, 4350-4377`). Collectors pass (kind, confidence, sequence) to a sink. Evidence is built only for returned winners; in ranking mode it is never read. Best modes need no dedupe because validation is deterministic. Order is confidence, then encounter order; span is the same for every candidate of a leg. Per scale and category the confidence bound is fixed (for example motive ≤ 96 − min(18, 2·grouped), floor 60). Skip any block that can't win, compute coarse scales first with a rank tuple so ties are preserved, and validate leading diagonals lazily. | 3–10x on leg validation, so a scan drops to about 0.45 s | Medium (ties). Test against the old implementation |
| **4** | Flatten the parent search. Dense pivot IDs; scale sets as bitmasks of 7 bits or fewer; precomputed `int[]` successor lists; persistent path nodes, materialised as lists only for snapshots; a `long`-keyed leg cache in front of the context cache. Visit order stays the same. | 2–3x on the tree walk; MSFT history 8 min → about 2–3 min | Medium |
| **5** | Harmonics. Merge on Candidates and format strings only for formations that survive; packed `long` keys; memoise classification per geometry across the two scans and across confluence prefixes; primitive OHLC arrays; a `ScanKey` without boxing. | 2–3x per scan | Low: map insertion order is unchanged |
| **6** | Small fixes and config. Static `Pattern` and sets; run TTL expiry at most once per second; make `CustomAlertFactsLoader` keep parents and children (`:636-637`) as `ValidatedCandleWindow`s so as-of prefixes stop re-sorting about 7,000 candles. Lower `custom-alerts.preview-elliott-budget-ms` from 6000 to 3000. | Preview at 4 s or less | Low |

If checks are still over 5 s after step 6, there are two fallbacks from the other planners' areas: scan seeds in parallel, or read the previous-candle count from the shared analysis store.

## 4. Proving results are unchanged
- **Golden outputs.** Record with `PatternScanEquivalenceTest` before step 1 and compare after every step, as JSON trees. Extend it to cover:
  - developing primaries, with and without 60-minute candles, on the latest and previous candle
  - all hypotheses, and confluence observations for the last 20 prefixes
  - viability and mismatch for every II/IV count
  - historical structures and pattern candidates
  - harmonics: `detectHistorical`, `detectRange` and `detectNewlyAvailable`
  - projection scenarios
  - `findStrictSubdivisions` for every leg, in all 5 modes, with ranking on and off

  The run must include CSCO, AAPL and MSFT.
- **Old vs new on random legs.** Keep the old `computeStrictSubdivisions` in test scope and compare it on 10,000 random legs, synthetic and real.
- **Existing tests stay green.** `DocumentedElliottWaveBenchmarkTest` (exact scores), `DevelopingElliottSyntheticRecallBenchmarkTest` (identical tallies, with outputs dumped), `AnalysisCheckpointTest`, `ElliottCorrectionSuffixTest`, `ElliottHistoryIntegrationTest`, and `CustomAlertLoadTest` (the same 1,198 of 6,750 alert-stock pairs).
- **Stored data.** Rebuild the V92 histories and check the 575 rows are identical. Recompute the chart cache payloads and diff them.

## 5. Verification
The proof is `ScheduledCheckBenchmarkTest`, before and after: the daily check per stock must have p95 and max at or under 5 s, including the developing scan, the projection and custom alerts, excluding market-data sync. Repeat with `-XX:ActiveProcessorCount=2`.

Secondary checks:
- the `CustomAlertLoadTest` daily and baseline figures
- cold MockMvc timings for the chart endpoints and the manual check
- the preview with an empty and with a complete history
- as-of history times for CSCO, AAPL and MSFT

In production, compare the `Technical signal check completed elapsedMs` log line before and after, and use `pg_stat_statements` on the `analysis_checkpoints` upsert.

## 6. Risks, open questions, effort
**Risks**
- Tie-breaking order in steps 3–4; covered by the rank tuple and the old-vs-new tests.
- Restart granularity after step 1: at most one 30 s slice of work is lost.
- Memory from cached detector instances and wider memo scopes; mitigated by bounded caches.
- The gains depend on the data.
- Every timing above is an estimate until step 0 has run.

**Open questions for the user**
1. Is the target per stock-interval job, or a whole watchlist?
2. Are provider waits excluded?
3. Which hardware: the dev laptop or a 2-core server?
4. Is lowering the preview budget from 6 s acceptable?
5. Can you share production `elapsedMs` logs? They would confirm D1.
6. Is the background work in row E in scope?

**Effort:** about 13–16 developer days. Step 0: 1; step 1: 1.5; step 2: 1.5; step 3: 2.5; step 4: 3; step 5: 1.5; step 6: 0.5; equivalence harness: 2.

### Critical Files for Implementation
- src/main/java/org/example/stockwatch247/service/ElliottWaveDetectionService.java
- src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java
- src/main/java/org/example/stockwatch247/service/HarmonicPatternDetectionService.java
- src/main/java/org/example/stockwatch247/service/AnalysisWork.java (with AnalysisCheckpointStore.java)
- src/test/java/org/example/stockwatch247/service/customalert/PatternScanEquivalenceTest.java
