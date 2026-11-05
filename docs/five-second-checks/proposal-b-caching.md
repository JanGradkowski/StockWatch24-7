# Plan B: stop recomputing. One scan per stock and candle, shared, precomputed and incremental

## Summary
Run each expensive scan at most once per (stock, interval, family, settings, completed candle). The built-in alerts, custom alerts, previews, the manual check and the chart/detail pages should all read that one result. Three supporting changes:
- Remove the two background loads that make the system sluggish: the store's follow-up work for stocks nobody follows, and checkpoint "caches" that never hit.
- Do the next candle's predictable work in the background.
- Optionally, make the scan inputs stable from day to day, so a new candle extends yesterday's work instead of redoing it.

**Expected end state (estimates; nothing measured here, JDK 25 isn't installed):**
- Scheduled check: about 1.5–3 s p95 per stock.
- Manual check, preview, chart overlays and signal/Elliott detail pages, including the projection: under 1 s when the store already has the result.
- The exception is a cold scan of a very long history (MSFT-class). That still needs the detector work in the other plan.

## 1. Which checks the 5 s target covers
I assumed 5 s server-side wall time, p95 on the dev laptop, excluding provider HTTP time (Twelve Data allows 7 requests a minute). It covers:
- **a) Scheduled check per stock and interval** (`ScheduledAlertService.processNextQueuedCheck`): built-in Elliott (developing count, trade plan, projection), harmonic, candlestick, outlook, chart alerts and custom alerts.
- **b) Manual check**, `POST /api/alerts/{symbol}/check`. I found no UI caller, so it is API-only.
- **c) Custom alerts:** the baseline per stock after an alert is saved, and `POST /api/custom-alerts/preview`.
- **d) Chart and pages:** `elliott-waves/history/range`, `candidates/range` and `harmonic-formations/history/range` (one request per 128-day chunk), `elliott-waves/hierarchy`, the Elliott and harmonic detail pages, and signal detail with its projection.

**To confirm with you:** whether the target is per stock or for the whole daily run, whether it includes provider time, and whether cold MSFT-class stocks are in scope.

## 2. Diagnosis

1. **Regression from #38 (8a4922b, today).**
   - The daily job set now includes stocks with POTENTIAL events and PROJECTION_ONLY plans, which nobody needs to follow (`AlertCheckJobStore.java:59,72-73,84-85`).
   - Every check calls `sharedStore.followed()` regardless (`CustomAlertEngine.java:162-163`).
   - That queues factory snapshots at the followed priority, which has no CPU budget (`SharedAnalysisStore.java:128`; the budget covers only non-followed work, `:217`).
   - It also queues a full as-of Elliott history (`:130`), measured at 20 s–8 min per stock.
   - Result: likely hours of background CPU each day, competing with page requests.
2. **A checkpoint store that costs work on every scan in scheduled checks but almost never pays back** (scheduled checks open `AnalysisWork.continuous`, `ScheduledAlertService.java:297`).
   - Each seed pair does one read and one `REQUIRES_NEW` write (`ElliottWaveDetectionService.java:2454-2456, 2491-2492`; `AnalysisCheckpointStore.java:17-25`).
   - Keys are built from the text of every parent and child candle (`:2155, 2344-2347`).
   - It writes even when the in-memory cache already hit (`:2158, 2419`). Harmonic scans do the same (`HarmonicPatternDetectionService.java:141-149`).
   - Keys are exact inputs, so they only help a job that restarts in the middle.
   - `CustomAlertLoadTest` calls `engine.evaluate` without `AnalysisWork` and without the built-in rules. So the documented 2.05 s / 3.8 s p95 leaves out both this overhead and the built-in stages.
3. **The same Elliott count is computed up to four times per stock-day, each time with slightly different inputs.**
   - Built-in alerts: 1,000 candles enriched with indicator warm-up (`ScheduledAlertService.java:379-383,416-420`), children cut at `scheduledFor` (`:846-847`).
   - Custom alerts: 1,000 candles with no warm-up (`CustomAlertFactsLoader.java:60,614-619`). The indicator values differ, so the store misses.
   - The background factory snapshot (`SharedAnalysisStore.java:224-231`).
   - The history extension re-reads the latest candle (`ElliottHistoryBuilder.java:109`).
   - On top of that, previous-candle scans run in the built-in check (`ScheduledAlertService.java:812-814`) and in previews (`CustomAlertFactsLoader.java:576`).
   - `ElliottAlertInputs`' Javadoc says both paths see the same count. They don't.
