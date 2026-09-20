package org.example.stockwatch247.service;

import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

/** Reviewed, dated constituent snapshots. No runtime scraping or implicit resynchronization. */
@Component
public class WatchlistIndexCatalog {
    private final List<Preset> presets;
    private final Map<String,Instrument> instruments=new LinkedHashMap<>();
    public WatchlistIndexCatalog(ObjectMapper mapper) throws IOException {
        try(var input=new ClassPathResource("market/watchlist-indexes.json").getInputStream()) {
            presets=List.of(mapper.readValue(input,Preset[].class));
        }
        Set<String> ids=new HashSet<>();
        for(Preset preset:presets) {
            if(!ids.add(preset.id())) throw new IllegalStateException("Duplicate index preset.");
            Set<String> symbols=new HashSet<>();
            for(Instrument item:preset.constituents()) {
                SecurityInputValidator.requireMarketSymbol(item.symbol());
                if(!symbols.add(item.symbol())) throw new IllegalStateException("Duplicate index constituent.");
                instruments.putIfAbsent(item.symbol(),item);
            }
            instruments.putIfAbsent(preset.symbol(),new Instrument(preset.symbol(),preset.name(),preset.exchange(),preset.currency(),"INDEX"));
        }
    }
    public List<PresetView> all() {
        return presets.stream().map(p->new PresetView(p.id(),p.name(),p.region(),p.country(),p.symbol(),p.source(),p.asOf(),p.constituents().size())).toList();
    }
    public Preset get(String id) { return presets.stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown index preset.")); }
    public Instrument instrument(String symbol) { return instruments.get(symbol); }
    public record Instrument(String symbol,String name,String exchange,String currency,String type) {}
    public record Preset(String id,String name,String region,String country,String symbol,String exchange,String currency,String source,String asOf,List<Instrument> constituents) {}
    public record PresetView(String id,String name,String region,String country,String symbol,String source,String asOf,int count) {}
}
