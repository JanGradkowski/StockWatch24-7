package org.example.stockwatch247.service;

import java.util.function.IntToDoubleFunction;

/** Immutable range extrema: O(n log n) preparation, O(1) open-leg checks. */
final class PriceRangeIndex {
    private final double[][] lows, highs;
    PriceRangeIndex(int size, IntToDoubleFunction low, IntToDoubleFunction high) {
        int levels = size == 0 ? 1 : 32 - Integer.numberOfLeadingZeros(size);
        lows = new double[levels][size]; highs = new double[levels][size];
        for (int i = 0; i < size; i++) { lows[0][i] = low.applyAsDouble(i); highs[0][i] = high.applyAsDouble(i); }
        for (int k = 1; k < levels; k++) for (int i = 0; i + (1 << k) <= size; i++) {
            lows[k][i] = Math.min(lows[k-1][i], lows[k-1][i+(1 << (k-1))]);
            highs[k][i] = Math.max(highs[k-1][i], highs[k-1][i+(1 << (k-1))]);
        }
    }
    boolean contained(int from, int to, double low, double high) {
        if (from >= to) return true;
        int k = 31 - Integer.numberOfLeadingZeros(to-from), last = to-(1 << k);
        return Math.min(lows[k][from], lows[k][last]) >= low && Math.max(highs[k][from], highs[k][last]) <= high;
    }
}
