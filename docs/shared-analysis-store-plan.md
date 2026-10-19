# Shared analysis store: design plan

Date: 2026-10-19

Status: built on the `custom-alerts` branch (October 2026). The design below is kept as written;
[Implementation notes](#implementation-notes) at the end list where the build differs from it and what was
measured. Follows on from [Custom alerts](custom-alerts-plan.md), whose setup time on large watchlists this
mainly addresses.

## The idea in one paragraph

The expensive pattern analysis of a stock (Elliott Wave counts, harmonic formations, candlestick
signals, the automated outlook) is computed **once, early, and shared**. As soon as a signed-in user
opens a stock whose candles we store, a background job works out what each detector shows on the
latest completed candle and saves it, with factory settings, as the stock's **latest snapshot**.
Opening a stock does not follow it: the snapshot is only kept current while someone **follows** the
stock (a built-in follow, an outlook subscription or a custom alert). For followed stocks the
scheduled checks, which already do this work every candle, save what they compute instead of
discarding it. Everything that needs these results reads the same store: custom alert baselines,
scheduled checks, previews and chart overlays. A new custom alert on followed stocks then reads each
stock's snapshot instead of scanning it, which removes most of today's setup time.

## Goals

- A new custom alert of 25 rules on 300 **followed** stocks shows every stock's state within one
  minute, on this development laptop; a single new rule within 15 seconds. Today the 25-rule case
  takes about 14 minutes (see [Measurements](#measurements)).
- Opening a stock costs no market-data requests beyond what the stock page already makes.
- Background work never slows page loads or the scheduled checks, and has a budget the operator
  can lower for weak hardware.
- Every consumer gets the same answer it would compute itself. The store is a cache, never a
  second detector.

Not goals: caching indicators (they take milliseconds and come in unlimited parameter
combinations), changing the follow limits or provider budgets, or alerting on stocks nobody follows.

## Measurements

What the design is sized from. Laptop timings are from `CustomAlertLoadTest` and the live checks of
October 2026; weak hardware will be slower.

| What | Measured |
| --- | --- |
| Setup of 25 alerts on 270 followed stocks | 787 s of baselines + 14 waits of 15 s ≈ 16.6 min; ≈ 14 min after the shared leg context (scaled from the 60-stock run) |
| Daily check, 25 alerts, per stock | 2.05 s mean, p95 3.8 s; 0.63 s without the two Elliott alerts |
| Baseline, 25 alerts, per stock | 2.37 s |
| One alert-grade Elliott scan (with 60-minute subdivisions) | about 1 s, derived: a check's two scans took 2.04 s per stock without the shared leg context and 1.43 s with it, so the previous-candle scan adds about 0.4 s |
| As-of Elliott history of one stock (one scan per candle, ~500 candles) | CSCO 20 s, AAPL ~1 min, MSFT ~8 min |
| Stored as-of Elliott history (V92) | 575 signal rows for 9 stocks, 344 kB with indexes |
| Chart pattern cache (`historical_signal_cache`) | 341 entries, 12 MB; a daily candlestick history is ~200 kB |
| Accounts / stock assets / stocks with daily candles | 50 / 1,410 / 277 |
| Provider budget (Twelve Data defaults) | 7 requests a minute, 760 a day |
| Follow limits | 300 stocks per user, 500 overall (`TrackedInstrumentGuard`) |

## What exists today

| Producer | What it computes | Where it goes | Invalidated by |
| --- | --- | --- | --- |
| Chart overlays (`ChartController`, `HistoricalCandlestickController`) | Hindsight histories: completed Elliott structures (`findHistoricalWaveStructures`, daily candles only), harmonic and candlestick histories | `historical_signal_cache` (V48), keyed by family, detector version, settings hash and `candle_generation` | Any insert or update of a candle row of that stock and interval (trigger, V58/V60), including every intraday update of the forming candle |
| Scheduled checks (`ScheduledAlertService`) | Developing Elliott counts with lower-degree validation, alert-mode harmonics, candlestick lifecycles, outlook, chart and custom alerts, per follower's settings | Signals, events and plans; the scans themselves are discarded | — (in-memory caches of 16–32 entries for 10 minutes) |
| Custom alert previews (`CustomAlertPreview`, `ElliottHistoryBuilder`) | As-of Elliott history: what each candle's count showed then | `custom_alert_elliott_coverage` / `_signals` (V92), extended incrementally, removed after 60 days unused | Settings fingerprint; extension from the last covered candle |
| Custom alert baselines (`CustomAlertEngine.establishBaselines`) | Everything, from scratch, for each newly covered stock | States only | — |

Three things follow from this table:

1. **"Elliott Wave" is two different calculations.** The chart draws completed structures with
   hindsight from daily candles. Alerts and previews need the *developing* count as it stood on a
   candle, validated on 60-minute candles. They do not share code paths or results, and the as-of
   version costs one scan per candle.
2. **The chart cache rarely survives a day.** Its key includes the candle generation, which the
   trigger advances on every candle write. A followed stock's history is recomputed in full after
   nearly every sync.
3. **The scheduled checks already pay for the snapshot.** Every followed stock's latest-candle
   scans are computed daily and then dropped.

## Design

### Three kinds of result

- **Latest snapshot**: what each detector shows on the latest completed candle, for one stock,
  interval, family and settings. One row, replaced when a new candle is completed. This is what a
  baseline, the stock page and a scheduled check need.
- **As-of history**: for each completed candle, the signals the detector showed then, with the
  candle that first showed each one (`known_at`). Append-only. Previews and catch-ups need it.
  Elliott needs it because counts redraw; harmonics and candlesticks are read from one scan with
  their detection candle today (see [Open questions](#open-questions)).
- **Hindsight history**: the chart's completed structures. Stays in `historical_signal_cache`,
  with a new key (see [Invalidation](#invalidation)).

### Tables (Flyway V93+)

`analysis_series`, one row per stock, interval, family and settings fingerprint:

| Column | Meaning |
| --- | --- |
| `stock_asset_id`, `interval`, `family` | What is analysed (`ELLIOTT_WAVE`, `HARMONIC_FORMATION`, `CANDLESTICK`, `OUTLOOK`) |
| `settings_key` | SHA-256 of detector version, effective settings and child interval, as `ElliottHistoryStore.settingsKey` does today |
| `settings` | The effective settings (jsonb), so background work can run without a user |
| `snapshot_through`, `snapshot` | The latest snapshot (compact jsonb) and its candle |
| `inputs_complete` | False when an Elliott snapshot was computed without fresh 60-minute candles |
| `history_from`, `history_through`, `history_status` | As-of coverage (`NONE`, `PENDING`, `RUNNING`, `READY`, `FAILED`) |
| `tail_fingerprints` | Fingerprints of the last five input candles (see [Invalidation](#invalidation)) |
| `tier`, `requested_at`, `last_used_at` | Why it exists and when it was last read |
| `lease_owner`, `lease_until`, `attempts`, `last_error` | Background work, as in V92 |

`analysis_signals`, the as-of history: primary key (series, pattern, stage, confirmed candle), with
`known_at`, direction, score, anchor candle, price and a small jsonb for family-specific detail.
Deleted with its series. V92's two tables move into these (data copied, then dropped), and
`ElliottHistoryBuilder` becomes the store's background job.

`analysis_lookups`: user, stock and day of each opened stock page, kept seven days. It counts
distinct viewers and enforces the per-user limit.

`candle_revisions` gains `deep_revised_from`: the earliest candle older than the five newest whose
open, high, low, close or volume changed.

### Invalidation

- **Appends never invalidate.** A new completed candle extends the snapshot and the history from
  where they stand.
- **The forming candle is never an input.** As today, inputs stop at the first incomplete candle,
  so intraday updates change nothing.
- **Late finalisation is absorbed.** Providers often correct the last candle or two after the close.
  Each series keeps fingerprints of its last five input candles; an extension that finds a
  difference redoes the work from the first changed candle.
- **Deep revisions truncate.** The candles trigger records `deep_revised_from` for changes older
  than the newest five. A series that covers that candle drops its history from there and rebuilds
  forward. A split rewrites the whole history, so it rebuilds everything, which is correct.
- **Settings and detector versions** are part of `settings_key`. A change creates a new series; the
  old one ages out.
- **Chart hindsight cache**: keyed by the last completed candle and the deep revision instead of the
  raw generation. A new completed candle still invalidates it (structures can change), but once a
  day rather than on every sync.

### Which settings are computed

Detection depends on each user's candlestick, Elliott, harmonic and scoring preferences.

- **Factory settings**: always, for every tier. Most users keep them, so this is the series that
  is shared.
- **Followers' settings**: for followed stocks, the scheduled checks already compute each
  follower's settings; they save those series too.
- **Anyone else's custom settings** on an unfollowed stock: computed on demand, as today, and saved
  as a previewed series.

### Tiers

| Tier | Enters when | Snapshot | As-of history | Leaves when |
| --- | --- | --- | --- | --- |
| Followed | The stock is in `TrackedInstrumentGuard.globallyTrackedSymbols()` | Written by every scheduled check, factory settings included | Built in the background if missing, then extended by each check | The last follow ends (drops to Viewed) |
| Previewed | Someone tests a custom alert on the stock | On demand | Built in the background (as V92 does today) | 60 days without use |
| Viewed | A signed-in user opens `/stock/{symbol}` and its candles are stored | Background, factory settings, daily interval (weekly or monthly only if opened) | Only when at least two different users opened it in seven days | 60 days without use |

For followed stocks the factory-settings Elliott snapshot is computed even when no follower uses
factory settings, because most new alerts will. Cost: about one scan per followed stock per
completed candle, about eight minutes a day at the 500-stock limit, run in background slices.

An Elliott snapshot is alert-grade only where the 60-minute candles are kept fresh. Today that
happens only for stocks with an Elliott follow (built-in or custom), whose checks refresh them. For
other followed stocks the snapshot is saved with `inputs_complete = false`, and a new Elliott alert
on them still scans at baseline. Open question 1 asks whether to fetch those candles.

### Lookups and their limits

- A **lookup** is an opened stock page, recorded on the server for signed-in users only. Search
  suggestions, watchlist tables and chart API calls are not lookups.
- A lookup **never fetches extra market data**. The page already refreshes its daily candles
  (`refreshForFirstPage`); the snapshot uses what is stored. 60-minute candles are used only if
  they are already stored and fresh; otherwise the Elliott snapshot is saved with
  `inputs_complete = false`.
- **Per user**: at most 30 new stocks queued per hour. Past that the page works as usual, without
  precomputing.
- **Queue**: one shared queue of at most 500 pending requests, merged by series. Priority: followed
  (missing history) → previewed → viewed by several users → viewed once; oldest first within a
  level. When full, the lowest-priority oldest request is dropped.

### Background work and the CPU budget

- One dispatcher queue, `analysis-store`, with one slot, working in 20-second slices (as
  `ElliottHistoryBuilder` does now), with leases so a restart resumes.
- It pauses while scheduled alert check jobs are pending or running, so the daily run keeps the
  CPU.
- The Viewed tier has an hourly budget (default 5 minutes of work per hour). Followed and
  Previewed work is sliced but not budgeted, because alerts and open builder pages wait for it.
- Retention: series unused for 60 days are deleted unless followed. Snapshot rows are a few
  kilobytes; histories are compact rows (V92: about 40 kB per stock), not whole payloads.

### Consumers

- **Custom alert baselines** read the pattern families from the snapshot when its candle is the
  stock's latest completed candle, the settings match and (for Elliott) `inputs_complete` is true.
  Otherwise they compute as today and save the snapshot, so the next reader benefits. Indicators,
  prices and filings are still computed live.
- **Scheduled checks** compute as today, then save the snapshot and append that candle's history
  row. One shared function produces both, so the store and the alerts cannot disagree.
- **Custom alert previews** read the as-of history (today's V92 behaviour, moved into the store).
- **Chart overlays** keep their hindsight cache with the new key.
- **Stock page** (later): a "latest signals" summary from the snapshot, without computing anything
  on page load.

## Quick wins before the store

Independent of the store, small, and most of the gain for stocks the store has not seen:

1. **No waiting between baseline batches.** Dispatch baselines when an alert is saved, and keep
   working through batches in 30-second slices instead of 20 stocks every 15 seconds. Removes about
   3.5 minutes from the 25-rule case; for one ordinary rule on 300 stocks, most of today's wait.
2. **One Elliott scan when only the latest candle is evaluated.** The evaluator takes a pattern
   signal when its confirmation candle is inside the window and drops it only if it was first shown
   *after* the evaluated candle (`knownAt > to`). Everything in the latest count was shown by the
   latest candle, so a baseline or a one-candle daily check does not need the previous candle's
   scan. It stays for catch-ups of earlier candles and for "on each of the last N candles" windows.
   About 0.4 s per stock.
3. **Progress you can see.** "Checking 120 of 300" on the alert, states appearing as they are set,
   the stocks of the open watchlist first.
4. **Profile the non-Elliott 0.6 s per stock** (candle loading through Hibernate, indicator series,
   candlestick, harmonic and outlook scans) before optimising it.
5. Later: evaluate cheap conditions first in baselines and skip pattern scans that cannot change
   the answer (triggers still record every condition); parallel baselines capped below the core
   count.

## Configuration

As built (see [Implementation notes](#implementation-notes)):

| Property | Default | Meaning |
| --- | --- | --- |
| `analysis-store.enabled` | follows `custom-alerts.background-enabled` | Background work, lookups and the follow hook on and off |
| `analysis-store.lookup-precompute` | true | Whether opening a stock queues a snapshot |
| `analysis-store.followed-snapshots` | true | Whether every check of a followed stock queues its factory-settings snapshot |
| `analysis-store.followed-histories` | true | Whether followed stocks get an Elliott history built in the background |
| `analysis-store.lookups-per-user-hour` | 30 | New stocks one person can queue per hour |
| `analysis-store.max-pending` | 500 | Requests for unfollowed stocks before the lowest-priority, oldest are dropped |
| `analysis-store.viewed-budget-seconds-per-hour` | 300 | Work per hour for stocks nobody follows |
| `analysis-store.popular-viewers` | 2 | Distinct viewers in seven days before a viewed stock gets a history |
| `analysis-store.slice-ms` | 20000 | Length of one background slice |
| `analysis-store.delay-ms` | 10000 | Pause between background slices |
| `custom-alerts.baseline-slice-ms` | 120000 | Longest baseline run (quick win 1) |
| `custom-alerts.baseline-delay-ms` | 5000 | Pause between baseline runs (was 15000) |
| `custom-alerts.baseline-threads` | 0 | Stocks baselined at once; 0 means a quarter of the processors, at most four |

Stored snapshots unused for 30 days are removed by the cache's existing cleanup
(`historical-signal-cache.cleanup-cron`); Elliott histories after 60 days, as before.

## Edge cases

| Case | Behaviour |
| --- | --- |
| Provider corrects yesterday's candle | Tail fingerprints differ; the series redoes its last candles |
| Split or deep correction | `deep_revised_from` truncates history from that candle; rebuilt forward |
| Detector version or a user's settings change | New `settings_key`; old series ages out |
| 60-minute candles missing or stale | Elliott snapshot saved with `inputs_complete = false`; baselines compute instead; the next scheduled check, which refreshes them, is authoritative (as today) |
| Stock opened, never followed, opened again a month later | Candles refreshed by the page; snapshot and any history extended from where they stopped |
| Many stocks opened quickly, or a script opening hundreds | Per-user hourly limit and queue cap; the pages keep working |
| Last follower stops following | Tier drops to Viewed; no more daily updates; aged out after 60 days unused |
| Restart mid-slice | Lease expires; the slice is redone from the series' last saved candle |
| Two producers at once (check and background job) | Lease per series; the scheduled check wins and the job skips |
| Database short of space | Retention runs daily; histories of unfollowed stocks are removed first |

## Testing

- **Equivalence**: for each family, on real cached stocks copied into the test schema, the stored
  snapshot and history equal a direct computation at that candle, after a fresh build, after
  appending candles, after a tail correction and after a deep revision.
- **No look-ahead**: the existing cut-copy check, run against the store.
- **Load**: `CustomAlertLoadTest` with warm and cold stores; the goals above as assertions.
- **Queue**: per-user limit, queue cap and dropping order, priorities, pausing during the scheduled
  run, the hourly budget, leases after a restart.
- **Browser**: the baseline progress display, in Edge and WebKit (Firefox cannot run on this
  machine).

## Delivery phases

| Phase | Scope | Done when |
| --- | --- | --- |
| 0. Quick wins | Quick wins 1–4 | Load test: one rule on 300 stocks under a minute; 25 rules measurably faster; profile written up |
| 1. Store for followed stocks | V93 tables, invalidation, scheduled checks save snapshots, baselines read them | Equivalence tests pass; 25 rules on 300 followed stocks within one minute in the load test |
| 2. Lookups | `analysis_lookups`, the Viewed tier, limits, budget | Queue tests pass; opening 50 stocks quickly leaves pages and the daily run unaffected |
| 3. Histories in the store | V92 moved in, histories extended by scheduled checks, previews read them | Live-run comparison (preview vs stored triggers) still matches on every pair |
| 4. Chart cache and stock page | New hindsight key; optional snapshot summary on the stock page | Chart history survives intraday syncs; no detector runs on stock page load |

## Implementation notes

What the build does differently from the design above, and why:

- **The store is the existing durable cache.** `historical_signal_cache` (V48) already had leases,
  settings fingerprints and one row per profile, so it became the shared store instead of a new
  `analysis_series` table. Three families were added for what alerts read: `ELLIOTT_LATEST` (the
  latest candle's developing count), `HARMONIC_ALERTS` (alert-mode formations) and
  `CANDLESTICK_RECENT` (recent candlestick signals). V93 adds `inputs_fingerprint`.
- **Exact input fingerprints instead of a trigger.** An entry is valid for exactly the inputs it read.
  The chart's histories fingerprint the completed candles in the database (count, last candle and a
  sum of row hashes of every price and the volume, before the first incomplete candle). Alert
  snapshots fingerprint the candles they hold in memory, plus, for Elliott, the 60-minute candles
  (fingerprinted after the scheduled check has refreshed them). The candles trigger is unchanged and
  `deep_revised_from` was not needed. The completion cutoff was removed from the chart histories'
  settings, so one row per profile is replaced as candles complete instead of a new row each day.
- **Elliott histories stay in V92's tables**, with what the design gave them: a priority (previews 0,
  popular stocks 2, followed stocks 3), extension after every scheduled check of a followed stock, and
  input fingerprints. A fingerprint of everything but the last five candles comes from the database, so
  candles older than today's loaded window still count. One fingerprint per candle covers the last
  five. A late correction re-reads the history from the changed candle; an older change rebuilds it.
- **"Factory settings" means what someone who never changed theirs gets.** No owner resolves to each
  preference service's factory view, so default users share the factory entries.
- **Lookups are recorded on the chart's first daily candle page** (`/api/stocks/{symbol}/candles`),
  not on the page request. A stock opened for the first time has no candles until the chart fetches
  them. Once per person, stock and day.
- **The outlook is not stored.** It depends on each owner's profile, has its own in-memory cache, and
  is a small part of a check.
- **Quick win 5 (parallel baselines) was built.** A quarter of the processors, at most four
  (`custom-alerts.baseline-threads`, 0 = automatic). Machines with four cores or fewer keep one
  thread. Baselines called inside a transaction stay on the caller's thread. Skipping slow conditions
  in baselines was not built: the goals were met without it.
- **Background work steps aside for the daily run.** The snapshot worker and the Elliott history
  builder both pause while scheduled checks are waiting or running. The builder still builds a history
  that a preview is waiting for.
- **Not built:** the stock page's snapshot summary (open question 5).
- **Measured** on this laptop, with `CustomAlertLoadTest` (one transaction, one baseline thread) and on
  the running dev server (real commits, four baseline threads):

  | Case | Before | Now |
  | --- | --- | --- |
  | 25 alerts on 270 followed stocks, snapshots stored (load test) | 787 s of baselines plus 14 waits of 15 s, about 16.6 min | 65.6 s |
  | 25 alerts on 250 followed stocks, live | — | every state set 43.5 s after the first save |
  | One new Elliott + RSI alert on 270 stocks, snapshots stored | about 3.5 min of waiting between batches alone, plus the scans | 13.8 s |
  | One new indicator-only alert on 270 stocks | about 3.5 min of waiting alone | 10.4 s |
  | One new Elliott alert on 270 stocks, nothing stored (the floor) | — | 445 s on one thread (1.65 s per stock) |
  | New alert on 14 followed stocks, live | up to 15 s before the first baseline, then a scan per stock | 2.5 s |
  | Background snapshot of a followed stock (three families) | — | 2.4 s (load test), 3.7 s (live) |
  | Cold daily check, 25 alerts, same run with and without the store | 2.53 s per stock | 2.59 s per stock (+2%) |
  | Chart Elliott history, second request | recomputed after every candle sync | 75 ms (first 774 ms) |

  Results are unchanged: with snapshots stored, the 25 alerts match the same 1,198 of 6,750 alert-stock
  pairs as when computed from scratch. The live run's 134 triggers still equal the preview's replay on
  all 50 alert-stock pairs.
- **The cost moved to the background.** Keeping followed stocks' snapshots current costs about 2.4–3.7 s
  per stock after each daily run, roughly 15 minutes for 250 followed stocks. Building histories for
  followed stocks (`analysis-store.followed-histories`) costs 20 s to 8 min per stock, once. Both run
  after the scheduled checks, and both can be switched off.
- **Profiling the non-Elliott cost.** A cold check spends most of its non-Elliott time in the
  candlestick scan (0.3–0.7 s per stock). Most of that is the harmonic scan that candlestick scoring
  runs for its cross-pattern confluence, once per candlestick candidate, plus Elliott confluence keys
  built from candle text. The store serves that scan in 10–20 ms once it has been computed. Memoising
  the confluence's harmonic prefix scans is the next optimisation, if the scheduled run needs one.

## Open questions

1. Should opened stocks, and followed stocks without an Elliott follow, also fetch 60-minute
   candles while the provider budget allows (say, under half of the day's Twelve Data requests
   used)? Without them their Elliott snapshots are not alert-grade.
2. Do harmonic and candlestick signals need as-of histories, or is one scan with detection
   candles exact? The no-look-ahead check per family answers it.
3. Is "two viewers in seven days" the right bar for building a viewed stock's history?
4. Weekly and monthly snapshots on lookup: only when opened, or always (they are cheap)?
5. Should the snapshot feed the stock page and watchlist tables, so current signals show without
   any computation on page load?
6. The app runs as one instance today. Leases make several instances safe, but are they needed?

As built, pending a decision:
1. Nothing extra is fetched. Snapshots fingerprint the 60-minute candles that are stored, so a snapshot
   read with stale or missing child candles can never be mistaken for a fresh one.
2. One scan is enough. In October's look-ahead check, candlestick and harmonic rules replayed from one
   scan gave the same answers with and without later candles on three stocks. So no as-of history
   was built for them.
3. Two viewers in seven days (`analysis-store.popular-viewers`).
4. Daily only on lookup; weekly and monthly snapshots come from the checks of followed stocks.
5. Not built.
6. One instance; the store's leases and `for update skip locked` claims make more safe.
