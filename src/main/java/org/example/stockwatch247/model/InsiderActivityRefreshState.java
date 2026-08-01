package org.example.stockwatch247.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "insider_activity_refresh_state")
public class InsiderActivityRefreshState {
    @Id
    @Column(name = "stock_asset_id")
    private Long stockAssetId;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    public Long getStockAssetId() { return stockAssetId; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public String getLastError() { return lastError; }

    public void setStockAssetId(Long stockAssetId) { this.stockAssetId = stockAssetId; }
    public void setLastSuccessAt(Instant lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
