alter table anchored_volume_profile_refresh_state
    add column snapshot_interval varchar(16);

update anchored_volume_profile_refresh_state
set snapshot_interval = chart_interval;

alter table anchored_volume_profile_refresh_state
    alter column snapshot_interval set not null;
