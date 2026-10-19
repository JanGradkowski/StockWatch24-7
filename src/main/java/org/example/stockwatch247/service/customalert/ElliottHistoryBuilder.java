package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.BackgroundJobDispatcher;
import org.example.stockwatch247.service.InfrastructureRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the day-by-day Elliott Wave history previews read (see {@link ElliottHistoryStore}): reads each
 * completed candle's own wave count, oldest first, and stores the signals it showed. One wave scan costs up to a
 * second or two on long, busy histories, so the work runs in time slices, one history at a time, and resumes
 * where it stopped; once a history reaches the latest candle, later requests only add the new candles.
 */
@Component
public class ElliottHistoryBuilder {
    private static final Logger log = LoggerFactory.getLogger(ElliottHistoryBuilder.class);
    private static final Duration LEASE = Duration.ofMinutes(3);

    private final ElliottHistoryStore store;
    private final CustomAlertFactsLoader facts;
    private final StockAssetRepository assets;
    private final UserRepository users;
    private final boolean enabled;
    private final long sliceMillis;
    private final String owner = "elliott-history-" + UUID.randomUUID();
    private BackgroundJobDispatcher dispatcher;

    public ElliottHistoryBuilder(ElliottHistoryStore store, CustomAlertFactsLoader facts, StockAssetRepository assets,
                                 UserRepository users,
                                 @Value("${custom-alerts.background-enabled:${alerts.schedule.enabled:true}}") boolean enabled,
                                 @Value("${custom-alerts.elliott-history-slice-ms:20000}") long sliceMillis) {
        this.store = store;
        this.facts = facts;
        this.assets = assets;
        this.users = users;
        this.enabled = enabled;
        this.sliceMillis = Math.max(1000, sliceMillis);
    }

    @Autowired(required = false)
    void configureDispatcher(BackgroundJobDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${custom-alerts.elliott-history-delay-ms:5000}",
            initialDelayString = "${custom-alerts.elliott-history-initial-delay-ms:30000}")
    public void dispatch() {
        if (enabled && dispatcher != null) dispatcher.submit("elliott-history", this::buildNextSlice);
    }

    @Scheduled(cron = "${custom-alerts.elliott-history-cleanup-cron:0 40 3 * * *}")
    public void cleanup() {
        if (!enabled) return;
        int removed = store.removeUnused(Duration.ofDays(60));
        if (removed > 0) log.info("Removed {} Elliott Wave histories no preview used for 60 days.", removed);
    }

    /** Extends the oldest waiting history by one time slice. Returns false when nothing was waiting. */
    public boolean buildNextSlice() {
        // While scheduled checks run, only histories a preview is waiting for are built.
        ElliottHistoryStore.Claim claim = store.claimNext(owner, LEASE, store.checksRunning() ? 0 : Integer.MAX_VALUE).orElse(null);
        if (claim == null) return false;
        try {
            StockAsset asset = assets.findById(claim.stockAssetId()).orElse(null);
            if (asset == null) {
                store.fail(claim, "The stock no longer exists.");
                return true;
            }
            User user = claim.userId() == null ? null : users.findById(claim.userId()).orElse(null);
            CustomAlertFactsLoader.StockData data = facts.load(asset, claim.interval(), null);
            CustomAlertFactsLoader.StockData.ElliottInputs inputs = data.elliottInputs(user);
            if (inputs == null) {
                store.fail(claim, "Not enough candles are stored for a wave count.");
                return true;
            }
            if (!inputs.settingsKey().equals(claim.settingsKey())) {
                // The owner changed their Elliott settings: a preview with the new settings asks for a new history.
                store.fail(claim, "The Elliott Wave settings changed.");
                return true;
            }
            List<Candle> candles = data.candles();
            int last = candles.size() - 1;
            claim = checkInputs(claim, asset, candles);
            int windowStart = Math.max(1, candles.size() - CustomAlertPreview.HISTORY);
            int from = claim.coveredTo() == null ? windowStart : firstAfter(candles, claim.coveredTo());
            long coveredFrom = claim.coveredFrom() == null ? candles.get(windowStart).getTimestamp() : claim.coveredFrom();
            if (from > last) {
                if (claim.prefixFingerprint() == null) noteInputs(claim, asset, candles, coveredFrom, claim.coveredTo());
                store.release(claim, true);
                return true;
            }
            long started = System.nanoTime();
            Map<List<Object>, ElliottHistoryStore.Signal> signals = new LinkedHashMap<>();
            int done = inputs.readForward(from, last, started + sliceMillis * 1_000_000L, coveredFrom, hit ->
                    signals.putIfAbsent(List.of(hit.code(), hit.type(), hit.availableAt()), new ElliottHistoryStore.Signal(
                            hit.code(), hit.type(), hit.bullish(), hit.score(), hit.availableAt(), hit.knownAt(),
                            hit.anchorAt(), hit.price())));
            boolean kept = store.progress(claim, List.copyOf(signals.values()), coveredFrom, candles.get(done).getTimestamp(), LEASE);
            if (!kept) return true;
            noteInputs(claim, asset, candles, coveredFrom, candles.get(done).getTimestamp());
            store.release(claim, done >= last);
            log.info("Elliott Wave history for {} {}: read {} candle(s) in {} ms, {}.", asset.getTickerSymbol(), claim.interval(),
                    done - from + 1, (System.nanoTime() - started) / 1_000_000, done >= last ? "up to date" : "continuing");
        } catch (RuntimeException failure) {
            if (InfrastructureRecovery.isDatabaseUnavailable(failure)) throw failure;
            log.warn("Elliott Wave history build failed for stock {} {}", claim.stockAssetId(), claim.interval(), failure);
            store.fail(claim, failure.getClass().getSimpleName());
        }
        return true;
    }

