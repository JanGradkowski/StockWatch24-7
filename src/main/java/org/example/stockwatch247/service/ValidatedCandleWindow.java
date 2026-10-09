package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/** Immutable, validated range. Subdivision memo keys hash in constant time. */
final class ValidatedCandleWindow extends AbstractList<EnrichedCandle> implements RandomAccess {
    private final List<EnrichedCandle> root;
    private final int[] hashes, powers;
    private final int[] nextBullish, nextBearish;
    private final int from, to;

    static ValidatedCandleWindow latest(List<EnrichedCandle> input) {
        return input instanceof ValidatedCandleWindow window ? window
                : new ValidatedCandleWindow(PatternCandleIntegrity.enriched(input));
    }

    private ValidatedCandleWindow(List<EnrichedCandle> candles) {
        root = List.copyOf(candles);
        from = 0; to = root.size();
        hashes = new int[to + 1]; powers = new int[to + 1];
        nextBullish = new int[to + 1]; nextBearish = new int[to + 1];
        nextBullish[to] = -1; nextBearish[to] = -1;
        for (int index = to - 1; index >= 0; index--) {
            nextBullish[index] = index > 0 && root.get(index).close() > root.get(index - 1).high()
                    ? index : nextBullish[index + 1];
            nextBearish[index] = index > 0 && root.get(index).close() < root.get(index - 1).low()
                    ? index : nextBearish[index + 1];
        }
        powers[0] = 1;
        for (int i = 0; i < to; i++) {
            hashes[i + 1] = 31 * hashes[i] + root.get(i).hashCode();
            powers[i + 1] = 31 * powers[i];
        }
    }

    private ValidatedCandleWindow(ValidatedCandleWindow parent, int start, int end) {
        root = parent.root; hashes = parent.hashes; powers = parent.powers;
        nextBullish = parent.nextBullish; nextBearish = parent.nextBearish;
        from = parent.from + start; to = parent.from + end;
    }

    @Override public EnrichedCandle get(int index) {
        Objects.checkIndex(index, size());
        return root.get(from + index);
    }
    @Override public int size() { return to - from; }
    Object rootIdentity() { return root; }
    int rootOffset() { return from; }
    int firstConfirmationAfter(int index, boolean bullish) {
        Objects.checkIndex(index, size());
        int found = (bullish ? nextBullish : nextBearish)[from + index + 1];
        return found < 0 || found >= to ? -1 : found - from;
    }
    ValidatedCandleWindow suffixFromStart() { return new ValidatedCandleWindow(this, 0, root.size() - from); }
    @Override public ValidatedCandleWindow subList(int start, int end) {
        Objects.checkFromToIndex(start, end, size());
        return new ValidatedCandleWindow(this, start, end);
    }
    @Override public int hashCode() {
        return powers[size()] + hashes[to] - hashes[from] * powers[size()];
    }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (other instanceof ValidatedCandleWindow window) {
            if (size() != window.size() || hashCode() != window.hashCode()) return false;
            if (root == window.root && from == window.from) return true;
        }
        // Hash collisions and independently supplied/corrected inputs still require exact equality.
        return super.equals(other);
    }

    int lowerBound(long timestamp) {
        int low = 0, high = size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (get(middle).timestamp() < timestamp) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}
