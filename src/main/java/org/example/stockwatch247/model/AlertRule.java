package org.example.stockwatch247.model;

import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "alert_rules", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "stock_asset_id", "interval", "trade_signal", "pattern_family"})
})
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // A User can have many Alert Rules
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Multiple Alert Rules can point to the same Stock (e.g., AAPL)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_asset_id", nullable = false)
    private StockAsset stockAsset;

    // ALWAYS store Enums as Strings in the DB, not default Integers.
    // If you ever add a new Enum value in the middle, Integers will break your existing data!
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TimeInterval interval;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_pattern", nullable = false, columnDefinition = "varchar(64)")
    private CandlePattern targetPattern = CandlePattern.ANY;

    @Enumerated(EnumType.STRING)
    @Column(name = "pattern_family", columnDefinition = "varchar(32)")
    private AlertPatternFamily patternFamily = AlertPatternFamily.CANDLESTICK;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_signal")
    private TradeSignal tradeSignal = TradeSignal.BUY;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return isActive;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public CandlePattern getTargetPattern() {
        return targetPattern;
    }

    public AlertPatternFamily getPatternFamily() {
        return patternFamily == null ? AlertPatternFamily.CANDLESTICK : patternFamily;
    }

    public StockAsset getStockAsset() {
        return stockAsset;
    }

    public TimeInterval getInterval() {
        return interval;
    }

    public TradeSignal getTradeSignal() {
        return tradeSignal;
    }

    public void setTargetPattern(CandlePattern targetPattern) {
        this.targetPattern = targetPattern;
    }

    public void setPatternFamily(AlertPatternFamily patternFamily) {
        this.patternFamily = patternFamily == null ? AlertPatternFamily.CANDLESTICK : patternFamily;
    }

    public void setTradeSignal(TradeSignal tradeSignal) {
        this.tradeSignal = tradeSignal;
    }
    public void setInterval(TimeInterval interval) {
        this.interval = interval;
    }
    public void setUser(User user) {
        this.user = user;
    }
    public void setStockAsset(StockAsset stockAsset) {
        this.stockAsset = stockAsset;
    }
    public void setIsActive(boolean isActive) {
        this.isActive = isActive;
    }
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setActive(boolean active) {
        isActive = active;
    }
}
