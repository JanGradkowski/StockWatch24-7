alter table alert_events
    drop constraint if exists ck_alert_event_lifecycle_status;

alter table alert_events
    add constraint ck_alert_event_lifecycle_status
        check (lifecycle_status in (
            'DETECTED',
            'CONFIRMED',
            'UNCONFIRMED',
            'INVALIDATED',
            'EXPIRED'
        ));
