-- The channel outbox: one row per alert and channel, written in the same transaction as the alert. Workers
-- claim due rows with a lease and an owner fence, as the email outbox does. The payload is the localized
-- message, encrypted with the context "channel-delivery:<id>", and erased once the row is done.
create table alert_channel_deliveries (
    -- Also the webhook-id and the push notification tag, stable across retries.
    id uuid primary key,
    channel_id bigint not null references alert_channels(id) on delete cascade,
    deduplication_key text not null,
    alert_kind varchar(24) not null,
    group_key varchar(120),
    -- Each source row has its foreign key, so deleting it removes its pending channel rows.
    alert_event_id bigint references alert_events(id) on delete cascade,
    outlook_notification_id bigint references technical_outlook_notifications(id) on delete cascade,
    chart_alert_event_id bigint references chart_alert_events(id) on delete cascade,
    custom_alert_event_id bigint references custom_alert_events(id) on delete cascade,
    insider_delivery_id bigint references insider_trade_deliveries(id) on delete cascade,
    congressional_delivery_id bigint references congressional_trade_deliveries(id) on delete cascade,
    ciphertext text,
    iv text,
    created_at timestamptz not null default current_timestamp,
    available_at timestamptz not null default current_timestamp,
    expires_at timestamptz not null,
    -- Waiting for a summary of the channel's alerts (delivery_mode = SUMMARY).
    held boolean not null default false,
    owner uuid,
    lease_until timestamptz,
    attempts integer not null default 0,
    -- A long message goes out in parts; each sent part is committed, so a crash resumes at the next one.
    parts_sent smallint not null default 0,
    -- Telegram's message_id, so a follow-up replies to the alert it is about.
    provider_message_id varchar(64),
    delivered_at timestamptz,
    expired_at timestamptz,
    outcome varchar(24),
    constraint ux_alert_channel_delivery unique (channel_id, deduplication_key),
    constraint ck_alert_channel_delivery_source check (num_nonnulls(alert_event_id, outlook_notification_id,
        chart_alert_event_id, custom_alert_event_id, insider_delivery_id, congressional_delivery_id) <= 1),
    constraint ck_alert_channel_delivery_outcome check (outcome is null or outcome in ('DELIVERED','EXPIRED',
        'REJECTED','FAILED','CHANNEL_GONE','PAUSED','FILTERED'))
);
create index alert_channel_deliveries_pending on alert_channel_deliveries(channel_id, available_at)
    where delivered_at is null and expired_at is null;
create index alert_channel_deliveries_group on alert_channel_deliveries(channel_id, group_key)
    where delivered_at is null and expired_at is null and group_key is not null;
create index alert_channel_deliveries_event on alert_channel_deliveries(alert_event_id)
    where alert_event_id is not null;
create index alert_channel_deliveries_expiry on alert_channel_deliveries(expires_at)
    where delivered_at is null and expired_at is null;
create index alert_channel_deliveries_created on alert_channel_deliveries(created_at);
-- The foreign keys above cascade; these keep the deletes of their parents cheap.
create index alert_channel_deliveries_outlook on alert_channel_deliveries(outlook_notification_id)
    where outlook_notification_id is not null;
create index alert_channel_deliveries_chart on alert_channel_deliveries(chart_alert_event_id)
    where chart_alert_event_id is not null;
create index alert_channel_deliveries_custom on alert_channel_deliveries(custom_alert_event_id)
    where custom_alert_event_id is not null;
create index alert_channel_deliveries_insider on alert_channel_deliveries(insider_delivery_id)
    where insider_delivery_id is not null;
create index alert_channel_deliveries_congress on alert_channel_deliveries(congressional_delivery_id)
    where congressional_delivery_id is not null;

-- A provider-wide block shared by all instances: Telegram flood control, a Discord global 429, a push
-- service outage, our own misconfiguration.
create table alert_channel_provider_state (
    scope varchar(80) primary key,
    blocked_until timestamptz not null,
    reason varchar(40),
    updated_at timestamptz not null default current_timestamp
);
