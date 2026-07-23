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
import org.example.stockwatch247.model.enums.MarketDataProvider;

import java.time.Instant;

@Entity
@Table(name = "asset_provider_symbols", uniqueConstraints =
        @UniqueConstraint(name = "uk_asset_provider", columnNames = {"stock_asset_id", "provider"}))
public class ProviderSymbolAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_asset_id", nullable = false)
    private StockAsset stockAsset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MarketDataProvider provider;

    @Column(name = "provider_symbol", nullable = false, length = 64)
    private String providerSymbol;

    @Column(name = "mic_code", length = 12)
    private String micCode;

    @Column(name = "resolution_source", nullable = false, length = 32)
    private String resolutionSource;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    public Long getId() {
        return id;
    }

    public StockAsset getStockAsset() {
        return stockAsset;
    }

    public MarketDataProvider getProvider() {
        return provider;
    }

    public String getProviderSymbol() {
        return providerSymbol;
    }

    public String getMicCode() {
        return micCode;
    }

    public String getResolutionSource() {
        return resolutionSource;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }
}
