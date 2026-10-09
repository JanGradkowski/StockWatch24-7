-- Older NamedWatchlistIntegrationTest runs used the application's schema.
-- Match every fixture marker; preserve accounts, assets and signal history.
create temporary table leaked_watchlist_test_users on commit drop as
select id from users
where email ~ '^watchlist-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}@example\.test$'
  and password_hash = 'test-not-used'
  and first_name = 'Watchlist' and last_name = 'Test';

update alert_rules set is_active = false
where is_active and user_id in (select id from leaked_watchlist_test_users);
update technical_outlook_subscriptions set is_active = false
where is_active and user_id in (select id from leaked_watchlist_test_users);
update congressional_trade_subscriptions set active = false
where active and user_id in (select id from leaked_watchlist_test_users);
update insider_trade_subscriptions set active = false
where active and user_id in (select id from leaked_watchlist_test_users);

update watchlist_signal_settings set watched = false, email_enabled = false
where watchlist_id in (select id from watchlists where user_id in (select id from leaked_watchlist_test_users));

-- Pending jobs for real instruments can still serve real users. Cancel only the
-- generated assets, and leave any currently leased job to its owner.
update alert_check_jobs set status = 'FAILED', lease_until = null,
    last_error = 'Quarantined named-watchlist integration fixture', updated_at = current_timestamp
where (status = 'PENDING' or (status = 'PROCESSING' and lease_until <= current_timestamp))
  and symbol in (select ticker_symbol from stock_assets
                where ticker_symbol ~ '^WL[0-9A-F]{8}$' and company_name = 'Named watchlist test');
update insider_activity_check_jobs set status = 'FAILED', lease_until = null,
    last_error = 'Quarantined named-watchlist integration fixture', updated_at = current_timestamp
where (status = 'PENDING' or (status = 'PROCESSING' and lease_until <= current_timestamp))
  and stock_asset_id in (select id from stock_assets
                        where ticker_symbol ~ '^WL[0-9A-F]{8}$' and company_name = 'Named watchlist test');
