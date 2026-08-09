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
import org.example.stockwatch247.model.enums.VirtualTradeSide;
import org.example.stockwatch247.model.enums.VirtualTradeStatus;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "virtual_trades", uniqueConstraints = {
        @UniqueConstraint(name = "uk_virtual_trade_client_request", columnNames = "client_request_id")
})
public class VirtualTrade {
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
    @Column(nullable = false, length = 8)
    private VirtualTradeSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VirtualTradeStatus status = VirtualTradeStatus.TRACKING;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_interval", nullable = false, length = 24)
    private TimeInterval analysisInterval;

    @Column(name = "entry_price", nullable = false, precision = 24, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "entry_at", nullable = false)
    private Instant entryAt;

    @Column(name = "entry_quote_timestamp", nullable = false)
    private Long entryQuoteTimestamp;

    @Column(name = "entry_candle_timestamp", nullable = false)
    private Long entryCandleTimestamp;

    @Column(name = "entry_quote_source", nullable = false, length = 80)
    private String entryQuoteSource;

    @Column(nullable = false, length = 12)
    private String currency;

    @Column(precision = 24, scale = 8)
    private BigDecimal quantity;

    @Column(name = "notional_value", precision = 24, scale = 8)
    private BigDecimal notionalValue;

    @Column(name = "entry_snapshot", nullable = false, columnDefinition = "text")
    private String entrySnapshot;

    @Column(name = "exit_price", precision = 24, scale = 8)
    private BigDecimal exitPrice;

    @Column(name = "exit_at")
    private Instant exitAt;

    @Column(name = "exit_quote_timestamp")
    private Long exitQuoteTimestamp;

    @Column(name = "exit_quote_source", length = 80)
    private String exitQuoteSource;

    @Column(name = "exit_snapshot", columnDefinition = "text")
    private String exitSnapshot;

    @Column(name = "client_request_id", nullable = false, length = 36)
    private String clientRequestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public User getUser() { return user; }
    public StockAsset getStockAsset() { return stockAsset; }
    public VirtualTradeSide getSide() { return side; }
    public VirtualTradeStatus getStatus() { return status; }
    public TimeInterval getAnalysisInterval() { return analysisInterval; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public Instant getEntryAt() { return entryAt; }
    public Long getEntryQuoteTimestamp() { return entryQuoteTimestamp; }
    public Long getEntryCandleTimestamp() { return entryCandleTimestamp; }
    public String getEntryQuoteSource() { return entryQuoteSource; }
    public String getCurrency() { return currency; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getNotionalValue() { return notionalValue; }
    public String getEntrySnapshot() { return entrySnapshot; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public Instant getExitAt() { return exitAt; }
    public Long getExitQuoteTimestamp() { return exitQuoteTimestamp; }
    public String getExitQuoteSource() { return exitQuoteSource; }
    public String getExitSnapshot() { return exitSnapshot; }
    public String getClientRequestId() { return clientRequestId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setUser(User user) { this.user = user; }
    public void setStockAsset(StockAsset stockAsset) { this.stockAsset = stockAsset; }
    public void setSide(VirtualTradeSide side) { this.side = side; }
    public void setStatus(VirtualTradeStatus status) { this.status = status; }
    public void setAnalysisInterval(TimeInterval analysisInterval) { this.analysisInterval = analysisInterval; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public void setEntryAt(Instant entryAt) { this.entryAt = entryAt; }
    public void setEntryQuoteTimestamp(Long entryQuoteTimestamp) { this.entryQuoteTimestamp = entryQuoteTimestamp; }
    public void setEntryCandleTimestamp(Long entryCandleTimestamp) { this.entryCandleTimestamp = entryCandleTimestamp; }
    public void setEntryQuoteSource(String entryQuoteSource) { this.entryQuoteSource = entryQuoteSource; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public void setNotionalValue(BigDecimal notionalValue) { this.notionalValue = notionalValue; }
    public void setEntrySnapshot(String entrySnapshot) { this.entrySnapshot = entrySnapshot; }
    public void setExitPrice(BigDecimal exitPrice) { this.exitPrice = exitPrice; }
    public void setExitAt(Instant exitAt) { this.exitAt = exitAt; }
    public void setExitQuoteTimestamp(Long exitQuoteTimestamp) { this.exitQuoteTimestamp = exitQuoteTimestamp; }
    public void setExitQuoteSource(String exitQuoteSource) { this.exitQuoteSource = exitQuoteSource; }
    public void setExitSnapshot(String exitSnapshot) { this.exitSnapshot = exitSnapshot; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
