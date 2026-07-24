create table congressional_trades (
    id bigserial primary key,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    provider varchar(32) not null,
    provider_fingerprint varchar(64) not null,
    member_name varchar(255) not null,
    chamber varchar(32) not null,
    ticker_symbol varchar(20) not null,
    asset_name varchar(500),
    transaction_type varchar(32) not null,
    amount_range varchar(100) not null,
    transaction_date date not null,
    disclosure_date date not null,
    source_url varchar(1000),
    first_seen_at timestamp with time zone not null default current_timestamp,
    last_seen_at timestamp with time zone not null default current_timestamp,
    constraint uk_congressional_trade_provider_fingerprint
        unique (provider, provider_fingerprint)
);

create index ix_congressional_trades_asset_transaction_date
    on congressional_trades (stock_asset_id, transaction_date desc, id desc);

create index ix_congressional_trades_disclosure_date
    on congressional_trades (disclosure_date desc, id desc);

create table congressional_trade_subscriptions (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    active boolean not null default true,
    activated_at timestamp with time zone not null default current_timestamp,
    baseline_completed_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint uk_congressional_subscription_user_asset
        unique (user_id, stock_asset_id)
);

create index ix_congressional_subscriptions_active_asset
    on congressional_trade_subscriptions (active, stock_asset_id);

create table congressional_trade_cache_state (
    stock_asset_id bigint primary key references stock_assets(id) on delete cascade,
    coverage_start date,
    coverage_end date,
    last_success_at timestamp with time zone,
    last_attempt_at timestamp with time zone,
    lease_owner varchar(100),
    lease_until timestamp with time zone,
    last_error text
);

create table congressional_trade_poll_state (
    provider varchar(32) primary key,
    last_success_at timestamp with time zone,
    last_attempt_at timestamp with time zone,
    lease_owner varchar(100),
    lease_until timestamp with time zone,
    last_error text
);

create table congressional_trade_deliveries (
    id bigserial primary key,
    subscription_id bigint not null references congressional_trade_subscriptions(id) on delete cascade,
    trade_id bigint not null references congressional_trades(id) on delete cascade,
    status varchar(32) not null default 'PENDING',
    attempts integer not null default 0,
    available_at timestamp with time zone not null default current_timestamp,
    lease_owner varchar(100),
    lease_until timestamp with time zone,
    sent_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint uk_congressional_delivery_subscription_trade
        unique (subscription_id, trade_id)
);

create index ix_congressional_deliveries_work
    on congressional_trade_deliveries (status, available_at, lease_until);

create index ix_congressional_deliveries_subscription_created
    on congressional_trade_deliveries (subscription_id, created_at desc, id desc);

create table congressional_provider_daily_usage (
    provider varchar(32) not null,
    usage_date date not null,
    request_count integer not null default 0,
    updated_at timestamp with time zone not null default current_timestamp,
    primary key (provider, usage_date)
);
