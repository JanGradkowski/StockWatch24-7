# Signal pipeline audit — 22 September 2026

The audit inspected the running application's PostgreSQL data read-only, traced the scheduler, detection, lifecycle and notification paths, and replayed a cached candle snapshot through the current detectors. No live jobs or notifications were triggered.

## Confirmed faults and repairs

1. **CDR market-data refresh was failing.** Twelve Data required a higher subscription tier. Yahoo's valid `CDR.WA` fallback was rejected because the stored name is `CD Projekt S.A.` while Yahoo supplies `CD Projekt Red S.A.` as its long name. Its complete short name, `CDPROJEKT`, matches the stored company after legal-suffix and spacing normalization. The compatibility check now accepts that match while retaining listing, currency and instrument checks. The latest cached daily CDR candle was August 25; failed refreshes prevented all technical families from running for that symbol.
2. **Daily snapshots contaminated weekly history.** Yahoo weekly responses can append live quotes, including Monday quotes. The importer previously filtered such entries only for monthly responses. It now requires weekly aggregates to start at Monday midnight in the exchange timezone, before converting timestamps to canonical UTC dates. The live database contained 102 noncanonical weekly rows across 54 symbols. Migration V70 removes those rows without moving them onto or overwriting genuine weekly bars, resets affected refresh state, repairs history cursors, and lets existing deletion triggers invalidate historical caches. Saved signals are preserved.

The updated application must be started to activate the provider fix and apply V70. The audit did not modify the running production database or restart the IDE-managed application. Existing failed jobs are not rewritten, and historical candidate alerts are not backfilled by this migration.

## Other observations

- Only AAPL, CDR, ^GSPC, MARA, NFLX and TPG0 have active technical alert rules, covering daily, weekly and monthly intervals and both directions. The watchlist contains 25 symbols; membership alone does not enable each technical family.
- AAPL's September 18 daily Hanging Man was saved and its initial email was marked delivered on September 22 at 08:26 Brussels time. Its lifecycle was subsequently `REJECTED` because the next candle did not confirm it. Initial detection and later confirmation are separate events.
- The email outbox had no pending deliveries. This verifies application delivery state, not receipt in the recipient's inbox.
- Jobs were completing, with recent failures concentrated on CDR. Some September 17–21 jobs completed on September 22, so the observed processing was not consistently immediate.
- `CrossPatternConfluenceService` adjusts scores after detection; it does not require agreement between families before admitting a candlestick signal. No confluence defect was found in this audit.
- September's monthly candle is still open; a new monthly result is not expected every day.

## Replay and validation

The September-only replay found three daily candlestick candidates with both the configured and factory trend rules. Removing malformed weekly rows increased the total candlestick candidates from three to four and developing Elliott candidates from six to seven. This establishes that the bad weekly input changed detection results; it does not establish that every replayed candidate should have produced a historical notification.

An extended August 1–September 22 replay evaluated 237 completed candle endpoints, including monthly endpoints. It produced candlestick, Elliott and harmonic candidates, including an August 31 MARA harmonic formation. No eligible harmonic candidate appeared in the September-only replay.

Replay uses current code, the available candle snapshot and the stored analysis profile. The account has no saved candlestick-shape, Elliott or harmonic definition overrides, so factory definitions apply. Historical rule activation, prior code versions, subsequent candle corrections, existing Elliott-cycle rules and delivery eligibility can differ from a detector-only replay. A missing saved event is therefore a finding for investigation, not proof of a lost alert.

**174 targeted regression tests passed**, covering the three detectors, confluence, scheduler, candlestick lifecycle, completion boundaries, provider fallback and refresh paths, and the new provider/cache-repair cases. A separate PostgreSQL 16 database successfully applied migrations V1–V70. The cache-repair integration test verifies preserved weekly prices, unaffected daily data and other series, refresh-state removal, cursor repair, generation invalidation and idempotence. The temporary database was stopped afterward.

The opt-in `SignalPipelineAuditTest` ran successfully separately. It reads an explicit JSON snapshot and does not start Spring, refresh market data or send email. Example for the local audit snapshot:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=SignalPipelineAuditTest' '-Dsignal.audit.input=target/signal-audit-input.json' '-Dsignal.audit.canonicalOnly=true' '-Dsignal.audit.since=2026-08-01' '-Dsignal.audit.output=target/signal-audit-extended-report.json' test
```

Snapshot and generated reports remain in ignored `target/`. The regular regression suite skips this diagnostic unless `signal.audit.input` is supplied. This audit does not claim an exhaustive detector-accuracy benchmark or a passing full repository test suite.
