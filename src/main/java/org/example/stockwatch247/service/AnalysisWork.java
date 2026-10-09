package org.example.stockwatch247.service;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Job-scoped durable progress. Scheduled work runs continuously; sliced mode supports restart diagnostics. */
final class AnalysisWork implements AutoCloseable {
    private static final ThreadLocal<AnalysisWork> CURRENT = new ThreadLocal<>();
    final AnalysisCheckpointStore store;
    private final AnalysisWork previous;
    private long deadline;
    private long checkpointInterval;
    private boolean continuous;
    private final java.util.function.LongSupplier clock;
    private AnalysisWork(AnalysisCheckpointStore store, Duration slice, java.util.function.LongSupplier clock) {
        this.store = store; previous = CURRENT.get();
        this.clock = clock; deadline = clock.getAsLong() + slice.toNanos(); CURRENT.set(this);
    }
    static AnalysisWork open(AnalysisCheckpointStore store, Duration slice) { return open(store, slice, System::nanoTime); }
    static AnalysisWork open(AnalysisCheckpointStore store, Duration slice, java.util.function.LongSupplier clock) {
        return new AnalysisWork(store, slice, clock);
    }
    static AnalysisWork current() { return CURRENT.get(); }
    static AnalysisWork continuous(AnalysisCheckpointStore store, Duration checkpointInterval) {
        var work = open(store, checkpointInterval);
        work.continuous = true;
        work.checkpointInterval = checkpointInterval.toNanos();
        return work;
    }
    boolean exhausted() { return !continuous && checkpointDue(); }
    boolean runsContinuously() { return continuous; }
    boolean checkpointDue() { return clock.getAsLong() - deadline >= 0; }
    boolean continueAfterCheckpoint() {
        if (!continuous) return false;
        deadline = clock.getAsLong() + checkpointInterval;
        return true;
    }
    static String key(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static void rethrowInfrastructure(RuntimeException failure) {
        if (failure instanceof AnalysisDeferredException || InfrastructureRecovery.isDatabaseUnavailable(failure)) throw failure;
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
