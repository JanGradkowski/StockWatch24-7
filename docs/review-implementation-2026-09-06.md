# Code review implementation — 6 September 2026

The changes below implement the 21 findings in [the original audit](code-analysis-2026-09-06.md). They apply to the existing working tree, including the user's earlier changes. No commit or deployment was made. The original review remains a record of the pre-fix state; its line references and descriptions are historical.

## Changes by finding

| Audit item | Implemented behavior |
| --- | --- |
| 1. Password-code attempts | Transactional password operations return explicit rejected results so failed attempts commit. Code validation precedes password hashing/comparison; malformed codes consume attempts. Codes require eight ASCII digits and reject the exact expiry boundary. Reset and sensitive settings routes have account/client throttles. |
| 2. Stale MFA challenges | Password authentication carries an immutable account security version. Pending MFA binds that version, user, and start time. Completion checks version, expiry, account eligibility, and factor under the account lock, returns the accepted identity/version, rotates the session ID, and clears pending state. |
| 3. Lost initial alert emails | Initial candlestick, Elliott, and harmonic event persistence and encrypted email enqueueing share a transaction. A separate durable worker claims delivery, retries SMTP failures with backoff, and records delivery receipts only after successful sending. Developing Elliott initial/follow-up notifications have distinct deduplication keys and receipts. |
| 4. Historical cache consistency | Candle changes advance a database generation in the same transaction. Historical computations capture settings once and compare the generation before and after calculation; changed inputs trigger bounded retries and are never stored under the newer generation. Database leases coordinate instances; owner checks guard writes. The `REV2:` namespace avoids mixed-version deployment collisions with old cache writers. |
| 5. Provider budget coverage | Twelve Data reserves credits at the actual HTTP request boundary, including quote, candle, search/history and pagination requests, rather than relying on a particular outer synchronization path. Disabled/unconfigured requests do not reserve credits. |
| 6. Stale job owners | Alert and insider completion/retry/renewal statements match the claimed attempt generation and an unexpired lease. Heartbeats renew active claims. Technical publication transactions check ownership before changing events/plans; insider processing also checks ownership before persistence/delivery. |
| 7. Session validation | Authenticated sessions fail closed for missing accounts, missing accepted versions, changed versions, pending deletion, and required-but-missing verification. APIs return 401; page requests return to login. The filter runs once in the security chain. |
| 8. Cancellation-token leakage | Responses use `Referrer-Policy: no-referrer`; the Nginx example no longer logs Referer. A cancellation GET exchanges its URL token for short-lived session state and redirects to a clean URL. Confirmation consumes that session state. |
| 9. Scheduler contention | Durable alert, insider, and email dispatch uses a four-thread worker pool with fixed per-queue admission limits. Timer callbacks submit bounded work instead of running those jobs inline. The scheduler has six threads; alert/insider polling defaults to one second. |
| 10. Archive loading | Signal outcome sorting, null placement, ownership filtering and pagination run in SQL before entity hydration. Elliott stage plans and latest prices are fetched in batches for the selected page. Virtual trades likewise use database sorting and 50-row pages with navigation. |
| 11. Chart workload | Queries omit candles after the requested end, while retaining earlier history required for exact EMA/OBV continuity. A bounded revision-keyed batch cache avoids repeated calculations, identical configurations share a computation, and full candlestick history uses the durable historical cache. |
| 12. Repeated detection | A per-job computation scope shares effective preferences and detector results across equivalent alert rules. It computes only requested detector families and clears the scope when the job ends. |
| 13. Cache/map bounds | Refresh status and failed synchronization maps use bounded TTL caches. Insider asset locking uses 256 fixed stripes. Outlook/market cache identities include the relevant candle, daily, and benchmark generations so another instance's corrections invalidate local results. |
| 14. Cache-hit database cost | Generation lookup replaces repeated aggregate candle metadata scans. Cache access timestamps update at most daily. CPU work runs outside the historical-cache database transaction, with short claim/write transactions and bounded local calculation concurrency. |
| 15. Repeated account reads | A validated request carries its account through `CurrentAccount`; controllers and view advice reuse it. API requests skip page model advice. The email lookup index matches Spring Data's actual `upper(email)` expression. |
| 16. Account locks and SMTP | Account notifications enqueue encrypted messages within their business transaction; the worker performs SMTP afterward. Account cleanup locks a bounded batch of 25 with `SKIP LOCKED`; cancellation locks the same account row. |
| 17. Frontend transfer cost | Stock workspace JavaScript is external, tab behavior is shared, and CSS/JS URLs contain content hashes. The attempted CSS split was reverted after visual regressions: every page loads the complete original stylesheet. Successful fingerprinted assets receive one-year immutable caching; plain URLs and missing assets do not. Compression covers CSS, JavaScript and JSON. HTML retains its security cache policy. |
| 18. Repeated code and bloat | Shared interval labels/API values, pattern-family mapping, request account access, job computation scope and tab behavior replace repeated implementations. Four research/calibration services move to test sources and are excluded from the executable package. Large domain services still exist; this change extracts concrete shared behavior without claiming a complete architectural rewrite. |
| 19. JSON stack and wiring | Databind/core usage migrates to Jackson 3, and Jackson 2 databind/JSR-310 dependencies are removed. Jackson annotations remain the compatible annotation artifact. Production dependencies previously declared `required=false` are required; compatibility constructors and fallbacks remain for isolated unit tests. |
| 20. TOTP | Strict validation accepts the application's 32-character Base32 secrets and six ASCII code digits, with bounded input normalization. Verification decodes once, initializes one MAC and compares all allowed time steps. Decoded secret buffers are wiped. Security-sensitive callers use injected `Clock`; step replay protection remains atomic under the account lock. |
| 21. Dependency gate | Spring Boot is 4.1.1, embedded Tomcat 11.0.25, and Log4j 2.25.5. The resolved package uses Spring Framework 7.0.9 and Spring Security 7.1.1. The configured Dependency-Check release gate passes without new suppressions. |

