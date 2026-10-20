-- Shared analysis store (docs/shared-analysis-store-plan.md): one durable store of pattern results shared by the
-- chart, custom alert baselines, scheduled checks and previews, prepared early for stocks people open or follow.

-- An entry is valid for exactly the inputs it was computed from: a fingerprint of the completed candles it read
-- (or of the inputs its caller supplies). New candles replace it once they complete; intraday updates of the
-- forming candle no longer invalidate it, as the old candle generation did.
alter table historical_signal_cache add column inputs_fingerprint varchar(64);

-- Stocks waiting for their latest snapshot with factory settings, best priority first.
create table analysis_requests (
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    interval varchar(16) not null check (interval in ('DAILY', 'WEEKLY', 'MONTHLY')),
    -- 0 followed, 2 opened by several people in a week, 3 opened once.
    priority smallint not null,
    requested_at timestamp with time zone not null default current_timestamp,
    lease_owner varchar(64),
    lease_until timestamp with time zone,
    attempts integer not null default 0,
    last_error varchar(500),
    primary key (stock_asset_id, interval)
);
create index ix_analysis_requests_queue on analysis_requests (priority, requested_at);

-- Opened stock pages, kept a week: who opened which stock on which day, and when that queued work (the
-- per-person hourly limit).
create table analysis_lookups (
    user_id bigint not null references users(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    viewed_on date not null default current_date,
    queued_at timestamp with time zone,
    primary key (user_id, stock_asset_id, viewed_on)
);
create index ix_analysis_lookups_stock on analysis_lookups (stock_asset_id, viewed_on);
create index ix_analysis_lookups_queued on analysis_lookups (user_id, queued_at) where queued_at is not null;

-- Elliott histories: previews first (0), then stocks several people opened (2), then followed ones (3). Each
-- history also notes the candles it was read from, so a corrected candle redoes what it changed.
alter table custom_alert_elliott_coverage add column priority smallint not null default 0;
alter table custom_alert_elliott_coverage add column prefix_fingerprint varchar(64);
alter table custom_alert_elliott_coverage add column tail_fingerprints varchar(400);
drop index ix_custom_alert_elliott_coverage_queue;
create index ix_custom_alert_elliott_coverage_queue on custom_alert_elliott_coverage (priority, requested_at)
    where status in ('PENDING', 'RUNNING');