4. **Harmonics are scanned up to four times.**
   - Built-in `detectNewlyAvailable` runs two full scans (`HarmonicPatternDetectionService.java:193-201`) on a window whose length depends on the stock's rules.
   - Custom alerts (`HARMONIC_ALERTS`) scan again on a different window.
   - The manual check scans again with no store (`AlertRuleService.java:2344-2347`).
5. **Previews skip the store entirely** (`CustomAlertFactsLoader.java:500`, `replayFrom > 0`). That includes harmonics, which don't depend on the replay at all. The Elliott replay budget is 6 s (`CustomAlertPreview.java:48`), which is above the target on its own.
6. **Pages and checks repeat work they could store.**
   - Untracked Elliott events re-run `findStructureForSignal` on every check, forever (`CandlestickSignalLifecycleService.java:253-272`).
   - Legacy signal detail, and the projection backfill after every restart, rescan the full history (`AlertRuleService.java:1744-1762`).
   - The detail pages recompute the full history plus the confluence timeline on every view (`HistoricalElliottWaveService.java:80-122`, `HistoricalHarmonicFormationService.java:82,169`).
   - The hierarchy endpoint has no cache.
   - Range chunks are keyed by from/to/cutoff (`ChartController.java:361,380,395`). Their in-memory snapshot is keyed by the candle generation (`HistoricalPatternInputs.java:34`), which every candle write bumps.
7. **Sliding windows prevent incremental work.** The 1,000-candle window moves every day, and recursive indicators such as EMA and ATR then change every value. So the leg-context cache is cleared (`ElliottWaveDetectionService.java:5496`) and yesterday's scans are never reusable.

## 3. Steps

**Step 0: Measure (1 d).**
- Add per-stage timers and scan/hit counters to each check.
- Add a `ScheduledCheckLoadTest` that runs `processNextQueuedCheck` (with real `AnalysisWork` and built-in rules) on 50 and 250 stocks.
- Use it to gate every later step.

**Step 1: Stop the background flood (0.5 d). No schema change.**
- Call `followed()` only for stocks in `TrackedInstrumentGuard.globallyTrackedSymbols()`.
- Checks that only follow up open plans, projections or POTENTIAL events skip Elliott enrichment and the store hooks.
- Gain: removes the unbudgeted snapshot/history work for stocks nobody follows. Count the affected stocks with one SQL query first.
- Risk: low.

**Step 2: Checkpoint only when it can pay (1 d).**
- In continuous mode: no per-seed reads; write only when `checkpointDue()` fires (every 30 s) or on deferral; no write after an in-memory hit.
- Build keys lazily from the series fingerprint, as the faster confluence keys already do.
- Results are unchanged by construction.
- Gain (estimate): 0.2–2 s per Elliott scan in scheduled checks.
- Risk: low; only the restart granularity changes.

**Step 3: One Elliott count per candle (3–4 d).**
- **One input builder** (`ElliottAlertInputs`) for built-in, custom, manual and background use: the latest 1,000 completed parent candles enriched with warm-up; children before the first incomplete parent candle. Custom alerts then show what the built-in alerts already show (needs your sign-off).
- **New store family `ELLIOTT_COUNT`** holding the raw `DevelopingImpulse` lists, including points.
  - Key: detector version plus an automatic code fingerprint, settings, and sha256 of the parent and child candle fingerprints.
  - `ELLIOTT_LATEST`, `processDevelopingElliott` and previews all derive from it. Trade plans and projections are still prepared per rule (cheap).
- **History extension:** when the latest count is stored and the fingerprints still match, append its hits instead of scanning again.
- **Next-day previous count:** right after a stock's check, a background job computes tomorrow's previous-candle count and stores it. That is the window shifted one candle, with children before today's boundary. Tomorrow's check and previews just read it. This is exact.
- Gain (estimate): the Elliott stage drops from 2–4 scans to 1, about 1–3 s per stock with an Elliott follow.
- Risk: medium (aligning the inputs).

**Step 4: Harmonics the same way (2 d).**
- One fixed input: the latest N completed candles, shared by all paths. Today the built-in window varies with the stock's rules, so this needs sign-off.
- A `HARMONIC_SCAN` family stores the raw `detectAll` result; `detectHistorical`, `detectNewlyAvailable` and the manual check derive from it.
- The background next-prefix scan replaces the second (n−1) scan.

**Step 5: Previews, baselines and the manual check read the store (2 d).**
- Drop the preview bypass for harmonics. Key candlestick entries by their lookback.
- When an Elliott history is READY through the latest candle, skip `latest(true)` and read both counts from `ELLIOTT_COUNT`.
- Cut the replay budget to 2.5 s and return PREPARING beyond it (that status already exists).
- Give the manual check an `ELLIOTT_SIGNALS` family for `detectAlertSignals`.

