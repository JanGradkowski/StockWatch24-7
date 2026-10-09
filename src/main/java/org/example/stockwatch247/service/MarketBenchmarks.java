package org.example.stockwatch247.service;

import org.example.stockwatch247.model.StockAsset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The broad-market index each listing is compared with for relative strength. Matched by the
 * exchange MIC first, then the exchange name, then the country, then the currency. Every symbol here
 * was checked to have daily history from the market-data providers; Warsaw uses an ETF tracking
 * WIG20 total return because the index itself has no daily history there.
 */
final class MarketBenchmarks {
    record MarketBenchmark(String symbol, String name, boolean local) {
    }

    private static final MarketBenchmark SP500 = local("^GSPC", "S&P 500");
    static final MarketBenchmark FALLBACK = new MarketBenchmark("^GSPC", "S&P 500 (no local benchmark)", false);

    private static final MarketBenchmark FTSE = local("^FTSE", "FTSE 100");
    private static final MarketBenchmark DAX = local("^GDAXI", "DAX Performance Index");
    private static final MarketBenchmark CAC = local("^FCHI", "CAC 40");
    private static final MarketBenchmark NIKKEI = local("^N225", "Nikkei 225");
    private static final MarketBenchmark HANG_SENG = local("^HSI", "Hang Seng Index");
    private static final MarketBenchmark EURO_STOXX = local("^STOXX50E", "EURO STOXX 50");
    private static final MarketBenchmark WIG20 = local("ETFBW20TR.WA", "WIG20 total return (Beta ETF)");
    private static final MarketBenchmark TSX = local("^GSPTSE", "S&P/TSX Composite");
    private static final MarketBenchmark ASX = local("^AXJO", "S&P/ASX 200");
    private static final MarketBenchmark SMI = local("^SSMI", "Swiss Market Index");
    private static final MarketBenchmark OMX = local("^OMX", "OMX Stockholm 30");
    private static final MarketBenchmark AEX = local("^AEX", "AEX");
    private static final MarketBenchmark BEL20 = local("^BFX", "BEL 20");
    private static final MarketBenchmark FTSE_MIB = local("FTSEMIB.MI", "FTSE MIB");
    private static final MarketBenchmark IBEX = local("^IBEX", "IBEX 35");
    private static final MarketBenchmark OMXC25 = local("^OMXC25", "OMX Copenhagen 25");
    private static final MarketBenchmark OMXH25 = local("^OMXH25", "OMX Helsinki 25");
    private static final MarketBenchmark ATX = local("^ATX", "ATX");
    private static final MarketBenchmark PSI = local("PSI20.LS", "PSI");
    private static final MarketBenchmark ISEQ = local("^ISEQ", "ISEQ All Share");

    private static final Map<String, MarketBenchmark> BY_MIC = new LinkedHashMap<>();
    private static final Map<String, MarketBenchmark> BY_EXCHANGE = new LinkedHashMap<>();
    private static final Map<String, MarketBenchmark> BY_COUNTRY = new LinkedHashMap<>();
    private static final Map<String, MarketBenchmark> BY_CURRENCY = new LinkedHashMap<>();

