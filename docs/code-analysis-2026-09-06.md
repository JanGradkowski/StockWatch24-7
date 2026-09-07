**StockWatch24-7 code analysis — 6 September 2026**

Implementation status: the findings below describe the pre-fix baseline. See [the implementation report](review-implementation-2026-09-06.md) for the changes, final verification, and remaining validation limits.

The first fixes should address password-reset attempt accounting, stale MFA challenges, lost alert emails, and historical-cache consistency. The fresh dependency audit also fails the release security gate; item 21 documents the findings and upgrade candidates. Performance work should then focus on database queries, repeated detector runs, and bounded background work. TOTP calculation itself is a low-priority optimization.

This review covers the current working tree, including existing uncommitted changes. Repository-wide searches and structural measurements covered 212 production Java files, 127 test Java files, 32 templates, 10 JavaScript files, the stylesheet, Maven configuration, migrations, CI, and the supplied Nginx configuration. Production Java, HTML, JavaScript, and CSS total approximately 80,954 lines. Detailed manual tracing concentrated on authentication, account management, caching, provider requests, scheduling, alerts, archives, and chart calculations. This is a repository-wide static review with focused test execution, not a claim that every line or every runtime path has been verified.

No application source was changed. Local credentials were not read. Production database plans, production traffic, live HTTP headers, and multi-instance behavior were not measured. Findings below distinguish code defects from optimization candidates; proposed concurrency and transaction regression tests have not yet been implemented.

**Priority findings and suggested fixes**

**1. High — Failed password-code attempts roll back, defeating the five-attempt cap.**

Evidence: [PasswordSecurityCodeService.java:61](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/PasswordSecurityCodeService.java:61>), [AccountSecurityService.java:67](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AccountSecurityService.java:67>), [PasswordRecoveryController.java:51](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/controller/PasswordRecoveryController.java:51>).

`consume()` increments `failedAttempts` and returns false. Its caller, `resetPassword()` or `changePassword()`, then throws `IllegalArgumentException` inside the encompassing transaction. The increment joins that transaction and is rolled back. An attacker can continue trying reset codes during their five-minute lifetime without exhausting the intended five attempts. `/reset-password` has no application-level attempt limiter; the supplied proxy has only its general limit for this route. The current implementation also performs BCrypt work on the proposed password before validating the code.

Fix: return an explicit failure result from the transactional operation, allowing attempt accounting to commit; map that result to a user-facing error outside the transaction. Keep successful code consumption and password update atomic. Alternatively, use a carefully isolated attempt counter. Do not blindly add `REQUIRES_NEW` around the entire consumption flow while holding the user lock, or broadly suppress rollback for all argument errors. Add account and client throttles before BCrypt work, validate the code's exact length/format, and return a generic reset failure for both unknown accounts and invalid codes. Apply a shared throttle to `/settings/password` and `/settings/mfa/start` too; those password-verification paths lack the factor limiter used by the MFA disable/regenerate endpoints.

