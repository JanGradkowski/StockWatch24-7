**Harmonic and Elliott detection audit — 22 September 2026**

This is the pre-fix assessment of revision `247c443`. For subsequent changes,
verification and remaining limits, see the [remediation report](pattern-detection-remediation.md).

**Verdict: neither system can currently be described as a complete textbook detector.** There are reproducible false negatives, incomplete structural validation, and historical confluence that changes with future input. Some exclusions are intentional trading policies; others are implementation defects. Recognizing a structure, qualifying an alert, and predicting a profitable trade are separate questions. This audit addresses the first two, not profitability.

Audited revision: `247c443`, with a clean working tree at the start. Production Java was not changed. The review traced the two detectors, confluence, hierarchy/drill-down, preference definitions, scheduler entry points, historical views, and relevant tests. It did not inspect a running account's settings or database, send notifications, or run the entire application test suite.

For “by the book,” the comparison uses Elliott Wave International's Waveopedia and Scott Carney's published harmonic definitions. Where those sources describe an ideal or common ratio rather than an absolute constraint, this report does not turn the preference into a universal rule. Different harmonic schools have different extensions; support should name the chosen specification explicitly.

**Validation performed**

- 128 distinct targeted tests completed successfully across the detector, preferences, hierarchy, drill-down, historical-service, scheduler, lifecycle, and trade/stop-policy selections, including the two opt-in synthetic benchmarks. The initial selection reported 84 tests with one skipped; that skipped Elliott benchmark was subsequently enabled and passed.
- The developing Elliott benchmark recovered the exact intended count in 1,000/1,000 generated cases. This is evidence for that generator and test scope.
- The harmonic duration/noise benchmark recovered 990/1,000 cases: eight Bat misses, one Crab miss, and one Cypher miss. The test passes because its threshold is 95%.
- A separate [audit probe](audit/PatternAuditProbe.java) reproduced ratio exclusions, incorrect structural acceptance, missing boundary validation, changed historical confluence, and a reversed confluence pattern label. Its output is in `target/pattern-audit-probe.log`.
- The existing published-market Elliott tests pass while explicitly preserving a missed SPY correction and a recognized SPX count that falls below the alert threshold.

P1 below means a high-priority correctness issue; P2 means a material defect or coverage limitation; P3 means documentation/interpretation. “Reproduced” distinguishes executed examples from findings established by code inspection.

**1. P1 — “Strict” Elliott subdivisions can validate completely different parent prices. Reproduced.**

`ElliottWaveDetectionService.java:3205–3229`, called from `2814–2836`, `2970–2983`, and `2416–2427`.

`closestBoundaryPivot` searches the entire supplied child series for the closest price but never rejects a large mismatch or requires that the requested boundary actually exists. The returned pivot uses the child's price, replacing the parent boundary for validation.

The public call `findStrictSubdivisions(children, "I", 1, 1000)` accepts a child series whose complete price range is 100–160, returning `validated=true` with actual endpoints 100 and 160. This can falsely validate a parent when child history is incomplete or prices differ between parent and child feeds. The hierarchy and developing detector rely on this method.

Require coverage of both parent endpoints, an explicit price tolerance, and correct intrabar chronological alignment. Missing data should produce “unverified,” not a validated substitute.

**2. P1 — Historical confluence uses hindsight-dependent detector output. Reproduced.**

`CrossPatternConfluenceService.java:208–218,248–257`; `ElliottWaveDetectionService.java:273–298,1342–1369`; `HarmonicPatternDetectionService.java:184–218`.

The confluence assessment filters observations to timestamps before the target, but the observations are generated from the entire supplied history. Elliott structures are then globally ranked and overlapping structures removed. Later structures can erase earlier evidence. Harmonic volatility-derived swing scales also depend on the median of the entire supplied series, and its compression can replace an earlier terminal pivot.

Using the repository's SPY 2009 benchmark, with candlestick and harmonic observations disabled to isolate Elliott, eight historical target scores differ between full-history and prefix-only calculations. The first is 2009-11-02: base 50 becomes 40 with information available then, but remains 50 using the full later history. The other three benchmark series had no score differences in this probe. This proves historical inconsistency, not that every live score is affected.

The “look-back-only” claim is therefore too strong. Build observations by chronological replay or persist their actual first availability. Keep retrospective chart simplification separate from the event stream used for historical scoring. Add prefix-invariance tests for both detectors and confluence.

**3. P1 — Bat validation hard-codes preferred ratios as exhaustive acceptance rules. Reproduced.**

