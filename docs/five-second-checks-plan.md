# Plan: every stock check done in 5 seconds (v2)

Date: 2026-11-03. Status: steps 0–8 implemented on branch `perf/five-second-checks` (2026-11-05); every check in `ScheduledCheckBenchmarkTest` now finishes within 5 s. Then, for scanning the whole market: step 12 (parallel lanes), checks on threads of their own (part of step 9) and further cuts in processor time. See [Results](#results) at the end.

Three independent proposals were written and a judge compared them against the code: [A, detector algorithms](five-second-checks/proposal-a-detectors.md), [B, caching and incremental work](five-second-checks/proposal-b-caching.md) and [C, the execution pipeline](five-second-checks/proposal-c-pipeline.md). The [judge's verdict](five-second-checks/judge-verdict.md) chose C and borrowed steps from A and B; it also checks each proposal's main claims against the code, with `file:line` references. This plan is that result, updated with the owner's answers to six open questions. Every timing below is an estimate until step 0 has measured it: the project needs JDK 25, and the planning container only had JDK 21.

## Decisions
| # | Question | Answer | What it changes |
|---|---|---|---|
| 1 | What does the 5 s cover? | From the moment we start checking a stock until we're done | 5 s is a **maximum per stock, end to end**, not an average. It includes downloading candles, every detector, the Elliott projection, built-in and custom alerts, saving results and queuing emails. Large stocks (Microsoft-sized histories) are not excluded. |
| 2 | May custom alerts use the same Elliott inputs as built-in alerts? | Yes, if it's faster | Built-in and custom alerts share one Elliott scan and one harmonic scan per stock (step 5). Some custom-alert results will change once; we review that diff and then lock it in. |
| 3 | Is "still computing" acceptable on charts, signal detail and previews? Can the preview time limit drop to about 2.5 s? | Yes | Interactive pages answer within 5 s, either with the full result or with "computing…" that fills in shortly after (step 10). |
| 4 | How many CPU cores does production have? | 20 | One check can use several cores at once (steps 6 and 8), and several stocks can be checked side by side (step 12). |
| 5 | How long do checks take now? Should the follow-ups #38 added run as full checks? | Some Elliott checks seem to take about 30 s; yes, full checks | Step 0 must find out where those 30 s go. The follow-ups stay full checks, so proposal B's idea of skipping work for them is dropped. |
| 6 | Is it OK that a crash mid-scan restarts that scan? | Yes | Checks stop writing progress checkpoints to the database altogether, not just every 30 s (step 1). |

**How decision 1 is read.** The clock starts when work on that stock begins: the scheduled job is picked up, or the request arrives. It does not include time spent waiting in the queue behind other stocks. A daily run queues 300 stocks at once, and Twelve Data allows 7 requests a minute, so the whole run cannot finish in 5 s. It also does not include SMTP delivery, which the email outbox handles afterwards.

## Where the time budget goes (targets, per stock)
| Stage | Target |
|---|---|
| Download the latest candles (daily, weekly or monthly, plus 60-minute) | ≤ 1.5 s, a hard cap |
| Load from the database and compute indicators | ≤ 0.3 s |
| Elliott: latest count, previous-candle count, trade plan, projection | ≤ 1.5 s |
| Harmonics, candlesticks, outlook | ≤ 0.5 s, running at the same time as Elliott |
| Custom alerts, reading the shared results | ≤ 0.5 s |
| Saving, lifecycle updates, queuing emails | ≤ 0.3 s |
| **Total, with margin** | **≤ 5 s** |

## Why some Elliott checks take 30 s (likely causes; step 0 confirms)
The documented 2 s per check comes from a load test that skips the costly parts. A real scheduled check pays for these, all confirmed in the code:
1. **Database checkpoint writes inside the Elliott search.** Each starting point of the search gets one read and one separately committed write: about 800 round trips per scan (estimate). Each scan's checkpoint key is made by turning every candle into text and hashing it, about 1–2 MB of text. A check runs many scans: the latest count, the previous-candle count, one per open Elliott signal, and one per candle in the confluence comparison. So the cost multiplies.
2. **Duplicate scans.** Built-in and custom alerts scan the same stock on slightly different inputs, so neither can reuse the other's result. Some per-signal checks are not remembered within a check either.
3. **Waiting on locks.** Custom alerts run their scans while holding a database transaction and a row lock. The connection pool is the default 10, and logged-in page requests need connections from the same pool.

## Steps
The order is: first the changes that leave results exactly as they are, then the approved one that changes custom-alert results, then speed-ups for the largest stocks.

**0. Measure first (2 d).**
- Add a timer for every stage of a check to the existing "check completed" log line, and log a warning whenever a check takes more than 5 s. This shows where the reported 30 s go.
- Add a `ScheduledCheckBenchmarkTest` that runs a real scheduled check (`processNextQueuedCheck`) with:
  - checkpoints switched on, as in production
  - built-in rules for several users
  - open Elliott signals
  - 25 custom alerts
  - CSCO, AAPL and MSFT among the stocks
- Record "golden" outputs before anything changes (signals, trade plans, projections, custom-alert states, email deduplication keys), so every later step can be diffed against them.

**1. Stop writing checkpoints during checks (0.5–1 d).**
- Scheduled checks no longer open durable checkpointing (`ScheduledAlertService.java:297`), so there are no per-seed reads or writes and no MB-sized keys.
- Keep the in-memory caches, with cheap keys built once per candle list.
- Results don't change. Checkpoints only ever helped resume after a crash, and per answer 6 a crashed scan can simply restart.
- Probably the single biggest win.

**2. Compute each thing once per check (1.5–2 d).**
- Remember (memoise) these within a check, keyed by settings and inputs:
  - the per-signal checks: viability, wave-structure mismatch, historical structures
  - the confluence comparison, keyed by settings instead of by user
- Switch the scan caches to compute-once-across-threads (`BoundedTtlCache.getOrCompute`).
- Result: cost grows with distinct settings, not with the number of followers. Results don't change.

**3. Scans out of database transactions (2–3 d).**
- Custom alerts work out everything first. The transaction then only locks the state row, evaluates, writes and commits.
- The same applies to lifecycle updates and signal detail.
- The job lease is renewed outside business transactions.
- Raise the connection pool to 30. That suits 20 cores, parallel lanes and web traffic, and stays well under PostgreSQL's 100.
- Results don't change. This also fixes most of "the whole site is slow".

**4. Cap the candle download (1 d).**
- Inside a check, the download gets a hard 1.5 s deadline.
- Scheduled runs download candles ahead in a separate paced lane, so a check usually starts with its data already stored.
- If the latest completed candle still isn't there after the deadline, the check is postponed and retried rather than run on stale data. That keeps the rule "evaluate the candles available at the scheduled time".

**5. One shared scan for built-in and custom alerts (4–5 d; approved in decision 2).**
- One input builder gives built-in alerts, custom alerts, the manual check and the background store identical candles, indicator warm-up and 60-minute cut-off. Elliott and harmonics are each scanned once per stock and candle, and custom alerts read those results.
- Where the inputs match exactly, today's previous-candle count reuses yesterday's stored latest count.
- Custom-alert results change once: we review the diff against the step-0 golden outputs, then lock in the new baseline.

**6. Use several cores inside one check (1.5–2 d).**
- Run the independent stages of a check at the same time, on one shared CPU pool:
  - Elliott latest count
  - Elliott previous-candle count
  - harmonics
  - candlesticks and outlook
  - the remaining custom-alert families
- Results are merged in a fixed order, so the output stays deterministic and identical.

**7. Faster Elliott sub-wave search (3.5 d, including tests).**
- This is step 3 of [proposal A](five-second-checks/proposal-a-detectors.md). It is now planned rather than a fallback, because the 5 s is a hard limit that includes MSFT-sized stocks.
- The 60-minute sub-wave search currently builds and sorts every candidate and then keeps only the best one. Instead, it will skip candidates that cannot win and build the text descriptions only for the winner.
- About 60% of a scan's time is spent here.
- Results don't change. That is proven by comparing old and new code on 10,000 random legs.

**8. Split the Elliott search across cores (2–3 d; only if step 0 still shows large stocks over 1.5 s for Elliott).**
- The search's starting points are independent of each other (`ElliottWaveDetectionService.java:2368-2380`), so they can run on several threads.
- Results are merged in starting-point order, so output and tie-breaking stay identical.
- Medium risk: the detector's per-thread caches and the shared sub-wave cache need care.

**9. Separate thread pools (1.5–2 d).**
- Email, imports and insider polling get their own threads, so they never wait behind analysis.
- Background analysis (the shared store, history builds, projection backfill) shares the CPU pool from step 6. Checks always go first.

**10. Time limits on interactive pages (2–3 d; approved in decision 3).**
- Charts, signal detail, the Elliott hierarchy, previews and the manual check answer within 5 s.
- If the result isn't ready, they show "computing…", finish in the background, and serve the next load from the cache.
- The preview's Elliott time limit drops from 6 s to 2.5 s.
- The candle download for these pages also gets a time limit.

**11. Fewer database round trips (1–2 d).**
- Load the existing alert receipts for all of a stock's rules in one query.
- Load open signals and trade plans once per check.
- Pass the check's candles to the custom-alert engine instead of reloading 1,000 candles.
- Batch the projection loads.

**12. Several stocks side by side (1.5–2 d).**
- For example 3 lanes, each able to use about 5 cores. Two intervals of the same stock never run at once.
- The worker keeps taking jobs without the 1 s pause between them.
- This makes the daily run finish sooner. It doesn't make a single check faster, so it comes last and is tuned against step 0's per-check timings.

**Fallback, only if something is still over 5 s:** the anchored windows of [proposal B](five-second-checks/proposal-b-caching.md) (its step 7; 4–5 d). The 1,000-candle window starts on a fixed month boundary instead of sliding every day, so each day's scan extends the previous day's work instead of redoing it. Decision 2 allows this, but it is complex, so it stays in reserve.

**Dropped:** proposal B's idea of limiting background work for stocks nobody follows. Decision 5 keeps #38's follow-ups as full checks, background work already pauses while checks run, and with 20 cores the CPU pool in step 6 can absorb it.

## Milestones and effort
| Milestone | Steps | Effort | Expected result |
|---|---|---|---|
| A | 0–3 | about 1.5 weeks | Results identical. The 30 s checks should drop sharply, and the site stays responsive during check runs. Re-measure here. |
| B | 4–7 | about 2.5 weeks | Most stocks under 5 s end to end, including the Elliott projection and custom alerts. |
| C | 8–12 | about 2 weeks | Large stocks under 5 s, interactive pages always answer within 5 s, and a faster daily run. |

Total: about 24–32 developer days, or 5–6.5 weeks. The work can stop at any milestone where the benchmark already passes.

## How we prove it
- **Pass criterion.** In `ScheduledCheckBenchmarkTest`, on a 20-core machine, every check is at or under 5 s, measured as the maximum, not the average. That holds with MSFT included and with the step-12 lanes running.
- **Same results.** After every step, diff against the step-0 golden outputs:
  - detected signals, trade plans and projections
  - custom-alert states
  - email deduplication keys

  The only expected difference is the one-time custom-alert change in step 5, which is reviewed and then locked in. The existing equivalence and benchmark tests stay green.
- **No duplicate or missed emails.** Concurrency tests cover:
  - the same job dispatched twice
  - losing the lease mid-check
  - a crash mid-run
  - two intervals of the same stock never running at once
- **In production.** The step-0 log line gives per-stage timings, with a warning for any check over 5 s.

## Still open (none of this blocks starting)
- Is the 20-core machine the production server, and how much memory does it have? The shared caches will grow a little.
- After step 0 ships, one production log line from a 30-second check would be useful. It will show which stage is slow.

## Results
Measured with `ScheduledCheckBenchmarkTest` on a 20-thread laptop on mains power: 78 checks (6 stocks; 8 daily, 3 weekly and 2 monthly runs; 3 accounts; 25 custom alerts; 39 open developing Elliott signals). Every step left the golden outputs identical; step 5 was allowed to change custom alerts and changed none in this benchmark. Candle downloads are not timed there: the providers are replaced by stored candles.

| Checks | Step 0 | Milestone A (steps 0–3) | Milestone B (4–7) | Milestone C (8) |
|---|---|---|---|---|
| Daily: max / median | 24.3 / 10.1 s | 10.0 / 4.0 s | 9.3 / 3.1 s | 3.0 / 1.7 s |
| Weekly: max / median | 18.3 / 14.2 s | 14.0 / 8.4 s | 12.6 / 7.4 s | 4.1 / 3.3 s |
| Monthly: max / median | 9.2 / 3.4 s | 4.8 / 2.0 s | 3.9 / 1.7 s | 3.1 / 1.2 s |
| Over 5 s | 69 of 78 | 28 of 78 | 21 of 78 | 0 of 78 |

Step 0 ran on the original inputs; the later columns on inputs frozen in the `benchmark_snapshot` schema, after the running application had revised some candles. Milestones B and C let the in-memory caches expire between replayed runs, as the hours between real runs do. Milestone C is the slower of two runs in a row.

Where the time went, as measured:
- **Step 0:** durable checkpoint I/O was 52% of all check time (128,000 reads and 126,000 writes over 78 checks), then the Elliott counts. Step 1 removed the checkpoints.
- **After step 3** the Elliott count was half the time, worst on weekly checks, which validate sub-waves on about 5,000 daily candles. Custom alerts ran their own Elliott count on different inputs until step 5 (61 s over 78 checks, then 1.2 s).
- **Step 7** followed the profile instead of the proposal's best-only search: a quarter of the count went into the helper that enumerates sub-wave pivot selections, which now uses arrays and remembers follow-on pivots by position. An old-against-new test on 10,000 random legs (`ElliottSubdivisionEquivalenceTest`) proves the results identical.
- **Step 8** spreads a count's starting points over the analysis pool. It only paid off once the threads shared the count's memos and worked each value out once between them.

Not done at milestone C, under the rule to stop once every check is within 5 s: steps 9–12 and the anchored-window fallback. Step 12 and part of step 9 followed for whole-market scanning ([below](#scanning-the-whole-market)). Within step 5, the manual check keeps its own Elliott path (its results were not part of the approval), and the previous candle's count does not reuse yesterday's latest count, because with a window that moves every day the inputs never match exactly.

Still open:
- In production, the download is capped at 1.5 s inside a check (step 4) and usually done ahead by the prefetch lane. Weekly checks peak at about 4 s of computing here, so a check that waits the whole 1.5 s for its download can still go over 5 s.
- The custom alerts' candlestick scan costs about 0.4 s per daily check since step 1, because a cross-day cache stopped hitting; not traced yet.
- The production timings to watch are in the "Technical signal check completed" log line, with a warning for any check over 5 s.

### Scanning the whole market
The goal moved from one stock in 5 s to working through a market-wide queue quickly. `ScheduledCheckBenchmarkTest.marketRun` (`-DmarketRunBenchmark=true`) queues one daily check of every stock with at least 300 stored daily candles (271 stocks, frozen in the `market_snapshot` schema), each followed through the six built-in rules, and works through them on `-DmarketLanes` lanes. Goldens were identical in every run below.

| Market run, 271 daily checks | Checks a minute | Processor in use | Checks over 5 s |
|---|---|---|---|
| 1 lane (milestone C) | 43.0 | 21% | 2 |
| 4 lanes (step 12) | 65.8 | 44% | 56 |
| 4 lanes, lock-free analysis caches | 76.8 | 53% | 28 |
| 4 lanes, previous candle counts only deliverable developments | 84.2 (80.4 without, same hour) | 58% | 21 |
| 8 lanes, on a hot laptop | 67.3 (64.5 without the previous change; 58.7 on 4 lanes, same hour) | 71% | 174 |

What changed:
- **Lanes (step 12).** `alerts.schedule.parallel-checks` (`ALERT_PARALLEL_CHECKS`, default 1) runs that many checks at once, never two of one stock. A check that only waits for its own stock's running check no longer holds up the checks behind it. Checks run on lane threads of their own instead of the shared background dispatcher, so emails, imports and insider polling never wait behind them (part of step 9). An Elliott count spreads over the analysis pool's fair share for the checks running now.
- **Lock-free analysis caches.** With four lanes, a profile showed the checks queueing on one lock: the shared cache of sub-wave searches hashed keys of up to 2,048 candles while holding it. Lookups now take no lock; a cached key keeps its candle window's hash; sub-wave selections allocate only on a miss. Allocation fell by about a sixth.
- **The previous candle's count.** It only has to say whether a wave the rule could deliver (at or above its minimum confidence) was already showing, and each starting point owns its developments, so beside other checks it extends only those developments. A check running alone still counts them all, spread over the whole pool, which finishes sooner. Either way it reuses the legs the latest count validated, on whichever thread works it out.

Reading these numbers:
- More lanes trade time per check for time per queue: they share the processor, so each check takes longer while the queue empties sooner. One lane keeps every check within 5 s; a whole market's queue wants several. Production has 20 cores; the best lane count there needs measuring on that machine.
- This laptop's speed swings by about 30% with temperature and charging (a switch to battery mid-run, then fast charging, spoiled whole runs), so only runs in the same hour compare. On a hot laptop the heaviest weekly checks reach 5–6 s with one lane, as milestone C's code does under the same conditions; back to back, the current code is the faster of the two (weekly mean 2.8 s against 3.3 s).
- At four lanes, garbage collection took 15–19% of the processor time. The repository sets no JVM options; heap and collector settings are worth trying on the production server. A quick try of the parallel collector here was drowned out by the laptop's drift.
- Step 11 (fewer round trips) was skipped: the database stages are about 5% of a check, and with several lanes that time overlaps with computing. Step 10 (interactive time limits) does not affect scheduled checks.
- What would save most next changes results, so it needs the owner's approval: the anchored windows of the fallback above (proposal B's step 7). With a window that starts on a fixed boundary, today's previous-candle count is exactly yesterday's latest count, and each day's count extends the day before's.
