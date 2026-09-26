package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Fixed examples from the primary definitions, deliberately independent of the ratio generator. */
class PatternDetectionAuditRegressionTest {
    private final HarmonicPatternDetectionService harmonics = new HarmonicPatternDetectionService();
    private final ElliottWaveDetectionService waves = new ElliottWaveDetectionService();

    @Test void recognizesFourPointPatternWithOnlyItsRequiredConfirmationBars() {
        var detector = new HarmonicPatternDetectionService(new Rules(.03,.03,.10,0,1,40));
        double[] prices = {190,200,100,161.8,61.8,70};
        var candles = new ArrayList<Candle>();
        for (int i=0;i<prices.length;i++) {
            double price=prices[i];
            candles.add(new Candle("TEST","1d",ts(i),price,price,price,price,1000L));
        }
        assertThat(detector.detectAll(candles)).anyMatch(f -> f.pattern()==HarmonicPatternType.AB_CD);
    }

    @Test void recognizesPublishedHarmonicRangesInBothDirections() {
        check(HarmonicPatternType.BAT, 100, 200, 145, 181.25, 111.4);
        check(HarmonicPatternType.BAT, 100, 200, 150, 192.3, 111.4);
        check(HarmonicPatternType.BUTTERFLY, 100, 200, 121.4, 151.31347, 73);
        check(HarmonicPatternType.SHARK, 100, 200, 144, 214, 100);
    }

    @Test void recognizesAddedFamiliesInBothDirections() {
        check(HarmonicPatternType.ALTERNATE_BAT, 100, 200, 170, 192, 87);
        check(HarmonicPatternType.DEEP_CRAB, 100, 200, 111.4, 170, 38.2);
        check(HarmonicPatternType.FIVE_ZERO, 200, 300, 160, 420, 290);
        check(HarmonicPatternType.AB_CD, 200, 100, 161.8, 61.8);
        check(HarmonicPatternType.ALTERNATE_AB_CD, 300, 200, 261.8, 134.8);
    }

    @Test void addedFamiliesTravelThroughOhlcDetectionAndTradePlanning() {
        var examples = Map.of(
                HarmonicPatternType.ALTERNATE_BAT, new double[]{100,200,170,192,87},
                HarmonicPatternType.DEEP_CRAB, new double[]{100,200,111.4,170,38.2},
                HarmonicPatternType.FIVE_ZERO, new double[]{200,300,160,420,290},
                HarmonicPatternType.AB_CD, new double[]{200,100,161.8,61.8},
                HarmonicPatternType.ALTERNATE_AB_CD, new double[]{300,200,261.8,134.8});
        for(var example:examples.entrySet()) for(boolean bearish:List.of(false,true)) {
            double[] shifted=Arrays.stream(example.getValue()).map(p -> p+500).toArray();
            var geometry=pivots(bearish,shifted);
            var formation=harmonics.classifyAll(geometry).stream()
                    .filter(f -> f.pattern()==example.getKey()).findFirst().orElseThrow();
            double entry=geometry.getLast().price()+(bearish?-1:1);
            for(var interval:List.of(TimeInterval.DAILY,TimeInterval.WEEKLY,TimeInterval.MONTHLY)) {
                var plan=HarmonicStopPlanPolicy.calculate(formation,entry,2,interval).orElseThrow();
                assertThat(plan.primaryTarget()).isPositive();
                assertThat(plan.stopLossPrice()).isPositive();
            }
            double[] path=new double[geometry.size()+2];
            path[0]=geometry.getFirst().price()+(geometry.getFirst().type()==PivotType.LOW?10:-10);
            for(int i=0;i<geometry.size();i++)path[i+1]=geometry.get(i).price();
            path[path.length-1]=entry+(bearish?-5:5);
            assertThat(harmonics.detectAll(raw(series(path))))
                    .anyMatch(f -> f.pattern()==example.getKey() && f.direction()==formation.direction());
        }
    }