Validation: against an isolated PostgreSQL database, submit five wrong codes through the real controller/service proxies, then the correct code; verify rejection and a persisted count of five. Check concurrent attempts and successful single-use consumption. Jakarta transaction annotations default to REQUIRED and roll back for runtime exceptions; Spring documents participation in the outer physical transaction. [Jakarta Transactions](https://jakarta.ee/specifications/transactions/2.0/apidocs/jakarta/transaction/transactional), [Spring transaction propagation](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html).

**2. High — A pending MFA login survives password/session revocation and can ignore disabled-account state.**

Evidence: [MfaAuthenticationSuccessHandler.java:27](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/security/MfaAuthenticationSuccessHandler.java:27>), [MfaLoginController.java:42](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/controller/MfaLoginController.java:42>), [AccountSecurityService.java:130](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AccountSecurityService.java:130>), [CustomerUserDetailService.java:26](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/CustomerUserDetailService.java:26>).

The first-factor handler stores only a pending user ID and timestamp. MFA completion loads the current security version and stores it as the session's accepted version. Thus a password reset or session revocation between factors does not invalidate the pending login. Completion also directly calls `UsernamePasswordAuthenticationToken.authenticated(...)`; merely loading `UserDetails` does not enforce its `disabled` flag. A deletion-pending account can therefore complete an earlier MFA challenge if a valid factor is supplied. This requires a pre-existing successful first factor and a valid second factor; it is not an MFA bypass from an unauthenticated session.

Fix: bind the pending challenge to the security version accepted at password authentication. Under the user lock, check the version, challenge expiry, verification/deletion state, and second factor together. Return an immutable accepted identity/version, rather than re-reading and adopting a newer version after verification. Use the authentication/session strategy at successful MFA completion, including session-ID rotation and removal of pending state.

Validation: start MFA, reset the password or revoke sessions in another session, then submit a valid factor. Also test deletion pending, expired challenges, and concurrent factor reuse. All stale/disabled paths must reject authentication.

**3. High — SMTP failure can permanently lose an initial alert email.**

Evidence: [ScheduledAlertService.java:388](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java:388>), [ScheduledAlertService.java:922](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java:922>), [ScheduledAlertService.java:1006](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java:1006>), [ScheduledAlertService.java:216](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java:216>).

An alert event is committed with `saveAndFlush()` before `sendSignalEmail()`. If SMTP throws, the job is retried, but the existing event causes the next detection pass to skip delivery. No retry worker queries events with a missing `initialEmailSentAt`. A process crash between the save and send has the same effect. Conversely, a crash after sending but before recording success leaves an ambiguous delivery outcome.

Fix: atomically persist the event and an outbox delivery row. Process the outbox independently with durable attempts, retry times, and ownership fencing. The congressional delivery workflow already offers useful patterns to reuse. Model delivery separately from detection. Preserve the distinction between intentionally disabled email and failed delivery. SMTP generally cannot provide exactly-once delivery; use stable message identifiers and explicitly handle the small duplicate-delivery window.

Validation: inject SMTP failure after event persistence, restart the worker, and verify eventual delivery without a second signal event. Test crashes around acknowledgement and ensure intentional email disabling does not build an unwanted backlog.

**4. High — Historical results can be labeled with a candle revision they were never calculated from.**

Evidence: [HistoricalSignalCacheService.java:68](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/HistoricalSignalCacheService.java:68>), [HistoricalSignalCacheService.java:85](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/HistoricalSignalCacheService.java:85>), [ChartController.java:247](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/controller/ChartController.java:247>).

The cache captures a revision, runs a supplier that queries candles, then captures another revision and writes the result against the latter unconditionally. A concurrent candle update between the supplier's read and the final snapshot makes an old result look current. The advisory lock protects cache calculations, not candle writers. Settings have a related race: the controller fingerprints preferences, but the supplier reloads preferences when constructing its detector.

Fix: capture immutable effective settings once and pass those exact settings into calculation. Read the input and its monotonic revision consistently, and store the result against that input revision. If a later revision check differs, discard/retry or publish only under the older revision so current reads miss it. A consistent database snapshot is another option, but avoid keeping a connection open for long CPU work. The current `count/max(timestamp)/max(updated_at)` tuple is not a guaranteed revision identifier; transaction timestamps and concurrent commits can also leave it unchanged after a real update. Prefer a revision counter advanced atomically with candle changes.

Validation: pause a computation after it loads candles, commit a correction/backfill on another connection, then resume. The next request must not accept the old result under the new revision. Repeat with a preferences change.

**5. Medium — The shared Twelve Data budget misses several provider request paths.**

Evidence: [MarketDataService.java:178](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/MarketDataService.java:178>), [MarketDataService.java:302](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/MarketDataService.java:302>), [TwelveDataService.java:179](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/TwelveDataService.java:179>), [TwelveDataService.java:342](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/TwelveDataService.java:342>).

Only the latest-candle sync reserves the new budget. Historical pagination, quotes, search, and metadata reach the common HTTP method without reserving it. Browser use can therefore exhaust provider quota despite apparently conservative local limits. Conversely, a reservation can be spent before API-key validation discovers that no HTTP request can be made.

Fix: enforce budget reservation immediately before each actual provider request in a single client boundary, with the correct request credit cost where endpoints differ. Validate configuration first. Centralize quota-error handling there too, and remove the outer reservation to avoid double counting. Ensure reservations commit independently of unrelated application transactions and local quota rejection does not mark a surrounding business transaction rollback-only.

Validation: exercise quotes, search, metadata, current candles, historical pages, and retries through mocked HTTP. Check one reservation per outbound request and no outbound request once the applicable budget is exhausted.

**6. Medium — Reclaimed jobs can be completed or rescheduled by their old worker.**

Evidence: [AlertCheckJobStore.java:168](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AlertCheckJobStore.java:168>), [AlertCheckJobStore.java:179](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AlertCheckJobStore.java:179>), [InsiderActivityCheckJobStore.java:113](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/insider/InsiderActivityCheckJobStore.java:113>).

Claims increment attempts and set a lease, but completion/retry SQL checks only job ID and `PROCESSING` status. If worker A exceeds its lease and B reclaims the job, A can complete or fail B's claim. The risk applies to multiple instances or overlapping workers after long processing pauses.

Fix: issue a unique claim token or generation and include it in every completion, retry, heartbeat, and delivery transition. Check affected-row counts. Extend leases for long work and stop publishing side effects after ownership is lost. The owner predicates in market-data coordination and congressional delivery are better starting points than the current job-ID-only API.

Validation: expire A's lease, claim as B, then invoke A's completion and failure methods. Neither may alter B's claim. Increasing concurrency should follow this fix.

**7. Medium — Session validation fails open if the account no longer exists.**

Evidence: [AccountSessionValidationFilter.java:25](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/security/AccountSessionValidationFilter.java:25>).

Validation is inside `Optional.ifPresent()`. When the account is absent, the authenticated security context survives. A missing stored security version is also silently replaced with the current version. Ownership-checking controllers provide additional protection, but authenticated endpoints without user lookup can remain reachable from the stale session.

Fix: reject absent users, disabled/deletion-pending accounts, missing version metadata, and mismatched versions. Clear the context and invalidate the session; return 401 for APIs and a login redirect for pages. If legacy-session compatibility is required, define it explicitly rather than silently upgrading any incomplete session.

Validation: delete the user behind an established session and request both an authenticated page and an authenticated-only data API. Both must lose authentication.

**8. Medium — Deletion-cancellation tokens can enter Nginx logs through Referer.**

Evidence: [stockwatch.conf.example:7](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/deploy/nginx/stockwatch.conf.example:7>), [SecurityConfig.java:93](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/config/SecurityConfig.java:93>), [cancel-account-deletion.html:3](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/resources/templates/cancel-account-deletion.html:3>).

The access-log format omits the request query string but includes `$http_referer`. The cancellation page keeps the token in its URL while loading same-origin CSS and JavaScript. The configured `strict-origin-when-cross-origin` policy sends full path/query information on same-origin requests, so those asset requests can log the token. This is an inference from the supplied configuration, not an observation of live logs. [Referrer policy behavior](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Referrer-Policy).

Fix: set `Referrer-Policy: no-referrer` on token-bearing pages and remove or sanitize query strings in the logged referrer. Prefer exchanging the URL token for short-lived server-side state and redirecting to a clean URL before rendering assets. Keep token validation and CSRF protection on confirmation.

Validation: use a synthetic token in a browser through the supplied proxy and verify it appears nowhere in the access log, including asset requests.

**9. Medium — Scheduling, provider calls, and email delivery contend for the default scheduler thread.**

Evidence: [ScheduledAlertService.java:216](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java:216>), [application.properties:59](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/resources/application.properties:59>), [TechnicalOutlookAsyncConfiguration.java:13](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/config/TechnicalOutlookAsyncConfiguration.java:13>).

There is no custom `TaskScheduler` or scheduler-pool configuration. The technical-outlook executor is a separate facility and does not execute the other scheduled methods. With the checked-in configuration, Spring Boot's scheduler uses one thread. A slow provider, SMTP batch, or cleanup delays unrelated jobs. At the configured 20-second fixed delay, a single alert worker handles at most three jobs per minute before processing time; 500 symbol jobs require at least about 2.8 hours. This is a scheduling bound, not a measured production throughput. [Spring Boot scheduling](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html).

Fix: keep scheduled callbacks short and dispatch claimed jobs to bounded workers. Separate CPU calculation, provider I/O, and mail delivery. Set limits in concert with the DB connection pool and provider budget, preserve ordering per symbol/interval, and implement item 6 before adding overlapping workers. Monitor oldest pending job age as well as queue depth.

**10. Medium — Outcome sorting loads the entire archive and performs per-event queries.**

Evidence: [AlertRuleService.java:461](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AlertRuleService.java:461>), [AlertRuleService.java:515](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AlertRuleService.java:515>), [ElliottTradePlanService.java:228](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/ElliottTradePlanService.java:228>), [VirtualTradeService.java:155](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/VirtualTradeService.java:155>).

Sorting by trade return loads every user's event, builds every view, sorts in memory, and only then takes a page. Elliott stage-history lookup occurs per event, including on normally paginated archive paths. Virtual-trade archives also read all user trades and sort in memory. Cost therefore grows with lifetime history, not the requested page size.

Fix: persist or query a well-defined sortable outcome projection and paginate in SQL with a stable ID tie-breaker. Batch-fetch stage histories for the selected page. For floating virtual-trade returns, use a consistent quote snapshot/materialized projection so pagination remains coherent. Keep owner constraints in every query.

Validation: seed a large archive and measure query count and response latency for both date and return sorts. Query count for a page should remain bounded as total history grows. Use `EXPLAIN (ANALYZE, BUFFERS)` on an isolated representative database before selecting additional indexes.

**11. Medium — Chart indicators reload all candles even for a small viewport.**

Evidence: [ChartTechnicalIndicatorService.java:64](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/ChartTechnicalIndicatorService.java:64>), [HistoricalCandlestickService.java:175](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/HistoricalCandlestickService.java:175>).

`ChartTechnicalIndicatorService.calculate()` fetches all candles and constructs a full TA4J series before applying the requested output range. Up to 32 configurations can repeat indicator work. Full historical candlestick scans also deliberately reload/recalculate the archive; the durable history cache is wired to Elliott/harmonic overlays, not these candlestick scan paths. Existing indicator-count and period limits are useful but do not bound the input series size.

Fix: validate configurations/ranges before loading data, limit ordinary chart output, and query the viewport with sufficient warm-up history. EMA and cumulative indicators need an explicit continuity policy or persisted checkpoints; arbitrary truncation would change values. Deduplicate identical configurations, cache shared immutable results by candle revision/settings, and run truly full scans as bounded background work with pagination. If historical candlestick results are cached, include completion cutoff and effective definitions in identity, since time alone can make an existing candle complete.

Validation: compare paged values with full-history values for recursive and cumulative indicators, then measure allocation and CPU against long intraday histories.

**12. Medium — A shared alert job repeats detection for equivalent rules.**

Evidence: [ScheduledAlertService.java:388](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java:388>), [ScheduledAlertService.java:943](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java:943>).

Candles/enrichment are partly shared, but the rule loop calls historical harmonic detection or `detectSignals()` again for each rule. Multiple subscribers and BUY/SELL rules with identical settings repeat the same calculation. Preferences are also repeatedly loaded during detection, confluence, and personalization.

Fix: within each job, snapshot preferences once per user and group calculations by family, interval, candle revision, and effective detector/indicator settings. Compute immutable detections once per distinct configuration, then perform user-specific scoring, eligibility, ownership, and delivery separately. Do not share personalized final responses solely by symbol.

Validation: many rules with identical settings should invoke each detector once per configuration while still producing the same per-user events. Changed settings must create a separate calculation.

**13. Medium — Two maps grow without bounds, and local cache invalidation has consistency gaps.**

Evidence: [MarketDataService.java:46](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/MarketDataService.java:46>), [MarketDataService.java:479](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/MarketDataService.java:479>), [TechnicalOutlookService.java:69](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/TechnicalOutlookService.java:69>), [TechnicalOutlookService.java:176](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/TechnicalOutlookService.java:176>), [TechnicalOutlookService.java:403](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/TechnicalOutlookService.java:403>).

`recentFailedSyncs` removes expired entries only when that same key is read again; one-off failed symbols remain indefinitely. Completed `refreshStatuses` entries have no eviction. The bounded outlook cache is safer, but its invalidation event is process-local, and a calculation started before invalidation can insert an old view afterwards. Other instances can serve stale results until their five-minute TTL expires. This is bounded staleness for the outlook cache, distinct from item 4's mislabeled durable entry.

Fix: bound and expire the status/failure stores. Use revision-bearing cache identities or a generation check when publishing results. For multiple instances, read a lightweight shared revision or distribute invalidation, while retaining a TTL fallback. Choose an explicit acceptable staleness policy for user activity included in outlook views.

Validation: submit many unique invalid symbols and confirm memory stabilizes; race a refresh against calculation; update data on one instance and read from another.

**14. Medium — Even a historical-cache hit scans candle metadata and writes to PostgreSQL.**

Evidence: [HistoricalSignalCacheService.java:109](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/HistoricalSignalCacheService.java:109>), [HistoricalSignalCacheService.java:147](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/HistoricalSignalCacheService.java:147>), [HistoricalSignalCacheService.java:189](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/HistoricalSignalCacheService.java:189>), [BoundedTtlCache.java:31](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/BoundedTtlCache.java:31>).

Every hit aggregates the candle range, loads/deserializes the JSON payload, and updates `last_accessed_at`. On misses, an advisory transaction lock and a connection remain held during the CPU calculation; same-key waiters on other instances can occupy more connections. The local LRU's `put()` additionally scans all entries for expiry under synchronization, although its current 500-entry bound makes that a smaller concern.

Fix: maintain O(1) revision metadata with candle writes, throttle last-access updates, and consider a small immutable in-process cache for decoded shared results. Move expensive work outside long database transactions using leases plus revision validation. Bound waiting/concurrency. Replace the custom LRU only if profiling shows contention; adding a cache library alone will not fix data identity or transaction scope.

Validation: measure warm-hit DB statements, lock wait time, pool occupancy, payload allocation, and cache hit/miss rates. Load-test many simultaneous requests for one cold key and for distinct keys.

**15. Medium — Each authenticated request can reload the same user several times.**

Evidence: [AccountSessionValidationFilter.java:25](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/security/AccountSessionValidationFilter.java:25>), [UserViewModelAdvice.java:10](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/controller/UserViewModelAdvice.java:10>), [UserViewModelAdvice.java:17](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/controller/UserViewModelAdvice.java:17>), [TechnicalOutlookController.java:184](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/controller/TechnicalOutlookController.java:184>).

The session filter loads the full user; unrestricted model advice performs another lookup, including for REST controllers; controller helpers often perform a third. Preference and detector helpers can add more. Static-resource requests also pass through the session filter. This makes frequent chart polling and page asset loading unnecessarily dependent on database work.

Fix: load one minimal account/security projection per request and expose a request-scoped current-user abstraction. Restrict view-model advice to page controllers. Reuse the same request identity for ownership checks. Avoid a long-lived security-version cache unless revocation propagation and its acceptable delay are explicitly designed. Inspect generated case-insensitive lookup SQL: the migrations provide ordinary unique email/ticker indexes, so normalize consistently or add matching functional indexes only after checking plans and existing duplicates.

Validation: count SQL statements for a warm chart API response and a page. Add a regression test for immediate session revocation after the refactor.

**16. Medium — Account operations hold database transactions and locks during SMTP.**

Evidence: [AccountSecurityService.java:233](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AccountSecurityService.java:233>), [PasswordSecurityCodeService.java:32](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/PasswordSecurityCodeService.java:32>), [AccountDeletionService.java:35](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AccountDeletionService.java:35>), [AccountDeletionService.java:76](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AccountDeletionService.java:76>), [EmailVerificationService.java:98](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/EmailVerificationService.java:98>).

These operations send mail before the enclosing database transaction finishes. Slow SMTP retains connections and, for several paths, user row locks. Messages can be delivered even if the later commit fails. Expired-account cleanup sends multiple messages inside one transaction, increasing lock duration and rollback scope.

Fix: use a transactional outbox for reliable security notices and verification/reset delivery, with short-lived sensitive payloads protected and purged. An after-commit event is sufficient only for deliberately best-effort notices. Process account cleanup in bounded batches and recheck deletion eligibility under lock so cancellation cannot race an already selected stale user.

Validation: make SMTP slow/unavailable and confirm unrelated account operations retain normal pool access. Roll back a business operation and verify no corresponding outbox delivery is committed.

**17. Medium — Frontend size and cache policy increase repeated page cost.**

Evidence: [style.css:1](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/resources/static/css/style.css:1>), [stock.html:1](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/resources/templates/stock.html:1>), [application.properties:1](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/resources/application.properties:1>), [stockwatch.conf.example:1](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/deploy/nginx/stockwatch.conf.example:1>).

`style.css` contains 15,188 lines / approximately 382 KB; `stock.html` contains 5,825 lines / approximately 280 KB of template source. A local gzip estimate reduces the CSS to approximately 57 KB, demonstrating potential transfer savings, not current observed wire size. The supplied application/proxy configuration declares neither compression nor explicit static-asset cache lifetimes. Spring Security defaults to no-store unless resource handling supplies cache headers. [Spring Security cache headers](https://docs.spring.io/spring-security/reference/features/exploits/headers.html).

Fix: extract page-specific inline JavaScript into versioned external files; split core styles from chart/settings/about styles; consolidate only verified redundant rules. Enable gzip/Brotli at the serving layer and cache fingerprinted static assets with a long lifetime. Keep personalized HTML, account exports, MFA setup/QR data, and tokens non-cacheable. Measure rendered HTML and real response headers before choosing budgets. Add image formats/sizes and lazy loading where appropriate; do not treat documentation screenshots as executable dependency bloat.

Validation: capture cold/warm browser network sizes and cache headers, and visually check all pages, themes, and breakpoints after CSS consolidation.

**18. Low/medium — Repeated behavior and oversized services make fixes inconsistent.**

Evidence: [AlertRuleService.java:2044](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AlertRuleService.java:2044>), [ScheduledAlertService.java:1154](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/ScheduledAlertService.java:1154>), [AlertNotificationService.java:1057](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AlertNotificationService.java:1057>), [signal-detail.html:941](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/resources/templates/signal-detail.html:941>), [activity-signal-detail.html:357](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/resources/templates/activity-signal-detail.html:357>).

Measured concentrations include `ElliottWaveDetectionService` at 4,300 lines, `AlertRuleService` at 3,330, `CandlePatternDetectionService` at 2,147, and `AlertNotificationService` at 1,405. Concrete duplication includes current-user resolution in at least eight controllers, interval-to-provider mappings, pattern-family classification, and tab/dialog behavior in the activity and regular signal templates. Several historical detail pages already reuse `signalDetailBehavior`; extend that existing approach rather than duplicating it again.

Fix: introduce a request-scoped user resolver, one interval adapter, and explicit pattern-family metadata. Split archive querying/view assembly, rule commands, detector execution, and notification rendering along existing responsibilities. Share frontend behavior through external modules with page data passed in attributes. Centralize shared indicator construction carefully, preserving the existing parity tests. Research-only helpers such as `TechnicalCalculationComparisonService`, `HistoricalSignalBacktestService`, and the walk-forward/performance research services have no other production call sites found in the source scan; consider moving them into test support or a research module after checking external/script use. Avoid an inheritance hierarchy merely to remove similar JPA getters or provider-specific policy differences.

Validation: use existing detector benchmark/parity tests for behavioral refactors and browser tests for shared tab/chart behavior. Add duplication checks with generated/model boilerplate excluded; line counts alone are not defects.

**19. Low/medium — Both Jackson generations ship, and optional wiring obscures required behavior.**

Evidence: [pom.xml:52](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/pom.xml:52>), [StockWatch247Application.java:36](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/StockWatch247Application.java:36>), [ChartController.java:68](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/controller/ChartController.java:68>).

The resolved dependency tree and built JAR contain Jackson 2.21.5 plus Jackson 3.1.4. Application JSON/cache code imports Jackson 2, while Jackson 3 also arrives transitively through Flyway. The built artifact contains 100 runtime dependency JARs totaling about 68.7 MB uncompressed; this is inventory, not proof those dependencies are unnecessary. Jackson 2 databind/core/Java-time together account for about 2.44 MB. Numerous production dependencies also use optional setter injection or manually created fallback services, allowing silent changes in behavior if wiring is missing.

Fix: plan migration to the framework-supported Jackson 3 path, explicitly preserving DTO serialization, sensitive-field exclusions, date formats, persisted snapshots, and cache fingerprints. Version/invalidate cache formats where necessary. Spring Boot documents Jackson 2 support as deprecated. Use constructor injection for truly required services and explicit feature configuration for optional functionality. [Spring Boot JSON support](https://docs.spring.io/spring-boot/reference/features/json.html).

Maven `dependency:analyze` reports starters, WebJars, and Java-time support as unused because many are activated by resources/reflection/autoconfiguration. Those reports are not a safe deletion list. Lightweight Charts is referenced by templates, and Java-time registration is exercised by the project's tests. Retain the dependency security gate and review version overrides against verified advisories/BOM updates.

**20. Low — Tighten TOTP input handling and simplify its internal work.**

Evidence: [TotpService.java:50](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/TotpService.java:50>), [TotpService.java:65](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/TotpService.java:65>), [TotpService.java:95](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/TotpService.java:95>), [AccountSecurityService.java:188](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/src/main/java/org/example/stockwatch247/service/AccountSecurityService.java:188>).

The current implementation uses 20 random secret bytes, HMAC-SHA1, six digits, a 30-second period, a ±1-step tolerance, and constant-time comparison for each candidate. `AccountSecurityService` uses a pessimistic user lock and `lastAcceptedTotpStep` to reject reuse; AES-GCM encrypts stored secrets with user-specific authenticated context. These are important existing safeguards. HMAC-SHA1 here is part of the TOTP protocol, not automatically a vulnerability. Successful OTP reuse must be rejected by the consuming authentication flow. [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238.html).

Suggested changes: reject malformed Base32 characters rather than silently skipping them; validate secret length and trailing bits; centralize factor normalization, including consistent trimming and `Locale.ROOT`; convert the input code to bytes once and decode/init the secret once per verification instead of once per candidate. Keep `Mac` local to the invocation because it is mutable. Inject `Clock` for all authentication-time decisions. Clarify or remove the unused stateless `verify()` convenience method so future callers do not confuse it with replay-protected authentication. Persist replay state atomically as now; do not cache successful authentication decisions or decrypted secrets. Re-rendering the QR on settings visits is a minor cost and does not justify a shared secret-bearing cache.

Validation: add all relevant RFC vectors, malformed/empty Base32, time boundaries, consistent formatted-code handling, expiry, and concurrent replay tests. Existing TOTP coverage contains two tests and verifies only one RFC time point plus skew. Optimizing these few HMAC operations is lower value than correcting items 1–4 and reducing database/provider work.

**21. High priority for the release gate ? Dependency auditing currently fails.**

The fresh `mvnw.cmd -Psecurity-scan -DskipTests verify` run completed its NVD/CISA update and analysis, then failed on **31 unique CVE matches across six reported JARs**. One Spring Data JPA CVE is also attributed to the Boot integration JAR through product matching. The packaged affected-version families and suggested upgrade baselines are:

| Resolved dependency | Suggested baseline | Evidence and applicability notes |
| --- | --- | --- |
| Spring Framework 7.0.8 | 7.0.9 or a later compatible patched version | Vendor fixes include [CVE-2026-59282](https://spring.io/security/cve-2026-59282/) and [CVE-2026-59313](https://spring.io/security/cve-2026-59313/). Their self-growing-list binding and functional SSE prerequisites were not found in the reviewed source. |
| Spring Security 7.1.0 | 7.1.1 or a later compatible patched version | [CVE-2026-59270](https://spring.io/security/cve-2026-59270/) requires embedded UnboundID LDAP, which was not found here. The affected APIs listed for [CVE-2026-59276](https://spring.io/security/cve-2026-59276/) were not found in the configured BCrypt/form-login flow. |
| Spring Data JPA 4.1.0 | 4.1.1 or a later compatible patched version | [CVE-2026-47834](https://spring.io/security/cve-2026-47834/) concerns unsanitized sorting passed to native queries. The reviewed archive code uses an explicit sort allowlist. |
| Tomcat 11.0.23 | 11.0.25 or a later compatible patched version | The [Tomcat 11 advisory](https://tomcat.apache.org/security-11.html) lists fixes in 11.0.24/25. Many matches concern optional Realm/rewrite/cluster/example features absent from the supplied configuration. HTTP/2 is terminated at Nginx and proxied over HTTP/1.1 in that configuration. Validate actual deployment settings. |
| Log4j API 2.25.4 | 2.25.5 or 2.26.1 (or a later compatible patched version) | [CVE-2026-49844](https://logging.apache.org/security.html#CVE-2026-49844) concerns JSON serialization of a MapMessage containing non-finite values. That application usage was not found; SLF4J/Logback is the configured logging stack. |

Use [Spring Boot 4.1.1](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/) as a candidate coordinated parent upgrade, then inspect its effective dependency tree. Update/remove the explicit `tomcat.version=11.0.23` override as appropriate: changing the parent alone leaves that override in force. Verify all proposed resolved versions and rerun the full tests and security profile; these baseline suggestions are not a claim that an untested POM change will clear every advisory.

These are **scanner matches against affected versions, not 31 demonstrated exploits in this application**. Representative vendor prerequisites were checked against source/configuration; a complete per-CVE deployment applicability assessment remains necessary. The scanner labels CVE-2026-59313 as 9.8, while Spring's advisory classifies it as low-severity SSE stream corruption, illustrating why raw scores should not replace vendor review. Avoid blanket suppressions. If suppression is warranted, make it narrowly scoped, justified by an absent prerequisite, reviewed, and time-limited.

Scanner coverage also has limits: OSS Index was disabled because credentials were unavailable, and the embedded Lightweight Charts package lacks the npm lockfile/node_modules needed for complete Node dependency analysis. Preserve a separate audited frontend dependency inventory if WebJars prevent that analyzer from completing its coverage. The exact generated findings are available in [dependency-check-report.html](<C:/Users/Jan Gradkowski/IdeaProjects/StockWatch24-7/target/dependency-check-report.html>).

**Validation performed**

- Two focused Maven runs: **172 tests passed across 29 test classes**, no failures/errors/skips. Coverage included TOTP/crypto, request/security filters, serialization, configuration validation, caches, provider fallback/pagination, chart controllers, scheduling, archives, virtual trades, and outlook calculations. These are selected existing unit/component tests, not the full integration or research suite. They do not prove the newly identified races are safe.
- Maven dependency analysis and filtered dependency tree completed successfully. Runtime JAR contents and source size/duplicate-block measurements were inspected.
- Build/package completed during the security-profile run. The vulnerability scan **completed and failed the configured security gate**; see item 21 and the generated HTML report. Packaging success does not make the full security-profile build successful.
- Tests ran with the locally installed Java 26.0.1 against the project's Java 25 target. CI uses Java 25; repeat the full suite there and run transactional/concurrency regressions against an isolated PostgreSQL database.
- No production load tests, SQL execution-plan collection, live provider requests, browser penetration testing, or full CodeQL scan were performed. No code fixes were applied by this review.

**Suggested implementation order**

1. Address the dependency security-gate failure with a coordinated, tested patch upgrade. Fix/reset-test attempt accounting, pending MFA validation, and fail-closed session handling. Remove token leakage and throttle sensitive password actions.
2. Introduce durable alert delivery and fenced job ownership, then shorten transactions around mail/provider work.
3. Correct cache input identity and enforce provider budgets at the common client boundary.
4. Bound worker capacity and caches; batch archive queries and equivalent detector work; constrain chart inputs with continuity tests.
5. Split and cache static assets, extract repeated behavior, and migrate Jackson with snapshot/serialization compatibility tests.

Track p95/p99 endpoint latency, SQL count per request, connection-pool wait, oldest queued job age, provider credits, outbox retries, cache hit rates, heap allocation, and cold/warm asset bytes. Establish baseline measurements before attaching numerical performance targets to these changes.
