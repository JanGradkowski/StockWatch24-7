# Plan: every stock check done in 5 seconds (v2)

Date: 2026-11-03. Status: plan only, nothing implemented.

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
