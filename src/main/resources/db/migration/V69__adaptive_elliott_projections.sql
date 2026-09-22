alter table elliott_projection_sets
    add column available_from_timestamp bigint not null default 0,
    add column version bigint not null default 0;
update elliott_projection_sets p
set available_from_timestamp = greatest(p.source_timestamp, coalesce(e.signal_candle_timestamp, p.source_timestamp))
from alert_events e where e.id = p.alert_event_id;

alter table elliott_projection_scenarios
    add column original_maximum_candles integer not null default 0,
    add column deviation_kind varchar(24),
    add column deviation_streak integer not null default 0,
    add column boundary_streak integer not null default 0,
    add column promotion_streak integer not null default 0,
    add column last_revision_candle_count integer not null default 0,
    add column target_reached_timestamp bigint,
    add column target_history_checked boolean not null default false;
update elliott_projection_scenarios set original_maximum_candles = maximum_candles;
alter table elliott_projection_scenarios drop constraint ck_elliott_projection_scenario_status;
alter table elliott_projection_scenarios add constraint ck_elliott_projection_scenario_status
    check (status in ('ACTIVE', 'DISFAVORED', 'AWAITING_CONFIRMATION', 'UNRESOLVED',
                     'INVALIDATED', 'COMPLETED', 'SUPERSEDED'));

create table elliott_projection_revisions (
    scenario_id bigint not null references elliott_projection_scenarios(id) on delete cascade,
    revision_index integer not null,
    decision_timestamp bigint not null,
    projected_path text not null,
    minimum_candles integer not null,
    maximum_candles integer not null,
    target_zone_low double precision not null,
    target_zone_high double precision not null,
    reason varchar(255) not null,
    primary key (scenario_id, revision_index)
);
insert into elliott_projection_revisions
select s.id, 0, p.available_from_timestamp, s.projected_path, s.minimum_candles,
       s.maximum_candles, s.target_zone_low, s.target_zone_high,
       'Original stored projection, retained before adaptive monitoring.'
from elliott_projection_scenarios s join elliott_projection_sets p on p.id = s.projection_set_id;