## Verification

- Fresh compilation and full Maven suite against an isolated PostgreSQL 16 database: **608 tests, 0 failures, 0 errors, 24 skipped**. The skipped cases are optional research/backtest or local market-data diagnostics; they are not counted as passes. The two local-data diagnostic classes now require `-Ddiagnostic.local-data.enabled=true` rather than assuming a developer's populated database.
- Added regression coverage for committed password-code failures, MFA account/version/expiry/replay checks, fail-closed sessions, cache revisions changing during calculation, stale job acknowledgements, encrypted outbox rollback/retry/body erasure, provider budget boundaries, strict TOTP inputs, SQL archive ordering/ownership across a page boundary, and EMA/OBV range continuity.
- `mvnw.cmd --batch-mode --no-transfer-progress -Psecurity-scan -DskipTests verify`: **BUILD SUCCESS** after the full test run. The configured vulnerability gate passes. OSS Index was unavailable without credentials; the packaged chart WebJar also lacks an npm lockfile/module tree for Node analysis. This is not a claim of complete advisory coverage. CodeQL was not run locally.
- Packaged application started on loopback with isolated database credentials and outbound market/email workers disabled. HTTP checks verified successful fingerprinted asset responses, immutable caching, gzip for eligible CSS/JavaScript, no-store HTML, no-referrer responses, a non-cacheable missing asset, and non-immutable plain asset URLs.
- JavaScript syntax and template regression checks passed. `git diff --check` passed.
- Executable JAR inspection confirmed **97 library JARs**, no Jackson 2 core/databind or JSR-310 library, and none of the four moved research services. The resulting JAR is 71,772,150 bytes; no production memory/latency improvement is inferred from package size.

Local evidence is retained in `target/review-full-tests-final.log`, `target/review-final-verify.log`, `target/dependency-check-report.html`, `target/review-http-smoke.json`, and `target/surefire-reports/`. The isolated test runner is `target/run-review-tests.ps1`; it overrides optional configuration import, points to loopback PostgreSQL, and disables outbound data/email integrations. These are local build artifacts, not committed project files.

## Visual regression correction

The user reported changed fonts, broken spacing/form controls and horizontal page scrolling after the initial implementation. The CSS split incorrectly placed global font, reset, box-sizing and overflow rules in `outlook.css`, and shared form controls in `auth.css`; pages that omitted those files lost the rules. The original combined-source tests did not detect missing per-page dependencies.

Restored `style.css` byte-for-byte from the pre-implementation snapshot, including all original responsive and theme rules. All 24 complete page templates now load that single stylesheet; the 15 partial stylesheets and the concatenation manifest were removed from source. The frontend test helper now reads the actual stylesheet loaded by pages. No replacement font or new layout design was introduced. Hash-based asset caching, compression, the external workspace script and backend fixes remain.

