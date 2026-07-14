package org.example.stockwatch247.model;

import org.example.stockwatch247.model.enums.TimeInterval;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "candle_data", uniqueConstraints = {
        // A stock can only have ONE candle for a specific time and interval combination.
        // This prevents duplicate data if your scheduled task accidentally runs twice!
        @UniqueConstraint(columnNames = {"stock_asset_id", "interval", "candle_timestamp"})
})
public class CandleData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Which stock does this candle belong to?
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_asset_id", nullable = false)
    private StockAsset stockAsset;

    // Is this a 15-minute candle? A daily candle?
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TimeInterval interval;

    // The exact time this specific candle opened/closed
    @Column(name = "candle_timestamp", nullable = false)
    private LocalDateTime candleTimestamp;

    // The core OHLCV financial data
    @Column(nullable = false)
    private Double openPrice;

    @Column(nullable = false)
    private Double highPrice;

    @Column(nullable = false)
    private Double lowPrice;

    @Column(nullable = false)
    private Double closePrice;

    @Column(nullable = false)
    private Long volume;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public StockAsset getStockAsset() {
        return stockAsset;
    }

    public TimeInterval getInterval() {
        return interval;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Double getClosePrice() {
        return closePrice;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Double getHighPrice() {
        return highPrice;
    }

    public Double getLowPrice() {
        return lowPrice;
    }
    public Double getOpenPrice() {
        return openPrice;
    }
    public LocalDateTime getCandleTimestamp() {
        return candleTimestamp;
    }
    public void setCandleTimestamp(LocalDateTime candleTimestamp) {
        this.candleTimestamp = candleTimestamp;
    }

    public Long getVolume() {
        return volume;
    }

    public void setHighPrice(Double highPrice) {
        this.highPrice = highPrice;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setClosePrice(Double closePrice) {
        this.closePrice = closePrice;
    }

    public void setInterval(TimeInterval interval) {
        this.interval = interval;
    }

    public void setLowPrice(Double lowPrice) {
        this.lowPrice = lowPrice;
    }

    public void setOpenPrice(Double openPrice) {
        this.openPrice = openPrice;
    }

    public void setStockAsset(StockAsset stockAsset) {
        this.stockAsset = stockAsset;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }

}