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
import org.example.stockwatch247.model.enums.InsiderDeliveryStatus;

import java.time.Instant;

@Entity
@Table(name = "insider_trade_deliveries", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_insider_delivery_subscription_trade",
                columnNames = {"subscription_id", "trade_id"})
})
public class InsiderTradeDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private InsiderTradeSubscription subscription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trade_id", nullable = false)
    private InsiderTrade trade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InsiderDeliveryStatus status;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public InsiderTradeSubscription getSubscription() { return subscription; }
    public InsiderTrade getTrade() { return trade; }
    public InsiderDeliveryStatus getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setSubscription(InsiderTradeSubscription subscription) { this.subscription = subscription; }
    public void setTrade(InsiderTrade trade) { this.trade = trade; }
    public void setStatus(InsiderDeliveryStatus status) { this.status = status; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
