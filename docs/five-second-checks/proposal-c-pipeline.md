# Plan C: execution pipeline, orchestration and contention

## Summary
The strategy is to stop doing work the pipeline repeats or puts in the wrong place, before touching any detector math. In order:
1. Remove the durable checkpoint I/O and the megabyte-sized cache keys inside every Elliott and harmonic scan of a scheduled job.
2. Compute each follower-invariant Elliott scan once per job, not once per follower or event.
3. Move CPU work out of DB transactions and size the connection pool.
4. Run checks for different symbols in parallel lanes, with no polling gaps.
5. Give interactive endpoints time budgets and return partial results when they run out.

Expected end state (these are estimates, not measurements):
- Scheduled check: p50 about 1 s and p95 under 3 s per stock, whatever the follower count.
- Daily run of 270 stocks: from about 10–17 min down to 3–4 min.
- Chart overlays and projection: under 0.3 s from the cache, under 3 s cold, or a partial response.
- Custom alert preview: first answer under 3 s.

## 1. What "the checks" are (please confirm)
| Flow | Entry point | Assumed 5 s budget |
|---|---|---|
| A. Scheduled check | `ScheduledAlertService.processSymbolInterval`: lifecycles, trade plans, projections, built-in rules, outlook, chart and custom alerts | Per (symbol, interval) job, wall time, p95, including candle sync and projection |
| B. Manual check | `POST /api/alerts/{symbol}/check` | Per request |
| C. Chart open | `/elliott-waves`, `/elliott-waves/history`, `/harmonic-formations/history`, `/hierarchy` | Per request |
| D. Signal detail with the Elliott projection | `AlertRuleService.getSignalDetail` | Per request |
| E. Custom alert preview and baselines | `/api/custom-alerts/preview`, `establishBaselines` | First answer per request; the full replay may finish later |

Not covered: the whole daily run (it takes minutes) and Elliott history builds (20 s to 8 min each, by design).

Please confirm three things: whether the budget is per stock or for the whole run; the production core count; and whether partial or "still computing" answers are acceptable for C and E.

## 2. Diagnosis
**Measured** (docs, October): 2.05 s mean and 3.8 s p95 per stock, 0.63 s without Elliott, about 1 s per alert-grade scan.

**Not measured:** I could not build the project here (the container has JDK 21, the project needs 25, and there is no data). Every cost below is an estimate from reading the code.

1. **Checkpoint I/O inside every scan.** `ScheduledAlertService.java:297` turns on continuous checkpointing (`AnalysisWork.continuous`) for every job. Inside each scan:
   - `ElliottWaveDetectionService.java:2452-2492` does a checkpoint SELECT and a REQUIRES_NEW INSERT plus commit for every seed (tens to about 200 per scan).
   - `ElliottWaveDetectionService.java:2154-2168` reads and writes a completed-scan row per primary scan, and still writes it on an in-memory cache hit (line 2158).
   - `ElliottWaveDetectionService.java:2344-2347` and `AnalysisWork.java:40-43` build a cache key with `toString()` of about 2,000 parent and 60-minute candles and then SHA-256 it, giving multi-MB strings.
   - The same pattern is in `HarmonicPatternDetectionService.java:141-149` and the confluence scan (lines 2412-2437).
   - In continuous mode these checkpoints never make the job shorter; they only help resume after a crash. Estimated cost: 0.3–1 s and about 10 MB of garbage per stock.
2. **The same scans repeated for every follower.**
   - For every open Elliott event, `CandlestickSignalLifecycleService.java:381-386,441-446` calls `findLatestStructureForCycle`, which runs an unmemoized `collectHistoricalWaveStructures` (`ElliottWaveDetectionService.java:353-396`).
   - `ScheduledAlertService.java:948-979` calls `hasViableDevelopingHypothesis`, which recomputes hypotheses per event without a memo (line 3546), plus `findActionaryStructureMismatch`.
   - `findStructureForSignal` runs once per rule (`ScheduledAlertService.java:535`), and confluence timelines are keyed by user id rather than settings (line 1141).
   - Users on factory settings have identical inputs, so check cost grows with followers and events. That fits "recently slower".
   - Commit #38 (8a4922b, today) also adds more jobs to each run (`AlertCheckJobStore.java:59,70-85`) and adds `catchUpStaleSignals` (`ScheduledAlertService.java:470,1083`), which repeats the open-event and open-plan queries.
