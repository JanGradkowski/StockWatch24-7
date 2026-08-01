create table insider_trades (
    id bigserial primary key,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    provider varchar(32) not null,
    provider_fingerprint varchar(64) not null,
    ticker_symbol varchar(20) not null,
    insider_name varchar(255) not null,
    owner_role varchar(500),
    transaction_type varchar(32) not null,
    transaction_code varchar(50) not null,
    transaction_date date not null,
    filing_date date not null,
    shares numeric(24, 6),
    transaction_price numeric(24, 6),
    securities_owned numeric(24, 6),
    security_name varchar(255),
    source_url varchar(1000),
    first_seen_at timestamp with time zone not null default current_timestamp,
    last_seen_at timestamp with time zone not null default current_timestamp,
    constraint uk_insider_trade_provider_fingerprint
        unique (provider, provider_fingerprint)
);

create index ix_insider_trades_asset_transaction_date
    on insider_trades (stock_asset_id, transaction_date desc, id desc);

create index ix_insider_trades_filing_date
    on insider_trades (filing_date desc, id desc);

create table insider_trade_subscriptions (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    active boolean not null default true,
    activated_at timestamp with time zone not null default current_timestamp,
    baseline_completed_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint uk_insider_subscription_user_asset
        unique (user_id, stock_asset_id)
);

create index ix_insider_subscriptions_active_asset
    on insider_trade_subscriptions (active, stock_asset_id);

create table insider_trade_deliveries (
    id bigserial primary key,
    subscription_id bigint not null references insider_trade_subscriptions(id) on delete cascade,
    trade_id bigint not null references insider_trades(id) on delete cascade,
    status varchar(32) not null default 'PENDING',
    sent_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint uk_insider_delivery_subscription_trade
        unique (subscription_id, trade_id)
);

create index ix_insider_deliveries_subscription_created
    on insider_trade_deliveries (subscription_id, created_at desc, id desc);

create table insider_activity_refresh_state (
    stock_asset_id bigint primary key references stock_assets(id) on delete cascade,
    last_success_at timestamp with time zone,
    last_attempt_at timestamp with time zone,
    last_error text
);
