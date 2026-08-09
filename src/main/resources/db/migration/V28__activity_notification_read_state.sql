alter table congressional_trade_deliveries
    add column if not exists read_at timestamp with time zone;

alter table insider_trade_deliveries
    add column if not exists read_at timestamp with time zone;

create index if not exists ix_congressional_deliveries_unread_subscription
    on congressional_trade_deliveries (subscription_id, created_at desc, id desc)
    where read_at is null;

create index if not exists ix_insider_deliveries_unread_subscription
    on insider_trade_deliveries (subscription_id, created_at desc, id desc)
    where read_at is null;
