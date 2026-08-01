package org.example.stockwatch247.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "insider_trade_subscriptions", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_insider_subscription_user_asset",
                columnNames = {"user_id", "stock_asset_id"})
})
public class InsiderTradeSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_asset_id", nullable = false)
    private StockAsset stockAsset;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "baseline_completed_at")
    private Instant baselineCompletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public User getUser() { return user; }
    public StockAsset getStockAsset() { return stockAsset; }
    public boolean isActive() { return active; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getBaselineCompletedAt() { return baselineCompletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setUser(User user) { this.user = user; }
    public void setStockAsset(StockAsset stockAsset) { this.stockAsset = stockAsset; }
    public void setActive(boolean active) { this.active = active; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
    public void setBaselineCompletedAt(Instant baselineCompletedAt) { this.baselineCompletedAt = baselineCompletedAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
