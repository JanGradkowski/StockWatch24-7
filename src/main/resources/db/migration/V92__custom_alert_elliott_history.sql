-- Day-by-day Elliott Wave history for custom alert previews. A wave count is redrawn as candles arrive, so a
-- replay must read each candle's count as it stood on that candle: one full wave scan per candle. A background
-- job does that once per stock, interval and detection settings, stores what each candle's count showed, and
-- extends it as new candles complete; previews then read it instead of scanning.
-- Design: docs/custom-alerts-plan.md (Implementation notes).

create table custom_alert_elliott_coverage (
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    interval varchar(16) not null check (interval in ('DAILY', 'WEEKLY', 'MONTHLY')),
    -- Fingerprint of the detection rules, rule version and child timeframe the counts were read with.
    settings_key varchar(64) not null,
    -- Whose Elliott settings to read (null: the factory settings). The fingerprint guards against later edits.
    user_id bigint references users(id) on delete set null,
    status varchar(16) not null default 'PENDING' check (status in ('PENDING', 'RUNNING', 'READY', 'FAILED')),
    -- Candles whose own count is stored, as a contiguous range of candle timestamps.
    covered_from bigint,
    covered_to bigint,
    requested_at timestamp with time zone not null default current_timestamp,
    used_at timestamp with time zone not null default current_timestamp,
    lease_owner varchar(64),
    lease_until timestamp with time zone,
    attempts integer not null default 0,
    last_error varchar(500),
    updated_at timestamp with time zone not null default current_timestamp,
    primary key (stock_asset_id, interval, settings_key),
    check ((covered_from is null) = (covered_to is null) and (covered_from is null or covered_from <= covered_to))
);
create index ix_custom_alert_elliott_coverage_queue on custom_alert_elliott_coverage (requested_at)
    where status in ('PENDING', 'RUNNING');

-- One row per wave signal: the candle it was confirmed on and the first candle whose count showed it.
create table custom_alert_elliott_signals (
    stock_asset_id bigint not null,
    interval varchar(16) not null,
    settings_key varchar(64) not null,
    pattern varchar(64) not null,
    stage varchar(48) not null,
    bullish boolean not null,
    score integer not null,
    confirmed_at bigint not null,
    known_at bigint not null check (known_at >= confirmed_at),
    anchor_at bigint not null,
    price double precision,
    primary key (stock_asset_id, interval, settings_key, pattern, stage, confirmed_at),
    foreign key (stock_asset_id, interval, settings_key)
        references custom_alert_elliott_coverage (stock_asset_id, interval, settings_key) on delete cascade
);
