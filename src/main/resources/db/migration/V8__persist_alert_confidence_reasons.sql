alter table alert_events
    add column if not exists confidence_reasons text;
