package org.example.stockwatch247.market;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Preserve exchange identity when providers return an unqualified local symbol. */
public final class MarketListingSymbols {
    private MarketListingSymbols() {}
    private record Venue(String suffix,String mic) {}
    private static final List<Venue> VENUES=List.of(
            new Venue(".DE","XETR"),new Venue(".F","XFRA"),new Venue(".PA","XPAR"),
            new Venue(".AS","XAMS"),new Venue(".BR","XBRU"),new Venue(".MI","XMIL"),
            new Venue(".LS","XLIS"),new Venue(".MC","XMAD"),new Venue(".SW","XSWX"),
            new Venue(".L","XLON"),new Venue(".TO","XTSE"),new Venue(".V","XTSX"),
            new Venue(".T","XTKS"),new Venue(".HK","XHKG"),new Venue(".AX","XASX"),
            new Venue(".NS","XNSE"),new Venue(".BO","XBOM"),new Venue(".JO","XJSE"),
            new Venue(".SA","BVMF"),new Venue(".WA","XWAR"),new Venue(".ST","XSTO"),
            new Venue(".HE","XHEL"),new Venue(".CO","XCSE"),new Venue(".OL","XOSL"));
    private static String normalized(String value) { return value==null?"":value.strip().toUpperCase(Locale.ROOT); }
    public static String canonical(String raw,String rawMic) {
        String symbol=MarketIndexCatalog.canonicalTickerSymbol(raw),mic=normalized(rawMic);
        if(symbol.startsWith("^") || VENUES.stream().anyMatch(v->symbol.endsWith(v.suffix()))) return symbol;
        for(Venue venue:VENUES) if(venue.mic().equals(mic)) {
            String local=symbol;
            if(Set.of(".TO",".V",".L").contains(venue.suffix())) local=local.replace('.','-');
            if(venue.suffix().equals(".HK") && local.matches("[0-9]{1,4}")) local="0".repeat(4-local.length())+local;
            return local+venue.suffix();
        }
        return symbol;
    }
    public static String localSymbol(String canonical) {
        for(Venue venue:VENUES) if(canonical.endsWith(venue.suffix())) {
            String local=canonical.substring(0,canonical.length()-venue.suffix().length());
            if(Set.of(".TO",".V",".L").contains(venue.suffix())) local=local.replace('-','.');
            return local;
        }
        return canonical;
    }
    public static String mic(String canonical) {
        return VENUES.stream().filter(v->canonical.endsWith(v.suffix())).map(Venue::mic).findFirst().orElse(null);
    }
}