Verification: all 24 complete templates reference the restored stylesheet exactly once, and its bytes match the baseline (381,702 bytes). The 29 existing frontend security/template tests pass. Evidence: `target/visual-restoration-verification.json` and `target/visual-restoration-tests.log`. Browser automation again returned no available browser, so rendered screenshot parity is not claimed.

## Subsequent usability improvements

At the user's request, the dashboard, technical signal archives and general settings now provide:

- An unread-archive shortcut and clearer explanations separating setup scores from outlook percentages and trade outcomes.
- All / Unread / Active / Completed filters and case-insensitive ticker search for the account and company technical archives. Filtering and counting happen in PostgreSQL before pagination, including outcome sorting. Active includes potential setups and Elliott projections; completed includes resolved/rejected signals. Search text is bound as a literal SQL parameter, so `%` does not become a wildcard.
- Filter state in pagination and deletion redirects, plus an explicit return link from signal details to the same filtered archive. Return links accept only local archive paths. Filtered empty states offer a clear recovery action.
- Expandable candle/price details outside the main signal link, more prominent ticker/outcome text, consistent setup-score labels and visible keyboard focus. Existing row links and selection/deletion controls remain distinct.
- A password workflow driven by the actual database challenge: request a code first, then enter the code and new password while the challenge is valid. Expired or exhausted challenges return to step one. The page shows the recipient, expiry countdown, resend option, submission feedback and server validation errors. The workflow works without JavaScript; the countdown and busy labels enhance it.

The full restored baseline stylesheet remains an unchanged byte prefix; these enhancements append component-scoped rules. No global font, theme or horizontal overflow rule changed. All pages continue to use the shared stylesheet.

Validation for this increment: **82 affected unit/integration/template tests passed**, including archive filtering/counting across page boundaries, ownership restrictions, navigation preservation, password challenge expiry/attempt limits and rendering both password steps. JavaScript syntax, `git diff --check`, Maven packaging and packaged-resource checks passed. Logs: `target/usability-tests.log` and `target/usability-package.log`. No schema migration or dependency change was needed. Browser discovery again returned no available browser, so visual screenshots and live interaction testing remain unverified.

## Demo Trading portfolio summary

The Demo Trading archive now starts with portfolio value and four return cards: overall, latest trading day, week and month. Selecting a card shows the three highest and three lowest stock returns for that period. The selection survives sorting, pagination and deletion. This uses the original shared theme and component-scoped responsive CSS.

Accounting follows the existing demo decision model: sized BUY positions count toward the portfolio, closed BUY proceeds remain as cash, and SELL decisions do not create short positions or close unrelated BUYs. Unsized and deleted trades do not contribute. Each BUY is separately funded; automatic reinvestment and a cash spending ledger are not implied. Currencies are reported separately because no FX conversion ledger exists.

Portfolio value is open BUY market value plus closed BUY proceeds. Overall return divides cumulative profit/loss by invested capital. Period returns divide profit/loss since the baseline by opening portfolio value plus new investments; new investments are excluded from gains. These are capital-adjusted simple returns, not time-weighted returns or IRR. Per-stock rankings combine repeated buys using their capital rather than averaging trade percentages. Closed positions with no exposure during a period are omitted from that period's rankings, while their cash remains in the portfolio denominator. Lists contain up to three entries and can overlap for small portfolios.

Valuations use cached completed daily candles and recorded exit prices. Day compares to the previous available close; week and month use the close on or before seven calendar days / one calendar month before the latest cached close. Price-date coverage is shown. New buys after the available daily close retain their captured entry value until a newer close arrives. Missing current prices make complete portfolio value unavailable; missing comparison prices make the affected period return unavailable and exclude the affected stock from its ranking. Fees, dividends, taxes, FX and intraday mark history are not modeled.

`DemoPortfolioService` reads lightweight accounting columns and batches indexed price lookups into three database queries, without fetching technical snapshots, calling market providers or depending on the archive page. A short repeatable-read transaction keeps those reads consistent. No schema migration or dependency change is required.

Validation: **54 affected tests passed**, including five new integration tests for cash-flow accounting, closed proceeds, currency separation, ownership/deletion exclusions, missing prices, new entries, weighted stock rankings, weekend daily comparisons, a 51-trade portfolio spanning archive pages, period navigation and empty-state rendering. Evidence: `target/portfolio-tests.log`; package evidence: `target/portfolio-package.log`. Browser discovery returned no available browser; screenshot and live visual validation remain unperformed.

## Initial source/asset measurements (before the visual correction)

