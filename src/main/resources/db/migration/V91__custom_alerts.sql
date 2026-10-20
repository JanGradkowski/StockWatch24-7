-- Custom alerts: a user's own rule (conditions joined with all/any) applied to watchlists. The scheduled
-- checks evaluate it for every stock it covers on completed candles; each time the rule becomes true for a
-- stock, a custom_alert_events row records the trigger with the evidence for every condition.
-- Design: docs/custom-alerts-plan.md.

create table custom_alerts (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    name varchar(80) not null check (length(trim(name)) > 0),
    description varchar(500) not null default '',
    interval varchar(16) not null check (interval in ('DAILY', 'WEEKLY', 'MONTHLY')),
    -- Versioned JSON condition tree; validated on save and before every evaluation.
    definition text not null,
    -- Increases when the rule changes, so states and history know which rule they belong to.
    revision integer not null default 1,
    trigger_mode varchar(16) not null default 'BECOMES_TRUE'
        check (trigger_mode in ('BECOMES_TRUE', 'ONCE', 'EVERY_CANDLE')),
    quiet_candles integer not null default 0 check (quiet_candles between 0 and 60),
    outcome_candles integer not null default 10 check (outcome_candles between 1 and 60),
    email_enabled boolean not null default true,
    active boolean not null default true,
    pinned boolean not null default false,
    -- Set when the alert was made from a chart alert (its indicator or price level became a condition).
    source_chart_alert_id bigint references chart_alerts(id) on delete set null,
    last_triggered_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);
create index ix_custom_alerts_owner on custom_alerts (user_id, id);
create index ix_custom_alerts_active on custom_alerts (interval) where active;

-- Monitoring lives in watchlists: an alert applies to all members of a list, or to chosen members.
create table custom_alert_targets (
    id bigserial primary key,
    alert_id bigint not null references custom_alerts(id) on delete cascade,
    watchlist_id bigint not null references watchlists(id) on delete cascade,
    scope varchar(16) not null check (scope in ('ALL_MEMBERS', 'SELECTED')),
    created_at timestamp with time zone not null default current_timestamp,
    unique (alert_id, watchlist_id)
);
create index ix_custom_alert_targets_watchlist on custom_alert_targets (watchlist_id);

create table custom_alert_target_members (
    target_id bigint not null references custom_alert_targets(id) on delete cascade,
    watchlist_id bigint not null,
    stock_asset_id bigint not null,
    primary key (target_id, stock_asset_id),
    -- Leaving the list also removes the stock from the alert's chosen members.
    foreign key (watchlist_id, stock_asset_id)
        references watchlist_members(watchlist_id, stock_asset_id) on delete cascade
);
create index ix_custom_alert_target_members_asset on custom_alert_target_members (stock_asset_id);