    static {
        for (String mic : List.of("XNYS", "XNAS", "ARCX", "BATS", "XASE", "IEXG")) BY_MIC.put(mic, SP500);
        BY_MIC.put("XLON", FTSE);
        BY_MIC.put("XETR", DAX);
        BY_MIC.put("XFRA", DAX);
        BY_MIC.put("XPAR", CAC);
        BY_MIC.put("XTKS", NIKKEI);
        BY_MIC.put("XJPX", NIKKEI);
        BY_MIC.put("XHKG", HANG_SENG);
        BY_MIC.put("XWAR", WIG20);
        BY_MIC.put("XTSE", TSX);
        BY_MIC.put("XTSX", TSX);
        BY_MIC.put("XASX", ASX);
        BY_MIC.put("XSWX", SMI);
        BY_MIC.put("XVTX", SMI);
        BY_MIC.put("XSTO", OMX);
        BY_MIC.put("XAMS", AEX);
        BY_MIC.put("XBRU", BEL20);
        BY_MIC.put("XMIL", FTSE_MIB);
        BY_MIC.put("XMAD", IBEX);
        BY_MIC.put("BMEX", IBEX);
        BY_MIC.put("XCSE", OMXC25);
        BY_MIC.put("XHEL", OMXH25);
        BY_MIC.put("XWBO", ATX);
        BY_MIC.put("XLIS", PSI);
        BY_MIC.put("XDUB", ISEQ);

        for (String name : List.of("NYSE", "NASDAQ", "NYSE ARCA", "AMEX", "NYSE AMERICAN", "CBOE")) BY_EXCHANGE.put(name, SP500);
        for (String name : List.of("LSE", "LONDON")) BY_EXCHANGE.put(name, FTSE);
        for (String name : List.of("XETRA", "FSX", "FRANKFURT", "GER")) BY_EXCHANGE.put(name, DAX);
        for (String name : List.of("EURONEXT PARIS", "PAR", "PARIS")) BY_EXCHANGE.put(name, CAC);
        for (String name : List.of("TSE", "JPX", "TOKYO")) BY_EXCHANGE.put(name, NIKKEI);
        for (String name : List.of("HKEX", "HKG", "HONG KONG")) BY_EXCHANGE.put(name, HANG_SENG);
        for (String name : List.of("WSE", "WARSAW", "GPW")) BY_EXCHANGE.put(name, WIG20);
        for (String name : List.of("TSX", "TSXV", "TORONTO")) BY_EXCHANGE.put(name, TSX);
        for (String name : List.of("ASX", "SYDNEY")) BY_EXCHANGE.put(name, ASX);
        for (String name : List.of("SIX", "SWISS", "EBS")) BY_EXCHANGE.put(name, SMI);
        for (String name : List.of("OMX STOCKHOLM", "STOCKHOLM", "STO")) BY_EXCHANGE.put(name, OMX);
        for (String name : List.of("EURONEXT AMSTERDAM", "AMS", "AMSTERDAM")) BY_EXCHANGE.put(name, AEX);
        for (String name : List.of("EURONEXT BRUSSELS", "BRU", "BRUSSELS")) BY_EXCHANGE.put(name, BEL20);
        for (String name : List.of("BORSA ITALIANA", "MIL", "MILAN")) BY_EXCHANGE.put(name, FTSE_MIB);
        for (String name : List.of("BME", "MCE", "MADRID")) BY_EXCHANGE.put(name, IBEX);
        for (String name : List.of("OMX COPENHAGEN", "CPH", "COPENHAGEN")) BY_EXCHANGE.put(name, OMXC25);
        for (String name : List.of("OMX HELSINKI", "HEL", "HELSINKI")) BY_EXCHANGE.put(name, OMXH25);
        for (String name : List.of("VIE", "VIENNA", "WIENER BOERSE")) BY_EXCHANGE.put(name, ATX);
        for (String name : List.of("EURONEXT LISBON", "LIS", "LISBON")) BY_EXCHANGE.put(name, PSI);
        for (String name : List.of("EURONEXT DUBLIN", "ISE", "DUBLIN")) BY_EXCHANGE.put(name, ISEQ);

        BY_COUNTRY.put("UNITED STATES", SP500);
        BY_COUNTRY.put("USA", SP500);
        BY_COUNTRY.put("UNITED KINGDOM", FTSE);
        BY_COUNTRY.put("UK", FTSE);
        BY_COUNTRY.put("GERMANY", DAX);
        BY_COUNTRY.put("FRANCE", CAC);
        BY_COUNTRY.put("JAPAN", NIKKEI);
        BY_COUNTRY.put("HONG KONG", HANG_SENG);
        BY_COUNTRY.put("POLAND", WIG20);
        BY_COUNTRY.put("CANADA", TSX);
        BY_COUNTRY.put("AUSTRALIA", ASX);
        BY_COUNTRY.put("SWITZERLAND", SMI);
        BY_COUNTRY.put("SWEDEN", OMX);
        BY_COUNTRY.put("NETHERLANDS", AEX);
        BY_COUNTRY.put("BELGIUM", BEL20);
        BY_COUNTRY.put("ITALY", FTSE_MIB);
        BY_COUNTRY.put("SPAIN", IBEX);
        BY_COUNTRY.put("DENMARK", OMXC25);
        BY_COUNTRY.put("FINLAND", OMXH25);
        BY_COUNTRY.put("AUSTRIA", ATX);
        BY_COUNTRY.put("PORTUGAL", PSI);
        BY_COUNTRY.put("IRELAND", ISEQ);

        BY_CURRENCY.put("USD", SP500);
        BY_CURRENCY.put("GBP", FTSE);
        BY_CURRENCY.put("GBX", FTSE);
        BY_CURRENCY.put("JPY", NIKKEI);
        BY_CURRENCY.put("HKD", HANG_SENG);
        BY_CURRENCY.put("PLN", WIG20);
        BY_CURRENCY.put("CAD", TSX);
        BY_CURRENCY.put("AUD", ASX);
        BY_CURRENCY.put("CHF", SMI);
        BY_CURRENCY.put("SEK", OMX);
        BY_CURRENCY.put("DKK", OMXC25);
        BY_CURRENCY.put("EUR", EURO_STOXX);
    }

    private MarketBenchmarks() {
    }

    static MarketBenchmark forAsset(StockAsset asset) {
        if (asset == null) return FALLBACK;
        MarketBenchmark byMic = BY_MIC.get(normalize(asset.getMicCode()));
        if (byMic != null) return byMic;
        MarketBenchmark byExchange = BY_EXCHANGE.get(normalize(asset.getExchange()));
        if (byExchange != null) return byExchange;
        MarketBenchmark byCountry = BY_COUNTRY.get(normalize(asset.getCountry()));
        if (byCountry != null) return byCountry;
        MarketBenchmark byCurrency = BY_CURRENCY.get(normalize(asset.getCurrency()));
        return byCurrency != null ? byCurrency : FALLBACK;
    }

    /** True for any symbol used as a benchmark, including ones that are not catalog indexes. */
    static boolean isBenchmark(String symbol) {
        String normalized = normalize(symbol);
        return FALLBACK.symbol().equals(normalized)
                || BY_MIC.values().stream().anyMatch(benchmark -> benchmark.symbol().equals(normalized))
                || BY_CURRENCY.values().stream().anyMatch(benchmark -> benchmark.symbol().equals(normalized));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static MarketBenchmark local(String symbol, String name) {
        return new MarketBenchmark(symbol, name, true);
    }
}