    @Test void addedFamiliesRejectWrongCompletionPrices() {
        var invalid=Map.of(
                HarmonicPatternType.ALTERNATE_BAT,new double[]{100,200,170,192,65},
                HarmonicPatternType.DEEP_CRAB,new double[]{100,200,111.4,170,60},
                HarmonicPatternType.FIVE_ZERO,new double[]{200,300,160,420,330},
                HarmonicPatternType.AB_CD,new double[]{200,100,161.8,30},
                HarmonicPatternType.ALTERNATE_AB_CD,new double[]{300,200,261.8,180});
        for(var example:invalid.entrySet()) for(boolean bearish:List.of(false,true))
            assertThat(harmonics.classifyAll(pivots(bearish,example.getValue())))
                    .noneMatch(f -> f.pattern()==example.getKey());
    }

    @Test void preservesHistoricalCountsAcrossLaterMalformedBarsWithoutJoiningTheGap() {
        var input=raw(series(100,120,110,150,130,160,145));
        var before=new TechnicalIndicatorEnrichmentService().enrichForElliott(input,input.size());
        int originalSize=input.size();
        input.add(new Candle("TEST","1d",ts(originalSize),150,151,149,null,1000L));
        input.addAll(raw(series(140,150)).stream().map(c -> new Candle("TEST","1d",
                c.getTimestamp()+(originalSize+1)*86400L,c.getOpenPrice(),c.getHighPrice(),c.getLowPrice(),c.getClosePrice(),1000L)).toList());
        var enriched=new TechnicalIndicatorEnrichmentService().enrichForElliott(input,input.size());
        assertThat(enriched.subList(0,originalSize)).isEqualTo(before);
        assertThat(PatternCandleIntegrity.enrichedSegments(enriched)).hasSize(2);
        assertThat(waves.findAllWaveStructures(enriched)).containsAll(waves.findAllWaveStructures(before));
    }

    @Test void doesNotClaimCalendarCoverageWithoutAnExchangeCalendar() {
        var input=raw(series(100,120));
        assertThat(PatternCandleIntegrity.inspect(input,null).calendarContinuityVerified()).isFalse();
        var expected=input.stream().map(Candle::getTimestamp).toList();
        assertThat(PatternCandleIntegrity.inspect(input,expected).calendarContinuityVerified()).isTrue();
        input.remove(3);
        assertThat(PatternCandleIntegrity.inspect(input,expected).issues()).contains("Missing exchange period: "+ts(3));
    }

    @Test void rejectsAbsentParentPricesForMotiveAndTriangleCounts() {
        var children = series(100,120,110,150,130,160);
        assertThat(waves.findStrictSubdivisions(children,"I",1,1000)).isEmpty();
        assertThat(waves.findStrictSubdivisions(series(100,160,115,150,125,140),"IV",1,1000)).isEmpty();
    }

    @Test void rejectsNonWedgeDiagonalAndFailedZigzag() {
        assertThat(waves.findStrictSubdivisions(series(100,120,110,150,115,155),"V",100,155))
                .noneMatch(s -> s.structureLabel().contains("diagonal"));
        assertThat(waves.findStrictSubdivisions(series(200,160,180,170),"II",200,170)).isEmpty();
    }

    @Test void rejectsCorrectionEndingOnWrongSideOfWaveFive() {
        var data = series(100,120,110,150,130,160,140,178,170,179);
        for (int end=34;end<=data.size();end++) {
            assertThat(waves.detect(data.subList(0,end)))
                    .noneMatch(s -> s.pattern()==CandlePattern.ELLIOTT_BULLISH_RUNNING_FLAT_CORRECTION);
        }
    }

    @Test void recognizesTriangleVariantsWithoutInventingParentEndpoints() {
        assertTriangle("Contracting", 100,160,115,150,125,140);
        assertTriangle("Barrier", 100,160,115,160,125,145);
        assertTriangle("Expanding", 100,150,110,165,90,180);
    }

