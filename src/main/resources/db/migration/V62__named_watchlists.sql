create table watchlists (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    name varchar(100) not null check (length(trim(name)) > 0),
    description varchar(500) not null default '',
    pinned boolean not null default false,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp
);
create index ix_watchlists_owner on watchlists(user_id, id);
create table watchlist_members (
    watchlist_id bigint not null references watchlists(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    added_at timestamptz not null default current_timestamp,
    primary key (watchlist_id, stock_asset_id)
);
create index ix_watchlist_members_asset on watchlist_members(stock_asset_id, watchlist_id);

-- Only existing followers receive a migrated list. New and empty accounts stay empty.
create temporary table legacy_watchlist_members on commit drop as
select user_id, stock_asset_id from alert_rules where is_active
union select user_id, stock_asset_id from technical_outlook_subscriptions where is_active
union select user_id, stock_asset_id from congressional_trade_subscriptions where active
union select user_id, stock_asset_id from insider_trade_subscriptions where active;
insert into watchlists(user_id, name)
select distinct user_id, 'My watchlist' from legacy_watchlist_members;
insert into watchlist_members(watchlist_id, stock_asset_id)
select w.id, m.stock_asset_id from legacy_watchlist_members m join watchlists w on w.user_id=m.user_id;

-- Capture names and membership at delivery creation, independently of later list edits.
create table watchlist_notification_origins (
    user_id bigint not null references users(id) on delete cascade,
    kind varchar(20) not null,
    notification_id bigint not null,
    watchlist_id bigint references watchlists(id) on delete set null,
    name varchar(100) not null
);
create index ix_watchlist_notification_origins on watchlist_notification_origins(user_id, kind, notification_id);
create function capture_watchlist_origins() returns trigger language plpgsql as $$
declare account_id bigint; asset_id bigint; event_kind text;
begin
    event_kind := TG_ARGV[0];
    if TG_OP = 'DELETE' then
        delete from watchlist_notification_origins where kind=event_kind and notification_id=OLD.id;
        return OLD;
    end if;
    if event_kind = 'TECHNICAL' then
        select user_id, stock_asset_id into account_id, asset_id from alert_rules where id=NEW.alert_rule_id;
    elsif event_kind = 'OUTLOOK' then
        select user_id, stock_asset_id into account_id, asset_id from technical_outlook_subscriptions where id=NEW.subscription_id;
    elsif event_kind = 'CONGRESS' then
        select user_id, stock_asset_id into account_id, asset_id from congressional_trade_subscriptions where id=NEW.subscription_id;
    else
        select user_id, stock_asset_id into account_id, asset_id from insider_trade_subscriptions where id=NEW.subscription_id;
    end if;
    insert into watchlist_notification_origins(user_id, kind, notification_id, watchlist_id, name)
    select account_id, event_kind, NEW.id, w.id, w.name from watchlists w
    join watchlist_members m on m.watchlist_id=w.id
    where w.user_id=account_id and m.stock_asset_id=asset_id;
    return NEW;
end $$;
create trigger alert_watchlist_origins after insert or delete on alert_events
for each row execute function capture_watchlist_origins('TECHNICAL');
create trigger outlook_watchlist_origins after insert or delete on technical_outlook_notifications
for each row execute function capture_watchlist_origins('OUTLOOK');
create trigger congress_watchlist_origins after insert or delete on congressional_trade_deliveries
for each row execute function capture_watchlist_origins('CONGRESS');
create trigger insider_watchlist_origins after insert or delete on insider_trade_deliveries
for each row execute function capture_watchlist_origins('INSIDER');

-- A reusable account/instrument projection, including instruments with activity-only follows.
create view watchlist_monitoring as
select user_id, stock_asset_id from alert_rules where is_active
union select user_id, stock_asset_id from technical_outlook_subscriptions where is_active
union select user_id, stock_asset_id from congressional_trade_subscriptions where active
union select user_id, stock_asset_id from insider_trade_subscriptions where active;
