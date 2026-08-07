package org.example.stockwatch247.model.enums;

/**
 * Additive lifecycle for a detected directional technical signal.
 *
 * <p>DETECTED is the original alert. The remaining values are terminal
 * follow-up outcomes and never replace or suppress that initial alert.</p>
 */
public enum SignalLifecycleStatus {
    DETECTED,
    CONFIRMED,
    INVALIDATED,
    EXPIRED
}