3. **CPU work inside DB transactions.**
   - `CustomAlertEngine.java:224-250` opens a transaction and locks the state row `for update`. The Elliott and harmonic scans are then computed lazily inside it (`CustomAlertFactsLoader.java:402-433,499-547`). It may even call the provider for 60-minute candles (lines 510-512), and the nested REQUIRES_NEW checkpoint writes need a second connection. This breaks the "no transaction held during detector computation" promise in `HistoricalSignalCacheService.java:31`.
   - `evaluatePending` is `@Transactional` (`CandlestickSignalLifecycleService.java:299`).
   - `getSignalDetail` is `@Transactional` (`AlertRuleService.java:1147`) around a full-history scan (lines 1744-1752).
   - `JobLeaseGuard.requireOwnership` (lines 66-71) renews the job row inside these transactions, so the heartbeat thread (lines 59-62) blocks on that row lock while holding a connection.
4. **Serial queue with polling gaps.** `AlertCheckJobStore.java:166-173` allows one check at a time across all workers. `ScheduledAlertService.java:278-279` takes one job per 1 s tick, which wastes about 0.5 s per job.
5. **Shared, undersized pools.**
   - `BackgroundJobDispatcher.java:16-19` has 4 threads for 8 queues. Email, insider and import tasks wait behind analysis slices of 20–120 s in an unbounded queue.
   - The Hikari pool size is not set, so it defaults to 10, while Spring Session JDBC needs a connection for every signed-in request.
   - The scheduler pool has 6 threads and also runs the Elliott work of `ElliottProjectionBackfill` (lines 29-45).
6. **Interactive paths block and contend.**
   - `ChartController.java:186,271,436` call `syncCandles` synchronously, without the bounded wait in `MarketDataService.java:383-405`.
   - `HistoricalSignalCacheService.java:107-126` holds a monitor while waiting for one of 4 computation permits, then polls every 250 ms for up to 6 min.
   - The in-memory scan cache in `ElliottWaveDetectionService.java:20` is a plain get/put, so two concurrent requests for the same scan both compute it.
   - The preview's default Elliott budget is 6 s (`CustomAlertPreview.java:60`), which on its own is over the target.

**Why the whole system feels slow:**
- (a) Pool starvation from problems 3 and 5 stalls every page, for up to the 10 s connection timeout.
- (b) CPU is oversubscribed: the scheduled check, up to 4 baseline threads, the background analysis jobs and unbounded Tomcat threads all run Elliott scans at once.
- (c) The megabyte-sized keys create GC pressure.
- (d) Emails and imports are stuck behind analysis on the 4 shared workers.

## 3. Steps
**0. Instrument (0.5–1 d).**
- Per-stage wall and CPU time (extend `CheckProgress`), statement counts, Hikari active and pending connections, and JFR allocation.
- Add a scheduled-check mode with 1, 10 and 30 followers per stock to `CustomAlertLoadTest`.
- Record baseline numbers before changing anything.

**1. Keep checkpoints in memory in continuous mode (1 d).**
- Skip the per-seed and completed-scan reads and writes.
- Persist a checkpoint only when `checkpointDue()` actually fires (scans longer than 30 s).
- Compute keys lazily from a cheap fingerprint that is computed once per list.
- Expected gain: 0.3–1 s per stock, plus less GC.
- Risk: a crash mid-scan restarts that scan. Checkpoints never change results (see the comment in `AnalysisCheckpointStore.cleanup`).

**2. Compute once per job per settings (1.5–2 d).**
- Memoize, in `AnalysisComputationScope`:
  - `collectHistoricalWaveStructures`, `findStructureForSignal` and `findAllWaveStructures`
  - `hasViableDevelopingHypothesis` and `findActionaryStructureMismatch`
- Key each entry by rules, scoring model, input identity and arguments.
- Key confluence timelines by a settings fingerprint instead of user id.
- Switch the static scan caches to `BoundedTtlCache.getOrCompute`, which already computes each key only once across threads.
- Expected: check cost grows with distinct settings, not with followers, which saves seconds on popular stocks.
- Risk: an incomplete key reuses a wrong result. Prove it with the equivalence tests.

