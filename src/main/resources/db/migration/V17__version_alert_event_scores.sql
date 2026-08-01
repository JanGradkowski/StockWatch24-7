alter table alert_events
    add column if not exists score_version varchar(32) not null default 'LEGACY_UNVERSIONED';
