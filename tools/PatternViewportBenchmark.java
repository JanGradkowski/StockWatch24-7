package org.example.stockwatch247.service;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.StockAssetRepository;
import tools.jackson.databind.ObjectMapper;

class PatternViewportBenchmark {
    public static void main(String[] arguments) throws Exception {
        ObjectMapper json = new ObjectMapper();
        var snapshot = json.readValue(Files.readString(Path.of(arguments[0])), AnalysisRecoveryReplay.Snapshot.class);
        List<Candle> raw = snapshot.parents().stream().map(AnalysisRecoveryReplay.CandleBar::candle).toList();
        raw = raw.subList(Math.max(0, raw.size() - 1000), raw.size());
        int count = Math.min(raw.size(), Integer.parseInt(arguments[2]));
        String interval = raw.getFirst().getTimeInterval();
        TimeInterval timeframe = switch (interval) {
            case "1wk" -> TimeInterval.WEEKLY;
            case "1mo" -> TimeInterval.MONTHLY;
            default -> TimeInterval.DAILY;
        };
        long from = raw.get(raw.size() - count).getTimestamp();
        long to = raw.getLast().getTimestamp();
        var enrichment = new TechnicalIndicatorEnrichmentService();
        var outputs = new LinkedHashMap<String, Object>();
        long started = System.nanoTime();
        if (arguments.length > 3 && List.of("candles", "overlay").contains(arguments[3])) {
            var assets = (StockAssetRepository) Proxy.newProxyInstance(StockAssetRepository.class.getClassLoader(),
                    new Class<?>[]{StockAssetRepository.class}, (proxy, method, args) -> {
                        if (method.getName().equals("findByTickerSymbolIgnoreCase")) return Optional.empty();
                        throw new UnsupportedOperationException(method.getName());
                    });
            var completion = new CandleCompletionService("UTC", Clock.fixed(Instant.ofEpochSecond(to + 62 * 86400L), ZoneOffset.UTC));
            var service = new HistoricalCandlestickService(null, assets, null, enrichment,
                    new CandlePatternDetectionService(), completion, "UTC", 8);
            var rules = CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(timeframe);
            var definitions = CandlestickPatternPreferencesService.factoryPreferences();
            if (arguments[3].equals("overlay")) {
                var scan = service.scanOverlayRange(raw.getFirst().getSymbol(), interval, from, to, rules, definitions, raw);
                report("candlestick-overlay-cold", started, scan.signals().size());
                outputs.put("candles", scan.signals());
                started = System.nanoTime();
                var repeated = service.scanOverlayRange(raw.getFirst().getSymbol(), interval, from, to, rules, definitions, raw);
                report("candlestick-overlay-repeat", started, repeated.signals().size());
                if (!scan.equals(repeated)) throw new AssertionError("Repeated overlay results differ");
                Files.writeString(Path.of(arguments[1]), json.writeValueAsString(outputs));
                return;
            }
            var scan = service.scanRange(raw.getFirst().getSymbol(), interval, from, to, rules, definitions, raw);
            report("candlesticks-cold", started, scan.signals().size());
            outputs.put("candles", scan.signals());
            started = System.nanoTime();
            var repeated = service.scanRange(raw.getFirst().getSymbol(), interval, from, to, rules, definitions, raw);
            report("candlesticks-repeat", started, repeated.signals().size());
            if (!scan.equals(repeated)) throw new AssertionError("Repeated candlestick results differ");
        } else {
            var enriched = enrichment.enrichForElliott(raw, raw.size(), timeframe);
            report("enrichment", started, enriched.size());
            var waves = new ElliottWaveDetectionService();
            started = System.nanoTime();
            var structures = waves.findHistoricalWaveStructures(enriched).stream()
                    .filter(structure -> structure.points().getLast().timestamp() >= from
                            && structure.points().getFirst().timestamp() <= to).toList();
            report("elliott-structures", started, structures.size());
            outputs.put("structures", structures);
            started = System.nanoTime();
            var candidates = waves.findPatternCandidates(enriched, from, to);
            report("elliott-candidates", started, candidates.size());
            outputs.put("candidates", candidates);
            started = System.nanoTime();
            var formations = new HarmonicPatternDetectionService().detectRange(raw, from, to);
            report("harmonics", started, formations.size());
            outputs.put("harmonics", formations);
        }
        Files.writeString(Path.of(arguments[1]), json.writeValueAsString(outputs));
    }

    private static void report(String name, long start, int count) {
        System.out.printf(Locale.ROOT, "%s ms=%.3f count=%d%n", name, (System.nanoTime() - start) / 1e6, count);
    }
}
