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
import org.example.stockwatch247.model.enums.InsiderTradeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "insider_trades", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_insider_trade_provider_fingerprint",
                columnNames = {"provider", "provider_fingerprint"})
})
public class InsiderTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_asset_id", nullable = false)
    private StockAsset stockAsset;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_fingerprint", nullable = false, length = 64)
    private String providerFingerprint;

    @Column(name = "ticker_symbol", nullable = false, length = 20)
    private String tickerSymbol;

    @Column(name = "insider_name", nullable = false)
    private String insiderName;

    @Column(name = "owner_role", length = 500)
    private String ownerRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 32)
    private InsiderTradeType transactionType;

    @Column(name = "transaction_code", nullable = false, length = 50)
    private String transactionCode;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "filing_date", nullable = false)
    private LocalDate filingDate;

    @Column(precision = 24, scale = 6)
    private BigDecimal shares;

    @Column(name = "transaction_price", precision = 24, scale = 6)
    private BigDecimal transactionPrice;

    @Column(name = "securities_owned", precision = 24, scale = 6)
    private BigDecimal securitiesOwned;

    @Column(name = "security_name")
    private String securityName;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    public Long getId() { return id; }
    public StockAsset getStockAsset() { return stockAsset; }
    public String getProvider() { return provider; }
    public String getProviderFingerprint() { return providerFingerprint; }
    public String getTickerSymbol() { return tickerSymbol; }
    public String getInsiderName() { return insiderName; }
    public String getOwnerRole() { return ownerRole; }
    public InsiderTradeType getTransactionType() { return transactionType; }
    public String getTransactionCode() { return transactionCode; }
    public LocalDate getTransactionDate() { return transactionDate; }
    public LocalDate getFilingDate() { return filingDate; }
    public BigDecimal getShares() { return shares; }
    public BigDecimal getTransactionPrice() { return transactionPrice; }
    public BigDecimal getSecuritiesOwned() { return securitiesOwned; }
    public String getSecurityName() { return securityName; }
    public String getSourceUrl() { return sourceUrl; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }

    public void setId(Long id) { this.id = id; }
    public void setStockAsset(StockAsset stockAsset) { this.stockAsset = stockAsset; }
    public void setProvider(String provider) { this.provider = provider; }
    public void setProviderFingerprint(String providerFingerprint) { this.providerFingerprint = providerFingerprint; }
    public void setTickerSymbol(String tickerSymbol) { this.tickerSymbol = tickerSymbol; }
    public void setInsiderName(String insiderName) { this.insiderName = insiderName; }
    public void setOwnerRole(String ownerRole) { this.ownerRole = ownerRole; }
    public void setTransactionType(InsiderTradeType transactionType) { this.transactionType = transactionType; }
    public void setTransactionCode(String transactionCode) { this.transactionCode = transactionCode; }
    public void setTransactionDate(LocalDate transactionDate) { this.transactionDate = transactionDate; }
    public void setFilingDate(LocalDate filingDate) { this.filingDate = filingDate; }
    public void setShares(BigDecimal shares) { this.shares = shares; }
    public void setTransactionPrice(BigDecimal transactionPrice) { this.transactionPrice = transactionPrice; }
    public void setSecuritiesOwned(BigDecimal securitiesOwned) { this.securitiesOwned = securitiesOwned; }
    public void setSecurityName(String securityName) { this.securityName = securityName; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