    @Test void discoversStandaloneCountsWithoutRequiringAnEarlierImpulse() {
        assertThat(waves.findPatternCandidates(series(200,160,180,140,150)))
                .anySatisfy(c -> {
                    assertThat(c.structureLabel()).contains("zigzag");
                    assertThat(c.legalParentPositions()).contains("II");
                    assertThat(c.unresolvedLegs()).isNotEmpty();
                });
        assertThat(waves.findPatternCandidates(series(100,140,120,150,135,155,145)))
                .anyMatch(c -> c.structureLabel().contains("diagonal"));
        assertThat(waves.findPatternCandidates(series(100,160,115,150,125,140,130)))
                .anyMatch(c -> c.structureLabel().contains("triangle"));
        assertThat(waves.findPatternCandidates(series(200,180,190,170,185,165,175,155,165)))
                .anyMatch(c -> c.structureLabel().contains("W-X-Y"));
    }

    @Test void harmonicAvailabilityUsesRecognitionTimeRatherThanBackdatingToThePivot() {
        var formation=harmonics.classify(pivots(false,100,200,145,181.25,111.4)).orElseThrow();
        var late=new HarmonicPatternDetectionService() {
            @Override public List<HarmonicFormation> detectAll(List<Candle> input) {
                return input.size()>=40?List.of(formation):List.of();
            }
        };
        var input=raw(series(100,120,110,150,130,160));
        assertThat(late.detectNewlyAvailable(input.subList(0,40))).singleElement().satisfies(f -> {
            assertThat(f.confirmationTimestamp()).isEqualTo(ts(39));
            assertThat(f.points()).isEqualTo(formation.points());
        });
        assertThat(late.detectNewlyAvailable(input.subList(0,41))).isEmpty();
    }

