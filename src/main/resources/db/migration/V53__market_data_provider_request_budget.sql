create table if not exists market_data_provider_usage (
    provider varchar(32) not null,
    window_kind varchar(16) not null,
    window_started_at timestamp with time zone not null,
    request_count integer not null,
    blocked_until timestamp with time zone,
    updated_at timestamp with time zone not null default current_timestamp,
    primary key (provider, window_kind),
    constraint ck_market_data_provider_window_kind
        check (window_kind in ('MINUTE', 'DAY')),
    constraint ck_market_data_provider_request_count
        check (request_count >= 0)
);