`HarmonicPatternDetectionService.java:276–293`; matching preference defaults in `HarmonicPatternPreferencesService`.

The B range stops at 0.50, with tolerance extending it only to 0.515. CD/AB must be near 1.0 or 1.27. Carney describes B below 0.618, with 0.382/0.50 preferred, and equivalent AB=CD as a minimum with extensions permitted. [Primary definition](https://harmonictrader.com/harmonic-patterns/bat-pattern/).

Two bullish X-A-B-C-D examples rejected by `classify`:

| Points | Relevant ratios | Result |
|---|---|---|
| 100, 200, 145, 181.25, 111.4 | B/XA=0.55; AD/XA=0.886; CD/AB=1.27; CD/BC≈1.927 | Missed |
| 100, 200, 150, 192.3, 111.4 | B/XA=0.50; AD/XA=0.886; CD/AB=1.618; CD/BC≈1.913 | Missed |

Both meet the other implemented Bat ratio ranges. Separate preferred scoring zones from admissible structure, and document which alternate CD completions are supported.

**4. P1 — Butterfly excludes documented BC extensions. Reproduced.**

`HarmonicPatternDetectionService.java:296–311`.

The BC-extension ceiling is 2.24, approximately 2.307 with default tolerance. The author's definition also describes 2.618 BC projections and additional alternate AB=CD relationships. [Primary definition](https://harmonictrader.com/harmonic-patterns/butterfly-pattern/).

X-A-B-C-D = `100, 200, 121.4, 151.31347, 73` has B/XA=0.786, AD/XA=1.27, C/AB≈0.38185, CD/BC=2.618, and CD/AB≈0.99635. It is rejected despite the approximately equivalent AB=CD and the defining B/D ratios. The C retracement is within the existing tolerance. Update the supported extension set in both detector and preference defaults.

**5. P1 — Shark completion is two isolated targets instead of the documented zone. Reproduced.**

`HarmonicPatternDetectionService.java:333–351,556–581`.

The completion matcher permits only approximately 0.85942–0.91258 or 1.0961–1.1639 under default tolerance. It excludes the intervening region, including an exact retest of the origin. Carney describes the 0.886–1.13 completion area and origin retests. [Primary definition](https://harmonictrader.com/harmonic-patterns/shark-pattern/).

0-X-A-B-C = `100, 200, 144, 214, 100` is rejected. Its A/0X=0.56, AB/XA=1.25, BC/AB≈1.62857, and completion=1.0 meet the other implemented constraints. Use a completion-zone rule if supporting that published definition; reserve endpoint proximity for scoring.

**6. P1 — Elliott validation differs substantially between the legacy, developing, and hierarchy paths. Code-confirmed; public strict-validation examples reproduced.**

`ElliottWaveDetectionService.java:86–118,367–416,1000–1193,1743–1801,2100–2195,2814–2866,2970–3033`.

The legacy detector and historical/confluence path use six or nine swing endpoints without requiring the internal 5-3-5-3-5 impulse grammar or the appropriate correction subdivisions. The scheduler still calls the legacy alert path (`ScheduledAlertService.java:1190–1196`) as well as the developing path. Thus stronger developing validation does not certify every Elliott signal the application produces.

The developing path does require a recognized subdivision for each completed parent leg. That is a meaningful improvement. However, the subdivision recognizers ultimately accept endpoint geometry and bounded extra pivots; they do not recursively establish every claimed lower-level grammar. Corrective recognizers label a structure flat or zigzag mainly from B retracement and emit textbook-mode evidence without checking those child counts at that level. For example, the public strict recognizer accepts `200→160→180→170` as a validated zigzag, without a specific C-failure qualification or internal motive proof.

Finite resolution necessarily limits validation depth. The problem is conflating “compatible geometry at the observable degree” with fully validated textbook structure. Record observed depth and unresolved legs, and share one structural validator between emission and historical paths. [Impulse rules](https://www.elliottwave.com/waveopedia/impulse/), [zigzag grammar](https://www.elliottwave.com/waveopedia/zigzags/), [flat grammar](https://www.elliottwave.com/waveopedia/flats/).

**7. P2 — Diagonal detection does not validate diagonal geometry or distinguish leading and ending grammar. Reproduced acceptance; missing checks confirmed by inspection.**

`ElliottWaveDetectionService.java:2937–2939,3280–3301`; `ElliottWaveHierarchyService.java:218–230`.

The diagonal predicate essentially accepts directional five-wave motion, wave-IV overlap, timing, and the wave-III length rule. It has no contracting/expanding shape test or leading-versus-ending subtype. The public strict method labels `100→120→110→150→115→155` a validated motive diagonal; the actionary lengths are 20, 40, 40, without an explicit diagonal-shape qualification.

The hierarchy's child-nature logic also treats diagonal children using the default motive/corrective numbering, rather than explicitly assigning the ending diagonal's corrective children. Merely allowing overlap does not establish a textbook diagonal. Add subtype-specific geometry, position, and child grammar. [Primary diagonal definition](https://www.elliottwave.com/waveopedia/elliott-wave-pattern-diagonals/).

**8. P2 — Correction retracement loses direction through `abs`. Reproduced.**

`ElliottWaveDetectionService.java:1094–1155,1172–1193`.

`correctionMetrics` uses `abs(V−C)/abs(V−0)`, and the parent validator never separately requires a bullish cycle's correction C below V, or the mirrored bearish condition. It accepts the bullish sequence `100,120,110,150,130,160,140,178,170`: C=170 is above V=160, yet the computed retracement is positive.

The private validator returns true, and the public `detect` returns `ELLIOTT_BULLISH_RUNNING_FLAT_CORRECTION` on the confirmation prefix. In this fixture its score is 39, so the default 75-point alert threshold blocks it; the historical output also does not retain it. This is a proven structural acceptance error, not evidence of a delivered alert. Use signed retracement and explicit correction direction before scoring.

**9. P2 — Textbook families and variants are missing from the supported search space. Code-confirmed scope limits.**

`HarmonicPatternType.java`; `HarmonicPatternDetectionService.java:54–63,98–105`; `ElliottWaveDetectionService.java:367–400,3232–3277,4164–4173`.

| Area | Implemented | Missing or restricted |
|---|---|---|
| Harmonics | Gartley, Bat, Butterfly, Crab, Shark, Cypher, both directions | Standalone AB=CD/alternate AB=CD, Alternate Bat, Deep Crab, 5-0 |
| Top-level Elliott | Standard/truncated impulse; attached A-B-C correction with standard/expanded/running categories | Independent diagonal root structures; standalone corrections; top-level W-X-Y/W-X-Y-X-Z or A-B-C-D-E corrections |
| Elliott subdivisions | Motive, diagonal, ABC, several combinations, contracting triangle | Barrier and expanding triangle recognizers; complete subtype/context grammar |

Some combinations and triangles are supported inside waves II/IV/B, so it would be incorrect to say they are entirely absent. The missing top-level representations still prevent an “all Elliott waves” claim. The origin and <100% correction gates additionally restrict recognition to a continuation-cycle model, which is narrower than general Elliott counting.

The official harmonic catalogue includes the omitted families; triangle theory includes contracting, barrier, and expanding varieties. [Harmonic catalogue](https://harmonictrader.com/harmonic-patterns/), [Deep Crab](https://harmonictrader.com/harmonic-patterns/deep-crab-pattern/), [triangles](https://www.elliottwave.com/waveopedia/triangles/).

**10. P2 — Timing and score policies discard valid structures and can prune later valid stages. Reproduced published miss; other effects code-confirmed.**

`ElliottWaveDetectionService.java:20–28,115–118,404–416,723–725,1074–1078,1121–1123,1952–1961,2206–2210`.

The default imposes 34 input candles, at least two candles between wave endpoints, quality ≥68 for historical structures, and confidence ≥75 for alerts. Historical/developing endpoints must reverse with a close beyond the prior candle's extreme within three candles. These are application policies, not a complete definition of an Elliott wave.

`DocumentedElliottWaveBenchmarkTest.java:60–91` explicitly records the missed SPY 2009–2010 ABC because A completes in one weekly candle. The SPX 2011 example is recognized but its 72 score blocks alerts. The legacy `detect` path does not enforce the same maximum endpoint-confirmation lag as the historical/developing paths, adding inconsistency.

There is also an early-pruning issue: `extendDevelopingImpulse` returns as soon as an intermediate prefix fails `developingImpulse`, and that includes the three-candle confirmation gate. A slow wave-II confirmation can therefore prevent searching a later otherwise-valid wave-III/IV/V structure. Separate structural viability from stage notification timing; retain valid counts even when a trading trigger was missed.

**11. P2 — Pivot selection and ranking are heuristic and intentionally discard alternatives. Measured misses plus code-confirmed limits.**

`HarmonicPatternDetectionService.java:118–218,221–244,655–661,693–694`; `ElliottWaveDetectionService.java:1342–1369,1793,1928–1934,2023–2042,2829–2830,2893,3365–3486`.

Harmonics use a finite collection of windows/scales, a default 0.5% minimum swing, no skipped pivots at a selected scale, and skip bars that are simultaneously local highs and lows. They retain one geometry per pattern/direction/terminal timestamp and at most 250 formations. These policies can miss valid small, fast, noisy, overlapping, or differently scaled formations. Right-side confirmation also means a formation cannot be emitted at its turning candle without delay.

Elliott uses finite ATR/percentage sensitivity passes, with extra passes for developing counts. Some searches take only 256 chronological pivot combinations, retain 24 alternatives, or select one candidate per development key. Historical structures overlapping more than 40% of the shorter span are suppressed; nested degrees naturally overlap. These are not exhaustive parsers.

The executed 1,000-case harmonic noise benchmark demonstrates actual losses even for the application's own admissible ratios: 990 recovered, 10 missed. Keep display thinning separate from detection, expose alternative counts, and measure pivot recall independently from classification recall. Enabling skipped harmonic pivots also needs an intervening-extrema check: the current skip scan validates the five selected points without validating the omitted path.

**12. P2 — Live alert coverage is narrower than chart/history recognition. Code-confirmed.**

`ScheduledAlertService.java:43–46,338–344,409–414,502–524,1223–1229`.

Ordinary Elliott scanning uses the latest 100 enriched bars; developing weekly scanning uses 200. Longer structures visible in full historical charts can be outside the live scan. Harmonic/developing notifications require the reported confirmation timestamp to equal the latest completed candle. A newly recognized candidate carrying an older confirmation time will be skipped on that run.

A new developing cycle can only be created at wave II; if the user activates the rule later or wave II failed qualification, a wave-III/IV candidate cannot initialize that cycle. The separate legacy path may still provide wave-V/correction signals, so this is specifically a developing-cycle coverage gap.

There is scheduled recovery using historical cutoffs, so this audit does not claim all delayed jobs lose signals. The filters above still explain why a visible formation need not generate an alert. Define first-detection versus structural-confirmation time explicitly and allow late cycle attachment if the product intends to alert on all eligible stages.

**13. P2 — Confluence omits Elliott stages, mislabels wave V, and ignores the configured lag. Label inversion reproduced.**

`CrossPatternConfluenceService.java:208–245,261–274`.

Only wave-V and correction-end observations are included. Developing wave-II/III/IV signals and impulse-breakout signals do not contribute. That is narrower than the application's Elliott signal family.

Wave-V labels are selected from the trade direction instead of the underlying impulse direction. A bullish impulse ending in a SELL is recorded as `ELLIOTT_BEARISH_WAVE_V_END`. The SPX 2004 benchmark reproduces this exactly. The BUY/SELL direction used in score arithmetic remains correct, but the pattern identity and explanation are wrong. Truncated and flat variants are also collapsed into generic names.

Confluence independently hard-codes three confirmation candles instead of using the configured Elliott lag or an authoritative observation timestamp. Changing that preference can produce a structure accepted by the detector but absent from confluence. Consume the same normalized events used by the detector, including stage and subtype.

**14. P2 — Input validation does not establish a continuous, consistent OHLC history. Code-confirmed robustness gap.**

`HarmonicPatternDetectionService.java:664–671`; `ElliottWaveDetectionService.java:3957–3963`; `CrossPatternConfluenceService.java:332–336`.

The harmonic normalizer checks timestamps and positive high/low but then volatility calculation unboxes close values without a corresponding close check. Neither detector's local normalization establishes timestamp uniqueness, exchange-session continuity, complete OHLC ordering, or split consistency. Elliott drops invalid bars and evaluates timing on the shortened list. This can create artificial adjacency or count across missing observations.

Upstream services provide some completion and data validation, so this is not a claim that the current database contains these faults. Nevertheless, the detector APIs do not defend the pattern semantics against such inputs. Use a shared interval-aware integrity layer and report insufficient coverage instead of silently joining across holes.

**15. P2 — Existing accuracy tests do not establish total recall or false-positive rates. Code-confirmed validation gap.**

`HarmonicPatternIndependentValidationTest.java:128–182`; `HarmonicPatternReferenceRecallResearchTest.java:88–110`; `HarmonicPatternLongDurationResearchTest.java:83–86`; `DevelopingElliottSyntheticRecallBenchmarkTest.java:35–94`.

The harmonic “independent” ratio oracle repeats the narrow production B/CD/BC/Shark limits. It therefore cannot expose findings 3–5. The large reference-recall study starts from production-confirmed pivots, so formations that the pivot selector never finds are outside its denominator. Its existing 100% report is conditional on that search space; it was inspected, not rerun here.

The Elliott synthetic benchmark is a positive-case test generated from selected shape families. Passing 1,000 cases does not measure all alternative grammars, parent/child coverage failures, online first-availability, or precision on invalid structures. The four documented market examples are useful regressions but too small to establish market-wide recall.

Add an independently maintained labelled corpus, invalid grammar examples, boundary/near-miss cases, real prefix-by-prefix replay, and separate metrics for structure recall, alert recall, precision, and delay. Keep ratio-oracle agreement separate from full OHLC-pipeline performance.

**16. P3 — “Confidence” and documentation overstate what has been established.**

`HarmonicPatternDetectionService.java:401–407,693–694`; `ElliottWaveDetectionService.java:799–845,1377–1472`; `docs/harmonic-formations.md:10–11`.

Scores are hand-weighted ratio/shape/context measures, not calibrated probabilities that a pattern is correct or will succeed. The harmonic documentation still says 4% Fibonacci and 8% leg tolerance; defaults are now 3% and 3%. Configurable soft harmonic rules and switches disabling core Elliott requirements also mean a user-customized detector cannot automatically be called textbook strict. Update the documentation and distinguish structural validity, setup quality, confluence, and trade eligibility in outputs.

**What is already sound**

The default Elliott endpoint rules correctly enforce wave II staying short of the origin, wave IV avoiding wave I territory for standard impulses, and wave III not being the shortest of I/III/V. Wave I is allowed to be longest; wave I is not incorrectly required to be shortest by default. Truncation and expanded/running-flat categories exist. Alternation is generally used for scoring rather than imposed universally.

Harmonics use explicit application-owned ratios, mirrored directions, alternating point types, inside/outside completion boundaries, and separate terminal/confirmation timestamps. Gartley/Crab/Cypher arithmetic reviewed here did not yield an additional reproduced default-classification defect. This is not certification of exhaustive coverage. Confluence caps scores, avoids self-family boosts, and neutralizes simultaneous conflicting directions. Those correct local behaviors do not resolve the historical-input issue.

**Recommended order of work**

1. Fix parent/child boundary validation and signed correction direction; add adversarial grammar tests shared by all Elliott paths.
2. Correct Bat, Butterfly, and Shark acceptance definitions and their preference defaults against a named reference specification.
3. Make event timestamps and historical confluence reproducible from candle prefixes; fix subtype/stage identity.
4. Separate structural recognition from scoring, confirmation windows, alert history, and display thinning.
5. Expand the explicitly supported families and search coverage, then validate them against independent labelled examples and realistic missing/noisy data.

**Reproduction**

The diagnostic source is intentionally separate from production and normal regression tests. It prints current behavior, including defects, rather than asserting those defects as desired outcomes. After running the targeted tests, compile/run it using the Maven test classpath:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=HarmonicPatternDetectionServiceTest,HarmonicPatternIndependentValidationTest,HarmonicPatternPreferencesServiceTest,ElliottWaveDetectionServiceTest,DocumentedElliottWaveBenchmarkTest,ElliottWaveHierarchyServiceTest,ElliottWaveSignalLifecyclePolicyTest,CrossPatternConfluenceServiceTest' test
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=DevelopingElliottSyntheticRecallBenchmarkTest,ElliottWaveDrilldownServiceTest,ElliottWavePreferencesServiceTest,HistoricalElliottWaveServiceTest,HistoricalHarmonicFormationServiceTest,ScheduledAlertServiceTest,HarmonicStopPlanPolicyTest,ElliottTradePlanPolicyTest' '-Delliott.synthetic.benchmark=true' test
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=HarmonicPatternLongDurationResearchTest' '-Dbacktest.harmonic.long-duration.enabled=true' test
$auditXml = [xml](Get-Content target/surefire-reports/TEST-org.example.stockwatch247.service.CrossPatternConfluenceServiceTest.xml)
$auditCp = ($auditXml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
javac -cp $auditCp -d target docs/audit/PatternAuditProbe.java
java -cp "target;$auditCp" PatternAuditProbe
```

Logs from this audit: `target/pattern-audit-tests.log`, `target/pattern-audit-extended-tests.log`, `target/pattern-audit-harmonic-long-duration.log`, and `target/pattern-audit-probe.log`. Generated long-duration results are in `target/expanded-backtest-data/harmonic-long-duration-recall-report.md` and its companion CSV. No full-market accuracy percentage is justified by these checks.
