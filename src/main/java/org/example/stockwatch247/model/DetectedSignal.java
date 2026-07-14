package org.example.stockwatch247.model;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.model.enums.SignalStength;
public record DetectedSignal(
        CandlePattern pattern,
        TradeSignal tradeSignal,
        SignalStength strength,
        Long timestamp,
        Double closePrice
) {}