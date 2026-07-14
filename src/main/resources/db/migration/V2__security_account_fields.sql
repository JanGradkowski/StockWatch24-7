alter table users add column if not exists verification_token_hash varchar(64);
alter table users add column if not exists verification_expires_at timestamp without time zone;

-- Accounts created before verification was implemented are grandfathered in.
update users set is_verified = true where verification_token_hash is null;

update alert_rules set pattern_family = 'CANDLESTICK' where pattern_family is null;

do $$
declare
    constraint_record record;
begin
    for constraint_record in
        select rel.relname as table_name, con.conname as constraint_name
        from pg_constraint con
        join pg_class rel on rel.oid = con.conrelid
        join pg_namespace nsp on nsp.oid = rel.relnamespace
        where nsp.nspname = current_schema()
          and rel.relname in ('alert_rules', 'alert_events')
          and con.contype = 'c'
          and (pg_get_constraintdef(con.oid) like '%target_pattern%'
               or pg_get_constraintdef(con.oid) like '%pattern%')
    loop
        execute format('alter table %I drop constraint %I',
                       constraint_record.table_name,
                       constraint_record.constraint_name);
    end loop;

    for constraint_record in
        select rel.relname as table_name, con.conname as constraint_name
        from pg_constraint con
        join pg_class rel on rel.oid = con.conrelid
        join pg_namespace nsp on nsp.oid = rel.relnamespace
        where nsp.nspname = current_schema()
          and rel.relname = 'alert_rules'
          and con.contype = 'u'
          and pg_get_constraintdef(con.oid) = 'UNIQUE (user_id, stock_asset_id, "interval", trade_signal)'
    loop
        execute format('alter table %I drop constraint %I',
                       constraint_record.table_name,
                       constraint_record.constraint_name);
    end loop;
end $$;

create unique index if not exists ux_alert_rules_family
    on alert_rules (user_id, stock_asset_id, interval, trade_signal, pattern_family);
