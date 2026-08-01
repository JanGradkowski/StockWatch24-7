package org.example.stockwatch247.service.insider;

import org.example.stockwatch247.model.enums.InsiderTradeType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface InsiderTradeProvider {
    String providerName();

    List<ProviderTrade> fetchTickerTrades(String ticker);

    record ProviderTrade(
            String ticker,
            String insiderName,
            String ownerRole,
            InsiderTradeType transactionType,
            String transactionCode,
            LocalDate transactionDate,
            LocalDate filingDate,
            BigDecimal shares,
            BigDecimal transactionPrice,
            BigDecimal securitiesOwned,
            String securityName,
            String sourceUrl) {
    }
}
