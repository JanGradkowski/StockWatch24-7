alter table watchlists add column settings_configured boolean not null default false;

create table watchlist_signal_settings (
    watchlist_id bigint not null references watchlists(id) on delete cascade,
    signal_type varchar(32) not null,
    interval varchar(32) not null,
    direction varchar(16) not null,
    watched boolean not null,
    email_enabled boolean not null,
    primary key(watchlist_id, signal_type, interval, direction)
);
create table watchlist_member_signals (
    watchlist_id bigint not null,
    stock_asset_id bigint not null,
    signal_type varchar(32) not null,
    interval varchar(32) not null,
    direction varchar(16) not null,
    primary key(watchlist_id, stock_asset_id, signal_type, interval, direction),
    foreign key(watchlist_id, stock_asset_id) references watchlist_members(watchlist_id, stock_asset_id) on delete cascade
);
create index ix_watchlist_member_signals_asset on watchlist_member_signals(stock_asset_id, watchlist_id);

-- Preserve each instrument's existing follows, including heterogeneous lists.
insert into watchlist_member_signals
select m.watchlist_id,m.stock_asset_id,coalesce(r.pattern_family,'CANDLESTICK'),r.interval,r.trade_signal
from watchlist_members m join watchlists w on w.id=m.watchlist_id
join alert_rules r on r.user_id=w.user_id and r.stock_asset_id=m.stock_asset_id and r.is_active
where r.trade_signal in ('BUY','SELL')
union
select m.watchlist_id,m.stock_asset_id,'OUTLOOK',s.interval,'ANY'
from watchlist_members m join watchlists w on w.id=m.watchlist_id
join technical_outlook_subscriptions s on s.user_id=w.user_id and s.stock_asset_id=m.stock_asset_id and s.is_active
union
select m.watchlist_id,m.stock_asset_id,'CONGRESS','DAILY','ANY'
from watchlist_members m join watchlists w on w.id=m.watchlist_id
join congressional_trade_subscriptions s on s.user_id=w.user_id and s.stock_asset_id=m.stock_asset_id and s.active
union
select m.watchlist_id,m.stock_asset_id,'INSIDER','DAILY','ANY'
from watchlist_members m join watchlists w on w.id=m.watchlist_id
join insider_trade_subscriptions s on s.user_id=w.user_id and s.stock_asset_id=m.stock_asset_id and s.active;

-- Account-wide unfollow actions also clear the list-level requests that supported them.
create function clear_watchlist_signal_request() returns trigger language plpgsql as $$
declare category text; period text; side text;
begin
    if TG_TABLE_NAME='alert_rules' then
        if NEW.is_active or not OLD.is_active then return NEW; end if;
        category:=coalesce(OLD.pattern_family,'CANDLESTICK'); period:=OLD.interval; side:=OLD.trade_signal;
    elsif TG_TABLE_NAME='technical_outlook_subscriptions' then
        if NEW.is_active or not OLD.is_active then return NEW; end if;
        category:='OUTLOOK'; period:=OLD.interval; side:='ANY';
    else
        if NEW.active or not OLD.active then return NEW; end if;
        category:=case when TG_TABLE_NAME='congressional_trade_subscriptions' then 'CONGRESS' else 'INSIDER' end;
        period:='DAILY'; side:='ANY';
    end if;
    delete from watchlist_member_signals s using watchlists w
    where s.watchlist_id=w.id and w.user_id=OLD.user_id and s.stock_asset_id=OLD.stock_asset_id
      and s.signal_type=category and s.interval=period and s.direction=side;
    return NEW;
end $$;
create trigger clear_pattern_watchlist_request after update of is_active on alert_rules
for each row execute function clear_watchlist_signal_request();
create trigger clear_outlook_watchlist_request after update of is_active on technical_outlook_subscriptions
for each row execute function clear_watchlist_signal_request();
create trigger clear_congress_watchlist_request after update of active on congressional_trade_subscriptions
for each row execute function clear_watchlist_signal_request();
create trigger clear_insider_watchlist_request after update of active on insider_trade_subscriptions
for each row execute function clear_watchlist_signal_request();