**3. Compute outside transactions and size the pool (2–3 d).**
- In `CustomAlertEngine`, add a prepare phase before any transaction. It warms every (family, settings) pair the targets need and does the lower-degree refresh. The transaction then only locks, evaluates and writes.
- In the lifecycle service, compute first, then use a short transaction that re-reads the events and applies changes only if `lifecycleUpdatedAt` is unchanged.
- In `getSignalDetail`, load in a read-only transaction and compute outside it.
- Make the renew in `requireOwnership` run outside business transactions.
- Set `hikari.maximum-pool-size` to about 20 and turn on leak detection.
- Risk: something changes between compute and apply. The re-validation under the lock handles it.

**4. Drain loop and parallel lanes (2 d).**
- The worker claims and processes jobs until the queue is empty or 60 s have passed.
- Allow up to N jobs in PROCESSING (`alerts.schedule.parallel-checks`, default max(1, cores/2 − 1)), never two for the same symbol, because intervals share daily and 60-minute candles and their sync leases. Keep the rule that a series runs in schedule order.
- Give alerts their own executor and raise the heartbeat pool to at least N+1.
- Expected: no idle gap per job, and the daily run takes about 1/N of the time.
- Risk: the Twelve Data limit is enforced in the database, so 7 a minute still holds, but more syncs may fall back to Yahoo. An optional single paced pre-sync lane avoids that.

**5. Bulkheads (1–2 d).**
- Give email, insider and imports their own executors.
- Add one global CPU gate (cores − 1) shared by background analysis and the cache's computations.
- Move the projection backfill onto the dispatcher.
- In the cache, replace the monitor plus polling with one in-JVM future per key; waiters time out.

**6. Interactive time budgets (2–3 d).**
- Chart endpoints refresh candles with a bounded wait (2 s or less) and serve the cached overlay.
- If a computation needs more than about 3.5 s, return a "computing" status and finish it in the background (computed once), so a reload hits the cache.
- Lower the preview Elliott budget from 6000 to 2500 ms and rely on the existing PREPARING status.
- Give the manual check a bounded sync wait.
- Signal detail shows the projection without the hindsight overlay until a background backfill stores it.

**7. Fewer round trips per check (1–2 d).**
- Load the existing receipts for all of a symbol's rules in one query.
- Query open events and plans once per check.
- Pass the check's candles to `CustomAlertEngine` instead of reloading 1,000 candles.
- Batch the projection scenario loads.

Borrow from the other planners only if checks are still over target: sharing scans between built-in and custom alerts (this changes inputs, so it is a semantics change), and incremental detectors.

## 4. Correctness
- **Safe reuse:** reuse is limited to pure functions of rules and candles, and the cache keys include both.
- **Notifications:** `publishEvent` keeps the rule lock, the unique constraint and the outbox deduplication keys in one transaction. Custom alerts keep the state-row lock and `on conflict do nothing`. Lanes never share a symbol.
- **Leases:** complete, defer and backoff still require the attempt number and a live lease, and `requireOwnership` stays at publish time.
- **Recovery:** schedule runs, job uniqueness, series ordering and the `scheduledFor` bounds are unchanged.

## 5. Verification, risks, open questions, effort
**Verification**
- `PatternScanEquivalenceTest`, record before the change and compare after.
- A golden diff of old against new code on a database snapshot, covering:
  - `alert_events`, plans and projections
  - email outbox deduplication keys
  - custom alert states and events
- `CustomAlertLoadTest`, asserting a p95 under 5 s per check with 30 followers.
- Concurrency tests:
  - two intervals of the same symbol are never processed at once
  - losing the lease mid-check
  - kill -9 in the middle of a run
  - the same job dispatched twice
- Browsing during a daily run: page p95 under 1 s and no pending Hikari connections.
- Provider request counts per run stay the same.

**Risks:** memo key bugs; races between compute and apply; more Yahoo fallback; too large an N; losing mid-scan resume.

**Open questions**
- Is the target per stock or per run?
- How many production cores are there?
- Is crash-resume of long scans needed?
- Are partial answers acceptable for charts and previews?
- Is a change in the provider mix acceptable?
- What are the current production timings after #38?

**Effort:** about 13–18 developer days plus 3 days of verification (3–4 weeks). Steps 1–2 alone (about 3 days) probably bring flow A under target.

### Critical Files for Implementation
- src/main/java/org/example/stockwatch247/service/ElliottWaveDetectionService.java
- src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java
- src/main/java/org/example/stockwatch247/service/AlertCheckJobStore.java
- src/main/java/org/example/stockwatch247/service/customalert/CustomAlertEngine.java
- src/main/java/org/example/stockwatch247/service/CandlestickSignalLifecycleService.java
