package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TradeSignal;

import java.util.List;

/**
 * Locates the visible directional leg inside the wider context consumed by a
 * candlestick trend detector. Detection still uses its complete configured context.
 */
final class CandlestickTrendLegLocator {
    private static final int MINIMUM_VISIBLE_LEG_CANDLES = 3;
    private static final int PIVOT_SIDE_CANDLES = 2;

    private CandlestickTrendLegLocator() {
    }

    static int locateStartIndex(List<Candle> candles,
                                int contextStartIndex,
                                int patternStartIndex,
                                TradeSignal signalDirection) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("Candles are required to locate a trend leg.");
        }
        if (signalDirection == null) {
            throw new IllegalArgumentException("Signal direction is required to locate a trend leg.");
        }

        int trendEndIndex = Math.min(patternStartIndex - 1, candles.size() - 1);
        int firstContextIndex = Math.clamp(contextStartIndex, 0, Math.max(0, trendEndIndex));
        if (trendEndIndex <= firstContextIndex) {
            return firstContextIndex;
        }

        int structuralStart = confirmedStructureStart(
                candles, firstContextIndex, trendEndIndex, signalDirection);
        if (structuralStart >= 0) {
            return structuralStart;
        }

        // Fixed-window/custom policies do not necessarily have confirmed
        // pivots, so retain the directional-extreme fallback for those charts.
        int lastTurningPointIndex = Math.max(
                firstContextIndex,
                trendEndIndex - MINIMUM_VISIBLE_LEG_CANDLES + 1
        );
        int turningPointIndex = firstContextIndex;
        double extremePrice = turningPointPrice(candles.get(firstContextIndex), signalDirection);
        for (int index = firstContextIndex + 1; index <= lastTurningPointIndex; index++) {
            double candidatePrice = turningPointPrice(candles.get(index), signalDirection);
            if (!Double.isFinite(candidatePrice)) continue;
            boolean isMoreDirectionalExtreme = signalDirection == TradeSignal.SELL
                    ? candidatePrice <= extremePrice
                    : candidatePrice >= extremePrice;
            if (isMoreDirectionalExtreme) {
                turningPointIndex = index;
                extremePrice = candidatePrice;
            }
        }
        return turningPointIndex;
    }

    private static int confirmedStructureStart(List<Candle> candles,
                                               int contextStart,
                                               int trendEnd,
                                               TradeSignal signalDirection) {
        List<Integer> highs = new java.util.ArrayList<>();
        List<Integer> lows = new java.util.ArrayList<>();
        int firstCenter = Math.max(contextStart + PIVOT_SIDE_CANDLES, PIVOT_SIDE_CANDLES);
        int lastCenter = trendEnd - PIVOT_SIDE_CANDLES;
        for (int center = firstCenter; center <= lastCenter; center++) {
            if (isPivotHigh(candles, center)) highs.add(center);
            if (isPivotLow(candles, center)) lows.add(center);
        }
        if (highs.size() < 2 || lows.size() < 2) return -1;

        int previousHigh = highs.get(highs.size() - 2);
        int latestHigh = highs.getLast();
        int previousLow = lows.get(lows.size() - 2);
        int latestLow = lows.getLast();
        boolean requiredStructure = signalDirection == TradeSignal.SELL
                ? candles.get(latestHigh).getHighPrice() > candles.get(previousHigh).getHighPrice()
                    && candles.get(latestLow).getLowPrice() > candles.get(previousLow).getLowPrice()
                : candles.get(latestHigh).getHighPrice() < candles.get(previousHigh).getHighPrice()
                    && candles.get(latestLow).getLowPrice() < candles.get(previousLow).getLowPrice();
        return requiredStructure ? Math.min(previousHigh, previousLow) : -1;
    }

    private static boolean isPivotHigh(List<Candle> candles, int center) {
        double high = candles.get(center).getHighPrice();
        for (int index = center - PIVOT_SIDE_CANDLES;
             index <= center + PIVOT_SIDE_CANDLES; index++) {
            if (index != center && high <= candles.get(index).getHighPrice()) return false;
        }
        return true;
    }

    private static boolean isPivotLow(List<Candle> candles, int center) {
        double low = candles.get(center).getLowPrice();
        for (int index = center - PIVOT_SIDE_CANDLES;
             index <= center + PIVOT_SIDE_CANDLES; index++) {
            if (index != center && low >= candles.get(index).getLowPrice()) return false;
        }
        return true;
    }

    private static double turningPointPrice(Candle candle, TradeSignal signalDirection) {
        if (candle == null) return Double.NaN;
        // A SELL reversal needs an uptrend, starting at a swing low. A BUY
        // reversal needs a downtrend, starting at a swing high.
        return signalDirection == TradeSignal.SELL
                ? candle.getLowPrice()
                : candle.getHighPrice();
    }
}
