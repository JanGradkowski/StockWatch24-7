-- Partial bootstrap success must survive restarts; it is not proof of complete history.
create table market_data_analysis_state (
    symbol varchar(64) not null,
    interval varchar(16) not null,
    requested integer not null,
    received integer not null,
    checked_at timestamptz not null default current_timestamp,
    primary key(symbol, interval)
);

-- Immutable, versioned computation checkpoints. No user identity or email content is stored.
create table analysis_checkpoints (
    cache_key varchar(64) primary key,
    payload text not null,
    completed_nodes bigint not null default 0,
    updated_at timestamptz not null default current_timestamp
);
create index analysis_checkpoints_age on analysis_checkpoints(updated_at);

-- Claims include infrastructure recovery; only actual processing failures use the retry limit.
alter table insider_activity_check_jobs add column calculation_failures integer not null default 0;