| Measurement | Before | After |
| --- | ---: | ---: |
| Stock template | 5,825 lines / 279,818 bytes | 1,096 lines / 71,591 bytes |
| CSS linked by login page, uncompressed | 381,702 bytes | 69,723 bytes |
| CSS linked by stock page, uncompressed | 381,702 bytes | 301,036 bytes |
| CSS linked by dashboard (`home`), uncompressed | 381,702 bytes | 185,110 bytes |

Most removed stock-template code now lives in the cacheable workspace script; it was not deleted. The smaller page-specific CSS sizes above describe the faulty split and are superseded: pages now load the original 381,702-byte stylesheet, with compression and content-hash caching retained. These measurements describe file/response sizes, not production page timing.

## Migration and operational behavior

Deploy application code with Flyway migrations **V58–V60**. V58 introduces candle generations, historical calculation leases and the encrypted email outbox. V59 adds receipt types and corrects the case-insensitive email index. V60 uses statement-level transition-table triggers so bulk candle changes advance each affected series once per SQL statement, including corrections, deletions, moved series and truncation. Existing cache rows miss safely and are repopulated. The new namespace similarly causes one-time recomputation.

The outbox uses the existing `MFA_ENCRYPTION_KEY` configuration. Keep that key stable for both existing MFA secrets and pending encrypted emails. Password codes expire after five minutes; account notification and technical alert delivery lifetimes are separately bounded by their enqueue calls. Delivered or expired bodies are erased, and terminal delivery metadata is retained for 30 days. Monitor pending age, attempts and expired rows in `email_outbox`, alongside job queue depth, when deploying. Email sending requires the existing SMTP configuration and worker enablement.

SMTP delivery remains **at least once**: a process failure after SMTP accepts a message but before the database acknowledgement can cause a duplicate. Stable Message-ID and deduplication keys reduce duplication but cannot make SMTP exactly once. Initial technical alerts and account messages use the new outbox; existing congressional/other notification workflows retain their own delivery mechanisms.

Existing sessions without an accepted security version must sign in again. Cancellation confirmation requires the session established by opening its link. If another reverse proxy is used, apply the equivalent Referer-log change there; changing the supplied Nginx example does not alter a deployed proxy.

Browser screenshot validation was unavailable because no browser surface was available in this environment. Template, syntax and live HTTP checks do not establish visual parity across every page or viewport. Production `EXPLAIN ANALYZE`, realistic load tests, multi-process failure injection, and live SMTP/provider delivery were not performed. Recursive/cumulative indicators intentionally retain their full preceding history for numerical correctness; workloads with very long histories can still warrant measured checkpoint/precomputation work later.

The baseline source snapshot and prior compiler output are preserved under `target/review-implementation-baseline.zip` and `target/review-build-backup/`. The temporary app process and isolated `stockwatch-review-db` container were stopped after verification; the test container remains available to restart. User-provided source changes and the user's database were preserved.


## Shared workspace navigation ? 7 September 2026

Added one shared sidebar fragment and one navigation script, included through the existing navbar. Destinations are Home, Technical signals, Ticker alerts (the insider/congress dashboard view), Demo trading, Settings, and Help & about. Signed-in users retain the same navigation on the About page; public pages retain their public header. Active sections are resolved centrally without another account query, with dashboard hash changes handled in the shared client script.

The desktop sidebar is 224px wide and collapses to a 76px icon rail, remembering the local preference. Inline SVG icons require no icon library or external requests. At 1100px and below, navigation becomes a native modal drawer with focus containment, Escape, backdrop/close controls and focus restoration. Links retain the stock workspace's unsaved-change guard. Collapse emits a resize event for existing chart handlers. Settings subsections wrap as tabs within the content area. The redundant Settings and Dashboard header shortcuts were removed; stock search, account controls, and theme switching remain.

All 381,702 original stylesheet bytes remain unchanged at the start of the shared stylesheet. The sidebar and responsive adjustments are appended and scoped to the application shell; typography and the existing horizontal page overflow restriction are preserved.

Validation: 50 Java/security/template/MVC tests passed, including authenticated navigation rendering across seven routes and anonymous-page checks. Seven Node interaction tests passed (`node --test src/test/js/app-navigation.test.cjs`), covering collapse persistence, unavailable storage, mobile opening/closing, viewport changes, active links, guard-compatible link handling, and dashboard hash/keyboard navigation. Maven packaging passed. Logs: `target/sidebar-tests.log` and `target/sidebar-package.log`. Browser surfaces were unavailable, so desktop/mobile visual inspection and native browser focus behavior remain unverified; simulated interaction tests do not replace that check.


