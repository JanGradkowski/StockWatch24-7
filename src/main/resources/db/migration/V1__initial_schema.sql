create table if not exists users (
    id bigserial primary key,
    email varchar(255) not null unique,
    password_hash varchar(255) not null,
    first_name varchar(100) not null,
    last_name varchar(100) not null,
    is_2fa_enabled boolean not null default false,
    two_factor_secret varchar(255),
    is_verified boolean not null default false,
    created_at timestamp without time zone not null default current_timestamp
);

create table if not exists stock_assets (
    id bigserial primary key,
    ticker_symbol varchar(20) not null unique,
    company_name varchar(255) not null,
    exchange varchar(255) not null,
    currency varchar(10)
);

create table if not exists candles (
    id bigserial primary key,
    symbol varchar(20) not null,
    time_interval varchar(16) not null,
    timestamp bigint not null,
    open_price double precision,
    high_price double precision,
    low_price double precision,
    close_price double precision,
    volume bigint,
    constraint uk_candles_symbol_interval_timestamp unique (symbol, time_interval, timestamp)
);

create table if not exists candle_data (
    id bigserial primary key,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    interval varchar(32) not null,
    candle_timestamp timestamp without time zone not null,
    open_price double precision not null,
    high_price double precision not null,
    low_price double precision not null,
    close_price double precision not null,
    volume bigint not null,
    created_at timestamp without time zone default current_timestamp,
    constraint uk_candle_data_asset_interval_timestamp unique (stock_asset_id, interval, candle_timestamp)
);

create table if not exists alert_rules (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    interval varchar(32) not null,
    target_pattern varchar(64) not null,
    pattern_family varchar(32),
    trade_signal varchar(32),
    is_active boolean not null default true,
    created_at timestamp without time zone default current_timestamp
);

create table if not exists alert_events (
    id bigserial primary key,
    alert_rule_id bigint not null references alert_rules(id) on delete cascade,
    pattern varchar(64) not null,
    trade_signal varchar(32) not null,
    signal_candle_timestamp bigint not null,
    signal_strength varchar(32),
    confidence_score integer,
    close_price double precision,
    sent_at timestamp without time zone not null default current_timestamp,
    constraint uk_alert_event_rule_pattern_timestamp unique (alert_rule_id, pattern, signal_candle_timestamp)
);

create unique index if not exists ux_alert_rules_family
    on alert_rules (user_id, stock_asset_id, interval, trade_signal, pattern_family);
create index if not exists ix_candles_symbol_interval_timestamp
    on candles (symbol, time_interval, timestamp desc);
create index if not exists ix_alert_rules_active_interval
    on alert_rules (is_active, interval);
