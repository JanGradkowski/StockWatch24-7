create table if not exists alert_schedule_runs (
    interval varchar(32) not null,
    scheduled_for timestamp with time zone not null,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (interval, scheduled_for)
);

alter table alert_check_jobs
    add column if not exists scheduled_for timestamp with time zone;

update alert_check_jobs
set scheduled_for = created_at
where scheduled_for is null;

alter table alert_check_jobs
    alter column scheduled_for set not null;

drop index if exists ux_alert_check_jobs_active;

create unique index if not exists ux_alert_check_jobs_scheduled_run
    on alert_check_jobs (symbol, interval, scheduled_for);

create index if not exists ix_alert_schedule_runs_latest
    on alert_schedule_runs (interval, scheduled_for desc);