    /** Candles checked at the end of a stored range: a provider's late correction of the last candles is common. */
    static final int TAIL_CANDLES = 5;

    private org.example.stockwatch247.service.HistoricalSignalCacheService fingerprints;

    @Autowired(required = false)
    void configureFingerprints(org.example.stockwatch247.service.HistoricalSignalCacheService fingerprints) {
        this.fingerprints = fingerprints;
    }

    /**
     * The candles a stored range was read from, as noted when it was stored: one fingerprint for everything before
     * the last five (read from the database, so candles older than today's loaded window still count) and one per
     * candle for the last five.
     */
    record Inputs(String prefix, String tail) { }

    private Inputs inputs(StockAsset asset, org.example.stockwatch247.model.enums.TimeInterval interval, List<Candle> candles,
                          long coveredFrom, long coveredTo) {
        int end = firstAfter(candles, coveredTo);
        int tailStart = Math.max(0, end - TAIL_CANDLES);
        StringBuilder tail = new StringBuilder();
        for (int index = tailStart; index < end; index++) {
            Candle candle = candles.get(index);
            if (!tail.isEmpty()) tail.append(',');
            tail.append(candle.getTimestamp()).append(':').append(org.example.stockwatch247.service.HistoricalSignalCacheService.sha256(
                    candle.getTimestamp() + "|" + candle.getOpenPrice() + "|" + candle.getHighPrice() + "|" + candle.getLowPrice() + "|"
                            + candle.getClosePrice() + "|" + candle.getVolume()).substring(0, 16));
        }
        long prefixEnd = tailStart < end ? candles.get(tailStart).getTimestamp() : coveredTo + 1;
        String prefix = fingerprints.candleFingerprint(asset.getTickerSymbol(), CustomAlertFactsLoader.apiInterval(interval),
                coveredFrom, prefixEnd);
        return new Inputs(prefix, tail.toString());
    }

    /**
     * Before extending a stored range, compares the candles it was read from with those stored now. A changed candle
     * among the last five is read again from that candle on; a change further back starts the history again.
     */
    private ElliottHistoryStore.Claim checkInputs(ElliottHistoryStore.Claim claim, StockAsset asset, List<Candle> candles) {
        if (fingerprints == null || claim.coveredTo() == null || claim.coveredFrom() == null || claim.prefixFingerprint() == null) return claim;
        Inputs now = inputs(asset, claim.interval(), candles, claim.coveredFrom(), claim.coveredTo());
        if (!now.prefix().equals(claim.prefixFingerprint())) {
            log.info("Elliott Wave history for {} {} starts again: an older candle changed.", asset.getTickerSymbol(), claim.interval());
            store.reset(claim);
            return claim.covering(null, null);
        }
        if (now.tail().equals(claim.tailFingerprints())) return claim;
        List<String> before = claim.tailFingerprints() == null ? List.of() : List.of(claim.tailFingerprints().split(","));
        List<String> after = now.tail().isEmpty() ? List.of() : List.of(now.tail().split(","));
        int same = 0;
        while (same < before.size() && same < after.size() && before.get(same).equals(after.get(same))) same++;
        long changedFrom = same < before.size() ? Long.parseLong(before.get(same).substring(0, before.get(same).indexOf(':')))
                : same < after.size() ? Long.parseLong(after.get(same).substring(0, after.get(same).indexOf(':'))) : claim.coveredTo();
        int changedIndex = firstAfter(candles, changedFrom - 1);
        if (changedIndex <= 0 || candles.get(changedIndex - 1).getTimestamp() < claim.coveredFrom()) {
            store.reset(claim);
            return claim.covering(null, null);
        }
        long coveredTo = candles.get(changedIndex - 1).getTimestamp();
        log.info("Elliott Wave history for {} {} reads again from candle {}: it changed after it was read.",
                asset.getTickerSymbol(), claim.interval(), changedFrom);
        store.truncate(claim, changedFrom, coveredTo);
        return claim.covering(claim.coveredFrom(), coveredTo);
    }

    private void noteInputs(ElliottHistoryStore.Claim claim, StockAsset asset, List<Candle> candles, long coveredFrom, Long coveredTo) {
        if (fingerprints == null || coveredTo == null) return;
        Inputs inputs = inputs(asset, claim.interval(), candles, coveredFrom, coveredTo);
        store.fingerprint(claim, inputs.prefix(), inputs.tail());
    }

    private static int firstAfter(List<Candle> candles, long timestamp) {
        int low = 0, high = candles.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (candles.get(middle).getTimestamp() <= timestamp) low = middle + 1; else high = middle;
        }
        return low;
    }
}
