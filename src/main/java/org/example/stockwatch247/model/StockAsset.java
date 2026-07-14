package org.example.stockwatch247.model;

import jakarta.persistence.*;
import org.example.stockwatch247.model.enums.InstrumentType;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stock_assets")
public class StockAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "stockAsset", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CandleData> historicalData = new ArrayList<>();

    @Column(name = "ticker_symbol", nullable = false, unique = true, length = 20)
    private String tickerSymbol; // e.g., "AAPL"

    @Column(name = "company_name", nullable = false)
    private String companyName; // e.g., "Apple Inc."

    @Column(nullable = false)
    private String exchange; // e.g., "NASDAQ", "NYSE"

    // --- NEW FIELD ---
    @Column(name = "currency", length = 10)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", nullable = false, length = 20)
    private InstrumentType instrumentType = InstrumentType.EQUITY;

    // Getters, Setters, and Constructors omitted

    public Long getId() {
        return id;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getExchange() {
        return exchange;
    }

    public String getTickerSymbol() {
        return tickerSymbol;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public void setTickerSymbol(String tickerSymbol) {
        this.tickerSymbol = tickerSymbol;
    }

    public List<CandleData> getHistoricalData() {
        return historicalData;
    }

    public void setHistoricalData(List<CandleData> historicalData) {
        this.historicalData = historicalData;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public InstrumentType getInstrumentType() {
        return instrumentType;
    }

    public void setInstrumentType(InstrumentType instrumentType) {
        this.instrumentType = instrumentType == null ? InstrumentType.EQUITY : instrumentType;
    }
}
