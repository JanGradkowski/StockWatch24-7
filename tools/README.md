# Recovery diagnostics

Run from the repository root with Java 25. These helpers read the existing local
`.env`; never paste its credentials into commands or logs.

Build classes and a dependency classpath first:

```powershell
.\mvnw.cmd -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/diagnostic-classpath.txt'
$diagnosticCp = (Get-Content target/diagnostic-classpath.txt -Raw).Trim()
```

- `java -cp $diagnosticCp tools/SmtpConnectivityProbe.java` connects and
  authenticates with Gmail-compatible SSL/STARTTLS selected by port. It never
  sends a message; passing `465` or `587` explicitly probes that port.
- `java -cp $diagnosticCp tools/ProviderIdentityProbe.java` checks the explicit
  Home Depot XNYS identity against Twelve Data. This consumes a provider request.
- `java -cp $diagnosticCp tools/RecoveryMaintenance.java` audits the local schema,
  selected asset identities, failed-job classifications and delivery states.
  Only `--apply` changes data: it installs V74 if pending and performs the narrow,
  idempotent recovery operations described in the remediation report. It never
  starts workers or sends emails. Do not use it to bypass review of other failures.
- `java -cp $diagnosticCp tools/ClearTechnicalBacklog.java` audits technical job
  states, the cancellation cutoff and unfinished checkpoints (requires V75).
  Explicit `--apply` installs V75, cancels **all** pending/processing technical
  jobs, deletes unfinished checkpoints and advances the cancellation cutoff.
  Stop application workers first. This is operator-requested backlog cancellation,
  not successful analysis; saved signals and delivery records are retained.

The weekly analysis replay snapshots existing database candles read-only and
stores all output/checkpoints under `target/recovery-replay`. It makes no provider
requests and sends no emails. Compile and launch it separately:

```powershell
javac -cp "target/classes;$diagnosticCp" -d target/recovery-tools tools/AnalysisRecoveryReplay.java
java -Xmx768m -cp "target/recovery-tools;target/classes;$diagnosticCp" org.example.stockwatch247.service.AnalysisRecoveryReplay AAPL 1wk 2026-09-25T22:00:00Z all
```

Use `primary` instead of `all` for developing-wave detection only. Restarting the
same command reuses that snapshot and its durable checkpoints. Do not rebuild
classes used by a running replay. Stop the helper first, or copy the compiled
classes to a separate replay directory. This is a component replay, not a full
scheduled-job or SMTP-delivery acceptance test.

For a cold checkpoint run, append a new run name after `all`, for example
`all acceptance-20260930`. This reuses the immutable candle snapshot but writes
to `checkpoints-acceptance-20260930` and `primary-acceptance-20260930.json`,
preserving earlier diagnostics. Repeating that name resumes its checkpoints;
choose a different name to measure another cold run. The final line reports
wall time and process CPU time; continuation lines also include per-slice CPU
time. Large wall-time gaps with little CPU progress can indicate host suspension.
Cold and resumed measurements are different. Append `continuous` after the run
name to match current scheduled execution: checkpoints are saved without ending
the current calculation. Without it, the helper deliberately tests sliced restart
behavior. For example: `all cold-20260930 continuous`.

For a saved-snapshot candlestick/harmonic benchmark:

```powershell
javac -cp "target/classes;$diagnosticCp" -d target/recovery-tools tools/AnalysisRecoveryReplay.java tools/TechnicalSignalBenchmark.java
java -cp "target/recovery-tools;target/classes;$diagnosticCp" org.example.stockwatch247.service.TechnicalSignalBenchmark target/recovery-replay/AAPL-v2-1wk-1790373600/candles.json target/signal-benchmark.json
```

This runs three passes over 1,000 weekly bars, reports history and new-signal
timings separately and saves full result JSON for equivalence comparisons. It
does not access the database or providers. `java tools/ProfileSummary.java
target/recording.jfr` summarizes execution samples from an existing JFR recording.

Results and network observations are recorded in
[`docs/pattern-detection-remediation.md`](../docs/pattern-detection-remediation.md).

## Viewport performance

Compile the snapshot-only viewport helper with the same dependency classpath:

```powershell
javac -cp "target/classes;$diagnosticCp" -d target/viewport-tools tools/AnalysisRecoveryReplay.java tools/PatternViewportBenchmark.java tools/TechnicalSignalBenchmark.java
java -Xmx768m -cp "target/viewport-tools;target/classes;$diagnosticCp" org.example.stockwatch247.service.PatternViewportBenchmark target/recovery-replay/AAPL-v2-1wk-1790373600/candles.json target/viewport-overlays.json 96
java -Xmx768m -cp "target/viewport-tools;target/classes;$diagnosticCp" org.example.stockwatch247.service.PatternViewportBenchmark target/recovery-replay/AAPL-v2-1wk-1790373600/candles.json target/viewport-candlesticks.json 96 overlay
```

The first command measures structure selection, bounded candidate recognition and
harmonics. The second measures score-free candlestick overlays, including their
unchanged lifecycle outcomes. Use `candles` instead of `overlay` to measure fully
scored historical signals, including causal confluence; this can take much longer.
Use `1000` instead of `96` for full-window detector comparisons. Each invocation
uses a fresh JVM and reads only the supplied snapshot. Database loading and HTTP
rendering are not included. These commands send no notifications.

Append `uncached` to `TechnicalSignalBenchmark` to measure harmonic result-cache
misses on every pass. It varies only `maximumFormations`, the display limit that
`detectAll` and `detectNewlyAvailable` do not apply, giving distinct cache keys
without changing admissible formations. Omit it to measure repeated exact-input
reuse. Compare parsed JSON rather than bytes for harmonic measurement maps whose
property order can differ across JVMs.

## Automated outlook performance

`OutlookPerformanceReplay.java` reads a JSON object with `candles` (ordinary
`Candle` JSON fields) and `preferences` (an `AnalysisPreferencesService.PreferencesView`).
Keep snapshots under `target/`, not in version control. The local diagnostic
snapshot is `target/outlook-shw-snapshot.json`; it is not distributed with the repo.
No database, provider, user-delivery or email connections are made. Signal and
activity repositories return empty lists, so this measures numerical analysis
and view construction rather than full live tracking latency.

```powershell
$jdk = 'C:\Users\Jan Gradkowski\.jdks\openjdk-25.0.1\bin'
$diagnosticCp = (Get-Content target/diagnostic-classpath.txt -Raw).Trim()
& "$jdk/javac.exe" -cp "target/classes;$diagnosticCp" -d target/viewport-tools tools/OutlookPerformanceReplay.java
& "$jdk/java.exe" -Xmx768m -cp "target/viewport-tools;target/classes;$diagnosticCp" org.example.stockwatch247.service.OutlookPerformanceReplay target/outlook-shw-snapshot.json detail
```

Each invocation runs a cold detailed outlook and an identical repeat. Omit
`detail` to measure summary, subsequent detailed outlook (sharing core results),
and repeated detail. Start a fresh JVM for each cold measurement. Enable DEBUG
for `org.example.stockwatch247.service.TechnicalIndicatorEnrichmentService` in
the application to see core/research/KDE timings and numerical cache hits.
