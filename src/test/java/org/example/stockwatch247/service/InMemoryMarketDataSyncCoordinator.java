package org.example.stockwatch247.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

final class InMemoryMarketDataSyncCoordinator implements MarketDataSyncCoordinator {
    private final Map<String, State> states = new HashMap<>();
    private final LongSupplier now;

    InMemoryMarketDataSyncCoordinator(LongSupplier now) {
        this.now = now;
    }

    @Override
    public synchronized Claim tryClaim(String symbol, String interval, long cooldownSeconds, long leaseSeconds) {
        long current = now.getAsLong();
        String key = symbol + ':' + interval;
        State state = states.get(key);
        if (state != null && state.lastSuccess() != null
                && current - state.lastSuccess() < cooldownSeconds) {
            return new Claim(symbol, interval, null, ClaimStatus.RECENT_SUCCESS);
        }
        if (state != null && state.owner() != null && state.leaseUntil() > current) {
            return new Claim(symbol, interval, null, ClaimStatus.IN_PROGRESS);
        }
        String owner = UUID.randomUUID().toString();
        states.put(key, new State(state == null ? null : state.lastSuccess(), owner, current + leaseSeconds));
        return new Claim(symbol, interval, owner, ClaimStatus.ACQUIRED);
    }

    @Override
    public synchronized void markSuccessful(Claim claim) {
        states.put(claim.symbol() + ':' + claim.interval(), new State(now.getAsLong(), null, 0));
    }

    @Override
    public synchronized void release(Claim claim) {
        String key = claim.symbol() + ':' + claim.interval();
        State state = states.get(key);
        if (state != null && claim.owner().equals(state.owner())) {
            states.put(key, new State(state.lastSuccess(), null, 0));
        }
    }

    private record State(Long lastSuccess, String owner, long leaseUntil) { }
}
