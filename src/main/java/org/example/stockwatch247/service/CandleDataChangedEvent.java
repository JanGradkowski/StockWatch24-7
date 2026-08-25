package org.example.stockwatch247.service;

/** Emitted after a completed candle batch changes the durable cache. */
public record CandleDataChangedEvent(String symbol, String interval, int changedCandles) { }
