package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The new signal emails of one stock's check, on the thread running it. While a batch is open, each such email is
 * queued held (see EmailOutboxService#enqueueHeld) and noted here; closing the batch hands the notes over, so
 * signals that end at the same point (SamePointSignals) go out as one email and the others are released at once.
 */
public final class SamePointEmailBatch implements AutoCloseable {
    private static final ThreadLocal<SamePointEmailBatch> CURRENT = new ThreadLocal<>();

    /** A held email: the signal it is about and its queue key. */
    public record Held(AlertEvent event, String deduplicationKey) { }

    private final SamePointEmailBatch previous;
    private final Consumer<List<Held>> flush;
    private final List<Held> held = new ArrayList<>();

    private SamePointEmailBatch(Consumer<List<Held>> flush) {
        this.previous = CURRENT.get();
        this.flush = flush;
    }

    static SamePointEmailBatch open(Consumer<List<Held>> flush) {
        SamePointEmailBatch batch = new SamePointEmailBatch(flush);
        CURRENT.set(batch);
        return batch;
    }

    /** The batch open on this thread, or null. */
    static SamePointEmailBatch current() {
        return CURRENT.get();
    }

    void hold(AlertEvent event, String deduplicationKey) {
        if (held.stream().noneMatch(note -> note.deduplicationKey().equals(deduplicationKey))) {
            held.add(new Held(event, deduplicationKey));
        }
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
        if (!held.isEmpty()) flush.accept(List.copyOf(held));
    }
}
