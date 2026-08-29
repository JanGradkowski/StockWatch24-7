package org.example.stockwatch247.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Temporary fixed market-cap test universe. Delete this class together with the
 * dashboard bulk-follow control when large-universe testing is complete.
 *
 * <p>Snapshot source: CompaniesMarketCap, largest listed U.S. companies by
 * market capitalization, captured 2026-08-28. A duplicate CVS boundary entry
 * between source pages was removed and OXY was included as the 200th unique symbol.</p>
 */
final class TemporaryTopUsCompanyUniverse {
    static final String SNAPSHOT_LABEL = "28 Aug 2026 market-cap snapshot";
    static final int COMPANY_COUNT = 200;

    private static final String SYMBOL_TEXT = """
            NVDA AAPL GOOG MSFT AMZN SPCX AVGO META TSLA BRK.B LLY MU JPM WMT AMD V JNJ XOM MA INTC
            ABBV CSCO BAC ORCL PLTR COST CVX LRCX KO AMAT CAT MRK GE UNH NFLX PG MS HD GS PM
            DELL RTX PANW WFC ANET GEV KLAC TXN AMGN TMO AXP C MRVL SNDK IBM VZ APH ABT PEP TMUS
            CRWD BLK DIS SCHW MCD UNP GILD SCCO ADI T NEE QCOM WELL DE BX WDC CRM BA IBKR PFE UBER
            BKNG COP DHR TJX VRTX NEM PLD BMY COF ISRG GLW PH LMT NOW PGR SPGI SYK SBUX CVS LOW ADBE
            SNOW ADP MO FCX ABNB BNY NET HWM EQIX APP MCK VRT GD SO MPC DASH CME KKR CEG VLO HOOD USB
            PNC PSX CDNS CSX INTU DUK CMCSA PWR MAR MMM MNST HCA WMB MRSH ICE UPS SNPS MCO EMR DDOG
            WM ELV LITE EPD SHW REGN CVNA CTAS SLB SPG AMT MSI ITW ECL MDLZ CMI APO FDX NSC GM NOC
            TRV RCL EOG TGT ROST ET CI HLT DLR CL WBD HPE ORLY KMI HON APD BSX RSG AEP AJG PCAR TDG
            ALL URI MPWR BE GWW TRGP BKR TFC COR MET MPLX OKE OXY
            """;

    static final List<String> SYMBOLS = parseSymbols();

    private TemporaryTopUsCompanyUniverse() {
    }

    private static List<String> parseSymbols() {
        List<String> symbols = Arrays.stream(SYMBOL_TEXT.strip().split("\\s+"))
                .map(String::strip)
                .filter(symbol -> !symbol.isEmpty())
                .toList();
        if (symbols.size() != COMPANY_COUNT
                || new LinkedHashSet<>(symbols).size() != COMPANY_COUNT) {
            throw new IllegalStateException("Temporary U.S. test universe must contain 200 unique symbols.");
        }
        return symbols;
    }
}
