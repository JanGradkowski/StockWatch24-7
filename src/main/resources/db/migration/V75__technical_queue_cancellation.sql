alter table alert_check_jobs drop constraint ck_alert_check_job_status;
alter table alert_check_jobs add constraint ck_alert_check_job_status
    check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'));

-- Operator cancellation is distinct from a successfully executed schedule.
create table alert_schedule_control (
    id integer primary key check (id = 1),
    cancelled_through timestamptz not null
);
