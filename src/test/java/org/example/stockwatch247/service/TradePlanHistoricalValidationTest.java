package org.example.stockwatch247.service;

import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.assertj.core.api.Assertions.assertThat;

/** Causal sampled replay. Parameter choices are frozen before the later-period evaluation. */
@EnabledIfSystemProperty(named="trade.validation", matches="true")
class TradePlanHistoricalValidationTest {
    record Plan(double stop, Double target, int horizon, boolean qualified) {}
    static class Stats {
        int proposals, rejected, trades, targets, stops, expired, incomplete;
        double sumR, equityR, peakR, drawdownR;
        void record(double r, TradeOutcomePolicy.Kind kind) {
            trades++; sumR+=r; equityR+=r; peakR=Math.max(peakR,equityR); drawdownR=Math.max(drawdownR,peakR-equityR);
            switch(kind) { case STOPPED -> stops++; case TARGET_REACHED -> targets++; case TIME_STOPPED -> expired++; }
        }
    }
    final Map<String,Stats> stats=new TreeMap<>();
    final TechnicalIndicatorEnrichmentService enrichment=new TechnicalIndicatorEnrichmentService();
    final CandlePatternDetectionService candlesDetector=new CandlePatternDetectionService();
    final HarmonicPatternDetectionService harmonic=new HarmonicPatternDetectionService();
    final ElliottWaveDetectionService elliott=new ElliottWaveDetectionService();
    @Test void replaySavedDailyDataAcrossFamiliesAndIntervals() throws Exception {
        Path data=Path.of(System.getProperty("trade.validation.data","target/expanded-backtest-data/post-2018-candles.csv.gz"));
        assertThat(data).exists();
        int max=Integer.getInteger("trade.validation.symbols",8);
        Map<String,List<Candle>> universe=new LinkedHashMap<>();
        try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(data))))) {
            reader.readLine(); String line;
            while((line=reader.readLine())!=null) {
                if(line.isBlank()) continue;
                String[] f=line.split(",");
                if(!universe.containsKey(f[0]) && universe.size()>=max) continue;
                Candle c=new Candle(f[0],"1d",LocalDate.parse(f[1]).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                        Double.parseDouble(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4]),Double.parseDouble(f[5]),Long.parseLong(f[6]));
                if(TradeRiskPolicy.valid(c)) universe.computeIfAbsent(f[0],k->new ArrayList<>()).add(c);
            }
        }
        for(var item:universe.entrySet()) {
            for(var interval:List.of(TimeInterval.DAILY,TimeInterval.WEEKLY,TimeInterval.MONTHLY)) replay(aggregate(item.getValue(),interval),interval);
            System.out.println("Trade plan replay: "+item.getKey()+" complete");
        }
        var report=new StringBuilder("# Trade plan validation\n\n");
        report.append("Universe: ").append(universe.keySet()).append(". Sampled scans every 10 daily / 3 weekly / 1 monthly bars after 60 bars of history; rolling 180-bar detection.\n\n");
        report.append("Coverage: multi-candle candlestick patterns without the separate next-bar confirmation gate, completed Elliott Wave V/corrections, and harmonic formations. Developing Elliott stages and candidate-gated candles are covered by deterministic tests, not this replay. The baseline Elliott implementation rejects detector origins labeled with an empty string; those rejections are retained as the actual previous behavior. Both Elliott variants use the new fixed horizon for comparison. Every scan uses only completed prefix data. Entry is the next bar open; eligibility is recalculated at that price. Outcomes use OHLC, opening gaps and conservative stop-first ambiguity for both versions, with 10 basis points cost per side. Daily sub-bars are not used in this offline comparison. Baselines freeze the pre-change formulas; harmonic baseline has no target. Duplicate formations are excluded. Pending tails are excluded.\n\n");
        report.append("Before 2019 is the development slice; 2019 onward is temporal evaluation. Cached data was used in earlier research, so this is not a pristine holdout. Defaults were not optimized on either slice. Alphabetical cached sample, overlapping signals and survivor selection limit inference. Drawdown is a sequential equal-risk diagnostic, not a portfolio backtest. Zero counts indicate insufficient evidence.\n\n");
        report.append("| Group | Proposed | Rejected | Trades | Target | Stop | Expired | Incomplete | Mean net R | Sequential drawdown R |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        stats.forEach((key,v)->report.append(String.format(Locale.ROOT,"| %s | %d | %d | %d | %d | %d | %d | %d | %.3f | %.3f |%n",key,v.proposals,v.rejected,v.trades,v.targets,v.stops,v.expired,v.incomplete,v.trades==0?0:v.sumR/v.trades,v.drawdownR)));
        report.append("\nDo not promote tuned parameters or claim improved accuracy from this diagnostic alone. Expand the point-in-time universe and reserve a genuinely untouched period before selecting defaults.\n");
        Files.createDirectories(Path.of("target/trade-validation"));
        Files.writeString(Path.of("target/trade-validation/report.md"),report);
        assertThat(stats.values().stream().mapToInt(x->x.proposals).sum()).isPositive();
    }
    void replay(List<Candle> bars,TimeInterval interval) {
        int stride=interval==TimeInterval.DAILY?10:interval==TimeInterval.WEEKLY?3:1;
        Set<String> seen=new HashSet<>();
        for(int i=60;i+1<bars.size();i+=stride) {
            final int scan=i;
            var prefix=bars.subList(Math.max(0,i-179),i+1);
            double atr=TradeRiskPolicy.atr(prefix,prefix.size()-1,14), entry=bars.get(i+1).getOpenPrice();
            var enriched=enrichment.enrich(prefix,prefix.size(),interval);
            for(var signal:candlesDetector.detectFactory(enriched,interval)) {
                if(!Objects.equals(signal.candleTimestamp(),bars.get(i).getTimestamp())) continue;
                int count=CandlestickSignalLifecyclePolicy.patternCandleCount(signal.pattern());
                if(count==0) continue;
                if(CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern())) continue; // separate next-bar gate not approximated
                double stop=CandlestickSignalLifecyclePolicy.structuralStopPrice(signal.pattern(),prefix.subList(prefix.size()-count,prefix.size()));
                compare("CANDLE",interval,bars,i+1,signal.tradeSignal(),()->{
                    var p=CandlestickSignalLifecyclePolicy.tradePlan(signal.tradeSignal(),entry,stop,interval,2,atr,
                            CandlestickPatternPreferencesService.factoryPreferences().circuitBreaker(interval),prefix,prefix.size()-1);
                    return new Plan(p.stopLossPrice(),p.profitTargetPrice(),p.timeStopCandles(),p.actionable());
                },()->{
                    var p=BaselineCandlestickPolicy.tradePlan(signal.tradeSignal(),entry,stop,interval,2,
                            BaselineCandlestickPolicy.averageTrueRange(prefix,prefix.size()-1,14),CandlestickPatternPreferencesService.factoryPreferences().circuitBreaker(interval));
                    return new Plan(p.stopLossPrice(),p.profitTargetPrice(),8,true);
                });
            }
            for(var formation:harmonic.detectHistorical(prefix)) {
                if(formation.confirmationTimestamp()<bars.get(Math.max(0,i-stride+1)).getTimestamp()) continue;
                if(!seen.add("H"+formation.pattern()+formation.points())) continue;
                compare("HARMONIC",interval,bars,i+1,formation.tradeSignal(),()->HarmonicStopPlanPolicy.calculate(formation,entry,atr,interval)
                        .map(p->new Plan(p.stopLossPrice(),p.primaryTarget(),p.horizon(),p.actionable())).orElse(null),
                        ()->BaselineHarmonicPolicy.calculate(formation,entry).map(p->new Plan(p.stopLossPrice(),null,8,true)).orElse(null));
            }
            var ew=enrichment.enrichForElliott(prefix,prefix.size(),interval);
            for(var formation:elliott.findHistoricalWaveStructures(ew)) {
                if(formation.confirmationTimestamp()==null || formation.confirmationTimestamp()<bars.get(Math.max(0,i-stride+1)).getTimestamp()) continue;
                if(!seen.add("E"+formation.points())) continue;
                var stage=formation.correctionComplete()?ElliottSignalStage.CORRECTION_END:ElliottSignalStage.WAVE_V_END;
                boolean buy="BULLISH".equals(formation.direction()) == formation.correctionComplete();
                var side=buy?TradeSignal.BUY:TradeSignal.SELL;
                compare("ELLIOTT",interval,bars,i+1,side,()->ElliottTradePlanPolicy.calculate(stage,formation.direction(),side,entry,formation.points(),atr,interval)
                        .map(p->new Plan(p.stopLossPrice(),p.targetTriggerPrice(),p.horizon(),p.actionable())).orElse(null),
                        ()->BaselineElliottPolicy.calculate(stage,formation.direction(),side,entry,formation.points(),atr,interval)
                                .map(p->new Plan(p.stopLossPrice(),p.targetTriggerPrice(),TradeRiskPolicy.profile(interval).elliottHorizon(),p.actionable())).orElse(null));
            }
        }
    }
    void compare(String family,TimeInterval interval,List<Candle> bars,int entryIndex,TradeSignal side,
                 java.util.function.Supplier<Plan> refined,java.util.function.Supplier<Plan> baseline) {
        for(boolean current:List.of(false,true)) {
            String cohort=Instant.ofEpochSecond(bars.get(entryIndex).getTimestamp()).atZone(ZoneOffset.UTC).getYear()<2019?"development":"evaluation";
            Stats v=stats.computeIfAbsent(family+" / "+interval+" / "+cohort+" / "+(current?"refined":"baseline"),k->new Stats());
            v.proposals++; Plan p;
            try {p=(current?refined:baseline).get();} catch(IllegalArgumentException|IllegalStateException e) {v.rejected++;continue;}
            if(p==null || !p.qualified()) {v.rejected++;continue;}
            if(entryIndex+p.horizon()>bars.size()) {v.incomplete++;continue;}
            double entry=bars.get(entryIndex).getOpenPrice(),risk=TradeRiskPolicy.risk(side,entry,p.stop());
            if(risk<=0) {v.rejected++;continue;}
            TradeOutcomePolicy.Outcome result=null;
            for(int j=entryIndex;j<entryIndex+p.horizon();j++) {
                result=TradeOutcomePolicy.evaluate(side,p.stop(),p.target(),bars.get(j)); if(result!=null)break;
            }
            if(result==null) result=new TradeOutcomePolicy.Outcome(TradeOutcomePolicy.Kind.TIME_STOPPED,bars.get(entryIndex+p.horizon()-1).getClosePrice(),"Horizon");
            double net=TradeRiskPolicy.reward(side,entry,result.price())-.001*(entry+result.price());
            v.record(net/risk,result.kind());
        }
    }
    static List<Candle> aggregate(List<Candle> daily,TimeInterval interval) {
        if(interval==TimeInterval.DAILY)return daily;
        Map<LocalDate,List<Candle>> groups=new TreeMap<>();
        for(Candle c:daily) {
            LocalDate date=Instant.ofEpochSecond(c.getTimestamp()).atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate key=interval==TimeInterval.WEEKLY?date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)):date.withDayOfMonth(1);
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(c);
        }
        var result=new ArrayList<Candle>();
        groups.forEach((date,cs)->result.add(new Candle(cs.getFirst().getSymbol(),interval.analysisApiValue(),date.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                cs.getFirst().getOpenPrice(),cs.stream().mapToDouble(Candle::getHighPrice).max().orElseThrow(),cs.stream().mapToDouble(Candle::getLowPrice).min().orElseThrow(),
                cs.getLast().getClosePrice(),cs.stream().mapToLong(Candle::getVolume).sum())));
        // The cached series can end in an incomplete week/month; never treat its last aggregate as completed.
        if(!result.isEmpty())result.removeLast();
        return result;
    }
}
