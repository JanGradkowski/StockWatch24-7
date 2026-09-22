package org.example.stockwatch247.service;

import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.repository.UserAnalysisPreferencesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Offline diagnostic: reads an explicit candle/settings snapshot, never starts Spring or sends notifications. */
@EnabledIfSystemProperty(named = "signal.audit.input", matches = ".+")
class SignalPipelineAuditTest {
    @Test
    void replayCompletedCandlesThroughTheProductionDetectors() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.readTree(Files.readString(Path.of(System.getProperty("signal.audit.input"))));
        Instant asOf = Instant.ofEpochSecond(input.path("asOf").asLong());
        long since = LocalDate.parse(System.getProperty("signal.audit.since", "2026-09-01"))
                .atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        var completion = new CandleCompletionService("Europe/Brussels", Clock.fixed(asOf, ZoneOffset.UTC));
        User user = new User();
        user.setId(1L);
        var repository = mock(UserAnalysisPreferencesRepository.class);
        if (input.path("analysisPreferences").isTextual()) {
            UserAnalysisPreferences saved = new UserAnalysisPreferences();
            saved.setPreferencesPayload(input.path("analysisPreferences").asText());
            saved.setUser(user);
            when(repository.findByUser(user)).thenReturn(Optional.of(saved));
        }
        var preferences = new AnalysisPreferencesService(repository, mapper);
        var enrichment = new TechnicalIndicatorEnrichmentService();
        var candlestick = new CandlePatternDetectionService();
        var elliott = new ElliottWaveDetectionService();
        var harmonic = new HarmonicPatternDetectionService();
        Map<String, List<Candle>> series = new TreeMap<>();
        for (JsonNode item : input.path("series")) {
            String symbol = item.path("symbol").asText(), interval = item.path("time_interval").asText();
            List<Candle> candles = new ArrayList<>();
            for (JsonNode bar : item.path("bars")) {
                if (Boolean.getBoolean("signal.audit.canonicalOnly") && interval.equals("1wk")) {
                    var date = Instant.ofEpochSecond(bar.get(0).asLong()).atZone(ZoneOffset.UTC);
                    if (date.getDayOfWeek() != DayOfWeek.MONDAY || !date.toLocalTime().equals(LocalTime.MIDNIGHT)) continue;
                }
                candles.add(new Candle(symbol, interval, bar.get(0).asLong(), number(bar.get(1)), number(bar.get(2)),
                        number(bar.get(3)), number(bar.get(4)), bar.get(5).isNull() ? null : bar.get(5).asLong()));
            }
            series.put(symbol + "|" + interval, candles);
        }
        assertThat(series).isNotEmpty();
        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (var entry : series.entrySet()) {
            String[] key = entry.getKey().split("\\|");
            if (key[1].equals("60min")) continue;
            String symbol = key[0];
            TimeInterval interval = switch (key[1]) { case "1wk" -> TimeInterval.WEEKLY; case "1mo" -> TimeInterval.MONTHLY; default -> TimeInterval.DAILY; };
            List<Candle> completed = entry.getValue().stream()
                    .filter(c -> completion.isComplete(c.getTimestamp(), interval)).toList();
            int scans = 0, configuredCount = 0, factoryCount = 0, waveCount = 0, harmonicCount = 0;
            for (int i = 0; i < completed.size(); i++) {
                long timestamp = completed.get(i).getTimestamp();
                if (timestamp < since) continue;
                scans++;
                int required = Math.max(260, Math.max(enrichment.requiredInputCandles(100, interval),
                        enrichment.requiredElliottInputCandles(100, interval)));
                List<Candle> raw = completed.subList(Math.max(0, i + 1 - required), i + 1);
                try (var ignored = AnalysisComputationScope.open()) {
                    var context = new CandlestickFormationIntegrity(raw).latestContext(enrichment.enrich(raw, 100, interval));
                    var rules = preferences.trendDetectionRules(preferences.profile(user, interval));
                    var configured = candlestick.detectAlertSignals(context, rules,
                            CandlestickPatternPreferencesService.factoryPreferences());
                    var factory = candlestick.detectAlertSignalsFactory(context, interval);
                    configuredCount += configured.size();
                    factoryCount += factory.size();
                    for (var signal : configured) findings.add(finding(input, symbol, interval, "CANDLESTICK", signal.pattern().name(),
                            signal.candleTimestamp(), signal.confidenceScore(), null));
                    for (var signal : factory) {
                        if (configured.stream().noneMatch(c -> c.pattern() == signal.pattern())) {
                            findings.add(finding(input, symbol, interval, "FACTORY_ONLY_CANDLESTICK", signal.pattern().name(),
                                    signal.candleTimestamp(), signal.confidenceScore(), null));
                        }
                    }
                    var waveCandles = enrichment.enrichForElliott(raw, 100, interval);
                    for (var signal : elliott.detectAlertSignals(waveCandles)) {
                        if (!signal.pattern().name().endsWith("WAVE_V_END") && !signal.pattern().name().endsWith("CORRECTION")) continue;
                        waveCount++;
                        findings.add(finding(input, symbol, interval, "ELLIOTT_TERMINAL", signal.pattern().name(),
                                signal.candleTimestamp(), signal.confidenceScore(), null));
                    }
                    var parents = enrichment.enrichForElliott(raw, interval == TimeInterval.WEEKLY ? 200 : 100, interval);
                    var children = children(series, symbol, interval, parents, timestamp, enrichment);
                    var developing = children.isEmpty() ? elliott.findDevelopingImpulses(parents)
                            : elliott.findDevelopingImpulses(parents, children);
                    for (var candidate : developing) {
                        if (candidate.confirmationTimestamp() != timestamp || candidate.confidenceScore() < elliott.minimumSignalConfidence()) continue;
                        waveCount++;
                        findings.add(finding(input, symbol, interval, "ELLIOTT_DEVELOPING", candidate.pattern().name(),
                                timestamp, candidate.confidenceScore(), candidate.developmentKey()));
                    }
                    for (var formation : harmonic.detectHistorical(raw)) {
                        if (formation.confirmationTimestamp() != timestamp) continue;
                        harmonicCount++;
                        findings.add(finding(input, symbol, interval, "HARMONIC", HarmonicPatternDetectionService.signalPattern(formation.pattern()).name(),
                                timestamp, formation.qualityScore(), null));
                    }
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("symbol", symbol); summary.put("interval", interval);
            summary.put("latestCompleted", completed.isEmpty() ? "none" : date(completed.getLast().getTimestamp()));
            summary.put("scannedCandles", scans); summary.put("configuredCandlesticks", configuredCount);
            summary.put("factoryCandlesticks", factoryCount); summary.put("elliottCandidates", waveCount);
            summary.put("harmonicCandidates", harmonicCount);
            summaries.add(summary);
            System.out.println("SIGNAL_AUDIT " + mapper.writeValueAsString(summary));
        }
        Path output = Path.of(System.getProperty("signal.audit.output", "target/signal-audit-report.json"));
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("asOf", asOf.toString(), "summaries", summaries, "findings", findings,
                        "analysisProfile", preferences.get(user),
                        "scope", "Current code and cached candles; candlestick geometry, Elliott and harmonic definitions use factory settings. No jobs, data refreshes or emails are executed.")));
        System.out.println("SIGNAL_AUDIT findings=" + findings.size() + " report=" + output);
    }

    private List<EnrichedCandle> children(Map<String, List<Candle>> series, String symbol, TimeInterval interval,
                                        List<EnrichedCandle> parents, long endpoint,
                                        TechnicalIndicatorEnrichmentService enrichment) {
        if (parents.isEmpty()) return List.of();
        String child = switch (interval) { case WEEKLY -> "1d"; case MONTHLY -> "1wk"; default -> "60min"; };
        TimeInterval childInterval = switch (interval) { case WEEKLY -> TimeInterval.DAILY; case MONTHLY -> TimeInterval.WEEKLY; default -> TimeInterval.ONE_HOUR; };
        LocalDate start = Instant.ofEpochSecond(endpoint).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = switch (interval) { case WEEKLY -> start.plusDays(5); case MONTHLY -> start.plusMonths(1); default -> start.plusDays(1); };
        long cutoff = end.atStartOfDay(ZoneId.of("Europe/Brussels")).toEpochSecond();
        var raw = series.getOrDefault(symbol + "|" + child, List.of()).stream()
                .filter(c -> c.getTimestamp() >= parents.getFirst().timestamp() && c.getTimestamp() < cutoff).toList();
        long firstPeriodEnd = parents.size() > 1 ? parents.get(1).timestamp() : Long.MAX_VALUE;
        if (raw.isEmpty() || raw.getFirst().getTimestamp() >= firstPeriodEnd) return List.of();
        try { return enrichment.enrichForElliott(raw, raw.size(), childInterval); }
        catch (IllegalArgumentException ex) { return List.of(); }
    }

    private Map<String, Object> finding(JsonNode input, String symbol, TimeInterval interval, String detector,
                                        String pattern, long timestamp, int confidence, String developmentKey) {
        boolean recorded = false, existingCycle = false;
        for (JsonNode event : input.path("events")) {
            if (!symbol.equals(event.path("symbol").asText()) || !interval.name().equals(event.path("interval").asText())) continue;
            recorded |= pattern.equals(event.path("pattern").asText()) && timestamp == event.path("signal_candle_timestamp").asLong();
            existingCycle |= developmentKey != null && developmentKey.equals(event.path("elliott_development_key").asText());
        }
        return Map.of("symbol", symbol, "interval", interval, "detector", detector, "pattern", pattern,
                "date", date(timestamp), "score", confidence, "recorded", recorded, "existingCycle", existingCycle);
    }

    private static Double number(JsonNode node) { return node.isNull() ? null : node.asDouble(); }
    private static String date(long timestamp) { return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString(); }
}
