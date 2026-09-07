-- One revision update per changed series and SQL statement, rather than one per candle.
drop trigger candles_revision on candles;
drop function advance_candle_revision();

create function candle_insert_revisions() returns trigger language plpgsql as $$
begin
    insert into candle_revisions
    select symbol, time_interval, 1 from new_candles group by symbol, time_interval order by symbol, time_interval
    on conflict (symbol, time_interval) do update set generation = candle_revisions.generation + 1;
    return null;
end $$;
create function candle_delete_revisions() returns trigger language plpgsql as $$
begin
    insert into candle_revisions
    select symbol, time_interval, 1 from old_candles group by symbol, time_interval order by symbol, time_interval
    on conflict (symbol, time_interval) do update set generation = candle_revisions.generation + 1;
    return null;
end $$;
create function candle_update_revisions() returns trigger language plpgsql as $$
begin
    insert into candle_revisions
    select symbol, time_interval, 1 from (
        select symbol, time_interval from new_candles union select symbol, time_interval from old_candles
    ) changed order by symbol, time_interval
    on conflict (symbol, time_interval) do update set generation = candle_revisions.generation + 1;
    return null;
end $$;
create function candle_truncate_revisions() returns trigger language plpgsql as $$
begin
    update candle_revisions set generation = generation + 1;
    return null;
end $$;
create trigger candles_insert_revision after insert on candles referencing new table as new_candles
    for each statement execute function candle_insert_revisions();
create trigger candles_update_revision after update on candles referencing old table as old_candles new table as new_candles
    for each statement execute function candle_update_revisions();
create trigger candles_delete_revision after delete on candles referencing old table as old_candles
    for each statement execute function candle_delete_revisions();
create trigger candles_truncate_revision after truncate on candles
    for each statement execute function candle_truncate_revisions();
