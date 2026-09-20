package org.example.stockwatch247.service;

/** Shared archive/count scope: retain recorded origins, with a fallback for pre-watchlist history. */
final class WatchlistSignalScope {
    private WatchlistSignalScope() {}

    static String matches(String kind, String event, String asset, String type, String interval,
                          String direction, String list) {
        return """
                (exists(select 1 from watchlist_notification_origins origin
                    where origin.kind='%s' and origin.notification_id=%s and origin.watchlist_id=%s)
                 or (not exists(select 1 from watchlist_notification_origins origin
                        where origin.kind='%s' and origin.notification_id=%s)
                     and exists(select 1 from watchlist_member_signals requested
                        where requested.watchlist_id=%s and requested.stock_asset_id=%s
                          and requested.signal_type=%s and requested.interval=%s and requested.direction=%s)))
                """.formatted(kind, event, list, kind, event, list, asset, type, interval, direction);
    }
}
