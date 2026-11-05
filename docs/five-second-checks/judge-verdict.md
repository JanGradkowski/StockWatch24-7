# Judge's verdict: plan to get the checks under 5 s

## Verdict
I pick **Plan C (pipeline)** as the winner. Its diagnosis held up best when I checked it against the code. It gives the quickest win with no change to alert semantics (steps 1–2, about 3 days), and it is the only plan that explains why slow checks drag down the rest of the system. It falls short on per-check latency, because it leaves the built-in and custom alerts each running their own Elliott scan and leaves the per-scan cost as it is. Plan B's shared-input step and Plan A's subdivision search fill that gap as grafts.

## Claim verification
| Claim | Plan | Result | Note |
|---|---|---|---|
| Per-seed checkpoint read plus `REQUIRES_NEW` upsert, on every scheduled check | A, B, C | **verified** | `AnalysisWork.continuous` is opened for every job (`ScheduledAlertService:297`). The read is at `ElliottWaveDetectionService:2453-2456` and the write at `:2491-2492`; the write is `REQUIRES_NEW` with Jackson (`AnalysisCheckpointStore:19-27`). This happens only in primary and confluence scans, which are the developing scans. Custom-alert scans pay it too, because the engine runs inside the job (`:645`). |
| Keys built from `toString()` of every candle | A, B, C | **verified** | `AnalysisWork:40-43` hashes the `toString()` of every candle, and `:2149-2155` and `:2344-2347` pass in full parent and 60-minute lists of `EnrichedCandle`, a record with 23 fields. That is about 1–2 MB of text per key. The key at `:2344` is built even when the scan writes no checkpoint. The per-seed keys are small. |
| Writes even on in-memory hits | A, B, C | **verified, with a nuance** | `:2157-2158` and `:2418-2419`. The per-check memo (scope opened at `ScheduledAlertService:355`) catches repeat calls first, so this happens once per distinct scan per check. Harmonic scans do not write on a hit (`HarmonicPatternDetectionService:139-149`). |
| #38 floods background work through `followed()` at followed priority with no CPU budget | B | **partly** | `followed()` is called unconditionally (`CustomAlertEngine:161-162`), followed priority is never charged to the budget (`SharedAnalysisStore:217`), and #38 widened the job set (`AlertCheckJobStore:59,70-85`). However, the store and the history builder both pause while any check is pending or running (`SharedAnalysisStore:197`, `ElliottHistoryBuilder:76`), histories are built once and then extended (`ElliottHistoryStore:106-111`), and unfollowed jobs existed before #38 (outlook subscriptions, chart alerts). So "hours a day" is overstated, and this does not slow the checks themselves. |
| Built-in and custom alerts compute the Elliott count on different inputs | B | **verified** | Built-in alerts load 1,000 + 200 − 1 candles with warm-up (`ScheduledAlertService:379-383,416-420`); custom alerts load 1,000 with none (`CustomAlertFactsLoader:60,183,614`). The 60-minute cut-off differs too (`ScheduledAlertService:846-847` against `FactsLoader:617-619`). The detector reads ATR (`:4706`), so the cache keys miss and results can differ. This contradicts the Javadoc at `ElliottAlertInputs:15-19`. |
| CPU-heavy scans run inside a transaction and a row lock in `CustomAlertEngine` | C | **verified** | The transaction is at `:224`, the `for update` at `:245`, and the scans are computed lazily inside it (`FactsLoader:401-433,499-547`), including a provider refresh (`:511`). |
| `getSignalDetail` scans inside a transaction | C | **verified** | `AlertRuleService:1146-1147`. The full-history scan at `:1744-1752` runs only for legacy events (`:1741`). |
| Checks run one at a time | C | **verified** | `AlertCheckJobStore:166`, with a 1 s worker delay (`application.properties:63`). |
| Hikari pool size left at default | C | **verified, starvation unproven** | `maximum-pool-size` is not set anywhere, so it is 10, and sessions are JDBC (`pom.xml:90`). Each check holds at most 2 connections. |
| Lease renewal runs under the row lock | C | **verified** | `JobLeaseGuard:31-35` renews inside `@Transactional` callers, for example `CandlestickSignalLifecycleService:241-246`. |
| The projection itself is cheap | A | **verified** | `generate` loops over at most 3 seeds, and the evaluation is linear. Plan A misses that the projection on the signal-detail page triggers the legacy full scan. |
| Subdivision search is exhaustive | A | **verified** | `Integer.MAX_VALUE` limits at `:3749-3750` and `:4073-4074`; every candidate is collected, sorted and deduplicated (`:1893-1900`), then the search breaks after the first validated one (`:1916`). |
| "Nothing is shared between events" | A | **partly wrong** | A memo scope is already open for each check, the primary scan is memoised by settings (`:2149-2152`), and there is a static cache (`:20`). The fallback scan (`:3546`), the mismatch check and `collectHistoricalWaveStructures` really are unmemoised (`CandlestickSignalLifecycleService:441-445`). |
| `CustomAlertLoadTest` leaves out `AnalysisWork` and the built-in rules | A, B | **verified** | It calls `engine.evaluate` directly (`:352`). The documented 2.05 s / 3.8 s p95 therefore understates production. |

