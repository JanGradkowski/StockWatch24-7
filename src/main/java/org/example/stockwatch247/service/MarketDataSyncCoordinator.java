package org.example.stockwatch247.service;

public interface MarketDataSyncCoordinator {
    Claim tryClaim(String symbol, String interval, long cooldownSeconds, long leaseSeconds);

    void markSuccessful(Claim claim);

    void release(Claim claim);

    enum ClaimStatus {
        ACQUIRED,
        RECENT_SUCCESS,
        IN_PROGRESS
    }

    record Claim(String symbol, String interval, String owner, ClaimStatus status) {
        public boolean acquired() {
            return status == ClaimStatus.ACQUIRED;
        }
    }
}
