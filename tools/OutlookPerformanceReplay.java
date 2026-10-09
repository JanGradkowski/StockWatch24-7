package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.*;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

class OutlookPerformanceReplay {
    record Snapshot(List<Candle> candles, AnalysisPreferencesService.PreferencesView preferences) { }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Snapshot snapshot = mapper.readValue(Files.readString(Path.of(args[0])), Snapshot.class);
        Candle latest = snapshot.candles().getLast();
        CandleRepository candles = (CandleRepository) Proxy.newProxyInstance(OutlookPerformanceReplay.class.getClassLoader(),
                new Class<?>[]{CandleRepository.class}, (proxy, method, values) -> {
                    if (!method.getName().equals("findBySymbolAndTimeIntervalOrderByTimestampDesc")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return snapshot.candles().stream().filter(candle -> candle.getSymbol().equals(values[0])
                                    && candle.getTimeInterval().equals(values[1]))
                            .sorted(Comparator.comparing(Candle::getTimestamp).reversed())
                            .limit(((Pageable) values[2]).getPageSize()).toList();
                });
        AnalysisPreferencesService preferences = new AnalysisPreferencesService(null, mapper) {
            @Override public PreferencesView get(User user) { return snapshot.preferences(); }
        };
        var outlook = new TechnicalOutlookService(null, candles, empty(StockAssetRepository.class),
                new TechnicalIndicatorEnrichmentService(), empty(AlertEventRepository.class),
                empty(CongressionalTradeDeliveryRepository.class), empty(InsiderTradeDeliveryRepository.class), preferences);
        User user = new User();
        user.setId(1L);
        boolean detailOnly = args.length > 1 && args[1].equals("detail");
        System.out.println("Snapshot-only replay: no providers, database, real user deliveries, or email; bars=" + snapshot.candles().size());
        for (boolean detailed : detailOnly ? List.of(true, true) : List.of(false, true, true)) {
            long started = System.nanoTime();
            var result = outlook.getTrackingOutlook(user, latest.getSymbol(), latest.getTimeInterval(), detailed);
            System.out.println("Outlook detail=" + detailed + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000.0
                    + " classification=" + result.headlineScore().classification() + " indicators=" + result.indicators().size());
        }
    }

    private static <T> T empty(Class<T> repository) {
        return repository.cast(Proxy.newProxyInstance(OutlookPerformanceReplay.class.getClassLoader(),
                new Class<?>[]{repository}, (proxy, method, values) -> {
                    if (method.getReturnType() == Optional.class) return Optional.empty();
                    if (method.getReturnType() == List.class) return List.of();
                    throw new UnsupportedOperationException(method.getName());
                }));
    }
}
