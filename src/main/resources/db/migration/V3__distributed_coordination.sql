create table if not exists rate_limit_windows (
    counter_key varchar(64) primary key,
    window_started_at bigint not null,
    request_count integer not null
);

create table if not exists market_data_sync_state (
    symbol varchar(20) not null,
    time_interval varchar(16) not null,
    last_success_at timestamp with time zone,
    lease_owner varchar(36),
    lease_until timestamp with time zone,
    primary key (symbol, time_interval)
);

create table if not exists live_quote_cache (
    symbol varchar(20) primary key,
    quote_json text not null,
    cached_at timestamp with time zone not null default current_timestamp
);

create table if not exists alert_check_jobs (
    id bigserial primary key,
    symbol varchar(20) not null,
    interval varchar(32) not null,
    status varchar(16) not null,
    available_at timestamp with time zone not null default current_timestamp,
    lease_until timestamp with time zone,
    attempts integer not null default 0,
    last_error varchar(1000),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint ck_alert_check_job_status
        check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

create unique index if not exists ux_alert_check_jobs_active
    on alert_check_jobs (symbol, interval)
    where status in ('PENDING', 'PROCESSING');

create index if not exists ix_alert_check_jobs_claim
    on alert_check_jobs (status, available_at, lease_until, id);
