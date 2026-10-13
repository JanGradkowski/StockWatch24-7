-- Alerts set on chart drawings (lines, channels, rectangles) and indicators. The scheduled checks
-- evaluate them on completed candles; each trigger is a chart_alert_events row that appears in
-- Latest signals and is emailed when the user's email alerts allow it.
create table chart_alerts (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    symbol varchar(20) not null,
    interval varchar(16) not null check (interval in ('DAILY', 'WEEKLY', 'MONTHLY')),
    source_kind varchar(16) not null check (source_kind in ('DRAWING', 'INDICATOR')),
    source_id varchar(100) not null,
    label varchar(160) not null,
    condition varchar(24) not null,
    definition text not null,
    frequency varchar(16) not null check (frequency in ('ONCE', 'EVERY_BAR')),
    active boolean not null default true,
    -- The newest completed candle already evaluated; only later candles can trigger the alert.
    last_checked_timestamp bigint,
    trigger_count integer not null default 0,
    last_triggered_at timestamp without time zone,
    created_at timestamp without time zone not null default current_timestamp,
    updated_at timestamp without time zone not null default current_timestamp
);

create index ix_chart_alerts_active on chart_alerts (symbol, interval) where active;
create index ix_chart_alerts_user on chart_alerts (user_id, symbol);

create table chart_alert_events (
    id bigserial primary key,
    alert_id bigint not null references chart_alerts(id) on delete cascade,
    candle_timestamp bigint not null,
    close_price double precision not null,
    -- What was compared: the close (drawings) or the indicator output, and the level it was compared with.
    value double precision,
    level double precision,
    created_at timestamp without time zone not null default current_timestamp,
    email_queued_at timestamp without time zone,
    read_at timestamp without time zone,
    constraint uq_chart_alert_event unique (alert_id, candle_timestamp)
);

create index ix_chart_alert_events_alert on chart_alert_events (alert_id, created_at desc);
