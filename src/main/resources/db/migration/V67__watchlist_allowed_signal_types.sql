-- Absence of a restriction means allowed, including every existing watchlist.
create table watchlist_disallowed_signals (
    watchlist_id bigint not null references watchlists(id) on delete cascade,
    signal_type varchar(32) not null check (signal_type in ('CANDLESTICK','ELLIOTT_WAVE','HARMONIC_FORMATION','OUTLOOK','CONGRESS','INSIDER')),
    primary key (watchlist_id,signal_type)
);

-- Membership alone never attributes a notification to a list that forbids its type.
create or replace function capture_watchlist_origins() returns trigger language plpgsql as $$
declare account_id bigint; asset_id bigint; event_kind text; category text; period text; side text;
begin
    event_kind := TG_ARGV[0];
    if TG_OP = 'DELETE' then
        delete from watchlist_notification_origins where kind=event_kind and notification_id=OLD.id;
        return OLD;
    end if;
    if event_kind = 'TECHNICAL' then
        select user_id,stock_asset_id,coalesce(pattern_family,'CANDLESTICK'),interval into account_id,asset_id,category,period from alert_rules where id=NEW.alert_rule_id;
        side:=NEW.trade_signal;
    elsif event_kind = 'OUTLOOK' then
        select user_id,stock_asset_id,interval into account_id,asset_id,period from technical_outlook_subscriptions where id=NEW.subscription_id;
        category:='OUTLOOK'; side:='ANY';
    elsif event_kind = 'CONGRESS' then
        select user_id,stock_asset_id into account_id,asset_id from congressional_trade_subscriptions where id=NEW.subscription_id;
        category:='CONGRESS'; period:='DAILY'; side:='ANY';
    else
        select user_id,stock_asset_id into account_id,asset_id from insider_trade_subscriptions where id=NEW.subscription_id;
        category:='INSIDER'; period:='DAILY'; side:='ANY';
    end if;
    insert into watchlist_notification_origins(user_id,kind,notification_id,watchlist_id,name)
    select account_id,event_kind,NEW.id,w.id,w.name from watchlists w
    join watchlist_members m on m.watchlist_id=w.id and m.stock_asset_id=asset_id
    where w.user_id=account_id and (not w.settings_configured or exists (
        select 1 from watchlist_member_signals s where s.watchlist_id=w.id and s.stock_asset_id=asset_id
        and s.signal_type=category and s.interval=period and s.direction=side))
    and not exists(select 1 from watchlist_disallowed_signals d where d.watchlist_id=w.id and d.signal_type=category);
    return NEW;
end $$;
