-- Watchlist entries that still ask for technical monitoring the user has already unfollowed.
-- Before the unfollow fix, unfollowing switched rules off but left these rows, so a later
-- watchlist change could switch the rules back on. Run the report first; run the delete only
-- after reviewing it. The delete is not a Flyway migration on purpose.

-- 1. Dry run: what would be removed.
with stale as (
    select s.watchlist_id, s.stock_asset_id, s.signal_type, s.interval, s.direction, w.user_id
    from watchlist_member_signals s
    join watchlists w on w.id = s.watchlist_id
    where (s.signal_type in ('CANDLESTICK', 'ELLIOTT_WAVE', 'HARMONIC_FORMATION')
           and not exists (select 1 from alert_rules r
                           where r.user_id = w.user_id and r.stock_asset_id = s.stock_asset_id
                             and coalesce(r.pattern_family, 'CANDLESTICK') = s.signal_type
                             and r.interval = s.interval and r.trade_signal = s.direction and r.is_active))
       or (s.signal_type = 'OUTLOOK'
           and not exists (select 1 from technical_outlook_subscriptions o
                           where o.user_id = w.user_id and o.stock_asset_id = s.stock_asset_id
                             and o.interval = s.interval and o.is_active))
)
select signal_type, count(*) as rows_to_remove, count(distinct user_id) as users, count(distinct stock_asset_id) as instruments
from stale group by signal_type order by signal_type;

-- 2. Apply (only after reviewing the report):
-- delete from watchlist_member_signals s using watchlists w
-- where s.watchlist_id = w.id
--   and ((s.signal_type in ('CANDLESTICK', 'ELLIOTT_WAVE', 'HARMONIC_FORMATION')
--         and not exists (select 1 from alert_rules r
--                         where r.user_id = w.user_id and r.stock_asset_id = s.stock_asset_id
--                           and coalesce(r.pattern_family, 'CANDLESTICK') = s.signal_type
--                           and r.interval = s.interval and r.trade_signal = s.direction and r.is_active))
--     or (s.signal_type = 'OUTLOOK'
--         and not exists (select 1 from technical_outlook_subscriptions o
--                         where o.user_id = w.user_id and o.stock_asset_id = s.stock_asset_id
--                           and o.interval = s.interval and o.is_active)));
