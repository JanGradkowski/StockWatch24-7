package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Uses cached daily bars only when they reconcile to the completed parent bar. No market-data fetch. */
@Service
public class TradeExecutionService {
    private final CandleRepository repository;
    public TradeExecutionService(CandleRepository repository) { this.repository = repository; }
    TradeOutcomePolicy.Outcome evaluate(String symbol, TimeInterval interval, TradeSignal side,
                                         double stop, Double target, Candle parent) {
        var coarse = TradeOutcomePolicy.evaluate(side, stop, target, parent);
        if (coarse == null || interval == TimeInterval.DAILY || parent.getTimestamp() == null) return coarse;
        var start = Instant.ofEpochSecond(parent.getTimestamp()).atZone(ZoneOffset.UTC);
        long end = (interval == TimeInterval.WEEKLY ? start.plusWeeks(1) : start.plusMonths(1)).toEpochSecond();
        List<Candle> daily = AnalysisComputationScope.memo(List.of("trade-execution", symbol, parent.getTimestamp(), end),
                () -> repository.findBySymbolAndTimeIntervalAndTimestampGreaterThanAndTimestampLessThanOrderByTimestampAsc(
                        symbol, "1d", parent.getTimestamp() - 1, end, org.springframework.data.domain.PageRequest.of(0, 40)));
        if (!reconciles(parent, daily)) return coarse;
        for (Candle bar : daily) {
            var outcome = TradeOutcomePolicy.evaluate(side, stop, target, bar);
            if (outcome != null) return new TradeOutcomePolicy.Outcome(outcome.kind(), outcome.price(),
                    "Resolved with reconciled daily bars. " + outcome.reason());
        }
        return coarse;
    }
    static boolean reconciles(Candle parent, List<Candle> bars) {
        if (!TradeRiskPolicy.valid(parent) || bars == null || bars.size() < 2
                || parent.getVolume() == null || parent.getVolume() <= 0
                || bars.stream().anyMatch(c -> !TradeRiskPolicy.valid(c) || c.getVolume() == null || c.getVolume() < 0)) return false;
        long volume = bars.stream().mapToLong(Candle::getVolume).sum();
        return volume == parent.getVolume()
                && near(parent.getOpenPrice(), bars.getFirst().getOpenPrice())
                && near(parent.getClosePrice(), bars.getLast().getClosePrice())
                && near(parent.getHighPrice(), bars.stream().mapToDouble(Candle::getHighPrice).max().orElseThrow())
                && near(parent.getLowPrice(), bars.stream().mapToDouble(Candle::getLowPrice).min().orElseThrow());
    }
    private static boolean near(double a, double b) { return Math.abs(a-b) <= Math.max(.000001, a * .000001); }
}
