create table if not exists technical_outlook_subscriptions (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    interval varchar(32) not null,
    is_active boolean not null default true,
    last_candle_timestamp bigint,
    baseline_snapshot text,
    profile_fingerprint varchar(64),
    created_at timestamp without time zone not null default current_timestamp,
    updated_at timestamp without time zone not null default current_timestamp,
    constraint ux_technical_outlook_subscription unique (user_id, stock_asset_id, interval),
    constraint ck_technical_outlook_subscription_interval
        check (interval in ('DAILY', 'WEEKLY', 'MONTHLY'))
);

create index if not exists ix_technical_outlook_subscriptions_schedule
    on technical_outlook_subscriptions (interval, stock_asset_id)
    where is_active = true;

create table if not exists technical_outlook_notifications (
    id bigserial primary key,
    subscription_id bigint not null references technical_outlook_subscriptions(id) on delete cascade,
    previous_classification varchar(64) not null,
    current_classification varchar(64) not null,
    previous_score double precision not null,
    current_score double precision not null,
    previous_candle_timestamp bigint not null,
    current_candle_timestamp bigint not null,
    previous_snapshot text not null,
    current_snapshot text not null,
    change_report text not null,
    created_at timestamp without time zone not null default current_timestamp,
    email_sent_at timestamp without time zone,
    read_at timestamp without time zone,
    constraint ux_technical_outlook_notification
        unique (subscription_id, current_candle_timestamp)
);

create index if not exists ix_technical_outlook_notifications_latest_unread
    on technical_outlook_notifications (created_at desc, id desc)
    where read_at is null;

