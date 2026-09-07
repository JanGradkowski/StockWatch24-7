package org.example.stockwatch247.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.TechnicalOutlookNotification;
import org.example.stockwatch247.model.TechnicalOutlookSubscription;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.TechnicalOutlookNotificationRepository;
import org.example.stockwatch247.repository.TechnicalOutlookSubscriptionRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class TechnicalOutlookTrackingService {
    private static final List<TimeInterval> SUPPORTED_INTERVALS = List.of(
            TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY);
    private static final String SNAPSHOT_VERSION = "OUTLOOK_TRACKING_V1";
    private static final DateTimeFormatter PERIOD_FORMAT =
            DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

    private final TechnicalOutlookSubscriptionRepository subscriptionRepository;
    private final TechnicalOutlookNotificationRepository notificationRepository;
    private final StockAssetRepository stockAssetRepository;
    private final TwelveDataService twelveDataService;
    private final TechnicalOutlookService outlookService;
    private final AnalysisPreferencesService preferencesService;
    private final AlertNotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final int maxTrackedStocksPerUser;
    private final int maxGlobalTrackedStocks;
    private final int retentionDays;
    private final String publicBaseUrl;

    public TechnicalOutlookTrackingService(
            TechnicalOutlookSubscriptionRepository subscriptionRepository,
            TechnicalOutlookNotificationRepository notificationRepository,
            StockAssetRepository stockAssetRepository,
            TwelveDataService twelveDataService,
            TechnicalOutlookService outlookService,
            AnalysisPreferencesService preferencesService,
            AlertNotificationService notificationService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            @Value("${alerts.max-tracked-stocks-per-user:300}") int maxTrackedStocksPerUser,
            @Value("${alerts.max-global-tracked-stocks:500}") int maxGlobalTrackedStocks,
            @Value("${technical-outlook.notifications.retention-days:90}") int retentionDays,
            @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.subscriptionRepository = subscriptionRepository;
        this.notificationRepository = notificationRepository;
        this.stockAssetRepository = stockAssetRepository;
        this.twelveDataService = twelveDataService;
        this.outlookService = outlookService;
        this.preferencesService = preferencesService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.maxTrackedStocksPerUser = Math.max(1, maxTrackedStocksPerUser);
        this.maxGlobalTrackedStocks = Math.max(1, maxGlobalTrackedStocks);
        this.retentionDays = Math.max(1, retentionDays);
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public SubscriptionStateView getState(User user, String rawSymbol) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol).orElse(null);
        Map<String, Boolean> intervals = new LinkedHashMap<>();
        SUPPORTED_INTERVALS.forEach(interval -> intervals.put(interval.name(), false));
        if (asset != null) {
            subscriptionRepository.findByUserAndStockAsset(user, asset).forEach(subscription -> {
                if (SUPPORTED_INTERVALS.contains(subscription.getInterval())) {
                    intervals.put(subscription.getInterval().name(), subscription.isActive());
                }
            });
        }
        return new SubscriptionStateView(symbol, intervals);
    }

    @Transactional
    public SubscriptionStateView setSubscription(
            User user, String rawSymbol, TimeInterval interval, boolean active) {
        requireSupported(interval);
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol)
                .orElseGet(() -> twelveDataService.upsertStockAsset(symbol, symbol, "UNKNOWN", "USD"));
        jdbcTemplate.queryForObject(
                "select lock_name from security_resource_locks where lock_name = ? for update",
                String.class, "alert-stock-quota");
        TechnicalOutlookSubscription subscription = subscriptionRepository
                .findByUserAndStockAssetAndInterval(user, asset, interval)
                .orElseGet(TechnicalOutlookSubscription::new);
        boolean wasActive = subscription.getId() != null && subscription.isActive();
        if (active && !wasActive) {
            enforceCapacity(user, asset);
        }
        subscription.setUser(user);
        subscription.setStockAsset(asset);
        subscription.setInterval(interval);
        subscription.setActive(active);
        subscription.setUpdatedAt(LocalDateTime.now());
        if (active && !wasActive) {
            resetBaseline(subscription);
        }
        subscriptionRepository.save(subscription);
        if (active && !wasActive) {
            establishBaseline(subscription);
        }
        return getState(user, symbol);
    }

    @Transactional
    public EvaluationResult evaluate(String rawSymbol, TimeInterval interval) {
        requireSupported(interval);
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        int baselines = 0;
        int changes = 0;
        int skipped = 0;
        for (TechnicalOutlookSubscription subscription : subscriptionRepository
                .findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndActiveTrue(symbol, interval)) {
            try {
                EvaluationStatus status = evaluateSubscription(subscription);
                if (status == EvaluationStatus.BASELINE) baselines++;
                else if (status == EvaluationStatus.CHANGE) changes++;
                else skipped++;
            } catch (RuntimeException exception) {
                skipped++;
                System.err.println("Technical outlook tracking failed for " + symbol + " " + interval
                        + " user=" + subscription.getUser().getId() + ": " + exception.getMessage());
            }
        }
        return new EvaluationResult(baselines, changes, skipped);
    }

    private EvaluationStatus evaluateSubscription(TechnicalOutlookSubscription subscription) {
        User user = subscription.getUser();
        String symbol = subscription.getStockAsset().getTickerSymbol();
        String apiInterval = apiInterval(subscription.getInterval());
        outlookService.invalidate(user, symbol, apiInterval);
        TechnicalOutlookService.OutlookView current =
                outlookService.getSummaryOutlook(user, symbol, apiInterval);
        if (!current.available() || current.candleTimestamp() == null) {
            return EvaluationStatus.SKIPPED;
        }
        String fingerprint = profileFingerprint(user, subscription.getInterval());
        if (subscription.getBaselineSnapshot() == null
                || subscription.getLastCandleTimestamp() == null
                || !Objects.equals(fingerprint, subscription.getProfileFingerprint())) {
            updateBaseline(subscription, current, fingerprint);
            return EvaluationStatus.BASELINE;
        }
        if (current.candleTimestamp() <= subscription.getLastCandleTimestamp()) {
            return EvaluationStatus.SKIPPED;
        }

        TechnicalOutlookService.OutlookView previous = readOutlook(subscription.getBaselineSnapshot());
        String previousClass = previous.headlineScore().classification();
        String currentClass = current.headlineScore().classification();
        boolean changed = !Objects.equals(previousClass, currentClass);
        if (changed && !notificationRepository.existsBySubscriptionAndCurrentCandleTimestamp(
                subscription, current.candleTimestamp())) {
            TechnicalOutlookService.OutlookView detailed = outlookService.getOutlook(user, symbol, apiInterval);
            ChangeReport report = compare(previous, detailed);
            TechnicalOutlookNotification notification = new TechnicalOutlookNotification();
            notification.setSubscription(subscription);
            notification.setPreviousClassification(previousClass);
            notification.setCurrentClassification(currentClass);
            notification.setPreviousScore(previous.headlineScore().normalizedScore());
            notification.setCurrentScore(current.headlineScore().normalizedScore());
            notification.setPreviousCandleTimestamp(previous.candleTimestamp());
            notification.setCurrentCandleTimestamp(current.candleTimestamp());
            notification.setPreviousSnapshot(write(previous));
            notification.setCurrentSnapshot(write(detailed));
            notification.setChangeReport(write(report));
            notification = notificationRepository.save(notification);
            sendEmail(notification, report);
        }
        updateBaseline(subscription, current, fingerprint);
        return changed ? EvaluationStatus.CHANGE : EvaluationStatus.SKIPPED;
    }

    private void establishBaseline(TechnicalOutlookSubscription subscription) {
        String symbol = subscription.getStockAsset().getTickerSymbol();
        String interval = apiInterval(subscription.getInterval());
        outlookService.invalidate(subscription.getUser(), symbol, interval);
        TechnicalOutlookService.OutlookView outlook =
                outlookService.getSummaryOutlook(subscription.getUser(), symbol, interval);
        if (outlook.available() && outlook.candleTimestamp() != null) {
            updateBaseline(subscription, outlook,
                    profileFingerprint(subscription.getUser(), subscription.getInterval()));
        }
    }

    private void updateBaseline(TechnicalOutlookSubscription subscription,
                                TechnicalOutlookService.OutlookView outlook,
                                String fingerprint) {
        subscription.setBaselineSnapshot(write(outlook));
        subscription.setLastCandleTimestamp(outlook.candleTimestamp());
        subscription.setProfileFingerprint(fingerprint);
        subscription.setUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(subscription);
    }

    private void resetBaseline(TechnicalOutlookSubscription subscription) {
        subscription.setBaselineSnapshot(null);
        subscription.setLastCandleTimestamp(null);
        subscription.setProfileFingerprint(null);
    }

    private ChangeReport compare(TechnicalOutlookService.OutlookView previous,
                                 TechnicalOutlookService.OutlookView current) {
        Map<String, TechnicalOutlookService.IndicatorView> previousIndicators = new LinkedHashMap<>();
        previous.indicators().forEach(indicator -> previousIndicators.put(indicator.key(), indicator));
        List<IndicatorDelta> indicatorDeltas = current.indicators().stream().map(now -> {
            TechnicalOutlookService.IndicatorView before = previousIndicators.get(now.key());
            return new IndicatorDelta(
                    now.key(), now.label(), now.category(), now.unit(),
                    before == null ? null : before.currentValue(), now.currentValue(),
                    before == null ? "Unavailable" : before.classification(), now.classification(),
                    before == null ? 0 : before.vote(), now.vote(),
                    before == null || before.vote() != now.vote(),
                    valueChange(before == null ? null : before.currentValue(), now.currentValue()),
                    now.explanation(), now.rule());
        }).toList();

        Map<String, TechnicalOutlookService.CategoryView> previousCategories = new LinkedHashMap<>();
        previous.categories().forEach(category -> previousCategories.put(category.key(), category));
        List<CategoryDelta> categoryDeltas = current.categories().stream().map(now -> {
            TechnicalOutlookService.CategoryView before = previousCategories.get(now.key());
            return new CategoryDelta(now.key(), now.label(),
                    before == null ? "Unavailable" : before.classification(), now.classification(),
                    before == null ? 0 : before.vote(), now.vote(),
                    before == null ? 0 : before.averageInputVote(), now.averageInputVote(),
                    before == null || before.vote() != now.vote());
        }).toList();

        String direction = outlookDirection(current.headlineScore().classification());
        List<SignalEvidence> signalEvidence = current.recentSignals().stream()
                .filter(signal -> signal.timestamp() > previous.candleTimestamp()
                        && signal.timestamp() <= current.candleTimestamp())
                .map(signal -> new SignalEvidence(signal.id(), signal.family(), signal.label(),
                        signal.direction(), evidenceAlignment(direction, signal.direction()),
                        signal.timestamp(), signal.detailUrl()))
                .toList();

        List<String> decisiveDrivers = indicatorDeltas.stream()
                .filter(IndicatorDelta::voteChanged)
                .sorted(Comparator.comparingInt((IndicatorDelta delta) ->
                        Math.abs(delta.currentVote() - delta.previousVote())).reversed())
                .limit(6)
                .map(delta -> delta.label() + " changed from "
                        + displayClassification(delta.previousClassification()) + " to "
                        + displayClassification(delta.currentClassification()) + ".")
                .toList();
        String summary = changeSummary(previous, current, categoryDeltas, decisiveDrivers, signalEvidence);
        return new ChangeReport(SNAPSHOT_VERSION, summary, decisiveDrivers,
                indicatorDeltas, categoryDeltas, signalEvidence);
    }

    private String changeSummary(TechnicalOutlookService.OutlookView previous,
                                 TechnicalOutlookService.OutlookView current,
                                 List<CategoryDelta> categories,
                                 List<String> drivers,
                                 List<SignalEvidence> signals) {
        double delta = current.headlineScore().normalizedScore()
                - previous.headlineScore().normalizedScore();
        String movement = delta > 0 ? "strengthened" : delta < 0 ? "weakened" : "crossed a configured threshold";
        List<String> changedCategories = categories.stream().filter(CategoryDelta::voteChanged)
                .map(category -> category.label() + " ("
                        + displayClassification(category.previousClassification()) + " → "
                        + displayClassification(category.currentClassification()) + ")")
                .limit(4).toList();
        String categoryText = changedCategories.isEmpty()
                ? "No category vote changed; the configured classification boundary was crossed."
                : "Changed categories: " + String.join(", ", changedCategories) + ".";
        String signalText = signals.isEmpty()
                ? "No new followed technical signal was recorded between the two snapshots."
                : signals.size() + " new technical signal" + (signals.size() == 1 ? " was" : "s were")
                + " recorded and classified as supporting or contradicting evidence below.";
        String driverText = drivers.isEmpty() ? "" : " Main drivers: " + String.join(" ", drivers);
        return "%s %s from %s to %s (score %.1f%% → %.1f%%). %s %s%s".formatted(
                current.symbol(), movement,
                displayClassification(previous.headlineScore().classification()),
                displayClassification(current.headlineScore().classification()),
                previous.headlineScore().normalizedScore() * 100,
                current.headlineScore().normalizedScore() * 100,
                categoryText, signalText, driverText);
    }

    private void sendEmail(TechnicalOutlookNotification notification, ChangeReport report) {
        TechnicalOutlookSubscription subscription = notification.getSubscription();
        String symbol = subscription.getStockAsset().getTickerSymbol();
        String intervalLabel = intervalLabel(subscription.getInterval());
        String subject = symbol + " " + intervalLabel + " outlook changed: "
                + displayClassification(notification.getPreviousClassification()) + " → "
                + displayClassification(notification.getCurrentClassification());
        StringBuilder body = new StringBuilder(report.summary()).append("\n\nIndicator changes:\n");
        report.indicatorDeltas().stream().filter(IndicatorDelta::voteChanged).limit(10).forEach(delta ->
                body.append("- ").append(delta.label()).append(": ")
                        .append(formatValue(delta.previousValue(), delta.unit())).append(" (")
                        .append(displayClassification(delta.previousClassification())).append(") → ")
                        .append(formatValue(delta.currentValue(), delta.unit())).append(" (")
                        .append(displayClassification(delta.currentClassification())).append("). ")
                        .append(delta.currentExplanation()).append('\n'));
        if (report.signalEvidence().isEmpty()) {
            body.append("\nTechnical signal evidence: no new signal between snapshots.\n");
        } else {
            body.append("\nTechnical signal evidence:\n");
            report.signalEvidence().forEach(signal -> body.append("- ").append(signal.label())
                    .append(" (").append(signal.direction()).append("): ")
                    .append(signal.alignment().toLowerCase(Locale.ROOT)).append(" the new outlook.\n"));
        }
        body.append("\nCompleted candle: ")
                .append(PERIOD_FORMAT.format(Instant.ofEpochSecond(notification.getCurrentCandleTimestamp())))
                .append("\nDetailed report: ").append(publicBaseUrl)
                .append("/technical-outlook/changes/").append(notification.getId());
        try {
            if (notificationService.sendTechnicalOutlookChangeEmail(
                    subscription.getUser(), subscription.getInterval(), subject, body.toString())) {
                notification.setEmailSentAt(LocalDateTime.now());
                notificationRepository.save(notification);
            }
        } catch (RuntimeException exception) {
            System.err.println("Technical outlook email failed for " + symbol + " "
                    + subscription.getInterval() + ": " + exception.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<LatestOutlookChangeView> latestUnread(User user, int requestedLimit) {
        int limit = Math.max(1, Math.min(50, requestedLimit));
        return notificationRepository.findLatestUnreadForUser(user, PageRequest.of(0, limit)).stream()
                .map(this::latestView)
                .toList();
    }

    @Transactional
    public OutlookChangeDetailView detail(User user, Long id) {
        TechnicalOutlookNotification notification = notificationRepository.findOwnedById(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Technical outlook change was not found."));
        if (!notification.isRead()) {
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
        }
        TechnicalOutlookSubscription subscription = notification.getSubscription();
        return new OutlookChangeDetailView(notification.getId(),
                subscription.getStockAsset().getTickerSymbol(),
                subscription.getStockAsset().getCompanyName(),
                subscription.getInterval(), intervalLabel(subscription.getInterval()),
                notification.getPreviousClassification(), notification.getCurrentClassification(),
                notification.getPreviousScore(), notification.getCurrentScore(),
                notification.getPreviousCandleTimestamp(), notification.getCurrentCandleTimestamp(),
                notification.getCreatedAt(),
                readOutlook(notification.getPreviousSnapshot()),
                readOutlook(notification.getCurrentSnapshot()),
                readReport(notification.getChangeReport()));
    }

    private LatestOutlookChangeView latestView(TechnicalOutlookNotification notification) {
        TechnicalOutlookSubscription subscription = notification.getSubscription();
        return new LatestOutlookChangeView(notification.getId(),
                subscription.getStockAsset().getTickerSymbol(),
                subscription.getStockAsset().getCompanyName(),
                subscription.getInterval(), intervalLabel(subscription.getInterval()),
                notification.getPreviousClassification(), notification.getCurrentClassification(),
                notification.getPreviousScore(), notification.getCurrentScore(),
                notification.getCurrentCandleTimestamp(),
                PERIOD_FORMAT.format(Instant.ofEpochSecond(notification.getCurrentCandleTimestamp())),
                notification.getCreatedAt(), notification.isRead(),
                "/technical-outlook/changes/" + notification.getId());
    }

    private void enforceCapacity(User user, StockAsset asset) {
        Boolean userAlreadyTracks = jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from alert_rules where user_id = ? and stock_asset_id = ? and is_active = true
                    union all
                    select 1 from technical_outlook_subscriptions
                    where user_id = ? and stock_asset_id = ? and is_active = true
                )
                """, Boolean.class, user.getId(), asset.getId(), user.getId(), asset.getId());
        if (!Boolean.TRUE.equals(userAlreadyTracks)) {
            Integer userCount = jdbcTemplate.queryForObject("""
                    select count(distinct stock_asset_id) from (
                        select stock_asset_id from alert_rules where user_id = ? and is_active = true
                        union
                        select stock_asset_id from technical_outlook_subscriptions
                        where user_id = ? and is_active = true
                    ) tracked
                    """, Integer.class, user.getId(), user.getId());
            if (userCount != null && userCount >= maxTrackedStocksPerUser) {
                throw new TechnicalOutlookCapacityException("You can track at most " + maxTrackedStocksPerUser
                        + " companies across technical alerts and automated outlooks.");
            }
        }
        Boolean globallyTracked = jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from alert_rules where stock_asset_id = ? and is_active = true
                    union all
                    select 1 from technical_outlook_subscriptions
                    where stock_asset_id = ? and is_active = true
                )
                """, Boolean.class, asset.getId(), asset.getId());
        if (!Boolean.TRUE.equals(globallyTracked)) {
            Integer globalCount = jdbcTemplate.queryForObject("""
                    select count(distinct stock_asset_id) from (
                        select stock_asset_id from alert_rules where is_active = true
                        union
                        select stock_asset_id from technical_outlook_subscriptions where is_active = true
                    ) tracked
                    """, Integer.class);
            if (globalCount != null && globalCount >= maxGlobalTrackedStocks) {
                throw new TechnicalOutlookCapacityException(
                        "Global market-data tracking capacity has been reached.");
            }
        }
    }

    @Scheduled(cron = "${technical-outlook.notifications.cleanup-cron:0 45 2 * * *}")
    @Transactional
    public void cleanupExpiredNotifications() {
        notificationRepository.deleteOlderThan(LocalDateTime.now().minusDays(retentionDays));
    }

    private String profileFingerprint(User user, TimeInterval interval) {
        return sha256(write(preferencesService.get(user).profile(interval)));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Technical outlook snapshot could not be stored.", exception);
        }
    }

    private TechnicalOutlookService.OutlookView readOutlook(String payload) {
        try {
            return objectMapper.readValue(payload, TechnicalOutlookService.OutlookView.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored technical outlook snapshot could not be read.", exception);
        }
    }

    private ChangeReport readReport(String payload) {
        try {
            return objectMapper.readValue(payload, ChangeReport.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored technical outlook change report could not be read.", exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static double valueChange(Double previous, Double current) {
        if (previous == null || current == null) return 0;
        return current - previous;
    }

    private static String evidenceAlignment(String outlookDirection, String signalDirection) {
        if (outlookDirection.equals("NEUTRAL")) return "MIXED";
        return outlookDirection.equalsIgnoreCase(signalDirection) ? "SUPPORTS" : "CONTRADICTS";
    }

    private static String outlookDirection(String classification) {
        String value = classification == null ? "" : classification.toLowerCase(Locale.ROOT);
        if (value.contains("buy")) return "BUY";
        if (value.contains("sell")) return "SELL";
        return "NEUTRAL";
    }

    private static String displayClassification(String value) {
        if (value == null || value.isBlank()) return "Unavailable";
        String normalized = value.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static String formatValue(Double value, String unit) {
        if (value == null || !Double.isFinite(value)) return "unavailable";
        return "%.3f%s".formatted(value, unit == null || unit.isBlank() ? "" : " " + unit);
    }

    private static void requireSupported(TimeInterval interval) {
        if (!SUPPORTED_INTERVALS.contains(interval)) {
            throw new IllegalArgumentException("Automated Technical Outlook supports daily, weekly, and monthly intervals.");
        }
    }

    public static String apiInterval(TimeInterval interval) {
        requireSupported(interval);
        return interval.analysisApiValue();
    }

    public static String intervalLabel(TimeInterval interval) {
        return interval.analysisLabel();
    }

    private enum EvaluationStatus { BASELINE, CHANGE, SKIPPED }

    public record SubscriptionStateView(String symbol, Map<String, Boolean> intervals) { }
    public record EvaluationResult(int baselinesEstablished, int changesCreated, int skipped) { }
    public record IndicatorDelta(String key, String label, String category, String unit,
                                 Double previousValue, Double currentValue,
                                 String previousClassification, String currentClassification,
                                 int previousVote, int currentVote, boolean voteChanged,
                                 double valueChange, String currentExplanation, String rule) { }
    public record CategoryDelta(String key, String label,
                                String previousClassification, String currentClassification,
                                int previousVote, int currentVote,
                                double previousAverageVote, double currentAverageVote,
                                boolean voteChanged) { }
    public record SignalEvidence(Long id, String family, String label, String direction,
                                 String alignment, long timestamp, String detailUrl) { }
    public record ChangeReport(String version, String summary, List<String> decisiveDrivers,
                               List<IndicatorDelta> indicatorDeltas,
                               List<CategoryDelta> categoryDeltas,
                               List<SignalEvidence> signalEvidence) { }
    public record LatestOutlookChangeView(Long id, String symbol, String companyName,
                                          TimeInterval interval, String intervalLabel,
                                          String previousClassification, String currentClassification,
                                          double previousScore, double currentScore,
                                          long candleTimestamp, String candlePeriodLabel,
                                          LocalDateTime createdAt, boolean hasBeenRead,
                                          String detailUrl) { }
    public record OutlookChangeDetailView(Long id, String symbol, String companyName,
                                          TimeInterval interval, String intervalLabel,
                                          String previousClassification, String currentClassification,
                                          double previousScore, double currentScore,
                                          long previousCandleTimestamp, long currentCandleTimestamp,
                                          LocalDateTime createdAt,
                                          TechnicalOutlookService.OutlookView previousSnapshot,
                                          TechnicalOutlookService.OutlookView currentSnapshot,
                                          ChangeReport report) { }
}
