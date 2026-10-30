package org.example.stockwatch247.model;

import jakarta.persistence.*;
import org.example.stockwatch247.model.enums.InstrumentType;


@Entity
@Table(name = "stock_assets")
public class StockAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticker_symbol", nullable = false, unique = true, length = 20)
    private String tickerSymbol; // e.g., "AAPL"

    @Column(name = "company_name", nullable = false)
    private String companyName; // e.g., "Apple Inc."

    @Column(nullable = false)
    private String exchange; // e.g., "NASDAQ", "NYSE"

    @Column(name = "mic_code", length = 12)
    private String micCode;

    @Column(length = 100)
    private String country;

    @Column(length = 32)
    private String figi;

    @Column(length = 16)
    private String isin;

    // --- NEW FIELD ---
    @Column(name = "currency", length = 10)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", nullable = false, length = 20)
    private InstrumentType instrumentType = InstrumentType.EQUITY;

    /** Set once a provider has returned real candles for this listing. */
    @Column(name = "market_data_verified_at")
    private java.time.Instant marketDataVerifiedAt;

    public java.time.Instant getMarketDataVerifiedAt() {
        return marketDataVerifiedAt;
    }

    public void setMarketDataVerifiedAt(java.time.Instant marketDataVerifiedAt) {
        this.marketDataVerifiedAt = marketDataVerifiedAt;
    }

    @Column(name = "metadata_refreshed_at")
    private java.time.Instant metadataRefreshedAt;

    public java.time.Instant getMetadataRefreshedAt() {
        return metadataRefreshedAt;
    }

    public void setMetadataRefreshedAt(java.time.Instant metadataRefreshedAt) {
        this.metadataRefreshedAt = metadataRefreshedAt;
    }

    /** A provider has supplied a real name and venue, not the placeholder created for an unknown ticker. */
    public boolean hasConfirmedListing() {
        return exchange != null && !exchange.isBlank() && !"UNKNOWN".equalsIgnoreCase(exchange)
                && hasRealName(this);
    }

    /** The stored name is a company's name, not the ticker that stands in for one until a provider names it. */
    public static boolean hasRealName(StockAsset asset) {
        String name = asset == null ? null : asset.companyName;
        return name != null && !name.isBlank() && !name.equalsIgnoreCase(asset.tickerSymbol);
    }

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

    public String getMicCode() {
        return micCode;
    }

    public void setMicCode(String micCode) {
        this.micCode = micCode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getFigi() {
        return figi;
    }

    public void setFigi(String figi) {
        this.figi = figi;
    }

    public String getIsin() {
        return isin;
    }

    public void setIsin(String isin) {
        this.isin = isin;
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