## Scorecard (1–10)
| Criterion | A | B | C |
|---|---|---|---|
| (a) Reaches under 5 s | 7: cuts the cost of every scan, including cold ones, but keeps the duplicate scans | 7: one scan per stock, but depends on sign-offs, and cold MSFT-class stocks stay over 5 s | 6: duplicate scans and per-scan cost remain; its "p95 < 3 s" is optimistic |
| (b) Correctness risk (higher is safer) | 7: tie-breaking risk, with a strong harness | 4: steps 3, 4 and 7 change alert definitions; large new key surface | 7: no detector change; compute-then-apply races and parallel lanes add risk |
| (c) Diagnosis accuracy | 7 | 7 | 8 |
| (d) Speed to first win and effort | 6 | 5 | 8 |
| (e) Verification plan | 9 | 7 | 7: its load test needs real `AnalysisWork` |
| (f) Fixes "the whole system" | 3 | 6 | 9 |
| **Total** | 39 | 36 | **45** |

## Why C wins
- **Mostly sound evidence.** Plan C's transaction and lock findings are real and explain the "whole system is slow" symptom: connections and locks are held during scans, the heartbeat blocks, and email and imports wait behind 4 shared workers. Neither other plan saw these.
- **Quick, safe first win.** Steps 1–3 leave alert and email semantics untouched. Its verification diffs `alert_events`, plans, projections and the outbox deduplication keys, and adds concurrency tests.
- **Weaknesses.** It never notices that the built-in and custom alerts scan different inputs. Parallel lanes speed up the daily run, not a single check.

**Plan A's weaknesses.** Its step 2 partly re-does memoisation that already exists. It spends about 7 days on rewrites with tie-breaking risk before anything has been measured. It does almost nothing for the rest of the system.

**Plan B's weaknesses.** Its headline regression does not slow the checks. Its main lever changes alert definitions, and it carries the most complexity: new store families, a schema change and bytecode fingerprints. Its big unique insight does reach the target, so it becomes a graft.

## Recommended grafts
1. **Plan A's `ScheduledCheckBenchmarkTest` and golden JSON harness**, which runs `processNextQueuedCheck` with real `AnalysisWork`, built-in rules, open II/IV events and custom alerts. It goes in step 0, because Plan C's harness, built on `CustomAlertLoadTest`, would miss the very costs being fixed.
2. **Plan B step 3, core part: one Elliott input for built-in and custom alerts, shared through the store.** This turns two scans into one per stock and restores what the Javadoc promises. It goes after step 3 and needs your sign-off.
3. **Plan B step 1: call `followed()` only for tracked stocks.** It takes 0.5 days and cuts unbudgeted background work that competes with page requests. It goes with the bulkheads.
4. **Plan A step 3: best-only subdivision search with the old-vs-new test on 10,000 legs.** This is the contingency if any check is still over 5 s, for example MSFT-class stocks.

## Final step order
0. Instrumentation, plus graft 1's benchmark, golden recordings and database diff (2 d).
1. Keep checkpoints in memory in continuous mode, with lazy cheap keys and no write after an in-memory hit (1 d).
2. Compute once per job per settings: memoise viability, mismatch and structure per event, key confluence by settings, and use `getOrCompute` (1.5–2 d).
3. Compute outside transactions, renew the lease outside business transactions, and set the Hikari pool to 20 (2–3 d).
4. **Graft 2:** a shared Elliott input and count, after sign-off (3–4 d). This is required if the step-0 harness still shows p95 above 5 s.
5. Bulkheads, a CPU gate and **graft 3** (2–2.5 d).
6. Interactive time budgets: preview budget about 2.5 s, bounded candle sync, a "computing" status (2–3 d).
7. Fewer round trips per check, and pass the check's candles to the custom-alert engine (1–2 d).
8. Drain loop and parallel lanes, with a conservative N; this is for throughput only (2 d).
9. **Graft 4**, only if needed (3.5 d).

## Open questions for the user
1. Is the 5 s target per stock-interval check (p95) or for the whole run? Are provider waits and cold MSFT-class histories excluded?
2. May custom alerts use the same Elliott inputs as built-in alerts (indicator warm-up, same 60-minute cut-off)? Some custom-alert results would change.
3. Are "still computing" or partial answers acceptable for charts, signal detail and previews? Can the preview's Elliott budget drop from 6 s to about 2.5–3 s?
4. What hardware does production run on, and how many cores?
5. Can you share production `elapsedMs` logs from since #38? And should the POTENTIAL and PROJECTION_ONLY follow-ups that #38 added run full checks?
6. Is it acceptable that a crash in the middle of a scan restarts that scan, with checkpoints persisted only every 30 s?
