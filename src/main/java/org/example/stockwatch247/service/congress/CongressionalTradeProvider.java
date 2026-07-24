package org.example.stockwatch247.service.congress;

import org.example.stockwatch247.model.enums.CongressionalTradeType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface CongressionalTradeProvider {

    String providerName();

    ProviderBatch fetchTickerHistory(String ticker);

    ProviderBatch fetchRecentTrades();

    record ProviderBatch(List<ProviderTrade> trades, Instant providerUpdatedAt) {
        public ProviderBatch {
            trades = trades == null ? List.of() : List.copyOf(trades);
        }
    }

    record ProviderTrade(
            String memberName,
            String chamber,
            String ticker,
            CongressionalTradeType transactionType,
            String amountRange,
            LocalDate transactionDate,
            LocalDate disclosureDate,
            String assetName,
            String sourceUrl) {
    }
}
