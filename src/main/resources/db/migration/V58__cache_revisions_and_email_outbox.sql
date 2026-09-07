-- Revisions change in the same transaction as candle writes, including corrections and deletes.
create table candle_revisions (
    symbol varchar(64) not null,
    time_interval varchar(16) not null,
    generation bigint not null,
    primary key (symbol, time_interval)
);
insert into candle_revisions select symbol, time_interval, 1 from candles group by symbol, time_interval;

create function advance_candle_revision() returns trigger language plpgsql as $$
begin
    if TG_OP <> 'DELETE' then
        insert into candle_revisions values (NEW.symbol, NEW.time_interval, 1)
        on conflict (symbol, time_interval) do update set generation = candle_revisions.generation + 1;
    end if;
    if TG_OP = 'DELETE' or (TG_OP = 'UPDATE' and (OLD.symbol, OLD.time_interval) is distinct from (NEW.symbol, NEW.time_interval)) then
        insert into candle_revisions values (OLD.symbol, OLD.time_interval, 1)
        on conflict (symbol, time_interval) do update set generation = candle_revisions.generation + 1;
    end if;
    return null;
end $$;
create trigger candles_revision after insert or update or delete on candles
    for each row execute function advance_candle_revision();
alter table historical_signal_cache add column candle_generation bigint not null default -1;
create table historical_signal_cache_leases (
    cache_key text primary key,
    owner uuid not null,
    lease_until timestamptz not null
);

create table email_outbox (
    id uuid primary key,
    deduplication_key text unique,
    ciphertext text,
    iv text,
    alert_event_id bigint references alert_events(id) on delete cascade,
    created_at timestamptz not null default current_timestamp,
    expires_at timestamptz not null,
    available_at timestamptz not null default current_timestamp,
    lease_until timestamptz,
    owner uuid,
    attempts integer not null default 0,
    delivered_at timestamptz,
    expired_at timestamptz
);
create index email_outbox_pending on email_outbox(available_at)
    where delivered_at is null and expired_at is null;

create index if not exists users_email_lower_idx on users(lower(email));
