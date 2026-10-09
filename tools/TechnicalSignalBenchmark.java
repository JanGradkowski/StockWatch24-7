package org.example.stockwatch247.service;

import java.nio.file.*;
import java.util.*;
import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import tools.jackson.databind.ObjectMapper;

/** Saved-market-data benchmark. No Spring context, database writes, providers or mail. */
class TechnicalSignalBenchmark {
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();
        var snapshot=json.readValue(Files.readString(Path.of(args[0])),AnalysisRecoveryReplay.Snapshot.class);
        List<Candle> raw=snapshot.parents().stream().map(AnalysisRecoveryReplay.CandleBar::candle).toList();
        raw=raw.subList(Math.max(0,raw.size()-1000),raw.size());
        var enrichment=new TechnicalIndicatorEnrichmentService();
        long start=System.nanoTime();
        var enriched=enrichment.enrich(raw,raw.size(),TimeInterval.WEEKLY);
        report("enrichment",start,enriched.size());
        var candles=new CandlePatternDetectionService();
        var harmonic=new HarmonicPatternDetectionService();
        Map<String,Object> outputs=new LinkedHashMap<>();
        for(int pass=0;pass<3;pass++) {
            start=System.nanoTime();
            var historical=new ArrayList<CandlePatternDetectionService.DetectedSignal>();
            for(int index=1;index<enriched.size();index++) historical.addAll(candles.detectFactory(
                    enriched.subList(Math.max(0,index-99),index+1),TimeInterval.WEEKLY));
            report("candlestick-history-"+pass,start,historical.size());
            outputs.put("candlesticks",historical);
            start=System.nanoTime();
            var latest=candles.detectFactory(enriched.subList(Math.max(0,enriched.size()-100),enriched.size()),TimeInterval.WEEKLY);
            report("candlestick-latest-"+pass,start,latest.size());
            if (args.length > 2 && args[2].equals("uncached")) harmonic = uncachedDetector(250 - 2 * pass);
            start=System.nanoTime();
            var formations=harmonic.detectAll(raw); report("harmonic-history-"+pass,start,formations.size());
            outputs.put("harmonics",formations);
            if (args.length > 2 && args[2].equals("uncached")) harmonic = uncachedDetector(249 - 2 * pass);
            start=System.nanoTime();
            var added=harmonic.detectNewlyAvailable(raw); report("harmonic-latest-"+pass,start,added.size());
            outputs.put("newHarmonics",added);
        }
        Files.writeString(Path.of(args[1]),json.writeValueAsString(outputs));
    }
    static HarmonicPatternDetectionService uncachedDetector(int displayLimit) {
        var rules = HarmonicPatternDetectionService.Rules.defaults();
        return new HarmonicPatternDetectionService(new HarmonicPatternDetectionService.Rules(
                rules.fibonacciTolerance(), rules.legEqualityTolerance(), rules.materialLegDifference(),
                rules.minimumSwingFraction(), rules.pivotWindow(), displayLimit, rules.maximumPivotWindow(),
                rules.maximumSwingFraction(), rules.maximumSkippedPivots()));
    }
    static void report(String stage,long start,int count) {
        System.out.printf(Locale.ROOT,"%s ms=%.3f count=%d%n",stage,(System.nanoTime()-start)/1e6,count);
    }
}