## Clickable archive sorting ? 7 September 2026

Technical signal archives now sort from the Ticker, Interval, Received, Signal status, Setup score and Trade outcome headings. The activity/ticker-alert archive supports clickable Signal, Ticker, Company, Buyer / seller, Buy / sale and Traded headings. Ticker sorting is distinct from company-name sorting. Both pages use one Thymeleaf fragment with direction arrows and keyboard-accessible links that announce the current and next order. Clicking the active heading reverses direction; changing the sort resets pagination to page zero and retains technical filters and company scope. Existing server ordering still runs before pagination. The original sort forms remain available, including on mobile where headings are hidden. No new JavaScript or dependencies were added.

Validation: 49 affected Java/MVC/template/query tests passed (`target/archive-sort-tests.log`). The new fragment tests cover toggling, default directions, filter retention, company routes and activity links. Maven packaging passed (`target/archive-sort-package.log`). Browser visual verification was unavailable in this session.


## Dashboard navigation cleanup ? 7 September 2026

Removed the dashboard's four-button navigation strip and explanatory copy, plus the duplicate All signals button within Latest signals. Unread archive, All activity signals and the dashboard action controls remain. Dashboard visibility now follows the URL hash directly, including initial load, sidebar navigation and restored browser history; it no longer depends on removed tab buttons. No stylesheet changes were needed.

Validation: seven Node navigation tests passed, including switching dashboard sections with no tab buttons. The 21 MVC/application tests passed; after updating legacy assertions for the removed links, all 28 frontend tests passed as well (`target/dashboard-cleanup-tests.log`, `target/dashboard-cleanup-frontend-tests.log`). Packaging passed (`target/dashboard-cleanup-package.log`).


## Outlook-change chart legend and visibility ? 7 September 2026

The saved outlook-change report now identifies all eight price overlays with coloured checkbox legends. Labels use the indicator periods saved in the snapshot, with generic labels for older snapshots lacking settings. Bollinger lower/middle/upper lines have distinct dash styles mirrored in their legend swatches. Users can toggle individual overlays and signal-marker groups, or show/hide them together. Separate controls hide/show the lower indicator panels, whose dashed reference levels are now explained. Candlesticks remain visible; changing visibility does not modify the saved calculation, refetch data, or reset the chart zoom.

A single reusable `chart-legend.js` renderer supplies the controls for both charts and panels. Missing series data disables its checkbox and is labelled No data. Missing indicator values are excluded instead of being coerced to zero; genuine zero values remain valid. CSS is scoped to the new controls and the original stylesheet prefix remains intact.

Validation: 29 frontend/security/template tests and four Node behaviour tests passed. The tests render the complete Thymeleaf report and exercise the actual inline chart script with simulated chart/DOM APIs, including periods, colours/dashes, null values, visibility, marker groups, panels and old snapshots. Packaging passed. Logs: `target/outlook-legend-tests.log`, `target/outlook-legend-package.log`. Node command: `node --test src/test/js/outlook-change-legend.test.cjs`. No browser surface was connected, so visual verification remains outstanding.

## Interface wording cleanup - 7 September 2026

Reviewed all 34 HTML templates and simplified the wording identified in the copy audit, including shared settings and dynamically generated status messages. Headings, instructions, empty states and error messages now use more direct language. Demo trading and Ticker alerts have consistent names. Removed internal caching and processing terminology where it did not help the user, corrected outdated historical-caching claims, renamed the misleading Fibonacci "probability zone" to "Fibonacci target range", and fixed the mixed-evidence sentence in outlook-change reports. Historical-result, portfolio-accounting and filing-coverage explanations retain their relevant limits.

Detailed detector rules and formulas on About are expandable. Signup chart-colour preferences are also expandable, with the existing default values and form inputs preserved. Existing fonts, theme rules, responsive layout and horizontal overflow restrictions remain unchanged; the only CSS additions style these disclosure controls. No calculation, route, database schema or dependency changed.

Validation: 79 affected Java unit, template and MVC tests passed, plus 11 JavaScript navigation and chart-legend interaction tests. All 34 templates preserve their form controls, all inline and external scripts pass syntax checks, the existing stylesheet remains an unchanged prefix, and Maven packaging passed. Existing copy assertions were updated to match the new wording. Logs: `target/copy-tests.log` and `target/copy-package.log`. Live browser visual verification was not performed. The isolated test database container was stopped after verification.
