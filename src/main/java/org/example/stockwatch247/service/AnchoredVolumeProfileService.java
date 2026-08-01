package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.AnchoredVolumeProfileRefreshStore.CandleSnapshot;
import org.example.stockwatch247.service.AnchoredVolumeProfileRefreshStore.RefreshState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class AnchoredVolumeProfileService {
    private static final String INTRADAY_PROFILE_INTERVAL = "15min";

    private final CandleRepository candleRepository;
    private final MarketDataService marketDataService;
    private final CandleCompletionService completionService;
    private final AnchoredVolumeProfileRefreshStore refreshStore;
    private final ZoneId completionZone;
    private final int configuredPriceBins;
    private final double valueAreaFraction;
    private final int maximumLiveRefreshes;
    private final Clock clock;

    @Autowired
    public AnchoredVolumeProfileService(
            CandleRepository candleRepository,
            MarketDataService marketDataService,
            CandleCompletionService completionService,
            AnchoredVolumeProfileRefreshStore refreshStore,
            @Value("${alerts.schedule.zone:Europe/Brussels}") String completionZone,
            @Value("${anchored-volume-profile.price-bins:48}") int configuredPriceBins,
            @Value("${anchored-volume-profile.value-area-percent:70}") double valueAreaPercent,
            @Value("${anchored-volume-profile.maximum-live-refreshes-per-user:2}")
            int maximumLiveRefreshes) {
        this(
                candleRepository,
                marketDataService,
                completionService,
                refreshStore,
                completionZone,
                configuredPriceBins,
                valueAreaPercent,
                maximumLiveRefreshes,
                Clock.systemUTC());
    }

    AnchoredVolumeProfileService(
            CandleRepository candleRepository,
            MarketDataService marketDataService,
            CandleCompletionService completionService,
            AnchoredVolumeProfileRefreshStore refreshStore,
            String completionZone,
            int configuredPriceBins,
            double valueAreaPercent,
            int maximumLiveRefreshes,
            Clock clock) {
        this.candleRepository = candleRepository;
        this.marketDataService = marketDataService;
        this.completionService = completionService;
        this.refreshStore = refreshStore;
        this.completionZone = ZoneId.of(completionZone);
        this.configuredPriceBins = Math.max(12, Math.min(configuredPriceBins, 200));
        this.valueAreaFraction = Math.max(0.5, Math.min(valueAreaPercent / 100.0, 0.95));
        this.maximumLiveRefreshes = Math.max(1, Math.min(maximumLiveRefreshes, 2));
        this.clock = clock;
    }

    public ProfileResponse getProfile(
            User user,
            String rawSymbol,
            String rawInterval,
            long anchorTimestamp) {
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("Authentication is required.");
        }
        if (anchorTimestamp <= 0L) {
            throw new IllegalArgumentException("A valid anchor candle is required.");
        }
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        String interval = requireProfileInterval(rawInterval);
        TimeInterval timeInterval = toTimeInterval(interval);
        long activeCandleTimestamp = completionService.firstIncompleteCandleTimestamp(timeInterval);
        String liveRefreshInterval = liveRefreshInterval(interval);

        RefreshState refreshState = refreshStore
                .find(user.getId(), symbol, interval, activeCandleTimestamp)
                .orElse(null);
        MarketDataService.CandleSyncResult syncResult =
                new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE,
                        0,
                        null);

        if (refreshState == null || refreshState.refreshCount() < maximumLiveRefreshes) {
            syncResult = marketDataService.syncCandles(symbol, liveRefreshInterval, null);
            if (isProviderSource(syncResult.source())) {
                Candle latest = latestCandle(symbol, liveRefreshInterval)
                        .orElseThrow(() -> new IllegalStateException(
                                "No candle data is available for the requested profile."));
                refreshState = refreshStore.recordProviderRefresh(
                                user.getId(),
                                symbol,
                                interval,
                                activeCandleTimestamp,
                                maximumLiveRefreshes,
                                syncResult.source().name(),
                                snapshot(latest, liveRefreshInterval))
                        .orElseGet(() -> refreshStore
                                .find(user.getId(), symbol, interval, activeCandleTimestamp)
                                .orElse(null));
            }
        }

        boolean liveRefreshLocked = refreshState != null
                && refreshState.refreshCount() >= maximumLiveRefreshes;
        ProfileCandleSelection selection = profileCandles(
                symbol,
                interval,
                anchorTimestamp,
                activeCandleTimestamp,
                liveRefreshLocked ? refreshState : null);
        List<ProfileCandle> candles = selection.candles();
        boolean currentDailyAnchor = "1d".equals(interval)
                && anchorTimestamp == activeCandleTimestamp;
        if (candles.isEmpty()
                || (!currentDailyAnchor && candles.getFirst().timestamp() != anchorTimestamp)) {
            throw new IllegalArgumentException(
                    "The selected anchor candle is not available in the cached chart history.");
        }

        CalculatedProfile calculated = calculate(candles);
        int refreshesUsed = refreshState == null ? 0 : refreshState.refreshCount();
        Instant unlockAt = activeCandleUnlockAt(activeCandleTimestamp, timeInterval);
        String statusMessage = liveRefreshLocked
                ? "Live refresh limit reached for " + symbol
                + ". Showing the frozen live snapshot; another live update "
                + "becomes available when the current "
                + intervalLabel(interval) + " candle completes."
                : "Showing the latest cached candles. "
                + Math.max(0, maximumLiveRefreshes - refreshesUsed)
                + " live provider refresh(es) remain for this "
                + intervalLabel(interval) + " candle.";

        return new ProfileResponse(
                symbol,
                interval,
                selection.calculationInterval(),
                anchorTimestamp,
                candles.getLast().timestamp(),
                candles.size(),
                calculated.totalVolume(),
                calculated.pointOfControl(),
                calculated.valueAreaHigh(),
                calculated.valueAreaLow(),
                valueAreaFraction * 100.0,
                calculated.bins(),
                true,
                "Estimated from cached " + calculationIntervalLabel(selection.calculationInterval())
                        + " OHLCV candles by distributing each candle's volume "
                        + "uniformly across its high-low range.",
                syncResult.source().name(),
                refreshState == null ? null : refreshState.lastRefreshedAt(),
                refreshesUsed,
                Math.max(0, maximumLiveRefreshes - refreshesUsed),
                liveRefreshLocked,
                unlockAt,
                statusMessage);
    }

    CalculatedProfile calculate(List<ProfileCandle> candles) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("At least one candle is required.");
        }
        double minimumPrice = candles.stream()
                .mapToDouble(ProfileCandle::low)
                .min()
                .orElseThrow();
        double maximumPrice = candles.stream()
                .mapToDouble(ProfileCandle::high)
                .max()
                .orElseThrow();
        if (!Double.isFinite(minimumPrice)
                || !Double.isFinite(maximumPrice)
                || maximumPrice < minimumPrice) {
            throw new IllegalArgumentException("The candle price range is invalid.");
        }

        int binCount = maximumPrice > minimumPrice ? configuredPriceBins : 1;
        double binHeight = binCount == 1 ? 1.0 : (maximumPrice - minimumPrice) / binCount;
        double[] total = new double[binCount];
        double[] up = new double[binCount];
        double[] down = new double[binCount];

        for (ProfileCandle candle : candles) {
            double volume = Math.max(0L, candle.volume());
            if (volume == 0.0) {
                continue;
            }
            double candleLow = Math.max(minimumPrice, Math.min(candle.low(), candle.high()));
            double candleHigh = Math.min(maximumPrice, Math.max(candle.low(), candle.high()));
            if (binCount == 1 || candleHigh <= candleLow) {
                int index = priceBin(candle.close(), minimumPrice, binHeight, binCount);
                addVolume(total, up, down, index, volume, candle.close() >= candle.open());
                continue;
            }

            int firstBin = priceBin(candleLow, minimumPrice, binHeight, binCount);
            int lastBin = priceBin(candleHigh, minimumPrice, binHeight, binCount);
            double range = candleHigh - candleLow;
            double allocated = 0.0;
            for (int index = firstBin; index <= lastBin; index++) {
                double binLow = minimumPrice + index * binHeight;
                double binHigh = index == binCount - 1
                        ? maximumPrice
                        : binLow + binHeight;
                double overlap = Math.max(
                        0.0,
                        Math.min(candleHigh, binHigh) - Math.max(candleLow, binLow));
                double share = index == lastBin
                        ? Math.max(0.0, volume - allocated)
                        : volume * overlap / range;
                allocated += share;
                addVolume(total, up, down, index, share, candle.close() >= candle.open());
            }
        }

        int pointOfControlIndex = 0;
        for (int index = 1; index < binCount; index++) {
            if (total[index] > total[pointOfControlIndex]) {
                pointOfControlIndex = index;
            }
        }
        double totalVolume = 0.0;
        for (double volume : total) {
            totalVolume += volume;
        }
        int valueAreaLowIndex = pointOfControlIndex;
        int valueAreaHighIndex = pointOfControlIndex;
        double valueAreaVolume = total[pointOfControlIndex];
        double targetVolume = totalVolume * valueAreaFraction;
        while (valueAreaVolume < targetVolume
                && (valueAreaLowIndex > 0 || valueAreaHighIndex < binCount - 1)) {
            double lowerVolume = valueAreaLowIndex > 0
                    ? total[valueAreaLowIndex - 1]
                    : -1.0;
            double upperVolume = valueAreaHighIndex < binCount - 1
                    ? total[valueAreaHighIndex + 1]
                    : -1.0;
            if (lowerVolume == upperVolume && lowerVolume >= 0.0) {
                valueAreaLowIndex--;
                valueAreaHighIndex++;
                valueAreaVolume += lowerVolume + upperVolume;
            } else if (upperVolume > lowerVolume) {
                valueAreaHighIndex++;
                valueAreaVolume += upperVolume;
            } else {
                valueAreaLowIndex--;
                valueAreaVolume += lowerVolume;
            }
        }

        double maximumBinVolume = 0.0;
        for (double volume : total) {
            maximumBinVolume = Math.max(maximumBinVolume, volume);
        }
        List<ProfileBin> bins = new ArrayList<>(binCount);
        for (int index = 0; index < binCount; index++) {
            double low = binCount == 1 ? minimumPrice : minimumPrice + index * binHeight;
            double high = binCount == 1
                    ? maximumPrice
                    : (index == binCount - 1 ? maximumPrice : low + binHeight);
            bins.add(new ProfileBin(
                    low,
                    high,
                    total[index],
                    up[index],
                    down[index],
                    maximumBinVolume == 0.0 ? 0.0 : total[index] / maximumBinVolume,
                    index >= valueAreaLowIndex && index <= valueAreaHighIndex,
                    index == pointOfControlIndex));
        }

        double pointOfControl = midpoint(bins.get(pointOfControlIndex));
        double valueAreaLow = bins.get(valueAreaLowIndex).priceLow();
        double valueAreaHigh = bins.get(valueAreaHighIndex).priceHigh();
        return new CalculatedProfile(
                List.copyOf(bins),
                totalVolume,
                pointOfControl,
                valueAreaHigh,
                valueAreaLow);
    }

    private ProfileCandleSelection profileCandles(
            String symbol,
            String interval,
            long anchorTimestamp,
            long activeCandleTimestamp,
            RefreshState lockedState) {
        if ("1d".equals(interval)) {
            return dailyProfileCandles(
                    symbol,
                    anchorTimestamp,
                    activeCandleTimestamp,
                    lockedState);
        }

        List<ProfileCandle> candles = candleRepository
                .findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        symbol,
                        interval,
                        anchorTimestamp)
                .stream()
                .map(this::profileCandle)
                .toList();
        if (lockedState == null) {
            return new ProfileCandleSelection(candles, interval);
        }

        List<ProfileCandle> frozen = new ArrayList<>();
        candles.stream()
                .filter(candle -> candle.timestamp() < activeCandleTimestamp)
                .forEach(frozen::add);
        CandleSnapshot snapshot = lockedState.snapshot();
        if (interval.equals(snapshot.interval())
                && snapshot.timestamp() >= activeCandleTimestamp
                && snapshot.timestamp() >= anchorTimestamp) {
            frozen.add(profileCandle(snapshot));
        }
        frozen.sort(Comparator.comparingLong(ProfileCandle::timestamp));
        return new ProfileCandleSelection(List.copyOf(frozen), interval);
    }

    private ProfileCandleSelection dailyProfileCandles(
            String symbol,
            long anchorTimestamp,
            long activeCandleTimestamp,
            RefreshState lockedState) {
        List<ProfileCandle> completedDaily = candleRepository
                .findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        symbol,
                        "1d",
                        anchorTimestamp)
                .stream()
                .map(this::profileCandle)
                .filter(candle -> candle.timestamp() < activeCandleTimestamp)
                .toList();

        List<ProfileCandle> activeIntraday = candleRepository
                .findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        symbol,
                        INTRADAY_PROFILE_INTERVAL,
                        activeCandleTimestamp)
                .stream()
                .map(this::profileCandle)
                .toList();
        if (lockedState != null) {
            activeIntraday = freezeAtSnapshot(
                    activeIntraday,
                    lockedState.snapshot(),
                    INTRADAY_PROFILE_INTERVAL,
                    activeCandleTimestamp);
        }

        if (!activeIntraday.isEmpty()) {
            List<ProfileCandle> combined = new ArrayList<>(
                    completedDaily.size() + activeIntraday.size());
            combined.addAll(completedDaily);
            combined.addAll(activeIntraday);
            combined.sort(Comparator.comparingLong(ProfileCandle::timestamp));
            String calculationInterval = completedDaily.isEmpty()
                    ? INTRADAY_PROFILE_INTERVAL
                    : "1d+15min";
            return new ProfileCandleSelection(
                    List.copyOf(combined),
                    calculationInterval);
        }

        List<ProfileCandle> fallback = new ArrayList<>(completedDaily);
        if (lockedState != null && "1d".equals(lockedState.snapshot().interval())) {
            CandleSnapshot snapshot = lockedState.snapshot();
            if (snapshot.timestamp() >= Math.max(anchorTimestamp, activeCandleTimestamp)) {
                fallback.add(profileCandle(snapshot));
            }
        } else if (lockedState == null) {
            candleRepository
                    .findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                            symbol,
                            "1d",
                            Math.max(anchorTimestamp, activeCandleTimestamp))
                    .stream()
                    .map(this::profileCandle)
                    .forEach(fallback::add);
        }
        fallback.sort(Comparator.comparingLong(ProfileCandle::timestamp));
        return new ProfileCandleSelection(List.copyOf(fallback), "1d");
    }

    private List<ProfileCandle> freezeAtSnapshot(
            List<ProfileCandle> candles,
            CandleSnapshot snapshot,
            String expectedInterval,
            long lowerTimestamp) {
        if (!expectedInterval.equals(snapshot.interval())) {
            return List.of();
        }
        List<ProfileCandle> frozen = new ArrayList<>();
        candles.stream()
                .filter(candle -> candle.timestamp() < snapshot.timestamp())
                .forEach(frozen::add);
        if (snapshot.timestamp() >= lowerTimestamp) {
            frozen.add(profileCandle(snapshot));
        }
        frozen.sort(Comparator.comparingLong(ProfileCandle::timestamp));
        return List.copyOf(frozen);
    }

    private Optional<Candle> latestCandle(String symbol, String interval) {
        return candleRepository.findTop1BySymbolAndTimeIntervalOrderByTimestampDesc(
                        symbol,
                        interval)
                .stream()
                .findFirst();
    }

    private CandleSnapshot snapshot(Candle candle, String interval) {
        return new CandleSnapshot(
                interval,
                candle.getTimestamp(),
                safePrice(candle.getOpenPrice()),
                safePrice(candle.getHighPrice()),
                safePrice(candle.getLowPrice()),
                safePrice(candle.getClosePrice()),
                Math.max(0L, candle.getVolume() == null ? 0L : candle.getVolume()));
    }

    private ProfileCandle profileCandle(CandleSnapshot snapshot) {
        return new ProfileCandle(
                snapshot.timestamp(),
                snapshot.open(),
                snapshot.high(),
                snapshot.low(),
                snapshot.close(),
                snapshot.volume());
    }

    private ProfileCandle profileCandle(Candle candle) {
        return new ProfileCandle(
                candle.getTimestamp(),
                safePrice(candle.getOpenPrice()),
                safePrice(candle.getHighPrice()),
                safePrice(candle.getLowPrice()),
                safePrice(candle.getClosePrice()),
                Math.max(0L, candle.getVolume() == null ? 0L : candle.getVolume()));
    }

    private double safePrice(Double price) {
        if (price == null || !Double.isFinite(price)) {
            throw new IllegalArgumentException("A cached candle contains an invalid price.");
        }
        return price;
    }

    private boolean isProviderSource(MarketDataService.CandleSource source) {
        return source == MarketDataService.CandleSource.TWELVE_DATA
                || source == MarketDataService.CandleSource.YAHOO_FINANCE;
    }

    private int priceBin(double price, double minimum, double binHeight, int binCount) {
        if (binCount == 1 || binHeight <= 0.0) {
            return 0;
        }
        int index = (int) Math.floor((price - minimum) / binHeight);
        return Math.max(0, Math.min(binCount - 1, index));
    }

    private void addVolume(
            double[] total,
            double[] up,
            double[] down,
            int index,
            double volume,
            boolean upwardCandle) {
        total[index] += volume;
        if (upwardCandle) {
            up[index] += volume;
        } else {
            down[index] += volume;
        }
    }

    private double midpoint(ProfileBin bin) {
        return (bin.priceLow() + bin.priceHigh()) / 2.0;
    }

    private String requireProfileInterval(String interval) {
        String validated = SecurityInputValidator.requireInterval(interval);
        if (!List.of("1d", "1wk", "1mo").contains(validated)) {
            throw new IllegalArgumentException(
                    "Anchored volume profiles require a daily, weekly, or monthly interval.");
        }
        return validated;
    }

    private TimeInterval toTimeInterval(String interval) {
        return switch (interval) {
            case "1wk" -> TimeInterval.WEEKLY;
            case "1mo" -> TimeInterval.MONTHLY;
            default -> TimeInterval.DAILY;
        };
    }

    private String intervalLabel(String interval) {
        return switch (interval) {
            case "1wk" -> "weekly";
            case "1mo" -> "monthly";
            default -> "daily";
        };
    }

    private String liveRefreshInterval(String interval) {
        return "1d".equals(interval) ? INTRADAY_PROFILE_INTERVAL : interval;
    }

    private String calculationIntervalLabel(String interval) {
        return switch (interval) {
            case "15min" -> "15-minute";
            case "1d+15min" -> "daily and active-day 15-minute";
            default -> intervalLabel(interval);
        };
    }

    private Instant activeCandleUnlockAt(long activeCandleTimestamp, TimeInterval interval) {
        LocalDate activeDate = Instant.ofEpochSecond(activeCandleTimestamp)
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
        LocalDate unlockDate = switch (interval) {
            case DAILY -> activeDate.plusDays(1);
            case WEEKLY -> activeDate.plusDays(5);
            case MONTHLY -> activeDate.plusMonths(1);
            default -> throw new IllegalArgumentException("Unsupported profile interval.");
        };
        return unlockDate.atStartOfDay(completionZone).toInstant();
    }

    @Scheduled(cron = "${anchored-volume-profile.cleanup-cron:0 20 3 * * *}")
    public void cleanupOldRefreshStates() {
        refreshStore.removeOlderThan(clock.instant().minusSeconds(120L * 86_400L));
    }

    public record ProfileCandle(
            long timestamp,
            double open,
            double high,
            double low,
            double close,
            long volume) {
    }

    public record ProfileBin(
            double priceLow,
            double priceHigh,
            double volume,
            double upCandleVolume,
            double downCandleVolume,
            double relativeVolume,
            boolean inValueArea,
            boolean pointOfControl) {
    }

    record CalculatedProfile(
            List<ProfileBin> bins,
            double totalVolume,
            double pointOfControl,
            double valueAreaHigh,
            double valueAreaLow) {
    }

    private record ProfileCandleSelection(
            List<ProfileCandle> candles,
            String calculationInterval) {
    }

    public record ProfileResponse(
            String symbol,
            String interval,
            String calculationInterval,
            long anchorTimestamp,
            long endTimestamp,
            int candlesIncluded,
            double totalVolume,
            double pointOfControl,
            double valueAreaHigh,
            double valueAreaLow,
            double valueAreaPercent,
            List<ProfileBin> bins,
            boolean estimated,
            String methodology,
            String dataSource,
            Instant liveDataRefreshedAt,
            int liveRefreshesUsed,
            int liveRefreshesRemaining,
            boolean liveRefreshLocked,
            Instant liveRefreshAvailableAt,
            String statusMessage) {
    }
}