    @Test void publishedSpyConfluenceReplaysIdenticallyFromEveryPrefix() throws Exception {
        var raw=new ArrayList<Candle>();
        for(String line:java.nio.file.Files.readAllLines(java.nio.file.Path.of(
                "src/test/resources/elliott-wave/historical-benchmarks.csv"))) {
            if(!line.startsWith("stockcharts_spy_2009_cycle,"))continue;
            String[] f=line.split(",");
            long t=java.time.LocalDate.parse(f[3]).atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC);
            raw.add(new Candle(f[1],"1wk",t,Double.parseDouble(f[4]),Double.parseDouble(f[5]),
                    Double.parseDouble(f[6]),Double.parseDouble(f[7]),Long.parseLong(f[8])));
        }
        assertThat(raw).hasSizeGreaterThan(34);
        var enrichment=new TechnicalIndicatorEnrichmentService();
        var service=new CrossPatternConfluenceService(new CandlePatternDetectionService());
        var emptyH=new HarmonicPatternDetectionService(){
            @Override public List<HarmonicFormation> detectAll(List<Candle> c){return List.of();}
        };
        var full=service.buildTimeline(raw,List.of(),enrichment.enrichForElliott(raw,raw.size()),
                TimeInterval.WEEKLY,null,null,waves,emptyH);
        for(int end=34;end<=raw.size();end++) {
            var prefix=raw.subList(0,end);
            var timeline=service.buildTimeline(prefix,List.of(),enrichment.enrichForElliott(prefix,end),
                    TimeInterval.WEEKLY,null,null,waves,emptyH);
            long timestamp=prefix.getLast().getTimestamp();
            assertThat(service.assess(50,AlertPatternFamily.HARMONIC_FORMATION,TradeSignal.BUY,timestamp,full))
                    .isEqualTo(service.assess(50,AlertPatternFamily.HARMONIC_FORMATION,TradeSignal.BUY,timestamp,timeline));
        }
        assertThat(full.observations()).anyMatch(o -> o.pattern()==CandlePattern.ELLIOTT_BULLISH_WAVE_V_END
                && o.direction()==TradeSignal.SELL);
    }

    @Test void reportsFiniteValidationScope() {
        assertThat(waves.findStrictSubdivisions(series(100,120,110,150,130,160),"I",100,160))
                .anySatisfy(s -> {
                    assertThat(s.observedDepth()).isEqualTo(1);
                    assertThat(s.unresolvedLegs()).hasSize(5);
                    assertThat(s.validationScope()).isEqualTo("OBSERVED_DEGREE_GEOMETRY");
                });
    }

    @Test void malformedAndDuplicateBarsCannotBeSilentlyJoined() {
        var raw = raw(series(100,120,110,150,130,160));
        int middle=raw.size()/2;
        raw.get(middle).setClosePrice(null);
        assertThat(PatternCandleIntegrity.raw(raw)).hasSize(raw.size()-middle-1);
        assertThat(harmonics.detectAll(raw)).isNotNull();
        var duplicate = new ArrayList<>(raw(series(100,120,110,150,130,160)));
        duplicate.add(duplicate.get(middle));
        assertThat(PatternCandleIntegrity.raw(duplicate)).allMatch(c -> c.getTimestamp()>ts(middle));
    }

    @Test void confluenceIsInvariantToFutureCandlesIncludingChangingDetectorOutputs() {
        var raw = raw(series(100,120,110,150,130,160));
        var candleDetector = new CandlePatternDetectionService();
        var service = new CrossPatternConfluenceService(candleDetector);
        var changing = new HarmonicPatternDetectionService() {
            @Override public List<HarmonicFormation> detectAll(List<Candle> input) {
                if (input.size()<12 || input.size()>16) return List.of();
                return List.of(harmonics.classify(pivots(false,100,200,145,181.25,111.4)).orElseThrow());
            }
        };
        var full=service.buildTimeline(raw,List.of(),List.of(),TimeInterval.DAILY,null,null,waves,changing);
        for(int end=2;end<=raw.size();end++) {
            var prefix=service.buildTimeline(raw.subList(0,end),List.of(),List.of(),TimeInterval.DAILY,null,null,waves,changing);
            assertThat(service.assess(50,AlertPatternFamily.ELLIOTT_WAVE,TradeSignal.BUY,ts(end-1),full))
                    .isEqualTo(service.assess(50,AlertPatternFamily.ELLIOTT_WAVE,TradeSignal.BUY,ts(end-1),prefix));
        }
    }

    private void assertTriangle(String variant,double... prices) {
        assertThat(waves.findStrictSubdivisions(series(prices),"IV",prices[0],prices[prices.length-1]))
                .anyMatch(s -> s.structureLabel().startsWith(variant));
    }
    private void check(HarmonicPatternType type,double... prices) {
        for(boolean bearish:List.of(false,true)) {
            assertThat(harmonics.classifyAll(pivots(bearish,prices)))
                    .as("%s %s",type,bearish?"bearish":"bullish")
                    .anySatisfy(f -> {
                        assertThat(f.pattern()).isEqualTo(type);
                        assertThat(f.direction()).isEqualTo(bearish?Direction.BEARISH:Direction.BULLISH);
                        assertThat(HarmonicPatternDetectionService.signalPattern(type).name()).isEqualTo("HARMONIC_"+type);
                    });
        }
    }
    static List<HarmonicPivot> pivots(boolean bearish,double... prices) {
        var result=new ArrayList<HarmonicPivot>();
        for(int i=0;i<prices.length;i++) {
            boolean high=prices.length==4?i%2==0:i%2==1;
            if(bearish)high=!high;
            result.add(new HarmonicPivot(i*5,ts(i*5),bearish?1000-prices[i]:prices[i],
                    high?PivotType.HIGH:PivotType.LOW,ts(i*5+2)));
        }
        return result;
    }
    static long ts(int index) { return 1700000000L+index*86400L; }
    static List<EnrichedCandle> series(double... prices) {
        var result=new ArrayList<EnrichedCandle>();
        for(int i=0;i<prices.length-1;i++)for(int k=0;k<8;k++)
            result.add(ec(result.size(),prices[i]+(prices[i+1]-prices[i])*k/8));
        result.add(ec(result.size(),prices[prices.length-1]));
        return result;
    }
    static EnrichedCandle ec(int index,double price) {
        return new EnrichedCandle(ts(index),price,price,price,price,1000,1000,50,price,price,price-10,price+10,1);
    }
    static ArrayList<Candle> raw(List<EnrichedCandle> input) {
        var result=new ArrayList<Candle>();
        for(var c:input) result.add(new Candle("TEST","1d",c.timestamp(),c.open(),c.high(),c.low(),c.close(),1000L));
        return result;
    }
}
