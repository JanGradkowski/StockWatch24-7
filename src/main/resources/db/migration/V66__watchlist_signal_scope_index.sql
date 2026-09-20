create index ix_watchlist_origins_signal_scope
    on watchlist_notification_origins(kind, notification_id, watchlist_id);
