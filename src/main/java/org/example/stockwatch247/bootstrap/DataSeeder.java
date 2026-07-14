package org.example.stockwatch247.bootstrap;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
@Component
public class DataSeeder implements CommandLineRunner {
    private final StockAssetRepository stockAssetRepository;
    public DataSeeder(StockAssetRepository stockAssetRepository) {
        this.stockAssetRepository = stockAssetRepository;
    }
    @Override
    public void run(String... args) {
        System.out.println("--- Ensuring default stock assets exist ---");
        List<StockAsset> defaults = List.of(
                asset("AAPL", "Apple Inc.", "NASDAQ", "USD"),
                asset("TSLA", "Tesla Inc.", "NASDAQ", "USD"),
                asset("MSFT", "Microsoft Corp.", "NASDAQ", "USD"),
                asset("NVDA", "NVIDIA Corp.", "NASDAQ", "USD"),
                asset("AMZN", "Amazon.com Inc.", "NASDAQ", "USD"),
                asset("OTGLF", "CD Projekt Red / CD PROJEKT S.A.", "OTC", "USD")
        );

        defaults.forEach(defaultAsset ->
                stockAssetRepository.findByTickerSymbolIgnoreCase(defaultAsset.getTickerSymbol())
                        .ifPresentOrElse(
                                existing -> {
                                    existing.setCompanyName(defaultAsset.getCompanyName());
                                    existing.setExchange(defaultAsset.getExchange());
                                    existing.setCurrency(defaultAsset.getCurrency());
                                    existing.setInstrumentType(InstrumentType.EQUITY);
                                    stockAssetRepository.save(existing);
                                },
                                () -> stockAssetRepository.save(defaultAsset)
                        )
        );
        System.out.println("--- Default stock asset check complete ---");
    }

    private StockAsset asset(String symbol, String name, String exchange, String currency) {
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol(symbol);
        asset.setCompanyName(name);
        asset.setExchange(exchange);
        asset.setCurrency(currency);
        asset.setInstrumentType(InstrumentType.EQUITY);
        return asset;
    }
}