-- "Stop for this stock" on an alert that covers a whole list.
create table custom_alert_exclusions (
    alert_id bigint not null references custom_alerts(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (alert_id, stock_asset_id)
);

-- Every stock an active alert covers, with the lists that cover it.
create view custom_alert_effective_targets as
select t.alert_id, m.stock_asset_id, array_agg(distinct t.watchlist_id order by t.watchlist_id) as watchlist_ids
from custom_alert_targets t
join watchlist_members m on m.watchlist_id = t.watchlist_id
where (t.scope = 'ALL_MEMBERS' or exists (
        select 1 from custom_alert_target_members tm where tm.target_id = t.id and tm.stock_asset_id = m.stock_asset_id))
  and not exists (select 1 from custom_alert_exclusions x where x.alert_id = t.alert_id and x.stock_asset_id = m.stock_asset_id)
  and not exists (select 1 from watchlist_disallowed_signals d where d.watchlist_id = t.watchlist_id and d.signal_type = 'CUSTOM')
group by t.alert_id, m.stock_asset_id;

-- Per alert and stock: the newest evaluated candle and whether the rule held on it.
create table custom_alert_states (
    alert_id bigint not null references custom_alerts(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    revision integer not null,
    -- Null until the first check after the alert was applied sets the baseline; only later candles trigger.
    last_evaluated_timestamp bigint,
    last_result boolean,
    -- OK, SKIPPED (a condition cannot apply to this instrument), INSUFFICIENT_HISTORY or FAILED.
    status varchar(24) not null default 'OK',
    status_detail varchar(300),
    last_triggered_timestamp bigint,
    triggered_once boolean not null default false,
    -- Set when the user asked to hear about stocks that already match: the baseline then records a trigger.
    notify_on_baseline boolean not null default false,
    updated_at timestamp with time zone not null default current_timestamp,
    primary key (alert_id, stock_asset_id)
);
create index ix_custom_alert_states_asset on custom_alert_states (stock_asset_id);

create table custom_alert_events (
    id bigserial primary key,
    alert_id bigint not null references custom_alerts(id) on delete cascade,
    stock_asset_id bigint not null references stock_assets(id) on delete cascade,
    candle_timestamp bigint not null,
    -- TRIGGERED on a new candle, or MATCHED_ON_APPLY when the user asked about stocks already matching.
    kind varchar(20) not null check (kind in ('TRIGGERED', 'MATCHED_ON_APPLY')),
    close_price double precision not null,
    revision integer not null,
    -- The rule as it read at this moment, and the checked value of every condition (JSON).
    rule_text text not null,
    evidence text not null,
    matched_conditions integer not null,
    total_conditions integer not null,
    -- Close-to-close change after 1, 5, 10 and 20 candles (JSON), and at the alert's outcome window.
    forward_returns text,
    outcome_candles integer not null,
    outcome_return double precision,
    outcome_status varchar(16) not null default 'PENDING' check (outcome_status in ('PENDING', 'COMPLETE')),
    created_at timestamp with time zone not null default current_timestamp,
    read_at timestamp with time zone,
    -- NONE, PENDING (waiting to be sent with the other triggers of the same check), QUEUED or SKIPPED.
    email_status varchar(16) not null default 'NONE' check (email_status in ('NONE', 'PENDING', 'QUEUED', 'SKIPPED')),
    email_queued_at timestamp with time zone,
    deleted_at timestamp with time zone,
    constraint uq_custom_alert_event unique (alert_id, stock_asset_id, candle_timestamp)
);
create index ix_custom_alert_events_feed on custom_alert_events (alert_id, created_at desc);
create index ix_custom_alert_events_asset on custom_alert_events (stock_asset_id, alert_id);
create index ix_custom_alert_events_pending on custom_alert_events (stock_asset_id) where outcome_status = 'PENDING';
create index ix_custom_alert_events_unread on custom_alert_events (alert_id) where read_at is null and deleted_at is null;
create index ix_custom_alert_events_email on custom_alert_events (alert_id) where email_status = 'PENDING';

-- Watchlists may forbid custom alerts like any other signal type.
alter table watchlist_disallowed_signals drop constraint if exists watchlist_disallowed_signals_signal_type_check;
alter table watchlist_disallowed_signals add constraint watchlist_disallowed_signals_signal_type_check
    check (signal_type in ('CANDLESTICK','ELLIOTT_WAVE','HARMONIC_FORMATION','OUTLOOK','CONGRESS','INSIDER','CUSTOM'));

-- A trigger is attributed to the lists whose targets cover the stock at that moment (not to mere members),
-- so a watchlist's archive and unread counts show it; renaming or deleting a list keeps the history.
create function capture_custom_alert_origins() returns trigger language plpgsql as $$
begin
    if TG_OP = 'DELETE' then
        delete from watchlist_notification_origins where kind = 'CUSTOM' and notification_id = OLD.id;
        return OLD;
    end if;
    insert into watchlist_notification_origins(user_id, kind, notification_id, watchlist_id, name)
    select a.user_id, 'CUSTOM', NEW.id, w.id, w.name
    from custom_alerts a
    join custom_alert_targets t on t.alert_id = a.id
    join watchlists w on w.id = t.watchlist_id
    join watchlist_members m on m.watchlist_id = w.id and m.stock_asset_id = NEW.stock_asset_id
    where a.id = NEW.alert_id
      and (t.scope = 'ALL_MEMBERS' or exists (
            select 1 from custom_alert_target_members tm where tm.target_id = t.id and tm.stock_asset_id = NEW.stock_asset_id))
      and not exists (select 1 from watchlist_disallowed_signals d where d.watchlist_id = w.id and d.signal_type = 'CUSTOM');
    return NEW;
end $$;
create trigger custom_alert_watchlist_origins after insert or delete on custom_alert_events
for each row execute function capture_custom_alert_origins();
