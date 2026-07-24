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
import org.example.stockwatch247.model.enums.CongressionalTradeType;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "congressional_trades", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_congressional_trade_provider_fingerprint",
                columnNames = {"provider", "provider_fingerprint"})
})
public class CongressionalTrade {

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

    @Column(name = "member_name", nullable = false)
    private String memberName;

    @Column(nullable = false, length = 32)
    private String chamber;

    @Column(name = "ticker_symbol", nullable = false, length = 20)
    private String tickerSymbol;

    @Column(name = "asset_name", length = 500)
    private String assetName;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 32)
    private CongressionalTradeType transactionType;

    @Column(name = "amount_range", nullable = false, length = 100)
    private String amountRange;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "disclosure_date", nullable = false)
    private LocalDate disclosureDate;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    public Long getId() {
        return id;
    }

    public StockAsset getStockAsset() {
        return stockAsset;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderFingerprint() {
        return providerFingerprint;
    }

    public String getMemberName() {
        return memberName;
    }

    public String getChamber() {
        return chamber;
    }

    public String getTickerSymbol() {
        return tickerSymbol;
    }

    public String getAssetName() {
        return assetName;
    }

    public CongressionalTradeType getTransactionType() {
        return transactionType;
    }

    public String getAmountRange() {
        return amountRange;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public LocalDate getDisclosureDate() {
        return disclosureDate;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setStockAsset(StockAsset stockAsset) {
        this.stockAsset = stockAsset;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setProviderFingerprint(String providerFingerprint) {
        this.providerFingerprint = providerFingerprint;
    }

    public void setMemberName(String memberName) {
        this.memberName = memberName;
    }

    public void setChamber(String chamber) {
        this.chamber = chamber;
    }

    public void setTickerSymbol(String tickerSymbol) {
        this.tickerSymbol = tickerSymbol;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    public void setTransactionType(CongressionalTradeType transactionType) {
        this.transactionType = transactionType;
    }

    public void setAmountRange(String amountRange) {
        this.amountRange = amountRange;
    }

    public void setTransactionDate(LocalDate transactionDate) {
        this.transactionDate = transactionDate;
    }

    public void setDisclosureDate(LocalDate disclosureDate) {
        this.disclosureDate = disclosureDate;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