**Step 6: Store page results (2–3 d). Schema: V98, one column.**
- **Untracked Elliott events:** record the attempt with its prefix fingerprint; retry only if the fingerprint changes.
- **Legacy signal structure:** cache by (event, settings, prefix fingerprint). The candles before a past signal don't change. Cache failures too, so the backfill stops repeating them.
- **Detail pages:** cache the whole view, keyed by stage, endpoint, settings and the completed-candle fingerprint.
- **Range endpoints:** key the snapshot by the completed-candle fingerprint instead of the generation, store one full-history result per settings, and filter it per chunk. Elliott already filters this way; for harmonics, first prove that `detectRange` equals `detectAll` plus a filter.
- **Background warm-up:** after each completed candle, the store job also builds the factory chart histories and the hierarchy for followed and viewed stocks.

**Step 7 (optional; biggest incremental win; needs sign-off): stable windows.**
- Anchor the parent window at the first candle of a month at least 1,000 candles plus warm-up back. The window then holds 1,000–1,021 candles.
- Today's inputs then extend yesterday's exactly. That enables:
  - persisting `DevelopingScanContext` across days (new table `elliott_leg_contexts`, jsonb). In-check leg reuse already measured 2.04 → 1.43 s for two scans;
  - reading yesterday's result for every previous-candle scan;
  - keeping confluence prefix scans across days.
- One cold scan per stock per month, which the background can do ahead of the month change.
- Gain (estimate): 50–70% of the Elliott time left after Steps 1–5.
- Risk: the window length changes the alert definition; memory and storage.

## 4. Cold paths
- **A stock nobody has opened:** the first chart page raises it to interactive priority, and the ranges compute on demand. A full MSFT-class history may exceed 5 s once. The visible chunks come first (the loader already works in chunks).
- **A cache miss in a check:** compute once under the existing lease and store it; every other reader gets a hit.
- **Non-default settings:** every settings hash has its own key, and followers' checks already compute their settings. When someone saves preferences, queue fresh snapshots for their followed stocks; that takes seconds.
- **What can't be under 5 s:** a new settings profile's 500-candle as-of history takes minutes. Previews show PREPARING with a partial replay meanwhile.
- **Fair share for requests:** give interactive requests their own compute permits, so background work can't hold all four (`HistoricalSignalCacheService.java:42`). This is borrowed from the concurrency plan.

## 5. Correctness
- **Keys are exact inputs:** parent and child fingerprints, settings, the window definition, and detector versions plus a bytecode fingerprint of the detector classes, so a forgotten version bump can't serve stale results. The forming candle is never an input.
- **Equivalence tests:** extend `PatternScanEquivalenceTest` to raw counts, harmonic formations and built-in check outputs (events, plans and projections compared as JSON) on 30 real stocks. Cover:
  - cold and warm store;
  - an appended candle;
  - a corrected last candle;
  - a correction further back;
  - factory and custom settings.
- **Steps that change the alert definition (3, 4, 7):** review the old-vs-new differences first, then lock the new baseline.
- **No look-ahead:** run the existing cut-copy check against the store.
- **Shadow check in production:** setting `analysis-store.verify-rate` (for example 2%) recomputes a hit in the background. A mismatch raises a metric and switches that family off.

## 6. Verification, risks, open questions, effort
- **Verification:** the Step 0 harness before and after every step. `CustomAlertLoadTest` and `ScheduledCheckLoadTest` assert p95 < 5 s. Compare the live `elapsedMs` distribution of the check log line.
- **Risks:**
  - aligning inputs changes alert outputs; mitigate with sign-off and a diff review;
  - store growth; the 30-day cleanup exists;
  - payload size of the raw counts; use compact jsonb.
- **Open questions:**
  - The scope of the 5 s target (section 1).
  - Is aligning the inputs acceptable (Steps 3 and 4)? Are anchored windows (Step 7)?
  - Should POTENTIAL and PROJECTION_ONLY follow-ups trigger full checks?
  - What should happen for MSFT-class cold scans?
  - Do the 60-minute child candles grow without limit? (Not verified.)
- **Effort:** Steps 0–6 about 12–14 days; Step 7 another 4–5 days. Roughly 3–4 weeks in total.

### Critical Files for Implementation
- src/main/java/org/example/stockwatch247/service/customalert/CustomAlertFactsLoader.java
- src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java
- src/main/java/org/example/stockwatch247/service/ElliottWaveDetectionService.java
- src/main/java/org/example/stockwatch247/service/HistoricalSignalCacheService.java
- src/main/java/org/example/stockwatch247/service/customalert/SharedAnalysisStore.java
