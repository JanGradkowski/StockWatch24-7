package org.example.stockwatch247.model.enums;

/**
 * Lifecycle for a directional technical setup.
 *
 * <p>One-candle reversals begin as POTENTIAL and must pass an immediate
 * next-candle gate before becoming DETECTED. Other signal families continue
 * to begin at DETECTED.</p>
 */
public enum SignalLifecycleStatus {
    POTENTIAL,
    DETECTED,
    REJECTED,
    CONFIRMED,
    INVALIDATED,
    EXPIRED
}
