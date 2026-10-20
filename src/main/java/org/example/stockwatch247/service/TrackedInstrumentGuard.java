package org.example.stockwatch247.service;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collection;
import java.util.Set;

/**
 * The single gate every monitored instrument passes through: it must have returned real
 * market data, and it must fit the per-account and deployment-wide tracking capacity.
 * Technical alerts and automated outlooks share one definition of a tracked instrument.
 */
@Service
public class TrackedInstrumentGuard {
    private static final String QUOTA_LOCK = "alert-stock-quota";
    private static final String TRACKED_FOR_USER = """
            select stock_asset_id from alert_rules where user_id = ? and is_active = true
            union
            select stock_asset_id from technical_outlook_subscriptions where user_id = ? and is_active = true
            union
            select target.stock_asset_id from custom_alert_effective_targets target
            join custom_alerts alert on alert.id = target.alert_id where alert.user_id = ? and alert.active
            """;
    private static final String TRACKED_GLOBALLY = """
            select stock_asset_id from alert_rules where is_active = true
            union
            select stock_asset_id from technical_outlook_subscriptions where is_active = true
            union
            select target.stock_asset_id from custom_alert_effective_targets target
            join custom_alerts alert on alert.id = target.alert_id where alert.active
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StockAssetRepository assets;
    private final CandleRepository candles;
    private final YahooFinanceService yahooFinanceService;
    private final TwelveDataService twelveDataService;
    private final Clock clock;
    private final boolean verificationRequired;
    private final int maximumPerUser;
    private final int maximumGlobal;

    public TrackedInstrumentGuard(JdbcTemplate jdbcTemplate,
                                  StockAssetRepository assets,
                                  CandleRepository candles,
                                  YahooFinanceService yahooFinanceService,
                                  TwelveDataService twelveDataService,
                                  Clock clock,
                                  @Value("${market-data.require-verification:true}") boolean verificationRequired,
                                  @Value("${alerts.max-tracked-stocks-per-user:300}") int maximumPerUser,
                                  @Value("${alerts.max-global-tracked-stocks:500}") int maximumGlobal) {
        this.jdbcTemplate = jdbcTemplate;
        this.assets = assets;
        this.candles = candles;
        this.yahooFinanceService = yahooFinanceService;
        this.twelveDataService = twelveDataService;
        this.clock = clock;
        this.verificationRequired = verificationRequired;
        this.maximumPerUser = Math.max(1, maximumPerUser);
        this.maximumGlobal = Math.max(this.maximumPerUser, maximumGlobal);
    }

    public int maximumPerUser() {
        return maximumPerUser;
    }

    private static final java.util.regex.Pattern ROUND_THE_CLOCK_SYMBOL =
            java.util.regex.Pattern.compile(".+(-(USD|USDT|USDC|EUR|GBP|JPY|BTC|ETH)|=X)");

    /**
     * Crypto pairs (BTC-USD) and currency pairs (EURUSD=X) trade around the clock, so they have no
     * exchange session, weekend gap or official close; the candle-completion, gap and trading-day
     * rules that alerts and outlooks rely on do not hold for them. Share classes such as BRK-B pass.
     */
    public static void requireExchangeSession(String symbol) {
        if (symbol != null && ROUND_THE_CLOCK_SYMBOL.matcher(symbol.toUpperCase(java.util.Locale.ROOT)).matches()) {
            throw new UserInputException("Alerts and outlook tracking cover exchange-traded stocks, funds and indices. "
                    + symbol + " trades around the clock (crypto or currency pair), so the candle rules do not apply to it.");
        }
    }

    /**
     * Rejects listings that no provider can price. A ticker that never returns candles would
     * otherwise be queued for analysis forever and consume provider budget on every run.
     */
    public StockAsset requireMarketData(StockAsset asset) {
        requireExchangeSession(asset.getTickerSymbol());
        if (!verificationRequired || asset.getMarketDataVerifiedAt() != null) return asset;
        String symbol = asset.getTickerSymbol();
        boolean found = candles.countBySymbolAndTimeInterval(symbol, "1d") > 0;
        boolean providerUnavailable = false;
        if (!found) {
            try {
                // Yahoo first: verification must not spend the shared Twelve Data allowance.
                found = !yahooFinanceService.getTimeSeries(symbol, "1d", 5).isEmpty();
            } catch (YahooFinanceService.ProviderUnavailableException unavailable) {
                providerUnavailable = true;
            } catch (RuntimeException noData) {
                found = false;
            }
        }
        if (!found) {
            try {
                found = !twelveDataService.getTimeSeries(symbol, "1day", 5).isEmpty();
            } catch (MarketDataProviderRequestBudget.BudgetUnavailableException exhausted) {
                providerUnavailable = true;
            } catch (RuntimeException noData) {
                found = false;
            }
        }
        if (!found) {
            if (providerUnavailable) {
                throw new MarketDataUnavailableException(symbol, "1d",
                        "Market data providers were unavailable while verifying a new listing.");
            }
            throw new UserInputException("No market data was found for " + symbol
                    + ". Check the ticker and exchange, then try again.");
        }
        StockAsset current = assets.findById(asset.getId()).orElse(asset);
        current.setMarketDataVerifiedAt(clock.instant());
        return assets.save(current);
    }

    /**
     * Finds or creates the listing for a ticker someone typed. Yahoo is asked first because it has no
     * shared request allowance; Twelve Data is the fallback.
     */
    public StockAsset resolveListing(String symbol) {
        var existing = assets.findByTickerSymbolIgnoreCase(symbol);
        if (existing.isPresent()) return existing.get();
        try {
            return yahooFinanceService.refreshStockAssetMetadata(symbol);
        } catch (RuntimeException yahooUnavailable) {
            return twelveDataService.refreshStockAssetMetadata(symbol);
        }
    }

    /** Serializes quota decisions so concurrent requests cannot race past either cap. */
    public void enforceCapacity(User user, StockAsset asset) {
        jdbcTemplate.queryForObject(
                "select lock_name from security_resource_locks where lock_name = ? for update",
                String.class, QUOTA_LOCK);
        if (!tracksForUser(user, asset) && trackedCount(user) >= maximumPerUser) {
            throw new TechnicalOutlookCapacityException("You can track at most " + maximumPerUser
                    + " instruments across technical alerts and automated outlooks.");
        }
        if (!trackedGlobally(asset) && globalCount() >= maximumGlobal) {
            throw new TechnicalOutlookCapacityException(
                    "Global market-data tracking capacity has been reached.");
        }
    }

    /**
     * Whether adding these instruments keeps the account and the deployment within their capacity: the
     * check for applying a custom alert to many stocks at once. Instruments already tracked cost nothing.
     */
    public void enforceCapacity(User user, Collection<Long> assetIds) {
        enforceCapacity(user, assetIds, null);
    }

    /**
     * The same check for a custom alert being saved: its own stocks are left out of what is already tracked,
     * so the stocks it is about to cover count as new.
     */
    public void enforceCapacity(User user, Collection<Long> assetIds, Long customAlertId) {
        if (assetIds.isEmpty()) return;
        long except = customAlertId == null ? -1 : customAlertId;
        jdbcTemplate.queryForObject(
                "select lock_name from security_resource_locks where lock_name = ? for update",
                String.class, QUOTA_LOCK);
        Long[] ids = assetIds.toArray(Long[]::new);
        String trackedExcept = TRACKED_FOR_USER.replace("where alert.user_id = ? and alert.active",
                "where alert.user_id = ? and alert.active and alert.id <> ?");
        long newForUser = count(connection -> {
            var statement = connection.prepareStatement("select count(*) from unnest(?) id where id not in (" + trackedExcept + ")");
            statement.setArray(1, connection.createArrayOf("bigint", ids));
            for (int index = 2; index <= 4; index++) statement.setLong(index, user.getId());
            statement.setLong(5, except);
            return statement;
        });
        long tracked = count(connection -> {
            var statement = connection.prepareStatement("select count(*) from (" + trackedExcept + ") tracked");
            for (int index = 1; index <= 3; index++) statement.setLong(index, user.getId());
            statement.setLong(4, except);
            return statement;
        });
        long room = Math.max(0, maximumPerUser - tracked);
        if (newForUser > room) {
            throw new TechnicalOutlookCapacityException("This alert would track " + newForUser
                    + " more instruments, but your account has room for " + room
                    + ". Apply it to fewer stocks or stop following others.");
        }
        long newGlobally = count(connection -> {
            var statement = connection.prepareStatement("select count(*) from unnest(?) id where id not in ("
                    + TRACKED_GLOBALLY.replace("where alert.active", "where alert.active and alert.id <> ?") + ")");
            statement.setArray(1, connection.createArrayOf("bigint", ids));
            statement.setLong(2, except);
            return statement;
        });
        long globallyTracked = count(connection -> {
            var statement = connection.prepareStatement("select count(*) from ("
                    + TRACKED_GLOBALLY.replace("where alert.active", "where alert.active and alert.id <> ?") + ") tracked");
            statement.setLong(1, except);
            return statement;
        });
        if (newGlobally > 0 && globallyTracked + newGlobally > maximumGlobal) {
            throw new TechnicalOutlookCapacityException("Global market-data tracking capacity has been reached.");
        }
    }

    private long count(org.springframework.jdbc.core.PreparedStatementCreator query) {
        Long value = jdbcTemplate.query(query, result -> result.next() ? result.getLong(1) : 0L);
        return value == null ? 0 : value;
    }

    public long trackedCount(User user) {
        Long count = jdbcTemplate.queryForObject("select count(*) from (" + TRACKED_FOR_USER + ") tracked",
                Long.class, user.getId(), user.getId(), user.getId());
        return count == null ? 0 : count;
    }

    /** Instruments this account tracks now, among the requested symbols. */
    public Set<String> trackedSymbols(User user, Collection<String> symbols) {
        if (symbols.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(symbols.size(), "?"));
        java.util.List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(user.getId());
        arguments.add(user.getId());
        arguments.add(user.getId());
        arguments.addAll(symbols);
        return Set.copyOf(jdbcTemplate.queryForList(
                "select a.ticker_symbol from (" + TRACKED_FOR_USER + ") tracked "
                        + "join stock_assets a on a.id = tracked.stock_asset_id where a.ticker_symbol in (" + placeholders + ")",
                String.class, arguments.toArray()));
    }

    public long globalCount() {
        Long count = jdbcTemplate.queryForObject("select count(*) from (" + TRACKED_GLOBALLY + ") tracked", Long.class);
        return count == null ? 0 : count;
    }

    public Set<String> globallyTrackedSymbols() {
        return Set.copyOf(jdbcTemplate.queryForList(
                "select a.ticker_symbol from (" + TRACKED_GLOBALLY + ") tracked join stock_assets a on a.id = tracked.stock_asset_id",
                String.class));
    }

    private boolean tracksForUser(User user, StockAsset asset) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from (" + TRACKED_FOR_USER + ") tracked where stock_asset_id = ?)",
                Boolean.class, user.getId(), user.getId(), user.getId(), asset.getId()));
    }

    private boolean trackedGlobally(StockAsset asset) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from (" + TRACKED_GLOBALLY + ") tracked where stock_asset_id = ?)",
                Boolean.class, asset.getId()));
    }
}
