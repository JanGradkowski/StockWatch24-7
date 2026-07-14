package org.example.stockwatch247.model;
import jakarta.persistence.*;

@Entity
@Table(name = "candles", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"symbol", "time_interval", "timestamp"})
})
public class Candle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "time_interval",nullable = false)
    private String timeInterval;

    @Column(nullable = false)
    private Long timestamp;

    private Double openPrice;
    private Double highPrice;
    private Double lowPrice;
    private Double closePrice;
    private Long volume;

    public Candle(){}

    public Candle(String symbol, String timeInterval, Long timestamp, double openPrice, double highPrice, double lowPrice, Double closePrice, Long volume) {
        this.symbol = symbol;
        this.timestamp = timestamp;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
        this.volume = volume;
        this.timeInterval  = timeInterval;
    }

    public Double getLowPrice() {
        return lowPrice;
    }

    public Double getHighPrice() {
        return highPrice;
    }

    public Double getClosePrice() {
        return closePrice;
    }

    public Long getVolume() {
        return volume;
    }
    public String getSymbol() {
        return symbol;
    }
    public String getTimeInterval() {
        return timeInterval;
    }
    public Long getTimestamp() {
        return timestamp;
    }
    public Double getOpenPrice() {
        return openPrice;
    }
    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public void setOpenPrice(Double openPrice) {
        this.openPrice = openPrice;
    }

    public void setLowPrice(Double lowPrice) {
        this.lowPrice = lowPrice;
    }

    public void setClosePrice(Double closePrice) {
        this.closePrice = closePrice;
    }

    public void setHighPrice(Double highPrice) {
        this.highPrice = highPrice;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }

    public void setTimeInterval(String timeInterval) {
        this.timeInterval = timeInterval;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

}
