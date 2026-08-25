create index if not exists ix_alert_rules_user_asset_interval
    on alert_rules (user_id, stock_asset_id, interval);

create index if not exists ix_alert_events_recent_visible
    on alert_events (alert_rule_id, signal_candle_timestamp desc, id desc)
    where deleted_at is null;

create index if not exists ix_congressional_trades_ticker_date
    on congressional_trades (lower(ticker_symbol), transaction_date desc, id desc);

create index if not exists ix_insider_trades_ticker_date
    on insider_trades (lower(ticker_symbol), transaction_date desc, id desc);
