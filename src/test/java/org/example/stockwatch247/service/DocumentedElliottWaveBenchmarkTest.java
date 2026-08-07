package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline regression checks against wave counts published for real markets by StockCharts.
 * Label sources are documented beside each test; OHLC is frozen from Yahoo Finance in the CSV fixture.
 */
class DocumentedElliottWaveBenchmarkTest {
    private static final Path FIXTURE = Path.of(
            "src/test/resources/elliott-wave/historical-benchmarks.csv");
    private static final String SPY_2009_CYCLE = "stockcharts_spy_2009_cycle";
    private static final String SPX_2004_IMPULSE = "stockcharts_spx_2004_impulse";
    private static final String SPX_2011_BEARISH = "stockcharts_spx_2011_bearish";
    private static final String SPX_2012_OVERLAP = "stockcharts_spx_2012_overlap";

    private final TechnicalIndicatorEnrichmentService enrichmentService =
            new TechnicalIndicatorEnrichmentService();
    private final ElliottWaveDetectionService detectionService =
            new ElliottWaveDetectionService();

    @Test
    void detectsPublishedSpx2004BullishImpulseAndItsWaveFiveEnd() throws IOException {
        // https://articles.stockcharts.com/article/articles-chartwatchers-2005-02-sp-500-and-elliott-wave/
        BenchmarkResult result = benchmark(SPX_2004_IMPULSE);

        assertThat(result.structures()).singleElement().satisfies(structure -> {
            assertImpulse(structure, "BULLISH",
                    "2004-08-09", "2004-10-04", "2004-10-25",
                    "2005-01-03", "2005-01-24", "2005-03-07");
            assertThat(structure.qualityScore()).isEqualTo(90);
        });
        assertThat(result.signals()).anySatisfy(observed -> {
            assertThat(observed.date()).isEqualTo(LocalDate.parse("2005-02-28"));
            assertThat(observed.signal().pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_IMPULSE);
            assertThat(observed.signal().confidenceScore()).isEqualTo(100);
        });
        assertThat(result.signals()).anySatisfy(observed -> {
            assertThat(observed.date()).isEqualTo(LocalDate.parse("2005-03-14"));
            assertThat(observed.signal().pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
            assertThat(observed.signal().confidenceScore()).isEqualTo(100);
            assertThat(observed.signal().eligibilityScore()).isEqualTo(100);
        });
    }

    @Test
    void detectsPublishedSpy2009ImpulseAndRecordsCurrentAbcTimingGap() throws IOException {
        // https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/
        // technical-overlays/zigzag — labels a complete five-up/three-down cycle through July 2010.
        BenchmarkResult result = benchmark(SPY_2009_CYCLE);

        assertThat(result.structures()).singleElement().satisfies(structure -> {
            assertImpulse(structure, "BULLISH",
                    "2009-03-02", "2009-06-08", "2009-07-06",
                    "2010-01-11", "2010-02-01", "2010-04-26");
            assertThat(structure.qualityScore()).isEqualTo(88);
        });
        assertThat(result.signals()).anySatisfy(observed -> {
            assertThat(observed.date()).isEqualTo(LocalDate.parse("2010-05-03"));
            assertThat(observed.signal().pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
            assertThat(observed.signal().confidenceScore()).isEqualTo(90);
            assertThat(observed.signal().eligibilityScore()).isEqualTo(90);
        });

        // Known gap: the published A leg completes in the first weekly candle after Wave V,
        // while the detector currently requires at least two candles for every correction leg.
        assertThat(result.signals()).noneMatch(observed ->
                !observed.date().isBefore(LocalDate.parse("2010-06-01"))
                        && observed.signal().pattern().name().endsWith("CORRECTION"));
        assertThat(result.structures()).noneMatch(
                ElliottWaveDetectionService.ElliottWaveStructure::correctionComplete);
    }

    @Test
    void detectsPublishedSpx2011BearishImpulseButKeepsItBelowEmailThreshold() throws IOException {
        // https://articles.stockcharts.com/article/members-analysis-20111011-1/
        BenchmarkResult result = benchmark(SPX_2011_BEARISH);

        assertThat(result.structures()).singleElement().satisfies(structure -> {
            assertImpulse(structure, "BEARISH",
                    "2011-05-02", "2011-06-13", "2011-07-04",
                    "2011-08-08", "2011-08-29", "2011-10-03");
            assertThat(structure.qualityScore()).isEqualTo(69);
            assertThat(structure.qualityWarnings())
                    .contains("Deep Wave II 87.5% — reduced confidence");
        });
        assertThat(result.signals()).anySatisfy(observed -> {
            assertThat(observed.date()).isEqualTo(LocalDate.parse("2011-10-10"));
            assertThat(observed.signal().pattern()).isEqualTo(CandlePattern.ELLIOTT_BEARISH_WAVE_V_END);
            assertThat(observed.signal().confidenceScore()).isEqualTo(72);
            assertThat(observed.signal().eligibilityScore()).isEqualTo(72);
        });

        List<Candle> confirmationPrefix = through(result.candles(), LocalDate.parse("2011-10-10"));
        List<EnrichedCandle> enriched = enrichmentService.enrichForElliott(
                confirmationPrefix, confirmationPrefix.size());
        assertThat(detectionService.detectAlertSignals(enriched))
                .noneMatch(signal -> signal.pattern() == CandlePattern.ELLIOTT_BEARISH_WAVE_V_END);
    }

    @Test
    void rejectsPublishedSpx2012CountThatTheSourceSaysBreaksTheOverlapRule() throws IOException {
        // https://articles.stockcharts.com/article/members-analysis-20120917-1/
        BenchmarkResult result = benchmark(SPX_2012_OVERLAP);

        Candle waveOneWeek = candleOn(result.candles(), LocalDate.parse("2011-10-24"));
        Candle waveFourWeek = candleOn(result.candles(), LocalDate.parse("2012-06-04"));
        assertThat(waveFourWeek.getLowPrice()).isLessThan(waveOneWeek.getHighPrice());
        assertThat(result.signals()).isEmpty();
        assertThat(result.structures()).isEmpty();
    }

    private BenchmarkResult benchmark(String caseId) throws IOException {
        List<Candle> candles = candles(caseId);
        assertThat(candles).hasSizeGreaterThanOrEqualTo(34);

        List<ObservedSignal> signals = new ArrayList<>();
        for (int endExclusive = 34; endExclusive <= candles.size(); endExclusive++) {
            List<Candle> prefix = candles.subList(0, endExclusive);
            List<EnrichedCandle> enriched =
                    enrichmentService.enrichForElliott(prefix, prefix.size());
            detectionService.detect(enriched).forEach(signal ->
                    signals.add(new ObservedSignal(date(signal.candleTimestamp()), signal)));
        }

        List<EnrichedCandle> enriched =
                enrichmentService.enrichForElliott(candles, candles.size());
        List<ElliottWaveDetectionService.ElliottWaveStructure> structures =
                detectionService.findHistoricalWaveStructures(enriched);
        return new BenchmarkResult(candles, List.copyOf(signals), structures);
    }

    private void assertImpulse(ElliottWaveDetectionService.ElliottWaveStructure structure,
                               String direction,
                               String... expectedDates) {
        assertThat(structure.direction()).isEqualTo(direction);
        assertThat(structure.correctionComplete()).isFalse();
        assertThat(structure.points()).extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                .containsExactly("", "I", "II", "III", "IV", "V");
        assertThat(structure.points()).extracting(point -> date(point.timestamp()).toString())
                .containsExactly(expectedDates);
    }

    private List<Candle> candles(String caseId) throws IOException {
        List<Candle> candles = new ArrayList<>();
        for (String line : Files.readAllLines(FIXTURE)) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("case_id,")) {
                continue;
            }
            String[] values = line.split(",");
            if (!caseId.equals(values[0])) {
                continue;
            }
            long timestamp = LocalDate.parse(values[3]).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            candles.add(new Candle(
                    values[1], values[2], timestamp,
                    Double.parseDouble(values[4]), Double.parseDouble(values[5]),
                    Double.parseDouble(values[6]), Double.parseDouble(values[7]),
                    Long.parseLong(values[8])));
        }
        return List.copyOf(candles);
    }

    private List<Candle> through(List<Candle> candles, LocalDate inclusiveDate) {
        return candles.stream()
                .filter(candle -> !date(candle.getTimestamp()).isAfter(inclusiveDate))
                .toList();
    }

    private Candle candleOn(List<Candle> candles, LocalDate date) {
        return candles.stream()
                .filter(candle -> date(candle.getTimestamp()).equals(date))
                .findFirst()
                .orElseThrow();
    }

    private LocalDate date(Long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private record ObservedSignal(LocalDate date, DetectedSignal signal) {
    }

    private record BenchmarkResult(
            List<Candle> candles,
            List<ObservedSignal> signals,
            List<ElliottWaveDetectionService.ElliottWaveStructure> structures) {
    }
}
