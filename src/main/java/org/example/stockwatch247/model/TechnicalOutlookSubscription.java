package org.example.stockwatch247.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.example.stockwatch247.model.enums.TimeInterval;

import java.time.LocalDateTime;

@Entity
@Table(name = "technical_outlook_subscriptions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "stock_asset_id", "interval"})
})
public class TechnicalOutlookSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_asset_id", nullable = false)
    private StockAsset stockAsset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TimeInterval interval;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_candle_timestamp")
    private Long lastCandleTimestamp;

    @Column(name = "baseline_snapshot", columnDefinition = "text")
    private String baselineSnapshot;

    @Column(name = "profile_fingerprint", length = 64)
    private String profileFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public User getUser() { return user; }
    public StockAsset getStockAsset() { return stockAsset; }
    public TimeInterval getInterval() { return interval; }
    public boolean isActive() { return active; }
    public Long getLastCandleTimestamp() { return lastCandleTimestamp; }
    public String getBaselineSnapshot() { return baselineSnapshot; }
    public String getProfileFingerprint() { return profileFingerprint; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setUser(User user) { this.user = user; }
    public void setStockAsset(StockAsset stockAsset) { this.stockAsset = stockAsset; }
    public void setInterval(TimeInterval interval) { this.interval = interval; }
    public void setActive(boolean active) { this.active = active; }
    public void setLastCandleTimestamp(Long value) { this.lastCandleTimestamp = value; }
    public void setBaselineSnapshot(String value) { this.baselineSnapshot = value; }
    public void setProfileFingerprint(String value) { this.profileFingerprint = value; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
    public void setUpdatedAt(LocalDateTime value) { this.updatedAt = value; }
}

