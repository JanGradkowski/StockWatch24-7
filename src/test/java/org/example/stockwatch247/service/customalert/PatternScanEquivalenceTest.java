package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.AnalysisPreferencesService;
import org.example.stockwatch247.service.CandlestickPatternPreferencesService;
import org.example.stockwatch247.service.HistoricalCandlestickService;
import org.example.stockwatch247.service.HistoricalSignalCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards optimisations of the pattern detectors: candlestick scans (with their cross-pattern confluence) and the
 * custom alert pattern facts on real cached stocks must stay byte-for-byte the same. Run once with
 * {@code -DscanEquivalence=record} on the code before a change, then with {@code -DscanEquivalence=compare} after it.
 * The recordings go to target/scan-equivalence.
 */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alerts.email.enabled=false", "custom-alerts.background-enabled=false"})
@EnabledIfSystemProperty(named = "scanEquivalence", matches = "record|compare")
@Transactional
class PatternScanEquivalenceTest {
    @Autowired private HistoricalCandlestickService candlesticks;
    @Autowired private AnalysisPreferencesService analysis;
    @Autowired private CustomAlertFactsLoader facts;
    @Autowired private HistoricalSignalCacheService cache;
    @Autowired private StockAssetRepository assets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    @Test
    void scansAreUnchanged() throws Exception {
        boolean record = "record".equals(System.getProperty("scanEquivalence"));
        Path folder = Path.of("target", "scan-equivalence");
        Files.createDirectories(folder);
        org.assertj.core.api.Assumptions.assumeThat(jdbc.queryForObject("select to_regclass('public.candles') is not null", Boolean.class))
                .as("needs a local database with cached candles").isTrue();
        List<String> symbols = jdbc.queryForList("""
                select c.symbol from public.candles c join public.stock_assets a on a.ticker_symbol = c.symbol
                where c.time_interval = '1d' and c.symbol not like 'ZZ%' group by c.symbol having count(*) >= 500
                order by md5(c.symbol) limit 30
                """, String.class);
        for (String symbol : symbols) {
            jdbc.update("""
                    insert into stock_assets (ticker_symbol, company_name, exchange, currency, instrument_type)
                    select ticker_symbol, company_name, exchange, currency, instrument_type from public.stock_assets p where p.ticker_symbol = ?
                      and not exists (select 1 from stock_assets x where x.ticker_symbol = p.ticker_symbol)
                    """, symbol);
            jdbc.update("""
                    insert into candles (symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at)
                    select symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at
                    from public.candles where symbol = ? and time_interval in ('1d', '60min')
                    """, symbol);
        }
        jdbc.execute("analyze candles, stock_assets");
        var trend = analysis.trendDetectionRules(AnalysisPreferencesService.factoryProfile(TimeInterval.DAILY));
        var definitions = CandlestickPatternPreferencesService.factoryPreferences();
        List<String> differences = new ArrayList<>();
        long started = System.nanoTime();
        facts.configureStore(null);
        try {
            for (String symbol : symbols) {
                StringBuilder output = new StringBuilder();
                for (int lookback : new int[]{60, 300, HistoricalCandlestickService.MAX_LOOKBACK_CANDLES}) {
                    output.append("scanStored ").append(lookback).append('\n')
                            .append(json.writeValueAsString(candlesticks.scanStored(symbol, "1d", lookback, trend, definitions).signals())).append('\n');
                }
                entityManager.flush();
                entityManager.clear();
                StockAsset asset = assets.findByTickerSymbolIgnoreCase(symbol).orElseThrow();
                var data = facts.load(asset, TimeInterval.DAILY, null);
                data.evaluatesLatestOnly(true);
                for (String family : CustomAlertDefinition.FAMILIES) {
                    output.append(family).append('\n').append(json.writeValueAsString(
                            CustomAlertFactsLoader.StoredHits.of(data.hits(family, null)))).append('\n');
                }
                Path file = folder.resolve(symbol.replaceAll("[^A-Za-z0-9]", "_") + ".txt");
                if (record) {
                    Files.writeString(file, output);
                } else {
                    // Compared as JSON trees: a formation's measurements map has no fixed key order between runs.
                    String[] before = Files.readString(file).split("\n");
                    String[] after = output.toString().split("\n");
                    boolean same = before.length == after.length;
                    for (int line = 0; same && line < before.length; line++) {
                        same = line % 2 == 0 ? before[line].equals(after[line]) : json.readTree(before[line]).equals(json.readTree(after[line]));
                    }
                    if (!same) {
                        differences.add(symbol);
                        Files.writeString(folder.resolve(file.getFileName().toString().replace(".txt", ".now.txt")), output);
                    }
                }
            }
        } finally {
            facts.configureStore(cache);
        }
        System.out.printf("SCAN EQUIVALENCE %s: %d stocks in %.1f s%s%n", record ? "recorded" : "compared", symbols.size(),
                (System.nanoTime() - started) / 1e9, differences.isEmpty() ? "" : "; different: " + differences);
        assertThat(differences).as("stocks whose scans changed").isEmpty();
    }
}
