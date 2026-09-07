Implementation of the 6 September code review (current working tree).

Baseline source snapshot: `target/review-implementation-baseline.zip`. Existing user changes are preserved.
See [implementation details, validation, and limits](review-implementation-2026-09-06.md).

- [x] 1, 2, 7, 8, 15, 20: account transaction results, MFA challenge binding, session validation/request user, token referrer, TOTP/Clock.
- [x] 3, 16: transactional encrypted email outbox, bounded account cleanup.
- [x] 4, 13, 14: monotonic candle revisions, consistent historical cache, bounded local caches/invalidation.
- [x] 5: provider budgeting at client boundary.
- [x] 6, 9: fenced job generations, bounded scheduling/workers.
- [x] 10, 11, 12: archive queries, chart range/continuity, shared per-job detection.
- [x] 17, 18: frontend modules/assets, shared interval/pattern helpers, research code separation. Reverted faulty CSS splitting; all pages load the exact original stylesheet while caching/compression remain enabled.
- [x] 19, 21: patched dependencies, Jackson migration, explicit production wiring.
- [x] Regression tests, isolated database integration, full Maven tests, security gate, implementation notes.

Final full suite: 608 tests, 0 failures, 0 errors, 24 skipped optional research/data diagnostics.
Packaged application HTTP checks and dependency security gate passed.
Visual browser checks and production load measurements remain unperformed; these are validation limits, not claimed results.

Visual regression correction: 29 frontend/template tests pass; all 24 full-page templates load the restored baseline stylesheet. See the implementation report for the cause and correction.
