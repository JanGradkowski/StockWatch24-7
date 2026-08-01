create table insider_activity_schedule_runs (
    scheduled_for timestamp with time zone primary key,
    created_at timestamp with time zone not null default current_timestamp
);

create table insider_activity_check_jobs (
    id bigserial primary key,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    ticker_symbol varchar(20) not null,
    scheduled_for timestamp with time zone not null,
    status varchar(16) not null default 'PENDING',
    available_at timestamp with time zone not null default current_timestamp,
    lease_until timestamp with time zone,
    attempts integer not null default 0,
    last_error varchar(1000),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint ck_insider_activity_check_job_status
        check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    constraint uk_insider_activity_check_job_schedule
        unique (stock_asset_id, scheduled_for)
);

create index ix_insider_activity_schedule_runs_latest
    on insider_activity_schedule_runs (scheduled_for desc);

create index ix_insider_activity_check_jobs_claim
    on insider_activity_check_jobs (status, available_at, lease_until, scheduled_for, id);
