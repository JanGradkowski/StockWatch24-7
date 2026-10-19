package org.example.stockwatch247.service.customalert;

/** Stocks an alert covers, or its rule, changed: baselines are due as soon as the change is committed. */
public record CustomAlertsChanged(long alertId) { }
