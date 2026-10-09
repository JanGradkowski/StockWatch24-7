-- attempts remains the monotonically increasing lease ownership fence.
-- Provider deferrals must not consume the independent calculation failure budget.
alter table alert_check_jobs add column calculation_failures integer not null default 0;
