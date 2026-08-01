create table anchored_volume_profile_refresh_state (
    user_id bigint not null references users(id) on delete cascade,
    symbol varchar(20) not null,
    chart_interval varchar(16) not null,
    active_candle_timestamp bigint not null,
    refresh_count integer not null default 0,
    last_refreshed_at timestamp with time zone not null default current_timestamp,
    provider_source varchar(32) not null,
    snapshot_candle_timestamp bigint not null,
    snapshot_open double precision not null,
    snapshot_high double precision not null,
    snapshot_low double precision not null,
    snapshot_close double precision not null,
    snapshot_volume bigint not null,
    primary key (user_id, symbol, chart_interval, active_candle_timestamp),
    constraint ck_anchored_volume_profile_refresh_count
        check (refresh_count between 0 and 2)
);

create index ix_anchored_volume_profile_refresh_cleanup
    on anchored_volume_profile_refresh_state (last_refreshed_at);
